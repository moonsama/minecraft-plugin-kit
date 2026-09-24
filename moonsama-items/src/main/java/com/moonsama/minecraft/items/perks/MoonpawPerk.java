package com.moonsama.minecraft.items.perks;

import com.moonsama.minecraft.items.Offhand;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.List;

/**
 * Moonpaw: crouch for two seconds, then run or jump to pounce (Speed I for 10 s). 40 s cooldown.
 */
public final class MoonpawPerk extends Perk {
    static final long TRIGGER_MS = 2_000;
    static final long EFFECT_MS = 10_000;
    static final long COOLDOWN_MS = EFFECT_MS * 4;
    static final int POUNCE_MODEL = 54;   // iron sword
    static final int BUZZER_MODEL = 55;   // iron sword
    private static final long ARMED_WINDOW_MS = 3_000;
    private static final String SNEAK_SINCE = "sneakSince";
    private static final String ARMED_UNTIL = "armedUntil";
    private static final String CHARGED_TOLD = "chargedTold";

    public MoonpawPerk(PerkContext ctx, Offhand offhand) {
        super(ctx, offhand);
    }

    @Override
    public void onSneak(Player player, PlayerToggleSneakEvent event, PerkState state) {
        if (!state.ready()) {
            return;
        }
        if (event.isSneaking()) {
            state.put(SNEAK_SINCE, System.currentTimeMillis());
            state.remove(CHARGED_TOLD);
        } else {
            Long since = state.get(SNEAK_SINCE);
            state.remove(SNEAK_SINCE);
            if (since != null && System.currentTimeMillis() - since >= TRIGGER_MS) {
                state.put(ARMED_UNTIL, System.currentTimeMillis() + ARMED_WINDOW_MS);
                ctx.actionBar(player, "Run to pounce", NamedTextColor.YELLOW);
            }
        }
    }

    @Override
    public void tick(Player player, PerkState state) {
        long now = System.currentTimeMillis();
        Long since = state.get(SNEAK_SINCE);
        if (since != null && state.ready() && now - since >= TRIGGER_MS && !state.has(CHARGED_TOLD)) {
            state.put(CHARGED_TOLD, Boolean.TRUE);
            ctx.actionBar(player, "Release and run to pounce", NamedTextColor.YELLOW);
        }
        Long armedUntil = state.get(ARMED_UNTIL);
        if (armedUntil != null) {
            if (now > armedUntil) {
                state.remove(ARMED_UNTIL);
            } else if (player.isSprinting() || player.getVelocity().getY() > 0.1) {
                state.remove(ARMED_UNTIL);
                pounce(player, state);
            }
        }
        updateForm(player, state);
    }

    private void pounce(Player player, PerkState state) {
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, (int) (EFFECT_MS / 50), 0, false, true, true));
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_CAT_HISS, SoundCategory.PLAYERS, 0.4f, 0.8f);
        ctx.actionBar(player, "Go get them! Rawwwrrr!", NamedTextColor.GREEN);
        state.startCooldown(COOLDOWN_MS);
    }

    private void updateForm(Player player, PerkState state) {
        if (state.ready()) {
            ctx.resetForm(player, offhand);
        } else if (state.remainingMs() > COOLDOWN_MS - EFFECT_MS) {
            ctx.setForm(player, offhand, Material.IRON_SWORD, POUNCE_MODEL, List.of("Pouncing"));
        } else {
            ctx.setForm(player, offhand, Material.IRON_SWORD, BUZZER_MODEL, List.of("Recharging"));
            ctx.showCooldown(player, offhand, state.remainingTicks());
        }
    }

    @Override
    public void onUnequip(Player player, PerkState state) {
        ctx.resetForm(player, offhand);
    }
}
