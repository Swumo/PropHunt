package me.swumo.prophunt.utils;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
 
/**
 * Much love to Kint for sharing <3
 * 
 * Disguises a player as a BlockDisplay by mounting a client-side-only display
 * entity on the real player and making the player itself invisible.
 *
 * <p>Why a passenger and not a replacement entity: the real player keeps its
 * hitbox, its movement packets, and lag compensation. Passengers are positioned
 * client-side relative to the vehicle, so no per-tick teleport packets are
 * needed and there is no interpolation stutter.
 *
 * <p>Everything here is packet-level. The display entity does not exist on the
 * server, so no chunk/entity tracker interaction, no persistence, no ticking.
 */
public final class BlockDisguiseManager {
 
  /**
   * Resolved once at startup from NMS rather than hardcoded. The Display layout
   * shifted in 1.20.2 when DATA_POS_ROT_INTERPOLATION_DURATION_ID was inserted
   * at index 10, pushing 11..23 up by one. Reading it off the accessor means a
   * future insertion does not silently corrupt the payload.
   */
  private static final class DisplayIndices {
 
    final int translation;
    final int scale;
    final int billboard;
    final int viewRange;
    final int blockState;
 
    DisplayIndices(int translation, int scale, int billboard, int viewRange, int blockState) {
      this.translation = translation;
      this.scale = scale;
      this.billboard = billboard;
      this.viewRange = viewRange;
      this.blockState = blockState;
    }
  }
 
  /** Base Entity flags accessor is always index 0 across every version. */
  private static final int ENTITY_FLAGS_INDEX = 0;
 
  private static final byte FLAG_INVISIBLE = 0x20;

  // Minecraft positions passengers above a player; offset the display back to the
  // player's feet so the block occupies the normal disguise location.
  private static final float PLAYER_PASSENGER_FEET_OFFSET = -1.75f;
  private static final float ARENA_BORDER_HEIGHT = 30f;
 
  private final DisplayIndices indices;
 
  /** Real player UUID -> the active fake display and its recipient state. */
  private final Map<UUID, ActiveDisguise> activeDisguises = new HashMap<>();

  /** Viewer UUID -> the four client-side display ids used for an arena border. */
  private final Map<UUID, int[]> activeArenaBorders = new HashMap<>();

  /** One visual block in a mounted disguise, positioned relative to the player. */
  public record Part(BlockData block, float xOffset, float yOffset, float zOffset) {}

  private record ActiveDisguise(int[] entityIds, List<Part> parts, Set<UUID> sentViewerIds) {}
 
  public BlockDisguiseManager() {
    this.indices = resolveIndices();
  }
 
  // ---------------------------------------------------------------------
  // Index resolution
  // ---------------------------------------------------------------------
 
  /**
   * Walks the static EntityDataAccessor fields on Display and Display$BlockDisplay
   * and pulls their runtime ids. Paper has shipped Mojang mappings at runtime
   * since 1.20.5, so the field names below are the real ones on 1.21.11.
   *
   * <p>If any of these fail to resolve we throw rather than fall back to a
   * hardcoded guess — a wrong index produces a malformed metadata blob, and a
   * malformed metadata blob disconnects clients.
   */
  private static DisplayIndices resolveIndices() {
    try {
      Class<?> display = Class.forName("net.minecraft.world.entity.Display");
      Class<?> blockDisplay = Class.forName("net.minecraft.world.entity.Display$BlockDisplay");
 
      int translation = accessorId(display, "DATA_TRANSLATION_ID");
      int scale = accessorId(display, "DATA_SCALE_ID");
      int billboard = accessorId(display, "DATA_BILLBOARD_RENDER_CONSTRAINTS_ID");
      int viewRange = accessorId(display, "DATA_VIEW_RANGE_ID");
      int blockState = accessorId(blockDisplay, "DATA_BLOCK_STATE_ID");
 
      return new DisplayIndices(translation, scale, billboard, viewRange, blockState);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(
          "Could not resolve Display metadata indices from NMS. "
              + "Field names may have changed for this server version.",
          e);
    }
  }
 
  /**
   * Reads {@code EntityDataAccessor#id()} off a named static field. Falls back to
   * a name-contains scan if the exact field name has been renamed upstream,
   * which is the failure mode most likely to survive a version bump.
   */
  private static int accessorId(Class<?> owner, String fieldName)
      throws ReflectiveOperationException {
    Field field = findField(owner, fieldName)
        .orElseThrow(() -> new NoSuchFieldException(owner.getName() + "#" + fieldName));
    field.setAccessible(true);
    Object accessor = field.get(null);
    return (int) accessor.getClass().getMethod("id").invoke(accessor);
  }
 
