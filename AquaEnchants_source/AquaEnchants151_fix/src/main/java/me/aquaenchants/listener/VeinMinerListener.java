package me.aquaenchants.listener;

import me.aquaenchants.AquaEnchatsPlugin;
import me.aquaenchants.enchant.CustomEnchant;
import me.aquaenchants.enchant.EnchantLevel;
import me.aquaenchants.enchant.EnchantManager;
import me.aquaenchants.hook.WorldGuardHook;
import me.aquaenchants.util.ProtectedBlockUtil;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.*;

/**
 * Реализация зачарования veinminer:
 *  - добывает целую жилу руды того же типа;
 *  - шанс и кулдаун берутся из конфига уровней;
 *  - не работает вместе с шёлковым касанием, experience и trench.
 */
public class VeinMinerListener implements Listener {

    private final AquaEnchatsPlugin plugin;
    private final EnchantManager enchantManager;
    private final Random random = new Random();

    // Кулдауны по игрокам
    private final Map<UUID, Long> cooldowns = new HashMap<>();

    // Кэш белых списков руд по уровню
    private final Map<Integer, Set<Material>> whitelistCache = new HashMap<>();

    // Флаг, чтобы не вызывать эффект рекурсивно для блоков, которые мы сами ломаем
    private static boolean processingVein = false;

    public VeinMinerListener(AquaEnchatsPlugin plugin, EnchantManager enchantManager) {
        this.plugin = plugin;
        this.enchantManager = enchantManager;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (processingVein) {
            // Внутренние BlockBreakEvent от breakNaturally() — пропускаем, чтобы не зациклиться.
            return;
        }

        Player player = event.getPlayer();
        if (player == null) {
            return;
        }

        ItemStack tool = player.getInventory().getItemInMainHand();
        if (tool == null || tool.getType() == Material.AIR) {
            return;
        }

        Map<CustomEnchant, Integer> enchants = enchantManager.getEnchantmentsOnItem(tool);
        if (enchants == null || enchants.isEmpty()) {
            return;
        }

        CustomEnchant veinEnchant = null;
        int veinLevel = 0;
        boolean hasTrench = false;
        boolean hasExperience = false;

        for (Map.Entry<CustomEnchant, Integer> e : enchants.entrySet()) {
            CustomEnchant ench = e.getKey();
            int lvl = e.getValue();
            if (ench == null || lvl <= 0) {
                continue;
            }
            String id = ench.getId();
            if (id == null) {
                continue;
            }
            String norm = id.toLowerCase(Locale.ROOT);
            if (norm.equals("veinminer")) {
                veinEnchant = ench;
                veinLevel = lvl;
            } else if (norm.equals("trench")) {
                hasTrench = true;
            } else if (norm.equals("experience")) {
                hasExperience = true;
            }
        }

        if (veinEnchant == null || veinLevel <= 0) {
            return;
        }

        // Не работаем вместе с silk touch, trench и experience
        if (tool.containsEnchantment(Enchantment.SILK_TOUCH) || hasTrench || hasExperience) {
            return;
        }

        Block origin = event.getBlock();
        if (ProtectedBlockUtil.isProtected(origin)) {
            event.setCancelled(true);
            event.setDropItems(false);
            event.setExpToDrop(0);
            return;
        }
        Material type = origin.getType();
        Set<Material> whitelist = getWhitelistForLevel(veinLevel);
        if (whitelist.isEmpty() || !whitelist.contains(type)) {
            return;
        }

        EnchantLevel data = veinEnchant.getLevel(veinLevel);
        int chance = 100;
        int cooldownSec = 0;
        if (data != null) {
            if (data.getChance() > 0) {
                chance = data.getChance();
            }
            if (data.getCooldown() > 0) {
                cooldownSec = data.getCooldown();
            }
        }

        if (chance < 100) {
            double roll = random.nextDouble() * 100.0;
            if (roll > chance) {
                return;
            }
        }

        if (cooldownSec > 0) {
            long now = System.currentTimeMillis();
            long cdMs = cooldownSec * 1000L;
            UUID uuid = player.getUniqueId();
            Long last = cooldowns.get(uuid);
            if (last != null && now - last < cdMs) {
                return;
            }
            cooldowns.put(uuid, now);
        }

        // Собираем жилу
        List<Block> veinBlocks = collectVein(origin, type, 128);
        if (veinBlocks.isEmpty()) {
            return;
        }

        processingVein = true;
        try {
            for (Block b : veinBlocks) {
                if (b == null || b.equals(origin) || ProtectedBlockUtil.isProtected(b)) {
                    continue;
                }
                if (!WorldGuardHook.canUseTimber(player, b)) {
                    continue;
                }
                // Ломаем блок "по-ванильному", чтобы сработали другие эффекты и зачарования
                b.breakNaturally(tool);
            }
        } finally {
            processingVein = false;
        }
    }

    private List<Block> collectVein(Block origin, Material type, int maxBlocks) {
        List<Block> result = new ArrayList<>();
        if (origin == null || type == null || maxBlocks <= 0) {
            return result;
        }

        Set<Block> visited = new HashSet<>();
        Deque<Block> queue = new ArrayDeque<>();

        visited.add(origin);
        queue.add(origin);

        while (!queue.isEmpty() && result.size() < maxBlocks) {
            Block current = queue.poll();
            if (current == null) {
                continue;
            }

            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) != 1) {
                            // 6-соседей (по осям), без диагоналей
                            continue;
                        }
                        Block n = current.getRelative(dx, dy, dz);
                        if (n == null || visited.contains(n) || ProtectedBlockUtil.isProtected(n)) {
                            continue;
                        }
                        visited.add(n);
                        if (n.getType() == type) {
                            result.add(n);
                            queue.add(n);
                            if (result.size() >= maxBlocks) {
                                break;
                            }
                        }
                    }
                    if (result.size() >= maxBlocks) {
                        break;
                    }
                }
                if (result.size() >= maxBlocks) {
                    break;
                }
            }
        }

        return result;
    }

    private Set<Material> getWhitelistForLevel(int level) {
        return whitelistCache.computeIfAbsent(level, this::loadWhitelistForLevel);
    }

    private Set<Material> loadWhitelistForLevel(int level) {
        Set<Material> set = new HashSet<>();
        try {
            File file = new File(plugin.getDataFolder(), "enachants.yml");
            if (!file.exists()) {
                return set;
            }
            FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);
            String path = "veinminer.levels." + level + ".settings.whitelist";
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
        return set;
    }
}
