package com.moonsama.minecraft.compositor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Read-only view of {@code cosmetics-data/compositor}: collections, slots, assets, components and
 * layer images. Loaded once and shared; image files are cached after first use.
 */
public final class CompositorData {
    public static final String RESOURCE_ROOT = "moonsama/cosmetics/compositor/";

    private final Map<String, String> portalToComposer;
    private final Map<String, String> legacyContracts;
    private final Map<String, Map<String, String>> renderVariants;
    private final Map<String, CollectionDef> collections;
    private final Map<String, List<Component>> componentsByCollection;
    private final Map<String, Component> componentsByRef;
    private final Function<String, InputStream> opener;
    private final Map<String, Optional<BufferedImage>> imageCache = new ConcurrentHashMap<>();

    private CompositorData(
            Map<String, String> portalToComposer,
            Map<String, String> legacyContracts,
            Map<String, Map<String, String>> renderVariants,
            Map<String, CollectionDef> collections,
            Map<String, List<Component>> componentsByCollection,
            Function<String, InputStream> opener
    ) {
        this.portalToComposer = portalToComposer;
        this.legacyContracts = legacyContracts;
        this.renderVariants = renderVariants;
        this.collections = collections;
        this.componentsByCollection = componentsByCollection;
        this.opener = opener;
        Map<String, Component> byRef = new LinkedHashMap<>();
        componentsByCollection.values().forEach(list -> list.forEach(c -> byRef.put(c.collection() + "/" + c.referenceId(), c)));
        this.componentsByRef = Collections.unmodifiableMap(byRef);
    }

    /** Loads from the class path (the {@code cosmetics-data} JAR). */
    public static CompositorData load(ClassLoader loader) {
        return load(relative -> loader.getResourceAsStream("moonsama/cosmetics/" + relative));
    }

    /** Loads from an unpacked {@code cosmetics-data} directory. */
    public static CompositorData load(Path cosmeticsRoot) {
        return load(relative -> {
            Path file = cosmeticsRoot.resolve(relative);
            try {
                return Files.exists(file) ? Files.newInputStream(file) : null;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /**
     * @param opener resolves paths relative to the cosmetics root, e.g.
     *               {@code compositor/collections.json}; returns null when absent
     */
    public static CompositorData load(Function<String, InputStream> opener) {
        JsonObject portal = readJson(opener, "collections.json").getAsJsonObject();
        Map<String, String> portalToComposer = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : portal.getAsJsonObject("portalCollections").entrySet()) {
            portalToComposer.put(entry.getKey(),
                    entry.getValue().getAsJsonObject().get("composerCollection").getAsString());
        }
        Map<String, String> legacyContracts = new LinkedHashMap<>();
        JsonElement contracts = portal.get("legacyContracts");
        if (contracts != null && contracts.isJsonArray()) {
            for (JsonElement element : contracts.getAsJsonArray()) {
                JsonObject row = element.getAsJsonObject();
                if (row.has("portal") && !row.get("portal").isJsonNull()) {
                    legacyContracts.put(contractKey(row.get("chainId").getAsLong(), row.get("address").getAsString()),
                            row.get("portal").getAsString());
                }
            }
        }

        JsonObject root = readJson(opener, "compositor/collections.json").getAsJsonObject();
        Map<String, Map<String, String>> variants = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("renderVariants").entrySet()) {
            Map<String, String> byType = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> v : entry.getValue().getAsJsonObject().entrySet()) {
                byType.put(v.getKey(), v.getValue().getAsString());
            }
            variants.put(entry.getKey(), Collections.unmodifiableMap(byType));
        }

        Map<String, CollectionDef> collections = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("collections").entrySet()) {
            collections.put(entry.getKey(), CollectionDef.parse(entry.getKey(), entry.getValue().getAsJsonObject()));
        }

        Map<String, List<Component>> components = new LinkedHashMap<>();
        for (String ref : collections.keySet()) {
            JsonElement file = readJsonOrNull(opener, "compositor/components/" + ref + ".json");
            if (file == null) {
                continue;
            }
            List<Component> list = new ArrayList<>();
            for (JsonElement element : file.getAsJsonArray()) {
                JsonObject row = element.getAsJsonObject();
                list.add(new Component(
                        ref,
                        row.get("referenceId").getAsString(),
                        row.getAsJsonObject("states"),
                        row.getAsJsonObject("config")
                ));
            }
            components.put(ref, Collections.unmodifiableList(list));
        }
        return new CompositorData(
                Collections.unmodifiableMap(portalToComposer),
                Collections.unmodifiableMap(legacyContracts),
                Collections.unmodifiableMap(variants),
                Collections.unmodifiableMap(collections),
                Collections.unmodifiableMap(components),
                opener
        );
    }

