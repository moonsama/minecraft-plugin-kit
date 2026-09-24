package com.moonsama.minecraft.skins;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.moonsama.minecraft.api.AssetHolding;
import com.moonsama.minecraft.api.HoldingsSnapshot;
import com.moonsama.minecraft.api.MoonsamaService;
import com.moonsama.minecraft.skins.event.MoonsamaSkinChangedEvent;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Applies signed NFT skins to players and keeps them consistent with Portal ownership.
 *
 * <p>All public methods that touch a {@link Player} must run on the main thread; the
 * asynchronous entry points hop back onto it before doing so.
 */
public final class SkinService {
    private static final String TEXTURES = "textures";

    private final Plugin plugin;
    private final MoonsamaService moonsama;
    private final SkinCatalog catalog;
    private final EquippedSkinStore store;

    public SkinService(Plugin plugin, MoonsamaService moonsama, SkinCatalog catalog, EquippedSkinStore store) {
        this.plugin = plugin;
        this.moonsama = moonsama;
        this.catalog = catalog;
        this.store = store;
    }

    public SkinCatalog catalog() {
        return catalog;
    }

    public Optional<SkinRef> equipped(UUID mojangUuid) {
        return store.get(mojangUuid).map(EquippedSkinStore.Equipped::ref);
    }

    /** True when the player wears a composed (wardrobe) skin rather than an NFT's bundled one. */
    public boolean wearsCustom(UUID mojangUuid) {
        return store.get(mojangUuid).map(EquippedSkinStore.Equipped::isCustom).orElse(false);
    }

    /**
     * NFTs the player holds (per MoonsamaCore's cache) for which a signed skin is bundled.
     */
    public CompletableFuture<OwnedSkins> ownedSkins(UUID mojangUuid) {
        return moonsama.cachedHoldings(mojangUuid).thenApply(snapshot -> {
            List<SignedSkin> owned = new ArrayList<>();
            for (AssetHolding holding : snapshot.holdings()) {
                if (!holding.hasPositiveBalance()) {
                    continue;
                }
                catalog.find(holding.collection(), holding.tokenId()).ifPresent(owned::add);
            }
            return new OwnedSkins(snapshot.status(), List.copyOf(owned));
        });
    }

    /**
     * Wears {@code ref} if the player owns it. Completes on the main thread with the outcome.
     */
    public CompletableFuture<Result> wear(Player player, SkinRef ref) {
        Optional<SignedSkin> skin = catalog.find(ref);
        if (skin.isEmpty()) {
            return CompletableFuture.completedFuture(Result.UNKNOWN_SKIN);
        }
        return verifyOwnership(player, ref).thenCompose(result -> onMain(() -> {
            if (result == Result.APPLIED && player.isOnline()) {
                apply(player, ref, skin.get().value(), skin.get().signature(), null, null,
                        MoonsamaSkinChangedEvent.Reason.SELECTED);
            }
            return result;
        }));
    }

    /**
     * Wears a composed skin (e.g. from the wardrobe) that is based on {@code base}, which the
     * player must own. {@code value}/{@code signature} must be a Mojang-signed textures property.
     * Completes on the main thread with the outcome.
     */
    public CompletableFuture<Result> wearCustom(Player player, SkinRef base, String value, String signature) {
        if (value == null || signature == null || value.isBlank() || signature.isBlank()) {
            return CompletableFuture.completedFuture(Result.UNKNOWN_SKIN);
        }
        return verifyOwnership(player, base).thenCompose(result -> onMain(() -> {
            if (result == Result.APPLIED && player.isOnline()) {
                apply(player, base, value, signature, value, signature, MoonsamaSkinChangedEvent.Reason.SELECTED);
            }
            return result;
        }));
    }

    private CompletableFuture<Result> verifyOwnership(Player player, SkinRef ref) {
        UUID mojangUuid = player.getUniqueId();
        return moonsama.linkedPlayer(mojangUuid)
                .thenCompose(linked -> {
                    if (linked.isEmpty()) {
                        return CompletableFuture.completedFuture(Result.NOT_LINKED);
                    }
                    return moonsama.cachedHoldings(mojangUuid).thenCompose(snapshot -> {
                        if (owns(snapshot.holdings(), ref)) {
                            return CompletableFuture.completedFuture(Result.APPLIED);
                        }
                        if (snapshot.status() == HoldingsSnapshot.Status.FRESH) {
                            return CompletableFuture.completedFuture(Result.NOT_OWNED);
                        }
                        // Cache is cold or stale: ask Portal directly before refusing.
                        return moonsama.holdings(mojangUuid).thenApply(holdings ->
                                owns(holdings, ref) ? Result.APPLIED : Result.NOT_OWNED);
                    });
                })
                .exceptionally(failure -> {
                    plugin.getLogger().log(Level.WARNING, "Could not verify ownership of " + ref, failure);
                    return Result.UNAVAILABLE;
                });
    }

