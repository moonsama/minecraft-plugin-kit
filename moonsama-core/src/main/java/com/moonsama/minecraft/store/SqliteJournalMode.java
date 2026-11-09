package com.moonsama.minecraft.store;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/**
 * How SQLite journals writes to {@code data.db}.
 *
 * <p>{@link #WAL} is the default and the best choice on a local disk. It keeps a shared-memory
 * index ({@code data.db-shm}) that SQLite memory-maps, which some virtual and network
 * filesystems do not support reliably (Docker Desktop bind mounts via virtiofs, NFS, some game
 * panel volumes); the symptom is the whole JVM dying with {@code SIGBUS} inside SQLite.
 * {@link #TRUNCATE} uses a classic rollback journal and no memory mapping, at the cost of
 * slightly slower writes - irrelevant for this store's traffic.
 */
public enum SqliteJournalMode {
    WAL,
    TRUNCATE;

    public static SqliteJournalMode parse(String value) {
        if (value == null || value.isBlank()) {
            return WAL;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown SQLite journal mode '" + value + "'; expected one of wal, truncate");
        }
    }

    /** True when the mode is stored in the database file rather than per connection. */
    public boolean persistent() {
        return this == WAL;
    }

    public void apply(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=" + name());
        }
    }
}
