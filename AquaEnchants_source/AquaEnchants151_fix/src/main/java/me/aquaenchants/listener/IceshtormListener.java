package me.aquaenchants.listener;

import me.aquaenchants.AquaEnchatsPlugin;
import me.aquaenchants.enchant.CustomEnchant;
import me.aquaenchants.enchant.EffectConfig;
import me.aquaenchants.enchant.EnchantLevel;
import me.aquaenchants.enchant.EnchantManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
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
 * 2. Вокруг кастера образуется вращающаяся двойная спираль метели и ледяная мандала
 * 3. По мере накопления энергии вокруг кастера материализуются и вращаются ледяные кристаллы
 * 4. Цели в радиусе покрываются льдом и инеем
 * 5. При полной зарядке → ударная волна холода, ледяные кристаллы запускаются в цели как снаряды
 * 6. При попадании снарядов → взрыв ледяной новы (Frost Nova) с осколками синего льда
 * 7. Финал → небесный ледяной луч и громовой морозный шторм
 */
public class IceshtormListener implements Listener {

    private static final String ENCHANT_ID = "iceshtorm";
    private static final Particle.DustOptions DUST_CYAN = new Particle.DustOptions(Color.fromRGB(130, 220, 255), 1.2f);
    private static final Particle.DustOptions DUST_ICE_WHITE = new Particle.DustOptions(Color.fromRGB(220, 245, 255), 1.0f);

    private final AquaEnchatsPlugin plugin;
    private final EnchantManager enchantManager;

