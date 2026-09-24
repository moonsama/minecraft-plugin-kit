package com.moonsama.minecraft.items.perks;

import com.moonsama.minecraft.items.Offhand;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.TreeType;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Moontree: plant up to four saplings (crimson fungus placeholders) that grow into spruce trees
 * after ten seconds if there is room. Saplings recharge one per minute.
 */
public final class MoontreePerk extends Perk implements Listener {
    static final int MAX_SAPLINGS = 4;
    static final long RECHARGE_MS = 60_000;
    static final int MIN_SKY_LIGHT = 13;
    static final long GROW_DELAY_TICKS = 20 * 10;
    static final int GROW_ATTEMPTS = 6;
    static final Material SAPLING = Material.CRIMSON_FUNGUS;
    static final int BUZZER_MODEL = 59;   // iron sword
    private static final long PLACE_DEBOUNCE_MS = 200;
    private static final String LAST_PLACE = "lastPlace";
    private static final String RECHARGE_AT = "rechargeAt";

    /** Sapling blocks we placed and still watch; their drops are suppressed. */
    private final Set<Block> saplings = new HashSet<>();

    public MoontreePerk(PerkContext ctx, Offhand offhand) {
        super(ctx, offhand);
        ctx.plugin().getServer().getPluginManager().registerEvents(this, ctx.plugin());
    }

    @Override
    public void onEquip(Player player, PerkState state) {
        state.setBalance(MAX_SAPLINGS);
        updateForm(player, state);
    }

    @Override
    public void tick(Player player, PerkState state) {
        if (state.balance() < MAX_SAPLINGS) {
            Long rechargeAt = state.get(RECHARGE_AT);
            long now = System.currentTimeMillis();
            if (rechargeAt == null) {
                state.put(RECHARGE_AT, now + RECHARGE_MS);
            } else if (now >= rechargeAt) {
                state.setBalance(state.balance() + 1);
                state.put(RECHARGE_AT, state.balance() < MAX_SAPLINGS ? now + RECHARGE_MS : null);
            }
        }
        updateForm(player, state);
    }

    @Override
    public void onInteract(Player player, PlayerInteractEvent event, PerkState state) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        event.setCancelled(true);
        long now = System.currentTimeMillis();
        Long last = state.get(LAST_PLACE);
        if (last != null && now - last < PLACE_DEBOUNCE_MS) {
            return;
        }
        state.put(LAST_PLACE, now);
        if (state.balance() <= 0) {
            ctx.actionBar(player, "No Moontree sapling ready", NamedTextColor.LIGHT_PURPLE);
            return;
        }
        Block target = event.getClickedBlock().getRelative(event.getBlockFace());
        Material type = target.getType();
        if (!(type.isAir() || type == Material.SHORT_GRASS || type == Material.TALL_GRASS)) {
            ctx.actionBar(player, "Cannot place a Moontree here", NamedTextColor.LIGHT_PURPLE);
            return;
        }
        if (!target.getRelative(0, -1, 0).getType().isSolid()) {
            ctx.actionBar(player, "Cannot place a Moontree here", NamedTextColor.LIGHT_PURPLE);
            return;
        }
        if (target.getLightFromSky() < MIN_SKY_LIGHT) {
            ctx.actionBar(player, "Sky light needed", NamedTextColor.LIGHT_PURPLE);
            return;
        }
        state.setBalance(state.balance() - 1);
        if (!state.has(RECHARGE_AT)) {
            state.put(RECHARGE_AT, now + RECHARGE_MS);
        }
        target.setType(SAPLING);
        saplings.add(target);
        scheduleGrowth(target, 0);
        updateForm(player, state);
    }

    private void scheduleGrowth(Block block, int attempt) {
        BukkitTask[] handle = new BukkitTask[1];
        handle[0] = ctx.later(() -> {
            if (block.getType() != SAPLING) {
                saplings.remove(block);
                return; // someone removed it
            }
            block.setType(Material.AIR);
            boolean grown = block.getWorld().generateTree(block.getLocation(), ctx.random(), TreeType.REDWOOD);
            if (grown) {
                saplings.remove(block);
                return;
            }
            block.setType(SAPLING);
            for (Player nearby : block.getWorld().getNearbyPlayers(block.getLocation(), 8)) {
                ctx.actionBar(nearby, "The Moontree needs more space", NamedTextColor.RED);
            }
            if (attempt + 1 >= GROW_ATTEMPTS) {
                block.setType(Material.AIR);
                saplings.remove(block);
                for (Player nearby : block.getWorld().getNearbyPlayers(block.getLocation(), 8)) {
                    ctx.actionBar(nearby, "The Moontree did not grow", NamedTextColor.LIGHT_PURPLE);
                }
                return;
            }
            scheduleGrowth(block, attempt + 1);
        }, GROW_DELAY_TICKS);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSaplingDrop(BlockDropItemEvent event) {
        if (event.getBlockState().getType() == SAPLING && saplings.remove(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    private void updateForm(Player player, PerkState state) {
        int balance = state.balance();
        if (balance <= 0) {
            ctx.setForm(player, offhand, Material.IRON_SWORD, BUZZER_MODEL, List.of("Growing new saplings"));
            Long rechargeAt = state.get(RECHARGE_AT);
            if (rechargeAt != null) {
                ctx.showCooldown(player, offhand, (int) Math.max(0, (rechargeAt - System.currentTimeMillis()) / 50));
            }
        } else {
            ctx.setForm(player, offhand, offhand.material(), offhand.customModelData(), List.of("Right-click the ground to plant"), balance);
        }
    }

    @Override
    public void onUnequip(Player player, PerkState state) {
        ctx.resetForm(player, offhand);
    }
}
