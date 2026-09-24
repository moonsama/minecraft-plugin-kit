package com.moonsama.minecraft.wardrobe;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Per-player budget for MineSkin signing requests. Every <em>new</em> look costs one request at
 * the signing backend (looks already signed on this server are cache hits and are not counted),
 * so a player cycling through outfits could otherwise drain the operator's MineSkin quota.
 *
 * <p>Two limits, both optional: a minimum gap between two requests ({@code cooldown}) and a
 * maximum number of requests per rolling hour ({@code perHour}). Zero disables a limit.
 */
public final class SigningBudget {
    private static final Duration WINDOW = Duration.ofHours(1);

    private final Duration cooldown;
    private final int perHour;
    private final Supplier<Instant> clock;
    private final Map<UUID, Deque<Instant>> requests = new ConcurrentHashMap<>();

    public SigningBudget(Duration cooldown, int perHour) {
        this(cooldown, perHour, Instant::now);
    }

    SigningBudget(Duration cooldown, int perHour, Supplier<Instant> clock) {
        this.cooldown = cooldown == null || cooldown.isNegative() ? Duration.ZERO : cooldown;
        this.perHour = Math.max(0, perHour);
        this.clock = clock;
    }

    public boolean enabled() {
        return !cooldown.isZero() || perHour > 0;
    }

    public Duration cooldown() {
        return cooldown;
    }

    public int perHour() {
        return perHour;
    }

    /**
     * Records a signing request for {@code player} if the budget allows it. Returns empty when
     * the request may proceed, otherwise how long the player has to wait.
     */
    public Optional<Duration> tryAcquire(UUID player) {
        if (!enabled()) {
            return Optional.empty();
        }
        Instant now = clock.get();
        Deque<Instant> history = requests.computeIfAbsent(player, ignored -> new ArrayDeque<>());
        synchronized (history) {
            Instant windowStart = now.minus(WINDOW);
            while (!history.isEmpty() && !history.peekFirst().isAfter(windowStart)) {
                history.pollFirst();
            }
            Instant last = history.peekLast();
            if (last != null && !cooldown.isZero()) {
                Instant readyAt = last.plus(cooldown);
                if (readyAt.isAfter(now)) {
                    return Optional.of(Duration.between(now, readyAt));
                }
            }
            if (perHour > 0 && history.size() >= perHour) {
                return Optional.of(Duration.between(now, history.peekFirst().plus(WINDOW)));
            }
            history.addLast(now);
            return Optional.empty();
        }
    }
    // History is deliberately kept across relogs; otherwise leaving and rejoining would reset
    // the hourly cap. Entries are pruned to the last hour on every call, so memory stays small.
}
