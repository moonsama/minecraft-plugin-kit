package com.moonsama.minecraft.items.perks;

import com.moonsama.minecraft.items.Offhand;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.List;

/**
 * Moonflake: when another player hits you, a blizzard slows and freezes everyone around you.
 * 3 min cooldown, which restarts when the flake is equipped (legacy anti-swap rule).
 */
public final class MoonflakePerk extends Perk {
    static final long COOLDOWN_MS = 3 * 60_000;
    static final long DEBUFF_MS = 10_000;
    static final int DEBUFF_DISTANCE = 20;
    static final int SLOWNESS_AMPLIFIER = 5;
    static final int FREEZE_TICKS = 140;
    static final int BUZZER_MODEL = 60;   // iron sword

    public MoonflakePerk(PerkContext ctx, Offhand offhand) {
        super(ctx, offhand);
    }

    @Override
    public boolean resetsCooldownOnEquip() {
        return true;
    }

    @Override
    public void onEquip(Player player, PerkState state) {
        state.startCooldown(COOLDOWN_MS);
        updateForm(player, state);
    }

    @Override
    public void tick(Player player, PerkState state) {
        updateForm(player, state);
    }

    @Override
    public void onDamagedByPlayer(Player victim, Player attacker, EntityDamageByEntityEvent event, PerkState state) {
        if (!state.ready()) {
            return;
        }
        state.startCooldown(COOLDOWN_MS);
        int ticks = (int) (DEBUFF_MS / 50);
        for (Entity entity : victim.getNearbyEntities(DEBUFF_DISTANCE, DEBUFF_DISTANCE, DEBUFF_DISTANCE)) {
            if (!(entity instanceof LivingEntity living) || entity.equals(victim)) {
                continue;
            }
            living.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, ticks, SLOWNESS_AMPLIFIER, false, true, true));
            living.setFreezeTicks(Math.max(living.getFreezeTicks(), Math.min(living.getMaxFreezeTicks(), FREEZE_TICKS)));
            if (living instanceof Player other) {
                ctx.actionBar(other, "A sudden blizzard locks you in your tracks!", NamedTextColor.AQUA);
            }
        }
        ctx.actionBar(victim, "Your Moonflake unleashes a blizzard!", NamedTextColor.AQUA);
        updateForm(victim, state);
    }

    private void updateForm(Player player, PerkState state) {
        if (state.ready()) {
            ctx.resetForm(player, offhand);
        } else {
            ctx.setForm(player, offhand, Material.IRON_SWORD, BUZZER_MODEL, List.of("Charging"));
            ctx.showCooldown(player, offhand, state.remainingTicks());
        }
    }

    @Override
    public void onUnequip(Player player, PerkState state) {
        ctx.resetForm(player, offhand);
    }
}
