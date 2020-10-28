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

import org.bukkit.*;
import org.bukkit.World.Environment;
import org.bukkit.block.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Dispenser;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.minecart.HopperMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.block.BlockIgniteEvent.IgniteCause;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.projectiles.BlockProjectileSource;
import org.bukkit.projectiles.ProjectileSource;

import java.util.*;

//event handlers related to blocks
public class BlockEventHandler implements Listener {
	//convenience reference to singleton datastore
	private final DataStore dataStore;

	private final EnumSet<Material> trashBlocks;

	//constructor
	public BlockEventHandler(DataStore dataStore) {
		this.dataStore = dataStore;

		//create the list of blocks which will not trigger a warning when they're placed outside of land claims
		this.trashBlocks = EnumSet.noneOf(Material.class);
		this.trashBlocks.add(Material.COBBLESTONE);
		this.trashBlocks.add(Material.TORCH);
		this.trashBlocks.add(Material.DIRT);
		this.trashBlocks.add(Material.OAK_SAPLING);
		this.trashBlocks.add(Material.SPRUCE_SAPLING);
		this.trashBlocks.add(Material.BIRCH_SAPLING);
		this.trashBlocks.add(Material.JUNGLE_SAPLING);
		this.trashBlocks.add(Material.ACACIA_SAPLING);
		this.trashBlocks.add(Material.DARK_OAK_SAPLING);
		this.trashBlocks.add(Material.GRAVEL);
		this.trashBlocks.add(Material.SAND);
		this.trashBlocks.add(Material.TNT);
		this.trashBlocks.add(Material.CRAFTING_TABLE);
	}

	//when a player breaks a block...
	@EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
	public void onBlockBreak(BlockBreakEvent breakEvent) {
		Player player = breakEvent.getPlayer();
		Block block = breakEvent.getBlock();

		//make sure the player is allowed to break at the location
		String noBuildReason = GriefPreventionLight.instance.allowBreak(player, block.getLocation(), breakEvent);
		if(noBuildReason != null) {
			GriefPreventionLight.sendMessage(player, TextMode.Err, noBuildReason);
			breakEvent.setCancelled(true);
		}
	}

	//when a player changes the text of a sign...
	@EventHandler(ignoreCancelled = true)
	public void onSignChanged(SignChangeEvent event) {
		Player player = event.getPlayer();
		Block sign = event.getBlock();

		String noBuildReason = GriefPreventionLight.instance.allowBuild(player, sign.getLocation());
		if(noBuildReason != null) {
			GriefPreventionLight.sendMessage(player, TextMode.Err, noBuildReason);
			event.setCancelled(true);
			return;
		}

		//send sign content to online administrators
		if(!GriefPreventionLight.instance.config_signNotifications) return;

		StringBuilder lines = new StringBuilder(" placed a sign @ " + event.getBlock().getLocation());
		boolean notEmpty = false;
		for(int i = 0; i < event.getLines().length; i++) {
			String withoutSpaces = event.getLine(i).replace(" ", "");
			if(!withoutSpaces.isEmpty()) {
				notEmpty = true;
				lines.append("\n  ").append(event.getLine(i));
			}
		}

		String signMessage = lines.toString();

		//prevent signs with blocked IP addresses
		if(!player.hasPermission("griefprevention.spam") && GriefPreventionLight.instance.containsBlockedIP(signMessage)) {
			event.setCancelled(true);
			return;
		}

		PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
		//if not empty and wasn't the same as the last sign, log it and remember it for later
		//This has been temporarily removed since `signMessage` includes location, not just the message. Waste of memory IMO
		//if(notEmpty && (playerData.lastSignMessage == null || !playerData.lastSignMessage.equals(signMessage)))
		if(notEmpty) {
			GriefPreventionLight.AddLogEntry(player.getName() + lines.toString().replace("\n  ", ";"), null);
			PlayerEventHandler.makeSocialLogEntry(player.getName(), signMessage);
			//playerData.lastSignMessage = signMessage;

			if(!player.hasPermission("griefprevention.eavesdropsigns")) {
				@SuppressWarnings("unchecked")
				Collection<Player> players = (Collection<Player>) GriefPreventionLight.instance.getServer().getOnlinePlayers();
				for(Player otherPlayer : players) {
					if(otherPlayer.hasPermission("griefprevention.eavesdropsigns")) {
						otherPlayer.sendMessage(ChatColor.GRAY + player.getName() + signMessage);
					}
				}
			}
		}
	}

