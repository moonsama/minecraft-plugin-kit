package com.moonsama.minecraft.wardrobe;

import com.moonsama.minecraft.api.MoonsamaService;
import com.moonsama.minecraft.api.SkinSigner;
import com.moonsama.minecraft.api.event.PortalHoldingsLoadedEvent;
import com.moonsama.minecraft.api.event.PortalPlayerErasedEvent;
import com.moonsama.minecraft.compositor.CompositorData.SlotValue;
import com.moonsama.minecraft.compositor.SkinCompositor;
import com.moonsama.minecraft.skins.SkinRef;
import com.moonsama.minecraft.skins.SkinService;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public final class MoonsamaWardrobePlugin extends JavaPlugin implements Listener, TabCompleter {
    private MoonsamaService moonsama;
    private SkinService skins;
    private WardrobeService service;
    private ExecutorService renderExecutor;
    private NamespacedKey tagKey;
    private Map<String, String> collectionNames;
    private final Set<UUID> applying = new HashSet<>();

    @Override
    public void onEnable() {
        moonsama = getServer().getServicesManager().load(MoonsamaService.class);
        if (moonsama == null) {
            throw new IllegalStateException("MoonsamaCore service is unavailable");
        }
        skins = getServer().getServicesManager().load(SkinService.class);
        if (skins == null) {
            throw new IllegalStateException("MoonsamaSkins service is unavailable");
        }
        saveDefaultConfig();
        collectionNames = readCollectionNames();
        tagKey = new NamespacedKey(this, "menu_entry");

        SkinCompositor compositor = SkinCompositor.load(getClassLoader());
        List<String> collections = getConfig().getStringList("collections");
        Set<String> hidden = new HashSet<>(getConfig().getStringList("hidden-slots"));
        for (String collection : collections) {
            if (!compositor.supports(collection)) {
                getLogger().warning("Collection '" + collection + "' has no compositor data and cannot be customized.");
            }
        }

        WardrobeStore store = new WardrobeStore(getDataFolder().toPath().resolve("looks.json"));
        store.load();
        renderExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "moonsama-wardrobe-render");
            t.setDaemon(true);
            return t;
        });
        Unlocks.OutsidePortalPolicy outsidePortal = "free".equalsIgnoreCase(getConfig().getString("parts-outside-portal", "locked"))
                ? Unlocks.OutsidePortalPolicy.FREE : Unlocks.OutsidePortalPolicy.LOCKED;
        SigningBudget budget = new SigningBudget(
                java.time.Duration.ofSeconds(Math.max(0, getConfig().getLong("signing-budget.cooldown-seconds", 20))),
                getConfig().getInt("signing-budget.max-per-hour", 30));
        service = new WardrobeService(this, moonsama, skins, compositor, store,
                () -> getServer().getServicesManager().load(SkinSigner.class), renderExecutor, collections, hidden,
                outsidePortal, budget);

        getLogger().info(String.format(Locale.ROOT, "Wardrobe ready for %s; %d saved look(s); skin signing %s; costume-only parts %s; signing budget %s",
                service.collections(), store.size(), service.signingAvailable() ? "available" : "NOT configured (set MINESKIN_API_KEY)",
                outsidePortal == Unlocks.OutsidePortalPolicy.FREE ? "free for everyone" : "locked",
                budget.enabled()
                        ? budget.cooldown().toSeconds() + "s cooldown, " + (budget.perHour() > 0 ? budget.perHour() + "/hour" : "no hourly cap")
                        : "off"));

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getServicesManager().register(WardrobeService.class, service, this, ServicePriority.Normal);
        var command = getCommand("wardrobe");
        if (command != null) {
            command.setTabCompleter(this);
        }
    }

    @Override
    public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
        if (renderExecutor != null) {
            renderExecutor.shutdown();
            try {
                renderExecutor.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private Map<String, String> readCollectionNames() {
        Map<String, String> names = new LinkedHashMap<>();
        ConfigurationSection section = getConfig().getConfigurationSection("collection-names");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                names.put(key, section.getString(key, key));
            }
        }
        return names;
    }

    // ---------------------------------------------------------------- commands

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used by a player.");
            return true;
        }
        if (args.length == 0) {
            openBases(player);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "status" -> status(player);
            case "reset" -> {
                if (args.length >= 3) {
                    parseRef(args[1], args[2]).ifPresentOrElse(ref -> {
                        service.resetLook(player.getUniqueId(), ref);
                        player.sendRichMessage("<green>Look for " + display(ref) + " reset to the NFT's own parts.</green>");
                    }, () -> player.sendRichMessage("<red>Usage: /" + label + " reset <collection> <id></red>"));
                } else {
                    player.sendRichMessage("<red>Usage: /" + label + " reset <collection> <id></red>");
                }
            }
            default -> {
                if (args.length >= 2) {
                    Optional<SkinRef> ref = parseRef(args[0], args[1]);
                    if (ref.isEmpty() || !service.canCustomize(ref.get())) {
                        player.sendRichMessage("<red>Unknown or non-customizable NFT. Collections: "
                                + String.join(", ", service.collections()) + "</red>");
                    } else {
                        openSlots(player, ref.get());
                    }
                } else {
                    player.sendRichMessage("<red>Usage: /" + label + " [<collection> <id>|reset <collection> <id>|status]</red>");
                }
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, String @NotNull [] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String option : List.of("status", "reset")) {
                if (option.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    out.add(option);
                }
            }
            for (String collection : service.collections()) {
                if (collection.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    out.add(collection);
                }
            }
        } else if (args.length == 2 && "reset".equalsIgnoreCase(args[0])) {
            out.addAll(service.collections());
        }
        return out;
    }

    private void status(Player player) {
        UUID uuid = player.getUniqueId();
        Optional<SkinRef> worn = skins.equipped(uuid);
        if (worn.isEmpty()) {
            player.sendRichMessage("<gray>You are wearing your own Minecraft skin.</gray>");
        } else {
            player.sendRichMessage("<gray>Wearing " + display(worn.get())
                    + (skins.wearsCustom(uuid) ? " with a custom look" : " as minted") + ".</gray>");
        }
        player.sendRichMessage(service.signingAvailable()
                ? "<gray>Skin signing: <green>available</green></gray>"
                : "<gray>Skin signing: <gold>not configured</gold> (looks can be saved but not worn)</gray>");
    }

    // ---------------------------------------------------------------- menu flow

    private WardrobeMenu menuOf(Player player) {
        if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof WardrobeMenu menu) {
            return menu;
        }
        return new WardrobeMenu(service, tagKey, collectionNames, player.getUniqueId());
    }

    private void openBases(Player player) {
        WardrobeMenu menu = menuOf(player);
        service.ownedBases(player.getUniqueId()).whenComplete((owned, failure) -> runMain(() -> {
            if (!player.isOnline()) {
                return;
            }
            if (failure != null) {
                getLogger().log(Level.WARNING, "Could not load holdings for " + player.getName(), failure);
                player.sendRichMessage("<red>Could not load your Portal holdings right now.</red>");
                return;
            }
            menu.showBases(player, owned);
        }));
    }

    private void openSlots(Player player, SkinRef ref) {
        menuOf(player).showSlots(player, ref);
    }

    private void openParts(Player player, WardrobeMenu menu, SkinRef ref, String slot) {
        service.options(player.getUniqueId(), ref, slot).whenComplete((options, failure) -> runMain(() -> {
            if (!player.isOnline()) {
                return;
            }
            if (failure != null) {
                getLogger().log(Level.WARNING, "Could not load parts for " + player.getName(), failure);
                player.sendRichMessage("<red>Could not load your Portal holdings right now.</red>");
                return;
            }
            menu.showParts(player, ref, slot, options);
        }));
    }

    private void applyLook(Player player, WardrobeMenu menu, SkinRef ref) {
        UUID uuid = player.getUniqueId();
        if (!applying.add(uuid)) {
            player.sendRichMessage("<yellow>Still working on your previous look…</yellow>");
            return;
        }
        boolean custom = !service.look(uuid, ref).isEmpty();
        if (custom && !service.signingAvailable()) {
            applying.remove(uuid);
            player.sendRichMessage("<gold>Your look is saved, but this server cannot sign custom skins yet "
                    + "(MINESKIN_API_KEY is not set on MoonsamaCore).</gold>");
            return;
        }
        player.closeInventory();
        player.sendRichMessage(custom
                ? "<yellow>Composing and signing your look for " + display(ref) + "…</yellow>"
                : "<yellow>Wearing " + display(ref) + "…</yellow>");
        service.apply(player, ref).whenComplete((outcome, failure) -> runMain(() -> {
            applying.remove(uuid);
            if (!player.isOnline()) {
                return;
            }
            if (failure != null) {
                getLogger().log(Level.WARNING, "Wardrobe apply failed for " + player.getName(), failure);
                player.sendRichMessage("<red>Something went wrong while applying your look.</red>");
                return;
            }
            player.sendRichMessage(switch (outcome.status()) {
                case APPLIED -> "<green>You are now wearing your " + display(ref) + " look.</green>";
                case NOT_LINKED -> "<red>Link your Minecraft account in Moonsama Portal first.</red>";
                case NOT_OWNED -> "<red>You no longer hold " + display(ref) + ".</red>";
                case NOT_UNLOCKED -> "<red>Some parts are no longer unlocked: " + safe(outcome.detail())
                        + ". Open the wardrobe to adjust them.</red>";
                case UNKNOWN_SKIN -> "<red>That NFT cannot be customized.</red>";
                case SIGNING_UNAVAILABLE -> "<gold>Skin signing is not configured on this server.</gold>";
                case SIGNING_FAILED -> "<red>Skin signing failed: " + safe(outcome.detail()) + ". Try again in a minute.</red>";
                case RATE_LIMITED -> "<gold>Slow down - new looks are limited. Try again in " + safe(outcome.detail())
                        + "s (looks you have worn before are always instant).</gold>";
                case UNAVAILABLE -> "<red>Could not apply your look right now. Please try again later.</red>";
            });
        }));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof WardrobeMenu menu)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getClickedInventory() != event.getInventory()) {
            return;
        }
        int rawSlot = event.getSlot();
        UUID uuid = player.getUniqueId();
        switch (menu.view()) {
            case BASES -> menu.actionOf(event.getCurrentItem())
                    .filter(a -> a.startsWith("base:"))
                    .flatMap(a -> WardrobeMenu.parseRef(a.substring("base:".length())))
                    .ifPresent(ref -> menu.showSlots(player, ref));
            case SLOTS -> {
                SkinRef ref = menu.base();
                switch (rawSlot) {
                    case WardrobeMenu.SLOT_BACK -> openBases(player);
                    case WardrobeMenu.SLOT_RESET -> {
                        service.resetLook(uuid, ref);
                        menu.showSlots(player, ref);
                    }
                    case WardrobeMenu.SLOT_APPLY -> applyLook(player, menu, ref);
                    default -> menu.actionOf(event.getCurrentItem())
                            .filter(a -> a.startsWith("slot:"))
                            .ifPresent(a -> openParts(player, menu, ref, a.substring("slot:".length())));
                }
            }
            case PARTS -> {
                SkinRef ref = menu.base();
                String slot = menu.slot();
                switch (rawSlot) {
                    case WardrobeMenu.SLOT_BACK -> menu.showSlots(player, ref);
                    case WardrobeMenu.SLOT_PREVIOUS -> menu.previousPage(player);
                    case WardrobeMenu.SLOT_NEXT -> menu.nextPage(player);
                    default -> menu.actionOf(event.getCurrentItem()).ifPresent(action -> {
                        Look look = service.look(uuid, ref);
                        if ("revert".equals(action)) {
                            look.revert(slot);
                        } else if ("clear".equals(action)) {
                            if (service.isRequired(ref.collection(), slot)) {
                                return;
                            }
                            look.clear(slot);
                        } else if (action.startsWith("part:")) {
                            Optional<SlotValue> chosen = parseIndex(action.substring("part:".length())).flatMap(menu::option);
                            if (chosen.isEmpty()) {
                                return;
                            }
                            Optional<SlotValue> own = service.defaultPart(ref, slot);
                            if (own.isPresent() && own.get().asset().equals(chosen.get().asset())) {
                                look.revert(slot);
                            } else {
                                look.choose(slot, chosen.get());
                            }
                        } else {
                            return;
                        }
                        service.saveLook(uuid, ref, look);
                        menu.showSlots(player, ref);
                    });
                }
            }
        }
    }

    @EventHandler
    public void onMenuDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder(false) instanceof WardrobeMenu) {
            event.setCancelled(true);
        }
    }

    // ---------------------------------------------------------------- Portal events

    @EventHandler
    public void onHoldingsLoaded(PortalHoldingsLoadedEvent event) {
        UUID uuid = event.player().mojangUuid();
        Optional<SkinRef> worn = skins.equipped(uuid);
        if (worn.isEmpty() || !skins.wearsCustom(uuid) || !service.canCustomize(worn.get())) {
            return;
        }
        if (service.revalidate(uuid, worn.get(), event.holdings())) {
            Player player = getServer().getPlayer(uuid);
            if (player != null) {
                player.sendRichMessage("<yellow>Some parts of your custom look are no longer unlocked; "
                        + "open /wardrobe to refresh it.</yellow>");
            }
        }
    }

    @EventHandler
    public void onPlayerErased(PortalPlayerErasedEvent event) {
        service.forget(event.mojangUuid());
    }

    // ---------------------------------------------------------------- helpers

    private Optional<SkinRef> parseRef(String collection, String tokenId) {
        try {
            return Optional.of(new SkinRef(collection.toLowerCase(Locale.ROOT), Long.parseLong(tokenId)));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    private static Optional<Integer> parseIndex(String value) {
        try {
            return Optional.of(Integer.parseInt(value));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    private String display(SkinRef ref) {
        return collectionNames.getOrDefault(ref.collection(), ref.collection()) + " #" + ref.tokenId();
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
