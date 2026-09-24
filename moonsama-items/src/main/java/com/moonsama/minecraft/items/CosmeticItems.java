package com.moonsama.minecraft.items;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.inventory.meta.components.UseCooldownComponent;
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
    private final NamespacedKey perkKey;

    public CosmeticItems(Plugin plugin) {
        this.skinKey = new NamespacedKey(plugin, "item_skin");
        this.offhandKey = new NamespacedKey(plugin, "offhand");
        this.hatKey = new NamespacedKey(plugin, "hat");
        this.perkKey = new NamespacedKey(plugin, "perk_item");
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
        return buildOffhandForm(offhand, offhand.material(), offhand.customModelData(), List.of());
    }

    /**
     * An alternative look of the same off-hand (e.g. the drinkable or "recharging" form a perk
     * switches to). Carries the same tag, so it is still recognised as that off-hand.
     */
    public ItemStack buildOffhandForm(Offhand offhand, Material material, int customModelData, List<String> extraLore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        setModelData(meta, customModelData);
        meta.displayName(Component.text(offhand.name(), NamedTextColor.LIGHT_PURPLE)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        if (offhand.legacyPerk() != null && !offhand.legacyPerk().isBlank()) {
            lore.add(Component.text(offhand.legacyPerk(), NamedTextColor.BLUE).decoration(TextDecoration.ITALIC, false));
        }
        for (String line : extraLore) {
            lore.add(gray(line));
        }
        lore.add(gray("Moonsama off-hand cosmetic"));
        lore.add(gray("Cannot be dropped or stored"));
        meta.lore(lore);
        meta.setUnbreakable(true);
        meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE, ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        // Vanilla item-cooldown overlay, shared by every form of this off-hand.
        UseCooldownComponent cooldown = meta.getUseCooldown();
        cooldown.setCooldownGroup(cooldownGroup(offhand.id()));
        cooldown.setCooldownSeconds(1.0f);
        meta.setUseCooldown(cooldown);
        meta.getPersistentDataContainer().set(offhandKey, PersistentDataType.STRING, offhand.id());
        item.setItemMeta(meta);
        return item;
    }

    public static NamespacedKey cooldownGroup(String offhandId) {
        return new NamespacedKey("moonsama", "offhand/" + offhandId.replace(':', '/').toLowerCase(java.util.Locale.ROOT));
    }

    public Optional<String> offhandOf(ItemStack item) {
        return tag(item, offhandKey);
    }

    // ------------------------------------------------------------ perk items

    /** An item a perk hands out (e.g. the Moonsquid helmet); protected like an off-hand. */
    public ItemStack buildPerkItem(String perkItemId, Material material, int customModelData, String name, List<String> loreLines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        setModelData(meta, customModelData);
        meta.displayName(Component.text(name, NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        for (String line : loreLines) {
            lore.add(gray(line));
        }
        meta.lore(lore);
        meta.setUnbreakable(true);
        meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE, ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        meta.getPersistentDataContainer().set(perkKey, PersistentDataType.STRING, perkItemId);
        item.setItemMeta(meta);
        return item;
    }

    public Optional<String> perkItemOf(ItemStack item) {
        return tag(item, perkKey);
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

    /** Off-hands, perk items and hats: items players may not drop, store or lose. */
    public boolean isManagedCosmetic(ItemStack item) {
        return offhandOf(item).isPresent() || perkItemOf(item).isPresent() || isHat(item);
    }

    // --------------------------------------------------------------- helpers

    public static void setModelData(ItemMeta meta, int customModelData) {
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
