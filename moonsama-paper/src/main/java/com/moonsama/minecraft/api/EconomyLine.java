package com.moonsama.minecraft.api;

import java.util.UUID;

public record EconomyLine(
        UUID mojangUuid,
        String collection,
        String tokenId,
        String amount
) {
}
