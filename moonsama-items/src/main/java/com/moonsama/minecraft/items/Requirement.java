package com.moonsama.minecraft.items;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.moonsama.minecraft.api.AssetHolding;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * What a player must hold in Portal to use a cosmetic: any of the listed clauses, each a
 * collection and optionally a specific token id.
 *
 * <p>JSON forms: {@code {"collection": "moonsama-x", "tokenId": 12}},
 * {@code {"collection": "pods"}} (any token) or {@code {"anyOf": [clause, clause]}}.
 * A {@code null} collection means the cosmetic cannot be unlocked through Portal.
 */
public record Requirement(List<Clause> anyOf) {
    public record Clause(String collection, Long tokenId) {
        boolean matches(AssetHolding holding) {
            if (collection == null || !collection.equals(holding.collection()) || !holding.hasPositiveBalance()) {
                return false;
            }
            return tokenId == null || Long.toString(tokenId).equals(normalize(holding.tokenId()));
        }
    }

    public static Requirement parse(JsonElement element) {
        List<Clause> clauses = new ArrayList<>();
        if (element != null && element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            if (object.has("anyOf") && object.get("anyOf").isJsonArray()) {
                JsonArray array = object.getAsJsonArray("anyOf");
                for (JsonElement clause : array) {
                    if (clause.isJsonObject()) {
                        clauses.add(clause(clause.getAsJsonObject()));
                    }
                }
            } else {
                clauses.add(clause(object));
            }
        }
        return new Requirement(List.copyOf(clauses));
    }

    private static Clause clause(JsonObject object) {
        String collection = object.has("collection") && !object.get("collection").isJsonNull()
                ? object.get("collection").getAsString() : null;
        Long tokenId = null;
        if (object.has("tokenId") && !object.get("tokenId").isJsonNull()) {
            try {
                tokenId = Long.parseLong(object.get("tokenId").getAsString().trim());
            } catch (NumberFormatException ignored) {
                tokenId = null;
            }
        }
        return new Clause(collection, tokenId);
    }

    /** Whether the requirement can be unlocked through Portal at all. */
    public boolean unlockable() {
        return anyOf.stream().anyMatch(clause -> clause.collection() != null);
    }

    public boolean satisfiedBy(List<AssetHolding> holdings) {
        for (Clause clause : anyOf) {
            for (AssetHolding holding : holdings) {
                if (clause.matches(holding)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Human readable form, e.g. {@code Multiverse Items #12} or {@code any Moonsama or Exosama}. */
    public String describe(Map<String, String> collectionNames) {
        if (!unlockable()) {
            return "not available through Portal";
        }
        return anyOf.stream()
                .filter(clause -> clause.collection() != null)
                .map(clause -> {
                    String name = collectionNames.getOrDefault(clause.collection(), clause.collection());
                    return clause.tokenId() == null ? "any " + name : name + " #" + clause.tokenId();
                })
                .distinct()
                .collect(Collectors.joining(" or "));
    }

    static String normalize(String tokenId) {
        if (tokenId == null) {
            return "";
        }
        try {
            return Long.toString(Long.parseLong(tokenId.trim()));
        } catch (NumberFormatException ignored) {
            return tokenId.trim();
        }
    }
}
