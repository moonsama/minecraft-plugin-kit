package com.moonsama.minecraft.wardrobe;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.moonsama.minecraft.compositor.CompositorData.SlotDef;
import com.moonsama.minecraft.compositor.CompositorData.SlotValue;
import com.moonsama.minecraft.skins.SignedSkin;
import com.moonsama.minecraft.skins.SkinRef;
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
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Three-level chest menu: pick an NFT → pick a slot → pick a part. One instance per viewer; the
 * inventory is rebuilt for each view so titles can change.
 */
public final class WardrobeMenu implements InventoryHolder {
    static final int PAGE_SIZE = 45;
    static final int SLOT_BACK = 45;
    static final int SLOT_RESET = 47;
    static final int SLOT_APPLY = 49;
    static final int SLOT_PREVIOUS = 48;
    static final int SLOT_NEXT = 50;
    static final int SLOT_INFO = 53;

    private static final Map<String, Material> SLOT_ICONS = Map.ofEntries(
            Map.entry("hair", Material.STRING),
            Map.entry("hat", Material.LEATHER_HELMET),
            Map.entry("helmet", Material.IRON_HELMET),
            Map.entry("eyes", Material.ENDER_EYE),
            Map.entry("eyewear", Material.SPYGLASS),
            Map.entry("mouth", Material.SWEET_BERRIES),
            Map.entry("facepaint", Material.RED_DYE),
            Map.entry("mask", Material.CARVED_PUMPKIN),
            Map.entry("outfit", Material.LEATHER_CHESTPLATE),
            Map.entry("clothes", Material.LEATHER_CHESTPLATE),
            Map.entry("costume", Material.GOLDEN_CHESTPLATE),
            Map.entry("bodyaccessory", Material.IRON_CHAIN),
            Map.entry("hands", Material.LEATHER_BOOTS),
            Map.entry("mainhand", Material.IRON_SWORD),
            Map.entry("offhand", Material.SHIELD),
            Map.entry("weapon", Material.IRON_SWORD),
            Map.entry("neckcharm", Material.AMETHYST_SHARD),
            Map.entry("wristcharm", Material.GOLD_NUGGET),
            Map.entry("elbowcharm", Material.IRON_NUGGET),
            Map.entry("beltcharm", Material.LEAD),
            Map.entry("souvenir", Material.NAUTILUS_SHELL)
    );

    enum View { BASES, SLOTS, PARTS }

    private final WardrobeService service;
    private final NamespacedKey tagKey;
    private final Map<String, String> collectionNames;
    private final UUID viewer;

    private Inventory inventory;
    private View view = View.BASES;
    private SkinRef base;
    private String slot;
    private List<SlotValue> options = List.of();
    private int page;

