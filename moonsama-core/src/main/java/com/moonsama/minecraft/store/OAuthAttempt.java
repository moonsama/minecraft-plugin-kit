package com.moonsama.minecraft.store;

import java.time.Instant;
import java.util.UUID;

/**
 * A pending {@code /moonsama link} started in-game. {@code mojangName} is the Minecraft name at
 * the time the attempt was created; it is shown on the browser confirmation page so the Portal
 * user can see which Minecraft account they are about to link.
 */
public record OAuthAttempt(
        String state,
        UUID mojangUuid,
        String mojangName,
        String codeVerifier,
        Instant expiresAt
) {
}
