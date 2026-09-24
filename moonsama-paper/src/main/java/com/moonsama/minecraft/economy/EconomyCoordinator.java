package com.moonsama.minecraft.economy;

import com.moonsama.minecraft.MoonsamaConfig;
import com.moonsama.minecraft.api.EconomyLine;
import com.moonsama.minecraft.api.EconomyOperation;
import com.moonsama.minecraft.api.EconomyRefundRequest;
import com.moonsama.minecraft.api.EconomyRequest;
import com.moonsama.minecraft.store.JournalRecords.ResolvedLine;
import com.moonsama.minecraft.store.JournalRecords.Write;
import com.moonsama.minecraft.store.LinkStore;
import com.moonsama.minecraft.store.LinkedPlayer;
import com.moonsama.minecraft.store.SqlitePortalStore;
import com.moonsama.portal.PortalClient;
import com.moonsama.portal.PortalException;
import com.moonsama.portal.PortalModels;
import com.moonsama.portal.PortalUnreachableException;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class EconomyCoordinator {
    private static final Set<String> REAUTH_CODES = Set.of(
            "PLAYER_NOT_CONSENTED",
            "CONSENT_REQUIRED",
            "PLAYER_UNKNOWN"
    );

    private final PortalClient portal;
    private final LinkStore links;
    private final SqlitePortalStore store;
    private final Executor databaseExecutor;
    private final ScheduledExecutorService scheduler;
    private final MoonsamaConfig config;
    private final Consumer<EconomyOperation> eventSink;
    private final Consumer<Throwable> errorSink;
    private final Map<UUID, CompletableFuture<EconomyOperation>> inFlight =
            new java.util.HashMap<>();
    private final String installationId;

    public EconomyCoordinator(
            PortalClient portal,
            LinkStore links,
            SqlitePortalStore store,
            Executor databaseExecutor,
            ScheduledExecutorService scheduler,
            MoonsamaConfig config,
            Consumer<EconomyOperation> eventSink,
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
                this::recoverDueWrites,
                1,
                2,
                TimeUnit.SECONDS
        );
        scheduler.scheduleWithFixedDelay(
                this::deliverOutbox,
                1,
                1,
                TimeUnit.SECONDS
        );
    }

    public CompletableFuture<EconomyOperation> spend(
            Plugin owner,
            EconomyRequest request
    ) {
        if (!config.spendEnabled()) {
            return disabled("spend");
        }
        return submit(
                owner,
                EconomyOperation.Kind.SPEND,
                request.businessKey(),
                request.items(),
                request.description(),
                request.reference(),
                null
        );
    }

    public CompletableFuture<EconomyOperation> reward(
            Plugin owner,
            EconomyRequest request
    ) {
        if (!config.rewardEnabled()) {
            return disabled("reward");
        }
        return submit(
                owner,
                EconomyOperation.Kind.REWARD,
                request.businessKey(),
                request.items(),
                request.description(),
                request.reference(),
                null
        );
    }

    public CompletableFuture<EconomyOperation> refund(
            Plugin owner,
            EconomyRefundRequest request
    ) {
        if (!config.refundEnabled()) {
            return disabled("refund");
        }
        String ownerName = ownerName(owner);
        return database(() -> {
            validateBusinessKey(request.businessKey());
            validateBusinessKey(request.originalBusinessKey());
            Write original = store.findWrite(ownerName, request.originalBusinessKey())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Original operation does not exist"
                    ));
            if (original.state() != EconomyOperation.State.SUCCEEDED) {
                throw new IllegalStateException(
                        "Original operation must have succeeded before it can be refunded"
                );
            }
            List<ResolvedLine> lines = request.items() == null
                    ? List.of()
                    : resolveLines(request.items());
            return prepareWrite(
                    ownerName,
                    EconomyOperation.Kind.REFUND,
                    request.businessKey(),
                    lines,
                    request.description(),
                    request.reference(),
                    original.portalKey()
            );
        }).thenCompose(this::startOrReturn);
    }

    public CompletableFuture<Optional<EconomyOperation>> operation(
            Plugin owner,
            String businessKey
    ) {
        return database(() -> store.findWrite(ownerName(owner), businessKey)
                .map(this::toApi));
    }

    CompletableFuture<EconomyOperation> reconcile(UUID operationId) {
        return process(operationId);
    }

    private CompletableFuture<EconomyOperation> submit(
            Plugin owner,
            EconomyOperation.Kind kind,
            String businessKey,
            List<EconomyLine> items,
            String description,
            String reference,
            String originalPortalKey
    ) {
        String ownerName = ownerName(owner);
        return database(() -> {
            validateBusinessKey(businessKey);
            List<ResolvedLine> lines = resolveLines(items);
            return prepareWrite(
                    ownerName,
                    kind,
                    businessKey,
                    lines,
                    description,
                    reference,
                    originalPortalKey
            );
        }).thenCompose(this::startOrReturn);
    }

    private Write prepareWrite(
            String owner,
            EconomyOperation.Kind kind,
            String businessKey,
            List<ResolvedLine> lines,
            String description,
            String reference,
            String originalPortalKey
    ) {
        validateLength(description, "description", 64);
        validateLength(reference, "reference", 255);
        Instant now = Instant.now();
        String portalKey = portalKey(owner, businessKey);
        String payloadHash = payloadHash(
                kind,
                lines,
                originalPortalKey
        );
        Write proposed = new Write(
                UUID.randomUUID(),
                owner,
                businessKey,
                portalKey,
                payloadHash,
                kind,
                EconomyOperation.State.QUEUED,
                lines,
                description,
                reference,
                originalPortalKey,
                0,
                now,
                null,
                null,
                now,
                now
        );
        return store.createOrGetWrite(proposed);
    }

    private CompletableFuture<EconomyOperation> startOrReturn(Write write) {
        if (terminal(write.state())) {
            return CompletableFuture.completedFuture(toApi(write));
        }
        return process(write.operationId());
    }

    private CompletableFuture<EconomyOperation> process(UUID operationId) {
        synchronized (inFlight) {
            CompletableFuture<EconomyOperation> existing = inFlight.get(operationId);
            if (existing != null) {
                return existing;
            }
            CompletableFuture<EconomyOperation> created = database(() ->
                    store.findWrite(operationId).orElseThrow()
            ).thenCompose(write -> {
                if (write.state() == EconomyOperation.State.RECOVERING
                        || write.state() == EconomyOperation.State.SENDING) {
                    return recover(write);
                }
                return send(write);
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

    private CompletableFuture<EconomyOperation> send(Write write) {
        return database(() -> {
            store.markWriteSending(write.operationId());
            return store.findWrite(write.operationId()).orElseThrow();
        }).thenCompose(sending -> portalWrite(sending)
                .thenCompose(receipt -> database(() -> {
                    store.completeWrite(sending.operationId(), receipt);
                    return toApi(store.findWrite(sending.operationId()).orElseThrow());
                }))
                .handle((result, failure) -> {
                    if (failure == null) {
                        return CompletableFuture.completedFuture(result);
                    }
                    return handleWriteFailure(sending, unwrap(failure));
                })
                .thenCompose(value -> value));
    }

    private CompletableFuture<EconomyOperation> recover(Write write) {
        return portal.receipt(write.portalKey())
                .thenCompose(receipt -> {
                    if (receipt.isPresent()) {
                        return database(() -> {
                            store.completeWrite(write.operationId(), receipt.get());
                            return toApi(store.findWrite(write.operationId()).orElseThrow());
                        });
                    }
                    return database(() -> {
                        store.markWriteTerminal(
                                write.operationId(),
                                EconomyOperation.State.QUEUED,
                                null,
                                null,
                                null
                        );
                        return store.findWrite(write.operationId()).orElseThrow();
                    }).thenCompose(this::send);
                })
                .handle((result, failure) -> {
                    if (failure == null) {
                        return CompletableFuture.completedFuture(result);
                    }
                    return markRecovering(write, unwrap(failure));
                })
                .thenCompose(value -> value);
    }

    private CompletableFuture<PortalModels.Receipt> portalWrite(Write write) {
        List<PortalModels.Line> lines = write.lines().stream()
                .map(line -> new PortalModels.Line(
                        line.playerId(),
                        line.collection(),
                        line.tokenId(),
                        line.amount()
                ))
                .toList();
        if (write.kind() == EconomyOperation.Kind.REFUND) {
            return portal.refund(
                    write.portalKey(),
                    new PortalModels.RefundRequest(
                            write.originalPortalKey(),
                            lines.isEmpty() ? null : lines,
                            write.description(),
                            write.reference()
                    )
            );
        }
        PortalModels.WriteRequest request = new PortalModels.WriteRequest(
                lines,
                write.description(),
                write.reference()
        );
        return write.kind() == EconomyOperation.Kind.SPEND
                ? portal.spend(write.portalKey(), request)
                : portal.reward(write.portalKey(), request);
    }

    private CompletableFuture<EconomyOperation> handleWriteFailure(
            Write write,
            Throwable failure
    ) {
        if (failure instanceof PortalUnreachableException) {
            return markRecovering(write, failure);
        }
        if (failure instanceof PortalException portalFailure) {
            if (portalFailure.retryable()
                    && (portalFailure.status() != 429
                    || "RATE_LIMITED".equals(portalFailure.code()))) {
                return markRecovering(write, portalFailure);
            }
            EconomyOperation.State state;
            if (portalFailure.status() == 422) {
                state = EconomyOperation.State.QUARANTINED_BUG;
            } else if (REAUTH_CODES.contains(portalFailure.code())) {
                state = EconomyOperation.State.NEEDS_REAUTH;
            } else {
                state = EconomyOperation.State.REJECTED;
            }
            return database(() -> {
                store.markWriteTerminal(
                        write.operationId(),
                        state,
                        portalFailure.status(),
                        portalFailure.code(),
                        portalFailure.getMessage()
                );
                EconomyOperation operation = toApi(
                        store.findWrite(write.operationId()).orElseThrow()
                );
                eventSink.accept(operation);
                return operation;
            });
        }
        return database(() -> {
            store.markWriteTerminal(
                    write.operationId(),
                    EconomyOperation.State.QUARANTINED_BUG,
                    null,
                    "CORE_FAILURE",
                    failure.getMessage()
            );
            EconomyOperation operation = toApi(
                    store.findWrite(write.operationId()).orElseThrow()
            );
            eventSink.accept(operation);
            return operation;
        });
    }

    private CompletableFuture<EconomyOperation> markRecovering(
            Write write,
            Throwable failure
    ) {
        Integer status = failure instanceof PortalException portalFailure
                ? portalFailure.status()
                : null;
        String code = failure instanceof PortalException portalFailure
                ? portalFailure.code()
                : "PORTAL_UNREACHABLE";
        Duration delay = Duration.ofSeconds(5);
        if (failure instanceof PortalException portalFailure
                && portalFailure.retryAfterSeconds() != null) {
            delay = Duration.ofSeconds(Math.max(1, portalFailure.retryAfterSeconds()));
        }
        Instant retryAt = Instant.now().plus(delay);
        return database(() -> {
            store.markWriteRecovering(
                    write.operationId(),
                    retryAt,
                    status,
                    code,
                    failure.getMessage()
            );
            return toApi(store.findWrite(write.operationId()).orElseThrow());
        });
    }

    private void recoverDueWrites() {
        database(() -> store.dueWrites(Instant.now(), 25))
                .thenAccept(writes -> writes.forEach(write ->
                        process(write.operationId()).exceptionally(failure -> {
                            errorSink.accept(unwrap(failure));
                            return null;
                        })
                ))
                .exceptionally(failure -> {
                    errorSink.accept(unwrap(failure));
                    return null;
                });
    }

    private void deliverOutbox() {
        database(() -> store.pendingEvents(50))
                .thenAccept(events -> events.forEach(event -> {
                    if (!"WRITE_SUCCEEDED".equals(event.type())) {
                        return;
                    }
                    try {
                        UUID operationId = UUID.fromString(event.aggregateId());
                        database(() -> store.findWrite(operationId).map(this::toApi))
                                .thenAccept(operation -> operation.ifPresent(value -> {
                                    eventSink.accept(value);
                                    database(() -> {
                                        store.markEventDelivered(event.eventId());
                                        return null;
                                    });
                                }));
                    } catch (IllegalArgumentException ignored) {
                        database(() -> {
                            store.markEventDelivered(event.eventId());
                            return null;
                        });
                    }
                }))
                .exceptionally(failure -> {
                    errorSink.accept(unwrap(failure));
                    return null;
                });
    }

    private List<ResolvedLine> resolveLines(List<EconomyLine> requested) {
        if (requested == null || requested.isEmpty() || requested.size() > 20) {
            throw new IllegalArgumentException("Economy request must contain 1 to 20 items");
        }
        List<ResolvedLine> resolved = new ArrayList<>();
        for (int index = 0; index < requested.size(); index++) {
            EconomyLine line = requested.get(index);
            if (line.mojangUuid() == null) {
                throw new IllegalArgumentException("Player UUID is required");
            }
            LinkedPlayer linked = links.findLink(line.mojangUuid())
                    .orElseThrow(() -> new IllegalStateException(
                            "Player " + line.mojangUuid() + " is not linked"
                    ));
            String collection = required(line.collection(), "collection");
            String tokenId = normalizeTokenId(line.tokenId());
            String amount = normalizeAmount(line.amount());
            resolved.add(new ResolvedLine(
                    index,
                    line.mojangUuid(),
                    linked.playerId(),
                    collection,
                    tokenId,
                    amount
            ));
        }
        return resolved;
    }

    private EconomyOperation toApi(Write write) {
        return new EconomyOperation(
                write.operationId(),
                write.ownerPlugin(),
                write.businessKey(),
                write.portalKey(),
                write.kind(),
                write.state(),
                write.receipt(),
                write.failure(),
                write.createdAt(),
                write.updatedAt()
        );
    }

    private String portalKey(String owner, String businessKey) {
        return "mc:" + installationId.substring(0, 8)
                + ":" + owner.toLowerCase(Locale.ROOT)
                + ":" + sha256(businessKey).substring(0, 32);
    }

    private static String payloadHash(
            EconomyOperation.Kind kind,
            List<ResolvedLine> lines,
            String originalPortalKey
    ) {
        StringBuilder canonical = new StringBuilder(kind.name())
                .append('\n').append(nullToEmpty(originalPortalKey));
        for (ResolvedLine line : lines) {
            canonical.append('\n')
                    .append(line.playerId()).append('\0')
                    .append(line.collection()).append('\0')
                    .append(line.tokenId()).append('\0')
                    .append(line.amount());
        }
        return sha256(canonical.toString());
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    static String normalizeAmount(String amount) {
        String input = required(amount, "amount");
        if (!input.matches("[0-9]{1,60}(\\.[0-9]{1,18})?")) {
            throw new IllegalArgumentException(
                    "amount must be a positive decimal token amount "
                            + "with at most 60 integer and 18 fractional digits"
            );
        }
        try {
            BigDecimal value = new BigDecimal(input);
            if (value.signum() <= 0) {
                throw new IllegalArgumentException("amount must be positive");
            }
            return value.stripTrailingZeros().toPlainString();
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "amount must be a positive decimal token amount",
                    exception
            );
        }
    }

    private static String normalizeTokenId(String tokenId) {
        try {
            BigInteger value = new BigInteger(required(tokenId, "tokenId"));
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

    private static void validateBusinessKey(String businessKey) {
        String value = required(businessKey, "businessKey");
        if (value.length() > 128) {
            throw new IllegalArgumentException("businessKey must be at most 128 characters");
        }
    }

    private static void validateLength(String value, String name, int maximum) {
        if (value != null && value.length() > maximum) {
            throw new IllegalArgumentException(
                    name + " must be at most " + maximum + " characters"
            );
        }
    }

    private static String ownerName(Plugin owner) {
        return required(owner.getName(), "plugin name");
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static CompletableFuture<EconomyOperation> disabled(String operation) {
        return CompletableFuture.failedFuture(new IllegalStateException(
                "Portal " + operation + " operations are disabled by the server operator"
        ));
    }

    private static boolean terminal(EconomyOperation.State state) {
        return switch (state) {
            case SUCCEEDED, REJECTED, NEEDS_REAUTH, QUARANTINED_BUG, CANCELLED -> true;
            default -> false;
        };
    }

    private <T> CompletableFuture<T> database(Supplier<T> action) {
        return CompletableFuture.supplyAsync(action, databaseExecutor);
    }

    private static Throwable unwrap(Throwable failure) {
        if ((failure instanceof CompletionException
                || failure instanceof java.util.concurrent.ExecutionException)
                && failure.getCause() != null) {
            return failure.getCause();
        }
        return failure;
    }
}
