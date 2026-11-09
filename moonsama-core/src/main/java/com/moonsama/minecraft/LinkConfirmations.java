package com.moonsama.minecraft;

import com.moonsama.minecraft.store.LinkedPlayer;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Links that completed Portal login but still wait for the Portal user to confirm, in the
 * browser, that the named Minecraft account is theirs.
 *
 * <p>Without this step anyone could run {@code /moonsama link}, hand the resulting Portal URL to
 * somebody else and - once that person logged in - own their Portal identity in-game. The
 * OAuth {@code state} only proves which Minecraft player <em>started</em> the flow, not that
 * the browser belongs to that player. The confirmation page shows the Minecraft name and
 * requires an explicit click, which is the only point where the Portal account owner can
 * object.
 *
 * <p>Entries live in memory only: they are short-lived and a restart simply asks the player to
 * link again.
 */
final class LinkConfirmations {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();

    record Pending(String token, LinkedPlayer link, String minecraftName, Instant expiresAt) {
    }

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final Supplier<Instant> clock;

    LinkConfirmations(Duration ttl) {
        this(ttl, Instant::now);
    }

    LinkConfirmations(Duration ttl, Supplier<Instant> clock) {
        this.ttl = ttl;
        this.clock = clock;
    }

    /** Stores a completed login and returns the one-time token the confirmation form must send. */
    Pending create(LinkedPlayer link, String minecraftName) {
        Instant now = clock.get();
        pending.values().removeIf(entry -> !entry.expiresAt().isAfter(now));
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        Pending entry = new Pending(BASE64_URL.encodeToString(bytes), link, minecraftName, now.plus(ttl));
        pending.put(entry.token(), entry);
        return entry;
    }

    /** Removes and returns the entry for {@code token}; empty when unknown, used or expired. */
    Optional<Pending> take(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        Pending entry = pending.remove(token);
        if (entry == null || !entry.expiresAt().isAfter(clock.get())) {
            return Optional.empty();
        }
        return Optional.of(entry);
    }

    int size() {
        return pending.size();
    }
}
