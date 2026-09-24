package com.moonsama.minecraft.wardrobe;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.moonsama.minecraft.api.AssetHolding;
import com.moonsama.minecraft.compositor.CompositorData;
import com.moonsama.minecraft.compositor.CompositorData.SlotValue;
import com.moonsama.minecraft.compositor.SkinCompositor;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Decides which parts a player may put on a skin, from Portal holdings alone.
 *
 * <p>Two sources of unlocks, mirroring the Customizer:
 * <ul>
 *   <li><b>Traits</b> — a part is unlocked when one of the player's NFTs in the same collection
 *       has it (its default composition is exactly its traits). This covers the Customizer's
 *       trait/pattern/automatic rules without needing token metadata.</li>
 *   <li><b>Unlock rules</b> — {@code nft} conditions on legacy contracts, mapped to Portal
 *       collections through {@code collections.json}. Contracts that Portal does not know
 *       (Multiverse Costumes, Backgrounds) never unlock anything.</li>
 * </ul>
 */
public final class Unlocks {
    /** What to do with rules that only reference contracts Portal will never index (Multiverse Costumes, Backgrounds). */
    public enum OutsidePortalPolicy {
        /** Such conditions are false; the parts stay locked (the old paid costumes are not given away). */
        LOCKED,
        /** Such conditions are true for every linked player; the parts become free for all. */
        FREE
    }

    private final SkinCompositor compositor;
    private final OutsidePortalPolicy outsidePortal;

    public Unlocks(SkinCompositor compositor) {
        this(compositor, OutsidePortalPolicy.LOCKED);
    }

    public Unlocks(SkinCompositor compositor, OutsidePortalPolicy outsidePortal) {
        this.compositor = compositor;
        this.outsidePortal = outsidePortal;
    }

    public static String key(SlotValue value) {
        return value.collection() + "/" + value.asset();
    }

    /** Parts from the default compositions of every NFT the player holds in {@code portalCollection}. */
    public Set<String> ownedParts(String portalCollection, List<AssetHolding> holdings) {
        String composer = compositor.composerCollection(portalCollection).orElse(null);
        Set<String> parts = new LinkedHashSet<>();
        if (composer == null) {
            return parts;
        }
        for (AssetHolding holding : holdings) {
            if (!portalCollection.equals(holding.collection()) || !holding.hasPositiveBalance()) {
                continue;
            }
            Long tokenId = parseToken(holding.tokenId());
            if (tokenId == null) {
                continue;
            }
            compositor.defaultSlots(portalCollection, tokenId).ifPresent(slots -> {
                for (SlotValue value : slots) {
                    parts.add(key(new SlotValue(value.slot(), value.collection() == null ? composer : value.collection(), value.asset())));
                }
            });
        }
        return parts;
    }

    /** The permitted assets of a slot the player may actually use. */
    public List<SlotValue> available(String portalCollection, String slot, List<AssetHolding> holdings) {
        Set<String> owned = ownedParts(portalCollection, holdings);
        return compositor.permittedAssets(portalCollection, slot).stream()
                .filter(value -> isUnlocked(value, owned, holdings))
                .toList();
    }

    public boolean isUnlocked(SlotValue value, Set<String> ownedParts, List<AssetHolding> holdings) {
        String key = key(value);
        if (ownedParts.contains(key)) {
            return true;
        }
        JsonObject rule = compositor.data().collection(value.collection())
                .map(def -> def.unlockRules().get(value.asset()))
                .orElse(null);
        return rule != null && evaluate(rule, key, ownedParts, holdings);
    }

    boolean evaluate(JsonObject rule, String assetKey, Set<String> ownedParts, List<AssetHolding> holdings) {
        String type = optString(rule, "type");
        boolean invert = rule.has("invert") && rule.get("invert").isJsonPrimitive()
                && rule.get("invert").getAsJsonPrimitive().isBoolean() && rule.get("invert").getAsBoolean();
        boolean result;
        if (type == null) {
            result = false;
        } else {
            result = switch (type) {
                case "AND", "OR", "NAND", "NOR" -> gate(type, rule, assetKey, ownedParts, holdings);
                case "nft" -> nft(rule, assetKey, ownedParts, holdings);
                default -> false; // "none" and unknown types never unlock
            };
        }
        return invert != result;
    }

    private boolean gate(String type, JsonObject rule, String assetKey, Set<String> ownedParts, List<AssetHolding> holdings) {
        int trues = 0;
        int total = 0;
        JsonElement conditions = rule.get("conditions");
        if (conditions != null && conditions.isJsonArray()) {
            for (JsonElement condition : conditions.getAsJsonArray()) {
                if (condition.isJsonObject()) {
                    total++;
                    if (evaluate(condition.getAsJsonObject(), assetKey, ownedParts, holdings)) {
                        trues++;
                    }
                }
            }
        }
        return switch (type) {
            case "AND" -> total > 0 && trues == total;
            case "OR" -> trues > 0;
            case "NAND" -> trues < total;
            case "NOR" -> trues == 0;
            default -> false;
        };
    }

    private boolean nft(JsonObject rule, String assetKey, Set<String> ownedParts, List<AssetHolding> holdings) {
        long chainId = rule.has("chainId") ? rule.get("chainId").getAsLong() : 0;
        String address = optString(rule, "assetAddress");
        String portal = compositor.data().portalCollectionOf(chainId, address).orElse(null);
        if (portal == null) {
            return outsidePortal == OutsidePortalPolicy.FREE && compositor.data().isOutsidePortal(chainId, address);
        }
        String reference = optString(rule, "referenceType");
        reference = reference == null ? "any" : reference.toLowerCase(Locale.ROOT);
        switch (reference) {
            case "trait" -> {
                // Trait ownership ⇔ an owned NFT of that collection has the part in its default look.
                return ownedParts.contains(assetKey) && holdsAny(portal, holdings);
            }
            case "specific" -> {
                long wanted = rule.has("min") ? rule.get("min").getAsLong() : -1;
                return holdsToken(portal, holdings, wanted, wanted);
            }
            case "range" -> {
                long min = rule.has("min") ? rule.get("min").getAsLong() : Long.MIN_VALUE;
                long max = rule.has("max") ? rule.get("max").getAsLong() : Long.MAX_VALUE;
                return holdsToken(portal, holdings, min, max);
            }
            default -> {
                return holdsAny(portal, holdings);
            }
        }
    }

    private static boolean holdsAny(String portal, List<AssetHolding> holdings) {
        return holdings.stream().anyMatch(h -> portal.equals(h.collection()) && h.hasPositiveBalance());
    }

    private static boolean holdsToken(String portal, List<AssetHolding> holdings, long min, long max) {
        for (AssetHolding holding : holdings) {
            if (!portal.equals(holding.collection()) || !holding.hasPositiveBalance()) {
                continue;
            }
            Long tokenId = parseToken(holding.tokenId());
            if (tokenId != null && tokenId >= min && tokenId <= max) {
                return true;
            }
        }
        return false;
    }

    private static String optString(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    static Long parseToken(String tokenId) {
        if (tokenId == null) {
            return null;
        }
        try {
            return Long.parseLong(tokenId.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
