package me.swumo.prophunt.listeners;

import me.swumo.prophunt.PropHunt;
import me.swumo.prophunt.game.GameManager;
import me.swumo.prophunt.game.HiderData;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

public class GameListeners implements Listener {
    private final PropHunt plugin;

    public GameListeners(PropHunt plugin) {
        this.plugin = plugin;
    }

    private GameManager gm() {
        return plugin.getGameManager();
    }

    // Handle seekers hitting hiders by left-clicking them or their interaction
    // hitboxes/block displays
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player seeker))
            return;
        Entity target = event.getEntity();

        switch (target) {
            case Interaction hitbox -> {
                event.setCancelled(true);
                if (gm().isSeeker(seeker))
                    gm().handleHiderHit(seeker, hitbox);
            }
            case BlockDisplay bd -> {
                event.setCancelled(true);
                if (gm().isSeeker(seeker))
                    gm().handleHiderHit(seeker, bd);
            }
            case Player victim -> {
                if (gm().isSeeker(seeker)) {
                    event.setCancelled(true);
                }

                HiderData data = gm().getHiderData(victim.getUniqueId());
                if (gm().isSeeker(seeker) && gm().isHider(victim) && data != null && !data.isLocked()) {
                    gm().handleHiderHit(seeker, victim);
                }
            }
            default -> {
                // no-op
            }
        }

    }

    // Prevent hiders from taking any damage during the seeking phase
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player p))
            return;

        if (!gm().isHider(p))
            return;

        HiderData data = gm().getHiderData(p.getUniqueId());
        if (gm().getState() == GameManager.State.SEEKING_PHASE || (data != null && data.isLocked()))
            event.setCancelled(true);
    }

    // Remove any block damage caused by seekers
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockDamage(BlockDamageEvent event) {
        Player seeker = event.getPlayer();
        if (!gm().isSeeker(seeker))
            return;
        if (!gm().handleHiderBlockHit(seeker, event.getBlock().getLocation()))
            return;

        event.setCancelled(true);
    }

    // Fallback if hitting the interaction hitbox or block display fails for some
    // reason
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerLeftClickBlock(PlayerInteractEvent event) {
        if (event.getAction() != Action.LEFT_CLICK_BLOCK || event.getClickedBlock() == null)
            return;

        Player seeker = event.getPlayer();
        if (!gm().isSeeker(seeker))
            return;
        if (!gm().handleHiderBlockHit(seeker, event.getClickedBlock().getLocation()))
            return;

        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onHiderMenuItemInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK)
            return;

        EquipmentSlot hand = event.getHand();
        if (hand == null)
            return;

        Player player = event.getPlayer();
        if (!gm().isHider(player))
            return;

        ItemStack usedItem = event.getItem();
        if (usedItem == null) {
            usedItem = hand == EquipmentSlot.HAND
                    ? player.getInventory().getItemInMainHand()
                    : player.getInventory().getItemInOffHand();
        }
        if (!gm().isHiderMenuItem(usedItem))
            return;

        if (hand == EquipmentSlot.OFF_HAND && gm().isHiderMenuItem(player.getInventory().getItemInMainHand())) {
            return;
        }

        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);
        event.setCancelled(true);
        gm().openHiderBlockSelectionMenu(player);
    }

    // Prevent seekers from moving their locked seeker weapon in their inventory or
    // dropping it
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player))
            return;

        if (gm().isHider(player)) {
            if (moveLockedHiderMenuItemFromOffhand(player)) {
                event.setCancelled(true);
                return;
            }

            if (gm().isLockedHiderMenuItem(event.getCurrentItem()) || gm().isLockedHiderMenuItem(event.getCursor())) {
                event.setCancelled(true);
                return;
            }

            int hiderMenuSlot = gm().getHiderMenuSlot();
            if (event.getHotbarButton() == hiderMenuSlot) {
                PlayerInventory inventory = player.getInventory();
                if (gm().isLockedHiderMenuItem(inventory.getItem(hiderMenuSlot))) {
                    event.setCancelled(true);
                    return;
                }
            }

            if (event.getClickedInventory() instanceof PlayerInventory
                    && event.getSlot() == hiderMenuSlot) {
                event.setCancelled(true);
                return;
            }
        }

        if (!gm().isSeeker(player))
            return;

        if (moveLockedSeekerWeaponFromOffhand(player)) {
            event.setCancelled(true);
            return;
        }

        if (gm().isLockedSeekerWeapon(event.getCurrentItem()) || gm().isLockedSeekerWeapon(event.getCursor())) {
            event.setCancelled(true);
            return;
        }

        if (event.getHotbarButton() == GameManager.SEEKER_WEAPON_SLOT) {
            PlayerInventory inventory = player.getInventory();
            if (gm().isLockedSeekerWeapon(inventory.getItem(GameManager.SEEKER_WEAPON_SLOT))) {
                event.setCancelled(true);
                return;
            }
        }

        if (event.getClickedInventory() instanceof PlayerInventory
                && event.getSlot() == GameManager.SEEKER_WEAPON_SLOT) {
            event.setCancelled(true);
        }
    }

    // Prevent seekers from dragging the locked seeker weapon or swapping it to
    // their off-hand
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player))
            return;

        if (gm().isHider(player)) {
            if (gm().isLockedHiderMenuItem(event.getOldCursor())) {
                event.setCancelled(true);
                return;
            }

            int hiderMenuSlot = gm().getHiderMenuSlot();
            if (event.getRawSlots().contains(hiderMenuSlot)
                    && gm().isLockedHiderMenuItem(player.getInventory().getItem(hiderMenuSlot))) {
                event.setCancelled(true);
                return;
            }
        }

        if (!gm().isSeeker(player))
            return;

        if (gm().isLockedSeekerWeapon(event.getOldCursor())) {
            event.setCancelled(true);
            return;
        }

        if (!event.getRawSlots().contains(GameManager.SEEKER_WEAPON_SLOT))
            return;
        if (gm().isLockedSeekerWeapon(player.getInventory().getItem(GameManager.SEEKER_WEAPON_SLOT))) {
            event.setCancelled(true);
        }
    }

    // Prevent seekers from dropping the locked seeker weapon
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        Player player = event.getPlayer();

        if (gm().isHider(player) && gm().isLockedHiderMenuItem(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
            return;
        }

        if (!gm().isSeeker(player))
            return;

        if (gm().isActiveRound()) {
            event.setCancelled(true);
            return;
        }

        if (!gm().isLockedSeekerWeapon(event.getItemDrop().getItemStack()))
            return;

        event.setCancelled(true);
    }

    // Prevent seekers from swapping the locked seeker weapon to their off-hand
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerSwapHandItems(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();

        if (gm().isHider(player)) {
            boolean mainHandLocked = gm().isLockedHiderMenuItem(event.getMainHandItem());
            boolean offHandLocked = gm().isLockedHiderMenuItem(event.getOffHandItem());
            if (mainHandLocked || offHandLocked) {
                event.setCancelled(true);
                if (offHandLocked) {
                    moveLockedHiderMenuItemFromOffhand(player);
                }
                return;
            }
        }

        if (!gm().isSeeker(player))
            return;
        boolean mainHandLocked = gm().isLockedSeekerWeapon(event.getMainHandItem());
        boolean offHandLocked = gm().isLockedSeekerWeapon(event.getOffHandItem());
        if (!mainHandLocked && !offHandLocked)
            return;

        event.setCancelled(true);
        if (offHandLocked) {
            moveLockedSeekerWeaponFromOffhand(player);
        }
    }

    private boolean moveLockedSeekerWeaponFromOffhand(Player player) {
        PlayerInventory inventory = player.getInventory();
        ItemStack offhand = inventory.getItemInOffHand();
        if (!gm().isLockedSeekerWeapon(offhand))
            return false;

        ItemStack slotZero = inventory.getItem(GameManager.SEEKER_WEAPON_SLOT);
        if (slotZero != null && !slotZero.getType().isAir() && !gm().isLockedSeekerWeapon(slotZero)) {
            inventory.addItem(slotZero);
        }

        inventory.setItem(GameManager.SEEKER_WEAPON_SLOT, offhand.clone());
        inventory.setItemInOffHand(new ItemStack(Material.AIR));
        inventory.setHeldItemSlot(GameManager.SEEKER_WEAPON_SLOT);
        return true;
    }

    private boolean moveLockedHiderMenuItemFromOffhand(Player player) {
        PlayerInventory inventory = player.getInventory();
        ItemStack offhand = inventory.getItemInOffHand();
        if (!gm().isLockedHiderMenuItem(offhand))
            return false;

        int hiderMenuSlot = gm().getHiderMenuSlot();
        ItemStack menuSlotItem = inventory.getItem(hiderMenuSlot);
        if (menuSlotItem != null && !menuSlotItem.getType().isAir() && !gm().isLockedHiderMenuItem(menuSlotItem)) {
            inventory.addItem(menuSlotItem);
        }

        inventory.setItem(hiderMenuSlot, offhand.clone());
        inventory.setItemInOffHand(new ItemStack(Material.AIR));
        return true;
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player))
            return;

        gm().setBlockSelectionMenuOpen(player, false);
    }

    // Handle player movement to unlock them if they move off their locked block
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player p = event.getPlayer();

        if (!gm().isHider(p))
            return;

        HiderData data = gm().getHiderData(p.getUniqueId());
        if (data == null || !data.isLocked())
            return;
        if (gm().isWithinLockMovementGrace(p))
            return;

        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null)
            return;

        double movedX = to.getX() - from.getX();
        double movedZ = to.getZ() - from.getZ();
        double horizontalMovedSquared = movedX * movedX + movedZ * movedZ;
        if (horizontalMovedSquared < 0.0004)
            return;

        Location placed = data.getPlacedBlockLocation();
        if (placed == null || placed.getWorld() == null)
            return;
        if (!placed.getWorld().equals(to.getWorld())) {
            gm().unlockHider(p);
            return;
        }

        Location expected = data.getLockedPlayerLocation();
        double centerX = expected != null ? expected.getX() : placed.getX() + 0.5;
        double centerZ = expected != null ? expected.getZ() : placed.getZ() + 0.5;
        double distanceX = to.getX() - centerX;
        double distanceZ = to.getZ() - centerZ;
        double horizontalDistanceFromCenterSquared = distanceX * distanceX + distanceZ * distanceZ;
        if (horizontalDistanceFromCenterSquared < 0.04)
            return;

        gm().unlockHider(p);
    }

    // Handle player quitting - remove them from the game
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        gm().handlePlayerQuit(p);
    }

    // Hiders cant die during gameplay, so cancel any death events and suppress the
    // message just in case
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player p = event.getEntity();
        if (gm().isHider(p) && gm().getState() == GameManager.State.SEEKING_PHASE) {
            event.setShowDeathMessages(false);
            event.deathMessage(null); // Not technically needed, nice sanity check just in-case
            event.setKeepInventory(true);
            event.setKeepLevel(true);
        }
    }

    // Handle seekers hitting hiders by left-clicking interaction hitboxes or block
    // displays
    @EventHandler
    public void onInteractAtEntity(PlayerInteractAtEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND)
            return;

        Player player = event.getPlayer();
        if (gm().isHider(player) && gm().isHiderMenuItem(player.getInventory().getItemInMainHand())) {
            event.setCancelled(true);
            gm().openHiderBlockSelectionMenu(player);
            return;
        }

        Player seeker = player;
        if (!gm().isSeeker(seeker))
            return;

        Entity target = event.getRightClicked();
        if (target instanceof Interaction hitbox) {
            event.setCancelled(true);
            gm().handleHiderHit(seeker, hitbox);
            return;
        }

        if (target instanceof BlockDisplay blockDisplay) {
            event.setCancelled(true);
            gm().handleHiderHit(seeker, blockDisplay);
        }
    }
}
