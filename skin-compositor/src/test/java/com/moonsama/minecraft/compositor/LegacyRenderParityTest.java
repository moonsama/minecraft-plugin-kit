package com.moonsama.minecraft.compositor;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Renders every token's default composition and compares it with the skin the legacy
 * Composer/Unity pipeline produced (archived under {@code references/archive/composer-skins}).
 * Skipped when the archive is not present (community checkouts).
 *
 * <p>Full parity is impossible by construction: the archived Exosama renders predate the last
 * re-import of the Exosama layer files (renders 2023-08-31 13:xx, files 16:54), and the legacy
 * renderer applied some shirt shadows inconsistently. The thresholds below therefore guard
 * against regressions rather than demand identity; mismatching pairs are written to
 * {@code build/parity-mismatches} for eyeballing.
 */
class LegacyRenderParityTest {
    private static final Map<String, String> ARCHIVE_DIRS = Map.of(
            "moonsama", "moonsama",
            "exosama", "exosama",
            "moonsama-embassy", "moonsama-embassy"
    );

    /**
     * The legacy renderer was Unity, whose sRGB/linear round trip shifts opaque colours by up to one
     * step per channel. Anything larger is a real compositing difference.
     */
    static final int CHANNEL_TOLERANCE = 1;

    private static SkinCompositor compositor;
    private static Path archive;

    @BeforeAll
    static void load() {
        compositor = SkinCompositor.load(LegacyRenderParityTest.class.getClassLoader());
        String property = System.getProperty("moonsama.compositor.archive");
        archive = property == null ? null : Path.of(property);
    }

    @Test
    void rendersMoonsamaOneWithoutWarnings() {
        SkinCompositor.Rendered rendered = compositor.renderToken("moonsama", 1).orElseThrow();
        assertThat(rendered.image().getWidth()).isEqualTo(64);
        assertThat(rendered.image().getHeight()).isEqualTo(64);
        assertThat(rendered.warnings()).isEmpty();
        assertThat(rendered.resolution().components()).extracting(CompositorData.Component::referenceId)
                .contains("exosama_mc:template_settings", "exosama_mc:minecraft_settings", "moonsama_mc:yellow_bird");
        // Something must have been drawn on the head.
        assertThat(rendered.image().getRGB(12, 12) >>> 24).isEqualTo(255);
    }

    @Test
    void reportsWhichCollectionsAreComposable() {
        assertThat(compositor.supports("moonsama")).isTrue();
        assertThat(compositor.supports("exosama")).isTrue();
        assertThat(compositor.supports("gromlin")).isFalse();
        assertThat(compositor.supports("unknown")).isFalse();
    }

    @ParameterizedTest(name = "{0} >= {1}")
    @CsvSource({"moonsama, 0.55", "exosama, 0.60", "moonsama-embassy, 0.90"})
    void matchesArchivedLegacyRenders(String portalCollection, double minimumIdenticalRatio) throws IOException {
        assumeTrue(archive != null && Files.isDirectory(archive.resolve(ARCHIVE_DIRS.get(portalCollection))),
                "legacy render archive not available");
        Path dir = archive.resolve(ARCHIVE_DIRS.get(portalCollection));

        int compared = 0;
        int identical = 0;
        int nearlyIdentical = 0;
        List<String> mismatches = new ArrayList<>();
        try (var files = Files.list(dir)) {
            for (Path file : files.sorted().toList()) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".png")) {
                    continue;
                }
                long tokenId = Long.parseLong(name.substring(0, name.length() - 4));
                var rendered = compositor.renderToken(portalCollection, tokenId);
                if (rendered.isEmpty()) {
                    mismatches.add(tokenId + ": no default composition");
                    continue;
                }
                compared++;
                BufferedImage expected = CompositorData.toArgb(ImageIO.read(file.toFile()));
                int differing = differingPixels(expected, rendered.get().image());
                if (differing == 0) {
                    identical++;
                } else if (differing <= 4) {
                    nearlyIdentical++;
                } else if (mismatches.size() < 25) {
                    mismatches.add(String.format(Locale.ROOT, "%d: %d px differ", tokenId, differing));
                    dumpMismatch(portalCollection, tokenId, expected, rendered.get().image());
                }
            }
        }
        System.out.printf(Locale.ROOT, "%s: %d/%d identical to legacy renders, %d within 4 px%n",
                portalCollection, identical, compared, nearlyIdentical);
        assertThat(compared).isPositive();
        assertThat((double) identical / compared)
                .as(portalCollection + " parity too low; sample mismatches: " + mismatches)
                .isGreaterThanOrEqualTo(minimumIdenticalRatio);
    }

    /** Writes expected/actual side by side (8x) under build/parity-mismatches for inspection. */
    private static void dumpMismatch(String collection, long tokenId, BufferedImage expected, BufferedImage actual) throws IOException {
        Path dir = Path.of("build", "parity-mismatches", collection);
        Files.createDirectories(dir);
        int scale = 8;
        int w = expected.getWidth();
        int h = expected.getHeight();
        BufferedImage sheet = new BufferedImage((w * 2 + 4) * scale, h * scale, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h * scale; y++) {
            for (int x = 0; x < w * scale; x++) {
                sheet.setRGB(x, y, expected.getRGB(x / scale, y / scale));
                sheet.setRGB(x + (w + 4) * scale, y, actual.getRGB(x / scale, y / scale));
            }
        }
        ImageIO.write(sheet, "png", dir.resolve(tokenId + ".png").toFile());
    }

    static boolean withinTolerance(int a, int b) {
        for (int shift = 0; shift <= 24; shift += 8) {
            if (Math.abs(((a >>> shift) & 0xFF) - ((b >>> shift) & 0xFF)) > CHANNEL_TOLERANCE) {
                return false;
            }
        }
        return true;
    }

    static int differingPixels(BufferedImage a, BufferedImage b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
            return Integer.MAX_VALUE;
        }
        int count = 0;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                int pa = a.getRGB(x, y);
                int pb = b.getRGB(x, y);
                if ((pa >>> 24) == 0 && (pb >>> 24) == 0) {
                    continue; // fully transparent: colour channels are irrelevant
                }
                if (!withinTolerance(pa, pb)) {
                    count++;
                }
            }
        }
        return count;
    }
}
