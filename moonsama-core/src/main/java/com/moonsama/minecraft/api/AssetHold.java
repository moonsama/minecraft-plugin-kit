package com.moonsama.minecraft.api;

import java.time.Instant;
import java.util.UUID;

public record AssetHold(
        UUID operationId,
        String ownerPlugin,
        String businessKey,
        UUID mojangUuid,
        String collection,
        String tokenId,
        int ttlSeconds,
        String reference,
        String holdId,
        State state,
        Instant expiresAt,
        Failure failure
) {
    public enum State {
        REQUESTED,
        PLACING,
        PLACEMENT_UNKNOWN,
        ACTIVE,
        RENEWING,
        RELEASING,
        RELEASED,
        LOST,
        EXPIRED,
        REJECTED
    }

    public record Failure(Integer status, String code, String message) {
    }
}
