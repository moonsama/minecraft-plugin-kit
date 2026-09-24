package com.moonsama.minecraft;

import com.moonsama.minecraft.store.SqliteJournalMode;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.time.Duration;

public record MoonsamaConfig(
        URI apiUrl,
        URI portalUrl,
        String apiKey,
        String apiSecret,
        String oauthClientId,
        String oauthClientSecret,
        URI oauthRedirectUri,
        String callbackBindHost,
        int callbackPort,
        Duration oauthAttemptTtl,
        boolean resourcePackEnabled,
        URI resourcePackPublicUrl,
        String resourcePackFile,
        boolean resourcePackRequired,
        boolean spendEnabled,
        boolean rewardEnabled,
        boolean refundEnabled,
        boolean holdsEnabled,
        Duration holdingsTtl,
        Duration changesInterval,
        Duration erasuresInterval,
        Duration receiptsInterval,
        String mineskinApiKey,
        URI mineskinUrl,
        SqliteJournalMode journalMode
) {
    static MoonsamaConfig load(JavaPlugin plugin) {
        FileConfiguration config = plugin.getConfig();
        return new MoonsamaConfig(
                uri(value(config, "portal.api-url", "PORTAL_API_URL")),
                uri(value(config, "portal.web-url", "PORTAL_WEB_URL")),
                value(config, "portal.api-key", "PORTAL_API_KEY"),
                value(config, "portal.api-secret", "PORTAL_API_SECRET"),
                value(config, "oauth.client-id", "PORTAL_OAUTH_CLIENT_ID"),
                value(config, "oauth.client-secret", "PORTAL_OAUTH_CLIENT_SECRET"),
                uri(value(config, "oauth.redirect-uri", "PORTAL_OAUTH_REDIRECT_URI")),
                value(config, "oauth.callback-bind-host", "PORTAL_CALLBACK_BIND_HOST"),
                integer(config, "oauth.callback-port", "PORTAL_CALLBACK_PORT"),
                Duration.ofMinutes(config.getLong("oauth.attempt-ttl-minutes", 10)),
                config.getBoolean("resource-pack.enabled", true),
                uri(config.getString(
                        "resource-pack.public-url",
                        "http://127.0.0.1:8080/resourcepack.zip"
                )),
                config.getString("resource-pack.file", "resourcepack.zip"),
                config.getBoolean("resource-pack.required", true),
                bool(config, "economy.spend-enabled", "PORTAL_SPEND_ENABLED"),
                bool(config, "economy.reward-enabled", "PORTAL_REWARD_ENABLED"),
                bool(config, "economy.refund-enabled", "PORTAL_REFUND_ENABLED"),
                bool(config, "economy.holds-enabled", "PORTAL_HOLDS_ENABLED"),
                Duration.ofSeconds(config.getLong("sync.holdings-ttl-seconds", 60)),
                Duration.ofSeconds(config.getLong("sync.changes-interval-seconds", 5)),
                Duration.ofSeconds(config.getLong("sync.erasures-interval-seconds", 86_400)),
                Duration.ofSeconds(config.getLong("sync.receipts-interval-seconds", 30)),
                value(config, "skins.mineskin-api-key", "MINESKIN_API_KEY"),
                uri(config.getString("skins.mineskin-url", "https://api.mineskin.org")),
                SqliteJournalMode.parse(value(config, "storage.journal-mode", "MOONSAMA_SQLITE_JOURNAL_MODE"))
        );
    }

    boolean isComplete() {
        return notBlank(apiKey)
                && notBlank(apiSecret)
                && notBlank(oauthClientId)
                && notBlank(oauthClientSecret);
    }

    private static String value(FileConfiguration config, String path, String environmentName) {
        String environmentValue = System.getenv(environmentName);
        if (environmentValue != null && !environmentValue.isBlank()) {
            return environmentValue;
        }
        return config.getString(path, "");
    }

    private static int integer(FileConfiguration config, String path, String environmentName) {
        String environmentValue = System.getenv(environmentName);
        if (environmentValue != null && !environmentValue.isBlank()) {
            return Integer.parseInt(environmentValue);
        }
        return config.getInt(path);
    }

    private static boolean bool(
            FileConfiguration config,
            String path,
            String environmentName
    ) {
        String environmentValue = System.getenv(environmentName);
        if (environmentValue != null && !environmentValue.isBlank()) {
            return Boolean.parseBoolean(environmentValue);
        }
        return config.getBoolean(path, false);
    }

    private static URI uri(String value) {
        return URI.create(value);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
