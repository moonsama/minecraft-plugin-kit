package com.moonsama.minecraft.soulbound;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns a Mojang UUID into the owner tag stored on bound items: an HMAC-SHA256 under a per-server
 * secret, so item data (which game clients can read) never reveals who owns it.
 */
final class OwnerTags {
    static final int KEY_BYTES = 32;
    private static final int TAG_BYTES = 16;
    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;
    private final Map<UUID, String> cache = new ConcurrentHashMap<>();

    OwnerTags(byte[] secret) {
        if (secret.length != KEY_BYTES) {
            throw new IllegalArgumentException("soulbound key must be " + KEY_BYTES + " bytes, got " + secret.length);
        }
        this.key = new SecretKeySpec(secret.clone(), ALGORITHM);
    }

    /** Reads the secret from {@code file}, creating it with fresh random bytes on first use. */
    static OwnerTags load(Path file) {
        try {
            if (Files.notExists(file)) {
                create(file);
            }
            return new OwnerTags(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the soulbound key " + file, e);
        }
    }

    private static void create(Path file) throws IOException {
        byte[] secret = new byte[KEY_BYTES];
        new SecureRandom().nextBytes(secret);
        Files.createDirectories(file.toAbsolutePath().getParent());
        try {
            Files.write(file, secret, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (FileAlreadyExistsException ignored) {
            return; // created concurrently; use whatever is there
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // not a POSIX file system
        }
    }

    String tagOf(UUID mojangUuid) {
        return cache.computeIfAbsent(mojangUuid, this::compute);
    }

    void forget(UUID mojangUuid) {
        cache.remove(mojangUuid);
    }

    private String compute(UUID mojangUuid) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            byte[] digest = mac.doFinal(mojangUuid.toString().getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(digest, TAG_BYTES));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }
}
