package com.moonsama.minecraft.whale;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Name colours by power, including the legacy per-letter rainbow. */
public final class NameStyle {
    static final List<TextColor> RAINBOW = List.of(
            NamedTextColor.RED, NamedTextColor.GOLD, NamedTextColor.YELLOW, NamedTextColor.GREEN,
            NamedTextColor.AQUA, NamedTextColor.BLUE, NamedTextColor.LIGHT_PURPLE);

    private NameStyle() {
    }

    /** The highest tier whose {@code min-power} the power reaches, if any. */
    public static Optional<WhaleConfig.NameTier> tierFor(WhaleConfig config, double power) {
        WhaleConfig.NameTier best = null;
        for (WhaleConfig.NameTier tier : config.nameTiers()) {
            if (power >= tier.minPower()) {
                best = tier;
            }
        }
        return Optional.ofNullable(best);
    }

    /** Styled name, or empty when the player keeps their plain name. */
    public static Optional<Component> styled(WhaleConfig config, String name, double power) {
        if (!config.namesEnabled()) {
            return Optional.empty();
        }
        return tierFor(config, power).map(tier -> "rainbow".equals(tier.color())
                ? rainbow(name)
                : Component.text(name, namedColor(tier.color())));
    }

    public static Component rainbow(String name) {
        TextComponent.Builder builder = Component.text();
        for (int i = 0; i < name.length(); i++) {
            builder.append(Component.text(name.charAt(i), RAINBOW.get(i % RAINBOW.size())));
        }
        return builder.build();
    }

    public static NamedTextColor namedColor(String name) {
        return NamedTextColor.NAMES.value(name.toLowerCase(Locale.ROOT));
    }
}
