package com.github.xt449.griefpreventionlight;

import org.bukkit.Location;
import org.bukkit.World;

/**
 * @author Jonathan Talcott (xt449/BinaryBanana)
 */
public class Coordinate implements Cloneable {

	public int x;
	public int z;

	public Coordinate(int x, int z) {
		this.x = x;
		this.z = z;
	}

	public int getX() {
		return x;
	}

	public int getZ() {
		return z;
	}

	@Override
	public String toString() {
		return '(' + x + ", " + z + ')';
	}

	public static Coordinate fromLocation(Location location) {
		return new Coordinate(location.getBlockX(), location.getBlockZ());
	}

	public Location toLocation(World world) {
		return new Location(world, x, 0, z);
	}

	public Location toLocation(World world, int y) {
		return new Location(world, x, y, z);
	}
}
