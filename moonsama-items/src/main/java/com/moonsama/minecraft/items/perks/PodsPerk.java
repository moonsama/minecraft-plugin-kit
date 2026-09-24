package com.moonsama.minecraft.items.perks;

import com.moonsama.minecraft.items.CosmeticItems;
import com.moonsama.minecraft.items.Offhand;
import com.moonsama.minecraft.items.perks.PerkSettings.Song;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Pods: pick a song and throw a jam — a 10-block circle where the DJ gets Haste, Strength and
 * Resistance II for the length of the track. 7 minute cooldown, remembered per player even when
 * the Pods are unequipped (legacy rule).
 */
public final class PodsPerk extends Perk implements Listener {
    static final long COOLDOWN_MS = 7 * 60 * 1000;
    static final int RADIUS = 10;
    static final int CHARGING_MODEL = 57;    // iron sword
    static final int SONG_MODEL = 112;       // sugar, song menu entry
    static final int GHOST_MODEL = 113;      // sugar, floating centre piece
    static final int BUFF_PERIOD_TICKS = 40;
    static final int BUFF_DURATION_TICKS = 45;
    static final Color BOUNDARY = Color.fromRGB(210, 88, 189);
    private static final Component MENU_TITLE = Component.text("Song Selection");

    /** Cooldown end per player, persists across equips for the lifetime of the server. */
    private final Map<UUID, Long> cooldownUntil = new HashMap<>();
    private final Map<UUID, Jam> jams = new HashMap<>();

    public PodsPerk(PerkContext ctx, Offhand offhand) {
        super(ctx, offhand);
        ctx.plugin().getServer().getPluginManager().registerEvents(this, ctx.plugin());
    }

    private boolean charged(Player player) {
        Long until = cooldownUntil.get(player.getUniqueId());
        return until == null || System.currentTimeMillis() >= until;
    }

    private long remainingMs(Player player) {
        Long until = cooldownUntil.get(player.getUniqueId());
        return until == null ? 0 : Math.max(0, until - System.currentTimeMillis());
    }

    @Override
    public void onEquip(Player player, PerkState state) {
        updateForm(player);
    }

    @Override
    public void tick(Player player, PerkState state) {
        updateForm(player);
    }

    private void updateForm(Player player) {
        if (charged(player)) {
            ctx.resetForm(player, offhand);
        } else {
            ctx.setForm(player, offhand, Material.IRON_SWORD, CHARGING_MODEL, List.of("Charging"));
            ctx.showCooldown(player, offhand, (int) Math.ceil(remainingMs(player) / 50.0));
        }
    }

    @Override
    public void onInteract(Player player, PlayerInteractEvent event, PerkState state) {
        if (!event.getAction().isRightClick()) {
            return;
        }
        event.setCancelled(true);
        event.setUseInteractedBlock(Event.Result.DENY);
        if (!charged(player) || jams.containsKey(player.getUniqueId())) {
            return;
        }
        if (nearbyJam(player.getLocation()).isPresent()) {
            ctx.actionBar(player, "Cannot play music right now.", NamedTextColor.RED);
            return;
        }
        openMenu(player);
    }

    // ------------------------------------------------------------------ menu

    /** Marker so the click listener recognises our inventory. */
    private final class SongMenu implements InventoryHolder {
        private final List<Song> songs;
        private Inventory inventory;

        SongMenu(List<Song> songs) {
            this.songs = songs;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private void openMenu(Player player) {
        List<Song> songs = ctx.settings().podSongs();
        if (songs.isEmpty()) {
            ctx.actionBar(player, "No songs are configured for the Pods", NamedTextColor.RED);
            return;
        }
        int rows = Math.max(1, Math.min(6, (songs.size() + 8) / 9));
        SongMenu holder = new SongMenu(songs);
        Inventory inventory = ctx.plugin().getServer().createInventory(holder, rows * 9, MENU_TITLE);
        holder.inventory = inventory;
        for (int i = 0; i < songs.size() && i < rows * 9; i++) {
            Song song = songs.get(i);
            ItemStack disc = new ItemStack(Material.SUGAR);
            ItemMeta meta = disc.getItemMeta();
            CosmeticItems.setModelData(meta, SONG_MODEL);
            meta.displayName(Component.text(song.name(), NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    Component.text("by " + song.artist(), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.text("Click to play", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)));
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.values());
            disc.setItemMeta(meta);
            inventory.setItem(i, disc);
        }
        player.openInventory(inventory);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof SongMenu menu)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getClickedInventory() != event.getInventory()) {
            return;
        }
        int slot = event.getSlot();
        if (slot < 0 || slot >= menu.songs.size()) {
            return;
        }
        Song song = menu.songs.get(slot);
        ctx.later(player::closeInventory, 1);
        play(player, song);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMenuDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder(false) instanceof SongMenu) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------- jam

    private void play(Player player, Song song) {
        if (!ctx.holds(player, offhand) || !charged(player) || jams.containsKey(player.getUniqueId())) {
            return;
        }
        if (nearbyJam(player.getLocation()).isPresent()) {
            ctx.actionBar(player, "Cannot play music right now.", NamedTextColor.RED);
            return;
        }
        cooldownUntil.put(player.getUniqueId(), System.currentTimeMillis() + COOLDOWN_MS);
        Jam jam = new Jam(player, song, player.getLocation().getBlock().getLocation());
        jams.put(player.getUniqueId(), jam);
        jam.start();
        updateForm(player);
    }

