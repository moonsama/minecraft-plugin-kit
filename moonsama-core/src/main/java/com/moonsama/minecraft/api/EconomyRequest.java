package com.moonsama.minecraft.api;

import java.util.List;

public record EconomyRequest(
        String businessKey,
        List<EconomyLine> items,
        String description,
        String reference
) {
    public EconomyRequest {
        items = List.copyOf(items);
    }
}
