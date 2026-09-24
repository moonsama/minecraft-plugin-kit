package com.moonsama.minecraft.wardrobe;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moonsama.minecraft.compositor.CompositorData.SlotValue;
import com.moonsama.minecraft.skins.SkinRef;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Persists wardrobe looks per Minecraft account and NFT as JSON.
 *
 * <pre>{@code
 * {"version":1,"players":{"<uuid>":{"moonsama:123":{"hat":{"collection":"moonsama","asset":"moonsama:aviator"},"hair":null}}}}
 * }</pre>
 *
 * <p>Only Minecraft UUIDs and Portal collection/token ids are stored; no Portal identifiers.
 */
public final class WardrobeStore {
    // serializeNulls: a cleared slot is stored as an explicit null.
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private final Path file;
    private final Map<UUID, Map<String, Look>> looks = new HashMap<>();

    public WardrobeStore(Path file) {
        this.file = file;
    }

    public synchronized void load() {
        looks.clear();
        if (file == null || !Files.exists(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            JsonObject players = root.getAsJsonObject("players");
            if (players == null) {
                return;
            }
            for (Map.Entry<String, JsonElement> player : players.entrySet()) {
                UUID uuid = UUID.fromString(player.getKey());
                Map<String, Look> perSkin = new LinkedHashMap<>();
                for (Map.Entry<String, JsonElement> skin : player.getValue().getAsJsonObject().entrySet()) {
                    perSkin.put(skin.getKey(), readLook(skin.getValue().getAsJsonObject()));
                }
                looks.put(uuid, perSkin);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized Optional<Look> get(UUID mojangUuid, SkinRef ref) {
        Map<String, Look> perSkin = looks.get(mojangUuid);
        return perSkin == null ? Optional.empty() : Optional.ofNullable(perSkin.get(ref.key())).map(l -> new Look(l.rawOverrides()));
    }

    public synchronized void put(UUID mojangUuid, SkinRef ref, Look look) {
        if (look == null || look.rawOverrides().isEmpty()) {
            remove(mojangUuid, ref);
            return;
        }
        looks.computeIfAbsent(mojangUuid, k -> new LinkedHashMap<>()).put(ref.key(), new Look(look.rawOverrides()));
        save();
    }

    public synchronized void remove(UUID mojangUuid, SkinRef ref) {
        Map<String, Look> perSkin = looks.get(mojangUuid);
        if (perSkin != null && perSkin.remove(ref.key()) != null) {
            if (perSkin.isEmpty()) {
                looks.remove(mojangUuid);
            }
            save();
        }
    }

    public synchronized void forget(UUID mojangUuid) {
        if (looks.remove(mojangUuid) != null) {
            save();
        }
    }

    public synchronized int size() {
        return looks.values().stream().mapToInt(Map::size).sum();
    }

    private void save() {
        if (file == null) {
            return;
        }
        JsonObject players = new JsonObject();
        looks.forEach((uuid, perSkin) -> {
            JsonObject skins = new JsonObject();
            perSkin.forEach((key, look) -> skins.add(key, writeLook(look)));
            players.add(uuid.toString(), skins);
        });
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.add("players", players);
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Look readLook(JsonObject json) {
        Map<String, SlotValue> overrides = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isJsonNull()) {
                overrides.put(entry.getKey(), null);
            } else {
                JsonObject value = entry.getValue().getAsJsonObject();
                overrides.put(entry.getKey(), new SlotValue(
                        entry.getKey(),
                        value.has("collection") && !value.get("collection").isJsonNull() ? value.get("collection").getAsString() : null,
                        value.get("asset").getAsString()));
            }
        }
        return new Look(overrides);
    }

    private static JsonObject writeLook(Look look) {
        JsonObject json = new JsonObject();
        look.rawOverrides().forEach((slot, value) -> {
            if (value == null) {
                json.add(slot, JsonNull.INSTANCE);
            } else {
                JsonObject v = new JsonObject();
                v.addProperty("collection", value.collection());
                v.addProperty("asset", value.asset());
                json.add(slot, v);
            }
        });
        return json;
    }
}