	//when a player places multiple blocks...
	@EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
	public void onBlocksPlace(BlockMultiPlaceEvent placeEvent) {
		Player player = placeEvent.getPlayer();

		//don't track in worlds where claims are not enabled
		if(!GriefPreventionLight.instance.claimsEnabledForWorld(placeEvent.getBlock().getWorld())) return;

		//make sure the player is allowed to build at the location
		for(BlockState block : placeEvent.getReplacedBlockStates()) {
			String noBuildReason = GriefPreventionLight.instance.allowBuild(player, block.getLocation());
			if(noBuildReason != null) {
				GriefPreventionLight.sendMessage(player, TextMode.Err, noBuildReason);
				placeEvent.setCancelled(true);
				return;
			}
		}
	}

	//when a player places a block...
	@SuppressWarnings("null")
	@EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
	public void onBlockPlace(BlockPlaceEvent placeEvent) {
		Player player = placeEvent.getPlayer();
		Block block = placeEvent.getBlock();

		//don't track in worlds where claims are not enabled
		if(!GriefPreventionLight.instance.claimsEnabledForWorld(placeEvent.getBlock().getWorld())) return;

		//make sure the player is allowed to build at the location
		String noBuildReason = GriefPreventionLight.instance.allowBuild(player, block.getLocation());
		if(noBuildReason != null) {
			// Allow players with container trust to place books in lecterns
			PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
			Claim claim = this.dataStore.getClaimAt(block.getLocation(), playerData.lastClaim);
			if(block.getType() == Material.LECTERN && placeEvent.getBlockReplacedState().getType() == Material.LECTERN) {
				if(claim != null) {
					playerData.lastClaim = claim;
					String noContainerReason = claim.allowContainers(player);
					if(noContainerReason == null)
						return;

					placeEvent.setCancelled(true);
					GriefPreventionLight.sendMessage(player, TextMode.Err, noContainerReason);
					return;
				}
			}
			GriefPreventionLight.sendMessage(player, TextMode.Err, noBuildReason);
			placeEvent.setCancelled(true);
			return;
		}

		//if the block is being placed within or under an existing claim
		PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
		Claim claim = this.dataStore.getClaimAt(block.getLocation(), playerData.lastClaim);
		if(claim != null) {
			playerData.lastClaim = claim;

			//allow for a build warning in the future
			playerData.warnedAboutBuildingOutsideClaims = false;
		}

		//FEATURE: automatically create a claim when a player who has no claims places a chest

		//otherwise if there's no claim, the player is placing a chest, and new player automatic claims are enabled
		else if(GriefPreventionLight.instance.config_claims_automaticClaimsForNewPlayersRadius > -1 && player.hasPermission("griefprevention.createclaims") && block.getType() == Material.CHEST) {
			//if the chest is too deep underground, don't create the claim and explain why
			if(GriefPreventionLight.instance.config_claims_preventTheft && block.getY() < GriefPreventionLight.instance.config_claims_maxDepth) {
				GriefPreventionLight.sendMessage(player, TextMode.Warn, Messages.TooDeepToClaim);
				return;
			}

			int radius = GriefPreventionLight.instance.config_claims_automaticClaimsForNewPlayersRadius;

			//if the player doesn't have any claims yet, automatically create a claim centered at the chest
			if(playerData.getClaims().size() == 0) {
				//radius == 0 means protect ONLY the chest
				if(GriefPreventionLight.instance.config_claims_automaticClaimsForNewPlayersRadius == 0) {
					this.dataStore.createClaim(block.getWorld(), block.getX(), block.getX(), block.getZ(), block.getZ(), player.getUniqueId(), null, null, player);
					GriefPreventionLight.sendMessage(player, TextMode.Success, Messages.ChestClaimConfirmation);
				}

				//otherwise, create a claim in the area around the chest
				else {
					//if failure due to insufficient claim blocks available
					if(playerData.getRemainingClaimBlocks() < Math.pow(1 + 2 * GriefPreventionLight.instance.config_claims_automaticClaimsForNewPlayersRadiusMin, 2)) {
						GriefPreventionLight.sendMessage(player, TextMode.Warn, Messages.NoEnoughBlocksForChestClaim);
						return;
					}

					//as long as the automatic claim overlaps another existing claim, shrink it
					//note that since the player had permission to place the chest, at the very least, the automatic claim will include the chest
					CreateClaimResult result = null;
					while(radius >= GriefPreventionLight.instance.config_claims_automaticClaimsForNewPlayersRadiusMin) {
						int area = (radius * 2 + 1) * (radius * 2 + 1);
						if(playerData.getRemainingClaimBlocks() >= area) {
							result = this.dataStore.createClaim(
									block.getWorld(),
									block.getX() - radius, block.getX() + radius,
									block.getZ() - radius, block.getZ() + radius,
									player.getUniqueId(),
									null, null,
									player);

							if(result.succeeded) break;
						}

						radius--;
					}

					if(result != null && result.claim != null) {
						if(result.succeeded) {
							//notify and explain to player
							GriefPreventionLight.sendMessage(player, TextMode.Success, Messages.AutomaticClaimNotification);

							//show the player the protected area
							Visualization visualization = Visualization.FromClaim(result.claim, VisualizationType.Claim, player.getLocation());
							Visualization.Apply(player, visualization);
						} else {
							//notify and explain to player
							GriefPreventionLight.sendMessage(player, TextMode.Err, Messages.AutomaticClaimOtherClaimTooClose);

							//show the player the protected area
							Visualization visualization = Visualization.FromClaim(result.claim, VisualizationType.ErrorClaim, player.getLocation());
							Visualization.Apply(player, visualization);
						}
					}
				}

				GriefPreventionLight.sendMessage(player, TextMode.Instr, Messages.SurvivalBasicsVideo2, DataStore.SURVIVAL_VIDEO_URL);
			}

			//check to see if this chest is in a claim, and warn when it isn't
			if(GriefPreventionLight.instance.config_claims_preventTheft && this.dataStore.getClaimAt(block.getLocation(), playerData.lastClaim) == null) {
				GriefPreventionLight.sendMessage(player, TextMode.Warn, Messages.UnprotectedChestWarning);
			}
		}

		//FEATURE: limit wilderness tree planting to grass, or dirt with more blocks beneath it
		else if(Tag.SAPLINGS.isTagged(block.getType()) && GriefPreventionLight.instance.config_blockSkyTrees && GriefPreventionLight.instance.claimsEnabledForWorld(player.getWorld())) {
			Block earthBlock = placeEvent.getBlockAgainst();
			if(earthBlock.getType() != Material.GRASS) {
				if(earthBlock.getRelative(BlockFace.DOWN).getType() == Material.AIR ||
						earthBlock.getRelative(BlockFace.DOWN).getRelative(BlockFace.DOWN).getType() == Material.AIR) {
					placeEvent.setCancelled(true);
				}
			}
		}

		//FEATURE: warn players when they're placing non-trash blocks outside of their claimed areas
		else if(!this.trashBlocks.contains(block.getType()) && GriefPreventionLight.instance.claimsEnabledForWorld(block.getWorld())) {
			if(!playerData.warnedAboutBuildingOutsideClaims && !player.hasPermission("griefprevention.adminclaims")
					&& player.hasPermission("griefprevention.createclaims") && ((playerData.lastClaim == null
					&& playerData.getClaims().size() == 0) || (playerData.lastClaim != null
					&& playerData.lastClaim.isNear(player.getLocation(), 15)))) {
				Long now = null;
				if(playerData.buildWarningTimestamp == null || (now = System.currentTimeMillis()) - playerData.buildWarningTimestamp > 600000)  //10 minute cooldown
				{
					GriefPreventionLight.sendMessage(player, TextMode.Warn, Messages.BuildingOutsideClaims);
					playerData.warnedAboutBuildingOutsideClaims = true;

					if(now == null) now = System.currentTimeMillis();
					playerData.buildWarningTimestamp = now;

					if(playerData.getClaims().size() < 2) {
						GriefPreventionLight.sendMessage(player, TextMode.Instr, Messages.SurvivalBasicsVideo2, DataStore.SURVIVAL_VIDEO_URL);
					}

					if(playerData.lastClaim != null) {
						Visualization visualization = Visualization.FromClaim(playerData.lastClaim, VisualizationType.Claim, player.getLocation());
						Visualization.Apply(player, visualization);
					}
				}
			}
		}

		// TODO
		//warn players when they place TNT above sea level, since it doesn't destroy blocks there
		if(GriefPreventionLight.instance.config_blockSurfaceOtherExplosions && block.getType() == Material.TNT &&
				block.getWorld().getEnvironment() != Environment.NETHER &&
				block.getY() > GriefPreventionLight.instance.getSeaLevel(block.getWorld()) - 5 &&
				claim == null) {
			GriefPreventionLight.sendMessage(player, TextMode.Warn, Messages.NoTNTDamageAboveSeaLevel);
		}

		//warn players about disabled pistons outside of land claims
		if(GriefPreventionLight.instance.config_pistonMovement == PistonMode.CLAIMS_ONLY &&
				(block.getType() == Material.PISTON || block.getType() == Material.STICKY_PISTON) &&
				claim == null) {
			GriefPreventionLight.sendMessage(player, TextMode.Warn, Messages.NoPistonsOutsideClaims);
		}
	}

