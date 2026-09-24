package com.moonsama.minecraft.items.perks;

import com.moonsama.minecraft.items.CosmeticItems;
import com.moonsama.minecraft.items.ItemsCatalog;
import com.moonsama.minecraft.items.Offhand;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Keeps one active {@link Perk} per online player, following whatever cosmetic off-hand is in
 * their off-hand slot, and routes the relevant Bukkit events to it.
 *
 * <p>Instead of mirroring every inventory event like the legacy plugin, the slot is polled every
 * {@link Perk#PERIOD} ticks; a switch is detected within a quarter second, which is plenty.
 */
public final class PerkManager implements Listener {
    private final Plugin plugin;
    private final CosmeticItems items;
    private final PerkRegistry registry;
    private final Map<UUID, Active> active = new HashMap<>();
    private BukkitTask task;

    private record Active(Perk perk, PerkState state) {}

    public PerkManager(Plugin plugin, ItemsCatalog catalog, CosmeticItems items, PerkSettings settings) {
        this.plugin = plugin;
        this.items = items;
        this.registry = new PerkRegistry(new PerkContext(plugin, items, settings), catalog, settings);
    }

    public PerkRegistry registry() {
        return registry;
    }

    public void start() {
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickAll, 1, Perk.PERIOD);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            deactivate(player);
        }
        active.clear();
        registry.shutdown();
    }

    /** The perk currently driving the player's off-hand, if any. */
    public Optional<Perk> activePerk(Player player) {
        Active current = active.get(player.getUniqueId());
        return current == null ? Optional.empty() : Optional.of(current.perk());
    }

    public Optional<PerkState> activeState(Player player) {
        Active current = active.get(player.getUniqueId());
        return current == null ? Optional.empty() : Optional.of(current.state());
    }

    // ------------------------------------------------------------------ core

    private void tickAll() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            try {
                sync(player);
                Active current = active.get(player.getUniqueId());
                if (current != null) {
                    current.perk().tick(player, current.state());
                }
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "Off-hand perk tick failed for " + player.getName(), e);
            }
        }
    }

    /** Aligns the active perk with the item in the off-hand slot. */
    public void sync(Player player) {
        Optional<Perk> wanted = items.offhandOf(player.getInventory().getItemInOffHand()).flatMap(registry::forOffhand);
        Active current = active.get(player.getUniqueId());
        if (current != null && wanted.isPresent() && current.perk() == wanted.get()) {
            return;
        }
        if (current != null) {
            deactivate(player);
        }
        if (wanted.isPresent()) {
            PerkState state = new PerkState();
            Active next = new Active(wanted.get(), state);
            active.put(player.getUniqueId(), next);
            try {
                wanted.get().onEquip(player, state);
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "Off-hand perk equip failed for " + player.getName(), e);
            }
        }
    }

    private void deactivate(Player player) {
        Active current = active.remove(player.getUniqueId());
        if (current == null) {
            return;
        }
        try {
            current.perk().onUnequip(player, current.state());
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Off-hand perk unequip failed for " + player.getName(), e);
        }
    }

    private boolean isActiveOffhand(Player player, ItemStack item) {
        Active current = active.get(player.getUniqueId());
        return current != null && items.offhandOf(item).filter(current.perk().offhand().id()::equals).isPresent();
    }

    // ---------------------------------------------------------------- events

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        Active current = active.get(player.getUniqueId());
        if (current == null) {
            return;
        }
        if (event.getHand() == EquipmentSlot.OFF_HAND) {
            if (event.useItemInHand() != org.bukkit.event.Event.Result.DENY) {
                current.perk().onInteract(player, event, current.state());
            }
        } else if (event.getHand() == EquipmentSlot.HAND && event.getItem() != null
                && items.offhandOf(event.getItem()).isPresent()) {
            // Legacy behaviour: the perk only works from the off-hand.
            player.sendActionBar(net.kyori.adventure.text.Component.text("This item only works in the off-hand", NamedTextColor.RED));
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onConsume(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        Active current = active.get(player.getUniqueId());
        if (current == null) {
            return;
        }
        if (!isActiveOffhand(player, event.getItem())) {
            if (items.offhandOf(event.getItem()).isPresent()) {
                event.setCancelled(true); // some other copy/form of an off-hand: never edible
            }
            return;
        }
        if (event.getHand() != EquipmentSlot.OFF_HAND) {
            event.setCancelled(true);
            player.sendActionBar(net.kyori.adventure.text.Component.text("This item only works in the off-hand", NamedTextColor.RED));
            return;
        }
        current.perk().onConsume(player, event, current.state());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFoodChange(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        Active current = active.get(player.getUniqueId());
        if (current != null && event.getItem() != null && isActiveOffhand(player, event.getItem())) {
            current.perk().onFoodChange(player, event, current.state());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof Player victim && event.getDamager() instanceof Player attacker) {
            Active victimPerk = active.get(victim.getUniqueId());
            if (victimPerk != null) {
                victimPerk.perk().onDamagedByPlayer(victim, attacker, event, victimPerk.state());
            }
            Active attackerPerk = active.get(attacker.getUniqueId());
            if (attackerPerk != null) {
                attackerPerk.perk().onAttackPlayer(attacker, victim, event, attackerPerk.state());
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSneak(PlayerToggleSneakEvent event) {
        Active current = active.get(event.getPlayer().getUniqueId());
        if (current != null) {
            current.perk().onSneak(event.getPlayer(), event, current.state());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onItemDamage(PlayerItemDamageEvent event) {
        if (items.isManagedCosmetic(event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Active current = active.get(event.getEntity().getUniqueId());
        if (current != null) {
            current.perk().onDeath(event.getEntity(), current.state());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        deactivate(event.getPlayer());
    }

    /** Off-hands are allowed to be "used" (eaten, thrown) only through their perks. */
    public boolean handlesConsumption(Offhand offhand) {
        return registry.forOffhand(offhand.id()).isPresent();
    }
}