  private static Optional<Field> findField(Class<?> owner, String exactName) {
    for (Field f : owner.getDeclaredFields()) {
      if (!Modifier.isStatic(f.getModifiers())) {
        continue;
      }
      if (f.getName().equals(exactName)) {
        return Optional.of(f);
      }
    }
    // Loose match: strip DATA_/_ID and compare cores, in case of an upstream rename.
    String core = exactName.replace("DATA_", "").replace("_ID", "");
    for (Field f : owner.getDeclaredFields()) {
      if (Modifier.isStatic(f.getModifiers())
          && f.getType().getSimpleName().equals("EntityDataAccessor")
          && f.getName().contains(core)) {
        return Optional.of(f);
      }
    }
    return Optional.empty();
  }
 
  // ---------------------------------------------------------------------
  // Applying a disguise
  // ---------------------------------------------------------------------
 
  /**
   * Disguises {@code target} as the given block for every viewer passed in.
   *
   * @param target   the player being disguised
   * @param block    the block appearance, e.g. Material.OAK_LOG.createBlockData()
   * @param viewers  who should see the disguise; usually everyone but {@code target}
   */
  public void disguise(Player target, BlockData block, List<Player> viewers) {
    disguise(target, List.of(new Part(block, 0f, 0f, 0f)), viewers);
  }

  /**
   * Disguises {@code target} with one or more mounted block displays.
   *
   * <p>Each part remains a passenger of the real player, so even multi-block
   * props follow movement without per-tick display teleport packets.
   */
  public void disguise(Player target, List<Part> parts, List<Player> viewers) {
    undisguise(target, viewers);
    if (parts.isEmpty()) {
      return;
    }
 
    int[] displayIds = new int[parts.size()];
    for (int index = 0; index < displayIds.length; index++) {
      displayIds[index] = nextEntityId();
    }
    ActiveDisguise disguise = new ActiveDisguise(displayIds, List.copyOf(parts), new HashSet<>());
    activeDisguises.put(target.getUniqueId(), disguise);
 
    for (Player viewer : viewers) {
      if (viewer != null && viewer.isOnline()
          && disguise.sentViewerIds().add(viewer.getUniqueId())) {
        sendDisguise(viewer, target, disguise);
      }
    }
  }

  /**
   * Delivers an existing disguise to viewers that have just started tracking
   * its real-player mount. Viewers that leave tracking are forgotten so their
   * next tracking transition receives a fresh client-side entity spawn.
   */
  public void syncViewers(Player target, List<Player> viewers) {
    ActiveDisguise disguise = activeDisguises.get(target.getUniqueId());
    if (disguise == null) {
      return;
    }

    Set<UUID> currentViewerIds = new HashSet<>();
    for (Player viewer : viewers) {
      if (viewer != null && viewer.isOnline()) {
        currentViewerIds.add(viewer.getUniqueId());
      }
    }
    disguise.sentViewerIds().retainAll(currentViewerIds);

    for (Player viewer : viewers) {
      if (viewer != null && viewer.isOnline()
          && disguise.sentViewerIds().add(viewer.getUniqueId())) {
        sendDisguise(viewer, target, disguise);
      }
    }
  }
 
  /** Removes the disguise and un-hides the real player model. */
  public void undisguise(Player target, List<Player> viewers) {
    ActiveDisguise disguise = activeDisguises.remove(target.getUniqueId());
    if (disguise == null) {
      return;
    }
    for (Player viewer : viewers) {
      PacketEvents.getAPI()
          .getPlayerManager()
          .sendPacket(viewer, new WrapperPlayServerDestroyEntities(disguise.entityIds()));
      restorePlayerEquipment(viewer, target);
    }
  }

