package com.moonsama.minecraft.compositor;

import com.moonsama.minecraft.compositor.CompositorData.SlotValue;

import java.util.List;
import java.util.Objects;

/**
 * What to render: the main Composer collection, the token (for token-specific components such
 * as the neon Moonsama variants) and the assets placed in slots.
 *
 * @param collection Composer collection referenceId, e.g. {@code moonsama} or {@code gromlins}
 * @param tokenId    token id, or null for a composition that belongs to no token
 * @param slots      slot values; a slot value's collection defaults to {@code collection}
 */
public record Composition(String collection, Long tokenId, List<SlotValue> slots) {
    public Composition {
        Objects.requireNonNull(collection, "collection");
        slots = List.copyOf(slots);
    }

    public static Composition of(String collection, long tokenId, List<SlotValue> slots) {
        return new Composition(collection, tokenId, slots);
    }
}
