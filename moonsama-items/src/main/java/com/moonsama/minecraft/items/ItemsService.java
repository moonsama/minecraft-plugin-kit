package com.moonsama.minecraft.items;

import com.moonsama.minecraft.api.AssetHolding;
import com.moonsama.minecraft.api.HoldingsSnapshot;
import com.moonsama.minecraft.api.MoonsamaService;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Applies item skins, off-hands and hats and keeps them consistent with Portal ownership.
 * Methods touching a {@link Player} run on the main thread; async entry points hop back onto it.
 */
public final class ItemsService {
    private final Plugin plugin;
    private final MoonsamaService moonsama;
    private final ItemsCatalog catalog;
    private final CosmeticItems items;
    private final EquippedOffhandStore offhandStore;

    public ItemsService(Plugin plugin, MoonsamaService moonsama, ItemsCatalog catalog,
                        CosmeticItems items, EquippedOffhandStore offhandStore) {
        this.plugin = plugin;
        this.moonsama = moonsama;
        this.catalog = catalog;
        this.items = items;
        this.offhandStore = offhandStore;
    }

    public ItemsCatalog catalog() {
        return catalog;
    }

    public CosmeticItems items() {
        return items;
    }

    public Optional<String> equippedOffhand(UUID mojangUuid) {
        return offhandStore.get(mojangUuid);
    }

    // -------------------------------------------------------------- ownership

    /** Cosmetics the player may use according to MoonsamaCore's holdings cache. */
    public CompletableFuture<Owned> owned(UUID mojangUuid) {
        return moonsama.cachedHoldings(mojangUuid).thenApply(snapshot -> owned(snapshot.status(), snapshot.holdings()));
    }

    Owned owned(HoldingsSnapshot.Status status, List<AssetHolding> holdings) {
        List<ItemSkin> skins = new ArrayList<>();
        for (ItemSkin skin : catalog.itemSkins().values()) {
            if (skin.requirement().satisfiedBy(holdings)) {
                skins.add(skin);
            }
        }
        List<Offhand> offhands = new ArrayList<>();
        for (Offhand offhand : catalog.offhands().values()) {
            if (offhand.requirement().satisfiedBy(holdings)) {
                offhands.add(offhand);
            }
        }
        return new Owned(status, List.copyOf(skins), List.copyOf(offhands));
    }

    /**
     * Checks a requirement against the cache, falling back to a live Portal query while the
     * cache is not fresh.
     */
    public CompletableFuture<Result> verify(UUID mojangUuid, Requirement requirement) {
        if (!requirement.unlockable()) {
            return CompletableFuture.completedFuture(Result.NOT_OWNED);
        }
        return moonsama.linkedPlayer(mojangUuid)
                .thenCompose(linked -> {
                    if (linked.isEmpty()) {
                        return CompletableFuture.completedFuture(Result.NOT_LINKED);
                    }
                    return moonsama.cachedHoldings(mojangUuid).thenCompose(snapshot -> {
                        if (requirement.satisfiedBy(snapshot.holdings())) {
                            return CompletableFuture.completedFuture(Result.OK);
                        }
                        if (snapshot.status() == HoldingsSnapshot.Status.FRESH) {
                            return CompletableFuture.completedFuture(Result.NOT_OWNED);
                        }
                        return moonsama.holdings(mojangUuid).thenApply(holdings ->
                                requirement.satisfiedBy(holdings) ? Result.OK : Result.NOT_OWNED);
                    });
                })
                .exceptionally(failure -> {
                    plugin.getLogger().log(Level.WARNING, "Could not verify Portal holdings", failure);
                    return Result.UNAVAILABLE;
                });
    }

    // ------------------------------------------------------------- item skins

