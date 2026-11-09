package com.moonsama.minecraft;

import com.sun.net.httpserver.HttpExchange;
import net.kyori.adventure.text.Component;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class ResourcePackManager implements Listener {
    private final JavaPlugin plugin;
    private final MoonsamaConfig config;
    private final byte[] bytes;
    private final String sha1;

    private ResourcePackManager(
            JavaPlugin plugin,
            MoonsamaConfig config,
            byte[] bytes,
            String sha1
    ) {
        this.plugin = plugin;
        this.config = config;
        this.bytes = bytes;
        this.sha1 = sha1;
    }

    static ResourcePackManager load(JavaPlugin plugin, MoonsamaConfig config) {
        if (!config.resourcePackEnabled()) {
            return null;
        }
        Path file = plugin.getDataFolder().toPath().resolve(config.resourcePackFile());
        if (!Files.isRegularFile(file)) {
            plugin.getLogger().warning(
                    "Resource pack is enabled but " + file + " does not exist."
            );
            return null;
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            String sha1 = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-1").digest(bytes)
            );
            plugin.getLogger().info("Loaded resource pack " + file + " (SHA-1 " + sha1 + ")");
            return new ResourcePackManager(plugin, config, bytes, sha1);
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Could not load resource pack " + file, exception);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () ->
                event.getPlayer().setResourcePack(
                        config.resourcePackPublicUrl().toString(),
                        sha1,
                        config.resourcePackRequired(),
                        Component.text("Moonsama custom items require this resource pack.")
                ), 20L);
    }

    void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "application/zip");
        exchange.getResponseHeaders().set("Cache-Control", "public, max-age=31536000, immutable");
        exchange.getResponseHeaders().set("ETag", "\"" + sha1 + "\"");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
