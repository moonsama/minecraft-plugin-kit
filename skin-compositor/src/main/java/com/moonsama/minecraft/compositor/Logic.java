package com.moonsama.minecraft.compositor;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.moonsama.minecraft.compositor.ComponentResolver.ResolvedSlot;

import java.util.ArrayList;
import java.util.List;

/**
 * Evaluates a component's {@code states} tree the way the Composer's LogicService did.
 *
 * <p>Leaf types: {@code asset} (is a given asset, or any asset with given tags, in a slot),
 * {@code token} (token id equals), {@code renderType}, {@code none}. Gates: {@code AND},
 * {@code OR}, {@code NAND}, {@code NOR}, {@code XOR}. Any entry may carry {@code invert}.
 */
final class Logic {
    private Logic() {
    }

    record Context(Long tokenId, String renderType, List<ResolvedSlot> slots) {
        ResolvedSlot slot(String id) {
            for (ResolvedSlot slot : slots) {
                if (slot.slot().equals(id)) {
                    return slot;
                }
            }
            return null;
        }
    }

    static boolean test(JsonObject logic, Context context) {
        String type = CompositorData.optString(logic, "type");
        boolean invert = logic.has("invert") && logic.get("invert").isJsonPrimitive()
                && logic.get("invert").getAsJsonPrimitive().isBoolean() && logic.get("invert").getAsBoolean();
        boolean result;
        if (type == null) {
            result = false;
        } else {
            result = switch (type) {
                case "asset" -> testAsset(logic, context);
                case "token" -> testToken(logic, context);
                case "renderType" -> context.renderType().equals(CompositorData.optString(logic, "renderType"));
                case "none" -> false;
                case "AND", "OR", "NAND", "NOR", "XOR" -> testGate(type, logic, context);
                default -> false;
            };
        }
        return invert != result;
    }

    private static boolean testGate(String type, JsonObject logic, Context context) {
        List<Boolean> results = new ArrayList<>();
        JsonElement conditions = logic.get("conditions");
        if (conditions != null && conditions.isJsonArray()) {
            for (JsonElement condition : conditions.getAsJsonArray()) {
                if (condition.isJsonObject()) {
                    results.add(test(condition.getAsJsonObject(), context));
                }
            }
        }
        long trues = results.stream().filter(Boolean::booleanValue).count();
        long falses = results.size() - trues;
        return switch (type) {
            case "AND" -> falses == 0;
            case "OR" -> trues > 0;
            case "NAND" -> falses > 0;
            case "NOR" -> trues == 0;
            case "XOR" -> !results.isEmpty() && (trues > 0) == (falses > 0);
            default -> false;
        };
    }

    private static boolean testAsset(JsonObject logic, Context context) {
        String assetId = CompositorData.optString(logic, "assetId");
        String slotId = CompositorData.optString(logic, "slot");
        ResolvedSlot slot = slotId == null ? null : context.slot(slotId);
        if (slot == null) {
            return assetId == null;
        }
        if (assetId == null) {
            return false; // slot is occupied but the rule wants it empty
        }
        if (!"*".equals(assetId) && !assetId.equals(slot.assetRef())) {
            return false;
        }
        List<String> tags = CompositorData.strings(logic.get("tags"));
        if (tags.isEmpty()) {
            return true;
        }
        return slot.tags().size() >= tags.size() && slot.tags().containsAll(tags);
    }

    private static boolean testToken(JsonObject logic, Context context) {
        long wanted = 0;
        JsonElement assetId = logic.get("assetId");
        if (assetId != null && assetId.isJsonPrimitive()) {
            try {
                wanted = Long.parseLong(assetId.getAsString().trim());
            } catch (NumberFormatException ignored) {
                wanted = 0;
            }
        }
        long actual = context.tokenId() == null ? 0 : context.tokenId();
        return wanted == actual;
    }
}
