package com.moonsama.minecraft.soulbound;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OwnerTagsTest {
    private static final UUID ALICE = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");
    private static final UUID BOB = UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7");

    @Test
    void createsTheKeyOnceAndKeepsTagsStable(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("plugins/MoonsamaCore/soulbound.key");

        String first = OwnerTags.load(file).tagOf(ALICE);
        byte[] key = Files.readAllBytes(file);
        String afterRestart = OwnerTags.load(file).tagOf(ALICE);

        assertThat(key).hasSize(OwnerTags.KEY_BYTES);
        assertThat(Files.readAllBytes(file)).isEqualTo(key);
        assertThat(afterRestart).isEqualTo(first);
    }

    @Test
    void tagsDifferPerPlayerAndPerServer(@TempDir Path dir) {
        OwnerTags server = OwnerTags.load(dir.resolve("a.key"));
        OwnerTags otherServer = OwnerTags.load(dir.resolve("b.key"));

        assertThat(server.tagOf(ALICE)).isNotEqualTo(server.tagOf(BOB));
        assertThat(server.tagOf(ALICE)).isNotEqualTo(otherServer.tagOf(ALICE));
    }

    @Test
    void tagsDoNotRevealTheUuid() {
        String tag = new OwnerTags(new byte[OwnerTags.KEY_BYTES]).tagOf(ALICE);

        assertThat(tag).hasSize(22).doesNotContain(ALICE.toString()).doesNotContain("0f8fad5b");
    }

    @Test
    void rejectsAKeyOfTheWrongSize(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("soulbound.key");
        Files.write(file, new byte[7]);

        assertThatThrownBy(() -> OwnerTags.load(file)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 bytes");
    }
}
