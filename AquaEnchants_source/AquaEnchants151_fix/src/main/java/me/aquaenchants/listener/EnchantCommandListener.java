package me.aquaenchants.listener;

import me.aquaenchants.enchant.EnchantManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Фикс для случая когда ванильные зачарования добавляются командой (/enchant и аналоги).
 * Тогда ItemMeta меняется, но лор от AquaEnchants не пересобирается, из-за чего
 * (при включённом скрытии ванильных энчантов) игрок не видит зачарований.
 */
public final class EnchantCommandListener implements Listener {

    private final JavaPlugin plugin;
    private final EnchantManager enchantManager;

    public EnchantCommandListener(JavaPlugin plugin, EnchantManager enchantManager) {
        this.plugin = plugin;
        this.enchantManager = enchantManager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String msg = event.getMessage();
        if (msg == null || msg.isEmpty()) return;

        // Ловим ванильный /enchant и /minecraft:enchant (а также варианты с пробелом после слеша)
        String lower = msg.toLowerCase();
        if (!(lower.startsWith("/enchant") || lower.startsWith("/minecraft:enchant"))) {
            return;
        }

        Player player = event.getPlayer();

        // Команда применит энчант уже после события — поэтому обновляем на следующем тике.
        Bukkit.getScheduler().runTask(plugin, () -> refreshPlayerItems(player));
    }

    private void refreshPlayerItems(Player player) {
        if (player == null || !player.isOnline()) return;

        // Обновляем всё, чтобы поймать любые команды/плагины, которые чарят предметы в инвентаре.
        for (ItemStack item : player.getInventory().getContents()) {
            enchantManager.refreshLoreIfCustom(item);
        }
        for (ItemStack item : player.getInventory().getArmorContents()) {
            enchantManager.refreshLoreIfCustom(item);
        }
        enchantManager.refreshLoreIfCustom(player.getInventory().getItemInOffHand());
        enchantManager.refreshLoreIfCustom(player.getInventory().getItemInMainHand());
    }
}
