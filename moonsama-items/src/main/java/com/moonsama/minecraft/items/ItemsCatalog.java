package com.moonsama.minecraft.items;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Material;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * The bundled cosmetics tables ({@code moonsama/cosmetics/item-skins.json},
 * {@code offhands.json}, {@code hats.json}) plus lazy access to the default composition
 * of each NFT for hat selection.
 */
public final class ItemsCatalog {
    private static final String ROOT = "moonsama/cosmetics/";

    private final Function<String, InputStream> opener;
    private final Map<String, ItemSkin> itemSkins;
    private final Map<String, Offhand> offhands;
    private final HatRules hats;
    private final List<String> warnings;
    private final Map<String, Map<Long, Map<String, String>>> compositions = new HashMap<>();

    private ItemsCatalog(Function<String, InputStream> opener, Map<String, ItemSkin> itemSkins,
                         Map<String, Offhand> offhands, HatRules hats, List<String> warnings) {
        this.opener = opener;
        this.itemSkins = itemSkins;
        this.offhands = offhands;
        this.hats = hats;
        this.warnings = warnings;
    }

    public static ItemsCatalog load(ClassLoader loader) {
        return load(path -> loader.getResourceAsStream(ROOT + path));
    }

    /** @param opener resolves paths relative to the cosmetics root, returning null when absent */
    public static ItemsCatalog load(Function<String, InputStream> opener) {
        List<String> warnings = new ArrayList<>();
        Map<String, ItemSkin> skins = new LinkedHashMap<>();
        JsonElement skinsJson = read(opener, "item-skins.json");
        if (skinsJson != null) {
            for (JsonElement element : skinsJson.getAsJsonArray()) {
                parseItemSkin(element.getAsJsonObject(), warnings).ifPresent(skin -> skins.merge(skin.identifier(), skin,
                        (existing, added) -> merge(existing, added, warnings)));
            }
        } else {
            warnings.add("item-skins.json is missing");
        }

        Map<String, Offhand> offhands = new LinkedHashMap<>();
        JsonElement offhandsJson = read(opener, "offhands.json");
        if (offhandsJson != null) {
            for (JsonElement element : offhandsJson.getAsJsonObject().getAsJsonArray("offhands")) {
                parseOffhand(element.getAsJsonObject(), warnings).ifPresent(offhand -> offhands.put(offhand.id(), offhand));
            }
        } else {
            warnings.add("offhands.json is missing");
        }

        JsonElement hatsJson = read(opener, "hats.json");
        HatRules hats = hatsJson != null
                ? parseHats(hatsJson.getAsJsonObject(), warnings)
                : new HatRules(Material.PAPER, Set.of(), List.of());
        if (hatsJson == null) {
            warnings.add("hats.json is missing");
        }
        return new ItemsCatalog(opener, Collections.unmodifiableMap(skins), Collections.unmodifiableMap(offhands),
                hats, List.copyOf(warnings));
    }

    public List<String> warnings() {
        return warnings;
    }

    public Map<String, ItemSkin> itemSkins() {
        return itemSkins;
    }

    public Map<String, Offhand> offhands() {
        return offhands;
    }

    public HatRules hats() {
        return hats;
    }

    public Optional<ItemSkin> itemSkin(String identifier) {
        return Optional.ofNullable(itemSkins.get(normalizeId(identifier)));
    }

    public Optional<Offhand> offhand(String id) {
        return Optional.ofNullable(offhands.get(normalizeId(id)));
    }

    private static String normalizeId(String id) {
        if (id == null) {
            return "";
        }
        String lower = id.toLowerCase(Locale.ROOT);
        return lower.contains(":") ? lower : "moonsama:" + lower;
    }

    /**
     * The default composition of an NFT as {@code slot -> asset}, empty when the collection
     * has no compositions (e.g. gromlins) or the token is unknown.
     */
    public synchronized Map<String, String> composition(String portalCollection, long tokenId) {
        Map<Long, Map<String, String>> byToken = compositions.computeIfAbsent(portalCollection, this::loadCompositions);
        return byToken.getOrDefault(tokenId, Map.of());
    }

