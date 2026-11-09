package com.moonsama.minecraft.store;

import com.moonsama.minecraft.api.AssetHold;
import com.moonsama.minecraft.api.AssetHolding;
import com.moonsama.minecraft.api.EconomyOperation;
import com.moonsama.minecraft.store.JournalRecords.CachedPlayer;
import com.moonsama.minecraft.store.JournalRecords.FeedCheckpoint;
import com.moonsama.minecraft.store.JournalRecords.Hold;
import com.moonsama.minecraft.store.JournalRecords.Invalidation;
import com.moonsama.minecraft.store.JournalRecords.OutboxEvent;
import com.moonsama.minecraft.store.JournalRecords.ResolvedLine;
import com.moonsama.minecraft.store.JournalRecords.Write;
import com.moonsama.portal.PortalModels.Change;
import com.moonsama.portal.PortalModels.Erasure;
import com.moonsama.portal.PortalModels.Receipt;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class SqlitePortalStore {
    private final String jdbcUrl;
    private final SqliteJournalMode journalMode;

    public SqlitePortalStore(Path databasePath) {
        this(databasePath, SqliteJournalMode.WAL);
    }

    public SqlitePortalStore(Path databasePath, SqliteJournalMode journalMode) {
        this.jdbcUrl = "jdbc:sqlite:" + databasePath.toAbsolutePath();
        this.journalMode = journalMode;
    }

    public void initialize() {
        try (Connection connection = open(); Statement sql = connection.createStatement()) {
            journalMode.apply(connection);
            sql.execute("""
                    CREATE TABLE IF NOT EXISTS schema_migrations (
                        version INTEGER PRIMARY KEY,
                        applied_at INTEGER NOT NULL
                    )
                    """);
            sql.execute("""
                    CREATE TABLE IF NOT EXISTS portal_meta (
                        key TEXT PRIMARY KEY,
                        value TEXT NOT NULL
                    )
                    """);
            sql.execute("""
                    CREATE TABLE IF NOT EXISTS write_operations (
                        operation_id TEXT PRIMARY KEY,
                        owner_plugin TEXT NOT NULL,
                        business_key TEXT NOT NULL,
                        portal_key TEXT UNIQUE NOT NULL,
                        payload_hash TEXT NOT NULL,
                        kind TEXT NOT NULL,
                        state TEXT NOT NULL,
                        description TEXT,
                        reference_value TEXT,
                        original_portal_key TEXT,
                        attempts INTEGER NOT NULL DEFAULT 0,
                        next_attempt_at INTEGER,
                        last_status INTEGER,
                        last_code TEXT,
                        last_error TEXT,
                        receipt_id TEXT,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        UNIQUE(owner_plugin, business_key)
                    )
                    """);
            sql.execute("""
                    CREATE TABLE IF NOT EXISTS write_lines (
                        operation_id TEXT NOT NULL REFERENCES write_operations(operation_id) ON DELETE CASCADE,
                        line_number INTEGER NOT NULL,
                        mojang_uuid TEXT,
                        player_id TEXT,
                        collection TEXT NOT NULL,
                        token_id TEXT NOT NULL,
                        amount TEXT NOT NULL,
                        PRIMARY KEY(operation_id, line_number)
                    )
                    """);
            sql.execute("""
                    CREATE TABLE IF NOT EXISTS receipts (
                        receipt_id TEXT PRIMARY KEY,
                        idempotency_key TEXT UNIQUE NOT NULL,
                        kind TEXT NOT NULL,
                        reference_value TEXT,
                        description TEXT,
                        original_key TEXT,
                        created_at INTEGER NOT NULL
                    )
                    """);
            sql.execute("""
                    CREATE TABLE IF NOT EXISTS receipt_lines (
                        receipt_id TEXT NOT NULL REFERENCES receipts(receipt_id) ON DELETE CASCADE,
                        line_number INTEGER NOT NULL,
                        mojang_uuid TEXT,
                        player_id TEXT,
                        collection TEXT NOT NULL,
                        token_id TEXT NOT NULL,
                        amount TEXT NOT NULL,
                        burned TEXT NOT NULL,
                        to_treasury TEXT NOT NULL,
                        treasury_bps INTEGER NOT NULL,
                        PRIMARY KEY(receipt_id, line_number)
                    )
                    """);
            sql.execute("""
                    CREATE TABLE IF NOT EXISTS portal_holds (
                        operation_id TEXT PRIMARY KEY,
                        owner_plugin TEXT NOT NULL,
                        business_key TEXT NOT NULL,
                        payload_hash TEXT NOT NULL,
                        mojang_uuid TEXT,
                        player_id TEXT,
                        collection TEXT NOT NULL,
                        token_id TEXT,
                        ttl_seconds INTEGER NOT NULL,
                        builder_reference TEXT,
                        portal_reference TEXT UNIQUE NOT NULL,
                        hold_id TEXT UNIQUE,
                        state TEXT NOT NULL,
                        expires_at INTEGER,
                        last_status INTEGER,
                        last_code TEXT,
                        last_error TEXT,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        UNIQUE(owner_plugin, business_key)
                    )
                    """);
            sql.execute("""
                    CREATE TABLE IF NOT EXISTS feed_checkpoints (
                        feed_name TEXT PRIMARY KEY,
                        since_ms INTEGER NOT NULL,
                        cursor TEXT,
                        updated_at INTEGER NOT NULL
                    )
                    """);
            sql.execute("""
                    CREATE TABLE IF NOT EXISTS holding_players (
                        player_id TEXT PRIMARY KEY,
                        known INTEGER NOT NULL,
                        fetched_at INTEGER NOT NULL
                    )
                    """);
            sql.execute("""
                    CREATE TABLE IF NOT EXISTS holding_tokens (
                        player_id TEXT NOT NULL REFERENCES holding_players(player_id) ON DELETE CASCADE,
                        collection TEXT NOT NULL,
                        token_id TEXT NOT NULL,
                        balance TEXT NOT NULL,
                        PRIMARY KEY(player_id, collection, token_id)
                    )
                    """);
            sql.execute("""
                    CREATE TABLE IF NOT EXISTS holding_invalidations (
                        player_id TEXT NOT NULL,
                        collection TEXT NOT NULL,
                        generation INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        PRIMARY KEY(player_id, collection)
                    )
                    """);
            sql.execute("""
                    CREATE TABLE IF NOT EXISTS event_outbox (
                        event_id TEXT PRIMARY KEY,
                        type TEXT NOT NULL,
                        aggregate_id TEXT NOT NULL,
                        payload TEXT,
                        created_at INTEGER NOT NULL,
                        delivered_at INTEGER
                    )
                    """);
            try (PreparedStatement migration = connection.prepareStatement("""
                    INSERT OR IGNORE INTO schema_migrations(version, applied_at)
                    VALUES (2, ?)
                    """)) {
                migration.setLong(1, Instant.now().toEpochMilli());
                migration.executeUpdate();
            }
            sql.execute("PRAGMA user_version=2");
            ensureInstallationId(connection);
        } catch (SQLException exception) {
            throw failure("initialize Portal database", exception);
        }
    }

    public String installationId() {
        try (Connection connection = open();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT value FROM portal_meta WHERE key = 'installation_id'");
             ResultSet result = query.executeQuery()) {
            if (!result.next()) {
                throw new IllegalStateException("Portal installation ID is missing");
            }
            return result.getString(1);
        } catch (SQLException exception) {
            throw failure("read installation ID", exception);
        }
    }

    public Write createOrGetWrite(Write proposed) {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                Optional<Write> existing = findWrite(
                        connection,
                        proposed.ownerPlugin(),
                        proposed.businessKey()
                );
                if (existing.isPresent()) {
                    if (!existing.get().payloadHash().equals(proposed.payloadHash())) {
                        throw new IllegalArgumentException(
                                "Business key was already used with a different payload"
                        );
                    }
                    connection.commit();
                    return existing.get();
                }
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO write_operations(
                            operation_id, owner_plugin, business_key, portal_key,
                            payload_hash, kind, state, description, reference_value,
                            original_portal_key, attempts, next_attempt_at,
                            created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?)
                        """)) {
                    insert.setString(1, proposed.operationId().toString());
                    insert.setString(2, proposed.ownerPlugin());
                    insert.setString(3, proposed.businessKey());
                    insert.setString(4, proposed.portalKey());
                    insert.setString(5, proposed.payloadHash());
                    insert.setString(6, proposed.kind().name());
                    insert.setString(7, proposed.state().name());
                    insert.setString(8, proposed.description());
                    insert.setString(9, proposed.reference());
                    insert.setString(10, proposed.originalPortalKey());
                    setInstant(insert, 11, proposed.nextAttemptAt());
                    insert.setLong(12, proposed.createdAt().toEpochMilli());
                    insert.setLong(13, proposed.updatedAt().toEpochMilli());
                    insert.executeUpdate();
                }
                insertWriteLines(connection, proposed);
                connection.commit();
                return proposed;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw failure("create write operation", exception);
        }
    }

    public Optional<Write> findWrite(String ownerPlugin, String businessKey) {
        try (Connection connection = open()) {
            return findWrite(connection, ownerPlugin, businessKey);
        } catch (SQLException exception) {
            throw failure("read write operation", exception);
        }
    }

    public Optional<Write> findWrite(UUID operationId) {
        try (Connection connection = open();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT * FROM write_operations WHERE operation_id = ?
                     """)) {
            query.setString(1, operationId.toString());
            try (ResultSet result = query.executeQuery()) {
                return result.next()
                        ? Optional.of(readWrite(connection, result))
                        : Optional.empty();
            }
        } catch (SQLException exception) {
            throw failure("read write operation", exception);
        }
    }

    public Optional<Write> findWriteByPortalKey(String portalKey) {
        try (Connection connection = open();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT * FROM write_operations WHERE portal_key = ?
                     """)) {
            query.setString(1, portalKey);
            try (ResultSet result = query.executeQuery()) {
                return result.next()
                        ? Optional.of(readWrite(connection, result))
                        : Optional.empty();
            }
        } catch (SQLException exception) {
            throw failure("read write operation by Portal key", exception);
        }
    }

    public List<Write> dueWrites(Instant now, int limit) {
        try (Connection connection = open();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT * FROM write_operations
                     WHERE state IN ('QUEUED', 'RETRY_WAIT', 'RECOVERING', 'SENDING')
                       AND (next_attempt_at IS NULL OR next_attempt_at <= ?)
                     ORDER BY created_at
                     LIMIT ?
                     """)) {
            query.setLong(1, now.toEpochMilli());
            query.setInt(2, limit);
            List<Write> writes = new ArrayList<>();
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    writes.add(readWrite(connection, result));
                }
            }
            return writes;
        } catch (SQLException exception) {
            throw failure("read due writes", exception);
        }
    }

    public void markWriteSending(UUID operationId) {
        updateWrite(operationId, EconomyOperation.State.SENDING, null, null, null, null, true);
    }

    public void markWriteRecovering(
            UUID operationId,
            Instant nextAttemptAt,
            Integer status,
            String code,
            String message
    ) {
        updateWrite(
                operationId,
                EconomyOperation.State.RECOVERING,
                nextAttemptAt,
                status,
                code,
                message,
                false
        );
    }

    public void markWriteTerminal(
            UUID operationId,
            EconomyOperation.State state,
            Integer status,
            String code,
            String message
    ) {
        updateWrite(operationId, state, null, status, code, message, false);
    }

    public void completeWrite(UUID operationId, Receipt receipt) {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                Write write = findWrite(connection, operationId)
                        .orElseThrow(() -> new IllegalStateException("Unknown operation " + operationId));
                if (write.state() == EconomyOperation.State.SUCCEEDED
                        && write.receipt() != null
                        && receipt.receiptId().equals(write.receipt().receiptId())) {
                    connection.commit();
                    return;
                }
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO receipts(
                            receipt_id, idempotency_key, kind, reference_value,
                            description, original_key, created_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT(receipt_id) DO UPDATE SET
                            idempotency_key = excluded.idempotency_key,
                            kind = excluded.kind,
                            reference_value = excluded.reference_value,
                            description = excluded.description,
                            original_key = excluded.original_key,
                            created_at = excluded.created_at
                        """)) {
                    insert.setString(1, receipt.receiptId());
                    insert.setString(2, receipt.idempotencyKey());
                    insert.setString(3, receipt.kind());
                    insert.setString(4, receipt.reference());
                    insert.setString(5, receipt.description());
                    insert.setString(6, receipt.originalKey());
                    insert.setLong(7, receipt.createdAt());
                    insert.executeUpdate();
                }
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM receipt_lines WHERE receipt_id = ?")) {
                    delete.setString(1, receipt.receiptId());
                    delete.executeUpdate();
                }
                for (int index = 0; index < receipt.items().size(); index++) {
                    var item = receipt.items().get(index);
                    ResolvedLine source = index < write.lines().size()
                            ? write.lines().get(index)
                            : null;
                    try (PreparedStatement insert = connection.prepareStatement("""
                            INSERT INTO receipt_lines(
                                receipt_id, line_number, mojang_uuid, player_id,
                                collection, token_id, amount, burned,
                                to_treasury, treasury_bps
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """)) {
                        insert.setString(1, receipt.receiptId());
                        insert.setInt(2, index);
                        insert.setString(3, source == null || source.mojangUuid() == null
                                ? null : source.mojangUuid().toString());
                        insert.setString(4, source == null ? null : source.playerId());
                        insert.setString(5, item.collection());
                        insert.setString(6, item.tokenId());
                        insert.setString(7, item.amount());
                        insert.setString(8, item.burned());
                        insert.setString(9, item.toTreasury());
                        insert.setInt(10, item.treasuryBps());
                        insert.executeUpdate();
                    }
                }
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE write_operations
                        SET state = 'SUCCEEDED', receipt_id = ?,
                            next_attempt_at = NULL, last_status = NULL,
                            last_code = NULL, last_error = NULL, updated_at = ?
                        WHERE operation_id = ?
                        """)) {
                    update.setString(1, receipt.receiptId());
                    update.setLong(2, Instant.now().toEpochMilli());
                    update.setString(3, operationId.toString());
                    update.executeUpdate();
                }
                enqueueEvent(
                        connection,
                        "WRITE_SUCCEEDED",
                        operationId.toString(),
                        receipt.receiptId()
                );
                for (ResolvedLine line : write.lines()) {
                    if (line.playerId() != null) {
                        invalidate(
                                connection,
                                line.playerId(),
                                line.collection(),
                                Instant.now().toEpochMilli()
                        );
                    }
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw failure("complete write operation", exception);
        }
    }

    public Hold createOrGetHold(Hold proposed) {
        try (Connection connection = open()) {
            Optional<Hold> existing = findHold(
                    connection,
                    proposed.ownerPlugin(),
                    proposed.businessKey()
            );
            if (existing.isPresent()) {
                if (!existing.get().payloadHash().equals(proposed.payloadHash())) {
                    throw new IllegalArgumentException(
                            "Hold business key was already used with a different payload"
                    );
                }
                return existing.get();
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO portal_holds(
                        operation_id, owner_plugin, business_key, payload_hash,
                        mojang_uuid, player_id, collection, token_id, ttl_seconds,
                        builder_reference, portal_reference, hold_id, state,
                        expires_at, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                insert.setString(1, proposed.operationId().toString());
                insert.setString(2, proposed.ownerPlugin());
                insert.setString(3, proposed.businessKey());
                insert.setString(4, proposed.payloadHash());
                insert.setString(5, proposed.mojangUuid() == null
                        ? null : proposed.mojangUuid().toString());
                insert.setString(6, proposed.playerId());
                insert.setString(7, proposed.collection());
                insert.setString(8, proposed.tokenId());
                insert.setInt(9, proposed.ttlSeconds());
                insert.setString(10, proposed.builderReference());
                insert.setString(11, proposed.portalReference());
                insert.setString(12, proposed.holdId());
                insert.setString(13, proposed.state().name());
                setInstant(insert, 14, proposed.expiresAt());
                insert.setLong(15, proposed.createdAt().toEpochMilli());
                insert.setLong(16, proposed.updatedAt().toEpochMilli());
                insert.executeUpdate();
            }
            return proposed;
        } catch (SQLException exception) {
            throw failure("create hold operation", exception);
        }
    }

    public Optional<Hold> findHold(String ownerPlugin, String businessKey) {
        try (Connection connection = open()) {
            return findHold(connection, ownerPlugin, businessKey);
        } catch (SQLException exception) {
            throw failure("read hold operation", exception);
        }
    }

    public Optional<Hold> findHold(UUID operationId) {
        try (Connection connection = open();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT * FROM portal_holds WHERE operation_id = ?")) {
            query.setString(1, operationId.toString());
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? Optional.of(readHold(result)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw failure("read hold operation", exception);
        }
    }

    public List<Hold> recoverableHolds(Instant now, int limit) {
        try (Connection connection = open();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT * FROM portal_holds
                     WHERE state IN (
                         'REQUESTED', 'PLACING', 'PLACEMENT_UNKNOWN',
                         'RENEWING', 'RELEASING'
                     )
                        OR (state = 'ACTIVE' AND expires_at <= ?)
                     ORDER BY updated_at
                     LIMIT ?
                     """)) {
            query.setLong(1, now.toEpochMilli());
            query.setInt(2, limit);
            List<Hold> holds = new ArrayList<>();
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    holds.add(readHold(result));
                }
            }
            return holds;
        } catch (SQLException exception) {
            throw failure("read recoverable holds", exception);
        }
    }

    public void updateHold(
            UUID operationId,
            AssetHold.State state,
            String holdId,
            Instant expiresAt,
            Integer status,
            String code,
            String message
    ) {
        try (Connection connection = open();
             PreparedStatement update = connection.prepareStatement("""
                     UPDATE portal_holds
                     SET state = ?, hold_id = COALESCE(?, hold_id),
                         expires_at = COALESCE(?, expires_at),
                         last_status = ?, last_code = ?, last_error = ?,
                         updated_at = ?
                     WHERE operation_id = ?
                     """)) {
            update.setString(1, state.name());
            update.setString(2, holdId);
            setInstant(update, 3, expiresAt);
            setInteger(update, 4, status);
            update.setString(5, code);
            update.setString(6, truncate(message, 2_000));
            update.setLong(7, Instant.now().toEpochMilli());
            update.setString(8, operationId.toString());
            update.executeUpdate();
        } catch (SQLException exception) {
            throw failure("update hold operation", exception);
        }
    }

    public void updateHoldTtl(UUID operationId, int ttlSeconds) {
        try (Connection connection = open();
             PreparedStatement update = connection.prepareStatement("""
                     UPDATE portal_holds
                     SET ttl_seconds = ?, updated_at = ?
                     WHERE operation_id = ?
                     """)) {
            update.setInt(1, ttlSeconds);
            update.setLong(2, Instant.now().toEpochMilli());
            update.setString(3, operationId.toString());
            update.executeUpdate();
        } catch (SQLException exception) {
            throw failure("update hold TTL", exception);
        }
    }

    public FeedCheckpoint checkpoint(String feedName, long defaultSince) {
        try (Connection connection = open();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT since_ms, cursor FROM feed_checkpoints WHERE feed_name = ?
                     """)) {
            query.setString(1, feedName);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) {
                    return new FeedCheckpoint(feedName, defaultSince, null);
                }
                return new FeedCheckpoint(
                        feedName,
                        result.getLong("since_ms"),
                        result.getString("cursor")
                );
            }
        } catch (SQLException exception) {
            throw failure("read feed checkpoint", exception);
        }
    }

    public void saveCheckpoint(String feedName, long nextSince, String nextCursor) {
        try (Connection connection = open()) {
            saveCheckpoint(connection, feedName, nextSince, nextCursor);
        } catch (SQLException exception) {
            throw failure("save feed checkpoint", exception);
        }
    }

    public void applyChangePage(
            List<Change> changes,
            long nextSince,
            String nextCursor
    ) {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                for (Change change : changes) {
                    invalidate(
                            connection,
                            change.playerId(),
                            change.collection(),
                            change.updatedAt()
                    );
                }
                saveCheckpoint(connection, "changes", nextSince, nextCursor);
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw failure("apply changes feed page", exception);
        }
    }

    public List<Invalidation> invalidatedPlayers(int limit) {
        try (Connection connection = open();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT player_id, MAX(generation) AS generation
                     FROM holding_invalidations
                     GROUP BY player_id
                     ORDER BY MIN(updated_at)
                     LIMIT ?
                     """)) {
            query.setInt(1, limit);
            List<Invalidation> invalidations = new ArrayList<>();
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    invalidations.add(new Invalidation(
                            result.getString("player_id"),
                            result.getLong("generation")
                    ));
                }
            }
            return invalidations;
        } catch (SQLException exception) {
            throw failure("read holdings invalidations", exception);
        }
    }

    public long invalidationGeneration(String playerId) {
        try (Connection connection = open();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT COALESCE(MAX(generation), 0)
                     FROM holding_invalidations
                     WHERE player_id = ?
                     """)) {
            query.setString(1, playerId);
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? result.getLong(1) : 0;
            }
        } catch (SQLException exception) {
            throw failure("read holdings generation", exception);
        }
    }

    public void invalidate(String playerId, String collection, long updatedAt) {
        try (Connection connection = open()) {
            invalidate(connection, playerId, collection, updatedAt);
        } catch (SQLException exception) {
            throw failure("invalidate holdings", exception);
        }
    }

    public void saveHoldings(
            String playerId,
            boolean known,
            List<AssetHolding> holdings,
            long consumedGeneration
    ) {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                long now = Instant.now().toEpochMilli();
                try (PreparedStatement player = connection.prepareStatement("""
                        INSERT INTO holding_players(player_id, known, fetched_at)
                        VALUES (?, ?, ?)
                        ON CONFLICT(player_id) DO UPDATE SET
                            known = excluded.known,
                            fetched_at = excluded.fetched_at
                        """)) {
                    player.setString(1, playerId);
                    player.setInt(2, known ? 1 : 0);
                    player.setLong(3, now);
                    player.executeUpdate();
                }
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM holding_tokens WHERE player_id = ?")) {
                    delete.setString(1, playerId);
                    delete.executeUpdate();
                }
                for (AssetHolding holding : holdings) {
                    try (PreparedStatement insert = connection.prepareStatement("""
                            INSERT INTO holding_tokens(
                                player_id, collection, token_id, balance
                            ) VALUES (?, ?, ?, ?)
                            """)) {
                        insert.setString(1, playerId);
                        insert.setString(2, holding.collection());
                        insert.setString(3, holding.tokenId());
                        insert.setString(4, holding.balance());
                        insert.executeUpdate();
                    }
                }
                try (PreparedStatement clear = connection.prepareStatement("""
                        DELETE FROM holding_invalidations
                        WHERE player_id = ? AND generation <= ?
                        """)) {
                    clear.setString(1, playerId);
                    clear.setLong(2, consumedGeneration);
                    clear.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw failure("save holdings cache", exception);
        }
    }

    public Optional<CachedPlayer> cachedHoldings(String playerId) {
        try (Connection connection = open();
             PreparedStatement player = connection.prepareStatement("""
                     SELECT known, fetched_at FROM holding_players WHERE player_id = ?
                     """)) {
            player.setString(1, playerId);
            try (ResultSet result = player.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                boolean known = result.getInt("known") != 0;
                Instant fetchedAt = Instant.ofEpochMilli(result.getLong("fetched_at"));
                List<AssetHolding> holdings = new ArrayList<>();
                try (PreparedStatement tokens = connection.prepareStatement("""
                        SELECT collection, token_id, balance
                        FROM holding_tokens
                        WHERE player_id = ?
                        ORDER BY collection, token_id
                        """)) {
                    tokens.setString(1, playerId);
                    try (ResultSet tokenRows = tokens.executeQuery()) {
                        while (tokenRows.next()) {
                            holdings.add(new AssetHolding(
                                    tokenRows.getString("collection"),
                                    tokenRows.getString("token_id"),
                                    tokenRows.getString("balance")
                            ));
                        }
                    }
                }
                return Optional.of(new CachedPlayer(playerId, known, holdings, fetchedAt));
            }
        } catch (SQLException exception) {
            throw failure("read holdings cache", exception);
        }
    }

    public void applyErasurePage(
            List<Erasure> erasures,
            long nextSince,
            String nextCursor
    ) {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                for (Erasure erasure : erasures) {
                    purgePlayer(connection, erasure.playerId());
                }
                saveCheckpoint(connection, "erasures", nextSince, nextCursor);
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw failure("apply erasure feed page", exception);
        }
    }

    public List<OutboxEvent> pendingEvents(int limit) {
        try (Connection connection = open();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT event_id, type, aggregate_id, payload
                     FROM event_outbox
                     WHERE delivered_at IS NULL
                     ORDER BY created_at
                     LIMIT ?
                     """)) {
            query.setInt(1, limit);
            List<OutboxEvent> events = new ArrayList<>();
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    events.add(new OutboxEvent(
                            result.getString("event_id"),
                            result.getString("type"),
                            result.getString("aggregate_id"),
                            result.getString("payload")
                    ));
                }
            }
            return events;
        } catch (SQLException exception) {
            throw failure("read event outbox", exception);
        }
    }

    public Map<String, Long> writeStateCounts() {
        return stateCounts("write_operations");
    }

    public Map<String, Long> holdStateCounts() {
        return stateCounts("portal_holds");
    }

    public Optional<Instant> oldestPendingWriteAt() {
        try (Connection connection = open();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT MIN(created_at)
                     FROM write_operations
                     WHERE state IN ('QUEUED', 'SENDING', 'RECOVERING', 'RETRY_WAIT')
                     """);
             ResultSet result = query.executeQuery()) {
            if (!result.next()) {
                return Optional.empty();
            }
            long value = result.getLong(1);
            return result.wasNull()
                    ? Optional.empty()
                    : Optional.of(Instant.ofEpochMilli(value));
        } catch (SQLException exception) {
            throw failure("read oldest pending write", exception);
        }
    }

    public void markEventDelivered(String eventId) {
        try (Connection connection = open();
             PreparedStatement update = connection.prepareStatement("""
                     DELETE FROM event_outbox WHERE event_id = ?
                     """)) {
            update.setString(1, eventId);
            update.executeUpdate();
        } catch (SQLException exception) {
            throw failure("mark event delivered", exception);
        }
    }

    private Optional<Write> findWrite(
            Connection connection,
            String ownerPlugin,
            String businessKey
    ) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM write_operations
                WHERE owner_plugin = ? AND business_key = ?
                """)) {
            query.setString(1, ownerPlugin);
            query.setString(2, businessKey);
            try (ResultSet result = query.executeQuery()) {
                return result.next()
                        ? Optional.of(readWrite(connection, result))
                        : Optional.empty();
            }
        }
    }

    private Optional<Write> findWrite(Connection connection, UUID operationId)
            throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM write_operations WHERE operation_id = ?
                """)) {
            query.setString(1, operationId.toString());
            try (ResultSet result = query.executeQuery()) {
                return result.next()
                        ? Optional.of(readWrite(connection, result))
                        : Optional.empty();
            }
        }
    }

    private Write readWrite(Connection connection, ResultSet result) throws SQLException {
        UUID operationId = UUID.fromString(result.getString("operation_id"));
        List<ResolvedLine> lines = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM write_lines
                WHERE operation_id = ?
                ORDER BY line_number
                """)) {
            query.setString(1, operationId.toString());
            try (ResultSet lineRows = query.executeQuery()) {
                while (lineRows.next()) {
                    String mojangUuid = lineRows.getString("mojang_uuid");
                    lines.add(new ResolvedLine(
                            lineRows.getInt("line_number"),
                            mojangUuid == null ? null : UUID.fromString(mojangUuid),
                            lineRows.getString("player_id"),
                            lineRows.getString("collection"),
                            lineRows.getString("token_id"),
                            lineRows.getString("amount")
                    ));
                }
            }
        }
        String receiptId = result.getString("receipt_id");
        EconomyOperation.Receipt receipt = receiptId == null
                ? null
                : readReceipt(connection, receiptId);
        Integer status = nullableInteger(result, "last_status");
        String code = result.getString("last_code");
        String error = result.getString("last_error");
        EconomyOperation.Failure operationFailure = status == null && code == null && error == null
                ? null
                : new EconomyOperation.Failure(status, code, error);
        return new Write(
                operationId,
                result.getString("owner_plugin"),
                result.getString("business_key"),
                result.getString("portal_key"),
                result.getString("payload_hash"),
                EconomyOperation.Kind.valueOf(result.getString("kind")),
                EconomyOperation.State.valueOf(result.getString("state")),
                lines,
                result.getString("description"),
                result.getString("reference_value"),
                result.getString("original_portal_key"),
                result.getInt("attempts"),
                nullableInstant(result, "next_attempt_at"),
                receipt,
                operationFailure,
                Instant.ofEpochMilli(result.getLong("created_at")),
                Instant.ofEpochMilli(result.getLong("updated_at"))
        );
    }

    private EconomyOperation.Receipt readReceipt(
            Connection connection,
            String receiptId
    ) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM receipts WHERE receipt_id = ?
                """)) {
            query.setString(1, receiptId);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                List<EconomyOperation.ReceiptLine> lines = new ArrayList<>();
                try (PreparedStatement lineQuery = connection.prepareStatement("""
                        SELECT * FROM receipt_lines
                        WHERE receipt_id = ?
                        ORDER BY line_number
                        """)) {
                    lineQuery.setString(1, receiptId);
                    try (ResultSet rows = lineQuery.executeQuery()) {
                        while (rows.next()) {
                            String mojangUuid = rows.getString("mojang_uuid");
                            lines.add(new EconomyOperation.ReceiptLine(
                                    mojangUuid == null ? null : UUID.fromString(mojangUuid),
                                    rows.getString("collection"),
                                    rows.getString("token_id"),
                                    rows.getString("amount"),
                                    rows.getString("burned"),
                                    rows.getString("to_treasury"),
                                    rows.getInt("treasury_bps")
                            ));
                        }
                    }
                }
                return new EconomyOperation.Receipt(
                        result.getString("receipt_id"),
                        result.getString("idempotency_key"),
                        EconomyOperation.Kind.valueOf(result.getString("kind")),
                        result.getString("reference_value"),
                        result.getString("description"),
                        result.getString("original_key"),
                        result.getLong("created_at"),
                        lines
                );
            }
        }
    }

    private void insertWriteLines(Connection connection, Write write) throws SQLException {
        for (ResolvedLine line : write.lines()) {
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO write_lines(
                        operation_id, line_number, mojang_uuid, player_id,
                        collection, token_id, amount
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """)) {
                insert.setString(1, write.operationId().toString());
                insert.setInt(2, line.lineNumber());
                insert.setString(3, line.mojangUuid() == null
                        ? null : line.mojangUuid().toString());
                insert.setString(4, line.playerId());
                insert.setString(5, line.collection());
                insert.setString(6, line.tokenId());
                insert.setString(7, line.amount());
                insert.executeUpdate();
            }
        }
    }

    private void updateWrite(
            UUID operationId,
            EconomyOperation.State state,
            Instant nextAttemptAt,
            Integer status,
            String code,
            String message,
            boolean incrementAttempts
    ) {
        try (Connection connection = open();
             PreparedStatement update = connection.prepareStatement("""
                     UPDATE write_operations
                     SET state = ?, next_attempt_at = ?, last_status = ?,
                         last_code = ?, last_error = ?, updated_at = ?,
                         attempts = attempts + ?
                     WHERE operation_id = ?
                     """)) {
            update.setString(1, state.name());
            setInstant(update, 2, nextAttemptAt);
            setInteger(update, 3, status);
            update.setString(4, code);
            update.setString(5, truncate(message, 2_000));
            update.setLong(6, Instant.now().toEpochMilli());
            update.setInt(7, incrementAttempts ? 1 : 0);
            update.setString(8, operationId.toString());
            update.executeUpdate();
        } catch (SQLException exception) {
            throw failure("update write operation", exception);
        }
    }

    private Optional<Hold> findHold(
            Connection connection,
            String ownerPlugin,
            String businessKey
    ) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM portal_holds
                WHERE owner_plugin = ? AND business_key = ?
                """)) {
            query.setString(1, ownerPlugin);
            query.setString(2, businessKey);
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? Optional.of(readHold(result)) : Optional.empty();
            }
        }
    }

    private Hold readHold(ResultSet result) throws SQLException {
        String mojangUuid = result.getString("mojang_uuid");
        Integer status = nullableInteger(result, "last_status");
        String code = result.getString("last_code");
        String error = result.getString("last_error");
        AssetHold.Failure holdFailure = status == null && code == null && error == null
                ? null
                : new AssetHold.Failure(status, code, error);
        return new Hold(
                UUID.fromString(result.getString("operation_id")),
                result.getString("owner_plugin"),
                result.getString("business_key"),
                result.getString("payload_hash"),
                mojangUuid == null ? null : UUID.fromString(mojangUuid),
                result.getString("player_id"),
                result.getString("collection"),
                result.getString("token_id"),
                result.getInt("ttl_seconds"),
                result.getString("builder_reference"),
                result.getString("portal_reference"),
                result.getString("hold_id"),
                AssetHold.State.valueOf(result.getString("state")),
                nullableInstant(result, "expires_at"),
                holdFailure,
                Instant.ofEpochMilli(result.getLong("created_at")),
                Instant.ofEpochMilli(result.getLong("updated_at"))
        );
    }

    private void purgePlayer(Connection connection, String playerId) throws SQLException {
        String mojangUuid = null;
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT mojang_uuid FROM player_links WHERE player_id = ?
                """)) {
            query.setString(1, playerId);
            try (ResultSet result = query.executeQuery()) {
                if (result.next()) {
                    mojangUuid = result.getString(1);
                }
            }
        }
        try (PreparedStatement cancel = connection.prepareStatement("""
                UPDATE write_operations
                SET state = CASE
                    WHEN state NOT IN (
                        'SUCCEEDED', 'REJECTED', 'NEEDS_REAUTH',
                        'QUARANTINED_BUG', 'CANCELLED'
                    ) THEN 'CANCELLED'
                    ELSE state
                END,
                updated_at = ?
                WHERE operation_id IN (
                    SELECT operation_id FROM write_lines WHERE player_id = ?
                )
                """)) {
            cancel.setLong(1, Instant.now().toEpochMilli());
            cancel.setString(2, playerId);
            cancel.executeUpdate();
        }
        try (PreparedStatement holds = connection.prepareStatement("""
                UPDATE portal_holds
                SET state = CASE
                    WHEN hold_id IS NOT NULL AND state NOT IN ('RELEASED', 'EXPIRED', 'REJECTED')
                        THEN 'RELEASING'
                    WHEN hold_id IS NULL AND state NOT IN ('RELEASED', 'EXPIRED', 'REJECTED')
                        THEN 'LOST'
                    ELSE state
                END,
                mojang_uuid = NULL,
                player_id = NULL,
                updated_at = ?
                WHERE player_id = ?
                """)) {
            holds.setLong(1, Instant.now().toEpochMilli());
            holds.setString(2, playerId);
            holds.executeUpdate();
        }
        for (String table : List.of("write_lines", "receipt_lines")) {
            try (PreparedStatement redact = connection.prepareStatement(
                    "UPDATE " + table + " SET mojang_uuid = NULL, player_id = NULL "
                            + "WHERE player_id = ?")) {
                redact.setString(1, playerId);
                redact.executeUpdate();
            }
        }
        for (String table : List.of(
                "holding_players",
                "holding_invalidations",
                "player_links"
        )) {
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM " + table + " WHERE player_id = ?")) {
                delete.setString(1, playerId);
                delete.executeUpdate();
            }
        }
        enqueueEvent(
                connection,
                "PLAYER_ERASED",
                playerId,
                mojangUuid
        );
    }

    private void saveCheckpoint(
            Connection connection,
            String feedName,
            long nextSince,
            String nextCursor
    ) throws SQLException {
        try (PreparedStatement checkpoint = connection.prepareStatement("""
                INSERT INTO feed_checkpoints(
                    feed_name, since_ms, cursor, updated_at
                ) VALUES (?, ?, ?, ?)
                ON CONFLICT(feed_name) DO UPDATE SET
                    since_ms = excluded.since_ms,
                    cursor = excluded.cursor,
                    updated_at = excluded.updated_at
                """)) {
            checkpoint.setString(1, feedName);
            checkpoint.setLong(2, nextSince);
            checkpoint.setString(3, nextCursor);
            checkpoint.setLong(4, Instant.now().toEpochMilli());
            checkpoint.executeUpdate();
        }
    }

    private void invalidate(
            Connection connection,
            String playerId,
            String collection,
            long updatedAt
    ) throws SQLException {
        try (PreparedStatement invalidate = connection.prepareStatement("""
                INSERT INTO holding_invalidations(
                    player_id, collection, generation, updated_at
                ) VALUES (?, ?, 1, ?)
                ON CONFLICT(player_id, collection) DO UPDATE SET
                    generation = holding_invalidations.generation + 1,
                    updated_at = excluded.updated_at
                """)) {
            invalidate.setString(1, playerId);
            invalidate.setString(2, collection);
            invalidate.setLong(3, updatedAt);
            invalidate.executeUpdate();
        }
    }

    private void enqueueEvent(
            Connection connection,
            String type,
            String aggregateId,
            String payload
    ) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO event_outbox(
                    event_id, type, aggregate_id, payload, created_at
                ) VALUES (?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, UUID.randomUUID().toString());
            insert.setString(2, type);
            insert.setString(3, aggregateId);
            insert.setString(4, payload);
            insert.setLong(5, Instant.now().toEpochMilli());
            insert.executeUpdate();
        }
    }

    private Map<String, Long> stateCounts(String table) {
        try (Connection connection = open();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT state, COUNT(*) AS count_value FROM "
                             + table + " GROUP BY state");
             ResultSet result = query.executeQuery()) {
            Map<String, Long> counts = new HashMap<>();
            while (result.next()) {
                counts.put(result.getString("state"), result.getLong("count_value"));
            }
            return Map.copyOf(counts);
        } catch (SQLException exception) {
            throw failure("read operation status", exception);
        }
    }

    private void ensureInstallationId(Connection connection) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT OR IGNORE INTO portal_meta(key, value)
                VALUES ('installation_id', ?)
                """)) {
            insert.setString(1, UUID.randomUUID().toString());
            insert.executeUpdate();
        }
    }

    private Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");
        }
        if (!journalMode.persistent()) {
            // Rollback-journal modes are per connection; WAL is remembered by the database file.
            journalMode.apply(connection);
        }
        return connection;
    }

    private static void setInstant(
            PreparedStatement statement,
            int index,
            Instant value
    ) throws SQLException {
        if (value == null) {
            statement.setObject(index, null);
        } else {
            statement.setLong(index, value.toEpochMilli());
        }
    }

    private static void setInteger(
            PreparedStatement statement,
            int index,
            Integer value
    ) throws SQLException {
        if (value == null) {
            statement.setObject(index, null);
        } else {
            statement.setInt(index, value);
        }
    }

    private static Instant nullableInstant(ResultSet result, String column)
            throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : Instant.ofEpochMilli(value);
    }

    private static Integer nullableInteger(ResultSet result, String column)
            throws SQLException {
        int value = result.getInt(column);
        return result.wasNull() ? null : value;
    }

    private static String truncate(String value, int length) {
        if (value == null || value.length() <= length) {
            return value;
        }
        return value.substring(0, length);
    }

    private static IllegalStateException failure(String action, SQLException cause) {
        return new IllegalStateException("Could not " + action, cause);
    }
}
