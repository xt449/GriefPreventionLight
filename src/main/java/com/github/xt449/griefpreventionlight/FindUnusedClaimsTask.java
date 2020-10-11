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

import java.util.*;
import java.util.stream.Collectors;

//FEATURE: automatically remove claims owned by inactive players which:
//...aren't protecting much OR
//...are a free new player claim (and the player has no other claims) OR
//...because the player has been gone a REALLY long time, and that expiration has been configured in config.yml

//runs every 1 minute in the main thread
class FindUnusedClaimsTask implements Runnable {
	private List<UUID> claimOwnerUUIDs;
	private Iterator<UUID> claimOwnerIterator;

	FindUnusedClaimsTask() {
		refreshUUIDs();
	}

	@Override
	public void run() {
		//don't do anything when there are no claims
		if(claimOwnerUUIDs.isEmpty()) return;

		//wrap search around to beginning
		if(!claimOwnerIterator.hasNext()) {
			refreshUUIDs();
			return;
		}

		GriefPreventionLight.instance.getServer().getScheduler().runTaskAsynchronously(GriefPreventionLight.instance, new CleanupUnusedClaimPreTask(claimOwnerIterator.next()));
	}

	public void refreshUUIDs() {
		// Fetch owner UUIDs from list of claims
		claimOwnerUUIDs = GriefPreventionLight.instance.dataStore.claims.stream().map(claim -> claim.ownerID)
				.distinct().filter(Objects::nonNull).collect(Collectors.toList());

		if(!claimOwnerUUIDs.isEmpty()) {
			// Randomize order
			Collections.shuffle(claimOwnerUUIDs);
		}

		GriefPreventionLight.AddLogEntry("The following UUIDs own a claim and will be checked for inactivity in the following order:", CustomLogEntryTypes.Debug, true);

		for(UUID uuid : claimOwnerUUIDs)
			GriefPreventionLight.AddLogEntry(uuid.toString(), CustomLogEntryTypes.Debug, true);

		claimOwnerIterator = claimOwnerUUIDs.iterator();
	}
}
