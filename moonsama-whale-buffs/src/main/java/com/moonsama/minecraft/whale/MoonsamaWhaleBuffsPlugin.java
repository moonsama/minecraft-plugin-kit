package com.moonsama.minecraft.whale;

import com.moonsama.minecraft.api.MoonsamaService;
import com.moonsama.minecraft.api.event.PortalHoldingsLoadedEvent;
import com.moonsama.minecraft.api.event.PortalPlayerErasedEvent;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Moon Power buffs for linked holders. See config.yml and README.md. */
public final class MoonsamaWhaleBuffsPlugin extends JavaPlugin implements Listener, TabCompleter {
    private MoonsamaService moonsama;
    private WhaleBuffs buffs;
    private SkinBridge skins;

    @Override
    public void onEnable() {
        moonsama = getServer().getServicesManager().load(MoonsamaService.class);
        if (moonsama == null) {
            throw new IllegalStateException("MoonsamaCore service is unavailable");
        }
        saveDefaultConfig();
        var command = getCommand("whalebuffs");
        if (command != null) {
            command.setTabCompleter(this);
        }
        start();
    }

    private void start() {
        WhaleConfig config;
        try {
            config = WhaleConfig.from(getConfig());
        } catch (IllegalArgumentException e) {
            getLogger().severe("config.yml is invalid: " + e.getMessage() + " — whale buffs stay OFF.");
            return;
        }
        buffs = new WhaleBuffs(this, moonsama, config);
        if (!config.enabled()) {
            getLogger().info("Whale buffs are disabled (enabled: false).");
            return;
        }
        if (config.requireEntitledSkin()) {
            Plugin skinsPlugin = getServer().getPluginManager().getPlugin("MoonsamaSkins");
            skins = skinsPlugin != null && skinsPlugin.isEnabled() ? SkinBridge.create(getServer(), buffs).orElse(null) : null;
            if (skins == null) {
                getLogger().warning("skins.require-entitled-skin is on but MoonsamaSkins is not available: "
                        + "nobody is entitled, so no buffs apply. Install MoonsamaSkins or set it to false.");
            } else {
                buffs.skins(skins);
                getServer().getPluginManager().registerEvents(skins, this);
            }
        }
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(buffs, this);
        getServer().getServicesManager().register(WhaleBuffs.class, buffs, this, ServicePriority.Normal);
        buffs.start();
        getLogger().info("Whale buffs enabled: " + config.powerPerToken() + " power per token, "
                + config.healthPerPower() + " HP and " + (config.damagePerPower() * 100) + "% damage per power");
    }

    private void stop() {
        HandlerList.unregisterAll((Listener) this);
        if (buffs != null) {
            HandlerList.unregisterAll(buffs);
            buffs.stop();
        }
        if (skins != null) {
            HandlerList.unregisterAll(skins);
            skins = null;
        }
        getServer().getServicesManager().unregisterAll(this);
    }

    @Override
    public void onDisable() {
        stop();
    }

    public Optional<WhaleBuffs> buffs() {
        return Optional.ofNullable(buffs);
    }

    // ---------------------------------------------------------------- events

    @EventHandler
    public void onHoldingsLoaded(PortalHoldingsLoadedEvent event) {
        Player player = getServer().getPlayer(event.player().mojangUuid());
        if (player != null) {
            buffs.setHoldings(player, event.holdings());
        }
    }

    @EventHandler
    public void onErased(PortalPlayerErasedEvent event) {
        Player player = getServer().getPlayer(event.mojangUuid());
        if (player != null) {
            buffs.setHoldings(player, List.of());
        }
    }

    // -------------------------------------------------------------- commands

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String @NotNull [] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                stop();
                reloadConfig();
                start();
                sender.sendMessage("Whale buffs reloaded" + (buffs != null && buffs.config().enabled() ? "." : " (disabled)."));
            }
            case "status" -> {
                if (buffs == null) {
                    sender.sendMessage("Config invalid; see console.");
                    return true;
                }
                WhaleConfig config = buffs.config();
                sender.sendRichMessage("<gold>Whale buffs:</gold> " + (config.enabled() ? "<green>ON</green>" : "<red>OFF</red>")
                        + " <gray>entitled skin required=" + config.requireEntitledSkin()
                        + (config.requireEntitledSkin() ? " (" + (skins != null ? "MoonsamaSkins linked" : "MoonsamaSkins MISSING") + ")" : "")
                        + ", scepter at " + config.whaleMode().scepterMinPower() + " power</gray>");
                Player target = args.length >= 2 ? getServer().getPlayer(args[1])
                        : sender instanceof Player self ? self : null;
                if (target != null) {
                    describe(sender, target);
                } else if (args.length >= 2) {
                    sender.sendRichMessage("<red>Player not found.</red>");
                }
            }
            default -> sender.sendRichMessage("<red>Usage: /" + label + " [status [player]|reload]</red>");
        }
        return true;
    }

    private void describe(CommandSender sender, Player target) {
        Optional<WhaleBuffs.Tracker> tracker = buffs.tracker(target.getUniqueId());
        if (tracker.isEmpty()) {
            sender.sendRichMessage("<gray>" + target.getName() + ": not tracked yet.</gray>");
            return;
        }
        WhaleBuffs.Tracker t = tracker.get();
        double effective = buffs.effectivePower(target);
        sender.sendRichMessage("<gold>" + target.getName() + ":</gold> <white>power " + Scepter.format(t.power())
                + "</white> <gray>(effective " + Scepter.format(effective) + ", entitled=" + t.entitled()
                + ", +" + Scepter.format(MoonPower.extraHealth(buffs.config(), effective, false)) + " HP, ×"
                + String.format(Locale.ROOT, "%.2f", MoonPower.damageFactor(buffs.config(), effective)) + " damage"
                + (t.whaleMode() ? ", WHALE MODE" : t.cooldownRemainingMs() > 0 ? ", cooldown " + (t.cooldownRemainingMs() + 999) / 1000 + " s" : "")
                + ")</gray>");
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, String @NotNull [] args) {
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            for (String option : List.of("status", "reload")) {
                if (option.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    options.add(option);
                }
            }
        } else if (args.length == 2 && "status".equalsIgnoreCase(args[0])) {
            for (Player player : getServer().getOnlinePlayers()) {
                if (player.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    options.add(player.getName());
                }
            }
        }
        return options;
    }
}