	static boolean isActiveBlock(Block block) {
		return isActiveBlock(block.getType());
	}

	static boolean isActiveBlock(BlockState state) {
		return isActiveBlock(state.getType());
	}

	static boolean isActiveBlock(Material type) {
		return type == Material.HOPPER || type == Material.BEACON || type == Material.SPAWNER;
	}

	// Prevent pistons pushing blocks into or out of claims.
	@EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
	public void onBlockPistonExtend(BlockPistonExtendEvent event) {
		onPistonEvent(event, event.getBlocks(), false);
	}

	// Prevent pistons pulling blocks into or out of claims.
	@EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
	public void onBlockPistonRetract(BlockPistonRetractEvent event) {
		onPistonEvent(event, event.getBlocks(), true);
	}

	// Handle piston push and pulls.
	private void onPistonEvent(BlockPistonEvent event, List<Block> blocks, boolean retracting) {
		PistonMode pistonMode = GriefPreventionLight.instance.config_pistonMovement;
		// Return if piston movements are ignored.
		if(pistonMode == PistonMode.IGNORED) return;

		// Don't check in worlds where claims are not enabled.
		if(!GriefPreventionLight.instance.claimsEnabledForWorld(event.getBlock().getWorld())) return;

		BlockFace direction = event.getDirection();
		if(retracting) {
			direction = direction.getOppositeFace();
		}

		Block pistonBlock = event.getBlock();
		Claim pistonClaim = this.dataStore.getClaimAt(pistonBlock.getLocation(), null);

		// A claim is required, but the piston is not inside a claim.
		if(pistonClaim == null && pistonMode == PistonMode.CLAIMS_ONLY) {
			event.setCancelled(true);
			return;
		}

		// If no blocks are moving, quickly check if another claim's boundaries are violated.
		if(blocks.isEmpty()) {
			Block invadedBlock = pistonBlock.getRelative(direction);
			Claim invadedClaim = this.dataStore.getClaimAt(invadedBlock.getLocation(), pistonClaim);
			if(invadedClaim != null && (pistonClaim == null || !Objects.equals(pistonClaim.ownerID, invadedClaim.ownerID))) {
				event.setCancelled(true);
			}

			return;
		}

		int minX, maxX, minY, maxY, minZ, maxZ;
		minX = maxX = pistonBlock.getX();
		minY = maxY = pistonBlock.getY();
		minZ = maxZ = pistonBlock.getZ();

		// Find min and max values for faster claim lookups and bounding box-based fast mode.
		for(Block block : blocks) {
			minX = Math.min(minX, block.getX());
			minY = Math.min(minY, block.getY());
			minZ = Math.min(minZ, block.getZ());
			maxX = Math.max(maxX, block.getX());
			maxY = Math.max(maxY, block.getY());
			maxZ = Math.max(maxZ, block.getZ());
		}

		// Add direction to include invaded zone.
		if(direction.getModX() > 0)
			maxX += direction.getModX();
		else
			minX += direction.getModX();
		if(direction.getModY() > 0)
			maxY += direction.getModY();
		if(direction.getModZ() > 0)
			maxZ += direction.getModZ();
		else
			minZ += direction.getModZ();

		/*
		 * Claims-only mode. All moved blocks must be inside of the owning claim.
		 * From BigScary:
		 *  - Could push into another land claim, don't want to spend CPU checking for that
		 *  - Push ice out, place torch, get water outside the claim
		 */
		if(pistonMode == PistonMode.CLAIMS_ONLY) {
			if(minY < 0 || minX < pistonClaim.lesserBoundaryCorner.getX() || maxX > pistonClaim.greaterBoundaryCorner.getX() || minZ < pistonClaim.lesserBoundaryCorner.getZ() || maxZ > pistonClaim.greaterBoundaryCorner.getZ())
				event.setCancelled(true);

			return;
		}

		// Pushing down or pulling up is safe if all blocks are in line with the piston.
		if(minX == maxX && minZ == maxZ && direction == (retracting ? BlockFace.DOWN : BlockFace.UP))
			return;

		// Fast mode: Use the intersection of a cuboid containing all blocks instead of individual locations.
		if(pistonMode == PistonMode.EVERYWHERE_SIMPLE) {
			ArrayList<Claim> intersectable = new ArrayList<>();
			int chunkXMax = maxX >> 4;
			int chunkZMax = maxZ >> 4;

			for(int chunkX = minX >> 4; chunkX <= chunkXMax; ++chunkX) {
				for(int chunkZ = minZ >> 4; chunkZ <= chunkZMax; ++chunkZ) {
					ArrayList<Claim> chunkClaims = dataStore.chunksToClaimsMap.get(DataStore.getChunkHash(chunkX, chunkZ));
					if(chunkClaims != null)
						intersectable.addAll(chunkClaims);
				}
			}

			for(Claim claim : intersectable) {
				if(claim == pistonClaim) continue;

				// Ensure claim intersects with bounding box.
				if(maxY < 0 || minX > claim.greaterBoundaryCorner.getX() || maxX < claim.lesserBoundaryCorner.getX() || minZ > claim.greaterBoundaryCorner.getZ() || maxZ < claim.lesserBoundaryCorner.getZ())
					continue;

				// If owners are different, cancel.
				if(pistonClaim == null || !Objects.equals(pistonClaim.ownerID, claim.ownerID)) {
					event.setCancelled(true);
					return;
				}
			}

			return;
		}

		// Precise mode: Each block must be considered individually.
		Claim lastClaim = pistonClaim;
		HashSet<Block> checkBlocks = new HashSet<>(blocks);

		// Add all blocks that will be occupied after the shift.
		for(Block block : blocks)
			if(block.getPistonMoveReaction() != PistonMoveReaction.BREAK)
				checkBlocks.add(block.getRelative(direction));

		for(Block block : checkBlocks) {
			// Reimplement DataStore#getClaimAt to ignore subclaims to maximize performance.
			Location location = block.getLocation();
			Claim claim = null;
			if(lastClaim != null && lastClaim.inDataStore && lastClaim.contains(location, true))
				claim = lastClaim;
			else {
				ArrayList<Claim> chunkClaims = dataStore.chunksToClaimsMap.get(DataStore.getChunkHash(location));
				if(chunkClaims != null) {
					for(Claim chunkClaim : chunkClaims) {
						if(chunkClaim.contains(location, true)) {
							claim = chunkClaim;
							break;
						}
					}
				}
			}

			if(claim == null) continue;

			lastClaim = claim;

			// If pushing this block will change ownership, cancel the event and take away the piston (for performance reasons).
			if(pistonClaim == null || !Objects.equals(pistonClaim.ownerID, claim.ownerID)) {
				event.setCancelled(true);
				pistonBlock.getWorld().createExplosion(pistonBlock.getLocation(), 0);
				pistonBlock.getWorld().dropItem(pistonBlock.getLocation(), new ItemStack(event.isSticky() ? Material.STICKY_PISTON : Material.PISTON));
				pistonBlock.setType(Material.AIR);
				return;
			}
		}
	}

