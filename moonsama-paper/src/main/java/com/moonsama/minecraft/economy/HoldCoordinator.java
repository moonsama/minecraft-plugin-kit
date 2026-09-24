package com.moonsama.minecraft.economy;

import com.moonsama.minecraft.MoonsamaConfig;
import com.moonsama.minecraft.api.AssetHold;
import com.moonsama.minecraft.api.AssetHoldRequest;
import com.moonsama.minecraft.store.JournalRecords.Hold;
import com.moonsama.minecraft.store.LinkStore;
import com.moonsama.minecraft.store.LinkedPlayer;
import com.moonsama.minecraft.store.SqlitePortalStore;
import com.moonsama.portal.PortalClient;
import com.moonsama.portal.PortalException;
import com.moonsama.portal.PortalModels;
import com.moonsama.portal.PortalUnreachableException;
import org.bukkit.plugin.Plugin;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class HoldCoordinator {
    private final PortalClient portal;
    private final LinkStore links;
    private final SqlitePortalStore store;
    private final Executor databaseExecutor;
    private final ScheduledExecutorService scheduler;
    private final MoonsamaConfig config;
    private final Consumer<AssetHold> eventSink;
    private final Consumer<Throwable> errorSink;
    private final Map<UUID, CompletableFuture<AssetHold>> inFlight =
            new java.util.HashMap<>();
    private final String installationId;

    public HoldCoordinator(
            PortalClient portal,
            LinkStore links,
            SqlitePortalStore store,
            Executor databaseExecutor,
            ScheduledExecutorService scheduler,
            MoonsamaConfig config,
            Consumer<AssetHold> eventSink,
            Consumer<Throwable> errorSink
    ) {
        this.portal = portal;
        this.links = links;
        this.store = store;
        this.databaseExecutor = databaseExecutor;
        this.scheduler = scheduler;
        this.config = config;
        this.eventSink = eventSink;
        this.errorSink = errorSink;
        this.installationId = store.installationId();
    }

    public void start() {
        scheduler.scheduleWithFixedDelay(
                this::recoverHolds,
                2,
                5,
                TimeUnit.SECONDS
        );
    }

    public CompletableFuture<AssetHold> place(
            Plugin owner,
            AssetHoldRequest request
    ) {
        if (!config.holdsEnabled()) {
            return disabled();
        }
        String ownerName = owner.getName();
        return database(() -> {
            validate(request);
            LinkedPlayer linked = links.findLink(request.mojangUuid())
                    .orElseThrow(() -> new IllegalStateException(
                            "Player " + request.mojangUuid() + " is not linked"
                    ));
            Instant now = Instant.now();
            String portalReference = portalReference(ownerName, request.businessKey());
            String tokenId = normalizeTokenId(request.tokenId());
            String hash = sha256(
                    linked.playerId() + "\0"
                            + request.collection() + "\0"
                            + nullToEmpty(tokenId) + "\0"
                            + request.ttlSeconds() + "\0"
                            + nullToEmpty(request.reference())
            );
            Hold proposed = new Hold(
                    UUID.randomUUID(),
                    ownerName,
                    request.businessKey(),
                    hash,
                    request.mojangUuid(),
                    linked.playerId(),
                    request.collection(),
                    tokenId,
                    request.ttlSeconds(),
                    request.reference(),
                    portalReference,
                    null,
                    AssetHold.State.REQUESTED,
                    null,
                    null,
                    now,
                    now
            );
            return store.createOrGetHold(proposed);
        }).thenCompose(hold -> {
            if (hold.state() != AssetHold.State.REQUESTED
                    && hold.state() != AssetHold.State.PLACING
                    && hold.state() != AssetHold.State.PLACEMENT_UNKNOWN) {
                return CompletableFuture.completedFuture(toApi(hold));
            }
            return process(hold.operationId());
        });
    }

    public CompletableFuture<AssetHold> renew(
            Plugin owner,
            String businessKey,
            int ttlSeconds
    ) {
        if (!config.holdsEnabled()) {
            return disabled();
        }
        if (ttlSeconds < 60 || ttlSeconds > 604_800) {
            throw new IllegalArgumentException("ttlSeconds must be between 60 and 604800");
        }
        return database(() -> {
            Hold hold = store.findHold(owner.getName(), businessKey)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown hold"));
            if (hold.holdId() == null || terminal(hold.state())) {
                throw new IllegalStateException("Hold is not active");
            }
            store.updateHoldTtl(hold.operationId(), ttlSeconds);
            store.updateHold(
                    hold.operationId(),
                    AssetHold.State.RENEWING,
                    null,
                    null,
                    null,
                    null,
                    null
            );
            return store.findHold(hold.operationId()).orElseThrow();
        }).thenCompose(hold -> portal.renewHold(hold.holdId(), ttlSeconds)
                .thenCompose(result -> savePortalHold(hold, result, AssetHold.State.ACTIVE))
                .exceptionallyCompose(failure ->
                        handleUncertainOrRejected(hold, unwrap(failure), AssetHold.State.RENEWING)
                ));
    }

    public CompletableFuture<AssetHold> release(Plugin owner, String businessKey) {
        return database(() -> {
            Hold hold = store.findHold(owner.getName(), businessKey)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown hold"));
            if (hold.state() == AssetHold.State.RELEASED) {
                return hold;
            }
            if (hold.holdId() == null) {
                throw new IllegalStateException("Hold placement was not confirmed");
            }
            store.updateHold(
                    hold.operationId(),
                    AssetHold.State.RELEASING,
                    null,
                    null,
                    null,
                    null,
                    null
            );
            return store.findHold(hold.operationId()).orElseThrow();
        }).thenCompose(this::releasePortalHold);
    }

    public CompletableFuture<Optional<AssetHold>> find(
            Plugin owner,
            String businessKey
    ) {
        return database(() -> store.findHold(owner.getName(), businessKey)
                .map(this::toApi));
    }

    private CompletableFuture<AssetHold> process(UUID operationId) {
        synchronized (inFlight) {
            CompletableFuture<AssetHold> existing = inFlight.get(operationId);
            if (existing != null) {
                return existing;
            }
            CompletableFuture<AssetHold> created = database(() ->
                    store.findHold(operationId).orElseThrow()
            ).thenCompose(hold -> {
                if (hold.state() == AssetHold.State.PLACING
                        || hold.state() == AssetHold.State.PLACEMENT_UNKNOWN) {
                    return recoverPlacement(hold);
                }
                return placePortalHold(hold);
            });
            inFlight.put(operationId, created);
            created.whenComplete((ignored, failure) -> {
                synchronized (inFlight) {
                    inFlight.remove(operationId);
                }
            });
            return created;
        }
    }

    private CompletableFuture<AssetHold> placePortalHold(Hold hold) {
        return database(() -> {
            store.updateHold(
                    hold.operationId(),
                    AssetHold.State.PLACING,
                    null,
                    null,
                    null,
                    null,
                    null
            );
            return store.findHold(hold.operationId()).orElseThrow();
        }).thenCompose(placing -> portal.placeHold(new PortalModels.HoldRequest(
                        placing.playerId(),
                        placing.collection(),
                        placing.tokenId(),
                        placing.ttlSeconds(),
                        placing.portalReference()
                ))
                .thenCompose(result ->
                        savePortalHold(placing, result, AssetHold.State.ACTIVE))
                .exceptionallyCompose(failure ->
                        handleUncertainOrRejected(
                                placing,
                                unwrap(failure),
                                AssetHold.State.PLACEMENT_UNKNOWN
                        )
                ));
    }

    private CompletableFuture<AssetHold> recoverPlacement(Hold hold) {
        if (hold.playerId() == null) {
            return database(() -> updateAndRead(
                    hold,
                    AssetHold.State.LOST,
                    null,
                    null,
                    null,
                    "PLAYER_ERASED",
                    "Player identity was erased before placement could be recovered"
            ));
        }
        return portal.holds(hold.playerId(), 100)
                .thenCompose(activeHolds -> {
                    Optional<PortalModels.Hold> match = activeHolds.stream()
                            .filter(value ->
                                    hold.portalReference().equals(value.reference()))
                            .findFirst();
                    if (match.isPresent()) {
                        return savePortalHold(hold, match.get(), AssetHold.State.ACTIVE);
                    }
                    return placePortalHold(hold);
                })
                .exceptionallyCompose(failure ->
                        handleUncertainOrRejected(
                                hold,
                                unwrap(failure),
                                AssetHold.State.PLACEMENT_UNKNOWN
                        )
                );
    }

    private CompletableFuture<AssetHold> releasePortalHold(Hold hold) {
        return portal.releaseHold(hold.holdId())
                .thenCompose(result ->
                        savePortalHold(hold, result, AssetHold.State.RELEASED))
                .exceptionallyCompose(failure -> {
                    Throwable cause = unwrap(failure);
                    if (cause instanceof PortalException portalFailure
                            && portalFailure.status() == 404) {
                        return database(() -> updateAndRead(
                                hold,
                                AssetHold.State.RELEASED,
                                null,
                                hold.expiresAt(),
                                404,
                                portalFailure.code(),
                                "Hold was already absent"
                        ));
                    }
                    return handleUncertainOrRejected(
                            hold,
                            cause,
                            AssetHold.State.RELEASING
                    );
                });
    }

    private CompletableFuture<AssetHold> savePortalHold(
            Hold hold,
            PortalModels.Hold portalHold,
            AssetHold.State state
    ) {
        return database(() -> {
            AssetHold value = updateAndRead(
                    hold,
                    state,
                    portalHold.holdId(),
                    parseInstant(portalHold.expiresAt()),
                    null,
                    null,
                    null
            );
            eventSink.accept(value);
            return value;
        });
    }

    private CompletableFuture<AssetHold> handleUncertainOrRejected(
            Hold hold,
            Throwable failure,
            AssetHold.State uncertainState
    ) {
        boolean uncertain = failure instanceof PortalUnreachableException
                || failure instanceof PortalException portalFailure
                && portalFailure.status() >= 500;
        AssetHold.State state = uncertain
                ? uncertainState
                : AssetHold.State.REJECTED;
        Integer status = failure instanceof PortalException portalFailure
                ? portalFailure.status()
                : null;
        String code = failure instanceof PortalException portalFailure
                ? portalFailure.code()
                : "PORTAL_UNREACHABLE";
        return database(() -> {
            AssetHold result = updateAndRead(
                    hold,
                    state,
                    null,
                    null,
                    status,
                    code,
                    failure.getMessage()
            );
            if (!uncertain) {
                eventSink.accept(result);
            }
            return result;
        });
    }

    private AssetHold updateAndRead(
            Hold hold,
            AssetHold.State state,
            String holdId,
            Instant expiresAt,
            Integer status,
            String code,
            String message
    ) {
        store.updateHold(
                hold.operationId(),
                state,
                holdId,
                expiresAt,
                status,
                code,
                message
        );
        return toApi(store.findHold(hold.operationId()).orElseThrow());
    }

    private void recoverHolds() {
        database(() -> store.recoverableHolds(Instant.now(), 25))
                .thenAccept(holds -> holds.forEach(hold -> {
                    if (hold.state() == AssetHold.State.ACTIVE) {
                        if (hold.expiresAt() != null
                                && !hold.expiresAt().isAfter(Instant.now())) {
                            database(() -> {
                                AssetHold expired = updateAndRead(
                                        hold,
                                        AssetHold.State.EXPIRED,
                                        null,
                                        hold.expiresAt(),
                                        null,
                                        null,
                                        null
                                );
                                eventSink.accept(expired);
                                return null;
                            });
                        }
                    } else if (hold.state() == AssetHold.State.RELEASING) {
                        releasePortalHold(hold).exceptionally(this::ignoreFailure);
                    } else if (hold.state() == AssetHold.State.RENEWING) {
                        portal.renewHold(hold.holdId(), hold.ttlSeconds())
                                .thenCompose(result -> savePortalHold(
                                        hold,
                                        result,
                                        AssetHold.State.ACTIVE
                                ))
                                .exceptionallyCompose(failure ->
                                        handleUncertainOrRejected(
                                                hold,
                                                unwrap(failure),
                                                AssetHold.State.RENEWING
                                        )
                                )
                                .exceptionally(this::ignoreFailure);
                    } else {
                        process(hold.operationId()).exceptionally(this::ignoreFailure);
                    }
                }))
                .exceptionally(this::ignoreFailure);
    }

    private AssetHold toApi(Hold hold) {
        return new AssetHold(
                hold.operationId(),
                hold.ownerPlugin(),
                hold.businessKey(),
                hold.mojangUuid(),
                hold.collection(),
                hold.tokenId(),
                hold.ttlSeconds(),
                hold.builderReference(),
                hold.holdId(),
                hold.state(),
                hold.expiresAt(),
                hold.failure()
        );
    }

    private String portalReference(String owner, String businessKey) {
        return "mc:" + installationId.substring(0, 8)
                + ":hold:" + owner.toLowerCase(Locale.ROOT)
                + ":" + sha256(businessKey).substring(0, 24);
    }

    private static CompletableFuture<AssetHold> disabled() {
        return CompletableFuture.failedFuture(new IllegalStateException(
                "Portal hold operations are disabled by the server operator"
        ));
    }

    private static void validate(AssetHoldRequest request) {
        if (request.businessKey() == null || request.businessKey().isBlank()) {
            throw new IllegalArgumentException("businessKey is required");
        }
        if (request.businessKey().length() > 128) {
            throw new IllegalArgumentException("businessKey must be at most 128 characters");
        }
        if (request.mojangUuid() == null) {
            throw new IllegalArgumentException("mojangUuid is required");
        }
        if (request.collection() == null || request.collection().isBlank()) {
            throw new IllegalArgumentException("collection is required");
        }
        if (request.reference() != null && request.reference().length() > 255) {
            throw new IllegalArgumentException("reference must be at most 255 characters");
        }
        if (request.ttlSeconds() < 60 || request.ttlSeconds() > 604_800) {
            throw new IllegalArgumentException("ttlSeconds must be between 60 and 604800");
        }
    }

    private static String normalizeTokenId(String tokenId) {
        if (tokenId == null) {
            return null;
        }
        try {
            BigInteger value = new BigInteger(tokenId);
            if (value.signum() < 0) {
                throw new IllegalArgumentException("tokenId must be non-negative");
            }
            return value.toString();
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "tokenId must be a canonical decimal integer",
                    exception
            );
        }
    }

    private static boolean terminal(AssetHold.State state) {
        return switch (state) {
            case RELEASED, LOST, EXPIRED, REJECTED -> true;
            default -> false;
        };
    }

    private static Instant parseInstant(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            return Instant.ofEpochMilli(Long.parseLong(value));
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private <T> CompletableFuture<T> database(Supplier<T> action) {
        return CompletableFuture.supplyAsync(action, databaseExecutor);
    }

    private <T> T ignoreFailure(Throwable failure) {
        errorSink.accept(unwrap(failure));
        return null;
    }

    private static Throwable unwrap(Throwable failure) {
        if (failure instanceof CompletionException && failure.getCause() != null) {
            return failure.getCause();
        }
        return failure;
    }
}
