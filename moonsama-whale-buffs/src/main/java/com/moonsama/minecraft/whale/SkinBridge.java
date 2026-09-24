package com.moonsama.minecraft.whale;

import com.moonsama.minecraft.skins.SkinRef;
import com.moonsama.minecraft.skins.SkinService;
import com.moonsama.minecraft.skins.event.MoonsamaSkinChangedEvent;
import org.bukkit.Server;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The only class that touches MoonsamaSkins: tells whether a player wears an NFT skin from an
 * entitled collection and re-applies buffs when the skin changes. Loaded only when the
 * MoonsamaSkins plugin is present.
 */
final class SkinBridge implements Listener {
    private final SkinService skins;
    private final WhaleBuffs buffs;

    private SkinBridge(SkinService skins, WhaleBuffs buffs) {
        this.skins = skins;
        this.buffs = buffs;
    }

    static Optional<SkinBridge> create(Server server, WhaleBuffs buffs) {
        SkinService skins = server.getServicesManager().load(SkinService.class);
        return skins == null ? Optional.empty() : Optional.of(new SkinBridge(skins, buffs));
    }

    boolean wearsEntitledSkin(UUID mojangUuid, Set<String> collections) {
        Optional<SkinRef> worn = skins.equipped(mojangUuid);
        return worn.isPresent() && collections.contains(worn.get().collection());
    }

    @EventHandler
    public void onSkinChanged(MoonsamaSkinChangedEvent event) {
        buffs.refreshEntitlement(event.getPlayer());
    }
}
