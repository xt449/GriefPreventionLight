/*
    GriefPrevention Server Plugin for Minecraft
    Copyright (C) 2012 Ryan Hamshire

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

import com.github.xt449.griefpreventionlight.events.PreventBlockBreakEvent;
import com.github.xt449.griefpreventionlight.events.SaveTrappedPlayerEvent;
import com.github.xt449.griefpreventionlight.events.TrustChangedEvent;
import org.bukkit.*;
import org.bukkit.BanList.Type;
import org.bukkit.World.Environment;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GriefPreventionLight extends JavaPlugin {
	//for convenience, a reference to the instance of this plugin
	public static GriefPreventionLight instance;

	//for logging to the console and log file
	private static Logger log;

	//this handles data storage, like player and region data
	public DataStore dataStore;

	//this tracks item stacks expected to drop which will need protection
	ArrayList<PendingItemProtection> pendingItemWatchList = new ArrayList<>();

	//log entry manager for GP's custom log files
	CustomLogger customLogger;

	//configuration variables, loaded/saved from a config.yml

	//claim mode for each world
	public ConcurrentHashMap<World, ClaimsMode> config_claims_worldModes;

	public boolean config_claims_preventGlobalMonsterEggs; //whether monster eggs can be placed regardless of trust.
	public boolean config_claims_preventTheft;                        //whether containers and crafting blocks are protectable
	public boolean config_claims_protectCreatures;                    //whether claimed animals may be injured by players without permission
	public boolean config_claims_protectHorses;                        //whether horses on a claim should be protected by that claim's rules
	public boolean config_claims_protectDonkeys;                    //whether donkeys on a claim should be protected by that claim's rules
	public boolean config_claims_protectLlamas;                        //whether llamas on a claim should be protected by that claim's rules
	public boolean config_claims_preventButtonsSwitches;            //whether buttons and switches are protectable
	public boolean config_claims_lockWoodenDoors;                    //whether wooden doors should be locked by default (require /accesstrust)
	public boolean config_claims_lockTrapDoors;                        //whether trap doors should be locked by default (require /accesstrust)
	public boolean config_claims_lockFenceGates;                    //whether fence gates should be locked by default (require /accesstrust)
	public boolean config_claims_enderPearlsRequireAccessTrust;        //whether teleporting into a claim with a pearl requires access trust
	public boolean config_claims_raidTriggersRequireBuildTrust;      //whether raids are triggered by a player that doesn't have build permission in that claim
	public int config_claims_maxClaimsPerPlayer;                    //maximum number of claims per player
	public boolean config_claims_villagerTradingRequiresTrust;      //whether trading with a claimed villager requires permission

	public int config_claims_initialBlocks;                            //the number of claim blocks a new player starts with
	public double config_claims_abandonReturnRatio;                 //the portion of claim blocks returned to a player when a claim is abandoned
	public int config_claims_blocksAccruedPerHour_default;            //how many additional blocks players get each hour of play (can be zero) without any special permissions
	public int config_claims_maxAccruedBlocks_default;                //the limit on accrued blocks (over time) for players without any special permissions.  doesn't limit purchased or admin-gifted blocks
	public int config_claims_accruedIdleThreshold;                    //how far (in blocks) a player must move in order to not be considered afk/idle when determining accrued claim blocks
	public int config_claims_accruedIdlePercent;                    //how much percentage of claim block accruals should idle players get
	public int config_claims_maxDepth;                                //limit on how deep claims can go
	public int config_claims_expirationDays;                        //how many days of inactivity before a player loses his claims
	public int config_claims_expirationExemptionTotalBlocks;        //total claim blocks amount which will exempt a player from claim expiration
	public int config_claims_expirationExemptionBonusBlocks;        //bonus claim blocks amount which will exempt a player from claim expiration

	public int config_claims_automaticClaimsForNewPlayersRadius;    //how big automatic new player claims (when they place a chest) should be.  -1 to disable
	public int config_claims_automaticClaimsForNewPlayersRadiusMin; //how big automatic new player claims must be. 0 to disable
	public int config_claims_claimsExtendIntoGroundDistance;        //how far below the shoveled block a new claim will reach
	public int config_claims_minWidth;                                //minimum width for non-admin claims
	public int config_claims_minArea;                               //minimum area for non-admin claims

	public int config_claims_chestClaimExpirationDays;                //number of days of inactivity before an automatic chest claim will be deleted
	public int config_claims_unusedClaimExpirationDays;                //number of days of inactivity before an unused (nothing build) claim will be deleted
	public boolean config_claims_allowTrappedInAdminClaims;            //whether it should be allowed to use /trapped in adminclaims.

	public Material config_claims_investigationTool;                //which material will be used to investigate claims with a right click
	public Material config_claims_modificationTool;                    //which material will be used to create/resize claims with a right click

	public ArrayList<String> config_claims_commandsRequiringAccessTrust; //the list of slash commands requiring access trust when in a claim
	public boolean config_claims_supplyPlayerManual;                //whether to give new players a book with land claim help in it
	public int config_claims_manualDeliveryDelaySeconds;            //how long to wait before giving a book to a new player

	public boolean config_claims_firespreads;                        //whether fire will spread in claims
	public boolean config_claims_firedamages;                        //whether fire will damage in claims

	public boolean config_claims_lecternReadingRequiresAccessTrust;                    //reading lecterns requires access trust

	public boolean config_spam_enabled;                                //whether or not to monitor for spam
	public int config_spam_loginCooldownSeconds;                    //how long players must wait between logins.  combats login spam.
	public int config_spam_loginLogoutNotificationsPerMinute;        //how many login/logout notifications to show per minute (global, not per player)
	public ArrayList<String> config_spam_monitorSlashCommands;    //the list of slash commands monitored for spam
	public boolean config_spam_banOffenders;                        //whether or not to ban spammers automatically
	public String config_spam_banMessage;                            //message to show an automatically banned player
	public String config_spam_warningMessage;                        //message to show a player who is close to spam level
	public String config_spam_allowedIpAddresses;                    //IP addresses which will not be censored
	public int config_spam_deathMessageCooldownSeconds;                //cooldown period for death messages (per player) in seconds
	public int config_spam_logoutMessageDelaySeconds;               //delay before a logout message will be shown (only if the player stays offline that long)

	public boolean config_lockDeathDropsInPvpWorlds;                //whether players' dropped on death items are protected in pvp worlds
	public boolean config_lockDeathDropsInNonPvpWorlds;             //whether players' dropped on death items are protected in non-pvp worlds

	public int config_economy_claimBlocksMaxBonus;                  //max "bonus" blocks a player can buy.  set to zero for no limit.
	public double config_economy_claimBlocksPurchaseCost;            //cost to purchase a claim block.  set to zero to disable purchase.
	public double config_economy_claimBlocksSellValue;                //return on a sold claim block.  set to zero to disable sale.

	public boolean config_blockClaimExplosions;                     //whether explosions may destroy claimed blocks
	public boolean config_blockSurfaceCreeperExplosions;            //whether creeper explosions near or above the surface destroy blocks
	public boolean config_blockSurfaceOtherExplosions;                //whether non-creeper explosions near or above the surface destroy blocks
	public boolean config_blockSkyTrees;                            //whether players can build trees on platforms in the sky

	public boolean config_fireSpreads;                                //whether fire spreads outside of claims
	public boolean config_fireDestroys;                                //whether fire destroys blocks outside of claims

	public boolean config_whisperNotifications;                    //whether whispered messages will broadcast to administrators in game
	public boolean config_signNotifications;                        //whether sign content will broadcast to administrators in game
	public ArrayList<String> config_eavesdrop_whisperCommands;        //list of whisper commands to eavesdrop on

	public boolean config_smartBan;                                    //whether to ban accounts which very likely owned by a banned player

	public boolean config_endermenMoveBlocks;                        //whether or not endermen may move blocks around
	public boolean config_claims_ravagersBreakBlocks;                //whether or not ravagers may break blocks in claims
	public boolean config_silverfishBreakBlocks;                    //whether silverfish may break blocks
	public boolean config_creaturesTrampleCrops;                    //whether or not non-player entities may trample crops
	public boolean config_rabbitsEatCrops;                          //whether or not rabbits may eat crops
	public boolean config_zombiesBreakDoors;                        //whether or not hard-mode zombies may break down wooden doors

	public int config_ipLimit;                                      //how many players can share an IP address

	public boolean config_trollFilterEnabled;                       //whether to auto-mute new players who use banned words right after joining

	public HashMap<String, Integer> config_seaLevelOverride;        //override for sea level, because bukkit doesn't report the right value for all situations

	public boolean config_limitTreeGrowth;                          //whether trees should be prevented from growing into a claim from outside
	public PistonMode config_pistonMovement;                            //Setting for piston check options

	public boolean config_advanced_fixNegativeClaimblockAmounts;    //whether to attempt to fix negative claim block amounts (some addons cause/assume players can go into negative amounts)
	public int config_advanced_claim_expiration_check_rate;            //How often GP should check for expired claims, amount in seconds
	public int config_advanced_offlineplayer_cache_days;            //Cache players who have logged in within the last x number of days

	//custom log settings
	public int config_logs_daysToKeep;
	public boolean config_logs_socialEnabled;
	public boolean config_logs_suspiciousEnabled;
	public boolean config_logs_adminEnabled;
	public boolean config_logs_debugEnabled;
	public boolean config_logs_mutedChatEnabled;

	//ban management plugin interop settings
	public boolean config_ban_useCommand;
	public String config_ban_commandFormat;

	private String databaseUrl;
	private String databaseUserName;
	private String databasePassword;

	//how long to wait before deciding a player is staying online or staying offline, for notication messages
	public static final int NOTIFICATION_SECONDS = 20;

	//adds a server log entry
	public static synchronized void AddLogEntry(String entry, CustomLogEntryTypes customLogType, boolean excludeFromServerLogs) {
		if(customLogType != null && GriefPreventionLight.instance.customLogger != null) {
			GriefPreventionLight.instance.customLogger.AddEntry(entry, customLogType);
		}
		if(!excludeFromServerLogs) log.info(entry);
	}

	public static synchronized void AddLogEntry(String entry, CustomLogEntryTypes customLogType) {
		AddLogEntry(entry, customLogType, false);
	}

	public static synchronized void AddLogEntry(String entry) {
		AddLogEntry(entry, CustomLogEntryTypes.Debug);
	}

	//initializes well...   everything
	public void onEnable() {
		instance = this;
		log = instance.getLogger();

		this.loadConfig();

		this.customLogger = new CustomLogger();

		AddLogEntry("Finished loading configuration.");

		//when datastore initializes, it loads player and claim data, and posts some stats to the log
		if(this.databaseUrl.length() > 0) {
			try {
				DatabaseDataStore databaseStore = new DatabaseDataStore(this.databaseUrl, this.databaseUserName, this.databasePassword);

				if(FlatFileDataStore.hasData()) {
					GriefPreventionLight.AddLogEntry("There appears to be some data on the hard drive.  Migrating those data to the database...");
					FlatFileDataStore flatFileStore = new FlatFileDataStore();
					this.dataStore = flatFileStore;
					flatFileStore.migrateData(databaseStore);
					GriefPreventionLight.AddLogEntry("Data migration process complete.");
				}

				this.dataStore = databaseStore;
			} catch(Exception e) {
				GriefPreventionLight.AddLogEntry("Because there was a problem with the database, GriefPrevention will not function properly.  Either update the database config settings resolve the issue, or delete those lines from your config.yml so that GriefPrevention can use the file system to store data.");
				e.printStackTrace();
				this.getServer().getPluginManager().disablePlugin(this);
				return;
			}
		}

		//if not using the database because it's not configured or because there was a problem, use the file system to store data
		//this is the preferred method, as it's simpler than the database scenario
		if(this.dataStore == null) {
			File oldclaimdata = new File(getDataFolder(), "ClaimData");
			if(oldclaimdata.exists()) {
				if(!FlatFileDataStore.hasData()) {
					File claimdata = new File("plugins" + File.separator + "GriefPreventionData" + File.separator + "ClaimData");
					oldclaimdata.renameTo(claimdata);
					File oldplayerdata = new File(getDataFolder(), "PlayerData");
					File playerdata = new File("plugins" + File.separator + "GriefPreventionData" + File.separator + "PlayerData");
					oldplayerdata.renameTo(playerdata);
				}
			}
			try {
				this.dataStore = new FlatFileDataStore();
			} catch(Exception e) {
				GriefPreventionLight.AddLogEntry("Unable to initialize the file system data store.  Details:");
				GriefPreventionLight.AddLogEntry(e.getMessage());
				e.printStackTrace();
			}
		}

		String dataMode = (this.dataStore instanceof FlatFileDataStore) ? "(File Mode)" : "(Database Mode)";
		AddLogEntry("Finished loading data " + dataMode + ".");

		//unless claim block accrual is disabled, start the recurring per 10 minute event to give claim blocks to online players
		//20L ~ 1 second
		if(this.config_claims_blocksAccruedPerHour_default > 0) {
			DeliverClaimBlocksTask task = new DeliverClaimBlocksTask(null, this);
			this.getServer().getScheduler().scheduleSyncRepeatingTask(this, task, 20L * 60 * 10, 20L * 60 * 10);
		}

		//register for events
		PluginManager pluginManager = this.getServer().getPluginManager();

		//player events
		PlayerEventHandler playerEventHandler = new PlayerEventHandler(this.dataStore, this);
		pluginManager.registerEvents(playerEventHandler, this);

		//block events
		BlockEventHandler blockEventHandler = new BlockEventHandler(this.dataStore);
		pluginManager.registerEvents(blockEventHandler, this);

		//entity events
		EntityEventHandler entityEventHandler = new EntityEventHandler();
		pluginManager.registerEvents(entityEventHandler, this);

		//cache offline players
		OfflinePlayer[] offlinePlayers = this.getServer().getOfflinePlayers();
		CacheOfflinePlayerNamesThread namesThread = new CacheOfflinePlayerNamesThread(offlinePlayers, this.playerNameToIDMap);
		namesThread.setPriority(Thread.MIN_PRIORITY);
		namesThread.start();

		//load ignore lists for any already-online players
		@SuppressWarnings("unchecked")
		Collection<Player> players = (Collection<Player>) GriefPreventionLight.instance.getServer().getOnlinePlayers();
		for(Player player : players) {
			new IgnoreLoaderThread(player.getUniqueId(), this.dataStore.getPlayerData(player.getUniqueId()).ignoredPlayers).start();
		}

		AddLogEntry("Boot finished.");
	}

	private void loadConfig() {
		//load the config if it exists
		FileConfiguration config = YamlConfiguration.loadConfiguration(new File(DataStore.configFilePath));
		FileConfiguration outConfig = new YamlConfiguration();
		outConfig.options().header("Default values are perfect for most servers.  If you want to customize and have a question, look for the answer here first: http://dev.bukkit.org/bukkit-plugins/grief-prevention/pages/setup-and-configuration/");

		//read configuration settings (note defaults)

		//get (deprecated node) claims world names from the config file
		List<World> worlds = this.getServer().getWorlds();
		List<String> deprecated_claimsEnabledWorldNames = config.getStringList("GriefPrevention.Claims.Worlds");

		//validate that list
		for(int i = 0; i < deprecated_claimsEnabledWorldNames.size(); i++) {
			String worldName = deprecated_claimsEnabledWorldNames.get(i);
			World world = this.getServer().getWorld(worldName);
			if(world == null) {
				deprecated_claimsEnabledWorldNames.remove(i--);
			}
		}

		//decide claim mode for each world
		this.config_claims_worldModes = new ConcurrentHashMap<>();
		for(World world : worlds) {
			//is it specified in the config file?
			String configSetting = config.getString("GriefPrevention.Claims.Mode." + world.getName());
			if(configSetting != null) {
				ClaimsMode claimsMode = this.configStringToClaimsMode(configSetting);
				if(claimsMode != null) {
					this.config_claims_worldModes.put(world, claimsMode);
					continue;
				} else {
					GriefPreventionLight.AddLogEntry("Error: Invalid claim mode \"" + configSetting + "\".  Options are Survival, Creative, and Disabled.");
				}
			}

			//was it specified in a deprecated config node?
			if(deprecated_claimsEnabledWorldNames.contains(world.getName())) {
				this.config_claims_worldModes.put(world, ClaimsMode.Survival);
			}

			//does the world's name indicate its purpose?
			else if(world.getName().toLowerCase().contains("survival")) {
				this.config_claims_worldModes.put(world, ClaimsMode.Survival);
			}

			//decide a default based on server type and world type
			else if(world.getEnvironment() == Environment.NORMAL) {
				this.config_claims_worldModes.put(world, ClaimsMode.Survival);
			} else {
				this.config_claims_worldModes.put(world, ClaimsMode.Disabled);
			}

			//if the setting WOULD be disabled but this is a server upgrading from the old config format,
			//then default to survival mode for safety's sake (to protect any admin claims which may
			//have been created there)
			if(this.config_claims_worldModes.get(world) == ClaimsMode.Disabled &&
					deprecated_claimsEnabledWorldNames.size() > 0) {
				this.config_claims_worldModes.put(world, ClaimsMode.Survival);
			}
		}

		//sea level
		this.config_seaLevelOverride = new HashMap<>();
		for(World world : worlds) {
			int seaLevelOverride = config.getInt("GriefPrevention.SeaLevelOverrides." + world.getName(), -1);
			outConfig.set("GriefPrevention.SeaLevelOverrides." + world.getName(), seaLevelOverride);
			this.config_seaLevelOverride.put(world.getName(), seaLevelOverride);
		}

		this.config_claims_preventGlobalMonsterEggs = config.getBoolean("GriefPrevention.Claims.PreventGlobalMonsterEggs", true);
		this.config_claims_preventTheft = config.getBoolean("GriefPrevention.Claims.PreventTheft", true);
		this.config_claims_protectCreatures = config.getBoolean("GriefPrevention.Claims.ProtectCreatures", true);
		this.config_claims_protectHorses = config.getBoolean("GriefPrevention.Claims.ProtectHorses", true);
		this.config_claims_protectDonkeys = config.getBoolean("GriefPrevention.Claims.ProtectDonkeys", true);
		this.config_claims_protectLlamas = config.getBoolean("GriefPrevention.Claims.ProtectLlamas", true);
		this.config_claims_preventButtonsSwitches = config.getBoolean("GriefPrevention.Claims.PreventButtonsSwitches", true);
		this.config_claims_lockWoodenDoors = config.getBoolean("GriefPrevention.Claims.LockWoodenDoors", false);
		this.config_claims_lockTrapDoors = config.getBoolean("GriefPrevention.Claims.LockTrapDoors", false);
		this.config_claims_lockFenceGates = config.getBoolean("GriefPrevention.Claims.LockFenceGates", true);
		this.config_claims_enderPearlsRequireAccessTrust = config.getBoolean("GriefPrevention.Claims.EnderPearlsRequireAccessTrust", true);
		this.config_claims_raidTriggersRequireBuildTrust = config.getBoolean("GriefPrevention.Claims.RaidTriggersRequireBuildTrust", true);
		this.config_claims_initialBlocks = config.getInt("GriefPrevention.Claims.InitialBlocks", 100);
		this.config_claims_blocksAccruedPerHour_default = config.getInt("GriefPrevention.Claims.BlocksAccruedPerHour", 100);
		this.config_claims_blocksAccruedPerHour_default = config.getInt("GriefPrevention.Claims.Claim Blocks Accrued Per Hour.Default", config_claims_blocksAccruedPerHour_default);
		this.config_claims_maxAccruedBlocks_default = config.getInt("GriefPrevention.Claims.MaxAccruedBlocks", 2000);
		this.config_claims_maxAccruedBlocks_default = config.getInt("GriefPrevention.Claims.Max Accrued Claim Blocks.Default", this.config_claims_maxAccruedBlocks_default);
		this.config_claims_accruedIdleThreshold = config.getInt("GriefPrevention.Claims.AccruedIdleThreshold", 0);
		this.config_claims_accruedIdleThreshold = config.getInt("GriefPrevention.Claims.Accrued Idle Threshold", this.config_claims_accruedIdleThreshold);
		this.config_claims_accruedIdlePercent = config.getInt("GriefPrevention.Claims.AccruedIdlePercent", 0);
		this.config_claims_abandonReturnRatio = config.getDouble("GriefPrevention.Claims.AbandonReturnRatio", 1.0D);
		this.config_claims_automaticClaimsForNewPlayersRadius = config.getInt("GriefPrevention.Claims.AutomaticNewPlayerClaimsRadius", 4);
		this.config_claims_automaticClaimsForNewPlayersRadiusMin = Math.max(0, Math.min(this.config_claims_automaticClaimsForNewPlayersRadius,
				config.getInt("GriefPrevention.Claims.AutomaticNewPlayerClaimsRadiusMinimum", 0)));
		this.config_claims_claimsExtendIntoGroundDistance = Math.abs(config.getInt("GriefPrevention.Claims.ExtendIntoGroundDistance", 5));
		this.config_claims_minWidth = config.getInt("GriefPrevention.Claims.MinimumWidth", 5);
		this.config_claims_minArea = config.getInt("GriefPrevention.Claims.MinimumArea", 100);
		this.config_claims_maxDepth = config.getInt("GriefPrevention.Claims.MaximumDepth", 0);
		this.config_claims_chestClaimExpirationDays = config.getInt("GriefPrevention.Claims.Expiration.ChestClaimDays", 7);
		this.config_claims_unusedClaimExpirationDays = config.getInt("GriefPrevention.Claims.Expiration.UnusedClaimDays", 14);
		this.config_claims_expirationDays = config.getInt("GriefPrevention.Claims.Expiration.AllClaims.DaysInactive", 60);
		this.config_claims_expirationExemptionTotalBlocks = config.getInt("GriefPrevention.Claims.Expiration.AllClaims.ExceptWhenOwnerHasTotalClaimBlocks", 10000);
		this.config_claims_expirationExemptionBonusBlocks = config.getInt("GriefPrevention.Claims.Expiration.AllClaims.ExceptWhenOwnerHasBonusClaimBlocks", 5000);
		this.config_claims_allowTrappedInAdminClaims = config.getBoolean("GriefPrevention.Claims.AllowTrappedInAdminClaims", false);

		this.config_claims_maxClaimsPerPlayer = config.getInt("GriefPrevention.Claims.MaximumNumberOfClaimsPerPlayer", 0);
		this.config_claims_villagerTradingRequiresTrust = config.getBoolean("GriefPrevention.Claims.VillagerTradingRequiresPermission", true);
		String accessTrustSlashCommands = config.getString("GriefPrevention.Claims.CommandsRequiringAccessTrust", "/sethome");
		this.config_claims_supplyPlayerManual = config.getBoolean("GriefPrevention.Claims.DeliverManuals", true);
		this.config_claims_manualDeliveryDelaySeconds = config.getInt("GriefPrevention.Claims.ManualDeliveryDelaySeconds", 30);
		this.config_claims_ravagersBreakBlocks = config.getBoolean("GriefPrevention.Claims.RavagersBreakBlocks", true);

		this.config_claims_firespreads = config.getBoolean("GriefPrevention.Claims.FireSpreadsInClaims", false);
		this.config_claims_firedamages = config.getBoolean("GriefPrevention.Claims.FireDamagesInClaims", false);
		this.config_claims_lecternReadingRequiresAccessTrust = config.getBoolean("GriefPrevention.Claims.LecternReadingRequiresAccessTrust", true);

		this.config_spam_enabled = config.getBoolean("GriefPrevention.Spam.Enabled", true);
		this.config_spam_loginCooldownSeconds = config.getInt("GriefPrevention.Spam.LoginCooldownSeconds", 60);
		this.config_spam_loginLogoutNotificationsPerMinute = config.getInt("GriefPrevention.Spam.LoginLogoutNotificationsPerMinute", 5);
		this.config_spam_warningMessage = config.getString("GriefPrevention.Spam.WarningMessage", "Please reduce your noise level.  Spammers will be banned.");
		this.config_spam_allowedIpAddresses = config.getString("GriefPrevention.Spam.AllowedIpAddresses", "1.2.3.4; 5.6.7.8");
		this.config_spam_banOffenders = config.getBoolean("GriefPrevention.Spam.BanOffenders", true);
		this.config_spam_banMessage = config.getString("GriefPrevention.Spam.BanMessage", "Banned for spam.");
		String slashCommandsToMonitor = config.getString("GriefPrevention.Spam.MonitorSlashCommands", "/me;/global;/local");
		slashCommandsToMonitor = config.getString("GriefPrevention.Spam.ChatSlashCommands", slashCommandsToMonitor);
		this.config_spam_logoutMessageDelaySeconds = config.getInt("GriefPrevention.Spam.Logout Message Delay In Seconds", 0);

		this.config_economy_claimBlocksMaxBonus = config.getInt("GriefPrevention.Economy.ClaimBlocksMaxBonus", 0);
		this.config_economy_claimBlocksPurchaseCost = config.getDouble("GriefPrevention.Economy.ClaimBlocksPurchaseCost", 0);
		this.config_economy_claimBlocksSellValue = config.getDouble("GriefPrevention.Economy.ClaimBlocksSellValue", 0);

		this.config_lockDeathDropsInPvpWorlds = config.getBoolean("GriefPrevention.ProtectItemsDroppedOnDeath.PvPWorlds", false);
		this.config_lockDeathDropsInNonPvpWorlds = config.getBoolean("GriefPrevention.ProtectItemsDroppedOnDeath.NonPvPWorlds", true);

		this.config_blockClaimExplosions = config.getBoolean("GriefPrevention.BlockLandClaimExplosions", true);
		this.config_blockSurfaceCreeperExplosions = config.getBoolean("GriefPrevention.BlockSurfaceCreeperExplosions", true);
		this.config_blockSurfaceOtherExplosions = config.getBoolean("GriefPrevention.BlockSurfaceOtherExplosions", true);
		this.config_blockSkyTrees = config.getBoolean("GriefPrevention.LimitSkyTrees", true);
		this.config_limitTreeGrowth = config.getBoolean("GriefPrevention.LimitTreeGrowth", false);
		this.config_pistonMovement = PistonMode.of(config.getString("GriefPrevention.PistonMovement", "CLAIMS_ONLY"));
		if(config.isBoolean("GriefPrevention.LimitPistonsToLandClaims") && !config.getBoolean("GriefPrevention.LimitPistonsToLandClaims"))
			this.config_pistonMovement = PistonMode.EVERYWHERE_SIMPLE;
		if(config.isBoolean("GriefPrevention.CheckPistonMovement") && !config.getBoolean("GriefPrevention.CheckPistonMovement"))
			this.config_pistonMovement = PistonMode.IGNORED;

		this.config_fireSpreads = config.getBoolean("GriefPrevention.FireSpreads", false);
		this.config_fireDestroys = config.getBoolean("GriefPrevention.FireDestroys", false);

		this.config_whisperNotifications = config.getBoolean("GriefPrevention.AdminsGetWhispers", true);
		this.config_signNotifications = config.getBoolean("GriefPrevention.AdminsGetSignNotifications", true);
		String whisperCommandsToMonitor = config.getString("GriefPrevention.WhisperCommands", "/tell;/pm;/r;/whisper;/msg");
		whisperCommandsToMonitor = config.getString("GriefPrevention.Spam.WhisperSlashCommands", whisperCommandsToMonitor);

		this.config_smartBan = config.getBoolean("GriefPrevention.SmartBan", true);
		this.config_trollFilterEnabled = config.getBoolean("GriefPrevention.Mute New Players Using Banned Words", true);
		this.config_ipLimit = config.getInt("GriefPrevention.MaxPlayersPerIpAddress", 3);

		this.config_endermenMoveBlocks = config.getBoolean("GriefPrevention.EndermenMoveBlocks", false);
		this.config_silverfishBreakBlocks = config.getBoolean("GriefPrevention.SilverfishBreakBlocks", false);
		this.config_creaturesTrampleCrops = config.getBoolean("GriefPrevention.CreaturesTrampleCrops", false);
		this.config_rabbitsEatCrops = config.getBoolean("GriefPrevention.RabbitsEatCrops", true);
		this.config_zombiesBreakDoors = config.getBoolean("GriefPrevention.HardModeZombiesBreakDoors", false);
		this.config_ban_useCommand = config.getBoolean("GriefPrevention.UseBanCommand", false);
		this.config_ban_commandFormat = config.getString("GriefPrevention.BanCommandPattern", "ban %name% %reason%");

		//default for claim investigation tool
		String investigationToolMaterialName = Material.STICK.name();

		//get investigation tool from config
		investigationToolMaterialName = config.getString("GriefPrevention.Claims.InvestigationTool", investigationToolMaterialName);

		//validate investigation tool
		this.config_claims_investigationTool = Material.getMaterial(investigationToolMaterialName);
		if(this.config_claims_investigationTool == null) {
			GriefPreventionLight.AddLogEntry("ERROR: Material " + investigationToolMaterialName + " not found.  Defaulting to the stick.  Please update your config.yml.");
			this.config_claims_investigationTool = Material.STICK;
		}

		//default for claim creation/modification tool
		String modificationToolMaterialName = Material.GOLDEN_SHOVEL.name();

		//get modification tool from config
		modificationToolMaterialName = config.getString("GriefPrevention.Claims.ModificationTool", modificationToolMaterialName);

		//validate modification tool
		this.config_claims_modificationTool = Material.getMaterial(modificationToolMaterialName);
		if(this.config_claims_modificationTool == null) {
			GriefPreventionLight.AddLogEntry("ERROR: Material " + modificationToolMaterialName + " not found.  Defaulting to the golden shovel.  Please update your config.yml.");
			this.config_claims_modificationTool = Material.GOLDEN_SHOVEL;
		}

		//optional database settings
		this.databaseUrl = config.getString("GriefPrevention.Database.URL", "");
		this.databaseUserName = config.getString("GriefPrevention.Database.UserName", "");
		this.databasePassword = config.getString("GriefPrevention.Database.Password", "");

		this.config_advanced_fixNegativeClaimblockAmounts = config.getBoolean("GriefPrevention.Advanced.fixNegativeClaimblockAmounts", true);
		this.config_advanced_claim_expiration_check_rate = config.getInt("GriefPrevention.Advanced.ClaimExpirationCheckRate", 60);
		this.config_advanced_offlineplayer_cache_days = config.getInt("GriefPrevention.Advanced.OfflinePlayer_cache_days", 90);

		//custom logger settings
		this.config_logs_daysToKeep = config.getInt("GriefPrevention.Abridged Logs.Days To Keep", 7);
		this.config_logs_socialEnabled = config.getBoolean("GriefPrevention.Abridged Logs.Included Entry Types.Social Activity", true);
		this.config_logs_suspiciousEnabled = config.getBoolean("GriefPrevention.Abridged Logs.Included Entry Types.Suspicious Activity", true);
		this.config_logs_adminEnabled = config.getBoolean("GriefPrevention.Abridged Logs.Included Entry Types.Administrative Activity", false);
		this.config_logs_debugEnabled = config.getBoolean("GriefPrevention.Abridged Logs.Included Entry Types.Debug", false);
		this.config_logs_mutedChatEnabled = config.getBoolean("GriefPrevention.Abridged Logs.Included Entry Types.Muted Chat Messages", false);

		//claims mode by world
		for(World world : this.config_claims_worldModes.keySet()) {
			outConfig.set(
					"GriefPrevention.Claims.Mode." + world.getName(),
					this.config_claims_worldModes.get(world).name());
		}


		outConfig.set("GriefPrevention.Claims.PreventGlobalMonsterEggs", this.config_claims_preventGlobalMonsterEggs);
		outConfig.set("GriefPrevention.Claims.PreventTheft", this.config_claims_preventTheft);
		outConfig.set("GriefPrevention.Claims.ProtectCreatures", this.config_claims_protectCreatures);
		outConfig.set("GriefPrevention.Claims.PreventButtonsSwitches", this.config_claims_preventButtonsSwitches);
		outConfig.set("GriefPrevention.Claims.LockWoodenDoors", this.config_claims_lockWoodenDoors);
		outConfig.set("GriefPrevention.Claims.LockTrapDoors", this.config_claims_lockTrapDoors);
		outConfig.set("GriefPrevention.Claims.LockFenceGates", this.config_claims_lockFenceGates);
		outConfig.set("GriefPrevention.Claims.EnderPearlsRequireAccessTrust", this.config_claims_enderPearlsRequireAccessTrust);
		outConfig.set("GriefPrevention.Claims.RaidTriggersRequireBuildTrust", this.config_claims_raidTriggersRequireBuildTrust);
		outConfig.set("GriefPrevention.Claims.ProtectHorses", this.config_claims_protectHorses);
		outConfig.set("GriefPrevention.Claims.ProtectDonkeys", this.config_claims_protectDonkeys);
		outConfig.set("GriefPrevention.Claims.ProtectLlamas", this.config_claims_protectLlamas);
		outConfig.set("GriefPrevention.Claims.InitialBlocks", this.config_claims_initialBlocks);
		outConfig.set("GriefPrevention.Claims.Claim Blocks Accrued Per Hour.Default", this.config_claims_blocksAccruedPerHour_default);
		outConfig.set("GriefPrevention.Claims.Max Accrued Claim Blocks.Default", this.config_claims_maxAccruedBlocks_default);
		outConfig.set("GriefPrevention.Claims.Accrued Idle Threshold", this.config_claims_accruedIdleThreshold);
		outConfig.set("GriefPrevention.Claims.AccruedIdlePercent", this.config_claims_accruedIdlePercent);
		outConfig.set("GriefPrevention.Claims.AbandonReturnRatio", this.config_claims_abandonReturnRatio);
		outConfig.set("GriefPrevention.Claims.AutomaticNewPlayerClaimsRadius", this.config_claims_automaticClaimsForNewPlayersRadius);
		outConfig.set("GriefPrevention.Claims.AutomaticNewPlayerClaimsRadiusMinimum", this.config_claims_automaticClaimsForNewPlayersRadiusMin);
		outConfig.set("GriefPrevention.Claims.ExtendIntoGroundDistance", this.config_claims_claimsExtendIntoGroundDistance);
		outConfig.set("GriefPrevention.Claims.MinimumWidth", this.config_claims_minWidth);
		outConfig.set("GriefPrevention.Claims.MinimumArea", this.config_claims_minArea);
		outConfig.set("GriefPrevention.Claims.MaximumDepth", this.config_claims_maxDepth);
		outConfig.set("GriefPrevention.Claims.InvestigationTool", this.config_claims_investigationTool.name());
		outConfig.set("GriefPrevention.Claims.ModificationTool", this.config_claims_modificationTool.name());
		outConfig.set("GriefPrevention.Claims.Expiration.ChestClaimDays", this.config_claims_chestClaimExpirationDays);
		outConfig.set("GriefPrevention.Claims.Expiration.UnusedClaimDays", this.config_claims_unusedClaimExpirationDays);
		outConfig.set("GriefPrevention.Claims.Expiration.AllClaims.DaysInactive", this.config_claims_expirationDays);
		outConfig.set("GriefPrevention.Claims.Expiration.AllClaims.ExceptWhenOwnerHasTotalClaimBlocks", this.config_claims_expirationExemptionTotalBlocks);
		outConfig.set("GriefPrevention.Claims.Expiration.AllClaims.ExceptWhenOwnerHasBonusClaimBlocks", this.config_claims_expirationExemptionBonusBlocks);
		outConfig.set("GriefPrevention.Claims.AllowTrappedInAdminClaims", this.config_claims_allowTrappedInAdminClaims);
		outConfig.set("GriefPrevention.Claims.MaximumNumberOfClaimsPerPlayer", this.config_claims_maxClaimsPerPlayer);
		outConfig.set("GriefPrevention.Claims.VillagerTradingRequiresPermission", this.config_claims_villagerTradingRequiresTrust);
		outConfig.set("GriefPrevention.Claims.CommandsRequiringAccessTrust", accessTrustSlashCommands);
		outConfig.set("GriefPrevention.Claims.DeliverManuals", config_claims_supplyPlayerManual);
		outConfig.set("GriefPrevention.Claims.ManualDeliveryDelaySeconds", config_claims_manualDeliveryDelaySeconds);
		outConfig.set("GriefPrevention.Claims.RavagersBreakBlocks", config_claims_ravagersBreakBlocks);

		outConfig.set("GriefPrevention.Claims.FireSpreadsInClaims", config_claims_firespreads);
		outConfig.set("GriefPrevention.Claims.FireDamagesInClaims", config_claims_firedamages);
		outConfig.set("GriefPrevention.Claims.LecternReadingRequiresAccessTrust", config_claims_lecternReadingRequiresAccessTrust);

		outConfig.set("GriefPrevention.Spam.Enabled", this.config_spam_enabled);
		outConfig.set("GriefPrevention.Spam.LoginCooldownSeconds", this.config_spam_loginCooldownSeconds);
		outConfig.set("GriefPrevention.Spam.LoginLogoutNotificationsPerMinute", this.config_spam_loginLogoutNotificationsPerMinute);
		outConfig.set("GriefPrevention.Spam.ChatSlashCommands", slashCommandsToMonitor);
		outConfig.set("GriefPrevention.Spam.WhisperSlashCommands", whisperCommandsToMonitor);
		outConfig.set("GriefPrevention.Spam.WarningMessage", this.config_spam_warningMessage);
		outConfig.set("GriefPrevention.Spam.BanOffenders", this.config_spam_banOffenders);
		outConfig.set("GriefPrevention.Spam.BanMessage", this.config_spam_banMessage);
		outConfig.set("GriefPrevention.Spam.AllowedIpAddresses", this.config_spam_allowedIpAddresses);
		outConfig.set("GriefPrevention.Spam.Logout Message Delay In Seconds", this.config_spam_logoutMessageDelaySeconds);

		outConfig.set("GriefPrevention.Economy.ClaimBlocksMaxBonus", this.config_economy_claimBlocksMaxBonus);
		outConfig.set("GriefPrevention.Economy.ClaimBlocksPurchaseCost", this.config_economy_claimBlocksPurchaseCost);
		outConfig.set("GriefPrevention.Economy.ClaimBlocksSellValue", this.config_economy_claimBlocksSellValue);

		outConfig.set("GriefPrevention.ProtectItemsDroppedOnDeath.PvPWorlds", this.config_lockDeathDropsInPvpWorlds);
		outConfig.set("GriefPrevention.ProtectItemsDroppedOnDeath.NonPvPWorlds", this.config_lockDeathDropsInNonPvpWorlds);

		outConfig.set("GriefPrevention.BlockLandClaimExplosions", this.config_blockClaimExplosions);
		outConfig.set("GriefPrevention.BlockSurfaceCreeperExplosions", this.config_blockSurfaceCreeperExplosions);
		outConfig.set("GriefPrevention.BlockSurfaceOtherExplosions", this.config_blockSurfaceOtherExplosions);
		outConfig.set("GriefPrevention.LimitSkyTrees", this.config_blockSkyTrees);
		outConfig.set("GriefPrevention.LimitTreeGrowth", this.config_limitTreeGrowth);
		outConfig.set("GriefPrevention.PistonMovement", this.config_pistonMovement.name());
		outConfig.set("GriefPrevention.CheckPistonMovement", null);
		outConfig.set("GriefPrevention.LimitPistonsToLandClaims", null);

		outConfig.set("GriefPrevention.FireSpreads", this.config_fireSpreads);
		outConfig.set("GriefPrevention.FireDestroys", this.config_fireDestroys);

		outConfig.set("GriefPrevention.AdminsGetWhispers", this.config_whisperNotifications);
		outConfig.set("GriefPrevention.AdminsGetSignNotifications", this.config_signNotifications);

		outConfig.set("GriefPrevention.SmartBan", this.config_smartBan);
		outConfig.set("GriefPrevention.Mute New Players Using Banned Words", this.config_trollFilterEnabled);
		outConfig.set("GriefPrevention.MaxPlayersPerIpAddress", this.config_ipLimit);

		outConfig.set("GriefPrevention.EndermenMoveBlocks", this.config_endermenMoveBlocks);
		outConfig.set("GriefPrevention.SilverfishBreakBlocks", this.config_silverfishBreakBlocks);
		outConfig.set("GriefPrevention.CreaturesTrampleCrops", this.config_creaturesTrampleCrops);
		outConfig.set("GriefPrevention.RabbitsEatCrops", this.config_rabbitsEatCrops);
		outConfig.set("GriefPrevention.HardModeZombiesBreakDoors", this.config_zombiesBreakDoors);

		outConfig.set("GriefPrevention.Database.URL", this.databaseUrl);
		outConfig.set("GriefPrevention.Database.UserName", this.databaseUserName);
		outConfig.set("GriefPrevention.Database.Password", this.databasePassword);

		outConfig.set("GriefPrevention.UseBanCommand", this.config_ban_useCommand);
		outConfig.set("GriefPrevention.BanCommandPattern", this.config_ban_commandFormat);

		outConfig.set("GriefPrevention.Advanced.fixNegativeClaimblockAmounts", this.config_advanced_fixNegativeClaimblockAmounts);
		outConfig.set("GriefPrevention.Advanced.ClaimExpirationCheckRate", this.config_advanced_claim_expiration_check_rate);
		outConfig.set("GriefPrevention.Advanced.OfflinePlayer_cache_days", this.config_advanced_offlineplayer_cache_days);

		//custom logger settings
		outConfig.set("GriefPrevention.Abridged Logs.Days To Keep", this.config_logs_daysToKeep);
		outConfig.set("GriefPrevention.Abridged Logs.Included Entry Types.Social Activity", this.config_logs_socialEnabled);
		outConfig.set("GriefPrevention.Abridged Logs.Included Entry Types.Suspicious Activity", this.config_logs_suspiciousEnabled);
		outConfig.set("GriefPrevention.Abridged Logs.Included Entry Types.Administrative Activity", this.config_logs_adminEnabled);
		outConfig.set("GriefPrevention.Abridged Logs.Included Entry Types.Debug", this.config_logs_debugEnabled);
		outConfig.set("GriefPrevention.Abridged Logs.Included Entry Types.Muted Chat Messages", this.config_logs_mutedChatEnabled);

		try {
			outConfig.save(DataStore.configFilePath);
		} catch(IOException exception) {
			AddLogEntry("Unable to write to the configuration file at \"" + DataStore.configFilePath + "\"");
		}

		//try to parse the list of commands requiring access trust in land claims
		this.config_claims_commandsRequiringAccessTrust = new ArrayList<>();
		String[] commands = accessTrustSlashCommands.split(";");
		for(String command : commands) {
			if(!command.isEmpty()) {
				this.config_claims_commandsRequiringAccessTrust.add(command.trim().toLowerCase());
			}
		}

		//try to parse the list of commands which should be monitored for spam
		this.config_spam_monitorSlashCommands = new ArrayList<>();
		commands = slashCommandsToMonitor.split(";");
		for(String command : commands) {
			this.config_spam_monitorSlashCommands.add(command.trim().toLowerCase());
		}

		//try to parse the list of commands which should be included in eavesdropping
		this.config_eavesdrop_whisperCommands = new ArrayList<>();
		commands = whisperCommandsToMonitor.split(";");
		for(String command : commands) {
			this.config_eavesdrop_whisperCommands.add(command.trim().toLowerCase());
		}
	}

	private ClaimsMode configStringToClaimsMode(String configSetting) {
		if(configSetting.equalsIgnoreCase("Survival")) {
			return ClaimsMode.Survival;
		} else if(configSetting.equalsIgnoreCase("Disabled")) {
			return ClaimsMode.Disabled;
		} else if(configSetting.equalsIgnoreCase("SurvivalRequiringClaims")) {
			return ClaimsMode.SurvivalRequiringClaims;
		} else {
			return null;
		}
	}

	//handles slash commands
	public boolean onCommand(CommandSender sender, Command cmd, String commandLabel, String[] args) {

		Player player = null;
		if(sender instanceof Player) {
			player = (Player) sender;
		}

		//claim
		if(cmd.getName().equalsIgnoreCase("claim") && player != null) {
			if(!GriefPreventionLight.instance.claimsEnabledForWorld(player.getWorld())) {
				sendMessage(player, TextMode.Err, Messages.ClaimsDisabledWorld);
				return true;
			}

			PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());

			//if he's at the claim count per player limit already and doesn't have permission to bypass, display an error message
			if(GriefPreventionLight.instance.config_claims_maxClaimsPerPlayer > 0 &&
					!player.hasPermission("griefprevention.overrideclaimcountlimit") &&
					playerData.getClaims().size() >= GriefPreventionLight.instance.config_claims_maxClaimsPerPlayer) {
				sendMessage(player, TextMode.Err, Messages.ClaimCreationFailedOverClaimCountLimit);
				return true;
			}

			//default is chest claim radius, unless -1
			int radius = GriefPreventionLight.instance.config_claims_automaticClaimsForNewPlayersRadius;
			if(radius < 0) radius = (int) Math.ceil(Math.sqrt(GriefPreventionLight.instance.config_claims_minArea) / 2);

			//if player has any claims, respect claim minimum size setting
			if(playerData.getClaims().size() > 0) {
				//if player has exactly one land claim, this requires the claim modification tool to be in hand (or creative mode player)
				if(playerData.getClaims().size() == 1 && player.getGameMode() != GameMode.CREATIVE && player.getItemInHand().getType() != GriefPreventionLight.instance.config_claims_modificationTool) {
					sendMessage(player, TextMode.Err, Messages.MustHoldModificationToolForThat);
					return true;
				}

				radius = (int) Math.ceil(Math.sqrt(GriefPreventionLight.instance.config_claims_minArea) / 2);
			}

			//allow for specifying the radius
			if(args.length > 0) {
				if(playerData.getClaims().size() < 2 && player.getGameMode() != GameMode.CREATIVE && player.getInventory().getItemInMainHand().getType() != GriefPreventionLight.instance.config_claims_modificationTool) {
					sendMessage(player, TextMode.Err, Messages.RadiusRequiresGoldenShovel);
					return true;
				}

				int specifiedRadius;
				try {
					specifiedRadius = Integer.parseInt(args[0]);
				} catch(NumberFormatException e) {
					return false;
				}

				if(specifiedRadius < radius) {
					sendMessage(player, TextMode.Err, Messages.MinimumRadius, String.valueOf(radius));
					return true;
				} else {
					radius = specifiedRadius;
				}
			}

			if(radius < 0) radius = 0;

			Location lc = player.getLocation().add(-radius, 0, -radius);
			Location gc = player.getLocation().add(radius, 0, radius);

			//player must have sufficient unused claim blocks
			int area = Math.abs((gc.getBlockX() - lc.getBlockX() + 1) * (gc.getBlockZ() - lc.getBlockZ() + 1));
			int remaining = playerData.getRemainingClaimBlocks();
			if(remaining < area) {
				sendMessage(player, TextMode.Err, Messages.CreateClaimInsufficientBlocks, String.valueOf(area - remaining));
				GriefPreventionLight.instance.dataStore.tryAdvertiseAdminAlternatives(player);
				return true;
			}

			CreateClaimResult result = this.dataStore.createClaim(lc.getWorld(),
					lc.getBlockX(), gc.getBlockX(),
					lc.getBlockZ(), gc.getBlockZ(),
					player.getUniqueId(), null, null, player);
			if(!result.succeeded) {
				if(result.claim != null) {
					sendMessage(player, TextMode.Err, Messages.CreateClaimFailOverlapShort);

					Visualization visualization = Visualization.FromClaim(result.claim, VisualizationType.ErrorClaim, player.getLocation());
					Visualization.Apply(player, visualization);
				} else {
					sendMessage(player, TextMode.Err, Messages.CreateClaimFailOverlapRegion);
				}
			} else {
				sendMessage(player, TextMode.Success, Messages.CreateClaimSuccess);

				//link to a video demo of land claiming, based on world type
				if(GriefPreventionLight.instance.claimsEnabledForWorld(player.getLocation().getWorld())) {
					sendMessage(player, TextMode.Instr, Messages.SurvivalBasicsVideo2, DataStore.SURVIVAL_VIDEO_URL);
				}
				Visualization visualization = Visualization.FromClaim(result.claim, VisualizationType.Claim, player.getLocation());
				Visualization.Apply(player, visualization);
				playerData.claimResizing = null;
				playerData.lastShovelLocation = null;
			}

			return true;
		}

		//extendclaim
		if(cmd.getName().equalsIgnoreCase("extendclaim") && player != null) {
			if(args.length < 1) {
				//link to a video demo of land claiming, based on world type
				if(GriefPreventionLight.instance.claimsEnabledForWorld(player.getLocation().getWorld())) {
					sendMessage(player, TextMode.Instr, Messages.SurvivalBasicsVideo2, DataStore.SURVIVAL_VIDEO_URL);
				}
				return false;
			}

			int amount;
			try {
				amount = Integer.parseInt(args[0]);
			} catch(NumberFormatException e) {
				//link to a video demo of land claiming, based on world type
				if(GriefPreventionLight.instance.claimsEnabledForWorld(player.getLocation().getWorld())) {
					sendMessage(player, TextMode.Instr, Messages.SurvivalBasicsVideo2, DataStore.SURVIVAL_VIDEO_URL);
				}
				return false;
			}

			//requires claim modification tool in hand
			if(player.getGameMode() != GameMode.CREATIVE && player.getInventory().getItemInMainHand().getType() != GriefPreventionLight.instance.config_claims_modificationTool) {
				sendMessage(player, TextMode.Err, Messages.MustHoldModificationToolForThat);
				return true;
			}

			//must be standing in a land claim
			PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
			Claim claim = this.dataStore.getClaimAt(player.getLocation(), playerData.lastClaim);
			if(claim == null) {
				sendMessage(player, TextMode.Err, Messages.StandInClaimToResize);
				return true;
			}

			//must have permission to edit the land claim you're in
			String errorMessage = claim.allowEdit(player);
			if(errorMessage != null) {
				sendMessage(player, TextMode.Err, Messages.NotYourClaim);
				return true;
			}

			//determine new corner coordinates
			org.bukkit.util.Vector direction = player.getLocation().getDirection();

			int newx1 = claim.lesserBoundaryCorner.x;
			int newz1 = claim.lesserBoundaryCorner.z;

			int newx2 = claim.greaterBoundaryCorner.x;
			int newz2 = claim.greaterBoundaryCorner.z;

			//if changing Z only
			if(Math.abs(direction.getX()) < .3) {
				if(direction.getZ() > 0) {
					newz2 += amount;  //north
				} else {
					newz1 -= amount;  //south
				}
			}

			//if changing X only
			else if(Math.abs(direction.getZ()) < .3) {
				if(direction.getX() > 0) {
					newx2 += amount;  //east
				} else {
					newx1 -= amount;  //west
				}
			}

			//diagonals
			else {
				if(direction.getX() > 0) {
					newx2 += amount;
				} else {
					newx1 -= amount;
				}

				if(direction.getZ() > 0) {
					newz2 += amount;
				} else {
					newz1 -= amount;
				}
			}

			//attempt resize
			playerData.claimResizing = claim;
			this.dataStore.resizeClaimWithChecks(player, playerData, newx1, newx2, newz1, newz2);
			playerData.claimResizing = null;

			return true;
		}

		//abandonclaim
		if(cmd.getName().equalsIgnoreCase("abandonclaim") && player != null) {
			return this.abandonClaimHandler(player, false);
		}

		//abandontoplevelclaim
		if(cmd.getName().equalsIgnoreCase("abandontoplevelclaim") && player != null) {
			return this.abandonClaimHandler(player, true);
		}

		//ignoreclaims
		if(cmd.getName().equalsIgnoreCase("ignoreclaims") && player != null) {
			PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());

			playerData.ignoreClaims = !playerData.ignoreClaims;

			//toggle ignore claims mode on or off
			if(!playerData.ignoreClaims) {
				sendMessage(player, TextMode.Success, Messages.RespectingClaims);
			} else {
				sendMessage(player, TextMode.Success, Messages.IgnoringClaims);
			}

			return true;
		}

		//abandonallclaims
		else if(cmd.getName().equalsIgnoreCase("abandonallclaims") && player != null) {
			if(args.length > 1) return false;

			if(args.length != 1 || !"confirm".equalsIgnoreCase(args[0])) {
				sendMessage(player, TextMode.Err, Messages.ConfirmAbandonAllClaims);
				return true;
			}

			//count claims
			PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
			int originalClaimCount = playerData.getClaims().size();

			//check count
			if(originalClaimCount == 0) {
				sendMessage(player, TextMode.Err, Messages.YouHaveNoClaims);
				return true;
			}

			if(this.config_claims_abandonReturnRatio != 1.0D) {
				//adjust claim blocks
				for(Claim claim : playerData.getClaims()) {
					playerData.setAccruedClaimBlocks(playerData.getAccruedClaimBlocks() - (int) Math.ceil((claim.getArea() * (1 - this.config_claims_abandonReturnRatio))));
				}
			}


			//delete them
			this.dataStore.deleteClaimsForPlayer(player.getUniqueId(), false);

			//inform the player
			int remainingBlocks = playerData.getRemainingClaimBlocks();
			sendMessage(player, TextMode.Success, Messages.SuccessfulAbandon, String.valueOf(remainingBlocks));

			//revert any current visualization
			Visualization.Revert(player);

			return true;
		}

		//trust <player>
		else if(cmd.getName().equalsIgnoreCase("trust") && player != null) {
			//requires exactly one parameter, the other player's name
			if(args.length != 1) return false;

			//most trust commands use this helper method, it keeps them consistent
			this.handleTrustCommand(player, ClaimPermission.Build, args[0]);

			return true;
		}

		//transferclaim <player>
		else if(cmd.getName().equalsIgnoreCase("transferclaim") && player != null) {
			//which claim is the user in?
			Claim claim = this.dataStore.getClaimAt(player.getLocation(), null);
			if(claim == null) {
				sendMessage(player, TextMode.Instr, Messages.TransferClaimMissing);
				return true;
			}

			//check additional permission for admin claims
			if(claim.isAdminClaim() && !player.hasPermission("griefprevention.adminclaims")) {
				sendMessage(player, TextMode.Err, Messages.TransferClaimPermission);
				return true;
			}

			UUID newOwnerID = null;  //no argument = make an admin claim
			String ownerName = "admin";

			if(args.length > 0) {
				OfflinePlayer targetPlayer = this.resolvePlayerByName(args[0]);
				if(targetPlayer == null) {
					sendMessage(player, TextMode.Err, Messages.PlayerNotFound2);
					return true;
				}
				newOwnerID = targetPlayer.getUniqueId();
				ownerName = targetPlayer.getName();
			}

			//change ownerhsip
			try {
				this.dataStore.changeClaimOwner(claim, newOwnerID);
			} catch(DataStore.NoTransferException e) {
				sendMessage(player, TextMode.Instr, Messages.TransferTopLevel);
				return true;
			}

			//confirm
			sendMessage(player, TextMode.Success, Messages.TransferSuccess);
			GriefPreventionLight.AddLogEntry(player.getName() + " transferred a claim at " + claim.world.getName() + claim.lesserBoundaryCorner.toString() + " to " + ownerName + ".", CustomLogEntryTypes.AdminActivity);

			return true;
		}

		//trustlist
		else if(cmd.getName().equalsIgnoreCase("trustlist") && player != null) {
			Claim claim = this.dataStore.getClaimAt(player.getLocation(), null);

			//if no claim here, error message
			if(claim == null) {
				sendMessage(player, TextMode.Err, Messages.TrustListNoClaim);
				return true;
			}

			//if no permission to manage permissions, error message
			String errorMessage = claim.allowGrantPermission(player);
			if(errorMessage != null) {
				GriefPreventionLight.sendMessage(player, TextMode.Err, errorMessage);
				return true;
			}

			//otherwise build a list of explicit permissions by permission level
			//and send that to the player
			ArrayList<String> builders = new ArrayList<>();
			ArrayList<String> containers = new ArrayList<>();
			ArrayList<String> accessors = new ArrayList<>();
			ArrayList<String> managers = new ArrayList<>();
			claim.getPermissions(builders, containers, accessors, managers);

			sendMessage(player, TextMode.Info, Messages.TrustListHeader);

			StringBuilder permissions = new StringBuilder();
			permissions.append(ChatColor.GOLD).append('>');

			if(managers.size() > 0) {
				for(String manager : managers)
					permissions.append(this.trustEntryToPlayerName(manager)).append(' ');
			}

			player.sendMessage(permissions.toString());
			permissions = new StringBuilder();
			permissions.append(ChatColor.YELLOW).append('>');

			if(builders.size() > 0) {
				for(String builder : builders)
					permissions.append(this.trustEntryToPlayerName(builder)).append(' ');
			}

			player.sendMessage(permissions.toString());
			permissions = new StringBuilder();
			permissions.append(ChatColor.GREEN).append('>');

			if(containers.size() > 0) {
				for(String container : containers)
					permissions.append(this.trustEntryToPlayerName(container)).append(' ');
			}

			player.sendMessage(permissions.toString());
			permissions = new StringBuilder();
			permissions.append(ChatColor.BLUE).append('>');

			if(accessors.size() > 0) {
				for(String accessor : accessors)
					permissions.append(this.trustEntryToPlayerName(accessor)).append(' ');
			}

			player.sendMessage(permissions.toString());

			player.sendMessage(
					ChatColor.GOLD + this.dataStore.getMessage(Messages.Manage) + " " +
							ChatColor.YELLOW + this.dataStore.getMessage(Messages.Build) + " " +
							ChatColor.GREEN + this.dataStore.getMessage(Messages.Containers) + " " +
							ChatColor.BLUE + this.dataStore.getMessage(Messages.Access));

			if(claim.getSubclaimRestrictions()) {
				sendMessage(player, TextMode.Err, Messages.HasSubclaimRestriction);
			}

			return true;
		}

		//untrust <player> or untrust [<group>]
		else if(cmd.getName().equalsIgnoreCase("untrust") && player != null) {
			//requires exactly one parameter, the other player's name
			if(args.length != 1) return false;

			//determine which claim the player is standing in
			Claim claim = this.dataStore.getClaimAt(player.getLocation(), null);

			//bracket any permissions
			if(args[0].contains(".") && !args[0].startsWith("[") && !args[0].endsWith("]")) {
				args[0] = "[" + args[0] + "]";
			}

			//determine whether a single player or clearing permissions entirely
			boolean clearPermissions = false;
			OfflinePlayer otherPlayer = null;
			if(args[0].equals("all")) {
				if(claim == null || claim.allowEdit(player) == null) {
					clearPermissions = true;
				} else {
					sendMessage(player, TextMode.Err, Messages.ClearPermsOwnerOnly);
					return true;
				}
			} else {
				//validate player argument or group argument
				if(!args[0].startsWith("[") || !args[0].endsWith("]")) {
					otherPlayer = this.resolvePlayerByName(args[0]);
					if(otherPlayer == null && !args[0].equals("public")) {
						sendMessage(player, TextMode.Err, Messages.PlayerNotFound2);
						return true;
					}

					//correct to proper casing
					if(otherPlayer != null)
						args[0] = otherPlayer.getName();
				}
			}

			//if no claim here, apply changes to all his claims
			if(claim == null) {
				PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());

				String idToDrop = args[0];
				if(otherPlayer != null) {
					idToDrop = otherPlayer.getUniqueId().toString();
				}

				//calling event
				TrustChangedEvent event = new TrustChangedEvent(player, playerData.getClaims(), null, false, idToDrop);
				Bukkit.getPluginManager().callEvent(event);

				if(event.isCancelled()) {
					return true;
				}

				//dropping permissions
				for(int i = 0; i < playerData.getClaims().size(); i++) {
					claim = playerData.getClaims().get(i);

					//if untrusting "all" drop all permissions
					if(clearPermissions) {
						claim.clearPermissions();
					}

					//otherwise drop individual permissions
					else {
						claim.dropPermission(idToDrop);
						claim.managers.remove(idToDrop);
					}

					//save changes
					this.dataStore.saveClaim(claim);
				}

				//beautify for output
				if(args[0].equals("public")) {
					args[0] = "the public";
				}

				//confirmation message
				if(!clearPermissions) {
					sendMessage(player, TextMode.Success, Messages.UntrustIndividualAllClaims, args[0]);
				} else {
					sendMessage(player, TextMode.Success, Messages.UntrustEveryoneAllClaims);
				}
			}

			//otherwise, apply changes to only this claim
			else if(claim.allowGrantPermission(player) != null) {
				sendMessage(player, TextMode.Err, Messages.NoPermissionTrust, claim.getOwnerName());
				return true;
			} else {
				//if clearing all
				if(clearPermissions) {
					//requires owner
					if(claim.allowEdit(player) != null) {
						sendMessage(player, TextMode.Err, Messages.UntrustAllOwnerOnly);
						return true;
					}

					//calling the event
					TrustChangedEvent event = new TrustChangedEvent(player, claim, null, false, args[0]);
					Bukkit.getPluginManager().callEvent(event);

					if(event.isCancelled()) {
						return true;
					}

					claim.clearPermissions();
					sendMessage(player, TextMode.Success, Messages.ClearPermissionsOneClaim);
				}

				//otherwise individual permission drop
				else {
					String idToDrop = args[0];
					if(otherPlayer != null) {
						idToDrop = otherPlayer.getUniqueId().toString();
					}
					boolean targetIsManager = claim.managers.contains(idToDrop);
					if(targetIsManager && claim.allowEdit(player) != null)  //only claim owners can untrust managers
					{
						sendMessage(player, TextMode.Err, Messages.ManagersDontUntrustManagers, claim.getOwnerName());
						return true;
					} else {
						//calling the event
						TrustChangedEvent event = new TrustChangedEvent(player, claim, null, false, idToDrop);
						Bukkit.getPluginManager().callEvent(event);

						if(event.isCancelled()) {
							return true;
						}

						claim.dropPermission(idToDrop);
						claim.managers.remove(idToDrop);

						//beautify for output
						if(args[0].equals("public")) {
							args[0] = "the public";
						}

						sendMessage(player, TextMode.Success, Messages.UntrustIndividualSingleClaim, args[0]);
					}
				}

				//save changes
				this.dataStore.saveClaim(claim);
			}

			return true;
		}

		//accesstrust <player>
		else if(cmd.getName().equalsIgnoreCase("accesstrust") && player != null) {
			//requires exactly one parameter, the other player's name
			if(args.length != 1) return false;

			this.handleTrustCommand(player, ClaimPermission.Access, args[0]);

			return true;
		}

		//containertrust <player>
		else if(cmd.getName().equalsIgnoreCase("containertrust") && player != null) {
			//requires exactly one parameter, the other player's name
			if(args.length != 1) return false;

			this.handleTrustCommand(player, ClaimPermission.Inventory, args[0]);

			return true;
		}

		//permissiontrust <player>
		else if(cmd.getName().equalsIgnoreCase("permissiontrust") && player != null) {
			//requires exactly one parameter, the other player's name
			if(args.length != 1) return false;

			this.handleTrustCommand(player, null, args[0]);  //null indicates permissiontrust to the helper method

			return true;
		}

		//restrictsubclaim
		else if(cmd.getName().equalsIgnoreCase("restrictsubclaim") && player != null) {
			PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
			Claim claim = this.dataStore.getClaimAt(player.getLocation(), playerData.lastClaim);
			if(claim == null || claim.parent == null) {
				sendMessage(player, TextMode.Err, Messages.StandInSubclaim);
				return true;
			}

			// If player has /ignoreclaims on, continue
			// If admin claim, fail if this user is not an admin
			// If not an admin claim, fail if this user is not the owner
			if(!playerData.ignoreClaims && (claim.isAdminClaim() ? !player.hasPermission("griefprevention.adminclaims") : !player.getUniqueId().equals(claim.parent.ownerID))) {
				sendMessage(player, TextMode.Err, Messages.OnlyOwnersModifyClaims, claim.getOwnerName());
				return true;
			}

			if(claim.getSubclaimRestrictions()) {
				claim.setSubclaimRestrictions(false);
				sendMessage(player, TextMode.Success, Messages.SubclaimUnrestricted);
			} else {
				claim.setSubclaimRestrictions(true);
				sendMessage(player, TextMode.Success, Messages.SubclaimRestricted);
			}
			this.dataStore.saveClaim(claim);
			return true;
		}

		//adminclaims
		else if(cmd.getName().equalsIgnoreCase("adminclaims") && player != null) {
			PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
			playerData.shovelMode = ShovelMode.Admin;
			sendMessage(player, TextMode.Success, Messages.AdminClaimsMode);

			return true;
		}

		//basicclaims
		else if(cmd.getName().equalsIgnoreCase("basicclaims") && player != null) {
			PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
			playerData.shovelMode = ShovelMode.Basic;
			playerData.claimSubdividing = null;
			sendMessage(player, TextMode.Success, Messages.BasicClaimsMode);

			return true;
		}

		//subdivideclaims
		else if(cmd.getName().equalsIgnoreCase("subdivideclaims") && player != null) {
			PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
			playerData.shovelMode = ShovelMode.Subdivide;
			playerData.claimSubdividing = null;
			sendMessage(player, TextMode.Instr, Messages.SubdivisionMode);
			sendMessage(player, TextMode.Instr, Messages.SubdivisionVideo2, DataStore.SUBDIVISION_VIDEO_URL);

			return true;
		}

		//deleteclaim
		else if(cmd.getName().equalsIgnoreCase("deleteclaim") && player != null) {
			//determine which claim the player is standing in
			Claim claim = this.dataStore.getClaimAt(player.getLocation(), null);

			if(claim == null) {
				sendMessage(player, TextMode.Err, Messages.DeleteClaimMissing);
			} else {
				//deleting an admin claim additionally requires the adminclaims permission
				if(!claim.isAdminClaim() || player.hasPermission("griefprevention.adminclaims")) {
					PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
					if(claim.children.size() > 0 && !playerData.warnedAboutMajorDeletion) {
						sendMessage(player, TextMode.Warn, Messages.DeletionSubdivisionWarning);
						playerData.warnedAboutMajorDeletion = true;
					} else {
						this.dataStore.deleteClaim(claim, true, true);

						sendMessage(player, TextMode.Success, Messages.DeleteSuccess);
						GriefPreventionLight.AddLogEntry(player.getName() + " deleted " + claim.getOwnerName() + "'s claim at " + claim.world.getName() + claim.lesserBoundaryCorner.toString(), CustomLogEntryTypes.AdminActivity);

						//revert any current visualization
						Visualization.Revert(player);

						playerData.warnedAboutMajorDeletion = false;
					}
				} else {
					sendMessage(player, TextMode.Err, Messages.CantDeleteAdminClaim);
				}
			}

			return true;
		} else if(cmd.getName().equalsIgnoreCase("claimexplosions") && player != null) {
			//determine which claim the player is standing in
			Claim claim = this.dataStore.getClaimAt(player.getLocation(), null);

			if(claim == null) {
				sendMessage(player, TextMode.Err, Messages.DeleteClaimMissing);
			} else {
				String noBuildReason = claim.allowBuild(player);
				if(noBuildReason != null) {
					GriefPreventionLight.sendMessage(player, TextMode.Err, noBuildReason);
					return true;
				}

				if(claim.areExplosivesAllowed) {
					claim.areExplosivesAllowed = false;
					sendMessage(player, TextMode.Success, Messages.ExplosivesDisabled);
				} else {
					claim.areExplosivesAllowed = true;
					sendMessage(player, TextMode.Success, Messages.ExplosivesEnabled);
				}
			}

			return true;
		}

		//deleteallclaims <player>
		else if(cmd.getName().equalsIgnoreCase("deleteallclaims")) {
			//requires exactly one parameter, the other player's name
			if(args.length != 1) return false;

			//try to find that player
			OfflinePlayer otherPlayer = this.resolvePlayerByName(args[0]);
			if(otherPlayer == null) {
				sendMessage(player, TextMode.Err, Messages.PlayerNotFound2);
				return true;
			}

			//delete all that player's claims
			this.dataStore.deleteClaimsForPlayer(otherPlayer.getUniqueId(), true);

			sendMessage(player, TextMode.Success, Messages.DeleteAllSuccess, otherPlayer.getName());
			if(player != null) {
				GriefPreventionLight.AddLogEntry(player.getName() + " deleted all claims belonging to " + otherPlayer.getName() + ".", CustomLogEntryTypes.AdminActivity);

				//revert any current visualization
				Visualization.Revert(player);
			}

			return true;
		} else if(cmd.getName().equalsIgnoreCase("deleteclaimsinworld")) {
			//must be executed at the console
			if(player != null) {
				sendMessage(player, TextMode.Err, Messages.ConsoleOnlyCommand);
				return true;
			}

			//requires exactly one parameter, the world name
			if(args.length != 1) return false;

			//try to find the specified world
			World world = Bukkit.getServer().getWorld(args[0]);
			if(world == null) {
				sendMessage(null, TextMode.Err, Messages.WorldNotFound);
				return true;
			}

			//delete all claims in that world
			this.dataStore.deleteClaimsInWorld(world, true);
			GriefPreventionLight.AddLogEntry("Deleted all claims in world: " + world.getName() + ".", CustomLogEntryTypes.AdminActivity);
			return true;
		} else if(cmd.getName().equalsIgnoreCase("deleteuserclaimsinworld")) {
			//must be executed at the console
			if(player != null) {
				sendMessage(player, TextMode.Err, Messages.ConsoleOnlyCommand);
				return true;
			}

			//requires exactly one parameter, the world name
			if(args.length != 1) return false;

			//try to find the specified world
			World world = Bukkit.getServer().getWorld(args[0]);
			if(world == null) {
				sendMessage(null, TextMode.Err, Messages.WorldNotFound);
				return true;
			}

			//delete all USER claims in that world
			this.dataStore.deleteClaimsInWorld(world, false);
			GriefPreventionLight.AddLogEntry("Deleted all user claims in world: " + world.getName() + ".", CustomLogEntryTypes.AdminActivity);
			return true;
		}

		//claimbook
		else if(cmd.getName().equalsIgnoreCase("claimbook")) {
			//requires one parameter
			if(args.length != 1) return false;

			//try to find the specified player
			Player otherPlayer = this.getServer().getPlayer(args[0]);
			if(otherPlayer == null) {
				sendMessage(player, TextMode.Err, Messages.PlayerNotFound2);
			} else {
				WelcomeTask task = new WelcomeTask(otherPlayer);
				task.run();
			}
			return true;
		}

		//claimslist or claimslist <player>
		else if(cmd.getName().equalsIgnoreCase("claimslist")) {
			//at most one parameter
			if(args.length > 1) return false;

			//player whose claims will be listed
			OfflinePlayer otherPlayer;

			//if another player isn't specified, assume current player
			if(args.length < 1) {
				if(player != null)
					otherPlayer = player;
				else
					return false;
			}

			//otherwise if no permission to delve into another player's claims data
			else if(player != null && !player.hasPermission("griefprevention.claimslistother")) {
				sendMessage(player, TextMode.Err, Messages.ClaimsListNoPermission);
				return true;
			}

			//otherwise try to find the specified player
			else {
				otherPlayer = this.resolvePlayerByName(args[0]);
				if(otherPlayer == null) {
					sendMessage(player, TextMode.Err, Messages.PlayerNotFound2);
					return true;
				}
			}

			//load the target player's data
			PlayerData playerData = this.dataStore.getPlayerData(otherPlayer.getUniqueId());
			Vector<Claim> claims = playerData.getClaims();
			sendMessage(player, TextMode.Instr, Messages.StartBlockMath,
					String.valueOf(playerData.getAccruedClaimBlocks()),
					String.valueOf((playerData.getBonusClaimBlocks() + this.dataStore.getGroupBonusBlocks(otherPlayer.getUniqueId()))),
					String.valueOf((playerData.getAccruedClaimBlocks() + playerData.getBonusClaimBlocks() + this.dataStore.getGroupBonusBlocks(otherPlayer.getUniqueId()))));
			if(claims.size() > 0) {
				sendMessage(player, TextMode.Instr, Messages.ClaimsListHeader);
				for(int i = 0; i < playerData.getClaims().size(); i++) {
					Claim claim = playerData.getClaims().get(i);
					GriefPreventionLight.sendMessage(player, TextMode.Instr, claim.world.getName() + claim.lesserBoundaryCorner + this.dataStore.getMessage(Messages.ContinueBlockMath, String.valueOf(claim.getArea())));
				}

				sendMessage(player, TextMode.Instr, Messages.EndBlockMath, String.valueOf(playerData.getRemainingClaimBlocks()));
			}

			//drop the data we just loaded, if the player isn't online
			if(!otherPlayer.isOnline())
				this.dataStore.clearCachedPlayerData(otherPlayer.getUniqueId());

			return true;
		}

		//adminclaimslist
		else if(cmd.getName().equalsIgnoreCase("adminclaimslist")) {
			//find admin claims
			Vector<Claim> claims = new Vector<>();
			for(Claim claim : this.dataStore.claims) {
				if(claim.ownerID == null)  //admin claim
				{
					claims.add(claim);
				}
			}
			if(claims.size() > 0) {
				sendMessage(player, TextMode.Instr, Messages.ClaimsListHeader);
				for(Claim claim : claims) {
					GriefPreventionLight.sendMessage(player, TextMode.Instr, claim.world.getName() + claim.lesserBoundaryCorner);
				}
			}

			return true;
		}

		//unlockItems
		else if(cmd.getName().equalsIgnoreCase("unlockdrops") && player != null) {
			PlayerData playerData;

			if(player.hasPermission("griefprevention.unlockothersdrops") && args.length == 1) {
				Player otherPlayer = Bukkit.getPlayer(args[0]);
				if(otherPlayer == null) {
					sendMessage(player, TextMode.Err, Messages.PlayerNotFound2);
					return true;
				}

				playerData = this.dataStore.getPlayerData(otherPlayer.getUniqueId());
				sendMessage(player, TextMode.Success, Messages.DropUnlockOthersConfirmation, otherPlayer.getName());
			} else {
				playerData = this.dataStore.getPlayerData(player.getUniqueId());
				sendMessage(player, TextMode.Success, Messages.DropUnlockConfirmation);
			}

			playerData.dropsAreUnlocked = true;

			return true;
		}

		//deletealladminclaims
		else if(player != null && cmd.getName().equalsIgnoreCase("deletealladminclaims")) {
			if(!player.hasPermission("griefprevention.deleteclaims")) {
				sendMessage(player, TextMode.Err, Messages.NoDeletePermission);
				return true;
			}

			//delete all admin claims
			this.dataStore.deleteClaimsForPlayer(null, true);  //null for owner id indicates an administrative claim

			sendMessage(player, TextMode.Success, Messages.AllAdminDeleted);
			GriefPreventionLight.AddLogEntry(player.getName() + " deleted all administrative claims.", CustomLogEntryTypes.AdminActivity);

			//revert any current visualization
			Visualization.Revert(player);

			return true;
		}

		//adjustbonusclaimblocks <player> <amount> or [<permission>] amount
		else if(cmd.getName().equalsIgnoreCase("adjustbonusclaimblocks")) {
			//requires exactly two parameters, the other player or group's name and the adjustment
			if(args.length != 2) return false;

			//parse the adjustment amount
			int adjustment;
			try {
				adjustment = Integer.parseInt(args[1]);
			} catch(NumberFormatException numberFormatException) {
				return false;  //causes usage to be displayed
			}

			//if granting blocks to all players with a specific permission
			if(args[0].startsWith("[") && args[0].endsWith("]")) {
				String permissionIdentifier = args[0].substring(1, args[0].length() - 1);
				int newTotal = this.dataStore.adjustGroupBonusBlocks(permissionIdentifier, adjustment);

				sendMessage(player, TextMode.Success, Messages.AdjustGroupBlocksSuccess, permissionIdentifier, String.valueOf(adjustment), String.valueOf(newTotal));
				if(player != null)
					GriefPreventionLight.AddLogEntry(player.getName() + " adjusted " + permissionIdentifier + "'s bonus claim blocks by " + adjustment + ".");

				return true;
			}

			//otherwise, find the specified player
			OfflinePlayer targetPlayer;
			try {
				UUID playerID = UUID.fromString(args[0]);
				targetPlayer = this.getServer().getOfflinePlayer(playerID);

			} catch(IllegalArgumentException e) {
				targetPlayer = this.resolvePlayerByName(args[0]);
			}

			if(targetPlayer == null) {
				sendMessage(player, TextMode.Err, Messages.PlayerNotFound2);
				return true;
			}

			//give blocks to player
			PlayerData playerData = this.dataStore.getPlayerData(targetPlayer.getUniqueId());
			playerData.setBonusClaimBlocks(playerData.getBonusClaimBlocks() + adjustment);
			this.dataStore.savePlayerData(targetPlayer.getUniqueId(), playerData);

			sendMessage(player, TextMode.Success, Messages.AdjustBlocksSuccess, targetPlayer.getName(), String.valueOf(adjustment), String.valueOf(playerData.getBonusClaimBlocks()));
			if(player != null)
				GriefPreventionLight.AddLogEntry(player.getName() + " adjusted " + targetPlayer.getName() + "'s bonus claim blocks by " + adjustment + ".", CustomLogEntryTypes.AdminActivity);

			return true;
		}

		//adjustbonusclaimblocksall <amount>
		else if(cmd.getName().equalsIgnoreCase("adjustbonusclaimblocksall")) {
			//requires exactly one parameter, the amount of adjustment
			if(args.length != 1) return false;

			//parse the adjustment amount
			int adjustment;
			try {
				adjustment = Integer.parseInt(args[0]);
			} catch(NumberFormatException numberFormatException) {
				return false;  //causes usage to be displayed
			}

			//for each online player
			@SuppressWarnings("unchecked")
			Collection<Player> players = (Collection<Player>) this.getServer().getOnlinePlayers();
			StringBuilder builder = new StringBuilder();
			for(Player onlinePlayer : players) {
				UUID playerID = onlinePlayer.getUniqueId();
				PlayerData playerData = this.dataStore.getPlayerData(playerID);
				playerData.setBonusClaimBlocks(playerData.getBonusClaimBlocks() + adjustment);
				this.dataStore.savePlayerData(playerID, playerData);
				builder.append(onlinePlayer.getName()).append(' ');
			}

			sendMessage(player, TextMode.Success, Messages.AdjustBlocksAllSuccess, String.valueOf(adjustment));
			GriefPreventionLight.AddLogEntry("Adjusted all " + players.size() + "players' bonus claim blocks by " + adjustment + ".  " + builder.toString(), CustomLogEntryTypes.AdminActivity);

			return true;
		}

		//setaccruedclaimblocks <player> <amount>
		else if(cmd.getName().equalsIgnoreCase("setaccruedclaimblocks")) {
			//requires exactly two parameters, the other player's name and the new amount
			if(args.length != 2) return false;

			//parse the adjustment amount
			int newAmount;
			try {
				newAmount = Integer.parseInt(args[1]);
			} catch(NumberFormatException numberFormatException) {
				return false;  //causes usage to be displayed
			}

			//find the specified player
			OfflinePlayer targetPlayer = this.resolvePlayerByName(args[0]);
			if(targetPlayer == null) {
				sendMessage(player, TextMode.Err, Messages.PlayerNotFound2);
				return true;
			}

			//set player's blocks
			PlayerData playerData = this.dataStore.getPlayerData(targetPlayer.getUniqueId());
			playerData.setAccruedClaimBlocks(newAmount);
			this.dataStore.savePlayerData(targetPlayer.getUniqueId(), playerData);

			sendMessage(player, TextMode.Success, Messages.SetClaimBlocksSuccess);
			if(player != null)
				GriefPreventionLight.AddLogEntry(player.getName() + " set " + targetPlayer.getName() + "'s accrued claim blocks to " + newAmount + ".", CustomLogEntryTypes.AdminActivity);

			return true;
		}

		//trapped
		else if(cmd.getName().equalsIgnoreCase("trapped") && player != null) {
			//FEATURE: empower players who get "stuck" in an area where they don't have permission to build to save themselves

			PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
			Claim claim = this.dataStore.getClaimAt(player.getLocation(), playerData.lastClaim);

			//if another /trapped is pending, ignore this slash command
			if(playerData.pendingTrapped) {
				return true;
			}

			//if the player isn't in a claim or has permission to build, tell him to man up
			if(claim == null || claim.allowBuild(player) == null) {
				sendMessage(player, TextMode.Err, Messages.NotTrappedHere);
				return true;
			}

			//rescue destination may be set by GPFlags or other plugin, ask to find out
			SaveTrappedPlayerEvent event = new SaveTrappedPlayerEvent(claim);
			Bukkit.getPluginManager().callEvent(event);

			//if the player is in the nether or end, he's screwed (there's no way to programmatically find a safe place for him)
			if(player.getWorld().getEnvironment() != Environment.NORMAL && event.getDestination() == null) {
				sendMessage(player, TextMode.Err, Messages.TrappedWontWorkHere);
				return true;
			}

			//if the player is in an administrative claim and AllowTrappedInAdminClaims is false, he should contact an admin
			if(!GriefPreventionLight.instance.config_claims_allowTrappedInAdminClaims && claim.isAdminClaim() && event.getDestination() == null) {
				sendMessage(player, TextMode.Err, Messages.TrappedWontWorkHere);
				return true;
			}
			//send instructions
			sendMessage(player, TextMode.Instr, Messages.RescuePending);

			//create a task to rescue this player in a little while
			PlayerRescueTask task = new PlayerRescueTask(player, player.getLocation(), event.getDestination());
			this.getServer().getScheduler().scheduleSyncDelayedTask(this, task, 200L);  //20L ~ 1 second

			return true;
		}

		//softmute
		else if(cmd.getName().equalsIgnoreCase("softmute")) {
			//requires one parameter
			if(args.length != 1) return false;

			//find the specified player
			OfflinePlayer targetPlayer = this.resolvePlayerByName(args[0]);
			if(targetPlayer == null) {
				sendMessage(player, TextMode.Err, Messages.PlayerNotFound2);
				return true;
			}

			//toggle mute for player
			boolean isMuted = this.dataStore.toggleSoftMute(targetPlayer.getUniqueId());
			if(isMuted) {
				sendMessage(player, TextMode.Success, Messages.SoftMuted, targetPlayer.getName());
				String executorName = "console";
				if(player != null) {
					executorName = player.getName();
				}

				GriefPreventionLight.AddLogEntry(executorName + " muted " + targetPlayer.getName() + ".", CustomLogEntryTypes.AdminActivity, true);
			} else {
				sendMessage(player, TextMode.Success, Messages.UnSoftMuted, targetPlayer.getName());
			}

			return true;
		} else if(cmd.getName().equalsIgnoreCase("gpreload")) {
			this.loadConfig();
			if(player != null) {
				GriefPreventionLight.sendMessage(player, TextMode.Success, "Configuration updated.  If you have updated your Grief Prevention JAR, you still need to /reload or reboot your server.");
			} else {
				GriefPreventionLight.AddLogEntry("Configuration updated.  If you have updated your Grief Prevention JAR, you still need to /reload or reboot your server.");
			}

			return true;
		}

		//givepet
		else if(cmd.getName().equalsIgnoreCase("givepet") && player != null) {
			//requires one parameter
			if(args.length < 1) return false;

			PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());

			//special case: cancellation
			if(args[0].equalsIgnoreCase("cancel")) {
				playerData.petGiveawayRecipient = null;
				sendMessage(player, TextMode.Success, Messages.PetTransferCancellation);
				return true;
			}

			//find the specified player
			OfflinePlayer targetPlayer = this.resolvePlayerByName(args[0]);
			if(targetPlayer == null) {
				sendMessage(player, TextMode.Err, Messages.PlayerNotFound2);
				return true;
			}

			//remember the player's ID for later pet transfer
			playerData.petGiveawayRecipient = targetPlayer;

			//send instructions
			sendMessage(player, TextMode.Instr, Messages.ReadyToTransferPet);

			return true;
		}

		//gpblockinfo
		else if(cmd.getName().equalsIgnoreCase("gpblockinfo") && player != null) {
			ItemStack inHand = player.getInventory().getItemInMainHand();
			player.sendMessage("In Hand: " + inHand.getType().name());

			Block inWorld = player.getTargetBlockExact(300, FluidCollisionMode.ALWAYS);
			if(inWorld == null) inWorld = player.getEyeLocation().getBlock();
			player.sendMessage("In World: " + inWorld.getType().name());

			return true;
		}

		//ignoreplayer
		else if(cmd.getName().equalsIgnoreCase("ignoreplayer") && player != null) {
			//requires target player name
			if(args.length < 1) return false;

			//validate target player
			OfflinePlayer targetPlayer = this.resolvePlayerByName(args[0]);
			if(targetPlayer == null) {
				sendMessage(player, TextMode.Err, Messages.PlayerNotFound2);
				return true;
			}

			this.setIgnoreStatus(player, targetPlayer, IgnoreMode.StandardIgnore);

			sendMessage(player, TextMode.Success, Messages.IgnoreConfirmation);

			return true;
		}

		//unignoreplayer
		else if(cmd.getName().equalsIgnoreCase("unignoreplayer") && player != null) {
			//requires target player name
			if(args.length < 1) return false;

			//validate target player
			OfflinePlayer targetPlayer = this.resolvePlayerByName(args[0]);
			if(targetPlayer == null) {
				sendMessage(player, TextMode.Err, Messages.PlayerNotFound2);
				return true;
			}

			PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
			Boolean ignoreStatus = playerData.ignoredPlayers.get(targetPlayer.getUniqueId());
			if(ignoreStatus == null || ignoreStatus) {
				sendMessage(player, TextMode.Err, Messages.NotIgnoringPlayer);
				return true;
			}

			this.setIgnoreStatus(player, targetPlayer, IgnoreMode.None);

			sendMessage(player, TextMode.Success, Messages.UnIgnoreConfirmation);

			return true;
		}

		//ignoredplayerlist
		else if(cmd.getName().equalsIgnoreCase("ignoredplayerlist") && player != null) {
			PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
			StringBuilder builder = new StringBuilder();
			for(Entry<UUID, Boolean> entry : playerData.ignoredPlayers.entrySet()) {
				if(entry.getValue() != null) {
					//if not an admin ignore, add it to the list
					if(!entry.getValue()) {
						builder.append(GriefPreventionLight.lookupPlayerName(entry.getKey()));
						builder.append(" ");
					}
				}
			}

			String list = builder.toString().trim();
			if(list.isEmpty()) {
				sendMessage(player, TextMode.Info, Messages.NotIgnoringAnyone);
			} else {
				GriefPreventionLight.sendMessage(player, TextMode.Info, list);
			}

			return true;
		}

		//separateplayers
		else if(cmd.getName().equalsIgnoreCase("separate")) {
			//requires two player names
			if(args.length < 2) return false;

			//validate target players
			OfflinePlayer targetPlayer = this.resolvePlayerByName(args[0]);
			if(targetPlayer == null) {
				sendMessage(player, TextMode.Err, Messages.PlayerNotFound2);
				return true;
			}

			OfflinePlayer targetPlayer2 = this.resolvePlayerByName(args[1]);
			if(targetPlayer2 == null) {
				sendMessage(player, TextMode.Err, Messages.PlayerNotFound2);
				return true;
			}

			this.setIgnoreStatus(targetPlayer, targetPlayer2, IgnoreMode.AdminIgnore);

			sendMessage(player, TextMode.Success, Messages.SeparateConfirmation);

			return true;
		}

		//unseparateplayers
		else if(cmd.getName().equalsIgnoreCase("unseparate")) {
			//requires two player names
			if(args.length < 2) return false;

			//validate target players
			OfflinePlayer targetPlayer = this.resolvePlayerByName(args[0]);
			if(targetPlayer == null) {
				sendMessage(player, TextMode.Err, Messages.PlayerNotFound2);
				return true;
			}

			OfflinePlayer targetPlayer2 = this.resolvePlayerByName(args[1]);
			if(targetPlayer2 == null) {
				sendMessage(player, TextMode.Err, Messages.PlayerNotFound2);
				return true;
			}

			this.setIgnoreStatus(targetPlayer, targetPlayer2, IgnoreMode.None);
			this.setIgnoreStatus(targetPlayer2, targetPlayer, IgnoreMode.None);

			sendMessage(player, TextMode.Success, Messages.UnSeparateConfirmation);

			return true;
		}
		return false;
	}

	void setIgnoreStatus(OfflinePlayer ignorer, OfflinePlayer ignoree, IgnoreMode mode) {
		PlayerData playerData = this.dataStore.getPlayerData(ignorer.getUniqueId());
		if(mode == IgnoreMode.None) {
			playerData.ignoredPlayers.remove(ignoree.getUniqueId());
		} else {
			playerData.ignoredPlayers.put(ignoree.getUniqueId(), mode != IgnoreMode.StandardIgnore);
		}

		playerData.ignoreListChanged = true;
		if(!ignorer.isOnline()) {
			this.dataStore.savePlayerData(ignorer.getUniqueId(), playerData);
			this.dataStore.clearCachedPlayerData(ignorer.getUniqueId());
		}
	}

	public enum IgnoreMode {None, StandardIgnore, AdminIgnore}

	private String trustEntryToPlayerName(String entry) {
		if(entry.startsWith("[") || entry.equals("public")) {
			return entry;
		} else {
			return GriefPreventionLight.lookupPlayerName(entry);
		}
	}

	private boolean abandonClaimHandler(Player player, boolean deleteTopLevelClaim) {
		PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());

		//which claim is being abandoned?
		Claim claim = this.dataStore.getClaimAt(player.getLocation(), null);
		if(claim == null) {
			sendMessage(player, TextMode.Instr, Messages.AbandonClaimMissing);
		}

		//verify ownership
		else if(claim.allowEdit(player) != null) {
			sendMessage(player, TextMode.Err, Messages.NotYourClaim);
		}

		//warn if has children and we're not explicitly deleting a top level claim
		else if(claim.children.size() > 0 && !deleteTopLevelClaim) {
			sendMessage(player, TextMode.Instr, Messages.DeleteTopLevelClaim);
			return true;
		} else {
			//delete it
			this.dataStore.deleteClaim(claim, true, false);

			//adjust claim blocks when abandoning a top level claim
			if(this.config_claims_abandonReturnRatio != 1.0D && claim.parent == null && claim.ownerID.equals(playerData.playerID)) {
				playerData.setAccruedClaimBlocks(playerData.getAccruedClaimBlocks() - (int) Math.ceil((claim.getArea() * (1 - this.config_claims_abandonReturnRatio))));
			}

			//tell the player how many claim blocks he has left
			int remainingBlocks = playerData.getRemainingClaimBlocks();
			sendMessage(player, TextMode.Success, Messages.AbandonSuccess, String.valueOf(remainingBlocks));

			//revert any current visualization
			Visualization.Revert(player);

			playerData.warnedAboutMajorDeletion = false;
		}

		return true;

	}

	//helper method keeps the trust commands consistent and eliminates duplicate code
	private void handleTrustCommand(Player player, ClaimPermission permissionLevel, String recipientName) {
		//determine which claim the player is standing in
		Claim claim = this.dataStore.getClaimAt(player.getLocation(), null);

		//validate player or group argument
		String permission = null;
		UUID recipientID = null;
		if(recipientName.startsWith("[") && recipientName.endsWith("]")) {
			permission = recipientName.substring(1, recipientName.length() - 1);
			if(permission.isEmpty()) {
				sendMessage(player, TextMode.Err, Messages.InvalidPermissionID);
				return;
			}
		} else if(recipientName.contains(".")) {
			permission = recipientName;
		} else {
			OfflinePlayer otherPlayer = this.resolvePlayerByName(recipientName);
			if(otherPlayer == null && !recipientName.equals("public") && !recipientName.equals("all")) {
				sendMessage(player, TextMode.Err, Messages.PlayerNotFound2);
				return;
			}

			if(otherPlayer != null) {
				recipientName = otherPlayer.getName();
				recipientID = otherPlayer.getUniqueId();
			} else {
				recipientName = "public";
			}
		}

		//determine which claims should be modified
		ArrayList<Claim> targetClaims = new ArrayList<>();
		if(claim == null) {
			PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
			targetClaims.addAll(playerData.getClaims());
		} else {
			//check permission here
			if(claim.allowGrantPermission(player) != null) {
				sendMessage(player, TextMode.Err, Messages.NoPermissionTrust, claim.getOwnerName());
				return;
			}

			//see if the player has the level of permission he's trying to grant
			String errorMessage;

			//permission level null indicates granting permission trust
			if(permissionLevel == null) {
				errorMessage = claim.allowEdit(player);
				if(errorMessage != null) {
					errorMessage = "Only " + claim.getOwnerName() + " can grant /PermissionTrust here.";
				}
			}

			//otherwise just use the ClaimPermission enum values
			else {
				switch(permissionLevel) {
					case Access:
						errorMessage = claim.allowAccess(player);
						break;
					case Inventory:
						errorMessage = claim.allowContainers(player);
						break;
					default:
						errorMessage = claim.allowBuild(player);
				}
			}

			//error message for trying to grant a permission the player doesn't have
			if(errorMessage != null) {
				sendMessage(player, TextMode.Err, Messages.CantGrantThatPermission);
				return;
			}

			targetClaims.add(claim);
		}

		//if we didn't determine which claims to modify, tell the player to be specific
		if(targetClaims.size() == 0) {
			sendMessage(player, TextMode.Err, Messages.GrantPermissionNoClaim);
			return;
		}

		String identifierToAdd = recipientName;
		if(permission != null) {
			identifierToAdd = "[" + permission + "]";
		} else if(recipientID != null) {
			identifierToAdd = recipientID.toString();
		}

		//calling the event
		TrustChangedEvent event = new TrustChangedEvent(player, targetClaims, permissionLevel, true, identifierToAdd);
		Bukkit.getPluginManager().callEvent(event);

		if(event.isCancelled()) {
			return;
		}

		//apply changes
		for(Claim currentClaim : targetClaims) {
			if(permissionLevel == null) {
				if(!currentClaim.managers.contains(identifierToAdd)) {
					currentClaim.managers.add(identifierToAdd);
				}
			} else {
				currentClaim.setPermission(identifierToAdd, permissionLevel);
			}
			this.dataStore.saveClaim(currentClaim);
		}

		//notify player
		if("public".equals(recipientName)) recipientName = this.dataStore.getMessage(Messages.CollectivePublic);
		String permissionDescription;
		if(permissionLevel == null) {
			permissionDescription = this.dataStore.getMessage(Messages.PermissionsPermission);
		} else if(permissionLevel == ClaimPermission.Build) {
			permissionDescription = this.dataStore.getMessage(Messages.BuildPermission);
		} else if(permissionLevel == ClaimPermission.Access) {
			permissionDescription = this.dataStore.getMessage(Messages.AccessPermission);
		} else //ClaimPermission.Inventory
		{
			permissionDescription = this.dataStore.getMessage(Messages.ContainersPermission);
		}

		String location;
		if(claim == null) {
			location = this.dataStore.getMessage(Messages.LocationAllClaims);
		} else {
			location = this.dataStore.getMessage(Messages.LocationCurrentClaim);
		}

		sendMessage(player, TextMode.Success, Messages.GrantPermissionConfirmation, recipientName, permissionDescription, location);
	}

	//helper method to resolve a player by name
	ConcurrentHashMap<String, UUID> playerNameToIDMap = new ConcurrentHashMap<>();

	//thread to build the above cache
	private class CacheOfflinePlayerNamesThread extends Thread {
		private final OfflinePlayer[] offlinePlayers;
		private final ConcurrentHashMap<String, UUID> playerNameToIDMap;

		CacheOfflinePlayerNamesThread(OfflinePlayer[] offlinePlayers, ConcurrentHashMap<String, UUID> playerNameToIDMap) {
			this.offlinePlayers = offlinePlayers;
			this.playerNameToIDMap = playerNameToIDMap;
		}

		public void run() {
			long now = System.currentTimeMillis();
			final long millisecondsPerDay = 1000 * 60 * 60 * 24;
			for(OfflinePlayer player : offlinePlayers) {
				try {
					UUID playerID = player.getUniqueId();
					long lastSeen = player.getLastPlayed();

					//if the player has been seen in the last 90 days, cache his name/UUID pair
					long diff = now - lastSeen;
					long daysDiff = diff / millisecondsPerDay;
					if(daysDiff <= config_advanced_offlineplayer_cache_days) {
						String playerName = player.getName();
						if(playerName == null) continue;
						this.playerNameToIDMap.put(playerName, playerID);
						this.playerNameToIDMap.put(playerName.toLowerCase(), playerID);
					}
				} catch(Exception e) {
					e.printStackTrace();
				}
			}
		}
	}


	public OfflinePlayer resolvePlayerByName(String name) {
		//try online players first
		Player targetPlayer = this.getServer().getPlayerExact(name);
		if(targetPlayer != null) return targetPlayer;

		//try exact match first
		UUID bestMatchID = this.playerNameToIDMap.get(name);

		//if failed, try ignore case
		if(bestMatchID == null) {
			bestMatchID = this.playerNameToIDMap.get(name.toLowerCase());
		}
		if(bestMatchID == null) {
			return null;
		}

		return this.getServer().getOfflinePlayer(bestMatchID);
	}

	//helper method to resolve a player name from the player's UUID
	static String lookupPlayerName(UUID playerID) {
		//parameter validation
		if(playerID == null) return "somebody";

		//check the cache
		OfflinePlayer player = GriefPreventionLight.instance.getServer().getOfflinePlayer(playerID);
		if(player.hasPlayedBefore() || player.isOnline()) {
			return player.getName();
		} else {
			return "someone(" + playerID.toString() + ")";
		}
	}

	//cache for player name lookups, to save searches of all offline players
	static void cacheUUIDNamePair(UUID playerID, String playerName) {
		//store the reverse mapping
		GriefPreventionLight.instance.playerNameToIDMap.put(playerName, playerID);
		GriefPreventionLight.instance.playerNameToIDMap.put(playerName.toLowerCase(), playerID);
	}

	//string overload for above helper
	static String lookupPlayerName(String playerID) {
		UUID id;
		try {
			id = UUID.fromString(playerID);
		} catch(IllegalArgumentException ex) {
			GriefPreventionLight.AddLogEntry("Error: Tried to look up a local player name for invalid UUID: " + playerID);
			return "someone";
		}

		return lookupPlayerName(id);
	}

	public void onDisable() {
		//save data for any online players
		@SuppressWarnings("unchecked")
		Collection<Player> players = (Collection<Player>) this.getServer().getOnlinePlayers();
		for(Player player : players) {
			UUID playerID = player.getUniqueId();
			PlayerData playerData = this.dataStore.getPlayerData(playerID);
			this.dataStore.savePlayerDataSync(playerID, playerData);
		}

		this.dataStore.close();

		//dump any remaining unwritten log entries
		this.customLogger.WriteEntries();

		AddLogEntry("GriefPrevention disabled.");
	}

	//called when a player spawns, applies protection for that player if necessary
	public void checkPvpProtectionNeeded(Player player) {

		//if player is in creative mode, do nothing
		if(player.getGameMode() == GameMode.CREATIVE) return;

		//if the player has the damage any player permission enabled, do nothing
		if(player.hasPermission("griefprevention.nopvpimmunity")) return;

		//check inventory for well, anything
		if(GriefPreventionLight.isInventoryEmpty(player)) {
			//if empty, apply immunity
			PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
		}
	}

	static boolean isInventoryEmpty(Player player) {
		PlayerInventory inventory = player.getInventory();
		ItemStack[] armorStacks = inventory.getArmorContents();

		//check armor slots, stop if any items are found
		for(ItemStack armorStack : armorStacks) {
			if(!(armorStack == null || armorStack.getType() == Material.AIR)) return false;
		}

		//check other slots, stop if any items are found
		ItemStack[] generalStacks = inventory.getContents();
		for(ItemStack generalStack : generalStacks) {
			if(!(generalStack == null || generalStack.getType() == Material.AIR)) return false;
		}

		return true;
	}

	//moves a player from the claim he's in to a nearby wilderness location
	public Location ejectPlayer(Player player) {
		//look for a suitable location
		Location candidateLocation = player.getLocation();
		while(true) {
			Claim claim = GriefPreventionLight.instance.dataStore.getClaimAt(candidateLocation, null);

			//if there's a claim here, keep looking
			if(claim != null) {
				// TODO
				candidateLocation = new Location(claim.world, claim.lesserBoundaryCorner.x - 1, 64, claim.lesserBoundaryCorner.z - 1);
			}

			//otherwise find a safe place to teleport the player
			else {
				//find a safe height, a couple of blocks above the surface
				GuaranteeChunkLoaded(candidateLocation);
				Block highestBlock = candidateLocation.getWorld().getHighestBlockAt(candidateLocation.getBlockX(), candidateLocation.getBlockZ());
				Location destination = new Location(highestBlock.getWorld(), highestBlock.getX(), highestBlock.getY() + 2, highestBlock.getZ());
				player.teleport(destination);
				return destination;
			}
		}
	}

	//ensures a piece of the managed world is loaded into server memory
	//(generates the chunk if necessary)
	private static void GuaranteeChunkLoaded(Location location) {
		Chunk chunk = location.getChunk();
		while(!chunk.isLoaded() || !chunk.load(true)) ;
	}

	//sends a color-coded message to a player
	public static void sendMessage(Player player, ChatColor color, Messages messageID, String... args) {
		sendMessage(player, color, messageID, 0, args);
	}

	//sends a color-coded message to a player
	public static void sendMessage(Player player, ChatColor color, Messages messageID, long delayInTicks, String... args) {
		String message = GriefPreventionLight.instance.dataStore.getMessage(messageID, args);
		sendMessage(player, color, message, delayInTicks);
	}

	//sends a color-coded message to a player
	public static void sendMessage(Player player, ChatColor color, String message) {
		if(message == null || message.length() == 0) return;

		if(player == null) {
			GriefPreventionLight.AddLogEntry(color + message);
		} else {
			player.sendMessage(color + message);
		}
	}

	public static void sendMessage(Player player, ChatColor color, String message, long delayInTicks) {
		SendPlayerMessageTask task = new SendPlayerMessageTask(player, color, message);

		//Only schedule if there should be a delay. Otherwise, send the message right now, else the message will appear out of order.
		if(delayInTicks > 0) {
			GriefPreventionLight.instance.getServer().getScheduler().runTaskLater(GriefPreventionLight.instance, task, delayInTicks);
		} else {
			task.run();
		}
	}

	//checks whether players can create claims in a world
	public boolean claimsEnabledForWorld(World world) {
		ClaimsMode mode = this.config_claims_worldModes.get(world);
		return mode != null && mode != ClaimsMode.Disabled;
	}

	public String allowBuild(Player player, Location location) {
		if(!GriefPreventionLight.instance.claimsEnabledForWorld(location.getWorld())) return null;

		PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
		Claim claim = this.dataStore.getClaimAt(location, playerData.lastClaim);

		//exception: administrators in ignore claims mode or wilderness
		if(playerData.ignoreClaims || claim == null) return null;

			//if not in the wilderness, then apply claim rules (permissions, etc)
		else {
			//cache the claim for later reference
			playerData.lastClaim = claim;
			return claim.allowBuild(player);
		}
	}

	public String allowBreak(Player player, Location location) {
		return this.allowBreak(player, location, null);
	}

	public String allowBreak(Player player, Location location, BlockBreakEvent breakEvent) {
		if(!GriefPreventionLight.instance.claimsEnabledForWorld(location.getWorld())) return null;

		PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
		Claim claim = this.dataStore.getClaimAt(location, playerData.lastClaim);

		//exception: administrators in ignore claims mode or wilderness
		if(playerData.ignoreClaims || claim == null) return null;

		//cache the claim for later reference
		playerData.lastClaim = claim;

		//if not in the wilderness, then apply claim rules (permissions, etc)
		String cancel = claim.allowBreak(player);
		if(cancel != null && breakEvent != null) {
			PreventBlockBreakEvent preventionEvent = new PreventBlockBreakEvent(breakEvent);
			Bukkit.getPluginManager().callEvent(preventionEvent);
			if(preventionEvent.isCancelled()) {
				cancel = null;
			}
		}

		return cancel;
	}

	public int getSeaLevel(World world) {
		Integer overrideValue = this.config_seaLevelOverride.get(world.getName());
		if(overrideValue == null || overrideValue == -1) {
			return world.getSeaLevel();
		} else {
			return overrideValue;
		}
	}

	public boolean containsBlockedIP(String message) {
		message = message.replace("\r\n", "");
		Pattern ipAddressPattern = Pattern.compile("([0-9]{1,3}\\.){3}[0-9]{1,3}");
		Matcher matcher = ipAddressPattern.matcher(message);

		//if it looks like an IP address
		if(matcher.find()) {
			//and it's not in the list of allowed IP addresses
			return !GriefPreventionLight.instance.config_spam_allowedIpAddresses.contains(matcher.group());
		}

		return false;
	}

	public static boolean isNewToServer(Player player) {
		if(player.getStatistic(Statistic.PICKUP, Material.OAK_LOG) > 0 ||
				player.getStatistic(Statistic.PICKUP, Material.SPRUCE_LOG) > 0 ||
				player.getStatistic(Statistic.PICKUP, Material.BIRCH_LOG) > 0 ||
				player.getStatistic(Statistic.PICKUP, Material.JUNGLE_LOG) > 0 ||
				player.getStatistic(Statistic.PICKUP, Material.ACACIA_LOG) > 0 ||
				player.getStatistic(Statistic.PICKUP, Material.DARK_OAK_LOG) > 0) return false;

		PlayerData playerData = instance.dataStore.getPlayerData(player.getUniqueId());
		return playerData.getClaims().size() <= 0;
	}

	static void banPlayer(Player player, String reason, String source) {
		if(GriefPreventionLight.instance.config_ban_useCommand) {
			Bukkit.getServer().dispatchCommand(
					Bukkit.getConsoleSender(),
					GriefPreventionLight.instance.config_ban_commandFormat.replace("%name%", player.getName()).replace("%reason%", reason));
		} else {
			BanList bans = Bukkit.getServer().getBanList(Type.NAME);
			bans.addBan(player.getName(), reason, null, source);

			//kick
			if(player.isOnline()) {
				player.kickPlayer(reason);
			}
		}
	}

	public ItemStack getItemInHand(Player player, EquipmentSlot hand) {
		if(hand == EquipmentSlot.OFF_HAND) return player.getInventory().getItemInOffHand();
		return player.getInventory().getItemInMainHand();
	}

	//Track scheduled "rescues" so we can cancel them if the player happens to teleport elsewhere so we can cancel it.
	ConcurrentHashMap<UUID, BukkitTask> portalReturnTaskMap = new ConcurrentHashMap<>();

	public void startRescueTask(Player player, Location location) {
		//Schedule task to reset player's portal cooldown after 30 seconds (Maximum timeout time for client, in case their network is slow and taking forever to load chunks)
		BukkitTask task = new CheckForPortalTrapTask(player, this, location).runTaskLater(GriefPreventionLight.instance, 600L);

		//Cancel existing rescue task
		if(portalReturnTaskMap.containsKey(player.getUniqueId()))
			portalReturnTaskMap.put(player.getUniqueId(), task).cancel();
		else
			portalReturnTaskMap.put(player.getUniqueId(), task);
	}
}
