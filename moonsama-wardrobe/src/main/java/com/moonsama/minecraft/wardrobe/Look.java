package com.moonsama.minecraft.wardrobe;

import com.moonsama.minecraft.compositor.CompositorData.SlotValue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A player's customizations of one NFT: per slot, either a chosen part or an explicit "nothing".
 * Slots that are not mentioned keep the NFT's own part.
 */
public final class Look {
    /** Slot → chosen part; a {@code null} value means the slot was cleared. */
    private final Map<String, SlotValue> overrides = new LinkedHashMap<>();

    public Look() {
    }

    public Look(Map<String, SlotValue> overrides) {
        this.overrides.putAll(overrides);
    }

    public boolean isEmpty() {
        return overrides.isEmpty();
    }

    public Map<String, SlotValue> overrides() {
        return Map.copyOf(overridesNullSafe());
    }

    /** Raw view including cleared slots (null values). */
    public Map<String, SlotValue> rawOverrides() {
        return new LinkedHashMap<>(overrides);
    }

    public boolean touches(String slot) {
        return overrides.containsKey(slot);
    }

    public boolean isCleared(String slot) {
        return overrides.containsKey(slot) && overrides.get(slot) == null;
    }

    public Optional<SlotValue> chosen(String slot) {
        return Optional.ofNullable(overrides.get(slot));
    }

    public Look choose(String slot, SlotValue value) {
        overrides.put(slot, value == null ? null : new SlotValue(slot, value.collection(), value.asset()));
        return this;
    }

    public Look clear(String slot) {
        overrides.put(slot, null);
        return this;
    }

    public Look revert(String slot) {
        overrides.remove(slot);
        return this;
    }

    /** Applies the overrides on top of the NFT's default parts. */
    public List<SlotValue> applyTo(List<SlotValue> defaults, String mainCollection) {
        Map<String, SlotValue> result = new LinkedHashMap<>();
        for (SlotValue value : defaults) {
            result.put(value.slot(), value.collection() == null
                    ? new SlotValue(value.slot(), mainCollection, value.asset())
                    : value);
        }
        for (Map.Entry<String, SlotValue> entry : overrides.entrySet()) {
            if (entry.getValue() == null) {
                result.remove(entry.getKey());
            } else {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return List.copyOf(result.values());
    }

    private Map<String, SlotValue> overridesNullSafe() {
        Map<String, SlotValue> copy = new LinkedHashMap<>();
        overrides.forEach((slot, value) -> {
            if (value != null) {
                copy.put(slot, value);
            }
        });
        return copy;
    }
}
