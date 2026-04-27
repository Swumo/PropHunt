package me.Swumo.PropHunt.Game;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import me.Swumo.PropHunt.PropHunt;

public class GameManager {

    public enum State {
        WAITING, HIDING_PHASE, SEEKING_PHASE, END
    }

    private final PropHunt plugin;
    private State state = State.WAITING;
    private final Map<UUID, HiderData> hiders = new HashMap<>();
    private final Set<UUID> seekers = new HashSet<>();
    private final Map<UUID, Location> lastLocations = new HashMap<>();
    private final Map<UUID, Boolean> previousCollidable = new HashMap<>();
    private final Map<UUID, Location> frozenSeekers = new HashMap<>();
    private final Set<UUID> queuedPlayers = new LinkedHashSet<>();
    private final List<Material> currentArenaBlockPool = new ArrayList<>();
    private boolean queueOpen;
    private Arena selectedArena;
    private BukkitTask tickTask;
    private BukkitTask countdownTask;
    private BukkitTask gameTimerTask;
    private int seekerGraceCountdown;
    private int gameSecondsRemaining;

    private int lockTicksRequired;
    private int hiderMaxHp;
    private int seekerGracePeriod;
    private int gameDuration;
    private int arenaScanMaxBlocks;
    private int playersPerSeeker;
    private int seekerHitCooldownTicks;
    private int freezeBlindnessBufferSeconds;
    private int countdownTitleThresholdSeconds;
    private int titleFadeInTicks;
    private int titleStayTicks;
    private int titleFadeOutTicks;
    private List<Integer> roundWarningSeconds = new ArrayList<>();
    private final List<Material> defaultHiderBlocks = new ArrayList<>();
    private static final List<Material> FALLBACK_HIDER_BLOCKS = Arrays.asList(
            Material.GRASS_BLOCK, Material.DIRT, Material.STONE, Material.COBBLESTONE,
            Material.OAK_LOG, Material.BIRCH_LOG, Material.SAND, Material.GRAVEL,
            Material.OAK_PLANKS, Material.SPRUCE_PLANKS, Material.BRICKS,
            Material.MOSSY_COBBLESTONE, Material.BOOKSHELF, Material.CRAFTING_TABLE,
            Material.CHEST, Material.BARREL, Material.HAY_BLOCK, Material.MELON,
            Material.PUMPKIN, Material.TNT, Material.CACTUS, Material.CLAY,
            Material.SMOOTH_STONE, Material.ANDESITE, Material.DIORITE, Material.GRANITE,
            Material.OAK_LEAVES, Material.SNOW_BLOCK, Material.ICE, Material.FURNACE);

    public GameManager(PropHunt plugin) {
        this.plugin = plugin;
        loadSettings();
        currentArenaBlockPool.addAll(defaultHiderBlocks);
    }

    public void reloadFromConfig() {
        int oldHiderMaxHp = hiderMaxHp;
        int oldSeekerGracePeriod = seekerGracePeriod;
        int oldGameDuration = gameDuration;

        loadSettings();
        adjustRunningMatch(oldHiderMaxHp, oldSeekerGracePeriod, oldGameDuration);

        if (selectedArena != null) {
            currentArenaBlockPool.clear();
            if (!selectedArena.cachedBlockPool.isEmpty()) {
                currentArenaBlockPool.addAll(selectedArena.cachedBlockPool);
            } else {
                currentArenaBlockPool.addAll(sampleArenaBlocks(selectedArena));
            }
            if (currentArenaBlockPool.isEmpty())
                currentArenaBlockPool.addAll(defaultHiderBlocks);
        } else if (state == State.WAITING) {
            currentArenaBlockPool.clear();
            currentArenaBlockPool.addAll(defaultHiderBlocks);
        }
    }

    private static class Arena {
        final String name;
        final String world;
        final Location pos1;
        final Location pos2;
        final List<Location> hiderSpawns;
        final List<Location> seekerSpawns;
        final List<Material> cachedBlockPool = Collections.synchronizedList(new ArrayList<>());
        boolean blockPoolGenerated = false;

        Arena(String name, String world, Location pos1, Location pos2, List<Location> hiderSpawns,
                List<Location> seekerSpawns) {
            this.name = name;
            this.world = world;
            this.pos1 = pos1;
            this.pos2 = pos2;
            this.hiderSpawns = hiderSpawns;
            this.seekerSpawns = seekerSpawns;
        }

        boolean hasCuboid() {
            return pos1 != null && pos2 != null && world != null;
        }

        boolean hasSpawns() {
            return !hiderSpawns.isEmpty() && !seekerSpawns.isEmpty();
        }
    }

