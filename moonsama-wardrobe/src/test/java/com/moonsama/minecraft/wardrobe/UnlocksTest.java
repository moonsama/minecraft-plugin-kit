package com.moonsama.minecraft.wardrobe;

import com.moonsama.minecraft.api.AssetHolding;
import com.moonsama.minecraft.compositor.CompositorData.SlotValue;
import com.moonsama.minecraft.compositor.SkinCompositor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnlocksTest {
    private static SkinCompositor compositor;
    private static Unlocks unlocks;

    @BeforeAll
    static void load() {
        compositor = SkinCompositor.load(UnlocksTest.class.getClassLoader());
        unlocks = new Unlocks(compositor);
    }

    private static AssetHolding holding(String collection, long tokenId) {
        return new AssetHolding(collection, Long.toString(tokenId), "1");
    }

    private static boolean offers(List<SlotValue> options, String asset) {
        return options.stream().anyMatch(v -> v.asset().equals(asset));
    }

    @Test
    void traitsOfOtherOwnedTokensUnlockParts() {
        // Moonsama #2 wears the aviator hat; #1 has no hat.
        List<AssetHolding> onlyOne = List.of(holding("moonsama", 1));
        List<AssetHolding> both = List.of(holding("moonsama", 1), holding("moonsama", 2));

        assertFalse(offers(unlocks.available("moonsama", "hat", onlyOne), "moonsama:aviator"));
        assertTrue(offers(unlocks.available("moonsama", "hat", both), "moonsama:aviator"));
    }

    @Test
    void ownPartsAreAlwaysAvailable() {
        List<AssetHolding> onlyOne = List.of(holding("moonsama", 1));
        assertTrue(offers(unlocks.available("moonsama", "hair", onlyOne), "moonsama:blue_slick"));
        assertTrue(offers(unlocks.available("moonsama", "outfit", onlyOne), "moonsama:rainbow_shirt"));
    }

    @Test
    void multiverseItemsUnlockThroughMoonsamaXHoldings() {
        List<AssetHolding> withoutItem = List.of(holding("moonsama", 1));
        List<AssetHolding> withItem = List.of(holding("moonsama", 1), holding("moonsama-x", 64));

        boolean anywhereWithout = compositor.customizableSlots("moonsama").stream()
                .anyMatch(slot -> offers(unlocks.available("moonsama", slot.referenceId(), withoutItem), "multiverse_items:amber_detectore"));
        boolean anywhereWith = compositor.customizableSlots("moonsama").stream()
                .anyMatch(slot -> offers(unlocks.available("moonsama", slot.referenceId(), withItem), "multiverse_items:amber_detectore"));

        assertFalse(anywhereWithout, "detectore must be locked without the Moonsama X token");
        assertTrue(anywhereWith, "Moonsama X #64 must unlock the amber detectore");
    }

    @Test
    void partsGatedOnContractsOutsidePortalStayLocked() {
        // Multiverse Costumes (0xc630…) is not a Portal collection; a generous wallet still cannot unlock it.
        List<AssetHolding> whale = List.of(holding("moonsama", 1), holding("moonsama", 2),
                holding("moonsama-x", 4), holding("moonsama-x", 37), holding("exosama", 1));
        Set<String> owned = unlocks.ownedParts("moonsama", whale);
        SlotValue partyHat = new SlotValue("costume", "multiverse-costumes", "multiverse_costumes:2nd_anniversary_party_hat");
        assertFalse(unlocks.isUnlocked(partyHat, owned, whale));
    }

    @Test
    void holdingsWithZeroBalanceDoNotCount() {
        List<AssetHolding> sold = List.of(holding("moonsama", 1), new AssetHolding("moonsama", "2", "0"));
        assertFalse(offers(unlocks.available("moonsama", "hat", sold), "moonsama:aviator"));
    }
}
