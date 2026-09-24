package com.moonsama.minecraft.skins;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class SkinCatalogTest {
    private static final Pattern MOJANG_TEXTURE =
            Pattern.compile("^https?://textures\\.minecraft\\.net/texture/[0-9a-f]{56,64}$");

    @Test
    void parsesJsonLinesAndSkipsMissingCollections() {
        String data = """
                {"id": 1, "value": "dmFsdWUx", "signature": "c2lnMQ==", "model": "slim"}

                {"id": 2, "value": "dmFsdWUy", "signature": "c2lnMg=="}
                """;
        SkinCatalog catalog = SkinCatalog.load(List.of("demo", "absent"), name ->
                name.equals("demo") ? stream(data) : null);

        assertThat(catalog.collections()).containsExactly("demo");
        assertThat(catalog.missingCollections(List.of("demo", "absent"))).containsExactly("absent");
        assertThat(catalog.size()).isEqualTo(2);
        assertThat(catalog.find(new SkinRef("demo", 1))).get()
                .extracting(SignedSkin::model).isEqualTo("slim");
        assertThat(catalog.find("demo", " 2 ")).get()
                .extracting(SignedSkin::model).isEqualTo("classic");
        assertThat(catalog.find("demo", "x")).isEmpty();
        assertThat(catalog.find("other", "1")).isEmpty();
    }

    @Test
    void bundledDataCoversPortalCollectionsWithMojangSignedTextures() {
        Map<String, Integer> expected = Map.of(
                "moonsama", 1000,
                "exosama", 10000,
                "gromlin", 3333,
                "moonsama-embassy", 15
        );
        SkinCatalog catalog = SkinCatalog.load(getClass().getClassLoader(), List.copyOf(expected.keySet()));

        assertThat(catalog.collections()).containsExactlyInAnyOrderElementsOf(expected.keySet());
        expected.forEach((collection, count) -> assertThat(catalog.size(collection))
                .as(collection).isEqualTo(count));

        for (String collection : expected.keySet()) {
            for (long id = 1; id <= expected.get(collection); id++) {
                SignedSkin skin = catalog.find(new SkinRef(collection, id)).orElseThrow();
                JsonObject payload = JsonParser.parseString(new String(
                        Base64.getDecoder().decode(skin.value()), StandardCharsets.UTF_8)).getAsJsonObject();
                String url = payload.getAsJsonObject("textures").getAsJsonObject("SKIN").get("url").getAsString();
                assertThat(url).as(skin.ref().key()).matches(MOJANG_TEXTURE);
                assertThat(Base64.getDecoder().decode(skin.signature())).as(skin.ref().key()).hasSize(512);
            }
        }
    }

    private static InputStream stream(String data) {
        return new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8));
    }
}
