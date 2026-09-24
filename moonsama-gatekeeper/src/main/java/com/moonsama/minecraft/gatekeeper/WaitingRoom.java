package com.moonsama.minecraft.gatekeeper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Players who may be on the server but not play yet: they can look around and chat, use a
 * few commands (linking!), and nothing else. Reminders and the grace timer run from
 * {@link #tick()} once per second.
 */
public final class WaitingRoom implements Listener {
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    public record Entry(Gate.Reason reason, long since, long lastReminder) {}

    private final GateConfig config;
    private final Map<UUID, Entry> restricted = new HashMap<>();
    /** Called when the grace period (unlinked) or the wait (unavailable) runs out. */
    private final BiConsumer<Player, Gate.Reason> onTimeout;

    public WaitingRoom(GateConfig config, BiConsumer<Player, Gate.Reason> onTimeout) {
        this.config = config;
        this.onTimeout = onTimeout;
    }

    public boolean isRestricted(UUID uuid) {
        return restricted.containsKey(uuid);
    }

    public Optional<Entry> entry(UUID uuid) {
        return Optional.ofNullable(restricted.get(uuid));
    }

    public int size() {
        return restricted.size();
    }

    /** Puts (or keeps) the player in the waiting room; the title reflects the latest reason. */
    public void restrict(Player player, Gate.Reason reason) {
        long now = System.currentTimeMillis();
        Entry previous = restricted.get(player.getUniqueId());
        boolean sameReason = previous != null && previous.reason() == reason;
        // A new reason restarts the clock (grace after linking, wait after a refresh).
        restricted.put(player.getUniqueId(), new Entry(reason,
                sameReason ? previous.since() : now,
                sameReason ? previous.lastReminder() : now));
        if (!sameReason) {
            player.showTitle(Title.title(
                    MINI.deserialize(config.message("restricted-title")),
                    MINI.deserialize(config.message(Gate.subtitleKey(reason))),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(4), Duration.ofSeconds(1))));
            player.sendMessage(MINI.deserialize(config.message("restricted-reminder")));
        }
    }

    /** Restarts the timer of a restricted player without changing the reason. */
    public void restart(UUID uuid) {
        Entry entry = restricted.get(uuid);
        if (entry != null) {
            restricted.put(uuid, new Entry(entry.reason(), System.currentTimeMillis(), entry.lastReminder()));
        }
    }

    /** Lets the player play; returns true if they were restricted. */
    public boolean release(Player player) {
        return restricted.remove(player.getUniqueId()) != null;
    }

    public void forget(UUID uuid) {
        restricted.remove(uuid);
    }

    /** Once per second from the plugin. */
    public void tick(Iterable<? extends Player> online) {
        long now = System.currentTimeMillis();
        for (Player player : online) {
            Entry entry = restricted.get(player.getUniqueId());
            if (entry == null) {
                continue;
            }
            long elapsed = (now - entry.since()) / 1000;
            if (entry.reason() == Gate.Reason.UNLINKED && config.graceSeconds() > 0 && elapsed >= config.graceSeconds()) {
                onTimeout.accept(player, entry.reason());
                continue;
            }
            if (entry.reason() == Gate.Reason.UNAVAILABLE && elapsed >= config.unavailableWaitSeconds()) {
                onTimeout.accept(player, entry.reason());
                continue;
            }
            if (config.reminderSeconds() > 0 && now - entry.lastReminder() >= config.reminderSeconds() * 1000L) {
                restricted.put(player.getUniqueId(), new Entry(entry.reason(), entry.since(), now));
                player.sendActionBar(MINI.deserialize(config.message("restricted-reminder")));
            }
        }
    }

    // ------------------------------------------------------------- blocking

    private boolean blocked(Player player) {
        if (!restricted.containsKey(player.getUniqueId())) {
            return false;
        }
        player.sendActionBar(MINI.deserialize(config.message("restricted-blocked")));
        return true;
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!restricted.containsKey(event.getPlayer().getUniqueId()) || !event.hasExplicitlyChangedPosition()) {
            return;
        }
        Location to = event.getFrom().clone();
        to.setYaw(event.getTo().getYaw());
        to.setPitch(event.getTo().getPitch());
        event.setTo(to);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!restricted.containsKey(event.getPlayer().getUniqueId())) {
            return;
        }
        String command = event.getMessage().substring(1).trim().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        int namespace = command.indexOf(':');
        if (namespace >= 0) {
            command = command.substring(namespace + 1);
        }
        if (!config.allowedCommands().contains(command)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(MINI.deserialize(config.message("restricted-blocked")));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (blocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (blocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (restricted.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (blocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player victim && restricted.containsKey(victim.getUniqueId())) {
            event.setCancelled(true); // nobody gets hurt in the waiting room
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player attacker && blocked(attacker)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && restricted.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player && restricted.containsKey(player.getUniqueId())
                && event.getInventory().getHolder(false) != player) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (blocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && restricted.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        restricted.remove(event.getPlayer().getUniqueId());
    }

    public static Component render(String miniMessage) {
        return MINI.deserialize(miniMessage);
    }
}
