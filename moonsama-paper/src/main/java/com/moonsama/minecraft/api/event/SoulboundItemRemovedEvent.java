package com.moonsama.minecraft.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.UUID;

/**
 * MoonsamaCore deleted a bound item because it was somewhere it does not belong. Fired on the
 * main thread; schedule any inventory changes (such as rebuilding the item) for the next tick.
 */
public final class SoulboundItemRemovedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID owner;
    private final ItemStack item;
    private final Reason reason;

    public enum Reason {
        /** Dropped, picked up, or found in a container, bundle or ender chest. */
        ESCAPED,
        /** Found with a player who is not its owner. */
        NOT_OWNER
    }

    public SoulboundItemRemovedEvent(UUID owner, ItemStack item, Reason reason) {
        this.owner = owner;
        this.item = item.clone();
        this.reason = reason;
    }

    /** The owner, when they are online. */
    public Optional<UUID> owner() {
        return Optional.ofNullable(owner);
    }

    /** A copy of the deleted item. */
    public ItemStack item() {
        return item.clone();
    }

    public Reason reason() {
        return reason;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
