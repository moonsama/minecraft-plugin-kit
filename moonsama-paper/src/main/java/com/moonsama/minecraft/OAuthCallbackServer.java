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
import java.time.Duration;
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
    /** How long the browser confirmation page stays valid after Portal login. */
    static final Duration CONFIRMATION_TTL = Duration.ofMinutes(5);
    private static final int MAX_FORM_BYTES = 4096;

    private final MoonsamaConfig config;
    private final PortalClient portal;
    private final LinkStore links;
    private final Consumer<LinkedPlayer> onLinked;
    private final ResourcePackManager resourcePack;
    private final Consumer<Throwable> onError;
    private final Executor databaseExecutor;
    private final LinkConfirmations confirmations = new LinkConfirmations(CONFIRMATION_TTL);
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
        server.createContext("/callback", this::handleCallback);
        server.createContext("/callback/confirm", this::handleConfirm);
        server.createContext("/callback/cancel", this::handleCancel);
        if (resourcePack != null) {
            server.createContext("/resourcepack.zip", resourcePack::handle);
        }
        server.setExecutor(executor);
        server.start();
    }

    /** Actual bound port (differs from the configured one only when it was 0). */
    int port() {
        return server.getAddress().getPort();
    }

    /**
     * Step 1: Portal redirects the browser here after login. The code is exchanged and the
     * Portal identity resolved, but nothing is linked yet - the page asks the Portal user to
     * confirm the Minecraft account by name (see {@link LinkConfirmations}).
     */
    private void handleCallback(HttpExchange exchange) throws IOException {
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
            String minecraftName = attempt.mojangName() == null || attempt.mojangName().isBlank()
                    ? attempt.mojangUuid().toString()
                    : attempt.mojangName();
            LinkConfirmations.Pending pending = confirmations.create(linked, minecraftName);
            respondHtml(exchange, 200, confirmationPage(pending));
        } catch (RuntimeException exception) {
            onError.accept(exception);
            respond(exchange, 502,
                    "Portal login could not be completed. Return to Minecraft and try /moonsama link again.",
                    false);
        }
    }

    /** Step 2: the Portal user clicked "Yes, link" on the confirmation page. */
    private void handleConfirm(HttpExchange exchange) throws IOException {
        LinkConfirmations.Pending pending = takePending(exchange);
        if (pending == null) {
            return;
        }
        try {
            CompletableFuture.runAsync(
                    () -> links.saveLink(pending.link()),
                    databaseExecutor
            ).join();
            onLinked.accept(pending.link());
            respond(exchange, 200,
                    "Your Moonsama account is linked to " + pending.minecraftName()
                            + ". You can return to Minecraft.",
                    true);
        } catch (RuntimeException exception) {
            onError.accept(exception);
            respond(exchange, 500,
                    "The link could not be saved. Return to Minecraft and try /moonsama link again.",
                    false);
        }
    }

    /** The Portal user said the Minecraft account is not theirs. */
    private void handleCancel(HttpExchange exchange) throws IOException {
        LinkConfirmations.Pending pending = takePending(exchange);
        if (pending == null) {
            return;
        }
        respond(exchange, 200,
                "Nothing was linked. If you did not start this from your own Minecraft account, "
                        + "somebody sent you their link - ignore it.",
                false);
    }

    /** Reads the confirmation token from a POST form; responds with an error and returns null if invalid. */
    private LinkConfirmations.Pending takePending(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "Method not allowed", false);
            return null;
        }
        byte[] body = exchange.getRequestBody().readNBytes(MAX_FORM_BYTES + 1);
        if (body.length > MAX_FORM_BYTES) {
            respond(exchange, 413, "Request too large.", false);
            return null;
        }
        Map<String, String> form = parseQuery(new String(body, StandardCharsets.UTF_8));
        LinkConfirmations.Pending pending = confirmations.take(form.get("token")).orElse(null);
        if (pending == null) {
            respond(exchange, 400,
                    "This confirmation is invalid, expired, or already used. "
                            + "Return to Minecraft and run /moonsama link again.",
                    false);
        }
        return pending;
    }

    private static String confirmationPage(LinkConfirmations.Pending pending) {
        String minecraft = escapeHtml(pending.minecraftName());
        String portalName = pending.link().gamerTag() == null || pending.link().gamerTag().isBlank()
                ? "your Portal account"
                : "Portal account <strong>" + escapeHtml(pending.link().gamerTag()) + "</strong>";
        String token = escapeHtml(pending.token());
        return page("#ffd43b", """
                <p>You are about to link %s to the Minecraft player
                   <strong style="font-size:1.4em">%s</strong>.</p>
                <p><strong>Only continue if %s is your own Minecraft account.</strong>
                   If somebody sent you this link, they would gain access to your Portal
                   account's NFTs and balance in this game - click "Not me" instead.</p>
                <form method="post" action="/callback/confirm" style="display:inline">
                  <input type="hidden" name="token" value="%s">
                  <button type="submit" style="font-size:1.1em;padding:.6em 1.4em;background:#51cf66;border:0;border-radius:.4em;cursor:pointer">
                    Yes, link %s
                  </button>
                </form>
                <form method="post" action="/callback/cancel" style="display:inline;margin-left:1rem">
                  <input type="hidden" name="token" value="%s">
                  <button type="submit" style="font-size:1.1em;padding:.6em 1.4em;background:#444;color:#eee;border:0;border-radius:.4em;cursor:pointer">
                    Not me
                  </button>
                </form>
                """.formatted(portalName, minecraft, minecraft, token, minecraft, token));
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
        respondHtml(exchange, status, page(color, "<p>" + escapeHtml(message) + "</p>"));
    }

    /** {@code content} must already be HTML-escaped where it carries user data. */
    private static String page(String headingColor, String content) {
        return """
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
                      %s
                    </main>
                  </body>
                </html>
                """.formatted(headingColor, content);
    }

    private static void respondHtml(HttpExchange exchange, int status, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
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
