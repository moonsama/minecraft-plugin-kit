package com.moonsama.minecraft.items;

import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static com.moonsama.minecraft.items.ItemsService.RestorePlan.Action.*;

class ItemsServiceTest {
    @Test
    void managedCopySlotsFindsNoCopies() {
        ItemStack[] contents = new ItemStack[41];

        assertThat(ItemsService.managedCopySlots(contents, item -> false)).isEmpty();
    }

    @Test
    void managedCopySlotsFindsOneCopy() {
        ItemStack[] contents = new ItemStack[41];

        assertThat(ItemsService.managedCopySlots(contents, managedAt(7)))
                .containsExactly(7);
    }

    @Test
    void managedCopySlotsFindsThreeCopiesIncludingOffhand() {
        ItemStack[] contents = new ItemStack[41];

        assertThat(ItemsService.managedCopySlots(contents, managedAt(0, 12, 40)))
                .containsExactly(0, 12, 40);
    }

    @Test
    void managedCopySlotsIgnoresUnmanagedArmorEntries() {
        ItemStack[] contents = new ItemStack[41];

        assertThat(ItemsService.managedCopySlots(contents, managedAt(4, 40)))
                .containsExactly(4, 40);
    }

    @Test
    void planRestorePrioritizesMatchingWornCopy() {
        assertPlan(true, false, false, -1, ItemsService.HandState.EMPTY, SWEEP_ONLY, 40);
        assertPlan(true, true, true, 41, ItemsService.HandState.MATCHING, SWEEP_ONLY, 40);
        assertPlan(true, false, true, 3, ItemsService.HandState.STALE, SWEEP_ONLY, 40);
    }

    @Test
    void planRestoreMovesMatchingStorageCopyIntoAirOffhand() {
        assertPlan(false, false, true, 7, ItemsService.HandState.EMPTY, MOVE_TO_OFFHAND, 7);
        assertPlan(false, true, true, 4, ItemsService.HandState.OCCUPIED_REAL, MOVE_TO_OFFHAND, 4);
    }

    @Test
    void planRestoreBuildsWhenAirOffhandHasNoMatchingCopy() {
        assertPlan(false, false, true, -1, ItemsService.HandState.EMPTY, BUILD, -1);
    }

    @Test
    void planRestoreSweepsStaleWornCopyThenMovesMatchingCopy() {
        assertPlan(false, true, false, 9, ItemsService.HandState.EMPTY, SWEEP_STALE_THEN_MOVE, 9);
    }

    @Test
    void planRestoreSweepsStaleWornCopyThenBuilds() {
        assertPlan(false, true, false, -1, ItemsService.HandState.STALE, SWEEP_STALE_THEN_BUILD, -1);
    }

    @Test
    void planRestoreKeepsOneMatchingCopyBehindLegitimateItem() {
        assertPlan(false, false, false, 2, ItemsService.HandState.OCCUPIED_REAL, SWEEP_TO_ONE, 2);
        assertPlan(false, false, false, 39, ItemsService.HandState.OCCUPIED_REAL, SWEEP_TO_ONE, 39);
    }

    @Test
    void planRestoreLeavesSelectionDormantBehindLegitimateItemWithoutCopy() {
        assertPlan(false, false, false, -1, ItemsService.HandState.OCCUPIED_REAL,
                SWEEP_ALL_STALE_AND_DORMANT, -1);
        assertPlan(false, false, false, -2, ItemsService.HandState.OCCUPIED_REAL,
                SWEEP_ALL_STALE_AND_DORMANT, -1);
    }

    @Test
    void planRestoreKeepsMatchingMainHandCopy() {
        assertPlan(false, false, true, 41, ItemsService.HandState.MATCHING, KEEP_IN_HAND, 41);
        assertPlan(false, false, false, 41, ItemsService.HandState.MATCHING,
                SWEEP_CONTENTS_KEEP_HAND, 41);
    }

    @Test
    void planRestoreTreatsClearedStaleHandAsAbsent() {
        assertPlan(false, false, true, 7, ItemsService.HandState.STALE, MOVE_TO_OFFHAND, 7);
    }

    @Test
    void unitAmountDoesNotNeedNormalization() {
        assertThat(ItemsService.needsAmountNormalization(1)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 12})
    void stackedAmountsNeedNormalization(int amount) {
        assertThat(ItemsService.needsAmountNormalization(amount)).isTrue();
    }

    private static void assertPlan(boolean matching, boolean stale, boolean air, int matchingSlot,
                                   ItemsService.HandState handState,
                                   ItemsService.RestorePlan.Action action, int keepSlot) {
        assertThat(ItemsService.planRestore(matching, stale, air, matchingSlot, handState))
                .isEqualTo(new ItemsService.RestorePlan(action, keepSlot));
    }

    private static java.util.function.Predicate<ItemStack> managedAt(Integer... slots) {
        Set<Integer> managedSlots = Set.of(slots);
        AtomicInteger index = new AtomicInteger();
        return ignored -> managedSlots.contains(index.getAndIncrement());
    }
}