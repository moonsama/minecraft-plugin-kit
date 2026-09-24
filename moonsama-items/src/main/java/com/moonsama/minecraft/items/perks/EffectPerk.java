package com.moonsama.minecraft.items.perks;

import com.moonsama.minecraft.items.Offhand;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * A constant potion effect while equipped: Moonsama Egg (Haste II), Moonana (Jump Boost I),
 * Moondrink (Haste III, nights only).
 */
public final class EffectPerk extends Perk {
    private static final int DURATION_TICKS = 40;
    private static final String CHEERED = "cheered";

    private final PotionEffectType type;
    private final int amplifier;
    private final boolean nightOnly;

    public EffectPerk(PerkContext ctx, Offhand offhand, PotionEffectType type, int amplifier, boolean nightOnly) {
        super(ctx, offhand);
        this.type = type;
        this.amplifier = amplifier;
        this.nightOnly = nightOnly;
    }

    @Override
    public void onEquip(Player player, PerkState state) {
        if (nightOnly && isDay(player)) {
            ctx.actionBar(player, "The Moondrink only works at night.", NamedTextColor.LIGHT_PURPLE);
            ctx.chat(player, "The Moondrink only works at night.", NamedTextColor.LIGHT_PURPLE);
        }
    }

    @Override
    public void tick(Player player, PerkState state) {
        if (nightOnly) {
            if (isDay(player)) {
                state.remove(CHEERED);
                return;
            }
            if (!state.has(CHEERED)) {
                state.put(CHEERED, Boolean.TRUE);
                ctx.actionBar(player, "Cheers!", NamedTextColor.LIGHT_PURPLE);
            }
        }
        player.addPotionEffect(new PotionEffect(type, DURATION_TICKS, amplifier, true, false, true));
    }

    @Override
    public void onUnequip(Player player, PerkState state) {
        PotionEffect current = player.getPotionEffect(type);
        if (current != null && current.isAmbient() && current.getDuration() <= DURATION_TICKS) {
            player.removePotionEffect(type);
        }
    }

    /** Legacy gate: the drink works unless the world time is within (0, 12000). */
    static boolean isDay(Player player) {
        long time = player.getWorld().getTime();
        return time > 0 && time < 12000;
    }
}
