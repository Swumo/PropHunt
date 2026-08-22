package me.swumo.prophunt;

import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;

import com.github.retrooper.packetevents.manager.protocol.ProtocolManager;
import lombok.Getter;
import me.swumo.prophunt.commands.PropHuntCommand;
import me.swumo.prophunt.game.GameManager;
import me.swumo.prophunt.listeners.GameListeners;
import me.swumo.prophunt.platform.PlatformScheduler;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.incendo.cloud.annotations.AnnotationParser;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.minecraft.extras.MinecraftHelp;
import org.incendo.cloud.paper.PaperCommandManager;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.util.TimeStampMode;

import xyz.xenondevs.invui.InvUI;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class PropHunt extends JavaPlugin {

    @Getter private static PropHunt instance;
    @Getter private GameManager gameManager;
    @Getter private PlatformScheduler platformScheduler;
    @Getter public ProtocolManager protocolManager;
    private File arenaConfigFile;
    private FileConfiguration arenaConfig;

    @Override
    public void onEnable() {
        instance = this;
        PacketEvents.getAPI().init();
        protocolManager = PacketEvents.getAPI().getProtocolManager();
        saveDefaultConfig();
        mergeMissingConfigDefaults();
        setupArenaConfig();
        platformScheduler = new PlatformScheduler(this);
        getLogger().info("Detected platform: " + (platformScheduler.isFolia() ? "Folia" : "Paper"));
        InvUI.getInstance().setPlugin(this);
        gameManager = new GameManager(this);
        getServer().getPluginManager().registerEvents(new GameListeners(this), this);
        registerCommands();
        getLogger().info("PropHunt enabled.");
    }

    @Override
    public void onLoad() {
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
        PacketEvents.getAPI().getSettings().checkForUpdates(true).debug(false).timeStampMode(TimeStampMode.MILLIS);
        PacketEvents.getAPI().load();
    }

    private void registerCommands() {
        PaperCommandManager<CommandSourceStack> commandManager = PaperCommandManager.builder()
            .executionCoordinator(ExecutionCoordinator.simpleCoordinator())
            .buildOnEnable(this);
        MinecraftHelp<CommandSourceStack> minecraftHelp = MinecraftHelp.<CommandSourceStack>builder()
            .commandManager(commandManager)
            .audienceProvider(stack -> stack.getSender())
            .commandPrefix("/prophunt help")
            .build();
        AnnotationParser<CommandSourceStack> parser = new AnnotationParser<>(commandManager, CommandSourceStack.class);
        parser.parse(new PropHuntCommand(this, minecraftHelp));
    }

    @Override
    public void onDisable() {
        if (gameManager != null)
            gameManager.forceStop();
        PacketEvents.getAPI().terminate();
        getLogger().info("PropHunt disabled.");
    }

    public void reloadPluginConfig() {
        reloadConfig();
        mergeMissingConfigDefaults();
        reloadArenaConfig();
        mergeMissingArenaDefaults();
        if (gameManager != null)
            gameManager.reloadFromConfig();
    }

    public FileConfiguration getArenaConfig() {
        if (arenaConfig == null) {
            reloadArenaConfig();
        }
        return arenaConfig;
    }

    public void saveArenaConfig() {
        if (arenaConfig == null || arenaConfigFile == null)
            return;

        try {
            arenaConfig.save(arenaConfigFile);
        } catch (IOException exception) {
            getLogger().severe("Failed to save arenas.yml: " + exception.getMessage());
        }
    }

    public void reloadArenaConfig() {
        if (arenaConfigFile == null) {
            arenaConfigFile = new File(getDataFolder(), "arenas.yml");
        }

        arenaConfig = YamlConfiguration.loadConfiguration(arenaConfigFile);
        if (!arenaConfig.isConfigurationSection("arenas")) {
            arenaConfig.createSection("arenas");
        }
    }

    private void setupArenaConfig() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }

        arenaConfigFile = new File(getDataFolder(), "arenas.yml");
        if (!arenaConfigFile.exists()) {
            saveResource("arenas.yml", false);
        }

        reloadArenaConfig();
        mergeMissingArenaDefaults();
        migrateLegacyArenaConfig();
        saveArenaConfig();
    }

    private void mergeMissingConfigDefaults() {
        mergeMissingFromResource("config.yml", getConfig(), this::saveConfig);
    }

    private void mergeMissingArenaDefaults() {
        mergeMissingFromResource("arenas.yml", getArenaConfig(), this::saveArenaConfig);
    }

    private void mergeMissingFromResource(String resourceName, FileConfiguration targetConfig, Runnable saveAction) {
        if (targetConfig == null || saveAction == null)
            return;

        try (InputStream input = getResource(resourceName)) {
            if (input == null)
                return;

            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(input, StandardCharsets.UTF_8));
            boolean changed = false;
            for (String path : defaults.getKeys(true)) {
                if (defaults.isConfigurationSection(path))
                    continue;
                if (!targetConfig.isSet(path)) {
                    targetConfig.set(path, defaults.get(path));
                    changed = true;
                }
            }

            if (changed) {
                saveAction.run();
                getLogger().info("Added missing config entries to " + resourceName);
            }
        } catch (Exception exception) {
            getLogger().warning("Failed to merge missing defaults from " + resourceName + ": " + exception.getMessage());
        }
    }

    private void migrateLegacyArenaConfig() {
        if (arenaConfig == null)
            return;

        if (arenaConfig.isConfigurationSection("arenas") && !arenaConfig.getConfigurationSection("arenas").getKeys(false).isEmpty()) {
            return;
        }

        ConfigurationSection legacyArenas = getConfig().getConfigurationSection("arenas");
        if (legacyArenas == null)
            return;

        arenaConfig.set("arenas", legacyArenas.getValues(true));
        getConfig().set("arenas", null);
        saveConfig();
        getLogger().info("Migrated arena data from config.yml to arenas.yml");
    }

    public String getConfigText(String path, String fallback) {
        return getConfigText(path, fallback, Map.of());
    }

    public String getConfigText(String path, String fallback, Map<String, ?> placeholders) {
        return formatText(getConfig().getString(path, fallback), placeholders);
    }

    public String getCommandConfigText(String path, String fallback) {
        return getCommandConfigText(path, fallback, Map.of());
    }

    public String getCommandConfigText(String path, String fallback, Map<String, ?> placeholders) {
        return applyCommandPrefix(getConfigText(path, fallback, placeholders));
    }

    public String applyCommandPrefix(String message) {
        if (!useCommandPrefix())
            return message;
        return getConfigText("messages.prefix", "<gradient:#FDE68A:#F97316><bold>PropHunt</bold></gradient> <dark_gray>»</dark_gray> ") + message;
    }

    public void broadcastCommandConfigText(String path, String fallback) {
        broadcastCommandConfigText(path, fallback, Map.of());
    }

    public void broadcastCommandConfigText(String path, String fallback, Map<String, ?> placeholders) {
        broadcastCommandMessage(getConfigText(path, fallback, placeholders));
    }

    public void broadcastCommandMessage(String message) {
        String formatted = applyCommandPrefix(message);
        Component component = deserializeMiniMessage(formatted);
        for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(component);
        }
    }

    public void sendMiniMessage(CommandSender recipient, String message) {
        if (recipient != null) {
            recipient.sendMessage(deserializeMiniMessage(message));
        }
    }

    public Component deserializeMiniMessage(String message) {
        return MiniMessage.miniMessage().deserialize(message == null ? "" : message);
    }

    public List<String> getConfigTextList(String path, List<String> fallback) {
        List<String> values = getConfig().isList(path) ? getConfig().getStringList(path) : fallback;
        List<String> lines = new ArrayList<>(values.size());
        for (String value : values) {
            lines.add(formatText(value, Map.of()));
        }
        return lines;
    }

    private String formatText(String raw, Map<String, ?> placeholders) {
        String formatted = raw == null ? "" : raw;
        for (Map.Entry<String, ?> entry : placeholders.entrySet()) {
            formatted = formatted.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
        }
        return formatted;
    }

    private boolean useCommandPrefix() {
        if (getConfig().contains("messages.command.use-prefix")) {
            return getConfig().getBoolean("messages.command.use-prefix", true);
        }
        return getConfig().getBoolean("messages.use-prefix", true);
    }

}
