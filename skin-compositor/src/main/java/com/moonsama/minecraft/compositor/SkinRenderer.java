package com.moonsama.minecraft.compositor;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.moonsama.minecraft.compositor.CompositorData.Component;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Executes the 2D element graph of the resolved components and produces a 64×64 skin.
 *
 * <p>Element types handled:
 * <ul>
 *   <li>{@code texture}: declares a named (empty or file-backed) target texture.</li>
 *   <li>{@code texture_fragment}: copies a rectangle of a layer file onto a target texture,
 *       ordered by {@code z-index} (stable for equal values).</li>
 *   <li>{@code texture_atlas}: stacks textures/files in layer order into a new texture.</li>
 *   <li>{@code sprite}: names the output texture ({@code #composition}).</li>
 * </ul>
 * {@code model} elements only matter for the 3D preview renderer and are ignored.
 */
public final class SkinRenderer {
    public static final int SIZE = 64;
    /**
     * Fragments tagged {@code pass: skin} are drawn after untagged ones within a target texture.
     * This reproduces the majority of the archived legacy renders (e.g. bird bases covering the
     * soft arm shadows of shirts); set the system property to {@code false} to draw strictly in
     * component order instead.
     */
    static final boolean PASS_ORDERING = Boolean.parseBoolean(System.getProperty("moonsama.compositor.passOrdering", "true"));

    private final CompositorData data;
    private final List<String> warnings = new ArrayList<>();

    public SkinRenderer(CompositorData data) {
        this.data = data;
    }

    /** Non-fatal problems from the last {@link #render} call, e.g. missing layer files. */
    public List<String> warnings() {
        return List.copyOf(warnings);
    }

    public BufferedImage render(List<Component> components) {
        warnings.clear();
        Map<String, TextureDecl> textures = new LinkedHashMap<>();
        List<Fragment> fragments = new ArrayList<>();
        List<Atlas> atlases = new ArrayList<>();
        String output = null;

        int order = 0;
        for (Component component : components) {
            for (JsonElement element : component.elements()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject e = element.getAsJsonObject();
                String type = CompositorData.optString(e, "type");
                if (type == null) {
                    continue;
                }
                switch (type) {
                    case "texture" -> {
                        String id = CompositorData.optString(e, "id");
                        if (id != null) {
                            textures.putIfAbsent(id, new TextureDecl(id, component.collection(),
                                    CompositorData.optString(e, "url"), intOr(e, "width", SIZE), intOr(e, "height", SIZE)));
                        }
                    }
                    case "texture_fragment" -> fragments.add(new Fragment(order++, component, e));
                    case "texture_atlas" -> atlases.add(new Atlas(component, e));
                    case "sprite" -> {
                        String url = CompositorData.optString(e, "url");
                        if (url != null && url.startsWith("#")) {
                            output = url.substring(1);
                        }
                    }
                    default -> {
                        // model / animation / unknown: not part of the 2D skin
                    }
                }
            }
        }

        Map<String, BufferedImage> canvases = new LinkedHashMap<>();
        for (TextureDecl decl : textures.values()) {
            BufferedImage canvas = blank(decl.width(), decl.height());
            if (decl.url() != null && !decl.url().startsWith("#")) {
                Optional<BufferedImage> initial = data.image(decl.collection(), decl.url());
                if (initial.isPresent()) {
                    blit(initial.get(), 0, 0, initial.get().getWidth(), initial.get().getHeight(), canvas, 0, 0);
                } else {
                    warnings.add("texture " + decl.id() + ": missing file " + decl.collection() + "/" + decl.url());
                }
            }
            canvases.put(decl.id(), canvas);
        }

        fragments.sort((a, b) -> {
            int pass = Integer.compare(a.passOrder(), b.passOrder());
            if (pass != 0) {
                return pass;
            }
            int z = Integer.compare(a.zIndex(), b.zIndex());
            return z != 0 ? z : Integer.compare(a.order(), b.order());
        });
        for (Fragment fragment : fragments) {
            fragment.draw(canvases);
        }
        for (Atlas atlas : atlases) {
            atlas.draw(canvases);
        }

        BufferedImage result = output != null ? canvases.get(output) : canvases.get("composition");
        if (result == null) {
            warnings.add("no output texture; producing an empty skin");
            result = blank(SIZE, SIZE);
        }
        return result;
    }

    // ------------------------------------------------------------------ helpers

    private static int intOr(JsonObject json, String key, int fallback) {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return (int) Math.round(value.getAsDouble());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    static BufferedImage blank(int width, int height) {
        return new BufferedImage(Math.max(1, width), Math.max(1, height), BufferedImage.TYPE_INT_ARGB);
    }

    private BufferedImage copyOf(BufferedImage source) {
        BufferedImage copy = blank(source.getWidth(), source.getHeight());
        copy.setRGB(0, 0, source.getWidth(), source.getHeight(),
                source.getRGB(0, 0, source.getWidth(), source.getHeight(), null, 0, source.getWidth()), 0, source.getWidth());
        return copy;
    }

    /**
     * Straight-alpha source-over blit of {@code src[sx,sy,w,h]} onto {@code dst} at {@code (dx,dy)},
     * clamped to both images.
     */
    static void blit(BufferedImage src, int sx, int sy, int w, int h, BufferedImage dst, int dx, int dy) {
        for (int y = 0; y < h; y++) {
            int syy = sy + y;
            int dyy = dy + y;
            if (syy < 0 || dyy < 0 || syy >= src.getHeight() || dyy >= dst.getHeight()) {
                continue;
            }
            for (int x = 0; x < w; x++) {
                int sxx = sx + x;
                int dxx = dx + x;
                if (sxx < 0 || dxx < 0 || sxx >= src.getWidth() || dxx >= dst.getWidth()) {
                    continue;
                }
                int s = src.getRGB(sxx, syy);
                int sa = s >>> 24;
                if (sa == 0) {
                    continue;
                }
                if (sa == 255) {
                    dst.setRGB(dxx, dyy, s);
                    continue;
                }
                dst.setRGB(dxx, dyy, over(s, dst.getRGB(dxx, dyy)));
            }
        }
    }

    private static final float[] TO_LINEAR = new float[256];

    static {
        for (int i = 0; i < 256; i++) {
            double c = i / 255.0;
            TO_LINEAR[i] = (float) (c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4));
        }
    }

    private static int toSrgb(double linear) {
        double c = linear <= 0.0031308 ? linear * 12.92 : 1.055 * Math.pow(linear, 1 / 2.4) - 0.055;
        return (int) Math.max(0, Math.min(255, Math.round(c * 255)));
    }

    /**
     * Source-over compositing with straight alpha, blending colour in linear light (the legacy
     * Unity renderer ran in linear colour space, which is visible on semi-transparent shading).
     */
    static int over(int src, int dst) {
        double sa = (src >>> 24) / 255.0;
        double da = (dst >>> 24) / 255.0;
        double outA = sa + da * (1 - sa);
        if (outA <= 0) {
            return 0;
        }
        int r = channel(src >> 16, dst >> 16, sa, da, outA);
        int g = channel(src >> 8, dst >> 8, sa, da, outA);
        int b = channel(src, dst, sa, da, outA);
        int a = (int) Math.round(outA * 255);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int channel(int s, int d, double sa, double da, double outA) {
        double ls = TO_LINEAR[s & 0xFF];
        double ld = TO_LINEAR[d & 0xFF];
        return toSrgb((ls * sa + ld * da * (1 - sa)) / outA);
    }

    // ------------------------------------------------------------------- pieces

    private record TextureDecl(String id, String collection, String url, int width, int height) {
    }

    private final class Fragment {
        private final int order;
        private final Component component;
        private final JsonObject e;

        Fragment(int order, Component component, JsonObject e) {
            this.order = order;
            this.component = component;
            this.e = e;
        }

        int order() {
            return order;
        }

        int zIndex() {
            return intOr(e, "z-index", 0);
        }

        int passOrder() {
            if (!PASS_ORDERING) {
                return 0;
            }
            return "skin".equals(CompositorData.optString(e, "pass")) ? 1 : 0;
        }

        void draw(Map<String, BufferedImage> canvases) {
            String target = CompositorData.optString(e, "target_texture_id");
            String url = CompositorData.optString(e, "url");
            if (target == null || url == null) {
                return;
            }
            Optional<BufferedImage> source = url.startsWith("#")
                    ? Optional.ofNullable(canvases.get(url.substring(1))).map(SkinRenderer.this::copyOf)
                    : data.image(component.collection(), url);
            if (source.isEmpty()) {
                warnings.add(component.referenceId() + ": missing layer " + component.collection() + "/" + url);
                return;
            }
            BufferedImage dst = canvases.computeIfAbsent(target, k -> blank(SIZE, SIZE));
            int w = intOr(e, "width", source.get().getWidth());
            int h = intOr(e, "height", source.get().getHeight());
            int sx = intOr(e, "x", 0);
            int syUnity = intOr(e, "y", 0);
            int dx = intOr(e, "pos_x", sx);
            int dyUnity = intOr(e, "pos_y", syUnity);
            // The legacy renderer was Unity: texture coordinates start at the bottom-left corner.
            int sy = source.get().getHeight() - syUnity - h;
            int dy = dst.getHeight() - dyUnity - h;
            blit(source.get(), sx, sy, w, h, dst, dx, dy);
        }
    }

    private final class Atlas {
        private final Component component;
        private final JsonObject e;

        Atlas(Component component, JsonObject e) {
            this.component = component;
            this.e = e;
        }

        void draw(Map<String, BufferedImage> canvases) {
            String id = CompositorData.optString(e, "id");
            if (id == null) {
                return;
            }
            BufferedImage dst = blank(intOr(e, "width", SIZE), intOr(e, "height", SIZE));
            JsonElement layers = e.get("layers");
            if (layers != null && layers.isJsonArray()) {
                for (JsonElement layerElement : layers.getAsJsonArray()) {
                    if (!layerElement.isJsonObject()) {
                        continue;
                    }
                    JsonObject layer = layerElement.getAsJsonObject();
                    String url = CompositorData.optString(layer, "url");
                    if (url == null) {
                        continue;
                    }
                    BufferedImage src = url.startsWith("#")
                            ? canvases.get(url.substring(1))
                            : data.image(component.collection(), url).orElse(null);
                    if (src == null) {
                        continue; // undeclared layers (e.g. cape) are simply empty
                    }
                    int dy = dst.getHeight() - intOr(layer, "y", 0) - src.getHeight(); // Unity: bottom-left origin
                    blit(src, 0, 0, src.getWidth(), src.getHeight(), dst, intOr(layer, "x", 0), dy);
                }
            }
            canvases.put(id, dst);
        }
    }
}
