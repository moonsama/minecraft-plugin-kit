package com.moonsama.minecraft.items;

import com.moonsama.minecraft.skins.SkinRef;
import com.moonsama.minecraft.skins.SkinService;
import com.moonsama.minecraft.skins.event.MoonsamaSkinChangedEvent;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.Optional;

/**
 * Connects hats to MoonsamaSkins: whenever a player's NFT skin changes, the matching 3D hat is
 * put on (or taken off). Only loaded when the MoonsamaSkins plugin is present, so this is the
 * single class allowed to reference its API.
 */
final class SkinHatBridge implements Listener {
    private final ItemsService service;
    private final SkinService skins;

    private SkinHatBridge(ItemsService service, SkinService skins) {
        this.service = service;
        this.skins = skins;
    }

    /** Empty when MoonsamaSkins has not registered its service. */
    static Optional<SkinHatBridge> create(Server server, ItemsService service) {
        SkinService skins = server.getServicesManager().load(SkinService.class);
        return skins == null ? Optional.empty() : Optional.of(new SkinHatBridge(service, skins));
    }

    @EventHandler
    public void onSkinChanged(MoonsamaSkinChangedEvent event) {
        apply(event.getPlayer(), event.skin());
    }

    /** Syncs the hat with whatever skin is currently worn (join, respawn). */
    void sync(Player player) {
        apply(player, skins.equipped(player.getUniqueId()));
    }

    private void apply(Player player, Optional<SkinRef> skin) {
        if (skin.isPresent()) {
            service.updateHat(player, skin.get().collection(), skin.get().tokenId());
        } else {
            service.updateHat(player, null, null);
        }
    }
}
