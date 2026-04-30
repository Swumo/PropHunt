package me.swumo.prophunt.utils;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class GameMessageUtils {

    private GameMessageUtils() {
        throw new IllegalStateException("This is a utility class.");
    }

    public static void sendChat(Collection<UUID> recipients, String message) {
        for (UUID recipientId : recipients) {
            Player recipient = Bukkit.getPlayer(recipientId);
            if (recipient == null || !recipient.isOnline()) continue;

            recipient.sendMessage(message);
        }
    }

    public static void sendTitle(Collection<UUID> recipients, String title, String subtitle) {
        for (UUID recipientId : recipients) {
            Player recipient = Bukkit.getPlayer(recipientId);
            if (recipient == null || !recipient.isOnline()) continue;

            recipient.showTitle(net.kyori.adventure.title.Title.title(
                    LegacyComponentSerializer.legacySection().deserialize(title),
                    LegacyComponentSerializer.legacySection().deserialize(subtitle)
            ));
        }
    }

    public static Set<UUID> matchRecipients(Collection<UUID> hiders, Collection<UUID> seekers) {
        Set<UUID> recipients = new HashSet<>();
        recipients.addAll(hiders);
        recipients.addAll(seekers);
        return recipients;
    }

    public static Set<UUID> matchAndAdminRecipients(Collection<UUID> hiders, Collection<UUID> seekers, String adminPermission) {
        Set<UUID> recipients = matchRecipients(hiders, seekers);
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!online.hasPermission(adminPermission)) continue;

            recipients.add(online.getUniqueId());
        }
        return recipients;
    }

    public static void sendPrefixedChat(Collection<UUID> recipients, String prefix, String message) {
        sendChat(recipients, prefix + message);
    }

    public static void sendTitle(Player player, String title, String subtitle) {
        if (player == null) return;

        sendTitle(Set.of(player.getUniqueId()), title, subtitle);
    }
}
