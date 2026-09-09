package me.aquaenchants.listener;

import me.aquaenchants.AquaEnchatsPlugin;
import me.aquaenchants.enchant.CustomEnchant;
import me.aquaenchants.enchant.EffectConfig;
import me.aquaenchants.enchant.EnchantLevel;
import me.aquaenchants.enchant.EnchantManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * Зачарование "iceshtorm" - Ледяной шторм
 * 
 * Механика:
 * 1. ПКМ + удержание → начало зарядки энергии (снятие опыта)
 * 2. Над кастером растущее кольцо из снежных партиклов
 * 3. Цели в радиусе обездвижены (замедление 255)
 * 4. Через 40 тиков → белые партиклы на целях
 * 5. Каждые 5 тиков → спавн блоков льда над кастером
 * 6. При полной зарядке → слепота на цели
 * 7. Блоки льда летят к целям → урон при касании
 * 8. Через 5 тиков → усиленная молния по целям
 * 9. Если ПКМ удерживается → повтор цикла
 */
public class IceshtormListener implements Listener {

    private static final String ENCHANT_ID = "iceshtorm";

    private final AquaEnchatsPlugin plugin;
    private final EnchantManager enchantManager;

    // Отслеживание удержания ПКМ
    private final Map<UUID, Long> rightClickHeld = new HashMap<>();
    private static final long HOLD_TIMEOUT = 300L; // 300мс = 6 тиков (увеличен для стабильности)
    private static final long CLICK_COOLDOWN = 50L; // Минимум 50мс между обработкой кликов
    private static final long START_GRACE_PERIOD = 200L; // 200мс после старта не проверяем отпускание
    
    private final Map<UUID, ChargingData> activeCharges = new HashMap<>();
    private final Map<UUID, BossBar> bossBars = new HashMap<>();
    private final Map<UUID, Long> lastCastTime = new HashMap<>();
    
    // Отслеживание блоков льда для урона
    private final Map<UUID, IceBlockData> iceBlocks = new HashMap<>();

    // Состояние шторма для отложенного удара молнией после полета льда
    private final Map<UUID, StormLightningState> stormStates = new HashMap<>();

    private boolean actionBarErrorLogged = false;

    public IceshtormListener(AquaEnchatsPlugin plugin, EnchantManager enchantManager) {
        this.plugin = plugin;
        this.enchantManager = enchantManager;
        
        // Запускаем таск для проверки удержания ПКМ
        Bukkit.getScheduler().runTaskTimer(plugin, this::checkHoldingPlayers, 0L, 1L);
        
        // Запускаем таск для анимации ледяных блоков
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickIceBlocks, 0L, 1L);

    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onRightClick(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;

        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack weapon = player.getInventory().getItemInMainHand();
        
        if (weapon == null || weapon.getType() == Material.AIR) {
            return;
        }

        int level = getIceshtormLevel(weapon);
        if (level <= 0) {
            return;
        }

        UUID playerId = player.getUniqueId();
        long now = System.currentTimeMillis();
        
        // Защита от спама: игнорируем клики чаще чем каждые 50мс
        Long lastClickTime = rightClickHeld.get(playerId);
        if (lastClickTime != null && now - lastClickTime < CLICK_COOLDOWN) {
            // Слишком быстрый повторный клик - игнорируем
            return;
        }

        // Отменяем стандартное взаимодействие
        if (action == Action.RIGHT_CLICK_BLOCK) {
            event.setCancelled(true);
        }

        // Обновляем время последнего ПКМ
        rightClickHeld.put(playerId, now);

        // Если уже заряжается - просто обновили время выше, продолжаем
        if (activeCharges.containsKey(playerId)) {
            return;
        }

        // === НОВАЯ ЗАРЯДКА ===
        
        // Проверка кулдауна
        CustomEnchant enchant = enchantManager.getEnchant(ENCHANT_ID);
        EnchantLevel data = enchant != null ? enchant.getLevel(level) : null;
        
        int cooldownSeconds = data != null && data.getCooldown() > 0 ? data.getCooldown() : 0;
        
        if (cooldownSeconds > 0) {
            Long last = lastCastTime.get(playerId);
            if (last != null && now - last < cooldownSeconds * 1000L) {
                long remain = ((cooldownSeconds * 1000L) - (now - last)) / 1000L + 1L;
                player.sendMessage(ChatColor.RED + "Ледяной шторм перезаряжается (" + remain + "с)");
                rightClickHeld.remove(playerId); // Очищаем, чтобы не мешало
                return;
            }
        }

        // Проверка опыта
        if (player.getLevel() < 10) {
            player.sendMessage(ChatColor.RED + "Недостаточно опыта для зарядки ледяного шторма");
            rightClickHeld.remove(playerId);
            return;
        }

        // Начинаем зарядку
        startCharging(player, level, data);
    }
    
