package me.aquaenchants.listener;

import me.aquaenchants.AquaEnchatsPlugin;
import me.aquaenchants.enchant.CustomEnchant;
import me.aquaenchants.enchant.EnchantManager;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Map;

/**
 * Зачарование "signal" - предупреждение о низкой прочности предмета.
 * Работает на любом предмете/оружии/инструменте/броне, у которого есть это зачарование.
 * При падении прочности ниже порога игрок получает сообщение в чат и тайтл
 * при каждом дальнейшем использовании предмета (каждой потере прочности).
 */
public class SignalListener implements Listener {

    private static final String ENCHANT_ID = "signal";
    // Порог "критической" прочности
    private static final int THRESHOLD_DURABILITY = 15;

    private final AquaEnchatsPlugin plugin;
    private final EnchantManager enchantManager;

    public SignalListener(AquaEnchatsPlugin plugin, EnchantManager enchantManager) {
        this.plugin = plugin;
        this.enchantManager = enchantManager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemDamage(PlayerItemDamageEvent event) {
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null || item.getType() == Material.AIR) {
            return;
        }

        // Проверяем, есть ли на предмете наше зачарование "signal"
        CustomEnchant signalEnchant = enchantManager.getEnchant(ENCHANT_ID);
        if (signalEnchant == null) {
            return;
        }

        Map<CustomEnchant, Integer> enchants = enchantManager.getEnchantmentsOnItem(item);
        Integer level = enchants.get(signalEnchant);
        if (level == null || level <= 0) {
            return;
        }

        // Предмет должен иметь прочность
        ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof Damageable)) {
            return;
        }
        Damageable damageable = (Damageable) meta;

        int maxDurability = item.getType().getMaxDurability();
        if (maxDurability <= 0) {
            return;
        }

        int currentDamage = damageable.getDamage();
        int newDamage = currentDamage + event.getDamage();
        int remaining = maxDurability - newDamage;

        if (remaining <= 0) {
            // предмет сейчас сломается, можно не спамить лишним сообщением
            return;
        }

        if (remaining >= THRESHOLD_DURABILITY) {
            // ещё не достигли "критической" зоны
            return;
        }

        // Формируем сообщения
        String chatMsg = ChatColor.RED + "Прочность предмета критически мала и равна "
                + ChatColor.YELLOW + remaining + ChatColor.RED + " единиц.";

        player.sendMessage(chatMsg);

        String title = ChatColor.RED + "Низкая прочность!";
        String subtitle = ChatColor.YELLOW + "Осталось " + remaining + " единиц прочности.";

        try {
            // API 1.9+
            player.sendTitle(title, subtitle, 10, 40, 10);
        } catch (NoSuchMethodError ignored) {
            // На всякий случай, если на старой версии нет такого метода
        }
    }
}