  /**
   * Shows a red stained-glass-pane border around a cuboid for one viewer, or
   * removes that viewer's existing border when invoked again.
   *
   * @return {@code true} when the border was shown, {@code false} when it was removed
   */
  public boolean toggleArenaBorder(Player viewer, Location pos1, Location pos2) {
    if (clearArenaBorder(viewer)) {
      return false;
    }

    int minX = Math.min(pos1.getBlockX(), pos2.getBlockX());
    int maxX = Math.max(pos1.getBlockX(), pos2.getBlockX());
    int minY = Math.min(pos1.getBlockY(), pos2.getBlockY());
    int minZ = Math.min(pos1.getBlockZ(), pos2.getBlockZ());
    int maxZ = Math.max(pos1.getBlockZ(), pos2.getBlockZ());
    float width = maxX - minX + 1;
    float depth = maxZ - minZ + 1;

    int[] displayIds = {nextEntityId(), nextEntityId(), nextEntityId(), nextEntityId()};
    activeArenaBorders.put(viewer.getUniqueId(), displayIds);

    BlockData glass = Material.RED_STAINED_GLASS.createBlockData();
    sendBorderDisplay(viewer, displayIds[0], new Location(pos1.getWorld(), minX, minY, minZ), glass,
      new Vector3f(width, ARENA_BORDER_HEIGHT, 0.125f));
    sendBorderDisplay(viewer, displayIds[1], new Location(pos1.getWorld(), minX, minY, maxZ + 1), glass,
      new Vector3f(width, ARENA_BORDER_HEIGHT, 0.125f));
    sendBorderDisplay(viewer, displayIds[2], new Location(pos1.getWorld(), minX, minY, minZ), glass,
      new Vector3f(0.125f, ARENA_BORDER_HEIGHT, depth));
    sendBorderDisplay(viewer, displayIds[3], new Location(pos1.getWorld(), maxX + 1, minY, minZ), glass,
      new Vector3f(0.125f, ARENA_BORDER_HEIGHT, depth));
    return true;
  }

  public boolean clearArenaBorder(Player viewer) {
    int[] displayIds = activeArenaBorders.remove(viewer.getUniqueId());
    if (displayIds == null) {
      return false;
    }
    PacketEvents.getAPI().getPlayerManager()
        .sendPacket(viewer, new WrapperPlayServerDestroyEntities(displayIds));
    return true;
  }

  public void clearAllArenaBorders() {
    for (UUID viewerId : new ArrayList<>(activeArenaBorders.keySet())) {
      Player viewer = Bukkit.getPlayer(viewerId);
      if (viewer != null && viewer.isOnline()) {
        clearArenaBorder(viewer);
      } else {
        activeArenaBorders.remove(viewerId);
      }
    }
  }
 
  // ---------------------------------------------------------------------
  // Packet construction
  // ---------------------------------------------------------------------
 
  private void sendSpawn(Player viewer, Player target, int displayId) {
    // Spawn position is largely cosmetic — the mount packet overrides it
    // immediately — but sending the target's real position avoids a one-frame
    // pop if the mount packet arrives late.
    sendSpawn(viewer, target.getLocation(), displayId);
  }

