package com.moonsama.minecraft.items;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ItemsServiceTest {
    private static final int HELD = 2;

    @Test
    void keepsTheCopyInTheOffhandFirst() {
        assertThat(ItemsService.copyToKeep(List.of(HELD, 17, ItemsService.OFFHAND_SLOT), HELD))
                .isEqualTo(ItemsService.OFFHAND_SLOT);
    }

    @Test
    void thenTheOneInTheMainHand() {
        assertThat(ItemsService.copyToKeep(List.of(17, HELD), HELD)).isEqualTo(HELD);
    }

    @Test
    void thenTheFirstOneFound() {
        assertThat(ItemsService.copyToKeep(List.of(5, 17), HELD)).isEqualTo(5);
    }

    @Test
    void noneWhenThereIsNoCopy() {
        assertThat(ItemsService.copyToKeep(List.of(), HELD)).isEqualTo(-1);
    }
}