    private Map<Long, Map<String, String>> loadCompositions(String portalCollection) {
        Map<Long, Map<String, String>> result = new HashMap<>();
        try (InputStream in = opener.apply("compositor/compositions/" + portalCollection + ".jsonl")) {
            if (in == null) {
                return result;
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonObject row = JsonParser.parseString(line).getAsJsonObject();
                Map<String, String> slots = new LinkedHashMap<>();
                for (JsonElement element : row.getAsJsonArray("slots")) {
                    JsonObject slot = element.getAsJsonObject();
                    if (slot.has("value") && !slot.get("value").isJsonNull()) {
                        slots.put(slot.get("slot").getAsString(), slot.get("value").getAsString());
                    }
                }
                result.put(row.get("id").getAsLong(), Collections.unmodifiableMap(slots));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed reading compositions for " + portalCollection, e);
        }
        return result;
    }

    // ---------------------------------------------------------------- parsing

    private static Optional<ItemSkin> parseItemSkin(JsonObject json, List<String> warnings) {
        String identifier = json.get("identifier").getAsString();
        List<Material> materials = new ArrayList<>();
        for (JsonElement element : json.getAsJsonArray("materials")) {
            Material material = Material.matchMaterial(element.getAsString());
            if (material == null) {
                warnings.add(identifier + ": unknown material " + element.getAsString());
            } else {
                materials.add(material);
            }
        }
        if (materials.isEmpty()) {
            warnings.add(identifier + ": no usable materials, skipped");
            return Optional.empty();
        }
        return Optional.of(new ItemSkin(
                identifier,
                string(json, "name", identifier),
                string(json, "description", ""),
                json.has("rarity") ? json.get("rarity").getAsInt() : 0,
                string(json, "group", ""),
                json.get("customModelData").getAsInt(),
                List.copyOf(materials),
                Requirement.parse(json.get("requirement"))
        ));
    }

    /**
     * The legacy data lists one row per material group for a few skins (e.g. the carrot tools);
     * they share an identifier and model data, so they become one skin covering every material.
     */
    private static ItemSkin merge(ItemSkin existing, ItemSkin added, List<String> warnings) {
        if (existing.customModelData() != added.customModelData()) {
            warnings.add(existing.identifier() + ": duplicate identifier with different custom model data, keeping the first");
            return existing;
        }
        List<Material> materials = new ArrayList<>(existing.materials());
        for (Material material : added.materials()) {
            if (!materials.contains(material)) {
                materials.add(material);
            }
        }
        String group = existing.group().equals(added.group()) ? existing.group() : "tools";
        return new ItemSkin(existing.identifier(), existing.name(), existing.description(), existing.rarity(),
                group, existing.customModelData(), List.copyOf(materials), existing.requirement());
    }

    private static Optional<Offhand> parseOffhand(JsonObject json, List<String> warnings) {
        String id = json.get("id").getAsString();
        Material material = Material.matchMaterial(json.get("material").getAsString());
        if (material == null) {
            warnings.add(id + ": unknown material " + json.get("material").getAsString() + ", skipped");
            return Optional.empty();
        }
        return Optional.of(new Offhand(
                id,
                string(json, "name", id),
                material,
                json.get("customModelData").getAsInt(),
                string(json, "legacyPerk", null),
                Requirement.parse(json.get("requirement"))
        ));
    }

    private static HatRules parseHats(JsonObject json, List<String> warnings) {
        Material material = Material.matchMaterial(string(json, "material", "minecraft:paper"));
        if (material == null) {
            warnings.add("hats.json: unknown material, using paper");
            material = Material.PAPER;
        }
        Set<String> hidden = new LinkedHashSet<>();
        if (json.has("hiddenByCostumes")) {
            for (JsonElement element : json.getAsJsonArray("hiddenByCostumes")) {
                hidden.add(element.getAsString());
            }
        }
        List<HatRules.Rule> rules = new ArrayList<>();
        JsonArray array = json.has("rules") ? json.getAsJsonArray("rules") : new JsonArray();
        for (JsonElement element : array) {
            JsonObject rule = element.getAsJsonObject();
            Set<String> collections = new LinkedHashSet<>();
            if (rule.has("collections")) {
                for (JsonElement collection : rule.getAsJsonArray("collections")) {
                    collections.add(collection.getAsString());
                }
            }
            Map<String, String> requires = new LinkedHashMap<>();
            if (rule.has("requires") && rule.get("requires").isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : rule.getAsJsonObject("requires").entrySet()) {
                    requires.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
            rules.add(new HatRules.Rule(
                    string(rule, "name", "Hat"),
                    string(rule, "slot", "hat"),
                    rule.get("asset").getAsString(),
                    rule.get("customModelData").getAsInt(),
                    Set.copyOf(collections),
                    Collections.unmodifiableMap(requires)
            ));
        }
        return new HatRules(material, Set.copyOf(hidden), List.copyOf(rules));
    }

    private static String string(JsonObject json, String key, String fallback) {
        JsonElement value = json.get(key);
        return value == null || value.isJsonNull() ? fallback : value.getAsString();
    }

    private static JsonElement read(Function<String, InputStream> opener, String path) {
        try (InputStream in = opener.apply(path)) {
            if (in == null) {
                return null;
            }
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed reading " + path, e);
        }
    }
}
