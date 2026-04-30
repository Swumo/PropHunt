package me.swumo.prophunt.utils;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

public final class GameConfigReader {

    private GameConfigReader() {
        throw new IllegalStateException("This is a utility class.");
    }

    public static int intSetting(FileConfiguration config, String primaryPath, String legacyPath, int defaultValue, int minValue) {
        int value;
        if (config.contains(primaryPath)) {
            value = config.getInt(primaryPath, defaultValue);
        } else if (legacyPath != null && config.contains(legacyPath)) {
            value = config.getInt(legacyPath, defaultValue);
        } else {
            value = defaultValue;
        }

        return Math.max(minValue, value);
    }

    public static List<Integer> intListSetting(FileConfiguration config, String path, List<Integer> fallback) {
        List<Integer> values = new ArrayList<>();
        if (config.isList(path)) {
            for (Object raw : config.getList(path, Collections.emptyList())) {
                if (raw instanceof Number number) {
                    values.add(Math.max(0, number.intValue()));
                } else if (raw instanceof String text) {
                    try {
                        values.add(Math.max(0, Integer.parseInt(text)));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        if (values.isEmpty()) values.addAll(fallback);

        values.sort(Comparator.reverseOrder());
        return values;
    }

    public static List<Material> materialListSetting(
            FileConfiguration config,
            String path,
            Collection<Material> fallback,
            Predicate<Material> validator
    ) {
        List<String> values = config.getStringList(path);
        List<Material> materials = new ArrayList<>();
        for (String value : values) {
            Material material = Material.matchMaterial(value);
            if (material == null || material == Material.AIR) continue;
            if (validator != null && !validator.test(material)) continue;

            materials.add(material);
        }

        if (materials.isEmpty()) materials.addAll(fallback);
        return materials;
    }

    public static Set<Material> materialSetSetting(FileConfiguration config, String path, Collection<Material> fallback) {
        Set<Material> materials = EnumSet.noneOf(Material.class);
        for (String value : config.getStringList(path)) {
            Material material = Material.matchMaterial(value);
            if (material == null || material == Material.AIR || !material.isBlock()) continue;

            materials.add(material);
        }

        if (materials.isEmpty() && fallback != null && !fallback.isEmpty()) {
            materials.addAll(fallback);
        }

        return materials;
    }
}
