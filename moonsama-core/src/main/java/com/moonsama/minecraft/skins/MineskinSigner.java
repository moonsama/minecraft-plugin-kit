package com.moonsama.minecraft.skins;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moonsama.minecraft.api.SkinSigner;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * {@link SkinSigner} backed by the MineSkin v2 queue API.
 *
 * <p>Requests are submitted as multipart uploads to {@code POST /v2/queue}; if MineSkin does not
 * answer with a finished skin straight away the job is polled at {@code GET /v2/queue/{id}}.
 * Successful results are stored in a {@link SignatureCache} keyed by the PNG's SHA-256 so the
 * same composition is never uploaded twice. All work happens on the supplied executor, which
 * should be single-threaded to respect MineSkin's rate limits.
 */
public final class MineskinSigner implements SkinSigner {
    static final int MAX_POLLS = 40;
    static final Duration POLL_INTERVAL = Duration.ofMillis(1500);

    private final URI baseUrl;
    private final String apiKey;
    private final String userAgent;
    private final HttpClient http;
    private final SignatureCache cache;
    private final Executor executor;
    private final Consumer<String> log;
    private final Sleeper sleeper;

    public MineskinSigner(URI baseUrl, String apiKey, String userAgent, SignatureCache cache,
                          Executor executor, Consumer<String> log) {
        this(baseUrl, apiKey, userAgent, cache, executor, log,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build(), MineskinSigner::sleep);
    }

