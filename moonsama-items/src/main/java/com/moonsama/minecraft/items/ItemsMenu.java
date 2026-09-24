package com.moonsama.minecraft.items;

import com.moonsama.minecraft.api.HoldingsSnapshot;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Paged chest menu of the item skins and off-hands a player owns. Entries render with their
 * custom model data, so a player with the resource pack sees the actual cosmetic.
 */
public final class ItemsMenu implements InventoryHolder {
    static final int PAGE_SIZE = 45;
    static final int SLOT_PREVIOUS = 45;
    static final int SLOT_REFRESH = 47;
    static final int SLOT_CLEAR_SKIN = 49;
    static final int SLOT_REMOVE_OFFHAND = 51;
    static final int SLOT_NEXT = 53;
    private static final int ROWS = 6;

    /** What a menu entry stands for. */
    public sealed interface Entry permits SkinEntry, OffhandEntry {
    }

    public record SkinEntry(ItemSkin skin) implements Entry {
    }

    public record OffhandEntry(Offhand offhand) implements Entry {
    }

    private final NamespacedKey entryKey;
    private final ItemsService service;
    private final Map<String, String> collectionNames;
    private final List<Entry> entries = new ArrayList<>();
    private final HoldingsSnapshot.Status status;
    private final String equippedOffhand;
    private final Inventory inventory;
    private int page;

    public ItemsMenu(NamespacedKey entryKey, ItemsService service, Map<String, String> collectionNames,
                     ItemsService.Owned owned, String equippedOffhand) {
        this.entryKey = entryKey;
        this.service = service;
        this.collectionNames = collectionNames;
        this.status = owned.status();
        this.equippedOffhand = equippedOffhand;
        List<ItemSkin> skins = new ArrayList<>(owned.skins());
        skins.sort(Comparator.comparing(ItemSkin::group).thenComparing(ItemSkin::name, String.CASE_INSENSITIVE_ORDER));
        for (ItemSkin skin : skins) {
            entries.add(new SkinEntry(skin));
        }
        List<Offhand> offhands = new ArrayList<>(owned.offhands());
        offhands.sort(Comparator.comparing(Offhand::name, String.CASE_INSENSITIVE_ORDER));
        for (Offhand offhand : offhands) {
            entries.add(new OffhandEntry(offhand));
        }
        this.inventory = Bukkit.createInventory(this, ROWS * 9, Component.text("Moonsama Items"));
        render();
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    public void open(Player player) {
        player.openInventory(inventory);
    }

    public int pageCount() {
        return Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    public void nextPage() {
        if (page + 1 < pageCount()) {
            page++;
            render();
        }
    }

    public void previousPage() {
        if (page > 0) {
            page--;
            render();
        }
    }

    public Optional<Entry> entryOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return Optional.empty();
        }
        String tagged = item.getItemMeta().getPersistentDataContainer().get(entryKey, PersistentDataType.STRING);
        if (tagged == null) {
            return Optional.empty();
        }
        if (tagged.startsWith("skin/")) {
            return service.catalog().itemSkin(tagged.substring(5)).map(SkinEntry::new);
        }
        if (tagged.startsWith("offhand/")) {
            return service.catalog().offhand(tagged.substring(8)).map(OffhandEntry::new);
        }
        return Optional.empty();
    }