    private static JsonElement readJson(Function<String, InputStream> opener, String relative) {
        JsonElement element = readJsonOrNull(opener, relative);
        if (element == null) {
            throw new IllegalStateException("Missing cosmetics resource " + relative);
        }
        return element;
    }

    private static JsonElement readJsonOrNull(Function<String, InputStream> opener, String relative) {
        try (InputStream in = opener.apply(relative)) {
            if (in == null) {
                return null;
            }
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed reading " + relative, e);
        }
    }

    // ------------------------------------------------------------------ lookups

    public Optional<String> composerCollection(String portalCollection) {
        return Optional.ofNullable(portalToComposer.get(portalCollection));
    }

    /**
     * Portal collection slug of a legacy on-chain contract referenced by unlock rules, empty when
     * the contract is not represented in Portal (e.g. Multiverse Costumes).
     */
    public Optional<String> portalCollectionOf(long chainId, String address) {
        if (address == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(legacyContracts.get(contractKey(chainId, address)));
    }

    static String contractKey(long chainId, String address) {
        return chainId + ":" + address.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public Map<String, String> portalCollections() {
        return portalToComposer;
    }

    public Optional<CollectionDef> collection(String referenceId) {
        return Optional.ofNullable(collections.get(referenceId));
    }

    public Map<String, CollectionDef> collections() {
        return collections;
    }

    public Optional<String> renderVariant(String collection, String renderType) {
        Map<String, String> byType = renderVariants.get(collection);
        return byType == null ? Optional.empty() : Optional.ofNullable(byType.get(renderType));
    }

    public List<Component> components(String collection) {
        return componentsByCollection.getOrDefault(collection, List.of());
    }

    /** Component referenceIds are only unique within a collection. */
    public Optional<Component> component(String collection, String referenceId) {
        return Optional.ofNullable(componentsByRef.get(collection + "/" + referenceId));
    }

    /** Child collections whose {@code parentCollection} is the given one. */
    public List<CollectionDef> children(String parent) {
        List<CollectionDef> result = new ArrayList<>();
        for (CollectionDef def : collections.values()) {
            if (parent.equals(def.parentCollection())) {
                result.add(def);
            }
        }
        return result;
    }

    /** Layer image of a component's collection, or empty when the legacy file never existed. */
    public Optional<BufferedImage> image(String collection, String path) {
        String key = collection + "/" + path;
        return imageCache.computeIfAbsent(key, k -> {
            try (InputStream in = opener.apply("compositor/files/" + k)) {
                if (in == null) {
                    return Optional.empty();
                }
                BufferedImage raw = ImageIO.read(in);
                return Optional.ofNullable(raw).map(CompositorData::toArgb);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed reading layer " + k, e);
            }
        });
    }

    /** Reads {@code compositor/compositions/<portal>.jsonl}: the default look of each token. */
    public Map<Long, List<SlotValue>> defaultCompositions(String portalCollection) {
        Map<Long, List<SlotValue>> result = new LinkedHashMap<>();
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
                List<SlotValue> slots = new ArrayList<>();
                for (JsonElement element : row.getAsJsonArray("slots")) {
                    JsonObject slot = element.getAsJsonObject();
                    slots.add(new SlotValue(
                            slot.get("slot").getAsString(),
                            optString(slot, "collection"),
                            slot.get("value").getAsString()
                    ));
                }
                result.put(row.get("id").getAsLong(), List.copyOf(slots));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return result;
    }

    static BufferedImage toArgb(BufferedImage image) {
        if (image.getType() == BufferedImage.TYPE_INT_ARGB) {
            return image;
        }
        BufferedImage copy = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                copy.setRGB(x, y, image.getRGB(x, y));
            }
        }
        return copy;
    }

