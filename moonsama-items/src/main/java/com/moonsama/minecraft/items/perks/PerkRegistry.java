package com.moonsama.minecraft.items.perks;

import com.moonsama.minecraft.items.ItemsCatalog;
import com.moonsama.minecraft.items.Offhand;
import org.bukkit.potion.PotionEffectType;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.logging.Logger;

/**
 * Maps catalog off-hand ids to their legacy gameplay perk. One perk instance per off-hand,
 * shared by every player who equips it (per-player data lives in {@link PerkState}).
 */
public final class PerkRegistry {
    private final Map<String, Perk> perks = new LinkedHashMap<>();

    public PerkRegistry(PerkContext ctx, ItemsCatalog catalog, PerkSettings settings) {
        Logger log = ctx.logger();
        for (Offhand offhand : catalog.offhands().values()) {
            if (!settings.isEnabled(offhand.id())) {
                continue;
            }
            BiFunction<PerkContext, Offhand, Perk> factory = factoryFor(offhand.id());
            if (factory == null) {
                log.fine("No gameplay perk for off-hand " + offhand.id());
                continue;
            }
            try {
                perks.put(offhand.id(), factory.apply(ctx, offhand));
            } catch (RuntimeException e) {
                log.warning("Could not set up perk for " + offhand.id() + ": " + e.getMessage());
            }
        }
    }

    /** Which perk (if any) would be created for an off-hand id; null when the id has no legacy behaviour. */
    static BiFunction<PerkContext, Offhand, Perk> factoryFor(String id) {
        if (id.startsWith("moonsama:moonbrella_")) {
            return MoonbrellaPerk::new;
        }
        if (id.startsWith("moonsama:detectore_")) {
            return DetectorePerk::new;
        }
        return switch (id) {
            case "moonsama:moonsama_egg" -> (ctx, o) -> new EffectPerk(ctx, o, PotionEffectType.HASTE, 1, false);
            case "moonsama:moonana" -> (ctx, o) -> new EffectPerk(ctx, o, PotionEffectType.JUMP_BOOST, 0, false);
            case "moonsama:moondrink" -> (ctx, o) -> new EffectPerk(ctx, o, PotionEffectType.HASTE, 2, true);
            case "moonsama:moonburger" -> MoonburgerPerk::new;
            case "moonsama:moonrum" -> MoonrumPerk::new;
            case "moonsama:moonpaw" -> MoonpawPerk::new;
            case "moonsama:moonsquid", "moonsama:moonsquid_neon" -> MoonsquidPerk::new;
            case "moonsama:moonbroom" -> MoonbroomPerk::new;
            case "moonsama:moontree" -> MoontreePerk::new;
            case "moonsama:moonflake" -> MoonflakePerk::new;
            case "moonsama:moonbag" -> MoonbagPerk::new;
            case "bunnysama:moonbasket" -> MoonbasketPerk::new;
            case "publicpressure:pods" -> PodsPerk::new;
            default -> null;
        };
    }

    public static boolean hasPerk(String offhandId) {
        return factoryFor(offhandId) != null;
    }

    public Optional<Perk> forOffhand(String offhandId) {
        return Optional.ofNullable(perks.get(offhandId));
    }

    public Map<String, Perk> all() {
        return Collections.unmodifiableMap(perks);
    }

    /** Tears down perks that hold world state (running jams, listeners). */
    public void shutdown() {
        for (Perk perk : perks.values()) {
            if (perk instanceof PodsPerk pods) {
                pods.stopAll();
            }
        }
    }
}
