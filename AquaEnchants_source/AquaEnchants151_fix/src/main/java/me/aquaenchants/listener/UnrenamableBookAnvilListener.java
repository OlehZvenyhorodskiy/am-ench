package me.aquaenchants.listener;

import me.aquaenchants.enchant.EnchantManager;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Method;

/**
 * Blocks renaming of plugin-issued enchanted books in an anvil.
 *
 * Important: we do NOT block using the book to apply enchantments (when a right item exists).
 * We only block the "rename" scenario where the book is placed alone in the left slot.
 */
public class UnrenamableBookAnvilListener implements Listener {

    private final EnchantManager enchantManager;

    public UnrenamableBookAnvilListener(EnchantManager enchantManager) {
        this.enchantManager = enchantManager;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        AnvilInventory inv = event.getInventory();
        ItemStack left = inv.getItem(0);
        if (!enchantManager.isUnrenamableBook(left)) {
            return;
        }

        // If there is a right item, the player is most likely applying the book to an item.
        // Do not block that (even if they also rename the resulting item).
        ItemStack right = inv.getItem(1);
        if (right != null && right.getType() != Material.AIR) {
            return;
        }

        // Rename attempt?
        String renameText = getRenameText(inv);
        if (renameText == null) {
            // If we cannot detect rename text on this server version, safest behavior is to
            // block the anvil result when the unrenamable book is placed alone.
            event.setResult(null);
            return;
        }

        if (!renameText.isEmpty()) {
            event.setResult(null);
        }
    }

    private String getRenameText(AnvilInventory inv) {
        try {
            Method m = inv.getClass().getMethod("getRenameText");
            Object v = m.invoke(inv);
            return v == null ? "" : String.valueOf(v);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
