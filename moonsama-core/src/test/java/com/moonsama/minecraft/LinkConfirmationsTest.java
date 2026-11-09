package com.moonsama.minecraft;

import com.moonsama.minecraft.store.LinkedPlayer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class LinkConfirmationsTest {
    private final LinkedPlayer link = new LinkedPlayer(UUID.randomUUID(), "plr_1", "Sama", Instant.now());

    @Test
    void tokenIsSingleUse() {
        LinkConfirmations confirmations = new LinkConfirmations(Duration.ofMinutes(5));
        LinkConfirmations.Pending pending = confirmations.create(link, "Steve");

        assertThat(pending.token()).hasSizeGreaterThanOrEqualTo(32);
        assertThat(confirmations.take(pending.token())).get()
                .extracting(LinkConfirmations.Pending::link, LinkConfirmations.Pending::minecraftName)
                .containsExactly(link, "Steve");
        assertThat(confirmations.take(pending.token())).isEmpty();
        assertThat(confirmations.take("nope")).isEmpty();
        assertThat(confirmations.take(null)).isEmpty();
    }

    @Test
    void tokensExpireAndAreSweptOnCreate() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        LinkConfirmations confirmations = new LinkConfirmations(Duration.ofMinutes(5), now::get);
        LinkConfirmations.Pending first = confirmations.create(link, "Steve");

        now.set(now.get().plus(Duration.ofMinutes(5)));
        assertThat(confirmations.take(first.token())).isEmpty();

        LinkConfirmations.Pending second = confirmations.create(link, "Alex");
        now.set(now.get().plus(Duration.ofMinutes(6)));
        confirmations.create(link, "Third");
        assertThat(confirmations.size()).isEqualTo(1);
        assertThat(confirmations.take(second.token())).isEmpty();
    }

    @Test
    void tokensAreUnique() {
        LinkConfirmations confirmations = new LinkConfirmations(Duration.ofMinutes(5));
        assertThat(confirmations.create(link, "a").token())
                .isNotEqualTo(confirmations.create(link, "a").token());
    }
}
