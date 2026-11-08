package com.moonsama.minecraft.items;

import com.moonsama.minecraft.api.HoldingsSnapshot;
import com.moonsama.minecraft.api.MoonsamaService;
import com.moonsama.minecraft.api.event.PortalHoldingsLoadedEvent;
import com.moonsama.minecraft.api.event.PortalPlayerErasedEvent;
import com.moonsama.minecraft.items.perks.PerkManager;
import com.moonsama.minecraft.items.perks.PerkSettings;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.plugin.Plugin;
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
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public final class MoonsamaItemsPlugin extends JavaPlugin implements Listener, TabCompleter {
    private MoonsamaService moonsama;
    private ItemsService service;
    private SkinHatBridge hats;
    private PerkManager perks;
    private NamespacedKey entryKey;
    private Map<String, String> collectionNames;
    // Bukkit inventory events are delivered on the main thread.
    private final Set<UUID> enderChestView = new HashSet<>();

    @Override
    public void onEnable() {
        moonsama = getServer().getServicesManager().load(MoonsamaService.class);
        if (moonsama == null) {
            throw new IllegalStateException("MoonsamaCore service is unavailable");
        }
        saveDefaultConfig();
        collectionNames = readCollectionNames();
        entryKey = new NamespacedKey(this, "menu_entry");

        ItemsCatalog catalog = ItemsCatalog.load(getClassLoader());
        for (String warning : catalog.warnings()) {
            getLogger().warning(warning);
        }
        getLogger().info(String.format(Locale.ROOT, "Loaded %d item skins, %d off-hands and %d hat rules",
                catalog.itemSkins().size(), catalog.offhands().size(), catalog.hats().rules().size()));

        EquippedOffhandStore store = new EquippedOffhandStore(getDataFolder().toPath().resolve("offhands.json"));
        store.load();
        service = new ItemsService(this, moonsama, catalog, new CosmeticItems(this), store);

        getServer().getPluginManager().registerEvents(this, this);
        if (getConfig().getBoolean("hats.enabled", true)) {
            hookSkins();
        }
        PerkSettings perkSettings = PerkSettings.from(getConfig().getConfigurationSection("offhands.perks"));
        if (offhandsEnabled() && perkSettings.enabled()) {
            perks = new PerkManager(this, catalog, service.items(), perkSettings);
            getServer().getPluginManager().registerEvents(perks, this);
            perks.start();
            getLogger().info("Off-hand perks enabled for " + perks.registry().all().size() + " off-hands");
        } else {
            getLogger().info("Off-hand perks are disabled; off-hands are purely cosmetic.");
        }
        getServer().getServicesManager().register(ItemsService.class, service, this, ServicePriority.Normal);
        var command = getCommand("items");
        if (command != null) {
            command.setTabCompleter(this);
        }
    }

    private void hookSkins() {
        Plugin skinsPlugin = getServer().getPluginManager().getPlugin("MoonsamaSkins");
        if (skinsPlugin == null || !skinsPlugin.isEnabled()) {
            getLogger().info("MoonsamaSkins is not installed; hats are disabled.");
            return;
        }
        hats = SkinHatBridge.create(getServer(), service).orElse(null);
        if (hats == null) {
            getLogger().warning("MoonsamaSkins is present but exposes no SkinService; hats are disabled.");
            return;
        }
        getServer().getPluginManager().registerEvents(hats, this);
    }

    @Override
    public void onDisable() {
        if (perks != null) {
            perks.stop();
            perks = null;
        }
        getServer().getServicesManager().unregisterAll(this);
    }

    /** Active off-hand perks, or empty when they are disabled by config. */
    public Optional<PerkManager> perks() {
        return Optional.ofNullable(perks);
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

    private boolean skinsEnabled() {
        return getConfig().getBoolean("item-skins.enabled", true);
    }

    private boolean offhandsEnabled() {
        return getConfig().getBoolean("offhands.enabled", true);
    }

    // ---------------------------------------------------------------- commands

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used by a player.");
            return true;
        }
        String sub = args.length == 0 ? "menu" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "menu" -> openMenu(player);
            case "list" -> list(player);
            case "status" -> status(player);
            case "skin" -> {
                if (!skinsEnabled()) {
                    player.sendRichMessage("<red>Item skins are disabled on this server.</red>");
                } else if (args.length < 2) {
                    player.sendRichMessage("<red>Usage: /" + label + " skin <id>|clear</red>");
                } else if ("clear".equalsIgnoreCase(args[1]) || "none".equalsIgnoreCase(args[1])) {
                    player.sendRichMessage(service.clearHeldSkin(player)
                            ? "<green>Skin removed from the item in your hand.</green>"
                            : "<yellow>The item in your hand has no Moonsama skin.</yellow>");
                } else {
                    Optional<ItemSkin> skin = service.catalog().itemSkin(args[1]);
                    if (skin.isEmpty()) {
                        player.sendRichMessage("<red>Unknown item skin. See /" + label + " list.</red>");
                    } else {
                        applySkin(player, skin.get());
                    }
                }
            }
            case "offhand" -> {
                if (!offhandsEnabled()) {
                    player.sendRichMessage("<red>Off-hand cosmetics are disabled on this server.</red>");
                } else if (args.length < 2) {
                    player.sendRichMessage("<red>Usage: /" + label + " offhand <id>|none</red>");
                } else if ("none".equalsIgnoreCase(args[1]) || "clear".equalsIgnoreCase(args[1])) {
                    player.sendRichMessage(service.removeOffhand(player)
                            ? "<green>Off-hand cosmetic removed.</green>"
                            : "<yellow>You had no off-hand cosmetic equipped.</yellow>");
                } else {
                    Optional<Offhand> offhand = service.catalog().offhand(args[1]);
                    if (offhand.isEmpty()) {
                        player.sendRichMessage("<red>Unknown off-hand. See /" + label + " list.</red>");
                    } else {
                        equipOffhand(player, offhand.get());
                    }
                }
            }
            default -> player.sendRichMessage("<red>Usage: /" + label
                    + " [skin <id>|skin clear|offhand <id>|offhand none|list|status]</red>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, String @NotNull [] args) {
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            for (String option : List.of("skin", "offhand", "list", "status")) {
                if (option.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    options.add(option);
                }
            }
        } else if (args.length == 2) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            if ("skin".equalsIgnoreCase(args[0])) {
                options.add("clear");
                service.catalog().itemSkins().keySet().forEach(options::add);
            } else if ("offhand".equalsIgnoreCase(args[0])) {
                options.add("none");
                service.catalog().offhands().keySet().forEach(options::add);
            }
            options.removeIf(option -> !option.startsWith(prefix) && !option.substring(option.indexOf(':') + 1).startsWith(prefix));
        }
        return options;
    }

    private void list(Player player) {
        service.owned(player.getUniqueId()).whenComplete((owned, failure) -> runMain(() -> {
            if (failure != null || owned == null) {
                player.sendRichMessage("<red>Could not load your holdings right now.</red>");
                return;
            }
            if (owned.skins().isEmpty() && owned.offhands().isEmpty()) {
                player.sendRichMessage("<gray>You own no Moonsama item cosmetics (holdings: "
                        + owned.status().name().toLowerCase(Locale.ROOT) + ").</gray>");
                return;
            }
            if (!owned.skins().isEmpty()) {
                player.sendRichMessage("<gold>Item skins:</gold>");
                for (ItemSkin skin : owned.skins()) {
                    player.sendRichMessage("<gray> - <white>" + skin.identifier() + "</white> " + skin.name() + "</gray>");
                }
            }
            if (!owned.offhands().isEmpty()) {
                player.sendRichMessage("<gold>Off-hands:</gold>");
                for (Offhand offhand : owned.offhands()) {
                    player.sendRichMessage("<gray> - <white>" + offhand.id() + "</white> " + offhand.name() + "</gray>");
                }
            }
        }));
    }

    private void status(Player player) {
        Optional<String> offhand = service.equippedOffhand(player.getUniqueId());
        Optional<String> heldSkin = service.items().skinOf(player.getInventory().getItemInMainHand());
        player.sendRichMessage("<gray>Off-hand: <white>" + offhand.orElse("none") + "</white></gray>");
        player.sendRichMessage("<gray>Skin on held item: <white>" + heldSkin.orElse("none") + "</white></gray>");
        player.sendRichMessage("<gray>Hat: <white>"
                + (service.items().isHat(player.getInventory().getHelmet()) ? "Moonsama hat" : "none") + "</white></gray>");
    }

    private void applySkin(Player player, ItemSkin skin) {
        service.applySkin(player, skin).whenComplete((result, failure) -> runMain(() -> {
            if (failure != null || result == null) {
                player.sendRichMessage("<red>Could not apply the skin right now. Try again in a moment.</red>");
                return;
            }
            switch (result) {
                case OK -> player.sendRichMessage("<green>Applied <white>" + skin.name() + "</white> to the item in your hand.</green>");
                case WRONG_ITEM -> player.sendRichMessage("<red>Hold a matching item first: " + skin.name() + " fits "
                        + skin.materials().size() + " kinds of " + skin.group() + ".</red>");
                default -> explain(player, result, skin.name());
            }
        }));
    }

    private void equipOffhand(Player player, Offhand offhand) {
        service.equipOffhand(player, offhand).whenComplete((result, failure) -> runMain(() -> {
            if (failure != null || result == null) {
                player.sendRichMessage("<red>Could not equip the off-hand right now. Try again in a moment.</red>");
                return;
            }
            switch (result) {
                case OK -> player.sendRichMessage("<green>You are now holding <white>" + offhand.name() + "</white>.</green>");
                case INVENTORY_FULL -> player.sendRichMessage("<red>Free a slot first so your current off-hand item can be moved.</red>");
                default -> explain(player, result, offhand.name());
            }
        }));
    }

    private void explain(Player player, ItemsService.Result result, String name) {
        switch (result) {
            case NOT_LINKED -> player.sendRichMessage("<red>Link your Portal account first with <white>/moonsama link</white>.</red>");
            case NOT_OWNED -> player.sendRichMessage("<red>Your Portal account does not unlock " + name + ".</red>");
            case UNAVAILABLE -> player.sendRichMessage("<red>Portal is unreachable right now. Try again in a moment.</red>");
            default -> player.sendRichMessage("<red>Could not apply " + name + ".</red>");
        }
    }

    // ------------------------------------------------------------------- menu

    private void openMenu(Player player) {
        UUID mojangUuid = player.getUniqueId();
        moonsama.linkedPlayer(mojangUuid).thenCompose(linked -> {
            if (linked.isEmpty()) {
                return CompletableFuture.<ItemsService.Owned>completedFuture(null);
            }
            return service.owned(mojangUuid);
        }).whenComplete((owned, failure) -> runMain(() -> {
            if (!player.isOnline()) {
                return;
            }
            if (failure != null) {
                getLogger().log(Level.WARNING, "Could not load cosmetics for " + player.getName(), failure);
                player.sendRichMessage("<red>Could not load your cosmetics right now. Try again in a moment.</red>");
                return;
            }
            if (owned == null) {
                player.sendRichMessage("<red>Link your Portal account first with <white>/moonsama link</white>.</red>");
                return;
            }
            ItemsService.Owned filtered = new ItemsService.Owned(owned.status(),
                    skinsEnabled() ? owned.skins() : List.of(),
                    offhandsEnabled() ? owned.offhands() : List.of());
            String equipped = service.equippedOffhand(mojangUuid).flatMap(service.catalog()::offhand)
                    .map(Offhand::name).orElse(null);
            new ItemsMenu(entryKey, service, collectionNames, filtered, equipped).open(player);
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
            if (player.isOnline() && player.getOpenInventory().getTopInventory().getHolder(false) instanceof ItemsMenu) {
                openMenu(player);
            }
        }));
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof ItemsMenu menu)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getClickedInventory() != event.getInventory()) {
            return;
        }
        switch (event.getSlot()) {
            case ItemsMenu.SLOT_PREVIOUS -> menu.previousPage();
            case ItemsMenu.SLOT_NEXT -> menu.nextPage();
            case ItemsMenu.SLOT_REFRESH -> {
                player.sendRichMessage("<yellow>Refreshing your Portal holdings…</yellow>");
                refreshAndReopen(player);
            }
            case ItemsMenu.SLOT_CLEAR_SKIN -> {
                player.closeInventory();
                player.sendRichMessage(service.clearHeldSkin(player)
                        ? "<green>Skin removed from the item in your hand.</green>"
                        : "<yellow>The item in your hand has no Moonsama skin.</yellow>");
            }
            case ItemsMenu.SLOT_REMOVE_OFFHAND -> {
                player.closeInventory();
                player.sendRichMessage(service.removeOffhand(player)
                        ? "<green>Off-hand cosmetic removed.</green>"
                        : "<yellow>You had no off-hand cosmetic equipped.</yellow>");
            }
            default -> menu.entryOf(event.getCurrentItem()).ifPresent(entry -> {
                player.closeInventory();
                switch (entry) {
                    case ItemsMenu.SkinEntry s -> applySkin(player, s.skin());
                    case ItemsMenu.OffhandEntry o -> equipOffhand(player, o.offhand());
                }
            });
        }
    }

    @EventHandler
    public void onMenuDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder(false) instanceof ItemsMenu) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------- cosmetic item protection

    /** Hats remain fixed; off-hands may move only within safe player-inventory slots. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCosmeticClick(InventoryClickEvent event) {
        if (event.getInventory().getHolder(false) instanceof ItemsMenu) {
            return;
        }
        CosmeticItems items = service.items();
        ItemStack current = event.getCurrentItem();
        ItemStack cursor = event.getCursor();
        int hotbarButton = event.getHotbarButton();
        ItemStack hotbar = hotbarButton >= 0 && event.getWhoClicked() instanceof Player player
                ? player.getInventory().getItem(hotbarButton)
                : null;
        boolean clickedTop = event.getClickedInventory() != event.getView().getBottomInventory();
        boolean taintedCurrentBundle = bundleHoldsManaged(current, items);
        boolean taintedCursorBundle = bundleHoldsManaged(cursor, items);
        if (isBundleRetrieval(event.getAction())
                && taintedCurrentBundle) {
            // Refuse retrieval from bundles containing managed copies to prevent a second live cosmetic.
            event.setCancelled(true);
            return;
        }
        if ((clickedTop && (taintedCurrentBundle || taintedCursorBundle))
                || (event.getAction() == InventoryAction.CLONE_STACK && taintedCurrentBundle)
                || (isDropAction(event.getAction()) && (taintedCurrentBundle || taintedCursorBundle))
                || (event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY && taintedCurrentBundle)) {
            // Legacy-world taint is contained at every movement boundary: retrieval, cloning, transfer, and dropping
            // are refused, so contents survive on the owner. Ownership-loss sweeps do not inspect bundle contents;
            // a stale tainted bundle can therefore remain as an acceptable locked curiosity.
            event.setCancelled(true);
            return;
        }
        boolean managedCurrent = items.isManagedCosmetic(current);
        boolean managedCursor = items.isManagedCosmetic(cursor);
        boolean topIsOwnCrafting = event.getView().getTopInventory() instanceof CraftingInventory
                && event.getView().getTopInventory().getHolder(false) == event.getWhoClicked();
        if (items.isHat(current) || items.isHat(cursor)) {
            event.setCancelled(true);
            return;
        }
        if (taintedCurrentBundle || taintedCursorBundle
                || (hotbarButton >= 0 && bundleHoldsManaged(hotbar, items))) {
            event.setCancelled(true);
            return;
        }

        if (!managedCurrent && !managedCursor
                && hotbarButton >= 0 && hotbar != null) {
            if (items.isHat(hotbar)) {
                event.setCancelled(true);
                return;
            }
            if (!items.isManagedCosmetic(hotbar)) {
                return;
            }
            if (event.getClickedInventory() == null) {
                event.setCancelled(true);
                return;
            }
            boolean clickedBottom = event.getClickedInventory() == event.getView().getBottomInventory();
            InventoryGuard.ClickOutcome outcome = InventoryGuard.classifyOffhandClick(
                    !clickedBottom,
                    event.getSlotType(),
                    event.getSlot(),
                    event.getView().getBottomInventory() instanceof PlayerInventory,
                    InventoryGuard.actionStaysPlayer(event.getAction(), topIsOwnCrafting));
            event.setCancelled(outcome == InventoryGuard.ClickOutcome.BLOCK);
            return;
        }

        if (!managedCurrent && !managedCursor) {
            return;
        }
        if (event.getClickedInventory() == null) {
            event.setCancelled(true);
            return;
        }

        boolean clickedBottom = event.getClickedInventory() == event.getView().getBottomInventory();
        boolean destinationStaysPlayer = InventoryGuard.actionStaysPlayer(event.getAction(), topIsOwnCrafting);
        InventoryGuard.ClickOutcome outcome = InventoryGuard.classifyOffhandClick(
                !clickedBottom,
                event.getSlotType(),
                event.getSlot(),
                event.getView().getBottomInventory() instanceof PlayerInventory,
                destinationStaysPlayer);
        if (outcome == InventoryGuard.ClickOutcome.BLOCK) {
            event.setCancelled(true);
        }
    }

    private static boolean isBundleRetrieval(InventoryAction action) {
        return switch (action) {
            case PICKUP_FROM_BUNDLE, PICKUP_ALL, PICKUP_HALF, PICKUP_ONE, PICKUP_SOME -> true;
            default -> false;
        };
    }

    private static boolean isDropAction(InventoryAction action) {
        return switch (action) {
            case DROP_ALL_CURSOR, DROP_ALL_SLOT, DROP_ONE_CURSOR, DROP_ONE_SLOT -> true;
            default -> false;
        };
    }

    private static boolean bundleHoldsManaged(ItemStack stack, CosmeticItems items) {
        return stack != null
                && stack.getItemMeta() instanceof BundleMeta bundle
                && InventoryGuard.containsManaged(bundle.getItems(), items::isManagedCosmetic);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCosmeticDrag(InventoryDragEvent event) {
        CosmeticItems items = service.items();
        if (bundleHoldsManaged(event.getOldCursor(), items)) {
            event.setCancelled(true);
            return;
        }
        if (items.isHat(event.getOldCursor())) {
            event.setCancelled(true);
            return;
        }
        if (!items.isManagedCosmetic(event.getOldCursor())) {
            return;
        }

        // Conservatively allow drags only across raw 9-45 of the player's own default view.
        boolean ownCraftingView = event.getView().getTopInventory() instanceof CraftingInventory
                && event.getView().getTopInventory().getHolder(false) == event.getWhoClicked()
                && event.getView().getBottomInventory() instanceof PlayerInventory;
        boolean onlySafePlayerSlots = event.getRawSlots().stream().allMatch(slot -> slot >= 9 && slot <= 45);
        if (!ownCraftingView || !onlySafePlayerSlots) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getInventory().getType() == InventoryType.ENDER_CHEST) {
            enderChestView.add(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        enderChestView.remove(event.getPlayer().getUniqueId());
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (!(event.getView().getBottomInventory() instanceof PlayerInventory inventory)) {
            return;
        }
        CosmeticItems items = service.items();

        if (event.getView().getTopInventory().getType() == InventoryType.ENDER_CHEST) {
            ItemStack hand = inventory.getItemInMainHand();
            if (items.offhandOf(hand).isPresent()) {
                inventory.setItemInMainHand(null);
                if (inventory.getItemInOffHand().isEmpty()) {
                    inventory.setItemInOffHand(hand);
                } else {
                    placeSafely(player, hand);
                }
                getLogger().fine("Returned parked off-hand cosmetic from the main hand of "
                        + player.getName() + " after closing an ender chest");
            }
        }

        ItemStack cursor = event.getView().getCursor();
        if (!items.isManagedCosmetic(cursor) || items.isHat(cursor)) {
            return;
        }

        event.getView().setCursor(null);
        placeSafely(player, cursor);
    }

    private void placeSafely(Player player, ItemStack cosmetic) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] storage = inventory.getStorageContents();
        boolean[] occupied = new boolean[storage.length];
        for (int index = 0; index < storage.length; index++) {
            occupied[index] = storage[index] != null && !storage[index].isEmpty();
        }
        int emptyStorageIndex = InventoryGuard.firstEmptyStorageIndex(occupied);
        if (emptyStorageIndex >= 0) {
            inventory.setItem(emptyStorageIndex, cosmetic);
            return;
        }

        ItemStack offhand = inventory.getItem(40);
        if (offhand == null || offhand.isEmpty()) {
            inventory.setItem(40, cosmetic);
            return;
        }

        // Managed cosmetics are deterministic rebuildables; the stored selection survives this discarded stack.
        getLogger().warning("Inventory full - parked off-hand cosmetic discarded for " + player.getName()
                + "; it will rebuild automatically from the stored selection");
        player.sendRichMessage("<gray>Inventory full - parked off-hand cosmetic discarded; "
                + "it will rebuild automatically from the stored selection</gray>");
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        boolean swappingManaged = service.items().isManagedCosmetic(event.getOffHandItem())
                || service.items().isManagedCosmetic(event.getMainHandItem());
        if (swappingManaged && enderChestView.contains(player.getUniqueId())) {
            event.setCancelled(true);
            // The hand can be smuggled into the chest view's offhand slot; refuse while that view is open.
            player.sendActionBar(Component.text(
                    "Off-hand swap is disabled while the ender chest is open", NamedTextColor.GRAY));
            return;
        }
        getServer().getScheduler().runTask(this, () -> {
            if (player.isOnline() && perks != null) {
                perks.sync(player);
            }
        });
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        ItemStack dropped = event.getItemDrop().getItemStack();
        if (service.items().isManagedCosmetic(dropped) || bundleHoldsManaged(dropped, service.items())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        event.getDrops().removeIf(service.items()::isManagedCosmetic);
        for (ItemStack dropped : event.getDrops()) {
            if (!(dropped.getItemMeta() instanceof BundleMeta bundle)
                    || !InventoryGuard.containsManaged(bundle.getItems(), service.items()::isManagedCosmetic)) {
                continue;
            }
            List<ItemStack> safeContents = bundle.getItems().stream()
                    .filter(item -> !service.items().isManagedCosmetic(item))
                    .toList();
            bundle.setItems(safeContents);
            dropped.setItemMeta(bundle);
            getLogger().fine("Scrubbed managed cosmetics from a dropped bundle owned by "
                    + event.getEntity().getName());
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        getServer().getScheduler().runTask(this, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (offhandsEnabled() && getConfig().getBoolean("offhands.reequip-on-join", true)) {
                service.restoreOffhand(player);
            }
            if (hats != null) {
                hats.sync(player);
            }
        });
    }

    // ----------------------------------------------------------------- events

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        service.stripEnderChest(player);
        if (offhandsEnabled() && getConfig().getBoolean("offhands.reequip-on-join", true)) {
            service.restoreOffhand(player);
        } else {
            service.removeOffhand(player);
        }
        if (hats != null) {
            hats.sync(player);
        } else {
            service.removeHat(player);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        enderChestView.remove(event.getPlayer().getUniqueId());
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
        List<String> removed = service.revalidate(player, event.holdings());
        if (!removed.isEmpty()) {
            player.sendRichMessage("<yellow>" + String.join(", ", removed)
                    + " left your Portal account, so the cosmetic was removed.</yellow>");
        }
    }

    @EventHandler
    public void onPlayerErased(PortalPlayerErasedEvent event) {
        Player player = getServer().getPlayer(event.mojangUuid());
        if (player != null) {
            service.stripAll(player);
        } else {
            service.forget(event.mojangUuid());
        }
    }

    // ---------------------------------------------------------------- helpers

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
