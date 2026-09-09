package me.aquaenchants.listener;

import me.aquaenchants.AquaEnchatsPlugin;
import me.aquaenchants.enchant.CustomEnchant;
import me.aquaenchants.enchant.EnchantLevel;
import me.aquaenchants.enchant.EnchantManager;
import me.aquaenchants.enchant.TimberEnergyUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Отвечает за:
 *  - зарядку инструмента Timber опытом игрока (ПКМ и удержание);
 *  - отображение полосы прогресса энергии над инвентарём (BossBar).
 */
public class TimberEnergyListener implements Listener {

    private final AquaEnchatsPlugin plugin;
    private final EnchantManager enchantManager;

    private final Map<UUID, BossBar> bossBars = new HashMap<>();
    private final Map<UUID, Integer> chargeTasks = new HashMap<>();
    private final Map<UUID, Long> lastNotEnoughXpMessage = new HashMap<>();
    private final int updateTaskId;

    public TimberEnergyListener(AquaEnchatsPlugin plugin, EnchantManager enchantManager) {
        this.plugin = plugin;
        this.enchantManager = enchantManager;

        // Периодически обновляем полосы энергии для всех игроков.
        this.updateTaskId = Bukkit.getScheduler().runTaskTimer(plugin, this::updateAllPlayersBars, 20L, 20L).getTaskId();
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.NORMAL)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;

        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack tool = player.getInventory().getItemInMainHand();
        if (tool == null || tool.getType().isAir()) {
            return;
        }

        int timberLevel = getTimberLevel(tool);
        if (timberLevel <= 0) {
            return;
        }

        int currentEnergy = TimberEnergyUtil.getEnergy(tool);
        int maxEnergy = getTimberMaxEnergy(tool, timberLevel);

        plugin.debug("[TimberEnergy] onInteract: player=" + player.getName()
                + ", action=" + action
                + ", tool=" + tool.getType()
                + ", level=" + timberLevel
                + ", energy=" + currentEnergy
                + ", maxEnergy=" + maxEnergy);

        // Если в руке инструмент с Timber и игрок кликает по брёвну,
        // отменяем стандартное взаимодействие (например, обтёску),
        // чтобы ПКМ использовалась только для зарядки.
        if (action == Action.RIGHT_CLICK_BLOCK) {
            Block clicked = event.getClickedBlock();
            if (clicked != null && isLog(clicked.getType())) {
                event.setCancelled(true);
            }
        }

        if (maxEnergy <= 0) {
            // Если прогресс не настроен в конфиге — считаем, что энергии нет.
            plugin.debug("[TimberEnergy] progress not configured for level " + timberLevel + " (maxEnergy <= 0)");
            player.sendMessage(ChatColor.RED + "Это зачарование Timber не настроено (отсутствует progress в конфиге).");
            return;
        }

        currentEnergy = TimberEnergyUtil.getEnergy(tool);
        if (currentEnergy >= maxEnergy) {
            plugin.debug("[TimberEnergy] tool already fully charged: energy=" + currentEnergy + ", maxEnergy=" + maxEnergy);
            player.sendMessage(ChatColor.YELLOW + "Инструмент уже полностью заряжен.");
            return;
        }

        if (player.getLevel() < 10) {
            plugin.debug("[TimberEnergy] not enough XP to start charge: totalExp=" + player.getTotalExperience());
            sendNotEnoughExpMessage(player, ChatColor.RED + "Недостаточно опыта для зарядки инструмента.");
            return;
        }

        // Запускаем задачу перекачки опыта раз в 20 тиков, если ещё не запущена.
        if (chargeTasks.containsKey(player.getUniqueId())) {
            plugin.debug("[TimberEnergy] charge task already running for " + player.getName());
            return;
        }

