package com.moonsama.minecraft.store;

import com.moonsama.minecraft.api.EconomyOperation;
import com.moonsama.minecraft.store.JournalRecords.ResolvedLine;
import com.moonsama.minecraft.store.JournalRecords.Write;
import com.moonsama.portal.PortalModels;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlitePortalStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void journalsReceiptAndRedactsErasedPlayer() {
        Path database = temporaryDirectory.resolve("portal.db");
        UUID mojangUuid = UUID.randomUUID();
        SqliteLinkStore links = new SqliteLinkStore(database);
        links.initialize();
        links.saveLink(new LinkedPlayer(
                mojangUuid,
                "plr_test",
                "Builder",
                Instant.now()
        ));

        SqlitePortalStore store = new SqlitePortalStore(database);
        store.initialize();
        Write write = write(mojangUuid, "payload-a");
        assertThat(store.createOrGetWrite(write).state())
                .isEqualTo(EconomyOperation.State.QUEUED);

        store.markWriteSending(write.operationId());
        store.completeWrite(write.operationId(), new PortalModels.Receipt(
                "receipt-1",
                write.portalKey(),
                "SPEND",
                "COMPLETED",
                "craft-1",
                "Craft",
                null,
                Instant.now().toEpochMilli(),
                List.of(new PortalModels.ReceiptItem(
                        "plr_test",
                        "sandbox-gold",
                        "0",
                        "10",
                        "10",
                        "0",
                        0,
                        null
                ))
        ));

        Write completed = new SqlitePortalStore(database)
                .findWrite(write.operationId())
                .orElseThrow();
        assertThat(completed.state()).isEqualTo(EconomyOperation.State.SUCCEEDED);
        assertThat(completed.receipt().items()).singleElement()
                .extracting(EconomyOperation.ReceiptLine::mojangUuid)
                .isEqualTo(mojangUuid);
        assertThat(store.invalidationGeneration("plr_test")).isEqualTo(1);

        store.applyErasurePage(
                List.of(new PortalModels.Erasure(
                        "plr_test",
                        Instant.now().toEpochMilli()
                )),
                Instant.now().toEpochMilli(),
                null
        );

        Write redacted = store.findWrite(write.operationId()).orElseThrow();
        assertThat(redacted.lines()).singleElement()
                .extracting(ResolvedLine::playerId)
                .isNull();
        assertThat(redacted.receipt().items()).singleElement()
                .extracting(EconomyOperation.ReceiptLine::mojangUuid)
                .isNull();
        assertThat(links.findLink(mojangUuid)).isEmpty();
    }

    @Test
    void rejectsBusinessKeyReuseWithDifferentPayload() {
        Path database = temporaryDirectory.resolve("portal.db");
        new SqliteLinkStore(database).initialize();
        SqlitePortalStore store = new SqlitePortalStore(database);
        store.initialize();
        Write first = write(UUID.randomUUID(), "payload-a");
        store.createOrGetWrite(first);

        Write changed = new Write(
                UUID.randomUUID(),
                first.ownerPlugin(),
                first.businessKey(),
                "another-portal-key",
                "payload-b",
                first.kind(),
                first.state(),
                first.lines(),
                first.description(),
                first.reference(),
                null,
                0,
                Instant.now(),
                null,
                null,
                Instant.now(),
                Instant.now()
        );

        assertThatThrownBy(() -> store.createOrGetWrite(changed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different payload");
    }

    private static Write write(UUID mojangUuid, String payloadHash) {
        Instant now = Instant.now();
        UUID operationId = UUID.randomUUID();
        return new Write(
                operationId,
                "ExamplePlugin",
                "craft-1",
                "mc:test:craft-1",
                payloadHash,
                EconomyOperation.Kind.SPEND,
                EconomyOperation.State.QUEUED,
                List.of(new ResolvedLine(
                        0,
                        mojangUuid,
                        "plr_test",
                        "sandbox-gold",
                        "0",
                        "10"
                )),
                "Craft",
                "craft-1",
                null,
                0,
                now,
                null,
                null,
                now,
                now
        );
    }
}
