package com.moonsama.minecraft.store;

import java.time.Instant;
import java.util.UUID;

public record OAuthAttempt(
        String state,
        UUID mojangUuid,
        String codeVerifier,
        Instant expiresAt
) {
}
