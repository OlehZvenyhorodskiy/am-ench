package me.aquaenchants.listener;

import me.aquaenchants.AquaEnchatsPlugin;
import me.aquaenchants.enchant.CustomEnchant;
import me.aquaenchants.enchant.EnchantManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Particle;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.File;
import java.util.*;

/**
 * Зачарование "indikator" — подсветка руд вокруг игрока за счёт энергии опыта.
 * 
 * Улучшения:
 *  - Группировка блоков руды в жилы
 *  - Приоритизация ценных руд (древние обломки, изумруд, алмаз)
 *  - Оптимизированный поиск с кешированием
 *  - Луч к центру жилы, а не к каждому блоку
 */
public class IndikatorListener implements Listener {

    private static final String ENCHANT_ID = "indikator";
    private static final int XP_DRAIN = 5;
    private static final long XP_INTERVAL_MS = 3000L;
    private static final long SCAN_INTERVAL_TICKS = 6L;
    private static final int SWITCH_SCANS = 2;
    private static final double PARTICLES_PER_BLOCK = 5.0D;
    
    // Приоритеты руд (чем выше, тем важнее)
    private static final Map<Material, Integer> ORE_PRIORITY = new HashMap<>();
    static {
        // Высший приоритет
        ORE_PRIORITY.put(Material.ANCIENT_DEBRIS, 100);
        ORE_PRIORITY.put(Material.EMERALD_ORE, 90);
        ORE_PRIORITY.put(Material.DEEPSLATE_EMERALD_ORE, 90);
        ORE_PRIORITY.put(Material.DIAMOND_ORE, 80);
        ORE_PRIORITY.put(Material.DEEPSLATE_DIAMOND_ORE, 80);
        
        // Средний приоритет
        ORE_PRIORITY.put(Material.GOLD_ORE, 50);
        ORE_PRIORITY.put(Material.DEEPSLATE_GOLD_ORE, 50);
        ORE_PRIORITY.put(Material.NETHER_GOLD_ORE, 50);
        ORE_PRIORITY.put(Material.REDSTONE_ORE, 40);
        ORE_PRIORITY.put(Material.DEEPSLATE_REDSTONE_ORE, 40);
        ORE_PRIORITY.put(Material.LAPIS_ORE, 35);
        ORE_PRIORITY.put(Material.DEEPSLATE_LAPIS_ORE, 35);
        
        // Низкий приоритет
        ORE_PRIORITY.put(Material.IRON_ORE, 20);
        ORE_PRIORITY.put(Material.DEEPSLATE_IRON_ORE, 20);
        ORE_PRIORITY.put(Material.COPPER_ORE, 15);
        ORE_PRIORITY.put(Material.DEEPSLATE_COPPER_ORE, 15);
        ORE_PRIORITY.put(Material.COAL_ORE, 10);
        ORE_PRIORITY.put(Material.DEEPSLATE_COAL_ORE, 10);
        ORE_PRIORITY.put(Material.NETHER_QUARTZ_ORE, 10);
    }

    private static final Set<Material> DEFAULT_ORE_WHITELIST = new HashSet<>(ORE_PRIORITY.keySet());

    private final AquaEnchatsPlugin plugin;
    private final EnchantManager enchantManager;

    private final Map<UUID, BukkitTask> scanTasks = new HashMap<>();
    private final Map<UUID, Long> lastXpDrainTime = new HashMap<>();
    private final Map<UUID, Integer> currentVeinIndex = new HashMap<>();
    private final Map<UUID, Integer> scanCounter = new HashMap<>();
    
    // Кеш найденных жил для каждого игрока
    private final Map<UUID, List<OreVein>> cachedVeins = new HashMap<>();
    private final Map<UUID, Long> lastScanTime = new HashMap<>();
    private static final long CACHE_DURATION_MS = 5000L; // Обновляем кеш раз в 5 секунд

    public IndikatorListener(AquaEnchatsPlugin plugin, EnchantManager enchantManager) {
        this.plugin = plugin;
        this.enchantManager = enchantManager;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.NORMAL)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        if (item == null) {
            return;
        }

        CustomEnchant enchant = enchantManager.getEnchant(ENCHANT_ID);
        if (enchant == null) {
            return;
        }

        Map<CustomEnchant, Integer> enchants = enchantManager.getEnchantmentsOnItem(item);
        Integer levelObj = enchants.get(enchant);
        if (levelObj == null || levelObj <= 0) {
            return;
        }
        int level = levelObj;

        UUID uuid = player.getUniqueId();

        // Тоггл
        if (scanTasks.containsKey(uuid)) {
            stopScan(player, false);
            return;
        }

