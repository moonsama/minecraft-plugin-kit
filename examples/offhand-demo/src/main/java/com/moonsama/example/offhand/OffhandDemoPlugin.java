package com.moonsama.example.offhand;

import com.moonsama.minecraft.api.AssetHolding;
import com.moonsama.minecraft.api.EconomyLine;
import com.moonsama.minecraft.api.EconomyOperation;
import com.moonsama.minecraft.api.EconomyRequest;
import com.moonsama.minecraft.api.MoonsamaService;
import com.moonsama.minecraft.api.event.EconomyOperationEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

public final class OffhandDemoPlugin extends JavaPlugin implements Listener {
    private MoonsamaService moonsama;
    private NamespacedKey purchaseKey;

    @Override
    public void onEnable() {
        moonsama = getServer().getServicesManager().load(MoonsamaService.class);
        if (moonsama == null) {
            throw new IllegalStateException("MoonsamaCore service is unavailable");
        }
        purchaseKey = new NamespacedKey(this, "purchase_id");
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            String @NotNull [] args
    ) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used by a player.");
            return true;
        }

        if ("buyrelic".equalsIgnoreCase(command.getName())) {
            buyRelic(player);
            return true;
        }

        player.sendRichMessage("<yellow>Checking your sandbox entitlement…</yellow>");
        moonsama.holdings(player.getUniqueId()).whenComplete((holdings, failure) ->
                getServer().getScheduler().runTask(this, () -> {
                    if (failure != null) {
                        player.sendRichMessage(
                                "<red>Could not check holdings. Link with /moonsama link first.</red>"
                        );
                        return;
                    }
                    if (!isEntitled(holdings)) {
                        player.sendRichMessage(
                                "<red>You need sandbox-items token 1 to use this demo.</red>"
                        );
                        return;
                    }
                    player.getInventory().setItemInOffHand(demoOffhand(null));
                    player.sendRichMessage(
                            "<green>Sandbox entitlement verified. Check your offhand.</green>"
                    );
                })
        );
        return true;
    }

    private void buyRelic(Player player) {
        String businessKey = "relic:" + player.getUniqueId() + ":" + UUID.randomUUID();
        player.sendRichMessage("<yellow>Submitting a 10 sandbox-gold purchase…</yellow>");
        moonsama.spend(this, new EconomyRequest(
                businessKey,
                List.of(new EconomyLine(
                        player.getUniqueId(),
                        "sandbox-gold",
                        "0",
                        "10"
                )),
                "Portal Relic demo purchase",
                businessKey
        )).whenComplete((operation, failure) -> {
            getServer().getScheduler().runTask(this, () -> {
                if (failure != null) {
                        player.sendRichMessage(
                                "<red>Purchase could not be submitted: "
                                        + safe(failure.getMessage()) + "</red>"
                        );
                    return;
                }
                remember(operation, player.getUniqueId());
                if (operation.state() == EconomyOperation.State.SUCCEEDED) {
                    fulfill(operation, player.getUniqueId());
                } else {
                    player.sendRichMessage(
                            "<yellow>Purchase state: "
                                    + operation.state()
                                    + ". MoonsamaCore will recover it safely.</yellow>"
                    );
                }
            });
        });
    }

    @EventHandler
    public void onEconomyOperation(EconomyOperationEvent event) {
        EconomyOperation operation = event.operation();
        if (!getName().equals(operation.ownerPlugin())
                || !operation.businessKey().startsWith("relic:")
                || operation.state() != EconomyOperation.State.SUCCEEDED
                || operation.receipt() == null
                || operation.receipt().items().isEmpty()
                || operation.receipt().items().getFirst().mojangUuid() == null) {
            return;
        }
        UUID playerId = operation.receipt().items().getFirst().mojangUuid();
        remember(operation, playerId);
        fulfill(operation, playerId);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        var operations = getConfig().getConfigurationSection("operations");
        if (operations == null) {
            return;
        }
        for (String operationId : operations.getKeys(false)) {
            String path = "operations." + operationId;
            if (event.getPlayer().getUniqueId().toString()
                    .equals(getConfig().getString(path + ".player"))
                    && getConfig().getBoolean(path + ".succeeded")
                    && !getConfig().getBoolean(path + ".fulfilled")) {
                grant(event.getPlayer(), operationId);
            }
        }
    }

    private synchronized void remember(EconomyOperation operation, UUID playerId) {
        String path = "operations." + operation.operationId();
        getConfig().set(path + ".player", playerId.toString());
        getConfig().set(
                path + ".succeeded",
                operation.state() == EconomyOperation.State.SUCCEEDED
        );
        if (!getConfig().contains(path + ".fulfilled")) {
            getConfig().set(path + ".fulfilled", false);
        }
        saveConfig();
    }

    private void fulfill(EconomyOperation operation, UUID playerId) {
        getServer().getScheduler().runTask(this, () -> {
            Player player = getServer().getPlayer(playerId);
            if (player == null) {
                return;
            }
            grant(player, operation.operationId().toString());
        });
    }

    private synchronized void grant(Player player, String operationId) {
        String path = "operations." + operationId;
        if (getConfig().getBoolean(path + ".fulfilled")
                || hasPurchaseItem(player, operationId)) {
            getConfig().set(path + ".fulfilled", true);
            saveConfig();
            return;
        }
        var leftovers = player.getInventory().addItem(demoOffhand(operationId));
        if (!leftovers.isEmpty()) {
            player.sendRichMessage(
                    "<yellow>Your paid relic is pending. Free one inventory slot and rejoin.</yellow>"
            );
            return;
        }
        getConfig().set(path + ".fulfilled", true);
        saveConfig();
        player.sendRichMessage(
                "<green>Purchase confirmed. Your Portal Relic was delivered.</green>"
        );
    }

    private boolean hasPurchaseItem(Player player, String operationId) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || !item.hasItemMeta()) {
                continue;
            }
            String tagged = item.getItemMeta().getPersistentDataContainer()
                    .get(purchaseKey, PersistentDataType.STRING);
            if (operationId.equals(tagged)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isEntitled(List<AssetHolding> holdings) {
        return holdings.stream().anyMatch(holding ->
                "sandbox-items".equals(holding.collection())
                        && "1".equals(holding.tokenId())
                        && holding.hasPositiveBalance()
        );
    }

    private ItemStack demoOffhand(String operationId) {
        ItemStack item = new ItemStack(Material.FEATHER);
        var meta = item.getItemMeta();
        meta.displayName(Component.text("Moonsama Portal Relic", NamedTextColor.AQUA));
        var modelData = meta.getCustomModelDataComponent();
        // Matches the 9001 threshold in resourcepack/src/assets/minecraft/items/feather.json;
        // 1–2 are legacy Moonsama off-hands.
        modelData.setFloats(List.of(9001.0F));
        meta.setCustomModelDataComponent(modelData);
        if (operationId != null) {
            meta.getPersistentDataContainer().set(
                    purchaseKey,
                    PersistentDataType.STRING,
                    operationId
            );
        }
        item.setItemMeta(meta);
        return item;
    }

    private static String safe(String value) {
        if (value == null) {
            return "unknown error";
        }
        return value.replace("<", "").replace(">", "");
    }
}
