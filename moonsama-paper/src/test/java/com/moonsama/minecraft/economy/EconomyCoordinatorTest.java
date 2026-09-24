package com.moonsama.minecraft.economy;

import com.moonsama.minecraft.MoonsamaConfig;
import com.moonsama.minecraft.api.EconomyOperation;
import com.moonsama.minecraft.store.JournalRecords.ResolvedLine;
import com.moonsama.minecraft.store.JournalRecords.Write;
import com.moonsama.minecraft.store.SqliteLinkStore;
import com.moonsama.minecraft.store.SqlitePortalStore;
import com.moonsama.portal.PortalClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EconomyCoordinatorTest {
    @TempDir
    Path temporaryDirectory;

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void normalizesDecimalTokenAmounts() {
        assertThat(EconomyCoordinator.normalizeAmount("001.5000")).isEqualTo("1.5");
        assertThat(EconomyCoordinator.normalizeAmount("0.000000000000000001"))
                .isEqualTo("0.000000000000000001");
        assertThat(EconomyCoordinator.normalizeAmount("10")).isEqualTo("10");
    }

    @Test
    void rejectsInvalidTokenAmounts() {
        assertThatThrownBy(() -> EconomyCoordinator.normalizeAmount("0"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EconomyCoordinator.normalizeAmount("1e18"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                EconomyCoordinator.normalizeAmount("0.0000000000000000001"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void crashRecoveryChecksReceiptBeforeResending() throws Exception {
        AtomicInteger postRequests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                postRequests.incrementAndGet();
            }
            respond(exchange, 200, receiptJson("mc:test:operation"));
        });
        server.start();

        try (Fixture fixture = fixture()) {
            Write write = fixture.sendingWrite("mc:test:operation");
            EconomyOperation recovered = fixture.coordinator()
                    .reconcile(write.operationId())
                    .join();

            assertThat(recovered.state()).isEqualTo(EconomyOperation.State.SUCCEEDED);
            assertThat(recovered.receipt().receiptId()).isEqualTo("receipt-1");
            assertThat(postRequests).hasValue(0);
        }
    }

    @Test
    void absentReceiptRetriesWithTheSamePortalKey() throws Exception {
        AtomicInteger postRequests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 404, """
                        {"statusCode":404,"message":"No receipt","error":"Not Found"}
                        """);
                return;
            }
            postRequests.incrementAndGet();
            assertThat(exchange.getRequestHeaders().getFirst("Idempotency-Key"))
                    .isEqualTo("mc:test:operation");
            respond(exchange, 201, receiptJson("mc:test:operation"));
        });
        server.start();

        try (Fixture fixture = fixture()) {
            Write write = fixture.sendingWrite("mc:test:operation");
            EconomyOperation recovered = fixture.coordinator()
                    .reconcile(write.operationId())
                    .join();

            assertThat(recovered.state()).isEqualTo(EconomyOperation.State.SUCCEEDED);
            assertThat(postRequests).hasValue(1);
        }
    }

    private Fixture fixture() {
        Path database = temporaryDirectory.resolve("portal.db");
        SqliteLinkStore links = new SqliteLinkStore(database);
        links.initialize();
        SqlitePortalStore store = new SqlitePortalStore(database);
        store.initialize();
        URI apiUrl = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        PortalClient portal = new PortalClient(new PortalClient.Options(
                apiUrl,
                "key",
                "secret",
                Duration.ofSeconds(2),
                1
        ));
        ExecutorService databaseExecutor = Executors.newSingleThreadExecutor();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        EconomyCoordinator coordinator = new EconomyCoordinator(
                portal,
                links,
                store,
                databaseExecutor,
                scheduler,
                config(apiUrl),
                ignored -> {
                },
                ignored -> {
                }
        );
        return new Fixture(
                store,
                portal,
                databaseExecutor,
                scheduler,
                coordinator
        );
    }

    private static MoonsamaConfig config(URI apiUrl) {
        return new MoonsamaConfig(
                apiUrl,
                URI.create("https://portal.example"),
                "key",
                "secret",
                "client",
                "client-secret",
                URI.create("http://127.0.0.1/callback"),
                "127.0.0.1",
                8080,
                Duration.ofMinutes(10),
                false,
                URI.create("http://127.0.0.1/resourcepack.zip"),
                "resourcepack.zip",
                false,
                true,
                true,
                true,
                true,
                Duration.ofSeconds(60),
                Duration.ofSeconds(5),
                Duration.ofDays(1),
                Duration.ofSeconds(30)
        );
    }

    private static String receiptJson(String key) {
        return """
                {
                  "receiptId":"receipt-1",
                  "idempotencyKey":"%s",
                  "kind":"SPEND",
                  "outcome":"COMPLETED",
                  "reference":"craft-1",
                  "description":"Craft",
                  "originalKey":null,
                  "createdAt":1789000000000,
                  "items":[{
                    "playerId":"plr_test",
                    "collection":"sandbox-gold",
                    "tokenId":"0",
                    "amount":"10",
                    "burned":"10",
                    "toTreasury":"0",
                    "treasuryBps":0,
                    "sweptAt":null
                  }]
                }
                """.formatted(key);
    }

    private static void respond(HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private record Fixture(
            SqlitePortalStore store,
            PortalClient portal,
            ExecutorService databaseExecutor,
            ScheduledExecutorService scheduler,
            EconomyCoordinator coordinator
    ) implements AutoCloseable {
        Write sendingWrite(String portalKey) {
            Instant now = Instant.now();
            Write write = new Write(
                    UUID.randomUUID(),
                    "ExamplePlugin",
                    "craft-1",
                    portalKey,
                    "payload",
                    EconomyOperation.Kind.SPEND,
                    EconomyOperation.State.SENDING,
                    List.of(new ResolvedLine(
                            0,
                            UUID.randomUUID(),
                            "plr_test",
                            "sandbox-gold",
                            "0",
                            "10"
                    )),
                    "Craft",
                    "craft-1",
                    null,
                    1,
                    now,
                    null,
                    null,
                    now,
                    now
            );
            return store.createOrGetWrite(write);
        }

        @Override
        public void close() {
            scheduler.shutdownNow();
            databaseExecutor.shutdownNow();
            portal.close();
        }
    }
}
