package com.moonsama.minecraft.api;

import java.math.BigDecimal;

public record AssetHolding(
        String collection,
        String tokenId,
        String balance
) {
    public boolean hasPositiveBalance() {
        if (balance == null) {
            return false;
        }
        try {
            return new BigDecimal(balance).signum() > 0;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }
}