    public WardrobeMenu(WardrobeService service, NamespacedKey tagKey, Map<String, String> collectionNames, UUID viewer) {
        this.service = service;
        this.tagKey = tagKey;
        this.collectionNames = collectionNames;
        this.viewer = viewer;
        this.inventory = Bukkit.createInventory(this, 54, Component.text("Wardrobe"));
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    public View view() {
        return view;
    }

    public SkinRef base() {
        return base;
    }

    public String slot() {
        return slot;
    }

    // ------------------------------------------------------------------ views

    public void showBases(Player player, WardrobeService.Owned owned) {
        view = View.BASES;
        base = null;
        slot = null;
        page = 0;
        inventory = Bukkit.createInventory(this, 54, Component.text("Wardrobe — pick an NFT"));
        List<SkinRef> refs = owned.refs();
        for (int i = 0; i < PAGE_SIZE && i < refs.size(); i++) {
            inventory.setItem(i, baseItem(refs.get(i)));
        }
        if (refs.isEmpty()) {
            inventory.setItem(22, button(Material.PAPER, "Nothing to customize", NamedTextColor.GRAY,
                    "Hold a Moonsama or Exosama NFT in", "your Portal account.", "Holdings: " + owned.status().toLowerCase()));
        }
        inventory.setItem(SLOT_INFO, infoItem());
        player.openInventory(inventory);
    }

    public void showSlots(Player player, SkinRef ref) {
        view = View.SLOTS;
        base = ref;
        slot = null;
        page = 0;
        inventory = Bukkit.createInventory(this, 54, Component.text("Wardrobe — " + displayName(ref)));
        Look look = service.look(viewer, ref);
        List<SlotDef> slots = service.slots(ref.collection());
        for (int i = 0; i < Math.min(slots.size(), PAGE_SIZE); i++) {
            inventory.setItem(i, slotItem(ref, slots.get(i), look));
        }
        inventory.setItem(SLOT_BACK, button(Material.ARROW, "Back", NamedTextColor.YELLOW, "Pick another NFT"));
        inventory.setItem(SLOT_RESET, button(Material.BARRIER, "Reset to the NFT's own look", NamedTextColor.RED,
                look.isEmpty() ? "No changes yet" : "Forgets " + look.rawOverrides().size() + " change(s)"));
        boolean signing = service.signingAvailable();
        inventory.setItem(SLOT_APPLY, button(Material.NETHER_STAR, "Wear this look", NamedTextColor.GREEN,
                signing || look.isEmpty()
                        ? new String[]{"Composes, signs and applies the skin", "This takes a few seconds"}
                        : new String[]{"Skin signing is not configured", "on this server; ask an admin."}));
        inventory.setItem(SLOT_INFO, infoItem());
        player.openInventory(inventory);
    }

    public void showParts(Player player, SkinRef ref, String slotId, List<SlotValue> available) {
        view = View.PARTS;
        base = ref;
        slot = slotId;
        options = List.copyOf(available);
        page = 0;
        renderParts(player);
    }

    public void nextPage(Player player) {
        if (view == View.PARTS && (page + 1) * partsPerPage() < options.size() + fixedPartCount()) {
            page++;
            renderParts(player);
        }
    }

    public void previousPage(Player player) {
        if (view == View.PARTS && page > 0) {
            page--;
            renderParts(player);
        }
    }

    private void renderParts(Player player) {
        inventory = Bukkit.createInventory(this, 54,
                Component.text("Wardrobe — " + prettySlot(slot) + " of " + displayName(base)));
        Look look = service.look(viewer, base);
        Optional<SlotValue> current = service.currentPart(viewer, base, slot);
        Optional<SlotValue> own = service.defaultPart(base, slot);

        List<ItemStack> items = new ArrayList<>();
        // Fixed entries first: NFT's own part and "nothing".
        items.add(fixedItem(Material.TOTEM_OF_UNDYING, own.map(v -> "Own part: " + service.partName(v)).orElse("Own part: nothing"),
                "revert", !look.touches(slot), "The part this NFT was minted with"));
        if (!service.isRequired(base.collection(), slot)) {
            items.add(fixedItem(Material.GLASS_PANE, "Nothing", "clear", look.isCleared(slot), "Leave this slot empty"));
        }
        for (int i = 0; i < options.size(); i++) {
            SlotValue option = options.get(i);
            boolean selected = current.isPresent() && sameAsset(current.get(), option);
            boolean isOwn = own.isPresent() && sameAsset(own.get(), option);
            items.add(partItem(option, i, selected, isOwn));
        }

        int perPage = partsPerPage();
        int start = page * perPage;
        for (int i = 0; i < perPage && start + i < items.size(); i++) {
            inventory.setItem(i, items.get(start + i));
        }
        int pages = Math.max(1, (items.size() + perPage - 1) / perPage);
        if (page > 0) {
            inventory.setItem(SLOT_PREVIOUS, button(Material.ARROW, "Previous page", NamedTextColor.YELLOW,
                    "Page " + page + " of " + pages));
        }
        if (page + 1 < pages) {
            inventory.setItem(SLOT_NEXT, button(Material.ARROW, "Next page", NamedTextColor.YELLOW,
                    "Page " + (page + 2) + " of " + pages));
        }
        inventory.setItem(SLOT_BACK, button(Material.ARROW, "Back", NamedTextColor.YELLOW, "Back to the slots"));
        if (options.isEmpty()) {
            inventory.setItem(SLOT_INFO, button(Material.PAPER, "No other parts unlocked", NamedTextColor.GRAY,
                    "Parts unlock through the NFTs you hold:", "other " + collectionName(base.collection()) + " traits,",
                    "Moonsama items, embassy passes…"));
        } else {
            inventory.setItem(SLOT_INFO, infoItem());
        }
        player.openInventory(inventory);
    }

    private int partsPerPage() {
        return PAGE_SIZE;
    }

    private int fixedPartCount() {
        return service.isRequired(base.collection(), slot) ? 1 : 2;
    }

    // ------------------------------------------------------------------ actions from clicks

    /** The action encoded in a clicked item: {@code base:<key>}, {@code slot:<id>}, {@code part:<index>}, {@code revert}, {@code clear}. */
    public Optional<String> actionOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return Optional.empty();
        }
        return Optional.ofNullable(item.getItemMeta().getPersistentDataContainer().get(tagKey, PersistentDataType.STRING));
    }

    public Optional<SlotValue> option(int index) {
        return index >= 0 && index < options.size() ? Optional.of(options.get(index)) : Optional.empty();
    }

    public static Optional<SkinRef> parseRef(String key) {
        int split = key.lastIndexOf(':');
        if (split <= 0) {
            return Optional.empty();
        }
        try {
            return Optional.of(new SkinRef(key.substring(0, split), Long.parseLong(key.substring(split + 1))));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------ items

    private ItemStack baseItem(SkinRef ref) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        service.skinCatalog().find(ref).ifPresent(skin -> meta.setPlayerProfile(profileFor(skin)));
        boolean customized = !service.look(viewer, ref).isEmpty();
        meta.displayName(text(displayName(ref), customized ? NamedTextColor.AQUA : NamedTextColor.WHITE));
        List<Component> lore = new ArrayList<>();
        lore.add(text(collectionName(ref.collection()), NamedTextColor.GRAY));
        lore.add(text(customized ? "Has a custom look" : "Click to customize", customized ? NamedTextColor.AQUA : NamedTextColor.YELLOW));
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        meta.getPersistentDataContainer().set(tagKey, PersistentDataType.STRING, "base:" + ref.key());
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack slotItem(SkinRef ref, SlotDef def, Look look) {
        String id = def.referenceId();
        Material icon = SLOT_ICONS.getOrDefault(id, Material.NAME_TAG);
        ItemStack item = new ItemStack(icon);
        ItemMeta meta = item.getItemMeta();
        boolean changed = look.touches(id);
        meta.displayName(text(service.slotName(def), changed ? NamedTextColor.AQUA : NamedTextColor.WHITE));
        List<Component> lore = new ArrayList<>();
        Optional<SlotValue> current = service.currentPart(viewer, ref, id);
        lore.add(text("Now: " + current.map(service::partName).orElse("nothing"), NamedTextColor.GRAY));
        if (changed) {
            lore.add(text("Changed from: " + service.defaultPart(ref, id).map(service::partName).orElse("nothing"), NamedTextColor.DARK_AQUA));
        }
        lore.add(text("Click to choose a part", NamedTextColor.YELLOW));
        meta.lore(lore);
        if (changed) {
            meta.setEnchantmentGlintOverride(true);
        }
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        meta.getPersistentDataContainer().set(tagKey, PersistentDataType.STRING, "slot:" + id);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack partItem(SlotValue option, int index, boolean selected, boolean isOwn) {
        ItemStack item = new ItemStack(selected ? Material.LIME_DYE : Material.LIGHT_GRAY_DYE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(text(service.partName(option), selected ? NamedTextColor.GREEN : NamedTextColor.WHITE));
        List<Component> lore = new ArrayList<>();
        String source = option.collection();
        lore.add(text(isOwn ? "This NFT's own part" : "From " + source.replace('_', ' '), NamedTextColor.GRAY));
        lore.add(text(selected ? "Selected" : "Click to select", selected ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
        meta.lore(lore);
        if (selected) {
            meta.setEnchantmentGlintOverride(true);
        }
        meta.getPersistentDataContainer().set(tagKey, PersistentDataType.STRING, "part:" + index);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack fixedItem(Material material, String name, String action, boolean selected, String description) {
        ItemStack item = button(material, name, selected ? NamedTextColor.GREEN : NamedTextColor.WHITE,
                description, selected ? "Selected" : "Click to select");
        ItemMeta meta = item.getItemMeta();
        if (selected) {
            meta.setEnchantmentGlintOverride(true);
        }
        meta.getPersistentDataContainer().set(tagKey, PersistentDataType.STRING, action);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack infoItem() {
        return service.signingAvailable()
                ? button(Material.WRITABLE_BOOK, "How it works", NamedTextColor.AQUA,
                "Pick an NFT, swap parts you have", "unlocked, then wear the look.", "Only parts from NFTs you hold are offered.")
                : button(Material.WRITABLE_BOOK, "Signing not configured", NamedTextColor.GOLD,
                "You can browse and save looks, but", "wearing them needs MINESKIN_API_KEY", "on the server (MoonsamaCore).");
    }

    private static boolean sameAsset(SlotValue a, SlotValue b) {
        return a.asset().equals(b.asset()) && String.valueOf(a.collection()).equals(String.valueOf(b.collection()));
    }

    private static PlayerProfile profileFor(SignedSkin skin) {
        UUID id = UUID.nameUUIDFromBytes(("moonsama-skin:" + skin.ref().key()).getBytes(StandardCharsets.UTF_8));
        PlayerProfile profile = Bukkit.createProfile(id, "MoonsamaSkin");
        profile.setProperty(new ProfileProperty("textures", skin.value(), skin.signature()));
        return profile;
    }

    private String collectionName(String collection) {
        return collectionNames.getOrDefault(collection, collection);
    }

    private String displayName(SkinRef ref) {
        return collectionName(ref.collection()) + " #" + ref.tokenId();
    }

    static String prettySlot(String slot) {
        String id = slot.replace('_', ' ');
        return Character.toUpperCase(id.charAt(0)) + id.substring(1);
    }

    private static Component text(String value, NamedTextColor color) {
        return Component.text(value, color).decoration(TextDecoration.ITALIC, false);
    }

    private static ItemStack button(Material material, String name, NamedTextColor color, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(text(name, color));
        List<Component> lines = new ArrayList<>();
        for (String line : lore) {
            lines.add(text(line, NamedTextColor.GRAY));
        }
        meta.lore(lines);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }
}
