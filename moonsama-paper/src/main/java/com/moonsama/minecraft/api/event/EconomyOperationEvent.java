package com.moonsama.minecraft.api.event;

import com.moonsama.minecraft.api.EconomyOperation;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public final class EconomyOperationEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final EconomyOperation operation;

    public EconomyOperationEvent(EconomyOperation operation) {
        this.operation = operation;
    }

    public EconomyOperation operation() {
        return operation;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
