package me.swumo.prophunt.utils;

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

    public static String formatBlockName(Material material) {
        if (material == null) return "Unknown";

        return StringUtils.capitalize(material.name());
    }
}
