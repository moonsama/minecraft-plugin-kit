package com.moonsama.minecraft.skins;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moonsama.minecraft.api.SkinSigner;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Signed textures by PNG hash and variant, persisted as JSON so restarts do not re-upload
 * compositions. Signed textures are public data (they describe a Mojang-hosted image).
 */
public final class SignatureCache {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final Path file;
    private final Map<String, SkinSigner.SignedTexture> entries = new LinkedHashMap<>();

    public SignatureCache(Path file) {
        this.file = file;
    }

    public synchronized void load() {
        entries.clear();
        if (file == null || !Files.exists(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            JsonObject textures = root.getAsJsonObject("textures");
            if (textures == null) {
                return;
            }
            for (Map.Entry<String, JsonElement> entry : textures.entrySet()) {
                JsonObject row = entry.getValue().getAsJsonObject();
                entries.put(entry.getKey(), new SkinSigner.SignedTexture(
                        row.get("value").getAsString(),
                        row.get("signature").getAsString(),
                        row.has("url") && !row.get("url").isJsonNull() ? row.get("url").getAsString() : null));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed reading " + file, e);
        }
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized Optional<SkinSigner.SignedTexture> get(String hash, SkinSigner.Variant variant) {
        return Optional.ofNullable(entries.get(key(hash, variant)));
    }

    public synchronized void put(String hash, SkinSigner.Variant variant, SkinSigner.SignedTexture texture) {
        entries.put(key(hash, variant), texture);
        if (file != null) {
            save();
        }
    }

    private static String key(String hash, SkinSigner.Variant variant) {
        return variant.name().toLowerCase(Locale.ROOT) + ":" + hash;
    }

    private void save() {
        JsonObject textures = new JsonObject();
        for (Map.Entry<String, SkinSigner.SignedTexture> entry : entries.entrySet()) {
            JsonObject row = new JsonObject();
            row.addProperty("value", entry.getValue().value());
            row.addProperty("signature", entry.getValue().signature());
            row.addProperty("url", entry.getValue().textureUrl());
            textures.add(entry.getKey(), row);
        }
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.add("textures", textures);
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, GSON.toJson(root), StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed writing " + file, e);
        }
    }
}
