package com.moonsama.minecraft.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SqliteLinkStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void consumesOAuthAttemptOnlyOnce() {
        SqliteLinkStore store = store();
        OAuthAttempt attempt = new OAuthAttempt(
                "state",
                UUID.randomUUID(),
                "verifier",
                Instant.now().plusSeconds(60)
        );
        store.createAttempt(attempt);

        assertThat(store.consumeAttempt("state")).get().satisfies(consumed -> {
            assertThat(consumed.state()).isEqualTo(attempt.state());
            assertThat(consumed.mojangUuid()).isEqualTo(attempt.mojangUuid());
            assertThat(consumed.codeVerifier()).isEqualTo(attempt.codeVerifier());
            assertThat(consumed.expiresAt().toEpochMilli())
                    .isEqualTo(attempt.expiresAt().toEpochMilli());
        });
        assertThat(store.consumeAttempt("state")).isEmpty();
    }

    @Test
    void rejectsExpiredOAuthAttempt() {
        SqliteLinkStore store = store();
        store.createAttempt(new OAuthAttempt(
                "expired",
                UUID.randomUUID(),
                "verifier",
                Instant.now().minusSeconds(1)
        ));

        assertThat(store.consumeAttempt("expired")).isEmpty();
    }

    @Test
    void persistsAndRelinksPortalPlayer() {
        SqliteLinkStore store = store();
        UUID firstMinecraftAccount = UUID.randomUUID();
        UUID secondMinecraftAccount = UUID.randomUUID();
        String playerId = "plr_example";

        store.saveLink(new LinkedPlayer(
                firstMinecraftAccount,
                playerId,
                "FirstName",
                Instant.now()
        ));
        store.saveLink(new LinkedPlayer(
                secondMinecraftAccount,
                playerId,
                "SecondName",
                Instant.now()
        ));

        assertThat(store.findLink(firstMinecraftAccount)).isEmpty();
        assertThat(store.findLink(secondMinecraftAccount))
                .get()
                .extracting(LinkedPlayer::playerId, LinkedPlayer::gamerTag)
                .containsExactly(playerId, "SecondName");
    }

    private SqliteLinkStore store() {
        SqliteLinkStore store = new SqliteLinkStore(temporaryDirectory.resolve("test.db"));
        store.initialize();
        return store;
    }
}
