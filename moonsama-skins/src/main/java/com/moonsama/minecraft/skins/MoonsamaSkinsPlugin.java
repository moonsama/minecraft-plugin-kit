package com.moonsama.minecraft.skins;

import com.moonsama.minecraft.api.HoldingsSnapshot;
import com.moonsama.minecraft.api.MoonsamaService;
import com.moonsama.minecraft.api.event.PortalHoldingsLoadedEvent;
import com.moonsama.minecraft.api.event.PortalPlayerErasedEvent;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public final class MoonsamaSkinsPlugin extends JavaPlugin implements Listener, TabCompleter {
    private MoonsamaService moonsama;
    private SkinService skins;
    private NamespacedKey skinKey;
    private SkinCollections collections;

    @Override
    public void onEnable() {
        moonsama = getServer().getServicesManager().load(MoonsamaService.class);
        if (moonsama == null) {
            throw new IllegalStateException("MoonsamaCore service is unavailable");
        }
        saveDefaultConfig();
        collections = SkinCollections.from(getConfig());
        skinKey = new NamespacedKey(this, "skin");

        long started = System.nanoTime();
        SkinCatalog catalog = SkinCatalog.load(getClassLoader(), collections.order());
        for (String missing : catalog.missingCollections(collections.order())) {
            getLogger().warning("No bundled skins for collection '" + missing + "'; it will be skipped.");
        }
        getLogger().info(String.format(Locale.ROOT, "Loaded %,d signed skins for %s in %d ms",
                catalog.size(), catalog.collections(), (System.nanoTime() - started) / 1_000_000));
        if (!collections.uniform().isEmpty()) {
            getLogger().info("Uniform collections (one skin regardless of token): " + collections.uniform());
        }

        EquippedSkinStore store = new EquippedSkinStore(getDataFolder().toPath().resolve("equipped.json"));
        store.load();
        skins = new SkinService(this, moonsama, catalog, collections, store);

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getServicesManager().register(SkinService.class, skins, this, ServicePriority.Normal);
        var command = getCommand("skins");
        if (command != null) {
            command.setTabCompleter(this);
        }
    }

    @Override
    public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
    }

    // ---------------------------------------------------------------- commands

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            String @NotNull [] args
    ) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used by a player.");
            return true;
        }
        String sub = args.length == 0 ? "menu" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "menu" -> openMenu(player);
            case "reset", "off" -> {
                if (skins.reset(player)) {
                    player.sendRichMessage("<green>You are wearing your own skin again.</green>");
                } else {
                    player.sendRichMessage("<yellow>You were not wearing a Moonsama skin.</yellow>");
                }
            }
            case "status" -> status(player);
            case "wear" -> {
                String collection = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
                Optional<SignedSkin> skin;
                if (args.length == 2 && collections.isUniform(collection)) {
                    // Every token looks the same; any bundled entry will do.
                    skin = skins.catalog().first(collection);
                } else if (args.length == 3) {
                    skin = skins.catalog().find(collection, args[2]);
                } else {
                    player.sendRichMessage("<red>Usage: /" + label + " wear <collection> <id></red>");
                    return true;
                }
                if (skin.isEmpty()) {
                    player.sendRichMessage("<red>Unknown skin. Collections: " + String.join(", ", skins.catalog().collections()) + "</red>");
                    return true;
                }
                wear(player, skin.get().ref());
            }
            default -> player.sendRichMessage("<red>Usage: /" + label + " [wear <collection> <id>|reset|status]</red>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            String @NotNull [] args
    ) {
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            for (String option : List.of("wear", "reset", "status")) {
                if (option.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    options.add(option);
                }
            }
        } else if (args.length == 2 && "wear".equalsIgnoreCase(args[0])) {
            for (String collection : skins.catalog().collections()) {
                if (collection.startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    options.add(collection);
                }
            }
        }
        return options;
    }

    private void status(Player player) {
        Optional<SkinRef> equipped = skins.equipped(player.getUniqueId());
        if (equipped.isEmpty()) {
            player.sendRichMessage("<gray>You are wearing your own skin.</gray>");
        } else {
            player.sendRichMessage("<gray>You are wearing <white>" + displayName(equipped.get()) + "</white>.</gray>");
        }
    }

    private void wear(Player player, SkinRef ref) {
        player.sendRichMessage("<yellow>Checking your Portal holdings…</yellow>");
        skins.wear(player, ref).whenComplete((result, failure) -> runMain(() -> {
            if (failure != null || result == null) {
                player.sendRichMessage("<red>Could not change your skin right now. Try again in a moment.</red>");
                return;
            }
            switch (result) {
                case APPLIED -> player.sendRichMessage("<green>You are now wearing <white>" + displayName(ref) + "</white>.</green>");
                case NOT_LINKED -> player.sendRichMessage("<red>Link your Portal account first with <white>/moonsama link</white>.</red>");
                case NOT_OWNED -> player.sendRichMessage("<red>Your Portal account does not hold " + displayName(ref) + ".</red>");
                case UNKNOWN_SKIN -> player.sendRichMessage("<red>There is no skin for " + displayName(ref) + ".</red>");
                case UNAVAILABLE -> player.sendRichMessage("<red>Portal is unreachable right now. Try again in a moment.</red>");
            }
        }));
    }

    // ------------------------------------------------------------------- menu

    private void openMenu(Player player) {
        UUID mojangUuid = player.getUniqueId();
        moonsama.linkedPlayer(mojangUuid).thenCompose(linked -> {
            if (linked.isEmpty()) {
                return CompletableFuture.<SkinService.OwnedSkins>completedFuture(null);
            }
            return skins.ownedSkins(mojangUuid);
        }).whenComplete((owned, failure) -> runMain(() -> {
            if (!player.isOnline()) {
                return;
            }
            if (failure != null) {
                getLogger().log(Level.WARNING, "Could not load skins for " + player.getName(), failure);
                player.sendRichMessage("<red>Could not load your skins right now. Try again in a moment.</red>");
                return;
            }
            if (owned == null) {
                player.sendRichMessage("<red>Link your Portal account first with <white>/moonsama link</white>.</red>");
                return;
            }
            SkinMenu menu = new SkinMenu(skinKey, collections, owned, skins.equipped(mojangUuid).orElse(null));
            menu.open(player);
            if (owned.status() == HoldingsSnapshot.Status.UNKNOWN) {
                refreshAndReopen(player);
            }
        }));
    }

    private void refreshAndReopen(Player player) {
        moonsama.holdings(player.getUniqueId()).whenComplete((holdings, failure) -> runMain(() -> {
            if (failure != null) {
                player.sendRichMessage("<red>Could not refresh your holdings: " + safe(failure.getMessage()) + "</red>");
                return;
            }
            if (player.isOnline() && player.getOpenInventory().getTopInventory().getHolder(false) instanceof SkinMenu) {
                openMenu(player);
            }
        }));
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof SkinMenu menu)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || event.getClickedInventory() != event.getInventory()) {
            return;
        }
        int slot = event.getSlot();
        switch (slot) {
            case SkinMenu.SLOT_PREVIOUS -> menu.previousPage();
            case SkinMenu.SLOT_NEXT -> menu.nextPage();
            case SkinMenu.SLOT_REFRESH -> {
                player.sendRichMessage("<yellow>Refreshing your Portal holdings…</yellow>");
                refreshAndReopen(player);
            }
            case SkinMenu.SLOT_RESET -> {
                player.closeInventory();
                if (skins.reset(player)) {
                    player.sendRichMessage("<green>You are wearing your own skin again.</green>");
                }
            }
            default -> {
                ItemStack item = event.getCurrentItem();
                menu.skinOf(item).ifPresent(ref -> {
                    player.closeInventory();
                    wear(player, ref);
                });
            }
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder(false) instanceof SkinMenu) {
            event.setCancelled(true);
        }
    }

    // ----------------------------------------------------------------- events

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (getConfig().getBoolean("reapply-on-join", true)) {
            skins.reapply(event.getPlayer());
        }
    }

    @EventHandler
    public void onHoldingsLoaded(PortalHoldingsLoadedEvent event) {
        if (!getConfig().getBoolean("unequip-when-sold", true)) {
            return;
        }
        Player player = getServer().getPlayer(event.player().mojangUuid());
        if (player == null) {
            return;
        }
        Optional<SkinRef> worn = skins.equipped(player.getUniqueId());
        if (worn.isPresent() && skins.revalidate(player, event.holdings())) {
            player.sendRichMessage("<yellow>" + displayName(worn.get())
                    + " is no longer in your Portal account, so your own skin is back.</yellow>");
        }
    }

    @EventHandler
    public void onPlayerErased(PortalPlayerErasedEvent event) {
        Player player = getServer().getPlayer(event.mojangUuid());
        if (player != null) {
            skins.reset(player);
        } else {
            skins.forget(event.mojangUuid());
        }
    }

    // ---------------------------------------------------------------- helpers

    private String displayName(SkinRef ref) {
        return collections.displayName(ref);
    }

    private void runMain(Runnable action) {
        if (getServer().isPrimaryThread()) {
            action.run();
        } else {
            getServer().getScheduler().runTask(this, action);
        }
    }

    private static String safe(String value) {
        if (value == null) {
            return "unknown error";
        }
        return value.replace("<", "").replace(">", "");
    }
}
