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

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.List;

//represents a player claim
//creating an instance doesn't make an effective claim
//only claims which have been added to the datastore have any effect
public class Claim {
	//two locations, which together define the boundaries of the claim
	//note that the upper Y value is always ignored, because claims ALWAYS extend up to the sky
	// TODO
	//Location lesserBoundaryCorner;
	//Location greaterBoundaryCorner;

	// TODO
	final World world;
	Coordinate lesserBoundaryCorner;
	Coordinate greaterBoundaryCorner;

	//modification date.  this comes from the file timestamp during load, and is updated with runtime changes
	public Date modifiedDate;

	//id number.  unique to this claim, never changes.
	Long id;

	//ownerID.  for admin claims, this is NULL
	//use getOwnerName() to get a friendly name (will be "an administrator" for admin claims)
	public UUID ownerID;

	//list of players who (beyond the claim owner) have permission to grant permissions in this claim
	public ArrayList<String> managers = new ArrayList<>();

	//permissions for this claim, see ClaimPermission class
	private HashMap<String, ClaimPermission> playerIDToClaimPermissionMap = new HashMap<>();

	//whether or not this claim is in the data store
	//if a claim instance isn't in the data store, it isn't "active" - players can't interract with it
	//why keep this?  so that claims which have been removed from the data store can be correctly
	//ignored even though they may have references floating around
	public boolean inDataStore = false;

	public boolean areExplosivesAllowed = false;

	//parent claim
	//only used for claim subdivisions.  top level claims have null here
	public Claim parent = null;

	// intended for subclaims - they inherit no permissions
	private boolean inheritNothing = false;

	//children (subdivisions)
	//note subdivisions themselves never have children
	public ArrayList<Claim> children = new ArrayList<>();

	//following a siege, buttons/levers are unlocked temporarily.  this represents that state
	public boolean doorsOpen = false;

	//whether or not this is an administrative claim
	//administrative claims are created and maintained by players with the griefprevention.adminclaims permission.
	public boolean isAdminClaim() {
		if(this.parent != null) return this.parent.isAdminClaim();

		return (this.ownerID == null);
	}

	//accessor for ID
	public Long getID() {
		return this.id;
	}

	//players may only siege someone when he's not in an admin claim
	//and when he has some level of permission in the claim
	public boolean canSiege(Player defender) {
		if(this.isAdminClaim()) return false;

		return this.allowAccess(defender) == null;
	}

	//main constructor.  note that only creating a claim instance does nothing - a claim must be added to the data store to be effective
	Claim(World world, Coordinate lesserBoundaryCorner, Coordinate greaterBoundaryCorner, UUID ownerID, List<String> builderIDs, List<String> containerIDs, List<String> accessorIDs, List<String> managerIDs, boolean inheritNothing, Long id) {
		//modification date
		this.modifiedDate = Calendar.getInstance().getTime();

		//id
		this.id = id;

		//store corners
		this.world = world;
		this.lesserBoundaryCorner = lesserBoundaryCorner;
		this.greaterBoundaryCorner = greaterBoundaryCorner;

		//owner
		this.ownerID = ownerID;

		//other permissions
		for(String builderID : builderIDs) {
			this.setPermission(builderID, ClaimPermission.Build);
		}

		for(String containerID : containerIDs) {
			this.setPermission(containerID, ClaimPermission.Inventory);
		}

		for(String accessorID : accessorIDs) {
			this.setPermission(accessorID, ClaimPermission.Access);
		}

		for(String managerID : managerIDs) {
			if(managerID != null && !managerID.isEmpty()) {
				this.managers.add(managerID);
			}
		}

		this.inheritNothing = inheritNothing;
	}

	Claim(World world, Coordinate lesserBoundaryCorner, Coordinate greaterBoundaryCorner, UUID ownerID, List<String> builderIDs, List<String> containerIDs, List<String> accessorIDs, List<String> managerIDs, Long id) {
		this(world, lesserBoundaryCorner, greaterBoundaryCorner, ownerID, builderIDs, containerIDs, accessorIDs, managerIDs, false, id);
	}

