package com.moonsama.minecraft.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
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
                "Steve",
                "verifier",
                Instant.now().plusSeconds(60)
        );
        store.createAttempt(attempt);

        assertThat(store.consumeAttempt("state")).get().satisfies(consumed -> {
            assertThat(consumed.state()).isEqualTo(attempt.state());
            assertThat(consumed.mojangUuid()).isEqualTo(attempt.mojangUuid());
            assertThat(consumed.mojangName()).isEqualTo("Steve");
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
                "Steve",
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

    @Test
    void upgradesSchemaWithoutMinecraftNameColumn() throws Exception {
        Path database = temporaryDirectory.resolve("old.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE oauth_attempts (
                        state TEXT PRIMARY KEY NOT NULL,
                        mojang_uuid TEXT NOT NULL,
                        code_verifier TEXT NOT NULL,
                        expires_at INTEGER NOT NULL
                    )
                    """);
            statement.execute("PRAGMA user_version=1");
        }

        SqliteLinkStore store = new SqliteLinkStore(database);
        store.initialize();
        store.initialize(); // idempotent
        UUID uuid = UUID.randomUUID();
        store.createAttempt(new OAuthAttempt("s", uuid, "Alex", "v", Instant.now().plusSeconds(60)));

        assertThat(store.consumeAttempt("s")).get()
                .extracting(OAuthAttempt::mojangUuid, OAuthAttempt::mojangName)
                .containsExactly(uuid, "Alex");
    }

    private SqliteLinkStore store() {
        SqliteLinkStore store = new SqliteLinkStore(temporaryDirectory.resolve("test.db"));
        store.initialize();
        return store;
    }
}
