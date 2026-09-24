package com.moonsama.minecraft.api;

import java.time.Instant;
import java.util.List;

public record HoldingsSnapshot(
        Status status,
        List<AssetHolding> holdings,
        Instant fetchedAt
) {
    public HoldingsSnapshot {
        holdings = List.copyOf(holdings);
    }

    public enum Status {
        FRESH,
        REFRESHING,
        STALE,
        UNKNOWN
    }
}
