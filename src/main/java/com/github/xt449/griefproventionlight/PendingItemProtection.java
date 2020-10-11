package com.github.xt449.griefproventionlight;

import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

class PendingItemProtection {
	public Location location;
	public UUID owner;
	long expirationTimestamp;
	ItemStack itemStack;

	public PendingItemProtection(Location location, UUID owner, long expirationTimestamp, ItemStack itemStack) {
		this.location = location;
		this.owner = owner;
		this.expirationTimestamp = expirationTimestamp;
		this.itemStack = itemStack;
	}
}
