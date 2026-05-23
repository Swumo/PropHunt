package me.swumo.prophunt.game;

import lombok.Getter;
import me.swumo.prophunt.PropHunt;
import me.swumo.prophunt.gui.BlockSelectGUI;
import me.swumo.prophunt.platform.PlatformScheduler;
import me.swumo.prophunt.platform.PlatformScheduler.PlatformTask;
import me.swumo.prophunt.utils.ArenaUtils;
import me.swumo.prophunt.utils.ArenaUtils.ArenaRuntime;
import me.swumo.prophunt.utils.GameConfigReader;
import me.swumo.prophunt.utils.GameFormatUtils;
import me.swumo.prophunt.utils.GameMessageUtils;
import me.swumo.prophunt.utils.WorldBorderUtils;
import org.bukkit.*;
import org.bukkit.Tag;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class GameManager {
    public static final int SEEKER_WEAPON_SLOT = 0;
    private static final int HIDER_MENU_SLOT = 8;
    private static final String SEEKER_WEAPON_NAME = "§6Seeker Sword";
    private static final String HIDER_MENU_ITEM_NAME = "§bBlock Picker";
    private static final String HIDER_NO_COLLISION_TEAM = "ph_hiders";
    private static final String SEEKER_NO_COLLISION_TEAM = "ph_seekers";
    private static final double LOCKED_HIDER_SNAP_BACK_DRIFT = 0.18;
    private static final long LOCKED_HIDER_MOVEMENT_GRACE_MS = 900L;

    public enum State {
        WAITING, HIDING_PHASE, SEEKING_PHASE, END
    }

    private final PropHunt plugin;
    private final BlockSelectGUI blockSelectGUI;
    @Getter
    private State state = State.WAITING;
    private final Map<UUID, HiderData> hiders = new HashMap<>();
    private final Set<UUID> seekers = new HashSet<>();
    private final Map<UUID, Location> lastLocations = new HashMap<>();
    private final Map<UUID, Location> frozenSeekers = new HashMap<>();
    private final Map<UUID, Long> lockedMovementGraceUntil = new HashMap<>();
    private final Map<UUID, ItemStack> seekerHeldItemSnapshots = new HashMap<>();
    private final Set<UUID> blockSelectionMenuPlayers = new HashSet<>();
    private final Set<UUID> queuedPlayers = new LinkedHashSet<>();
    private final List<Material> currentArenaBlockPool = new ArrayList<>();
    private boolean queueOpen;
    private ArenaRuntime selectedArena;
    private PlatformTask tickTask;
    private PlatformTask countdownTask;
    private PlatformTask gameTimerTask;
    private int seekerGraceCountdown;
    private int gameSecondsRemaining;

    private int lockTicksRequired;
    private int hiderMaxHp;
    private int seekerGracePeriod;
    private int gameDuration;
    private int arenaScanMaxBlocks;
    private int arenaMinBlockOccurrences;
    private int playersPerSeeker;
    private int seekerHitCooldownTicks;
    private int freezeBlindnessBufferSeconds;
    private int countdownTitleThresholdSeconds;
    private List<Integer> roundWarningSeconds = new ArrayList<>();
    private final Set<Material> blockedDisguiseBlocks = EnumSet.noneOf(Material.class);
    private final Set<Material> allowedNonSolidDisguiseBlocks = EnumSet.noneOf(Material.class);
    private final Set<Material> forceAllowDisguiseBlocks = EnumSet.noneOf(Material.class);
    private final Set<Material> forceDenyDisguiseBlocks = EnumSet.noneOf(Material.class);
    private final List<Material> defaultHiderBlocks = new ArrayList<>();
    private static final EnumSet<Material> FALLBACK_HIDER_BLOCKS = EnumSet.of(
            Material.GRASS_BLOCK, Material.DIRT, Material.STONE, Material.COBBLESTONE,
            Material.OAK_LOG, Material.BIRCH_LOG, Material.SAND, Material.GRAVEL,
            Material.OAK_PLANKS, Material.SPRUCE_PLANKS, Material.BRICKS,
            Material.MOSSY_COBBLESTONE, Material.BOOKSHELF, Material.CRAFTING_TABLE,
            Material.CHEST, Material.BARREL, Material.HAY_BLOCK, Material.MELON,
            Material.PUMPKIN, Material.TNT, Material.CACTUS, Material.CLAY,
            Material.SMOOTH_STONE, Material.ANDESITE, Material.DIORITE, Material.GRANITE,
            Material.OAK_LEAVES, Material.ICE, Material.FURNACE);
    private static final EnumSet<Material> DEFAULT_BLOCKED_DISGUISE_BLOCKS = EnumSet.of(
            Material.BEDROCK, Material.BARRIER, Material.COMMAND_BLOCK, Material.CHAIN_COMMAND_BLOCK,
            Material.REPEATING_COMMAND_BLOCK, Material.WATER, Material.LAVA, Material.LIGHT, Material.STRUCTURE_VOID,
            Material.STONE_SLAB, Material.SMOOTH_STONE_SLAB, Material.SANDSTONE_SLAB, Material.PETRIFIED_OAK_SLAB,
            Material.COBBLESTONE_SLAB, Material.BRICK_SLAB, Material.STONE_BRICK_SLAB, Material.NETHER_BRICK_SLAB,
            Material.QUARTZ_SLAB, Material.RED_SANDSTONE_SLAB, Material.PURPUR_SLAB, Material.PRISMARINE_SLAB,
            Material.PRISMARINE_BRICK_SLAB, Material.DARK_PRISMARINE_SLAB, Material.OAK_SLAB, Material.SPRUCE_SLAB,
            Material.BIRCH_SLAB, Material.JUNGLE_SLAB, Material.ACACIA_SLAB, Material.DARK_OAK_SLAB,
            Material.MANGROVE_SLAB, Material.CHERRY_SLAB, Material.PALE_OAK_SLAB, Material.BAMBOO_SLAB,
            Material.WARPED_SLAB, Material.CRIMSON_SLAB, Material.BLACKSTONE_SLAB,
            Material.POLISHED_BLACKSTONE_SLAB, Material.POLISHED_BLACKSTONE_BRICK_SLAB,
            Material.STONE_STAIRS, Material.SANDSTONE_STAIRS, Material.NETHER_BRICK_STAIRS,
            Material.STONE_BRICK_STAIRS, Material.DARK_PRISMARINE_STAIRS, Material.PRISMARINE_BRICK_STAIRS,
            Material.PRISMARINE_STAIRS, Material.OAK_STAIRS, Material.SPRUCE_STAIRS, Material.BIRCH_STAIRS,
            Material.JUNGLE_STAIRS, Material.ACACIA_STAIRS, Material.DARK_OAK_STAIRS, Material.MANGROVE_STAIRS,
            Material.CHERRY_STAIRS, Material.PALE_OAK_STAIRS, Material.BAMBOO_STAIRS, Material.WARPED_STAIRS,
            Material.CRIMSON_STAIRS, Material.BLACKSTONE_STAIRS, Material.POLISHED_BLACKSTONE_STAIRS,
            Material.POLISHED_BLACKSTONE_BRICK_STAIRS, Material.RED_SANDSTONE_STAIRS, Material.QUARTZ_STAIRS,
            Material.PURPUR_STAIRS,
            Material.WHITE_CARPET, Material.ORANGE_CARPET, Material.MAGENTA_CARPET, Material.LIGHT_BLUE_CARPET,
            Material.YELLOW_CARPET, Material.LIME_CARPET, Material.PINK_CARPET, Material.GRAY_CARPET,
            Material.LIGHT_GRAY_CARPET, Material.CYAN_CARPET, Material.PURPLE_CARPET, Material.BLUE_CARPET,
            Material.BROWN_CARPET, Material.GREEN_CARPET, Material.RED_CARPET, Material.BLACK_CARPET,
            Material.SNOW, Material.SNOW_BLOCK);
    private static final EnumSet<Material> DEFAULT_ALLOWED_NON_SOLID_DISGUISE_BLOCKS = EnumSet.of(
            Material.SHORT_GRASS, Material.FERN,
            Material.TALL_GRASS, Material.LARGE_FERN,
            Material.SUNFLOWER, Material.LILAC, Material.ROSE_BUSH, Material.PEONY,
            Material.PITCHER_PLANT,
            Material.WHITE_BED, Material.ORANGE_BED, Material.MAGENTA_BED, Material.LIGHT_BLUE_BED,
            Material.YELLOW_BED, Material.LIME_BED, Material.PINK_BED, Material.GRAY_BED,
            Material.LIGHT_GRAY_BED, Material.CYAN_BED, Material.PURPLE_BED, Material.BLUE_BED,
            Material.BROWN_BED, Material.GREEN_BED, Material.RED_BED, Material.BLACK_BED,
            Material.IRON_DOOR, Material.OAK_DOOR, Material.SPRUCE_DOOR, Material.BIRCH_DOOR,
            Material.JUNGLE_DOOR, Material.ACACIA_DOOR, Material.DARK_OAK_DOOR, Material.MANGROVE_DOOR,
            Material.CHERRY_DOOR, Material.PALE_OAK_DOOR, Material.BAMBOO_DOOR, Material.CRIMSON_DOOR,
            Material.WARPED_DOOR);

    public GameManager(PropHunt plugin) {
        this.plugin = plugin;
        this.blockSelectGUI = new BlockSelectGUI(plugin);
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
                currentArenaBlockPool.addAll(ArenaUtils.sampleArenaBlocks(
                        selectedArena.pos1,
                        selectedArena.pos2,
                        arenaScanMaxBlocks,
                        arenaMinBlockOccurrences,
                        this::isAllowedPropMaterial));
            }

            if (currentArenaBlockPool.isEmpty())
                currentArenaBlockPool.addAll(defaultHiderBlocks);
        } else if (state == State.WAITING) {
            currentArenaBlockPool.clear();
            currentArenaBlockPool.addAll(defaultHiderBlocks);
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

        List<ArenaRuntime> arenas = loadArenasFromConfig();
        if (arenas.isEmpty()) {
            startError(sender,
                    msg("messages.game.no-arenas", "&cNo arenas configured. Use /prophunt arena create <name>."));
            return;
        }

        boolean hasAnyCuboid = arenas.stream().anyMatch(ArenaRuntime::hasCuboid);
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

        List<ArenaRuntime> playableArenas = new ArrayList<>();
        for (ArenaRuntime arena : arenas) {
            if (!arena.hasCuboid())
                continue;
            if (!arena.hasSpawns())
                continue;

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
            currentArenaBlockPool.addAll(ArenaUtils.sampleArenaBlocks(
                    selectedArena.pos1,
                    selectedArena.pos2,
                    arenaScanMaxBlocks,
                    arenaMinBlockOccurrences,
                    this::isAllowedPropMaterial));
        }

        if (currentArenaBlockPool.isEmpty())
            currentArenaBlockPool.addAll(defaultHiderBlocks);

        hiders.clear();
        seekers.clear();
        lastLocations.clear();
        assignTeams(queuedOnlinePlayers);
        state = State.HIDING_PHASE;

        Set<UUID> matchAndAdmins = GameMessageUtils.matchAndAdminRecipients(hiders.keySet(), seekers, "prophunt.admin");
        String prefix = msg("messages.prefix", "&6[PropHunt] &r");
        GameMessageUtils.sendPrefixedChat(
                matchAndAdmins,
                prefix,
                msg("messages.game.round-start", "&6=== PropHunt Started on Arena: {arena} ===",
                        Map.of("arena", selectedArena.name)));
        GameMessageUtils.sendPrefixedChat(
                matchAndAdmins,
                prefix,
                msg("messages.game.round-start-block-pool",
                        "&aHiders are assigned random map blocks from this arena."));
        GameMessageUtils.sendPrefixedChat(
                matchAndAdmins,
                prefix,
                msg("messages.game.seeker-release-delay", "&cSeekers: releasing in {seconds} seconds!",
                        Map.of("seconds", seekerGracePeriod)));
        GameMessageUtils.sendTitle(
                matchAndAdmins,
                msg("titles.round-start.title", "&6PropHunt Started"),
                msg("titles.round-start.subtitle", "&eArena: {arena} &7| Seekers release in {seconds}s",
                        Map.of("arena", selectedArena.name, "seconds", seekerGracePeriod)));

        for (UUID sid : seekers) {
            Player p = Bukkit.getPlayer(sid);
            if (p == null)
                continue;

            teleportToRandomSpawn(p, selectedArena.seekerSpawns);
            p.setGameMode(GameMode.ADVENTURE);
            p.setGravity(true);
            giveSeekerVisualWeapon(p);
            assignPlayerToNoCollisionTeam(p, false);
            freezeSeeker(p);
            GameMessageUtils.sendTitle(p,
                    msg("titles.seeker-role.title", "&cYou are the SEEKER"),
                    msg("titles.seeker-role.subtitle", "&eFrozen and blinded until release"));
            p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, PotionEffect.INFINITE_DURATION, 0, false,
                    false, false));
            p.addPotionEffect(new PotionEffect(PotionEffectType.SATURATION, PotionEffect.INFINITE_DURATION, 0, false,
                    false, false));
        }

        for (UUID hid : hiders.keySet()) {
            Player p = Bukkit.getPlayer(hid);
            if (p == null)
                continue;

            teleportToRandomSpawn(p, selectedArena.hiderSpawns);
            p.setGameMode(GameMode.ADVENTURE);
            p.setGravity(true);
            p.setInvisible(true);
            assignPlayerToNoCollisionTeam(p, true);
            giveHiderMenuItem(p);
            assignRandomBlock(p);
            GameMessageUtils.sendTitle(p,
                    msg("titles.hider-role.title", "&aYou are a HIDER"),
                    msg("titles.hider-role.subtitle", "&eStand still to solidify before release"));
            p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, PotionEffect.INFINITE_DURATION, 0, false,
                    false, false));
            p.addPotionEffect(new PotionEffect(PotionEffectType.SATURATION, PotionEffect.INFINITE_DURATION, 0, false,
                    false, false));
        }

        if (selectedArena != null && selectedArena.hasCuboid() && selectedArena.pos1.getWorld() != null) {
            WorldBorderUtils.applyArenaWorldBorders(
                    GameMessageUtils.matchRecipients(hiders.keySet(), seekers),
                    selectedArena.pos1,
                    selectedArena.pos2,
                    selectedArena.hiderSpawns,
                    selectedArena.seekerSpawns);
        }

        PlatformScheduler scheduler = plugin.getPlatformScheduler();

        // Run hider locking tick immediately, so solidifying works during hiding phase.
        tickTask = scheduler.runGlobalRepeating(this::gameTick, 1L, 1L);

        seekerGraceCountdown = seekerGracePeriod;
        countdownTask = scheduler.runGlobalRepeating(() -> {
            seekerGraceCountdown--;
            if (seekerGraceCountdown <= 0) {
                countdownTask.cancel();
                releaseSeekersPhase();
            } else {
                String countdownText;
                String countdownTitle;
                if (seekerGraceCountdown > countdownTitleThresholdSeconds) {
                    countdownTitle = msg("titles.seekers-waiting.title", "&cWaiting for hiders to hide...");
                    countdownText = msg("titles.seekers-waiting.subtitle", "&7Releasing in {seconds} seconds",
                            Map.of("seconds", seekerGraceCountdown));
                } else {
                    countdownTitle = msg("titles.release-countdown.title", "&cRelease in {seconds}",
                            Map.of("seconds", seekerGraceCountdown));
                    countdownText = msg("titles.release-countdown.subtitle", "&7Get ready");
                }

                for (UUID sid : seekers) {
                    Player p = Bukkit.getPlayer(sid);
                    if (p != null) {
                        GameMessageUtils.updateTitle(p, countdownTitle, countdownText);
                    }
                }
            }
        }, 20L, 20L);
    }

    private void startError(CommandSender sender, String message) {
        if (sender != null) {
            sender.sendMessage(plugin.applyCommandPrefix(message));
        } else {
            String prefix = msg("messages.prefix", "&6[PropHunt] &r");
            Set<UUID> matchAndAdmins = GameMessageUtils.matchAndAdminRecipients(hiders.keySet(), seekers,
                    "prophunt.admin");
            GameMessageUtils.sendPrefixedChat(matchAndAdmins, prefix, message);
        }
    }

    private void releaseSeekersPhase() {
        state = State.SEEKING_PHASE;
        String prefix = msg("messages.prefix", "&6[PropHunt] &r");
        Set<UUID> participants = GameMessageUtils.matchRecipients(hiders.keySet(), seekers);
        GameMessageUtils.sendPrefixedChat(
                participants,
                prefix,
                msg("messages.game.seekers-released", "&cSeekers RELEASED! Find the props!"));
        Set<UUID> matchAndAdmins = GameMessageUtils.matchAndAdminRecipients(hiders.keySet(), seekers, "prophunt.admin");
        GameMessageUtils.sendTitle(
                matchAndAdmins,
                msg("titles.seekers-released.title", "&cSeekers Released"),
                msg("titles.seekers-released.subtitle", "&eFind the props"));
        for (UUID sid : seekers) {
            Player p = Bukkit.getPlayer(sid);
            if (p != null) {
                unfreezeSeeker(p);
                p.setGameMode(GameMode.ADVENTURE);
                GameMessageUtils.sendTitle(p, msg("titles.seekers-go.title", "&cGO"),
                        msg("titles.seekers-go.subtitle", "&eFind the hiders"));
            }
        }
        gameSecondsRemaining = gameDuration;
        gameTimerTask = plugin.getPlatformScheduler().runGlobalRepeating(() -> {
            gameSecondsRemaining -= 1;
            if (gameSecondsRemaining <= 0) {
                gameTimerTask.cancel();
                endGame(true);
            } else if (gameSecondsRemaining == 60) {
                messageRemainingBlocksToSeekers();
                if (roundWarningSeconds.contains(gameSecondsRemaining)) {
                    GameMessageUtils.sendPrefixedChat(participants, prefix, msg(
                            "messages.game.time-remaining",
                            "&e{time} remaining!",
                            Map.of("time", GameFormatUtils.formatRemainingTime(gameSecondsRemaining), "seconds",
                                    gameSecondsRemaining)));
                }
            } else if (roundWarningSeconds.contains(gameSecondsRemaining)) {
                GameMessageUtils.sendPrefixedChat(participants, prefix, msg(
                        "messages.game.time-remaining",
                        "&e{time} remaining!",
                        Map.of("time", GameFormatUtils.formatRemainingTime(gameSecondsRemaining), "seconds",
                                gameSecondsRemaining)));
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
                if (!shouldReapplyFrozenView(freezeLoc, seeker.getLocation()))
                    continue;

                seeker.teleport(freezeLoc.clone());
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
                    double yOffset = HiderData.requiresVerticalSpace(data.getChosenBlock()) ? 2.001 : 1.001;
                    Location anchor = data.getPlacedBlockLocation().clone().add(0.5, yOffset, 0.5);
                    anchor.setYaw(p.getLocation().getYaw());
                    anchor.setPitch(p.getLocation().getPitch());

                    if (isWithinLockMovementGrace(p)) {
                        continue;
                    }

                    double horizontalDriftSquared = horizontalDistanceSquaredFromAnchor(p.getLocation(), anchor);
                    double snapBackDriftSquared = LOCKED_HIDER_SNAP_BACK_DRIFT * LOCKED_HIDER_SNAP_BACK_DRIFT;
                    if (horizontalDriftSquared > snapBackDriftSquared) {
                        p.teleport(anchor);
                    }
                }
                continue;
            }

            p.setInvisible(true);
            data.updateMobileDisguisePosition(p);
            if (isInBlockSelectionMenu(p)) {
                data.resetStillTicks();
                continue;
            }

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
        Material chosenBlock = data.getChosenBlock();
        if (!canSolidifyAtCurrentPosition(p, chosenBlock)) {
            data.setLocked(false);
            data.resetStillTicks();
            GameMessageUtils.sendTitle(
                    p,
                    msg("titles.unlocked.title", "&6UNLOCKED"),
                    msg("titles.unlocked.subtitle", "&aKeep moving"));
            p.sendMessage(msg("messages.game.cannot-solidify-block", "&cThis disguise cannot solidify here."));
            return;
        }

        Location anchor = getFloorAnchorLocation(p, data.getChosenBlock());
        if (anchor == null) {
            data.setLocked(false);
            data.resetStillTicks();
            GameMessageUtils.sendTitle(
                    p,
                    msg("titles.unlocked.title", "&6UNLOCKED"),
                    msg("titles.unlocked.subtitle", "&aKeep moving"));
            p.sendMessage(msg("messages.game.cannot-solidify-floor", "&cYou cannot solidify on this surface."));
            return;
        }

        data.setLocked(true);
        data.resetStillTicks();
        p.setInvisible(true);
        data.setLockedAnchor(anchor);
        data.placeWorldBlock(anchor, p);
        data.removeDisplay();

        // While locked, remove the personal world border so border push cannot pop the
        // hider out.
        p.setWorldBorder(null);

        // Keep hiders in adventure so they remain valid spectator-teleport targets.
        p.setGameMode(GameMode.ADVENTURE);
        p.setAllowFlight(false);
        p.setFlying(false);
        p.setGravity(false);
        double yOffset = HiderData.requiresVerticalSpace(chosenBlock) ? 2.001 : 1.001;
        Location lockTp = anchor.clone().add(0.0, yOffset, 0.0);
        lockTp.setYaw(p.getLocation().getYaw());
        lockTp.setPitch(p.getLocation().getPitch());
        p.teleport(lockTp);
        lockedMovementGraceUntil.put(p.getUniqueId(), System.currentTimeMillis() + LOCKED_HIDER_MOVEMENT_GRACE_MS);
        hideHiderFromSeekers(p);

        GameMessageUtils.sendTitle(p, msg("titles.solidified.title", "&bSOLIDIFIED"),
                msg("titles.solidified.subtitle", "&eYou are locked in place"));
        Material chosen = data.getChosenBlock();
        Sound placeSound = chosen != null
                ? chosen.createBlockData().getSoundGroup().getPlaceSound()
                : Sound.BLOCK_STONE_PLACE;
        p.playSound(p.getLocation(), placeSound, 1f, 1f);
    }

    private boolean canSolidifyAtCurrentPosition(Player player, Material material) {
        if (material == null || !material.isBlock() || material.isAir())
            return false;
        if (player == null || player.getWorld() == null)
            return false;

        Location loc = player.getLocation();
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();
        World world = player.getWorld();

        // Prevent solidifying while standing inside any occupied block space.
        if (!isValidAnchorSpace(world.getBlockAt(x, y, z).getType(), material))
            return false;

        boolean needsVerticalSpace = HiderData.requiresVerticalSpace(material);
        if (needsVerticalSpace && !isValidAnchorSpace(world.getBlockAt(x, y + 1, z).getType(), material))
            return false;

        HiderData.HorizontalOffset horizontalOffset = HiderData.horizontalOffset(material, player);
        if (horizontalOffset.x() != 0 || horizontalOffset.z() != 0) {
            int hx = x + horizontalOffset.x();
            int hz = z + horizontalOffset.z();

            if (!isValidAnchorSpace(world.getBlockAt(hx, y, hz).getType(), material))
                return false;
            if (needsVerticalSpace && !isValidAnchorSpace(world.getBlockAt(hx, y + 1, hz).getType(), material))
                return false;
        }

        return true;
    }

    private Location getFloorAnchorLocation(Player p, Material chosenBlock) {
        Location loc = p.getLocation();
        if (loc.getWorld() == null)
            return null;

        int x = loc.getBlockX();
        int z = loc.getBlockZ();
        int y = loc.getBlockY();
        var world = loc.getWorld();

        // Anchor only at the current support level to avoid sinking the player
        // into lower floors when standing on invalid support (e.g.
        // anvils/slabs/stairs).
        boolean needsVerticalSpace = HiderData.requiresVerticalSpace(chosenBlock);
        HiderData.HorizontalOffset horizontalOffset = HiderData.horizontalOffset(chosenBlock, p);
        boolean needsHorizontalSpace = horizontalOffset.x() != 0 || horizontalOffset.z() != 0;

        Material supportAtFeet = world.getBlockAt(x, y, z).getType();
        boolean standingInBlock = !isValidAnchorSpace(supportAtFeet, chosenBlock);
        if (standingInBlock && !isValidFloorBlock(supportAtFeet)) {
            // Example: anvil/slab/stairs. Do not sink the player to a lower floor.
            return null;
        }

        int floorY = standingInBlock ? y : y - 1;
        Material floorType = world.getBlockAt(x, floorY, z).getType();
        Material anchorType = world.getBlockAt(x, floorY + 1, z).getType();
        Material upperAnchorType = world.getBlockAt(x, floorY + 2, z).getType();

        Material horizontalFloorType = needsHorizontalSpace
                ? world.getBlockAt(x + horizontalOffset.x(), floorY, z + horizontalOffset.z()).getType()
                : Material.AIR;
        Material horizontalAnchorType = needsHorizontalSpace
                ? world.getBlockAt(x + horizontalOffset.x(), floorY + 1, z + horizontalOffset.z()).getType()
                : Material.AIR;

        if (!(isValidFloorBlock(floorType)
                && isValidAnchorSpace(anchorType, chosenBlock)
                && (!needsHorizontalSpace || (isValidFloorBlock(horizontalFloorType)
                        && isValidAnchorSpace(horizontalAnchorType, chosenBlock)))
                && (!needsVerticalSpace || isValidAnchorSpace(upperAnchorType, chosenBlock)))) {
            return null;
        }

        return new Location(world, x + 0.5, floorY + 1.0, z + 0.5);
    }

    private boolean isValidAnchorSpace(Material mat, Material chosenBlock) {
        // Only allow placing into air-like blocks so we never replace decorations
        // such as panes, walls, fences, torches, flower pots, etc.
        if (mat.isAir())
            return true;
        if (chosenBlock == null)
            return false;
        if (mat == chosenBlock)
            return true;

        // Allow replacing equivalent disguise families to make solidification
        // consistent.
        if (isCauldronFamily(mat) && isCauldronFamily(chosenBlock))
            return true;
        if (isFenceFamily(mat) && isFenceFamily(chosenBlock))
            return true;
        if (isPaneFamily(mat) && isPaneFamily(chosenBlock))
            return true;
        return isGrassFamily(mat) && isGrassFamily(chosenBlock);
    }

    private boolean isCauldronFamily(Material material) {
        return material == Material.CAULDRON
                || material == Material.WATER_CAULDRON
                || material == Material.LAVA_CAULDRON
                || material == Material.POWDER_SNOW_CAULDRON;
    }

    private boolean isGrassFamily(Material material) {
        return material == Material.SHORT_GRASS
                || material == Material.TALL_GRASS
                || material == Material.FERN
                || material == Material.LARGE_FERN;
    }

    private boolean isFenceFamily(Material material) {
        return Tag.FENCES.isTagged(material) || material == Material.NETHER_BRICK_FENCE;
    }

    private boolean isPaneFamily(Material material) {
        if (material == null)
            return false;
        return material == Material.GLASS_PANE || material.name().endsWith("_PANE");
    }

    private boolean isValidFloorBlock(Material mat) {
        if (!mat.isSolid() || mat.isAir())
            return false;

        // Reject multi-block structures and problematic materials
        return switch (mat) {
            // Doors (all types)
            case IRON_DOOR, OAK_DOOR, SPRUCE_DOOR, BIRCH_DOOR, JUNGLE_DOOR, ACACIA_DOOR,
                    DARK_OAK_DOOR, MANGROVE_DOOR, CHERRY_DOOR, PALE_OAK_DOOR, CRIMSON_DOOR, WARPED_DOOR ->
                false;
            // Trapdoors (all types)
            case IRON_TRAPDOOR, OAK_TRAPDOOR, SPRUCE_TRAPDOOR, BIRCH_TRAPDOOR, JUNGLE_TRAPDOOR,
                    ACACIA_TRAPDOOR, DARK_OAK_TRAPDOOR, MANGROVE_TRAPDOOR, CHERRY_TRAPDOOR, PALE_OAK_TRAPDOOR,
                    CRIMSON_TRAPDOOR, WARPED_TRAPDOOR ->
                false;
            // Beds and other multi-block furniture
            case RED_BED, BLACK_BED, BLUE_BED, BROWN_BED, CYAN_BED, GRAY_BED, GREEN_BED, LIGHT_BLUE_BED,
                    LIGHT_GRAY_BED, LIME_BED, MAGENTA_BED, ORANGE_BED, PINK_BED, PURPLE_BED, WHITE_BED, YELLOW_BED ->
                false;
            // Flower pots (fragile decorations)
            case FLOWER_POT, POTTED_OAK_SAPLING, POTTED_SPRUCE_SAPLING, POTTED_BIRCH_SAPLING, POTTED_JUNGLE_SAPLING,
                    POTTED_ACACIA_SAPLING, POTTED_DARK_OAK_SAPLING, POTTED_MANGROVE_PROPAGULE, POTTED_CHERRY_SAPLING,
                    POTTED_PALE_OAK_SAPLING, POTTED_FERN, POTTED_DANDELION, POTTED_POPPY, POTTED_BLUE_ORCHID,
                    POTTED_ALLIUM, POTTED_AZURE_BLUET, POTTED_RED_TULIP, POTTED_ORANGE_TULIP, POTTED_WHITE_TULIP,
                    POTTED_PINK_TULIP, POTTED_OXEYE_DAISY, POTTED_CORNFLOWER, POTTED_LILY_OF_THE_VALLEY,
                    POTTED_WITHER_ROSE, POTTED_TORCHFLOWER, POTTED_DEAD_BUSH, POTTED_CACTUS, POTTED_BAMBOO,
                    POTTED_CRIMSON_FUNGUS, POTTED_WARPED_FUNGUS, POTTED_CRIMSON_ROOTS, POTTED_WARPED_ROOTS ->
                false;
            // Double chests (use single chest as floor instead)
            case CHEST -> false;
            // Pistons
            case PISTON, STICKY_PISTON, MOVING_PISTON -> false;
            // Rails
            case RAIL, POWERED_RAIL, DETECTOR_RAIL, ACTIVATOR_RAIL -> false;
            // Slabs and stairs (uneven surfaces that can cause teleportation issues)
            case OAK_SLAB, SPRUCE_SLAB, BIRCH_SLAB, JUNGLE_SLAB, ACACIA_SLAB, DARK_OAK_SLAB, MANGROVE_SLAB,
                    CHERRY_SLAB, PALE_OAK_SLAB, CRIMSON_SLAB, WARPED_SLAB,
                    OAK_STAIRS, SPRUCE_STAIRS, BIRCH_STAIRS, JUNGLE_STAIRS, ACACIA_STAIRS, DARK_OAK_STAIRS,
                    MANGROVE_STAIRS, CHERRY_STAIRS, PALE_OAK_STAIRS, CRIMSON_STAIRS, WARPED_STAIRS ->
                false;
            // Other non-full blocks that can cause issues
            case GLASS_PANE, IRON_BARS, COBBLESTONE_WALL, MOSSY_COBBLESTONE_WALL, GRANITE_WALL, DIORITE_WALL,
                    ANDESITE_WALL,
                    NETHER_BRICK_FENCE, BONE_BLOCK, ANVIL, CHIPPED_ANVIL, DAMAGED_ANVIL,
                    BEACON, ENCHANTING_TABLE ->
                false;
            // Other problematic blocks
            case BEDROCK, BARRIER, STRUCTURE_BLOCK, JIGSAW, END_PORTAL_FRAME, END_PORTAL,
                    SPAWNER ->
                false;

            default -> true;
        };
    }

    public void unlockHider(Player p) {
        HiderData data = hiders.get(p.getUniqueId());
        if (data == null || !data.isLocked())
            return;

        data.setLocked(false);
        lockedMovementGraceUntil.remove(p.getUniqueId());
        Location revealLoc = data.getPlacedBlockLocation() == null ? p.getLocation()
                : data.getPlacedBlockLocation().clone().add(0.5, 0.0, 0.5);
        data.restoreWorldBlock();

        p.setGameMode(GameMode.ADVENTURE);
        p.setAllowFlight(false);
        p.setGravity(true);
        p.teleport(revealLoc);
        p.setInvisible(true);
        if (state == State.HIDING_PHASE || state == State.SEEKING_PHASE) {
            if (selectedArena != null && selectedArena.hasCuboid() && selectedArena.pos1.getWorld() != null) {
                WorldBorderUtils.applyArenaWorldBorder(
                        p,
                        selectedArena.pos1,
                        selectedArena.pos2,
                        selectedArena.hiderSpawns,
                        selectedArena.seekerSpawns);
            }
        }

        data.spawnDisplay(p);
        showHiderToSeekers(p);
        GameMessageUtils.sendTitle(p, msg("titles.unlocked.title", "&eUNLOCKED"),
                msg("titles.unlocked.subtitle", "&aKeep moving"));
    }

    public void handleHiderHit(Player seeker, Player hider) {
        if (state != State.SEEKING_PHASE)
            return;
        if (hider == null || !hider.isOnline())
            return;

        HiderData data = hiders.get(hider.getUniqueId());
        if (data == null)
            return;
        if (data.isLocked())
            return;

        applyHiderHit(seeker, hider.getUniqueId());
    }

    public void handleHiderHit(Player seeker, org.bukkit.entity.BlockDisplay display) {
        if (state != State.SEEKING_PHASE)
            return;
        UUID targetUUID = null;
        for (Map.Entry<UUID, HiderData> entry : hiders.entrySet()) {
            if (entry.getValue().ownsDisplay(display)) {
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
            if (entry.getValue().occupiesWorldBlock(blockLocation)) {
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
        seekers.add(uid);
        lockedMovementGraceUntil.remove(uid);

        Player hider = Bukkit.getPlayer(uid);
        if (hider != null) {
            removeHiderMenuItem(hider);
            assignPlayerToNoCollisionTeam(hider, false);
            hider.setInvisible(false);
            hider.setGameMode(GameMode.ADVENTURE);
            hider.setAllowFlight(false);
            hider.setGravity(true);
            showHiderToSeekers(hider);
            if (selectedArena != null && !selectedArena.seekerSpawns.isEmpty()) {
                teleportToRandomSpawn(hider, selectedArena.seekerSpawns);
            }
            if (selectedArena != null && selectedArena.hasCuboid() && selectedArena.pos1.getWorld() != null) {
                WorldBorderUtils.applyArenaWorldBorder(
                        hider,
                        selectedArena.pos1,
                        selectedArena.pos2,
                        selectedArena.hiderSpawns,
                        selectedArena.seekerSpawns);
            }
            giveSeekerVisualWeapon(hider);
            hideLockedHidersFromSeeker(hider);
            GameMessageUtils.sendTitle(hider, msg("titles.found.title", "&cYou Were Found"),
                    msg("titles.found.subtitle", "&eYou are now a seeker"));
            hider.playSound(hider.getLocation(), Sound.ENTITY_PLAYER_DEATH, 1f, 1f);
        }

        GameMessageUtils.sendPrefixedChat(
                GameMessageUtils.matchRecipients(hiders.keySet(), seekers),
                msg("messages.prefix", "&6[PropHunt] &r"),
                msg(
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
        GameMessageUtils.sendTitle(
                GameMessageUtils.matchAndAdminRecipients(hiders.keySet(), seekers, "prophunt.admin"),
                hidersWin ? msg("titles.hiders-win.title", "&aHIDERS WIN")
                        : msg("titles.seekers-win.title", "&cSEEKERS WIN"),
                hidersWin ? msg("titles.hiders-win.subtitle", "&eRound over")
                        : msg("titles.seekers-win.subtitle", "&eRound over"));
        for (Map.Entry<UUID, HiderData> e : hiders.entrySet()) {
            e.getValue().clearDisguise();
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) {
                removeHiderMenuItem(p);
                removePlayerFromCollisionTeams(p);
                showHiderToSeekers(p);
                resetPlayerAfterGame(p, GameMode.ADVENTURE);
            }
        }
        for (UUID uid : seekers) {
            Player p = Bukkit.getPlayer(uid);
            if (p != null) {
                removeHiderMenuItem(p);
                removePlayerFromCollisionTeams(p);
                restoreSeekerVisualWeapon(p);
                resetPlayerAfterGame(p, GameMode.ADVENTURE);
            }
        }

        cleanup();
        plugin.getPlatformScheduler().runGlobalLater(() -> state = State.WAITING, 100L);
    }

    private void cleanup() {
        WorldBorderUtils.clearWorldBorders(GameMessageUtils.matchRecipients(hiders.keySet(), seekers));
        hiders.clear();
        seekers.clear();
        lastLocations.clear();
        frozenSeekers.clear();
        lockedMovementGraceUntil.clear();
        blockSelectionMenuPlayers.clear();
        seekerGraceCountdown = 0;
        gameSecondsRemaining = 0;
        queueOpen = false;
        queuedPlayers.clear();
        selectedArena = null;
        currentArenaBlockPool.clear();
        currentArenaBlockPool.addAll(defaultHiderBlocks);
    }

    public void forceStop() {
        if (tickTask != null)
            tickTask.cancel();
        if (countdownTask != null)
            countdownTask.cancel();
        if (gameTimerTask != null)
            gameTimerTask.cancel();

        Set<UUID> participants = GameMessageUtils.matchRecipients(hiders.keySet(), seekers);

        unfreezeAllSeekers();
        for (HiderData d : hiders.values())
            d.clearDisguise();

        for (UUID uid : hiders.keySet()) {
            Player p = Bukkit.getPlayer(uid);
            if (p != null) {
                removeHiderMenuItem(p);
                removePlayerFromCollisionTeams(p);
                showHiderToSeekers(p);
                resetPlayerAfterGame(p, GameMode.SURVIVAL);
            }
        }

        for (UUID uid : seekers) {
            Player p = Bukkit.getPlayer(uid);
            if (p != null) {
                removeHiderMenuItem(p);
                removePlayerFromCollisionTeams(p);
                restoreSeekerVisualWeapon(p);
                resetPlayerAfterGame(p, GameMode.SURVIVAL);
            }
        }

        GameMessageUtils.sendPrefixedChat(
                participants,
                msg("messages.prefix", "&6[PropHunt] &r"),
                msg("messages.game.force-stopped", "&cThe game was force stopped by an admin."));

        cleanup();
        state = State.WAITING;
    }

    public void handlePlayerQuit(Player p) {
        HiderData data = hiders.get(p.getUniqueId());
        if (data != null)
            data.clearDisguise();

        queuedPlayers.remove(p.getUniqueId());
        frozenSeekers.remove(p.getUniqueId());
        lockedMovementGraceUntil.remove(p.getUniqueId());
        blockSelectionMenuPlayers.remove(p.getUniqueId());
        removeHiderMenuItem(p);
        removePlayerFromCollisionTeams(p);
        restoreSeekerVisualWeapon(p);
        p.setWorldBorder(null);
    }

    private void assignPlayerToNoCollisionTeam(Player player, boolean hiderRole) {
        if (player == null)
            return;

        Team hiderTeam = ensureNoCollisionTeam(HIDER_NO_COLLISION_TEAM);
        Team seekerTeam = ensureNoCollisionTeam(SEEKER_NO_COLLISION_TEAM);
        if (hiderTeam == null || seekerTeam == null)
            return;

        String entry = player.getName();
        hiderTeam.removeEntry(entry);
        seekerTeam.removeEntry(entry);

        if (hiderRole) {
            hiderTeam.addEntry(entry);
            return;
        }

        seekerTeam.addEntry(entry);
    }

    private void removePlayerFromCollisionTeams(Player player) {
        if (player == null)
            return;

        Team hiderTeam = ensureNoCollisionTeam(HIDER_NO_COLLISION_TEAM);
        Team seekerTeam = ensureNoCollisionTeam(SEEKER_NO_COLLISION_TEAM);
        if (hiderTeam == null || seekerTeam == null)
            return;

        String entry = player.getName();
        hiderTeam.removeEntry(entry);
        seekerTeam.removeEntry(entry);
    }

    private Team ensureNoCollisionTeam(String teamName) {
        ScoreboardManager scoreboardManager = Bukkit.getScoreboardManager();
        if (scoreboardManager == null)
            return null;

        Scoreboard scoreboard = scoreboardManager.getMainScoreboard();
        Team team = scoreboard.getTeam(teamName);
        if (team == null) {
            team = scoreboard.registerNewTeam(teamName);
        }

        team.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
        return team;
    }

    private void resetPlayerAfterGame(Player player, GameMode gameMode) {
        if (player == null)
            return;

        player.setInvisible(false);
        player.setGameMode(gameMode);
        player.setAllowFlight(false);
        player.setGravity(true);
        clearAllPotionEffects(player);
    }

    private void clearAllPotionEffects(Player player) {
        if (player == null)
            return;

        player.clearActivePotionEffects();
        for (PotionEffect effect : new ArrayList<>(player.getActivePotionEffects())) {
            player.removePotionEffect(effect.getType());
        }
    }

    private void hideHiderFromSeekers(Player hider) {
        if (hider == null)
            return;

        for (UUID sid : seekers) {
            Player seeker = Bukkit.getPlayer(sid);
            if (seeker == null || !seeker.isOnline())
                continue;

            seeker.hidePlayer(plugin, hider);
        }
    }

    private void showHiderToSeekers(Player hider) {
        if (hider == null)
            return;

        for (UUID sid : seekers) {
            Player seeker = Bukkit.getPlayer(sid);
            if (seeker == null || !seeker.isOnline())
                continue;

            seeker.showPlayer(plugin, hider);
        }
    }

    private void hideLockedHidersFromSeeker(Player seeker) {
        if (seeker == null)
            return;

        for (Map.Entry<UUID, HiderData> entry : hiders.entrySet()) {
            if (!entry.getValue().isLocked())
                continue;

            Player lockedHider = Bukkit.getPlayer(entry.getKey());
            if (lockedHider == null || !lockedHider.isOnline())
                continue;

            seeker.hidePlayer(plugin, lockedHider);
        }
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
        Collection<Material> source = currentArenaBlockPool.isEmpty() ? defaultHiderBlocks : currentArenaBlockPool;
        if (source.isEmpty())
            source = FALLBACK_HIDER_BLOCKS;

        return new ArrayList<>(new LinkedHashSet<>(source));
    }

    public String createArena(String name) {
        ArenaUtils.ArenaMutationResult result = ArenaUtils.createArena(plugin.getConfig(), name);
        if (result.status() == ArenaUtils.ArenaMutationStatus.ALREADY_EXISTS) {
            return msg("messages.arena.already-exists", "&cArena already exists: {arena}",
                    Map.of("arena", result.key()));
        }

        plugin.saveConfig();
        return msg("messages.arena.created", "&aCreated arena: {arena}", Map.of("arena", result.key()));
    }

    public String removeArena(String name) {
        ArenaUtils.ArenaMutationResult result = ArenaUtils.removeArena(plugin.getConfig(), name);
        if (result.status() == ArenaUtils.ArenaMutationStatus.NOT_FOUND) {
            return msg("messages.arena.not-found", "&cArena not found: {arena}", Map.of("arena", result.key()));
        }

        plugin.saveConfig();
        return msg("messages.arena.removed", "&aRemoved arena: {arena}", Map.of("arena", result.key()));
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
        return ArenaUtils.getArenaNames(plugin.getConfig());
    }

    public String arenaInfo(String name) {
        ArenaUtils.ArenaInfo info = ArenaUtils.getArenaInfo(plugin.getConfig(), name);
        if (!info.exists())
            return msg("messages.arena.not-found", "&cArena not found: {arena}", Map.of("arena", info.key()));

        return msg(
                "messages.arena.info",
                "&6Arena {arena}&7 | cuboid={cuboid} | hiderSpawns={hiders} | seekerSpawns={seekers}",
                Map.of(
                        "arena", info.key(),
                        "cuboid", info.hasPos1() && info.hasPos2(),
                        "hiders", info.hiderSpawnCount(),
                        "seekers", info.seekerSpawnCount()));
    }

    private String setArenaPos(Player player, String name, String posKey) {
        ArenaUtils.ArenaMutationResult result = ArenaUtils.setArenaPos(plugin.getConfig(), player, name, posKey);
        if (result.status() == ArenaUtils.ArenaMutationStatus.NOT_FOUND) {
            return msg("messages.arena.not-found", "&cArena not found: {arena}", Map.of("arena", result.key()));
        }

        plugin.saveConfig();

        // Trigger block pre-generation when cuboid is completed
        if (result.pos1() != null && result.pos2() != null) {
            ArenaRuntime tempArena = new ArenaRuntime(result.key(), result.world(), result.pos1(), result.pos2(),
                    new ArrayList<>(), new ArrayList<>());
            if (!tempArena.blockPoolGenerated) {
                ArenaUtils.preGenerateArenaBlocks(
                        tempArena.pos1,
                        tempArena.pos2,
                        arenaScanMaxBlocks,
                        arenaMinBlockOccurrences,
                        this::isAllowedPropMaterial,
                        tempArena.cachedBlockPool,
                        runnable -> plugin.getPlatformScheduler().runAsync(runnable),
                        () -> tempArena.blockPoolGenerated = true);
            }
        }

        return msg("messages.arena.set-pos", "&aSet {pos} for arena {arena}.",
                Map.of("pos", posKey, "arena", result.key()));
    }

    private String addSpawn(Player player, String name, boolean hider) {
        ArenaUtils.ArenaMutationResult result = ArenaUtils.addSpawn(plugin.getConfig(), player, name, hider);
        if (result.status() == ArenaUtils.ArenaMutationStatus.NOT_FOUND) {
            return msg("messages.arena.not-found", "&cArena not found: {arena}", Map.of("arena", result.key()));
        }
        if (result.status() == ArenaUtils.ArenaMutationStatus.MISSING_CUBOID) {
            return msg("messages.arena.spawn-needs-cuboid", "&cSet pos1 and pos2 for this arena before adding spawns.");
        }
        if (result.status() == ArenaUtils.ArenaMutationStatus.OUTSIDE_CUBOID) {
            return msg("messages.arena.spawn-outside-cuboid", "&cSpawn must be inside the arena cuboid.");
        }

        plugin.saveConfig();
        return msg(
                "messages.arena.add-spawn",
                "&aAdded {type} spawn to arena {arena}.",
                Map.of("type", hider ? "hider" : "seeker", "arena", result.key()));
    }

    private List<ArenaRuntime> loadArenasFromConfig() {
        List<ArenaUtils.ArenaDefinition> loadedArenas = ArenaUtils.loadArenas(plugin.getConfig());
        if (loadedArenas.isEmpty())
            return Collections.emptyList();

        List<ArenaRuntime> arenas = new ArrayList<>();
        for (ArenaUtils.ArenaDefinition loaded : loadedArenas) {
            ArenaRuntime arena = new ArenaRuntime(loaded.name(), loaded.world(), loaded.pos1(), loaded.pos2(),
                    loaded.hiderSpawns(), loaded.seekerSpawns());
            arenas.add(arena);

            // Pre-generate block pool asynchronously when arena is loaded
            if (!arena.blockPoolGenerated) {
                ArenaUtils.preGenerateArenaBlocks(
                        arena.pos1,
                        arena.pos2,
                        arenaScanMaxBlocks,
                        arenaMinBlockOccurrences,
                        this::isAllowedPropMaterial,
                        arena.cachedBlockPool,
                        runnable -> plugin.getPlatformScheduler().runAsync(runnable),
                        () -> arena.blockPoolGenerated = true);
            }
        }
        return arenas;
    }

    private void teleportToRandomSpawn(Player p, List<Location> spawns) {
        if (spawns.isEmpty())
            return;

        Location target = spawns.get(ThreadLocalRandom.current().nextInt(spawns.size()));
        p.teleport(target);
    }

    private boolean isAllowedPropMaterial(Material mat) {
        if (mat == null)
            return false;
        if (!mat.isBlock() || mat.isAir())
            return false;
        if (forceDenyDisguiseBlocks.contains(mat))
            return false;
        if (forceAllowDisguiseBlocks.contains(mat))
            return true;
        if (blockedDisguiseBlocks.contains(mat))
            return false;
        if (!mat.isSolid() && !allowedNonSolidDisguiseBlocks.contains(mat))
            return false;

        return true;
    }

    private void freezeSeeker(Player player) {
        Location freezeLocation = player.getLocation().clone();
        freezeLocation.setPitch(90f);
        frozenSeekers.put(player.getUniqueId(), freezeLocation);
        player.teleport(freezeLocation);
        player.setWalkSpeed(0f);
        player.setFlySpeed(0f);
        player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS,
                (seekerGracePeriod + freezeBlindnessBufferSeconds) * 20, 1, false, false, false));
    }

    private void unfreezeSeeker(Player player) {
        frozenSeekers.remove(player.getUniqueId());
        player.setWalkSpeed(0.2f);
        player.setFlySpeed(0.1f);
        player.removePotionEffect(PotionEffectType.BLINDNESS);
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
            if (i < seekerCount) {
                seekers.add(uid);
            } else {
                hiders.put(uid, new HiderData(uid, hiderMaxHp));
            }
        }
    }

    private void giveSeekerVisualWeapon(Player player) {
        if (player == null)
            return;
        UUID playerId = player.getUniqueId();
        seekerHeldItemSnapshots.computeIfAbsent(playerId, ignored -> {
            ItemStack current = player.getInventory().getItem(SEEKER_WEAPON_SLOT);
            return current == null ? null : current.clone();
        });

        removeSeekerVisualWeapons(player);
        player.getInventory().setItem(SEEKER_WEAPON_SLOT, createSeekerVisualWeapon());
        player.getInventory().setHeldItemSlot(SEEKER_WEAPON_SLOT);
    }

    private void restoreSeekerVisualWeapon(Player player) {
        if (player == null)
            return;

        UUID playerId = player.getUniqueId();
        ItemStack previous = seekerHeldItemSnapshots.remove(playerId);
        removeSeekerVisualWeapons(player);
        if (previous != null) {
            player.getInventory().setItem(SEEKER_WEAPON_SLOT, previous);
            return;
        }

        player.getInventory().setItem(SEEKER_WEAPON_SLOT, new ItemStack(Material.AIR));
    }

    private void removeSeekerVisualWeapons(Player player) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (isSeekerVisualWeapon(contents[slot])) {
                player.getInventory().setItem(slot, new ItemStack(Material.AIR));
            }
        }
    }

    private ItemStack createSeekerVisualWeapon() {
        ItemStack item = new ItemStack(Material.WOODEN_SWORD);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§6Seeker Sword");
            meta.setUnbreakable(true);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_UNBREAKABLE);
            item.setItemMeta(meta);
        }

        return item;
    }

    private boolean isSeekerVisualWeapon(ItemStack item) {
        if (item == null || item.getType() != Material.WOODEN_SWORD || !item.hasItemMeta())
            return false;

        ItemMeta meta = item.getItemMeta();
        return meta != null && SEEKER_WEAPON_NAME.equals(meta.getDisplayName()) && meta.isUnbreakable();
    }

    public boolean isLockedSeekerWeapon(ItemStack item) {
        return isSeekerVisualWeapon(item);
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
        if (isFinalMinuteBlockSwapLocked()) {
            p.sendMessage(msg(
                    "messages.game.block-change-locked-final-minute",
                    "&cYou cannot change disguise blocks in the final minute."));
            return;
        }
        if (data.isLocked()) {
            p.sendMessage(msg("messages.game.unlock-first", "&cUnlock first by moving!"));
            return;
        }
        if (!isAllowedPropMaterial(block)) {
            p.sendMessage(msg("messages.game.invalid-disguise", "&cThat block cannot be used as a disguise."));
            return;
        }
        data.setChosenBlock(block);
        data.clearDisguise();
        data.spawnDisplay(p);
        p.sendMessage(msg("messages.game.disguised-as", "&aDisguised as {block}!", Map.of("block", block.name())));
    }

    public boolean openHiderBlockSelectionMenu(Player player) {
        if (player == null)
            return false;

        State currentState = getState();
        if (currentState != State.HIDING_PHASE && currentState != State.SEEKING_PHASE) {
            player.sendMessage(msg("messages.command.no-active-game", "&cNo active game."));
            return false;
        }

        if (!isHider(player)) {
            player.sendMessage(msg("messages.command.not-a-hider", "&cYou are not a hider."));
            return false;
        }

        if (isFinalMinuteBlockSwapLocked()) {
            player.sendMessage(msg(
                    "messages.game.block-change-locked-final-minute",
                    "&cYou cannot change disguise blocks in the final minute."));
            return false;
        }

        player.sendMessage(msg("messages.command.changing-disguise", "&eChanging disguise block..."));
        blockSelectGUI.open(player);
        return true;
    }

    public boolean isHiderMenuItem(ItemStack item) {
        if (item == null || item.getType() != Material.COMPASS || !item.hasItemMeta())
            return false;

        ItemMeta meta = item.getItemMeta();
        return meta != null && HIDER_MENU_ITEM_NAME.equals(meta.getDisplayName());
    }

    private boolean isFinalMinuteBlockSwapLocked() {
        return state == State.SEEKING_PHASE && gameSecondsRemaining > 0 && gameSecondsRemaining <= 60;
    }

    private void giveHiderMenuItem(Player player) {
        if (player == null)
            return;

        PlayerInventory inventory = player.getInventory();
        ItemStack slotItem = inventory.getItem(HIDER_MENU_SLOT);
        if (slotItem != null && !slotItem.getType().isAir() && !isHiderMenuItem(slotItem)) {
            inventory.addItem(slotItem);
        }

        inventory.setItem(HIDER_MENU_SLOT, createHiderMenuItem());
    }

    private void removeHiderMenuItem(Player player) {
        if (player == null)
            return;

        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (isHiderMenuItem(contents[slot])) {
                inventory.setItem(slot, new ItemStack(Material.AIR));
            }
        }

        if (isHiderMenuItem(inventory.getItemInOffHand())) {
            inventory.setItemInOffHand(new ItemStack(Material.AIR));
        }
    }

    private ItemStack createHiderMenuItem() {
        ItemStack item = new ItemStack(Material.COMPASS);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(HIDER_MENU_ITEM_NAME);
            meta.setUnbreakable(true);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_UNBREAKABLE);
            item.setItemMeta(meta);
        }

        return item;
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

    private double horizontalDistanceSquaredFromAnchor(Location current, Location anchor) {
        if (current == null || anchor == null)
            return Double.MAX_VALUE;
        if (current.getWorld() == null || anchor.getWorld() == null)
            return Double.MAX_VALUE;
        if (!current.getWorld().equals(anchor.getWorld()))
            return Double.MAX_VALUE;

        double dx = current.getX() - anchor.getX();
        double dz = current.getZ() - anchor.getZ();
        return dx * dx + dz * dz;
    }

    private boolean isInBlockSelectionMenu(Player player) {
        if (player == null)
            return false;
        if (blockSelectionMenuPlayers.contains(player.getUniqueId()))
            return true;
        if (player.getOpenInventory() == null)
            return false;

        String title = player.getOpenInventory().getTitle();
        if (title == null)
            return false;

        String plain = ChatColor.stripColor(title);
        return plain != null && plain.startsWith("Pick Your Block");
    }

    public void setBlockSelectionMenuOpen(Player player, boolean open) {
        if (player == null)
            return;

        if (open) {
            blockSelectionMenuPlayers.add(player.getUniqueId());
        } else {
            blockSelectionMenuPlayers.remove(player.getUniqueId());
        }
    }

    private boolean shouldReapplyFrozenView(Location frozen, Location current) {
        if (!sameBlock(frozen, current))
            return true;

        return Math.abs(frozen.getYaw() - current.getYaw()) > 0.5f
                || Math.abs(frozen.getPitch() - current.getPitch()) > 0.5f;
    }

    public boolean isHider(Player p) {
        return hiders.containsKey(p.getUniqueId());
    }

    public boolean isSeeker(Player p) {
        return seekers.contains(p.getUniqueId());
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

    public boolean isWithinLockMovementGrace(Player player) {
        if (player == null)
            return false;

        Long until = lockedMovementGraceUntil.get(player.getUniqueId());
        if (until == null)
            return false;
        if (System.currentTimeMillis() <= until)
            return true;

        lockedMovementGraceUntil.remove(player.getUniqueId());
        return false;
    }

    private void messageRemainingBlocksToSeekers() {
        Map<Material, Integer> remainingCounts = new HashMap<>();
        for (HiderData data : hiders.values()) {
            Material chosenBlock = data.getChosenBlock();
            if (chosenBlock == null)
                continue;

            remainingCounts.merge(chosenBlock, 1, Integer::sum);
        }

        GameMessageUtils.sendPrefixedChat(seekers, msg("messages.prefix", "&6[PropHunt] &r"), msg(
                "messages.game.final-minute-blocks-header",
                "&6One minute left! &eRemaining hider blocks:"));

        if (remainingCounts.isEmpty()) {
            GameMessageUtils.sendPrefixedChat(seekers, msg("messages.prefix", "&6[PropHunt] &r"), msg(
                    "messages.game.final-minute-blocks-none",
                    "&7No hiders are left."));
            return;
        }

        List<Map.Entry<Material, Integer>> entries = new ArrayList<>(remainingCounts.entrySet());
        entries.sort((left, right) -> {
            int countCompare = Integer.compare(right.getValue(), left.getValue());
            if (countCompare != 0)
                return countCompare;

            return left.getKey().name().compareTo(right.getKey().name());
        });

        for (Map.Entry<Material, Integer> entry : entries) {
            GameMessageUtils.sendPrefixedChat(seekers, msg("messages.prefix", "&6[PropHunt] &r"), msg(
                    "messages.game.final-minute-blocks-line",
                    "&e{count}x &f{block}",
                    Map.of(
                            "count", entry.getValue(),
                            "block", GameFormatUtils.formatBlockName(entry.getKey()))));
        }
    }

    private void loadSettings() {
        FileConfiguration config = plugin.getConfig();
        lockTicksRequired = GameConfigReader.intSetting(config, "gameplay.lock-ticks", "lock-ticks", 60, 1);
        hiderMaxHp = GameConfigReader.intSetting(config, "gameplay.hider-hp", "hider-hp", 3, 1);
        seekerGracePeriod = GameConfigReader.intSetting(config, "gameplay.seeker-grace-seconds", "seeker-grace-seconds",
                30, 0);
        gameDuration = GameConfigReader.intSetting(config, "gameplay.game-duration-seconds", "game-duration-seconds",
                300, 1);
        arenaScanMaxBlocks = GameConfigReader.intSetting(config, "gameplay.arena-scan-max-blocks",
                "arena-scan-max-blocks", 120000, 1);
        arenaMinBlockOccurrences = GameConfigReader.intSetting(config, "gameplay.arena-min-block-occurrences", null, 8,
                1);
        playersPerSeeker = GameConfigReader.intSetting(config, "gameplay.players-per-seeker", null, 3, 1);
        seekerHitCooldownTicks = GameConfigReader.intSetting(config, "gameplay.seeker-hit-cooldown-ticks", null, 4, 0);
        freezeBlindnessBufferSeconds = GameConfigReader.intSetting(config, "gameplay.freeze-blindness-buffer-seconds",
                null, 5, 0);
        countdownTitleThresholdSeconds = GameConfigReader.intSetting(config,
                "gameplay.countdown-title-threshold-seconds", null, 5, 0);
        roundWarningSeconds = GameConfigReader.intListSetting(config, "gameplay.round-warning-seconds",
                Arrays.asList(300, 60));
        blockedDisguiseBlocks.clear();
        blockedDisguiseBlocks.addAll(GameConfigReader.materialSetSetting(config, "gameplay.blocked-disguise-blocks",
                DEFAULT_BLOCKED_DISGUISE_BLOCKS));
        allowedNonSolidDisguiseBlocks.clear();
        allowedNonSolidDisguiseBlocks.addAll(GameConfigReader.materialSetSetting(config,
                "gameplay.allowed-non-solid-disguise-blocks", DEFAULT_ALLOWED_NON_SOLID_DISGUISE_BLOCKS));
        forceAllowDisguiseBlocks.clear();
        forceAllowDisguiseBlocks.addAll(GameConfigReader.materialSetSetting(config,
                "gameplay.force-allow-disguise-blocks", Collections.emptySet()));
        forceDenyDisguiseBlocks.clear();
        forceDenyDisguiseBlocks.addAll(GameConfigReader.materialSetSetting(config,
                "gameplay.force-deny-disguise-blocks", Collections.emptySet()));
        defaultHiderBlocks.clear();
        List<Material> loadedBlocks = GameConfigReader.materialListSetting(
                config,
                "gameplay.default-hider-blocks",
                FALLBACK_HIDER_BLOCKS,
                this::isAllowedPropMaterial);
        // Filter out invalid materials (slabs, stairs, carpets, etc.)
        for (Material mat : loadedBlocks) {
            if (isAllowedPropMaterial(mat)) {
                defaultHiderBlocks.add(mat);
            }
        }
        if (defaultHiderBlocks.isEmpty()) {
            // FALLBACK_HIDER_BLOCKS should only contain valid materials, but validate just
            // in case
            for (Material mat : FALLBACK_HIDER_BLOCKS) {
                if (isAllowedPropMaterial(mat)) {
                    defaultHiderBlocks.add(mat);
                }
            }
        }
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

    private String msg(String path, String fallback) {
        return plugin.getConfigText(path, fallback);
    }

    private String msg(String path, String fallback, Map<String, ?> placeholders) {
        return plugin.getConfigText(path, fallback, placeholders);
    }
}
