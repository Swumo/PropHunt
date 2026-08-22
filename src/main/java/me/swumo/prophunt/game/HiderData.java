package me.swumo.prophunt.game;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import me.swumo.prophunt.utils.BlockDisguiseManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.Rotatable;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.block.data.type.TrapDoor;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Getter
public class HiderData {
    private static final BlockDisguiseManager BLOCK_DISGUISES = new BlockDisguiseManager();
    private final UUID uuid;
    private Material chosenBlock;
    private BlockData baseBlockData;
    private BlockFace mobileFacing;
    private final List<UUID> disguiseViewerIds = new ArrayList<>();
    private Interaction propHitbox;
    private Location placedBlockLocation;
    private Location lockedPlayerLocation;
    @Getter(AccessLevel.NONE)
    private final List<PlacedBlockState> replacedBlockStates = new ArrayList<>();
    @Getter(AccessLevel.NONE)
    private boolean worldBlockPlaced;
    @Setter
    private boolean locked = false;
    private int stillTicks = 0;
    @Setter
    private int hp;

    public HiderData(UUID uuid, int maxHp) {
        this.uuid = uuid;
        this.hp = maxHp;
    }

    public void setChosenBlock(Material chosenBlock) {
        this.chosenBlock = chosenBlock;
        this.baseBlockData = chosenBlock == null ? null : chosenBlock.createBlockData();
        this.mobileFacing = null;
    }

    public void resetStillTicks() {
        stillTicks = 0;
    }

    public void incrementStillTicks() {
        stillTicks++;
    }

    public boolean isAlive() {
        return hp > 0;
    }

    // Apply the packet-only mobile disguise and its server-side interaction hitbox.
    public void applyMobileDisguise(Player player, Collection<? extends Player> viewers) {
        restoreWorldBlock();
        removeMobileDisguise();
        if (chosenBlock == null)
            return;

        Location anchor = toDisplayAnchor(player.getLocation());
        mobileFacing = resolveHorizontalFace(player);

        List<Player> activeViewers = activeViewers(player, viewers);
        BlockData orientedData = createOrientedBlockData(player);
        List<BlockDisguiseManager.Part> displayParts = buildDisguiseParts(orientedData).stream()
            .map(part -> new BlockDisguiseManager.Part(
                part.blockData(), part.xOffset(), part.yOffset(), part.zOffset()))
            .toList();
        BLOCK_DISGUISES.disguise(player, displayParts, activeViewers);

        propHitbox = player.getWorld().spawn(anchor, Interaction.class, interaction -> {
            // Keep the hitbox compact so it does not steal right-click interactions
            // from nearby map blocks (e.g., doors) while still allowing seeker hits.
            interaction.setInteractionWidth(0.9f);
            interaction.setInteractionHeight(1.0f);
            interaction.setResponsive(true);
            interaction.setGravity(false);
            interaction.setPersistent(false);
        });
        updateMobileDisguisePosition(player, viewers);
    }

    // Remove the packet-only visual disguise and its interaction hitbox.
    public void removeMobileDisguise() {
        Player player = Bukkit.getPlayer(uuid);
        if (player != null)
            BLOCK_DISGUISES.undisguise(player, resolveDisguiseViewers());
        if (propHitbox != null && !propHitbox.isDead())
            propHitbox.remove();

        disguiseViewerIds.clear();
        propHitbox = null;
        mobileFacing = null;
    }

    // Update the position of the block display and the interaction hitbox to match
    // the player's current location
    public void updateMobileDisguisePosition(Player player, Collection<? extends Player> viewers) {
        if (player == null)
            return;

        if (requiresFacingRefresh(player)) {
            applyMobileDisguise(player, viewers);
            return;
        }

        updateDisguisePosition(player.getLocation(), player, viewers);
    }

    private boolean requiresFacingRefresh(Player player) {
        if (baseBlockData == null || player == null)
            return false;
        if (!(baseBlockData instanceof Directional)
                && !(baseBlockData instanceof Rotatable)
                && !(baseBlockData instanceof Orientable))
            return false;

        return mobileFacing != resolveHorizontalFace(player);
    }

    // Update the locked display using a fixed world anchor while keeping the
    // player's current orientation
    public void updateLockedDisguisePosition(Location anchor, Player orientationSource,
                                             Collection<? extends Player> viewers) {
        updateDisguisePosition(anchor, orientationSource, viewers);
    }