	//produces a copy of a claim.
	public Claim(Claim claim) {
		this.modifiedDate = claim.modifiedDate;
		this.world = claim.world;
		this.lesserBoundaryCorner = claim.greaterBoundaryCorner;
		this.greaterBoundaryCorner = claim.greaterBoundaryCorner;
		this.id = claim.id;
		this.ownerID = claim.ownerID;
		this.managers = new ArrayList<>(claim.managers);
		this.playerIDToClaimPermissionMap = new HashMap<>(claim.playerIDToClaimPermissionMap);
		this.inDataStore = false; //since it's a copy of a claim, not in datastore!
		this.areExplosivesAllowed = claim.areExplosivesAllowed;
		this.parent = claim.parent;
		this.inheritNothing = claim.inheritNothing;
		this.children = new ArrayList<>(claim.children);
		this.doorsOpen = claim.doorsOpen;
	}

	//measurements.  all measurements are in blocks
	public int getArea() {
		return getWidth() * getLength();
	}

	public int getWidth() {
		return this.greaterBoundaryCorner.getX() - this.lesserBoundaryCorner.getX() + 1;
	}

	public int getLength() {
		return this.greaterBoundaryCorner.getZ() - this.lesserBoundaryCorner.getZ() + 1;
	}

	public boolean getSubclaimRestrictions() {
		return inheritNothing;
	}

	public void setSubclaimRestrictions(boolean inheritNothing) {
		this.inheritNothing = inheritNothing;
	}

	//distance check for claims, distance in this case is a band around the outside of the claim rather then euclidean distance
	public boolean isNear(Location location, int howNear) {
		Claim claim = new Claim(
				location.getWorld(),
				new Coordinate(this.lesserBoundaryCorner.getX() - howNear, this.lesserBoundaryCorner.getZ() - howNear),
				new Coordinate(this.greaterBoundaryCorner.getX() + howNear, this.greaterBoundaryCorner.getZ() + howNear),
				null,
				new ArrayList<>(),
				new ArrayList<>(),
				new ArrayList<>(),
				new ArrayList<>(),
				null
		);

		return claim.contains(location, true);
	}

	//permissions.  note administrative "public" claims have different rules than other claims
	//all of these return NULL when a player has permission, or a String error message when the player doesn't have permission
	public String allowEdit(Player player) {
		//if we don't know who's asking, always say no (i've been told some mods can make this happen somehow)
		if(player == null) return "";

		//special cases...

		//admin claims need adminclaims permission only.
		if(this.isAdminClaim()) {
			if(player.hasPermission("griefprevention.adminclaims")) return null;
		}

		//anyone with deleteclaims permission can modify non-admin claims at any time
		else {
			if(player.hasPermission("griefprevention.deleteclaims")) return null;
		}

		//no resizing, deleting, and so forth while under siege
		if(player.getUniqueId().equals(this.ownerID)) {

			//otherwise, owners can do whatever
			return null;
		}

		//permission inheritance for subdivisions
		if(this.parent != null) {
			if(player.getUniqueId().equals(this.parent.ownerID))
				return null;
			if(!inheritNothing)
				return this.parent.allowEdit(player);
		}

		//error message if all else fails
		return GriefPreventionLight.instance.dataStore.getMessage(Messages.OnlyOwnersModifyClaims, this.getOwnerName());
	}

	private static final Set<Material> PLACEABLE_FARMING_BLOCKS = EnumSet.of(
			Material.PUMPKIN_STEM,
			Material.WHEAT,
			Material.MELON_STEM,
			Material.CARROTS,
			Material.POTATOES,
			Material.NETHER_WART,
			Material.BEETROOTS);

