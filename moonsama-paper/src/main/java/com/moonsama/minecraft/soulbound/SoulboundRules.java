package com.moonsama.minecraft.soulbound;

import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryType.SlotType;

/** Pure decisions for moving bound items around inventory views; testable without a server. */
final class SoulboundRules {
    /** {@code PlayerInventory} index of the off-hand slot. */
    static final int OFFHAND_SLOT = 40;

    /** How strongly a bound item is involved in a click: not at all, movable, or fixed in place. */
    enum Kind {
        NONE, FREE, LOCKED;

        static Kind strongest(Kind... kinds) {
            Kind result = NONE;
            for (Kind kind : kinds) {
                if (kind.ordinal() > result.ordinal()) {
                    result = kind;
                }
            }
            return result;
        }
    }

    /** Which part of the view was clicked. */
    enum Side { TOP, BOTTOM, OUTSIDE }

    /**
     * One inventory click. {@code current} is the item in the clicked slot; {@code others} the
     * strongest of the cursor, the hotbar item a number key swaps and the off-hand item the F
     * key swaps. {@code ownView} is the player's own inventory screen (2x2 crafting on top).
     */
    record Click(InventoryAction action, Side side, SlotType slotType, int slot, boolean ownView,
                 Kind current, Kind others) {
        Kind involved() {
            return Kind.strongest(current, others);
        }
    }

    private SoulboundRules() {
    }

    /**
     * The F key inside an inventory screen swaps the off-hand with the hovered slot. Paper reports
     * it like a number key with button -1, and neither the clicked item nor the cursor carries the
     * off-hand item, so it has to be looked up separately. Matched by name so the deprecated
     * {@code HOTBAR_MOVE_AND_READD} variant is still caught without referencing it.
     */
    static boolean offhandSwap(InventoryAction action, int hotbarButton) {
        return hotbarButton < 0 && action.name().startsWith("HOTBAR_");
    }

    static boolean allowClick(Click click) {
        Kind involved = click.involved();
        if (involved == Kind.NONE) {
            return true;
        }
        if (involved == Kind.LOCKED) {
            return false;
        }
        if (click.side() == Side.TOP) {
            return takesBackFromOwnCraftingGrid(click);
        }
        if (click.side() != Side.BOTTOM || !playerSlot(click)) {
            return false;
        }
        return switch (click.action()) {
            case NOTHING, PICKUP_ALL, PICKUP_HALF, PICKUP_ONE, PICKUP_SOME,
                 PLACE_ALL, PLACE_ONE, PLACE_SOME, SWAP_WITH_CURSOR, HOTBAR_SWAP -> true;
            // With a container open, shift-click and double-click reach into the top inventory.
            case MOVE_TO_OTHER_INVENTORY, COLLECT_TO_CURSOR -> click.ownView();
            // Drops, bundles, cloning and anything new fail closed.
            default -> false;
        };
    }

    /** The recipe book can pull a bound item into the 2x2 grid; let the owner take it back out. */
    private static boolean takesBackFromOwnCraftingGrid(Click click) {
        return click.ownView() && click.slotType() == SlotType.CRAFTING
                && click.current() == Kind.FREE && click.others() == Kind.NONE
                && switch (click.action()) {
                    case PICKUP_ALL, PICKUP_HALF, PICKUP_ONE, PICKUP_SOME, MOVE_TO_OTHER_INVENTORY -> true;
                    default -> false;
                };
    }

    /** Hotbar and storage rows, plus the off-hand in the player's own screen; never armor. */
    private static boolean playerSlot(Click click) {
        return click.slotType() == SlotType.CONTAINER || click.slotType() == SlotType.QUICKBAR
                || (click.ownView() && click.slot() == OFFHAND_SLOT);
    }

    /**
     * A drag spreading the cursor stack. {@code onlyPlayerSlots}: every slot it touches is the
     * player's own hotbar, storage or off-hand.
     */
    static boolean allowDrag(Kind cursor, boolean onlyPlayerSlots) {
        return switch (cursor) {
            case NONE -> true;
            case FREE -> onlyPlayerSlots;
            case LOCKED -> false;
        };
    }

    /** Raw slots 9-45 of the player's own screen: storage, hotbar and off-hand (no armor, no grid). */
    static boolean ownViewPlayerSlot(int rawSlot) {
        return rawSlot >= 9 && rawSlot <= 45;
    }
}
