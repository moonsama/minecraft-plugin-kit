package com.moonsama.minecraft;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;

final class MoonsamaCommand implements CommandExecutor, TabCompleter {
    private static final List<String> SUBCOMMANDS =
            List.of("link", "status", "holdings", "admin");
    private final MoonsamaPaperPlugin plugin;

    MoonsamaCommand(MoonsamaPaperPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            String @NotNull [] args
    ) {
        String subcommand = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        if ("admin".equals(subcommand)) {
            adminStatus(sender, args);
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used by a player.");
            return true;
        }

        switch (subcommand) {
            case "link" -> link(player);
            case "status" -> status(player);
            case "holdings" -> holdings(player);
            default -> player.sendRichMessage(
                    "<red>Usage: /moonsama <link|status|holdings></red>"
            );
        }
        return true;
    }

    private void adminStatus(CommandSender sender, String[] args) {
        if (!sender.hasPermission("moonsama.admin")) {
            sender.sendMessage("You do not have permission to inspect MoonsamaCore.");
            return;
        }
        if (args.length > 1 && !"status".equalsIgnoreCase(args[1])) {
            sender.sendMessage("Usage: /moonsama admin status");
            return;
        }
        plugin.operatorStatus().whenComplete((lines, failure) ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (failure != null) {
                        sender.sendMessage("Could not read MoonsamaCore status: "
                                + plugin.userMessage(failure));
                        return;
                    }
                    sender.sendMessage("MoonsamaCore status");
                    lines.forEach(sender::sendMessage);
                })
        );
    }

    private void link(Player player) {
        player.sendRichMessage("<yellow>Preparing a secure Portal login link…</yellow>");
        plugin.beginLink(player.getUniqueId()).whenComplete((url, failure) ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (failure != null) {
                        player.sendRichMessage("<red>" + safe(plugin.userMessage(failure)) + "</red>");
                        return;
                    }
                    player.sendRichMessage(
                            "<green>Connect your Moonsama account:</green> "
                                    + "<click:open_url:'" + url + "'>"
                                    + "<hover:show_text:'Open Moonsama Portal'>"
                                    + "<aqua><underlined>[Open Portal]</underlined></aqua>"
                                    + "</hover></click>"
                    );
                })
        );
    }

    private void status(Player player) {
        plugin.linkedPlayer(player.getUniqueId()).whenComplete((linked, failure) ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (failure != null) {
                        player.sendRichMessage("<red>" + safe(plugin.userMessage(failure)) + "</red>");
                    } else if (linked.isEmpty()) {
                        player.sendRichMessage(
                                "<yellow>Not linked. Run <white>/moonsama link</white>.</yellow>"
                        );
                    } else {
                        player.sendRichMessage(
                                "<green>Linked to Portal as <white>"
                                        + safe(linked.get().gamerTag())
                                        + "</white>.</green>"
                        );
                    }
                })
        );
    }

    private void holdings(Player player) {
        player.sendRichMessage("<yellow>Checking Portal holdings…</yellow>");
        plugin.holdings(player.getUniqueId()).whenComplete((holdings, failure) ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (failure != null) {
                        player.sendRichMessage("<red>" + safe(plugin.userMessage(failure)) + "</red>");
                        return;
                    }
                    if (holdings.isEmpty()) {
                        player.sendRichMessage("<gray>No holdings are visible to this app.</gray>");
                        return;
                    }
                    player.sendRichMessage("<green>Portal holdings:</green>");
                    holdings.forEach(holding -> player.sendRichMessage(
                            "<gray>• <white>" + safe(holding.collection())
                                    + "</white> #" + safe(holding.tokenId())
                                    + ": <aqua>" + safe(holding.balance()) + "</aqua></gray>"
                    ));
                })
        );
    }

    @Override
    public List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            String @NotNull [] args
    ) {
        if (args.length != 1) {
            if (args.length == 2 && "admin".equalsIgnoreCase(args[0])
                    && "status".startsWith(args[1].toLowerCase(Locale.ROOT))) {
                return List.of("status");
            }
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return SUBCOMMANDS.stream().filter(value -> value.startsWith(prefix)).toList();
    }

    private static String safe(String value) {
        if (value == null) {
            return "unknown";
        }
        return value.replace("<", "").replace(">", "");
    }
}
