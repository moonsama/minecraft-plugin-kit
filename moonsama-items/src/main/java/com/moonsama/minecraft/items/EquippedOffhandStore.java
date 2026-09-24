package com.moonsama.minecraft.items;

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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Remembers which cosmetic off-hand each Minecraft player chose so it can be handed back after
 * a death or a rejoin. Keyed by Minecraft account UUID only; no Portal identifiers are stored.
 */
public final class EquippedOffhandStore {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final Path file;
    private final Map<UUID, String> equipped = new LinkedHashMap<>();

    public EquippedOffhandStore(Path file) {
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
                equipped.put(UUID.fromString(entry.getKey()), entry.getValue().getAsString());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed reading " + file, e);
        }
    }

    public synchronized Optional<String> get(UUID mojangUuid) {
        return Optional.ofNullable(equipped.get(mojangUuid));
    }

    public synchronized void put(UUID mojangUuid, String offhandId) {
        equipped.put(mojangUuid, offhandId);
        save();
    }

    public synchronized Optional<String> remove(UUID mojangUuid) {
        String previous = equipped.remove(mojangUuid);
        if (previous != null) {
            save();
        }
        return Optional.ofNullable(previous);
    }

    private void save() {
        JsonObject players = new JsonObject();
        for (Map.Entry<UUID, String> entry : equipped.entrySet()) {
            players.addProperty(entry.getKey().toString(), entry.getValue());
        }
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.add("players", players);
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed writing " + file, e);
        }
    }
}
