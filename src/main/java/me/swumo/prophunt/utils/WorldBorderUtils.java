package me.swumo.prophunt.utils;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.WorldBorder;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public final class WorldBorderUtils {

    private WorldBorderUtils() {
        throw new IllegalStateException("This is a utility class.");
    }

    public static void applyArenaWorldBorder(Player player, Location pos1, Location pos2, List<Location> hiderSpawns, List<Location> seekerSpawns) {
        if (player == null || pos1 == null || pos2 == null || pos1.getWorld() == null || pos2.getWorld() == null) {
            return;
        }

        int minX = Math.min(pos1.getBlockX(), pos2.getBlockX());
        int maxX = Math.max(pos1.getBlockX(), pos2.getBlockX());
        int minZ = Math.min(pos1.getBlockZ(), pos2.getBlockZ());
        int maxZ = Math.max(pos1.getBlockZ(), pos2.getBlockZ());

        for (Location spawn : hiderSpawns) {
            if (spawn == null || spawn.getWorld() == null || !spawn.getWorld().equals(pos1.getWorld())) continue;
            minX = Math.min(minX, spawn.getBlockX());
            maxX = Math.max(maxX, spawn.getBlockX());
            minZ = Math.min(minZ, spawn.getBlockZ());
            maxZ = Math.max(maxZ, spawn.getBlockZ());
        }

        for (Location spawn : seekerSpawns) {
            if (spawn == null || spawn.getWorld() == null || !spawn.getWorld().equals(pos1.getWorld())) continue;
            minX = Math.min(minX, spawn.getBlockX());
            maxX = Math.max(maxX, spawn.getBlockX());
            minZ = Math.min(minZ, spawn.getBlockZ());
            maxZ = Math.max(maxZ, spawn.getBlockZ());
        }

        double centerX = (minX + maxX + 1) / 2.0;
        double centerZ = (minZ + maxZ + 1) / 2.0;
        double size = Math.max((maxX - minX) + 2.0, (maxZ - minZ) + 2.0);

        WorldBorder border = Bukkit.createWorldBorder();
        border.setCenter(centerX, centerZ);
        border.setSize(size);
        border.setWarningDistance(0);
        border.setDamageBuffer(0.0);
        player.setWorldBorder(border);
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

    public static void clearWorldBorders(Collection<UUID> recipients) {
        for (UUID recipientId : recipients) {
            Player recipient = Bukkit.getPlayer(recipientId);
            if (recipient == null || !recipient.isOnline()) continue;

            recipient.setWorldBorder(null);
        }
    }

    public static void applyArenaWorldBorders(
            Collection<UUID> recipients,
            Location pos1,
            Location pos2,
            List<Location> hiderSpawns,
            List<Location> seekerSpawns
    ) {
        for (UUID recipientId : recipients) {
            Player recipient = Bukkit.getPlayer(recipientId);
            if (recipient == null || !recipient.isOnline()) continue;

            applyArenaWorldBorder(recipient, pos1, pos2, hiderSpawns, seekerSpawns);
        }
    }
}
