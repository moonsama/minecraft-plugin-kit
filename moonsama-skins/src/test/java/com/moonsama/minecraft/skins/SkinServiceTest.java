package com.moonsama.minecraft.skins;

import com.moonsama.minecraft.api.AssetHolding;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SkinServiceTest {
    @Test
    void ownershipMatchesCollectionAndNormalizedTokenIdWithPositiveBalance() {
        List<AssetHolding> holdings = List.of(
                new AssetHolding("moonsama", "0042", "1"),
                new AssetHolding("exosama", "7", "0"),
                new AssetHolding("gromlin", "9", null)
        );

        assertThat(SkinService.owns(holdings, new SkinRef("moonsama", 42))).isTrue();
        assertThat(SkinService.owns(holdings, new SkinRef("moonsama", 43))).isFalse();
        assertThat(SkinService.owns(holdings, new SkinRef("exosama", 7))).isFalse();
        assertThat(SkinService.owns(holdings, new SkinRef("gromlin", 9))).isFalse();
        assertThat(SkinService.owns(holdings, new SkinRef("moonsama-embassy", 42))).isFalse();
    }
}
