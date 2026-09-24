package com.moonsama.minecraft;

import com.moonsama.minecraft.api.AssetHolding;
import com.moonsama.minecraft.api.AssetHold;
import com.moonsama.minecraft.api.AssetHoldRequest;
import com.moonsama.minecraft.api.EconomyOperation;
import com.moonsama.minecraft.api.EconomyRefundRequest;
import com.moonsama.minecraft.api.EconomyRequest;
import com.moonsama.minecraft.api.HoldingsSnapshot;
import com.moonsama.minecraft.api.MoonsamaService;
import com.moonsama.minecraft.api.SkinSigner;
import com.moonsama.minecraft.api.PortalPlayer;
import com.moonsama.minecraft.api.event.AssetHoldChangedEvent;
import com.moonsama.minecraft.api.event.EconomyOperationEvent;
import com.moonsama.minecraft.api.event.PortalHoldingsLoadedEvent;
import com.moonsama.minecraft.api.event.PortalPlayerErasedEvent;
import com.moonsama.minecraft.api.event.PortalPlayerLinkedEvent;
import com.moonsama.minecraft.economy.EconomyCoordinator;
import com.moonsama.minecraft.skins.MineskinSigner;
import com.moonsama.minecraft.skins.SignatureCache;
import com.moonsama.minecraft.economy.HoldCoordinator;
import com.moonsama.minecraft.economy.PortalSyncService;
import com.moonsama.minecraft.store.LinkStore;
import com.moonsama.minecraft.store.LinkedPlayer;
import com.moonsama.minecraft.store.OAuthAttempt;
import com.moonsama.minecraft.store.SqliteLinkStore;
import com.moonsama.minecraft.store.SqlitePortalStore;
import com.moonsama.portal.PortalClient;
import com.moonsama.portal.PortalException;
import com.moonsama.portal.PortalOAuth;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public final class MoonsamaPaperPlugin extends JavaPlugin implements MoonsamaService, Listener {
    private final ExecutorService storageExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService signingExecutor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService workerScheduler =
            Executors.newScheduledThreadPool(2);
    private MoonsamaConfig config;
    private LinkStore links;
    private SqlitePortalStore portalStore;
    private PortalClient portal;
    private EconomyCoordinator economy;
    private HoldCoordinator holds;
    private PortalSyncService sync;
    private OAuthCallbackServer callbackServer;
    private ResourcePackManager resourcePack;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        config = MoonsamaConfig.load(this);

        getDataFolder().mkdirs();
        var databasePath = getDataFolder().toPath().resolve("data.db");
        links = new SqliteLinkStore(databasePath);
        links.initialize();
        portalStore = new SqlitePortalStore(databasePath);
        portalStore.initialize();

        portal = new PortalClient(new PortalClient.Options(
                config.apiUrl(),
                config.apiKey(),
                config.apiSecret(),
                java.time.Duration.ofSeconds(15),
                4
        ));

        economy = new EconomyCoordinator(
                portal,
                links,
                portalStore,
                storageExecutor,
                workerScheduler,
                config,
                this::onEconomyOperation,
                this::onWorkerError
        );
        holds = new HoldCoordinator(
                portal,
                links,
                portalStore,
                storageExecutor,
                workerScheduler,
                config,
                this::onHoldChanged,
                this::onWorkerError
        );
        sync = new PortalSyncService(
                portal,
                links,
                portalStore,
                storageExecutor,
                workerScheduler,
                config,
                this::onHoldingsLoaded,
                this::onPlayerErased,
                this::onWorkerError
        );
        Bukkit.getServicesManager().register(
                MoonsamaService.class,
                this,
                this,
                ServicePriority.Normal
        );
        registerSkinSigner();

        MoonsamaCommand command = new MoonsamaCommand(this);
        var registeredCommand = getCommand("moonsama");
        if (registeredCommand == null) {
            throw new IllegalStateException("The moonsama command is missing from paper-plugin.yml");
        }
        registeredCommand.setExecutor(command);
        registeredCommand.setTabCompleter(command);
        Bukkit.getPluginManager().registerEvents(this, this);

        resourcePack = ResourcePackManager.load(this, config);
        if (resourcePack != null) {
            Bukkit.getPluginManager().registerEvents(resourcePack, this);
        }

        if (!config.isComplete()) {
            getLogger().warning(
                    "Portal credentials are not configured. "
                            + "Set the PORTAL_* environment variables before using /moonsama link."
            );
        }

        callbackServer = new OAuthCallbackServer(
                config,
                portal,
                links,
                this::onPlayerLinked,
                resourcePack,
                storageExecutor,
                failure -> getLogger().log(
                        java.util.logging.Level.SEVERE,
                        "OAuth callback failed",
                        failure
                )
        );
        try {
            callbackServer.start();
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Could not start OAuth callback server on "
                            + config.callbackBindHost() + ":" + config.callbackPort(),
                    exception
            );
        }

        if (config.isComplete()) {
            economy.start();
            holds.start();
            sync.start();
            getLogger().info("Moonsama Portal integration is ready.");
        }
    }

    private void registerSkinSigner() {
        SignatureCache signatures = new SignatureCache(getDataFolder().toPath().resolve("skin-signatures.json"));
        signatures.load();
        MineskinSigner signer = new MineskinSigner(
                config.mineskinUrl(),
                config.mineskinApiKey(),
                "MoonsamaMinecraftKit/" + getPluginMeta().getVersion() + " (+https://moonsama.com)",
                signatures,
                signingExecutor,
                message -> getLogger().info(message)
        );
        Bukkit.getServicesManager().register(SkinSigner.class, signer, this, ServicePriority.Normal);
        if (signer.isAvailable()) {
            getLogger().info("Skin signing through MineSkin is enabled (" + signatures.size() + " cached textures).");
        } else {
            getLogger().info("Skin signing is disabled; set MINESKIN_API_KEY to enable composed skins.");
        }
    }

    @Override
    public void onDisable() {
        Bukkit.getServicesManager().unregisterAll(this);
        signingExecutor.shutdownNow();
        if (callbackServer != null) {
            callbackServer.close();
        }
        workerScheduler.shutdownNow();
        if (portal != null) {
            portal.close();
        }
        if (links != null) {
            links.close();
        }
        storageExecutor.close();
    }

    CompletableFuture<URI> beginLink(UUID mojangUuid) {
        if (!config.isComplete()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Portal credentials are not configured")
            );
        }
        PortalOAuth.Attempt oauth = PortalOAuth.newAttempt();
        OAuthAttempt stored = new OAuthAttempt(
                oauth.state(),
                mojangUuid,
                oauth.verifier(),
                Instant.now().plus(config.oauthAttemptTtl())
        );
        return CompletableFuture.runAsync(() -> links.createAttempt(stored), storageExecutor)
                .thenApply(ignored -> PortalOAuth.authorizationUri(
                        config.portalUrl(),
                        config.oauthClientId(),
                        config.oauthRedirectUri(),
                        oauth.state(),
                        oauth.challenge()
                ));
    }

    CompletableFuture<List<String>> operatorStatus() {
        return CompletableFuture.supplyAsync(() -> {
            List<String> lines = new java.util.ArrayList<>();
            lines.add("Portal configured: " + config.isComplete());
            lines.add("Gates: spend=" + config.spendEnabled()
                    + ", reward=" + config.rewardEnabled()
                    + ", refund=" + config.refundEnabled()
                    + ", holds=" + config.holdsEnabled());
            lines.add("Write states: " + portalStore.writeStateCounts());
            lines.add("Hold states: " + portalStore.holdStateCounts());
            lines.add("Oldest pending write: " + portalStore.oldestPendingWriteAt()
                    .map(value -> java.time.Duration.between(value, Instant.now()).toSeconds()
                            + "s ago")
                    .orElse("none"));
            lines.add("Feed cursors: changes="
                    + portalStore.checkpoint("changes", 0).since()
                    + ", receipts=" + portalStore.checkpoint("receipts", 0).since()
                    + ", erasures=" + portalStore.checkpoint("erasures", 0).since());
            lines.add("Rate limit: " + portal.latestRateLimit()
                    .map(value -> value.remaining() + "/" + value.limit()
                            + " reset=" + value.reset())
                    .orElse("not observed"));
            return List.copyOf(lines);
        }, storageExecutor);
    }

    @Override
    public CompletableFuture<Optional<PortalPlayer>> linkedPlayer(UUID mojangUuid) {
        return CompletableFuture.supplyAsync(
                () -> links.findLink(mojangUuid).map(MoonsamaPaperPlugin::apiPlayer),
                storageExecutor
        );
    }

    @Override
    public CompletableFuture<List<AssetHolding>> holdings(UUID mojangUuid) {
        return sync.refresh(mojangUuid);
    }

    @Override
    public CompletableFuture<HoldingsSnapshot> cachedHoldings(UUID mojangUuid) {
        return sync.cached(mojangUuid);
    }

    @Override
    public CompletableFuture<EconomyOperation> spend(
            Plugin owner,
            EconomyRequest request
    ) {
        return economy.spend(owner, request);
    }

    @Override
    public CompletableFuture<EconomyOperation> reward(
            Plugin owner,
            EconomyRequest request
    ) {
        return economy.reward(owner, request);
    }

    @Override
    public CompletableFuture<EconomyOperation> refund(
            Plugin owner,
            EconomyRefundRequest request
    ) {
        return economy.refund(owner, request);
    }

    @Override
    public CompletableFuture<Optional<EconomyOperation>> operation(
            Plugin owner,
            String businessKey
    ) {
        return economy.operation(owner, businessKey);
    }

    @Override
    public CompletableFuture<AssetHold> placeHold(
            Plugin owner,
            AssetHoldRequest request
    ) {
        return holds.place(owner, request);
    }

    @Override
    public CompletableFuture<AssetHold> renewHold(
            Plugin owner,
            String businessKey,
            int ttlSeconds
    ) {
        return holds.renew(owner, businessKey, ttlSeconds);
    }

    @Override
    public CompletableFuture<AssetHold> releaseHold(
            Plugin owner,
            String businessKey
    ) {
        return holds.release(owner, businessKey);
    }

    @Override
    public CompletableFuture<Optional<AssetHold>> hold(
            Plugin owner,
            String businessKey
    ) {
        return holds.find(owner, businessKey);
    }

    String userMessage(Throwable failure) {
        Throwable cause = unwrap(failure);
        if (cause instanceof PortalException portalFailure) {
            return switch (portalFailure.code()) {
                case "PLAYER_CONSENT_REQUIRED", "CONSENT_STALE" ->
                        "Portal consent needs updating. Run /moonsama link.";
                case "PLAYER_SUSPENDED", "PLAYER_CLOSED" ->
                        "This Portal account cannot use Moonsama features (" + portalFailure.code() + ").";
                case null, default -> "Portal request failed: " + portalFailure.getMessage();
            };
        }
        return cause.getMessage() == null ? "Something went wrong." : cause.getMessage();
    }

    private void onPlayerLinked(LinkedPlayer linked) {
        Bukkit.getScheduler().runTask(this, () -> {
            PortalPlayer apiPlayer = apiPlayer(linked);
            Bukkit.getPluginManager().callEvent(new PortalPlayerLinkedEvent(apiPlayer));
            Player player = Bukkit.getPlayer(linked.mojangUuid());
            if (player != null) {
                player.sendRichMessage(
                        "<green>Your Moonsama account is linked as <white>"
                                + safe(linked.gamerTag()) + "</white>.</green>"
                );
            }
        });
        if (config.isComplete()) {
            sync.refresh(linked.mojangUuid()).exceptionally(failure -> {
                getLogger().fine("Initial holdings refresh failed: " + failure.getMessage());
                return List.of();
            });
        }
    }

    private void onEconomyOperation(EconomyOperation operation) {
        Bukkit.getScheduler().runTask(this, () ->
                Bukkit.getPluginManager().callEvent(
                        new EconomyOperationEvent(operation)
                ));
    }

    private void onHoldChanged(AssetHold hold) {
        Bukkit.getScheduler().runTask(this, () ->
                Bukkit.getPluginManager().callEvent(
                        new AssetHoldChangedEvent(hold)
                ));
    }

    private void onHoldingsLoaded(UUID mojangUuid) {
        linkedPlayer(mojangUuid)
                .thenCombine(cachedHoldings(mojangUuid), (player, snapshot) ->
                        Map.entry(player, snapshot))
                .thenAccept(result -> result.getKey().ifPresent(player ->
                        Bukkit.getScheduler().runTask(this, () ->
                                Bukkit.getPluginManager().callEvent(
                                        new PortalHoldingsLoadedEvent(
                                                player,
                                                result.getValue().holdings()
                                        )
                                ))
                ))
                .exceptionally(failure -> null);
    }

    private void onPlayerErased(UUID mojangUuid) {
        Bukkit.getScheduler().runTask(this, () ->
                Bukkit.getPluginManager().callEvent(
                        new PortalPlayerErasedEvent(mojangUuid)
                ));
    }

    private void onWorkerError(Throwable failure) {
        getLogger().log(
                java.util.logging.Level.WARNING,
                "Portal background worker failed",
                failure
        );
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!config.isComplete()) {
            return;
        }
        sync.refresh(event.getPlayer().getUniqueId()).exceptionally(failure -> null);
    }

    private static PortalPlayer apiPlayer(LinkedPlayer linked) {
        return new PortalPlayer(
                linked.mojangUuid(),
                linked.playerId(),
                linked.gamerTag(),
                linked.linkedAt()
        );
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable result = failure;
        while ((result instanceof CompletionException)
                && result.getCause() != null) {
            result = result.getCause();
        }
        return result;
    }

    private static String safe(String value) {
        if (value == null) {
            return "unknown";
        }
        return value.replace("<", "").replace(">", "");
    }
}
