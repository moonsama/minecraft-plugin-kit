package com.moonsama.minecraft.store;

import com.moonsama.minecraft.api.AssetHold;
import com.moonsama.minecraft.api.EconomyOperation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class JournalRecords {
    private JournalRecords() {
    }

    public record ResolvedLine(
            int lineNumber,
            UUID mojangUuid,
            String playerId,
            String collection,
            String tokenId,
            String amount
    ) {
    }

    public record Write(
            UUID operationId,
            String ownerPlugin,
            String businessKey,
            String portalKey,
            String payloadHash,
            EconomyOperation.Kind kind,
            EconomyOperation.State state,
            List<ResolvedLine> lines,
            String description,
            String reference,
            String originalPortalKey,
            int attempts,
            Instant nextAttemptAt,
            EconomyOperation.Receipt receipt,
            EconomyOperation.Failure failure,
            Instant createdAt,
            Instant updatedAt
    ) {
        public Write {
            lines = List.copyOf(lines);
        }
    }

    public record Hold(
            UUID operationId,
            String ownerPlugin,
            String businessKey,
            String payloadHash,
            UUID mojangUuid,
            String playerId,
            String collection,
            String tokenId,
            int ttlSeconds,
            String builderReference,
            String portalReference,
            String holdId,
            AssetHold.State state,
            Instant expiresAt,
            AssetHold.Failure failure,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record CachedCollection(
            String playerId,
            String collection,
            boolean known,
            List<com.moonsama.minecraft.api.AssetHolding> holdings,
            Instant fetchedAt,
            long generation
    ) {
        public CachedCollection {
            holdings = List.copyOf(holdings);
        }
    }

    public record CachedPlayer(
            String playerId,
            boolean known,
            List<com.moonsama.minecraft.api.AssetHolding> holdings,
            Instant fetchedAt
    ) {
        public CachedPlayer {
            holdings = List.copyOf(holdings);
        }
    }

    public record Invalidation(
            String playerId,
            long generation
    ) {
    }

    public record FeedCheckpoint(
            String feedName,
            long since,
            String cursor
    ) {
    }

    public record OutboxEvent(
            String eventId,
            String type,
            String aggregateId,
            String payload
    ) {
    }
}
