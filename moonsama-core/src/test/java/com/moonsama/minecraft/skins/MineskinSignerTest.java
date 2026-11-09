package com.moonsama.minecraft.skins;

import com.moonsama.minecraft.api.SkinSigner;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MineskinSignerTest {
    private static final byte[] PNG = "not-really-a-png".getBytes(StandardCharsets.UTF_8);

    private HttpServer server;
    private ExecutorService executor;
    private final List<String> requests = new ArrayList<>();
    private final AtomicInteger polls = new AtomicInteger();

    @TempDir
    Path temp;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newSingleThreadExecutor();
        server.createContext("/v2/queue", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath()
                    + " auth=" + exchange.getRequestHeaders().getFirst("Authorization"));
            String path = exchange.getRequestURI().getPath();
            if ("POST".equals(exchange.getRequestMethod())) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                assertThat(body).contains("name=\"file\"; filename=\"skin.png\"").contains("not-really-a-png")
                        .contains("name=\"variant\"\r\n\r\nslim");
                respond(exchange, 202, "{\"success\":true,\"job\":{\"id\":\"job-1\",\"status\":\"waiting\"}}");
            } else if (path.endsWith("/job-1")) {
                if (polls.incrementAndGet() < 2) {
                    respond(exchange, 200, "{\"success\":true,\"job\":{\"id\":\"job-1\",\"status\":\"processing\"}}");
                } else {
                    respond(exchange, 200, "{\"success\":true,\"job\":{\"id\":\"job-1\",\"status\":\"completed\"},"
                            + "\"skin\":{\"uuid\":\"abc\",\"texture\":{\"url\":{\"skin\":\"http://textures.minecraft.net/texture/xyz\",\"cape\":null},"
                            + "\"data\":{\"value\":\"VALUE\",\"signature\":\"SIG\"}}}}");
                }
            } else {
                respond(exchange, 404, "{\"success\":false,\"errors\":[{\"code\":\"job_not_found\",\"message\":\"nope\"}]}");
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    private MineskinSigner signer(String apiKey, SignatureCache cache) {
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        return new MineskinSigner(base, apiKey, "test-agent", cache, executor, message -> {
        }, HttpClient.newHttpClient(), duration -> {
        });
    }

    @Test
    void queuesPollsAndCachesByHash() throws Exception {
        SignatureCache cache = new SignatureCache(temp.resolve("cache.json"));
        MineskinSigner signer = signer("secret-key", cache);
        assertThat(signer.isAvailable()).isTrue();

        SkinSigner.SignedTexture signed = signer.sign(PNG, SkinSigner.Variant.SLIM).get();
        assertThat(signed.value()).isEqualTo("VALUE");
        assertThat(signed.signature()).isEqualTo("SIG");
        assertThat(signed.textureUrl()).isEqualTo("http://textures.minecraft.net/texture/xyz");
        assertThat(requests).hasSize(3).allSatisfy(line -> assertThat(line).contains("auth=Bearer secret-key"));

        // Second call is served from the cache without touching the network.
        assertThat(signer.sign(PNG, SkinSigner.Variant.SLIM).get()).isEqualTo(signed);
        assertThat(requests).hasSize(3);
        // A different variant is a different texture.
        assertThat(cache.get(MineskinSigner.sha256(PNG), SkinSigner.Variant.CLASSIC)).isEmpty();

        SignatureCache reloaded = new SignatureCache(temp.resolve("cache.json"));
        reloaded.load();
        assertThat(reloaded.get(MineskinSigner.sha256(PNG), SkinSigner.Variant.SLIM)).contains(signed);
    }

    @Test
    void failsFastWithoutApiKey() {
        MineskinSigner signer = signer("", new SignatureCache(null));
        assertThat(signer.isAvailable()).isFalse();
        assertThatThrownBy(() -> signer.sign(PNG, SkinSigner.Variant.CLASSIC).get())
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(SkinSigner.SigningException.class)
                .hasMessageContaining("MINESKIN_API_KEY");
        assertThat(requests).isEmpty();
    }

    @Test
    void reportsBackendErrors() {
        server.removeContext("/v2/queue");
        server.createContext("/v2/queue", exchange -> respond(exchange, 401,
                "{\"success\":false,\"errors\":[{\"code\":\"unauthorized\",\"message\":\"bad key\"}]}"));
        MineskinSigner signer = signer("wrong", new SignatureCache(null));
        assertThatThrownBy(() -> signer.sign(PNG, SkinSigner.Variant.CLASSIC).get())
                .hasCauseInstanceOf(SkinSigner.SigningException.class)
                .hasMessageContaining("unauthorized: bad key");
    }

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
