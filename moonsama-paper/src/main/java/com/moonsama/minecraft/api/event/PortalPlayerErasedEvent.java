package com.moonsama.minecraft.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

public final class PortalPlayerErasedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID mojangUuid;

    public PortalPlayerErasedEvent(UUID mojangUuid) {
        this.mojangUuid = mojangUuid;
    }

    public UUID mojangUuid() {
        return mojangUuid;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
