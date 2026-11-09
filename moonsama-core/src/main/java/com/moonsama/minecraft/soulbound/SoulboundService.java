package com.moonsama.minecraft.soulbound;

import com.moonsama.minecraft.api.SoulboundItems;
import com.moonsama.minecraft.api.event.SoulboundItemRemovedEvent;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Stores the owner tag and movement rule on bound items; {@link SoulboundGuard} enforces them. */
public final class SoulboundService implements SoulboundItems {
    private final Plugin plugin;
    private final OwnerTags tags;
    private final NamespacedKey ownerKey;
    private final NamespacedKey movementKey;
    /** Owner tag of every online player, to name the owner of a deleted item. */
    private final Map<String, UUID> online = new HashMap<>();

    private SoulboundService(Plugin plugin, Path keyFile) {
        this.plugin = plugin;
        this.tags = OwnerTags.load(keyFile);
        this.ownerKey = new NamespacedKey(plugin, "soulbound_owner");
        this.movementKey = new NamespacedKey(plugin, "soulbound_movement");
    }

    /** Creates the service with the secret in {@code keyFile} and starts enforcing it. */
    public static SoulboundService enable(Plugin plugin, Path keyFile) {
        SoulboundService service = new SoulboundService(plugin, keyFile);
        plugin.getServer().getPluginManager().registerEvents(new SoulboundGuard(plugin, service), plugin);
        plugin.getServer().getOnlinePlayers().forEach(service::track);
        return service;
    }

    @Override
    public ItemStack bind(ItemStack item, Player owner, Movement movement) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(movement, "movement");
        if (item == null || item.isEmpty()) {
            throw new IllegalArgumentException("Only real items can be bound");
        }
        String tag = tags.tagOf(owner.getUniqueId());
        item.editMeta(meta -> {
            meta.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, tag);
            meta.getPersistentDataContainer().set(movementKey, PersistentDataType.STRING,
                    movement.name().toLowerCase(Locale.ROOT));
        });
        return item;
    }

    @Override
    public boolean isBound(ItemStack item) {
        return ownerTag(item).isPresent();
    }

    @Override
    public boolean isBoundTo(ItemStack item, Player player) {
        return ownerTag(item).filter(tags.tagOf(player.getUniqueId())::equals).isPresent();
    }

    /** Bound to somebody other than {@code holder}. */
    boolean isForeign(ItemStack item, Player holder) {
        Optional<String> tag = ownerTag(item);
        return tag.isPresent() && !tag.get().equals(tags.tagOf(holder.getUniqueId()));
    }

    SoulboundRules.Kind kind(ItemStack item) {
        if (!isBound(item)) {
            return SoulboundRules.Kind.NONE;
        }
        String movement = item.getPersistentDataContainer().get(movementKey, PersistentDataType.STRING);
        // Anything unreadable stays put: LOCKED is the safe default.
        return Movement.FREE.name().equalsIgnoreCase(movement) ? SoulboundRules.Kind.FREE : SoulboundRules.Kind.LOCKED;
    }

    void track(Player player) {
        online.put(tags.tagOf(player.getUniqueId()), player.getUniqueId());
    }

    void untrack(Player player) {
        online.remove(tags.tagOf(player.getUniqueId()));
        tags.forget(player.getUniqueId());
    }

    /** Announces that {@code item} was deleted so its plugin can rebuild it for the owner. */
    void removed(ItemStack item, SoulboundItemRemovedEvent.Reason reason) {
        UUID owner = ownerTag(item).map(online::get).orElse(null);
        plugin.getServer().getPluginManager().callEvent(new SoulboundItemRemovedEvent(owner, item, reason));
    }

    private Optional<String> ownerTag(ItemStack item) {
        if (item == null || item.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(item.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING));
    }
}
