package me.aquaenchants.listener;

import me.aquaenchants.AquaEnchatsPlugin;
import me.aquaenchants.enchant.CustomEnchant;
import me.aquaenchants.enchant.EnchantManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * Зачарование "palirol" на поножах:
 * Уменьшает замедление от паутины (COBWEB), позволяя игроку
 * проходить через паутину с разной скоростью в зависимости от уровня.
 *
 * Уровни:
 *  1 -> 40% от нормальной скорости движения
 *  2 -> 70% от нормальной скорости движения
 *  3 -> 100% (полная свобода, паутина не замедляет)
 *
 * Механика:
 * - Используется комбинация Speed эффекта и прямой корректировки velocity
 * - Проверка состояния каждые 2 тика для оптимизации
 * - Плавное применение эффектов без рывков
 */
public class PalirolListener implements Listener {

    private static final String ENCHANT_ID = "palirol";
    private static final UUID SPEED_MODIFIER_UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
    
    private final AquaEnchatsPlugin plugin;
    private final EnchantManager enchantManager;
    
    // Отслеживание игроков в паутине
    private final Map<UUID, PlayerCobwebData> playerData = new HashMap<>();
    private final Set<UUID> playersInCobweb = new HashSet<>();
    
    // Задача периодической проверки
    private int checkTaskId = -1;
    
    // Флаг для предотвращения создания задач после shutdown
    private boolean isShutdown = false;

    public PalirolListener(AquaEnchatsPlugin plugin, EnchantManager enchantManager) {
        this.plugin = plugin;
        this.enchantManager = enchantManager;
        startPeriodicCheck();
    }

