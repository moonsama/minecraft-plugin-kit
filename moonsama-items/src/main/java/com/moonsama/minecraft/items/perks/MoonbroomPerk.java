package com.moonsama.minecraft.items.perks;

import com.moonsama.minecraft.items.Offhand;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.List;

/**
 * Moonbroom: right-click to launch yourself on a broom (a ridden snowball). One flight per
 * minute; hitting another player while the broom is ready puts it on a 10 s cooldown.
 */
public final class MoonbroomPerk extends Perk {
    static final long COOLDOWN_MS = 60_000;
    static final long PVP_COOLDOWN_MS = 10_000;
    static final double LAUNCH_SPEED = 1.5;
    static final int PROJECTILE_MODEL = 114;
    static final int READY_MODEL = 10;      // shield
    static final int BUZZER_MODEL = 58;     // iron sword
    private static final int LANDING_CHECK_AFTER_TICKS = 40;
    private static final String BROOM = "broom";
    private static final String STARTED = "started";
    private static final Particle.DustOptions DUST = new Particle.DustOptions(Color.fromRGB(150, 60, 200), 1.0f);

    public MoonbroomPerk(PerkContext ctx, Offhand offhand) {
        super(ctx, offhand);
    }

    @Override
    public void onInteract(Player player, PlayerInteractEvent event, PerkState state) {
        event.setCancelled(true);
        tryLaunch(player, state);
    }

    @Override
    public void tick(Player player, PerkState state) {
        Snowball broom = state.get(BROOM);
        if (broom != null) {
            if (!broom.isValid() || broom.isDead() || !broom.getPassengers().contains(player)) {
                end(player, state);
            } else {
                Location at = broom.getLocation();
                at.getWorld().spawnParticle(Particle.DUST, at, 3, 0.2, 0.2, 0.2, 0, DUST);
                long started = state.<Long>get(STARTED);
                if (System.currentTimeMillis() - started >= LANDING_CHECK_AFTER_TICKS * 50L && nearSolid(at)) {
                    end(player, state);
                }
            }
            return;
        }
        if (player.isHandRaised() && ctx.holds(player, offhand)) {
            tryLaunch(player, state);
        }
        updateForm(player, state);
    }

    private void tryLaunch(Player player, PerkState state) {
        if (state.has(BROOM) || !state.ready() || player.isFlying() || player.isInsideVehicle()) {
            return;
        }
        Vector direction = player.getLocation().getDirection().normalize().multiply(LAUNCH_SPEED);
        Location spawn = player.getLocation().add(0, 0.2, 0);
        Snowball broom = player.getWorld().spawn(spawn, Snowball.class, s -> {
            s.setShooter(player);
            ItemStack look = new ItemStack(Material.SUGAR);
            var meta = look.getItemMeta();
            com.moonsama.minecraft.items.CosmeticItems.setModelData(meta, PROJECTILE_MODEL);
            look.setItemMeta(meta);
            s.setItem(look);
            s.setVelocity(direction);
        });
        if (!broom.addPassenger(player)) {
            broom.remove();
            ctx.actionBar(player, "The magic seems too weak here...", NamedTextColor.LIGHT_PURPLE);
            return;
        }
        state.put(BROOM, broom);
        state.put(STARTED, System.currentTimeMillis());
        state.startCooldown(COOLDOWN_MS);
    }

    private void end(Player player, PerkState state) {
        Snowball broom = state.get(BROOM);
        if (broom != null) {
            broom.eject();
            broom.remove();
        }
        state.remove(BROOM);
        state.remove(STARTED);
        updateForm(player, state);
    }

    private void updateForm(Player player, PerkState state) {
        if (state.ready()) {
            ctx.setForm(player, offhand, Material.SHIELD, READY_MODEL, List.of("Right-click to take off"));
        } else {
            ctx.setForm(player, offhand, Material.IRON_SWORD, BUZZER_MODEL, List.of("Recharging"));
            ctx.showCooldown(player, offhand, state.remainingTicks());
        }
    }

    private static boolean nearSolid(Location at) {
        Block below = at.clone().subtract(0, 0.5, 0).getBlock();
        if (below.getType().isSolid()) {
            return true;
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (at.clone().add(dx, 0, dz).getBlock().getType().isSolid()) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void onAttackPlayer(Player attacker, Player victim, EntityDamageByEntityEvent event, PerkState state) {
        if (!state.has(BROOM) && state.remainingMs() < PVP_COOLDOWN_MS) {
            state.startCooldown(PVP_COOLDOWN_MS);
            updateForm(attacker, state);
        }
    }

    @Override
    public void onDeath(Player player, PerkState state) {
        end(player, state);
    }

    @Override
    public void onUnequip(Player player, PerkState state) {
        Snowball broom = state.get(BROOM);
        if (broom != null) {
            broom.eject();
            broom.remove();
        }
        state.remove(BROOM);
        ctx.resetForm(player, offhand);
    }
}
