package me.aquaenchants.excavator;

import me.aquaenchants.util.ProtectedBlockUtil;
import me.aquaenchants.AquaEnchatsPlugin;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.block.data.Lightable;
import org.bukkit.block.BlockState;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Piglin;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import java.io.File;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.*;
import java.lang.reflect.Method;

/**
 * Обработчик процесса раскопки чанка по полосам
 */
public class ExcavatorProcessor {
    
    private final AquaEnchatsPlugin plugin;
    private final ExcavatorManager manager;
    private final Set<Material> whitelist;
    private final Set<Material> blacklist;
    
    // Активные процессы раскопки
    private final Map<Location, BukkitRunnable> activeProcesses = new HashMap<>();
    
    // Ключи PDC для кристалла и голограммы
    private final NamespacedKey excavatorCrystalKey;
    private final NamespacedKey excavatorHologramKey;

    public ExcavatorProcessor(AquaEnchatsPlugin plugin, ExcavatorManager manager) {
        this.plugin = plugin;
        this.manager = manager;
        this.whitelist = new HashSet<>();
        this.blacklist = new HashSet<>();
        this.excavatorCrystalKey = new NamespacedKey(plugin, "excavator_crystal");
        this.excavatorHologramKey = new NamespacedKey(plugin, "excavator_hologram");
        initWhitelist();
        initBlacklist();
        Bukkit.getLogger().info("[ExcavatorDBG] ExcavatorProcessor initialized. Whitelist size=" + whitelist.size() + " materials=" + whitelist);

    }
    
