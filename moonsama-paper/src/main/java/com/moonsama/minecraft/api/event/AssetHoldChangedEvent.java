package com.moonsama.minecraft.api.event;

import com.moonsama.minecraft.api.AssetHold;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public final class AssetHoldChangedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final AssetHold hold;

    public AssetHoldChangedEvent(AssetHold hold) {
        this.hold = hold;
    }

    public AssetHold hold() {
        return hold;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