    // Отслеживание удержания ПКМ
    private final Map<UUID, Long> rightClickHeld = new HashMap<>();
    private static final long HOLD_TIMEOUT = 750L; // 750мс для надёжного непрерывного удержания ПКМ
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
            if (charging == null || charging.directTest) continue;
            
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
                        + ", lastClick=" + (lastClick != null ? (now - lastClick) + "ms ago" : "null")
                        + ", energy=" + charging.energy);
                    if (charging.energy > 0) {
                        castIceshtorm(player, charging);
                    } else {
                        stopCharging(player, true);
                    }
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

                // Красивый морозный вихревой шлейф за летящим ледяным снарядом
                try {
                    Location trailLoc = loc.clone().add(0, 0.4, 0);
                    fb.getWorld().spawnParticle(Particle.SNOWFLAKE, trailLoc, 4, 0.18, 0.18, 0.18, 0.02);
                    fb.getWorld().spawnParticle(Particle.DUST, trailLoc, 3, 0.12, 0.12, 0.12, 0, DUST_CYAN);
                    fb.getWorld().spawnParticle(Particle.END_ROD, trailLoc, 1, 0.05, 0.05, 0.05, 0.01);
                } catch (Throwable ignored) {}

                // Проверка попадания по целям
                LivingEntity closest = null;
                double minDist = 1.3;
                for (LivingEntity target : data.targets) {
                    if (!target.isValid() || target.isDead()) continue;
                    double dist = target.getLocation().distance(loc);
                    if (dist < minDist) {
                        minDist = dist;
                        closest = target;
                    }
                }

                if (closest != null) {
                    playFrostNovaShatter(loc.clone().add(0, 0.5, 0), closest, data.caster);
                    plugin.debug("[Iceshtorm] Ice block hit " + closest.getName());

                    fb.remove();
                    it.remove();
                    continue;
                }

                // Ограничение жизни блока, чтобы он не летал бесконечно
                if (data.ticksLived > 40) {
                    playFrostNovaShatter(loc.clone().add(0, 0.4, 0), null, data.caster);
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
                        strikeTargetsWithLightning(caster, state.targets, state.fallbackLoc);
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

        event.setCancelled(true); // Блок не должен упасть в мир
        fb.remove();

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

        playFrostNovaShatter(loc.clone().add(0, 0.4, 0), closest, data.caster);
    }

    /**
     * Эффект Frost Nova: мощный взрыв льда с осколками синего и плотного льда,
     * вспышкой и звоном разбивающегося хрусталя.
     */
    private void playFrostNovaShatter(Location loc, LivingEntity target, Player caster) {
        World world = loc.getWorld();
        if (world == null) return;

        try {
            BlockData blueIce = Material.BLUE_ICE.createBlockData();
            BlockData packedIce = Material.PACKED_ICE.createBlockData();
            world.spawnParticle(Particle.BLOCK, loc, 45, 0.35, 0.35, 0.35, 0.15, blueIce);
            world.spawnParticle(Particle.BLOCK, loc, 30, 0.3, 0.3, 0.3, 0.12, packedIce);
            world.spawnParticle(Particle.SNOWFLAKE, loc, 35, 0.5, 0.5, 0.5, 0.08);
            world.spawnParticle(Particle.FLASH, loc, 1, 0, 0, 0, 0);
        } catch (Throwable ignored) {}

        try {
            world.playSound(loc, Sound.BLOCK_GLASS_BREAK, 1.6f, 0.75f);
            world.playSound(loc, Sound.BLOCK_AMETHYST_BLOCK_BREAK, 1.4f, 0.8f);
            world.playSound(loc, Sound.ENTITY_ZOMBIE_VILLAGER_CONVERTED, 0.9f, 1.6f);
        } catch (Throwable ignored) {}

        if (target != null && target.isValid() && !target.isDead()) {
            try {
                if (caster != null) {
                    target.damage(3.5, caster);
                } else {
                    target.damage(3.5);
                }
            } catch (Throwable ignored) {
                target.damage(3.5);
            }
            try {
                target.setFreezeTicks(Math.max(target.getFreezeTicks(), 200));
            } catch (Throwable ignored) {}
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

        charging.ticks++;

        // 1) На первом тике: применяем мороз и начальные звуки
        if (charging.ticks == 1) {
            applyFreezeEffect(charging);
            applySlowdownToTargets(charging);
            try {
                player.getWorld().playSound(player.getLocation(), Sound.BLOCK_POWDER_SNOW_PLACE, 1.2f, 0.8f);
                player.getWorld().playSound(player.getLocation(), Sound.BLOCK_GLASS_STEP, 1.0f, 1.5f);
            } catch (Throwable ignored) {}
        }

        // 2) Через 20 тиков усиливаем заморозку
        if (charging.ticks == 20) {
            applyParalysisEffect(charging);
        }

        // 3) Каждую секунду (20 тиков) снимаем 10 опыта и добавляем 1 энергию
        if (charging.ticks % 20 == 0) {
            if (player.getLevel() < 10) {
                stopCharging(player, true);
                player.sendMessage(ChatColor.RED + "Недостаточно опыта для продолжения зарядки");
                return;
            }

            player.giveExp(-10);
            charging.energy++;

            // Восходящий кристальный звон при наборе энергии
            try {
                float pitch = 0.65f + (charging.energy * 0.16f);
                player.getWorld().playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.2f, pitch);
                player.getWorld().playSound(player.getLocation(), Sound.BLOCK_POWDER_SNOW_STEP, 1.0f, 0.85f);
                player.getWorld().playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_DIAMOND, 0.6f, 1.4f);
            } catch (Throwable ignored) {}

            plugin.debug("[Iceshtorm] Energy tick: " + charging.energy + "/" + charging.requiredEnergy);
        }

        // 4) Восходящая метель (двойная спираль) + морозная мандала на земле
        spawnChargingBlizzard(player, charging);

        // 5) Вращающиеся кристаллы синего льда вокруг кастера
        updateOrbitingIceBlocks(player, charging);

        // 6) Морозные индикаторы над целями
        updateTargetIndicators(charging);

        // Обновление прогресс-бара
        double progress = Math.min(1.0, (double) charging.energy / (double) charging.requiredEnergy);
        String barText = ChatColor.AQUA + "Ледяной шторм: " + ChatColor.GREEN + charging.energy 
                + ChatColor.GRAY + "/" + ChatColor.GREEN + charging.requiredEnergy;
        updateBossBar(player, barText, progress);
        sendActionBar(player, barText);

        // Завершение зарядки: удар ледяного шторма
        if (charging.energy >= charging.requiredEnergy) {
            castIceshtorm(player, charging);
            return;
        }
    }

    /**
     * Восходящая двойная спираль метели и ледяная мандала под ногами кастера.
     */
    private void spawnChargingBlizzard(Player player, ChargingData charging) {
        Location pLoc = player.getLocation();
        World world = player.getWorld();

        double vortexRadius = 1.35;
        double progressRatio = Math.min(1.0, (double) charging.energy / Math.max(1, charging.requiredEnergy));

        // Спираль 1 (снежные хлопья)
        double h1 = ((charging.ticks * 2) % 50) / 50.0 * 2.5;
        double a1 = charging.ticks * 0.22 + h1 * 2.2;
        double x1 = pLoc.getX() + Math.cos(a1) * (vortexRadius * (1.0 - h1 * 0.12));
        double z1 = pLoc.getZ() + Math.sin(a1) * (vortexRadius * (1.0 - h1 * 0.12));
        try {
            world.spawnParticle(Particle.SNOWFLAKE, x1, pLoc.getY() + h1, z1, 1, 0, 0, 0, 0.005);
        } catch (Throwable ignored) {}

        // Спираль 2 (ледяная лазурная пыль)
        double h2 = (((charging.ticks * 2) + 25) % 50) / 50.0 * 2.5;
        double a2 = a1 + Math.PI;
        double x2 = pLoc.getX() + Math.cos(a2) * (vortexRadius * (1.0 - h2 * 0.12));
        double z2 = pLoc.getZ() + Math.sin(a2) * (vortexRadius * (1.0 - h2 * 0.12));
        try {
            world.spawnParticle(Particle.DUST, x2, pLoc.getY() + h2, z2, 1, 0, 0, 0, 0, DUST_CYAN);
        } catch (Throwable ignored) {}

        // Ледяная мандала на земле каждые 3 тика
        if (charging.ticks % 3 == 0) {
            double rRune = 1.8 + progressRatio * 0.4;
            int points = 12;
            double groundY = pLoc.getY() + 0.06;
            for (int p = 0; p < points; p++) {
                double rAngle = (2 * Math.PI * p / points) + (charging.ticks * 0.06);
                double rx = pLoc.getX() + Math.cos(rAngle) * rRune;
                double rz = pLoc.getZ() + Math.sin(rAngle) * rRune;
                try {
                    world.spawnParticle(Particle.SNOWFLAKE, rx, groundY, rz, 1, 0, 0, 0, 0.002);
                } catch (Throwable ignored) {}
            }
        }

        // Окружающий лёгкий морозный туман
        if (charging.ticks % 6 == 0) {
            try {
                world.spawnParticle(Particle.SNOWFLAKE, pLoc.clone().add(0, 1.2, 0), 4, 0.6, 0.6, 0.6, 0.02);
                if (charging.ticks > 30) {
                    world.spawnParticle(Particle.CLOUD, pLoc.clone().add(0, 0.2, 0), 2, 0.4, 0.1, 0.4, 0.01);
                }
            } catch (Throwable ignored) {}
        }
    }

    /**
     * Плавное вращение кристаллов синего льда вокруг кастера.
     * Количество и скорость вращения растут вместе с накоплением энергии.
     */
    private void updateOrbitingIceBlocks(Player player, ChargingData charging) {
        World world = player.getWorld();
        Location center = player.getLocation().add(0, 1.2, 0);

        // Количество орбит масштабируется с энергией (от 1 до 5)
        int desiredOrbs = Math.min(5, Math.max(1, charging.energy + 1));

        while (charging.spawnedIceBlocks.size() < desiredOrbs) {
            try {
                BlockData blueIce = Material.BLUE_ICE.createBlockData();
                FallingBlock fb = world.spawnFallingBlock(center, blueIce);
                fb.setDropItem(false);
                fb.setGravity(false);
                fb.setHurtEntities(false);
                fb.setVelocity(new Vector(0, 0, 0));
                charging.spawnedIceBlocks.add(fb);

                world.playSound(center, Sound.BLOCK_GLASS_PLACE, 0.9f, 1.6f);
                world.spawnParticle(Particle.FLASH, center, 1, 0, 0, 0, 0);
            } catch (Throwable ignored) {
                break;
            }
        }

        int count = charging.spawnedIceBlocks.size();
        if (count == 0) return;

        double speedMultiplier = 1.0 + (charging.energy * 0.35);
        double baseAngle = charging.ticks * 0.12 * speedMultiplier;
        double orbitRadius = 1.55 + 0.12 * Math.sin(charging.ticks * 0.2);

        for (int i = 0; i < count; i++) {
            FallingBlock fb = charging.spawnedIceBlocks.get(i);
            if (!fb.isValid() || fb.isDead()) {
                try {
                    fb = world.spawnFallingBlock(center, Material.BLUE_ICE.createBlockData());
                    fb.setDropItem(false);
                    fb.setGravity(false);
                    fb.setHurtEntities(false);
                    charging.spawnedIceBlocks.set(i, fb);
                } catch (Throwable ignored) {
                    continue;
                }
            }

            double angle = baseAngle + (i * 2 * Math.PI / count);
            double targetX = player.getLocation().getX() + Math.cos(angle) * orbitRadius;
            double targetZ = player.getLocation().getZ() + Math.sin(angle) * orbitRadius;
            double targetY = player.getLocation().getY() + 1.25 + 0.25 * Math.sin(charging.ticks * 0.18 + i);
            Location targetLoc = new Location(world, targetX, targetY, targetZ);

            try {
                fb.teleport(targetLoc);
                fb.setVelocity(new Vector(0, 0, 0));
            } catch (Throwable ignored) {}

            try {
                world.spawnParticle(Particle.DUST, targetLoc.clone().add(0, 0.4, 0), 2, 0.1, 0.1, 0.1, 0, DUST_CYAN);
                world.spawnParticle(Particle.END_ROD, targetLoc.clone().add(0, 0.4, 0), 1, 0.05, 0.05, 0.05, 0.01);
            } catch (Throwable ignored) {}
        }
    }

    /**
     * Морозные индикаторы над целями: кольцо инея под ногами и сияние над головой.
     */
    private void updateTargetIndicators(ChargingData charging) {
        if (charging.ticks % 4 != 0) return;

        for (LivingEntity target : charging.getTargets()) {
            if (!target.isValid() || target.isDead()) continue;
            Location loc = target.getLocation();
            World world = target.getWorld();

            try {
                world.spawnParticle(Particle.SNOWFLAKE, loc.clone().add(0, 0.1, 0), 5, 0.35, 0.05, 0.35, 0.01);
                world.spawnParticle(Particle.END_ROD, loc.clone().add(0, target.getHeight() + 0.5, 0), 2, 0.15, 0.15, 0.15, 0.02);
            } catch (Throwable ignored) {}

            try {
                target.setFreezeTicks(Math.max(target.getFreezeTicks(), 140));
            } catch (Throwable ignored) {}
        }
    }

    private void applySlowdownToTargets(ChargingData charging) {
        for (LivingEntity target : charging.getTargets()) {
            if (!target.isValid() || target.isDead()) continue;

            try {
                target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 999999, 255, false, false, false));
                target.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, 999999, 250, false, false, false));
            } catch (Throwable ignored) {}
        }
    }

    private void applyFreezeEffect(ChargingData charging) {
        List<LivingEntity> targets = charging.getTargets();
        for (LivingEntity target : targets) {
            if (!target.isValid() || target.isDead()) continue;
            try {
                target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 200, 0, false, false, true));
                try {
                    target.setFreezeTicks(Math.max(target.getFreezeTicks(), target.getMaxFreezeTicks() / 2));
                } catch (Throwable ignored) {}
            } catch (Throwable ignored) {}
        }
    }

    private void applyParalysisEffect(ChargingData charging) {
        List<LivingEntity> targets = charging.getTargets();
        for (LivingEntity target : targets) {
            if (!target.isValid() || target.isDead()) continue;
            try {
                target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 160, 4, false, false, true));
                target.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, 160, 250, false, false, true));
                target.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 160, 1, false, false, true));
            } catch (Throwable ignored) {}
        }
    }

    /**
     * Кульминация каста: мощная морозная вспышка и запуск кристаллов в цели.
     */
    private void castIceshtorm(Player player, ChargingData charging) {
        plugin.debug("[Iceshtorm] CASTING storm for " + player.getName());

        List<LivingEntity> targets = charging.getTargets();
        Location fallbackLoc = player.getLocation().add(player.getLocation().getDirection().multiply(8.0));

        Location pLoc = player.getLocation().add(0, 1.2, 0);
        World world = player.getWorld();

        // 1. Мощная ударная волна холода от кастера
        try {
            world.spawnParticle(Particle.FLASH, pLoc, 2, 0, 0, 0, 0);
            world.spawnParticle(Particle.SONIC_BOOM, pLoc, 1, 0, 0, 0, 0);
            for (int i = 0; i < 36; i++) {
                double a = 2 * Math.PI * i / 36;
                double vx = Math.cos(a) * 0.65;
                double vz = Math.sin(a) * 0.65;
                world.spawnParticle(Particle.SNOWFLAKE, pLoc, 0, vx, 0.05, vz, 0.4);
            }
            world.playSound(pLoc, Sound.ENTITY_EVOKER_CAST_SPELL, 1.3f, 0.85f);
            world.playSound(pLoc, Sound.ITEM_TRIDENT_THUNDER, 1.3f, 1.25f);
            world.playSound(pLoc, Sound.ENTITY_PLAYER_HURT_FREEZE, 1.6f, 0.5f);
        } catch (Throwable ignored) {}

        // 2. Краткая слепота на цели (если есть)
        for (LivingEntity target : targets) {
            if (!target.isValid() || target.isDead()) continue;
            try {
                target.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 60, 0, false, true, true));
            } catch (Throwable ignored) {}
        }

        // 3. Запуск ледяных кристаллов в цели или в направлении взгляда
        launchIceBlocks(player, charging, targets);

        // 4. Регистрация состояния шторма для последующего громового финала
        stormStates.put(player.getUniqueId(), new StormLightningState(player.getUniqueId(), targets, fallbackLoc));

        lastCastTime.put(player.getUniqueId(), System.currentTimeMillis());
        stopCharging(player, false);
    }

    public void castDirectly(Player player, int energy) {
        CustomEnchant enchant = enchantManager.getEnchant(ENCHANT_ID);
        int level = 1;
        ItemStack weapon = player.getInventory().getItemInMainHand();
        int itemLvl = getIceshtormLevel(weapon);
        if (itemLvl > 0) level = itemLvl;
        EnchantLevel data = enchant != null ? enchant.getLevel(level) : null;

        ChargingData charging = new ChargingData(player, level, data, 5, 12.0);
        charging.energy = Math.max(1, Math.min(5, energy));
        castIceshtorm(player, charging);
    }

    public void startChargingDirectly(Player player, int seconds) {
        CustomEnchant enchant = enchantManager.getEnchant(ENCHANT_ID);
        int level = 1;
        ItemStack weapon = player.getInventory().getItemInMainHand();
        int itemLvl = getIceshtormLevel(weapon);
        if (itemLvl > 0) level = itemLvl;
        EnchantLevel data = enchant != null ? enchant.getLevel(level) : null;

        int req = Math.max(1, Math.min(5, seconds));
        ChargingData charging = new ChargingData(player, level, data, req, 12.0);
        charging.directTest = true;
        activeCharges.put(player.getUniqueId(), charging);
        applySlowdownToTargets(charging);

        int taskId = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            tickCharging(player);
        }, 0L, 1L).getTaskId();
        charging.taskId = taskId;
    }

    /**
     * Запуск летящих ледяных кристаллов в цели с высокой скоростью.
     */
    private void launchIceBlocks(Player caster, ChargingData charging, List<LivingEntity> targets) {
        List<FallingBlock> blocks = new ArrayList<>(charging.spawnedIceBlocks);
        charging.spawnedIceBlocks.clear(); // Очищаем список орбит, чтобы stopCharging их не удалил

        if (blocks.isEmpty()) {
            int toSpawn = Math.max(1, Math.min(5, charging.energy > 0 ? charging.energy : 3));
            for (int i = 0; i < toSpawn; i++) {
                double a = 2 * Math.PI * i / toSpawn;
                Location spawnLoc = caster.getLocation().add(Math.cos(a) * 1.5, 1.5, Math.sin(a) * 1.5);
                try {
                    FallingBlock fb = caster.getWorld().spawnFallingBlock(spawnLoc, Material.BLUE_ICE.createBlockData());
                    fb.setDropItem(false);
                    fb.setHurtEntities(false);
                    fb.setGravity(false);
                    blocks.add(fb);
                } catch (Throwable ignored) {}
            }
        }

        for (FallingBlock fb : blocks) {
            if (!fb.isValid() || fb.isDead()) continue;

            Vector dir;
            if (targets != null && !targets.isEmpty()) {
                LivingEntity target = targets.get(new Random().nextInt(targets.size()));
                Vector targetVec = target.getLocation().add(0, target.getHeight() * 0.5, 0).toVector();
                Vector fbVec = fb.getLocation().toVector();
                dir = targetVec.subtract(fbVec);
            } else {
                Vector look = caster.getLocation().getDirection();
                double spreadX = (Math.random() - 0.5) * 0.25;
                double spreadY = (Math.random() - 0.5) * 0.15;
                double spreadZ = (Math.random() - 0.5) * 0.25;
                dir = look.clone().add(new Vector(spreadX, spreadY, spreadZ));
            }

            if (dir.lengthSquared() > 0.001) {
                dir.normalize().multiply(0.85); // Быстрый и ощутимый полёт
            } else {
                dir = new Vector(0, 0.5, 0);
            }

            try {
                fb.setGravity(false);
                fb.setVelocity(dir);

                IceBlockData data = new IceBlockData(caster, targets, dir);
                iceBlocks.put(fb.getUniqueId(), data);

                fb.getWorld().playSound(fb.getLocation(), Sound.ENTITY_WIND_CHARGE_WIND_BURST, 1.0f, 1.3f);
                fb.getWorld().playSound(fb.getLocation(), Sound.ENTITY_SNOWBALL_THROW, 1.0f, 0.7f);

                plugin.debug("[Iceshtorm] Launched ice block from " + caster.getName());
            } catch (Throwable ignored) {}
        }
    }

    /**
     * Финальный ледяной громовой удар: небесный луч и морозный взрыв.
     */
    private void strikeTargetsWithLightning(Player caster, List<LivingEntity> targets, Location fallbackLoc) {
        if (targets == null || targets.isEmpty()) {
            if (fallbackLoc != null && fallbackLoc.getWorld() != null) {
                playCelestialLightningAt(caster, fallbackLoc, null);
            }
            return;
        }
        for (LivingEntity target : targets) {
            if (!target.isValid() || target.isDead()) continue;
            playCelestialLightningAt(caster, target.getLocation(), target);
        }
    }

    private void playCelestialLightningAt(Player caster, Location loc, LivingEntity target) {
        World w = loc.getWorld();
        if (w == null) return;

        // 1. Реальная молния
        try {
            w.strikeLightning(loc);
        } catch (Throwable ignored) {
            w.strikeLightningEffect(loc);
        }

        // 2. Небесный ледяной луч, нисходящий из облаков
        try {
            double baseY = loc.getY();
            for (double y = baseY; y <= baseY + 20; y += 1.2) {
                Location beamLoc = new Location(w, loc.getX(), y, loc.getZ());
                w.spawnParticle(Particle.END_ROD, beamLoc, 2, 0.2, 0.1, 0.2, 0.02);
                w.spawnParticle(Particle.DUST, beamLoc, 3, 0.25, 0.1, 0.25, 0, DUST_CYAN);
            }
        } catch (Throwable ignored) {}

        // 3. Ударная волна по земле
        try {
            w.spawnParticle(Particle.SONIC_BOOM, loc.clone().add(0, 0.2, 0), 1, 0, 0, 0, 0);
            for (int i = 0; i < 28; i++) {
                double a = 2 * Math.PI * i / 28;
                double vx = Math.cos(a) * 0.55;
                double vz = Math.sin(a) * 0.55;
                w.spawnParticle(Particle.SNOWFLAKE, loc.clone().add(0, 0.2, 0), 0, vx, 0.1, vz, 0.35);
            }
            BlockData blueIce = Material.BLUE_ICE.createBlockData();
            w.spawnParticle(Particle.BLOCK, loc.clone().add(0, 0.5, 0), 45, 0.5, 0.5, 0.5, 0.12, blueIce);
        } catch (Throwable ignored) {}

        // 4. Многослойный звуковой дизайн (гром + ледяной хруст + резонанс)
        try {
            w.playSound(loc, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 2.0f, 0.85f);
            w.playSound(loc, Sound.BLOCK_GLASS_BREAK, 1.8f, 0.6f);
            w.playSound(loc, Sound.BLOCK_CONDUIT_DEACTIVATE, 1.5f, 0.75f);
        } catch (Throwable ignored) {}

        // 5. Урон и глубокая заморозка
        if (target != null && target.isValid() && !target.isDead()) {
            double maxHp = target.getMaxHealth();
            double damage = Math.max(2.0, maxHp * 0.25);

            try {
                if (caster != null) target.damage(damage, caster);
                else target.damage(damage);
            } catch (Throwable ignored) {
                target.damage(damage);
            }

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
        boolean directTest = false;
        
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
        Location fallbackLoc;
        int idleTicks;

        StormLightningState(UUID casterId, List<LivingEntity> targets, Location fallbackLoc) {
            this.casterId = casterId;
            this.targets = targets != null ? new ArrayList<>(targets) : new ArrayList<>();
            this.fallbackLoc = fallbackLoc;
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

