package com.moonsama.minecraft.gatekeeper;

import com.moonsama.minecraft.api.AssetHolding;
import com.moonsama.minecraft.api.HoldingsSnapshot;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GateTest {
    private static GateConfig bundled() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new InputStreamReader(
                GateTest.class.getClassLoader().getResourceAsStream("config.yml"), StandardCharsets.UTF_8));
        return GateConfig.from(yaml);
    }

    @Test
    void bundledConfigIsOffByDefaultAndListsTheLegacyPasses() {
        GateConfig config = bundled();
        assertThat(config.enabled()).isFalse();
        assertThat(config.passes()).extracting(Pass::collection)
                .containsExactly("moonsama", "exosama", "gromlin", "moonsama-embassy",
                        "moonsama-multiverse-art-eth", "moonsama-x");
        assertThat(config.passes().get(5).tokenIds()).containsExactly("1");
        assertThat(config.unlinked()).isEqualTo(GateConfig.Action.RESTRICT);
        assertThat(config.denied()).isEqualTo(GateConfig.Action.KICK);
        assertThat(config.unavailable()).isEqualTo(GateConfig.Action.ALLOW);
        assertThat(config.graceSeconds()).isEqualTo(600);
        assertThat(config.allowedCommands()).contains("moonsama", "help");
        assertThat(config.message("granted")).contains("Welcome");
        assertThat(config.message("missing-key")).isEqualTo("missing-key");
    }

    @Test
    void passesMatchCollectionTokenAndBalance() {
        Pass any = Pass.any("moonsama");
        assertThat(any.matches(new AssetHolding("moonsama", "42", "1"))).isTrue();
        assertThat(any.matches(new AssetHolding("moonsama", "42", "0"))).isFalse();
        assertThat(any.matches(new AssetHolding("exosama", "42", "1"))).isFalse();

        Pass vip = new Pass("moonsama-x", Set.of("1"), null);
        assertThat(vip.matches(new AssetHolding("moonsama-x", "1", "3"))).isTrue();
        assertThat(vip.matches(new AssetHolding("moonsama-x", "12", "3"))).isFalse();
        assertThat(vip.describe()).isEqualTo("moonsama-x #1");

        Pass whale = new Pass("moonsama-x", Set.of(), new BigDecimal("5"));
        assertThat(whale.matches(new AssetHolding("moonsama-x", "7", "5"))).isTrue();
        assertThat(whale.matches(new AssetHolding("moonsama-x", "7", "4.5"))).isFalse();
        assertThat(whale.matches(new AssetHolding("moonsama-x", "7", "x"))).isFalse();
        assertThat(whale.describe()).isEqualTo("moonsama-x ≥ 5");

        assertThatThrownBy(() -> new Pass(" ", Set.of(), null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decisionsFollowConfiguredActions() {
        Gate gate = new Gate(bundled());
        assertThat(gate.forBypass().allowed()).isTrue();
        assertThat(gate.forUnlinked().action()).isEqualTo(GateConfig.Action.RESTRICT);
        assertThat(gate.forUnlinked().reason()).isEqualTo(Gate.Reason.UNLINKED);

        HoldingsSnapshot unknown = new HoldingsSnapshot(HoldingsSnapshot.Status.UNKNOWN, List.of(), Instant.EPOCH);
        assertThat(gate.forLinked(unknown).reason()).isEqualTo(Gate.Reason.UNAVAILABLE);
        assertThat(gate.forLinked(unknown).allowed()).isTrue();
        assertThat(gate.forLinked(null).reason()).isEqualTo(Gate.Reason.UNAVAILABLE);

        HoldingsSnapshot stale = new HoldingsSnapshot(HoldingsSnapshot.Status.STALE,
                List.of(new AssetHolding("gromlin", "9", "1")), Instant.EPOCH);
        Gate.Decision admitted = gate.forLinked(stale);
        assertThat(admitted.allowed()).isTrue();
        assertThat(admitted.reason()).isEqualTo(Gate.Reason.PASS);
        assertThat(admitted.pass()).map(AssetHolding::collection).contains("gromlin");

        Gate.Decision denied = gate.forHoldings(List.of(new AssetHolding("moonsama-x", "12", "1")));
        assertThat(denied.action()).isEqualTo(GateConfig.Action.KICK);
        assertThat(denied.reason()).isEqualTo(Gate.Reason.DENIED);
        assertThat(Gate.kickMessageKey(denied.reason())).isEqualTo("kick-denied");
        assertThat(Gate.subtitleKey(Gate.Reason.UNLINKED)).isEqualTo("restricted-subtitle-unlinked");
    }

    @Test
    void configRejectsNonsense() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("denied.action", "allow");
        assertThatThrownBy(() -> GateConfig.from(yaml)).hasMessageContaining("denied.action");

        YamlConfiguration typo = new YamlConfiguration();
        typo.set("unlinked.action", "restrikt");
        assertThatThrownBy(() -> GateConfig.from(typo)).hasMessageContaining("unknown action");

        YamlConfiguration noCollection = new YamlConfiguration();
        noCollection.set("passes", List.of(java.util.Map.of("token-ids", List.of("1"))));
        assertThatThrownBy(() -> GateConfig.from(noCollection)).hasMessageContaining("collection");

        YamlConfiguration minimal = new YamlConfiguration();
        minimal.set("enabled", true);
        minimal.set("passes", List.of(java.util.Map.of("collection", "exosama", "token-ids", 5, "min-balance", 2)));
        minimal.set("allowed-commands", List.of("/Moonsama"));
        GateConfig config = GateConfig.from(minimal);
        assertThat(config.passes()).containsExactly(new Pass("exosama", Set.of("5"), new BigDecimal("2")));
        assertThat(config.allowedCommands()).containsExactly("moonsama");
        assertThat(config.unavailableWaitSeconds()).isEqualTo(15);
    }
}