    /** Applies {@code skin} to the tool in the player's main hand once ownership is confirmed. */
    public CompletableFuture<Result> applySkin(Player player, ItemSkin skin) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (!skin.appliesTo(held.getType())) {
            return CompletableFuture.completedFuture(Result.WRONG_ITEM);
        }
        return verify(player.getUniqueId(), skin.requirement()).thenCompose(result -> onMain(() -> {
            if (result != Result.OK || !player.isOnline()) {
                return result;
            }
            ItemStack tool = player.getInventory().getItemInMainHand();
            return items.applySkin(tool, skin) ? Result.OK : Result.WRONG_ITEM;
        }));
    }

    /** Removes the skin from the held tool. Main thread only. */
    public boolean clearHeldSkin(Player player) {
        return items.clearSkin(player.getInventory().getItemInMainHand());
    }

    // --------------------------------------------------------------- offhands

    public CompletableFuture<Result> equipOffhand(Player player, Offhand offhand) {
        return verify(player.getUniqueId(), offhand.requirement()).thenCompose(result -> onMain(() -> {
            if (result != Result.OK || !player.isOnline()) {
                return result;
            }
            return placeOffhand(player, offhand) ? Result.OK : Result.INVENTORY_FULL;
        }));
    }

    /** Puts the off-hand item in place, moving a real item out of the way. Main thread only. */
    private boolean placeOffhand(Player player, Offhand offhand) {
        PlayerInventory inventory = player.getInventory();
        clearStaleHandCopy(player, offhand.id());
        removeManagedOffhands(inventory);
        ItemStack current = inventory.getItemInOffHand();
        if (!current.getType().isAir()) {
            Map<Integer, ItemStack> leftover = inventory.addItem(current);
            if (!leftover.isEmpty()) {
                return false;
            }
        }
        inventory.setItemInOffHand(items.buildOffhand(offhand));
        offhandStore.put(player.getUniqueId(), offhand.id());
        return true;
    }

    /** Removes the cosmetic off-hand and forgets the choice. Main thread only. */
    public boolean removeOffhand(Player player) {
        String selectedId = offhandStore.get(player.getUniqueId()).orElse("");
        boolean removed = removeManagedOffhands(player.getInventory());
        stripEnderChest(player);
        clearStaleHandCopy(player, selectedId);
        offhandStore.remove(player.getUniqueId());
        return removed;
    }

    /** Removes rebuildable cosmetic copies from an invalid resting place. Main thread only. */
    public int stripEnderChest(Player player) {
        Inventory enderChest = player.getEnderChest();
        List<Integer> straySlots = enderChestStraySlots(
                Arrays.asList(enderChest.getContents()),
                item -> item instanceof ItemStack stack && items.offhandOf(stack).isPresent());
        for (int slot : straySlots) {
            enderChest.setItem(slot, null);
        }
        if (!straySlots.isEmpty()) {
            plugin.getLogger().info("Stripped " + straySlots.size()
                    + " off-hand cosmetic copy(ies) from the ender chest of " + player.getName());
        }
        return straySlots.size();
    }

    /** Hands the stored off-hand back (join/respawn) if the slot is free. Main thread only. */
    public void restoreOffhand(Player player) {
        Optional<Offhand> stored = offhandStore.get(player.getUniqueId()).flatMap(catalog::offhand);
        if (stored.isEmpty()) {
            offhandStore.remove(player.getUniqueId());
            return;
        }
        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = inventory.getContents();
        List<Integer> managedSlots = managedCopySlots(contents, item -> items.offhandOf(item).isPresent());
        int heldSlot = inventory.getHeldItemSlot();
        managedSlots = managedSlots.stream().filter(slot -> slot != heldSlot).toList();
        ItemStack worn = inventory.getItemInOffHand();
        Optional<String> wornId = items.offhandOf(worn);
        boolean wornManagedMatching = wornId.filter(stored.get().id()::equals).isPresent();
        boolean wornManagedStale = wornId.isPresent() && !wornManagedMatching;
        boolean wornAir = worn.getType().isAir();
        ItemStack hand = inventory.getItemInMainHand();
        Optional<String> handId = items.offhandOf(hand);
        HandState handState;
        if (hand.getType().isAir()) {
            handState = HandState.EMPTY;
        } else if (handId.filter(stored.get().id()::equals).isPresent()) {
            handState = HandState.MATCHING;
        } else if (handId.isPresent()) {
            handState = HandState.STALE;
        } else {
            handState = HandState.OCCUPIED_REAL;
        }
        clearStaleHandCopy(player, stored.get().id());
        if (handState == HandState.MATCHING && needsAmountNormalization(hand.getAmount())) {
            inventory.setItemInMainHand(normalizedSingle(hand));
        }
        int matchingStorageSlot = -1;
        for (int slot = 0; slot < Math.min(40, contents.length); slot++) {
            if (slot == heldSlot) {
                continue;
            }
            if (items.offhandOf(contents[slot]).filter(stored.get().id()::equals).isPresent()) {
                matchingStorageSlot = slot;
                break;
            }
        }
        int matchingSlot = handState == HandState.MATCHING ? 41 : matchingStorageSlot;
        RestorePlan plan = planRestore(
                wornManagedMatching, wornManagedStale, wornAir, matchingSlot, handState);
        if (handState == HandState.MATCHING
                && plan.action() != RestorePlan.Action.KEEP_IN_HAND
                && plan.action() != RestorePlan.Action.SWEEP_CONTENTS_KEEP_HAND) {
            inventory.setItemInMainHand(null);
        }
        switch (plan.action()) {
            case SWEEP_ONLY -> {
                sweepManaged(inventory, managedSlots, 40);
                if (needsAmountNormalization(contents[40].getAmount())) {
                    inventory.setItem(40, normalizedSingle(contents[40]));
                }
            }
            case MOVE_TO_OFFHAND -> {
                ItemStack selectedCopy = normalizedSingle(contents[plan.keepSlot()]);
                sweepManaged(inventory, managedSlots, -1);
                inventory.setItem(40, selectedCopy);
            }
            case BUILD -> {
                sweepManaged(inventory, managedSlots, -1);
                inventory.setItemInOffHand(items.buildOffhand(stored.get()));
            }
            case SWEEP_STALE_THEN_MOVE -> {
                ItemStack selectedCopy = normalizedSingle(contents[plan.keepSlot()]);
                sweepManaged(inventory, managedSlots, -1);
                inventory.setItem(40, selectedCopy);
            }
            case SWEEP_STALE_THEN_BUILD -> {
                sweepManaged(inventory, managedSlots, -1);
                inventory.setItemInOffHand(items.buildOffhand(stored.get()));
            }
            case SWEEP_TO_ONE -> {
                ItemStack selectedCopy = normalizedSingle(contents[plan.keepSlot()]);
                sweepManaged(inventory, managedSlots, plan.keepSlot());
                inventory.setItem(plan.keepSlot(), selectedCopy);
            }
            case SWEEP_ALL_STALE_AND_DORMANT -> {
                sweepManaged(inventory, managedSlots, -1);
                plugin.getLogger().fine("Off-hand slot occupied; keeping stored cosmetic selection dormant for "
                        + player.getName());
            }
            case KEEP_IN_HAND, SWEEP_CONTENTS_KEEP_HAND -> {
                sweepManaged(inventory, managedSlots, -1);
                plugin.getLogger().fine("Keeping stored off-hand cosmetic parked in the main hand of "
                        + player.getName());
            }
        }
    }

    static boolean needsAmountNormalization(int amount) {
        return amount != 1;
    }

    private static ItemStack normalizedSingle(ItemStack stack) {
        ItemStack single = stack.clone();
        single.setAmount(1);
        return single;
    }

    static RestorePlan planRestore(boolean wornManagedMatching, boolean wornManagedStale,
                                   boolean wornAir, int matchingSlot, HandState handState) {
        if (wornManagedMatching) {
            return new RestorePlan(RestorePlan.Action.SWEEP_ONLY, 40);
        }
        if (handState == HandState.MATCHING && matchingSlot == 41) {
            return new RestorePlan(wornAir
                    ? RestorePlan.Action.KEEP_IN_HAND
                    : RestorePlan.Action.SWEEP_CONTENTS_KEEP_HAND, 41);
        }
        if (wornAir) {
            return matchingSlot >= 0
                    ? new RestorePlan(RestorePlan.Action.MOVE_TO_OFFHAND, matchingSlot)
                    : new RestorePlan(RestorePlan.Action.BUILD, -1);
        }
        if (wornManagedStale) {
            return matchingSlot >= 0
                    ? new RestorePlan(RestorePlan.Action.SWEEP_STALE_THEN_MOVE, matchingSlot)
                    : new RestorePlan(RestorePlan.Action.SWEEP_STALE_THEN_BUILD, -1);
        }
        return matchingSlot >= 0
                ? new RestorePlan(RestorePlan.Action.SWEEP_TO_ONE, matchingSlot)
                : new RestorePlan(RestorePlan.Action.SWEEP_ALL_STALE_AND_DORMANT, -1);
    }

    private static void sweepManaged(PlayerInventory inventory, List<Integer> managedSlots, int keepSlot) {
        for (int slot : managedSlots) {
            if (slot != keepSlot) {
                inventory.setItem(slot, null);
            }
        }
    }

    static List<Integer> managedCopySlots(ItemStack[] contents, Predicate<ItemStack> isManaged) {
        List<Integer> slots = new ArrayList<>();
        for (int slot = 0; slot < contents.length; slot++) {
            if (isManaged.test(contents[slot])) {
                slots.add(slot);
            }
        }
        return List.copyOf(slots);
    }

    static List<Integer> enderChestStraySlots(List<?> contents, Predicate<Object> isManaged) {
        List<Integer> slots = new ArrayList<>();
        for (int slot = 0; slot < contents.size(); slot++) {
            Object item = contents.get(slot);
            if (item != null && isManaged.test(item)) {
                slots.add(slot);
            }
        }
        return List.copyOf(slots);
    }

    private boolean removeManagedOffhands(PlayerInventory inventory) {
        boolean removed = false;
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (items.offhandOf(contents[slot]).isPresent()) {
                inventory.setItem(slot, null);
                removed = true;
            }
        }
        return removed;
    }

    private void clearStaleHandCopy(Player player, String selectedId) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        Optional<String> handId = items.offhandOf(hand);
        if (handId.isPresent() && !handId.get().equals(selectedId)) {
            player.getInventory().setItemInMainHand(null);
            plugin.getLogger().fine("Removed stale cosmetic from the main hand of " + player.getName());
        }
    }

    // ------------------------------------------------------------------- hats

    /**
     * Shows the hat belonging to the NFT skin the player wears, or removes ours when there is
     * none. Real helmets are never replaced. Main thread only.
     */
    public Optional<HatRules.Rule> updateHat(Player player, String portalCollection, Long tokenId) {
        Optional<HatRules.Rule> rule = portalCollection == null || tokenId == null
                ? Optional.empty()
                : catalog.hats().resolve(portalCollection, catalog.composition(portalCollection, tokenId));
        PlayerInventory inventory = player.getInventory();
        ItemStack helmet = inventory.getHelmet();
        boolean wearingOurs = items.isHat(helmet);
        if (rule.isEmpty()) {
            if (wearingOurs) {
                inventory.setHelmet(null);
            }
            removeStrayHats(inventory);
            return Optional.empty();
        }
        removeStrayHats(inventory);
        if (helmet == null || helmet.getType().isAir() || wearingOurs) {
            if (!wearingOurs || items.hatModelOf(helmet).orElse(-1) != rule.get().customModelData()) {
                inventory.setHelmet(items.buildHat(catalog.hats(), rule.get()));
            }
        }
        return rule;
    }

    public void removeHat(Player player) {
        PlayerInventory inventory = player.getInventory();
        if (items.isHat(inventory.getHelmet())) {
            inventory.setHelmet(null);
        }
        removeStrayHats(inventory);
    }

    /** Hats only live in the helmet slot; anything else is a leftover from an inventory shuffle. */
    private void removeStrayHats(PlayerInventory inventory) {
        ItemStack[] storage = inventory.getStorageContents();
        for (int slot = 0; slot < storage.length; slot++) {
            if (items.isHat(storage[slot])) {
                inventory.setItem(slot, null);
            }
        }
        if (items.isHat(inventory.getItemInOffHand())) {
            inventory.setItemInOffHand(null);
        }
    }

    // ----------------------------------------------------------- consistency

    /**
     * Strips cosmetics the fresh holdings no longer justify. Returns the names of what was
     * removed. Main thread only.
     */
    public List<String> revalidate(Player player, List<AssetHolding> holdings) {
        List<String> removed = new ArrayList<>();
        PlayerInventory inventory = player.getInventory();
        for (ItemStack stack : inventory.getContents()) {
            Optional<ItemSkin> skin = items.skinOf(stack).flatMap(catalog::itemSkin);
            if (skin.isPresent() && !skin.get().requirement().satisfiedBy(holdings)) {
                items.clearSkin(stack);
                removed.add(skin.get().name());
            } else if (skin.isEmpty() && items.skinOf(stack).isPresent()) {
                items.clearSkin(stack); // skin no longer in the catalog
            }
        }
        Optional<Offhand> offhand = offhandStore.get(player.getUniqueId()).flatMap(catalog::offhand);
        if (offhand.isPresent() && !offhand.get().requirement().satisfiedBy(holdings)) {
            removeOffhand(player);
            removed.add(offhand.get().name());
        }
        return removed;
    }

    /** Removes everything this plugin put on the player (Portal erasure, unlink). Main thread only. */
    public void stripAll(Player player) {
        for (ItemStack stack : player.getInventory().getContents()) {
            items.clearSkin(stack);
        }
        removeOffhand(player);
        removeHat(player);
    }

    public void forget(UUID mojangUuid) {
        offhandStore.remove(mojangUuid);
    }

    private <T> CompletableFuture<T> onMain(Supplier<T> action) {
        if (plugin.getServer().isPrimaryThread()) {
            return CompletableFuture.completedFuture(action.get());
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                future.complete(action.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    public enum Result {
        OK,
        NOT_LINKED,
        NOT_OWNED,
        WRONG_ITEM,
        INVENTORY_FULL,
        UNAVAILABLE
    }

    public record Owned(HoldingsSnapshot.Status status, List<ItemSkin> skins, List<Offhand> offhands) {
    }

    enum HandState {
        EMPTY,
        MATCHING,
        STALE,
        OCCUPIED_REAL
    }

    record RestorePlan(Action action, int keepSlot) {
        enum Action {
            SWEEP_ONLY,
            MOVE_TO_OFFHAND,
            BUILD,
            SWEEP_STALE_THEN_MOVE,
            SWEEP_STALE_THEN_BUILD,
            SWEEP_TO_ONE,
            SWEEP_ALL_STALE_AND_DORMANT,
            KEEP_IN_HAND,
            SWEEP_CONTENTS_KEEP_HAND
        }
    }
}
