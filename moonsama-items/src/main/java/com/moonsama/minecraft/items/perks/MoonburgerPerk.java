package com.moonsama.minecraft.items.perks;

import com.moonsama.minecraft.items.Offhand;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** Moonburger: a snack that restores 4 food and is delivered again two minutes later. */
public final class MoonburgerPerk extends ConsumablePerk {
    static final long COOLDOWN_MS = 2 * 60_000;
    static final int FOOD_MODEL = 1;      // cooked beef
    static final int BUZZER_MODEL = 53;   // iron sword
    static final int FOOD_BONUS = 4;

    public MoonburgerPerk(PerkContext ctx, Offhand offhand) {
        super(ctx, offhand, Material.COOKED_BEEF, FOOD_MODEL, BUZZER_MODEL, COOLDOWN_MS, FOOD_BONUS);
    }

    @Override
    protected void onConsumed(Player player, PerkState state) {
        // Nutrition is handled in onFoodChange; nothing else happens.
    }

    @Override
    protected String readyHint() {
        return "Eat it from the off-hand";
    }
}
