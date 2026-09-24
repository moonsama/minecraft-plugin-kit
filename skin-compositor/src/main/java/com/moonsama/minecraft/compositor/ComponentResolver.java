package com.moonsama.minecraft.compositor;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.moonsama.minecraft.compositor.CompositorData.AssetDef;
import com.moonsama.minecraft.compositor.CompositorData.CollectionDef;
import com.moonsama.minecraft.compositor.CompositorData.Component;
import com.moonsama.minecraft.compositor.CompositorData.Permission;
import com.moonsama.minecraft.compositor.CompositorData.SlotDef;
import com.moonsama.minecraft.compositor.CompositorData.SlotValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Port of the Composer's {@code CompositionService.getAssetComposition}: decides which components
 * take part in a render.
 *
 * <ol>
 *   <li>Each slot value is resolved to an asset and checked against the slot permissions; the
 *       matching permission's render type suffix selects the representation collection
 *       (e.g. {@code multiverse-costumes} rendered as {@code minecraft-moonsama}).</li>
 *   <li>Asset proxies of the representation collection may override the asset's tags.</li>
 *   <li>Base components come from the main representation collection's result types
 *       ({@code *} and the render type).</li>
 *   <li>Every other component of the involved collections is included when its state logic
 *       matches the slots and token.</li>
 * </ol>
 */
public final class ComponentResolver {
    public static final String MINECRAFT = "minecraft";

    private final CompositorData data;

    public ComponentResolver(CompositorData data) {
        this.data = data;
    }

    /** A resolved slot entry with the asset's effective tags. */
    public record ResolvedSlot(String slot, String assetRef, List<String> tags, String renderType, CollectionDef representation) {
    }

    public record Resolution(CollectionDef main, CollectionDef mainRepresentation, List<ResolvedSlot> slots, List<Component> components) {
    }

    public Resolution resolve(Composition composition, String renderType) {
        CollectionDef main = data.collection(composition.collection())
                .orElseThrow(() -> new IllegalArgumentException("Unknown collection " + composition.collection()));
        CollectionDef mainRepresentation = data.renderVariant(main.referenceId(), renderType)
                .flatMap(data::collection)
                .orElse(main);

        List<ResolvedSlot> slots = new ArrayList<>();
        Map<String, CollectionDef> representations = new LinkedHashMap<>();
        for (SlotValue value : composition.slots()) {
            resolveSlot(main, value, renderType).ifPresent(slot -> {
                slots.add(slot);
                if (slot.representation() != null) {
                    representations.putIfAbsent(slot.representation().referenceId(), slot.representation());
                }
            });
        }

        List<Component> components = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (CompositorData.ResultType resultType : mainRepresentation.resultTypes()) {
            if (!"*".equals(resultType.renderType()) && !renderType.equals(resultType.renderType())) {
                continue;
            }
            if (resultType.component() == null || resultType.component().isEmpty()) {
                continue;
            }
            data.component(mainRepresentation.referenceId(), resultType.component()).ifPresent(component -> {
                if (seen.add(key(component))) {
                    components.add(component);
                }
            });
        }

        Logic.Context context = new Logic.Context(composition.tokenId(), renderType, slots);
        List<CollectionDef> involved = new ArrayList<>(representations.values());
        if (!representations.containsKey(mainRepresentation.referenceId())) {
            involved.add(mainRepresentation);
        }
        for (CollectionDef collection : involved) {
            for (Component component : data.components(collection.referenceId())) {
                if (!seen.contains(key(component)) && component.hasStates()
                        && Logic.test(component.states(), context)) {
                    seen.add(key(component));
                    components.add(component);
                }
            }
        }
        return new Resolution(main, mainRepresentation, List.copyOf(slots), List.copyOf(components));
    }

