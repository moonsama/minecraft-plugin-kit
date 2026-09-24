package com.moonsama.minecraft.gatekeeper;

import com.moonsama.minecraft.api.AssetHolding;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * One way into the server: a holding in {@code collection}, optionally limited to specific
 * token ids and a minimum balance.
 */
public record Pass(String collection, Set<String> tokenIds, BigDecimal minBalance) {
    public Pass {
        if (collection == null || collection.isBlank()) {
            throw new IllegalArgumentException("pass without collection");
        }
        tokenIds = tokenIds == null ? Set.of() : Set.copyOf(tokenIds);
    }

    public static Pass any(String collection) {
        return new Pass(collection, Set.of(), null);
    }

    public boolean matches(AssetHolding holding) {
        if (!collection.equals(holding.collection())) {
            return false;
        }
        if (!tokenIds.isEmpty() && !tokenIds.contains(holding.tokenId())) {
            return false;
        }
        if (minBalance == null) {
            return holding.hasPositiveBalance();
        }
        try {
            return holding.balance() != null && new BigDecimal(holding.balance()).compareTo(minBalance) >= 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** The first holding that satisfies any of {@code passes}. */
    public static Optional<AssetHolding> firstMatch(List<Pass> passes, List<AssetHolding> holdings) {
        for (AssetHolding holding : holdings) {
            for (Pass pass : passes) {
                if (pass.matches(holding)) {
                    return Optional.of(holding);
                }
            }
        }
        return Optional.empty();
    }

    public String describe() {
        StringBuilder text = new StringBuilder(collection);
        if (!tokenIds.isEmpty()) {
            text.append(" #").append(String.join(", #", tokenIds.stream().sorted().toList()));
        }
        if (minBalance != null) {
            text.append(" ≥ ").append(minBalance.stripTrailingZeros().toPlainString());
        }
        return text.toString();
    }
}
