package me.swumo.prophunt.utils;

import org.bukkit.Location;

public final class ArenaBoundsUtils {

    private ArenaBoundsUtils() {
        throw new IllegalStateException("This is a utility class.");
    }

    public static boolean isInsideCuboid(Location location, Location pos1, Location pos2) {
        if (location == null || pos1 == null || pos2 == null) return false;
        if (location.getWorld() == null || pos1.getWorld() == null || pos2.getWorld() == null) return false;
        if (!location.getWorld().equals(pos1.getWorld()) || !location.getWorld().equals(pos2.getWorld())) return false;

        int minX = Math.min(pos1.getBlockX(), pos2.getBlockX());
        int maxX = Math.max(pos1.getBlockX(), pos2.getBlockX());
        int minY = Math.min(pos1.getBlockY(), pos2.getBlockY());
        int maxY = Math.max(pos1.getBlockY(), pos2.getBlockY());
        int minZ = Math.min(pos1.getBlockZ(), pos2.getBlockZ());
        int maxZ = Math.max(pos1.getBlockZ(), pos2.getBlockZ());

        return location.getX() >= minX && location.getX() <= (maxX + 1)
                && location.getY() >= minY && location.getY() <= (maxY + 1)
                && location.getZ() >= minZ && location.getZ() <= (maxZ + 1);
    }

    public static boolean isInsideHorizontalBounds(Location location, Location pos1, Location pos2) {
        if (location == null || pos1 == null || pos2 == null) return false;
        if (location.getWorld() == null || pos1.getWorld() == null || pos2.getWorld() == null) return false;
        if (!location.getWorld().equals(pos1.getWorld()) || !location.getWorld().equals(pos2.getWorld())) return false;

        int minX = Math.min(pos1.getBlockX(), pos2.getBlockX());
        int maxX = Math.max(pos1.getBlockX(), pos2.getBlockX());
        int minZ = Math.min(pos1.getBlockZ(), pos2.getBlockZ());
        int maxZ = Math.max(pos1.getBlockZ(), pos2.getBlockZ());

        return location.getX() >= minX && location.getX() <= (maxX + 1)
                && location.getZ() >= minZ && location.getZ() <= (maxZ + 1);
    }
}