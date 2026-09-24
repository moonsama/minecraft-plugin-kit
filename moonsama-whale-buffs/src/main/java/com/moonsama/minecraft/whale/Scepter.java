package com.moonsama.minecraft.whale;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Locale;

/** The Whale Scepter: a bound item that appears in the inventory of players with enough power. */
public final class Scepter {
    private final NamespacedKey key;
    private final WhaleConfig config;

    public Scepter(Plugin plugin, WhaleConfig config) {
        this.key = new NamespacedKey(plugin, "whale_scepter");
        this.config = config;
    }

    public boolean isScepter(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    public ItemStack build(double power) {
        WhaleConfig.WhaleMode mode = config.whaleMode();
        ItemStack item = new ItemStack(mode.scepterMaterial());
        ItemMeta meta = item.getItemMeta();
        meta.displayName(MiniMessage.miniMessage().deserialize(mode.scepterName()).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Power: " + format(power), NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false),
                Component.text("When in Main Hand:", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Activate Whale Mode", NamedTextColor.BLUE).decoration(TextDecoration.ITALIC, false)));
        CustomModelDataComponent model = meta.getCustomModelDataComponent();
        model.setFloats(List.of((float) mode.scepterModel()));
        meta.setCustomModelDataComponent(model);
        meta.setUnbreakable(true);
        meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE, ItemFlag.HIDE_ATTRIBUTES);
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    /** Gives, refreshes or removes the scepter to match {@code power}. */
    public void sync(Player player, double power, boolean entitled) {
        PlayerInventory inventory = player.getInventory();
        boolean wanted = config.enabled() && config.whaleMode().enabled() && entitled
                && power >= config.whaleMode().scepterMinPower();
        int found = -1;
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (isScepter(contents[slot])) {
                if (found < 0) {
                    found = slot;
                } else {
                    inventory.setItem(slot, null); // duplicates never survive
                }
            }
        }
        if (!wanted) {
            if (found >= 0) {
                inventory.setItem(found, null);
            }
            return;
        }
        ItemStack fresh = build(power);
        if (found >= 0) {
            if (!fresh.isSimilar(contents[found])) {
                inventory.setItem(found, fresh);
            }
        } else if (inventory.firstEmpty() >= 0) {
            inventory.addItem(fresh);
        }
    }

    public void remove(Player player) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (isScepter(contents[slot])) {
                inventory.setItem(slot, null);
            }
        }
    }

    static String format(double power) {
        return power == Math.rint(power) ? String.valueOf((long) power) : String.format(Locale.ROOT, "%.1f", power);
    }
}
