package com.moonsama.minecraft.economy;

import com.moonsama.minecraft.MoonsamaConfig;
import com.moonsama.minecraft.api.AssetHolding;
import com.moonsama.minecraft.api.HoldingsSnapshot;
import com.moonsama.minecraft.store.JournalRecords.CachedPlayer;
import com.moonsama.minecraft.store.JournalRecords.FeedCheckpoint;
import com.moonsama.minecraft.store.JournalRecords.Invalidation;
import com.moonsama.minecraft.store.LinkStore;
import com.moonsama.minecraft.store.LinkedPlayer;
import com.moonsama.minecraft.store.SqlitePortalStore;
import com.moonsama.portal.PortalClient;
import com.moonsama.portal.PortalException;
import com.moonsama.portal.PortalModels;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class PortalSyncService {
    private final PortalClient portal;
    private final LinkStore links;
    private final SqlitePortalStore store;
    private final Executor databaseExecutor;
    private final ScheduledExecutorService scheduler;
    private final MoonsamaConfig config;
    private final Consumer<UUID> holdingsSink;
    private final Consumer<UUID> erasureSink;
    private final Consumer<Throwable> errorSink;
    private final Map<String, CompletableFuture<List<AssetHolding>>> refreshes =
            new HashMap<>();
    private final AtomicBoolean changesRunning = new AtomicBoolean();
    private final AtomicBoolean erasuresRunning = new AtomicBoolean();
    private final AtomicBoolean refreshRunning = new AtomicBoolean();
    private final AtomicBoolean receiptsRunning = new AtomicBoolean();

    public PortalSyncService(
            PortalClient portal,
            LinkStore links,
            SqlitePortalStore store,
            Executor databaseExecutor,
            ScheduledExecutorService scheduler,
            MoonsamaConfig config,
            Consumer<UUID> holdingsSink,
            Consumer<UUID> erasureSink,
            Consumer<Throwable> errorSink
    ) {
        this.portal = portal;
        this.links = links;
        this.store = store;
        this.databaseExecutor = databaseExecutor;
        this.scheduler = scheduler;
        this.config = config;
        this.holdingsSink = holdingsSink;
        this.erasureSink = erasureSink;
        this.errorSink = errorSink;
    }

    public void start() {
        scheduler.scheduleWithFixedDelay(
                this::pollChanges,
                1,
                Math.max(1, config.changesInterval().toSeconds()),
                TimeUnit.SECONDS
        );
        scheduler.scheduleWithFixedDelay(
                this::refreshInvalidated,
                2,
                2,
                TimeUnit.SECONDS
        );
        scheduler.scheduleWithFixedDelay(
                this::pollErasures,
                3,
                Math.max(60, config.erasuresInterval().toSeconds()),
                TimeUnit.SECONDS
        );
        scheduler.scheduleWithFixedDelay(
                this::pollReceipts,
                4,
                Math.max(5, config.receiptsInterval().toSeconds()),
                TimeUnit.SECONDS
        );
        scheduler.scheduleWithFixedDelay(
                this::deliverErasureEvents,
                1,
                1,
                TimeUnit.SECONDS
        );
    }

    public CompletableFuture<List<AssetHolding>> refresh(UUID mojangUuid) {
        return database(() -> {
            LinkedPlayer linked = links.findLink(mojangUuid)
                    .orElseThrow(() -> new IllegalStateException("Player is not linked"));
            return new Invalidation(
                    linked.playerId(),
                    store.invalidationGeneration(linked.playerId())
            );
        }).thenCompose(target -> refresh(target.playerId(), target.generation())
                .thenCompose(holdings -> database(() -> {
                    CachedPlayer cached = store.cachedHoldings(target.playerId())
                            .orElseThrow();
                    if (!cached.known()) {
                        throw new IllegalStateException(
                                "Portal consent is missing; link again"
                        );
                    }
                    return holdings;
                })));
    }

    public CompletableFuture<HoldingsSnapshot> cached(UUID mojangUuid) {
        return database(() -> {
            Optional<LinkedPlayer> linked = links.findLink(mojangUuid);
            if (linked.isEmpty()) {
                return new HoldingsSnapshot(
                        HoldingsSnapshot.Status.UNKNOWN,
                        List.of(),
                        null
                );
            }
            String playerId = linked.get().playerId();
            Optional<CachedPlayer> cached = store.cachedHoldings(playerId);
            if (cached.isEmpty()) {
                return new HoldingsSnapshot(
                        isRefreshing(playerId)
                                ? HoldingsSnapshot.Status.REFRESHING
                                : HoldingsSnapshot.Status.UNKNOWN,
                        List.of(),
                        null
                );
            }
            if (!cached.get().known()) {
                return new HoldingsSnapshot(
                        HoldingsSnapshot.Status.UNKNOWN,
                        List.of(),
                        cached.get().fetchedAt()
                );
            }
            boolean stale = cached.get().fetchedAt()
                    .plus(config.holdingsTtl())
                    .isBefore(Instant.now())
                    || store.invalidationGeneration(playerId) > 0;
            HoldingsSnapshot.Status status = isRefreshing(playerId)
                    ? HoldingsSnapshot.Status.REFRESHING
                    : stale
                    ? HoldingsSnapshot.Status.STALE
                    : HoldingsSnapshot.Status.FRESH;
            return new HoldingsSnapshot(
                    status,
                    cached.get().holdings(),
                    cached.get().fetchedAt()
            );
        });
    }

    private CompletableFuture<List<AssetHolding>> refresh(
            String playerId,
            long generation
    ) {
        synchronized (refreshes) {
            CompletableFuture<List<AssetHolding>> existing = refreshes.get(playerId);
            if (existing != null) {
                return existing;
            }
            CompletableFuture<List<AssetHolding>> created = portal.holdings(List.of(playerId))
                    .thenCompose(results -> {
                        PortalModels.PlayerHoldings result = results.stream()
                                .filter(value -> playerId.equals(value.playerId()))
                                .findFirst()
                                .orElse(new PortalModels.PlayerHoldings(
                                        playerId,
                                        false,
                                        List.of()
                                ));
                        List<AssetHolding> holdings = result.holdings().stream()
                                .map(value -> new AssetHolding(
                                        value.collection(),
                                        value.tokenId(),
                                        value.balance()
                                ))
                                .toList();
                        return database(() -> {
                            store.saveHoldings(
                                    playerId,
                                    result.known(),
                                    holdings,
                                    generation
                            );
                            links.findLinkByPlayerId(playerId)
                                    .ifPresent(linked -> holdingsSink.accept(
                                            linked.mojangUuid()
                                    ));
                            return holdings;
                        });
                    });
            refreshes.put(playerId, created);
            created.whenComplete((ignored, failure) -> {
                synchronized (refreshes) {
                    refreshes.remove(playerId);
                }
            });
            return created;
        }
    }

    private void pollChanges() {
        if (!changesRunning.compareAndSet(false, true)) {
            return;
        }
        database(() -> store.checkpoint(
                "changes",
                Math.max(0, Instant.now().minusSeconds(60).toEpochMilli())
        )).thenCompose(checkpoint -> pollChangePage(
                start(checkpoint),
                checkpoint.since(),
                0
        )).whenComplete((ignored, failure) -> {
            changesRunning.set(false);
            report(failure);
        });
    }

    private CompletableFuture<Void> pollChangePage(
            PortalModels.FeedStart start,
            long durableSince,
            int page
    ) {
        return portal.changes(start, 100).thenCompose(result ->
                database(() -> {
                    store.applyChangePage(
                            result.results(),
                            result.nextSince(),
                            result.hasMore() ? result.nextCursor() : null
                    );
                    return null;
                }).thenCompose(ignored -> {
                    if (!result.hasMore() || page >= 19) {
                        return CompletableFuture.completedFuture(null);
                    }
                    return pollChangePage(
                            PortalModels.FeedStart.cursor(result.nextCursor()),
                            result.nextSince(),
                            page + 1
                    );
                })
        ).exceptionallyCompose(failure -> {
            Throwable cause = unwrap(failure);
            if (start.cursor() != null
                    && cause instanceof PortalException portalFailure
                    && portalFailure.status() == 400) {
                return database(() -> {
                    store.saveCheckpoint("changes", durableSince, null);
                    return null;
                }).thenCompose(ignored -> pollChangePage(
                        PortalModels.FeedStart.since(durableSince),
                        durableSince,
                        page
                ));
            }
            return CompletableFuture.failedFuture(cause);
        });
    }

    private void pollErasures() {
        if (!erasuresRunning.compareAndSet(false, true)) {
            return;
        }
        database(() -> store.checkpoint("erasures", 0))
                .thenCompose(checkpoint -> pollErasurePage(
                        start(checkpoint),
                        checkpoint.since(),
                        0
                ))
                .whenComplete((ignored, failure) -> {
                    erasuresRunning.set(false);
                    report(failure);
                });
    }

    private CompletableFuture<Void> pollErasurePage(
            PortalModels.FeedStart start,
            long durableSince,
            int page
    ) {
        return portal.erasures(start, 100).thenCompose(result ->
                database(() -> {
                    store.applyErasurePage(
                            result.results(),
                            result.nextSince(),
                            result.hasMore() ? result.nextCursor() : null
                    );
                    return null;
                }).thenCompose(ignored -> {
                    if (!result.hasMore() || page >= 19) {
                        return CompletableFuture.completedFuture(null);
                    }
                    return pollErasurePage(
                            PortalModels.FeedStart.cursor(result.nextCursor()),
                            result.nextSince(),
                            page + 1
                    );
                })
        ).exceptionallyCompose(failure -> {
            Throwable cause = unwrap(failure);
            if (start.cursor() != null
                    && cause instanceof PortalException portalFailure
                    && portalFailure.status() == 400) {
                return database(() -> {
                    store.saveCheckpoint("erasures", durableSince, null);
                    return null;
                }).thenCompose(ignored -> pollErasurePage(
                        PortalModels.FeedStart.since(durableSince),
                        durableSince,
                        page
                ));
            }
            return CompletableFuture.failedFuture(cause);
        });
    }

    private void refreshInvalidated() {
        if (!refreshRunning.compareAndSet(false, true)) {
            return;
        }
        database(() -> store.invalidatedPlayers(100))
                .thenCompose(this::refreshBatch)
                .whenComplete((ignored, failure) -> {
                    refreshRunning.set(false);
                    report(failure);
                });
    }

    private CompletableFuture<Void> refreshBatch(List<Invalidation> invalidations) {
        if (invalidations.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        List<String> playerIds = invalidations.stream()
                .map(Invalidation::playerId)
                .toList();
        Map<String, Long> generations = new HashMap<>();
        invalidations.forEach(value ->
                generations.put(value.playerId(), value.generation()));
        return portal.holdings(playerIds).thenCompose(results -> {
            Map<String, PortalModels.PlayerHoldings> byPlayer = new HashMap<>();
            results.forEach(result -> byPlayer.put(result.playerId(), result));
            return database(() -> {
                for (String playerId : playerIds) {
                    PortalModels.PlayerHoldings result = byPlayer.getOrDefault(
                            playerId,
                            new PortalModels.PlayerHoldings(playerId, false, List.of())
                    );
                    List<AssetHolding> holdings = result.holdings().stream()
                            .map(value -> new AssetHolding(
                                    value.collection(),
                                    value.tokenId(),
                                    value.balance()
                            ))
                            .toList();
                    store.saveHoldings(
                            playerId,
                            result.known(),
                            holdings,
                            generations.get(playerId)
                    );
                    links.findLinkByPlayerId(playerId)
                            .ifPresent(linked ->
                                    holdingsSink.accept(linked.mojangUuid()));
                }
                return null;
            });
        });
    }

    private void pollReceipts() {
        if (!receiptsRunning.compareAndSet(false, true)) {
            return;
        }
        database(() -> store.checkpoint(
                "receipts",
                Math.max(0, Instant.now().minusSeconds(60).toEpochMilli())
        )).thenCompose(checkpoint ->
                portal.receipts(checkpoint.since(), 100, null)
                        .thenCompose(receipts -> database(() -> {
                            long nextSince = checkpoint.since();
                            for (PortalModels.Receipt receipt : receipts) {
                                nextSince = Math.max(nextSince, receipt.createdAt());
                                store.findWriteByPortalKey(receipt.idempotencyKey())
                                        .filter(write -> write.state()
                                                != com.moonsama.minecraft.api.EconomyOperation.State.SUCCEEDED)
                                        .ifPresent(write ->
                                                store.completeWrite(
                                                        write.operationId(),
                                                        receipt
                                                ));
                            }
                            store.saveCheckpoint("receipts", nextSince, null);
                            return null;
                        }))
        ).whenComplete((ignored, failure) -> {
            receiptsRunning.set(false);
            report(failure);
        });
    }

    private void deliverErasureEvents() {
        database(() -> store.pendingEvents(50))
                .thenAccept(events -> events.forEach(event -> {
                    if (!"PLAYER_ERASED".equals(event.type())) {
                        return;
                    }
                    if (event.payload() != null && !event.payload().isBlank()) {
                        erasureSink.accept(UUID.fromString(event.payload()));
                    }
                    database(() -> {
                        store.markEventDelivered(event.eventId());
                        return null;
                    });
                }))
                .exceptionally(failure -> {
                    report(failure);
                    return null;
                });
    }

    private boolean isRefreshing(String playerId) {
        synchronized (refreshes) {
            return refreshes.containsKey(playerId);
        }
    }

    private static PortalModels.FeedStart start(FeedCheckpoint checkpoint) {
        return checkpoint.cursor() == null
                ? PortalModels.FeedStart.since(checkpoint.since())
                : PortalModels.FeedStart.cursor(checkpoint.cursor());
    }

    private <T> CompletableFuture<T> database(Supplier<T> action) {
        return CompletableFuture.supplyAsync(action, databaseExecutor);
    }

    private static Throwable unwrap(Throwable failure) {
        return failure instanceof CompletionException && failure.getCause() != null
                ? failure.getCause()
                : failure;
    }

    private void report(Throwable failure) {
        if (failure != null) {
            errorSink.accept(unwrap(failure));
        }
    }
}
