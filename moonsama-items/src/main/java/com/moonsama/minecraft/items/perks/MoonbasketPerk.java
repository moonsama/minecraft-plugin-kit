package com.moonsama.minecraft.items.perks;

import com.moonsama.minecraft.items.Offhand;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.List;

/**
 * Moonbasket: throw up to three Eggnades — harmless explosions that knock everything around
 * them away. Eggs recharge one per minute.
 */
public final class MoonbasketPerk extends Perk implements Listener {
    static final int MAX_EGGS = 3;
    static final long RECHARGE_MS = 60_000;
    static final float EXPLOSION_POWER = 3.5f;
    static final int EXPLOSION_RADIUS = 5;
    static final float EXPLOSION_STRENGTH = 1.2f;
    static final int EMPTY_MODEL = 61;               // iron sword
    static final int[] EGG_MODELS = {3, 4, 5};        // egg, by charge
    private static final long THROW_DEBOUNCE_MS = 200;
    private static final String LAST_THROW = "lastThrow";
    private static final String RECHARGE_AT = "rechargeAt";

    private final NamespacedKey eggnadeKey;

    public MoonbasketPerk(PerkContext ctx, Offhand offhand) {
        super(ctx, offhand);
        this.eggnadeKey = new NamespacedKey(ctx.plugin(), "eggnade");
        ctx.plugin().getServer().getPluginManager().registerEvents(this, ctx.plugin());
    }

    @Override
    public void onEquip(Player player, PerkState state) {
        state.setBalance(MAX_EGGS);
        updateForm(player, state);
    }

    @Override
    public void tick(Player player, PerkState state) {
        if (state.balance() < MAX_EGGS) {
            Long rechargeAt = state.get(RECHARGE_AT);
            long now = System.currentTimeMillis();
            if (rechargeAt == null) {
                state.put(RECHARGE_AT, now + RECHARGE_MS);
            } else if (now >= rechargeAt) {
                state.setBalance(state.balance() + 1);
                state.put(RECHARGE_AT, state.balance() < MAX_EGGS ? now + RECHARGE_MS : null);
            }
        }
        updateForm(player, state);
    }

    @Override
    public void onInteract(Player player, PlayerInteractEvent event, PerkState state) {
        event.setCancelled(true); // never a vanilla egg throw
        long now = System.currentTimeMillis();
        Long last = state.get(LAST_THROW);
        if (last != null && now - last < THROW_DEBOUNCE_MS) {
            return;
        }
        state.put(LAST_THROW, now);
        if (state.balance() <= 0) {
            ctx.actionBar(player, "Cannot throw an Eggnade right now", NamedTextColor.LIGHT_PURPLE);
            return;
        }
        state.setBalance(state.balance() - 1);
        if (!state.has(RECHARGE_AT)) {
            state.put(RECHARGE_AT, now + RECHARGE_MS);
        }
        ItemStack look = new ItemStack(Material.EGG);
        var meta = look.getItemMeta();
        com.moonsama.minecraft.items.CosmeticItems.setModelData(meta, EGG_MODELS[EGG_MODELS.length - 1]);
        look.setItemMeta(meta);
        player.launchProjectile(Snowball.class, player.getLocation().getDirection().multiply(1.5), snowball -> {
            snowball.setItem(look);
            snowball.getPersistentDataContainer().set(eggnadeKey, PersistentDataType.BYTE, (byte) 1);
        });
        updateForm(player, state);
    }

    private void updateForm(Player player, PerkState state) {
        int balance = state.balance();
        if (balance <= 0) {
            ctx.setForm(player, offhand, Material.IRON_SWORD, EMPTY_MODEL, List.of("Out of Eggnades"));
            Long rechargeAt = state.get(RECHARGE_AT);
            if (rechargeAt != null) {
                ctx.showCooldown(player, offhand, (int) Math.max(0, (rechargeAt - System.currentTimeMillis()) / 50));
            }
        } else {
            int model = EGG_MODELS[Math.min(balance, EGG_MODELS.length) - 1];
            ctx.setForm(player, offhand, Material.EGG, model, List.of("Right-click to throw an Eggnade"), balance);
        }
    }

    private boolean isEggnade(Entity entity) {
        return entity instanceof Snowball && entity.getPersistentDataContainer().has(eggnadeKey, PersistentDataType.BYTE);
    }

    @EventHandler
    public void onHit(ProjectileHitEvent event) {
        if (!isEggnade(event.getEntity())) {
            return;
        }
        Snowball snowball = (Snowball) event.getEntity();
        Location at = snowball.getLocation();
        boolean breakBlocks = ctx.settings().moonbasketBreaksBlocks();
        at.getWorld().createExplosion(at.getX(), at.getY(), at.getZ(), EXPLOSION_POWER, false, breakBlocks, snowball);
        double maxDistanceSquared = (double) EXPLOSION_RADIUS * EXPLOSION_RADIUS;
        for (Entity entity : at.getWorld().getNearbyEntities(at, EXPLOSION_RADIUS * 2, EXPLOSION_RADIUS * 2, EXPLOSION_RADIUS * 2)) {
            if (entity == snowball || entity.isInvulnerable() || !entity.hasGravity()) {
                continue;
            }
            double distance = entity.getLocation().distanceSquared(at);
            if (distance > maxDistanceSquared) {
                continue;
            }
            float strength = (float) (1 - distance / maxDistanceSquared) * EXPLOSION_STRENGTH;
            knockback(entity, at, strength);
        }
    }

    private void knockback(Entity entity, Location epicenter, float strength) {
        Vector direction = entity.getLocation().add(0, entity.getHeight() / 2, 0).toVector().subtract(epicenter.toVector());
        double length = direction.length();
        if (length < 0.001) {
            direction = new Vector(0, 1, 0);
            length = 1;
        }
        if (direction.getY() < length / 2) {
            direction.setY(length / 2);
        }
        Vector velocity = direction.normalize().multiply(strength);
        for (int i = 0; i < 2; i++) {
            ctx.later(() -> entity.setVelocity(velocity.clone()), i);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onExplosionDamage(EntityDamageByEntityEvent event) {
        if (event.getCause() == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION && isEggnade(event.getDamager())) {
            event.setDamage(0);
        }
    }

    @Override
    public void onUnequip(Player player, PerkState state) {
        ctx.resetForm(player, offhand);
    }
}