	//blocks are ignited ONLY by flint and steel (not by being near lava, open flames, etc), unless configured otherwise
	@EventHandler(priority = EventPriority.LOWEST)
	public void onBlockIgnite(BlockIgniteEvent igniteEvent) {
		//don't track in worlds where claims are not enabled
		if(!GriefPreventionLight.instance.claimsEnabledForWorld(igniteEvent.getBlock().getWorld())) return;

		if(igniteEvent.getCause() == IgniteCause.LIGHTNING && GriefPreventionLight.instance.dataStore.getClaimAt(igniteEvent.getIgnitingEntity().getLocation(), null) != null) {
//        	if(igniteEvent.getIgnitingEntity().hasMetadata("GP_TRIDENT")){ //BlockIgniteEvent is called before LightningStrikeEvent. See #532
			igniteEvent.setCancelled(true);
//			}
		}

		// If a fire is started by a fireball from a dispenser, allow it if the dispenser is in the same claim.
		if(igniteEvent.getCause() == IgniteCause.FIREBALL && igniteEvent.getIgnitingEntity() instanceof Fireball) {
			ProjectileSource shooter = ((Fireball) igniteEvent.getIgnitingEntity()).getShooter();
			if(shooter instanceof BlockProjectileSource) {
				Claim claim = GriefPreventionLight.instance.dataStore.getClaimAt(igniteEvent.getBlock().getLocation(), null);
				if(claim != null && GriefPreventionLight.instance.dataStore.getClaimAt(((BlockProjectileSource) shooter).getBlock().getLocation(), claim) == claim) {
					return;
				}
			}
		}

		// Handle arrows igniting TNT.
		if(igniteEvent.getCause() == IgniteCause.ARROW) {
			Claim claim = GriefPreventionLight.instance.dataStore.getClaimAt(igniteEvent.getBlock().getLocation(), null);

			if(claim == null) {
				// Only TNT can be ignited by arrows, so the targeted block will be destroyed by completion.
				if(!GriefPreventionLight.instance.config_fireDestroys || !GriefPreventionLight.instance.config_fireSpreads)
					igniteEvent.setCancelled(true);
				return;
			}

			if(igniteEvent.getIgnitingEntity() instanceof Projectile) {
				ProjectileSource shooter = ((Projectile) igniteEvent.getIgnitingEntity()).getShooter();

				// Allow ignition if arrow was shot by a player with build permission.
				if(shooter instanceof Player && claim.allowBuild((Player) shooter) == null) return;

				// Allow ignition if arrow was shot by a dispenser in the same claim.
				if(shooter instanceof BlockProjectileSource &&
						GriefPreventionLight.instance.dataStore.getClaimAt(((BlockProjectileSource) shooter).getBlock().getLocation(), claim) == claim)
					return;
			}

			// Block all other ignition by arrows in claims.
			igniteEvent.setCancelled(true);
			return;
		}

		if(!GriefPreventionLight.instance.config_fireSpreads && igniteEvent.getCause() != IgniteCause.FLINT_AND_STEEL && igniteEvent.getCause() != IgniteCause.LIGHTNING) {
			igniteEvent.setCancelled(true);
		}
	}

