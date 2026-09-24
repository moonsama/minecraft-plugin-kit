package com.moonsama.minecraft.skins;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AttributionsTest {
    private static final String MULTIVERSE_ART = "moonsama-multiverse-art-eth";

    @Test
    void everyMultiverseArtSkinIsCredited() {
        Attributions attributions = Attributions.load(getClass().getClassLoader());
        SkinCatalog catalog = SkinCatalog.load(getClass().getClassLoader(), List.of(MULTIVERSE_ART));

        assertThat(attributions.hasCredits(MULTIVERSE_ART)).isTrue();
        assertThat(attributions.size()).isEqualTo(27);
        for (long id = 1; id <= catalog.size(MULTIVERSE_ART); id++) {
            SkinRef ref = new SkinRef(MULTIVERSE_ART, id);
            assertThat(attributions.find(ref)).as("credit for %s", ref).isPresent()
                    .get().satisfies(credit -> {
                        assertThat(credit.artist()).isNotBlank();
                        assertThat(credit.piece()).isNotBlank();
                    });
        }
    }

    @Test
    void mappingMatchesTheArtistsOriginals() {
        // Verified by pixel-matching the artists' delivered skin files against the bundled textures.
        Attributions attributions = Attributions.load(getClass().getClassLoader());
        assertThat(attributions.find(new SkinRef(MULTIVERSE_ART, 1))).get()
                .extracting(Attributions.Credit::artist, Attributions.Credit::piece)
                .containsExactly("Yumi", "Nemesis, the Queen of Retribution");
        assertThat(attributions.find(new SkinRef(MULTIVERSE_ART, 11))).get()
                .extracting(Attributions.Credit::artist).isEqualTo("Blood Moon Clan");
        assertThat(attributions.find(new SkinRef(MULTIVERSE_ART, 24))).get()
                .extracting(Attributions.Credit::artist, Attributions.Credit::collection, Attributions.Credit::piece)
                .containsExactly("Tako", "Shiba Tales", "El Rmrko");
        assertThat(attributions.find(new SkinRef(MULTIVERSE_ART, 27))).get()
                .extracting(Attributions.Credit::artist).isEqualTo("Ruben Topia");
        assertThat(attributions.find(new SkinRef("moonsama", 1))).isEmpty();
        assertThat(attributions.hasCredits("moonsama")).isFalse();
    }
}