    /**
     * Запускает периодическую проверку игроков в паутине
     */
    private void startPeriodicCheck() {
        if (isShutdown) return;
        
        checkTaskId = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (isShutdown) return;
            
            for (UUID uuid : new HashSet<>(playersInCobweb)) {
                Player player = Bukkit.getPlayer(uuid);
                if (player == null || !player.isOnline()) {
                    cleanupPlayer(uuid);
                    continue;
                }
                
                if (isInCobweb(player)) {
                    applyPalirolEffect(player);
                } else {
                    cleanupPlayer(uuid);
                }
            }
        }, 2L, 2L).getTaskId(); // Каждые 2 тика для баланса производительности
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        
        boolean inCobweb = isInCobweb(player);
        
        if (inCobweb) {
            playersInCobweb.add(uuid);
            applyPalirolEffect(player);
        } else if (playersInCobweb.contains(uuid)) {
            cleanupPlayer(uuid);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        cleanupPlayer(event.getPlayer().getUniqueId());
    }

    /**
     * Применяет эффект зачарования к игроку
     */
    private void applyPalirolEffect(Player player) {
        UUID uuid = player.getUniqueId();
        
        // Получаем уровень зачарования
        ItemStack leggings = player.getInventory().getLeggings();
        int level = getEnchantLevel(leggings);
        
        if (level <= 0) {
            cleanupPlayer(uuid);
            return;
        }
        
        // Получаем или создаем данные игрока
        PlayerCobwebData data = playerData.computeIfAbsent(uuid, k -> new PlayerCobwebData());
        
        // Если уровень изменился, обновляем эффекты
        if (data.currentLevel != level) {
            data.currentLevel = level;
            applySpeedBoost(player, level);
        }
        
        // Корректируем velocity для компенсации замедления паутины
        correctVelocity(player, level);
        
        data.lastCheckTick = player.getWorld().getFullTime();
    }

    /**
     * Применяет эффект скорости для компенсации замедления
     */
    private void applySpeedBoost(Player player, int level) {
        // Убираем старые эффекты скорости от этого зачарования
        player.removePotionEffect(PotionEffectType.SPEED);
        
        // Определяем силу эффекта на основе уровня
        int amplifier;
        switch (level) {
            case 1:
                amplifier = 1; // Speed II для ~40% компенсации
                break;
            case 2:
                amplifier = 3; // Speed IV для ~70% компенсации
                break;
            default: // 3+
                amplifier = 5; // Speed VI для ~100% компенсации
                break;
        }
        
        // Применяем эффект с коротким временем, но постоянно обновляем его
        player.addPotionEffect(new PotionEffect(
            PotionEffectType.SPEED,
            40, // 2 секунды (40 тиков)
            amplifier,
            false, // не ambient
            false, // не показывать частицы
            false  // не показывать иконку
        ));
    }

    /**
     * Корректирует velocity игрока для естественного движения в паутине
     */
    private void correctVelocity(Player player, int level) {
        Vector velocity = player.getVelocity();
        
        // Паутина замедляет velocity до очень малых значений
        // Мы усиливаем его в зависимости от уровня
        
        double multiplier;
        switch (level) {
            case 1:
                multiplier = 2.5; // Умеренное усиление
                break;
            case 2:
                multiplier = 4.5; // Сильное усиление
                break;
            default: // 3+
                multiplier = 8.0; // Максимальное усиление
                break;
        }
        
        // Усиливаем горизонтальное движение
        double newX = velocity.getX() * multiplier;
        double newZ = velocity.getZ() * multiplier;
        
        // Вертикальное движение усиливаем меньше (паутина сильнее замедляет по Y)
        double verticalMultiplier = multiplier * 1.5;
        double newY = velocity.getY() * verticalMultiplier;
        
        // Ограничиваем максимальную скорость
        double maxHorizontal = level == 3 ? 0.6 : 0.4; 
        double maxVertical = level == 3 ? 1.0 : 0.6;
        
        newX = clamp(newX, -maxHorizontal, maxHorizontal);
        newZ = clamp(newZ, -maxHorizontal, maxHorizontal);
        newY = clamp(newY, -maxVertical, maxVertical);
        
        // Добавляем дополнительный импульс при спринте
        if (player.isSprinting() && level >= 2) {
            Vector direction = player.getLocation().getDirection();
            direction.setY(0);
            direction.normalize();
            
            double sprintBoost = level == 3 ? 0.15 : 0.08;
            newX += direction.getX() * sprintBoost;
            newZ += direction.getZ() * sprintBoost;
        }
        
        // Применяем новую velocity только если есть существенное изменение
        if (Math.abs(newX - velocity.getX()) > 0.001 || 
            Math.abs(newZ - velocity.getZ()) > 0.001 ||
            Math.abs(newY - velocity.getY()) > 0.001) {
            player.setVelocity(new Vector(newX, newY, newZ));
        }
    }

    /**
     * Ограничивает значение в заданном диапазоне
     */
    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Очищает эффекты зачарования для игрока
     */
    private void cleanupPlayer(UUID uuid) {
        playersInCobweb.remove(uuid);
        PlayerCobwebData data = playerData.remove(uuid);
        
        if (data != null) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                // Убираем эффекты скорости, если они были от этого зачарования
                PotionEffect speedEffect = player.getPotionEffect(PotionEffectType.SPEED);
                if (speedEffect != null && !speedEffect.hasIcon()) {
                    player.removePotionEffect(PotionEffectType.SPEED);
                }
            }
        }
    }

    /**
     * Получает уровень зачарования на предмете
     */
    private int getEnchantLevel(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return 0;
        }

        Map<CustomEnchant, Integer> enchants = enchantManager.getEnchantmentsOnItem(item);
        if (enchants == null || enchants.isEmpty()) {
            return 0;
        }

        for (Map.Entry<CustomEnchant, Integer> entry : enchants.entrySet()) {
            CustomEnchant enchant = entry.getKey();
            if (enchant != null && ENCHANT_ID.equalsIgnoreCase(enchant.getId())) {
                return entry.getValue();
            }
        }
        
        return 0;
    }

    /**
     * Проверяет, находится ли игрок в паутине
     */
    private boolean isInCobweb(Player player) {
        Block feetBlock = player.getLocation().getBlock();
        Block headBlock = feetBlock.getRelative(0, 1, 0);
        Block eyeBlock = player.getEyeLocation().getBlock();
        
        // Проверяем все три уровня для надежности
        return feetBlock.getType() == Material.COBWEB || 
               headBlock.getType() == Material.COBWEB ||
               eyeBlock.getType() == Material.COBWEB;
    }

    /**
     * Очистка при выключении плагина
     */
    public void shutdown() {
        isShutdown = true;
        
        // Останавливаем периодическую задачу
        if (checkTaskId != -1) {
            try {
                Bukkit.getScheduler().cancelTask(checkTaskId);
            } catch (Exception e) {
                plugin.getLogger().warning("Error canceling Palirol task: " + e.getMessage());
            }
            checkTaskId = -1;
        }
        
        // Очищаем всех игроков
        for (UUID uuid : new HashSet<>(playersInCobweb)) {
            cleanupPlayer(uuid);
        }
        
        playerData.clear();
        playersInCobweb.clear();
        
        plugin.getLogger().info("PalirolListener shutdown complete");
    }

    /**
     * Класс для хранения данных игрока в паутине
     */
    private static class PlayerCobwebData {
        int currentLevel = 0;
        long lastCheckTick = 0;
    }
}
