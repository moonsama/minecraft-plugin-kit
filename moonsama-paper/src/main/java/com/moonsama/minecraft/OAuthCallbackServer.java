package com.moonsama.minecraft;

import com.moonsama.minecraft.store.LinkStore;
import com.moonsama.minecraft.store.LinkedPlayer;
import com.moonsama.minecraft.store.OAuthAttempt;
import com.moonsama.portal.PortalClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.Collectors;

final class OAuthCallbackServer implements AutoCloseable {
    private final MoonsamaConfig config;
    private final PortalClient portal;
    private final LinkStore links;
    private final Consumer<LinkedPlayer> onLinked;
    private final ResourcePackManager resourcePack;
    private final Consumer<Throwable> onError;
    private final Executor databaseExecutor;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private HttpServer server;

    OAuthCallbackServer(
            MoonsamaConfig config,
            PortalClient portal,
            LinkStore links,
            Consumer<LinkedPlayer> onLinked,
            ResourcePackManager resourcePack,
            Executor databaseExecutor,
            Consumer<Throwable> onError
    ) {
        this.config = config;
        this.portal = portal;
        this.links = links;
        this.onLinked = onLinked;
        this.resourcePack = resourcePack;
        this.databaseExecutor = databaseExecutor;
        this.onError = onError;
    }

    void start() throws IOException {
        server = HttpServer.create(
                new InetSocketAddress(config.callbackBindHost(), config.callbackPort()),
                0
        );
        server.createContext("/callback", this::handle);
        if (resourcePack != null) {
            server.createContext("/resourcepack.zip", resourcePack::handle);
        }
        server.setExecutor(executor);
        server.start();
    }

    private void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "Method not allowed", false);
            return;
        }

        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
        if (query.containsKey("error")) {
            respond(exchange, 400,
                    "Portal login was not completed: "
                            + query.getOrDefault("error_description", query.get("error")),
                    false);
            return;
        }

        String code = query.get("code");
        String state = query.get("state");
        if (code == null || state == null) {
            respond(exchange, 400, "Missing OAuth code or state.", false);
            return;
        }

        OAuthAttempt attempt = CompletableFuture.supplyAsync(
                () -> links.consumeAttempt(state).orElse(null),
                databaseExecutor
        ).join();
        if (attempt == null) {
            respond(exchange, 400, "This link is invalid, expired, or already used.", false);
            return;
        }

        try {
            var tokens = portal.exchangeCode(
                    config.oauthClientId(),
                    config.oauthClientSecret(),
                    code,
                    config.oauthRedirectUri(),
                    attempt.codeVerifier()
            ).join();
            var userInfo = portal.userInfo(tokens.accessToken()).join();
            LinkedPlayer linked = new LinkedPlayer(
                    attempt.mojangUuid(),
                    userInfo.sub(),
                    userInfo.preferredUsername(),
                    Instant.now()
            );
            CompletableFuture.runAsync(
                    () -> links.saveLink(linked),
                    databaseExecutor
            ).join();
            onLinked.accept(linked);
            respond(exchange, 200,
                    "Your Moonsama account is linked. You can return to Minecraft.",
                    true);
        } catch (RuntimeException exception) {
            onError.accept(exception);
            respond(exchange, 502,
                    "Portal login could not be completed. Return to Minecraft and try /moonsama link again.",
                    false);
        }
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return Map.of();
        }
        return Arrays.stream(rawQuery.split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(
                        pair -> decode(pair[0]),
                        pair -> pair.length == 2 ? decode(pair[1]) : "",
                        (first, ignored) -> first
                ));
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static void respond(
            HttpExchange exchange,
            int status,
            String message,
            boolean success
    ) throws IOException {
        String color = success ? "#51cf66" : "#ff6b6b";
        String body = """
                <!doctype html>
                <html lang="en">
                  <head>
                    <meta charset="utf-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                    <title>Moonsama Minecraft</title>
                  </head>
                  <body style="font-family:system-ui;background:#111;color:#eee;padding:3rem">
                    <main style="max-width:42rem;margin:auto">
                      <h1 style="color:%s">Moonsama Minecraft</h1>
                      <p>%s</p>
                    </main>
                  </body>
                </html>
                """.formatted(color, escapeHtml(message));
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String escapeHtml(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop(0);
        }
        executor.close();
    }
}
