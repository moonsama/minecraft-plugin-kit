package com.moonsama.minecraft.wardrobe;

import com.moonsama.minecraft.api.AssetHolding;
import com.moonsama.minecraft.api.MoonsamaService;
import com.moonsama.minecraft.api.SkinSigner;
import com.moonsama.minecraft.compositor.Composition;
import com.moonsama.minecraft.compositor.CompositorData;
import com.moonsama.minecraft.compositor.CompositorData.SlotDef;
import com.moonsama.minecraft.compositor.CompositorData.SlotValue;
import com.moonsama.minecraft.compositor.SkinCompositor;
import com.moonsama.minecraft.skins.SignedSkin;
import com.moonsama.minecraft.skins.SkinRef;
import com.moonsama.minecraft.skins.SkinService;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Wardrobe use cases: which NFTs can be customized, which parts are available, and turning a
 * {@link Look} into a worn skin (render → sign → wear).
 */
public final class WardrobeService {
    private final Plugin plugin;
    private final MoonsamaService moonsama;
    private final SkinService skins;
    private final SkinCompositor compositor;
    private final Unlocks unlocks;
    private final WardrobeStore store;
    private final Supplier<SkinSigner> signer;
    private final Executor renderExecutor;
    private final List<String> collections;
    private final Set<String> hiddenSlots;

    public WardrobeService(Plugin plugin, MoonsamaService moonsama, SkinService skins, SkinCompositor compositor,
                           WardrobeStore store, Supplier<SkinSigner> signer, Executor renderExecutor,
                           List<String> collections, Set<String> hiddenSlots) {
        this.plugin = plugin;
        this.moonsama = moonsama;
        this.skins = skins;
        this.compositor = compositor;
        this.unlocks = new Unlocks(compositor);
        this.store = store;
        this.signer = signer;
        this.renderExecutor = renderExecutor;
        this.collections = collections.stream().filter(compositor::supports).toList();
        this.hiddenSlots = Set.copyOf(hiddenSlots);
    }

    public List<String> collections() {
        return collections;
    }

    public SkinCompositor compositor() {
        return compositor;
    }

    public Unlocks unlocks() {
        return unlocks;
    }

    public com.moonsama.minecraft.skins.SkinCatalog skinCatalog() {
        return skins.catalog();
    }

    public boolean signingAvailable() {
        SkinSigner s = signer.get();
        return s != null && s.isAvailable();
    }

    public boolean canCustomize(SkinRef ref) {
        return collections.contains(ref.collection())
                && compositor.defaultSlots(ref.collection(), ref.tokenId()).isPresent();
    }

    /** Customizable, visible slots of a collection in catalog order. */
    public List<SlotDef> slots(String portalCollection) {
        return compositor.customizableSlots(portalCollection).stream()
                .filter(slot -> "asset".equalsIgnoreCase(slot.type()))
                .filter(slot -> !hiddenSlots.contains(slot.referenceId()))
                .toList();
    }

    /** NFTs the player holds (per the cache) that the wardrobe can customize. */
    public CompletableFuture<Owned> ownedBases(UUID mojangUuid) {
        return moonsama.cachedHoldings(mojangUuid).thenApply(snapshot -> {
            List<SkinRef> refs = new ArrayList<>();
            for (AssetHolding holding : snapshot.holdings()) {
                if (!holding.hasPositiveBalance() || !collections.contains(holding.collection())) {
                    continue;
                }
                Long tokenId = Unlocks.parseToken(holding.tokenId());
                if (tokenId == null) {
                    continue;
                }
                SkinRef ref = new SkinRef(holding.collection(), tokenId);
                if (canCustomize(ref)) {
                    refs.add(ref);
                }
            }
            return new Owned(snapshot.status().name(), List.copyOf(refs));
        });
    }

    public Look look(UUID mojangUuid, SkinRef ref) {
        return store.get(mojangUuid, ref).orElseGet(Look::new);
    }

    public void saveLook(UUID mojangUuid, SkinRef ref, Look look) {
        store.put(mojangUuid, ref, look);
    }

    public void resetLook(UUID mojangUuid, SkinRef ref) {
        store.remove(mojangUuid, ref);
    }

    public void forget(UUID mojangUuid) {
        store.forget(mojangUuid);
    }

    /** The NFT's own part in a slot, if any. */
    public Optional<SlotValue> defaultPart(SkinRef ref, String slot) {
        String composer = compositor.composerCollection(ref.collection()).orElse(null);
        return compositor.defaultSlots(ref.collection(), ref.tokenId()).flatMap(slots -> slots.stream()
                .filter(v -> v.slot().equals(slot))
                .findFirst()
                .map(v -> v.collection() == null ? new SlotValue(slot, composer, v.asset()) : v));
    }

    /** What the player currently has in a slot, taking the look into account. */
    public Optional<SlotValue> currentPart(UUID mojangUuid, SkinRef ref, String slot) {
        Look look = look(mojangUuid, ref);
        if (look.isCleared(slot)) {
            return Optional.empty();
        }
        return look.chosen(slot).or(() -> defaultPart(ref, slot));
    }

    /** Parts the player may put into a slot, according to cached holdings. */
    public CompletableFuture<List<SlotValue>> options(UUID mojangUuid, SkinRef ref, String slot) {
        return moonsama.cachedHoldings(mojangUuid)
                .thenApply(snapshot -> unlocks.available(ref.collection(), slot, snapshot.holdings()));
    }

    public String partName(SlotValue value) {
        return compositor.assetName(value);
    }

