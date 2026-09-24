package com.moonsama.minecraft.skins;

import java.util.Objects;

/**
 * Identifies an NFT by its Portal collection slug and token id.
 */
public record SkinRef(String collection, long tokenId) {
    public SkinRef {
        Objects.requireNonNull(collection, "collection");
        if (tokenId < 0) {
            throw new IllegalArgumentException("tokenId must not be negative");
        }
    }

    public String key() {
        return collection + ":" + tokenId;
    }

    @Override
    public String toString() {
        return key();
    }
}