    /**
     * Проверяет каждый тик, удерживают ли игроки ПКМ
     */
    private void checkHoldingPlayers() {
        long now = System.currentTimeMillis();
        
        // Проверяем всех игроков с активной зарядкой
        for (UUID playerId : new HashSet<>(activeCharges.keySet())) {
            ChargingData charging = activeCharges.get(playerId);
            if (charging == null) continue;
            
            // Даём "период прощения" после старта зарядки
            if (now - charging.startTime < START_GRACE_PERIOD) {
                continue; // Не проверяем удержание в первые 200мс
            }
            
            Long lastClick = rightClickHeld.get(playerId);
            
            // Если прошло больше HOLD_TIMEOUT с последнего клика - игрок отпустил ПКМ
            if (lastClick == null || now - lastClick > HOLD_TIMEOUT) {
                Player player = Bukkit.getPlayer(playerId);
                if (player != null) {
                    plugin.debug("[Iceshtorm] Player released RMB: " + player.getName() 
                        + ", lastClick=" + (lastClick != null ? (now - lastClick) + "ms ago" : "null"));
                    stopCharging(player, true);
                }
            }
        }
    }

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent event) {
        // Удалено - не используем присед для остановки
    }

    @EventHandler
    public void onItemSwitch(PlayerItemHeldEvent event) {
        // Смена предмета - сброс зарядки
        Player player = event.getPlayer();
        if (activeCharges.containsKey(player.getUniqueId())) {
            stopCharging(player, true);
            player.sendMessage(ChatColor.RED + "Зарядка прервана - вы сменили предмет!");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (activeCharges.containsKey(player.getUniqueId())) {
            stopCharging(player, true);
        }
        rightClickHeld.remove(player.getUniqueId());
    }

    // Тик анимации летящих блоков льда
    private void tickIceBlocks() {
        // Анимация блоков льда
        if (!iceBlocks.isEmpty()) {
            Iterator<Map.Entry<UUID, IceBlockData>> it = iceBlocks.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, IceBlockData> entry = it.next();
                UUID id = entry.getKey();
                IceBlockData data = entry.getValue();

                Entity ent = Bukkit.getEntity(id);
                if (!(ent instanceof FallingBlock)) {
                    it.remove();
                    continue;
                }
                FallingBlock fb = (FallingBlock) ent;

                data.ticksLived++;

                // Поддерживаем полёт по заданному вектору (без гравитации)
                try {
                    fb.setGravity(false);
                    fb.setVelocity(data.velocity);
                } catch (Throwable ignored) {}

                Location loc = fb.getLocation();

                // Небольшие снежные партиклы вокруг блока для видимости траектории
                try {
                    fb.getWorld().spawnParticle(Particle.SNOWFLAKE, loc.clone().add(0, 0.5, 0),
                            3, 0.15, 0.15, 0.15, 0.01);
                } catch (Throwable ignored) {}

                // Проверка попадания по целям
                LivingEntity closest = null;
                double minDist = 1.2;
                for (LivingEntity target : data.targets) {
                    if (!target.isValid() || target.isDead()) continue;
                    double dist = target.getLocation().distance(loc);
                    if (dist < minDist) {
                        minDist = dist;
                        closest = target;
                    }
                }

                if (closest != null) {
                    // Урон как у зомби
                    try {
                        closest.damage(3.0, data.caster);
                    } catch (Throwable ignored) {
                        closest.damage(3.0);
                    }

                    closest.getWorld().spawnParticle(Particle.CLOUD,
                            closest.getLocation().add(0, 1, 0),
                            15, 0.3, 0.3, 0.3, 0.05);

                    plugin.debug("[Iceshtorm] Ice block hit " + closest.getName());

                    fb.remove();
                    it.remove();
                    continue;
                }

                // Ограничение жизни блока, чтобы он не летал бесконечно
                if (data.ticksLived > 40) {
                    fb.remove();
                    it.remove();
                }
            }
        }

        // Логика отложенного удара молнией:
        // ждём, пока для кастера не останется летящих блоков, и только после этого бьём молнией по его целям
        if (!stormStates.isEmpty()) {
            // Собираем список кастеров, у которых ещё есть активные блоки
            Set<UUID> castersWithBlocks = new HashSet<>();
            for (IceBlockData data : iceBlocks.values()) {
                if (data.caster != null) {
                    castersWithBlocks.add(data.caster.getUniqueId());
                }
            }

            Iterator<Map.Entry<UUID, StormLightningState>> it = stormStates.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, StormLightningState> entry = it.next();
                UUID casterId = entry.getKey();
                StormLightningState state = entry.getValue();

                if (castersWithBlocks.contains(casterId)) {
                    // У кастера ещё есть летящие блоки льда → ждём
                    state.idleTicks = 0;
                    continue;
                }

                // Блоков для этого кастера больше нет → считаем "фазу льда" завершённой
                state.idleTicks++;

                // Дадим 2 тика "паузы" после исчезновения последнего блока, затем ударим молнией
                if (state.idleTicks >= 2) {
                    Player caster = Bukkit.getPlayer(casterId);
                    if (caster != null && caster.isOnline()) {
                        strikeTargetsWithLightning(caster, state.targets);
                    }
                    it.remove();
                }
            }
        }
    }


