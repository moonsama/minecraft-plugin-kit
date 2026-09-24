package com.moonsama.minecraft.gatekeeper;

import com.moonsama.minecraft.api.AssetHolding;
import com.moonsama.minecraft.api.HoldingsSnapshot;
import com.moonsama.minecraft.api.MoonsamaService;
import com.moonsama.minecraft.api.event.PortalHoldingsLoadedEvent;
import com.moonsama.minecraft.api.event.PortalPlayerErasedEvent;
import com.moonsama.minecraft.api.event.PortalPlayerLinkedEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Optional access gate: players play only while their linked Portal account holds one of the
 * configured passes. Everyone else is kicked or parked in the {@link WaitingRoom}, depending
 * on config, and re-evaluated whenever MoonsamaCore reports fresh holdings.
 */
public final class MoonsamaGatekeeperPlugin extends JavaPlugin implements Listener, TabCompleter {
    private MoonsamaService moonsama;
    private Gate gate;
    private WaitingRoom waitingRoom;
    private BukkitTask ticker;

    @Override
    public void onEnable() {
        moonsama = getServer().getServicesManager().load(MoonsamaService.class);
        if (moonsama == null) {
            throw new IllegalStateException("MoonsamaCore service is unavailable");
        }
        saveDefaultConfig();
        var command = getCommand("gatekeeper");
        if (command != null) {
            command.setTabCompleter(this);
        }
        start();
    }

    private void start() {
        GateConfig config;
        try {
            config = GateConfig.from(getConfig());
        } catch (IllegalArgumentException e) {
            getLogger().severe("config.yml is invalid: " + e.getMessage() + " — the gate stays OFF.");
            return;
        }
        gate = new Gate(config);
        if (!config.enabled()) {
            getLogger().info("Access gate is disabled (enabled: false).");
            return;
        }
        if (config.passes().isEmpty()) {
            getLogger().severe("No passes configured; the gate would lock everyone out and stays OFF.");
            return;
        }
        waitingRoom = new WaitingRoom(config, this::onTimeout);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(waitingRoom, this);
        ticker = getServer().getScheduler().runTaskTimer(this, () -> waitingRoom.tick(getServer().getOnlinePlayers()), 20, 20);
        getLogger().info("Access gate enabled; passes: " + config.passes().stream().map(Pass::describe).toList());
        for (Player player : getServer().getOnlinePlayers()) {
            evaluate(player);
        }
    }

