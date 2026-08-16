package me.swumo.prophunt.utils;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.title.TitlePart;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class GameMessageUtils {

    private static final Title.Times NO_FADE_TITLE_TIMES = Title.Times.times(
            Duration.ZERO,
            Duration.ofMillis(3500),
            Duration.ZERO);

    private GameMessageUtils() {
        throw new IllegalStateException("This is a utility class.");
    }

    public static void sendChat(Collection<UUID> recipients, String message) {
        var component = MiniMessage.miniMessage().deserialize(message);
        for (UUID recipientId : recipients) {
            Player recipient = Bukkit.getPlayer(recipientId);
            if (recipient == null || !recipient.isOnline())
                continue;

            recipient.sendMessage(component);
        }
    }

    public static void sendTitle(Collection<UUID> recipients, String title, String subtitle) {
        MiniMessage mini = MiniMessage.miniMessage();
        for (UUID recipientId : recipients) {
            Player recipient = Bukkit.getPlayer(recipientId);
            if (recipient == null || !recipient.isOnline())
                continue;

            recipient.showTitle(Title.title(
                    mini.deserialize(title),
                    mini.deserialize(subtitle),
                    NO_FADE_TITLE_TIMES));
        }
    }

    public static void updateTitle(Collection<UUID> recipients, String title, String subtitle) {
        MiniMessage mini = MiniMessage.miniMessage();
        for (UUID recipientId : recipients) {
            Player recipient = Bukkit.getPlayer(recipientId);
            if (recipient == null || !recipient.isOnline())
                continue;

            recipient.sendTitlePart(TitlePart.TIMES, NO_FADE_TITLE_TIMES);
            recipient.sendTitlePart(TitlePart.TITLE, mini.deserialize(title));
            recipient.sendTitlePart(TitlePart.SUBTITLE, mini.deserialize(subtitle));
        }
    }

    public static Set<UUID> matchRecipients(Collection<UUID> hiders, Collection<UUID> seekers) {
        Set<UUID> recipients = new HashSet<>();
        recipients.addAll(hiders);
        recipients.addAll(seekers);
        return recipients;
    }

    public static Set<UUID> matchAndAdminRecipients(Collection<UUID> hiders, Collection<UUID> seekers,
            String adminPermission) {
        Set<UUID> recipients = matchRecipients(hiders, seekers);
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!online.hasPermission(adminPermission))
                continue;

            recipients.add(online.getUniqueId());
        }
        return recipients;
    }

    public static void sendPrefixedChat(Collection<UUID> recipients, String prefix, String message) {
        sendChat(recipients, prefix + message);
    }

    public static void sendTitle(Player player, String title, String subtitle) {
        if (player == null)
            return;

        sendTitle(Set.of(player.getUniqueId()), title, subtitle);
    }

    public static void updateTitle(Player player, String title, String subtitle) {
        if (player == null)
            return;

        updateTitle(Set.of(player.getUniqueId()), title, subtitle);
    }
}
