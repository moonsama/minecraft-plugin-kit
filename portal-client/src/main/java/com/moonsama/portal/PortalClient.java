package com.moonsama.portal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.moonsama.portal.PortalModels.CatalogEntry;
import com.moonsama.portal.PortalModels.Change;
import com.moonsama.portal.PortalModels.CollectionHoldings;
import com.moonsama.portal.PortalModels.Erasure;
import com.moonsama.portal.PortalModels.FeedPage;
import com.moonsama.portal.PortalModels.FeedStart;
import com.moonsama.portal.PortalModels.Hold;
import com.moonsama.portal.PortalModels.HoldRequest;
import com.moonsama.portal.PortalModels.PlayerHoldings;
import com.moonsama.portal.PortalModels.PlayerStanding;
import com.moonsama.portal.PortalModels.RateLimit;
import com.moonsama.portal.PortalModels.RedeemedCode;
import com.moonsama.portal.PortalModels.Receipt;
import com.moonsama.portal.PortalModels.RefundRequest;
import com.moonsama.portal.PortalModels.TokenSet;
import com.moonsama.portal.PortalModels.TokenMetadata;
import com.moonsama.portal.PortalModels.UserInfo;
import com.moonsama.portal.PortalModels.WriteRequest;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PortalClient implements AutoCloseable {
    private static final Pattern ERROR_CODE = Pattern.compile("^([A-Z][A-Z0-9_]+)\\b");
    private static final String USER_AGENT = "moonsama-minecraft-plugin-kit/0.1";

    private final Options options;
    private final HttpClient httpClient;
    private final ObjectMapper json;
    private final AtomicReference<RateLimit> latestRateLimit = new AtomicReference<>();

    private enum RetryPolicy {
        SAFE,
        NONE
    }

    public record Options(
            URI apiUrl,
            String apiKey,
            String apiSecret,
            Duration timeout,
            int maxAttempts
    ) {
        public Options {
            Objects.requireNonNull(apiUrl, "apiUrl");
            Objects.requireNonNull(apiKey, "apiKey");
            Objects.requireNonNull(apiSecret, "apiSecret");
            timeout = timeout == null ? Duration.ofSeconds(15) : timeout;
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("maxAttempts must be at least 1");
            }
        }

        public static Options defaults(URI apiUrl, String apiKey, String apiSecret) {
            return new Options(apiUrl, apiKey, apiSecret, Duration.ofSeconds(15), 4);
        }
    }

    private record CatalogResponse(List<CatalogEntry> results) {
    }

    private record PlayersResponse(List<PlayerStanding> results) {
    }

    private record HoldingsResponse(List<PlayerHoldings> results) {
    }

    private record ReceiptsResponse(List<Receipt> results) {
    }

    private record HoldsResponse(List<Hold> results) {
    }

    private record TokenMetadataResponse(List<TokenMetadata> results) {
    }

    public PortalClient(Options options) {
        this(options, HttpClient.newBuilder()
                .connectTimeout(options.timeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    PortalClient(Options options, HttpClient httpClient) {
        this.options = Objects.requireNonNull(options, "options");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.json = new ObjectMapper()
                .registerModule(new Jdk8Module())
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    public CompletableFuture<List<CatalogEntry>> catalog() {
        HttpRequest request = apiRequest("/v1/economy/catalog")
                .GET()
                .build();
        return send(request, new TypeReference<CatalogResponse>() {
        }).thenApply(CatalogResponse::results);
    }

    public CompletableFuture<List<PlayerStanding>> players(List<String> playerIds) {
        return jsonApiPost("/v1/economy/players/lookup", Map.of("playerIds", playerIds),
                new TypeReference<PlayersResponse>() {
                }).thenApply(PlayersResponse::results);
    }

    public CompletableFuture<PlayerStanding> player(String playerId) {
        HttpRequest request = apiRequest(
                "/v1/economy/players/" + pathSegment(playerId)
        ).GET().build();
        return send(request, new TypeReference<PlayerStanding>() {
        });
    }

    public CompletableFuture<List<PlayerHoldings>> holdings(List<String> playerIds) {
        return holdings(playerIds, false);
    }

    public CompletableFuture<List<PlayerHoldings>> holdings(
            List<String> playerIds,
            boolean breakdown
    ) {
        return jsonApiPost("/v1/economy/players/holdings",
                Map.of("playerIds", playerIds, "breakdown", breakdown),
                new TypeReference<HoldingsResponse>() {
                }).thenApply(HoldingsResponse::results);
    }

    public CompletableFuture<CollectionHoldings> collectionHoldings(
            String playerId,
            String collection,
            String tokenId,
            boolean breakdown
    ) {
        Map<String, String> query = new LinkedHashMap<>();
        if (tokenId != null) {
            query.put("tokenId", tokenId);
        }
        query.put("breakdown", Boolean.toString(breakdown));
        HttpRequest request = apiRequest(withQuery(
                "/v1/economy/players/" + pathSegment(playerId)
                        + "/holdings/" + pathSegment(collection),
                query,
                List.of()
        )).GET().build();
        return send(request, new TypeReference<CollectionHoldings>() {
        });
    }

    public CompletableFuture<RedeemedCode> redeemCode(
            String code,
            List<String> collections,
            boolean breakdown
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        if (collections != null) {
            body.put("collections", collections);
        }
        body.put("breakdown", breakdown);
        return jsonApiPost(
                "/v1/economy/players/redeem-code",
                body,
                new TypeReference<RedeemedCode>() {
                },
                RetryPolicy.NONE
        );
    }

    public CompletableFuture<TokenMetadata> tokenMetadata(
            String collection,
            String tokenId
    ) {
        HttpRequest request = apiRequest(
                "/v1/economy/collections/" + pathSegment(collection)
                        + "/tokens/" + pathSegment(tokenId)
        ).GET().build();
        return send(request, new TypeReference<TokenMetadata>() {
        });
    }

    public CompletableFuture<List<TokenMetadata>> tokenMetadata(
            String collection,
            List<String> tokenIds
    ) {
        HttpRequest request = apiRequest(withQuery(
                "/v1/economy/collections/" + pathSegment(collection) + "/tokens",
                Map.of("tokenIds", String.join(",", tokenIds)),
                List.of()
        )).GET().build();
        return send(request, new TypeReference<TokenMetadataResponse>() {
        }).thenApply(TokenMetadataResponse::results);
    }

    public CompletableFuture<Receipt> spend(String idempotencyKey, WriteRequest requestBody) {
        return keyedWrite("/v1/economy/spend", idempotencyKey, requestBody);
    }

    public CompletableFuture<Receipt> reward(String idempotencyKey, WriteRequest requestBody) {
        return keyedWrite("/v1/economy/rewards", idempotencyKey, requestBody);
    }

    public CompletableFuture<Receipt> refund(String idempotencyKey, RefundRequest requestBody) {
        return keyedWrite("/v1/economy/refunds", idempotencyKey, requestBody);
    }

    public CompletableFuture<Optional<Receipt>> receipt(String idempotencyKey) {
        HttpRequest request = apiRequest(
                "/v1/economy/receipts/" + pathSegment(idempotencyKey)
        ).GET().build();
        return send(request, new TypeReference<Receipt>() {
        }).handle((receipt, failure) -> {
            if (failure == null) {
                return CompletableFuture.completedFuture(Optional.of(receipt));
            }
            Throwable cause = unwrap(failure);
            if (cause instanceof PortalException portalFailure
                    && portalFailure.status() == 404) {
                return CompletableFuture.completedFuture(Optional.<Receipt>empty());
            }
            return CompletableFuture.<Optional<Receipt>>failedFuture(cause);
        }).thenCompose(Function.identity());
    }

    public CompletableFuture<List<Receipt>> receipts(
            long since,
            int take,
            String reference
    ) {
        HttpRequest request = apiRequest(withQuery(
                "/v1/economy/receipts",
                Map.of(
                        "since", Long.toString(since),
                        "take", Integer.toString(take),
                        "reference", reference == null ? "" : reference
                ),
                reference == null ? List.of("reference") : List.of()
        )).GET().build();
        return send(request, new TypeReference<ReceiptsResponse>() {
        }).thenApply(ReceiptsResponse::results);
    }

    public CompletableFuture<Hold> placeHold(HoldRequest requestBody) {
        return jsonApiPost(
                "/v1/economy/holds",
                requestBody,
                new TypeReference<Hold>() {
                },
                RetryPolicy.NONE
        );
    }

    public CompletableFuture<Hold> renewHold(String holdId, int ttlSeconds) {
        return jsonApiPost(
                "/v1/economy/holds/" + pathSegment(holdId) + "/renew",
                Map.of("ttlSeconds", ttlSeconds),
                new TypeReference<Hold>() {
                },
                RetryPolicy.NONE
        );
    }

    public CompletableFuture<Hold> releaseHold(String holdId) {
        HttpRequest request = apiRequest(
                "/v1/economy/holds/" + pathSegment(holdId)
        ).DELETE().build();
        return send(request, new TypeReference<Hold>() {
        });
    }

    public CompletableFuture<List<Hold>> holds(String playerId, int take) {
        HttpRequest request = apiRequest(withQuery(
                "/v1/economy/holds",
                Map.of(
                        "playerId", playerId == null ? "" : playerId,
                        "take", Integer.toString(take)
                ),
                playerId == null ? List.of("playerId") : List.of()
        )).GET().build();
        return send(request, new TypeReference<HoldsResponse>() {
        }).thenApply(HoldsResponse::results);
    }

    public CompletableFuture<FeedPage<Change>> changes(FeedStart from, int take) {
        HttpRequest request = apiRequest(feedPath("/v1/economy/changes", from, take))
                .GET()
                .build();
        return send(request, new TypeReference<FeedPage<Change>>() {
        });
    }

    public CompletableFuture<FeedPage<Erasure>> erasures(FeedStart from, int take) {
        HttpRequest request = apiRequest(
                feedPath("/v1/economy/players/erasures", from, take)
        ).GET().build();
        return send(request, new TypeReference<FeedPage<Erasure>>() {
        });
    }

    public Optional<RateLimit> latestRateLimit() {
        return Optional.ofNullable(latestRateLimit.get());
    }

    public CompletableFuture<TokenSet> exchangeCode(
            String clientId,
            String clientSecret,
            String code,
            URI redirectUri,
            String codeVerifier
    ) {
        String form = form(Map.of(
                "grant_type", "authorization_code",
                "client_id", clientId,
                "client_secret", clientSecret,
                "code", code,
                "redirect_uri", redirectUri.toString(),
                "code_verifier", codeVerifier
        ));
        HttpRequest request = baseRequest("/oauth2/token")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        return send(request, new TypeReference<TokenSet>() {
        }, RetryPolicy.NONE);
    }

    private CompletableFuture<Receipt> keyedWrite(
            String path,
            String idempotencyKey,
            Object body
    ) {
        final String requestBody;
        try {
            requestBody = json.writeValueAsString(body);
        } catch (JsonProcessingException exception) {
            return CompletableFuture.failedFuture(exception);
        }
        HttpRequest request = apiRequest(path)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
        return send(request, new TypeReference<Receipt>() {
        });
    }

    public CompletableFuture<UserInfo> userInfo(String accessToken) {
        HttpRequest request = baseRequest("/oauth2/resource/userinfo")
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();
        return send(request, new TypeReference<UserInfo>() {
        }, RetryPolicy.NONE);
    }

    private <T> CompletableFuture<T> jsonApiPost(
            String path,
            Object body,
            TypeReference<T> responseType
    ) {
        return jsonApiPost(path, body, responseType, RetryPolicy.SAFE);
    }

    private <T> CompletableFuture<T> jsonApiPost(
            String path,
            Object body,
            TypeReference<T> responseType,
            RetryPolicy retryPolicy
    ) {
        final String requestBody;
        try {
            requestBody = json.writeValueAsString(body);
        } catch (JsonProcessingException exception) {
            return CompletableFuture.failedFuture(exception);
        }
        HttpRequest request = apiRequest(path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
        return send(request, responseType, retryPolicy);
    }

    private HttpRequest.Builder apiRequest(String path) {
        return baseRequest(path)
                .header("x-api-key", options.apiKey())
                .header("x-api-secret", options.apiSecret());
    }

    private HttpRequest.Builder baseRequest(String path) {
        String base = options.apiUrl().toString().replaceAll("/+$", "");
        return HttpRequest.newBuilder(URI.create(base + path))
                .timeout(options.timeout())
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT);
    }

    private <T> CompletableFuture<T> send(HttpRequest request, TypeReference<T> responseType) {
        return send(request, responseType, RetryPolicy.SAFE);
    }

    private <T> CompletableFuture<T> send(
            HttpRequest request,
            TypeReference<T> responseType,
            RetryPolicy retryPolicy
    ) {
        return send(request, responseType, retryPolicy, 1);
    }

    private <T> CompletableFuture<T> send(
            HttpRequest request,
            TypeReference<T> responseType,
            RetryPolicy retryPolicy,
            int attempt
    ) {
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .handle((response, failure) -> {
                    if (failure != null) {
                        Throwable cause = unwrap(failure);
                        if (retryPolicy == RetryPolicy.SAFE
                                && attempt < options.maxAttempts()) {
                            return after(backoffMillis(attempt))
                                    .thenCompose(ignored ->
                                            send(request, responseType, retryPolicy, attempt + 1));
                        }
                        return CompletableFuture.<T>failedFuture(
                                new PortalUnreachableException(
                                        request.method() + " " + request.uri() + ": no response",
                                        cause
                                ));
                    }

                    RateLimit rateLimit = rateLimit(response);
                    if (rateLimit != null) {
                        latestRateLimit.set(rateLimit);
                    }

                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        try {
                            return CompletableFuture.completedFuture(
                                    json.readValue(response.body(), responseType)
                            );
                        } catch (IOException exception) {
                            return CompletableFuture.<T>failedFuture(
                                    new IllegalStateException("Portal returned invalid JSON", exception)
                            );
                        }
                    }

                    PortalException exception = portalException(request, response, rateLimit);
                    if (retryPolicy == RetryPolicy.SAFE
                            && exception.retryable()
                            && attempt < options.maxAttempts()) {
                        long delayMillis = exception.retryAfterSeconds() == null
                                ? backoffMillis(attempt)
                                : exception.retryAfterSeconds() * 1_000L;
                        return after(delayMillis)
                                .thenCompose(ignored ->
                                        send(request, responseType, retryPolicy, attempt + 1));
                    }
                    return CompletableFuture.<T>failedFuture(exception);
                })
                .thenCompose(Function.identity());
    }

    private PortalException portalException(
            HttpRequest request,
            HttpResponse<String> response,
            RateLimit rateLimit
    ) {
        String message = "HTTP " + response.statusCode();
        String code = null;
        try {
            JsonNode body = json.readTree(response.body());
            JsonNode messageNode = body.get("message");
            if (messageNode != null) {
                if (messageNode.isArray()) {
                    message = messageNode.toString();
                } else if (messageNode.isTextual()) {
                    message = messageNode.asText();
                }
                Matcher matcher = ERROR_CODE.matcher(message);
                if (matcher.find()) {
                    code = matcher.group(1);
                }
            }
        } catch (JsonProcessingException ignored) {
            // Preserve the raw body below for diagnostics.
        }
        Long retryAfter = response.headers().firstValue("Retry-After")
                .map(value -> {
                    try {
                        return Long.parseLong(value);
                    } catch (NumberFormatException ignored) {
                        return null;
                    }
                })
                .orElse(null);
        return new PortalException(
                response.statusCode(),
                code,
                request.method() + " " + request.uri().getPath() + " -> "
                        + response.statusCode() + ": " + message,
                response.body(),
                retryAfter,
                rateLimit
        );
    }

    private static String form(Map<String, String> fields) {
        return fields.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .reduce((left, right) -> left + "&" + right)
                .orElse("");
    }

    private static String feedPath(String path, FeedStart from, int take) {
        Map<String, String> query = new LinkedHashMap<>();
        if (from.since() != null) {
            query.put("since", Long.toString(from.since()));
        } else {
            query.put("cursor", from.cursor());
        }
        query.put("take", Integer.toString(take));
        return withQuery(path, query, List.of());
    }

    private static String withQuery(
            String path,
            Map<String, String> query,
            List<String> omittedKeys
    ) {
        String values = query.entrySet().stream()
                .filter(entry -> !omittedKeys.contains(entry.getKey()))
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .reduce((left, right) -> left + "&" + right)
                .orElse("");
        return values.isEmpty() ? path : path + "?" + values;
    }

    private static String pathSegment(String value) {
        return encode(value).replace("+", "%20");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static RateLimit rateLimit(HttpResponse<?> response) {
        try {
            Optional<String> limit = response.headers().firstValue("X-RateLimit-Limit");
            Optional<String> remaining = response.headers().firstValue("X-RateLimit-Remaining");
            Optional<String> reset = response.headers().firstValue("X-RateLimit-Reset");
            if (limit.isEmpty() || remaining.isEmpty() || reset.isEmpty()) {
                return null;
            }
            return new RateLimit(
                    Long.parseLong(limit.get()),
                    Long.parseLong(remaining.get()),
                    Long.parseLong(reset.get())
            );
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static CompletableFuture<Void> after(long millis) {
        return CompletableFuture.runAsync(
                () -> {
                },
                CompletableFuture.delayedExecutor(millis, TimeUnit.MILLISECONDS)
        );
    }

    private static long backoffMillis(int attempt) {
        long cap = Math.min(5_000L, 250L << Math.min(attempt - 1, 4));
        return ThreadLocalRandom.current().nextLong(Math.max(1L, cap / 2L), cap + 1L);
    }

    private static Throwable unwrap(Throwable failure) {
        if (failure instanceof CompletionException && failure.getCause() != null) {
            return failure.getCause();
        }
        return failure;
    }

    @Override
    public void close() {
        httpClient.close();
    }
}
