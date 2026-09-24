package com.moonsama.minecraft.gatekeeper;

import org.bukkit.configuration.ConfigurationSection;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Parsed {@code config.yml}. */
public record GateConfig(
        boolean enabled,
        List<Pass> passes,
        Action unlinked,
        int graceSeconds,
        Action denied,
        Action unavailable,
        int unavailableWaitSeconds,
        boolean recheckOnRefresh,
        String bypassPermission,
        Set<String> allowedCommands,
        int reminderSeconds,
        Map<String, String> messages
) {
    public enum Action { ALLOW, RESTRICT, KICK }

    public GateConfig {
        passes = List.copyOf(passes);
        allowedCommands = Set.copyOf(allowedCommands);
        messages = Map.copyOf(messages);
    }

    public static GateConfig from(ConfigurationSection root) {
        List<Pass> passes = new ArrayList<>();
        for (Map<?, ?> raw : root.getMapList("passes")) {
            Object collection = raw.get("collection");
            if (collection == null) {
                throw new IllegalArgumentException("passes: every entry needs a collection");
            }
            Set<String> tokenIds = new HashSet<>();
            Object ids = raw.get("token-ids");
            if (ids instanceof List<?> list) {
                for (Object id : list) {
                    tokenIds.add(String.valueOf(id));
                }
            } else if (ids != null) {
                tokenIds.add(String.valueOf(ids));
            }
            Object min = raw.get("min-balance");
            BigDecimal minBalance = min == null ? null : new BigDecimal(String.valueOf(min));
            passes.add(new Pass(String.valueOf(collection), tokenIds, minBalance));
        }

        Set<String> commands = new HashSet<>();
        for (String command : root.getStringList("allowed-commands")) {
            commands.add(command.toLowerCase(Locale.ROOT).replaceFirst("^/", ""));
        }

        Map<String, String> messages = new LinkedHashMap<>();
        ConfigurationSection section = root.getConfigurationSection("messages");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                messages.put(key, section.getString(key, ""));
            }
        }

        return new GateConfig(
                root.getBoolean("enabled", false),
                passes,
                action(root, "unlinked.action", Action.RESTRICT, Action.values()),
                Math.max(0, root.getInt("unlinked.grace-seconds", 600)),
                action(root, "denied.action", Action.KICK, Action.KICK, Action.RESTRICT),
                action(root, "unavailable.action", Action.ALLOW, Action.values()),
                Math.max(0, root.getInt("unavailable.wait-seconds", 15)),
                root.getBoolean("recheck-on-refresh", true),
                root.getString("bypass-permission", "moonsama.gatekeeper.bypass"),
                commands,
                Math.max(0, root.getInt("reminder-seconds", 30)),
                messages);
    }

    private static Action action(ConfigurationSection root, String path, Action fallback, Action... allowed) {
        String raw = root.getString(path);
        if (raw == null) {
            return fallback;
        }
        Action action;
        try {
            action = Action.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(path + ": unknown action '" + raw + "'");
        }
        for (Action candidate : allowed) {
            if (candidate == action) {
                return action;
            }
        }
        throw new IllegalArgumentException(path + ": '" + raw + "' is not allowed here");
    }

    public String message(String key) {
        return messages.getOrDefault(key, key);
    }
}
