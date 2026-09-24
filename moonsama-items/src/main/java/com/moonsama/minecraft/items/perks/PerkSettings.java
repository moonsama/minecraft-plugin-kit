package com.moonsama.minecraft.items.perks;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Operator-tunable parts of the perks, read from {@code config.yml} → {@code offhands.perks}. */
public record PerkSettings(
        boolean enabled,
        Set<String> disabled,
        boolean moonbasketBreaksBlocks,
        List<Song> podSongs
) {
    /** A song the Pods can play. {@code sound} is a resource-pack sound event; {@code lengthMs} its duration. */
    public record Song(String id, String sound, long lengthMs, String name, String artist) {}

    public static PerkSettings defaults() {
        return new PerkSettings(true, Set.of(), false, legacySongs());
    }

    public static PerkSettings from(ConfigurationSection section) {
        if (section == null) {
            return defaults();
        }
        List<Song> songs = new ArrayList<>();
        ConfigurationSection songsSection = section.getConfigurationSection("pod-songs");
        if (songsSection != null) {
            for (String id : songsSection.getKeys(false)) {
                ConfigurationSection song = songsSection.getConfigurationSection(id);
                if (song == null) {
                    continue;
                }
                songs.add(new Song(id,
                        song.getString("sound", ""),
                        song.getLong("length-ms", 0),
                        song.getString("name", id),
                        song.getString("artist", "")));
            }
        } else {
            songs = legacySongs();
        }
        return new PerkSettings(
                section.getBoolean("enabled", true),
                Set.copyOf(section.getStringList("disabled")),
                section.getBoolean("moonbasket-breaks-blocks", false),
                List.copyOf(songs));
    }

    public boolean isEnabled(String offhandId) {
        return enabled && !disabled.contains(offhandId);
    }

    /**
     * The Public Pressure tracks the legacy pack shipped. The music itself is third-party and is
     * not part of this kit's resource pack; servers that still distribute the old pack get sound,
     * everyone else gets the jam buffs without audio.
     */
    static List<Song> legacySongs() {
        return List.of(
                new Song("merk_and_kremont_holy_water", "publicpressure:merk_and_kremont.holy_water.mono", 163_000, "Holy Water", "Merk & Kremont"),
                new Song("merk_and_kremont_i_can_be", "publicpressure:merk_and_kremont.i_can_be.mono", 146_000, "I Can Be", "Merk & Kremont"),
                new Song("merk_and_kremont_mot__chandon", "publicpressure:merk_and_kremont.mot__chandon.mono", 188_000, "Moët & Chandon", "Merk & Kremont")
        );
    }
}
