package com.moonsama.minecraft.skins;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
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
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Paged chest menu listing the NFT skins a player owns as player heads.
 */
public final class SkinMenu implements InventoryHolder {
    static final int PAGE_SIZE = 45;
    static final int SLOT_PREVIOUS = 45;
    static final int SLOT_REFRESH = 47;
    static final int SLOT_RESET = 49;
    static final int SLOT_NEXT = 53;
    private static final int ROWS = 6;

    private final NamespacedKey skinKey;
    private final SkinCollections collections;
    private final List<SignedSkin> skins;
    private final HoldingsSnapshot.Status status;
    private final SkinRef equipped;
    private final Inventory inventory;
    private int page;

    public SkinMenu(
            NamespacedKey skinKey,
            SkinCollections collections,
            SkinService.OwnedSkins owned,
            SkinRef equipped
    ) {
        this.skinKey = skinKey;
        this.collections = collections;
        this.status = owned.status();
        this.equipped = equipped;
        this.skins = new ArrayList<>(owned.skins());
        this.skins.sort(Comparator
                .comparingInt((SignedSkin skin) -> collections.indexOf(skin.ref().collection()))
                .thenComparingLong(skin -> skin.ref().tokenId()));
        this.inventory = Bukkit.createInventory(this, ROWS * 9, Component.text("Moonsama Skins"));
        render();
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    public int pageCount() {
        return Math.max(1, (skins.size() + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    public void open(Player player) {
        player.openInventory(inventory);
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

    /**
     * Resolves the skin represented by an item in this menu.
     */
    public Optional<SkinRef> skinOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return Optional.empty();
        }
        String tagged = item.getItemMeta().getPersistentDataContainer().get(skinKey, PersistentDataType.STRING);
        if (tagged == null) {
            return Optional.empty();
        }
        int split = tagged.lastIndexOf(':');
        if (split <= 0) {
            return Optional.empty();
        }
        try {
            return Optional.of(new SkinRef(tagged.substring(0, split), Long.parseLong(tagged.substring(split + 1))));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    private void render() {
        inventory.clear();
        int start = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && start + i < skins.size(); i++) {
            inventory.setItem(i, headFor(skins.get(start + i)));
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
        inventory.setItem(SLOT_RESET, button(Material.BARRIER, "Wear my own skin", NamedTextColor.RED,
                equipped == null ? "You are wearing your own skin" : "Currently wearing " + displayName(equipped)));
        if (skins.isEmpty()) {
            inventory.setItem(22, button(Material.PAPER, "No skins found", NamedTextColor.GRAY,
                    "Hold an NFT from one of these collections", "in your Portal account:",
                    String.join(", ", collections.order().stream().map(collections::name).toList()), statusLine()));
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

    private ItemStack headFor(SignedSkin skin) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        meta.setPlayerProfile(profileFor(skin));
        boolean worn = isWorn(skin.ref());
        meta.displayName(Component.text(displayName(skin.ref()), worn ? NamedTextColor.GREEN : NamedTextColor.WHITE)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(collections.name(skin.ref().collection()), NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text(worn ? "Currently worn" : "Click to wear",
                worn ? NamedTextColor.GREEN : NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        if (worn) {
            meta.setEnchantmentGlintOverride(true);
        }
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        meta.getPersistentDataContainer().set(skinKey, PersistentDataType.STRING, skin.ref().key());
        item.setItemMeta(meta);
        return item;
    }

    /**
     * A synthetic profile carrying the NFT's signed textures so the head renders that skin.
     */
    static PlayerProfile profileFor(SignedSkin skin) {
        UUID id = UUID.nameUUIDFromBytes(("moonsama-skin:" + skin.ref().key()).getBytes(StandardCharsets.UTF_8));
        PlayerProfile profile = Bukkit.createProfile(id, "MoonsamaSkin");
        profile.setProperty(new ProfileProperty("textures", skin.value(), skin.signature()));
        return profile;
    }

    private String displayName(SkinRef ref) {
        return collections.displayName(ref);
    }

    /** The single entry of a uniform collection counts as worn whichever token was equipped. */
    private boolean isWorn(SkinRef ref) {
        if (equipped == null) {
            return false;
        }
        if (collections.isUniform(ref.collection())) {
            return ref.collection().equals(equipped.collection());
        }
        return ref.equals(equipped);
    }

    private static ItemStack button(Material material, String name, NamedTextColor color, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, color).decoration(TextDecoration.ITALIC, false));
        List<Component> lines = new ArrayList<>();
        for (String line : lore) {
            lines.add(Component.text(line, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lines);
        item.setItemMeta(meta);
        return item;
    }
}
