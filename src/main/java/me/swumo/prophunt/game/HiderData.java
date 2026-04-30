package me.swumo.prophunt.game;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.util.Vector;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.Rotatable;
import org.bukkit.block.data.type.Bed;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Getter
public class HiderData {
    private static final AxisAngle4f NO_ROTATION = new AxisAngle4f(0, 0, 1, 0);
    private final UUID uuid;
    @Setter private Material chosenBlock;
    private final List<BlockDisplay> blockDisplays = new ArrayList<>();
    private Interaction propHitbox;
    private Location placedBlockLocation;
    @Getter(AccessLevel.NONE)
    private final List<PlacedBlockState> replacedBlockStates = new ArrayList<>();
    @Getter(AccessLevel.NONE)
    private boolean worldBlockPlaced;
    @Setter private boolean locked = false;
    private int stillTicks = 0;
    @Setter private int hp;

    public HiderData(UUID uuid, int maxHp) {
        this.uuid = uuid;
        this.hp = maxHp;
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

    // Spawn the display block and interaction hitbox for this hider, and position them at the player's location
    public void spawnDisplay(Player player) {
        restoreWorldBlock();
        removeDisplay();
        if (chosenBlock == null)
            return;

        BlockData orientedData = createOrientedBlockData(player);
        List<DisguisePart> parts = buildDisguiseParts(orientedData);
        for (DisguisePart part : parts) {
            BlockDisplay display = player.getWorld().spawn(player.getLocation(), BlockDisplay.class, bd -> {
                bd.setBlock(part.blockData());
                bd.setTransformation(new Transformation(
                        new Vector3f(-0.5f + part.xOffset(), part.yOffset(), -0.5f + part.zOffset()),
                        NO_ROTATION,
                        new Vector3f(1f, 1f, 1f),
                        NO_ROTATION));
                bd.setGravity(false);
                bd.setInterpolationDuration(2);
                bd.setTeleportDuration(2);
                bd.setPersistent(false);
            });
            blockDisplays.add(display);
        }

        propHitbox = player.getWorld().spawn(player.getLocation(), Interaction.class, interaction -> {
            boolean verticalMultipart = requiresVerticalSpace(chosenBlock);
            boolean horizontalMultipart = requiresHorizontalSpace(chosenBlock);
            interaction.setInteractionWidth(horizontalMultipart ? 2.2f : 1.2f);
            interaction.setInteractionHeight(verticalMultipart ? 2.2f : 1.2f);
            interaction.setResponsive(true);
            interaction.setGravity(false);
            interaction.setPersistent(false);
        });
        updateMobileDisguisePosition(player);
    }

    // Remove the display entities
    public void removeDisplay() {
        for (BlockDisplay blockDisplay : blockDisplays) {
            if (blockDisplay != null && !blockDisplay.isDead()) blockDisplay.remove();
        }
        if (propHitbox != null && !propHitbox.isDead()) propHitbox.remove();

        blockDisplays.clear();
        propHitbox = null;
    }

    // Update the position of the block display and the interaction hitbox to match the player's current location
    public void updateMobileDisguisePosition(Player player) {
        if (chosenBlock == null) return;

        Location feet = player.getLocation().clone();
        BlockData orientedData = createOrientedBlockData(player);
        List<DisguisePart> parts = buildDisguiseParts(orientedData);

        if (blockDisplays.size() != parts.size()) {
            // Defensive fallback: rebuild displays if part count changed unexpectedly.
            spawnDisplay(player);
            return;
        }

        for (int i = 0; i < blockDisplays.size(); i++) {
            BlockDisplay blockDisplay = blockDisplays.get(i);
            if (blockDisplay == null || blockDisplay.isDead()) continue;

            DisguisePart part = parts.get(i);
            blockDisplay.setBlock(part.blockData());
            blockDisplay.setTransformation(new Transformation(
                    new Vector3f(-0.5f + part.xOffset(), part.yOffset(), -0.5f + part.zOffset()),
                    NO_ROTATION,
                    new Vector3f(1f, 1f, 1f),
                    NO_ROTATION));
            blockDisplay.teleport(feet);
        }
        if (propHitbox != null && !propHitbox.isDead()) propHitbox.teleport(feet);
    }

    // Place the chosen block in the world at the specified location, saving the original block data to restore later
    public void placeWorldBlock(Location anchor) {
        placeWorldBlock(anchor, null);
    }

    public void placeWorldBlock(Location anchor, Player player) {
        restoreWorldBlock();
        if (chosenBlock == null || anchor == null || anchor.getWorld() == null) return;

        BlockData orientedData = createOrientedBlockData(player);
        List<DisguisePart> parts = buildDisguiseParts(orientedData);
        for (DisguisePart part : parts) {
            Block block = anchor.clone().add(part.xOffset(), part.yOffset(), part.zOffset()).getBlock();
            replacedBlockStates.add(new PlacedBlockState(block.getLocation(), block.getBlockData().clone()));
            block.setBlockData(part.blockData(), false);
        }
        placedBlockLocation = anchor.getBlock().getLocation();
        worldBlockPlaced = true;
    }

    // Restore the original block data at the previously placed block location
    public void restoreWorldBlock() {
        if (!worldBlockPlaced) return;

        for (int index = replacedBlockStates.size() - 1; index >= 0; index--) {
            PlacedBlockState state = replacedBlockStates.get(index);
            Location location = state.location();
            if (location == null || location.getWorld() == null) continue;

            Block block = location.getBlock();
            block.setBlockData(state.blockData(), false);
        }

        placedBlockLocation = null;
        replacedBlockStates.clear();
        worldBlockPlaced = false;
    }

    // Remove the disguise by deleting the display entities and restoring any world block that was placed
    public void clearDisguise() {
        removeDisplay();
        restoreWorldBlock();
    }

    public boolean ownsDisplay(BlockDisplay display) {
        return blockDisplays.contains(display);
    }

    public static boolean requiresVerticalSpace(Material material) {
        if (material == null || !material.isBlock() || material.isAir()) return false;

        return material.createBlockData() instanceof Bisected;
    }

    public static boolean requiresHorizontalSpace(Material material) {
        if (material == null || !material.isBlock() || material.isAir()) return false;

        return material.createBlockData() instanceof Bed;
    }

    public static HorizontalOffset horizontalOffset(Material material, Player player) {
        if (!requiresHorizontalSpace(material)) return new HorizontalOffset(0, 0);

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

        if (baseData instanceof Bisected bisected) {
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

    private BlockData createOrientedBlockData(Player player) {
        BlockData data = chosenBlock.createBlockData();
        if (player == null) return data;

        float pitch = player.getLocation().getPitch();
        BlockFace horizontalFace = resolveHorizontalFace(player);

        if (data instanceof Directional directional) {
            applyDirectionalFacing(directional, horizontalFace, pitch);
        }

        if (data instanceof Rotatable rotatable) {
            rotatable.setRotation(horizontalFace);
        }

        if (data instanceof Orientable orientable) {
            orientable.setAxis(resolveAxis(horizontalFace, pitch));
        }

        return data;
    }

    private void applyDirectionalFacing(Directional directional, BlockFace horizontalFace, float pitch) {
        Collection<BlockFace> faces = directional.getFaces();
        BlockFace preferred = horizontalFace;
        if (pitch <= -55f) {
            preferred = BlockFace.UP;
        } else if (pitch >= 55f) {
            preferred = BlockFace.DOWN;
        }

        if (faces.contains(preferred)) {
            directional.setFacing(preferred);
            return;
        }
        if (faces.contains(horizontalFace)) {
            directional.setFacing(horizontalFace);
            return;
        }

        BlockFace opposite = horizontalFace.getOppositeFace();
        if (faces.contains(opposite)) {
            directional.setFacing(opposite);
            return;
        }

        // Final fallback for constrained directional blocks.
        directional.setFacing(faces.iterator().next());
    }

    private static org.bukkit.Axis resolveAxis(BlockFace horizontalFace, float pitch) {
        if (pitch <= -55f || pitch >= 55f) {
            return org.bukkit.Axis.Y;
        }

        return switch (horizontalFace) {
            case EAST, WEST -> org.bukkit.Axis.X;
            default -> org.bukkit.Axis.Z;
        };
    }

    private static BlockFace resolveHorizontalFace(Player player) {
        if (player == null) return BlockFace.SOUTH;

        Vector direction = player.getLocation().getDirection();
        double x = direction.getX();
        double z = direction.getZ();
        if (Math.abs(x) > Math.abs(z)) {
            return x >= 0 ? BlockFace.EAST : BlockFace.WEST;
        }

        return z >= 0 ? BlockFace.SOUTH : BlockFace.NORTH;
    }

    private record DisguisePart(BlockData blockData, float xOffset, float yOffset, float zOffset) {}

    public record HorizontalOffset(int x, int z) {}

    private record PlacedBlockState(Location location, BlockData blockData) {}
}
