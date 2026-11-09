package com.moonsama.minecraft.api;

import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface MoonsamaService {
    CompletableFuture<Optional<PortalPlayer>> linkedPlayer(UUID mojangUuid);

    CompletableFuture<List<AssetHolding>> holdings(UUID mojangUuid);

    CompletableFuture<HoldingsSnapshot> cachedHoldings(UUID mojangUuid);

    CompletableFuture<EconomyOperation> spend(Plugin owner, EconomyRequest request);

    CompletableFuture<EconomyOperation> reward(Plugin owner, EconomyRequest request);

    CompletableFuture<EconomyOperation> refund(Plugin owner, EconomyRefundRequest request);

    CompletableFuture<Optional<EconomyOperation>> operation(
            Plugin owner,
            String businessKey
    );

    CompletableFuture<AssetHold> placeHold(Plugin owner, AssetHoldRequest request);

    CompletableFuture<AssetHold> renewHold(
            Plugin owner,
            String businessKey,
            int ttlSeconds
    );

    CompletableFuture<AssetHold> releaseHold(Plugin owner, String businessKey);

    CompletableFuture<Optional<AssetHold>> hold(Plugin owner, String businessKey);
}
