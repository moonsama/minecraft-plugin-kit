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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Applies item skins, off-hands and hats and keeps them consistent with Portal ownership.
 * Methods touching a {@link Player} run on the main thread; async entry points hop back onto it.
 */
public final class ItemsService {
    /** {@code PlayerInventory} index of the off-hand slot. */
    static final int OFFHAND_SLOT = 40;

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
        if (!skin.appliesTo(held.getType()) || items.isManagedCosmetic(held)) {
            return CompletableFuture.completedFuture(Result.WRONG_ITEM);
        }
        return verify(player.getUniqueId(), skin.requirement()).thenCompose(result -> onMain(() -> {
            if (result != Result.OK || !player.isOnline()) {
                return result;
            }
            ItemStack tool = player.getInventory().getItemInMainHand();
            return !items.isManagedCosmetic(tool) && items.applySkin(tool, skin) ? Result.OK : Result.WRONG_ITEM;
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
        removeManagedOffhands(inventory);
        ItemStack current = inventory.getItemInOffHand();
        if (!current.getType().isAir()) {
            Map<Integer, ItemStack> leftover = inventory.addItem(current);
            if (!leftover.isEmpty()) {
                return false;
            }
        }
        inventory.setItemInOffHand(items.buildOffhand(player, offhand));
        offhandStore.put(player.getUniqueId(), offhand.id());
        return true;
    }

    /** The player's own copy of the off-hand they have selected (any form): the only one whose perk works. */
    public boolean isCurrentOffhand(Player player, ItemStack item) {
        Optional<String> id = items.offhandOf(item);
        return id.isPresent() && id.equals(offhandStore.get(player.getUniqueId())) && items.isBoundTo(item, player);
    }

    /** Removes the cosmetic off-hand and forgets the choice. Main thread only. */
    public boolean removeOffhand(Player player) {
        boolean removed = removeManagedOffhands(player.getInventory());
        offhandStore.remove(player.getUniqueId());
        return removed;
    }

    /**
     * Leaves exactly one copy of the selected off-hand (join, respawn, after MoonsamaCore deleted
     * one). Copies may sit anywhere in the inventory; a missing one is rebuilt into the off-hand,
     * or into a free slot when the off-hand holds a real item. Main thread only.
     */
    public void restoreOffhand(Player player) {
        PlayerInventory inventory = player.getInventory();
        Optional<Offhand> stored = offhandStore.get(player.getUniqueId()).flatMap(catalog::offhand);
        if (stored.isEmpty()) {
            offhandStore.remove(player.getUniqueId());
            removeManagedOffhands(inventory);
            return;
        }
        ItemStack[] contents = inventory.getContents();
        List<Integer> copies = new ArrayList<>();
        for (int slot = 0; slot < contents.length; slot++) {
            if (items.offhandOf(contents[slot]).isEmpty()) {
                continue;
            }
            if (isCurrentOffhand(player, contents[slot])) {
                copies.add(slot);
            } else {
                inventory.setItem(slot, null); // another off-hand, or not bound to this player
            }
        }
        int keep = copyToKeep(copies, inventory.getHeldItemSlot());
        for (int slot : copies) {
            if (slot != keep) {
                inventory.setItem(slot, null);
            }
        }
        if (keep >= 0) {
            return;
        }
        ItemStack fresh = items.buildOffhand(player, stored.get());
        if (inventory.getItemInOffHand().isEmpty()) {
            inventory.setItemInOffHand(fresh);
        } else if (inventory.firstEmpty() >= 0) {
            inventory.setItem(inventory.firstEmpty(), fresh);
        } // otherwise it comes back on the next join or respawn with room for it
    }

    /** Which copy survives: the one in the off-hand, then the one in the main hand, then the first. */
    static int copyToKeep(List<Integer> copySlots, int heldSlot) {
        if (copySlots.contains(OFFHAND_SLOT)) {
            return OFFHAND_SLOT;
        }
        if (copySlots.contains(heldSlot)) {
            return heldSlot;
        }
        return copySlots.isEmpty() ? -1 : copySlots.getFirst();
    }

    /**
     * Removes this plugin's cosmetics that are not the player's own bound copies from their
     * inventory, and any from their ender chest: copies from before soulbinding, or ones that
     * slipped out. Bound copies of other players are already gone (MoonsamaCore). Main thread only.
     */
    public void removeStrays(Player player) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (items.isManagedCosmetic(contents[slot]) && !items.isBoundTo(contents[slot], player)) {
                inventory.setItem(slot, null);
            }
        }
        removeCosmetics(player.getEnderChest());
    }

    /** Clears this plugin's cosmetics out of a container they must never be in. Main thread only. */
    public void removeCosmetics(Inventory container) {
        ItemStack[] contents = container.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (items.isManagedCosmetic(contents[slot])) {
                container.setItem(slot, null);
            }
        }
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
            if (!wearingOurs || !items.isBoundTo(helmet, player)
                    || items.hatModelOf(helmet).orElse(-1) != rule.get().customModelData()) {
                inventory.setHelmet(items.buildHat(player, catalog.hats(), rule.get()));
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
}