	//fire doesn't spread unless configured to, but other blocks still do (mushrooms and vines, for example)
	@EventHandler(priority = EventPriority.LOWEST)
	public void onBlockSpread(BlockSpreadEvent spreadEvent) {
		if(spreadEvent.getSource().getType() != Material.FIRE) return;

		//don't track in worlds where claims are not enabled
		if(!GriefPreventionLight.instance.claimsEnabledForWorld(spreadEvent.getBlock().getWorld())) return;

		if(!GriefPreventionLight.instance.config_fireSpreads) {
			spreadEvent.setCancelled(true);

			Block underBlock = spreadEvent.getSource().getRelative(BlockFace.DOWN);
			if(underBlock.getType() != Material.NETHERRACK) {
				spreadEvent.getSource().setType(Material.AIR);
			}

			return;
		}

		//never spread into a claimed area, regardless of settings
		if(this.dataStore.getClaimAt(spreadEvent.getBlock().getLocation(), null) != null) {
			if(GriefPreventionLight.instance.config_claims_firespreads) return;
			spreadEvent.setCancelled(true);

			//if the source of the spread is not fire on netherrack, put out that source fire to save cpu cycles
			Block source = spreadEvent.getSource();
			if(source.getRelative(BlockFace.DOWN).getType() != Material.NETHERRACK) {
				source.setType(Material.AIR);
			}
		}
	}

