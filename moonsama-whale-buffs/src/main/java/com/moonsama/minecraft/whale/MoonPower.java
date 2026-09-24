package com.moonsama.minecraft.whale;

import com.moonsama.minecraft.api.AssetHolding;

import java.math.BigDecimal;
import java.util.List;

/** Moon Power arithmetic over Portal holdings. Pure. */
public final class MoonPower {
    private MoonPower() {
    }

    /** Sum of power over all holdings: overrides first, then per-collection value × balance. */
    public static double of(WhaleConfig config, List<AssetHolding> holdings) {
        double total = 0;
        for (AssetHolding holding : holdings) {
            total += of(config, holding);
        }
        return total;
    }

    public static double of(WhaleConfig config, AssetHolding holding) {
        if (!holding.hasPositiveBalance()) {
            return 0;
        }
        double perUnit = -1;
        for (WhaleConfig.PowerOverride override : config.overrides()) {
            if (override.collection().equals(holding.collection())
                    && (override.tokenIds().isEmpty() || override.tokenIds().contains(holding.tokenId()))) {
                perUnit = override.power();
                break;
            }
        }
        if (perUnit < 0) {
            Double configured = config.powerPerToken().get(holding.collection());
            if (configured == null) {
                return 0;
            }
            perUnit = configured;
        }
        return perUnit * units(holding.balance());
    }

    /** NFTs have balance 1; semi-fungibles count once per unit (fractions are floored). */
    static double units(String balance) {
        try {
            return Math.max(0, new BigDecimal(balance).setScale(0, java.math.RoundingMode.FLOOR).doubleValue());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static double extraHealth(WhaleConfig config, double power, boolean whaleMode) {
        double extra = power * config.healthPerPower();
        if (whaleMode) {
            extra = (extra + 20) * config.whaleMode().healthMultiplier() - 20;
        }
        return extra;
    }

    public static double damageFactor(WhaleConfig config, double power) {
        return 1 + power * config.damagePerPower();
    }
}
