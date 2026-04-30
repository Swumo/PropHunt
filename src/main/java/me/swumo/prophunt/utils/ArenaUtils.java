package me.swumo.prophunt.utils;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

public final class ArenaUtils {

    private ArenaUtils() {
        throw new IllegalStateException("This is a utility class.");
    }

    // -------------------------------------------------------------------------
    // Types
    // -------------------------------------------------------------------------

    public enum ArenaMutationStatus {
        SUCCESS,
        ALREADY_EXISTS,
        NOT_FOUND,
        MISSING_CUBOID,
        OUTSIDE_CUBOID
    }

    public record ArenaMutationResult(
            ArenaMutationStatus status,
            String key,
            String world,
            Location pos1,
            Location pos2
    ) {
    }

    public record ArenaDefinition(
            String name,
            String world,
            Location pos1,
            Location pos2,
            List<Location> hiderSpawns,
            List<Location> seekerSpawns
    ) {
        public boolean hasCuboid() {
            return pos1 != null && pos2 != null && world != null;
        }

        public boolean hasSpawns() {
            return !hiderSpawns.isEmpty() && !seekerSpawns.isEmpty();
        }
    }

    public record ArenaInfo(
            String key,
            boolean exists,
            boolean hasPos1,
            boolean hasPos2,
            int hiderSpawnCount,
            int seekerSpawnCount
    ) {
    }

    // -------------------------------------------------------------------------
    // Runtime state
    // -------------------------------------------------------------------------

    public static final class ArenaRuntime {
        public final String name;
        public final String world;
        public final Location pos1;
        public final Location pos2;
        public final List<Location> hiderSpawns;
        public final List<Location> seekerSpawns;
        public final List<Material> cachedBlockPool = Collections.synchronizedList(new ArrayList<>());
        public boolean blockPoolGenerated = false;

        public ArenaRuntime(String name, String world, Location pos1, Location pos2,
                            List<Location> hiderSpawns, List<Location> seekerSpawns) {
            this.name = name;
            this.world = world;
            this.pos1 = pos1;
            this.pos2 = pos2;
            this.hiderSpawns = hiderSpawns;
            this.seekerSpawns = seekerSpawns;
        }

        public boolean hasCuboid() {
            return pos1 != null && pos2 != null && world != null;
        }

        public boolean hasSpawns() {
            return !hiderSpawns.isEmpty() && !seekerSpawns.isEmpty();
        }
    }

    // -------------------------------------------------------------------------
    // Config CRUD
    // -------------------------------------------------------------------------

    public static ArenaMutationResult createArena(FileConfiguration cfg, String name) {
        String key = normalizeArenaName(name);
        if (cfg.isConfigurationSection("arenas." + key)) {
            return new ArenaMutationResult(ArenaMutationStatus.ALREADY_EXISTS, key, null, null, null);
        }

        cfg.createSection("arenas." + key);
        return new ArenaMutationResult(ArenaMutationStatus.SUCCESS, key, null, null, null);
    }

    public static ArenaMutationResult removeArena(FileConfiguration cfg, String name) {
        String key = normalizeArenaName(name);
        if (!cfg.isConfigurationSection("arenas." + key)) {
            return new ArenaMutationResult(ArenaMutationStatus.NOT_FOUND, key, null, null, null);
        }

        cfg.set("arenas." + key, null);
        return new ArenaMutationResult(ArenaMutationStatus.SUCCESS, key, null, null, null);
    }

    public static ArenaMutationResult setArenaPos(FileConfiguration cfg, Player player, String name, String posKey) {
        String key = normalizeArenaName(name);
        ConfigurationSection sec = cfg.getConfigurationSection("arenas." + key);
        if (sec == null) {
            return new ArenaMutationResult(ArenaMutationStatus.NOT_FOUND, key, null, null, null);
        }

        Location loc = player.getLocation();
        sec.set("world", loc.getWorld().getName());
        writeLocation(sec.createSection(posKey), loc);

        String world = sec.getString("world");
        Location pos1 = readLocation(sec.getConfigurationSection("pos1"), world);
        Location pos2 = readLocation(sec.getConfigurationSection("pos2"), world);
        return new ArenaMutationResult(ArenaMutationStatus.SUCCESS, key, world, pos1, pos2);
    }

