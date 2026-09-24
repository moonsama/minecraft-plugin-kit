package com.moonsama.minecraft.items.perks;

import com.moonsama.minecraft.items.Offhand;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Moonbrella: raise it (right-click / block with the shield) to float down slowly. All colours
 * behave the same; Moonglow, Neon and Flare add a sound.
 */
public final class MoonbrellaPerk extends Perk {
    /** Legacy: effect kept alive for {@code timeout = 10} periods after the last activation. */
    private static final int TIMEOUT_PERIODS = 10;
    private static final int EFFECT_TICKS = 10;
    private static final String TIMEOUT = "timeout";

    private final Sound extraSound;
    private final float extraVolume;

    public MoonbrellaPerk(PerkContext ctx, Offhand offhand) {
        super(ctx, offhand);
        String id = offhand.id();
        if (id.endsWith("moonglow")) {
            extraSound = Sound.BLOCK_AMETHYST_BLOCK_CHIME;
            extraVolume = 0.5f;
        } else if (id.endsWith("neon")) {
            extraSound = Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO;
            extraVolume = 0.2f;
        } else if (id.endsWith("flare")) {
            extraSound = Sound.ITEM_FIRECHARGE_USE;
            extraVolume = 0.2f;
        } else {
            extraSound = null;
            extraVolume = 0;
        }
    }

    @Override
    public void onInteract(Player player, PlayerInteractEvent event, PerkState state) {
        if (!state.has(TIMEOUT) || (int) state.<Integer>get(TIMEOUT) <= 0) {
            Location at = player.getEyeLocation().add(player.getLocation().getDirection().multiply(0.5));
            player.getWorld().playSound(at, Sound.ITEM_ARMOR_EQUIP_ELYTRA, SoundCategory.PLAYERS, 0.2f, 1f);
            if (extraSound != null) {
                player.getWorld().playSound(at, extraSound, SoundCategory.PLAYERS, extraVolume, 1f);
            }
        }
        state.put(TIMEOUT, TIMEOUT_PERIODS);
        apply(player);
    }

    @Override
    public void tick(Player player, PerkState state) {
        int timeout = state.has(TIMEOUT) ? state.<Integer>get(TIMEOUT) : 0;
        boolean blocking = player.isBlocking() || player.isHandRaised();
        if (blocking) {
            timeout = Math.max(timeout, 1);
        }
        if (timeout > 0) {
            apply(player);
            state.put(TIMEOUT, blocking ? timeout : timeout - 1);
        }
    }

    private void apply(Player player) {
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, EFFECT_TICKS, 0, true, false, true));
    }

    @Override
    public void onUnequip(Player player, PerkState state) {
        state.remove(TIMEOUT);
    }
}
