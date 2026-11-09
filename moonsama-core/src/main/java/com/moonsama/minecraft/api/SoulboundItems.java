package com.moonsama.minecraft.api;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Binds items a plugin hands out to the player they belong to. MoonsamaCore then keeps every
 * bound item with its owner: it can move inside the owner's own inventory (when
 * {@link Movement#FREE}) but never into containers, item frames, the ground or another
 * player's hands, and a bound item found anywhere it does not belong is deleted. Registered with
 * Paper's {@code ServicesManager}.
 *
 * <p>Bound items must be ones the plugin can rebuild: an escaped copy is deleted, not returned.
 * Listen to {@link com.moonsama.minecraft.api.event.SoulboundItemRemovedEvent} to hand the
 * owner a fresh copy. All methods are main-thread only.
 */
public interface SoulboundItems {
    enum Movement {
        /** Anywhere in the owner's own hotbar, storage rows and off-hand slot. */
        FREE,
        /** Stays in the slot the plugin put it in (hats, helmets). */
        LOCKED
    }

    /** Marks {@code item} as belonging to {@code owner} and returns it. */
    ItemStack bind(ItemStack item, Player owner, Movement movement);

    boolean isBound(ItemStack item);

    /** True when {@code item} is bound to {@code player}. */
    boolean isBoundTo(ItemStack item, Player player);
}
