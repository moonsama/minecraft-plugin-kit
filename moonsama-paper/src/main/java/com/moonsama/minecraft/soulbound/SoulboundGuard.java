package com.moonsama.minecraft.soulbound;

import com.moonsama.minecraft.api.event.SoulboundItemRemovedEvent.Reason;
import com.moonsama.minecraft.soulbound.SoulboundRules.Kind;
import com.moonsama.minecraft.soulbound.SoulboundRules.Side;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps bound items with their owner. Inventory moves follow {@link SoulboundRules}; everything
 * that would put a bound item into the world or into another inventory is refused; and any bound
 * item found where it does not belong (another player, a container, the ground) is deleted, so a
 * copy that slips past a rule is useless and short-lived.
 */
final class SoulboundGuard implements Listener {
    private final Plugin plugin;
    private final SoulboundService soulbound;

    SoulboundGuard(Plugin plugin, SoulboundService soulbound) {
        this.plugin = plugin;
        this.soulbound = soulbound;
    }

    // ------------------------------------------------------------ inventories

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        InventoryView view = event.getView();
        boolean ownView = isOwnView(view.getTopInventory());
        Side side = event.getClickedInventory() == null ? Side.OUTSIDE
                : event.getClickedInventory() == view.getBottomInventory() ? Side.BOTTOM : Side.TOP;
        ItemStack current = event.getCurrentItem();
        ItemStack cursor = event.getCursor();
        int button = event.getHotbarButton();
        ItemStack hotbar = button >= 0 ? player.getInventory().getItem(button) : null;
        ItemStack offhand = SoulboundRules.offhandSwap(event.getAction(), button)
                ? player.getInventory().getItemInOffHand() : null;

