package me.aquaenchants.listener;

import me.aquaenchants.AquaEnchatsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Post-fix for repair commands (/repair, /cmi repair).
 *
 * Why this exists:
 *  - Some plugins (incl. custom-enchant plugins) may rewrite ItemMeta shortly after a repair command,
 *    unintentionally restoring the previous damage value.
 *  - CMI may also skip repairing some items (e.g. items marked as Unbreakable but still damaged).
 *
 * Design goals:
 *  - DO NOT cancel the original command (avoid breaking other plugins and messages)
 *  - Run AFTER the command is processed (next tick) and hard-set damage to 0
 *  - Avoid permission bypass: we only run if the player is likely allowed to use repair.
 */
public final class RepairHardFixListener implements Listener {

    private final AquaEnchatsPlugin plugin;

    public RepairHardFixListener(AquaEnchatsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onCmd(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (player == null) return;

        String msg = event.getMessage();
        if (msg == null) return;

        String m = msg.trim().toLowerCase();
        boolean isRepair = m.startsWith("/repair") || m.startsWith("/cmi repair") || m.startsWith("/cmi:repair");
        if (!isRepair) return;

        boolean all = m.contains(" all") || m.endsWith("all");

        // Avoid permission bypass: check common permissions.
        // If your permissions differ, grant aquaenchants.repair to the groups who can use /repair.
        if (!hasRepairPermission(player, m)) return;

        // Run AFTER command execution.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            if (all) repairAll(player);
            else repairHand(player);
        });
    }

    private boolean hasRepairPermission(Player player, String m) {
        if (player.isOp()) return true;
        if (player.hasPermission("aquaenchants.repair")) return true;

        // CMI common permissions
        if (m.startsWith("/cmi") || m.startsWith("/cmi:")) {
            if (player.hasPermission("cmi.command.repair")) return true;
            if (player.hasPermission("cmi.command.repair.all")) return true;
            if (player.hasPermission("cmi.command.repairall")) return true;
            if (player.hasPermission("cmi.command.repairall.all")) return true;
        }

        // Generic /repair (EssentialsX / other)
        if (player.hasPermission("essentials.repair")) return true;
        if (player.hasPermission("essentials.repair.all")) return true;

        // Fallback: do not run.
        return false;
    }

    private void repairHand(Player player) {
        PlayerInventory inv = player.getInventory();
        ItemStack main = inv.getItemInMainHand();
        if (!repairItem(main)) {
            repairItem(inv.getItemInOffHand());
        }
        player.updateInventory();
        // Don't spam: original plugin already sends its own messages.
    }

    private void repairAll(Player player) {
        PlayerInventory inv = player.getInventory();
        for (ItemStack it : inv.getContents()) repairItem(it);
        for (ItemStack it : inv.getArmorContents()) repairItem(it);
        repairItem(inv.getItemInOffHand());
        repairItem(inv.getItemInMainHand());
        player.updateInventory();
        // Don't spam: original plugin already sends its own messages.
    }

    private boolean repairItem(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof Damageable dmg)) return false;
        dmg.setDamage(0);
        item.setItemMeta(meta);
        return true;
    }
}
