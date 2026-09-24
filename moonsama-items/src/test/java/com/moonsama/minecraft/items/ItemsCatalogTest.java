package com.moonsama.minecraft.items;

import com.moonsama.minecraft.api.AssetHolding;
import org.bukkit.Material;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ItemsCatalogTest {
    private static ItemsCatalog catalog;

    @BeforeAll
    static void load() {
        catalog = ItemsCatalog.load(ItemsCatalogTest.class.getClassLoader());
    }

    @Test
    void loadsBundledTablesWithoutWarnings() {
        assertThat(catalog.warnings()).isEmpty();
        // 68 rows; the carrot tools are listed once per material group and merge into one skin each.
        assertThat(catalog.itemSkins()).hasSize(60);
        ItemSkin carrot = catalog.itemSkin("bunnysama:carrot_sword").orElseThrow();
        assertThat(carrot.group()).isEqualTo("tools");
        assertThat(carrot.materials()).hasSize(30).contains(Material.IRON_SWORD, Material.NETHERITE_HOE);
        assertThat(catalog.offhands()).hasSize(27);
        assertThat(catalog.hats().rules()).hasSize(18);
        assertThat(catalog.hats().material()).isEqualTo(Material.PAPER);
    }

    @Test
    void itemSkinsAreKeyedByIdentifierAndUnlockedByMoonsamaX() {
        ItemSkin banana = catalog.itemSkin("banana_sword").orElseThrow();
        assertThat(banana.name()).isEqualTo("The Primate Peeler");
        assertThat(banana.customModelData()).isEqualTo(1);
        assertThat(banana.materials()).contains(Material.IRON_SWORD, Material.NETHERITE_SWORD);
        assertThat(banana.previewMaterial()).isEqualTo(Material.IRON_SWORD);
        assertThat(banana.requirement().anyOf()).containsExactly(new Requirement.Clause("moonsama-x", 12L));

        // The birthday cookie sword has no Portal collection and can never be unlocked.
        ItemSkin cookie = catalog.itemSkin("moonsama:cookie_sword").orElseThrow();
        assertThat(cookie.requirement().unlockable()).isFalse();
        assertThat(cookie.requirement().satisfiedBy(List.of(new AssetHolding("moonsama-x", "1", "1")))).isFalse();
    }

    @Test
    void offhandRequirementsSupportAnyTokenAndAlternatives() {
        Offhand egg = catalog.offhand("moonsama:moonsama_egg").orElseThrow();
        assertThat(egg.material()).isEqualTo(Material.FEATHER);
        assertThat(egg.requirement().satisfiedBy(List.of(new AssetHolding("exosama", "4711", "1")))).isTrue();
        assertThat(egg.requirement().satisfiedBy(List.of(new AssetHolding("gromlin", "1", "1")))).isFalse();
        assertThat(egg.requirement().describe(Map.of("moonsama", "Moonsama"))).isEqualTo("any Moonsama or any exosama");

        Offhand broom = catalog.offhand("moonbroom").orElseThrow();
        assertThat(broom.requirement().satisfiedBy(List.of(new AssetHolding("moonsama-x", "72", "1")))).isTrue();
        assertThat(broom.requirement().satisfiedBy(List.of(new AssetHolding("moonsama-x", "73", "1")))).isFalse();
        assertThat(broom.requirement().satisfiedBy(List.of(new AssetHolding("moonsama-x", "71", "0")))).isFalse();
    }

    @Test
    void hatRulesFollowTheLegacyPrecedence() {
        HatRules hats = catalog.hats();
        assertThat(hats.resolve("moonsama", Map.of("hat", "moonsama:black_cowboy")))
                .map(HatRules.Rule::customModelData).contains(1001);
        assertThat(hats.resolve("moonsama", Map.of("hat", "moonsama:black_cowboy", "dimension", "moonsama:neon")))
                .map(HatRules.Rule::customModelData).contains(1003);
        // A head-covering costume hides the hat slot...
        assertThat(hats.resolve("moonsama", Map.of("hat", "moonsama:blue_bufoon", "costume", "multiverse_costumes:tiara")))
                .isEmpty();
        // ...unless the costume brings its own hat.
        assertThat(hats.resolve("exosama", Map.of("hat", "moonsama:blue_bufoon", "costume", "multiverse_costumes:2nd_anniversary_party_hat")))
                .map(HatRules.Rule::customModelData).contains(1014);
        assertThat(hats.resolve("moonsama", Map.of("costume", "multiverse_costumes:2nd_anniversary_party_hat")))
                .map(HatRules.Rule::customModelData).contains(1015);
        assertThat(hats.resolve("exosama", Map.of("costume", "multiverse_costumes:donablo"))).isEmpty();
        assertThat(hats.resolve("moonsama", Map.of("hat", "moonsama:aviator"))).isEmpty();
    }

    @Test
    void compositionsResolveHatsForRealTokens() {
        Map<String, String> two = catalog.composition("moonsama", 2);
        assertThat(two).containsEntry("hat", "moonsama:aviator");
        assertThat(catalog.composition("gromlin", 1)).isEmpty();
        assertThat(catalog.composition("moonsama", 999_999)).isEmpty();

        long cowboys = Stream.iterate(1L, id -> id + 1).limit(1000)
                .filter(id -> catalog.hats().resolve("moonsama", catalog.composition("moonsama", id)).isPresent())
                .count();
        assertThat(cowboys).isGreaterThan(100);
    }

    @Test
    void everyCosmeticHasAResourcePackEntry() throws IOException {
        Path pack = Path.of("..", "resourcepack", "src", "assets", "minecraft", "items");
        for (ItemSkin skin : catalog.itemSkins().values()) {
            for (Material material : skin.materials()) {
                assertThat(definition(pack, material)).contains("\"threshold\": " + skin.customModelData() + ".0");
            }
        }
        for (Offhand offhand : catalog.offhands().values()) {
            assertThat(definition(pack, offhand.material())).contains("\"threshold\": " + offhand.customModelData() + ".0");
        }
        for (HatRules.Rule rule : catalog.hats().rules()) {
            assertThat(definition(pack, catalog.hats().material())).contains("\"threshold\": " + rule.customModelData() + ".0");
        }
    }

    private static String definition(Path pack, Material material) throws IOException {
        return Files.readString(pack.resolve(material.name().toLowerCase(java.util.Locale.ROOT) + ".json"));
    }

    @Test
    void missingTablesAreReportedNotFatal() {
        ItemsCatalog empty = ItemsCatalog.load(path -> (InputStream) null);
        assertThat(empty.warnings()).containsExactlyInAnyOrder(
                "item-skins.json is missing", "offhands.json is missing", "hats.json is missing");
        assertThat(empty.itemSkins()).isEmpty();
        assertThat(empty.composition("moonsama", 1)).isEmpty();
    }
}