    public String slotName(SlotDef slot) {
        String id = slot.referenceId().replace('_', ' ');
        return id.substring(0, 1).toUpperCase(Locale.ROOT) + id.substring(1);
    }

    /**
     * Renders the player's look for {@code ref}, has it signed and wears it. Completes on the
     * main thread. A look without overrides simply wears the NFT's bundled skin.
     */
    public CompletableFuture<Outcome> apply(Player player, SkinRef ref) {
        UUID mojangUuid = player.getUniqueId();
        if (!canCustomize(ref)) {
            return CompletableFuture.completedFuture(new Outcome(Status.UNKNOWN_SKIN, ref.toString()));
        }
        Look look = look(mojangUuid, ref);
        if (look.rawOverrides().isEmpty()) {
            return skins.wear(player, ref).thenApply(result -> new Outcome(fromSkins(result), null));
        }
        SkinSigner skinSigner = signer.get();
        if (skinSigner == null || !skinSigner.isAvailable()) {
            return CompletableFuture.completedFuture(new Outcome(Status.SIGNING_UNAVAILABLE, null));
        }
        String composer = compositor.composerCollection(ref.collection()).orElseThrow();
        return moonsama.cachedHoldings(mojangUuid).thenCompose(snapshot -> {
            List<AssetHolding> holdings = snapshot.holdings();
            if (!owns(holdings, ref)) {
                return CompletableFuture.completedFuture(new Outcome(Status.NOT_OWNED, ref.toString()));
            }
            List<String> locked = lockedSlots(ref, look, holdings);
            if (!locked.isEmpty()) {
                return CompletableFuture.completedFuture(new Outcome(Status.NOT_UNLOCKED, String.join(", ", locked)));
            }
            List<SlotValue> defaults = compositor.defaultSlots(ref.collection(), ref.tokenId()).orElseThrow();
            List<SlotValue> slots = look.applyTo(defaults, composer);
            SkinSigner.Variant variant = skins.catalog().find(ref).map(SignedSkin::model)
                    .filter(m -> "slim".equalsIgnoreCase(m))
                    .map(m -> SkinSigner.Variant.SLIM)
                    .orElse(SkinSigner.Variant.CLASSIC);
            return CompletableFuture.supplyAsync(() -> {
                        SkinCompositor.Rendered rendered = compositor.render(Composition.of(composer, ref.tokenId(), slots));
                        for (String warning : rendered.warnings()) {
                            plugin.getLogger().fine("Wardrobe render " + ref + ": " + warning);
                        }
                        return rendered.png();
                    }, renderExecutor)
                    .thenCompose(png -> skinSigner.sign(png, variant))
                    .thenCompose(signed -> skins.wearCustom(player, ref, signed.value(), signed.signature())
                            .thenApply(result -> new Outcome(fromSkins(result), null)))
                    .exceptionally(failure -> {
                        Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
                        if (cause instanceof SkinSigner.SigningException) {
                            plugin.getLogger().warning("Skin signing failed for " + player.getName() + ": " + cause.getMessage());
                            return new Outcome(Status.SIGNING_FAILED, cause.getMessage());
                        }
                        plugin.getLogger().log(Level.WARNING, "Wardrobe apply failed for " + player.getName(), cause);
                        return new Outcome(Status.UNAVAILABLE, cause.getMessage());
                    });
        });
    }

    /** Slots whose chosen part the player no longer has access to. */
    public List<String> lockedSlots(SkinRef ref, Look look, List<AssetHolding> holdings) {
        Set<String> owned = unlocks.ownedParts(ref.collection(), holdings);
        List<String> locked = new ArrayList<>();
        look.rawOverrides().forEach((slot, value) -> {
            if (value != null && !unlocks.isUnlocked(value, owned, holdings)) {
                locked.add(slot);
            }
        });
        return locked;
    }

    /**
     * Drops overrides the player can no longer use. Returns true when the stored look changed.
     */
    public boolean revalidate(UUID mojangUuid, SkinRef ref, List<AssetHolding> holdings) {
        Look look = look(mojangUuid, ref);
        List<String> locked = lockedSlots(ref, look, holdings);
        if (locked.isEmpty()) {
            return false;
        }
        locked.forEach(look::revert);
        store.put(mojangUuid, ref, look);
        return true;
    }

    public boolean isRequired(String portalCollection, String slot) {
        return compositor.data().collection(compositor.composerCollection(portalCollection).orElse(""))
                .map(CompositorData.CollectionDef::slots)
                .map(slots -> slots.get(slot))
                .map(SlotDef::required)
                .orElse(false);
    }

    private static boolean owns(List<AssetHolding> holdings, SkinRef ref) {
        String tokenId = Long.toString(ref.tokenId());
        return holdings.stream().anyMatch(h -> h.hasPositiveBalance()
                && ref.collection().equals(h.collection()) && tokenId.equals(h.tokenId()));
    }

    private static Status fromSkins(SkinService.Result result) {
        return switch (result) {
            case APPLIED -> Status.APPLIED;
            case NOT_LINKED -> Status.NOT_LINKED;
            case NOT_OWNED -> Status.NOT_OWNED;
            case UNKNOWN_SKIN -> Status.UNKNOWN_SKIN;
            default -> Status.UNAVAILABLE;
        };
    }

    public record Owned(String status, List<SkinRef> refs) {}

    public record Outcome(Status status, String detail) {}

    public enum Status {
        APPLIED, NOT_LINKED, NOT_OWNED, NOT_UNLOCKED, UNKNOWN_SKIN, SIGNING_UNAVAILABLE, SIGNING_FAILED, UNAVAILABLE
    }
}