@EventHandler(priority = EventPriority.HIGHEST)
    public void onIceBlockLand(EntityChangeBlockEvent event) {
        if (!(event.getEntity() instanceof FallingBlock)) return;
        
        FallingBlock fb = (FallingBlock) event.getEntity();
        UUID fbId = fb.getUniqueId();
        
        IceBlockData data = iceBlocks.remove(fbId);
        if (data == null) return;
        
        event.setCancelled(true); // Блок не должен упасть
        fb.remove();
        
        // Урон ближайшей цели
        Location loc = fb.getLocation();
        LivingEntity closest = null;
        double minDist = 2.0;
        
        for (LivingEntity target : data.targets) {
            if (!target.isValid() || target.isDead()) continue;
            double dist = target.getLocation().distance(loc);
            if (dist < minDist) {
                minDist = dist;
                closest = target;
            }
        }
        
        if (closest != null) {
            // Урон как у зомби (3.0)
            try {
                closest.damage(3.0, data.caster);
            } catch (Throwable ignored) {
                closest.damage(3.0);
            }
            
            // Эффект удара
            closest.getWorld().spawnParticle(Particle.CLOUD, closest.getLocation().add(0, 1, 0), 15, 0.3, 0.3, 0.3, 0.05);
            
            plugin.debug("[Iceshtorm] Ice block hit " + closest.getName());
        }
    }

    private void startCharging(Player player, int level, EnchantLevel data) {
        UUID pid = player.getUniqueId();
        
        int requiredEnergy = data != null && data.getProgress() > 0 ? data.getProgress() : 5;
        double radius = 10.0;
        
        // Парсинг конфига
        if (data != null && data.getEffects() != null) {
            for (EffectConfig ec : data.getEffects()) {
                String raw = ec.getId();
                if (raw == null) continue;
                String upper = raw.toUpperCase(Locale.ROOT).trim();
                
                if (upper.startsWith("ICE")) {
                    String[] parts = upper.replace(":", " ").replace("@", " ").split("\\s+");
                    for (int i = 0; i < parts.length; i++) {
                        if (parts[i].equals("RADIUS") && i + 1 < parts.length) {
                            try {
                                radius = Double.parseDouble(parts[i + 1]);
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                }
            }
        }
        
        ChargingData charging = new ChargingData(player, level, data, requiredEnergy, radius);
        charging.startTime = System.currentTimeMillis(); // Запоминаем время старта
        activeCharges.put(pid, charging);
        
        // Обновляем время клика при старте
        rightClickHeld.put(pid, System.currentTimeMillis());
        
        player.sendMessage(ChatColor.AQUA + "Началась зарядка " + ChatColor.BOLD + "Ледяного шторма");
        plugin.debug("[Iceshtorm] Started charging for " + player.getName() + ", required=" + requiredEnergy);
        
        // Немедленно применяем замедление к целям
        applySlowdownToTargets(charging);
        
        // Запускаем цикл зарядки
        int taskId = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            tickCharging(player);
        }, 0L, 1L).getTaskId();
        
        charging.taskId = taskId;
    }

    private void tickCharging(Player player) {
        UUID pid = player.getUniqueId();
        ChargingData charging = activeCharges.get(pid);
        
        if (charging == null) return;
        
        // Проверка валидности
        if (!player.isOnline() || player.isDead()) {
            stopCharging(player, true);
            return;
        }
        
        // Проверка оружия в руке
        ItemStack weapon = player.getInventory().getItemInMainHand();
        if (weapon == null || weapon.getType() == Material.AIR || getIceshtormLevel(weapon) <= 0) {
            stopCharging(player, true);
            player.sendMessage(ChatColor.RED + "Зарядка прервана - оружие убрано");
            return;
        }
        
        // Тик времени зарядки
        
        charging.ticks++;

        // 1) На самом первом тике:
        //  - накладываем базовый эффект замерзания на цели
        //  - создаём блоки льда над игроком
        //  - сразу запускаем их полёт к целям (как снаряды)
        if (charging.ticks == 1) {
            applyFreezeEffect(charging);

            List<LivingEntity> targets = charging.getTargets();
            if (!targets.isEmpty()) {
                // Спавним несколько блоков льда над головой
                for (int i = 0; i < 5; i++) {
                    spawnIceBlockAbove(player, charging);
                }

                // Сразу запускаем полёт льда к целям, чтобы он длился всю зарядку
                launchIceBlocks(player, charging, targets);
            }
        }

        // 2) Через 20 тиков усиливаем заморозку: паралич и слабость
        if (charging.ticks == 20) {
            applyParalysisEffect(charging);
        }

// Каждую секунду (20 тиков) снимаем 10 опыта и добавляем 1 энергию
        if (charging.ticks % 20 == 0) {
            if (player.getLevel() < 10) {
                stopCharging(player, true);
                player.sendMessage(ChatColor.RED + "Недостаточно опыта для продолжения зарядки");
                return;
            }
            
            player.giveExp(-10);
            charging.energy++;
            
            plugin.debug("[Iceshtorm] Energy tick: " + charging.energy + "/" + charging.requiredEnergy);
        }
        
        // Визуальные эффекты каждый тик (белые партиклы вокруг игрока)
        spawnChargingParticles(player, charging);
        
        // На 40-м тике - белые партиклы на целях
        if (charging.ticks == 40) {
            spawnTargetIndicators(charging);
        }
        
        // Обновление прогресс-бара
        double progress = Math.min(1.0, (double) charging.energy / (double) charging.requiredEnergy);
        String barText = ChatColor.AQUA + "Ледяной шторм: " + ChatColor.GREEN + charging.energy 
                + ChatColor.GRAY + "/" + ChatColor.GREEN + charging.requiredEnergy;
        updateBossBar(player, barText, progress);
        sendActionBar(player, barText);
        
        // Проверка завершения зарядки: один раз кастуем шторм и останавливаемся
        if (charging.energy >= charging.requiredEnergy) {
            castIceshtorm(player, charging);
            return;
        }

    }

    private void spawnChargingParticles(Player player, ChargingData charging) {
        Location center = player.getLocation().add(0, 2.5, 0);
        World world = player.getWorld();
        
        // Растущее кольцо из снежных партиклов (начинаем с малого количества)
        double radius = 0.3 + (charging.ticks / 120.0) * 1.2; // От 0.3 до 1.5 (медленнее рост)
        int basePoints = 8; // Начальное количество точек
        int maxPoints = 24; // Максимальное количество точек
        int points = basePoints + (int)((charging.ticks / 100.0) * (maxPoints - basePoints));
        
        // Показываем партиклы только каждые 2 тика для уменьшения нагрузки
        if (charging.ticks % 2 == 0) {
            for (int i = 0; i < points; i++) {
                double angle = 2 * Math.PI * i / points;
                double x = center.getX() + Math.cos(angle) * radius;
                double z = center.getZ() + Math.sin(angle) * radius;
                
                try {
                    // Меньше партиклов за раз
                    world.spawnParticle(Particle.SNOWFLAKE, x, center.getY(), z, 1, 0, 0, 0, 0);
                    
                    // Облачко только на поздних стадиях зарядки
                    if (charging.ticks > 40) {
                        world.spawnParticle(Particle.CLOUD, x, center.getY(), z, 1, 0.02, 0.02, 0.02, 0);
                    }
                } catch (Throwable ignored) {}
            }
        }
        
        // Дополнительный снег вокруг игрока - реже и меньше
        if (charging.ticks % 10 == 0) {
            try {
                world.spawnParticle(Particle.SNOWFLAKE, player.getLocation().add(0, 1, 0), 3, 0.3, 0.3, 0.3, 0.01);
            } catch (Throwable ignored) {}
        }
    }

    private void spawnTargetIndicators(ChargingData charging) {
        for (LivingEntity target : charging.getTargets()) {
            if (!target.isValid() || target.isDead()) continue;
            
            Location loc = target.getLocation().add(0, target.getHeight() + 0.5, 0);
            try {
                target.getWorld().spawnParticle(Particle.END_ROD, loc, 20, 0.3, 0.3, 0.3, 0.05);
            } catch (Throwable ignored) {}
        }
    }

    private void spawnIceBlockAbove(Player player, ChargingData charging) {
        // Ограничиваем максимальное количество блоков льда до 4-5
        if (charging.iceBlocksSpawned >= 5) {
            return;
        }
        
        Location spawnLoc = player.getLocation().add(0, 3 + (charging.iceBlocksSpawned * 0.4), 0);
        World world = player.getWorld();
        
        try {
            BlockData blueIce = Material.BLUE_ICE.createBlockData();
            FallingBlock fb = world.spawnFallingBlock(spawnLoc, blueIce);
            fb.setDropItem(false);
            fb.setGravity(false);
            fb.setVelocity(new Vector(0, 0, 0));
            
            charging.iceBlocksSpawned++;
            charging.spawnedIceBlocks.add(fb);
            
            plugin.debug("[Iceshtorm] Spawned ice block #" + charging.iceBlocksSpawned);
        } catch (Throwable ex) {
            plugin.debug("[Iceshtorm] Failed to spawn ice block: " + ex.getMessage());
        }
    }

    private void applySlowdownToTargets(ChargingData charging) {
        for (LivingEntity target : charging.getTargets()) {
            if (!target.isValid() || target.isDead()) continue;
            
            // Максимальное замедление
            try {
                target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 999999, 255, false, false, false));
                target.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, 999999, 250, false, false, false)); // Не дает прыгать
            } catch (Throwable ignored) {}
        }
    }


    // Базовый эффект замерзания: замедление и "холод"
    private void applyFreezeEffect(ChargingData charging) {
        List<LivingEntity> targets = charging.getTargets();
        for (LivingEntity target : targets) {
            if (!target.isValid() || target.isDead()) continue;
            try {
                // Лёгкое замедление на длительное время
                target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 200, 0, false, false, true));
                try {
                    target.setFreezeTicks(Math.max(target.getFreezeTicks(), target.getMaxFreezeTicks() / 2));
                } catch (Throwable ignored) {}
            } catch (Throwable ignored) {}
        }
    }
    
    // Усиленный эффект: паралич (очень сильное замедление, запрет прыжка) и слабость
    private void applyParalysisEffect(ChargingData charging) {
        List<LivingEntity> targets = charging.getTargets();
        for (LivingEntity target : targets) {
            if (!target.isValid() || target.isDead()) continue;
            try {
                // Очень сильное замедление
                target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 160, 4, false, false, true));
                // Запрет прыжка
                target.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, 160, 250, false, false, true));
                // Слабость для уменьшения урона с их стороны
                target.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 160, 1, false, false, true));
            } catch (Throwable ignored) {}
        }
    }

    
    private void castIceshtorm(Player player, ChargingData charging) {
        plugin.debug("[Iceshtorm] CASTING storm for " + player.getName());

        List<LivingEntity> targets = charging.getTargets();

        if (targets.isEmpty()) {
            stopCharging(player, false);
            player.sendMessage(ChatColor.RED + "Нет целей в радиусе действия");
            return;
        }

        // Дополнительно даём краткую слепоту всем целям перед финальным ударом
        for (LivingEntity target : targets) {
            if (!target.isValid() || target.isDead()) continue;

            try {
                target.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 60, 0, false, true, true));
            } catch (Throwable ignored) {}
        }

        // На этом этапе ледяные блоки уже летят к целям.
        // Здесь мы только регистрируем состояние шторма, чтобы после
        // завершения полёта льда по тем же целям ударила молния.
        stormStates.put(player.getUniqueId(), new StormLightningState(player.getUniqueId(), targets));

        // Устанавливаем время последнего каста и останавливаем зарядку
        lastCastTime.put(player.getUniqueId(), System.currentTimeMillis());
        stopCharging(player, false);
    }


    private void launchIceBlocks(Player caster, ChargingData charging, List<LivingEntity> targets) {
        for (FallingBlock fb : charging.spawnedIceBlocks) {
            if (!fb.isValid()) continue;
            
            // Выбираем случайную цель
            LivingEntity target = targets.get(new Random().nextInt(targets.size()));
            
            Vector dir = target.getLocation().toVector()
                    .subtract(fb.getLocation().toVector())
                    .normalize()
                    .multiply(0.35); // чуть медленнее, чтобы было видно полет
            
            try {
                // Ледяные блоки летят по воздуху без падения
                fb.setGravity(false);
                fb.setVelocity(dir);
                
                // Регистрируем блок для анимации и урона
                IceBlockData data = new IceBlockData(caster, targets, dir);
                iceBlocks.put(fb.getUniqueId(), data);
                
                plugin.debug("[Iceshtorm] Launched ice block to " + target.getName());
            } catch (Throwable ignored) {}
        }

    }

    private void strikeTargetsWithLightning(Player caster, List<LivingEntity> targets) {
        for (LivingEntity target : targets) {
            if (!target.isValid() || target.isDead()) continue;
            
            Location loc = target.getLocation();
            
            // Усиленная молния = реальная молния + урон
            try {
                target.getWorld().strikeLightning(loc);
            } catch (Throwable ignored) {
                target.getWorld().strikeLightningEffect(loc);
            }
            
            // Дополнительный урон (25% от макс. хп, минимум 2)
            double maxHp = target.getMaxHealth();
            double damage = Math.max(2.0, maxHp * 0.25);
            
            try {
                target.damage(damage, caster);
            } catch (Throwable ignored) {
                target.damage(damage);
            }
            
            // Эффект заморозки продолжается
            try {
                target.setFreezeTicks(target.getMaxFreezeTicks());
            } catch (Throwable ignored) {}
            
            plugin.debug("[Iceshtorm] Lightning struck " + target.getName() + " for " + damage + " damage");
        }

    }

    
    private void stopCharging(Player player, boolean cancelled) {
        UUID pid = player.getUniqueId();
        ChargingData charging = activeCharges.remove(pid);

        if (charging == null) return;

        // Очищаем отслеживание удержания ПКМ
        rightClickHeld.remove(pid);

        // Останавливаем таск
        if (charging.taskId != -1) {
            try {
                Bukkit.getScheduler().cancelTask(charging.taskId);
            } catch (Throwable ignored) {}
        }

        // Удаляем только те блоки льда, которые ещё не были запущены как снаряды
        for (FallingBlock fb : charging.spawnedIceBlocks) {
            if (fb.isValid() && !fb.isDead()) {
                if (!iceBlocks.containsKey(fb.getUniqueId())) {
                    fb.remove();
                }
            }
        }

        // Снимаем замедление с целей
        for (LivingEntity target : charging.getTargets()) {
            if (target.isValid() && !target.isDead()) {
                target.removePotionEffect(PotionEffectType.SLOWNESS);
                target.removePotionEffect(PotionEffectType.JUMP_BOOST);
            }
        }

        hideBossBar(player);
        sendActionBar(player, "");

        if (cancelled) {
            // Показываем сообщение только если была значительная зарядка
            if (charging.energy > 0) {
                player.sendMessage(ChatColor.YELLOW + "Зарядка прервана! Прогресс: " 
                    + charging.energy + "/" + charging.requiredEnergy);
            }
        }

        plugin.debug("[Iceshtorm] Stopped charging for " + player.getName() 
            + " (cancelled=" + cancelled + ", progress=" + charging.energy + ")");
    }


    private int getIceshtormLevel(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return 0;
        Map<CustomEnchant, Integer> enchants = enchantManager.getEnchantmentsOnItem(item);
        if (enchants == null || enchants.isEmpty()) return 0;

        for (Map.Entry<CustomEnchant, Integer> entry : enchants.entrySet()) {
            CustomEnchant ench = entry.getKey();
            Integer lvl = entry.getValue();
            if (ench == null || lvl == null || lvl <= 0) continue;
            if (ENCHANT_ID.equalsIgnoreCase(ench.getId())) {
                return lvl;
            }
        }
        return 0;
    }

    private void updateBossBar(Player player, String text, double progress) {
        BossBar bar = bossBars.get(player.getUniqueId());
        if (bar == null) {
            try {
                bar = Bukkit.createBossBar(text, BarColor.BLUE, BarStyle.SEGMENTED_10);
                bossBars.put(player.getUniqueId(), bar);
                bar.addPlayer(player);
            } catch (Throwable ignored) {
                return;
            }
        }
        try {
            bar.setTitle(text);
            bar.setProgress(Math.max(0.0, Math.min(1.0, progress)));
        } catch (Throwable ignored) {}
    }

    private void hideBossBar(Player player) {
        BossBar bar = bossBars.remove(player.getUniqueId());
        if (bar != null) {
            try {
                bar.removeAll();
            } catch (Throwable ignored) {}
        }
    }

    private void sendActionBar(Player player, String text) {
        if (player == null) return;
        if (text == null) text = "";

        try {
            Class<?> adventureComponentClass = Class.forName("net.kyori.adventure.text.Component");
            Object component = adventureComponentClass.getMethod("text", String.class).invoke(null, text);
            player.getClass().getMethod("sendActionBar", adventureComponentClass).invoke(player, component);
            return;
        } catch (Throwable ignored) {}

        try {
            Class<?> chatMsgTypeClass = Class.forName("net.md_5.bungee.api.ChatMessageType");
            Class<?> baseComponentClass = Class.forName("net.md_5.bungee.api.chat.BaseComponent");
            Class<?> textComponentClass = Class.forName("net.md_5.bungee.api.chat.TextComponent");

            @SuppressWarnings("unchecked")
            Object chatType = Enum.valueOf((Class<Enum>) chatMsgTypeClass.asSubclass(Enum.class), "ACTION_BAR");

            Object textComp = textComponentClass.getConstructor(String.class).newInstance(text);
            Object array = java.lang.reflect.Array.newInstance(baseComponentClass, 1);
            java.lang.reflect.Array.set(array, 0, textComp);

            Object spigot = player.getClass().getMethod("spigot").invoke(player);
            java.lang.reflect.Method sendMethod = spigot.getClass().getMethod("sendMessage", chatMsgTypeClass, array.getClass());
            sendMethod.invoke(spigot, chatType, array);
            return;
        } catch (Throwable ignored) {}

        if (!actionBarErrorLogged) {
            actionBarErrorLogged = true;
            plugin.getLogger().warning("[Iceshtorm] Не удалось отправлять сообщения в ActionBar");
        }
    }

    // Внутренние классы для хранения данных
    private class ChargingData {
        Player player;
        int level;
        EnchantLevel enchantData;
        int requiredEnergy;
        double radius;
        
        int energy = 0;
        int ticks = 0;
        int taskId = -1;
        int iceBlocksSpawned = 0;
        long startTime = 0; // Время начала зарядки
        
        List<FallingBlock> spawnedIceBlocks = new ArrayList<>();
        List<LivingEntity> cachedTargets = null;
        
        ChargingData(Player player, int level, EnchantLevel enchantData, int requiredEnergy, double radius) {
            this.player = player;
            this.level = level;
            this.enchantData = enchantData;
            this.requiredEnergy = requiredEnergy;
            this.radius = radius;
            this.startTime = System.currentTimeMillis();
        }
        
        List<LivingEntity> getTargets() {
            if (cachedTargets != null) {
                return cachedTargets;
            }
            
            cachedTargets = new ArrayList<>();
            Location center = player.getLocation();
            
            for (Entity e : player.getWorld().getNearbyEntities(center, radius, radius, radius)) {
                if (!(e instanceof LivingEntity)) continue;
                LivingEntity le = (LivingEntity) e;
                if (le.isDead() || le.equals(player)) continue;
                cachedTargets.add(le);
            }
            
            return cachedTargets;
        }
    }
    

    private static class StormLightningState {
        UUID casterId;
        List<LivingEntity> targets;
        int idleTicks;

        StormLightningState(UUID casterId, List<LivingEntity> targets) {
            this.casterId = casterId;
            this.targets = new ArrayList<>(targets);
            this.idleTicks = 0;
        }
    }

    private static class IceBlockData {
        Player caster;
        List<LivingEntity> targets;
        Vector velocity;
        int ticksLived;

        IceBlockData(Player caster, List<LivingEntity> targets, Vector velocity) {
            this.caster = caster;
            this.targets = new ArrayList<>(targets);
            this.velocity = velocity;
            this.ticksLived = 0;
        }
    }

}

