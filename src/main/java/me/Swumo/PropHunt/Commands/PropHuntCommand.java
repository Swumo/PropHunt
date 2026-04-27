package me.Swumo.PropHunt.Commands;

import me.Swumo.PropHunt.GUI.BlockSelectGUI;
import me.Swumo.PropHunt.Game.GameManager;
import me.Swumo.PropHunt.PropHunt;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public class PropHuntCommand implements CommandExecutor, TabCompleter {
    private final PropHunt plugin;
    private final BlockSelectGUI blockGui;

    public PropHuntCommand(PropHunt plugin) {
        this.plugin = plugin;
        this.blockGui = new BlockSelectGUI(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }
        GameManager gm = plugin.getGameManager();
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "join" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage(plugin.getCommandConfigText("messages.errors.players-only", "&cPlayers only."));
                    return true;
                }
                if (!gm.isQueueOpen()) {
                    p.sendMessage(plugin.getCommandConfigText("messages.queue.not-open",
                            "&cQueue is not open. Wait for an admin to use /prophunt queue."));
                    return true;
                }
                if (gm.joinQueue(p)) {
                    p.sendMessage(
                            plugin.getCommandConfigText("messages.queue.joined", "&aYou joined the PropHunt queue."));
                    plugin.broadcastCommandConfigText(
                            "messages.queue.player-joined",
                            "&e{player} has joined the queue.",
                            java.util.Map.of("player", p.getName()));
                } else
                    p.sendMessage(plugin.getCommandConfigText("messages.queue.already-joined",
                            "&eYou are already in the queue."));
            }
            case "leave" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage(plugin.getCommandConfigText("messages.errors.players-only", "&cPlayers only."));
                    return true;
                }
                if (gm.leaveQueue(p))
                    p.sendMessage(plugin.getCommandConfigText("messages.queue.left", "&eYou left the PropHunt queue."));
                else
                    p.sendMessage(
                            plugin.getCommandConfigText("messages.queue.not-in-queue", "&cYou are not in the queue."));
            }
            case "queue" -> {
                if (!sender.hasPermission("prophunt.admin")) {
                    sender.sendMessage(
                            plugin.getCommandConfigText("messages.errors.no-permission", "&cNo permission."));
                    return true;
                }
                if (gm.getState() != GameManager.State.WAITING) {
                    sender.sendMessage(plugin.getCommandConfigText("messages.queue.closed",
                            "&cQueue is closed while a game is running."));
                    return true;
                }
                if (gm.isQueueOpen()) {
                    if (!gm.closeQueue()) {
                        sender.sendMessage(plugin.getCommandConfigText("messages.queue.unavailable",
                                "&cCould not update the queue right now."));
                        return true;
                    }
                    plugin.broadcastCommandConfigText("messages.queue.closed-manual", "&eQueue closed.");
                } else {
                    if (!gm.openQueue()) {
                        sender.sendMessage(plugin.getCommandConfigText("messages.queue.unavailable",
                                "&cCould not update the queue right now."));
                        return true;
                    }
                    plugin.broadcastCommandConfigText("messages.queue.opened",
                            "&aQueue opened. Players can now use /prophunt join.");
                }
            }
            case "start" -> {
                if (!sender.hasPermission("prophunt.admin")) {
                    sender.sendMessage(
                            plugin.getCommandConfigText("messages.errors.no-permission", "&cNo permission."));
                    return true;
                }
                gm.startGame(sender);
            }
            case "stop" -> {
                if (!sender.hasPermission("prophunt.admin")) {
                    sender.sendMessage(
                            plugin.getCommandConfigText("messages.errors.no-permission", "&cNo permission."));
                    return true;
                }
                gm.forceStop();
                sender.sendMessage(plugin.getCommandConfigText("messages.command.stopped", "&ePropHunt stopped."));
            }
            case "reload" -> {
                if (!sender.hasPermission("prophunt.admin")) {
                    sender.sendMessage(
                            plugin.getCommandConfigText("messages.errors.no-permission", "&cNo permission."));
                    return true;
                }
                plugin.reloadPluginConfig();
                sender.sendMessage(
                        plugin.getCommandConfigText("messages.command.reload-complete", "&aPropHunt config reloaded."));
            }
            case "pick" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage(plugin.getCommandConfigText("messages.errors.players-only", "&cPlayers only."));
                    return true;
                }
                GameManager.State s = gm.getState();
                if (s != GameManager.State.HIDING_PHASE && s != GameManager.State.SEEKING_PHASE) {
                    p.sendMessage(plugin.getCommandConfigText("messages.command.no-active-game", "&cNo active game."));
                    return true;
                }
                if (!gm.isHider(p)) {
                    p.sendMessage(
                            plugin.getCommandConfigText("messages.command.not-a-hider", "&cYou are not a hider."));
                    return true;
                }
                p.sendMessage(plugin.getCommandConfigText("messages.command.changing-disguise",
                        "&eChanging disguise block..."));
                blockGui.open(p);
            }
            case "status" -> {
                sender.sendMessage(plugin.getCommandConfigText("messages.command.status-state", "&6State: {state}",
                        java.util.Map.of("state", gm.getState().name())));
                sender.sendMessage(plugin.getCommandConfigText("messages.command.status-hiders", "&aHiders: {count}",
                        java.util.Map.of("count", gm.getHiders().size())));
                sender.sendMessage(plugin.getCommandConfigText("messages.command.status-seekers", "&cSeekers: {count}",
                        java.util.Map.of("count", gm.getSeekers().size())));
                sender.sendMessage(plugin.getCommandConfigText("messages.command.status-queue",
                        "&eQueue: {count} ({open})",
                        java.util.Map.of("count", gm.getQueueSize(), "open", gm.isQueueOpen() ? "open" : "closed")));
            }
            case "arena" -> {
                if (!sender.hasPermission("prophunt.admin")) {
                    sender.sendMessage(
                            plugin.getCommandConfigText("messages.errors.no-permission", "&cNo permission."));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(plugin.getCommandConfigText("messages.command.arena-usage",
                            "&eUsage: /prophunt arena <create|remove|pos1|pos2|addhiderspawn|addseekerspawn|list|info> ..."));
                    return true;
                }
                String sub = args[1].toLowerCase(Locale.ROOT);
                switch (sub) {
                    case "create" -> {
                        if (args.length < 3) {
                            sender.sendMessage(plugin.getCommandConfigText("messages.command.arena-create-usage",
                                    "&cUsage: /prophunt arena create <name>"));
                            return true;
                        }
                        sender.sendMessage(plugin.applyCommandPrefix(gm.createArena(args[2])));
                    }
                    case "list" -> {
                        List<String> arenas = gm.getArenaNames();
                        if (arenas.isEmpty())
                            sender.sendMessage(plugin.getCommandConfigText("messages.command.no-arenas-configured",
                                    "&cNo arenas configured."));
                        else
                            sender.sendMessage(plugin.getCommandConfigText("messages.command.arenas-list",
                                    "&6Arenas: {arenas}", java.util.Map.of("arenas", String.join(", ", arenas))));
                    }
                    case "info" -> {
                        if (args.length < 3) {
                            sender.sendMessage(plugin.getCommandConfigText("messages.command.arena-info-usage",
                                    "&cUsage: /prophunt arena info <name>"));
                            return true;
                        }
                        sender.sendMessage(plugin.applyCommandPrefix(gm.arenaInfo(args[2])));
                    }
                    case "remove" -> {
                        if (args.length < 3) {
                            sender.sendMessage(plugin.getCommandConfigText("messages.command.arena-remove-usage",
                                    "&cUsage: /prophunt arena remove <name>"));
                            return true;
                        }
                        sender.sendMessage(plugin.applyCommandPrefix(gm.removeArena(args[2])));
                    }
                    case "pos1" -> {
                        if (!(sender instanceof Player p)) {
                            sender.sendMessage(
                                    plugin.getCommandConfigText("messages.errors.players-only", "&cPlayers only."));
                            return true;
                        }
                        if (args.length < 3) {
                            sender.sendMessage(plugin.getCommandConfigText("messages.command.arena-pos1-usage",
                                    "&cUsage: /prophunt arena pos1 <name>"));
                            return true;
                        }
                        sender.sendMessage(plugin.applyCommandPrefix(gm.setArenaPos1(p, args[2])));
                    }
                    case "pos2" -> {
                        if (!(sender instanceof Player p)) {
                            sender.sendMessage(
                                    plugin.getCommandConfigText("messages.errors.players-only", "&cPlayers only."));
                            return true;
                        }
                        if (args.length < 3) {
                            sender.sendMessage(plugin.getCommandConfigText("messages.command.arena-pos2-usage",
                                    "&cUsage: /prophunt arena pos2 <name>"));
                            return true;
                        }
                        sender.sendMessage(plugin.applyCommandPrefix(gm.setArenaPos2(p, args[2])));
                    }
                    case "addhiderspawn" -> {
                        if (!(sender instanceof Player p)) {
                            sender.sendMessage(
                                    plugin.getCommandConfigText("messages.errors.players-only", "&cPlayers only."));
                            return true;
                        }
                        if (args.length < 3) {
                            sender.sendMessage(plugin.getCommandConfigText("messages.command.arena-addhiderspawn-usage",
                                    "&cUsage: /prophunt arena addhiderspawn <name>"));
                            return true;
                        }
                        sender.sendMessage(plugin.applyCommandPrefix(gm.addHiderSpawn(p, args[2])));
                    }
                    case "addseekerspawn" -> {
                        if (!(sender instanceof Player p)) {
                            sender.sendMessage(
                                    plugin.getCommandConfigText("messages.errors.players-only", "&cPlayers only."));
                            return true;
                        }
                        if (args.length < 3) {
                            sender.sendMessage(
                                    plugin.getCommandConfigText("messages.command.arena-addseekerspawn-usage",
                                            "&cUsage: /prophunt arena addseekerspawn <name>"));
                            return true;
                        }
                        sender.sendMessage(plugin.applyCommandPrefix(gm.addSeekerSpawn(p, args[2])));
                    }
                    default ->
                        sender.sendMessage(plugin.getCommandConfigText("messages.command.unknown-arena-subcommand",
                                "&cUnknown arena subcommand."));
                }
            }
            default -> sendHelp(sender);
        }
        return true;
    }

    private void sendHelp(CommandSender sender) {
        boolean isAdmin = sender.hasPermission("prophunt.admin");

        List<String> playerFallback = Arrays.asList(
                "&6=== PropHunt Commands ===",
                "&e/prophunt join &f- Join the next game queue",
                "&e/prophunt leave &f- Leave the next game queue",
                "&e/prophunt pick  &f- Change your auto-assigned disguise block");

        List<String> adminFallback = new ArrayList<>(playerFallback);
        adminFallback.addAll(Arrays.asList(
                "&e/prophunt queue &f- Open the join queue",
                "&e/prophunt start &f- Start the game",
                "&e/prophunt stop  &f- Stop the game",
                "&e/prophunt reload &f- Reload config and live settings",
                "&e/prophunt status &f- Show game status",
                "&e/prophunt arena create <name> &f- Create arena",
                "&e/prophunt arena remove <name> &f- Remove arena",
                "&e/prophunt arena pos1/pos2 <name> &f- Set arena cuboid",
                "&e/prophunt arena addhiderspawn <name> &f- Add hider spawn",
                "&e/prophunt arena addseekerspawn <name> &f- Add seeker spawn",
                "&e/prophunt arena list|info <name> &f- Inspect arenas"));

        String helpPath = isAdmin ? "messages.command.help-admin" : "messages.command.help-player";
        List<String> fallback = isAdmin ? adminFallback : playerFallback;

        for (String line : plugin.getConfigTextList(helpPath, fallback)) {
            sender.sendMessage(plugin.applyCommandPrefix(line));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        boolean isAdmin = sender.hasPermission("prophunt.admin");

        if (args.length == 1) {
            List<String> suggestions = new ArrayList<>(Arrays.asList("join", "leave", "pick"));
            if (isAdmin)
                suggestions.addAll(Arrays.asList("queue", "start", "stop", "reload", "arena", "status"));
            return filterByPrefix(suggestions, args[0]);
        }

        if (args.length == 2 && "arena".equalsIgnoreCase(args[0])) {
            if (!isAdmin)
                return Collections.emptyList();
            return filterByPrefix(
                    Arrays.asList("create", "remove", "pos1", "pos2", "addhiderspawn", "addseekerspawn", "list",
                            "info"),
                    args[1]);
        }
        if (args.length == 3 && "arena".equalsIgnoreCase(args[0])) {
            if (!isAdmin)
                return Collections.emptyList();
            String sub = args[1].toLowerCase(Locale.ROOT);
            if (Arrays.asList("remove", "pos1", "pos2", "addhiderspawn", "addseekerspawn", "info").contains(sub)) {
                return filterByPrefix(gmArenaNames(), args[2]);
            }
        }
        return Collections.emptyList();
    }

    private List<String> filterByPrefix(List<String> options, String typed) {
        String query = typed == null ? "" : typed.toLowerCase(Locale.ROOT);
        return options.stream()
                .filter(option -> option.toLowerCase(Locale.ROOT).startsWith(query))
                .collect(Collectors.toList());
    }

    private List<String> gmArenaNames() {
        return plugin.getGameManager().getArenaNames().stream().sorted().collect(Collectors.toList());
    }
}