        int taskId = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline()) {
                plugin.debug("[TimberEnergy] player went offline, stopping charge for " + player.getName());
                stopCharge(player);
                return;
            }

            ItemStack currentTool = player.getInventory().getItemInMainHand();
            if (currentTool == null || currentTool.getType().isAir()) {
                stopCharge(player);
                return;
            }

            int lvl = getTimberLevel(currentTool);
            if (lvl <= 0) {
                stopCharge(player);
                return;
            }

            int max = getTimberMaxEnergy(currentTool, lvl);
            if (max <= 0) {
                plugin.debug("[TimberEnergy] tick: maxEnergy <= 0, stopping charge for " + player.getName());
                stopCharge(player);
                return;
            }

            int energy = TimberEnergyUtil.getEnergy(currentTool);
            if (energy >= max) {
                plugin.debug("[TimberEnergy] tick: energy>=max (" + energy + "/" + max + "), stopping charge for " + player.getName());
                stopCharge(player);
                return;
            }

            if (player.getLevel() < 10) {
                plugin.debug("[TimberEnergy] tick: not enough XP, totalExp=" + player.getTotalExperience() + ", stopping charge for " + player.getName());
                sendNotEnoughExpMessage(player, ChatColor.RED + "Недостаточно опыта для дальнейшей зарядки.");
                stopCharge(player);
                return;
            }

            // 10 единиц опыта = +1 единица энергии
            player.giveExp(-10);
            int newEnergy = TimberEnergyUtil.addEnergy(currentTool, 1, max);
            plugin.debug("[TimberEnergy] tick: +1 energy, now " + newEnergy + "/" + max + " for " + player.getName());

            updateBarForPlayer(player, currentTool, lvl, max);
        }, 0L, 20L).getTaskId();

        chargeTasks.put(player.getUniqueId(), taskId);
        player.sendMessage(ChatColor.GREEN + "Начата зарядка инструмента Timber. Удерживайте ПКМ, чтобы перенести опыт в энергию.");
    }

    private void stopCharge(Player player) {
        Integer id = chargeTasks.remove(player.getUniqueId());
        if (id != null) {
            plugin.debug("[TimberEnergy] stopping charge task for " + player.getName() + ", taskId=" + id);
            Bukkit.getScheduler().cancelTask(id);
        }
    }

    private void updateAllPlayersBars() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            ItemStack tool = player.getInventory().getItemInMainHand();
            int level = getTimberLevel(tool);
            if (tool == null || tool.getType().isAir() || level <= 0) {
                hideBar(player);
                continue;
            }

            int max = getTimberMaxEnergy(tool, level);
            if (max <= 0) {
                hideBar(player);
                continue;
            }

            updateBarForPlayer(player, tool, level, max);
        }
    }




    /**
     * Обновляет прогресс энергии Timber у игрока.
     * Текстовый индикатор вида "Энергия: X / Y" отображается над хотбаром (ActionBar).
     */
    private void updateBarForPlayer(Player player, ItemStack tool, int level, int maxEnergy) {
        if (tool == null || tool.getType().isAir() || level <= 0 || maxEnergy <= 0) {
            // Если инструмент убрали или Timber недоступен — очищаем отображение.
            sendActionBar(player, "");
            hideBar(player);
            return;
        }

        int energy = TimberEnergyUtil.getEnergy(tool);
        if (energy < 0) energy = 0;
        if (energy > maxEnergy && maxEnergy > 0) {
            energy = maxEnergy;
            TimberEnergyUtil.setEnergy(tool, energy);
        }

        String text = ChatColor.AQUA + "Энергия: " + ChatColor.GREEN + energy
                + ChatColor.GRAY + " / " + ChatColor.GREEN + maxEnergy;

        sendActionBar(player, text);
    }








    /**
     * Отключение всех задач и скрытие полосы энергии у всех игроков.
     * Вызывается при отключении / перезагрузке плагина.
     */
    public void shutdown() {
        // Останавливаем периодическое обновление полосы
        Bukkit.getScheduler().cancelTask(updateTaskId);

        // Отменяем все задачи зарядки
        for (Integer id : chargeTasks.values()) {
            if (id != null) {
                Bukkit.getScheduler().cancelTask(id);
            }
        }
        chargeTasks.clear();

        // Скрываем все BossBar'ы (если вдруг где-то создавались) и очищаем ActionBar
        for (BossBar bar : bossBars.values()) {
            if (bar != null) {
                bar.removeAll();
            }
        }
        bossBars.clear();

        // Очищаем ActionBar у всех онлайн-игроков
        for (Player p : Bukkit.getOnlinePlayers()) {
            try {
                sendActionBar(p, "");
            } catch (Throwable ignored) {}
        }
    }

    private boolean actionBarErrorLogged = false;

    /**
     * Отправка текста в ActionBar с поддержкой разных версий сервера.
     *
     * Порядок:
     *  1) Пытаемся использовать Adventure: player.sendActionBar(Component.text(text))
     *  2) Если нет - пробуем Bungee Chat API: player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent)
     *  3) В крайнем случае шлём обычное сообщение в чат, чтобы игрок хоть что-то увидел.
     */
    private void sendActionBar(Player player, String text) {
        // Adventure API (1.17+)
        try {
            Class<?> componentClass = Class.forName("net.kyori.adventure.text.Component");
            Object component = componentClass.getMethod("text", String.class).invoke(null, text);

            java.lang.reflect.Method sendActionBarMethod = player.getClass().getMethod("sendActionBar", componentClass);
            sendActionBarMethod.invoke(player, component);
            return;
        } catch (Throwable ignored) {
            // Переходим к следующей попытке
        }

        // Старый Bungee Chat API через player.spigot().sendMessage(...)
        try {
            Class<?> chatMsgTypeClass = Class.forName("net.md_5.bungee.api.ChatMessageType");
            Class<?> baseComponentClass = Class.forName("net.md_5.bungee.api.chat.BaseComponent");
            Class<?> textComponentClass = Class.forName("net.md_5.bungee.api.chat.TextComponent");

            @SuppressWarnings("unchecked")
            Object chatType = Enum.valueOf((Class<Enum>) chatMsgTypeClass.asSubclass(Enum.class), "ACTION_BAR");

            Object textComp = textComponentClass.getConstructor(String.class).newInstance(text);

            Object spigot = player.getClass().getMethod("spigot").invoke(player);
            java.lang.reflect.Method sendMethod = spigot.getClass().getMethod(
                    "sendMessage",
                    chatMsgTypeClass,
                    java.lang.reflect.Array.newInstance(baseComponentClass, 0).getClass()
            );

            Object array = java.lang.reflect.Array.newInstance(baseComponentClass, 1);
            java.lang.reflect.Array.set(array, 0, textComp);

            sendMethod.invoke(spigot, chatType, array);
            return;
        } catch (Throwable ignored) {
            // Переходим к фоллбэку
        }

        // Фоллбэк: простое сообщение в чат (чтоб не было "тишины", как сейчас)
        if (!actionBarErrorLogged && text != null && !text.isEmpty()) {
            actionBarErrorLogged = true;
            plugin.debug("TimberEnergyListener: не удалось отправить ActionBar ни через Adventure, ни через Bungee API. Использую чат как запасной вариант.");
        }
        if (text != null && !text.isEmpty()) {
            player.sendMessage(text);
        }
    }



    private void sendNotEnoughExpMessage(Player player, String message) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = lastNotEnoughXpMessage.get(uuid);
        if (last != null && (now - last) < 2000L) {
            return;
        }
        lastNotEnoughXpMessage.put(uuid, now);
        player.sendMessage(message);
    }

    private boolean isLog(Material type) {
        if (type == null) return false;
        String name = type.name();
        return name.endsWith("_LOG")
                || name.endsWith("_STEM")
                || name.endsWith("_WOOD")
                || name.endsWith("_HYPHAE")
                || (name.startsWith("STRIPPED_") && (
                        name.endsWith("_LOG")
                        || name.endsWith("_STEM")
                        || name.endsWith("_WOOD")
                        || name.endsWith("_HYPHAE")
                ));
    }

    private void hideBar(Player player) {
        BossBar bar = bossBars.remove(player.getUniqueId());
        if (bar != null) {
            bar.removeAll();
        }
    }

    private int getTimberLevel(ItemStack item) {
        if (item == null || item.getType().isAir()) return 0;
        if (enchantManager == null) return 0;

        Map<CustomEnchant, Integer> enchants = enchantManager.getEnchantmentsOnItem(item);
        if (enchants == null || enchants.isEmpty()) return 0;

        for (Map.Entry<CustomEnchant, Integer> entry : enchants.entrySet()) {
            CustomEnchant ench = entry.getKey();
            if (ench == null) continue;
            String id = ench.getId();
            if (id == null) continue;
            if (id.toLowerCase(Locale.ROOT).equals("timber")) {
                return entry.getValue();
            }
        }
        return 0;
    }

    private int getTimberMaxEnergy(ItemStack item, int level) {
        if (item == null || item.getType().isAir()) return 0;
        if (enchantManager == null) return 0;

        Map<CustomEnchant, Integer> enchants = enchantManager.getEnchantmentsOnItem(item);
        if (enchants == null || enchants.isEmpty()) return 0;

        for (Map.Entry<CustomEnchant, Integer> entry : enchants.entrySet()) {
            CustomEnchant ench = entry.getKey();
            if (ench == null) continue;
            String id = ench.getId();
            if (id == null) continue;
            if (id.toLowerCase(Locale.ROOT).equals("timber")) {
                EnchantLevel lvl = ench.getLevel(level);
                if (lvl != null) {
                    return lvl.getProgress();
                }
            }
        }
        return 0;
    }
}
