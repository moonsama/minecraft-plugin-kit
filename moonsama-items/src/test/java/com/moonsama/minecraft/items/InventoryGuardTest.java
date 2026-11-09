package com.moonsama.minecraft.items;

import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryType.SlotType;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.moonsama.minecraft.items.InventoryGuard.ClickOutcome.ALLOW;
import static com.moonsama.minecraft.items.InventoryGuard.ClickOutcome.BLOCK;
import static org.assertj.core.api.Assertions.assertThat;

class InventoryGuardTest {
    @Test
    @SuppressWarnings("removal")
    void classifiesEveryInventoryActionWithoutFailingOpen() {
        assertThat(InventoryAction.values()).containsExactlyInAnyOrder(
                InventoryAction.NOTHING,
                InventoryAction.PICKUP_ALL,
                InventoryAction.PICKUP_HALF,
                InventoryAction.PICKUP_ONE,
                InventoryAction.PICKUP_SOME,
                InventoryAction.PICKUP_FROM_BUNDLE,
                InventoryAction.PLACE_ALL,
                InventoryAction.PLACE_ONE,
                InventoryAction.PLACE_SOME,
                InventoryAction.SWAP_WITH_CURSOR,
                InventoryAction.COLLECT_TO_CURSOR,
                InventoryAction.HOTBAR_SWAP,
                InventoryAction.HOTBAR_MOVE_AND_READD,
                InventoryAction.MOVE_TO_OTHER_INVENTORY,
                InventoryAction.PICKUP_ALL_INTO_BUNDLE,
                InventoryAction.PICKUP_SOME_INTO_BUNDLE,
                InventoryAction.PLACE_ALL_INTO_BUNDLE,
                InventoryAction.PLACE_SOME_INTO_BUNDLE,
                InventoryAction.PLACE_FROM_BUNDLE,
                InventoryAction.CLONE_STACK,
                InventoryAction.DROP_ALL_CURSOR,
                InventoryAction.DROP_ALL_SLOT,
                InventoryAction.DROP_ONE_CURSOR,
                InventoryAction.DROP_ONE_SLOT,
                InventoryAction.UNKNOWN);

        assertThat(List.of(
                InventoryAction.NOTHING,
                InventoryAction.PICKUP_ALL,
                InventoryAction.PICKUP_HALF,
                InventoryAction.PICKUP_ONE,
                InventoryAction.PICKUP_SOME,
                InventoryAction.PICKUP_FROM_BUNDLE,
                InventoryAction.PLACE_ALL,
                InventoryAction.PLACE_ONE,
                InventoryAction.PLACE_SOME,
                InventoryAction.SWAP_WITH_CURSOR,
                InventoryAction.HOTBAR_SWAP,
                InventoryAction.HOTBAR_MOVE_AND_READD))
                .allMatch(action -> InventoryGuard.actionStaysPlayer(action, false));
        assertThat(List.of(
                InventoryAction.PICKUP_ALL_INTO_BUNDLE,
                InventoryAction.PICKUP_SOME_INTO_BUNDLE,
                InventoryAction.PLACE_ALL_INTO_BUNDLE,
                InventoryAction.PLACE_SOME_INTO_BUNDLE,
                InventoryAction.PLACE_FROM_BUNDLE,
                InventoryAction.CLONE_STACK,
                InventoryAction.DROP_ALL_CURSOR,
                InventoryAction.DROP_ALL_SLOT,
                InventoryAction.DROP_ONE_CURSOR,
                InventoryAction.DROP_ONE_SLOT,
                InventoryAction.UNKNOWN))
                .noneMatch(action -> InventoryGuard.actionStaysPlayer(action, true));
    }

    @Test
    void shiftMoveHonorsWhetherDestinationIsPlayerCrafting() {
        assertThat(InventoryGuard.actionStaysPlayer(InventoryAction.MOVE_TO_OTHER_INVENTORY, true)).isTrue();
        assertThat(InventoryGuard.actionStaysPlayer(InventoryAction.MOVE_TO_OTHER_INVENTORY, false)).isFalse();
    }

    @Test
    void collectToCursorOnlyStaysPlayerInOwnCraftingView() {
        assertThat(InventoryGuard.actionStaysPlayer(InventoryAction.COLLECT_TO_CURSOR, true)).isTrue();
        assertThat(InventoryGuard.actionStaysPlayer(InventoryAction.COLLECT_TO_CURSOR, false)).isFalse();
    }

    @Test
    void recognizesForeignOffhandSwapCaptureSignature() {
        assertThat(InventoryGuard.foreignOffhandSwap(InventoryAction.HOTBAR_SWAP, -1, true)).isTrue();
    }

    @Test
    void rejectsNumberKeySwapAsForeignOffhandSwap() {
        assertThat(InventoryGuard.foreignOffhandSwap(InventoryAction.HOTBAR_SWAP, 3, true)).isFalse();
    }

    @Test
    void rejectsBottomInventorySwapAsForeignOffhandSwap() {
        assertThat(InventoryGuard.foreignOffhandSwap(InventoryAction.HOTBAR_SWAP, -1, false)).isFalse();
    }

