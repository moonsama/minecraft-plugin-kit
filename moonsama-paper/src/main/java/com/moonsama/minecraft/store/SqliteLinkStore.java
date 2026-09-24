package com.moonsama.minecraft.store;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public final class SqliteLinkStore implements LinkStore {
    private final String jdbcUrl;

    public SqliteLinkStore(Path databasePath) {
        this.jdbcUrl = "jdbc:sqlite:" + databasePath.toAbsolutePath();
    }

    @Override
    public void initialize() {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS player_links (
                        mojang_uuid TEXT PRIMARY KEY NOT NULL,
                        player_id TEXT UNIQUE NOT NULL,
                        gamer_tag TEXT,
                        linked_at INTEGER NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS oauth_attempts (
                        state TEXT PRIMARY KEY NOT NULL,
                        mojang_uuid TEXT NOT NULL,
                        code_verifier TEXT NOT NULL,
                        expires_at INTEGER NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE INDEX IF NOT EXISTS oauth_attempts_expiry_idx
                    ON oauth_attempts(expires_at)
                    """);
            statement.execute("PRAGMA user_version=1");
        } catch (SQLException exception) {
            throw databaseFailure("initialize database", exception);
        }
    }

    @Override
    public void createAttempt(OAuthAttempt attempt) {
        try (Connection connection = open()) {
            try (PreparedStatement cleanup = connection.prepareStatement(
                    "DELETE FROM oauth_attempts WHERE expires_at < ?")) {
                cleanup.setLong(1, Instant.now().toEpochMilli());
                cleanup.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO oauth_attempts(state, mojang_uuid, code_verifier, expires_at)
                    VALUES (?, ?, ?, ?)
                    """)) {
                insert.setString(1, attempt.state());
                insert.setString(2, attempt.mojangUuid().toString());
                insert.setString(3, attempt.codeVerifier());
                insert.setLong(4, attempt.expiresAt().toEpochMilli());
                insert.executeUpdate();
            }
        } catch (SQLException exception) {
            throw databaseFailure("store OAuth attempt", exception);
        }
    }

    @Override
    public Optional<OAuthAttempt> consumeAttempt(String state) {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                OAuthAttempt attempt = null;
                try (PreparedStatement select = connection.prepareStatement("""
                        SELECT state, mojang_uuid, code_verifier, expires_at
                        FROM oauth_attempts
                        WHERE state = ?
                        """)) {
                    select.setString(1, state);
                    try (ResultSet result = select.executeQuery()) {
                        if (result.next()) {
                            attempt = new OAuthAttempt(
                                    result.getString("state"),
                                    UUID.fromString(result.getString("mojang_uuid")),
                                    result.getString("code_verifier"),
                                    Instant.ofEpochMilli(result.getLong("expires_at"))
                            );
                        }
                    }
                }
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM oauth_attempts WHERE state = ?")) {
                    delete.setString(1, state);
                    delete.executeUpdate();
                }
                connection.commit();
                if (attempt == null || attempt.expiresAt().isBefore(Instant.now())) {
                    return Optional.empty();
                }
                return Optional.of(attempt);
            } catch (SQLException failure) {
                connection.rollback();
                throw failure;
            } catch (RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw databaseFailure("consume OAuth attempt", exception);
        }
    }

    @Override
    public void saveLink(LinkedPlayer player) {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement removePrevious = connection.prepareStatement(
                        "DELETE FROM player_links WHERE player_id = ? AND mojang_uuid <> ?")) {
                    removePrevious.setString(1, player.playerId());
                    removePrevious.setString(2, player.mojangUuid().toString());
                    removePrevious.executeUpdate();
                }
                try (PreparedStatement upsert = connection.prepareStatement("""
                        INSERT INTO player_links(mojang_uuid, player_id, gamer_tag, linked_at)
                        VALUES (?, ?, ?, ?)
                        ON CONFLICT(mojang_uuid) DO UPDATE SET
                            player_id = excluded.player_id,
                            gamer_tag = excluded.gamer_tag,
                            linked_at = excluded.linked_at
                        """)) {
                    upsert.setString(1, player.mojangUuid().toString());
                    upsert.setString(2, player.playerId());
                    upsert.setString(3, player.gamerTag());
                    upsert.setLong(4, player.linkedAt().toEpochMilli());
                    upsert.executeUpdate();
                }
                connection.commit();
            } catch (SQLException failure) {
                connection.rollback();
                throw failure;
            } catch (RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw databaseFailure("save player link", exception);
        }
    }

    @Override
    public Optional<LinkedPlayer> findLink(UUID mojangUuid) {
        try (Connection connection = open();
             PreparedStatement select = connection.prepareStatement("""
                     SELECT player_id, gamer_tag, linked_at
                     FROM player_links
                     WHERE mojang_uuid = ?
                     """)) {
            select.setString(1, mojangUuid.toString());
            try (ResultSet result = select.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(new LinkedPlayer(
                        mojangUuid,
                        result.getString("player_id"),
                        result.getString("gamer_tag"),
                        Instant.ofEpochMilli(result.getLong("linked_at"))
                ));
            }
        } catch (SQLException exception) {
            throw databaseFailure("read player link", exception);
        }
    }

    @Override
    public Optional<LinkedPlayer> findLinkByPlayerId(String playerId) {
        try (Connection connection = open();
             PreparedStatement select = connection.prepareStatement("""
                     SELECT mojang_uuid, gamer_tag, linked_at
                     FROM player_links
                     WHERE player_id = ?
                     """)) {
            select.setString(1, playerId);
            try (ResultSet result = select.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(new LinkedPlayer(
                        UUID.fromString(result.getString("mojang_uuid")),
                        playerId,
                        result.getString("gamer_tag"),
                        Instant.ofEpochMilli(result.getLong("linked_at"))
                ));
            }
        } catch (SQLException exception) {
            throw databaseFailure("read player link by Portal player ID", exception);
        }
    }

    private Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");
        }
        return connection;
    }

    private static IllegalStateException databaseFailure(String action, SQLException cause) {
        return new IllegalStateException("Could not " + action, cause);
    }
}
