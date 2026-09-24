package com.moonsama.minecraft.skins;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * In-memory index of the bundled signed skins, keyed by Portal collection and token id.
 *
 * <p>The data lives in {@code moonsama/cosmetics/skins/<collection>.jsonl} on the class path
 * (one JSON object per line: {@code id}, {@code value}, {@code signature}, {@code model}).
 * Loading is read-only and needs no network access.
 */
public final class SkinCatalog {
    public static final String RESOURCE_ROOT = "moonsama/cosmetics/skins/";

    private final Map<String, Map<Long, SignedSkin>> byCollection;

    private SkinCatalog(Map<String, Map<Long, SignedSkin>> byCollection) {
        this.byCollection = byCollection;
    }

    /**
     * Loads the requested collections from the class path. Collections without a data file are
     * reported through {@link #missingCollections(List)} instead of failing the load.
     */
    public static SkinCatalog load(ClassLoader loader, List<String> collections) {
        return load(collections, name -> loader.getResourceAsStream(RESOURCE_ROOT + name + ".jsonl"));
    }

    static SkinCatalog load(List<String> collections, Function<String, InputStream> opener) {
        Map<String, Map<Long, SignedSkin>> loaded = new LinkedHashMap<>();
        for (String collection : collections) {
            try (InputStream in = opener.apply(collection)) {
                if (in == null) {
                    continue;
                }
                loaded.put(collection, Collections.unmodifiableMap(parse(collection, in)));
            } catch (IOException e) {
                throw new UncheckedIOException("Failed reading skins for " + collection, e);
            }
        }
        return new SkinCatalog(Collections.unmodifiableMap(loaded));
    }

    private static Map<Long, SignedSkin> parse(String collection, InputStream in) throws IOException {
        Map<Long, SignedSkin> skins = new LinkedHashMap<>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        int lineNumber = 0;
        while ((line = reader.readLine()) != null) {
            lineNumber++;
            if (line.isBlank()) {
                continue;
            }
            JsonObject row;
            try {
                row = JsonParser.parseString(line).getAsJsonObject();
            } catch (JsonSyntaxException | IllegalStateException e) {
                throw new IOException(collection + ".jsonl line " + lineNumber + " is not a JSON object", e);
            }
            long id = row.get("id").getAsLong();
            String model = row.has("model") && !row.get("model").isJsonNull()
                    ? row.get("model").getAsString()
                    : "classic";
            skins.put(id, new SignedSkin(
                    new SkinRef(collection, id),
                    row.get("value").getAsString(),
                    row.get("signature").getAsString(),
                    model
            ));
        }
        return skins;
    }

    public Set<String> collections() {
        return byCollection.keySet();
    }

    public List<String> missingCollections(List<String> requested) {
        return requested.stream().filter(c -> !byCollection.containsKey(c)).toList();
    }

    public int size(String collection) {
        Map<Long, SignedSkin> skins = byCollection.get(collection);
        return skins == null ? 0 : skins.size();
    }

    public int size() {
        return byCollection.values().stream().mapToInt(Map::size).sum();
    }

    public Optional<SignedSkin> find(SkinRef ref) {
        Map<Long, SignedSkin> skins = byCollection.get(ref.collection());
        return skins == null ? Optional.empty() : Optional.ofNullable(skins.get(ref.tokenId()));
    }

    public Optional<SignedSkin> find(String collection, String tokenId) {
        try {
            return find(new SkinRef(collection, Long.parseLong(tokenId.trim())));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }
}
