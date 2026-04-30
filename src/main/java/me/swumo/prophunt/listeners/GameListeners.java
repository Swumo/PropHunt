package me.swumo.prophunt.listeners;

import me.swumo.prophunt.PropHunt;
import me.swumo.prophunt.game.GameManager;
import me.swumo.prophunt.game.HiderData;
import org.bukkit.Location;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

public class GameListeners implements Listener {
    private final PropHunt plugin;

    public GameListeners(PropHunt plugin) {
        this.plugin = plugin;
    }

    private GameManager gm() {
        return plugin.getGameManager();
    }

    // Handle seekers hitting hiders by left-clicking them or their interaction hitboxes/block displays
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player seeker))
            return;
        Entity target = event.getEntity();
        
        switch (target) {
            case Interaction hitbox -> {
                event.setCancelled(true);
                if (gm().isSeeker(seeker)) gm().handleHiderHit(seeker, hitbox);
            }
            case BlockDisplay bd -> {
                event.setCancelled(true);
                if (gm().isSeeker(seeker)) gm().handleHiderHit(seeker, bd);
            }
            case Player victim -> {
                HiderData data = gm().getHiderData(victim.getUniqueId());
                if (gm().isSeeker(seeker) && gm().isHider(victim) && data != null && !data.isLocked()) {
                    event.setCancelled(true);
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
        if (!(event.getEntity() instanceof Player p)) return;

        if (gm().getState() == GameManager.State.SEEKING_PHASE && gm().isHider(p))
            event.setCancelled(true);
    }

    // Remove any block damage caused by seekers
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockDamage(BlockDamageEvent event) {
        Player seeker = event.getPlayer();
        if (!gm().isSeeker(seeker)) return;
        if (!gm().handleHiderBlockHit(seeker, event.getBlock().getLocation())) return;

        event.setCancelled(true);
    }

    // Fallback if hitting the interaction hitbox or block display fails for some
    // reason
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerLeftClickBlock(PlayerInteractEvent event) {
        if (event.getAction() != Action.LEFT_CLICK_BLOCK || event.getClickedBlock() == null) return;

        Player seeker = event.getPlayer();
        if (!gm().isSeeker(seeker)) return;
        if (!gm().handleHiderBlockHit(seeker, event.getClickedBlock().getLocation())) return;

        event.setCancelled(true);
    }

    // Handle player movement to unlock them if they move off their locked block
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player p = event.getPlayer();

        if (!gm().isHider(p)) return;

        HiderData data = gm().getHiderData(p.getUniqueId());
        if (data == null || !data.isLocked()) return;

        Location to = event.getTo();
        Location placed = data.getPlacedBlockLocation();
        if (placed == null || placed.getWorld() == null) return;
        if (!placed.getWorld().equals(to.getWorld())) {
            gm().unlockHider(p);
            return;
        }

        boolean movedOffLockedBlock = to.getBlockX() != placed.getBlockX() || to.getBlockZ() != placed.getBlockZ();
        if (!movedOffLockedBlock) return;

        gm().unlockHider(p);
    }

    // Handle player quitting - remove them from the game
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        gm().handlePlayerQuit(p);
    }

    // Hiders cant die during gameplay, so cancel any death events and suppress the message just in case
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

    // Handle seekers hitting hiders by left-clicking interaction hitboxes or block displays
    @EventHandler
    public void onInteractAtEntity(PlayerInteractAtEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;

        Player seeker = event.getPlayer();
        if (!gm().isSeeker(seeker)) return;

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
