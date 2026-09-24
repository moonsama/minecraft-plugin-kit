package com.moonsama.minecraft.items.perks;

import com.moonsama.minecraft.items.Offhand;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;

/**
 * The gameplay behaviour of one cosmetic off-hand while it sits in the player's off-hand.
 *
 * <p>One instance per off-hand definition; per-player data lives in the {@link PerkState}
 * that is handed to every hook. All hooks run on the main thread. Ported from the legacy
 * {@code moonsama-offhands} plugin; constants are the legacy values unless noted.
 */
public abstract class Perk {
    /** Ticks between {@link #tick} calls (legacy {@code Offhands.PERIOD}). */
    public static final int PERIOD = 5;

    protected final PerkContext ctx;
    protected final Offhand offhand;

    protected Perk(PerkContext ctx, Offhand offhand) {
        this.ctx = ctx;
        this.offhand = offhand;
    }

    public Offhand offhand() {
        return offhand;
    }

    /** Called when the off-hand appears in the player's off-hand slot. */
    public void onEquip(Player player, PerkState state) {
    }

    /** Called when it leaves the slot, the player quits, or the plugin disables. */
    public void onUnequip(Player player, PerkState state) {
    }

    /** Called every {@link #PERIOD} ticks while equipped. */
    public void tick(Player player, PerkState state) {
    }

    /** Right-click with the off-hand (or on a block) while equipped. */
    public void onInteract(Player player, PlayerInteractEvent event, PerkState state) {
    }

    /** The player is about to eat/drink the off-hand item. */
    public void onConsume(Player player, PlayerItemConsumeEvent event, PerkState state) {
    }

    /** Food change right after a consume of the off-hand item. */
    public void onFoodChange(Player player, FoodLevelChangeEvent event, PerkState state) {
    }

    /** The equipped player was hurt by another player. */
    public void onDamagedByPlayer(Player victim, Player attacker, EntityDamageByEntityEvent event, PerkState state) {
    }

    /** The equipped player hit another player. */
    public void onAttackPlayer(Player attacker, Player victim, EntityDamageByEntityEvent event, PerkState state) {
    }

    public void onSneak(Player player, PlayerToggleSneakEvent event, PerkState state) {
    }

    public void onDeath(Player player, PerkState state) {
    }

    /** True when {@link #onEquip} should reset an in-progress cooldown (legacy Moonflake). */
    public boolean resetsCooldownOnEquip() {
        return false;
    }
}
