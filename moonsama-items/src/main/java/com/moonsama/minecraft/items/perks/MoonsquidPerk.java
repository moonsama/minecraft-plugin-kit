package com.moonsama.minecraft.items.perks;

import com.moonsama.minecraft.items.Offhand;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/**
 * Moonsquid: Aqua Affinity while equipped. Puts a squid helmet on a bare head, or grants the
 * enchantment to the helmet already worn; both are undone when the squid comes off.
 */
public final class MoonsquidPerk extends Perk {
    static final String HELMET_ID = "moonsama:moonsquid_helmet";
    static final int HELMET_MODEL = 100;   // sugar
    private final NamespacedKey buffKey;

    public MoonsquidPerk(PerkContext ctx, Offhand offhand) {
        super(ctx, offhand);
        this.buffKey = new NamespacedKey(ctx.plugin(), "moonsquid_buff");
    }

    @Override
    public void onEquip(Player player, PerkState state) {
        apply(player);
    }

    @Override
    public void tick(Player player, PerkState state) {
        apply(player);
    }

    private void apply(Player player) {
        PlayerInventory inventory = player.getInventory();
        ItemStack helmet = inventory.getHelmet();
        if (helmet == null || helmet.getType().isAir()) {
            inventory.setHelmet(ctx.items().buildPerkItem(HELMET_ID, Material.SUGAR, HELMET_MODEL, "Moonsquid",
                    List.of("Aqua Affinity while the Moonsquid is equipped")));
            ItemStack placed = inventory.getHelmet();
            if (placed != null) {
                ItemMeta meta = placed.getItemMeta();
                meta.addEnchant(Enchantment.AQUA_AFFINITY, 1, true);
                meta.addEnchant(Enchantment.BINDING_CURSE, 1, true);
                placed.setItemMeta(meta);
                inventory.setHelmet(placed);
            }
            return;
        }
        if (ctx.items().perkItemOf(helmet).isPresent()) {
            return;
        }
        ItemMeta meta = helmet.getItemMeta();
        if (meta.getEnchantLevel(Enchantment.AQUA_AFFINITY) < 1) {
            meta.addEnchant(Enchantment.AQUA_AFFINITY, 1, true);
            meta.getPersistentDataContainer().set(buffKey, PersistentDataType.BYTE, (byte) 1);
            helmet.setItemMeta(meta);
            inventory.setHelmet(helmet);
        }
    }

    @Override
    public void onUnequip(Player player, PerkState state) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (item == null) {
                continue;
            }
            if (ctx.items().perkItemOf(item).filter(HELMET_ID::equals).isPresent()) {
                inventory.setItem(slot, null);
            } else if (item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(buffKey, PersistentDataType.BYTE)) {
                ItemMeta meta = item.getItemMeta();
                meta.removeEnchant(Enchantment.AQUA_AFFINITY);
                meta.getPersistentDataContainer().remove(buffKey);
                item.setItemMeta(meta);
                inventory.setItem(slot, item);
            }
        }
    }
}
