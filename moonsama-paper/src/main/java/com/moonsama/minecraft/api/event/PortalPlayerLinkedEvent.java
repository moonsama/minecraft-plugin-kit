package com.moonsama.minecraft.api.event;

import com.moonsama.minecraft.api.PortalPlayer;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

public final class PortalPlayerLinkedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final PortalPlayer player;

    public PortalPlayerLinkedEvent(PortalPlayer player) {
        this.player = player;
    }

    public PortalPlayer player() {
        return player;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
