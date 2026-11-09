package com.moonsama.minecraft.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqliteJournalModeTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void parsesCaseInsensitivelyAndDefaultsToWal() {
        assertThat(SqliteJournalMode.parse(null)).isEqualTo(SqliteJournalMode.WAL);
        assertThat(SqliteJournalMode.parse("")).isEqualTo(SqliteJournalMode.WAL);
        assertThat(SqliteJournalMode.parse("Truncate")).isEqualTo(SqliteJournalMode.TRUNCATE);
        assertThatThrownBy(() -> SqliteJournalMode.parse("memory"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("wal, truncate");
    }

    @Test
    void truncateModeLeavesNoSharedMemoryFileAndSurvivesSwitchingFromWal() throws SQLException {
        Path database = temporaryDirectory.resolve("data.db");
        UUID mojangUuid = UUID.randomUUID();

        // A database created in WAL mode by an earlier run...
        SqliteLinkStore wal = new SqliteLinkStore(database, SqliteJournalMode.WAL);
        wal.initialize();
        wal.saveLink(new LinkedPlayer(mojangUuid, "plr_wal", "Wal", Instant.now()));
        assertThat(journalMode(database)).isEqualToIgnoringCase("wal");

        // ...is converted when the operator switches to the rollback journal.
        SqliteLinkStore truncate = new SqliteLinkStore(database, SqliteJournalMode.TRUNCATE);
        truncate.initialize();
        new SqlitePortalStore(database, SqliteJournalMode.TRUNCATE).initialize();
        truncate.saveLink(new LinkedPlayer(UUID.randomUUID(), "plr_truncate", "Trunc", Instant.now()));

        assertThat(truncate.findLink(mojangUuid)).map(LinkedPlayer::playerId).contains("plr_wal");
        assertThat(journalMode(database)).isEqualToIgnoringCase("delete");
        assertThat(Files.exists(database.resolveSibling("data.db-shm"))).isFalse();
        assertThat(Files.exists(database.resolveSibling("data.db-wal"))).isFalse();
    }

    /** The mode a fresh, pragma-less connection sees, i.e. what is persisted in the file. */
    private static String journalMode(Path database) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA journal_mode")) {
            result.next();
            return result.getString(1);
        }
    }
}
