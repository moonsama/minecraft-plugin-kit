package com.moonsama.minecraft.items.perks;

import com.moonsama.minecraft.items.Offhand;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/**
 * Moonsquid: Aqua Affinity while equipped. A bare head gets the squid helmet, which carries the
 * enchantment and is soulbound. Otherwise the player gets the same effect as a temporary
 * attribute, so the helmet they wear is never changed and nothing outlives the perk.
 */
public final class MoonsquidPerk extends Perk {
    static final String HELMET_ID = "moonsama:moonsquid_helmet";
    static final int HELMET_MODEL = 100;   // sugar
    /** Vanilla Aqua Affinity: submerged mining speed +400 % of its base (0.2 → 1.0). */
    private static final double AQUA_AFFINITY_BONUS = 4.0;
    private final NamespacedKey modifierKey;
    /** Earlier versions enchanted the worn helmet and tagged it with this key. */
    private final NamespacedKey legacyBuffKey;

    public MoonsquidPerk(PerkContext ctx, Offhand offhand) {
        super(ctx, offhand);
        this.modifierKey = new NamespacedKey(ctx.plugin(), "moonsquid_aqua_affinity");
        this.legacyBuffKey = new NamespacedKey(ctx.plugin(), "moonsquid_buff");
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
            helmet = ctx.items().buildPerkItem(player, HELMET_ID, Material.SUGAR, HELMET_MODEL, "Moonsquid",
                    List.of("Aqua Affinity while the Moonsquid is equipped"));
            helmet.editMeta(meta -> {
                meta.addEnchant(Enchantment.AQUA_AFFINITY, 1, true);
                meta.addEnchant(Enchantment.BINDING_CURSE, 1, true);
            });
            inventory.setHelmet(helmet);
        }
        // A worn helmet with Aqua Affinity already gives the effect; adding ours would stack.
        setModifier(player, !helmet.containsEnchantment(Enchantment.AQUA_AFFINITY));
    }

    private void setModifier(Player player, boolean wanted) {
        AttributeInstance attribute = player.getAttribute(Attribute.SUBMERGED_MINING_SPEED);
        if (attribute == null) {
            return;
        }
        boolean present = attribute.getModifier(modifierKey) != null;
        if (wanted && !present) {
            attribute.addTransientModifier(new AttributeModifier(modifierKey, AQUA_AFFINITY_BONUS,
                    AttributeModifier.Operation.ADD_SCALAR, EquipmentSlotGroup.ANY));
        } else if (!wanted && present) {
            attribute.removeModifier(modifierKey);
        }
    }

    @Override
    public void onUnequip(Player player, PerkState state) {
        setModifier(player, false);
        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (item == null) {
                continue;
            }
            if (ctx.items().perkItemOf(item).filter(HELMET_ID::equals).isPresent()) {
                inventory.setItem(slot, null);
            } else if (item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(legacyBuffKey, PersistentDataType.BYTE)) {
                ItemMeta meta = item.getItemMeta();
                meta.removeEnchant(Enchantment.AQUA_AFFINITY);
                meta.getPersistentDataContainer().remove(legacyBuffKey);
                item.setItemMeta(meta);
                inventory.setItem(slot, item);
            }
        }
    }
}
