package com.moonsama.minecraft.skins;

import java.util.Objects;

/**
 * A Mojang-signed {@code textures} profile property for one NFT.
 *
 * @param ref       the NFT this skin renders
 * @param value     base64 encoded textures payload (as sent to clients)
 * @param signature Mojang signature over {@code value}
 * @param model     {@code "classic"} or {@code "slim"}
 */
public record SignedSkin(SkinRef ref, String value, String signature, String model) {
    public SignedSkin {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(signature, "signature");
        model = model == null || model.isBlank() ? "classic" : model;
    }
}
