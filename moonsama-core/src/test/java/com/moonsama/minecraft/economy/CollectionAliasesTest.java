package com.moonsama.minecraft.economy;

import com.moonsama.minecraft.api.AssetHolding;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CollectionAliasesTest {
    private static final AssetHolding MOONSAMA_X_VIP = new AssetHolding("moonsama-x", "1", "1");
    private static final AssetHolding MULTIVERSE_DETECTORE = new AssetHolding("multiverseitems", "64", "2");
    private static final AssetHolding MULTIVERSE_MOONBAG = new AssetHolding("multiverseitems", "85", "1");
    private static final AssetHolding PFP = new AssetHolding("moonsama", "12", "1");

    @Test
    void defaultsMergeMoonsamaXAndMultiverseItemsForEveryId() {
        List<AssetHolding> expanded = CollectionAliases.DEFAULTS.expand(
                List.of(MOONSAMA_X_VIP, MULTIVERSE_DETECTORE, MULTIVERSE_MOONBAG, PFP));

        assertThat(expanded).containsExactlyInAnyOrder(
                MOONSAMA_X_VIP, MULTIVERSE_DETECTORE, MULTIVERSE_MOONBAG, PFP,
                new AssetHolding("multiverseitems", "1", "1"),   // VIP ticket counts on the new slug
                new AssetHolding("moonsama-x", "64", "2"),       // and vice versa
                new AssetHolding("moonsama-x", "85", "1")        // every id, not just the 1-70 that existed on Moonriver
        );
    }

    @Test
    void doesNotDuplicateWhenBothSlugsAlreadyHoldTheId() {
        AssetHolding onOld = new AssetHolding("moonsama-x", "5", "1");
        AssetHolding onNew = new AssetHolding("multiverseitems", "5", "3");
        assertThat(CollectionAliases.DEFAULTS.expand(List.of(onOld, onNew)))
                .containsExactlyInAnyOrder(onOld, onNew);
    }

    @Test
    void noneIsIdentity() {
        List<AssetHolding> holdings = List.of(MOONSAMA_X_VIP, PFP);
        assertThat(CollectionAliases.NONE.expand(holdings)).isSameAs(holdings);
        assertThat(CollectionAliases.NONE.twinsOf("moonsama-x")).isEmpty();
        assertThat(CollectionAliases.DEFAULTS.twinsOf("moonsama-x")).containsExactly("multiverseitems");
    }

    @Test
    void loadsFromYamlAndFallsBackToDefaults() {
        YamlConfiguration yaml = new YamlConfiguration();
        assertThat(CollectionAliases.load(yaml, "collection-aliases").groups())
                .isEqualTo(CollectionAliases.DEFAULTS.groups());

        yaml.set("collection-aliases", List.of());
        assertThat(CollectionAliases.load(yaml, "collection-aliases").isEmpty()).isTrue();

        YamlConfiguration custom = new YamlConfiguration();
        custom.set("collection-aliases", List.of(
                java.util.Map.of("collections", List.of("Pondsama", "pondsama-movr"), "token-ids", List.of("1-10", "42")),
                java.util.Map.of("collections", List.of("a", "b"))));
        CollectionAliases loaded = CollectionAliases.load(custom, "collection-aliases");
        assertThat(loaded.groups()).hasSize(2);
        assertThat(loaded.groups().get(0).collections()).isEqualTo(Set.of("pondsama", "pondsama-movr"));
        assertThat(loaded.groups().get(0).tokenIds())
                .containsExactly(new CollectionAliases.Range(1, 10), new CollectionAliases.Range(42, 42));
        assertThat(loaded.groups().get(1).tokenIds()).isEmpty();
        assertThat(loaded.expand(List.of(new AssetHolding("a", "999", "1"))))
                .contains(new AssetHolding("b", "999", "1"));
        assertThat(loaded.expand(List.of(new AssetHolding("pondsama", "11", "1"))))
                .containsExactly(new AssetHolding("pondsama", "11", "1"));
    }

    @Test
    void foldShowsEachRealHoldingOnceWithTheSlugPortalReportedFirst() {
        CollectionAliases aliases = CollectionAliases.DEFAULTS;
        List<AssetHolding> expanded = aliases.expand(List.of(MOONSAMA_X_VIP, MULTIVERSE_DETECTORE, PFP));

        assertThat(aliases.fold(expanded)).containsExactly(
                new CollectionAliases.Folded(List.of("moonsama-x", "multiverseitems"), "1", "1"),
                new CollectionAliases.Folded(List.of("multiverseitems", "moonsama-x"), "64", "2"),
                new CollectionAliases.Folded(List.of("moonsama"), "12", "1")
        );

        AssetHolding onOld = new AssetHolding("moonsama-x", "5", "1");
        AssetHolding onNew = new AssetHolding("multiverseitems", "5", "3");
        assertThat(aliases.fold(aliases.expand(List.of(onOld, onNew)))).containsExactly(
                new CollectionAliases.Folded(List.of("moonsama-x"), "5", "1"),
                new CollectionAliases.Folded(List.of("multiverseitems"), "5", "3")
        );
    }

    @Test
    void keepsTheCollectionOrderAsWritten() {
        assertThat(CollectionAliases.DEFAULTS.toString()).isEqualTo("multiverseitems = moonsama-x");

        YamlConfiguration custom = new YamlConfiguration();
        custom.set("collection-aliases", List.of(
                java.util.Map.of("collections", List.of("pondsama-movr", "Pondsama"), "token-ids", List.of("1-10"))));
        assertThat(CollectionAliases.load(custom, "collection-aliases").toString())
                .isEqualTo("pondsama-movr = pondsama (ids 1-10)");
    }

    @Test
    void rejectsSingleCollectionGroupsAndBadRanges() {
        assertThatThrownBy(() -> new CollectionAliases.Group(Set.of("only"), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CollectionAliases.Range.parse("70-1"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
