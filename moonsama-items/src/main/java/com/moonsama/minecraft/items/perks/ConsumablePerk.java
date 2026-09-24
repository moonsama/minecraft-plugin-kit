package com.moonsama.minecraft.items.perks;

import com.moonsama.minecraft.items.Offhand;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;

import java.util.List;

/**
 * An off-hand that turns into food/drink when ready, is consumed from the off-hand, and then
 * recharges as a "buzzer". Moonrum and Moonburger.
 */
public abstract class ConsumablePerk extends Perk {
    private final Material readyMaterial;
    private final int readyModel;
    private final int buzzerModel;
    private final long cooldownMs;
    private final int foodBonus;

    protected ConsumablePerk(PerkContext ctx, Offhand offhand, Material readyMaterial, int readyModel,
                             int buzzerModel, long cooldownMs, int foodBonus) {
        super(ctx, offhand);
        this.readyMaterial = readyMaterial;
        this.readyModel = readyModel;
        this.buzzerModel = buzzerModel;
        this.cooldownMs = cooldownMs;
        this.foodBonus = foodBonus;
    }

    /** The perk's own effects, applied when the item is consumed. */
    protected abstract void onConsumed(Player player, PerkState state);

    @Override
    public void onEquip(Player player, PerkState state) {
        updateForm(player, state);
    }

    @Override
    public void tick(Player player, PerkState state) {
        updateForm(player, state);
    }

    @Override
    public void onConsume(Player player, PlayerItemConsumeEvent event, PerkState state) {
        if (!state.ready() || event.getItem().getType() != readyMaterial) {
            event.setCancelled(true);
            return;
        }
        // The vanilla result (empty bottle / nothing) is replaced by the recharging form.
        state.startCooldown(cooldownMs);
        event.setReplacement(ctx.items().buildOffhandForm(offhand, Material.IRON_SWORD, buzzerModel, List.of("Recharging")));
        onConsumed(player, state);
        ctx.later(() -> {
            if (player.isOnline()) {
                ctx.showCooldown(player, offhand, state.remainingTicks());
            }
        }, 1);
    }

    @Override
    public void onFoodChange(Player player, FoodLevelChangeEvent event, PerkState state) {
        if (event.getItem() != null && event.getItem().getType() == readyMaterial) {
            // Legacy fixed the nutrition instead of using the vanilla value of the base food.
            event.setFoodLevel(Math.min(20, player.getFoodLevel() + foodBonus));
        }
    }

    private void updateForm(Player player, PerkState state) {
        if (state.ready()) {
            ctx.setForm(player, offhand, readyMaterial, readyModel, List.of(readyHint()));
        } else {
            ctx.setForm(player, offhand, Material.IRON_SWORD, buzzerModel, List.of("Recharging"));
            ctx.showCooldown(player, offhand, state.remainingTicks());
        }
    }

    protected abstract String readyHint();

    @Override
    public void onUnequip(Player player, PerkState state) {
        ctx.resetForm(player, offhand);
    }
}
