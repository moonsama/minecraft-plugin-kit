package com.moonsama.minecraft.gatekeeper;

import com.moonsama.minecraft.api.AssetHolding;
import com.moonsama.minecraft.api.HoldingsSnapshot;

import java.util.List;
import java.util.Optional;

/** Turns what we know about a player into a verdict. Pure, so it can be unit tested. */
public final class Gate {
    public enum Reason { BYPASS, PASS, UNLINKED, DENIED, UNAVAILABLE }

    /** What to do with the player and why; {@code pass} is the holding that let them in. */
    public record Decision(GateConfig.Action action, Reason reason, Optional<AssetHolding> pass) {
        static Decision of(GateConfig.Action action, Reason reason) {
            return new Decision(action, reason, Optional.empty());
        }

        public boolean allowed() {
            return action == GateConfig.Action.ALLOW;
        }
    }

    private final GateConfig config;

    public Gate(GateConfig config) {
        this.config = config;
    }

    public GateConfig config() {
        return config;
    }

    public Decision forBypass() {
        return Decision.of(GateConfig.Action.ALLOW, Reason.BYPASS);
    }

    public Decision forUnlinked() {
        return Decision.of(config.unlinked(), Reason.UNLINKED);
    }

    /**
     * Verdict for a linked player from MoonsamaCore's cached snapshot. {@code UNKNOWN}
     * holdings (never loaded) count as unavailable; stale ones are still trusted.
     */
    public Decision forLinked(HoldingsSnapshot snapshot) {
        if (snapshot == null || snapshot.status() == HoldingsSnapshot.Status.UNKNOWN) {
            return Decision.of(config.unavailable(), Reason.UNAVAILABLE);
        }
        return forHoldings(snapshot.holdings());
    }

    public Decision forHoldings(List<AssetHolding> holdings) {
        Optional<AssetHolding> match = Pass.firstMatch(config.passes(), holdings);
        if (match.isPresent()) {
            return new Decision(GateConfig.Action.ALLOW, Reason.PASS, match);
        }
        return Decision.of(config.denied(), Reason.DENIED);
    }

    /** Message key for the kick screen matching a reason. */
    public static String kickMessageKey(Reason reason) {
        return switch (reason) {
            case UNLINKED -> "kick-unlinked";
            case UNAVAILABLE -> "kick-unavailable";
            default -> "kick-denied";
        };
    }

    public static String subtitleKey(Reason reason) {
        return switch (reason) {
            case UNLINKED -> "restricted-subtitle-unlinked";
            case UNAVAILABLE -> "restricted-subtitle-unavailable";
            default -> "restricted-subtitle-denied";
        };
    }
}
