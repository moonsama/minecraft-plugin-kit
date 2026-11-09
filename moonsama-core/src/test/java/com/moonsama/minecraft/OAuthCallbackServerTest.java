package com.moonsama.minecraft;

import com.moonsama.minecraft.store.LinkedPlayer;
import com.moonsama.minecraft.store.OAuthAttempt;
import com.moonsama.minecraft.store.SqliteJournalMode;
import com.moonsama.minecraft.store.SqliteLinkStore;
import com.moonsama.portal.PortalClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the embedded callback server against a stub Portal. The important property: a
 * completed Portal login must not create a link until the browser confirms the Minecraft name.
 */
class OAuthCallbackServerTest {
    private static final Pattern TOKEN = Pattern.compile("name=\"token\" value=\"([^\"]+)\"");

    @TempDir
    Path temporaryDirectory;

    private HttpServer portalStub;
    private PortalClient portal;
    private SqliteLinkStore links;
    private ExecutorService databaseExecutor;
    private OAuthCallbackServer callback;
    private final List<LinkedPlayer> linkedEvents = new CopyOnWriteArrayList<>();
    private final List<Throwable> errors = new CopyOnWriteArrayList<>();
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final UUID attacker = UUID.randomUUID();

    @BeforeEach
    void start() throws IOException {
        portalStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        portalStub.createContext("/oauth2/token", exchange -> json(exchange,
                "{\"access_token\":\"tok\",\"token_type\":\"Bearer\",\"expires_in\":3600}"));
        portalStub.createContext("/oauth2/resource/userinfo", exchange -> json(exchange,
                "{\"sub\":\"plr_victim\",\"preferred_username\":\"Victim\"}"));
        portalStub.start();
        URI apiUrl = URI.create("http://127.0.0.1:" + portalStub.getAddress().getPort());
        portal = new PortalClient(new PortalClient.Options(apiUrl, "key", "secret", Duration.ofSeconds(2), 1));

        links = new SqliteLinkStore(temporaryDirectory.resolve("links.db"));
        links.initialize();
        databaseExecutor = Executors.newSingleThreadExecutor();
        callback = new OAuthCallbackServer(
                config(apiUrl), portal, links, linkedEvents::add, null, databaseExecutor, errors::add);
        callback.start();
    }

    @AfterEach
    void stop() {
        callback.close();
        portal.close();
        portalStub.stop(0);
        databaseExecutor.shutdownNow();
    }

    @Test
    void portalLoginAloneDoesNotLink_confirmationDoes() throws Exception {
        links.createAttempt(attempt("state-1"));

        HttpResponse<String> page = get("/callback?code=abc&state=state-1");
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("Steve").contains("Victim").contains("Yes, link");
        assertThat(page.headers().firstValue("X-Frame-Options")).contains("DENY");
        // Nothing persisted yet: the Portal owner has not confirmed the Minecraft account.
        assertThat(links.findLink(attacker)).isEmpty();
        assertThat(linkedEvents).isEmpty();

        HttpResponse<String> confirmed = post("/callback/confirm", "token=" + tokenIn(page.body()));
        assertThat(confirmed.statusCode()).isEqualTo(200);
        assertThat(confirmed.body()).contains("linked to Steve");
        assertThat(links.findLink(attacker)).get()
                .extracting(LinkedPlayer::playerId, LinkedPlayer::gamerTag)
                .containsExactly("plr_victim", "Victim");
        assertThat(linkedEvents).hasSize(1);

        // The confirmation token is single use, and the OAuth state was consumed by the first GET.
        assertThat(post("/callback/confirm", "token=" + tokenIn(page.body())).statusCode()).isEqualTo(400);
        assertThat(get("/callback?code=abc&state=state-1").statusCode()).isEqualTo(400);
        assertThat(errors).isEmpty();
    }

    @Test
    void notMeCancelsWithoutLinking() throws Exception {
        links.createAttempt(attempt("state-2"));
        HttpResponse<String> page = get("/callback?code=abc&state=state-2");
        String token = tokenIn(page.body());

        HttpResponse<String> cancelled = post("/callback/cancel", "token=" + token);
        assertThat(cancelled.statusCode()).isEqualTo(200);
        assertThat(cancelled.body()).contains("Nothing was linked");
        assertThat(links.findLink(attacker)).isEmpty();
        assertThat(linkedEvents).isEmpty();
        // Cancelling burns the token; it cannot be confirmed afterwards.
        assertThat(post("/callback/confirm", "token=" + token).statusCode()).isEqualTo(400);
    }

    @Test
    void rejectsForgedOrMissingTokensAndWrongMethods() throws Exception {
        assertThat(post("/callback/confirm", "token=forged").statusCode()).isEqualTo(400);
        assertThat(post("/callback/confirm", "").statusCode()).isEqualTo(400);
        assertThat(get("/callback/confirm?token=x").statusCode()).isEqualTo(405);
        assertThat(get("/callback").statusCode()).isEqualTo(400);
        assertThat(get("/callback?code=abc&state=unknown").statusCode()).isEqualTo(400);
        assertThat(links.findLink(attacker)).isEmpty();
    }

    @Test
    void escapesNamesOnTheConfirmationPage() throws Exception {
        links.createAttempt(new OAuthAttempt(
                "state-3", attacker, "<img src=x onerror=alert(1)>", "verifier",
                Instant.now().plusSeconds(60)));
        HttpResponse<String> page = get("/callback?code=abc&state=state-3");
        assertThat(page.body()).doesNotContain("<img").contains("&lt;img");
    }

    private OAuthAttempt attempt(String state) {
        return new OAuthAttempt(state, attacker, "Steve", "verifier", Instant.now().plusSeconds(60));
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(callbackUri(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String form) throws Exception {
        return http.send(HttpRequest.newBuilder(callbackUri(path))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI callbackUri(String path) {
        return URI.create("http://127.0.0.1:" + callback.port() + path);
    }

    private static String tokenIn(String html) {
        Matcher matcher = TOKEN.matcher(html);
        assertThat(matcher.find()).as("confirmation token in page").isTrue();
        return matcher.group(1);
    }

    private static void json(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
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
                0,
                Duration.ofMinutes(10),
                false,
                URI.create("http://127.0.0.1/resourcepack.zip"),
                "resourcepack.zip",
                false,
                false,
                false,
                false,
                false,
                Duration.ofSeconds(60),
                Duration.ofSeconds(5),
                Duration.ofDays(1),
                Duration.ofSeconds(30),
                "",
                URI.create("https://api.mineskin.org"),
                SqliteJournalMode.WAL
        );
    }
}
