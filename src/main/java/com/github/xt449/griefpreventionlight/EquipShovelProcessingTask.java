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

import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;

//tells a player about how many claim blocks he has, etc
//implemented as a task so that it can be delayed
//otherwise, it's spammy when players mouse-wheel past the shovel in their hot bars
class EquipShovelProcessingTask implements Runnable {
	//player data
	private final Player player;

	public EquipShovelProcessingTask(Player player) {
		this.player = player;
	}

	@Override
	public void run() {
		//if he's not holding the golden shovel anymore, do nothing
		if(GriefPreventionLight.instance.getItemInHand(player, EquipmentSlot.HAND).getType() != GriefPreventionLight.instance.config_claims_modificationTool)
			return;

		PlayerData playerData = GriefPreventionLight.instance.dataStore.getPlayerData(player.getUniqueId());

		//reset any work he might have been doing
		playerData.lastShovelLocation = null;
		playerData.claimResizing = null;

		//always reset to basic claims mode
		if(playerData.shovelMode != ShovelMode.Basic) {
			playerData.shovelMode = ShovelMode.Basic;
			GriefPreventionLight.sendMessage(player, TextMode.Info, Messages.ShovelBasicClaimMode);
		}

		//tell him how many claim blocks he has available
		int remainingBlocks = playerData.getRemainingClaimBlocks();
		GriefPreventionLight.sendMessage(player, TextMode.Instr, Messages.RemainingBlocks, String.valueOf(remainingBlocks));

		//link to a video demo of land claiming, based on world type
		if(GriefPreventionLight.instance.creativeRulesApply(player.getLocation())) {
			GriefPreventionLight.sendMessage(player, TextMode.Instr, Messages.CreativeBasicsVideo2, DataStore.CREATIVE_VIDEO_URL);
		} else if(GriefPreventionLight.instance.claimsEnabledForWorld(player.getLocation().getWorld())) {
			GriefPreventionLight.sendMessage(player, TextMode.Instr, Messages.SurvivalBasicsVideo2, DataStore.SURVIVAL_VIDEO_URL);
		}

		//if standing in a claim owned by the player, visualize it
		Claim claim = GriefPreventionLight.instance.dataStore.getClaimAt(player.getLocation(), true, playerData.lastClaim);
		if(claim != null && claim.allowEdit(player) == null) {
			playerData.lastClaim = claim;
			Visualization.Apply(player, Visualization.FromClaim(claim, player.getEyeLocation().getBlockY(), VisualizationType.Claim, player.getLocation()));
		}
	}
}
