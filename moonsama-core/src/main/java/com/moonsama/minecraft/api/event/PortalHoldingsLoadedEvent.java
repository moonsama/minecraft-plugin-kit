package com.moonsama.minecraft.api.event;

import com.moonsama.minecraft.api.AssetHolding;
import com.moonsama.minecraft.api.PortalPlayer;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public final class PortalHoldingsLoadedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final PortalPlayer player;
    private final List<AssetHolding> holdings;

    public PortalHoldingsLoadedEvent(PortalPlayer player, List<AssetHolding> holdings) {
        this.player = player;
        this.holdings = List.copyOf(holdings);
    }

    public PortalPlayer player() {
        return player;
    }

    public List<AssetHolding> holdings() {
        return holdings;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
