package com.moonsama.minecraft.items;

import org.bukkit.Material;

import java.util.List;

/**
 * A weapon or tool skin: a custom model data value the resource pack renders on each of
 * the listed vanilla materials.
 */
public record ItemSkin(
        String identifier,
        String name,
        String description,
        int rarity,
        String group,
        int customModelData,
        List<Material> materials,
        Requirement requirement
) {
    public boolean appliesTo(Material material) {
        return materials.contains(material);
    }

    /** The material shown in menus. */
    public Material previewMaterial() {
        for (Material material : materials) {
            if (material.name().startsWith("IRON_")) {
                return material;
            }
        }
        return materials.get(0);
    }
}
