package com.moonsama.minecraft.items.perks;

import com.moonsama.minecraft.items.Offhand;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.List;
import java.util.Locale;

/**
 * Moonbag: a random buff that changes every five minutes and gets stronger the longer the bag
 * stays equipped (three tiers of six cycles). Legacy tier tables reproduced verbatim.
 */
public final class MoonbagPerk extends Perk {
    static final int CYCLE_LENGTH_TICKS = 60 * 5 * 20;
    static final int CYCLES_PER_TIER = 6;
    private static final String CYCLES = "cycles";
    private static final String CYCLE_TICKS = "cycleTicks";
    private static final String CURRENT = "current";

    /** A tier entry: vanilla effect key ({@code minecraft:<key>}) and level (1 = I). */
    record Buff(String key, int level) {
        PotionEffectType type() {
            PotionEffectType type = Registry.EFFECT.get(NamespacedKey.minecraft(key));
            if (type == null) {
                throw new IllegalStateException("Unknown potion effect " + key);
            }
            return type;
        }
    }

    private static Buff b(String key, int level) {
        return new Buff(key, level);
    }

    static final List<Buff> TIER_1 = List.of(
            b("haste", 1), b("jump_boost", 1), b("resistance", 1), b("health_boost", 1), b("absorption", 1));

    static final List<Buff> TIER_2 = List.of(
            b("speed", 1), b("speed", 1), b("haste", 1), b("haste", 1), b("haste", 2), b("strength", 1), b("strength", 1),
            b("jump_boost", 1), b("jump_boost", 1), b("jump_boost", 2), b("regeneration", 1), b("resistance", 1),
            b("resistance", 1), b("resistance", 2), b("fire_resistance", 1), b("fire_resistance", 1),
            b("water_breathing", 1), b("water_breathing", 1), b("night_vision", 1), b("night_vision", 1),
            b("health_boost", 1), b("health_boost", 1), b("health_boost", 2), b("absorption", 1), b("absorption", 1),
            b("absorption", 2), b("slow_falling", 1), b("slow_falling", 1));

    static final List<Buff> TIER_3 = List.of(
            b("speed", 1), b("speed", 1), b("speed", 2), b("strength", 1), b("strength", 1), b("strength", 2),
            b("regeneration", 1), b("regeneration", 1), b("regeneration", 2), b("resistance", 2), b("resistance", 2),
            b("resistance", 3), b("fire_resistance", 1), b("fire_resistance", 1), b("fire_resistance", 1),
            b("water_breathing", 1), b("water_breathing", 1), b("water_breathing", 1), b("health_boost", 2),
            b("health_boost", 2), b("health_boost", 3), b("absorption", 2), b("absorption", 2), b("absorption", 3),
            b("luck", 1), b("slow_falling", 1), b("slow_falling", 1), b("slow_falling", 1), b("conduit_power", 1),
            b("dolphins_grace", 1));

    public MoonbagPerk(PerkContext ctx, Offhand offhand) {
        super(ctx, offhand);
    }

    @Override
    public void onEquip(Player player, PerkState state) {
        state.put(CYCLES, 0);
        state.put(CYCLE_TICKS, 0);
        roll(player, state);
    }

    @Override
    public void tick(Player player, PerkState state) {
        int cycleTicks = state.<Integer>get(CYCLE_TICKS) + PERIOD;
        if (cycleTicks >= CYCLE_LENGTH_TICKS) {
            cycleTicks = 0;
            state.put(CYCLES, state.<Integer>get(CYCLES) + 1);
            roll(player, state);
        }
        state.put(CYCLE_TICKS, cycleTicks);
        Buff current = state.get(CURRENT);
        if (current != null) {
            int remaining = Math.max(PERIOD * 2, CYCLE_LENGTH_TICKS - cycleTicks);
            player.addPotionEffect(new PotionEffect(current.type(), remaining, current.level() - 1, true, false, true));
        }
    }

    private void roll(Player player, PerkState state) {
        Buff previous = state.get(CURRENT);
        if (previous != null) {
            player.removePotionEffect(previous.type());
        }
        Buff next = pick(state.<Integer>get(CYCLES), previous == null ? null : previous.key(), ctx.random());
        state.put(CURRENT, next);
        ctx.actionBar(player, "Moonbag: " + describe(next), NamedTextColor.LIGHT_PURPLE);
    }

    /** Picks from the tier for {@code cycles}, never repeating the effect that just ended. */
    static Buff pick(int cycles, String lastKey, java.util.Random random) {
        List<Buff> tier = cycles < CYCLES_PER_TIER ? TIER_1 : cycles < CYCLES_PER_TIER * 2 ? TIER_2 : TIER_3;
        List<Buff> candidates = tier.stream().filter(buff -> !buff.key().equals(lastKey)).toList();
        if (candidates.isEmpty()) {
            candidates = tier;
        }
        return candidates.get(random.nextInt(candidates.size()));
    }

    static String describe(Buff buff) {
        String name = buff.key().replace('_', ' ');
        name = Character.toUpperCase(name.charAt(0)) + name.substring(1).toLowerCase(Locale.ROOT);
        return buff.level() > 1 ? name + " " + "I".repeat(buff.level()).replace("IIII", "IV") : name;
    }

    @Override
    public void onUnequip(Player player, PerkState state) {
        Buff current = state.get(CURRENT);
        if (current != null) {
            player.removePotionEffect(current.type());
        }
    }
}
