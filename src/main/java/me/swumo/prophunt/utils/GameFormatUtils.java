package me.swumo.prophunt.utils;

import org.bukkit.Location;
import org.bukkit.Material;

public final class GameFormatUtils {

    private GameFormatUtils() {
        throw new IllegalStateException("This is a utility class.");
    }

    public static String formatRemainingTime(int totalSeconds) {
        if (totalSeconds % 60 == 0) {
            int minutes = totalSeconds / 60;
            return minutes + " minute" + (minutes == 1 ? "" : "s");
        }

        return totalSeconds + " seconds";
    }

    public static String formatTimer(int totalSeconds) {
        int safeSeconds = Math.max(0, totalSeconds);
        return String.format("%d:%02d", safeSeconds / 60, safeSeconds % 60);
    }

    public static String formatBlockName(Material material) {
        if (material == null) return "Unknown";

        return StringUtils.capitalize(material.name());
    }

    public static String formatLocation(Location location){
        if (location == null) return "Unknown";
        return String.format("World: %s, X: %.2f, Y: %.2f, Z: %.2f",
                location.getWorld().getName(),
                location.getX(),
                location.getY(),
                location.getZ());
    }
}