    @Test
    void rejectsCursorSwapAsForeignOffhandSwap() {
        assertThat(InventoryGuard.foreignOffhandSwap(InventoryAction.SWAP_WITH_CURSOR, -1, true)).isFalse();
    }

    @Test
    void rejectsShiftMoveAsForeignOffhandSwap() {
        assertThat(InventoryGuard.foreignOffhandSwap(InventoryAction.MOVE_TO_OTHER_INVENTORY, -1, true)).isFalse();
    }

    @Test
    void blocksTopInventoryClicks() {
        assertOutcome(true, SlotType.CONTAINER, 20, false, true, BLOCK);
    }

    @Test
    void allowsPlayerStorageWhenDestinationStaysPlayer() {
        assertOutcome(false, SlotType.CONTAINER, 20, true, true, ALLOW);
    }

    @Test
    void allowsPlayerQuickbarWhenDestinationStaysPlayer() {
        assertOutcome(false, SlotType.QUICKBAR, 4, true, true, ALLOW);
    }

    @Test
    void allowsPlayerRelativeOffhandWhenDestinationStaysPlayer() {
        assertOutcome(false, SlotType.ARMOR, 40, true, true, ALLOW);
    }

    @Test
    void allowsViewGlobalOffhandWhenDestinationStaysPlayer() {
        assertOutcome(false, SlotType.ARMOR, 45, true, true, ALLOW);
    }

    @Test
    void recognizesArmorTypedOffhandBeforeBlockingArmorSlots() {
        assertOutcome(false, SlotType.ARMOR, 40, true, true, ALLOW);
    }

    @Test
    void blocksArmorSlots() {
        for (int index : new int[]{36, 37, 38, 39, 5, 6, 7, 8}) {
            assertOutcome(false, SlotType.ARMOR, index, true, true, BLOCK);
        }
    }

    @Test
    void externalViewBottomStorageSlotsAreIndexAgnostic() {
        assertOutcome(false, SlotType.CONTAINER, 36, false, true, ALLOW);
        assertOutcome(false, SlotType.QUICKBAR, 5, false, true, ALLOW);
    }

    @Test
    void blocksCraftingSlots() {
        assertOutcome(false, SlotType.CRAFTING, 1, true, true, BLOCK);
    }

    @Test
    void blocksResultSlots() {
        assertOutcome(false, SlotType.RESULT, 0, true, true, BLOCK);
    }

    @Test
    void blocksStorageWhenDestinationEscapesPlayer() {
        assertOutcome(false, SlotType.CONTAINER, 20, true, false, BLOCK);
    }

    @Test
    void blocksQuickbarWhenDestinationEscapesPlayer() {
        assertOutcome(false, SlotType.QUICKBAR, 4, true, false, BLOCK);
    }

    @Test
    void blocksPlayerRelativeOffhandWhenDestinationEscapesPlayer() {
        assertOutcome(false, SlotType.ARMOR, 40, true, false, BLOCK);
    }

    @Test
    void blocksViewGlobalOffhandWhenDestinationEscapesPlayer() {
        assertOutcome(false, SlotType.ARMOR, 45, true, false, BLOCK);
    }

    @Test
    void blocksUnknownPlayerIndex() {
        assertOutcome(false, SlotType.ARMOR, -1, true, true, BLOCK);
    }

    @Test
    void allowsCursorSwapForOffhandWithCloseGuard() {
        assertOutcome(false, SlotType.ARMOR, 40, true, true, ALLOW);
    }

    @Test
    void findsFirstEmptyStorageIndexWhenAllSlotsAreEmpty() {
        assertThat(InventoryGuard.firstEmptyStorageIndex(new boolean[36])).isZero();
    }

    @Test
    void findsFirstEmptyStorageIndexInMiddle() {
        assertThat(InventoryGuard.firstEmptyStorageIndex(new boolean[]{true, true, false, true})).isEqualTo(2);
    }

    @Test
    void returnsNegativeOneWhenStorageIsFull() {
        assertThat(InventoryGuard.firstEmptyStorageIndex(new boolean[]{true, true, true})).isEqualTo(-1);
    }

    @Test
    void containsManagedMatchesAnyListEntry() {
        AtomicInteger inspected = new AtomicInteger();

        assertThat(InventoryGuard.containsManaged(Arrays.asList(null, null),
                ignored -> inspected.incrementAndGet() == 2)).isTrue();
        assertThat(inspected).hasValue(2);
        assertThat(InventoryGuard.containsManaged(Arrays.asList(null, null), ignored -> false)).isFalse();
        assertThat(InventoryGuard.containsManaged(List.of(), ignored -> true)).isFalse();
    }

    private static void assertOutcome(
            boolean clickedTop,
            SlotType slotType,
            int clickedSlotIndex,
            boolean bottomIsPlayerInventory,
            boolean destinationStaysPlayer,
            InventoryGuard.ClickOutcome expected) {
        assertThat(InventoryGuard.classifyOffhandClick(
                clickedTop,
                slotType,
                clickedSlotIndex,
                bottomIsPlayerInventory,
                destinationStaysPlayer)).isEqualTo(expected);
    }
}