	//blocks are not destroyed by fire, unless configured to do so
	@EventHandler(priority = EventPriority.LOWEST)
	public void onBlockBurn(BlockBurnEvent burnEvent) {
		//don't track in worlds where claims are not enabled
		if(!GriefPreventionLight.instance.claimsEnabledForWorld(burnEvent.getBlock().getWorld())) return;

		if(!GriefPreventionLight.instance.config_fireDestroys) {
			burnEvent.setCancelled(true);
			Block block = burnEvent.getBlock();
			Block[] adjacentBlocks = new Block[]
					{
							block.getRelative(BlockFace.UP),
							block.getRelative(BlockFace.DOWN),
							block.getRelative(BlockFace.NORTH),
							block.getRelative(BlockFace.SOUTH),
							block.getRelative(BlockFace.EAST),
							block.getRelative(BlockFace.WEST)
					};

			//pro-actively put out any fires adjacent the burning block, to reduce future processing here
			for(Block adjacentBlock : adjacentBlocks) {
				if(adjacentBlock.getType() == Material.FIRE && adjacentBlock.getRelative(BlockFace.DOWN).getType() != Material.NETHERRACK) {
					adjacentBlock.setType(Material.AIR);
				}
			}

			Block aboveBlock = block.getRelative(BlockFace.UP);
			if(aboveBlock.getType() == Material.FIRE) {
				aboveBlock.setType(Material.AIR);
			}
			return;
		}

		//never burn claimed blocks, regardless of settings
		if(this.dataStore.getClaimAt(burnEvent.getBlock().getLocation(), null) != null) {
			if(GriefPreventionLight.instance.config_claims_firedamages) return;
			burnEvent.setCancelled(true);
		}
	}


