package com.moonsama.minecraft.compositor;

import com.moonsama.minecraft.compositor.CompositorData.SlotValue;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Renders Minecraft skins from Moonsama NFT parts exactly like the retired Customizer did, but
 * offline: everything comes from the bundled {@code cosmetics-data}.
 *
 * <pre>{@code
 * SkinCompositor compositor = SkinCompositor.load(getClassLoader());
 * BufferedImage skin = compositor.renderToken("moonsama", 42).orElseThrow().image();
 * }</pre>
 *
 * <p>Instances are thread-safe; each render uses its own {@link SkinRenderer}.
 */
public final class SkinCompositor {
    private final CompositorData data;
    private final ComponentResolver resolver;
    private final Map<String, Map<Long, List<SlotValue>>> defaults = new ConcurrentHashMap<>();

    public SkinCompositor(CompositorData data) {
        this.data = data;
        this.resolver = new ComponentResolver(data);
    }

    public static SkinCompositor load(ClassLoader loader) {
        return new SkinCompositor(CompositorData.load(loader));
    }

    public CompositorData data() {
        return data;
    }

    /**
     * Whether a Portal collection has composable parts. Gromlins, for example, only ever had
     * finished skins in the legacy system (no slots or assets), so they cannot be re-composed.
     */
    public boolean supports(String portalCollection) {
        return data.composerCollection(portalCollection)
                .flatMap(data::collection)
                .map(def -> !def.assets().isEmpty() && !def.slots().isEmpty())
                .orElse(false);
    }

    /** The default slot values of a token, keyed by Portal collection slug. */
    public Optional<List<SlotValue>> defaultSlots(String portalCollection, long tokenId) {
        Map<Long, List<SlotValue>> byToken = defaults.computeIfAbsent(portalCollection, data::defaultCompositions);
        return Optional.ofNullable(byToken.get(tokenId));
    }

    /** Composer collection id behind a Portal collection slug. */
    public Optional<String> composerCollection(String portalCollection) {
        return data.composerCollection(portalCollection);
    }

    /**
     * Slots of a Portal collection that players may change, in definition order (e.g. hair, hat,
     * outfit). Base and dimension are fixed per token and therefore excluded.
     */
    public List<CompositorData.SlotDef> customizableSlots(String portalCollection) {
        return data.composerCollection(portalCollection)
                .flatMap(data::collection)
                .map(def -> def.slots().values().stream().filter(CompositorData.SlotDef::customizable).toList())
                .orElse(List.of());
    }

    /** Assets the collection's slot permissions allow in {@code slot}, before any ownership check. */
    public List<SlotValue> permittedAssets(String portalCollection, String slot) {
        return data.composerCollection(portalCollection)
                .map(composer -> resolver.permittedAssets(composer, slot))
                .orElse(List.of());
    }

    /** Display name of an asset, falling back to its reference id. */
    public String assetName(SlotValue value) {
        return data.collection(value.collection())
                .map(def -> def.assets().get(value.asset()))
                .map(asset -> asset.name() == null || asset.name().isBlank() ? value.asset() : asset.name())
                .orElse(value.asset());
    }

    /** Renders a token in its default look, keyed by Portal collection slug. */
    public Optional<Rendered> renderToken(String portalCollection, long tokenId) {
        String composer = data.composerCollection(portalCollection).orElse(null);
        if (composer == null) {
            return Optional.empty();
        }
        return defaultSlots(portalCollection, tokenId)
                .map(slots -> render(Composition.of(composer, tokenId, slots)));
    }

    /** Renders an arbitrary composition (custom wardrobe). */
    public Rendered render(Composition composition) {
        ComponentResolver.Resolution resolution = resolver.resolve(composition, ComponentResolver.MINECRAFT);
        SkinRenderer renderer = new SkinRenderer(data);
        BufferedImage image = renderer.render(resolution.components());
        return new Rendered(image, resolution, renderer.warnings());
    }

    public record Rendered(BufferedImage image, ComponentResolver.Resolution resolution, List<String> warnings) {
        public byte[] png() {
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ImageIO.write(image, "png", out);
                return out.toByteArray();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
