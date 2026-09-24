package com.moonsama.minecraft.items.perks;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moonsama.minecraft.items.ItemsCatalog;
import com.moonsama.minecraft.items.Offhand;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/** Keeps the perk constants, {@code offhand-states.json} and the resource pack in step. */
class PerkDataTest {
    private static ItemsCatalog catalog;
    private static JsonObject states;

    @BeforeAll
    static void load() throws IOException {
        catalog = ItemsCatalog.load(PerkDataTest.class.getClassLoader());
        try (var in = PerkDataTest.class.getClassLoader().getResourceAsStream("moonsama/cosmetics/offhand-states.json")) {
            assertThat(in).as("offhand-states.json bundled").isNotNull();
            states = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    @Test
    void everyCatalogOffhandHasALegacyPerk() {
        for (Offhand offhand : catalog.offhands().values()) {
            assertThat(PerkRegistry.hasPerk(offhand.id())).as("perk for " + offhand.id()).isTrue();
        }
        assertThat(PerkRegistry.hasPerk("moonsama:does_not_exist")).isFalse();
    }

    @Test
    void stateModelsMatchPerkConstants() {
        Map<String, int[]> expected = new HashMap<>();
        expected.put("buzzer", new int[]{MoonburgerPerk.BUZZER_MODEL});
        expected.put("moonpaw_pounce", new int[]{MoonpawPerk.POUNCE_MODEL});
        expected.put("moonpaw_buzzer", new int[]{MoonpawPerk.BUZZER_MODEL});
        expected.put("moonrum_empty", new int[]{MoonrumPerk.EMPTY_MODEL});
        expected.put("moonrum_drink", new int[]{MoonrumPerk.DRINK_MODEL});
        expected.put("moonburger_food", new int[]{MoonburgerPerk.FOOD_MODEL});
        expected.put("pods_charging", new int[]{PodsPerk.CHARGING_MODEL});
        expected.put("pods_song", new int[]{PodsPerk.SONG_MODEL});
        expected.put("pods_ghost", new int[]{PodsPerk.GHOST_MODEL});
        expected.put("moonbroom_buzzer", new int[]{MoonbroomPerk.BUZZER_MODEL});
        expected.put("moonbroom_ready", new int[]{MoonbroomPerk.READY_MODEL});
        expected.put("moonbroom_projectile", new int[]{MoonbroomPerk.PROJECTILE_MODEL});
        expected.put("moontree_buzzer", new int[]{MoontreePerk.BUZZER_MODEL});
        expected.put("moonflake_buzzer", new int[]{MoonflakePerk.BUZZER_MODEL});
        expected.put("moonbasket_empty", new int[]{MoonbasketPerk.EMPTY_MODEL});
        expected.put("moonbasket_1", new int[]{MoonbasketPerk.EGG_MODELS[0]});
        expected.put("moonbasket_2", new int[]{MoonbasketPerk.EGG_MODELS[1]});
        expected.put("moonbasket_3", new int[]{MoonbasketPerk.EGG_MODELS[2]});
        expected.put("moonsquid_helmet", new int[]{MoonsquidPerk.HELMET_MODEL});
        for (Map.Entry<String, Integer> glow : DetectorePerk.GLOW_BASE.entrySet()) {
            String id = glow.getKey().substring(glow.getKey().indexOf(':') + 1) + "_glow";
            expected.put(id, new int[]{glow.getValue(), DetectorePerk.Highlight.values().length});
        }

        Map<String, JsonObject> byId = new HashMap<>();
        for (JsonElement element : states.getAsJsonArray("states")) {
            JsonObject state = element.getAsJsonObject();
            byId.put(state.get("id").getAsString(), state);
        }
        assertThat(byId.keySet()).containsExactlyInAnyOrderElementsOf(expected.keySet());
        for (Map.Entry<String, int[]> entry : expected.entrySet()) {
            JsonObject state = byId.get(entry.getKey());
            assertThat(state.get("customModelData").getAsInt()).as(entry.getKey()).isEqualTo(entry.getValue()[0]);
            int count = state.has("count") ? state.get("count").getAsInt() : 1;
            assertThat(count).as(entry.getKey() + " count").isEqualTo(entry.getValue().length > 1 ? entry.getValue()[1] : 1);
            assertThat(catalog.offhand(state.get("offhand").getAsString())).as(entry.getKey() + " offhand").isPresent();
        }
    }

    @Test
    void resourcePackCoversEveryStateAndSound() throws IOException {
        Path pack = Path.of("..", "resourcepack", "src", "assets");
        for (JsonElement element : states.getAsJsonArray("states")) {
            JsonObject state = element.getAsJsonObject();
            String material = state.get("material").getAsString().substring("minecraft:".length());
            assertThat(Material.matchMaterial(material)).as(material).isNotNull();
            String definition = Files.readString(pack.resolve("minecraft/items/" + material + ".json"));
            int base = state.get("customModelData").getAsInt();
            int count = state.has("count") ? state.get("count").getAsInt() : 1;
            for (int i = 0; i < count; i++) {
                assertThat(definition).as(state.get("id").getAsString() + "+" + i)
                        .contains("\"threshold\": " + (base + i) + ".0");
            }
        }
        JsonArray sounds = states.getAsJsonArray("sounds");
        assertThat(sounds).isNotEmpty();
        for (JsonElement element : sounds) {
            String event = element.getAsJsonObject().get("event").getAsString();
            String namespace = event.substring(0, event.indexOf('.'));
            JsonObject soundsJson = JsonParser.parseString(Files.readString(pack.resolve(namespace + "/sounds.json"))).getAsJsonObject();
            assertThat(soundsJson.has(event)).as(event).isTrue();
        }
        assertThat(MoonrumPerk.LAUGH_SOUND).isEqualTo("moonsama.pirate_laugh");
    }

    @Test
    void detectoreColoursFollowTheLegacyTable() {
        assertThat(DetectorePerk.highlightOf(Material.DIAMOND_ORE)).contains(DetectorePerk.Highlight.LIGHT_BLUE);
        assertThat(DetectorePerk.highlightOf(Material.ANCIENT_DEBRIS)).contains(DetectorePerk.Highlight.BLACK);
        assertThat(DetectorePerk.highlightOf(Material.STONE)).isEmpty();
        // emerald base 22 + yellow (14) = 36, the last emerald glow model of the legacy pack
        assertThat(DetectorePerk.glowModel(22, DetectorePerk.Highlight.YELLOW)).isEqualTo(36);
        assertThat(DetectorePerk.glowBaseOf("moonsama:detectore_ruby")).contains(37);
        for (String id : catalog.offhands().keySet()) {
            if (id.startsWith("moonsama:detectore_")) {
                assertThat(DetectorePerk.glowBaseOf(id)).as(id).isPresent();
            }
        }
    }

    @Test
    void moonbagTiersGetStrongerAndNeverRepeatTheLastEffect() {
        Random random = new Random(42);
        for (int i = 0; i < 200; i++) {
            MoonbagPerk.Buff tier1 = MoonbagPerk.pick(0, "haste", random);
            assertThat(tier1.key()).isNotEqualTo("haste");
            assertThat(MoonbagPerk.TIER_1).contains(tier1);
            MoonbagPerk.Buff tier3 = MoonbagPerk.pick(MoonbagPerk.CYCLES_PER_TIER * 2, null, random);
            assertThat(MoonbagPerk.TIER_3).contains(tier3);
        }
        assertThat(MoonbagPerk.TIER_3).anyMatch(b -> b.level() == 3);
        assertThat(MoonbagPerk.TIER_1).allMatch(b -> b.level() == 1);
        assertThat(MoonbagPerk.describe(new MoonbagPerk.Buff("health_boost", 2))).isEqualTo("Health boost II");
    }

    @Test
    void settingsParseConfigAndFallBackToLegacySongs() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("enabled", true);
        yaml.set("disabled", java.util.List.of("publicpressure:pods"));
        yaml.set("moonbasket-breaks-blocks", true);
        yaml.set("pod-songs.my_track.sound", "myserver:music.my_track");
        yaml.set("pod-songs.my_track.length-ms", 180000);
        yaml.set("pod-songs.my_track.name", "My Track");
        yaml.set("pod-songs.my_track.artist", "Somebody");
        PerkSettings settings = PerkSettings.from(yaml);
        assertThat(settings.isEnabled("publicpressure:pods")).isFalse();
        assertThat(settings.isEnabled("moonsama:moonbroom")).isTrue();
        assertThat(settings.moonbasketBreaksBlocks()).isTrue();
        assertThat(settings.podSongs()).containsExactly(
                new PerkSettings.Song("my_track", "myserver:music.my_track", 180000, "My Track", "Somebody"));

        PerkSettings defaults = PerkSettings.from(new YamlConfiguration());
        assertThat(defaults.podSongs()).hasSize(3).allMatch(s -> s.artist().equals("Merk & Kremont"));
        assertThat(PerkSettings.from(null).isEnabled("moonsama:moonbag")).isTrue();

        // bundled config parses the same way
        YamlConfiguration bundled = YamlConfiguration.loadConfiguration(
                new InputStreamReader(PerkDataTest.class.getClassLoader().getResourceAsStream("config.yml"), StandardCharsets.UTF_8));
        PerkSettings fromBundled = PerkSettings.from(bundled.getConfigurationSection("offhands.perks"));
        assertThat(fromBundled.enabled()).isTrue();
        assertThat(fromBundled.disabled()).isEmpty();
        assertThat(fromBundled.moonbasketBreaksBlocks()).isFalse();
        assertThat(fromBundled.podSongs()).hasSize(3);
        assertThat(PerkContext.formatDuration(125_000)).isEqualTo("2m 5s".toLowerCase(Locale.ROOT));
    }
}
