package com.moonsama.minecraft.api;

import java.util.concurrent.CompletableFuture;

/**
 * Turns a raw 64×64 skin PNG into a Mojang-signed {@code textures} profile property that
 * players can wear. MoonsamaCore provides an implementation backed by MineSkin when
 * {@code MINESKIN_API_KEY} is configured; feature plugins never see the key.
 *
 * <p>Obtain it through {@code Bukkit.getServicesManager().load(SkinSigner.class)}. Results are
 * cached by content hash, so signing the same image twice costs one request.
 */
public interface SkinSigner {
    /** False when no signing backend is configured; {@link #sign} then fails immediately. */
    boolean isAvailable();

    /**
     * Signs a PNG. Completes off the main thread; may take several seconds while the backend
     * queues the request. Fails with {@link SigningException} for backend/config problems.
     */
    CompletableFuture<SignedTexture> sign(byte[] png, Variant variant);

    enum Variant {
        CLASSIC,
        SLIM
    }

    /**
     * @param value      base64 profile property value
     * @param signature  Mojang signature of {@code value}
     * @param textureUrl the texture on textures.minecraft.net
     */
    record SignedTexture(String value, String signature, String textureUrl) {
    }

    final class SigningException extends RuntimeException {
        public SigningException(String message) {
            super(message);
        }

        public SigningException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
