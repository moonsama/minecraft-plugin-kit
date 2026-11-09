package com.moonsama.minecraft.soulbound;

import com.moonsama.minecraft.soulbound.SoulboundRules.Click;
import com.moonsama.minecraft.soulbound.SoulboundRules.Kind;
import com.moonsama.minecraft.soulbound.SoulboundRules.Side;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryType.SlotType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SoulboundRulesTest {
    private static final boolean OWN_VIEW = true;
    private static final boolean CHEST_VIEW = false;

    private static boolean allow(InventoryAction action, Side side, SlotType type, int slot, boolean ownView,
                                 Kind current, Kind others) {
        return SoulboundRules.allowClick(new Click(action, side, type, slot, ownView, current, others));
    }

    @Test
    void clicksWithoutBoundItemsAreNotOurBusiness() {
        assertThat(allow(InventoryAction.MOVE_TO_OTHER_INVENTORY, Side.BOTTOM, SlotType.CONTAINER, 12, CHEST_VIEW,
                Kind.NONE, Kind.NONE)).isTrue();
        assertThat(allow(InventoryAction.DROP_ALL_CURSOR, Side.OUTSIDE, SlotType.OUTSIDE, -999, OWN_VIEW,
                Kind.NONE, Kind.NONE)).isTrue();
    }

    @Test
    void lockedItemsNeverMove() {
        assertThat(allow(InventoryAction.PICKUP_ALL, Side.BOTTOM, SlotType.ARMOR, 39, OWN_VIEW,
                Kind.LOCKED, Kind.NONE)).isFalse();
        assertThat(allow(InventoryAction.HOTBAR_SWAP, Side.BOTTOM, SlotType.CONTAINER, 12, OWN_VIEW,
                Kind.NONE, Kind.LOCKED)).isFalse();
    }

    @Test
    void freeItemsMoveAroundTheOwnInventory() {
        assertThat(allow(InventoryAction.PICKUP_ALL, Side.BOTTOM, SlotType.CONTAINER, 12, OWN_VIEW,
                Kind.FREE, Kind.NONE)).isTrue();
        assertThat(allow(InventoryAction.PLACE_ALL, Side.BOTTOM, SlotType.QUICKBAR, 3, OWN_VIEW,
                Kind.NONE, Kind.FREE)).isTrue();
        assertThat(allow(InventoryAction.SWAP_WITH_CURSOR, Side.BOTTOM, SlotType.ARMOR, SoulboundRules.OFFHAND_SLOT,
                OWN_VIEW, Kind.NONE, Kind.FREE)).as("off-hand slot").isTrue();
        assertThat(allow(InventoryAction.MOVE_TO_OTHER_INVENTORY, Side.BOTTOM, SlotType.QUICKBAR, 2, OWN_VIEW,
                Kind.FREE, Kind.NONE)).as("shift-click between hotbar and storage").isTrue();
        assertThat(allow(InventoryAction.HOTBAR_SWAP, Side.BOTTOM, SlotType.CONTAINER, 20, OWN_VIEW,
                Kind.NONE, Kind.FREE)).as("number key").isTrue();
        assertThat(allow(InventoryAction.HOTBAR_SWAP, Side.BOTTOM, SlotType.ARMOR, SoulboundRules.OFFHAND_SLOT,
                OWN_VIEW, Kind.FREE, Kind.NONE)).as("number key onto the off-hand slot").isTrue();
    }

    @Test
    void freeItemsStayOutOfArmorSlotsAndTheCraftingGrid() {
        assertThat(allow(InventoryAction.PLACE_ALL, Side.BOTTOM, SlotType.ARMOR, 39, OWN_VIEW,
                Kind.NONE, Kind.FREE)).isFalse();
        assertThat(allow(InventoryAction.PLACE_ALL, Side.TOP, SlotType.CRAFTING, 1, OWN_VIEW,
                Kind.NONE, Kind.FREE)).isFalse();
        assertThat(allow(InventoryAction.HOTBAR_SWAP, Side.TOP, SlotType.CRAFTING, 2, OWN_VIEW,
                Kind.NONE, Kind.FREE)).isFalse();
    }

    @Test
    void theOwnerCanTakeAnItemBackOutOfTheCraftingGrid() {
        assertThat(allow(InventoryAction.PICKUP_ALL, Side.TOP, SlotType.CRAFTING, 1, OWN_VIEW,
                Kind.FREE, Kind.NONE)).isTrue();
        assertThat(allow(InventoryAction.MOVE_TO_OTHER_INVENTORY, Side.TOP, SlotType.CRAFTING, 1, OWN_VIEW,
                Kind.FREE, Kind.NONE)).isTrue();
        assertThat(allow(InventoryAction.PICKUP_ALL, Side.TOP, SlotType.RESULT, 0, OWN_VIEW,
                Kind.FREE, Kind.NONE)).isFalse();
        assertThat(allow(InventoryAction.PICKUP_ALL, Side.TOP, SlotType.CONTAINER, 1, CHEST_VIEW,
                Kind.FREE, Kind.NONE)).as("only the player's own grid").isFalse();
    }

    @Test
    void freeItemsNeverEnterAContainer() {
        assertThat(allow(InventoryAction.PLACE_ALL, Side.TOP, SlotType.CONTAINER, 4, CHEST_VIEW,
                Kind.NONE, Kind.FREE)).isFalse();
        assertThat(allow(InventoryAction.MOVE_TO_OTHER_INVENTORY, Side.BOTTOM, SlotType.CONTAINER, 12, CHEST_VIEW,
                Kind.FREE, Kind.NONE)).as("shift-click").isFalse();
        assertThat(allow(InventoryAction.COLLECT_TO_CURSOR, Side.BOTTOM, SlotType.CONTAINER, 12, CHEST_VIEW,
                Kind.NONE, Kind.FREE)).as("double-click").isFalse();
        assertThat(allow(InventoryAction.HOTBAR_SWAP, Side.TOP, SlotType.CONTAINER, 4, CHEST_VIEW,
                Kind.NONE, Kind.FREE)).as("number key over a chest slot").isFalse();
        assertThat(allow(InventoryAction.HOTBAR_SWAP, Side.TOP, SlotType.CONTAINER, 4, CHEST_VIEW,
                Kind.NONE, Kind.FREE)).as("F key over a chest slot").isFalse();
    }

    @Test
    void freeItemsCanBeRearrangedWhileAContainerIsOpen() {
        assertThat(allow(InventoryAction.PICKUP_ALL, Side.BOTTOM, SlotType.CONTAINER, 12, CHEST_VIEW,
                Kind.FREE, Kind.NONE)).isTrue();
        assertThat(allow(InventoryAction.HOTBAR_SWAP, Side.BOTTOM, SlotType.QUICKBAR, 1, CHEST_VIEW,
                Kind.NONE, Kind.FREE)).isTrue();
        assertThat(allow(InventoryAction.SWAP_WITH_CURSOR, Side.BOTTOM, SlotType.ARMOR, SoulboundRules.OFFHAND_SLOT,
                CHEST_VIEW, Kind.NONE, Kind.FREE)).as("no off-hand slot in a chest view").isFalse();
    }

    @Test
    void dropsBundlesAndCloningAreRefused() {
        assertThat(allow(InventoryAction.DROP_ALL_CURSOR, Side.OUTSIDE, SlotType.OUTSIDE, -999, OWN_VIEW,
                Kind.NONE, Kind.FREE)).isFalse();
        assertThat(allow(InventoryAction.DROP_ONE_SLOT, Side.BOTTOM, SlotType.CONTAINER, 12, OWN_VIEW,
                Kind.FREE, Kind.NONE)).isFalse();
        assertThat(allow(InventoryAction.PICKUP_ALL_INTO_BUNDLE, Side.BOTTOM, SlotType.CONTAINER, 12, OWN_VIEW,
                Kind.FREE, Kind.NONE)).isFalse();
        assertThat(allow(InventoryAction.PLACE_ALL_INTO_BUNDLE, Side.BOTTOM, SlotType.CONTAINER, 12, OWN_VIEW,
                Kind.NONE, Kind.FREE)).isFalse();
        assertThat(allow(InventoryAction.CLONE_STACK, Side.BOTTOM, SlotType.CONTAINER, 12, OWN_VIEW,
                Kind.FREE, Kind.NONE)).isFalse();
        assertThat(allow(InventoryAction.UNKNOWN, Side.BOTTOM, SlotType.CONTAINER, 12, OWN_VIEW,
                Kind.FREE, Kind.NONE)).isFalse();
    }

    @Test
    void recognisesTheFKeySwapInsideAnInventoryScreen() {
        assertThat(SoulboundRules.offhandSwap(InventoryAction.HOTBAR_SWAP, -1)).isTrue();
        assertThat(SoulboundRules.offhandSwap(InventoryAction.HOTBAR_SWAP, 3)).as("number key").isFalse();
        assertThat(SoulboundRules.offhandSwap(InventoryAction.PICKUP_ALL, -1)).isFalse();
    }

    @Test
    void dragsStayInsideThePlayersOwnSlots() {
        assertThat(SoulboundRules.allowDrag(Kind.NONE, false)).isTrue();
        assertThat(SoulboundRules.allowDrag(Kind.FREE, true)).isTrue();
        assertThat(SoulboundRules.allowDrag(Kind.FREE, false)).isFalse();
        assertThat(SoulboundRules.allowDrag(Kind.LOCKED, true)).isFalse();

        assertThat(SoulboundRules.ownViewPlayerSlot(0)).as("crafting result").isFalse();
        assertThat(SoulboundRules.ownViewPlayerSlot(8)).as("boots").isFalse();
        assertThat(SoulboundRules.ownViewPlayerSlot(9)).isTrue();
        assertThat(SoulboundRules.ownViewPlayerSlot(44)).isTrue();
        assertThat(SoulboundRules.ownViewPlayerSlot(45)).as("off-hand").isTrue();
        assertThat(SoulboundRules.ownViewPlayerSlot(46)).isFalse();
    }

    @Test
    void strongestKindWins() {
        assertThat(Kind.strongest()).isEqualTo(Kind.NONE);
        assertThat(Kind.strongest(Kind.NONE, Kind.FREE)).isEqualTo(Kind.FREE);
        assertThat(Kind.strongest(Kind.FREE, Kind.LOCKED, Kind.NONE)).isEqualTo(Kind.LOCKED);
    }
}