	//build permission check
	public String allowBuild(Player player) {
		//if we don't know who's asking, always say no (i've been told some mods can make this happen somehow)
		if(player == null) return "";

		//admin claims can always be modified by admins, no exceptions
		if(this.isAdminClaim()) {
			if(player.hasPermission("griefprevention.adminclaims")) return null;
		}

		//no building while in pvp combat
		PlayerData playerData = GriefPreventionLight.instance.dataStore.getPlayerData(player.getUniqueId());

		//owners can make changes, or admins with ignore claims mode enabled
		if(player.getUniqueId().equals(this.ownerID) || GriefPreventionLight.instance.dataStore.getPlayerData(player.getUniqueId()).ignoreClaims)
			return null;

		//anyone with explicit build permission can make changes
		if(this.hasExplicitPermission(player, ClaimPermission.Build)) return null;

		//also everyone is a member of the "public", so check for public permission
		if(ClaimPermission.Build.isGrantedBy(this.playerIDToClaimPermissionMap.get("public"))) return null;

		//allow for farming with /containertrust permission
		if(this.allowContainers(player) == null) {
			return null;
		}

		//subdivision permission inheritance
		if(this.parent != null) {
			if(player.getUniqueId().equals(this.parent.ownerID))
				return null;
			if(!inheritNothing)
				return this.parent.allowBuild(player);
		}

		//failure message for all other cases
		String reason = GriefPreventionLight.instance.dataStore.getMessage(Messages.NoBuildPermission, this.getOwnerName());
		if(player.hasPermission("griefprevention.ignoreclaims"))
			reason += "  " + GriefPreventionLight.instance.dataStore.getMessage(Messages.IgnoreClaimsAdvertisement);

		return reason;
	}

	public boolean hasExplicitPermission(UUID uuid, ClaimPermission level) {
		return level.isGrantedBy(this.playerIDToClaimPermissionMap.get(uuid.toString()));
	}

	public boolean hasExplicitPermission(Player player, ClaimPermission level) {
		// Check explicit ClaimPermission for UUID
		if(this.hasExplicitPermission(player.getUniqueId(), level)) return true;

		// Check permission-based ClaimPermission
		for(Map.Entry<String, ClaimPermission> stringToPermission : this.playerIDToClaimPermissionMap.entrySet()) {
			String node = stringToPermission.getKey();
			// Ensure valid permission format for permissions - [permission.node]
			if(node.length() < 3 || node.charAt(0) != '[' || node.charAt(node.length() - 1) != ']') {
				continue;
			}

			// Check if level is high enough and player has node
			if(level.isGrantedBy(stringToPermission.getValue())
					&& player.hasPermission(node.substring(1, node.length() - 1))) {
				return true;
			}
		}

		return false;
	}

	//break permission check
	public String allowBreak(Player player) {

		//if not under siege, build rules apply
		return this.allowBuild(player);
	}

	//access permission check
	public String allowAccess(Player player) {
		//following a siege where the defender lost, the claim will allow everyone access for a time
		if(this.doorsOpen) return null;

		//admin claims need adminclaims permission only.
		if(this.isAdminClaim()) {
			if(player.hasPermission("griefprevention.adminclaims")) return null;
		}

		//claim owner and admins in ignoreclaims mode have access
		if(player.getUniqueId().equals(this.ownerID) || GriefPreventionLight.instance.dataStore.getPlayerData(player.getUniqueId()).ignoreClaims)
			return null;

		//look for explicit individual access, inventory, or build permission
		if(this.hasExplicitPermission(player, ClaimPermission.Access)) return null;

		//also check for public permission
		if(ClaimPermission.Access.isGrantedBy(this.playerIDToClaimPermissionMap.get("public"))) return null;

		//permission inheritance for subdivisions
		if(this.parent != null) {
			if(player.getUniqueId().equals(this.parent.ownerID))
				return null;
			if(!inheritNothing)
				return this.parent.allowAccess(player);
		}

		//catch-all error message for all other cases
		String reason = GriefPreventionLight.instance.dataStore.getMessage(Messages.NoAccessPermission, this.getOwnerName());
		if(player.hasPermission("griefprevention.ignoreclaims"))
			reason += "  " + GriefPreventionLight.instance.dataStore.getMessage(Messages.IgnoreClaimsAdvertisement);
		return reason;
	}

