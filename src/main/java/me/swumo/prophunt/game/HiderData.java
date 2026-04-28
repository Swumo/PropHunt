package me.swumo.prophunt.game;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.UUID;

@Getter
public class HiderData {
    private final UUID uuid;
    @Setter private Material chosenBlock;
    @Setter private BlockDisplay blockDisplay;
    private Interaction propHitbox;
    private Location placedBlockLocation;
    @Getter(AccessLevel.NONE)
    private BlockData replacedBlockData;
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

        blockDisplay = player.getWorld().spawn(player.getLocation(), BlockDisplay.class, bd -> {
            bd.setBlock(chosenBlock.createBlockData());
            Transformation t = new Transformation(
                    new Vector3f(-0.5f, 0f, -0.5f),
                    new AxisAngle4f(0, 0, 1, 0),
                    new Vector3f(1f, 1f, 1f),
                    new AxisAngle4f(0, 0, 1, 0));
            bd.setTransformation(t);
            bd.setGravity(false);
            bd.setInterpolationDuration(2);
            bd.setTeleportDuration(2);
            bd.setPersistent(false);
        });

        propHitbox = player.getWorld().spawn(player.getLocation(), Interaction.class, interaction -> {
            interaction.setInteractionWidth(1.2f);
            interaction.setInteractionHeight(1.2f);
            interaction.setResponsive(true);
            interaction.setGravity(false);
            interaction.setPersistent(false);
        });
        updateMobileDisguisePosition(player);
    }

    // Remove the display entities
    public void removeDisplay() {
        if (blockDisplay != null && !blockDisplay.isDead()) blockDisplay.remove();
        if (propHitbox != null && !propHitbox.isDead()) propHitbox.remove();

        blockDisplay = null;
        propHitbox = null;
    }

    // Update the position of the block display and the interaction hitbox to match the player's current location
    public void updateMobileDisguisePosition(Player player) {
        Location feet = player.getLocation().clone();

        if (blockDisplay != null && !blockDisplay.isDead()) blockDisplay.teleport(feet);
        if (propHitbox != null && !propHitbox.isDead()) propHitbox.teleport(feet);
    }

    // Place the chosen block in the world at the specified location, saving the original block data to restore later
    public void placeWorldBlock(Location anchor) {
        restoreWorldBlock();
        if (chosenBlock == null || anchor == null || anchor.getWorld() == null) return;

        Block block = anchor.getBlock();
        replacedBlockData = block.getBlockData().clone();
        block.setBlockData(chosenBlock.createBlockData(), false);
        placedBlockLocation = block.getLocation();
        worldBlockPlaced = true;
    }

    // Restore the original block data at the previously placed block location
    public void restoreWorldBlock() {
        if (!worldBlockPlaced || placedBlockLocation == null || placedBlockLocation.getWorld() == null) return;

        Block block = placedBlockLocation.getBlock();
        if (replacedBlockData != null) block.setBlockData(replacedBlockData, false);

        placedBlockLocation = null;
        replacedBlockData = null;
        worldBlockPlaced = false;
    }

    // Remove the disguise by deleting the display entities and restoring any world block that was placed
    public void clearDisguise() {
        removeDisplay();
        restoreWorldBlock();
    }
}
