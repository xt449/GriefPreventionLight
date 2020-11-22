/*
    GriefPrevention Server Plugin for Minecraft
    Copyright (C) 2011 Ryan Hamshire

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.github.xt449.griefpreventionlight;

import com.github.xt449.griefpreventionlight.events.ClaimInspectionEvent;
import com.github.xt449.griefpreventionlight.events.VisualizationEvent;
import org.bukkit.*;
import org.bukkit.World.Environment;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.command.Command;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.player.PlayerLoginEvent.Result;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.event.raid.RaidTriggerEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.BlockIterator;
import org.bukkit.util.RayTraceResult;

import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

class PlayerEventHandler implements Listener {
	private final DataStore dataStore;
	private final GriefPreventionLight instance;

	//list of temporarily banned ip's
	private final ArrayList<IpBanInfo> tempBannedIps = new ArrayList<>();

	//number of milliseconds in a day
	private final long MILLISECONDS_IN_DAY = 1000 * 60 * 60 * 24;

	//timestamps of login and logout notifications in the last minute
	private final ArrayList<Long> recentLoginLogoutNotifications = new ArrayList<>();

	//regex pattern for the "how do i claim land?" scanner
	private Pattern howToClaimPattern = null;

	//matcher for banned words
	private final WordFinder bannedWordFinder;

	//spam tracker
	SpamDetector spamDetector = new SpamDetector();

	Set<Material> spawn_eggs = new HashSet<>();
	Set<Material> dyes = new HashSet<>();

	{
		for(Material material : Material.values()) {
			if(material.name().endsWith("_SPAWN_EGG"))
				spawn_eggs.add(material);
			else if(material.name().endsWith("_DYE"))
				dyes.add(material);
		}
	}

	//typical constructor, yawn
	PlayerEventHandler(DataStore dataStore, GriefPreventionLight plugin) {
		this.dataStore = dataStore;
		this.instance = plugin;
		bannedWordFinder = new WordFinder(instance.dataStore.loadBannedWords());
	}

	//when a player chats, monitor for spam
	@EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
	synchronized void onPlayerChat(AsyncPlayerChatEvent event) {
		Player player = event.getPlayer();
		if(!player.isOnline()) {
			event.setCancelled(true);
			return;
		}

		String message = event.getMessage();

		boolean muted = this.handlePlayerChat(player, message, event);
		Set<Player> recipients = event.getRecipients();

		//muted messages go out to only the sender
		if(muted) {
			recipients.clear();
			recipients.add(player);
		}

		//soft muted messages go out to all soft muted players
		else if(this.dataStore.isSoftMuted(player.getUniqueId())) {
			String notificationMessage = "(Muted " + player.getName() + "): " + message;
			Set<Player> recipientsToKeep = new HashSet<>();
			for(Player recipient : recipients) {
				if(this.dataStore.isSoftMuted(recipient.getUniqueId())) {
					recipientsToKeep.add(recipient);
				} else if(recipient.hasPermission("griefprevention.eavesdrop")) {
					recipient.sendMessage(ChatColor.GRAY + notificationMessage);
				}
			}
			recipients.clear();
			recipients.addAll(recipientsToKeep);

			GriefPreventionLight.AddLogEntry(notificationMessage, CustomLogEntryTypes.MutedChat, false);
		}

		//troll and excessive profanity filter
		else if(!player.hasPermission("griefprevention.spam") && this.bannedWordFinder.hasMatch(message)) {
			//allow admins to see the soft-muted text
			String notificationMessage = "(Muted " + player.getName() + "): " + message;
			for(Player recipient : recipients) {
				if(recipient.hasPermission("griefprevention.eavesdrop")) {
					recipient.sendMessage(ChatColor.GRAY + notificationMessage);
				}
			}

			//limit recipients to sender
			recipients.clear();
			recipients.add(player);

			//if player not new warn for the first infraction per play session.
			if(!GriefPreventionLight.isNewToServer(player)) {
				PlayerData playerData = instance.dataStore.getPlayerData(player.getUniqueId());
				if(!playerData.profanityWarned) {
					playerData.profanityWarned = true;
					GriefPreventionLight.sendMessage(player, TextMode.Err, Messages.NoProfanity);
					event.setCancelled(true);
				}
			}

			//otherwise assume chat troll and mute all chat from this sender until an admin says otherwise
			else if(instance.config_trollFilterEnabled) {
				GriefPreventionLight.AddLogEntry("Auto-muted new player " + player.getName() + " for profanity shortly after join.  Use /SoftMute to undo.", CustomLogEntryTypes.AdminActivity);
				GriefPreventionLight.AddLogEntry(notificationMessage, CustomLogEntryTypes.MutedChat, false);
				instance.dataStore.toggleSoftMute(player.getUniqueId());
			}
		}

		//remaining messages
		else {
			//enter in abridged chat logs
			makeSocialLogEntry(player.getName(), message);

			//based on ignore lists, remove some of the audience
			if(!player.hasPermission("griefprevention.notignorable")) {
				Set<Player> recipientsToRemove = new HashSet<>();
				PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
				for(Player recipient : recipients) {
					if(!recipient.hasPermission("griefprevention.notignorable")) {
						if(playerData.ignoredPlayers.containsKey(recipient.getUniqueId())) {
							recipientsToRemove.add(recipient);
						} else {
							PlayerData targetPlayerData = this.dataStore.getPlayerData(recipient.getUniqueId());
							if(targetPlayerData.ignoredPlayers.containsKey(player.getUniqueId())) {
								recipientsToRemove.add(recipient);
							}
						}
					}
				}

				recipients.removeAll(recipientsToRemove);
			}
		}
	}

	//returns true if the message should be muted, true if it should be sent
	private boolean handlePlayerChat(Player player, String message, PlayerEvent event) {
		//FEATURE: automatically educate players about claiming land
		//watching for message format how*claim*, and will send a link to the basics video
		if(this.howToClaimPattern == null) {
			this.howToClaimPattern = Pattern.compile(this.dataStore.getMessage(Messages.HowToClaimRegex), Pattern.CASE_INSENSITIVE);
		}

		//FEATURE: automatically educate players about the /trapped command
		//check for "trapped" or "stuck" to educate players about the /trapped command
		String trappedwords = this.dataStore.getMessage(
				Messages.TrappedChatKeyword
		);
		if(!trappedwords.isEmpty()) {
			String[] checkWords = trappedwords.split(";");

			for(String checkWord : checkWords) {
				if(!message.contains("/trapped")
						&& message.contains(checkWord)) {
					GriefPreventionLight.sendMessage(
							player,
							TextMode.Info,
							Messages.TrappedInstructions,
							10L
					);
					break;
				}
			}
		}

		//FEATURE: monitor for chat and command spam

		if(!instance.config_spam_enabled) return false;

		//if the player has permission to spam, don't bother even examining the message
		if(player.hasPermission("griefprevention.spam")) return false;

		//examine recent messages to detect spam
		SpamAnalysisResult result = this.spamDetector.AnalyzeMessage(player.getUniqueId(), message, System.currentTimeMillis());

		//apply any needed changes to message (like lowercasing all-caps)
		if(event instanceof AsyncPlayerChatEvent) {
			((AsyncPlayerChatEvent) event).setMessage(result.finalMessage);
		}

		//don't allow new players to chat after logging in until they move
		PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
		if(playerData.noChatLocation != null) {
			Location currentLocation = player.getLocation();
			if(currentLocation.getBlockX() == playerData.noChatLocation.getBlockX() &&
					currentLocation.getBlockZ() == playerData.noChatLocation.getBlockZ()) {
				GriefPreventionLight.sendMessage(player, TextMode.Err, Messages.NoChatUntilMove, 10L);
				result.muteReason = "pre-movement chat";
			} else {
				playerData.noChatLocation = null;
			}
		}

		//filter IP addresses
		if(result.muteReason == null) {
			if(instance.containsBlockedIP(message)) {
				//block message
				result.muteReason = "IP address";
			}
		}

		//take action based on spam detector results
		if(result.shouldBanChatter) {
			if(instance.config_spam_banOffenders) {
				//log entry
				GriefPreventionLight.AddLogEntry("Banning " + player.getName() + " for spam.", CustomLogEntryTypes.AdminActivity);

				//kick and ban
				PlayerKickBanTask task = new PlayerKickBanTask(player, instance.config_spam_banMessage, "GriefPrevention Anti-Spam", true);
				instance.getServer().getScheduler().scheduleSyncDelayedTask(instance, task, 1L);
			} else {
				//log entry
				GriefPreventionLight.AddLogEntry("Kicking " + player.getName() + " for spam.", CustomLogEntryTypes.AdminActivity);

				//just kick
				PlayerKickBanTask task = new PlayerKickBanTask(player, "", "GriefPrevention Anti-Spam", false);
				instance.getServer().getScheduler().scheduleSyncDelayedTask(instance, task, 1L);
			}
		} else if(result.shouldWarnChatter) {
			//warn and log
			GriefPreventionLight.sendMessage(player, TextMode.Warn, instance.config_spam_warningMessage, 10L);
			GriefPreventionLight.AddLogEntry("Warned " + player.getName() + " about spam penalties.", CustomLogEntryTypes.Debug, true);
		}

		if(result.muteReason != null) {
			//mute and log
			GriefPreventionLight.AddLogEntry("Muted " + result.muteReason + ".");
			GriefPreventionLight.AddLogEntry("Muted " + player.getName() + " " + result.muteReason + ":" + message, CustomLogEntryTypes.Debug, true);

			return true;
		}

		return false;
	}

	//when a player uses a slash command...
	@EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
	synchronized void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event) {
		String message = event.getMessage();
		String[] args = message.split(" ");

		String command = args[0].toLowerCase();

		CommandCategory category = this.getCommandCategory(command);

		Player player = event.getPlayer();
		PlayerData playerData = null;

		//if a whisper
		if(category == CommandCategory.Whisper && args.length > 1) {
			//determine target player, might be NULL

			Player targetPlayer = instance.getServer().getPlayer(args[1]);

			//softmute feature
			if(this.dataStore.isSoftMuted(player.getUniqueId()) && targetPlayer != null && !this.dataStore.isSoftMuted(targetPlayer.getUniqueId())) {
				event.setCancelled(true);
				return;
			}

			//if eavesdrop enabled and sender doesn't have the eavesdrop immunity permission, eavesdrop
			if(instance.config_whisperNotifications && !player.hasPermission("griefprevention.eavesdropimmune")) {
				//except for when the recipient has eavesdrop immunity
				if(targetPlayer == null || !targetPlayer.hasPermission("griefprevention.eavesdropimmune")) {
					StringBuilder logMessageBuilder = new StringBuilder();
					logMessageBuilder.append("[[").append(event.getPlayer().getName()).append("]] ");

					for(int i = 1; i < args.length; i++) {
						logMessageBuilder.append(args[i]).append(" ");
					}

					String logMessage = logMessageBuilder.toString();

					@SuppressWarnings("unchecked")
					Collection<Player> players = (Collection<Player>) instance.getServer().getOnlinePlayers();
					for(Player onlinePlayer : players) {
						if(onlinePlayer.hasPermission("griefprevention.eavesdrop") && !onlinePlayer.equals(targetPlayer) && !onlinePlayer.equals(player)) {
							onlinePlayer.sendMessage(ChatColor.GRAY + logMessage);
						}
					}
				}
			}

			//ignore feature
			if(targetPlayer != null && targetPlayer.isOnline()) {
				//if either is ignoring the other, cancel this command
				playerData = this.dataStore.getPlayerData(player.getUniqueId());
				if(playerData.ignoredPlayers.containsKey(targetPlayer.getUniqueId()) && !targetPlayer.hasPermission("griefprevention.notignorable")) {
					event.setCancelled(true);
					GriefPreventionLight.sendMessage(player, TextMode.Err, Messages.IsIgnoringYou);
					return;
				}

				PlayerData targetPlayerData = this.dataStore.getPlayerData(targetPlayer.getUniqueId());
				if(targetPlayerData.ignoredPlayers.containsKey(player.getUniqueId()) && !player.hasPermission("griefprevention.notignorable")) {
					event.setCancelled(true);
					GriefPreventionLight.sendMessage(player, TextMode.Err, Messages.IsIgnoringYou);
					return;
				}
			}
		}

		//if in pvp, block any pvp-banned slash commands
		if(playerData == null) playerData = this.dataStore.getPlayerData(event.getPlayer().getUniqueId());

		//soft mute for chat slash commands
		if(category == CommandCategory.Chat && this.dataStore.isSoftMuted(player.getUniqueId())) {
			event.setCancelled(true);
			return;
		}

		//if the slash command used is in the list of monitored commands, treat it like a chat message (see above)
		boolean isMonitoredCommand = (category == CommandCategory.Chat || category == CommandCategory.Whisper);
		if(isMonitoredCommand) {
			//if anti spam enabled, check for spam
			if(instance.config_spam_enabled) {
				event.setCancelled(this.handlePlayerChat(event.getPlayer(), event.getMessage(), event));
			}

			if(!player.hasPermission("griefprevention.spam") && this.bannedWordFinder.hasMatch(message)) {
				event.setCancelled(true);
			}

			//unless cancelled, log in abridged logs
			StringBuilder builder = new StringBuilder();
			for(String arg : args) {
				builder.append(arg).append(' ');
			}

			makeSocialLogEntry(event.getPlayer().getName(), builder.toString());
		}

		//if requires access trust, check for permission
		isMonitoredCommand = false;
		String lowerCaseMessage = message.toLowerCase();
		for(String monitoredCommand : instance.config_claims_commandsRequiringAccessTrust) {
			if(lowerCaseMessage.startsWith(monitoredCommand)) {
				isMonitoredCommand = true;
				break;
			}
		}

		if(isMonitoredCommand) {
			Claim claim = this.dataStore.getClaimAt(player.getLocation(), playerData.lastClaim);
			if(claim != null) {
				playerData.lastClaim = claim;
				String reason = claim.allowAccess(player);
				if(reason != null) {
					GriefPreventionLight.sendMessage(player, TextMode.Err, reason);
					event.setCancelled(true);
				}
			}
		}
	}

	private final ConcurrentHashMap<String, CommandCategory> commandCategoryMap = new ConcurrentHashMap<>();

	private CommandCategory getCommandCategory(String commandName) {
		if(commandName.startsWith("/")) commandName = commandName.substring(1);

		//if we've seen this command or alias before, return the category determined previously
		CommandCategory category = this.commandCategoryMap.get(commandName);
		if(category != null) return category;

		//otherwise build a list of all the aliases of this command across all installed plugins
		HashSet<String> aliases = new HashSet<>();
		aliases.add(commandName);
		aliases.add("minecraft:" + commandName);
		for(Plugin plugin : Bukkit.getServer().getPluginManager().getPlugins()) {
			if(!(plugin instanceof JavaPlugin))
				continue;
			JavaPlugin javaPlugin = (JavaPlugin) plugin;
			Command command = javaPlugin.getCommand(commandName);
			if(command != null) {
				aliases.add(command.getName().toLowerCase());
				aliases.add(plugin.getName().toLowerCase() + ":" + command.getName().toLowerCase());
				for(String alias : command.getAliases()) {
					aliases.add(alias.toLowerCase());
					aliases.add(plugin.getName().toLowerCase() + ":" + alias.toLowerCase());
				}
			}
		}

		//also consider vanilla commands
		Command command = Bukkit.getServer().getPluginCommand(commandName);
		if(command != null) {
			aliases.add(command.getName().toLowerCase());
			aliases.add("minecraft:" + command.getName().toLowerCase());
			for(String alias : command.getAliases()) {
				aliases.add(alias.toLowerCase());
				aliases.add("minecraft:" + alias.toLowerCase());
			}
		}

		//if any of those aliases are in the chat list or whisper list, then we know the category for that command
		category = CommandCategory.None;
		for(String alias : aliases) {
			if(instance.config_eavesdrop_whisperCommands.contains("/" + alias)) {
				category = CommandCategory.Whisper;
			} else if(instance.config_spam_monitorSlashCommands.contains("/" + alias)) {
				category = CommandCategory.Chat;
			}

			//remember the categories for later
			this.commandCategoryMap.put(alias.toLowerCase(), category);
		}

		return category;
	}

	static int longestNameLength = 10;

	static void makeSocialLogEntry(String name, String message) {
		StringBuilder entryBuilder = new StringBuilder(name);
		for(int i = name.length(); i < longestNameLength; i++) {
			entryBuilder.append(' ');
		}
		entryBuilder.append(": ").append(message);

		longestNameLength = Math.max(longestNameLength, name.length());
		//TODO: cleanup static
		GriefPreventionLight.AddLogEntry(entryBuilder.toString(), CustomLogEntryTypes.SocialActivity, true);
	}

	private final ConcurrentHashMap<UUID, Date> lastLoginThisServerSessionMap = new ConcurrentHashMap<>();

	//when a player attempts to join the server...
	@EventHandler(priority = EventPriority.HIGHEST)
	void onPlayerLogin(PlayerLoginEvent event) {
		Player player = event.getPlayer();

		//all this is anti-spam code
		if(instance.config_spam_enabled) {
			//FEATURE: login cooldown to prevent login/logout spam with custom clients
			long now = Calendar.getInstance().getTimeInMillis();

			//if allowed to join and login cooldown enabled
			if(instance.config_spam_loginCooldownSeconds > 0 && event.getResult() == Result.ALLOWED && !player.hasPermission("griefprevention.spam")) {
				//determine how long since last login and cooldown remaining
				Date lastLoginThisSession = lastLoginThisServerSessionMap.get(player.getUniqueId());
				if(lastLoginThisSession != null) {
					long millisecondsSinceLastLogin = now - lastLoginThisSession.getTime();
					long secondsSinceLastLogin = millisecondsSinceLastLogin / 1000;
					long cooldownRemaining = instance.config_spam_loginCooldownSeconds - secondsSinceLastLogin;

					//if cooldown remaining
					if(cooldownRemaining > 0) {
						//DAS BOOT!
						event.setResult(Result.KICK_OTHER);
						event.setKickMessage("You must wait " + cooldownRemaining + " seconds before logging-in again.");
						event.disallow(event.getResult(), event.getKickMessage());
						return;
					}
				}
			}

			//if logging-in account is banned, remember IP address for later
			if(instance.config_smartBan && event.getResult() == Result.KICK_BANNED) {
				this.tempBannedIps.add(new IpBanInfo(event.getAddress(), now + this.MILLISECONDS_IN_DAY, player.getName()));
			}
		}

		//remember the player's ip address
		PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
		playerData.ipAddress = event.getAddress();
	}

	//when a player successfully joins the server...

	@EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
	void onPlayerJoin(PlayerJoinEvent event) {
		Player player = event.getPlayer();
		UUID playerID = player.getUniqueId();

		//note login time
		Date nowDate = new Date();
		long now = nowDate.getTime();
		PlayerData playerData = this.dataStore.getPlayerData(playerID);
		playerData.lastSpawn = now;
		this.lastLoginThisServerSessionMap.put(playerID, nowDate);

		//if newish, prevent chat until he's moved a bit to prove he's not a bot
		if(GriefPreventionLight.isNewToServer(player)) {
			playerData.noChatLocation = player.getLocation();
		}

		//if player has never played on the server before...
		if(!player.hasPlayedBefore()) {
			//may need pvp protection
			instance.checkPvpProtectionNeeded(player);

			//if in survival claims mode, send a message about the claim basics video (except for admins - assumed experts)
			if(instance.config_claims_worldModes.get(player.getWorld()) == ClaimsMode.Survival && !player.hasPermission("griefprevention.adminclaims") && this.dataStore.claims.size() > 10) {
				WelcomeTask task = new WelcomeTask(player);
				Bukkit.getScheduler().scheduleSyncDelayedTask(instance, task, instance.config_claims_manualDeliveryDelaySeconds * 20L);
			}
		}

		//silence notifications when they're coming too fast
		if(event.getJoinMessage() != null && this.shouldSilenceNotification()) {
			event.setJoinMessage(null);
		}

		//FEATURE: auto-ban accounts who use an IP address which was very recently used by another banned account
		if(instance.config_smartBan && !player.hasPlayedBefore()) {
			//search temporarily banned IP addresses for this one
			for(int i = 0; i < this.tempBannedIps.size(); i++) {
				IpBanInfo info = this.tempBannedIps.get(i);
				String address = info.address.toString();

				//eliminate any expired entries
				if(now > info.expirationTimestamp) {
					this.tempBannedIps.remove(i--);
				}

				//if we find a match
				else if(address.equals(playerData.ipAddress.toString())) {
					//if the account associated with the IP ban has been pardoned, remove all ip bans for that ip and we're done
					OfflinePlayer bannedPlayer = instance.getServer().getOfflinePlayer(info.bannedAccountName);
					if(!bannedPlayer.isBanned()) {
						for(int j = 0; j < this.tempBannedIps.size(); j++) {
							IpBanInfo info2 = this.tempBannedIps.get(j);
							if(info2.address.toString().equals(address)) {
								OfflinePlayer bannedAccount = instance.getServer().getOfflinePlayer(info2.bannedAccountName);
								instance.getServer().getBanList(BanList.Type.NAME).pardon(bannedAccount.getName());
								this.tempBannedIps.remove(j--);
							}
						}
					}

					//otherwise if that account is still banned, ban this account, too
					else {
						GriefPreventionLight.AddLogEntry("Auto-banned new player " + player.getName() + " because that account is using an IP address very recently used by banned player " + info.bannedAccountName + " (" + info.address.toString() + ").", CustomLogEntryTypes.AdminActivity);

						//notify any online ops
						@SuppressWarnings("unchecked")
						Collection<Player> players = (Collection<Player>) instance.getServer().getOnlinePlayers();
						for(Player otherPlayer : players) {
							if(otherPlayer.isOp()) {
								GriefPreventionLight.sendMessage(otherPlayer, TextMode.Success, Messages.AutoBanNotify, player.getName(), info.bannedAccountName);
							}
						}

						//ban player
						PlayerKickBanTask task = new PlayerKickBanTask(player, "", "GriefPrevention Smart Ban - Shared Login:" + info.bannedAccountName, true);
						instance.getServer().getScheduler().scheduleSyncDelayedTask(instance, task, 10L);

						//silence join message
						event.setJoinMessage("");

					}
					break;
				}
			}
		}

		//in case player has changed his name, on successful login, update UUID > Name mapping
		GriefPreventionLight.cacheUUIDNamePair(player.getUniqueId(), player.getName());

		//ensure we're not over the limit for this IP address
		InetAddress ipAddress = playerData.ipAddress;
		if(ipAddress != null) {
			int ipLimit = instance.config_ipLimit;
			if(ipLimit > 0 && GriefPreventionLight.isNewToServer(player)) {
				int ipCount = 0;

				@SuppressWarnings("unchecked")
				Collection<Player> players = (Collection<Player>) instance.getServer().getOnlinePlayers();
				for(Player onlinePlayer : players) {
					if(onlinePlayer.getUniqueId().equals(player.getUniqueId())) continue;

					PlayerData otherData = instance.dataStore.getPlayerData(onlinePlayer.getUniqueId());
					if(ipAddress.equals(otherData.ipAddress) && GriefPreventionLight.isNewToServer(onlinePlayer)) {
						ipCount++;
					}
				}

				if(ipCount >= ipLimit) {
					//kick player
					PlayerKickBanTask task = new PlayerKickBanTask(player, instance.dataStore.getMessage(Messages.TooMuchIpOverlap), "GriefPrevention IP-sharing limit.", false);
					instance.getServer().getScheduler().scheduleSyncDelayedTask(instance, task, 100L);

					//silence join message
					event.setJoinMessage(null);
					return;
				}
			}
		}

		//create a thread to load ignore information
		new IgnoreLoaderThread(playerID, playerData.ignoredPlayers).start();

		//is he stuck in a portal frame?
		if(player.hasMetadata("GP_PORTALRESCUE")) {
			//If so, let him know and rescue him in 10 seconds. If he is in fact not trapped, hopefully chunks will have loaded by this time so he can walk out.
			GriefPreventionLight.sendMessage(player, TextMode.Info, Messages.NetherPortalTrapDetectionMessage, 20L);
			new BukkitRunnable() {
				@Override
				public void run() {
					if(player.getPortalCooldown() > 8 && player.hasMetadata("GP_PORTALRESCUE")) {
						GriefPreventionLight.AddLogEntry("Rescued " + player.getName() + " from a nether portal.\nTeleported from " + player.getLocation().toString() + " to " + player.getMetadata("GP_PORTALRESCUE").get(0).value().toString(), CustomLogEntryTypes.Debug);
						player.teleport((Location) player.getMetadata("GP_PORTALRESCUE").get(0).value());
						player.removeMetadata("GP_PORTALRESCUE", instance);
					}
				}
			}.runTaskLater(instance, 200L);
		}
		//Otherwise just reset cooldown, just in case they happened to logout again...
		else
			player.setPortalCooldown(0);


		//if we're holding a logout message for this player, don't send that or this event's join message
		if(instance.config_spam_logoutMessageDelaySeconds > 0) {
			String joinMessage = event.getJoinMessage();
			if(joinMessage != null && !joinMessage.isEmpty()) {
				Integer taskID = this.heldLogoutMessages.get(player.getUniqueId());
				if(taskID != null && Bukkit.getScheduler().isQueued(taskID)) {
					Bukkit.getScheduler().cancelTask(taskID);
					player.sendMessage(event.getJoinMessage());
					event.setJoinMessage("");
				}
			}
		}
	}

	//when a player spawns, conditionally apply temporary pvp protection
	@EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
	void onPlayerRespawn(PlayerRespawnEvent event) {
		Player player = event.getPlayer();
		PlayerData playerData = instance.dataStore.getPlayerData(player.getUniqueId());
		playerData.lastSpawn = Calendar.getInstance().getTimeInMillis();
		playerData.lastPvpTimestamp = 0;  //no longer in pvp combat

		//also send him any messaged from grief prevention he would have received while dead
		if(playerData.messageOnRespawn != null) {
			GriefPreventionLight.sendMessage(player, ChatColor.RESET /*color is alrady embedded in message in this case*/, playerData.messageOnRespawn, 40L);
			playerData.messageOnRespawn = null;
		}

		instance.checkPvpProtectionNeeded(player);
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	void onPlayerDeath(PlayerDeathEvent event) {
		//FEATURE: prevent death message spam by implementing a "cooldown period" for death messages
		Player player = event.getEntity();

		//these are related to locking dropped items on death to prevent theft
		PlayerData playerData = instance.dataStore.getPlayerData(player.getUniqueId());
		playerData.dropsAreUnlocked = false;
		playerData.receivedDropUnlockAdvertisement = false;
	}

	//when a player gets kicked...
	@EventHandler(priority = EventPriority.HIGHEST)
	void onPlayerKicked(PlayerKickEvent event) {
		Player player = event.getPlayer();
		PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
		playerData.wasKicked = true;
	}

	//when a player quits...
	private final HashMap<UUID, Integer> heldLogoutMessages = new HashMap<>();

	@EventHandler(priority = EventPriority.HIGHEST)
	void onPlayerQuit(PlayerQuitEvent event) {
		Player player = event.getPlayer();
		UUID playerID = player.getUniqueId();
		PlayerData playerData = this.dataStore.getPlayerData(playerID);
		boolean isBanned;

		//If player is not trapped in a portal and has a pending rescue task, remove the associated metadata
		//Why 9? No idea why, but this is decremented by 1 when the player disconnects.
		if(player.getPortalCooldown() < 9) {
			player.removeMetadata("GP_PORTALRESCUE", instance);
		}

		if(playerData.wasKicked) {
			isBanned = player.isBanned();
		} else {
			isBanned = false;
		}

		//if banned, add IP to the temporary IP ban list
		if(isBanned && playerData.ipAddress != null) {
			long now = Calendar.getInstance().getTimeInMillis();
			this.tempBannedIps.add(new IpBanInfo(playerData.ipAddress, now + this.MILLISECONDS_IN_DAY, player.getName()));
		}

		//silence notifications when they're coming too fast
		if(event.getQuitMessage() != null && this.shouldSilenceNotification()) {
			event.setQuitMessage(null);
		}

		//silence notifications when the player is banned
		if(isBanned) {
			event.setQuitMessage(null);
		}

		//make sure his data is all saved - he might have accrued some claim blocks while playing that were not saved immediately
		else {
			this.dataStore.savePlayerData(player.getUniqueId(), playerData);
		}

		//FEATURE: during a siege, any player who logs out dies and forfeits the siege

		//drop data about this player
		this.dataStore.clearCachedPlayerData(playerID);

		//send quit message later, but only if the player stays offline
		if(instance.config_spam_logoutMessageDelaySeconds > 0) {
			String quitMessage = event.getQuitMessage();
			if(quitMessage != null && !quitMessage.isEmpty()) {
				BroadcastMessageTask task = new BroadcastMessageTask(quitMessage);
				int taskID = Bukkit.getScheduler().scheduleSyncDelayedTask(instance, task, 20L * instance.config_spam_logoutMessageDelaySeconds);
				this.heldLogoutMessages.put(playerID, taskID);
				event.setQuitMessage("");
			}
		}
	}

	//determines whether or not a login or logout notification should be silenced, depending on how many there have been in the last minute
	private boolean shouldSilenceNotification() {
		if(instance.config_spam_loginLogoutNotificationsPerMinute <= 0) {
			return false; // not silencing login/logout notifications
		}

		final long ONE_MINUTE = 60000;
		Long now = Calendar.getInstance().getTimeInMillis();

		//eliminate any expired entries (longer than a minute ago)
		for(int i = 0; i < this.recentLoginLogoutNotifications.size(); i++) {
			Long notificationTimestamp = this.recentLoginLogoutNotifications.get(i);
			if(now - notificationTimestamp > ONE_MINUTE) {
				this.recentLoginLogoutNotifications.remove(i--);
			} else {
				break;
			}
		}

		//add the new entry
		this.recentLoginLogoutNotifications.add(now);

		return this.recentLoginLogoutNotifications.size() > instance.config_spam_loginLogoutNotificationsPerMinute;
	}

	//when a player teleports via a portal
	@EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
	void onPlayerPortal(PlayerPortalEvent event) {
		//if the player isn't going anywhere, take no action
		if(event.getTo() == null || event.getTo().getWorld() == null) return;

		Player player = event.getPlayer();
		if(event.getCause() == TeleportCause.NETHER_PORTAL) {
			//FEATURE: when players get trapped in a nether portal, send them back through to the other side
			instance.startRescueTask(player, player.getLocation());
		}
	}

	//when a player teleports
	@EventHandler(priority = EventPriority.LOWEST)
	public void onPlayerTeleport(PlayerTeleportEvent event) {
		Player player = event.getPlayer();
		PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());

		//FEATURE: prevent players from using ender pearls to gain access to secured claims
		TeleportCause cause = event.getCause();
		if(cause == TeleportCause.CHORUS_FRUIT || (cause == TeleportCause.ENDER_PEARL && instance.config_claims_enderPearlsRequireAccessTrust)) {
			Claim toClaim = this.dataStore.getClaimAt(event.getTo(), playerData.lastClaim);
			if(toClaim != null) {
				playerData.lastClaim = toClaim;
				String noAccessReason = toClaim.allowAccess(player);
				if(noAccessReason != null) {
					GriefPreventionLight.sendMessage(player, TextMode.Err, noAccessReason);
					event.setCancelled(true);
					if(cause == TeleportCause.ENDER_PEARL)
						player.getInventory().addItem(new ItemStack(Material.ENDER_PEARL));
				}
			}
		}
	}

	//when a player triggers a raid (in a claim)
	@EventHandler(priority = EventPriority.LOWEST)
	public void onPlayerTriggerRaid(RaidTriggerEvent event) {
		if(!instance.config_claims_raidTriggersRequireBuildTrust)
			return;

		Player player = event.getPlayer();
		PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());

		Claim toClaim = this.dataStore.getClaimAt(player.getLocation(), playerData.lastClaim);
		if(toClaim == null)
			return;

		playerData.lastClaim = toClaim;
		if(toClaim.allowBuild(player) == null)
			return;

		event.setCancelled(true);
	}

	//when a player interacts with a specific part of entity...
	@EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
	public void onPlayerInteractAtEntity(PlayerInteractAtEntityEvent event) {
		//treat it the same as interacting with an entity in general
		if(event.getRightClicked().getType() == EntityType.ARMOR_STAND) {
			this.onPlayerInteractEntity(event);
		}
	}

	//when a player interacts with an entity...
	@EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
	public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
		Player player = event.getPlayer();
		Entity entity = event.getRightClicked();

		if(!instance.claimsEnabledForWorld(entity.getWorld())) return;

		//allow horse protection to be overridden to allow management from other plugins
		if(!instance.config_claims_protectHorses && entity instanceof AbstractHorse) return;
		if(!instance.config_claims_protectDonkeys && entity instanceof Donkey) return;
		if(!instance.config_claims_protectDonkeys && entity instanceof Mule) return;
		if(!instance.config_claims_protectLlamas && entity instanceof Llama) return;

		PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());

		//if entity is tameable and has an owner, apply special rules
		if(entity instanceof Tameable) {
			Tameable tameable = (Tameable) entity;
			if(tameable.isTamed()) {
				if(tameable.getOwner() != null) {
					UUID ownerID = tameable.getOwner().getUniqueId();

					//if the player interacting is the owner or an admin in ignore claims mode, always allow
					if(player.getUniqueId().equals(ownerID) || playerData.ignoreClaims) {
						//if giving away pet, do that instead
						if(playerData.petGiveawayRecipient != null) {
							tameable.setOwner(playerData.petGiveawayRecipient);
							playerData.petGiveawayRecipient = null;
							GriefPreventionLight.sendMessage(player, TextMode.Success, Messages.PetGiveawayConfirmation);
							event.setCancelled(true);
						}

						return;
					}
				}
			} else  //world repair code for a now-fixed GP bug //TODO: necessary anymore?
			{
				//ensure this entity can be tamed by players
				tameable.setOwner(null);
				if(tameable instanceof InventoryHolder) {
					InventoryHolder holder = (InventoryHolder) tameable;
					holder.getInventory().clear();
				}
			}
		}

		//don't allow interaction with item frames or armor stands in claimed areas without build permission
		if(entity.getType() == EntityType.ARMOR_STAND || entity instanceof Hanging) {
			String noBuildReason = instance.allowBuild(player, entity.getLocation());
			if(noBuildReason != null) {
				GriefPreventionLight.sendMessage(player, TextMode.Err, noBuildReason);
				event.setCancelled(true);
				return;
			}
		}

		//always allow interactions when player is in ignore claims mode
		if(playerData.ignoreClaims) return;

		//if the entity is a vehicle and we're preventing theft in claims
		if(instance.config_claims_preventTheft && entity instanceof Vehicle) {
			//if the entity is in a claim
			Claim claim = this.dataStore.getClaimAt(entity.getLocation(), null);
			if(claim != null) {
				//for storage entities, apply container rules (this is a potential theft)
				if(entity instanceof InventoryHolder) {
					String noContainersReason = claim.allowContainers(player);
					if(noContainersReason != null) {
						GriefPreventionLight.sendMessage(player, TextMode.Err, noContainersReason);
						event.setCancelled(true);
						return;
					}
				}
			}
		}

		//if the entity is an animal, apply container rules
		if((instance.config_claims_preventTheft && (entity instanceof Animals || entity instanceof Fish)) || (entity.getType() == EntityType.VILLAGER && instance.config_claims_villagerTradingRequiresTrust)) {
			//if the entity is in a claim
			Claim claim = this.dataStore.getClaimAt(entity.getLocation(), null);
			if(claim != null) {
				if(claim.allowContainers(player) != null) {
					String message = instance.dataStore.getMessage(Messages.NoDamageClaimedEntity, claim.getOwnerName());
					if(player.hasPermission("griefprevention.ignoreclaims"))
						message += "  " + instance.dataStore.getMessage(Messages.IgnoreClaimsAdvertisement);
					GriefPreventionLight.sendMessage(player, TextMode.Err, message);
					event.setCancelled(true);
					return;
				}
			}
		}

		//if preventing theft, prevent leashing claimed creatures
		if(instance.config_claims_preventTheft && entity instanceof Creature && instance.getItemInHand(player, event.getHand()).getType() == Material.LEAD) {
			Claim claim = this.dataStore.getClaimAt(entity.getLocation(), playerData.lastClaim);
			if(claim != null) {
				String failureReason = claim.allowContainers(player);
				if(failureReason != null) {
					event.setCancelled(true);
					GriefPreventionLight.sendMessage(player, TextMode.Err, failureReason);
				}
			}
		}
	}

	//when a player reels in his fishing rod
	@EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
	public void onPlayerFish(PlayerFishEvent event) {
		Entity entity = event.getCaught();
		if(entity == null) return;  //if nothing pulled, uninteresting event

		//if should be protected from pulling in land claims without permission
		if(entity.getType() == EntityType.ARMOR_STAND || entity instanceof Animals) {
			Player player = event.getPlayer();
			PlayerData playerData = instance.dataStore.getPlayerData(player.getUniqueId());
			Claim claim = instance.dataStore.getClaimAt(entity.getLocation(), playerData.lastClaim);
			if(claim != null) {
				//if no permission, cancel
				String errorMessage = claim.allowContainers(player);
				if(errorMessage != null) {
					event.setCancelled(true);
					GriefPreventionLight.sendMessage(player, TextMode.Err, Messages.NoDamageClaimedEntity, claim.getOwnerName());
				}
			}
		}
	}

	//when a player picks up an item...
	@EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
	public void onPlayerPickupItem(PlayerPickupItemEvent event) {
		Player player = event.getPlayer();

		//FEATURE: lock dropped items to player who dropped them

		//who owns this stack?
		Item item = event.getItem();
		List<MetadataValue> data = item.getMetadata("GP_ITEMOWNER");
		if(data.size() > 0) {
			UUID ownerID = (UUID) data.get(0).value();

			//has that player unlocked his drops?
			OfflinePlayer owner = instance.getServer().getOfflinePlayer(ownerID);
			String ownerName = GriefPreventionLight.lookupPlayerName(ownerID);
			if(owner.isOnline() && !player.equals(owner)) {
				PlayerData playerData = this.dataStore.getPlayerData(ownerID);

				//if locked, don't allow pickup
				if(!playerData.dropsAreUnlocked) {
					event.setCancelled(true);

					//if hasn't been instructed how to unlock, send explanatory messages
					if(!playerData.receivedDropUnlockAdvertisement) {
						GriefPreventionLight.sendMessage(owner.getPlayer(), TextMode.Instr, Messages.DropUnlockAdvertisement);
						GriefPreventionLight.sendMessage(player, TextMode.Err, Messages.PickupBlockedExplanation, ownerName);
						playerData.receivedDropUnlockAdvertisement = true;
					}
				}
			}
		}
	}

	//when a player switches in-hand items
	@EventHandler(ignoreCancelled = true)
	public void onItemHeldChange(PlayerItemHeldEvent event) {
		Player player = event.getPlayer();

		//if he's switching to the golden shovel
		int newSlot = event.getNewSlot();
		ItemStack newItemStack = player.getInventory().getItem(newSlot);
		if(newItemStack != null && newItemStack.getType() == instance.config_claims_modificationTool) {
			//give the player his available claim blocks count and claiming instructions, but only if he keeps the shovel equipped for a minimum time, to avoid mouse wheel spam
			if(instance.claimsEnabledForWorld(player.getWorld())) {
				EquipShovelProcessingTask task = new EquipShovelProcessingTask(player);
				instance.getServer().getScheduler().scheduleSyncDelayedTask(instance, task, 15L);  //15L is approx. 3/4 of a second
			}
		}
	}

	//block use of buckets within other players' claims
	private final Set<Material> commonAdjacentBlocks_water = EnumSet.of(Material.WATER, Material.FARMLAND, Material.DIRT, Material.STONE);
	private final Set<Material> commonAdjacentBlocks_lava = EnumSet.of(Material.LAVA, Material.DIRT, Material.STONE);

	@EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
	public void onPlayerBucketEmpty(PlayerBucketEmptyEvent bucketEvent) {
		if(!instance.claimsEnabledForWorld(bucketEvent.getBlockClicked().getWorld())) return;

		Player player = bucketEvent.getPlayer();
		Block block = bucketEvent.getBlockClicked().getRelative(bucketEvent.getBlockFace());

		//make sure the player is allowed to build at the location
		String noBuildReason = instance.allowBuild(player, block.getLocation());
		if(noBuildReason != null) {
			GriefPreventionLight.sendMessage(player, TextMode.Err, noBuildReason);
			bucketEvent.setCancelled(true);
			return;
		}

		//log any suspicious placements (check sea level, world type, and adjacent blocks)
		if(block.getY() >= instance.getSeaLevel(block.getWorld()) - 5 && !player.hasPermission("griefprevention.lava") && block.getWorld().getEnvironment() != Environment.NETHER) {
			//if certain blocks are nearby, it's less suspicious and not worth logging
			Set<Material> exclusionAdjacentTypes;
			if(bucketEvent.getBucket() == Material.WATER_BUCKET)
				exclusionAdjacentTypes = this.commonAdjacentBlocks_water;
			else
				exclusionAdjacentTypes = this.commonAdjacentBlocks_lava;

			boolean makeLogEntry = true;
			BlockFace[] adjacentDirections = new BlockFace[] {BlockFace.EAST, BlockFace.WEST, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.DOWN};
			for(BlockFace direction : adjacentDirections) {
				Material adjacentBlockType = block.getRelative(direction).getType();
				if(exclusionAdjacentTypes.contains(adjacentBlockType)) {
					makeLogEntry = false;
					break;
				}
			}

			if(makeLogEntry) {
				GriefPreventionLight.AddLogEntry(player.getName() + " placed suspicious " + bucketEvent.getBucket().name() + " @ " + block.getLocation(), CustomLogEntryTypes.SuspiciousActivity, true);
			}
		}
	}

	//see above
	@EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
	public void onPlayerBucketFill(PlayerBucketFillEvent bucketEvent) {
		Player player = bucketEvent.getPlayer();
		Block block = bucketEvent.getBlockClicked();

		if(!instance.claimsEnabledForWorld(block.getWorld())) return;

		//make sure the player is allowed to build at the location
		String noBuildReason = instance.allowBuild(player, block.getLocation());
		if(noBuildReason != null) {
			//exemption for cow milking (permissions will be handled by player interact with entity event instead)
			Material blockType = block.getType();
			if(blockType == Material.AIR)
				return;
			if(blockType.isSolid()) {
				BlockData blockData = block.getBlockData();
				if(!(blockData instanceof Waterlogged) || !((Waterlogged) blockData).isWaterlogged())
					return;
			}

			GriefPreventionLight.sendMessage(player, TextMode.Err, noBuildReason);
			bucketEvent.setCancelled(true);
		}
	}

	//when a player interacts with the world
	@EventHandler(priority = EventPriority.LOWEST)
	void onPlayerInteract(PlayerInteractEvent event) {
		//not interested in left-click-on-air actions
		Action action = event.getAction();
		if(action == Action.LEFT_CLICK_AIR) return;

		Player player = event.getPlayer();
		Block clickedBlock = event.getClickedBlock(); //null returned here means interacting with air

		Material clickedBlockType = null;
		if(clickedBlock != null) {
			clickedBlockType = clickedBlock.getType();
		} else {
			clickedBlockType = Material.AIR;
		}

		PlayerData playerData = null;

		//Turtle eggs
		if(action == Action.PHYSICAL) {
			if(clickedBlockType != Material.TURTLE_EGG)
				return;
			playerData = this.dataStore.getPlayerData(player.getUniqueId());
			Claim claim = this.dataStore.getClaimAt(clickedBlock.getLocation(), playerData.lastClaim);
			if(claim != null) {
				playerData.lastClaim = claim;

				String noAccessReason = claim.allowBreak(player);
				if(noAccessReason != null) {
					event.setCancelled(true);
					return;
				}
			}
			return;
		}

		//don't care about left-clicking on most blocks, this is probably a break action
		if(action == Action.LEFT_CLICK_BLOCK && clickedBlock != null) {
			if(clickedBlock.getY() < clickedBlock.getWorld().getMaxHeight() - 1 || event.getBlockFace() != BlockFace.UP) {
				Block adjacentBlock = clickedBlock.getRelative(event.getBlockFace());
				byte lightLevel = adjacentBlock.getLightFromBlocks();
				if(lightLevel == 15 && adjacentBlock.getType() == Material.FIRE) {
					playerData = this.dataStore.getPlayerData(player.getUniqueId());
					Claim claim = this.dataStore.getClaimAt(clickedBlock.getLocation(), playerData.lastClaim);
					if(claim != null) {
						playerData.lastClaim = claim;

						String noBuildReason = claim.allowBuild(player);
						if(noBuildReason != null) {
							event.setCancelled(true);
							GriefPreventionLight.sendMessage(player, TextMode.Err, noBuildReason);
							player.sendBlockChange(adjacentBlock.getLocation(), adjacentBlock.getType(), adjacentBlock.getData());
							return;
						}
					}
				}
			}

			//exception for blocks on a specific watch list
			if(!this.onLeftClickWatchList(clickedBlockType)) {
				return;
			}
		}

		//apply rules for containers and crafting blocks
		if(clickedBlock != null && instance.config_claims_preventTheft && (
				event.getAction() == Action.RIGHT_CLICK_BLOCK && (
						(this.isInventoryHolder(clickedBlock) && clickedBlock.getType() != Material.LECTERN) ||
								clickedBlockType == Material.CAULDRON ||
								clickedBlockType == Material.JUKEBOX ||
								clickedBlockType == Material.ANVIL ||
								clickedBlockType == Material.CHIPPED_ANVIL ||
								clickedBlockType == Material.DAMAGED_ANVIL ||
								clickedBlockType == Material.CAKE ||
								clickedBlockType == Material.SWEET_BERRY_BUSH ||
								clickedBlockType == Material.BEE_NEST ||
								clickedBlockType == Material.BEEHIVE ||
								clickedBlockType == Material.BEACON ||
								clickedBlockType == Material.BELL ||
								clickedBlockType == Material.STONECUTTER ||
								clickedBlockType == Material.GRINDSTONE ||
								clickedBlockType == Material.CARTOGRAPHY_TABLE ||
								clickedBlockType == Material.LOOM ||
								clickedBlockType == Material.RESPAWN_ANCHOR
				))) {
			if(playerData == null) playerData = this.dataStore.getPlayerData(player.getUniqueId());


			//otherwise check permissions for the claim the player is in
			Claim claim = this.dataStore.getClaimAt(clickedBlock.getLocation(), playerData.lastClaim);
			if(claim != null) {
				playerData.lastClaim = claim;

				String noContainersReason = claim.allowContainers(player);
				if(noContainersReason != null) {
					event.setCancelled(true);
					GriefPreventionLight.sendMessage(player, TextMode.Err, noContainersReason);
				}
			}
		}

		//otherwise apply rules for doors and beds, if configured that way
		else if(clickedBlock != null &&

				(instance.config_claims_lockWoodenDoors && Tag.WOODEN_DOORS.isTagged(clickedBlockType) ||

						instance.config_claims_preventButtonsSwitches && Tag.BEDS.isTagged(clickedBlockType) ||

						instance.config_claims_lockTrapDoors && Tag.WOODEN_TRAPDOORS.isTagged(clickedBlockType) ||

						instance.config_claims_lecternReadingRequiresAccessTrust && clickedBlockType == Material.LECTERN ||

						instance.config_claims_lockFenceGates && Tag.FENCE_GATES.isTagged(clickedBlockType))) {
			if(playerData == null) playerData = this.dataStore.getPlayerData(player.getUniqueId());
			Claim claim = this.dataStore.getClaimAt(clickedBlock.getLocation(), playerData.lastClaim);
			if(claim != null) {
				playerData.lastClaim = claim;

				String noAccessReason = claim.allowAccess(player);
				if(noAccessReason != null) {
					event.setCancelled(true);
					GriefPreventionLight.sendMessage(player, TextMode.Err, noAccessReason);
				}
			}
		}

		//otherwise apply rules for buttons and switches
		else if(clickedBlock != null && instance.config_claims_preventButtonsSwitches && (Tag.BUTTONS.isTagged(clickedBlockType) || clickedBlockType == Material.LEVER)) {
			if(playerData == null) playerData = this.dataStore.getPlayerData(player.getUniqueId());
			Claim claim = this.dataStore.getClaimAt(clickedBlock.getLocation(), playerData.lastClaim);
			if(claim != null) {
				playerData.lastClaim = claim;

				String noAccessReason = claim.allowAccess(player);
				if(noAccessReason != null) {
					event.setCancelled(true);
					GriefPreventionLight.sendMessage(player, TextMode.Err, noAccessReason);
				}
			}
		}

		//otherwise apply rule for cake
		else if(clickedBlock != null && instance.config_claims_preventTheft && clickedBlockType == Material.CAKE) {
			if(playerData == null) playerData = this.dataStore.getPlayerData(player.getUniqueId());
			Claim claim = this.dataStore.getClaimAt(clickedBlock.getLocation(), playerData.lastClaim);
			if(claim != null) {
				playerData.lastClaim = claim;

				String noContainerReason = claim.allowAccess(player);
				if(noContainerReason != null) {
					event.setCancelled(true);
					GriefPreventionLight.sendMessage(player, TextMode.Err, noContainerReason);
				}
			}
		}

		//apply rule for note blocks and repeaters and daylight sensors //RoboMWM: Include flower pots
		else if(clickedBlock != null &&
				(
						clickedBlockType == Material.NOTE_BLOCK ||
								clickedBlockType == Material.REPEATER ||
								clickedBlockType == Material.DRAGON_EGG ||
								clickedBlockType == Material.DAYLIGHT_DETECTOR ||
								clickedBlockType == Material.COMPARATOR ||
								clickedBlockType == Material.REDSTONE_WIRE ||
								Tag.FLOWER_POTS.isTagged(clickedBlockType)
				)) {
			if(playerData == null) playerData = this.dataStore.getPlayerData(player.getUniqueId());
			Claim claim = this.dataStore.getClaimAt(clickedBlock.getLocation(), playerData.lastClaim);
			if(claim != null) {
				String noBuildReason = claim.allowBuild(player);
				if(noBuildReason != null) {
					event.setCancelled(true);
					GriefPreventionLight.sendMessage(player, TextMode.Err, noBuildReason);
				}
			}
		}

		//otherwise handle right click (shovel, string, bonemeal) //RoboMWM: flint and steel
		else {
			//ignore all actions except right-click on a block or in the air
			if(action != Action.RIGHT_CLICK_BLOCK && action != Action.RIGHT_CLICK_AIR) return;

			//what's the player holding?
			EquipmentSlot hand = event.getHand();
			ItemStack itemInHand = instance.getItemInHand(player, hand);
			Material materialInHand = itemInHand.getType();

			// Allow placing boats and minecarts in any claim
			// TODO
			if(Tag.ITEMS_BOATS.isTagged(materialInHand) || Material.MINECART == materialInHand) {
				Bukkit.getScheduler().runTaskLater(GriefPreventionLight.instance, () -> {
					final RayTraceResult rayTrace = player.getWorld().rayTraceEntities(player.getEyeLocation(), player.getEyeLocation().getDirection(), 6, 1.1F, (entity -> entity instanceof Vehicle));
					if(rayTrace != null) {
						final Entity vehicle = rayTrace.getHitEntity();
						if(vehicle != null) {
							vehicle.setMetadata("griefprevention_owner", new FixedMetadataValue(GriefPreventionLight.instance, player.getUniqueId()));
						}
					}
				}, 1);
				return;
			}

			//if it's bonemeal, armor stand, spawn egg, etc - check for build permission //RoboMWM: also check flint and steel to stop TNT ignition
			if(clickedBlock != null && (materialInHand == Material.BONE_MEAL
					|| materialInHand == Material.ARMOR_STAND
					|| (spawn_eggs.contains(materialInHand) && GriefPreventionLight.instance.config_claims_preventGlobalMonsterEggs)
					|| materialInHand == Material.END_CRYSTAL
					|| materialInHand == Material.FLINT_AND_STEEL
					|| dyes.contains(materialInHand))) {
				String noBuildReason = instance
						.allowBuild(player, clickedBlock
								.getLocation()
						);
				if(noBuildReason != null) {
					GriefPreventionLight.sendMessage(player, TextMode.Err, noBuildReason);
					event.setCancelled(true);
				}

				return;
			}

			//survival world minecart placement requires container trust, which is the permission required to remove the minecart later
			else if(clickedBlock != null &&
					(materialInHand == Material.MINECART ||
							materialInHand == Material.FURNACE_MINECART ||
							materialInHand == Material.CHEST_MINECART ||
							materialInHand == Material.TNT_MINECART ||
							materialInHand == Material.HOPPER_MINECART)) {
				playerData = this.dataStore.getPlayerData(player.getUniqueId());
				Claim claim = this.dataStore.getClaimAt(clickedBlock.getLocation(), playerData.lastClaim);
				if(claim != null) {
					String reason = claim.allowContainers(player);
					if(reason != null) {
						GriefPreventionLight.sendMessage(player, TextMode.Err, reason);
						event.setCancelled(true);
					}
				}

				return;
			}

			//if he's investigating a claim
			else if(materialInHand == instance.config_claims_investigationTool && hand == EquipmentSlot.HAND) {
				//if claims are disabled in this world, do nothing
				if(!instance.claimsEnabledForWorld(player.getWorld())) return;

				//if holding shift (sneaking), show all claims in area
				if(player.isSneaking() && player.hasPermission("griefprevention.visualizenearbyclaims")) {
					//find nearby claims
					Set<Claim> claims = this.dataStore.getNearbyClaims(player.getLocation());

					// alert plugins of a claim inspection, return if cancelled
					ClaimInspectionEvent inspectionEvent = new ClaimInspectionEvent(player, null, claims, true);
					Bukkit.getPluginManager().callEvent(inspectionEvent);
					if(inspectionEvent.isCancelled()) return;

					//visualize boundaries
					Visualization visualization = Visualization.fromClaims(claims, VisualizationType.Claim, player.getLocation());

					// alert plugins of a visualization
					Bukkit.getPluginManager().callEvent(new VisualizationEvent(player, visualization, claims, true));

					Visualization.Apply(player, visualization);

					GriefPreventionLight.sendMessage(player, TextMode.Info, Messages.ShowNearbyClaims, String.valueOf(claims.size()));

					return;
				}

				//FEATURE: shovel and stick can be used from a distance away
				if(action == Action.RIGHT_CLICK_AIR) {
					//try to find a far away non-air block along line of sight
					clickedBlock = getTargetBlock(player);
					clickedBlockType = clickedBlock.getType();
				}

				//if no block, stop here
				if(clickedBlock == null) {
					return;
				}

				//air indicates too far away
				if(clickedBlockType == Material.AIR) {
					GriefPreventionLight.sendMessage(player, TextMode.Err, Messages.TooFarAway);

					// alert plugins of a visualization
					Bukkit.getPluginManager().callEvent(new VisualizationEvent(player, null, Collections.emptySet()));

					Visualization.Revert(player);
					return;
				}

				playerData = this.dataStore.getPlayerData(player.getUniqueId());
				Claim claim = this.dataStore.getClaimAt(clickedBlock.getLocation(), playerData.lastClaim);

				//no claim case
				if(claim == null) {
					// alert plugins of a claim inspection, return if cancelled
					ClaimInspectionEvent inspectionEvent = new ClaimInspectionEvent(player, clickedBlock, null);
					Bukkit.getPluginManager().callEvent(inspectionEvent);
					if(inspectionEvent.isCancelled()) return;

					GriefPreventionLight.sendMessage(player, TextMode.Info, Messages.BlockNotClaimed);

					// alert plugins of a visualization
					Bukkit.getPluginManager().callEvent(new VisualizationEvent(player, null, Collections.emptySet()));

					Visualization.Revert(player);
				}

				//claim case
				else {
					// alert plugins of a claim inspection, return if cancelled
					ClaimInspectionEvent inspectionEvent = new ClaimInspectionEvent(player, clickedBlock, claim);
					Bukkit.getPluginManager().callEvent(inspectionEvent);
					if(inspectionEvent.isCancelled()) return;

					playerData.lastClaim = claim;
					GriefPreventionLight.sendMessage(player, TextMode.Info, Messages.BlockClaimed, claim.getOwnerName());

					//visualize boundary
					Visualization visualization = Visualization.FromClaim(claim, VisualizationType.Claim, player.getLocation());

					// alert plugins of a visualization
					Bukkit.getPluginManager().callEvent(new VisualizationEvent(player, visualization, claim));

					Visualization.Apply(player, visualization);

					if(player.hasPermission("griefprevention.seeclaimsize")) {
						GriefPreventionLight.sendMessage(player, TextMode.Info, "  " + claim.getWidth() + "x" + claim.getLength() + "=" + claim.getArea());
					}

					//if permission, tell about the player's offline time
					if(!claim.isAdminClaim() && (player.hasPermission("griefprevention.deleteclaims") || player.hasPermission("griefprevention.seeinactivity"))) {
						if(claim.parent != null) {
							claim = claim.parent;
						}
						Date lastLogin = new Date(Bukkit.getOfflinePlayer(claim.ownerID).getLastPlayed());
						Date now = new Date();
						long daysElapsed = (now.getTime() - lastLogin.getTime()) / (1000 * 60 * 60 * 24);

						GriefPreventionLight.sendMessage(player, TextMode.Info, Messages.PlayerOfflineTime, String.valueOf(daysElapsed));

						//drop the data we just loaded, if the player isn't online
						if(instance.getServer().getPlayer(claim.ownerID) == null)
							this.dataStore.clearCachedPlayerData(claim.ownerID);
					}
				}

				return;
			}

			//if it's a golden shovel
			else if(materialInHand != instance.config_claims_modificationTool || hand != EquipmentSlot.HAND) return;

			event.setCancelled(true);  //GriefPrevention exclusively reserves this tool  (e.g. no grass path creation for golden shovel)

			//FEATURE: shovel and stick can be used from a distance away
			if(action == Action.RIGHT_CLICK_AIR) {
				//try to find a far away non-air block along line of sight
				clickedBlock = getTargetBlock(player);
				clickedBlockType = clickedBlock.getType();
			}

			//if no block, stop here
			if(clickedBlock == null) {
				return;
			}

			//can't use the shovel from too far away
			if(clickedBlockType == Material.AIR) {
				GriefPreventionLight.sendMessage(player, TextMode.Err, Messages.TooFarAway);
				return;
			}

			//if the player is in restore nature mode, do only that
			UUID playerID = player.getUniqueId();
			playerData = this.dataStore.getPlayerData(player.getUniqueId());

			//if the player doesn't have claims permission, don't do anything
			if(!player.hasPermission("griefprevention.createclaims")) {
				GriefPreventionLight.sendMessage(player, TextMode.Err, Messages.NoCreateClaimPermission);
				return;
			}

			//if he's resizing a claim and that claim hasn't been deleted since he started resizing it
			if(playerData.claimResizing != null && playerData.claimResizing.inDataStore) {
				if(clickedBlock.getLocation().equals(playerData.lastShovelLocation)) return;

				//figure out what the coords of his new claim would be
				int newx1, newx2, newz1, newz2;
				if(playerData.lastShovelLocation.getBlockX() == playerData.claimResizing.lesserBoundaryCorner.getX()) {
					newx1 = clickedBlock.getX();
				} else {
					newx1 = playerData.claimResizing.lesserBoundaryCorner.getX();
				}

				if(playerData.lastShovelLocation.getBlockX() == playerData.claimResizing.greaterBoundaryCorner.getX()) {
					newx2 = clickedBlock.getX();
				} else {
					newx2 = playerData.claimResizing.greaterBoundaryCorner.getX();
				}

				if(playerData.lastShovelLocation.getBlockZ() == playerData.claimResizing.lesserBoundaryCorner.getZ()) {
					newz1 = clickedBlock.getZ();
				} else {
					newz1 = playerData.claimResizing.lesserBoundaryCorner.getZ();
				}

				if(playerData.lastShovelLocation.getBlockZ() == playerData.claimResizing.greaterBoundaryCorner.getZ()) {
					newz2 = clickedBlock.getZ();
				} else {
					newz2 = playerData.claimResizing.greaterBoundaryCorner.getZ();
				}

				this.dataStore.resizeClaimWithChecks(player, playerData, newx1, newx2, newz1, newz2);

				return;
			}

			//otherwise, since not currently resizing a claim, must be starting a resize, creating a new claim, or creating a subdivision
			Claim claim = this.dataStore.getClaimAt(clickedBlock.getLocation(), playerData.lastClaim);

			//if within an existing claim, he's not creating a new one
			if(claim != null) {
				//if the player has permission to edit the claim or subdivision
				String noEditReason = claim.allowEdit(player);
				if(noEditReason == null) {
					//if he clicked on a corner, start resizing it
					if((clickedBlock.getX() == claim.lesserBoundaryCorner.getX() || clickedBlock.getX() == claim.greaterBoundaryCorner.getX()) && (clickedBlock.getZ() == claim.lesserBoundaryCorner.getZ() || clickedBlock.getZ() == claim.greaterBoundaryCorner.getZ())) {
						playerData.claimResizing = claim;
						playerData.lastShovelLocation = clickedBlock.getLocation();
						GriefPreventionLight.sendMessage(player, TextMode.Instr, Messages.ResizeStart);
					}

					//if he didn't click on a corner and is in subdivision mode, he's creating a new subdivision
					else if(playerData.shovelMode == ShovelMode.Subdivide) {
						//if it's the first click, he's trying to start a new subdivision
						if(playerData.lastShovelLocation == null) {
							//if the clicked claim was a subdivision, tell him he can't start a new subdivision here
							if(claim.parent != null) {
								GriefPreventionLight.sendMessage(player, TextMode.Err, Messages.ResizeFailOverlapSubdivision);
							}

							//otherwise start a new subdivision
							else {
								GriefPreventionLight.sendMessage(player, TextMode.Instr, Messages.SubdivisionStart);
								playerData.lastShovelLocation = clickedBlock.getLocation();
								playerData.claimSubdividing = claim;
							}
						}

						//otherwise, he's trying to finish creating a subdivision by setting the other boundary corner
						else {
							//if last shovel location was in a different world, assume the player is starting the create-claim workflow over
							if(!clickedBlock.getWorld().equals(playerData.lastShovelLocation.getWorld())) {
								playerData.lastShovelLocation = null;
								this.onPlayerInteract(event);
								return;
							}

							//try to create a new claim (will return null if this subdivision overlaps another)
							CreateClaimResult result = this.dataStore.createClaim(
									player.getWorld(),
									playerData.lastShovelLocation.getBlockX(), clickedBlock.getX(),
									playerData.lastShovelLocation.getBlockZ(), clickedBlock.getZ(),
									null,  //owner is not used for subdivisions
									playerData.claimSubdividing,
									null, player);

							//if it didn't succeed, tell the player why
							if(!result.succeeded) {
								GriefPreventionLight.sendMessage(player, TextMode.Err, Messages.CreateSubdivisionOverlap);

								Visualization visualization = Visualization.FromClaim(result.claim, VisualizationType.ErrorClaim, player.getLocation());

								// alert plugins of a visualization
								Bukkit.getPluginManager().callEvent(new VisualizationEvent(player, visualization, result.claim));

								Visualization.Apply(player, visualization);

								return;
							}

							//otherwise, advise him on the /trust command and show him his new subdivision
							else {
								GriefPreventionLight.sendMessage(player, TextMode.Success, Messages.SubdivisionSuccess);
								Visualization visualization = Visualization.FromClaim(result.claim, VisualizationType.Claim, player.getLocation());

								// alert plugins of a visualization
								Bukkit.getPluginManager().callEvent(new VisualizationEvent(player, visualization, result.claim));

								Visualization.Apply(player, visualization);
								playerData.lastShovelLocation = null;
								playerData.claimSubdividing = null;
							}
						}
					}

					//otherwise tell him he can't create a claim here, and show him the existing claim
					//also advise him to consider /abandonclaim or resizing the existing claim
					else {
						GriefPreventionLight.sendMessage(player, TextMode.Err, Messages.CreateClaimFailOverlap);
						Visualization visualization = Visualization.FromClaim(claim, VisualizationType.Claim, player.getLocation());

						// alert plugins of a visualization
						Bukkit.getPluginManager().callEvent(new VisualizationEvent(player, visualization, claim));

						Visualization.Apply(player, visualization);
					}
				}

				//otherwise tell the player he can't claim here because it's someone else's claim, and show him the claim
				else {
					GriefPreventionLight.sendMessage(player, TextMode.Err, Messages.CreateClaimFailOverlapOtherPlayer, claim.getOwnerName());
					Visualization visualization = Visualization.FromClaim(claim, VisualizationType.ErrorClaim, player.getLocation());

					// alert plugins of a visualization
					Bukkit.getPluginManager().callEvent(new VisualizationEvent(player, visualization, claim));

					Visualization.Apply(player, visualization);
				}

				return;
			}

			//otherwise, the player isn't in an existing claim!

			//if he hasn't already start a claim with a previous shovel action
			Location lastShovelLocation = playerData.lastShovelLocation;
			if(lastShovelLocation == null) {
				//if claims are not enabled in this world and it's not an administrative claim, display an error message and stop
				if(!instance.claimsEnabledForWorld(player.getWorld())) {
					GriefPreventionLight.sendMessage(player, TextMode.Err, Messages.ClaimsDisabledWorld);
					return;
				}

				//if he's at the claim count per player limit already and doesn't have permission to bypass, display an error message
				if(instance.config_claims_maxClaimsPerPlayer > 0 &&
						!player.hasPermission("griefprevention.overrideclaimcountlimit") &&
						playerData.getClaims().size() >= instance.config_claims_maxClaimsPerPlayer) {
					GriefPreventionLight.sendMessage(player, TextMode.Err, Messages.ClaimCreationFailedOverClaimCountLimit);
					return;
				}

				//remember it, and start him on the new claim
				playerData.lastShovelLocation = clickedBlock.getLocation();
				GriefPreventionLight.sendMessage(player, TextMode.Instr, Messages.ClaimStart);

				//show him where he's working
				Claim newClaim = new Claim(playerData.lastShovelLocation.getWorld(), new Coordinate(playerData.lastShovelLocation.getBlockX(), playerData.lastShovelLocation.getBlockZ()), new Coordinate(playerData.lastShovelLocation.getBlockX(), playerData.lastShovelLocation.getBlockZ()), null, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), null);
				Visualization visualization = Visualization.FromClaim(newClaim, VisualizationType.RestoreNature, player.getLocation());

				// alert plugins of a visualization
				Bukkit.getPluginManager().callEvent(new VisualizationEvent(player, visualization, newClaim));

				Visualization.Apply(player, visualization);
			}

			//otherwise, he's trying to finish creating a claim by setting the other boundary corner
			else {
				//if last shovel location was in a different world, assume the player is starting the create-claim workflow over
				if(!clickedBlock.getWorld().equals(lastShovelLocation.getWorld())) {
					playerData.lastShovelLocation = null;
					this.onPlayerInteract(event);
					return;
				}

				//apply minimum claim dimensions rule
				int newClaimWidth = Math.abs(playerData.lastShovelLocation.getBlockX() - clickedBlock.getX()) + 1;
				int newClaimHeight = Math.abs(playerData.lastShovelLocation.getBlockZ() - clickedBlock.getZ()) + 1;

				if(playerData.shovelMode != ShovelMode.Admin) {
					if(newClaimWidth < instance.config_claims_minWidth || newClaimHeight < instance.config_claims_minWidth) {
						//this IF block is a workaround for craftbukkit bug which fires two events for one interaction
						if(newClaimWidth != 1 && newClaimHeight != 1) {
							GriefPreventionLight.sendMessage(player, TextMode.Err, Messages.NewClaimTooNarrow, String.valueOf(instance.config_claims_minWidth));
						}
						return;
					}

					int newArea = newClaimWidth * newClaimHeight;
					if(newArea < instance.config_claims_minArea) {
						if(newArea != 1) {
							GriefPreventionLight.sendMessage(player, TextMode.Err, Messages.ResizeClaimInsufficientArea, String.valueOf(instance.config_claims_minArea));
						}

						return;
					}
				}

				//if not an administrative claim, verify the player has enough claim blocks for this new claim
				if(playerData.shovelMode != ShovelMode.Admin) {
					int newClaimArea = newClaimWidth * newClaimHeight;
					int remainingBlocks = playerData.getRemainingClaimBlocks();
					if(newClaimArea > remainingBlocks) {
						GriefPreventionLight.sendMessage(player, TextMode.Err, Messages.CreateClaimInsufficientBlocks, String.valueOf(newClaimArea - remainingBlocks));
						instance.dataStore.tryAdvertiseAdminAlternatives(player);
						return;
					}
				} else {
					playerID = null;
				}

				//try to create a new claim
				CreateClaimResult result = this.dataStore.createClaim(
						player.getWorld(),
						lastShovelLocation.getBlockX(), clickedBlock.getX(),
						lastShovelLocation.getBlockZ(), clickedBlock.getZ(),
						playerID,
						null, null,
						player);

				//if it didn't succeed, tell the player why
				if(!result.succeeded) {
					if(result.claim != null) {
						GriefPreventionLight.sendMessage(player, TextMode.Err, Messages.CreateClaimFailOverlapShort);

						Visualization visualization = Visualization.FromClaim(result.claim, VisualizationType.ErrorClaim, player.getLocation());

						// alert plugins of a visualization
						Bukkit.getPluginManager().callEvent(new VisualizationEvent(player, visualization, result.claim));

						Visualization.Apply(player, visualization);
					} else {
						GriefPreventionLight.sendMessage(player, TextMode.Err, Messages.CreateClaimFailOverlapRegion);
					}
				}

				//otherwise, advise him on the /trust command and show him his new claim
				else {
					GriefPreventionLight.sendMessage(player, TextMode.Success, Messages.CreateClaimSuccess);
					Visualization visualization = Visualization.FromClaim(result.claim, VisualizationType.Claim, player.getLocation());

					// alert plugins of a visualization
					Bukkit.getPluginManager().callEvent(new VisualizationEvent(player, visualization, result.claim));

					Visualization.Apply(player, visualization);
					playerData.lastShovelLocation = null;

					//if it's a big claim, tell the player about subdivisions
					if(!player.hasPermission("griefprevention.adminclaims") && result.claim.getArea() >= 1000) {
						GriefPreventionLight.sendMessage(player, TextMode.Info, Messages.BecomeMayor, 200L);
						GriefPreventionLight.sendMessage(player, TextMode.Instr, Messages.SubdivisionVideo2, 201L, DataStore.SUBDIVISION_VIDEO_URL);
					}
				}
			}
		}
	}

	// Stops an untrusted player from removing a book from a lectern
	@EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
	void onTakeBook(PlayerTakeLecternBookEvent event) {
		Player player = event.getPlayer();
		PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
		Claim claim = this.dataStore.getClaimAt(event.getLectern().getLocation(), playerData.lastClaim);
		if(claim != null) {
			playerData.lastClaim = claim;
			String noContainerReason = claim.allowContainers(player);
			if(noContainerReason != null) {
				event.setCancelled(true);
				player.closeInventory();
				GriefPreventionLight.sendMessage(player, TextMode.Err, noContainerReason);
			}
		}
	}

	//determines whether a block type is an inventory holder.  uses a caching strategy to save cpu time
	private final ConcurrentHashMap<Material, Boolean> inventoryHolderCache = new ConcurrentHashMap<>();

	private boolean isInventoryHolder(Block clickedBlock) {

		Material cacheKey = clickedBlock.getType();
		Boolean cachedValue = this.inventoryHolderCache.get(cacheKey);
		if(cachedValue != null) {
			return cachedValue;

		} else {
			boolean isHolder = clickedBlock.getState() instanceof InventoryHolder;
			this.inventoryHolderCache.put(cacheKey, isHolder);
			return isHolder;
		}
	}

	private boolean onLeftClickWatchList(Material material) {
		switch(material) {
			case OAK_BUTTON:
			case SPRUCE_BUTTON:
			case BIRCH_BUTTON:
			case JUNGLE_BUTTON:
			case ACACIA_BUTTON:
			case DARK_OAK_BUTTON:
			case STONE_BUTTON:
			case LEVER:
			case REPEATER:
			case CAKE:
			case DRAGON_EGG:
				return true;
			default:
				return false;
		}
	}

	static Block getTargetBlock(Player player) throws IllegalStateException {
		Location eye = player.getEyeLocation();
		Material eyeMaterial = eye.getBlock().getType();
		boolean passThroughWater = (eyeMaterial == Material.WATER);
		BlockIterator iterator = new BlockIterator(player.getLocation(), player.getEyeHeight(), 100);
		Block result = player.getLocation().getBlock().getRelative(BlockFace.UP);
		while(iterator.hasNext()) {
			result = iterator.next();
			Material type = result.getType();
			if(type != Material.AIR &&
					(!passThroughWater || type != Material.WATER) &&
					type != Material.GRASS &&
					type != Material.SNOW) return result;
		}

		return result;
	}
}