    static String optString(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    static List<String> strings(JsonElement element) {
        if (element == null || !element.isJsonArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (JsonElement e : element.getAsJsonArray()) {
            result.add(e.getAsString());
        }
        return List.copyOf(result);
    }

    // ------------------------------------------------------------------- records

    /** One entry of a composition: which asset sits in which slot. */
    public record SlotValue(String slot, String collection, String asset) {
        public SlotValue(String slot, String asset) {
            this(slot, null, asset);
        }
    }

    public record ResultType(String renderType, String component) {
    }

    public record SlotDef(String referenceId, String type, boolean customizable, boolean required, String parentSlot) {
    }

    public record Permission(String slot, String sourceCollection, String renderTypeSuffix, JsonObject config) {
    }

    public record AssetDef(String referenceId, String name, List<String> tags, List<String> groups) {
    }

    public record Component(String collection, String referenceId, JsonObject states, JsonObject config) {
        public boolean hasStates() {
            return states != null && !states.isEmpty();
        }

        public JsonArray elements() {
            JsonElement elements = config == null ? null : config.get("elements");
            return elements != null && elements.isJsonArray() ? elements.getAsJsonArray() : new JsonArray();
        }
    }

    public record CollectionDef(
            String referenceId,
            String type,
            String parentCollection,
            List<ResultType> resultTypes,
            Map<String, SlotDef> slots,
            List<Permission> permissions,
            Map<String, AssetDef> assets,
            Map<String, List<String>> proxyTags,
            Map<String, JsonObject> unlockRules
    ) {
        static CollectionDef parse(String referenceId, JsonObject json) {
            List<ResultType> resultTypes = new ArrayList<>();
            for (JsonElement e : json.getAsJsonArray("resultTypes")) {
                JsonObject row = e.getAsJsonObject();
                resultTypes.add(new ResultType(row.get("renderType").getAsString(), optString(row, "component")));
            }
            Map<String, SlotDef> slots = new LinkedHashMap<>();
            for (JsonElement e : json.getAsJsonArray("slots")) {
                JsonObject row = e.getAsJsonObject();
                String ref = row.get("referenceId").getAsString();
                slots.put(ref, new SlotDef(ref, optString(row, "type"), row.get("customizable").getAsBoolean(),
                        row.get("required").getAsBoolean(), optString(row, "parentSlot")));
            }
            List<Permission> permissions = new ArrayList<>();
            for (JsonElement e : json.getAsJsonArray("slotPermissions")) {
                JsonObject row = e.getAsJsonObject();
                permissions.add(new Permission(row.get("slot").getAsString(), optString(row, "sourceCollection"),
                        row.has("renderTypeSuffix") ? row.get("renderTypeSuffix").getAsString() : "",
                        row.has("config") && row.get("config").isJsonObject() ? row.getAsJsonObject("config") : new JsonObject()));
            }
            Map<String, AssetDef> assets = new LinkedHashMap<>();
            for (JsonElement e : json.getAsJsonArray("assets")) {
                JsonObject row = e.getAsJsonObject();
                String ref = row.get("referenceId").getAsString();
                assets.put(ref, new AssetDef(ref, optString(row, "name"), strings(row.get("tags")), strings(row.get("groups"))));
            }
            Map<String, List<String>> proxies = new LinkedHashMap<>();
            for (JsonElement e : json.getAsJsonArray("assetProxies")) {
                JsonObject row = e.getAsJsonObject();
                proxies.put(row.get("referenceId").getAsString(), strings(row.get("tags")));
            }
            Map<String, JsonObject> unlockRules = new LinkedHashMap<>();
            JsonElement rules = json.get("unlockRules");
            if (rules != null && rules.isJsonArray()) {
                for (JsonElement e : rules.getAsJsonArray()) {
                    JsonObject row = e.getAsJsonObject();
                    if (row.has("referenceId") && row.has("unlock") && row.get("unlock").isJsonObject()) {
                        unlockRules.put(row.get("referenceId").getAsString(), row.getAsJsonObject("unlock"));
                    }
                }
            }
            return new CollectionDef(referenceId, optString(json, "type"), optString(json, "parentCollection"),
                    List.copyOf(resultTypes), Collections.unmodifiableMap(slots), List.copyOf(permissions),
                    Collections.unmodifiableMap(assets), Collections.unmodifiableMap(proxies),
                    Collections.unmodifiableMap(unlockRules));
        }
    }
}