    private void stop() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
        HandlerList.unregisterAll((Listener) this);
        if (waitingRoom != null) {
            HandlerList.unregisterAll(waitingRoom);
            waitingRoom = null;
        }
    }

    @Override
    public void onDisable() {
        stop();
    }

    public boolean active() {
        return waitingRoom != null;
    }

    // ------------------------------------------------------------- decisions

    /** Loads link + cached holdings off the main thread and applies the verdict on it. */
    private void evaluate(Player player) {
        if (!active()) {
            return;
        }
        if (player.hasPermission(gate.config().bypassPermission())) {
            apply(player, gate.forBypass(), false);
            return;
        }
        UUID uuid = player.getUniqueId();
        moonsama.linkedPlayer(uuid).thenCompose(linked -> {
            if (linked.isEmpty()) {
                return CompletableFuture.completedFuture(gate.forUnlinked());
            }
            return moonsama.cachedHoldings(uuid).thenApply(gate::forLinked);
        }).whenComplete((decision, failure) -> runMain(() -> {
            if (!player.isOnline() || !active()) {
                return;
            }
            if (failure != null) {
                getLogger().log(Level.WARNING, "Could not evaluate " + player.getName(), failure);
                apply(player, Gate.Decision.of(gate.config().unavailable(), Gate.Reason.UNAVAILABLE), false);
                return;
            }
            apply(player, decision, false);
        }));
    }

    private void apply(Player player, Gate.Decision decision, boolean fromRefresh) {
        GateConfig config = gate.config();
        switch (decision.action()) {
            case ALLOW -> {
                boolean wasWaiting = waitingRoom.release(player);
                if (wasWaiting && decision.reason() == Gate.Reason.PASS) {
                    player.sendMessage(WaitingRoom.render(config.message("granted")));
                }
                if (decision.reason() == Gate.Reason.PASS) {
                    getLogger().fine(player.getName() + " admitted with " + decision.pass().map(h -> h.collection() + " #" + h.tokenId()).orElse("?"));
                }
            }
            case RESTRICT -> {
                if (fromRefresh && !waitingRoom.isRestricted(player.getUniqueId())) {
                    player.sendMessage(WaitingRoom.render(config.message("revoked")));
                }
                waitingRoom.restrict(player, decision.reason());
            }
            case KICK -> {
                waitingRoom.forget(player.getUniqueId());
                player.kick(WaitingRoom.render(config.message(Gate.kickMessageKey(decision.reason()))));
                getLogger().info("Kicked " + player.getName() + " (" + decision.reason().name().toLowerCase(Locale.ROOT) + ")");
            }
        }
    }

    /** Grace (unlinked) or wait (unavailable) ran out. */
    private void onTimeout(Player player, Gate.Reason reason) {
        if (reason == Gate.Reason.UNLINKED) {
            player.kick(WaitingRoom.render(gate.config().message("kick-grace")));
            getLogger().info("Kicked " + player.getName() + " (grace period over)");
            return;
        }
        // UNAVAILABLE: one more look at the cache, then the configured fallback.
        UUID uuid = player.getUniqueId();
        moonsama.cachedHoldings(uuid).whenComplete((snapshot, failure) -> runMain(() -> {
            if (!player.isOnline() || !active() || !waitingRoom.isRestricted(uuid)) {
                return;
            }
            Gate.Decision decision = failure == null ? gate.forLinked(snapshot)
                    : Gate.Decision.of(gate.config().unavailable(), Gate.Reason.UNAVAILABLE);
            if (decision.reason() == Gate.Reason.UNAVAILABLE && decision.action() == GateConfig.Action.RESTRICT) {
                waitingRoom.restart(uuid); // keep waiting, check again after wait-seconds
                return;
            }
            apply(player, decision, false);
        }));
    }

    // ---------------------------------------------------------------- events

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        evaluate(event.getPlayer());
    }

    @EventHandler
    public void onLinked(PortalPlayerLinkedEvent event) {
        Player player = getServer().getPlayer(event.player().mojangUuid());
        if (player != null && waitingRoom.isRestricted(player.getUniqueId())) {
            waitingRoom.restrict(player, Gate.Reason.UNAVAILABLE); // "checking…" until holdings arrive
        }
    }

    @EventHandler
    public void onHoldingsLoaded(PortalHoldingsLoadedEvent event) {
        Player player = getServer().getPlayer(event.player().mojangUuid());
        if (player == null || player.hasPermission(gate.config().bypassPermission())) {
            return;
        }
        Gate.Decision decision = gate.forHoldings(event.holdings());
        boolean playing = !waitingRoom.isRestricted(player.getUniqueId());
        if (decision.allowed() || !playing || gate.config().recheckOnRefresh()) {
            apply(player, decision, playing);
        }
    }

    @EventHandler
    public void onErased(PortalPlayerErasedEvent event) {
        Player player = getServer().getPlayer(event.mojangUuid());
        if (player != null) {
            evaluate(player);
        }
    }

    // -------------------------------------------------------------- commands

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String @NotNull [] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                stop();
                reloadConfig();
                start();
                sender.sendMessage(Component.text(active() ? "Gatekeeper reloaded; gate is ON." : "Gatekeeper reloaded; gate is OFF."));
            }
            case "status" -> {
                if (gate == null) {
                    sender.sendMessage(Component.text("Config invalid; see console."));
                    return true;
                }
                GateConfig config = gate.config();
                sender.sendRichMessage("<gold>Gate:</gold> " + (active() ? "<green>ON</green>" : "<red>OFF</red>")
                        + " <gray>unlinked=" + config.unlinked() + " denied=" + config.denied()
                        + " unavailable=" + config.unavailable() + " waiting=" + (active() ? waitingRoom.size() : 0) + "</gray>");
                for (Pass pass : config.passes()) {
                    sender.sendRichMessage("<gray> - pass: <white>" + pass.describe() + "</white></gray>");
                }
                Player target = args.length >= 2 ? getServer().getPlayer(args[1])
                        : sender instanceof Player self ? self : null;
                if (target != null) {
                    describe(sender, target);
                } else if (args.length >= 2) {
                    sender.sendRichMessage("<red>Player not found.</red>");
                }
            }
            default -> sender.sendRichMessage("<red>Usage: /" + label + " [status [player]|reload]</red>");
        }
        return true;
    }

    private void describe(CommandSender sender, Player target) {
        String state = active() && waitingRoom.isRestricted(target.getUniqueId())
                ? "waiting (" + waitingRoom.entry(target.getUniqueId()).map(e -> e.reason().name().toLowerCase(Locale.ROOT)).orElse("?") + ")"
                : "playing";
        sender.sendRichMessage("<gold>" + target.getName() + ":</gold> <white>" + state + "</white>"
                + (target.hasPermission(gate.config().bypassPermission()) ? " <gray>(bypass)</gray>" : ""));
        moonsama.linkedPlayer(target.getUniqueId()).thenCompose(linked -> {
            if (linked.isEmpty()) {
                return CompletableFuture.completedFuture("not linked");
            }
            return moonsama.cachedHoldings(target.getUniqueId()).thenApply(snapshot -> {
                Gate.Decision decision = gate.forLinked(snapshot);
                String pass = decision.pass().map(h -> h.collection() + " #" + h.tokenId()).orElse("none");
                return "linked, holdings " + snapshot.status().name().toLowerCase(Locale.ROOT)
                        + ", pass: " + pass + " → " + decision.action().name().toLowerCase(Locale.ROOT);
            });
        }).whenComplete((text, failure) -> runMain(() ->
                sender.sendRichMessage("<gray>Portal: <white>" + (failure != null ? "error: " + failure.getMessage() : text) + "</white></gray>")));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, String @NotNull [] args) {
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            for (String option : List.of("status", "reload")) {
                if (option.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    options.add(option);
                }
            }
        } else if (args.length == 2 && "status".equalsIgnoreCase(args[0])) {
            for (Player player : getServer().getOnlinePlayers()) {
                if (player.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    options.add(player.getName());
                }
            }
        }
        return options;
    }

    // --------------------------------------------------------------- helpers

    /** Convenience for other plugins: does this player currently hold a pass (per cache)? */
    public CompletableFuture<Optional<AssetHolding>> passOf(UUID mojangUuid) {
        if (gate == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return moonsama.cachedHoldings(mojangUuid).thenApply((HoldingsSnapshot snapshot) ->
                Pass.firstMatch(gate.config().passes(), snapshot.holdings()));
    }

    private void runMain(Runnable action) {
        if (getServer().isPrimaryThread()) {
            action.run();
        } else {
            getServer().getScheduler().runTask(this, action);
        }
    }
}
