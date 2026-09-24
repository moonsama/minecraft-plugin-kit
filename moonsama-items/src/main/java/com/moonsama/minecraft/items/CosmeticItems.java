package com.moonsama.minecraft.items;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Builds and recognises the item stacks this plugin manages. Every managed stack carries a
 * persistent data tag so it can be found again (and stripped) regardless of its looks.
 */
public final class CosmeticItems {
    private final NamespacedKey skinKey;
    private final NamespacedKey offhandKey;
    private final NamespacedKey hatKey;

    public CosmeticItems(Plugin plugin) {
        this.skinKey = new NamespacedKey(plugin, "item_skin");
        this.offhandKey = new NamespacedKey(plugin, "offhand");
        this.hatKey = new NamespacedKey(plugin, "hat");
    }

    // ------------------------------------------------------------ item skins

    /** Applies a skin to a real tool; returns false when the material does not match. */
    public boolean applySkin(ItemStack tool, ItemSkin skin) {
        if (tool == null || tool.getType().isAir() || !skin.appliesTo(tool.getType())) {
            return false;
        }
        ItemMeta meta = tool.getItemMeta();
        setModelData(meta, skin.customModelData());
        meta.getPersistentDataContainer().set(skinKey, PersistentDataType.STRING, skin.identifier());
        tool.setItemMeta(meta);
        return true;
    }

    /** Removes a skin applied by this plugin; returns false when the stack carried none. */
    public boolean clearSkin(ItemStack tool) {
        if (tool == null || !tool.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = tool.getItemMeta();
        if (!meta.getPersistentDataContainer().has(skinKey, PersistentDataType.STRING)) {
            return false;
        }
        meta.getPersistentDataContainer().remove(skinKey);
        meta.setCustomModelDataComponent(null);
        tool.setItemMeta(meta);
        return true;
    }

    public Optional<String> skinOf(ItemStack tool) {
        return tag(tool, skinKey);
    }

    // -------------------------------------------------------------- offhands

    public ItemStack buildOffhand(Offhand offhand) {
        ItemStack item = new ItemStack(offhand.material());
        ItemMeta meta = item.getItemMeta();
        setModelData(meta, offhand.customModelData());
        meta.displayName(Component.text(offhand.name(), NamedTextColor.LIGHT_PURPLE)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(gray("Moonsama off-hand cosmetic"));
        lore.add(gray("Cannot be dropped or stored"));
        meta.lore(lore);
        meta.setUnbreakable(true);
        meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE, ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        meta.getPersistentDataContainer().set(offhandKey, PersistentDataType.STRING, offhand.id());
        item.setItemMeta(meta);
        return item;
    }

    public Optional<String> offhandOf(ItemStack item) {
        return tag(item, offhandKey);
    }

    // ------------------------------------------------------------------ hats

    public ItemStack buildHat(HatRules rules, HatRules.Rule rule) {
        ItemStack item = new ItemStack(rules.material());
        ItemMeta meta = item.getItemMeta();
        setModelData(meta, rule.customModelData());
        meta.displayName(Component.text(rule.name(), NamedTextColor.LIGHT_PURPLE)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(gray("Part of your Moonsama skin")));
        meta.setUnbreakable(true);
        meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE, ItemFlag.HIDE_ATTRIBUTES);
        meta.getPersistentDataContainer().set(hatKey, PersistentDataType.INTEGER, rule.customModelData());
        item.setItemMeta(meta);
        return item;
    }

    public boolean isHat(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(hatKey, PersistentDataType.INTEGER);
    }

    public Optional<Integer> hatModelOf(ItemStack item) {
        if (!isHat(item)) {
            return Optional.empty();
        }
        return Optional.ofNullable(item.getItemMeta().getPersistentDataContainer().get(hatKey, PersistentDataType.INTEGER));
    }

    /** Off-hands and hats: items players may not drop, store or lose. */
    public boolean isManagedCosmetic(ItemStack item) {
        return offhandOf(item).isPresent() || isHat(item);
    }

    // --------------------------------------------------------------- helpers

    static void setModelData(ItemMeta meta, int customModelData) {
        CustomModelDataComponent component = meta.getCustomModelDataComponent();
        component.setFloats(List.of((float) customModelData));
        meta.setCustomModelDataComponent(component);
    }

    private static Optional<String> tag(ItemStack item, NamespacedKey key) {
        if (item == null || !item.hasItemMeta()) {
            return Optional.empty();
        }
        return Optional.ofNullable(item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING));
    }

    private static Component gray(String text) {
        return Component.text(text, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
    }
}
