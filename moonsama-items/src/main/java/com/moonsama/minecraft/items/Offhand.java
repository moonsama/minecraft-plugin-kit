package com.moonsama.minecraft.items;

import org.bukkit.Material;

/** A cosmetic off-hand item. {@code legacyPerk} is informational only. */
public record Offhand(
        String id,
        String name,
        Material material,
        int customModelData,
        String legacyPerk,
        Requirement requirement
) {
}
