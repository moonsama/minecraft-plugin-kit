package com.moonsama.minecraft.whale;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Parsed {@code config.yml}. */
public record WhaleConfig(
        boolean enabled,
        boolean requireEntitledSkin,
        Set<String> entitledCollections,
        Map<String, Double> powerPerToken,
        List<PowerOverride> overrides,
        double healthPerPower,
        double damagePerPower,
        boolean namesEnabled,
        List<NameTier> nameTiers,
        WhaleMode whaleMode
) {
    public record PowerOverride(String collection, Set<String> tokenIds, double power) {}

    /** {@code color} is a named text colour or {@code rainbow}. */
    public record NameTier(double minPower, String color) {}

    public record WhaleMode(
            boolean enabled,
            double scepterMinPower,
            int durationSeconds,
            int cooldownSeconds,
            double healthMultiplier,
            Lightning lightning,
            Material scepterMaterial,
            int scepterModel,
            String scepterName
    ) {}

    public enum Lightning { EFFECT, REAL, NONE }

    public WhaleConfig {
        entitledCollections = Set.copyOf(entitledCollections);
        powerPerToken = Map.copyOf(powerPerToken);
        overrides = List.copyOf(overrides);
        nameTiers = nameTiers.stream().sorted(Comparator.comparingDouble(NameTier::minPower)).toList();
    }

    public static WhaleConfig from(ConfigurationSection root) {
        Map<String, Double> perToken = new LinkedHashMap<>();
        ConfigurationSection perTokenSection = root.getConfigurationSection("power.per-token");
        if (perTokenSection != null) {
            for (String collection : perTokenSection.getKeys(false)) {
                perToken.put(collection, perTokenSection.getDouble(collection));
            }
        }
        List<PowerOverride> overrides = new ArrayList<>();
        for (Map<?, ?> raw : root.getMapList("power.overrides")) {
            Object collection = raw.get("collection");
            if (collection == null) {
                throw new IllegalArgumentException("power.overrides: every entry needs a collection");
            }
            Set<String> ids = new HashSet<>();
            Object tokenIds = raw.get("token-ids");
            if (tokenIds instanceof List<?> list) {
                list.forEach(id -> ids.add(String.valueOf(id)));
            } else if (tokenIds != null) {
                ids.add(String.valueOf(tokenIds));
            }
            Object power = raw.get("power");
            if (!(power instanceof Number number)) {
                throw new IllegalArgumentException("power.overrides: '" + collection + "' needs a numeric power");
            }
            overrides.add(new PowerOverride(String.valueOf(collection), ids, number.doubleValue()));
        }

        List<NameTier> tiers = new ArrayList<>();
        for (Map<?, ?> raw : root.getMapList("names.tiers")) {
            Object min = raw.get("min-power");
            Object color = raw.get("color");
            if (!(min instanceof Number number) || color == null) {
                throw new IllegalArgumentException("names.tiers: entries need min-power and color");
            }
            String colorName = String.valueOf(color).toLowerCase(Locale.ROOT);
            if (!colorName.equals("rainbow") && NameStyle.namedColor(colorName) == null) {
                throw new IllegalArgumentException("names.tiers: unknown colour '" + color + "'");
            }
            tiers.add(new NameTier(number.doubleValue(), colorName));
        }

        String lightningRaw = root.getString("whale-mode.lightning", "effect").trim().toUpperCase(Locale.ROOT);
        Lightning lightning;
        try {
            lightning = Lightning.valueOf(lightningRaw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("whale-mode.lightning: unknown value '" + lightningRaw + "'");
        }
        String materialRaw = root.getString("whale-mode.scepter.material", "FEATHER");
        Material material = Material.matchMaterial(materialRaw);
        if (material == null) {
            throw new IllegalArgumentException("whale-mode.scepter.material: unknown material '" + materialRaw + "'");
        }
        WhaleMode whaleMode = new WhaleMode(
                root.getBoolean("whale-mode.enabled", true),
                root.getDouble("whale-mode.scepter-min-power", 100),
                Math.max(1, root.getInt("whale-mode.duration-seconds", 120)),
                Math.max(0, root.getInt("whale-mode.cooldown-seconds", 300)),
                root.getDouble("whale-mode.health-multiplier", 1.5),
                lightning,
                material,
                root.getInt("whale-mode.scepter.custom-model-data", 2),
                root.getString("whale-mode.scepter.name", "<light_purple>Whale Scepter</light_purple>"));

        return new WhaleConfig(
                root.getBoolean("enabled", true),
                root.getBoolean("skins.require-entitled-skin", true),
                new HashSet<>(root.getStringList("skins.collections")),
                perToken,
                overrides,
                root.getDouble("health-per-power", 0.1),
                root.getDouble("damage-per-power", 0.025),
                root.getBoolean("names.enabled", true),
                tiers,
                whaleMode);
    }
}
