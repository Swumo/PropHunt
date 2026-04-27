package me.Swumo.PropHunt.GUI;

import me.Swumo.PropHunt.PropHunt;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import java.util.List;

public class BlockSelectGUI implements Listener {
    private static final String TITLE = ChatColor.DARK_GREEN + "Pick Your Block";
    private final PropHunt plugin;

    public BlockSelectGUI(PropHunt plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void open(Player player) {
        List<Material> blocks = plugin.getGameManager().getAvailableDisguiseBlocks();
        int size = Math.max(9, (int) (Math.ceil(blocks.size() / 9.0) * 9));
        Inventory inv = Bukkit.createInventory(null, size, TITLE);
        for (Material mat : blocks) {
            ItemStack item = new ItemStack(mat);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ChatColor.YELLOW + formatMaterialName(mat.name()));
                item.setItemMeta(meta);
            }
            inv.addItem(item);
        }
        player.openInventory(inv);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player))
            return;
        if (!event.getView().getTitle().equals(TITLE))
            return;
        event.setCancelled(true);
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR)
            return;
        plugin.getGameManager().setHiderBlock(player, clicked.getType());
        player.closeInventory();
    }

    private static String formatMaterialName(String name) {
        String[] parts = name.split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (!sb.isEmpty())
                sb.append(" ");
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1).toLowerCase());
        }
        return sb.toString();
    }
}
