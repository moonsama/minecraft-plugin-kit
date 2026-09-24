package com.moonsama.portal;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PortalClientTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void readsCatalogAndSendsCredentials() throws Exception {
        server = server(exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("x-api-key")).isEqualTo("key");
            assertThat(exchange.getRequestHeaders().getFirst("x-api-secret")).isEqualTo("secret");
            respond(exchange, 200, """
                    {"results":[{
                      "collection":"sandbox-items",
                      "description":"Items",
                      "idRange":["1-1000"],
                      "assetType":"ERC1155",
                      "decimals":0,
                      "enabled":true,
                      "rights":{"spend":true,"reward":true},
                      "futureField":"ignored"
                    }]}
                    """);
        });

        try (PortalClient client = client()) {
            List<PortalModels.CatalogEntry> result = client.catalog().join();
            assertThat(result).singleElement().satisfies(entry -> {
                assertThat(entry.collection()).isEqualTo("sandbox-items");
                assertThat(entry.enabled()).isTrue();
                assertThat(entry.rights().spend()).isTrue();
            });
        }
    }

    @Test
    void exposesPortalRefusalCode() throws Exception {
        server = server(exchange -> respond(exchange, 403, """
                {"statusCode":403,"message":"PLAYER_CONSENT_REQUIRED: login again.","error":"Forbidden"}
                """));

        try (PortalClient client = client()) {
            assertThatThrownBy(() -> client.holdings(List.of("plr_test")).join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(PortalException.class)
                    .cause()
                    .extracting("code")
                    .isEqualTo("PLAYER_CONSENT_REQUIRED");
        }
    }

    @Test
    void buildsPkceAuthorizationUrl() {
        PortalOAuth.Attempt attempt = PortalOAuth.newAttempt();
        URI uri = PortalOAuth.authorizationUri(
                URI.create("https://portal.moonsama.com"),
                "client id",
                URI.create("http://127.0.0.1:8080/callback"),
                attempt.state(),
                attempt.challenge()
        );

        assertThat(uri.toString())
                .startsWith("https://portal.moonsama.com/oauth?")
                .contains("client_id=client+id")
                .contains("scope=openid+profile+economy")
                .contains("code_challenge_method=S256");
        assertThat(attempt.verifier()).hasSizeBetween(43, 128);
        assertThat(attempt.challenge()).doesNotContain("=");
    }

    @Test
    void sendsKeyedSpendAndReadsReceipt() throws Exception {
        server = server(exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            assertThat(exchange.getRequestURI().getPath()).isEqualTo("/v1/economy/spend");
            assertThat(exchange.getRequestHeaders().getFirst("Idempotency-Key"))
                    .isEqualTo("order-1042");
            assertThat(new String(
                    exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8
            )).contains(
                    "\"playerId\":\"plr_test\"",
                    "\"amount\":\"25\"",
                    "\"reference\":\"order-1042\""
            );
            respond(exchange, 201, """
                    {
                      "receiptId":"receipt-1",
                      "idempotencyKey":"order-1042",
                      "kind":"SPEND",
                      "outcome":"COMPLETED",
                      "reference":"order-1042",
                      "description":"Crafting kit",
                      "originalKey":null,
                      "createdAt":1789000000000,
                      "items":[{
                        "playerId":"plr_test",
                        "collection":"sandbox-gold",
                        "tokenId":"0",
                        "amount":"25",
                        "burned":"25",
                        "toTreasury":"0",
                        "treasuryBps":0,
                        "sweptAt":null
                      }]
                    }
                    """);
        });

        try (PortalClient client = client()) {
            var receipt = client.spend(
                    "order-1042",
                    new PortalModels.WriteRequest(
                            List.of(new PortalModels.Line(
                                    "plr_test",
                                    "sandbox-gold",
                                    "0",
                                    "25"
                            )),
                            "Crafting kit",
                            "order-1042"
                    )
            ).join();
            assertThat(receipt.kind()).isEqualTo("SPEND");
            assertThat(receipt.items()).singleElement()
                    .extracting(PortalModels.ReceiptItem::burned)
                    .isEqualTo("25");
        }
    }

    @Test
    void neverAutomaticallyRetriesHoldPlacement() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server = server(exchange -> {
            requests.incrementAndGet();
            respond(exchange, 500, """
                    {"statusCode":500,"message":"Temporary failure","error":"Internal Server Error"}
                    """);
        });

        try (PortalClient client = new PortalClient(new PortalClient.Options(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "key",
                "secret",
                Duration.ofSeconds(2),
                4
        ))) {
            assertThatThrownBy(() -> client.placeHold(new PortalModels.HoldRequest(
                    "plr_test",
                    "sandbox-items",
                    "1",
                    900,
                    "match-1"
            )).join()).isInstanceOf(CompletionException.class);
            assertThat(requests).hasValue(1);
        }
    }

    @Test
    void readsChangeFeedAndRateLimitHeaders() throws Exception {
        server = server(exchange -> {
            assertThat(exchange.getRequestURI().getRawQuery())
                    .contains("since=100", "take=50");
            exchange.getResponseHeaders().set("X-RateLimit-Limit", "120");
            exchange.getResponseHeaders().set("X-RateLimit-Remaining", "119");
            exchange.getResponseHeaders().set("X-RateLimit-Reset", "1789000060");
            respond(exchange, 200, """
                    {
                      "results":[{
                        "playerId":"plr_test",
                        "collection":"sandbox-items",
                        "updatedAt":1789000000000
                      }],
                      "nextSince":1789000000000,
                      "nextCursor":"cursor-1",
                      "hasMore":false
                    }
                    """);
        });

        try (PortalClient client = client()) {
            var page = client.changes(PortalModels.FeedStart.since(100), 50).join();
            assertThat(page.results()).singleElement()
                    .extracting(PortalModels.Change::collection)
                    .isEqualTo("sandbox-items");
            assertThat(client.latestRateLimit()).get().satisfies(rateLimit -> {
                assertThat(rateLimit.limit()).isEqualTo(120);
                assertThat(rateLimit.remaining()).isEqualTo(119);
            });
        }
    }

    private PortalClient client() {
        return new PortalClient(new PortalClient.Options(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "key",
                "secret",
                Duration.ofSeconds(2),
                1
        ));
    }

    private static HttpServer server(ExchangeHandler handler) throws IOException {
        HttpServer result = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        result.createContext("/", exchange -> {
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        result.start();
        return result;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
