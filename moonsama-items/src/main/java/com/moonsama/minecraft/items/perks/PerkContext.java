package com.moonsama.minecraft.items.perks;

import com.moonsama.minecraft.items.CosmeticItems;
import com.moonsama.minecraft.items.Offhand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.Random;
import java.util.logging.Logger;

/** Shared services for perks: item form switching, scheduling, messages. */
public final class PerkContext {
    private final Plugin plugin;
    private final CosmeticItems items;
    private final PerkSettings settings;
    private final Random random = new Random();

    public PerkContext(Plugin plugin, CosmeticItems items, PerkSettings settings) {
        this.plugin = plugin;
        this.items = items;
        this.settings = settings;
    }

    public Plugin plugin() {
        return plugin;
    }

    public CosmeticItems items() {
        return items;
    }

    public PerkSettings settings() {
        return settings;
    }

    public Random random() {
        return random;
    }

    public Logger logger() {
        return plugin.getLogger();
    }

    // -------------------------------------------------------------- items

    /** True when the player's off-hand slot currently holds {@code offhand} (any form). */
    public boolean holds(Player player, Offhand offhand) {
        return items.offhandOf(player.getInventory().getItemInOffHand()).filter(offhand.id()::equals).isPresent();
    }

    /** Swaps the off-hand item to another look/material without losing its identity. */
    public void setForm(Player player, Offhand offhand, Material material, int customModelData, List<String> extraLore, int amount) {
        PlayerInventory inventory = player.getInventory();
        ItemStack current = inventory.getItemInOffHand();
        if (items.offhandOf(current).filter(offhand.id()::equals).isEmpty()) {
            return;
        }
        if (current.getType() == material && Math.max(1, amount) == current.getAmount() && modelOf(current) == customModelData) {
            return; // lore is static per form, so material + model + amount identify it
        }
        ItemStack form = items.buildOffhandForm(offhand, material, customModelData, extraLore);
        form.setAmount(Math.max(1, amount));
        inventory.setItemInOffHand(form);
    }

    public void setForm(Player player, Offhand offhand, Material material, int customModelData, List<String> extraLore) {
        setForm(player, offhand, material, customModelData, extraLore, 1);
    }

    /** Restores the catalog look of the off-hand. */
    public void resetForm(Player player, Offhand offhand) {
        setForm(player, offhand, offhand.material(), offhand.customModelData(), List.of(), 1);
    }

    public static int modelOf(ItemStack item) {
        if (item == null || !item.hasItemMeta() || !item.getItemMeta().hasCustomModelDataComponent()) {
            return -1;
        }
        List<Float> floats = item.getItemMeta().getCustomModelDataComponent().getFloats();
        return floats.isEmpty() ? -1 : Math.round(floats.get(0));
    }

    /** Shows the vanilla cooldown overlay on every form of the off-hand. */
    public void showCooldown(Player player, Offhand offhand, int ticks) {
        ItemStack current = player.getInventory().getItemInOffHand();
        if (items.offhandOf(current).filter(offhand.id()::equals).isPresent()) {
            player.setCooldown(current, Math.max(0, ticks));
        }
    }

    // ---------------------------------------------------------- scheduling

    public BukkitTask later(Runnable task, long delayTicks) {
        return plugin.getServer().getScheduler().runTaskLater(plugin, task, delayTicks);
    }

    public BukkitTask every(Runnable task, long delayTicks, long periodTicks) {
        return plugin.getServer().getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks);
    }

    // ------------------------------------------------------------ messages

    public void actionBar(Player player, String text, NamedTextColor color) {
        player.sendActionBar(Component.text(text, color));
    }

    public void chat(Player player, String text, NamedTextColor color) {
        player.sendMessage(Component.text(text, color));
    }

    public static String formatDuration(long millis) {
        long seconds = Math.max(0, (millis + 999) / 1000);
        if (seconds >= 60) {
            return (seconds / 60) + "m " + (seconds % 60) + "s";
        }
        return seconds + "s";
    }
}
