package com.moonsama.minecraft.api;

import java.util.List;

public record EconomyRefundRequest(
        String businessKey,
        String originalBusinessKey,
        List<EconomyLine> items,
        String description,
        String reference
) {
    public EconomyRefundRequest {
        items = items == null ? null : List.copyOf(items);
    }
}