	//ensures fluids don't flow into land claims from outside
	private Claim lastSpreadClaim = null;

	@EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
	public void onBlockFromTo(BlockFromToEvent spreadEvent) {
		//always allow fluids to flow straight down
		if(spreadEvent.getFace() == BlockFace.DOWN) return;

		//don't track in worlds where claims are not enabled
		if(!GriefPreventionLight.instance.claimsEnabledForWorld(spreadEvent.getBlock().getWorld())) return;

		//where to?
		Block toBlock = spreadEvent.getToBlock();
		Location toLocation = toBlock.getLocation();
		Claim toClaim = this.dataStore.getClaimAt(toLocation, lastSpreadClaim);

		//if into a land claim, it must be from the same land claim
		if(toClaim != null) {
			this.lastSpreadClaim = toClaim;
			if(!toClaim.contains(spreadEvent.getBlock().getLocation(), true)) {
				//exception: from parent into subdivision
				if(toClaim.parent == null || !toClaim.parent.contains(spreadEvent.getBlock().getLocation(), false)) {
					spreadEvent.setCancelled(true);
				}
			}
		}
	}

	//Stop projectiles from destroying blocks that don't fire a proper event
	@EventHandler(ignoreCancelled = true)
	private void chorusFlower(ProjectileHitEvent event) {
		//don't track in worlds where claims are not enabled
		if(!GriefPreventionLight.instance.claimsEnabledForWorld(event.getEntity().getWorld())) return;

		if(event.getHitBlock() == null || event.getHitBlock().getType() != Material.CHORUS_FLOWER)
			return;

		Block block = event.getHitBlock();

		Claim claim = dataStore.getClaimAt(block.getLocation(), null);
		if(claim == null)
			return;

		Player shooter = null;
		Projectile projectile = event.getEntity();

		if(projectile.getShooter() instanceof Player)
			shooter = (Player) projectile.getShooter();

		if(shooter == null) {
			event.getHitBlock().setType(Material.AIR);
			Bukkit.getScheduler().runTask(GriefPreventionLight.instance, () -> event.getHitBlock().setBlockData(block.getBlockData()));
			return;
		}

		String allowContainer = claim.allowContainers(shooter);

		if(allowContainer != null) {
			event.getHitBlock().setType(Material.AIR);
			Bukkit.getScheduler().runTask(GriefPreventionLight.instance, () -> event.getHitBlock().setBlockData(block.getBlockData()));
			GriefPreventionLight.sendMessage(shooter, TextMode.Err, allowContainer);
		}
	}

