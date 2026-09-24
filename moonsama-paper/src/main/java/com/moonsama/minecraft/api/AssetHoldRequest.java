package com.moonsama.minecraft.api;

import java.util.UUID;

public record AssetHoldRequest(
        String businessKey,
        UUID mojangUuid,
        String collection,
        String tokenId,
        int ttlSeconds,
        String reference
) {
}
