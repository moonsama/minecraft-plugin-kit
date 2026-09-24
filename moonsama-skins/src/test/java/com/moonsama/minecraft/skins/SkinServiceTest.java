package com.moonsama.minecraft.skins;

import com.moonsama.minecraft.api.AssetHolding;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SkinServiceTest {
    @Test
    void ownershipMatchesCollectionAndNormalizedTokenIdWithPositiveBalance() {
        List<AssetHolding> holdings = List.of(
                new AssetHolding("moonsama", "0042", "1"),
                new AssetHolding("exosama", "7", "0"),
                new AssetHolding("gromlin", "9", null)
        );

        assertThat(SkinService.owns(holdings, new SkinRef("moonsama", 42), false)).isTrue();
        assertThat(SkinService.owns(holdings, new SkinRef("moonsama", 43), false)).isFalse();
        assertThat(SkinService.owns(holdings, new SkinRef("exosama", 7), false)).isFalse();
        assertThat(SkinService.owns(holdings, new SkinRef("gromlin", 9), false)).isFalse();
        assertThat(SkinService.owns(holdings, new SkinRef("moonsama-embassy", 42), false)).isFalse();
    }

    @Test
    void uniformCollectionsAreOwnedThroughAnyToken() {
        List<AssetHolding> holdings = List.of(
                new AssetHolding("gromlin", "1200", "1"),
                new AssetHolding("gromlin", "7", "0")
        );

        assertThat(SkinService.owns(holdings, new SkinRef("gromlin", 1200), true)).isTrue();
        assertThat(SkinService.owns(holdings, new SkinRef("gromlin", 3), true)).isTrue();
        assertThat(SkinService.owns(holdings, new SkinRef("gromlin", 3), false)).isFalse();
        assertThat(SkinService.owns(List.of(new AssetHolding("gromlin", "7", "0")),
                new SkinRef("gromlin", 7), true)).isFalse();
        assertThat(SkinService.owns(holdings, new SkinRef("moonsama", 1200), true)).isFalse();
    }

    @Test
    void uniformCollectionsCollapseToOneMenuEntry() {
        String data = """
                {"id": 1, "value": "dmFsdWUx", "signature": "c2lnMQ=="}
                {"id": 2, "value": "dmFsdWUy", "signature": "c2lnMg=="}
                {"id": 3, "value": "dmFsdWUz", "signature": "c2lnMw=="}
                """;
        SkinCatalog catalog = SkinCatalog.load(List.of("gromlin", "moonsama"), name ->
                new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8)));
        SkinCollections collections = new SkinCollections(
                List.of("moonsama", "gromlin"), Map.of("gromlin", "Gromlin"), Set.of("gromlin"));
        SkinService service = new SkinService(null, null, catalog, collections, null);

        List<SignedSkin> owned = service.ownedSkins(List.of(
                new AssetHolding("gromlin", "3", "1"),
                new AssetHolding("gromlin", "2", "1"),
                new AssetHolding("gromlin", "1", "0"),
                new AssetHolding("moonsama", "1", "1"),
                new AssetHolding("moonsama", "3", "1")
        ));

        assertThat(owned).extracting(skin -> skin.ref().key())
                .containsExactlyInAnyOrder("moonsama:1", "moonsama:3", "gromlin:2");
        assertThat(collections.displayName(new SkinRef("gromlin", 2))).isEqualTo("Gromlin");
        assertThat(collections.displayName(new SkinRef("moonsama", 3))).isEqualTo("moonsama #3");
        assertThat(catalog.first("gromlin")).map(skin -> skin.ref().tokenId()).contains(1L);
    }

    @Test
    void bundledConfigMarksGromlinsUniform() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new InputStreamReader(
                getClass().getClassLoader().getResourceAsStream("config.yml"), StandardCharsets.UTF_8));
        SkinCollections collections = SkinCollections.from(yaml);

        assertThat(collections.uniform()).containsExactly("gromlin");
        assertThat(collections.order()).contains("gromlin", "moonsama-multiverse-art-eth");
        assertThat(collections.name("moonsama-multiverse-art-eth")).isEqualTo("Multiverse Avatars");
    }
}