  private void sendSpawn(Player viewer, Location location, int displayId) {
    Vector3d pos = new Vector3d(location.getX(), location.getY(), location.getZ());
 
    WrapperPlayServerSpawnEntity spawn =
        new WrapperPlayServerSpawnEntity(
            displayId,
            Optional.of(UUID.randomUUID()),
            EntityTypes.BLOCK_DISPLAY,
            pos,
            0f, // pitch
            0f, // yaw
            0f, // head yaw
            0, // object data
            Optional.empty()); // velocity
 
    PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, spawn);
  }
 
  private void sendDisplayMetadata(Player viewer, int displayId, Part part) {
    sendDisplayMetadata(viewer, displayId, part.block(), new Vector3f(
        -0.5f + part.xOffset(),
        PLAYER_PASSENGER_FEET_OFFSET + part.yOffset(),
        -0.5f + part.zOffset()), new Vector3f(1.0f, 1.0f, 1.0f));
  }

  private void sendBorderDisplay(Player viewer, int displayId, Location location, BlockData block,
      Vector3f scale) {
    sendSpawn(viewer, location, displayId);
    sendDisplayMetadata(viewer, displayId, block, new Vector3f(0f, 0f, 0f), scale, 128f);
  }

  private void sendDisplayMetadata(Player viewer, int displayId, BlockData block,
      Vector3f translation, Vector3f scale) {
    sendDisplayMetadata(viewer, displayId, block, translation, scale, null);
  }

  private void sendDisplayMetadata(Player viewer, int displayId, BlockData block,
      Vector3f translation, Vector3f scale, Float viewRange) {
    WrappedBlockState state = SpigotConversionUtil.fromBukkitBlockData(block);
    List<EntityData<?>> data = new ArrayList<>();
    data.add(new EntityData<>(indices.blockState, EntityDataTypes.BLOCK_STATE, state.getGlobalId()));
    data.add(new EntityData<>(indices.translation, EntityDataTypes.VECTOR3F, translation));
    data.add(new EntityData<>(indices.scale, EntityDataTypes.VECTOR3F, scale));
    data.add(new EntityData<>(indices.billboard, EntityDataTypes.BYTE, (byte) 0));
    if (viewRange != null) {
      data.add(new EntityData<>(indices.viewRange, EntityDataTypes.FLOAT, viewRange));
    }
    PacketEvents.getAPI().getPlayerManager()
        .sendPacket(viewer, new WrapperPlayServerEntityMetadata(displayId, data));
  }
 
  private void sendMount(Player viewer, int vehicleId, int[] passengerIds) {
    PacketEvents.getAPI()
        .getPlayerManager()
        .sendPacket(viewer, new WrapperPlayServerSetPassengers(vehicleId, passengerIds));
  }

  private void sendDisguise(Player viewer, Player target, ActiveDisguise disguise) {
    for (int index = 0; index < disguise.entityIds().length; index++) {
      sendSpawn(viewer, target, disguise.entityIds()[index]);
      sendDisplayMetadata(viewer, disguise.entityIds()[index], disguise.parts().get(index));
    }
    sendMount(viewer, target.getEntityId(), disguise.entityIds());
    setPlayerInvisible(viewer, target, true);
    hidePlayerEquipment(viewer, target);
  }
 
  /**
   * Toggles the invisible flag on the real player, for this viewer only.
   *
   * <p>Caveat: this only hides the player model. Held items and armour are sent
   * separately and must be blanked with an equipment packet, and the nametag
   * still renders — use a scoreboard team with NEVER visibility for that.
   */
  private void setPlayerInvisible(Player viewer, Player target, boolean invisible) {
    byte flags = invisible ? FLAG_INVISIBLE : 0;
    List<EntityData<?>> data =
        List.of(new EntityData<>(ENTITY_FLAGS_INDEX, EntityDataTypes.BYTE, flags));
 
    PacketEvents.getAPI()
        .getPlayerManager()
        .sendPacket(viewer, new WrapperPlayServerEntityMetadata(target.getEntityId(), data));
  }

  private void hidePlayerEquipment(Player viewer, Player target) {
    if (viewer.equals(target)) {
      return;
    }
    sendPlayerEquipment(viewer, target, new ItemStack(org.bukkit.Material.AIR));
  }

  private void restorePlayerEquipment(Player viewer, Player target) {
    if (viewer.equals(target)) {
      return;
    }
    sendPlayerEquipment(viewer, target, null);
  }

  private void sendPlayerEquipment(Player viewer, Player target, ItemStack replacement) {
    org.bukkit.inventory.PlayerInventory inventory = target.getInventory();
    List<Equipment> equipment = List.of(
        new Equipment(EquipmentSlot.MAIN_HAND, toProtocolItem(replacement, inventory.getItemInMainHand())),
        new Equipment(EquipmentSlot.OFF_HAND, toProtocolItem(replacement, inventory.getItemInOffHand())),
      new Equipment(EquipmentSlot.BOOTS, toProtocolItem(replacement, inventory.getBoots())),
      new Equipment(EquipmentSlot.LEGGINGS, toProtocolItem(replacement, inventory.getLeggings())),
      new Equipment(EquipmentSlot.CHEST_PLATE, toProtocolItem(replacement, inventory.getChestplate())),
      new Equipment(EquipmentSlot.HELMET, toProtocolItem(replacement, inventory.getHelmet())));
    PacketEvents.getAPI().getPlayerManager().sendPacket(
        viewer, new WrapperPlayServerEntityEquipment(target.getEntityId(), equipment));
  }

  private com.github.retrooper.packetevents.protocol.item.ItemStack toProtocolItem(
      ItemStack replacement, ItemStack actual) {
    ItemStack item = replacement == null ? actual : replacement;
    return SpigotConversionUtil.fromBukkitItemStack(item == null ? new ItemStack(org.bukkit.Material.AIR) : item);
  }
 
  // ---------------------------------------------------------------------
  // Entity id allocation
  // ---------------------------------------------------------------------
 
  /**
   * Client-side entity ids must not collide with real server entities. Counting
   * down from Integer.MAX_VALUE is the usual convention since the server counts
   * up from 0.
   */
  private static int nextEntityId() {
    return ThreadLocalRandom.current().nextInt(Integer.MAX_VALUE / 2, Integer.MAX_VALUE);
  }
}