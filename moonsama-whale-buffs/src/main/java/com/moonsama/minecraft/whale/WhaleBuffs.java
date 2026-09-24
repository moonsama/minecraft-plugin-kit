package com.moonsama.minecraft.whale;

import com.moonsama.minecraft.api.AssetHolding;
import com.moonsama.minecraft.api.MoonsamaService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Tracks Moon Power per online player and applies the buffs: max health, damage, name colour,
 * the Whale Scepter and Whale Mode.
 */
public final class WhaleBuffs implements Listener {
    static final int PERIOD_TICKS = 5;

    /** Per-player state; power is what the holdings are worth, entitled whether it applies. */
    public static final class Tracker {
        double power;
        boolean entitled;
        long whaleModeUntil;
        long cooldownUntil;
        BukkitTask countdown;

        public double power() {
            return power;
        }

        public boolean entitled() {
            return entitled;
        }

        public boolean whaleMode() {
            return System.currentTimeMillis() < whaleModeUntil;
        }

        public long cooldownRemainingMs() {
            return Math.max(0, cooldownUntil - System.currentTimeMillis());
        }
    }

    private final Plugin plugin;
    private final MoonsamaService moonsama;
    private final WhaleConfig config;
    private final Scepter scepter;
    private final NamespacedKey healthKey;
    private final Map<UUID, Tracker> trackers = new HashMap<>();
    /** Answers "does this player wear an entitled skin"; null when MoonsamaSkins is absent. */
    private SkinBridge skins;
    private BukkitTask ticker;

    public WhaleBuffs(Plugin plugin, MoonsamaService moonsama, WhaleConfig config) {
        this.plugin = plugin;
        this.moonsama = moonsama;
        this.config = config;
        this.scepter = new Scepter(plugin, config);
        this.healthKey = new NamespacedKey(plugin, "moon_power");
    }

    public WhaleConfig config() {
        return config;
    }

    public Scepter scepter() {
        return scepter;
    }

    void skins(SkinBridge bridge) {
        this.skins = bridge;
    }

