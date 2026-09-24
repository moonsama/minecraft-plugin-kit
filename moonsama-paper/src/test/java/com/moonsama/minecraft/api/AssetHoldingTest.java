package com.moonsama.minecraft.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AssetHoldingTest {
    @Test
    void detectsPositiveDecimalBalances() {
        assertThat(new AssetHolding("gold", "0", "0").hasPositiveBalance()).isFalse();
        assertThat(new AssetHolding("gold", "0", "0.000").hasPositiveBalance()).isFalse();
        assertThat(new AssetHolding("gold", "0", "0.5").hasPositiveBalance()).isTrue();
    }
}
