package com.moonsama.minecraft.skins;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EquippedSkinStoreTest {
    @TempDir
    Path directory;

    @Test
    void roundTripsRecordsThroughDisk() {
        Path file = directory.resolve("data").resolve("equipped.json");
        EquippedSkinStore store = new EquippedSkinStore(file);
        store.load();
        assertThat(store.all()).isEmpty();

        UUID withOriginal = UUID.randomUUID();
        UUID withoutOriginal = UUID.randomUUID();
        Instant at = Instant.parse("2026-09-24T10:15:30Z");
        store.put(withOriginal, new EquippedSkinStore.Equipped(new SkinRef("moonsama", 42), "orig", "sig", at));
        store.put(withoutOriginal, new EquippedSkinStore.Equipped(new SkinRef("exosama", 7), null, null, at));

        EquippedSkinStore reloaded = new EquippedSkinStore(file);
        reloaded.load();
        assertThat(reloaded.get(withOriginal)).contains(
                new EquippedSkinStore.Equipped(new SkinRef("moonsama", 42), "orig", "sig", at));
        assertThat(reloaded.get(withoutOriginal)).get()
                .satisfies(record -> {
                    assertThat(record.ref()).isEqualTo(new SkinRef("exosama", 7));
                    assertThat(record.hasOriginal()).isFalse();
                });

        assertThat(reloaded.remove(withOriginal)).isPresent();
        assertThat(reloaded.remove(withOriginal)).isEmpty();
        EquippedSkinStore again = new EquippedSkinStore(file);
        again.load();
        assertThat(again.all()).containsOnlyKeys(withoutOriginal);
        assertThat(Files.exists(file.resolveSibling("equipped.json.tmp"))).isFalse();
    }
}