    private void updateDisguisePosition(Location anchorSource, Player orientationSource,
                                        Collection<? extends Player> viewers) {
        if (chosenBlock == null || anchorSource == null || orientationSource == null)
            return;

        BLOCK_DISGUISES.syncViewers(orientationSource, activeViewers(orientationSource, viewers));

        if (propHitbox != null && !propHitbox.isDead())
            propHitbox.teleport(toDisplayAnchor(anchorSource));
    }

    private List<Player> activeViewers(Player target, Collection<? extends Player> viewers) {
        disguiseViewerIds.clear();
        List<Player> activeViewers = new ArrayList<>();
        if (viewers == null)
            return activeViewers;

        for (Player viewer : viewers) {
            if (viewer == null || !viewer.isOnline()
                    || !target.getWorld().equals(viewer.getWorld()))
                continue;

            disguiseViewerIds.add(viewer.getUniqueId());
            if (viewer.equals(target) || target.getTrackedBy().contains(viewer))
                activeViewers.add(viewer);
        }
        return activeViewers;
    }

    private List<Player> resolveDisguiseViewers() {
        List<Player> viewers = new ArrayList<>();
        for (UUID viewerId : disguiseViewerIds) {
            Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer != null && viewer.isOnline())
                viewers.add(viewer);
        }
        return viewers;
    }

    private Location toDisplayAnchor(Location source) {
        Location location = source.clone();
        location.setYaw(0f);
        location.setPitch(0f);
        return location;
    }

    public void setLockedAnchor(Location anchor) {
        if (anchor == null || anchor.getWorld() == null) {
            placedBlockLocation = null;
            return;
        }

        placedBlockLocation = anchor.getBlock().getLocation();
    }

    public void setLockedPlayerLocation(Location location) {
        if (location == null || location.getWorld() == null) {
            lockedPlayerLocation = null;
            return;
        }

        lockedPlayerLocation = location.clone();
    }

    // Place the chosen block in the world at the specified location, saving the
    // original block data to restore later
    public void placeWorldBlock(Location anchor) {
        placeWorldBlock(anchor, null);
    }

    public void placeWorldBlock(Location anchor, Player player) {
        restoreWorldBlock();
        if (chosenBlock == null || anchor == null || anchor.getWorld() == null)
            return;

        BlockData orientedData = createOrientedBlockData(player);
        List<DisguisePart> parts = buildDisguiseParts(orientedData);
        for (DisguisePart part : parts) {
            Block block = anchor.clone().add(part.xOffset(), part.yOffset(), part.zOffset()).getBlock();
            replacedBlockStates.add(new PlacedBlockState(block.getLocation(), block.getBlockData().clone()));
            BlockData placedData = part.blockData();
            if (placedData instanceof Leaves leaves) {
                Leaves persistentLeaves = (Leaves) leaves.clone();
                persistentLeaves.setPersistent(true);
                placedData = persistentLeaves;
            }
            block.setBlockData(placedData, false);
        }
        placedBlockLocation = anchor.getBlock().getLocation();
        worldBlockPlaced = true;
    }

    // Restore the original block data at the previously placed block location
    public void restoreWorldBlock() {
        if (worldBlockPlaced) {
            for (int index = replacedBlockStates.size() - 1; index >= 0; index--) {
                PlacedBlockState state = replacedBlockStates.get(index);
                Location location = state.location();
                if (location == null || location.getWorld() == null)
                    continue;

                Block block = location.getBlock();
                block.setBlockData(state.blockData(), false);
            }
        }

        placedBlockLocation = null;
        lockedPlayerLocation = null;
        replacedBlockStates.clear();
        worldBlockPlaced = false;
    }

    // Remove the disguise by deleting the display entities and restoring any world
    // block that was placed
    public void clearDisguise() {
        removeMobileDisguise();
        restoreWorldBlock();
    }

    public boolean occupiesWorldBlock(Location blockLocation) {
        if (!worldBlockPlaced || blockLocation == null || blockLocation.getWorld() == null)
            return false;

        for (PlacedBlockState state : replacedBlockStates) {
            Location location = state.location();
            if (location.getBlockX() == blockLocation.getBlockX()
                    && location.getBlockY() == blockLocation.getBlockY()
                    && location.getBlockZ() == blockLocation.getBlockZ()) {
                return true;
            }
        }

        return false;
    }

    public static boolean requiresVerticalSpace(Material material) {
        if (material == null || !material.isBlock() || material.isAir())
            return false;

        return isTrueTwoBlockDisguise(material.createBlockData());
    }

    public static boolean requiresHorizontalSpace(Material material) {
        if (material == null || !material.isBlock() || material.isAir())
            return false;

        return material.createBlockData() instanceof Bed;
    }

    public static HorizontalOffset horizontalOffset(Material material, Player player) {
        if (!requiresHorizontalSpace(material))
            return new HorizontalOffset(0, 0);

        BlockFace face = resolveHorizontalFace(player);
        return new HorizontalOffset(face.getModX(), face.getModZ());
    }

    private List<DisguisePart> buildDisguiseParts(BlockData baseData) {
        List<DisguisePart> parts = new ArrayList<>();
        if (baseData instanceof Bed bedData) {
            Bed foot = (Bed) bedData.clone();
            foot.setPart(Bed.Part.FOOT);

            Bed head = (Bed) bedData.clone();
            head.setPart(Bed.Part.HEAD);

            BlockFace facing = bedData.getFacing();
            parts.add(new DisguisePart(foot, 0f, 0f, 0f));
            parts.add(new DisguisePart(head, facing.getModX(), 0f, facing.getModZ()));
            return parts;
        }

        if (isTrueTwoBlockDisguise(baseData) && baseData instanceof Bisected bisected) {
            parts.add(new DisguisePart(withHalf((BlockData) bisected, Bisected.Half.BOTTOM), 0f, 0f, 0f));
            parts.add(new DisguisePart(withHalf((BlockData) bisected, Bisected.Half.TOP), 0f, 1f, 0f));
            return parts;
        }

        parts.add(new DisguisePart(baseData, 0f, 0f, 0f));
        return parts;
    }

    private BlockData withHalf(BlockData source, Bisected.Half half) {
        BlockData data = source.clone();
        if (data instanceof Bisected bisected) {
            bisected.setHalf(half);
        }
        return data;
    }

    private static boolean isTrueTwoBlockDisguise(BlockData data) {
        if (!(data instanceof Bisected))
            return false;

        // Stairs are Bisected (top/bottom) but occupy only one block, so do not split
        // them. Trapdoors are also Bisected but occupy one block.
        return !(data instanceof Stairs) && !(data instanceof TrapDoor);
    }

    private BlockData createOrientedBlockData(Player player) {
        if (baseBlockData == null)
            return chosenBlock == null ? null : chosenBlock.createBlockData();
        if (player == null)
            return baseBlockData;

        if (!(baseBlockData instanceof Directional)
                && !(baseBlockData instanceof Rotatable)
                && !(baseBlockData instanceof Orientable))
            return baseBlockData;

        BlockData data = baseBlockData.clone();
        BlockFace horizontalFace = resolveHorizontalFace(player);

        if (data instanceof Directional directional) {
            applyDirectionalFacing(directional, horizontalFace);
        }

        if (data instanceof Rotatable rotatable) {
            rotatable.setRotation(horizontalFace);
        }

        if (data instanceof Orientable orientable) {
            orientable.setAxis(resolveAxis(horizontalFace));
        }

        return data;
    }

    private void applyDirectionalFacing(Directional directional, BlockFace horizontalFace) {
        Collection<BlockFace> faces = directional.getFaces();
        if (faces.contains(horizontalFace)) {
            directional.setFacing(horizontalFace);
            return;
        }

        BlockFace opposite = horizontalFace.getOppositeFace();
        if (faces.contains(opposite)) {
            directional.setFacing(opposite);
            return;
        }

        if (faces.contains(BlockFace.UP)) {
            directional.setFacing(BlockFace.UP);
            return;
        }

        if (faces.contains(BlockFace.DOWN)) {
            directional.setFacing(BlockFace.DOWN);
            return;
        }

        // Final fallback for constrained directional blocks.
        directional.setFacing(faces.iterator().next());
    }

    private static org.bukkit.Axis resolveAxis(BlockFace horizontalFace) {
        return switch (horizontalFace) {
            case EAST, WEST -> org.bukkit.Axis.X;
            default -> org.bukkit.Axis.Z;
        };
    }

    private static BlockFace resolveHorizontalFace(Player player) {
        if (player == null)
            return BlockFace.SOUTH;

        float yaw = player.getLocation().getYaw();
        int quadrant = Math.floorMod(Math.round(yaw / 90f), 4);
        return switch (quadrant) {
            case 0 -> BlockFace.SOUTH;
            case 1 -> BlockFace.WEST;
            case 2 -> BlockFace.NORTH;
            default -> BlockFace.EAST;
        };
    }

    private record DisguisePart(BlockData blockData, float xOffset, float yOffset, float zOffset) {
    }

    public record HorizontalOffset(int x, int z) {
    }

    private record PlacedBlockState(Location location, BlockData blockData) {
    }
}