    private Optional<Jam> nearbyJam(Location location) {
        double limit = RADIUS * 2.2;
        for (Jam jam : jams.values()) {
            if (jam.center.getWorld().equals(location.getWorld()) && jam.center.distanceSquared(location) <= limit * limit) {
                return Optional.of(jam);
            }
        }
        return Optional.empty();
    }

    private final class Jam {
        private final Player owner;
        private final Song song;
        private final Location center;
        private final List<Item> props = new ArrayList<>();
        private final BossBar bar;
        private long endsAt;
        private int buffTimeout;
        private BukkitTask task;

        Jam(Player owner, Song song, Location center) {
            this.owner = owner;
            this.song = song;
            this.center = center;
            this.bar = BossBar.bossBar(Component.text(song.name() + " — " + song.artist(), NamedTextColor.LIGHT_PURPLE),
                    1f, BossBar.Color.PINK, BossBar.Overlay.PROGRESS);
        }

        void start() {
            endsAt = System.currentTimeMillis() + Math.max(1000, song.lengthMs());
            owner.showTitle(Title.title(Component.empty(), Component.text("You are pumped!", NamedTextColor.LIGHT_PURPLE)));
            ctx.actionBar(owner, "Playing " + song.name() + " by " + song.artist(), NamedTextColor.WHITE);
            owner.showBossBar(bar);
            World world = center.getWorld();
            if (!song.sound().isBlank()) {
                world.playSound(center, song.sound(), SoundCategory.RECORDS, 1f, 1f);
            }
            ItemStack ghost = new ItemStack(Material.SUGAR);
            ItemMeta meta = ghost.getItemMeta();
            CosmeticItems.setModelData(meta, GHOST_MODEL);
            ghost.setItemMeta(meta);
            Item item = world.dropItem(center.clone().add(0.5, 3, 0.5), ghost);
            item.setGravity(false);
            item.setPickupDelay(Integer.MAX_VALUE);
            item.setInvulnerable(true);
            item.setUnlimitedLifetime(true);
            item.setCanMobPickup(false);
            item.setCanPlayerPickup(false);
            item.setVelocity(new Vector(0, 0, 0));
            props.add(item);
            buffTimeout = 0;
            task = ctx.every(this::run, 1, 1);
        }

        private void run() {
            long now = System.currentTimeMillis();
            if (now >= endsAt || !owner.isOnline()) {
                stop();
                return;
            }
            long remaining = endsAt - now;
            bar.progress(Math.max(0f, Math.min(1f, remaining / (float) Math.max(1, song.lengthMs()))));
            bar.name(Component.text(song.name() + " — " + song.artist() + "  " + PerkContext.formatDuration(remaining), NamedTextColor.LIGHT_PURPLE));
            if (--buffTimeout <= 0) {
                buffTimeout = BUFF_PERIOD_TICKS;
                if (owner.getWorld().equals(center.getWorld()) && owner.getLocation().distanceSquared(center) <= RADIUS * RADIUS) {
                    owner.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, BUFF_DURATION_TICKS, 1, true, true));
                    owner.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, BUFF_DURATION_TICKS, 1, true, true));
                    owner.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, BUFF_DURATION_TICKS, 1, true, true));
                }
            }
            showBoundary(remaining / 50);
        }

        private void showBoundary(long remainingTicks) {
            World world = center.getWorld();
            double cx = center.getX() + 0.5;
            double cy = center.getY() + 1;
            double cz = center.getZ() + 0.5;
            Particle.DustOptions dust = new Particle.DustOptions(BOUNDARY, 1);
            for (double angle = 0; angle < Math.PI * 2; angle += Math.PI / 16) {
                double x = RADIUS * Math.cos(angle + remainingTicks * 0.001);
                double z = RADIUS * Math.sin(angle + remainingTicks * 0.001);
                world.spawnParticle(Particle.DUST, cx + x, cy, cz + z, 1, dust);
            }
        }

        void stop() {
            if (task != null) {
                task.cancel();
                task = null;
            }
            jams.remove(owner.getUniqueId());
            owner.hideBossBar(bar);
            if (!song.sound().isBlank()) {
                for (Player nearby : center.getWorld().getNearbyPlayers(center, RADIUS * 4)) {
                    nearby.stopSound(song.sound(), SoundCategory.RECORDS);
                }
                owner.stopSound(song.sound(), SoundCategory.RECORDS);
            }
            for (Item prop : props) {
                prop.getWorld().spawnParticle(Particle.SMOKE, prop.getLocation().add(0, 1, 0), 8, 0.2, 0.2, 0.2, 0.01);
                prop.remove();
            }
            props.clear();
        }
    }

    @Override
    public void onDeath(Player player, PerkState state) {
        Jam jam = jams.get(player.getUniqueId());
        if (jam != null) {
            jam.stop();
        }
    }

    @Override
    public void onUnequip(Player player, PerkState state) {
        // The jam keeps playing (legacy), only the item look is restored.
        ctx.resetForm(player, offhand);
    }

    /** Stops every running jam; call on plugin disable. */
    public void stopAll() {
        for (Jam jam : List.copyOf(jams.values())) {
            jam.stop();
        }
    }
}
