package com.moonsama.minecraft.items;

import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryType.SlotType;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.function.Predicate;

/** Pure inventory-movement decisions for managed off-hand cosmetics. */
public final class InventoryGuard {
    private InventoryGuard() {
    }

    public enum ClickOutcome {
        ALLOW,
        BLOCK
    }

    /** Whether this action keeps a managed cosmetic in player-controlled inventory. */
    public static boolean actionStaysPlayer(InventoryAction action, boolean shiftDestIsPlayerCrafting) {
        return switch (action) {
            case NOTHING, PICKUP_ALL, PICKUP_HALF, PICKUP_ONE, PICKUP_SOME, PICKUP_FROM_BUNDLE,
                    PLACE_ALL, PLACE_ONE, PLACE_SOME, SWAP_WITH_CURSOR,
                    HOTBAR_SWAP, HOTBAR_MOVE_AND_READD -> true;
            case MOVE_TO_OTHER_INVENTORY, COLLECT_TO_CURSOR -> shiftDestIsPlayerCrafting;
            // Bundle insertion and creative cloning can smuggle locked cosmetics into movable stacks or dupes, so fail closed.
            case PICKUP_ALL_INTO_BUNDLE, PICKUP_SOME_INTO_BUNDLE,
                    PLACE_ALL_INTO_BUNDLE, PLACE_SOME_INTO_BUNDLE, PLACE_FROM_BUNDLE,
                    CLONE_STACK, DROP_ALL_CURSOR, DROP_ALL_SLOT, DROP_ONE_CURSOR, DROP_ONE_SLOT, UNKNOWN -> false;
        };
    }

    /** Matches the captured F-key signature for swapping off-hand into a foreign top
     *  inventory: HOTBAR_SWAP (empty destination, button -1) and the occupied-destination
     *  variant HOTBAR_MOVE_AND_READD, both delivered with hotbarButton -1 on the top side. */
    public static boolean foreignOffhandSwap(InventoryAction action, int hotbarButton, boolean clickedTop) {
        return (action == InventoryAction.HOTBAR_SWAP || action == InventoryAction.HOTBAR_MOVE_AND_READD)
                && hotbarButton < 0 && clickedTop;
    }

    /** Pure decision core for a click involving a managed off-hand cosmetic. */
    public static ClickOutcome classifyOffhandClick(
            boolean clickedTop,
            SlotType slotType,
            int clickedSlotIndex,
            boolean bottomIsPlayerInventory,
            boolean destinationStaysPlayer) {
        if (clickedTop) {
            return ClickOutcome.BLOCK;
        }
        // Bukkit reports off-hand as ARMOR in player views. Accept both
        // PlayerInventory-relative (40) and view-global (45) slot conventions.
        if (bottomIsPlayerInventory && (clickedSlotIndex == 40 || clickedSlotIndex == 45)) {
            return destinationStaysPlayer ? ClickOutcome.ALLOW : ClickOutcome.BLOCK;
        }
        if (slotType == SlotType.CONTAINER || slotType == SlotType.QUICKBAR) {
            return destinationStaysPlayer ? ClickOutcome.ALLOW : ClickOutcome.BLOCK;
        }
        // Real armor, crafting, result, and unexpected slots fail closed.
        return ClickOutcome.BLOCK;
    }

    /** Returns the first unoccupied storage index, or -1 when every index is occupied. */
    public static int firstEmptyStorageIndex(boolean[] occupied) {
        for (int index = 0; index < occupied.length; index++) {
            if (!occupied[index]) {
                return index;
            }
        }
        return -1;
    }

    /** Whether a collection contains at least one managed cosmetic. */
    public static boolean containsManaged(List<ItemStack> contents, Predicate<ItemStack> isManaged) {
        return contents.stream().anyMatch(isManaged);
    }
}
