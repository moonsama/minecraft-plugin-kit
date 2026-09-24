package com.moonsama.minecraft.wardrobe;

import com.moonsama.minecraft.compositor.Composition;
import com.moonsama.minecraft.compositor.CompositorData.SlotValue;
import com.moonsama.minecraft.compositor.SkinCompositor;
import com.moonsama.minecraft.skins.SkinRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LookAndStoreTest {
    private static final SkinRef MOONSAMA_1 = new SkinRef("moonsama", 1);

    @Test
    void lookOverridesAndClearsSlotsOnTopOfDefaults() {
        SkinCompositor compositor = SkinCompositor.load(getClass().getClassLoader());
        List<SlotValue> defaults = compositor.defaultSlots("moonsama", 1).orElseThrow();

        Look look = new Look()
                .choose("hat", new SlotValue("hat", "moonsama", "moonsama:aviator"))
                .clear("hair");
        List<SlotValue> slots = look.applyTo(defaults, "moonsama");

        Map<String, SlotValue> bySlot = slots.stream().collect(java.util.stream.Collectors.toMap(SlotValue::slot, v -> v));
        assertEquals("moonsama:aviator", bySlot.get("hat").asset());
        assertFalse(bySlot.containsKey("hair"), "cleared slot must be dropped");
        assertEquals("moonsama:yellow_bird", bySlot.get("base").asset(), "untouched slots keep the NFT's parts");
        assertEquals("moonsama", bySlot.get("base").collection(), "main-collection parts get an explicit collection");
    }

    @Test
    void customizedCompositionRendersDifferentlyFromDefault() {
        SkinCompositor compositor = SkinCompositor.load(getClass().getClassLoader());
        List<SlotValue> defaults = compositor.defaultSlots("moonsama", 1).orElseThrow();
        Look look = new Look().choose("hat", new SlotValue("hat", "moonsama", "moonsama:aviator"));

        byte[] plain = compositor.renderToken("moonsama", 1).orElseThrow().png();
        byte[] custom = compositor.render(Composition.of("moonsama", 1, look.applyTo(defaults, "moonsama"))).png();

        assertTrue(plain.length > 0 && custom.length > 0);
        assertFalse(java.util.Arrays.equals(plain, custom), "adding a hat must change the rendered skin");
    }

    @Test
    void storeRoundTripsLooksIncludingClearedSlots(@TempDir Path dir) {
        Path file = dir.resolve("looks.json");
        UUID player = UUID.randomUUID();
        WardrobeStore store = new WardrobeStore(file);
        store.load();

        Look look = new Look()
                .choose("hat", new SlotValue("hat", "moonsama", "moonsama:aviator"))
                .clear("hair");
        store.put(player, MOONSAMA_1, look);
        assertTrue(Files.exists(file));

        WardrobeStore reloaded = new WardrobeStore(file);
        reloaded.load();
        Optional<Look> loaded = reloaded.get(player, MOONSAMA_1);
        assertTrue(loaded.isPresent());
        assertEquals(Optional.of("moonsama:aviator"), loaded.get().chosen("hat").map(SlotValue::asset));
        assertTrue(loaded.get().isCleared("hair"));
        assertFalse(loaded.get().touches("eyes"));
        assertEquals(1, reloaded.size());

        reloaded.remove(player, MOONSAMA_1);
        assertEquals(0, reloaded.size());
        WardrobeStore again = new WardrobeStore(file);
        again.load();
        assertTrue(again.get(player, MOONSAMA_1).isEmpty());
    }

    @Test
    void emptyLookIsNotPersisted(@TempDir Path dir) {
        Path file = dir.resolve("looks.json");
        WardrobeStore store = new WardrobeStore(file);
        store.load();
        UUID player = UUID.randomUUID();
        store.put(player, MOONSAMA_1, new Look().choose("hat", new SlotValue("hat", "moonsama", "moonsama:aviator")).revert("hat"));
        assertEquals(0, store.size());
        assertArrayEquals(new Object[0], store.get(player, MOONSAMA_1).stream().toArray());
    }
}