    public static ArenaMutationResult addSpawn(FileConfiguration cfg, Player player, String name, boolean hider) {
        String key = normalizeArenaName(name);
        ConfigurationSection sec = cfg.getConfigurationSection("arenas." + key);
        if (sec == null) {
            return new ArenaMutationResult(ArenaMutationStatus.NOT_FOUND, key, null, null, null);
        }

        Location loc = player.getLocation();
        sec.set("world", loc.getWorld().getName());

        String world = sec.getString("world");
        Location pos1 = readLocation(sec.getConfigurationSection("pos1"), world);
        Location pos2 = readLocation(sec.getConfigurationSection("pos2"), world);
        if (pos1 == null || pos2 == null) {
            return new ArenaMutationResult(ArenaMutationStatus.MISSING_CUBOID, key, world, pos1, pos2);
        }

        if (!WorldBorderUtils.isInsideCuboid(loc, pos1, pos2)) {
            return new ArenaMutationResult(ArenaMutationStatus.OUTSIDE_CUBOID, key, world, pos1, pos2);
        }

        String listPath = hider ? "hider-spawns" : "seeker-spawns";
        List<Map<?, ?>> mapList = sec.getMapList(listPath);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("x", loc.getX());
        map.put("y", loc.getY());
        map.put("z", loc.getZ());
        map.put("yaw", loc.getYaw());
        map.put("pitch", loc.getPitch());
        mapList.add(map);
        sec.set(listPath, mapList);

        return new ArenaMutationResult(ArenaMutationStatus.SUCCESS, key, world, pos1, pos2);
    }

    public static List<String> getArenaNames(FileConfiguration cfg) {
        ConfigurationSection arenasSec = cfg.getConfigurationSection("arenas");
        if (arenasSec == null) return Collections.emptyList();

        return new ArrayList<>(arenasSec.getKeys(false));
    }

    public static ArenaInfo getArenaInfo(FileConfiguration cfg, String name) {
        String key = normalizeArenaName(name);
        ConfigurationSection sec = cfg.getConfigurationSection("arenas." + key);
        if (sec == null) return new ArenaInfo(key, false, false, false, 0, 0);

        return new ArenaInfo(
                key,
                true,
                sec.isConfigurationSection("pos1"),
                sec.isConfigurationSection("pos2"),
                sec.getMapList("hider-spawns").size(),
                sec.getMapList("seeker-spawns").size()
        );
    }

    public static List<ArenaDefinition> loadArenas(FileConfiguration cfg) {
        ConfigurationSection arenasSec = cfg.getConfigurationSection("arenas");
        if (arenasSec == null) return Collections.emptyList();

        List<ArenaDefinition> arenas = new ArrayList<>();
        for (String name : arenasSec.getKeys(false)) {
            ConfigurationSection sec = arenasSec.getConfigurationSection(name);
            if (sec == null) continue;

            String world = sec.getString("world");
            Location pos1 = readLocation(sec.getConfigurationSection("pos1"), world);
            Location pos2 = readLocation(sec.getConfigurationSection("pos2"), world);
            List<Location> hiderSpawns = readLocationList(sec.getMapList("hider-spawns"), world);
            List<Location> seekerSpawns = readLocationList(sec.getMapList("seeker-spawns"), world);
            arenas.add(new ArenaDefinition(name, world, pos1, pos2, hiderSpawns, seekerSpawns));
        }

        return arenas;
    }

    public static String normalizeArenaName(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
    }

