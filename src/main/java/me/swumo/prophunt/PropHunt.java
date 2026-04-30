package me.swumo.prophunt;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import lombok.Getter;
import me.swumo.prophunt.commands.PropHuntCommand;
import me.swumo.prophunt.game.GameManager;
import me.swumo.prophunt.listeners.GameListeners;
import me.swumo.prophunt.platform.PlatformScheduler;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.incendo.cloud.annotations.AnnotationParser;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.minecraft.extras.MinecraftHelp;
import org.incendo.cloud.paper.PaperCommandManager;
import xyz.xenondevs.invui.InvUI;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PropHunt extends JavaPlugin {
    private static final Pattern GRADIENT_PATTERN = Pattern
            .compile("<gradient:(#[A-Fa-f0-9]{6}):(#[A-Fa-f0-9]{6})>(.*?)</gradient>", Pattern.DOTALL);
    private static final Pattern HEX_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");
    @Getter private static PropHunt instance;
    @Getter private GameManager gameManager;
    @Getter private PlatformScheduler platformScheduler;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        platformScheduler = new PlatformScheduler(this);
        getLogger().info("Detected platform: " + (platformScheduler.isFolia() ? "Folia" : "Paper"));
        InvUI.getInstance().setPlugin(this);
        gameManager = new GameManager(this);
        getServer().getPluginManager().registerEvents(new GameListeners(this), this);
        registerCommands();
        getLogger().info("PropHunt enabled.");
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
        getLogger().info("PropHunt disabled.");
    }

    public void reloadPluginConfig() {
        reloadConfig();
        if (gameManager != null)
            gameManager.reloadFromConfig();
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
        return getConfigText("messages.prefix", "&6[PropHunt] &r") + message;
    }

    public void broadcastCommandConfigText(String path, String fallback) {
        broadcastCommandConfigText(path, fallback, Map.of());
    }

    public void broadcastCommandConfigText(String path, String fallback, Map<String, ?> placeholders) {
        broadcastCommandMessage(getConfigText(path, fallback, placeholders));
    }

    public void broadcastCommandMessage(String message) {
        String formatted = applyCommandPrefix(message);
        Component component = LegacyComponentSerializer.legacySection().deserialize(formatted);
        for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(component);
        }
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
        formatted = applyGradients(formatted);
        formatted = applyHexColors(formatted);
        // Convert & codes to § (section symbol) for legacy format compatibility
        return convertAmpersandToSection(formatted);
    }

    private String convertAmpersandToSection(String text) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '&' && i + 1 < text.length()) {
                char code = text.charAt(++i);
                // Valid color/format codes
                if ("0123456789abcdefklmnor".indexOf(code) >= 0) {
                    result.append('§').append(code);
                } else {
                    result.append(c).append(code);
                }
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }

    private boolean useCommandPrefix() {
        if (getConfig().contains("messages.command.use-prefix")) {
            return getConfig().getBoolean("messages.command.use-prefix", true);
        }
        return getConfig().getBoolean("messages.use-prefix", true);
    }

    private String applyGradients(String input) {
        String formatted = input;
        Matcher matcher = GRADIENT_PATTERN.matcher(formatted);
        while (matcher.find()) {
            String replacement = gradientText(matcher.group(1), matcher.group(2), matcher.group(3));
            formatted = matcher.replaceFirst(Matcher.quoteReplacement(replacement));
            matcher = GRADIENT_PATTERN.matcher(formatted);
        }
        return formatted;
    }

    private String applyHexColors(String input) {
        Matcher matcher = HEX_PATTERN.matcher(input);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(toMinecraftHex("#" + matcher.group(1))));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private String gradientText(String startHex, String endHex, String content) {
        if (content == null || content.isEmpty())
            return "";

        int[] start = rgb(startHex);
        int[] end = rgb(endHex);
        int visibleChars = countVisibleGradientChars(content);
        if (visibleChars <= 0)
            return content;

        StringBuilder builder = new StringBuilder(content.length() * 18);
        Set<Character> activeFormats = new LinkedHashSet<>();
        int visibleIndex = 0;
        for (int i = 0; i < content.length(); i++) {
            if (isFormattingCode(content, i)) {
                updateActiveFormats(activeFormats, Character.toLowerCase(content.charAt(i + 1)));
                i++;
                continue;
            }

            double ratio = visibleChars == 1 ? 0d : (double) visibleIndex / (visibleChars - 1);
            int red = interpolate(start[0], end[0], ratio);
            int green = interpolate(start[1], end[1], ratio);
            int blue = interpolate(start[2], end[2], ratio);
            builder.append(toMinecraftHex(String.format("#%02X%02X%02X", red, green, blue)));
            appendActiveFormats(builder, activeFormats);
            builder.append(content.charAt(i));
            visibleIndex++;
        }
        return builder.toString();
    }

    private int countVisibleGradientChars(String content) {
        int count = 0;
        for (int i = 0; i < content.length(); i++) {
            if (isFormattingCode(content, i)) {
                i++;
                continue;
            }
            count++;
        }
        return count;
    }

    private boolean isFormattingCode(String content, int index) {
        if (index < 0 || index + 1 >= content.length())
            return false;
        if (content.charAt(index) != '&')
            return false;
        char code = Character.toLowerCase(content.charAt(index + 1));
        return (code >= '0' && code <= '9')
                || (code >= 'a' && code <= 'f')
                || code == 'k' || code == 'l' || code == 'm' || code == 'n' || code == 'o' || code == 'r';
    }

    private void updateActiveFormats(Set<Character> activeFormats, char code) {
        if ((code >= '0' && code <= '9') || (code >= 'a' && code <= 'f') || code == 'r') {
            activeFormats.clear();
            return;
        }
        activeFormats.add(code);
    }

    private void appendActiveFormats(StringBuilder builder, Set<Character> activeFormats) {
        for (char format : activeFormats) {
            builder.append('&').append(format);
        }
    }

    private int interpolate(int start, int end, double ratio) {
        return (int) Math.round(start + ((end - start) * ratio));
    }

    private int[] rgb(String hex) {
        return new int[] {
                Integer.parseInt(hex.substring(1, 3), 16),
                Integer.parseInt(hex.substring(3, 5), 16),
                Integer.parseInt(hex.substring(5, 7), 16)
        };
    }

    private String toMinecraftHex(String hex) {
        String clean = hex.charAt(0) == '#' ? hex.substring(1) : hex;
        StringBuilder builder = new StringBuilder("§x");
        for (char c : clean.toCharArray()) {
            builder.append('§').append(c);
        }
        return builder.toString();
    }

}
