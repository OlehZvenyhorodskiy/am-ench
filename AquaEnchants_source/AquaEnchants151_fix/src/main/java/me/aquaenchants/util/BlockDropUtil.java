package me.aquaenchants.util;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Collection;
import java.util.Collections;

/**
 * Helpers for manual block drops.
 *
 * Some Bukkit/Paper versions return a plain/empty shulker item from Block#getDrops(...)
 * when the drop is calculated manually before the block is broken. Vanilla block breaking
 * preserves the shulker BlockStateMeta, but effects like Telepathy, LineMine and BreakBlock
 * disable vanilla drops and move/drop items themselves. This helper keeps the contents when
 * those effects break shulker boxes.
 */
public final class BlockDropUtil {

    private BlockDropUtil() {
    }

    /**
     * Drop calculation for effects that handle drops manually.
     * Currently fixes shulker boxes with contents; other blocks use Bukkit's normal drops.
     */
    public static Collection<ItemStack> getDropsPreservingBlockState(Block block, ItemStack tool, Player player) {
        if (block == null) {
            return Collections.emptyList();
        }

        ItemStack shulkerDrop = createShulkerDropWithContents(block);
        if (shulkerDrop != null) {
            return Collections.singletonList(shulkerDrop);
        }

        try {
            Collection<ItemStack> drops = block.getDrops(tool, player);
            return drops != null ? drops : Collections.emptyList();
        } catch (Throwable ignored) {
            try {
                Collection<ItemStack> drops = block.getDrops(tool);
                return drops != null ? drops : Collections.emptyList();
            } catch (Throwable ignoredAgain) {
                return Collections.emptyList();
            }
        }
    }

    public static boolean isShulkerBox(Material type) {
        return type != null && type.name().endsWith("SHULKER_BOX");
    }

    /**
     * Creates a shulker item with copied inventory contents and custom name.
     * Returns null for non-shulker blocks so callers can fall back to normal drops.
     */
    public static ItemStack createShulkerDropWithContents(Block block) {
        if (block == null || !isShulkerBox(block.getType())) {
            return null;
        }

        ItemStack item = new ItemStack(block.getType(), 1);
        BlockState sourceState = block.getState();
        if (!(sourceState instanceof ShulkerBox)) {
            return item;
        }

        ItemMeta rawMeta = item.getItemMeta();
        if (!(rawMeta instanceof BlockStateMeta)) {
            return item;
        }

        ShulkerBox sourceBox = (ShulkerBox) sourceState;
        BlockStateMeta meta = (BlockStateMeta) rawMeta;

        try {
            BlockState itemState = meta.getBlockState();
            if (itemState instanceof ShulkerBox) {
                ShulkerBox targetBox = (ShulkerBox) itemState;
                targetBox.getInventory().setContents(cloneContents(sourceBox.getInventory().getContents()));

                try {
                    if (sourceBox.getCustomName() != null) {
                        targetBox.setCustomName(sourceBox.getCustomName());
                    }
                } catch (Throwable ignored) {
                    // Optional API compatibility path.
                }

                meta.setBlockState(targetBox);
            } else {
                // Fallback for unusual API implementations.
                meta.setBlockState(sourceState);
            }
            item.setItemMeta(meta);
        } catch (Throwable ignored) {
            // Keep at least the colored shulker item instead of failing the block break.
        }

        return item;
    }

    public static ItemStack[] cloneContents(ItemStack[] contents) {
        if (contents == null) {
            return new ItemStack[0];
        }
        ItemStack[] clone = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            clone[i] = item == null ? null : item.clone();
        }
        return clone;
    }
}