    /**
     * Every asset (own or from an equippable collection) the slot permissions of {@code collection}
     * allow in {@code slot}, as slot values ready for a {@link Composition}.
     */
    public List<SlotValue> permittedAssets(String collection, String slot) {
        CollectionDef main = data.collection(collection)
                .orElseThrow(() -> new IllegalArgumentException("Unknown collection " + collection));
        SlotDef slotDef = main.slots().get(slot);
        if (slotDef == null) {
            return List.of();
        }
        List<SlotValue> values = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Permission permission : main.permissions()) {
            if (!permission.slot().equals(slot)) {
                continue;
            }
            String source = permission.sourceCollection() != null ? permission.sourceCollection() : main.referenceId();
            CollectionDef assetCollection = data.collection(source).orElse(null);
            if (assetCollection == null) {
                continue;
            }
            for (AssetDef asset : assetCollection.assets().values()) {
                if (matchesAnyOption(main, permission, assetCollection, asset)
                        && seen.add(source + "/" + asset.referenceId())) {
                    values.add(new SlotValue(slot, source, asset.referenceId()));
                }
            }
        }
        return List.copyOf(values);
    }

    private static String key(Component component) {
        return component.collection() + "/" + component.referenceId();
    }

    private Optional<ResolvedSlot> resolveSlot(CollectionDef main, SlotValue value, String renderType) {
        SlotDef slot = main.slots().get(value.slot());
        if (slot == null || value.asset() == null) {
            return Optional.empty();
        }
        String collectionRef = value.collection() != null ? value.collection() : main.referenceId();
        CollectionDef assetCollection = data.collection(collectionRef).orElse(null);
        if (assetCollection == null) {
            return Optional.empty();
        }
        AssetDef asset = assetCollection.assets().get(value.asset());
        if (asset == null) {
            return Optional.empty();
        }
        Permission permission = permission(main, slot, assetCollection, asset).orElse(null);
        if (permission == null) {
            return Optional.empty();
        }
        String assetRenderType = renderType + permission.renderTypeSuffix();
        CollectionDef representation = representation(assetCollection, assetRenderType).orElse(null);
        List<String> tags = asset.tags();
        if (representation != null) {
            List<String> proxied = representation.proxyTags().get(asset.referenceId());
            if (proxied != null) {
                tags = proxied;
            }
        }
        return Optional.of(new ResolvedSlot(value.slot(), asset.referenceId(), tags, assetRenderType, representation));
    }

    private Optional<Permission> permission(CollectionDef main, SlotDef slot, CollectionDef assetCollection, AssetDef asset) {
        for (Permission permission : main.permissions()) {
            if (!permission.slot().equals(slot.referenceId())) {
                continue;
            }
            String source = permission.sourceCollection() != null ? permission.sourceCollection() : main.referenceId();
            if (!source.equals(assetCollection.referenceId())) {
                continue;
            }
            if (matchesAnyOption(main, permission, assetCollection, asset)) {
                return Optional.of(permission);
            }
        }
        return Optional.empty();
    }

    private boolean matchesAnyOption(CollectionDef main, Permission permission, CollectionDef assetCollection, AssetDef asset) {
        JsonElement options = permission.config().get("options");
        if (options == null || !options.isJsonArray()) {
            return false;
        }
        for (JsonElement element : options.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject option = element.getAsJsonObject();
            String type = CompositorData.optString(option, "type");
            if ("tagged".equals(type)) {
                List<String> tags = CompositorData.strings(option.get("tags"));
                if (!tags.isEmpty() && !asset.tags().isEmpty() && asset.tags().containsAll(tags)) {
                    return true;
                }
            } else if ("slot".equals(type)) {
                String other = CompositorData.optString(option, "slot");
                SlotDef target = other == null ? null : assetCollection.slots().get(other);
                if (target != null && permission(assetCollection, target, assetCollection, asset).isPresent()) {
                    return true;
                }
            }
        }
        return false;
    }

    private Optional<CollectionDef> representation(CollectionDef collection, String renderType) {
        Optional<CollectionDef> variant = data.renderVariant(collection.referenceId(), renderType).flatMap(data::collection);
        if (variant.isPresent()) {
            return variant;
        }
        for (CompositorData.ResultType type : collection.resultTypes()) {
            if (renderType.equals(type.renderType())) {
                return Optional.of(collection);
            }
        }
        for (CollectionDef child : data.children(collection.referenceId())) {
            for (CompositorData.ResultType type : child.resultTypes()) {
                if (renderType.equals(type.renderType())) {
                    return Optional.of(child);
                }
            }
        }
        return Optional.empty();
    }
}
