package me.swumo.prophunt.commands;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import me.swumo.prophunt.PropHunt;
import me.swumo.prophunt.game.GameManager;
import me.swumo.prophunt.gui.BlockSelectGUI;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.incendo.cloud.annotation.specifier.Greedy;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Permission;
import org.incendo.cloud.annotations.suggestion.Suggestions;
import org.incendo.cloud.minecraft.extras.MinecraftHelp;

import java.util.List;
import java.util.stream.Stream;

public class PropHuntCommand {

    private final PropHunt plugin;
    private final BlockSelectGUI blockGui;
    private final MinecraftHelp<CommandSourceStack> minecraftHelp;

    public PropHuntCommand(PropHunt plugin, MinecraftHelp<CommandSourceStack> minecraftHelp) {
        this.plugin = plugin;
        this.blockGui = new BlockSelectGUI(plugin);
        this.minecraftHelp = minecraftHelp;
    }

    @Command("prophunt|ph")
    @CommandDescription("Show PropHunt help.")
    public void rootHelp(CommandSourceStack stack) {
        minecraftHelp.queryCommands("", stack);
    }

    @Command("prophunt|ph help [query]")
    @CommandDescription("Show PropHunt help.")
    public void help(CommandSourceStack stack, @Argument("query") @Greedy String query) {
        minecraftHelp.queryCommands(query == null ? "" : query, stack);
    }

    @Command("prophunt|ph join")
    @CommandDescription("Join the PropHunt queue.")
    public void join(CommandSourceStack stack) {
        if (!(stack.getSender() instanceof Player p)) {
            stack.getSender().sendMessage(plugin.getCommandConfigText("messages.errors.players-only", "&cPlayers only."));
            return;
        }
        GameManager gm = plugin.getGameManager();
        if (!gm.isQueueOpen()) {
            p.sendMessage(plugin.getCommandConfigText("messages.queue.not-open",
                "&cQueue is not open. Wait for an admin to use /prophunt queue."));
            return;
        }
        if (gm.joinQueue(p)) {
            p.sendMessage(plugin.getCommandConfigText("messages.queue.joined", "&aYou joined the PropHunt queue."));
            plugin.broadcastCommandConfigText(
                "messages.queue.player-joined",
                "&e{player} has joined the queue.",
                java.util.Map.of("player", p.getName()));
        } else {
            p.sendMessage(plugin.getCommandConfigText("messages.queue.already-joined",
                "&eYou are already in the queue."));
        }
    }

    @Command("prophunt|ph leave")
    @CommandDescription("Leave the PropHunt queue.")
    public void leave(CommandSourceStack stack) {
        if (!(stack.getSender() instanceof Player p)) {
            stack.getSender().sendMessage(plugin.getCommandConfigText("messages.errors.players-only", "&cPlayers only."));
            return;
        }
        GameManager gm = plugin.getGameManager();
        if (gm.leaveQueue(p)) {
            p.sendMessage(plugin.getCommandConfigText("messages.queue.left", "&eYou left the PropHunt queue."));
        } else {
            p.sendMessage(plugin.getCommandConfigText("messages.queue.not-in-queue", "&cYou are not in the queue."));
        }
    }

    @Command("prophunt|ph queue")
    @CommandDescription("Toggle the join queue open/closed.")
    @Permission("prophunt.admin")
    public void queue(CommandSourceStack stack) {
        CommandSender sender = stack.getSender();
        GameManager gm = plugin.getGameManager();
        if (gm.getState() != GameManager.State.WAITING) {
            sender.sendMessage(plugin.getCommandConfigText("messages.queue.closed",
                "&cQueue is closed while a game is running."));
            return;
        }
        if (gm.isQueueOpen()) {
            if (!gm.closeQueue()) {
                sender.sendMessage(plugin.getCommandConfigText("messages.queue.unavailable",
                    "&cCould not update the queue right now."));
                return;
            }
            plugin.broadcastCommandConfigText("messages.queue.closed-manual", "&eQueue closed.");
        } else {
            if (!gm.openQueue()) {
                sender.sendMessage(plugin.getCommandConfigText("messages.queue.unavailable",
                    "&cCould not update the queue right now."));
                return;
            }
            plugin.broadcastCommandConfigText("messages.queue.opened",
                "&aQueue opened. Players can now use /prophunt join.");
        }
    }

    @Command("prophunt|ph start")
    @CommandDescription("Start the game.")
    @Permission("prophunt.admin")
    public void start(CommandSourceStack stack) {
        plugin.getGameManager().startGame(stack.getSender());
    }

    @Command("prophunt|ph stop")
    @CommandDescription("Stop the game.")
    @Permission("prophunt.admin")
    public void stop(CommandSourceStack stack) {
        plugin.getGameManager().forceStop();
        stack.getSender().sendMessage(plugin.getCommandConfigText("messages.command.stopped", "&ePropHunt stopped."));
    }

    @Command("prophunt|ph reload")
    @CommandDescription("Reload config and live settings.")
    @Permission("prophunt.admin")
    public void reload(CommandSourceStack stack) {
        plugin.reloadPluginConfig();
        stack.getSender().sendMessage(
            plugin.getCommandConfigText("messages.command.reload-complete", "&aPropHunt config reloaded."));
    }

