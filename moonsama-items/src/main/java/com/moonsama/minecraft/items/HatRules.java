package com.moonsama.minecraft.items;

import org.bukkit.Material;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Picks the 3D hat matching an NFT's composition ({@code slot -> asset}). Rules are tried
 * in order; hat-slot rules are suppressed while the costume slot holds a costume that
 * covers the head.
 */
public record HatRules(Material material, Set<String> hiddenByCostumes, List<Rule> rules) {
    public record Rule(
            String name,
            String slot,
            String asset,
            int customModelData,
            Set<String> collections,
            Map<String, String> requires
    ) {
        boolean matches(String portalCollection, Map<String, String> slots) {
            if (!asset.equals(slots.get(slot))) {
                return false;
            }
            if (!collections.isEmpty() && !collections.contains(portalCollection)) {
                return false;
            }
            for (Map.Entry<String, String> required : requires.entrySet()) {
                if (!required.getValue().equals(slots.get(required.getKey()))) {
                    return false;
                }
            }
            return true;
        }
    }

    public Optional<Rule> resolve(String portalCollection, Map<String, String> slots) {
        String costume = slots.get("costume");
        boolean headCovered = costume != null && hiddenByCostumes.contains(costume);
        for (Rule rule : rules) {
            if (headCovered && !"costume".equals(rule.slot())) {
                continue;
            }
            if (rule.matches(portalCollection, slots)) {
                return Optional.of(rule);
            }
        }
        return Optional.empty();
    }
}
