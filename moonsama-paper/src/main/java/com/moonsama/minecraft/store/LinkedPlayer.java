package com.moonsama.minecraft.store;

import java.time.Instant;
import java.util.UUID;

public record LinkedPlayer(
        UUID mojangUuid,
        String playerId,
        String gamerTag,
        Instant linkedAt
) {
}
