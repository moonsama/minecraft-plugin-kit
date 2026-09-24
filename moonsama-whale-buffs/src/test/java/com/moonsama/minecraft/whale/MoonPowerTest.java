package com.moonsama.minecraft.whale;

import com.moonsama.minecraft.api.AssetHolding;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class MoonPowerTest {
    private static WhaleConfig bundled() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new InputStreamReader(
                MoonPowerTest.class.getClassLoader().getResourceAsStream("config.yml"), StandardCharsets.UTF_8));
        return WhaleConfig.from(yaml);
    }

    @Test
    void bundledConfigHasTheLegacyNumbers() {
        WhaleConfig config = bundled();
        assertThat(config.enabled()).isTrue();
        assertThat(config.requireEntitledSkin()).isTrue();
        assertThat(config.entitledCollections()).containsExactlyInAnyOrder("moonsama", "exosama");
        assertThat(config.powerPerToken()).containsEntry("moonsama", 10.0).containsEntry("exosama", 1.0);
        assertThat(config.overrides()).hasSize(1);
        assertThat(config.overrides().get(0).tokenIds()).containsExactlyInAnyOrder("276", "511", "545", "605", "787", "920");
        assertThat(config.healthPerPower()).isEqualTo(0.1);
        assertThat(config.damagePerPower()).isEqualTo(0.025);
        assertThat(config.nameTiers()).extracting(WhaleConfig.NameTier::color).containsExactly("gold", "blue", "light_purple", "rainbow");
        assertThat(config.whaleMode().durationSeconds()).isEqualTo(120);
        assertThat(config.whaleMode().cooldownSeconds()).isEqualTo(300);
        assertThat(config.whaleMode().healthMultiplier()).isEqualTo(1.5);
        assertThat(config.whaleMode().scepterMinPower()).isEqualTo(100);
        assertThat(config.whaleMode().lightning()).isEqualTo(WhaleConfig.Lightning.EFFECT);
        assertThat(config.whaleMode().scepterMaterial()).isEqualTo(Material.FEATHER);
        assertThat(config.whaleMode().scepterModel()).isEqualTo(2);
    }

    @Test
    void powerAddsUpWithNeonOverride() {
        WhaleConfig config = bundled();
        List<AssetHolding> holdings = List.of(
                new AssetHolding("moonsama", "1", "1"),
                new AssetHolding("moonsama", "545", "1"),     // Neon → 100
                new AssetHolding("exosama", "9000", "1"),
                new AssetHolding("exosama", "9001", "0"),     // sold
                new AssetHolding("moonsama-x", "12", "5"),   // not counted
                new AssetHolding("gromlin", "3", "1"));      // not counted
        assertThat(MoonPower.of(config, holdings)).isEqualTo(111);
        assertThat(MoonPower.of(config, new AssetHolding("exosama", "1", "2.7"))).isEqualTo(2);
        assertThat(MoonPower.units("abc")).isZero();
    }

    @Test
    void healthAndDamageFollowTheLegacyFormulas() {
        WhaleConfig config = bundled();
        assertThat(MoonPower.extraHealth(config, 100, false)).isCloseTo(10, within(1e-9));
        // (10 + 20) * 1.5 - 20 = 25
        assertThat(MoonPower.extraHealth(config, 100, true)).isCloseTo(25, within(1e-9));
        assertThat(MoonPower.extraHealth(config, 0, false)).isZero();
        assertThat(MoonPower.damageFactor(config, 100)).isCloseTo(3.5, within(1e-9));
        assertThat(MoonPower.damageFactor(config, 0)).isEqualTo(1);
    }

    @Test
    void nameTiersAndRainbow() {
        WhaleConfig config = bundled();
        assertThat(NameStyle.styled(config, "Bob", 0)).isEmpty();
        assertThat(NameStyle.styled(config, "Bob", 10)).map(c -> c.color()).contains(NamedTextColor.GOLD);
        assertThat(NameStyle.styled(config, "Bob", 50)).map(c -> c.color()).contains(NamedTextColor.BLUE);
        assertThat(NameStyle.styled(config, "Bob", 150)).map(c -> c.color()).contains(NamedTextColor.LIGHT_PURPLE);
        Component rainbow = NameStyle.styled(config, "Whale", 250).orElseThrow();
        assertThat(PlainTextComponentSerializer.plainText().serialize(rainbow)).isEqualTo("Whale");
        assertThat(rainbow.children()).hasSize(5);
        assertThat(rainbow.children().get(0).color()).isEqualTo(NamedTextColor.RED);
        assertThat(rainbow.children().get(1).color()).isEqualTo(NamedTextColor.GOLD);
        assertThat(Scepter.format(100)).isEqualTo("100");
        assertThat(Scepter.format(12.5)).isEqualTo("12.5");
    }

    @Test
    void resourcePackRendersTheDefaultScepter() throws java.io.IOException {
        WhaleConfig config = bundled();
        java.nio.file.Path definition = java.nio.file.Path.of("..", "resourcepack", "src", "assets", "minecraft", "items",
                config.whaleMode().scepterMaterial().name().toLowerCase(java.util.Locale.ROOT) + ".json");
        assertThat(java.nio.file.Files.readString(definition))
                .contains("\"threshold\": " + config.whaleMode().scepterModel() + ".0")
                .contains("whale_scepter");
    }

    @Test
    void configRejectsNonsense() {
        YamlConfiguration bad = new YamlConfiguration();
        bad.set("names.tiers", List.of(java.util.Map.of("min-power", 1, "color", "sparkly")));
        assertThatThrownBy(() -> WhaleConfig.from(bad)).hasMessageContaining("unknown colour");

        YamlConfiguration lightning = new YamlConfiguration();
        lightning.set("whale-mode.lightning", "sometimes");
        assertThatThrownBy(() -> WhaleConfig.from(lightning)).hasMessageContaining("lightning");

        YamlConfiguration material = new YamlConfiguration();
        material.set("whale-mode.scepter.material", "UNOBTAINIUM");
        assertThatThrownBy(() -> WhaleConfig.from(material)).hasMessageContaining("material");

        YamlConfiguration empty = new YamlConfiguration();
        WhaleConfig defaults = WhaleConfig.from(empty);
        assertThat(defaults.powerPerToken()).isEmpty();
        assertThat(MoonPower.of(defaults, List.of(new AssetHolding("moonsama", "1", "1")))).isZero();
        assertThat(defaults.whaleMode().lightning()).isEqualTo(WhaleConfig.Lightning.EFFECT);
    }
}