	//inventory permission check
	public String allowContainers(Player player) {
		//if we don't know who's asking, always say no (i've been told some mods can make this happen somehow)
		if(player == null) return "";

		//owner and administrators in ignoreclaims mode have access
		if(player.getUniqueId().equals(this.ownerID) || GriefPreventionLight.instance.dataStore.getPlayerData(player.getUniqueId()).ignoreClaims)
			return null;

		//admin claims need adminclaims permission only.
		if(this.isAdminClaim()) {
			if(player.hasPermission("griefprevention.adminclaims")) return null;
		}

		//check for explicit individual container or build permission
		if(this.hasExplicitPermission(player, ClaimPermission.Inventory)) return null;

		//check for public container or build permission
		if(ClaimPermission.Inventory.isGrantedBy(this.playerIDToClaimPermissionMap.get("public"))) return null;

		//permission inheritance for subdivisions
		if(this.parent != null) {
			if(player.getUniqueId().equals(this.parent.ownerID))
				return null;
			if(!inheritNothing)
				return this.parent.allowContainers(player);
		}

		//error message for all other cases
		String reason = GriefPreventionLight.instance.dataStore.getMessage(Messages.NoContainersPermission, this.getOwnerName());
		if(player.hasPermission("griefprevention.ignoreclaims"))
			reason += "  " + GriefPreventionLight.instance.dataStore.getMessage(Messages.IgnoreClaimsAdvertisement);
		return reason;
	}

	//grant permission check, relatively simple
	public String allowGrantPermission(Player player) {
		//if we don't know who's asking, always say no (i've been told some mods can make this happen somehow)
		if(player == null) return "";

		//anyone who can modify the claim can do this
		if(this.allowEdit(player) == null) return null;

		//anyone who's in the managers (/PermissionTrust) list can do this
		for(String managerID : this.managers) {
			if(managerID == null) continue;
			if(player.getUniqueId().toString().equals(managerID)) return null;

			else if(managerID.startsWith("[") && managerID.endsWith("]")) {
				managerID = managerID.substring(1, managerID.length() - 1);
				if(managerID.isEmpty()) continue;
				if(player.hasPermission(managerID)) return null;
			}
		}

		//permission inheritance for subdivisions
		if(this.parent != null) {
			if(player.getUniqueId().equals(this.parent.ownerID))
				return null;
			if(!inheritNothing)
				return this.parent.allowGrantPermission(player);
		}

		//generic error message
		String reason = GriefPreventionLight.instance.dataStore.getMessage(Messages.NoPermissionTrust, this.getOwnerName());
		if(player.hasPermission("griefprevention.ignoreclaims"))
			reason += "  " + GriefPreventionLight.instance.dataStore.getMessage(Messages.IgnoreClaimsAdvertisement);
		return reason;
	}

	public ClaimPermission getPermission(String playerID) {
		if(playerID == null || playerID.isEmpty()) {
			return null;
		}

		return this.playerIDToClaimPermissionMap.get(playerID.toLowerCase());
	}

	//grants a permission for a player or the public
	public void setPermission(String playerID, ClaimPermission permissionLevel) {
		if(playerID == null || playerID.isEmpty()) {
			return;
		}

		this.playerIDToClaimPermissionMap.put(playerID.toLowerCase(), permissionLevel);
	}

	//revokes a permission for a player or the public
	public void dropPermission(String playerID) {
		this.playerIDToClaimPermissionMap.remove(playerID.toLowerCase());

		for(Claim child : this.children) {
			child.dropPermission(playerID);
		}
	}

	//clears all permissions (except owner of course)
	public void clearPermissions() {
		this.playerIDToClaimPermissionMap.clear();
		this.managers.clear();

		for(Claim child : this.children) {
			child.clearPermissions();
		}
	}