        boolean outOfPlace = side == Side.TOP && isContainer(view.getTopInventory()) && soulbound.isBound(current);
        if (outOfPlace || foreign(player, current, cursor, hotbar, offhand)) {
            event.setCancelled(true);
            scrubLater(player);
            return;
        }
        if (player.getGameMode() == GameMode.CREATIVE) {
            return; // creative players move freely, as on the legacy server; the clean-up still applies
        }
        SoulboundRules.Click click = new SoulboundRules.Click(event.getAction(), side, event.getSlotType(),
                event.getSlot(), ownView, soulbound.kind(current),
                Kind.strongest(soulbound.kind(cursor), soulbound.kind(hotbar), soulbound.kind(offhand)));
        if (!SoulboundRules.allowClick(click)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        ItemStack cursor = event.getOldCursor();
        if (soulbound.isForeign(cursor, player)) {
            event.setCancelled(true);
            scrubLater(player);
            return;
        }
        if (player.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        InventoryView view = event.getView();
        boolean ownView = isOwnView(view.getTopInventory());
        boolean onlyPlayerSlots = event.getRawSlots().stream().allMatch(raw -> ownView
                ? SoulboundRules.ownViewPlayerSlot(raw)
                : view.getInventory(raw) == view.getBottomInventory());
        if (!SoulboundRules.allowDrag(soulbound.kind(cursor), onlyPlayerSlots)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        if (foreign(player, event.getMainHandItem(), event.getOffHandItem())) {
            event.setCancelled(true);
            scrubLater(player);
        } else if (soulbound.kind(event.getMainHandItem()) == Kind.LOCKED
                || soulbound.kind(event.getOffHandItem()) == Kind.LOCKED) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        ItemStack dropped = event.getItemDrop().getItemStack();
        if (soulbound.isBound(dropped)) {
            event.setCancelled(true);
            if (soulbound.isForeign(dropped, event.getPlayer())) {
                scrubLater(event.getPlayer());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        event.getDrops().removeIf(soulbound::isBound);
        for (ItemStack drop : event.getDrops()) {
            stripBundle(drop);
        }
    }

    /** The recipe book can move a bound item into a crafting grid; it never becomes an ingredient. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        for (ItemStack ingredient : event.getInventory().getMatrix()) {
            if (soulbound.isBound(ingredient)) {
                event.getInventory().setResult(null);
                return;
            }
        }
    }

    // ------------------------------------------------------------------ world

    /** Item frames, allays, animals: nothing takes a bound item out of the hand that uses it. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (soulbound.isBound(event.getPlayer().getInventory().getItem(event.getHand()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractAtEntity(PlayerInteractAtEntityEvent event) {
        if (soulbound.isBound(event.getPlayer().getInventory().getItem(event.getHand()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        Player player = event.getPlayer();
        if (soulbound.isBound(player.getInventory().getItem(event.getHand()))
                || soulbound.isForeign(event.getArmorStandItem(), player)) {
            event.setCancelled(true);
        }
    }

    /** Decorated pots take the held item; shelves swap the hotbar. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteractBlock(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || block == null || event.getHand() == null) {
            return;
        }
        boolean shelf = block.getType().name().endsWith("_SHELF");
        if (!shelf && block.getType() != Material.DECORATED_POT) {
            return;
        }
        PlayerInventory inventory = event.getPlayer().getInventory();
        if (soulbound.isBound(inventory.getItem(event.getHand())) || (shelf && hotbarHoldsBound(inventory))) {
            event.setUseInteractedBlock(Event.Result.DENY);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        ItemStack item = event.getEntity().getItemStack();
        if (soulbound.isBound(item)) {
            event.setCancelled(true);
            soulbound.removed(item, Reason.ESCAPED);
        } else if (stripBundle(item)) {
            event.getEntity().setItemStack(item);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        ItemStack item = event.getItem().getItemStack();
        if (!soulbound.isBound(item)
                || (event.getEntity() instanceof Player player && soulbound.isBoundTo(item, player))) {
            return;
        }
        event.setCancelled(true);
        event.getItem().remove();
        soulbound.removed(item, Reason.ESCAPED);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHopperPickup(InventoryPickupItemEvent event) {
        ItemStack item = event.getItem().getItemStack();
        if (soulbound.isBound(item)) {
            event.setCancelled(true);
            event.getItem().remove();
            soulbound.removed(item, Reason.ESCAPED);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHopperMove(InventoryMoveItemEvent event) {
        if (soulbound.isBound(event.getItem())) {
            event.setCancelled(true);
            Inventory source = event.getSource();
            plugin.getServer().getScheduler().runTask(plugin, () -> scrubContainer(source));
        }
    }

    // --------------------------------------------------------------- clean-up

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (isContainer(event.getInventory())) {
            scrubContainer(event.getInventory());
        }
        scrubPlayer(player);
    }

    /** Runs before feature plugins restore their items, so they find a clean inventory. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        soulbound.track(player);
        scrubPlayer(player);
        scrubContainer(player.getEnderChest());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        soulbound.untrack(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeld(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        if (soulbound.isForeign(player.getInventory().getItem(event.getNewSlot()), player)) {
            scrubLater(player);
        }
    }

    private void scrubLater(Player player) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            scrubPlayer(player);
            Inventory top = player.getOpenInventory().getTopInventory();
            if (isContainer(top)) {
                scrubContainer(top);
            }
        });
    }

    /** Deletes items bound to someone else from the player's inventory and cursor. */
    private void scrubPlayer(Player player) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (soulbound.isForeign(item, player)) {
                inventory.setItem(slot, null);
                soulbound.removed(item, Reason.NOT_OWNER);
            } else if (stripBundle(item)) {
                inventory.setItem(slot, item);
            }
        }
        ItemStack cursor = player.getItemOnCursor();
        if (soulbound.isForeign(cursor, player)) {
            player.setItemOnCursor(null);
            soulbound.removed(cursor, Reason.NOT_OWNER);
        }
    }

    /** Bound items never belong in a container, whoever owns them. */
    private void scrubContainer(Inventory inventory) {
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (soulbound.isBound(item)) {
                inventory.setItem(slot, null);
                soulbound.removed(item, Reason.ESCAPED);
            } else if (stripBundle(item)) {
                inventory.setItem(slot, item);
            }
        }
    }

    /** Removes bound items from inside a bundle; true when {@code item} was changed. */
    private boolean stripBundle(ItemStack item) {
        if (item == null || !item.getType().name().endsWith("BUNDLE")
                || !(item.getItemMeta() instanceof BundleMeta bundle)) {
            return false;
        }
        List<ItemStack> kept = new ArrayList<>();
        List<ItemStack> removed = new ArrayList<>();
        for (ItemStack content : bundle.getItems()) {
            (soulbound.isBound(content) ? removed : kept).add(content);
        }
        if (removed.isEmpty()) {
            return false;
        }
        bundle.setItems(kept);
        item.setItemMeta(bundle);
        removed.forEach(content -> soulbound.removed(content, Reason.ESCAPED));
        return true;
    }

    private boolean foreign(Player player, ItemStack... items) {
        for (ItemStack item : items) {
            if (soulbound.isForeign(item, player)) {
                return true;
            }
        }
        return false;
    }

    private boolean hotbarHoldsBound(PlayerInventory inventory) {
        for (int slot = 0; slot < 9; slot++) {
            if (soulbound.isBound(inventory.getItem(slot))) {
                return true;
            }
        }
        return soulbound.isBound(inventory.getItemInOffHand());
    }

    /** The player's own inventory screen, with the 2x2 crafting grid on top. */
    private static boolean isOwnView(Inventory top) {
        return top.getType() == InventoryType.CRAFTING;
    }

    /** A top inventory bound items never belong in. A player inventory (e.g. an admin's invsee) is not one. */
    private static boolean isContainer(Inventory top) {
        return switch (top.getType()) {
            case CRAFTING, CREATIVE, PLAYER -> false;
            default -> true;
        };
    }
}
