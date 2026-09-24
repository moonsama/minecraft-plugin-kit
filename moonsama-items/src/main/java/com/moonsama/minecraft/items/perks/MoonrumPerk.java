package com.moonsama.minecraft.items.perks;

import com.moonsama.minecraft.items.Offhand;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/** Moonrum: drink for Strength I (30 s) with the nausea and slowness to match. 2 min cooldown. */
public final class MoonrumPerk extends ConsumablePerk {
    static final long EFFECT_MS = 30_000;
    static final long COOLDOWN_MS = EFFECT_MS * 4;
    static final int DRINK_MODEL = 1;     // potion
    static final int EMPTY_MODEL = 56;    // iron sword
    static final int FOOD_BONUS = 2;
    /** Sound event from the Moonsama resource pack (ported). */
    static final String LAUGH_SOUND = "moonsama.pirate_laugh";

    public MoonrumPerk(PerkContext ctx, Offhand offhand) {
        super(ctx, offhand, Material.POTION, DRINK_MODEL, EMPTY_MODEL, COOLDOWN_MS, FOOD_BONUS);
    }

    @Override
    protected void onConsumed(Player player, PerkState state) {
        int ticks = (int) (EFFECT_MS / 50);
        player.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, ticks, 0, false, true, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, ticks, 0, true, false, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, ticks, 0, true, false, true));
        player.getWorld().playSound(player.getLocation(), LAUGH_SOUND, SoundCategory.PLAYERS, 5f, 0.8f);
    }

    @Override
    protected String readyHint() {
        return "Drink it from the off-hand";
    }
}
