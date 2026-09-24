package com.moonsama.minecraft.skins;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Persists which NFT skin each Minecraft player wears, plus the textures they had before so a
 * reset can restore them offline.
 *
 * <p>Records are keyed by the player's Minecraft account UUID only. Portal identities are
 * resolved through MoonsamaCore at runtime and never written here.
 */
public final class EquippedSkinStore {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final Path file;
    private final Map<UUID, Equipped> equipped = new LinkedHashMap<>();

    public EquippedSkinStore(Path file) {
        this.file = file;
    }

    public synchronized void load() {
        equipped.clear();
        if (!Files.exists(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            JsonObject players = root.getAsJsonObject("players");
            if (players == null) {
                return;
            }
            for (Map.Entry<String, JsonElement> entry : players.entrySet()) {
                JsonObject row = entry.getValue().getAsJsonObject();
                equipped.put(UUID.fromString(entry.getKey()), Equipped.fromJson(row));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed reading " + file, e);
        }
    }

    public synchronized Optional<Equipped> get(UUID mojangUuid) {
        return Optional.ofNullable(equipped.get(mojangUuid));
    }

    public synchronized Map<UUID, Equipped> all() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(equipped));
    }

    public synchronized void put(UUID mojangUuid, Equipped record) {
        equipped.put(mojangUuid, record);
        save();
    }

    public synchronized Optional<Equipped> remove(UUID mojangUuid) {
        Equipped removed = equipped.remove(mojangUuid);
        if (removed != null) {
            save();
        }
        return Optional.ofNullable(removed);
    }

    private void save() {
        JsonObject players = new JsonObject();
        for (Map.Entry<UUID, Equipped> entry : equipped.entrySet()) {
            players.add(entry.getKey().toString(), entry.getValue().toJson());
        }
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.add("players", players);
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

    /**
     * @param ref               the NFT currently worn
     * @param originalValue     the player's own textures value before any NFT skin, or null
     * @param originalSignature signature of {@code originalValue}, or null
     * @param equippedAt        when the current skin was chosen
     */
    public record Equipped(SkinRef ref, String originalValue, String originalSignature, Instant equippedAt) {
        public boolean hasOriginal() {
            return originalValue != null && originalSignature != null;
        }

        JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("collection", ref.collection());
            json.addProperty("tokenId", ref.tokenId());
            json.addProperty("equippedAt", equippedAt.toString());
            if (hasOriginal()) {
                JsonObject original = new JsonObject();
                original.addProperty("value", originalValue);
                original.addProperty("signature", originalSignature);
                json.add("original", original);
            }
            return json;
        }

        static Equipped fromJson(JsonObject json) {
            JsonObject original = json.getAsJsonObject("original");
            return new Equipped(
                    new SkinRef(json.get("collection").getAsString(), json.get("tokenId").getAsLong()),
                    original == null ? null : original.get("value").getAsString(),
                    original == null ? null : original.get("signature").getAsString(),
                    Instant.parse(json.get("equippedAt").getAsString())
            );
        }
    }
}