    public void startGame(CommandSender sender) {
        if (state != State.WAITING) {
            startError(sender, msg("messages.game.already-running", "&cA game is already running!"));
            return;
        }
        if (!queueOpen) {
            startError(sender, msg("messages.queue.not-open", "&cQueue is not open. Use /prophunt queue first."));
            return;
        }
        cleanupQueue();
        List<Player> queuedOnlinePlayers = getQueuedOnlinePlayers();

        List<Arena> arenas = loadArenasFromConfig();
        if (arenas.isEmpty()) {
            startError(sender,
                    msg("messages.game.no-arenas", "&cNo arenas configured. Use /prophunt arena create <name>."));
            return;
        }

        boolean hasAnyCuboid = arenas.stream().anyMatch(Arena::hasCuboid);
        boolean hasAnyHiderSpawn = arenas.stream().anyMatch(a -> !a.hiderSpawns.isEmpty());
        boolean hasAnySeekerSpawn = arenas.stream().anyMatch(a -> !a.seekerSpawns.isEmpty());

        if (!hasAnyCuboid) {
            startError(sender, msg("messages.game.no-cuboids",
                    "&cNo cuboid(s) are set. Use /prophunt arena pos1 <name> and pos2."));
            return;
        }
        if (!hasAnyHiderSpawn || !hasAnySeekerSpawn) {
            startError(sender, msg("messages.game.missing-spawns",
                    "&cMissing spawn(s). Add both hider and seeker spawns to at least one arena."));
            return;
        }

        List<Arena> playableArenas = new ArrayList<>();
        for (Arena arena : arenas) {
            if (arena.hasCuboid() && arena.hasSpawns())
                playableArenas.add(arena);
        }
        if (playableArenas.isEmpty()) {
            startError(sender, msg("messages.game.no-playable-arenas",
                    "&cNo playable arena found (needs cuboid + hider spawns + seeker spawns)."));
            return;
        }

        selectedArena = playableArenas.get(ThreadLocalRandom.current().nextInt(playableArenas.size()));
        queueOpen = false;
        currentArenaBlockPool.clear();
        if (!selectedArena.cachedBlockPool.isEmpty()) {
            currentArenaBlockPool.addAll(selectedArena.cachedBlockPool);
        } else {
            currentArenaBlockPool.addAll(sampleArenaBlocks(selectedArena));
        }
        if (currentArenaBlockPool.isEmpty())
            currentArenaBlockPool.addAll(defaultHiderBlocks);

        hiders.clear();
        seekers.clear();
        lastLocations.clear();
        assignTeams(queuedOnlinePlayers);
        state = State.HIDING_PHASE;
        broadcast(msg("messages.game.round-start", "&6=== PropHunt Started on Arena: {arena} ===",
                Map.of("arena", selectedArena.name)));
        broadcast(msg("messages.game.round-start-block-pool",
                "&aHiders are assigned random map blocks from this arena."));
        broadcast(msg("messages.game.seeker-release-delay", "&cSeekers: releasing in {seconds} seconds!",
                Map.of("seconds", seekerGracePeriod)));
        broadcastTitle(
                msg("titles.round-start.title", "&6PropHunt Started"),
                msg("titles.round-start.subtitle", "&eArena: {arena} &7| Seekers release in {seconds}s",
                        Map.of("arena", selectedArena.name, "seconds", seekerGracePeriod)));
        for (UUID sid : seekers) {
            Player p = Bukkit.getPlayer(sid);
            if (p != null) {
                teleportToRandomSpawn(p, selectedArena.seekerSpawns);
                p.setGameMode(GameMode.ADVENTURE);
                freezeSeeker(p);
                sendTitle(
                        p,
                        msg("titles.seeker-role.title", "&cYou are the SEEKER"),
                        msg("titles.seeker-role.subtitle", "&eFrozen and blinded until release"));
            }
        }
        for (UUID hid : hiders.keySet()) {
            Player p = Bukkit.getPlayer(hid);
            if (p != null) {
                teleportToRandomSpawn(p, selectedArena.hiderSpawns);
                p.setGameMode(GameMode.ADVENTURE);
                p.setInvisible(true);
                assignRandomBlock(p);
                sendTitle(
                        p,
                        msg("titles.hider-role.title", "&aYou are a HIDER"),
                        msg("titles.hider-role.subtitle", "&eStand still to solidify before release"));
            }
        }

        applyArenaWorldBorders();

        // Run hider locking tick immediately, so solidifying works during hiding phase.
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::gameTick, 1L, 1L);

