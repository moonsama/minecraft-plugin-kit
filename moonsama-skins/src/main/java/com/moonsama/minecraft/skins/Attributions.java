package com.moonsama.minecraft.skins;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Artist credits for artist-made collections, from the bundled
 * {@code moonsama/cosmetics/attributions.json} ({@code {collection: {tokenId: {artist,
 * collection, piece}}}}). Collections without credits simply return empty.
 */
public final class Attributions {
    public static final String RESOURCE = "moonsama/cosmetics/attributions.json";

    /** @param collection the artist's own collection name, not the Portal collection */
    public record Credit(String artist, String collection, String piece) {
    }

    private final Map<String, Map<String, Credit>> credits;

    private Attributions(Map<String, Map<String, Credit>> credits) {
        this.credits = credits;
    }

    public static Attributions empty() {
        return new Attributions(Map.of());
    }

    public static Attributions load(ClassLoader loader) {
        try (InputStream in = loader.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return empty();
            }
            return parse(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed reading " + RESOURCE, e);
        }
    }

    static Attributions parse(InputStream in) {
        JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        Map<String, Map<String, Credit>> credits = new HashMap<>();
        for (Map.Entry<String, JsonElement> collection : root.entrySet()) {
            Map<String, Credit> perToken = new HashMap<>();
            for (Map.Entry<String, JsonElement> token : collection.getValue().getAsJsonObject().entrySet()) {
                JsonObject credit = token.getValue().getAsJsonObject();
                perToken.put(token.getKey(), new Credit(
                        credit.get("artist").getAsString(),
                        credit.has("collection") ? credit.get("collection").getAsString() : null,
                        credit.has("piece") ? credit.get("piece").getAsString() : null));
            }
            credits.put(collection.getKey().toLowerCase(Locale.ROOT), Map.copyOf(perToken));
        }
        return new Attributions(Map.copyOf(credits));
    }

    public Optional<Credit> find(SkinRef ref) {
        Map<String, Credit> perToken = credits.get(ref.collection().toLowerCase(Locale.ROOT));
        return perToken == null ? Optional.empty() : Optional.ofNullable(perToken.get(Long.toString(ref.tokenId())));
    }

    public boolean hasCredits(String collection) {
        return credits.containsKey(collection.toLowerCase(Locale.ROOT));
    }

    public int size() {
        return credits.values().stream().mapToInt(Map::size).sum();
    }
}