    public void start() {
        ticker = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1, PERIOD_TICKS);
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            refresh(player);
        }
    }

    public void stop() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            clear(player);
        }
        trackers.clear();
    }

    public Optional<Tracker> tracker(UUID uuid) {
        return Optional.ofNullable(trackers.get(uuid));
    }

    private Tracker trackerOf(Player player) {
        return trackers.computeIfAbsent(player.getUniqueId(), k -> new Tracker());
    }

    /** Power that currently applies (0 unless enabled and entitled). */
    public double effectivePower(Player player) {
        Tracker tracker = trackers.get(player.getUniqueId());
        if (tracker == null || !config.enabled() || !tracker.entitled) {
            return 0;
        }
        return tracker.power;
    }

    // --------------------------------------------------------------- updates

    /** Recomputes from MoonsamaCore's cached holdings (join, reload). */
    public void refresh(Player player) {
        UUID uuid = player.getUniqueId();
        moonsama.cachedHoldings(uuid).whenComplete((snapshot, failure) -> runMain(() -> {
            if (!player.isOnline()) {
                return;
            }
            if (failure != null) {
                plugin.getLogger().log(Level.FINE, "Holdings unavailable for " + player.getName(), failure);
                setHoldings(player, List.of());
            } else {
                setHoldings(player, snapshot.holdings());
            }
        }));
    }

    public void setHoldings(Player player, List<AssetHolding> holdings) {
        Tracker tracker = trackerOf(player);
        tracker.power = MoonPower.of(config, holdings);
        tracker.entitled = entitled(player);
        apply(player, tracker);
    }

    /** Re-reads the skin entitlement only (skin changed). */
    public void refreshEntitlement(Player player) {
        Tracker tracker = trackerOf(player);
        tracker.entitled = entitled(player);
        apply(player, tracker);
    }

    private boolean entitled(Player player) {
        if (!config.requireEntitledSkin()) {
            return true;
        }
        return skins != null && skins.wearsEntitledSkin(player.getUniqueId(), config.entitledCollections());
    }

    private void apply(Player player, Tracker tracker) {
        double power = config.enabled() && tracker.entitled ? tracker.power : 0;
        if (power <= 0 && tracker.whaleMode()) {
            endWhaleMode(player, tracker, false);
        }
        applyName(player, power);
        applyHealth(player, power, tracker.whaleMode());
        scepter.sync(player, tracker.power, config.enabled() && tracker.entitled);
    }

    private void applyName(Player player, double power) {
        Optional<Component> styled = NameStyle.styled(config, player.getName(), power);
        if (styled.isPresent()) {
            player.displayName(styled.get());
            player.playerListName(styled.get());
        } else if (config.namesEnabled()) {
            player.displayName(null);
            player.playerListName(null);
        }
    }

    private void applyHealth(Player player, double power, boolean whaleMode) {
        AttributeInstance attribute = player.getAttribute(Attribute.MAX_HEALTH);
        if (attribute == null) {
            return;
        }
        double extra = MoonPower.extraHealth(config, power, whaleMode);
        AttributeModifier previous = attribute.getModifier(healthKey);
        double previousExtra = previous == null ? 0 : previous.getAmount();
        if (previous != null) {
            attribute.removeModifier(healthKey);
        }
        if (extra != 0) {
            attribute.addModifier(new AttributeModifier(healthKey, extra, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.ANY));
        }
        double heal = Math.max(0, extra - previousExtra);
        player.setHealth(Math.min(attribute.getValue(), player.getHealth() + heal));
    }

    /** Removes everything this plugin put on the player. */
    private void clear(Player player) {
        Tracker tracker = trackers.get(player.getUniqueId());
        if (tracker != null && tracker.whaleMode()) {
            endWhaleMode(player, tracker, false);
        }
        AttributeInstance attribute = player.getAttribute(Attribute.MAX_HEALTH);
        if (attribute != null && attribute.getModifier(healthKey) != null) {
            attribute.removeModifier(healthKey);
            player.setHealth(Math.min(attribute.getValue(), player.getHealth()));
        }
        if (config.namesEnabled()) {
            player.displayName(null);
            player.playerListName(null);
        }
        scepter.remove(player);
    }

    // ------------------------------------------------------------ whale mode

    public boolean activateWhaleMode(Player player) {
        Tracker tracker = trackerOf(player);
        if (!config.enabled() || !config.whaleMode().enabled() || effectivePower(player) < config.whaleMode().scepterMinPower()) {
            return false;
        }
        if (tracker.whaleMode()) {
            return false;
        }
        long cooldown = tracker.cooldownRemainingMs();
        if (cooldown > 0) {
            player.sendActionBar(Component.text("Whale Mode recharges in " + (cooldown + 999) / 1000 + " s", NamedTextColor.GRAY));
            return false;
        }
        long now = System.currentTimeMillis();
        long duration = config.whaleMode().durationSeconds() * 1000L;
        tracker.whaleModeUntil = now + duration;
        strikeLightning(player);
        applyHealth(player, effectivePower(player), true);
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            AttributeInstance attribute = player.getAttribute(Attribute.MAX_HEALTH);
            if (attribute != null && player.isOnline()) {
                player.setHealth(attribute.getValue());
            }
        });
        player.showTitle(Title.title(Component.empty(), Component.text("Whale Mode activated!", NamedTextColor.LIGHT_PURPLE),
                Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofMillis(500))));
        if (tracker.countdown != null) {
            tracker.countdown.cancel();
        }
        tracker.countdown = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            long remaining = tracker.whaleModeUntil - System.currentTimeMillis();
            if (remaining <= 0 || !player.isOnline()) {
                return; // tick() finishes it
            }
            player.sendExperienceChange(Math.max(0f, Math.min(1f, remaining / (float) duration)), (int) Math.ceil(remaining / 1000.0));
        }, 1, 1);
        return true;
    }

    private void strikeLightning(Player player) {
        WhaleConfig.Lightning mode = config.whaleMode().lightning();
        if (mode == WhaleConfig.Lightning.NONE) {
            return;
        }
        for (int i = 0; i < 3; i++) {
            Location at = player.getLocation().add((Math.random() * 2 - 1) * 4, 0, (Math.random() * 2 - 1) * 4);
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (mode == WhaleConfig.Lightning.REAL) {
                    at.getWorld().strikeLightning(at);
                } else {
                    at.getWorld().strikeLightningEffect(at);
                }
            }, i * 10L);
        }
    }

    private void endWhaleMode(Player player, Tracker tracker, boolean startCooldown) {
        tracker.whaleModeUntil = 0;
        if (tracker.countdown != null) {
            tracker.countdown.cancel();
            tracker.countdown = null;
        }
        if (startCooldown) {
            tracker.cooldownUntil = System.currentTimeMillis() + config.whaleMode().cooldownSeconds() * 1000L;
        }
        if (player.isOnline()) {
            player.sendExperienceChange(player.getExp(), player.getLevel());
            applyHealth(player, effectivePower(player), false);
        }
    }

    private void tick() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            Tracker tracker = trackers.get(player.getUniqueId());
            if (tracker != null && tracker.whaleModeUntil > 0 && !tracker.whaleMode()) {
                endWhaleMode(player, tracker, true);
                player.sendActionBar(Component.text("Whale Mode is over", NamedTextColor.GRAY));
            }
        }
    }

    // ---------------------------------------------------------------- events

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        refresh(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        Tracker tracker = trackers.remove(player.getUniqueId());
        if (tracker != null && tracker.countdown != null) {
            tracker.countdown.cancel();
        }
        AttributeInstance attribute = player.getAttribute(Attribute.MAX_HEALTH);
        if (attribute != null && attribute.getModifier(healthKey) != null) {
            attribute.removeModifier(healthKey); // never persist buffs into the player file
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !event.getAction().isRightClick() || !scepter.isScepter(event.getItem())) {
            return;
        }
        event.setCancelled(true);
        activateWhaleMode(event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) {
            return;
        }
        double power = effectivePower(attacker);
        if (power <= 0) {
            return;
        }
        event.setDamage(Math.max(1, event.getDamage()) * MoonPower.damageFactor(config, power));
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (scepter.isScepter(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getType() == InventoryType.CRAFTING) {
            return; // own inventory: shuffle freely
        }
        ItemStack current = event.getCurrentItem();
        ItemStack cursor = event.getCursor();
        if (scepter.isScepter(current) || scepter.isScepter(cursor)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        event.getDrops().removeIf(scepter::isScepter);
        Tracker tracker = trackers.get(event.getEntity().getUniqueId());
        if (tracker != null && tracker.whaleMode()) {
            endWhaleMode(event.getEntity(), tracker, true);
        }
    }

    private void runMain(Runnable action) {
        if (plugin.getServer().isPrimaryThread()) {
            action.run();
        } else {
            plugin.getServer().getScheduler().runTask(plugin, action);
        }
    }
}