    MineskinSigner(URI baseUrl, String apiKey, String userAgent, SignatureCache cache, Executor executor,
                   Consumer<String> log, HttpClient http, Sleeper sleeper) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey == null || apiKey.isBlank() ? null : apiKey.trim();
        this.userAgent = userAgent;
        this.cache = cache;
        this.executor = executor;
        this.log = log;
        this.http = http;
        this.sleeper = sleeper;
    }

    @Override
    public boolean isAvailable() {
        return apiKey != null;
    }

    @Override
    public boolean isCached(byte[] png, Variant variant) {
        return cache.get(sha256(png), variant).isPresent();
    }

    @Override
    public CompletableFuture<SignedTexture> sign(byte[] png, Variant variant) {
        if (!isAvailable()) {
            return CompletableFuture.failedFuture(new SigningException(
                    "Skin signing is not configured; set MINESKIN_API_KEY for MoonsamaCore"));
        }
        String hash = sha256(png);
        Optional<SignedTexture> cached = cache.get(hash, variant);
        if (cached.isPresent()) {
            return CompletableFuture.completedFuture(cached.get());
        }
        return CompletableFuture.supplyAsync(() -> {
            Optional<SignedTexture> again = cache.get(hash, variant); // a queued twin may have finished
            if (again.isPresent()) {
                return again.get();
            }
            SignedTexture signed = generate(png, variant, hash);
            cache.put(hash, variant, signed);
            return signed;
        }, executor);
    }

    // ------------------------------------------------------------------ HTTP

    private SignedTexture generate(byte[] png, Variant variant, String hash) {
        String boundary = "----MoonsamaSkin" + hash.substring(0, 16);
        byte[] body = multipart(boundary, png, variant, "moonsama-" + hash.substring(0, 12));
        HttpRequest request = authorized(HttpRequest.newBuilder(baseUrl.resolve("/v2/queue")))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        JsonObject response = send(request);
        Optional<SignedTexture> immediate = extractSkin(response);
        if (immediate.isPresent()) {
            return immediate.get();
        }
        JsonObject job = response.getAsJsonObject("job");
        if (job == null || !job.has("id")) {
            throw new SigningException("MineSkin returned neither a skin nor a job: " + response);
        }
        String jobId = job.get("id").getAsString();
        for (int attempt = 0; attempt < MAX_POLLS; attempt++) {
            sleeper.sleep(POLL_INTERVAL);
            JsonObject polled = send(authorized(HttpRequest.newBuilder(baseUrl.resolve("/v2/queue/" + jobId)))
                    .timeout(Duration.ofSeconds(30)).GET().build());
            Optional<SignedTexture> skin = extractSkin(polled);
            if (skin.isPresent()) {
                return skin.get();
            }
            JsonObject polledJob = polled.getAsJsonObject("job");
            String status = polledJob != null && polledJob.has("status")
                    ? polledJob.get("status").getAsString().toLowerCase(Locale.ROOT) : "unknown";
            if ("failed".equals(status) || "cancelled".equals(status)) {
                throw new SigningException("MineSkin job " + jobId + " " + status + ": " + errors(polled));
            }
        }
        throw new SigningException("MineSkin job " + jobId + " did not finish in time");
    }

    private HttpRequest.Builder authorized(HttpRequest.Builder builder) {
        return builder
                .header("Authorization", "Bearer " + apiKey)
                .header("User-Agent", userAgent)
                .header("Accept", "application/json");
    }

    private JsonObject send(HttpRequest request) {
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new SigningException("MineSkin request failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SigningException("Interrupted while talking to MineSkin", e);
        }
        JsonObject json;
        try {
            JsonElement parsed = JsonParser.parseString(response.body());
            json = parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException e) {
            json = new JsonObject();
        }
        int status = response.statusCode();
        if (status == 429) {
            throw new SigningException("MineSkin rate limit reached; try again in "
                    + response.headers().firstValue("Retry-After").orElse("a few") + " seconds");
        }
        if (status == 401 || status == 403) {
            throw new SigningException("MineSkin rejected the API key (" + status + "): " + errors(json));
        }
        if (status >= 400) {
            throw new SigningException("MineSkin error " + status + ": " + errors(json));
        }
        if (json.has("success") && !json.get("success").getAsBoolean()) {
            throw new SigningException("MineSkin reported failure: " + errors(json));
        }
        return json;
    }

    static Optional<SignedTexture> extractSkin(JsonObject response) {
        JsonObject skin = response.getAsJsonObject("skin");
        if (skin == null) {
            return Optional.empty();
        }
        JsonObject texture = skin.getAsJsonObject("texture");
        JsonObject data = texture == null ? null : texture.getAsJsonObject("data");
        if (data == null || !data.has("value") || !data.has("signature")) {
            return Optional.empty();
        }
        return Optional.of(new SignedTexture(data.get("value").getAsString(), data.get("signature").getAsString(),
                textureUrl(texture.get("url"))));
    }

    /** v2 returns {@code "url": {"skin": "...", "cape": ...}}; older responses used a plain string. */
    static String textureUrl(JsonElement url) {
        if (url == null || url.isJsonNull()) {
            return null;
        }
        if (url.isJsonPrimitive()) {
            return url.getAsString();
        }
        if (url.isJsonObject()) {
            JsonElement skin = url.getAsJsonObject().get("skin");
            return skin != null && skin.isJsonPrimitive() ? skin.getAsString() : null;
        }
        return null;
    }

    private static String errors(JsonObject json) {
        JsonArray errors = json.has("errors") && json.get("errors").isJsonArray() ? json.getAsJsonArray("errors") : null;
        if (errors == null || errors.isEmpty()) {
            return json.has("message") ? json.get("message").getAsString() : "no details";
        }
        StringBuilder text = new StringBuilder();
        for (JsonElement error : errors) {
            if (!text.isEmpty()) {
                text.append("; ");
            }
            if (error.isJsonObject()) {
                JsonObject object = error.getAsJsonObject();
                text.append(object.has("code") ? object.get("code").getAsString() : "error");
                if (object.has("message")) {
                    text.append(": ").append(object.get("message").getAsString());
                }
            } else {
                text.append(error.getAsString());
            }
        }
        return text.toString();
    }

    static byte[] multipart(String boundary, byte[] png, Variant variant, String name) {
        String head = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"variant\"\r\n\r\n" + variant.name().toLowerCase(Locale.ROOT) + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"visibility\"\r\n\r\nunlisted\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"name\"\r\n\r\n" + name + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"skin.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n";
        String tail = "\r\n--" + boundary + "--\r\n";
        byte[] headBytes = head.getBytes(StandardCharsets.UTF_8);
        byte[] tailBytes = tail.getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[headBytes.length + png.length + tailBytes.length];
        System.arraycopy(headBytes, 0, body, 0, headBytes.length);
        System.arraycopy(png, 0, body, headBytes.length, png.length);
        System.arraycopy(tailBytes, 0, body, headBytes.length + png.length, tailBytes.length);
        return body;
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SigningException("Interrupted while waiting for MineSkin", e);
        }
    }

    interface Sleeper {
        void sleep(Duration duration);
    }
}