    /**
     * Restores the player's own Mojang skin and forgets the stored choice. Main thread only.
     */
    public boolean reset(Player player) {
        return restore(player, MoonsamaSkinChangedEvent.Reason.RESET);
    }

    /**
     * Drops the stored choice for an offline player (e.g. after a Portal erasure request).
     */
    public void forget(UUID mojangUuid) {
        store.remove(mojangUuid);
    }

    /**
     * Re-applies the stored skin after a join. Main thread only.
     */
    public void reapply(Player player) {
        Optional<EquippedSkinStore.Equipped> record = store.get(player.getUniqueId());
        if (record.isEmpty()) {
            return;
        }
        EquippedSkinStore.Equipped equipped = record.get();
        if (equipped.isCustom()) {
            apply(player, equipped.ref(), equipped.customValue(), equipped.customSignature(),
                    equipped.customValue(), equipped.customSignature(), MoonsamaSkinChangedEvent.Reason.REAPPLIED);
            return;
        }
        Optional<SignedSkin> skin = catalog.find(equipped.ref());
        if (skin.isEmpty()) {
            store.remove(player.getUniqueId());
            return;
        }
        apply(player, equipped.ref(), skin.get().value(), skin.get().signature(), null, null,
                MoonsamaSkinChangedEvent.Reason.REAPPLIED);
    }

    /**
     * Removes the skin when fresh holdings no longer include it. Returns true when it did.
     * Main thread only.
     */
    public boolean revalidate(Player player, List<AssetHolding> freshHoldings) {
        Optional<SkinRef> ref = equipped(player.getUniqueId());
        if (ref.isEmpty() || owns(freshHoldings, ref.get())) {
            return false;
        }
        return restore(player, MoonsamaSkinChangedEvent.Reason.UNOWNED);
    }

    private void apply(Player player, SkinRef ref, String value, String signature,
                       String customValue, String customSignature, MoonsamaSkinChangedEvent.Reason reason) {
        UUID mojangUuid = player.getUniqueId();
        PlayerProfile profile = player.getPlayerProfile();
        EquippedSkinStore.Equipped previous = store.get(mojangUuid).orElse(null);
        String originalValue = previous != null ? previous.originalValue() : null;
        String originalSignature = previous != null ? previous.originalSignature() : null;
        if (previous == null) {
            Optional<ProfileProperty> current = textures(profile);
            originalValue = current.map(ProfileProperty::getValue).orElse(null);
            originalSignature = current.map(ProfileProperty::getSignature).orElse(null);
        }

        profile.removeProperty(TEXTURES);
        profile.setProperty(new ProfileProperty(TEXTURES, value, signature));
        player.setPlayerProfile(profile);

        store.put(mojangUuid, new EquippedSkinStore.Equipped(
                ref, originalValue, originalSignature, Instant.now(), customValue, customSignature
        ));
        plugin.getServer().getPluginManager().callEvent(new MoonsamaSkinChangedEvent(player, ref, reason));
    }

    private boolean restore(Player player, MoonsamaSkinChangedEvent.Reason reason) {
        Optional<EquippedSkinStore.Equipped> record = store.remove(player.getUniqueId());
        if (record.isEmpty()) {
            return false;
        }
        PlayerProfile profile = player.getPlayerProfile();
        profile.removeProperty(TEXTURES);
        if (record.get().hasOriginal()) {
            profile.setProperty(new ProfileProperty(
                    TEXTURES, record.get().originalValue(), record.get().originalSignature()
            ));
        }
        player.setPlayerProfile(profile);
        plugin.getServer().getPluginManager().callEvent(new MoonsamaSkinChangedEvent(player, null, reason));
        return true;
    }

    private static Optional<ProfileProperty> textures(PlayerProfile profile) {
        return profile.getProperties().stream()
                .filter(property -> TEXTURES.equals(property.getName()))
                .findFirst();
    }

    static boolean owns(List<AssetHolding> holdings, SkinRef ref) {
        String tokenId = Long.toString(ref.tokenId());
        return holdings.stream().anyMatch(holding ->
                ref.collection().equals(holding.collection())
                        && tokenId.equals(normalize(holding.tokenId()))
                        && holding.hasPositiveBalance()
        );
    }

    private static String normalize(String tokenId) {
        if (tokenId == null) {
            return "";
        }
        try {
            return Long.toString(Long.parseLong(tokenId.trim()));
        } catch (NumberFormatException ignored) {
            return tokenId.trim();
        }
    }

    private <T> CompletableFuture<T> onMain(Supplier<T> action) {
        if (plugin.getServer().isPrimaryThread()) {
            return CompletableFuture.completedFuture(action.get());
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                future.complete(action.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    public enum Result {
        APPLIED,
        NOT_LINKED,
        NOT_OWNED,
        UNKNOWN_SKIN,
        UNAVAILABLE
    }

    public record OwnedSkins(HoldingsSnapshot.Status status, List<SignedSkin> skins) {
    }
}