        seekerGraceCountdown = seekerGracePeriod;
        countdownTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            seekerGraceCountdown--;
            if (seekerGraceCountdown <= 0) {
                countdownTask.cancel();
                releaseSeekersPhase();
            } else if (seekerGraceCountdown > countdownTitleThresholdSeconds) {
                for (UUID sid : seekers) {
                    Player p = Bukkit.getPlayer(sid);
                    if (p != null) {
                        sendTitle(
                                p,
                                msg("titles.seekers-waiting.title", "&cWaiting for hiders to hide..."),
                                msg("titles.seekers-waiting.subtitle", "&7Releasing in {seconds} seconds",
                                        Map.of("seconds", seekerGraceCountdown)));
                    }
                }
            } else if (seekerGraceCountdown <= countdownTitleThresholdSeconds) {
                for (UUID sid : seekers) {
                    Player p = Bukkit.getPlayer(sid);
                    if (p != null) {
                        sendTitle(
                                p,
                                msg("titles.release-countdown.title", "&cRelease in {seconds}",
                                        Map.of("seconds", seekerGraceCountdown)),
                                msg("titles.release-countdown.subtitle", "&7Get ready"));
                    }
                }
            }
        }, 20L, 20L);
    }

    private void startError(CommandSender sender, String message) {
        if (sender != null)
            sender.sendMessage(plugin.applyCommandPrefix(message));
        else
            broadcast(message);
    }

    private void releaseSeekersPhase() {
        state = State.SEEKING_PHASE;
        messageMatchPlayers(msg("messages.game.seekers-released", "&cSeekers RELEASED! Find the props!"));
        broadcastTitle(msg("titles.seekers-released.title", "&cSeekers Released"),
                msg("titles.seekers-released.subtitle", "&eFind the props"));
        for (UUID sid : seekers) {
            Player p = Bukkit.getPlayer(sid);
            if (p != null) {
                unfreezeSeeker(p);
                p.setGameMode(GameMode.ADVENTURE);
                sendTitle(p, msg("titles.seekers-go.title", "&cGO"),
                        msg("titles.seekers-go.subtitle", "&eFind the hiders"));
            }
        }
        gameSecondsRemaining = gameDuration;
        gameTimerTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            gameSecondsRemaining -= 1;
            if (gameSecondsRemaining <= 0) {
                gameTimerTask.cancel();
                endGame(true);
            } else if (roundWarningSeconds.contains(gameSecondsRemaining)) {
                messageMatchPlayers(msg(
                        "messages.game.time-remaining",
                        "&e{time} remaining!",
                        Map.of("time", formatRemainingTime(gameSecondsRemaining), "seconds", gameSecondsRemaining)));
            }
        }, 20L, 20L);
    }

    private void gameTick() {
        if (state != State.HIDING_PHASE && state != State.SEEKING_PHASE)
            return;

        if (state == State.HIDING_PHASE) {
            for (Map.Entry<UUID, Location> entry : new HashMap<>(frozenSeekers).entrySet()) {
                Player seeker = Bukkit.getPlayer(entry.getKey());
                if (seeker == null || !seeker.isOnline())
                    continue;
                Location freezeLoc = entry.getValue();
                if (shouldReapplyFrozenView(freezeLoc, seeker.getLocation())) {
                    seeker.teleport(freezeLoc.clone());
                }
            }
        }

        for (Map.Entry<UUID, HiderData> entry : new ArrayList<>(hiders.entrySet())) {
            Player p = Bukkit.getPlayer(entry.getKey());
            HiderData data = entry.getValue();
            if (p == null || !p.isOnline())
                continue;
            if (data.getChosenBlock() == null)
                continue;
            if (data.isLocked()) {
                p.setInvisible(true);
                if (data.getPlacedBlockLocation() != null) {
                    Location anchor = data.getPlacedBlockLocation().clone().add(0.5, 0.0, 0.5);
                    anchor.setYaw(p.getLocation().getYaw());
                    anchor.setPitch(p.getLocation().getPitch());
                    if (!sameBlock(anchor, p.getLocation())) {
                        p.teleport(anchor);
                    }
                }
                continue;
            }
            p.setInvisible(true);
            data.updateMobileDisguisePosition(p);
            if (isPlayerStill(p)) {
                data.incrementStillTicks();
            } else {
                data.resetStillTicks();
            }
            if (data.getStillTicks() >= lockTicksRequired)
                lockHider(p, data);
        }
        if (state == State.SEEKING_PHASE && hiders.isEmpty())
            endGame(false);
    }

    private boolean isPlayerStill(Player p) {
        Location cur = p.getLocation();
        Location last = lastLocations.put(p.getUniqueId(), cur.clone());
        if (last == null)
            return false;
        double dx = cur.getX() - last.getX(), dy = cur.getY() - last.getY(), dz = cur.getZ() - last.getZ();
        return (dx * dx + dy * dy + dz * dz) < 0.001;
    }

    private void lockHider(Player p, HiderData data) {
        data.setLocked(true);
        data.resetStillTicks();
        p.setInvisible(true);
        data.removeDisplay();
        Location anchor = getFloorAnchorLocation(p);
        data.placeWorldBlock(anchor);

        // While locked, remove the personal world border so border push cannot pop the
        // hider out.
        p.setWorldBorder(null);

        // Spectator mode prevents the new solid block from pushing/revealing the hidden
        // player.
        p.setGameMode(GameMode.SPECTATOR);
        Location lockTp = anchor.clone().add(0.5, 0.0, 0.5);
        lockTp.setYaw(p.getLocation().getYaw());
        lockTp.setPitch(p.getLocation().getPitch());
        p.teleport(lockTp);
        p.setFlying(true);
        sendTitle(p, msg("titles.solidified.title", "&bSOLIDIFIED"),
                msg("titles.solidified.subtitle", "&eYou are locked in place"));
        p.playSound(p.getLocation(), Sound.BLOCK_STONE_PLACE, 1f, 1f);
    }

    private Location getFloorAnchorLocation(Player p) {
        Location loc = p.getLocation();
        int x = loc.getBlockX();
        int z = loc.getBlockZ();
        int y = loc.getBlockY();

        // Prefer the block directly beneath the player. If it's not solid,
        // search downward a few blocks to find the nearest floor.
        int floorY = y - 1;
        for (int checkY = y - 1; checkY >= Math.max(y - 6, loc.getWorld().getMinHeight()); checkY--) {
            if (loc.getWorld().getBlockAt(x, checkY, z).getType().isSolid()) {
                floorY = checkY;
                break;
            }
        }

        return new Location(loc.getWorld(), x + 0.5, floorY + 1.0, z + 0.5);
    }

    public void unlockHider(Player p) {
        HiderData data = hiders.get(p.getUniqueId());
        if (data == null || !data.isLocked())
            return;
        data.setLocked(false);
        Location revealLoc = data.getPlacedBlockLocation() == null ? p.getLocation()
                : data.getPlacedBlockLocation().clone().add(0.5, 0.0, 0.5);
        data.restoreWorldBlock();

        p.setGameMode(GameMode.ADVENTURE);
        p.teleport(revealLoc);
        p.setInvisible(true);
        if (state == State.HIDING_PHASE || state == State.SEEKING_PHASE) {
            applyArenaWorldBorder(p);
        }
        data.spawnDisplay(p);
        sendTitle(p, msg("titles.unlocked.title", "&eUNLOCKED"), msg("titles.unlocked.subtitle", "&aKeep moving"));
    }

    public void handleHiderHit(Player seeker, org.bukkit.entity.BlockDisplay display) {
        if (state != State.SEEKING_PHASE)
            return;
        UUID targetUUID = null;
        for (Map.Entry<UUID, HiderData> entry : hiders.entrySet()) {
            if (display.equals(entry.getValue().getBlockDisplay())) {
                targetUUID = entry.getKey();
                break;
            }
        }
        if (targetUUID == null)
            return;
        applyHiderHit(seeker, targetUUID);
    }

    public void handleHiderHit(Player seeker, Interaction hitbox) {
        if (state != State.SEEKING_PHASE)
            return;
        UUID targetUUID = null;
        for (Map.Entry<UUID, HiderData> entry : hiders.entrySet()) {
            if (hitbox.equals(entry.getValue().getPropHitbox())) {
                targetUUID = entry.getKey();
                break;
            }
        }
        if (targetUUID == null)
            return;
        applyHiderHit(seeker, targetUUID);
    }

    public boolean handleHiderBlockHit(Player seeker, Location blockLocation) {
        if (state != State.SEEKING_PHASE)
            return false;
        for (Map.Entry<UUID, HiderData> entry : hiders.entrySet()) {
            Location placed = entry.getValue().getPlacedBlockLocation();
            if (sameBlock(placed, blockLocation)) {
                applyHiderHit(seeker, entry.getKey());
                return true;
            }
        }
        return false;
    }

    private void applyHiderHit(Player seeker, UUID targetUUID) {
        HiderData target = hiders.get(targetUUID);
        if (target == null)
            return;

        // Prevent accidental multi-hit in a single tick from multiple events.
        if (seeker.hasCooldown(Material.WOODEN_SWORD))
            return;
        seeker.setCooldown(Material.WOODEN_SWORD, seekerHitCooldownTicks);

        target.setHp(target.getHp() - 1);
        Player hider = Bukkit.getPlayer(targetUUID);
        if (hider != null) {
            hider.playSound(hider.getLocation(), Sound.ENTITY_PLAYER_HURT, 1f, 1f);
            hider.sendMessage(msg("messages.game.hider-hit", "&cYou were hit! HP: {hp}", Map.of("hp", target.getHp())));
        }
        seeker.sendMessage(msg("messages.game.seeker-hit-confirm", "&eHIT!"));
        if (!target.isAlive()) {
            killHider(targetUUID, seeker);
        } else if (target.isLocked() && hider != null) {
            unlockHider(hider);
        }
    }

    private void killHider(UUID uid, Player seeker) {
        HiderData data = hiders.remove(uid);
        if (data != null)
            data.clearDisguise();
        lastLocations.remove(uid);
        Player hider = Bukkit.getPlayer(uid);
        if (hider != null) {
            hider.setInvisible(false);
            hider.setGameMode(GameMode.SPECTATOR);
            sendTitle(hider, msg("titles.found.title", "&cYou Were Found"),
                    msg("titles.found.subtitle", "&7Spectating now"));
            hider.playSound(hider.getLocation(), Sound.ENTITY_PLAYER_DEATH, 1f, 1f);
        }
        seekers.add(uid);
        messageMatchPlayers(msg(
                "messages.game.found-broadcast",
                "&c{hider} was found by {seeker}!",
                Map.of("hider", hider != null ? hider.getName() : "A hider", "seeker", seeker.getName())));
        if (hiders.isEmpty())
            endGame(false);
    }

    private void endGame(boolean hidersWin) {
        if (state == State.END || state == State.WAITING)
            return;
        state = State.END;
        if (tickTask != null)
            tickTask.cancel();
        if (countdownTask != null)
            countdownTask.cancel();
        if (gameTimerTask != null)
            gameTimerTask.cancel();
        unfreezeAllSeekers();
        broadcastTitle(
                hidersWin ? msg("titles.hiders-win.title", "&aHIDERS WIN")
                        : msg("titles.seekers-win.title", "&cSEEKERS WIN"),
                hidersWin ? msg("titles.hiders-win.subtitle", "&eRound over")
                        : msg("titles.seekers-win.subtitle", "&eRound over"));
        for (Map.Entry<UUID, HiderData> e : hiders.entrySet()) {
            e.getValue().clearDisguise();
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) {
                p.setInvisible(false);
                p.setGameMode(GameMode.ADVENTURE);
            }
        }
        for (UUID uid : seekers) {
            Player p = Bukkit.getPlayer(uid);
            if (p != null) {
                p.setInvisible(false);
                p.setGameMode(GameMode.ADVENTURE);
            }
        }
        clearArenaWorldBorders();
        hiders.clear();
        seekers.clear();
        lastLocations.clear();
        frozenSeekers.clear();
        seekerGraceCountdown = 0;
        gameSecondsRemaining = 0;
        queueOpen = false;
        queuedPlayers.clear();
        selectedArena = null;
        currentArenaBlockPool.clear();
        currentArenaBlockPool.addAll(defaultHiderBlocks);
        Bukkit.getScheduler().runTaskLater(plugin, () -> state = State.WAITING, 100L);
    }

    public void forceStop() {
        if (tickTask != null)
            tickTask.cancel();
        if (countdownTask != null)
            countdownTask.cancel();
        if (gameTimerTask != null)
            gameTimerTask.cancel();
        unfreezeAllSeekers();
        for (HiderData d : hiders.values())
            d.clearDisguise();
        for (UUID uid : hiders.keySet()) {
            Player p = Bukkit.getPlayer(uid);
            if (p != null) {
                p.setInvisible(false);
                p.setGameMode(GameMode.SURVIVAL);
            }
        }
        for (UUID uid : seekers) {
            Player p = Bukkit.getPlayer(uid);
            if (p != null) {
                p.setInvisible(false);
                p.setGameMode(GameMode.SURVIVAL);
            }
        }
        clearArenaWorldBorders();
        hiders.clear();
        seekers.clear();
        lastLocations.clear();
        frozenSeekers.clear();
        seekerGraceCountdown = 0;
        gameSecondsRemaining = 0;
        queueOpen = false;
        queuedPlayers.clear();
        selectedArena = null;
        currentArenaBlockPool.clear();
        currentArenaBlockPool.addAll(defaultHiderBlocks);
        state = State.WAITING;
    }

    public void handlePlayerQuit(Player p) {
        HiderData data = hiders.get(p.getUniqueId());
        if (data != null)
            data.clearDisguise();
        queuedPlayers.remove(p.getUniqueId());
        frozenSeekers.remove(p.getUniqueId());
        p.setWorldBorder(null);
    }

    public boolean joinQueue(Player player) {
        if (player == null)
            return false;
        if (!isQueueOpen())
            return false;
        return queuedPlayers.add(player.getUniqueId());
    }

    public boolean openQueue() {
        if (state != State.WAITING || queueOpen)
            return false;
        queuedPlayers.clear();
        queueOpen = true;
        return true;
    }

    public boolean closeQueue() {
        if (state != State.WAITING || !queueOpen)
            return false;
        queuedPlayers.clear();
        queueOpen = false;
        return true;
    }

    public boolean isQueueOpen() {
        return state == State.WAITING && queueOpen;
    }

    public boolean leaveQueue(Player player) {
        if (player == null)
            return false;
        return queuedPlayers.remove(player.getUniqueId());
    }

    public int getQueueSize() {
        cleanupQueue();
        return queuedPlayers.size();
    }

    public List<Material> getAvailableDisguiseBlocks() {
        List<Material> source = currentArenaBlockPool.isEmpty() ? defaultHiderBlocks : currentArenaBlockPool;
        if (source.isEmpty())
            source = FALLBACK_HIDER_BLOCKS;
        return new ArrayList<>(new LinkedHashSet<>(source));
    }

    // -------------------------------------------------------------------------
    // Arena setup / configuration
    // -------------------------------------------------------------------------

    public String createArena(String name) {
        String key = normalizeArenaName(name);
        FileConfiguration cfg = plugin.getConfig();
        if (cfg.isConfigurationSection("arenas." + key)) {
            return msg("messages.arena.already-exists", "&cArena already exists: {arena}", Map.of("arena", key));
        }
        cfg.createSection("arenas." + key);
        plugin.saveConfig();
        return msg("messages.arena.created", "&aCreated arena: {arena}", Map.of("arena", key));
    }

    public String removeArena(String name) {
        String key = normalizeArenaName(name);
        FileConfiguration cfg = plugin.getConfig();
        if (!cfg.isConfigurationSection("arenas." + key)) {
            return msg("messages.arena.not-found", "&cArena not found: {arena}", Map.of("arena", key));
        }
        cfg.set("arenas." + key, null);
        plugin.saveConfig();
        return msg("messages.arena.removed", "&aRemoved arena: {arena}", Map.of("arena", key));
    }

    public String setArenaPos1(Player player, String name) {
        return setArenaPos(player, name, "pos1");
    }

    public String setArenaPos2(Player player, String name) {
        return setArenaPos(player, name, "pos2");
    }

    public String addHiderSpawn(Player player, String name) {
        return addSpawn(player, name, true);
    }

    public String addSeekerSpawn(Player player, String name) {
        return addSpawn(player, name, false);
    }

    public List<String> getArenaNames() {
        ConfigurationSection arenasSec = plugin.getConfig().getConfigurationSection("arenas");
        if (arenasSec == null)
            return Collections.emptyList();
        return new ArrayList<>(arenasSec.getKeys(false));
    }

    public String arenaInfo(String name) {
        String key = normalizeArenaName(name);
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("arenas." + key);
        if (sec == null)
            return msg("messages.arena.not-found", "&cArena not found: {arena}", Map.of("arena", key));

        boolean hasPos1 = sec.isConfigurationSection("pos1");
        boolean hasPos2 = sec.isConfigurationSection("pos2");
        int hiderCount = sec.getMapList("hider-spawns").size();
        int seekerCount = sec.getMapList("seeker-spawns").size();

        return msg(
                "messages.arena.info",
                "&6Arena {arena}&7 | cuboid={cuboid} | hiderSpawns={hiders} | seekerSpawns={seekers}",
                Map.of("arena", key, "cuboid", hasPos1 && hasPos2, "hiders", hiderCount, "seekers", seekerCount));
    }

    private String setArenaPos(Player player, String name, String posKey) {
        String key = normalizeArenaName(name);
        FileConfiguration cfg = plugin.getConfig();
        ConfigurationSection sec = cfg.getConfigurationSection("arenas." + key);
        if (sec == null)
            return msg("messages.arena.not-found", "&cArena not found: {arena}", Map.of("arena", key));

        Location loc = player.getLocation();
        sec.set("world", loc.getWorld().getName());
        writeLocation(sec.createSection(posKey), loc);
        plugin.saveConfig();

        // Trigger block pre-generation when cuboid is completed
        String world = sec.getString("world");
        Location pos1 = readLocation(sec.getConfigurationSection("pos1"), world);
        Location pos2 = readLocation(sec.getConfigurationSection("pos2"), world);
        if (pos1 != null && pos2 != null) {
            Arena tempArena = new Arena(key, world, pos1, pos2, new ArrayList<>(), new ArrayList<>());
            preGenerateArenaBlocks(tempArena);
        }

        return msg("messages.arena.set-pos", "&aSet {pos} for arena {arena}.", Map.of("pos", posKey, "arena", key));
    }

    private String addSpawn(Player player, String name, boolean hider) {
        String key = normalizeArenaName(name);
        FileConfiguration cfg = plugin.getConfig();
        ConfigurationSection sec = cfg.getConfigurationSection("arenas." + key);
        if (sec == null)
            return msg("messages.arena.not-found", "&cArena not found: {arena}", Map.of("arena", key));

        Location loc = player.getLocation();
        sec.set("world", loc.getWorld().getName());

        Location pos1 = readLocation(sec.getConfigurationSection("pos1"), sec.getString("world"));
        Location pos2 = readLocation(sec.getConfigurationSection("pos2"), sec.getString("world"));
        if (pos1 == null || pos2 == null) {
            return msg("messages.arena.spawn-needs-cuboid", "&cSet pos1 and pos2 for this arena before adding spawns.");
        }
        if (!isInsideCuboid(loc, pos1, pos2)) {
            return msg("messages.arena.spawn-outside-cuboid", "&cSpawn must be inside the arena cuboid.");
        }

        String listPath = hider ? "hider-spawns" : "seeker-spawns";
        List<Map<?, ?>> mapList = sec.getMapList(listPath);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("x", loc.getX());
        map.put("y", loc.getY());
        map.put("z", loc.getZ());
        map.put("yaw", loc.getYaw());
        map.put("pitch", loc.getPitch());
        mapList.add(map);
        sec.set(listPath, mapList);
        plugin.saveConfig();
        return msg(
                "messages.arena.add-spawn",
                "&aAdded {type} spawn to arena {arena}.",
                Map.of("type", hider ? "hider" : "seeker", "arena", key));
    }

    private List<Arena> loadArenasFromConfig() {
        ConfigurationSection arenasSec = plugin.getConfig().getConfigurationSection("arenas");
        if (arenasSec == null)
            return Collections.emptyList();

        List<Arena> arenas = new ArrayList<>();
        for (String name : arenasSec.getKeys(false)) {
            ConfigurationSection sec = arenasSec.getConfigurationSection(name);
            if (sec == null)
                continue;
            String world = sec.getString("world");
            Location pos1 = readLocation(sec.getConfigurationSection("pos1"), world);
            Location pos2 = readLocation(sec.getConfigurationSection("pos2"), world);
            List<Location> hiderSpawns = readLocationList(sec.getMapList("hider-spawns"), world);
            List<Location> seekerSpawns = readLocationList(sec.getMapList("seeker-spawns"), world);
            Arena arena = new Arena(name, world, pos1, pos2, hiderSpawns, seekerSpawns);
            arenas.add(arena);
            // Pre-generate block pool asynchronously when arena is loaded
            preGenerateArenaBlocks(arena);
        }
        return arenas;
    }

    private List<Location> readLocationList(List<Map<?, ?>> list, String worldName) {
        List<Location> out = new ArrayList<>();
        World world = worldName == null ? null : Bukkit.getWorld(worldName);
        if (world == null)
            return out;
        for (Map<?, ?> m : list) {
            try {
                double x = numberFromMap(m, "x", 0d);
                double y = numberFromMap(m, "y", 0d);
                double z = numberFromMap(m, "z", 0d);
                float yaw = (float) numberFromMap(m, "yaw", 0d);
                float pitch = (float) numberFromMap(m, "pitch", 0d);
                out.add(new Location(world, x, y, z, yaw, pitch));
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private double numberFromMap(Map<?, ?> map, String key, double defaultValue) {
        Object value = map.get(key);
        if (value instanceof Number n)
            return n.doubleValue();
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    private Location readLocation(ConfigurationSection sec, String worldName) {
        if (sec == null || worldName == null)
            return null;
        World world = Bukkit.getWorld(worldName);
        if (world == null)
            return null;
        return new Location(
                world,
                sec.getDouble("x"),
                sec.getDouble("y"),
                sec.getDouble("z"),
                (float) sec.getDouble("yaw"),
                (float) sec.getDouble("pitch"));
    }

    private void writeLocation(ConfigurationSection sec, Location loc) {
        sec.set("x", loc.getX());
        sec.set("y", loc.getY());
        sec.set("z", loc.getZ());
        sec.set("yaw", loc.getYaw());
        sec.set("pitch", loc.getPitch());
    }

    private String normalizeArenaName(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
    }

    private void teleportToRandomSpawn(Player p, List<Location> spawns) {
        if (spawns.isEmpty())
            return;
        Location target = spawns.get(ThreadLocalRandom.current().nextInt(spawns.size()));
        p.teleport(target);
    }

    private List<Material> sampleArenaBlocks(Arena arena) {
        if (arena == null || !arena.hasCuboid())
            return Collections.emptyList();

        int minX = Math.min(arena.pos1.getBlockX(), arena.pos2.getBlockX());
        int maxX = Math.max(arena.pos1.getBlockX(), arena.pos2.getBlockX());
        int minY = Math.max(arena.pos1.getWorld().getMinHeight(),
                Math.min(arena.pos1.getBlockY(), arena.pos2.getBlockY()));
        int maxY = Math.min(arena.pos1.getWorld().getMaxHeight() - 1,
                Math.max(arena.pos1.getBlockY(), arena.pos2.getBlockY()));
        int minZ = Math.min(arena.pos1.getBlockZ(), arena.pos2.getBlockZ());
        int maxZ = Math.max(arena.pos1.getBlockZ(), arena.pos2.getBlockZ());

        Map<Material, Integer> counts = new HashMap<>();
        int scanned = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (scanned++ >= arenaScanMaxBlocks)
                        break;
                    Block block = arena.pos1.getWorld().getBlockAt(x, y, z);
                    Material mat = block.getType();
                    if (!isAllowedPropMaterial(mat))
                        continue;
                    counts.put(mat, counts.getOrDefault(mat, 0) + 1);
                }
                if (scanned >= arenaScanMaxBlocks)
                    break;
            }
            if (scanned >= arenaScanMaxBlocks)
                break;
        }

        if (counts.isEmpty())
            return Collections.emptyList();

        List<Material> weighted = new ArrayList<>();
        for (Map.Entry<Material, Integer> entry : counts.entrySet()) {
            int weight = Math.max(1, Math.min(40, entry.getValue() / 8));
            for (int i = 0; i < weight; i++)
                weighted.add(entry.getKey());
        }
        return weighted;
    }

    private boolean isAllowedPropMaterial(Material mat) {
        if (mat == null)
            return false;
        if (!mat.isBlock() || !mat.isSolid() || mat.isAir())
            return false;
        return switch (mat) {
            case BEDROCK, BARRIER, COMMAND_BLOCK, CHAIN_COMMAND_BLOCK, REPEATING_COMMAND_BLOCK,
                    WATER, LAVA, LIGHT, STRUCTURE_VOID ->
                false;
            default -> true;
        };
    }

    private void preGenerateArenaBlocks(Arena arena) {
        if (arena == null || !arena.hasCuboid() || arena.blockPoolGenerated)
            return;

        // Run asynchronously to avoid blocking the main thread
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<Material> blocks = sampleArenaBlocks(arena);
            if (!blocks.isEmpty()) {
                arena.cachedBlockPool.clear();
                arena.cachedBlockPool.addAll(blocks);
            }
            arena.blockPoolGenerated = true;
        });
    }

    private void freezeSeeker(Player p) {
        Location freezeLoc = p.getLocation().clone();
        freezeLoc.setPitch(90f);
        frozenSeekers.put(p.getUniqueId(), freezeLoc);
        p.teleport(freezeLoc);
        p.setWalkSpeed(0f);
        p.setFlySpeed(0f);
        p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS,
                (seekerGracePeriod + freezeBlindnessBufferSeconds) * 20, 1, false, false, false));
    }

    private void unfreezeSeeker(Player p) {
        frozenSeekers.remove(p.getUniqueId());
        p.setWalkSpeed(0.2f);
        p.setFlySpeed(0.1f);
        p.removePotionEffect(PotionEffectType.BLINDNESS);
    }

    private void unfreezeAllSeekers() {
        for (UUID uid : new HashSet<>(frozenSeekers.keySet())) {
            Player p = Bukkit.getPlayer(uid);
            if (p != null)
                unfreezeSeeker(p);
        }
        frozenSeekers.clear();
    }

    private void assignTeams(List<Player> sourcePlayers) {
        List<Player> players = new ArrayList<>(sourcePlayers);
        Collections.shuffle(players);
        int seekerCount = Math.max(1, (int) Math.ceil((double) players.size() / playersPerSeeker));
        for (int i = 0; i < players.size(); i++) {
            UUID uid = players.get(i).getUniqueId();
            if (i < seekerCount)
                seekers.add(uid);
            else
                hiders.put(uid, new HiderData(uid, hiderMaxHp));
        }
    }

    private List<Player> getQueuedOnlinePlayers() {
        cleanupQueue();
        List<Player> players = new ArrayList<>();
        for (UUID queuedId : queuedPlayers) {
            Player player = Bukkit.getPlayer(queuedId);
            if (player != null && player.isOnline())
                players.add(player);
        }
        return players;
    }

    private void cleanupQueue() {
        queuedPlayers.removeIf(id -> {
            Player player = Bukkit.getPlayer(id);
            return player == null || !player.isOnline();
        });
    }

    private void applyArenaWorldBorders() {
        if (selectedArena == null || !selectedArena.hasCuboid() || selectedArena.pos1.getWorld() == null)
            return;
        Set<UUID> participants = new HashSet<>();
        participants.addAll(hiders.keySet());
        participants.addAll(seekers);

        for (UUID participantId : participants) {
            Player player = Bukkit.getPlayer(participantId);
            if (player == null || !player.isOnline())
                continue;
            applyArenaWorldBorder(player);
        }
    }

    private void applyArenaWorldBorder(Player player) {
        if (player == null || selectedArena == null || !selectedArena.hasCuboid()
                || selectedArena.pos1.getWorld() == null)
            return;

        int minX = Math.min(selectedArena.pos1.getBlockX(), selectedArena.pos2.getBlockX());
        int maxX = Math.max(selectedArena.pos1.getBlockX(), selectedArena.pos2.getBlockX());
        int minZ = Math.min(selectedArena.pos1.getBlockZ(), selectedArena.pos2.getBlockZ());
        int maxZ = Math.max(selectedArena.pos1.getBlockZ(), selectedArena.pos2.getBlockZ());

        for (Location spawn : selectedArena.hiderSpawns) {
            if (spawn == null || spawn.getWorld() == null || !spawn.getWorld().equals(selectedArena.pos1.getWorld()))
                continue;
            minX = Math.min(minX, spawn.getBlockX());
            maxX = Math.max(maxX, spawn.getBlockX());
            minZ = Math.min(minZ, spawn.getBlockZ());
            maxZ = Math.max(maxZ, spawn.getBlockZ());
        }
        for (Location spawn : selectedArena.seekerSpawns) {
            if (spawn == null || spawn.getWorld() == null || !spawn.getWorld().equals(selectedArena.pos1.getWorld()))
                continue;
            minX = Math.min(minX, spawn.getBlockX());
            maxX = Math.max(maxX, spawn.getBlockX());
            minZ = Math.min(minZ, spawn.getBlockZ());
            maxZ = Math.max(maxZ, spawn.getBlockZ());
        }

        double centerX = (minX + maxX + 1) / 2.0;
        double centerZ = (minZ + maxZ + 1) / 2.0;
        double size = Math.max((maxX - minX) + 2.0, (maxZ - minZ) + 2.0);

        WorldBorder border = Bukkit.createWorldBorder();
        border.setCenter(centerX, centerZ);
        border.setSize(size);
        border.setWarningDistance(0);
        border.setDamageBuffer(0.0);
        player.setWorldBorder(border);
    }

    private void clearArenaWorldBorders() {
        Set<UUID> participants = new HashSet<>();
        participants.addAll(hiders.keySet());
        participants.addAll(seekers);

        for (UUID participantId : participants) {
            Player player = Bukkit.getPlayer(participantId);
            if (player != null && player.isOnline())
                player.setWorldBorder(null);
        }
    }

    private void assignRandomBlock(Player p) {
        List<Material> pool = currentArenaBlockPool.isEmpty() ? defaultHiderBlocks : currentArenaBlockPool;
        Material chosen = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        setHiderBlock(p, chosen);
        p.sendMessage(
                msg("messages.game.random-disguise", "&aRandom disguise: {block}", Map.of("block", chosen.name())));
    }

    public void setHiderBlock(Player p, Material block) {
        HiderData data = hiders.get(p.getUniqueId());
        if (data == null)
            return;
        if (data.isLocked()) {
            p.sendMessage(msg("messages.game.unlock-first", "&cUnlock first by moving!"));
            return;
        }
        data.setChosenBlock(block);
        data.clearDisguise();
        data.spawnDisplay(p);
        p.sendMessage(msg("messages.game.disguised-as", "&aDisguised as {block}!", Map.of("block", block.name())));
    }

    private boolean sameBlock(Location a, Location b) {
        if (a == null || b == null)
            return false;
        if (a.getWorld() == null || b.getWorld() == null)
            return false;
        if (!a.getWorld().equals(b.getWorld()))
            return false;
        return a.getBlockX() == b.getBlockX()
                && a.getBlockY() == b.getBlockY()
                && a.getBlockZ() == b.getBlockZ();
    }

    private boolean shouldReapplyFrozenView(Location frozen, Location current) {
        if (!sameBlock(frozen, current))
            return true;
        return Math.abs(frozen.getYaw() - current.getYaw()) > 0.5f
                || Math.abs(frozen.getPitch() - current.getPitch()) > 0.5f;
    }

    private boolean isInsideCuboid(Location location, Location pos1, Location pos2) {
        if (location == null || pos1 == null || pos2 == null)
            return false;
        if (location.getWorld() == null || pos1.getWorld() == null || pos2.getWorld() == null)
            return false;
        if (!location.getWorld().equals(pos1.getWorld()) || !location.getWorld().equals(pos2.getWorld()))
            return false;

        int minX = Math.min(pos1.getBlockX(), pos2.getBlockX());
        int maxX = Math.max(pos1.getBlockX(), pos2.getBlockX());
        int minY = Math.min(pos1.getBlockY(), pos2.getBlockY());
        int maxY = Math.max(pos1.getBlockY(), pos2.getBlockY());
        int minZ = Math.min(pos1.getBlockZ(), pos2.getBlockZ());
        int maxZ = Math.max(pos1.getBlockZ(), pos2.getBlockZ());

        return location.getX() >= minX && location.getX() <= (maxX + 1)
                && location.getY() >= minY && location.getY() <= (maxY + 1)
                && location.getZ() >= minZ && location.getZ() <= (maxZ + 1);
    }

    public boolean isHider(Player p) {
        return hiders.containsKey(p.getUniqueId());
    }

    public boolean isSeeker(Player p) {
        return seekers.contains(p.getUniqueId());
    }

    public State getState() {
        return state;
    }

    public Map<UUID, HiderData> getHiders() {
        return Collections.unmodifiableMap(hiders);
    }

    public Set<UUID> getSeekers() {
        return Collections.unmodifiableSet(seekers);
    }

    public HiderData getHiderData(UUID uuid) {
        return hiders.get(uuid);
    }

    private void broadcast(String msg) {
        String prefixed = msg("messages.prefix", "&6[PropHunt] &r") + msg;
        for (UUID recipientId : getMatchAndAdminRecipients()) {
            Player recipient = Bukkit.getPlayer(recipientId);
            if (recipient != null && recipient.isOnline())
                recipient.sendMessage(prefixed);
        }
    }

    private Set<UUID> getMatchAndAdminRecipients() {
        Set<UUID> recipients = new HashSet<>();
        recipients.addAll(hiders.keySet());
        recipients.addAll(seekers);
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.hasPermission("prophunt.admin"))
                recipients.add(online.getUniqueId());
        }
        return recipients;
    }

    private void messageMatchPlayers(String msg) {
        String prefixed = msg("messages.prefix", "&6[PropHunt] &r") + msg;
        Set<UUID> participants = new HashSet<>();
        participants.addAll(hiders.keySet());
        participants.addAll(seekers);
        for (UUID participantId : participants) {
            Player participant = Bukkit.getPlayer(participantId);
            if (participant != null && participant.isOnline())
                participant.sendMessage(prefixed);
        }
    }

    private void broadcastTitle(String title, String subtitle) {
        for (UUID recipientId : getMatchAndAdminRecipients()) {
            Player recipient = Bukkit.getPlayer(recipientId);
            if (recipient != null && recipient.isOnline())
                sendTitle(recipient, title, subtitle);
        }
    }

    private void sendTitle(Player player, String title, String subtitle) {
        if (player == null)
            return;
        player.sendTitle(title, subtitle, titleFadeInTicks, titleStayTicks, titleFadeOutTicks);
    }

    private void loadSettings() {
        lockTicksRequired = intSetting("gameplay.lock-ticks", "lock-ticks", 60, 1);
        hiderMaxHp = intSetting("gameplay.hider-hp", "hider-hp", 3, 1);
        seekerGracePeriod = intSetting("gameplay.seeker-grace-seconds", "seeker-grace-seconds", 30, 0);
        gameDuration = intSetting("gameplay.game-duration-seconds", "game-duration-seconds", 300, 1);
        arenaScanMaxBlocks = intSetting("gameplay.arena-scan-max-blocks", "arena-scan-max-blocks", 120000, 1);
        playersPerSeeker = intSetting("gameplay.players-per-seeker", null, 3, 1);
        seekerHitCooldownTicks = intSetting("gameplay.seeker-hit-cooldown-ticks", null, 4, 0);
        freezeBlindnessBufferSeconds = intSetting("gameplay.freeze-blindness-buffer-seconds", null, 5, 0);
        countdownTitleThresholdSeconds = intSetting("gameplay.countdown-title-threshold-seconds", null, 5, 0);
        titleFadeInTicks = intSetting("display.title.fade-in-ticks", null, 10, 0);
        titleStayTicks = intSetting("display.title.stay-ticks", null, 50, 0);
        titleFadeOutTicks = intSetting("display.title.fade-out-ticks", null, 10, 0);
        roundWarningSeconds = intListSetting("gameplay.round-warning-seconds", Arrays.asList(300, 60));
        defaultHiderBlocks.clear();
        defaultHiderBlocks.addAll(materialListSetting("gameplay.default-hider-blocks", FALLBACK_HIDER_BLOCKS));
        if (defaultHiderBlocks.isEmpty())
            defaultHiderBlocks.addAll(FALLBACK_HIDER_BLOCKS);
    }

    private void adjustRunningMatch(int oldHiderMaxHp, int oldSeekerGracePeriod, int oldGameDuration) {
        if (oldHiderMaxHp > 0) {
            for (HiderData data : hiders.values()) {
                int damageTaken = Math.max(0, oldHiderMaxHp - data.getHp());
                data.setHp(Math.max(0, hiderMaxHp - damageTaken));
            }
        }

        if (state == State.HIDING_PHASE && countdownTask != null) {
            int elapsed = Math.max(0, oldSeekerGracePeriod - seekerGraceCountdown);
            seekerGraceCountdown = Math.max(0, seekerGracePeriod - elapsed);
            if (seekerGraceCountdown <= 0) {
                countdownTask.cancel();
                releaseSeekersPhase();
            }
        }

        if (state == State.SEEKING_PHASE && gameTimerTask != null) {
            int elapsed = Math.max(0, oldGameDuration - gameSecondsRemaining);
            gameSecondsRemaining = Math.max(0, gameDuration - elapsed);
            if (gameSecondsRemaining <= 0) {
                gameTimerTask.cancel();
                endGame(true);
            }
        }
    }

    private int intSetting(String primaryPath, String legacyPath, int defaultValue, int minValue) {
        FileConfiguration config = plugin.getConfig();
        int value;
        if (config.contains(primaryPath))
            value = config.getInt(primaryPath, defaultValue);
        else if (legacyPath != null && config.contains(legacyPath))
            value = config.getInt(legacyPath, defaultValue);
        else
            value = defaultValue;
        return Math.max(minValue, value);
    }

    private List<Integer> intListSetting(String path, List<Integer> fallback) {
        List<Integer> values = new ArrayList<>();
        if (plugin.getConfig().isList(path)) {
            for (Object raw : plugin.getConfig().getList(path, Collections.emptyList())) {
                if (raw instanceof Number number)
                    values.add(Math.max(0, number.intValue()));
                else if (raw instanceof String text) {
                    try {
                        values.add(Math.max(0, Integer.parseInt(text)));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        if (values.isEmpty())
            values.addAll(fallback);
        values.sort(Comparator.reverseOrder());
        return values;
    }

    private List<Material> materialListSetting(String path, List<Material> fallback) {
        List<String> values = plugin.getConfig().getStringList(path);
        List<Material> materials = new ArrayList<>();
        for (String value : values) {
            Material material = Material.matchMaterial(value);
            if (material != null && isAllowedPropMaterial(material))
                materials.add(material);
        }
        if (materials.isEmpty())
            materials.addAll(fallback);
        return materials;
    }

    private String formatRemainingTime(int totalSeconds) {
        if (totalSeconds % 60 == 0) {
            int minutes = totalSeconds / 60;
            return minutes + " minute" + (minutes == 1 ? "" : "s");
        }
        return totalSeconds + " seconds";
    }

    private String msg(String path, String fallback) {
        return plugin.getConfigText(path, fallback);
    }

    private String msg(String path, String fallback, Map<String, ?> placeholders) {
        return plugin.getConfigText(path, fallback, placeholders);
    }
}