    public static List<Location> readLocationList(List<Map<?, ?>> list, String worldName) {
        List<Location> out = new ArrayList<>();
        World world = worldName == null ? null : Bukkit.getWorld(worldName);
        if (world == null) return out;

        for (Map<?, ?> map : list) {
            try {
                double x = numberFromMap(map, "x", 0d);
                double y = numberFromMap(map, "y", 0d);
                double z = numberFromMap(map, "z", 0d);
                float yaw = (float) numberFromMap(map, "yaw", 0d);
                float pitch = (float) numberFromMap(map, "pitch", 0d);
                out.add(new Location(world, x, y, z, yaw, pitch));
            } catch (Exception ignored) {
            }
        }

        return out;
    }

    public static Location readLocation(ConfigurationSection section, String worldName) {
        if (section == null || worldName == null) return null;

        World world = Bukkit.getWorld(worldName);
        if (world == null) return null;

        return new Location(
                world,
                section.getDouble("x"),
                section.getDouble("y"),
                section.getDouble("z"),
                (float) section.getDouble("yaw"),
                (float) section.getDouble("pitch")
        );
    }

    public static void writeLocation(ConfigurationSection section, Location location) {
        section.set("x", location.getX());
        section.set("y", location.getY());
        section.set("z", location.getZ());
        section.set("yaw", location.getYaw());
        section.set("pitch", location.getPitch());
    }

    private static double numberFromMap(Map<?, ?> map, String key, double defaultValue) {
        Object value = map.get(key);
        if (value instanceof Number number) return number.doubleValue();

        if (value instanceof String text) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
            }
        }

        return defaultValue;
    }

    // -------------------------------------------------------------------------
    // Block pool sampling
    // -------------------------------------------------------------------------

    public static List<Material> sampleArenaBlocks(
            Location pos1,
            Location pos2,
            int arenaScanMaxBlocks,
            Predicate<Material> allowMaterial
    ) {
        if (pos1 == null || pos2 == null || pos1.getWorld() == null || pos2.getWorld() == null) {
            return Collections.emptyList();
        }

        int minX = Math.min(pos1.getBlockX(), pos2.getBlockX());
        int maxX = Math.max(pos1.getBlockX(), pos2.getBlockX());
        int minY = Math.max(pos1.getWorld().getMinHeight(), Math.min(pos1.getBlockY(), pos2.getBlockY()));
        int maxY = Math.min(pos1.getWorld().getMaxHeight() - 1, Math.max(pos1.getBlockY(), pos2.getBlockY()));
        int minZ = Math.min(pos1.getBlockZ(), pos2.getBlockZ());
        int maxZ = Math.max(pos1.getBlockZ(), pos2.getBlockZ());

        Map<Material, Integer> counts = new HashMap<>();
        int scanned = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (scanned++ >= arenaScanMaxBlocks) break;

                    Block block = pos1.getWorld().getBlockAt(x, y, z);
                    Material material = block.getType();
                    if (!allowMaterial.test(material)) continue;

                    counts.put(material, counts.getOrDefault(material, 0) + 1);
                }
                if (scanned >= arenaScanMaxBlocks) break;
            }
            if (scanned >= arenaScanMaxBlocks) break;
        }

        if (counts.isEmpty()) return Collections.emptyList();

        List<Material> weighted = new ArrayList<>();
        for (Map.Entry<Material, Integer> entry : counts.entrySet()) {
            int weight = Math.max(1, Math.min(40, entry.getValue() / 8));
            for (int i = 0; i < weight; i++) {
                weighted.add(entry.getKey());
            }
        }

        return weighted;
    }

    public static void preGenerateArenaBlocks(
            Location pos1,
            Location pos2,
            int arenaScanMaxBlocks,
            Predicate<Material> allowMaterial,
            List<Material> cachedBlockPool,
            Consumer<Runnable> asyncRunner,
            Runnable markGenerated
    ) {
        if (pos1 == null || pos2 == null || pos1.getWorld() == null || pos2.getWorld() == null) {
            return;
        }

        asyncRunner.accept(() -> {
            List<Material> blocks = sampleArenaBlocks(pos1, pos2, arenaScanMaxBlocks, allowMaterial);
            if (!blocks.isEmpty()) {
                cachedBlockPool.clear();
                cachedBlockPool.addAll(blocks);
            }
            markGenerated.run();
        });
    }
}
