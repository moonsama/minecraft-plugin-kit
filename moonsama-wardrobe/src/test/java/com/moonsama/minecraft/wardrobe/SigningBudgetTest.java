package com.moonsama.minecraft.wardrobe;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SigningBudgetTest {
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    private SigningBudget budget(long cooldownSeconds, int perHour) {
        return new SigningBudget(Duration.ofSeconds(cooldownSeconds), perHour, now::get);
    }

    private void advance(Duration by) {
        now.set(now.get().plus(by));
    }

    @Test
    void cooldownSeparatesRequestsPerPlayer() {
        SigningBudget budget = budget(20, 0);
        assertThat(budget.tryAcquire(alice)).isEmpty();
        assertThat(budget.tryAcquire(alice)).contains(Duration.ofSeconds(20));
        assertThat(budget.tryAcquire(bob)).as("independent per player").isEmpty();
        advance(Duration.ofSeconds(19));
        assertThat(budget.tryAcquire(alice)).contains(Duration.ofSeconds(1));
        advance(Duration.ofSeconds(1));
        assertThat(budget.tryAcquire(alice)).isEmpty();
    }

    @Test
    void hourlyCapIsARollingWindow() {
        SigningBudget budget = budget(0, 3);
        for (int i = 0; i < 3; i++) {
            assertThat(budget.tryAcquire(alice)).isEmpty();
            advance(Duration.ofMinutes(10));
        }
        // 30 minutes in, three used: must wait until the first one leaves the window.
        assertThat(budget.tryAcquire(alice)).contains(Duration.ofMinutes(30));
        advance(Duration.ofMinutes(30));
        assertThat(budget.tryAcquire(alice)).isEmpty();
        assertThat(budget.tryAcquire(alice)).contains(Duration.ofMinutes(10));
    }

    @Test
    void deniedAttemptsDoNotConsumeBudget() {
        SigningBudget budget = budget(0, 2);
        assertThat(budget.tryAcquire(alice)).isEmpty();
        assertThat(budget.tryAcquire(alice)).isEmpty();
        for (int i = 0; i < 5; i++) {
            assertThat(budget.tryAcquire(alice)).isPresent();
        }
        advance(Duration.ofHours(1));
        assertThat(budget.tryAcquire(alice)).isEmpty();
        assertThat(budget.tryAcquire(alice)).isEmpty();
    }

    @Test
    void zeroDisablesEverything() {
        SigningBudget budget = budget(0, 0);
        assertThat(budget.enabled()).isFalse();
        for (int i = 0; i < 100; i++) {
            assertThat(budget.tryAcquire(alice)).isEmpty();
        }
        assertThat(budget(-5, -1).enabled()).isFalse();
    }

    @Test
    void bundledConfigHasTheDefaultBudget() throws Exception {
        YamlConfiguration config;
        try (var reader = new InputStreamReader(
                getClass().getResourceAsStream("/config.yml"), StandardCharsets.UTF_8)) {
            config = YamlConfiguration.loadConfiguration(reader);
        }
        assertThat(config.getLong("signing-budget.cooldown-seconds")).isEqualTo(20);
        assertThat(config.getInt("signing-budget.max-per-hour")).isEqualTo(30);
    }
}