	//gets ALL permissions
	//useful for  making copies of permissions during a claim resize and listing all permissions in a claim
	public void getPermissions(ArrayList<String> builders, ArrayList<String> containers, ArrayList<String> accessors, ArrayList<String> managers) {
		//loop through all the entries in the hash map
		for(Map.Entry<String, ClaimPermission> entry : this.playerIDToClaimPermissionMap.entrySet()) {
			//build up a list for each permission level
			if(entry.getValue() == ClaimPermission.Build) {
				builders.add(entry.getKey());
			} else if(entry.getValue() == ClaimPermission.Inventory) {
				containers.add(entry.getKey());
			} else {
				accessors.add(entry.getKey());
			}
		}

		//managers are handled a little differently
		managers.addAll(this.managers);
	}

	/*//returns a copy of the location representing lower x, y, z limits
	public Coordinate lesserBoundaryCorner {
		return this.lesserBoundaryCorner;
	}

	//returns a copy of the location representing upper x, y, z limits
	//NOTE: remember upper Y will always be ignored, all claims always extend to the sky
	public Coordinate greaterBoundaryCorner {
		return this.greaterBoundaryCorner;
	}*/

	//returns a friendly owner name (for admin claims, returns "an administrator" as the owner)
	public String getOwnerName() {
		if(this.parent != null)
			return this.parent.getOwnerName();

		if(this.ownerID == null)
			return GriefPreventionLight.instance.dataStore.getMessage(Messages.OwnerNameForAdminClaims);

		return GriefPreventionLight.lookupPlayerName(this.ownerID);
	}

	//whether or not a location is in a claim
	//ignoreHeight = true means location UNDER the claim will return TRUE
	//excludeSubdivisions = true means that locations inside subdivisions of the claim will return FALSE
	public boolean contains(Location location, boolean excludeSubdivisions) {
		//not in the same world implies false
		if(!location.getWorld().equals(this.world)) return false;

		double x = location.getX();
		double y = location.getY();
		double z = location.getZ();

		//main check
		boolean inClaim = 
				x >= this.lesserBoundaryCorner.getX() &&
				x < this.greaterBoundaryCorner.getX() + 1 &&
				z >= this.lesserBoundaryCorner.getZ() &&
				z < this.greaterBoundaryCorner.getZ() + 1;

		if(!inClaim) return false;

		//additional check for subdivisions
		//you're only in a subdivision when you're also in its parent claim
		//NOTE: if a player creates subdivions then resizes the parent claim, it's possible that
		//a subdivision can reach outside of its parent's boundaries.  so this check is important!
		if(this.parent != null) {
			return this.parent.contains(location, false);
		}

		//code to exclude subdivisions in this check
		else if(excludeSubdivisions) {
			//search all subdivisions to see if the location is in any of them
			for(Claim child : this.children) {
				//if we find such a subdivision, return false
				if(child.contains(location, true)) {
					return false;
				}
			}
		}

		//otherwise yes
		return true;
	}

	public boolean intersects(Claim claim, boolean excludeSubdivisions) {
		//not in the same world implies false
		if(!claim.world.equals(this.world)) return false;

		double x = claim.lesserBoundaryCorner.getX();
		double z = claim.lesserBoundaryCorner.getZ();

		//main check
		boolean inClaim =
				x >= this.lesserBoundaryCorner.getX() &&
				x < this.greaterBoundaryCorner.getX() + 1 &&
				z >= this.lesserBoundaryCorner.getZ() &&
				z < this.greaterBoundaryCorner.getZ() + 1;

		if(!inClaim) return false;

		//additional check for subdivisions
		//you're only in a subdivision when you're also in its parent claim
		//NOTE: if a player creates subdivions then resizes the parent claim, it's possible that
		//a subdivision can reach outside of its parent's boundaries.  so this check is important!
		if(this.parent != null) {
			return this.parent.intersects(claim, false);
		}

		//code to exclude subdivisions in this check
		else if(excludeSubdivisions) {
			//search all subdivisions to see if the location is in any of them
			for(Claim child : this.children) {
				//if we find such a subdivision, return false
				if(child.intersects(claim, true)) {
					return false;
				}
			}
		}

		//otherwise yes
		return true;
	}