    private void render() {
        inventory.clear();
        int start = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && start + i < entries.size(); i++) {
            Entry entry = entries.get(start + i);
            inventory.setItem(i, switch (entry) {
                case SkinEntry s -> skinIcon(s.skin());
                case OffhandEntry o -> offhandIcon(o.offhand());
            });
        }
        if (page > 0) {
            inventory.setItem(SLOT_PREVIOUS, button(Material.ARROW, "Previous page", NamedTextColor.YELLOW,
                    "Page " + page + " of " + pageCount()));
        }
        if (page + 1 < pageCount()) {
            inventory.setItem(SLOT_NEXT, button(Material.ARROW, "Next page", NamedTextColor.YELLOW,
                    "Page " + (page + 2) + " of " + pageCount()));
        }
        inventory.setItem(SLOT_REFRESH, button(Material.CLOCK, "Refresh holdings", NamedTextColor.AQUA,
                statusLine(), "Click to re-check what you own in Portal"));
        inventory.setItem(SLOT_CLEAR_SKIN, button(Material.WATER_BUCKET, "Remove skin from held item", NamedTextColor.RED,
                "Hold the tool in your main hand"));
        inventory.setItem(SLOT_REMOVE_OFFHAND, button(Material.BARRIER, "Remove off-hand cosmetic", NamedTextColor.RED,
                equippedOffhand == null ? "No off-hand cosmetic equipped" : "Currently: " + equippedOffhand));
        if (entries.isEmpty()) {
            inventory.setItem(22, button(Material.PAPER, "No cosmetics found", NamedTextColor.GRAY,
                    "Hold Multiverse Items, Pods or a Moonsama/", "Exosama NFT in your Portal account.", statusLine()));
        }
    }

    private String statusLine() {
        return switch (status) {
            case FRESH -> "Holdings are up to date";
            case REFRESHING -> "Holdings are refreshing";
            case STALE -> "Holdings may be out of date";
            case UNKNOWN -> "Holdings not loaded yet";
        };
    }

    private ItemStack skinIcon(ItemSkin skin) {
        ItemStack item = new ItemStack(skin.previewMaterial());
        ItemMeta meta = item.getItemMeta();
        CosmeticItems.setModelData(meta, skin.customModelData());
        meta.displayName(Component.text(skin.name(), NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(gray("Item skin · " + prettyGroup(skin.group())));
        lore.add(gray("Unlocked by " + skin.requirement().describe(collectionNames)));
        lore.add(gray("Works on: " + materialList(skin)));
        lore.add(Component.text("Click while holding a matching tool", NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        meta.getPersistentDataContainer().set(entryKey, PersistentDataType.STRING, "skin/" + skin.identifier());
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack offhandIcon(Offhand offhand) {
        ItemStack item = new ItemStack(offhand.material());
        ItemMeta meta = item.getItemMeta();
        CosmeticItems.setModelData(meta, offhand.customModelData());
        boolean equipped = offhand.id().equals(equippedOffhand);
        meta.displayName(Component.text(offhand.name(), equipped ? NamedTextColor.GREEN : NamedTextColor.WHITE)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(gray("Off-hand cosmetic"));
        lore.add(gray("Unlocked by " + offhand.requirement().describe(collectionNames)));
        lore.add(Component.text(equipped ? "Currently equipped" : "Click to equip",
                equipped ? NamedTextColor.GREEN : NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        if (equipped) {
            meta.setEnchantmentGlintOverride(true);
        }
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        meta.getPersistentDataContainer().set(entryKey, PersistentDataType.STRING, "offhand/" + offhand.id());
        item.setItemMeta(meta);
        return item;
    }

    /** e.g. {@code iron, golden, diamond, netherite swords} or {@code wooden…netherite axes, hoes}. */
    static String materialList(ItemSkin skin) {
        List<String> tiers = new ArrayList<>();
        List<String> kinds = new ArrayList<>();
        for (Material material : skin.materials()) {
            String name = material.name().toLowerCase(Locale.ROOT);
            int underscore = name.indexOf('_');
            String tier = underscore > 0 ? name.substring(0, underscore) : name;
            String kind = underscore > 0 ? name.substring(underscore + 1) + "s" : "";
            if (!tiers.contains(tier)) {
                tiers.add(tier);
            }
            if (!kind.isEmpty() && !kinds.contains(kind)) {
                kinds.add(kind);
            }
        }
        return String.join(", ", tiers) + (kinds.isEmpty() ? "" : " " + String.join("/", kinds));
    }

    private static String prettyGroup(String group) {
        if (group == null || group.isEmpty()) {
            return "misc";
        }
        return group.substring(0, 1).toUpperCase(Locale.ROOT) + group.substring(1);
    }

    private static Component gray(String text) {
        return Component.text(text, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
    }

    private static ItemStack button(Material material, String name, NamedTextColor color, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, color).decoration(TextDecoration.ITALIC, false));
        List<Component> lines = new ArrayList<>();
        for (String line : lore) {
            lines.add(gray(line));
        }
        meta.lore(lines);
        item.setItemMeta(meta);
        return item;
    }
}
