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
    private final PropHunt plugin;

    public BlockSelectGUI(PropHunt plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        List<Material> blocks = plugin.getGameManager().getAvailableDisguiseBlocks();
        int rows = Math.max(1, (int) Math.ceil(blocks.size() / 9.0));
        Gui gui = Gui.empty(9, rows);
        for (int i = 0; i < blocks.size(); i++) {
            gui.setItem(i, new BlockItem(blocks.get(i)));
        }

        Window.single()
            .setViewer(player)
            .setTitle(TITLE)
            .setGui(gui)
            .build()
            .open();
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