	//whether or not two claims overlap
	//used internally to prevent overlaps when creating claims
	boolean overlaps(Claim otherClaim) {
		// For help visualizing test cases, try https://silentmatt.com/rectangle-intersection/

		if(!this.world.equals(otherClaim.world)) return false;

		return !(this.greaterBoundaryCorner.getX() < otherClaim.lesserBoundaryCorner.getX() ||
				this.lesserBoundaryCorner.getX() > otherClaim.greaterBoundaryCorner.getX() ||
				this.greaterBoundaryCorner.getZ() < otherClaim.lesserBoundaryCorner.getZ() ||
				this.lesserBoundaryCorner.getZ() > otherClaim.greaterBoundaryCorner.getZ());

	}

	//whether more entities may be added to a claim
	public String allowMoreEntities(boolean remove) {
		if(this.parent != null) return this.parent.allowMoreEntities(remove);

		//admin claims aren't restricted
		if(this.isAdminClaim()) return null;

		//don't apply this rule to very large claims
		if(this.getArea() > 10000) return null;

		//determine maximum allowable entity count, based on claim size
		int maxEntities = this.getArea() / 50;
		if(maxEntities == 0) return GriefPreventionLight.instance.dataStore.getMessage(Messages.ClaimTooSmallForEntities);

		//count current entities (ignoring players)
		int totalEntities = 0;
		ArrayList<Chunk> chunks = this.getChunks();
		for(Chunk chunk : chunks) {
			Entity[] entities = chunk.getEntities();
			for(Entity entity : entities) {
				if(!(entity instanceof Player) && this.contains(entity.getLocation(), false)) {
					totalEntities++;
					if(remove && totalEntities > maxEntities) entity.remove();
				}
			}
		}

		if(totalEntities >= maxEntities)
			return GriefPreventionLight.instance.dataStore.getMessage(Messages.TooManyEntitiesInClaim);

		return null;
	}

	public String allowMoreActiveBlocks() {
		if(this.parent != null) return this.parent.allowMoreActiveBlocks();

		//determine maximum allowable entity count, based on claim size
		int maxActives = this.getArea() / 100;
		if(maxActives == 0)
			return GriefPreventionLight.instance.dataStore.getMessage(Messages.ClaimTooSmallForActiveBlocks);

		//count current actives
		int totalActives = 0;
		ArrayList<Chunk> chunks = this.getChunks();
		for(Chunk chunk : chunks) {
			BlockState[] actives = chunk.getTileEntities();
			for(BlockState active : actives) {
				if(BlockEventHandler.isActiveBlock(active)) {
					if(this.contains(active.getLocation(), false)) {
						totalActives++;
					}
				}
			}
		}

		if(totalActives >= maxActives)
			return GriefPreventionLight.instance.dataStore.getMessage(Messages.TooManyActiveBlocksInClaim);

		return null;
	}

	//implements a strict ordering of claims, used to keep the claims collection sorted for faster searching
	boolean greaterThan(Claim otherClaim) {
		Coordinate thisCorner = this.lesserBoundaryCorner;
		Coordinate otherCorner = otherClaim.lesserBoundaryCorner;

		if(thisCorner.getX() > otherCorner.getX()) return true;

		if(thisCorner.getX() < otherCorner.getX()) return false;

		if(thisCorner.getZ() > otherCorner.getZ()) return true;

		if(thisCorner.getZ() < otherCorner.getZ()) return false;

		return world.getName().compareTo(otherClaim.world.getName()) < 0;
	}

	public ArrayList<Chunk> getChunks() {
		ArrayList<Chunk> chunks = new ArrayList<>();

		World world = this.world;
		Chunk lesserChunk = world.getChunkAt(lesserBoundaryCorner.x, lesserBoundaryCorner.z);
		Chunk greaterChunk = world.getChunkAt(greaterBoundaryCorner.x, greaterBoundaryCorner.z);

		for(int x = lesserChunk.getX(); x <= greaterChunk.getX(); x++) {
			for(int z = lesserChunk.getZ(); z <= greaterChunk.getZ(); z++) {
				chunks.add(world.getChunkAt(x, z));
			}
		}

		return chunks;
	}

	ArrayList<Long> getChunkHashes() {
		return DataStore.getChunkHashes(this);
	}
}
