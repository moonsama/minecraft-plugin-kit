package com.moonsama.minecraft.skins.event;

import com.moonsama.minecraft.skins.SkinRef;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Fired on the main thread after MoonsamaSkins changed a player's visible skin.
 *
 * <p>{@link #skin()} is empty when the player went back to their own Mojang skin.
 */
public final class MoonsamaSkinChangedEvent extends PlayerEvent {
    private static final HandlerList HANDLERS = new HandlerList();
    private final SkinRef skin;
    private final Reason reason;

    public MoonsamaSkinChangedEvent(Player player, SkinRef skin, Reason reason) {
        super(player);
        this.skin = skin;
        this.reason = reason;
    }

    public Optional<SkinRef> skin() {
        return Optional.ofNullable(skin);
    }

    public Reason reason() {
        return reason;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    public enum Reason {
        /** The player picked a skin (command or menu). */
        SELECTED,
        /** The stored skin was re-applied on join. */
        REAPPLIED,
        /** The player reset to their own skin. */
        RESET,
        /** The NFT left the player's Portal holdings, so the skin was removed. */
        UNOWNED
    }
}
