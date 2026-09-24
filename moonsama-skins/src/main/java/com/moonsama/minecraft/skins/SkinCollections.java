package com.moonsama.minecraft.skins;

import org.bukkit.configuration.ConfigurationSection;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * How collections are presented: display order, names and which ones are <em>uniform</em>.
 *
 * <p>A uniform collection has the same skin for every token (Gromlins). Holding any number of
 * its tokens unlocks exactly one entry in the menu, ownership is checked against the collection
 * rather than a specific token id, and names omit the token number.
 */
public record SkinCollections(List<String> order, Map<String, String> names, Set<String> uniform) {

    public SkinCollections {
        order = List.copyOf(order);
        names = Map.copyOf(names);
        uniform = Set.copyOf(uniform);
    }

    public static SkinCollections from(ConfigurationSection config) {
        List<String> order = config.getStringList("collections");
        Map<String, String> names = new LinkedHashMap<>();
        ConfigurationSection section = config.getConfigurationSection("collection-names");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                names.put(key, section.getString(key, key));
            }
        }
        Set<String> uniform = new TreeSet<>();
        for (String collection : config.getStringList("uniform-collections")) {
            uniform.add(collection.toLowerCase(Locale.ROOT));
        }
        return new SkinCollections(order, names, uniform);
    }

    public boolean isUniform(String collection) {
        return uniform.contains(collection);
    }

    public String name(String collection) {
        return names.getOrDefault(collection, collection);
    }

    /** "Moonsama #42", or just "Gromlin" for a uniform collection. */
    public String displayName(SkinRef ref) {
        String name = name(ref.collection());
        return isUniform(ref.collection()) ? name : name + " #" + ref.tokenId();
    }

    /** Position in the configured display order; unknown collections sort last. */
    public int indexOf(String collection) {
        int index = order.indexOf(collection);
        return index < 0 ? Integer.MAX_VALUE : index;
    }
}
