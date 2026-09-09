package me.aquaenchants.listener;

import me.aquaenchants.AquaEnchatsPlugin;
import me.aquaenchants.enchant.CustomEnchant;
import me.aquaenchants.enchant.EnchantManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * LavaWalkerListener — реализация эффекта LAVA_WALKER.
 * Позволяет игрокам временно ходить по лаве, заменяя её на обсидиан,
 * который через 20 секунд превращается обратно в лаву.
 *
 * Логика полностью аналогична WaterWalkerListener, но работает с лавой.
 */
public class LavaWalkerListener implements Listener {

    private final AquaEnchatsPlugin plugin;
    private final EnchantManager enchantManager;

    // временные блоки обсидиана: блок -> время установки (ms)
    private final Map<Block, Long> tempBlocks = new HashMap<>();

    // последняя целочисленная позиция под игроком, чтобы не триггерить
    // обработку десятки раз на одном и том же блоке
    private final Map<UUID, long[]> lastBlockPos = new HashMap<>();

    // время жизни дорожки (20 секунд)
    private static final long LIFETIME_MS = 20_000L;

    private final BukkitTask cleanupTask;

    public LavaWalkerListener(AquaEnchatsPlugin plugin, EnchantManager enchantManager) {
        this.plugin = plugin;
        this.enchantManager = enchantManager;

        // Раз в секунду очищаем устаревшие блоки
        this.cleanupTask = Bukkit.getScheduler().runTaskTimer(plugin, this::cleanupExpiredBlocks, 20L, 20L);
    }

    public void shutdown() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
        }

        // При желании можно вернуть лаву, но обычно это не критично
        for (Block block : tempBlocks.keySet()) {
            if (block != null && block.getType() == Material.OBSIDIAN) {
                block.setType(Material.LAVA, false);
            }
        }
        tempBlocks.clear();
        lastBlockPos.clear();
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (event.getTo() == null) return;

        // Игрок остался в том же блоке — ничего не делаем
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }

        if (player.isFlying() || player.isInsideVehicle() || player.isDead()) {
            return;
        }

        ItemStack boots = player.getInventory().getBoots();
        if (boots == null || boots.getType().isAir()) {
            return;
        }

        // Проверяем, есть ли на ботинках наше кастомное зачарование
        if (!hasLavaWalker(boots)) {
            return;
        }

        int bx = event.getTo().getBlockX();
        int by = event.getTo().getBlockY() - 1;
        int bz = event.getTo().getBlockZ();

        UUID uuid = player.getUniqueId();
        long[] last = lastBlockPos.get(uuid);
        if (last != null && last[0] == bx && last[1] == by && last[2] == bz) {
            // уже обрабатывали этот блок для этого игрока
            return;
        }
        lastBlockPos.put(uuid, new long[]{bx, by, bz});

        Block block = player.getWorld().getBlockAt(bx, by, bz);

        // Нас интересует только лава
        if (block.getType() != Material.LAVA) {
            return;
        }

        // Если блок уже временный — не трогаем его ещё раз
        if (tempBlocks.containsKey(block)) {
            return;
        }

        // Меняем лаву на обсидиан и запоминаем время
        block.setType(Material.OBSIDIAN, false);
        tempBlocks.put(block, System.currentTimeMillis());

        plugin.debug("[LavaWalker] replaced lava with obsidian at "
                + bx + "," + by + "," + bz + " for player " + player.getName());
    }

    private boolean hasLavaWalker(ItemStack boots) {
        Map<CustomEnchant, Integer> enchants = enchantManager.getEnchantmentsOnItem(boots);
        if (enchants == null || enchants.isEmpty()) return false;

        for (Map.Entry<CustomEnchant, Integer> entry : enchants.entrySet()) {
            CustomEnchant ench = entry.getKey();
            Integer lvl = entry.getValue();
            if (ench == null || lvl == null || lvl <= 0) continue;

            String id = ench.getId();
            if (id != null && id.equalsIgnoreCase("lavawalker")) {
                return true;
            }
        }
        return false;
    }

    private void cleanupExpiredBlocks() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<Block, Long>> iterator = tempBlocks.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<Block, Long> entry = iterator.next();
            Block block = entry.getKey();
            Long placedAt = entry.getValue();

            if (block == null || placedAt == null) {
                iterator.remove();
                continue;
            }

            if (now - placedAt >= LIFETIME_MS) {
                if (block.getType() == Material.OBSIDIAN) {
                    block.setType(Material.LAVA, false);
                }
                iterator.remove();
            }
        }
    }
}
