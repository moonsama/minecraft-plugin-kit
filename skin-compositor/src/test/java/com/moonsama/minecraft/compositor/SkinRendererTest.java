package com.moonsama.minecraft.compositor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moonsama.minecraft.compositor.CompositorData.Component;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SkinRendererTest {
    @Test
    void blendsSourceOverInLinearLight() {
        // Legacy sample: base 0x76 grey under a black pixel at alpha 66 became 0x67 (would be 0x58 in sRGB space).
        int result = SkinRenderer.over(0x42000000, 0xFF767676);
        assertThat(result >>> 24).isEqualTo(255);
        assertThat(result & 0xFF).isBetween(0x66, 0x68);
        assertThat(SkinRenderer.over(0x00123456, 0xFF767676)).isEqualTo(0xFF767676);
        assertThat(SkinRenderer.over(0xFF123456, 0xFF767676)).isEqualTo(0xFF123456);
    }

    @Test
    void fragmentsUseBottomLeftOriginAndZOrder() throws IOException {
        // 4×4 layer: top-left pixel red, bottom-left pixel blue.
        BufferedImage layer = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        layer.setRGB(0, 0, 0xFFFF0000);
        layer.setRGB(0, 3, 0xFF0000FF);
        BufferedImage green = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                green.setRGB(x, y, 0xFF00FF00);
            }
        }
        CompositorData data = CompositorData.load(fakeCosmetics(Map.of(
                "compositor/files/test/layer.png", png(layer),
                "compositor/files/test/green.png", png(green))));

        Component component = new Component("test", "test:c", new JsonObject(), JsonParser.parseString("""
                {"elements": [
                  {"type": "texture", "id": "skin", "width": 64, "height": 64},
                  {"type": "texture_fragment", "url": "green.png", "target_texture_id": "skin", "x": 0, "y": 0, "width": 4, "height": 4,
                   "pos_x": 0, "pos_y": 0, "z-index": 5},
                  {"type": "texture_fragment", "url": "layer.png", "target_texture_id": "skin", "x": 0, "y": 0, "width": 1, "height": 1,
                   "pos_x": 0, "pos_y": 0, "z-index": 10},
                  {"type": "sprite", "url": "#skin"}
                ]}
                """).getAsJsonObject());

        SkinRenderer renderer = new SkinRenderer(data);
        BufferedImage out = renderer.render(List.of(component));
        assertThat(renderer.warnings()).isEmpty();
        // Source rect (0,0,1,1) in bottom-left coordinates is the blue pixel; destination (0,0) is the bottom-left corner.
        assertThat(out.getRGB(0, 63)).isEqualTo(0xFF0000FF);
        // The green 4×4 block was drawn first (lower z-index) and sits in the bottom-left corner.
        assertThat(out.getRGB(3, 60)).isEqualTo(0xFF00FF00);
        assertThat(out.getRGB(0, 0) >>> 24).isZero();
    }

    private static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
    }

    /** Minimal cosmetics tree with one composer collection "test" mapped from portal slug "test". */
    private static java.util.function.Function<String, InputStream> fakeCosmetics(Map<String, byte[]> files) {
        JsonObject collections = new JsonObject();
        JsonObject portal = new JsonObject();
        JsonObject mapping = new JsonObject();
        mapping.addProperty("composerCollection", "test");
        portal.add("test", mapping);
        collections.add("portalCollections", portal);

        JsonObject compositor = new JsonObject();
        JsonObject test = new JsonObject();
        test.addProperty("id", "test");
        test.add("resultTypes", new JsonArray());
        test.add("slots", new JsonArray());
        test.add("slotPermissions", new JsonArray());
        test.add("assets", new JsonArray());
        test.add("assetProxies", new JsonArray());
        JsonObject byId = new JsonObject();
        byId.add("test", test);
        compositor.add("collections", byId);
        compositor.add("renderVariants", new JsonObject());

        Map<String, byte[]> all = new java.util.HashMap<>(files);
        all.put("collections.json", collections.toString().getBytes(StandardCharsets.UTF_8));
        all.put("compositor/collections.json", compositor.toString().getBytes(StandardCharsets.UTF_8));
        all.put("compositor/components/test.json", "[]".getBytes(StandardCharsets.UTF_8));
        return path -> {
            byte[] content = all.get(path);
            return content == null ? null : new ByteArrayInputStream(content);
        };
    }
}