        if (getCurrentXp(player) < XP_DRAIN) {
            player.sendMessage(ChatColor.RED + "Для эффекта подсветки руд не хватает опыта.");
            return;
        }

        player.sendMessage(ChatColor.GREEN + "Вы начинаете чувствовать, как опыт перетекает в кирку, подсвечивая руду вокруг. "
                + ChatColor.YELLOW + "Нажмите ПКМ ещё раз, чтобы остановить.");

        startScan(player, level);
    }

    private void startScan(final Player player, final int level) {
        final UUID uuid = player.getUniqueId();
        stopScan(player, true);

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline() || player.isDead()) {
                stopScan(player, true);
                return;
            }

            ItemStack inHand = player.getInventory().getItemInMainHand();
            if (!hasIndikator(inHand, level)) {
                stopScan(player, true);
                return;
            }

            long now = System.currentTimeMillis();

            // Списание опыта
            Long lastDrain = lastXpDrainTime.get(uuid);
            if (lastDrain == null) {
                lastDrain = 0L;
            }
            if (now - lastDrain >= XP_INTERVAL_MS) {
                if (getCurrentXp(player) < XP_DRAIN) {
                    player.sendMessage(ChatColor.RED + "Для эффекта подсветки руд не хватает опыта.");
                    stopScan(player, true);
                    return;
                }
                player.giveExp(-XP_DRAIN);
                lastXpDrainTime.put(uuid, now);
            }

            // Получаем или обновляем кеш жил
            List<OreVein> veins = getOrUpdateVeins(player, level, now);
            if (veins.isEmpty()) {
                return;
            }

            // Переключение между жилами
            int size = veins.size();
            int index = currentVeinIndex.getOrDefault(uuid, 0);
            if (index < 0 || index >= size) {
                index = 0;
            }

            int counter = scanCounter.getOrDefault(uuid, 0);
            counter++;
            if (counter >= SWITCH_SCANS) {
                counter = 0;
                index++;
                if (index >= size) {
                    index = 0;
                }
            }
            scanCounter.put(uuid, counter);
            currentVeinIndex.put(uuid, index);

            OreVein targetVein = veins.get(index);
            spawnBeamToVein(player, targetVein);
            highlightVein(player, targetVein);
        }, 0L, SCAN_INTERVAL_TICKS);

        scanTasks.put(uuid, task);
    }

    private List<OreVein> getOrUpdateVeins(Player player, int level, long now) {
        UUID uuid = player.getUniqueId();
        
        // Проверяем, нужно ли обновить кеш
        Long lastScan = lastScanTime.get(uuid);
        if (lastScan != null && (now - lastScan) < CACHE_DURATION_MS) {
            List<OreVein> cached = cachedVeins.get(uuid);
            if (cached != null && !cached.isEmpty()) {
                return cached;
            }
        }

        // Сканируем и группируем руды в жилы
        int radius = getRadiusForLevel(level);
        if (radius <= 0) {
            return Collections.emptyList();
        }

        Set<Material> whitelist = getWhitelistForLevel(level);
        if (whitelist.isEmpty()) {
            whitelist = DEFAULT_ORE_WHITELIST;
        }

        List<Block> oreBlocks = findNearbyOres(player, radius, whitelist);
        if (oreBlocks.isEmpty()) {
            return Collections.emptyList();
        }

        // Группируем блоки в жилы
        List<OreVein> veins = groupIntoVeins(oreBlocks, player.getLocation());
        
        // Сортируем жилы по приоритету и расстоянию
        veins.sort((v1, v2) -> {
            int priorityCompare = Integer.compare(v2.priority, v1.priority);
            if (priorityCompare != 0) {
                return priorityCompare;
            }
            return Double.compare(v1.distance, v2.distance);
        });

        cachedVeins.put(uuid, veins);
        lastScanTime.put(uuid, now);
        return veins;
    }

    /**
     * Группирует соседние блоки руды одного типа в жилы
     */
    private List<OreVein> groupIntoVeins(List<Block> oreBlocks, Location playerLoc) {
        Map<Material, List<Block>> byType = new HashMap<>();
        
        // Группируем по типу
        for (Block block : oreBlocks) {
            byType.computeIfAbsent(block.getType(), k -> new ArrayList<>()).add(block);
        }

        List<OreVein> veins = new ArrayList<>();

        // Для каждого типа руды находим связанные группы (жилы)
        for (Map.Entry<Material, List<Block>> entry : byType.entrySet()) {
            Material type = entry.getKey();
            List<Block> blocks = entry.getValue();
            
            Set<Block> visited = new HashSet<>();
            
            for (Block start : blocks) {
                if (visited.contains(start)) {
                    continue;
                }
                
                // BFS для поиска связанных блоков
                List<Block> veinBlocks = new ArrayList<>();
                Queue<Block> queue = new LinkedList<>();
                queue.add(start);
                visited.add(start);
                
                while (!queue.isEmpty()) {
                    Block current = queue.poll();
                    veinBlocks.add(current);
                    
                    // Проверяем соседние блоки (6 направлений)
                    for (Block neighbor : getAdjacentBlocks(current)) {
                        if (!visited.contains(neighbor) && 
                            neighbor.getType() == type && 
                            blocks.contains(neighbor)) {
                            queue.add(neighbor);
                            visited.add(neighbor);
                        }
                    }
                }
                
                // Создаем жилу
                OreVein vein = new OreVein(type, veinBlocks, playerLoc);
                veins.add(vein);
            }
        }

        return veins;
    }

    private List<Block> getAdjacentBlocks(Block block) {
        List<Block> adjacent = new ArrayList<>(6);
        adjacent.add(block.getRelative(1, 0, 0));
        adjacent.add(block.getRelative(-1, 0, 0));
        adjacent.add(block.getRelative(0, 1, 0));
        adjacent.add(block.getRelative(0, -1, 0));
        adjacent.add(block.getRelative(0, 0, 1));
        adjacent.add(block.getRelative(0, 0, -1));
        return adjacent;
    }

    private void stopScan(Player player, boolean silent) {
        UUID uuid = player.getUniqueId();
        BukkitTask task = scanTasks.remove(uuid);
        if (task != null) {
            task.cancel();
        }
        lastXpDrainTime.remove(uuid);
        currentVeinIndex.remove(uuid);
        scanCounter.remove(uuid);
        cachedVeins.remove(uuid);
        lastScanTime.remove(uuid);

        if (!silent) {
            player.sendMessage(ChatColor.YELLOW + "Вы отпускаете поток опыта, и подсветка руд прекращается.");
        }
    }

    private boolean hasIndikator(ItemStack item, int minLevel) {
        if (item == null) {
            return false;
        }
        CustomEnchant ench = enchantManager.getEnchant(ENCHANT_ID);
        if (ench == null) {
            return false;
        }
        Map<CustomEnchant, Integer> enchants = enchantManager.getEnchantmentsOnItem(item);
        Integer lvl = enchants.get(ench);
        return lvl != null && lvl >= minLevel;
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        stopScan(event.getPlayer(), true);
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        stopScan(event.getEntity(), true);
    }

    private List<Block> findNearbyOres(Player player, int radius, Set<Material> whitelist) {
        List<Block> result = new ArrayList<>();
        World world = player.getWorld();
        Location center = player.getLocation();

        int cx = center.getBlockX();
        int cy = center.getBlockY();
        int cz = center.getBlockZ();

        // Оптимизация: сканируем только Y-уровни в разумном диапазоне
        int minY = Math.max(world.getMinHeight(), cy - radius);
        int maxY = Math.min(world.getMaxHeight() - 1, cy + radius);

        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = cz - radius; z <= cz + radius; z++) {
                    Block b = world.getBlockAt(x, y, z);
                    if (whitelist.contains(b.getType())) {
                        result.add(b);
                    }
                }
            }
        }
        return result;
    }

    /**
     * Рисует луч от игрока к центру жилы
     */
    private void spawnBeamToVein(Player player, OreVein vein) {
        World world = player.getWorld();
        Location playerFeet = player.getLocation();
        Location veinCenter = vein.center;

        Vector dir = veinCenter.toVector().subtract(playerFeet.toVector());
        double length = dir.length();
        if (length <= 0.0001) {
            return;
        }

        int points = (int) Math.max(1, length * PARTICLES_PER_BLOCK);
        Vector step = dir.multiply(1.0 / points);

        Color color = getColorForOre(vein.type);
        Particle.DustOptions dust = new Particle.DustOptions(color, 1.2f);

        Location current = playerFeet.clone().add(0, 0.2, 0); // Чуть выше ног
        for (int i = 0; i <= points; i++) {
            world.spawnParticle(Particle.DUST, current, 1, 0, 0, 0, 0, dust, true);
            current.add(step);
        }
    }

    /**
     * Подсвечивает всю жилу частицами
     */
    private void highlightVein(Player player, OreVein vein) {
        World world = player.getWorld();
        Color color = getColorForOre(vein.type);
        Particle.DustOptions dust = new Particle.DustOptions(color, 0.8f);

        // Подсвечиваем только края жилы для экономии частиц
        int maxBlocks = Math.min(vein.blocks.size(), 10); // Максимум 10 блоков
        for (int i = 0; i < maxBlocks; i++) {
            Block block = vein.blocks.get(i);
            Location loc = block.getLocation().add(0.5, 0.5, 0.5);
            
            // 4 частицы вокруг центра блока
            world.spawnParticle(Particle.DUST, loc, 4, 0.3, 0.3, 0.3, 0, dust, true);
        }
    }

    private Color getColorForOre(Material type) {
        switch (type) {
            case DIAMOND_ORE:
            case DEEPSLATE_DIAMOND_ORE:
                return Color.AQUA;
            case EMERALD_ORE:
            case DEEPSLATE_EMERALD_ORE:
                return Color.LIME;
            case REDSTONE_ORE:
            case DEEPSLATE_REDSTONE_ORE:
                return Color.RED;
            case LAPIS_ORE:
            case DEEPSLATE_LAPIS_ORE:
                return Color.BLUE;
            case GOLD_ORE:
            case DEEPSLATE_GOLD_ORE:
            case NETHER_GOLD_ORE:
                return Color.YELLOW;
            case IRON_ORE:
            case DEEPSLATE_IRON_ORE:
                return Color.SILVER;
            case COAL_ORE:
            case DEEPSLATE_COAL_ORE:
                return Color.fromRGB(50, 50, 50);
            case COPPER_ORE:
            case DEEPSLATE_COPPER_ORE:
                return Color.ORANGE;
            case NETHER_QUARTZ_ORE:
                return Color.WHITE;
            case ANCIENT_DEBRIS:
                return Color.fromRGB(150, 75, 50);
            default:
                return Color.WHITE;
        }
    }

    private int getRadiusForLevel(int level) {
        try {
            File file = new File(plugin.getDataFolder(), "enachants.yml");
            if (!file.exists()) {
                return 0;
            }
            FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);
            String path = ENCHANT_ID + ".levels." + level + ".radius";
            return cfg.getInt(path, 0);
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private Set<Material> getWhitelistForLevel(int level) {
        Set<Material> set = new HashSet<>();
        try {
            File file = new File(plugin.getDataFolder(), "enachants.yml");
            if (!file.exists()) {
                set.addAll(DEFAULT_ORE_WHITELIST);
                return set;
            }
            FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);
            String path = ENCHANT_ID + ".levels." + level + ".settings.whitelist";
            List<String> list = cfg.getStringList(path);
            for (String s : list) {
                if (s == null || s.trim().isEmpty()) {
                    continue;
                }
                Material m = Material.matchMaterial(s.trim());
                if (m == null) {
                    try {
                        m = Material.valueOf(s.trim().toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
                if (m != null && !m.isAir()) {
                    set.add(m);
                }
            }
        } catch (Throwable ignored) {
        }
        if (set.isEmpty()) {
            set.addAll(DEFAULT_ORE_WHITELIST);
        }
        return set;
    }

    private int getCurrentXp(Player player) {
        if (player == null) return 0;
        int level = player.getLevel();
        float progress = player.getExp();
        int xp = 0;
        for (int i = 0; i < level; i++) {
            xp += getExpToLevel(i);
        }
        xp += Math.round(progress * getExpToLevel(level));
        return xp;
    }

    private int getExpToLevel(int level) {
        if (level <= 15) {
            return 2 * level + 7;
        } else if (level <= 30) {
            return 5 * level - 38;
        } else {
            return 9 * level - 158;
        }
    }

    public void shutdown() {
        for (BukkitTask task : scanTasks.values()) {
            try {
                task.cancel();
            } catch (Throwable ignored) {
            }
        }
        scanTasks.clear();
        lastXpDrainTime.clear();
        currentVeinIndex.clear();
        scanCounter.clear();
        cachedVeins.clear();
        lastScanTime.clear();
    }

    /**
     * Класс для представления жилы руды
     */
    private static class OreVein {
        final Material type;
        final List<Block> blocks;
        final Location center;
        final int priority;
        final double distance;

        OreVein(Material type, List<Block> blocks, Location playerLoc) {
            this.type = type;
            this.blocks = blocks;
            this.priority = ORE_PRIORITY.getOrDefault(type, 0);
            
            // Вычисляем центр жилы
            double sumX = 0, sumY = 0, sumZ = 0;
            for (Block b : blocks) {
                Location loc = b.getLocation();
                sumX += loc.getX();
                sumY += loc.getY();
                sumZ += loc.getZ();
            }
            int size = blocks.size();
            this.center = new Location(
                blocks.get(0).getWorld(),
                sumX / size + 0.5,
                sumY / size + 0.5,
                sumZ / size + 0.5
            );
            
            this.distance = center.distance(playerLoc);
        }
    }
}
