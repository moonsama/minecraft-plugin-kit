package com.moonsama.minecraft.api;

import java.time.Instant;
import java.util.UUID;

public record PortalPlayer(
        UUID mojangUuid,
        String playerId,
        String gamerTag,
        Instant linkedAt
) {
}