    @Command("prophunt|ph pick")
    @CommandDescription("Change your auto-assigned disguise block.")
    public void pick(CommandSourceStack stack) {
        if (!(stack.getSender() instanceof Player p)) {
            stack.getSender().sendMessage(plugin.getCommandConfigText("messages.errors.players-only", "&cPlayers only."));
            return;
        }
        GameManager gm = plugin.getGameManager();
        GameManager.State s = gm.getState();
        if (s != GameManager.State.HIDING_PHASE && s != GameManager.State.SEEKING_PHASE) {
            p.sendMessage(plugin.getCommandConfigText("messages.command.no-active-game", "&cNo active game."));
            return;
        }
        if (!gm.isHider(p)) {
            p.sendMessage(plugin.getCommandConfigText("messages.command.not-a-hider", "&cYou are not a hider."));
            return;
        }
        p.sendMessage(plugin.getCommandConfigText("messages.command.changing-disguise",
            "&eChanging disguise block..."));
        blockGui.open(p);
    }

    @Command("prophunt|ph status")
    @CommandDescription("Show game status.")
    public void status(CommandSourceStack stack) {
        CommandSender sender = stack.getSender();
        GameManager gm = plugin.getGameManager();
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

    @Command("prophunt|ph arena create <name>")
    @CommandDescription("Create a new arena.")
    @Permission("prophunt.admin")
    public void arenaCreate(CommandSourceStack stack, @Argument("name") String name) {
        stack.getSender().sendMessage(plugin.applyCommandPrefix(plugin.getGameManager().createArena(name)));
    }

    @Command("prophunt|ph arena remove <name>")
    @CommandDescription("Remove an arena.")
    @Permission("prophunt.admin")
    public void arenaRemove(CommandSourceStack stack, @Argument(value = "name", suggestions = "arena_names") String name) {
        stack.getSender().sendMessage(plugin.applyCommandPrefix(plugin.getGameManager().removeArena(name)));
    }

    @Command("prophunt|ph arena list")
    @CommandDescription("List configured arenas.")
    @Permission("prophunt.admin")
    public void arenaList(CommandSourceStack stack) {
        List<String> arenas = plugin.getGameManager().getArenaNames();
        if (arenas.isEmpty()) {
            stack.getSender().sendMessage(plugin.getCommandConfigText("messages.command.no-arenas-configured",
                "&cNo arenas configured."));
        } else {
            stack.getSender().sendMessage(plugin.getCommandConfigText("messages.command.arenas-list",
                "&6Arenas: {arenas}", java.util.Map.of("arenas", String.join(", ", arenas))));
        }
    }

    @Command("prophunt|ph arena info <name>")
    @CommandDescription("Show arena info.")
    @Permission("prophunt.admin")
    public void arenaInfo(CommandSourceStack stack, @Argument(value = "name", suggestions = "arena_names") String name) {
        stack.getSender().sendMessage(plugin.applyCommandPrefix(plugin.getGameManager().arenaInfo(name)));
    }

    @Command("prophunt|ph arena pos1 <name>")
    @CommandDescription("Set the first cuboid corner of an arena.")
    @Permission("prophunt.admin")
    public void arenaPos1(CommandSourceStack stack, @Argument(value = "name", suggestions = "arena_names") String name) {
        if (!(stack.getSender() instanceof Player p)) {
            stack.getSender().sendMessage(plugin.getCommandConfigText("messages.errors.players-only", "&cPlayers only."));
            return;
        }
        p.sendMessage(plugin.applyCommandPrefix(plugin.getGameManager().setArenaPos1(p, name)));
    }

    @Command("prophunt|ph arena pos2 <name>")
    @CommandDescription("Set the second cuboid corner of an arena.")
    @Permission("prophunt.admin")
    public void arenaPos2(CommandSourceStack stack, @Argument(value = "name", suggestions = "arena_names") String name) {
        if (!(stack.getSender() instanceof Player p)) {
            stack.getSender().sendMessage(plugin.getCommandConfigText("messages.errors.players-only", "&cPlayers only."));
            return;
        }
        p.sendMessage(plugin.applyCommandPrefix(plugin.getGameManager().setArenaPos2(p, name)));
    }

    @Command("prophunt|ph arena addhiderspawn <name>")
    @CommandDescription("Add a hider spawn at your current location.")
    @Permission("prophunt.admin")
    public void arenaAddHiderSpawn(CommandSourceStack stack, @Argument(value = "name", suggestions = "arena_names") String name) {
        if (!(stack.getSender() instanceof Player p)) {
            stack.getSender().sendMessage(plugin.getCommandConfigText("messages.errors.players-only", "&cPlayers only."));
            return;
        }
        p.sendMessage(plugin.applyCommandPrefix(plugin.getGameManager().addHiderSpawn(p, name)));
    }

    @Command("prophunt|ph arena addseekerspawn <name>")
    @CommandDescription("Add a seeker spawn at your current location.")
    @Permission("prophunt.admin")
    public void arenaAddSeekerSpawn(CommandSourceStack stack, @Argument(value = "name", suggestions = "arena_names") String name) {
        if (!(stack.getSender() instanceof Player p)) {
            stack.getSender().sendMessage(plugin.getCommandConfigText("messages.errors.players-only", "&cPlayers only."));
            return;
        }
        p.sendMessage(plugin.applyCommandPrefix(plugin.getGameManager().addSeekerSpawn(p, name)));
    }

    @Suggestions("arena_names")
    public Stream<String> suggestArenaNames() {
        return plugin.getGameManager().getArenaNames().stream().sorted();
    }
}
