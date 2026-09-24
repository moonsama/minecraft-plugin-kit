package com.moonsama.minecraft.items.perks;

import com.moonsama.minecraft.items.Offhand;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

/**
 * Detectore: samples random blocks around the player and, when an ore is nearby, the gem glows in
 * the ore's colour until the block changes or the player walks away.
 */
public final class DetectorePerk extends Perk {
    static final int ITERATIONS = 20;
    static final int MAX_SAMPLES = 100;
    static final int TIMEOUT_TICKS = 20;
    static final double RADIUS = 3.5;
    static final double RELEASE_RADIUS = 5;
    private static final String TARGET = "target";
    private static final String MATERIAL = "material";
    private static final String TIMEOUT = "timeout";

    /** Highlight colours in resource-pack model order (base + index). */
    public enum Highlight {
        BLACK, BLUE, CYAN, GRAY, GREEN, LIGHT_BLUE, LIME, MAGENTA, ORANGE, PINK, PURPLE, RED, SILVER, WHITE, YELLOW;

        public int modelIndex() {
            return ordinal();
        }
    }

    /** Ore → colour, from the legacy {@code detectore.json} (nether ore keys corrected). */
    static final Map<Material, Highlight> ORES = Map.ofEntries(
            Map.entry(Material.COAL_ORE, Highlight.GRAY),
            Map.entry(Material.DEEPSLATE_COAL_ORE, Highlight.GRAY),
            Map.entry(Material.IRON_ORE, Highlight.SILVER),
            Map.entry(Material.DEEPSLATE_IRON_ORE, Highlight.SILVER),
            Map.entry(Material.RAW_IRON_BLOCK, Highlight.SILVER),
            Map.entry(Material.COPPER_ORE, Highlight.CYAN),
            Map.entry(Material.DEEPSLATE_COPPER_ORE, Highlight.CYAN),
            Map.entry(Material.RAW_COPPER_BLOCK, Highlight.CYAN),
            Map.entry(Material.GOLD_ORE, Highlight.YELLOW),
            Map.entry(Material.DEEPSLATE_GOLD_ORE, Highlight.YELLOW),
            Map.entry(Material.RAW_GOLD_BLOCK, Highlight.CYAN),
            Map.entry(Material.REDSTONE_ORE, Highlight.RED),
            Map.entry(Material.DEEPSLATE_REDSTONE_ORE, Highlight.RED),
            Map.entry(Material.EMERALD_ORE, Highlight.GREEN),
            Map.entry(Material.DEEPSLATE_EMERALD_ORE, Highlight.GREEN),
            Map.entry(Material.LAPIS_ORE, Highlight.BLUE),
            Map.entry(Material.DEEPSLATE_LAPIS_ORE, Highlight.BLUE),
            Map.entry(Material.DIAMOND_ORE, Highlight.LIGHT_BLUE),
            Map.entry(Material.DEEPSLATE_DIAMOND_ORE, Highlight.LIGHT_BLUE),
            Map.entry(Material.NETHER_GOLD_ORE, Highlight.YELLOW),
            Map.entry(Material.NETHER_QUARTZ_ORE, Highlight.WHITE),
            Map.entry(Material.ANCIENT_DEBRIS, Highlight.BLACK),
            Map.entry(Material.AMETHYST_BLOCK, Highlight.PURPLE),
            Map.entry(Material.BUDDING_AMETHYST, Highlight.PURPLE));

    /** First custom model data of the glowing variants, per Detectore id. */
    static final Map<String, Integer> GLOW_BASE = Map.of(
            "moonsama:detectore_emerald", 22,
            "moonsama:detectore_ruby", 37,
            "moonsama:detectore_sapphire", 52,
            "moonsama:detectore_amber", 67,
            "moonsama:detectore_el_detectore", 82);

    private final int glowBase;

    public DetectorePerk(PerkContext ctx, Offhand offhand) {
        super(ctx, offhand);
        this.glowBase = glowBaseOf(offhand.id()).orElseThrow(
                () -> new IllegalArgumentException("No Detectore glow models for " + offhand.id()));
    }

    public static Optional<Integer> glowBaseOf(String offhandId) {
        return Optional.ofNullable(GLOW_BASE.get(offhandId));
    }

    public static Optional<Highlight> highlightOf(Material material) {
        return Optional.ofNullable(ORES.get(material));
    }

    /** Custom model data for a glowing gem: base of this Detectore + colour index. */
    public static int glowModel(int base, Highlight highlight) {
        return base + highlight.modelIndex();
    }

    @Override
    public void onEquip(Player player, PerkState state) {
        state.put(TIMEOUT, 0);
    }

    @Override
    public void tick(Player player, PerkState state) {
        Block target = state.get(TARGET);
        if (target == null) {
            int timeout = state.get(TIMEOUT, () -> 0);
            if (timeout <= 0) {
                scan(player, state);
            } else {
                state.put(TIMEOUT, timeout - PERIOD);
            }
            return;
        }
        Material material = state.get(MATERIAL);
        boolean sameWorld = player.getWorld().equals(target.getWorld());
        double distanceSquared = sameWorld ? player.getLocation().distanceSquared(target.getLocation()) : Double.MAX_VALUE;
        if (target.getType() != material || distanceSquared > RELEASE_RADIUS * RELEASE_RADIUS) {
            release(player, state);
            scan(player, state);
        }
    }

    private void scan(Player player, PerkState state) {
        state.put(TIMEOUT, TIMEOUT_TICKS);
        Random random = ctx.random();
        int hits = 0;
        Location origin = player.getLocation();
        for (int samples = 0; samples < MAX_SAMPLES && hits < ITERATIONS; samples++) {
            Vector offset = insideUnitSphere(random).multiply(1 + (RADIUS - 1) * random.nextDouble());
            Block block = origin.clone().add(offset).getBlock();
            Material type = block.getType();
            if (type.isAir()) {
                continue;
            }
            hits++;
            Highlight highlight = ORES.get(type);
            if (highlight != null) {
                state.put(TARGET, block);
                state.put(MATERIAL, type);
                ctx.setForm(player, offhand, offhand.material(), glowModel(glowBase, highlight), List.of());
                return;
            }
        }
    }

    private void release(Player player, PerkState state) {
        state.remove(TARGET);
        state.remove(MATERIAL);
        ctx.resetForm(player, offhand);
    }

    static Vector insideUnitSphere(Random random) {
        while (true) {
            Vector v = new Vector(random.nextDouble() * 2 - 1, random.nextDouble() * 2 - 1, random.nextDouble() * 2 - 1);
            if (v.lengthSquared() <= 1) {
                return v;
            }
        }
    }

    @Override
    public void onUnequip(Player player, PerkState state) {
        release(player, state);
    }
}