	//ensures dispensers can't be used to dispense a block(like water or lava) or item across a claim boundary
	@EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
	public void onDispense(BlockDispenseEvent dispenseEvent) {
		//don't track in worlds where claims are not enabled
		if(!GriefPreventionLight.instance.claimsEnabledForWorld(dispenseEvent.getBlock().getWorld())) return;

		//from where?
		Block fromBlock = dispenseEvent.getBlock();
		BlockData fromData = fromBlock.getBlockData();
		if(!(fromData instanceof Dispenser)) return;
		Dispenser dispenser = (Dispenser) fromData;

		//to where?
		Block toBlock = fromBlock.getRelative(dispenser.getFacing());
		Claim fromClaim = this.dataStore.getClaimAt(fromBlock.getLocation(), null);
		Claim toClaim = this.dataStore.getClaimAt(toBlock.getLocation(), fromClaim);

		//wilderness to wilderness is OK
		if(fromClaim == null && toClaim == null) return;

		//within claim is OK
		if(fromClaim == toClaim) return;

		//everything else is NOT OK
		dispenseEvent.setCancelled(true);
	}

	@EventHandler(ignoreCancelled = true)
	public void onTreeGrow(StructureGrowEvent growEvent) {
		//only take these potentially expensive steps if configured to do so
		if(!GriefPreventionLight.instance.config_limitTreeGrowth) return;

		//don't track in worlds where claims are not enabled
		if(!GriefPreventionLight.instance.claimsEnabledForWorld(growEvent.getWorld())) return;

		Location rootLocation = growEvent.getLocation();
		Claim rootClaim = this.dataStore.getClaimAt(rootLocation, null);
		String rootOwnerName = null;

		//who owns the spreading block, if anyone?
		if(rootClaim != null) {
			//tree growth in subdivisions is dependent on who owns the top level claim
			if(rootClaim.parent != null) rootClaim = rootClaim.parent;

			//if an administrative claim, just let the tree grow where it wants
			if(rootClaim.isAdminClaim()) return;

			//otherwise, note the owner of the claim
			rootOwnerName = rootClaim.getOwnerName();
		}

		//for each block growing
		for(int i = 0; i < growEvent.getBlocks().size(); i++) {
			BlockState block = growEvent.getBlocks().get(i);
			Claim blockClaim = this.dataStore.getClaimAt(block.getLocation(), rootClaim);

			//if it's growing into a claim
			if(blockClaim != null) {
				//if there's no owner for the new tree, or the owner for the new tree is different from the owner of the claim
				if(rootOwnerName == null || !rootOwnerName.equals(blockClaim.getOwnerName())) {
					growEvent.getBlocks().remove(i--);
				}
			}
		}
	}

	@EventHandler(ignoreCancelled = true)
	public void onInventoryPickupItem(InventoryPickupItemEvent event) {
		//prevent hoppers from picking-up items dropped by players on death

		InventoryHolder holder = event.getInventory().getHolder();
		if(holder instanceof HopperMinecart || holder instanceof Hopper) {
			Item item = event.getItem();
			List<MetadataValue> data = item.getMetadata("GP_ITEMOWNER");
			//if this is marked as belonging to a player
			if(data.size() > 0) {
				UUID ownerID = (UUID) data.get(0).value();

				//has that player unlocked his drops?
				OfflinePlayer owner = GriefPreventionLight.instance.getServer().getOfflinePlayer(ownerID);
				if(owner.isOnline()) {
					PlayerData playerData = this.dataStore.getPlayerData(ownerID);

					//if locked, don't allow pickup
					if(!playerData.dropsAreUnlocked) {
						event.setCancelled(true);
					}
				}
			}
		}
	}

	@EventHandler(ignoreCancelled = true)
	public void onItemFrameBrokenByBoat(final HangingBreakEvent event) {
		// Checks if the event is caused by physics - 90% of cases caused by a boat (other 10% would be block,
		// however since it's in a claim, unless you use a TNT block we don't need to worry about it).
		if(event.getCause() != HangingBreakEvent.RemoveCause.PHYSICS) {
			return;
		}

		// Cancels the event if in a claim, as we can not efficiently retrieve the person/entity who broke the Item Frame/Hangable Item.
		if(this.dataStore.getClaimAt(event.getEntity().getLocation(), null) != null) {
			event.setCancelled(true);
		}
	}
}
