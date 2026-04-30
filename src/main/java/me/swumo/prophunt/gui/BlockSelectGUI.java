package me.swumo.prophunt.gui;

import me.swumo.prophunt.PropHunt;
import me.swumo.prophunt.utils.StringUtils;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.jetbrains.annotations.NotNull;
import xyz.xenondevs.invui.gui.Gui;
import xyz.xenondevs.invui.item.ItemProvider;
import xyz.xenondevs.invui.item.builder.ItemBuilder;
import xyz.xenondevs.invui.item.impl.AbstractItem;
import xyz.xenondevs.invui.window.Window;

import java.util.List;

public class BlockSelectGUI {

    private static final String TITLE = "§2Pick Your Block";
    private static final int ITEMS_PER_PAGE = 36; // 4 rows x 9 columns
    private final PropHunt plugin;

    public BlockSelectGUI(PropHunt plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        List<Material> allBlocks = plugin.getGameManager().getAvailableDisguiseBlocks();
        List<Material> validBlocks = new java.util.ArrayList<>(allBlocks.size());
        for (Material mat : allBlocks) {
            if (isValidItemMaterial(mat)) {
                validBlocks.add(mat);
            }
        }

        openPage(player, validBlocks, 0);
    }

    private void openPage(Player player, List<Material> blocks, int pageNumber) {
        int totalPages = (int) Math.ceil((double) blocks.size() / ITEMS_PER_PAGE);
        if (pageNumber < 0) pageNumber = 0;
        if (pageNumber >= totalPages) pageNumber = totalPages - 1;
        if (totalPages == 0) {
            player.sendMessage("§cNo blocks available!");
            return;
        }

        // Build GUI
        Gui gui = Gui.empty(9, 6);
        
        // Add block items for this page
        int startIdx = pageNumber * ITEMS_PER_PAGE;
        int endIdx = Math.min(startIdx + ITEMS_PER_PAGE, blocks.size());
        for (int i = startIdx; i < endIdx; i++) {
            gui.setItem(i - startIdx, new BlockItem(blocks.get(i)));
        }
        
        // Add navigation buttons in bottom row
        if (pageNumber > 0) {
            gui.setItem(36, new NavigationItem(player, blocks, pageNumber - 1, "§6← Previous"));
        }
        if (pageNumber < totalPages - 1) {
            gui.setItem(43, new NavigationItem(player, blocks, pageNumber + 1, "§6Next →"));
        }
        
        // Display page info
        String pageInfo = totalPages > 1 ? " §7(" + (pageNumber + 1) + "/" + totalPages + ")" : "";
        
        Window.single()
            .setViewer(player)
            .setTitle(TITLE + pageInfo)
            .setGui(gui)
            .build()
            .open();
    }
    
    private boolean isValidItemMaterial(Material mat) {
        // Robust API-based filter: only real, placeable block items.
        return mat != null && mat.isBlock() && mat.isItem() && !mat.isAir();
    }
    
    private class NavigationItem extends AbstractItem {
        private final Player player;
        private final List<Material> blocks;
        private final int nextPage;
        private final String displayName;
        
        NavigationItem(Player player, List<Material> blocks, int nextPage, String displayName) {
            this.player = player;
            this.blocks = blocks;
            this.nextPage = nextPage;
            this.displayName = displayName;
        }
        
        @Override
        public ItemProvider getItemProvider() {
            return new ItemBuilder(Material.ARROW).setDisplayName(displayName);
        }
        
        @Override
        public void handleClick(@NotNull ClickType clickType, @NotNull Player player, @NotNull InventoryClickEvent event) {
            player.closeInventory();
            // Schedule the page opening for the next tick to ensure the inventory closes first
            org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> openPage(this.player, blocks, nextPage));
        }
    }

    private class BlockItem extends AbstractItem {
        private final Material material;

        BlockItem(Material material) {
            this.material = material;
        }

        @Override
        public ItemProvider getItemProvider() {
            return new ItemBuilder(material).setDisplayName("§e" + StringUtils.capitalize(material.name()));
        }

        @Override
        public void handleClick(@NotNull ClickType clickType, @NotNull Player player, @NotNull InventoryClickEvent event) {
            plugin.getGameManager().setHiderBlock(player, material);
            player.closeInventory();
        }
    }
}