    /**
     * Инициализация белого списка блоков (руды и ценные ресурсы)
     */
    private void initWhitelist() {
        // Руды
        whitelist.add(Material.COAL_ORE);
        whitelist.add(Material.DEEPSLATE_COAL_ORE);
        whitelist.add(Material.IRON_ORE);
        whitelist.add(Material.DEEPSLATE_IRON_ORE);
        whitelist.add(Material.COPPER_ORE);
        whitelist.add(Material.DEEPSLATE_COPPER_ORE);
        whitelist.add(Material.GOLD_ORE);
        whitelist.add(Material.DEEPSLATE_GOLD_ORE);
        whitelist.add(Material.REDSTONE_ORE);
        whitelist.add(Material.DEEPSLATE_REDSTONE_ORE);
        whitelist.add(Material.EMERALD_ORE);
        whitelist.add(Material.DEEPSLATE_EMERALD_ORE);
        whitelist.add(Material.LAPIS_ORE);
        whitelist.add(Material.DEEPSLATE_LAPIS_ORE);
        whitelist.add(Material.DIAMOND_ORE);
        whitelist.add(Material.DEEPSLATE_DIAMOND_ORE);
        whitelist.add(Material.NETHER_GOLD_ORE);
        whitelist.add(Material.NETHER_QUARTZ_ORE);
        whitelist.add(Material.ANCIENT_DEBRIS);
        
        loadWhitelistFromConfig();
        Bukkit.getLogger().info("[ExcavatorDBG] initWhitelist completed. Materials=" + whitelist);
    }
    
    


/**
 * Инициализация дарк-листа блоков (которые никогда не ломаются)
 */
private void initBlacklist() {
    blacklist.clear();
    // Значения по умолчанию, если в конфиге нет секции darklist
    blacklist.add(Material.BEDROCK);
    blacklist.add(Material.SPAWNER);
    blacklist.add(Material.END_PORTAL_FRAME);
    blacklist.add(Material.END_PORTAL);
    blacklist.add(Material.END_GATEWAY);
    blacklist.add(Material.NETHER_PORTAL);
    blacklist.add(Material.COMMAND_BLOCK);
    blacklist.add(Material.CHAIN_COMMAND_BLOCK);
    blacklist.add(Material.REPEATING_COMMAND_BLOCK);
    blacklist.add(Material.BARRIER);
    blacklist.add(Material.STRUCTURE_BLOCK);
    blacklist.add(Material.STRUCTURE_VOID);
    blacklist.add(Material.JIGSAW);
    blacklist.add(Material.LIGHT);
    blacklist.add(Material.TRIAL_SPAWNER);
    blacklist.add(Material.VAULT);
    blacklist.add(Material.REINFORCED_DEEPSLATE);
    blacklist.add(Material.COPPER_BULB); // материал самого экскаватора по умолчанию

    loadBlacklistFromConfig();
    Bukkit.getLogger().info("[ExcavatorDBG] initBlacklist completed. Materials=" + blacklist);
}

private void loadBlacklistFromConfig() {
    try {
        File configFile = new File(plugin.getDataFolder(), "items/excavator.yml");
        if (!configFile.exists()) {
            plugin.saveResource("items/excavator.yml", false);
        }
        if (!configFile.exists()) {
            Bukkit.getLogger().warning("[ExcavatorDBG] items/excavator.yml not found, using hardcoded blacklist.");
            return;
        }

        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(configFile);

        java.util.List<String> names = cfg.getStringList("excavator.darklist");
        if (names == null || names.isEmpty()) {
            names = cfg.getStringList("excavator.blacklist");
        }
        if (names == null || names.isEmpty()) {
            names = cfg.getStringList("darklist");
        }
        if (names == null || names.isEmpty()) {
            names = cfg.getStringList("blacklist");
        }
        if (names == null || names.isEmpty()) {
            Bukkit.getLogger().info("[ExcavatorDBG] No darklist/blacklist section in items/excavator.yml, using hardcoded list.");
            return;
        }

        blacklist.clear();
        for (String name : names) {
            if (name == null || name.trim().isEmpty()) continue;
            String upper = name.trim().toUpperCase(java.util.Locale.ROOT);
            try {
                Material mat = Material.valueOf(upper);
                blacklist.add(mat);
            } catch (IllegalArgumentException ex) {
                Bukkit.getLogger().warning("[ExcavatorDBG] Unknown material in darklist: " + name);
            }
        }
    } catch (Throwable t) {
        Bukkit.getLogger().warning("[ExcavatorDBG] Failed to load darklist from items/excavator.yml: " + t.getMessage());
    }
}
private void loadWhitelistFromConfig() {
    try {
        File configFile = new File(plugin.getDataFolder(), "items/excavator.yml");
        if (!configFile.exists()) {
            plugin.saveResource("items/excavator.yml", false);
        }
        if (!configFile.exists()) {
            Bukkit.getLogger().warning("[ExcavatorDBG] items/excavator.yml not found, using hardcoded whitelist.");
            return;
        }

        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(configFile);

        java.util.List<String> names = cfg.getStringList("excavator.whitelist");
        if (names == null || names.isEmpty()) {
            names = cfg.getStringList("whitelist");
        }
        if (names == null || names.isEmpty()) {
            Bukkit.getLogger().info("[ExcavatorDBG] No whitelist section in items/excavator.yml, using hardcoded list.");
            return;
        }

        whitelist.clear();
        for (String name : names) {
            if (name == null || name.trim().isEmpty()) continue;
            String upper = name.trim().toUpperCase(java.util.Locale.ROOT);
            try {
                Material mat = Material.valueOf(upper);
                whitelist.add(mat);
            } catch (IllegalArgumentException ex) {
                Bukkit.getLogger().warning("[ExcavatorDBG] Unknown material in whitelist: " + name);
            }
        }
    } catch (Throwable t) {
        Bukkit.getLogger().warning("[ExcavatorDBG] Failed to load whitelist from items/excavator.yml: " + t.getMessage());
    }
}

    
    /**
     * Начать процесс раскопки
     */
    
public void startExcavation(Player player, ExcavatorData data, ExcavatorData.ExcavatorMode mode) {
    Bukkit.getLogger().info("[ExcavatorDBG] startExcavation called by " + player.getName() + " mode=" + mode + " fuel=" + data.getFuel() + " loc=" + data.getLocation());

    Location loc = data.getLocation();

    // Проверка топлива
    if (data.getFuel() <= 0) {
        player.sendMessage(ChatColor.RED + "Разчистить не может - отсутствует топливо!");
        return;
    }

    // Проверка, не работает ли уже
    if (data.isWorking()) {
        player.sendMessage(ChatColor.RED + "Экскаватор уже работает!");
        return;
    }

    // Устанавливаем режим работы
    data.setMode(mode);
    data.setWorking(true);

    // Если выбран режим CHUNK — поднимаем экскаватор над верхним блоком в колонке
    if (mode == ExcavatorData.ExcavatorMode.CHUNK && loc != null && loc.getWorld() != null) {
        World world = loc.getWorld();
        Location oldLoc = loc.clone();
        int x = oldLoc.getBlockX();
        int z = oldLoc.getBlockZ();
        int maxY = world.getMaxHeight() - 1;
        int minY = world.getMinHeight();
        int topSolidY = minY - 1;

        for (int y = maxY; y >= minY; y--) {
            Material mat = world.getBlockAt(x, y, z).getType();
            if (mat != Material.AIR && mat != Material.CAVE_AIR && mat != Material.VOID_AIR) {
                topSolidY = y;
                break;
            }
        }

        int targetY = topSolidY + 1;
        if (targetY < minY) {
            targetY = minY;
        }
        if (targetY > maxY) {
            targetY = maxY;
        }

        if (targetY != oldLoc.getBlockY()) {
            Location newLoc = new Location(world, x, targetY, z);

            Block oldBlock = oldLoc.getBlock();
            Material excavatorType = oldBlock.getType();
            if (excavatorType == Material.AIR) {
                excavatorType = Material.COPPER_BULB;
            }

            Block newBlock = newLoc.getBlock();
            if (!ProtectedBlockUtil.canModifyBlock(newBlock)) {
                return;
            }

            oldBlock.setType(Material.AIR);
            newBlock.setType(excavatorType);
            // Если это "фонарь" (например, COPPER_BULB) — держим его включённым
            setLitIfPossible(newBlock, true);

            // Обновляем положение экскаватора и визуальные эффекты
            data.setLocation(newLoc);
            moveVisuals(oldLoc, newLoc);
            loc = newLoc;
        }
    }

    // Вычисляем начальный слой: на 1 блок ниже экскаватора
    int startY = loc.getBlockY() - 1;
    int minWorldY = loc.getWorld().getMinHeight();
    if (startY < minWorldY) {
        startY = minWorldY;
    }

    // Инициализация координат копания
    data.setCurrentY(startY);
    data.setCurrentZ(0);
    data.clearCollectedItems();

    manager.updateExcavator(data);
    // В режиме CHUNK заранее очищаем воду/водологги в чанке, чтобы его не заливало во время раскопки
    if (data.getMode() == ExcavatorData.ExcavatorMode.CHUNK) {
        try {
            clearWaterInChunk(data.getLocation().getChunk());
        } catch (Throwable ignored) {
        }
    }



    // Обратный отсчёт
    startCountdown(player, data);
}

private void startCountdown(Player player, ExcavatorData data) {
        new BukkitRunnable() {
            int countdown = 5;
            
            @Override
            public void run() {
                if (countdown > 0) {
                    player.sendMessage(ChatColor.YELLOW + "Чанк начнёт удаляться через: " + ChatColor.RED + countdown);
                    countdown--;
                } else {
                    player.sendMessage(ChatColor.GREEN + "Раскопка началась!");

                    spawnPiglin(data.getLocation());
                    startExcavationProcess(data);
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }
    
    /**
     * Создать танцующего пиглина на блоке
     */
    private void spawnPiglin(Location loc) {
        World world = loc.getWorld();
        Location piglinLoc = loc.clone().add(0.5, 1.0, 0.5);
        
        Piglin piglin = world.spawn(piglinLoc, Piglin.class, p -> {
            p.setImmuneToZombification(true);
            p.setAI(false);
            p.setInvulnerable(true);
            p.setGravity(false);
            p.setCustomName(ChatColor.GOLD + "Рабочий");
            p.setCustomNameVisible(true);
            
            // Заставляем танцевать
            startPiglinDance(p);
        });
    }
    
    /**
     * Анимация танца пиглина
     */
    private void startPiglinDance(Piglin piglin) {
        new BukkitRunnable() {
            double angle = 0;
            
            @Override
            public void run() {
                if (!piglin.isValid()) {
                    cancel();
                    return;
                }
                
                // Вращение
                Location loc = piglin.getLocation();
                loc.setYaw((float) angle);
                piglin.teleport(loc);
                
                angle += 45;
                if (angle >= 360) angle = 0;
            }
        }.runTaskTimer(plugin, 0L, 5L);
    }
    
    /**
     * Запустить основной процесс раскопки
     */
    private void startExcavationProcess(ExcavatorData data) {
        Location excavatorLoc = data.getLocation();
        
        BukkitRunnable process = new BukkitRunnable() {
            @Override
            public void run() {
                if (!data.isWorking()) {
                    stopExcavation(data);
                    cancel();
                    return;
                }

                // Проверка топлива
                if (data.getFuel() <= 0) {
                    // Топливо закончилось: НЕ удаляем экскаватор и НЕ выдаём шалкер.
                    // Просто ставим паузу и ждём, пока игрок снова загрузит топливо через GUI.
                    pauseDueToNoFuel(data);
                    cancel();
                    return;
                }

                // Обработка одной полосы
                boolean stripeComplete = processStripe(data);

                if (stripeComplete) {
                    // Полоса завершена, переходим к следующей
                    int nextZ = data.getCurrentZ() + 1;
                    
                    if (nextZ >= 16) {
                        // Слой полностью обработан
                        layerComplete(data);
                    } else {
                        // Следующая полоса в текущем слое
                        data.setCurrentZ(nextZ);
                        manager.updateExcavator(data);
                    }
                }
            }
        };
        
        activeProcesses.put(excavatorLoc, process);
        process.runTaskTimer(plugin, 0L, 2L);
    }

    /**
     * Пауза работы из-за отсутствия топлива.
     * Экскаватор остаётся на месте, визуальные эффекты остаются, можно пополнить топливо и запустить снова.
     */
    private void pauseDueToNoFuel(ExcavatorData data) {
        if (data == null) return;
        data.setWorking(false);
        manager.updateExcavator(data);

        // Убираем рабочего (если был), но сам экскаватор не трогаем
        if (data.getLocation() != null) {
            removePiglin(data.getLocation());
        }

        // Останавливаем задачу в реестре активных процессов
        if (data.getLocation() != null) {
            activeProcesses.remove(data.getLocation());
        }

        // Обновляем голограмму (покажет 0 топлива)
        updateHologram(data);
    }
    
    /**
     * Обработать одну полосу (16 блоков по X на текущей высоте Y и координате Z)
     */
    private boolean processStripe(ExcavatorData data) {
        Location excavatorLoc = data.getLocation();
        World world = excavatorLoc.getWorld();
        if (world == null) return false;

        Chunk chunk = excavatorLoc.getChunk();
        int chunkX = chunk.getX() * 16;
        int chunkZ = chunk.getZ() * 16;
        int currentY = data.getCurrentY();
        int currentZ = data.getCurrentZ();
        int worldZ = chunkZ + currentZ;
        Bukkit.getLogger().info("[ExcavatorDBG] processStripe: chunk=" + chunk.getX() + "," + chunk.getZ() +
                " Y=" + currentY + " Z=" + currentZ + " (worldZ=" + worldZ + ") mode=" + data.getMode());

        // Спавним частицы наутилуса (луч и кольцо по краю чанка)
        spawnNautilusParticles(data);

        // Проход по всей полосе X (0-15) блок за блоком
        for (int x = 0; x < 16; x++) {
            int worldX = chunkX + x;
            Block block = world.getBlockAt(worldX, currentY, worldZ);

            // Пропуск блоков
            if (shouldSkipBlock(block, excavatorLoc, data.getMode())) {
                continue;
            }

            Material blockType = block.getType();

            // Если блок является РУДОЙ из вайтлиста — учитываем как добычу
            if (whitelist.contains(blockType)) {
                Bukkit.getLogger().info("[ExcavatorDBG] Ore found at " + worldX + "," + currentY + "," + worldZ +
                        " type=" + blockType.name());
                data.addCollectedItem(blockType.name());
            }

            // Любые не пропущенные блоки в полосе удаляются (имитация прокопки)
            block.setType(Material.AIR);

            // Партиклы лавы для каждого удалённого блока
            // Убраны лавовые партиклы по просьбе: оставляем только NAUTILUS.
        }

        
        // Удаляем воду, которая могла успеть затечь в только что выкопанные уровни
        if (data != null && data.getMode() == ExcavatorData.ExcavatorMode.CHUNK) {
            try {
                Chunk ch = excavatorLoc.getChunk();
                clearWaterSlice(ch, currentY);
                clearWaterSlice(ch, currentY + 1);
            } catch (Throwable ignored) {
            }
        }

// Звук завершения полосы
        world.playSound(excavatorLoc, Sound.BLOCK_FIRE_AMBIENT, 0.5f, 1.0f);

        Bukkit.getLogger().info("[ExcavatorDBG] processStripe finished for Y=" + currentY + " Z=" + currentZ);
        return true; // Полоса завершена
    }


    
    /**
     * Проверка, нужно ли пропустить блок
     */
    
private boolean shouldSkipBlock(Block block, Location excavatorLoc, ExcavatorData.ExcavatorMode mode) {
    Material type = block.getType();

    // Воздух
    if (type == Material.AIR || type == Material.CAVE_AIR || type == Material.VOID_AIR) {
        return true;
    }

    // Абсолютная защита: бедрок не ломаем даже если его случайно убрали из darklist.
    if (ProtectedBlockUtil.isProtected(type)) {
        return true;
    }

    // Дарклист блоков, которые никогда не ломаем
    if (blacklist != null && blacklist.contains(type)) {
        return true;
    }

    // Сам блок экскаватора
    if (block.getLocation().equals(excavatorLoc)) {
        return true;
    }

    // Режим BELOW - пропускаем блоки на уровне и выше экскаватора
    if (mode == ExcavatorData.ExcavatorMode.BELOW) {
        if (block.getY() >= excavatorLoc.getBlockY()) {
            return true;
        }
    }

    return false;
}

    
    // spawnLavaParticle удалён: лавовые партиклы больше не используются.
    
    /**
     * Завершение слоя - сжечь топливо и опуститься ниже
     */
    private void layerComplete(ExcavatorData data) {
        // Сжигаем 1 топливо
        data.consumeFuel(1);
        updateHologram(data);
        
        // Опускаемся на слой ниже
        World world = data.getLocation().getWorld();
        int minY = world.getMinHeight();
        int nextY = data.getCurrentY() - 1;
        
        if (nextY < minY) {
            // Дошли до низа мира — работа реально завершена
            finishExcavation(data);
            return;
        }

        // Топливо закончилось — ставим паузу (экскаватор НЕ пропадает)
        if (data.getFuel() <= 0) {
            pauseDueToNoFuel(data);
            return;
        }
        
        // Переходим к следующему слою
        data.setCurrentY(nextY);
        data.setCurrentZ(0); // Сброс полосы на начало
        moveExcavatorDown(data);
        manager.updateExcavator(data);
    }
    
    /**
     * Спавн частиц наутилуса
     */
    private void spawnNautilusParticles(ExcavatorData data) {
        Location excavatorLoc = data.getLocation();
        World world = excavatorLoc.getWorld();
        if (world == null) return;

        Chunk chunk = excavatorLoc.getChunk();
        int y = data.getCurrentY();
        int currentZ = data.getCurrentZ();

        // Луч от экскаватора к текущему слою
        Location from = excavatorLoc.clone().add(0.5, 0.5, 0.5);
        Location to = new Location(world, excavatorLoc.getBlockX() + 0.5, y + 0.5, excavatorLoc.getBlockZ() + 0.5);

        double distance = from.distance(to);
        int steps = Math.max(1, (int) (distance * 2));
        double dx = (to.getX() - from.getX()) / steps;
        double dy = (to.getY() - from.getY()) / steps;
        double dz = (to.getZ() - from.getZ()) / steps;

        Location current = from.clone();
        for (int i = 0; i <= steps; i++) {
            world.spawnParticle(Particle.NAUTILUS, current, 1, 0, 0, 0, 0);
            current.add(dx, dy, dz);
        }

        // Полоса частиц вдоль текущей Z-полосы слоя
        int minX = chunk.getX() * 16;
        int minZ = chunk.getZ() * 16;
        int maxX = minX + 15;
        double py = y + 0.5;
        int z = minZ + currentZ;

        for (int x = minX; x <= maxX; x += 2) {
            world.spawnParticle(
                    Particle.NAUTILUS,
                    new Location(world, x + 0.5, py, z + 0.5),
                    1,
                    0, 0, 0, 0
            );
        }
    }
    
    /**
     * Опустить экскаватор на один блок вниз
     */
    private void moveExcavatorDown(ExcavatorData data) {
        Location oldLoc = data.getLocation();
        Location newLoc = oldLoc.clone().subtract(0, 1, 0);
        World world = oldLoc.getWorld();

        // Не опускаем экскаватор в запретные блоки.
        if (!ProtectedBlockUtil.canModifyBlock(newLoc.getBlock())) {
            return;
        }

        // Удаляем старый блок экскаватора
        oldLoc.getBlock().setType(Material.AIR);

        // Ставим новый блок экскаватора на уровень ниже (и включаем "свет")
        Block newBlock = newLoc.getBlock();
        newBlock.setType(Material.COPPER_BULB);
        setLitIfPossible(newBlock, true);

        // Обновляем данные о положении
        data.setLocation(newLoc);

        // Перемещаем рабочего пиглина
        world.getNearbyEntities(oldLoc, 2, 4, 2).forEach(entity -> {
            if (entity instanceof Piglin piglin) {
                String name = piglin.getCustomName();
                if (name != null && (ChatColor.GOLD + "Рабочий").equals(name)) {
                    Location pigLoc = piglin.getLocation().clone().subtract(0, 1, 0);
                    piglin.teleport(pigLoc);
                }
            }
        });

        // Обновляем визуальные эффекты (кристалл и голограмма)
        moveVisuals(oldLoc, newLoc);
    }

    /**
     * Включить/выключить "свет" у блока, если BlockData поддерживает lit.
     * Нужен, чтобы COPPER_BULB выглядел как "запитанный".
     */
    private void setLitIfPossible(Block block, boolean lit) {
        if (block == null || ProtectedBlockUtil.isProtected(block)) return;
        try {
            BlockData bd = block.getBlockData();
            if (bd instanceof Lightable lightable) {
                lightable.setLit(lit);
                block.setBlockData(lightable, false);
                return;
            }
            Method m = bd.getClass().getMethod("setLit", boolean.class);
            m.invoke(bd, lit);
            block.setBlockData(bd, false);
        } catch (Throwable ignored) {
        }
    }
    
    /**
     * Переместить кристалл и голограмму вместе с экскаватором
     */
    private void moveVisuals(Location oldLoc, Location newLoc) {
        World world = oldLoc.getWorld();
        if (world == null) return;

        Location base = newLoc.clone().add(0.5, 0.0, 0.5);
        Location crystalTarget = base.clone().add(0, -0.2, 0);
        Location holoTarget = base.clone().add(0, 1.2, 0);

        double radius = 3.0;
        for (Entity entity : world.getNearbyEntities(oldLoc, radius, radius, radius)) {
            PersistentDataContainer pdc = entity.getPersistentDataContainer();
            if (pdc.has(excavatorCrystalKey, PersistentDataType.STRING)) {
                entity.teleport(crystalTarget);
            } else if (pdc.has(excavatorHologramKey, PersistentDataType.STRING)) {
                entity.teleport(holoTarget);
            }
        }
    }

    /**
     * Обновить текст голограммы с топливом над экскаватором
     */
    public void updateHologram(ExcavatorData data) {
        if (data == null || data.getLocation() == null) return;
        Location loc = data.getLocation();
        World world = loc.getWorld();
        if (world == null) return;

        String text = ChatColor.AQUA + "Топливо: " + ChatColor.WHITE + data.getFuel() + " аметистов";

        Location center = loc.clone().add(0.5, 0.5, 0.5);
        double radius = 3.0;

        for (Entity entity : world.getNearbyEntities(center, radius, radius, radius)) {
            if (entity instanceof ArmorStand) {
                PersistentDataContainer pdc = entity.getPersistentDataContainer();
                if (pdc.has(excavatorHologramKey, PersistentDataType.STRING)) {
                    ArmorStand stand = (ArmorStand) entity;
                    stand.setCustomName(text);
                    stand.setCustomNameVisible(true);
                }
            }
        }
    }

    /**
     * Завершить раскопку - создать шалкер и удалить экскаватор
     */
    
private void finishExcavation(ExcavatorData data) {
    // Помечаем как завершённый
    data.setWorking(false);

    Location loc = data.getLocation();
    World world = (loc != null ? loc.getWorld() : null);

    if (loc != null) {
        // Удаляем визуальные эффекты и пиглина
        removeVisuals(loc);
        removePiglin(loc);

        // Удаляем блок экскаватора
        loc.getBlock().setType(Material.AIR);

        // Удаляем данные из менеджера и активные задачи, сразу сохраняем файл
        manager.removeExcavator(loc);
        activeProcesses.remove(loc);
    }

    // Создаём шалкер с ресурсами и остатками топлива
    createResourceShulker(data);

    // Эффекты завершения
    if (world != null && loc != null) {
        world.playSound(loc, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
        world.spawnParticle(Particle.EXPLOSION_EMITTER,
                loc.clone().add(0.5, 0.5, 0.5), 1);
    }
}

    /**
     * Остановить раскопку
     */
    public void stopExcavation(ExcavatorData data) {
        data.setWorking(false);
        manager.updateExcavator(data);
        
        removePiglin(data.getLocation());
        activeProcesses.remove(data.getLocation());
    }

    


    
    /**
     * Создать шалкер с собранными ресурсами И остатками топлива
     */
    
    
    private void createResourceShulker(ExcavatorData data) {
        Bukkit.getLogger().info("[ExcavatorDBG] createResourceShulker called at " + data.getLocation());
        Location loc = data.getLocation();
        World world = loc.getWorld();
        if (world == null) {
            Bukkit.getLogger().warning("[ExcavatorDBG] World is null for excavator at " + loc);
            return;
        }

        // Берём чанк экскаватора
        Chunk chunk = loc.getChunk();
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight() - 1;

        // Ищем верхнюю поверхность бедрока в чанке, чтобы примерно туда кинуть шалкер
        int bestY = Integer.MIN_VALUE;
        int bestX = 8;
        int bestZ = 8;

        for (int cx = 0; cx < 16; cx++) {
            for (int cz = 0; cz < 16; cz++) {
                for (int y = maxY; y >= minY; y--) {
                    Block b = chunk.getBlock(cx, y, cz);
                    if (b.getType() == Material.BEDROCK) {
                        Block above = (y + 1 <= maxY) ? chunk.getBlock(cx, y + 1, cz) : null;
                        if (above != null && (above.getType() == Material.AIR
                                || above.getType() == Material.CAVE_AIR
                                || above.getType() == Material.VOID_AIR)) {
                            if (y > bestY) {
                                bestY = y;
                                bestX = cx;
                                bestZ = cz;
                            }
                        }
                        break;
                    }
                }
            }
        }

        Location shulkerDropLoc;
        if (bestY == Integer.MIN_VALUE) {
            // Если бедрок не нашли (кастомный мир и т.п.) — кидаем шалкер примерно под экскаватором
            shulkerDropLoc = new Location(world, loc.getBlockX() + 0.5, minY + 2, loc.getBlockZ() + 0.5);
        } else {
            Block above = chunk.getBlock(bestX, bestY + 1, bestZ);
            shulkerDropLoc = above.getLocation().add(0.5, 0.0, 0.5);
        }

        Bukkit.getLogger().info("[ExcavatorDBG] Shulker item will be dropped at " + shulkerDropLoc);

        // Собираем предметы
        java.util.List<ItemStack> ores = data.getCollectedOres();
        Bukkit.getLogger().info("[ExcavatorDBG] Collected ores size=" + ores.size() + " from raw items=" + data.getCollectedItems());
        int remainingFuel = data.getFuel();
        Bukkit.getLogger().info("[ExcavatorDBG] Remaining fuel to store=" + remainingFuel);

        // ВСЁ взаимодействие с миром делаем в sync-задаче
        Bukkit.getScheduler().runTask(plugin, () -> {
            // Создаём предмет-шалкер и наполняем его через BlockStateMeta
            ItemStack shulkerItem = new ItemStack(Material.SHULKER_BOX, 1);
            if (!(shulkerItem.getItemMeta() instanceof org.bukkit.inventory.meta.BlockStateMeta)) {
                Bukkit.getLogger().warning("[ExcavatorDBG] BlockStateMeta is not available for shulker item");
                world.dropItemNaturally(shulkerDropLoc, shulkerItem);
                return;
            }

            org.bukkit.inventory.meta.BlockStateMeta meta = (org.bukkit.inventory.meta.BlockStateMeta) shulkerItem.getItemMeta();
            org.bukkit.block.ShulkerBox boxState = (org.bukkit.block.ShulkerBox) meta.getBlockState();
            Inventory inv = boxState.getInventory();

            int insertedOres = 0;
            for (ItemStack ore : ores) {
                if (ore == null || ore.getType() == Material.AIR) continue;
                inv.addItem(ore.clone());
                insertedOres++;
            }

            if (remainingFuel > 0) {
                ItemStack fuelItem = new ItemStack(Material.AMETHYST_SHARD, remainingFuel);
                inv.addItem(fuelItem);
            }

            // Сохраняем состояние в мету и обратно в ItemStack
            meta.setBlockState(boxState);
            shulkerItem.setItemMeta(meta);

            int nonEmpty = 0;
            for (ItemStack stack : inv.getContents()) {
                if (stack != null && stack.getType() != Material.AIR) nonEmpty++;
            }
            Bukkit.getLogger().info("[ExcavatorDBG] Filled shulker item non-empty slots=" + nonEmpty);

            // Пытаемся выдать шалкер игроку, который находится ближе всего к экскаватору
            Player target = null;
            double bestDistSq = Double.MAX_VALUE;
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getWorld() != world) continue;
                double distSq = p.getLocation().distanceSquared(loc);
                if (distSq < 100 * 100 && distSq < bestDistSq) {
                    bestDistSq = distSq;
                    target = p;
                }
            }

            if (target != null) {
                Bukkit.getLogger().info("[ExcavatorDBG] Giving filled shulker to player " + target.getName());
                Map<Integer, ItemStack> leftovers = target.getInventory().addItem(shulkerItem);
                if (leftovers.isEmpty()) {
                    target.sendMessage(ChatColor.GREEN + "Вы получили шалкер с ресурсами экскаватора.");
                } else {
                    target.sendMessage(ChatColor.YELLOW + "Ваш инвентарь заполнен, шалкер выпал рядом с вами.");
                    for (ItemStack leftoverItem : leftovers.values()) {
                        if (leftoverItem == null || leftoverItem.getType() == Material.AIR) continue;
                        world.dropItemNaturally(target.getLocation(), leftoverItem);
                    }
                }
            } else {
                // Если поблизости нет игроков — просто дропаем шалкер в мире как раньше
                Bukkit.getLogger().info("[ExcavatorDBG] No nearby player found, dropping shulker at " + shulkerDropLoc);
                world.dropItemNaturally(shulkerDropLoc, shulkerItem);
            }
        });
    }

    /**
     * Удалить визуальные эффекты (кристалл и голограмма)
     */
    private void removeVisuals(Location loc) {
        Location center = loc.clone().add(0.5, 0.5, 0.5);
        for (Entity entity : loc.getWorld().getNearbyEntities(center, 2.0, 3.0, 2.0)) {
            PersistentDataContainer pdc = entity.getPersistentDataContainer();
            if (pdc.has(excavatorCrystalKey, PersistentDataType.STRING)
                || pdc.has(excavatorHologramKey, PersistentDataType.STRING)) {
                entity.remove();
            }
        }
    }
    
    /**
     * Удалить пиглина
     */
    private void removePiglin(Location loc) {
        loc.getWorld().getNearbyEntities(loc, 2, 2, 2).forEach(entity -> {
            if (entity instanceof Piglin) {
                entity.remove();
            }
        });
    }
    
    /**
     * Возобновить все активные процессы при перезагрузке
     */
    public void resumeAll() {
        for (ExcavatorData data : manager.getAllExcavators()) {
            if (data.isWorking()) {
                startExcavationProcess(data);
            }
        }
    }


/**
 * Очистка визуальных эффектов и задач при выгрузке плагина
 */
public void cleanupOnDisable() {
    for (ExcavatorData data : manager.getAllExcavators()) {
        Location loc = data.getLocation();
        if (loc == null || loc.getWorld() == null) continue;

        // Удаляем голограмму и кристалл
        removeVisuals(loc);
        // Удаляем пиглина
        removePiglin(loc);

        // Отменяем активный процесс, если есть
        BukkitRunnable task = activeProcesses.remove(loc);
        if (task != null) {
            task.cancel();
        }
    }
}

/**
 * Возобновить работу всех экскаваторов, которые были в состоянии working=true при перезагрузке
 */
public void resumeAllRunningExcavators() {
    // Создаём копию списка, чтобы безопасно удалять записи из менеджера во время итерации
    for (ExcavatorData data : new ArrayList<>(manager.getAllExcavators())) {
        Location loc = data.getLocation();

        // Если локация повреждена — вычищаем такую запись
        if (loc == null || loc.getWorld() == null) {
            manager.removeExcavator(loc);
            continue;
        }

        Block block = loc.getBlock();
        Material type = block.getType();

        // Если по сохранённым координатам больше нет блока экскаватора,
        // считаем запись "зависшей" и удаляем её из файла.
        // Это как раз исправляет ситуацию, когда экскаватор исчез из мира,
        // но остался в excavators.yml и после перезагрузки снова появлялся.
        if (!data.isWorking() && type != Material.COPPER_BULB) {
            Bukkit.getLogger().info("[ExcavatorDBG] Removing stale excavator at "
                    + loc + " (saved working=false, block=" + type + ")");
            manager.removeExcavator(loc);
            continue;
        }

        // Всегда восстанавливаем визуальные эффекты (кристалл + голограмма)
        ensureVisuals(loc, data);

        // Если экскаватор был в процессе работы - продолжаем раскопку
        if (data.isWorking()) {
            startExcavationProcess(data);
        }
        // Если working == false (например, закончилось топливо) -
        // просто оставляем блок с голограммой на месте, без запуска процесса.
    }
}

/**
 * Убедиться, что кристалл и голограмма существуют для данного экскаватора,
 * при необходимости создать их.
 */
private void ensureVisuals(Location blockLoc, ExcavatorData data) {
    World world = blockLoc.getWorld();
    if (world == null) return;

    Location base = blockLoc.clone().add(0.5, 0.0, 0.5);
    boolean hasCrystal = false;
    boolean hasHologram = false;

    for (Entity entity : world.getNearbyEntities(base, 2.0, 3.0, 2.0)) {
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        if (pdc.has(excavatorCrystalKey, PersistentDataType.STRING)) {
            hasCrystal = true;
        }
        if (pdc.has(excavatorHologramKey, PersistentDataType.STRING)) {
            hasHologram = true;
        }
    }

    if (!hasCrystal) {
        world.spawn(base.clone().add(0, -0.2, 0), EnderCrystal.class, c -> {
            c.setShowingBottom(false);
            c.setInvulnerable(true);
            c.setGlowing(true);
            c.setCustomNameVisible(false);
            try {
                c.setGravity(false);
            } catch (Throwable ignored) {}
            PersistentDataContainer pdc = c.getPersistentDataContainer();
            pdc.set(excavatorCrystalKey, PersistentDataType.STRING, "true");
        });
    }

    if (!hasHologram) {
        String text = ChatColor.AQUA + "Топливо: " + ChatColor.WHITE + data.getFuel();
        world.spawn(base.clone().add(0, 1.2, 0), ArmorStand.class, as -> {
            as.setInvisible(true);
            as.setMarker(true);
            as.setSmall(true);
            as.setGravity(false);
            as.setCustomNameVisible(true);
            as.setCustomName(text);
            as.setInvulnerable(true);
            PersistentDataContainer pdc = as.getPersistentDataContainer();
            pdc.set(excavatorHologramKey, PersistentDataType.STRING, "true");
        });
    }
}


    /**
     * Полная очистка воды (и waterlogged) во всём чанке.
     * Делается один раз перед стартом раскопки, чтобы чанк не затапливало.
     */
    private void clearWaterInChunk(Chunk chunk) {
        if (chunk == null) return;
        World world = chunk.getWorld();
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight() - 1;
        int baseX = chunk.getX() << 4;
        int baseZ = chunk.getZ() << 4;

        for (int y = maxY; y >= minY; y--) {
            for (int dx = 0; dx < 16; dx++) {
                for (int dz = 0; dz < 16; dz++) {
                    Block b = world.getBlockAt(baseX + dx, y, baseZ + dz);
                    if (ProtectedBlockUtil.isProtected(b)) continue;

                    Material type = b.getType();
                    if (type == Material.WATER || type == Material.BUBBLE_COLUMN) {
                        b.setType(Material.AIR, false);
                        continue;
                    }

                    BlockData bd = b.getBlockData();
                    if (bd instanceof Waterlogged wl && wl.isWaterlogged()) {
                        wl.setWaterlogged(false);
                        b.setBlockData(wl, false);
                    }
                }
            }
        }
    }

    /**
     * Быстрая очистка "среза" чанка на конкретной высоте (вода + waterlogged).
     * Используется во время раскопки, чтобы вода не успевала залить выкопанное пространство.
     */
    private void clearWaterSlice(Chunk chunk, int y) {
        if (chunk == null) return;
        World world = chunk.getWorld();
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight() - 1;
        if (y < minY || y > maxY) return;

        int baseX = chunk.getX() << 4;
        int baseZ = chunk.getZ() << 4;

        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                Block b = world.getBlockAt(baseX + dx, y, baseZ + dz);
                if (ProtectedBlockUtil.isProtected(b)) continue;

                Material type = b.getType();
                if (type == Material.WATER || type == Material.BUBBLE_COLUMN) {
                    b.setType(Material.AIR, false);
                    continue;
                }

                BlockData bd = b.getBlockData();
                if (bd instanceof Waterlogged wl && wl.isWaterlogged()) {
                    wl.setWaterlogged(false);
                    b.setBlockData(wl, false);
                }
            }
        }
    }


}
