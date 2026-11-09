package com.moonsama.minecraft.economy;

import com.moonsama.minecraft.api.AssetHolding;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Portal collections that are the same items under the same token ids, because a collection
 * was migrated between chains and Portal exposes both the old and the new contract as
 * separate slugs (e.g. {@code moonsama-x} → {@code multiverseitems}).
 *
 * <p>{@link #expand(List)} adds, for every holding in an aliased collection whose token id is
 * inside the shared range, the same holding under each twin slug. Feature plugins then match
 * on whichever slug their data uses without knowing about the migration.
 */
public final class CollectionAliases {
    /** @param tokenIds inclusive id ranges that exist on every collection of the group; empty = all ids */
    public record Group(Set<String> collections, List<Range> tokenIds) {
        public Group {
            Set<String> normalized = new LinkedHashSet<>();
            collections.forEach(value -> normalized.add(value.toLowerCase(Locale.ROOT)));
            collections = Collections.unmodifiableSet(normalized);
            tokenIds = List.copyOf(tokenIds);
            if (collections.size() < 2) {
                throw new IllegalArgumentException("an alias group needs at least two collections: " + collections);
            }
        }

        boolean covers(String tokenId) {
            if (tokenIds.isEmpty()) {
                return true;
            }
            long id;
            try {
                id = Long.parseLong(tokenId.trim());
            } catch (NumberFormatException e) {
                return false;
            }
            return tokenIds.stream().anyMatch(range -> range.contains(id));
        }
    }

    public record Range(long from, long to) {
        boolean contains(long id) {
            return id >= from && id <= to;
        }

        /** Parses {@code "7"} or {@code "1-70"}. */
        static Range parse(String text) {
            String value = text.trim();
            int dash = value.indexOf('-', 1);
            if (dash < 0) {
                long single = Long.parseLong(value);
                return new Range(single, single);
            }
            long from = Long.parseLong(value.substring(0, dash).trim());
            long to = Long.parseLong(value.substring(dash + 1).trim());
            if (to < from) {
                throw new IllegalArgumentException("bad token id range: " + text);
            }
            return new Range(from, to);
        }
    }

    public static final CollectionAliases NONE = new CollectionAliases(List.of());

    /**
     * Moonsama X (Moonriver) migrated to Multiverse Items (Exosama network) with identical token
     * ids (verified id by id against Portal metadata and the legacy Customizer unlock rules, which
     * gated every item on "Moonriver #N or Exosama #N"). No id range: a holding Portal reports
     * under either slug is the same item under the other.
     */
    public static final CollectionAliases DEFAULTS = new CollectionAliases(List.of(
            new Group(new LinkedHashSet<>(List.of("multiverseitems", "moonsama-x")), List.of())
    ));

    private final List<Group> groups;

    public CollectionAliases(List<Group> groups) {
        this.groups = List.copyOf(groups);
    }

    /**
     * Reads a list such as
     * <pre>
     * collection-aliases:
     *   - collections: [multiverseitems, moonsama-x]
     *     token-ids: ["1-70"]   # optional; omit to alias every id
     * </pre>
     * A missing key keeps {@link #DEFAULTS}; an explicit empty list disables aliasing.
     */
    public static CollectionAliases load(ConfigurationSection config, String path) {
        if (!config.isList(path)) {
            return DEFAULTS;
        }
        List<Group> groups = new ArrayList<>();
        for (Map<?, ?> entry : config.getMapList(path)) {
            Object collections = entry.get("collections");
            if (!(collections instanceof List<?> names)) {
                throw new IllegalArgumentException(path + ": each entry needs a 'collections' list");
            }
            Set<String> slugs = new LinkedHashSet<>();
            names.forEach(name -> slugs.add(String.valueOf(name)));
            List<Range> ranges = new ArrayList<>();
            Object ids = entry.get("token-ids");
            if (ids instanceof List<?> list) {
                list.forEach(value -> ranges.add(Range.parse(String.valueOf(value))));
            } else if (ids != null) {
                ranges.add(Range.parse(String.valueOf(ids)));
            }
            groups.add(new Group(slugs, ranges));
        }
        return new CollectionAliases(groups);
    }

    public List<Group> groups() {
        return groups;
    }

    public boolean isEmpty() {
        return groups.isEmpty();
    }

    /** Twin slugs for a collection (excluding itself), or empty if it is not aliased. */
    public Set<String> twinsOf(String collection) {
        String slug = collection.toLowerCase(Locale.ROOT);
        Set<String> twins = new LinkedHashSet<>();
        for (Group group : groups) {
            if (group.collections().contains(slug)) {
                group.collections().stream().filter(other -> !other.equals(slug)).forEach(twins::add);
            }
        }
        return twins;
    }

    /** The input holdings plus one copy per twin slug for every holding inside an alias group's id range. */
    public List<AssetHolding> expand(List<AssetHolding> holdings) {
        if (groups.isEmpty()) {
            return holdings;
        }
        List<AssetHolding> result = new ArrayList<>(holdings);
        Set<String> present = new LinkedHashSet<>();
        holdings.forEach(holding -> present.add(key(holding.collection(), holding.tokenId())));
        for (AssetHolding holding : holdings) {
            String slug = holding.collection().toLowerCase(Locale.ROOT);
            for (Group group : groups) {
                if (!group.collections().contains(slug) || !group.covers(holding.tokenId())) {
                    continue;
                }
                for (String twin : group.collections()) {
                    if (!twin.equals(slug) && present.add(key(twin, holding.tokenId()))) {
                        result.add(new AssetHolding(twin, holding.tokenId(), holding.balance()));
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    /** A holding as shown to a player: every slug it counts under, the one Portal reported first. */
    public record Folded(List<String> collections, String tokenId, String balance) {
    }

    /** Undoes {@link #expand(List)} for display: a holding and its twins with the same id and balance become one entry. */
    public List<Folded> fold(List<AssetHolding> holdings) {
        List<Folded> result = new ArrayList<>();
        boolean[] folded = new boolean[holdings.size()];
        for (int i = 0; i < holdings.size(); i++) {
            if (folded[i]) {
                continue;
            }
            AssetHolding holding = holdings.get(i);
            Set<String> twins = coveringTwins(holding);
            List<String> slugs = new ArrayList<>(List.of(holding.collection()));
            for (int j = i + 1; j < holdings.size(); j++) {
                AssetHolding other = holdings.get(j);
                if (!folded[j]
                        && twins.contains(other.collection().toLowerCase(Locale.ROOT))
                        && other.tokenId().trim().equals(holding.tokenId().trim())
                        && other.balance().equals(holding.balance())) {
                    folded[j] = true;
                    slugs.add(other.collection());
                }
            }
            result.add(new Folded(List.copyOf(slugs), holding.tokenId(), holding.balance()));
        }
        return List.copyOf(result);
    }

    private Set<String> coveringTwins(AssetHolding holding) {
        String slug = holding.collection().toLowerCase(Locale.ROOT);
        Set<String> twins = new LinkedHashSet<>();
        for (Group group : groups) {
            if (group.collections().contains(slug) && group.covers(holding.tokenId())) {
                group.collections().stream().filter(other -> !other.equals(slug)).forEach(twins::add);
            }
        }
        return twins;
    }

    private static String key(String collection, String tokenId) {
        return collection.toLowerCase(Locale.ROOT) + "#" + tokenId.trim();
    }

    @Override
    public String toString() {
        return groups.stream()
                .map(group -> String.join(" = ", group.collections())
                        + (group.tokenIds().isEmpty() ? "" : " (ids " + group.tokenIds().stream()
                        .map(range -> range.from() == range.to() ? Long.toString(range.from()) : range.from() + "-" + range.to())
                        .reduce((a, b) -> a + ", " + b).orElse("") + ")"))
                .reduce((a, b) -> a + "; " + b)
                .orElse("none");
    }
}
