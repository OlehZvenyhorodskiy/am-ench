package me.aquaenchants.effect;

import me.aquaenchants.AquaEnchatsPlugin;
import me.aquaenchants.enchant.CustomEnchant;
import me.aquaenchants.enchant.EnchantManager;
import me.aquaenchants.enchant.TimberEnergyUtil;
import me.aquaenchants.hook.WorldGuardHook;
import me.aquaenchants.util.ProtectedBlockUtil;
import me.aquaenchants.util.BlockDropUtil;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/**
 * Эффект BREAK_TREE: ломает всё дерево целиком.
 * Под «деревом» понимается связная компонента блоков ствола (LOG / STEM / их STRIPPED-варианты).
 *
 * Эффект учитывает:
 *  - кастомное зачарование smelting (переплавка);
 *  - кастомное зачарование telepathy (дроп сразу в инвентарь).
 */
public class BreakTreeEffect {

    // Защита от слишком больших структур
    private static final int MAX_BLOCKS = 4096;

    public void handle(EffectContext ctx, int level) {
        Player player = ctx.getPlayer();
        Block origin = ctx.getBlock();

        if (player == null || origin == null || ProtectedBlockUtil.isProtected(origin)) {
            return;
        }

        World world = origin.getWorld();
        if (world == null) return;

        Material originType = origin.getType();
        if (!isLog(originType)) {
            // Timber срабатывает только по дереву
            return;
        }

        ItemStack tool = ctx.getTool();
        if (tool == null) {
            return;
        }

        // Проверяем энергию Timber
        if (!TimberEnergyUtil.hasEnergy(tool, TimberEnergyUtil.ENERGY_PER_USE)) {
            player.sendMessage(ChatColor.RED + "Инструмент нужно зарядить энергией, нажмите ПКМ для перекачки опыта.");
            return;
        }

        // Проверяем кастомные зачарования на инструменте
        boolean hasSmeltEnchant = false;
        boolean hasTelepathy = false;
        try {
            AquaEnchatsPlugin plugin = AquaEnchatsPlugin.getInstance();
            if (plugin != null) {
                EnchantManager em = plugin.getEnchantManager();
                if (em != null) {
                    Map<CustomEnchant, Integer> enchants = em.getEnchantmentsOnItem(tool);
                    for (Map.Entry<CustomEnchant, Integer> e : enchants.entrySet()) {
                        CustomEnchant ench = e.getKey();
                        int lvl = e.getValue();
                        if (ench == null || lvl <= 0) continue;
                        String id = ench.getId();
                        if (id == null) continue;
                        String norm = id.toLowerCase(Locale.ROOT);
                        if (norm.equals("smelting")) {
                            hasSmeltEnchant = true;
                        } else if (norm.equals("telepathy")) {
                            hasTelepathy = true;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        final boolean smeltEnabled = hasSmeltEnchant;
        final boolean telepathyEnabled = hasTelepathy;
        final SmeltEffect smeltEffect = smeltEnabled ? new SmeltEffect() : null;
        final TelepathyEffect telepathyHelper = telepathyEnabled ? new TelepathyEffect() : null;

        // Собираем всё дерево (ограничено MAX_BLOCKS)
        List<Block> treeBlocks = collectTreeBlocks(origin);

        if (treeBlocks.isEmpty()) {
            return;
        }

        // Звук ломания дерева
        world.playSound(origin.getLocation(), Sound.BLOCK_WOOD_BREAK, 1.0f, 1.0f);

        // Ломаем дерево начиная с верхних блоков (чтобы визуально было приятнее)
        treeBlocks.sort(Comparator.comparingDouble(b -> -b.getLocation().getY()));

        for (Block b : treeBlocks) {
            if (b == null) continue;
            Material type = b.getType();
            if (!isLog(type) || ProtectedBlockUtil.isProtected(type)) continue;

            // Уважаем ограничения WorldGuard для каждого блока ствола.
            try {
                if (!WorldGuardHook.canUseTimber(player, b)) {
                    continue;
                }
            } catch (Throwable ignored) {}

            Collection<ItemStack> drops = BlockDropUtil.getDropsPreservingBlockState(b, tool, player);
            b.setType(Material.AIR, false);

            if (drops == null || drops.isEmpty()) {
                continue;
            }

            for (ItemStack drop : drops) {
                if (drop == null || drop.getType().isAir()) continue;

                ItemStack out = drop;

                if (smeltEnabled && smeltEffect != null && !tool.containsEnchantment(Enchantment.SILK_TOUCH)) {
                    // Те же правила переплавки, что и в эффекте SMELT.
                    // Если предмет не переплавляется, сохраняем его мету (например содержимое шалкера).
                    out = smeltEffect.smeltOrKeepOriginal(drop);

                    // Лавовые партиклы отключены: на массовой добыче они сильно грузят сервер.
                }

                if (telepathyEnabled && telepathyHelper != null) {
                    telepathyHelper.giveOrDropToPlayer(player, world, out);
                } else {
                    world.dropItemNaturally(b.getLocation(), out);
                }
            }
        }

        // После успешного использования Timber списываем энергию
        TimberEnergyUtil.consumeEnergy(tool, TimberEnergyUtil.ENERGY_PER_USE);
    }

    private List<Block> collectTreeBlocks(Block origin) {
        List<Block> result = new ArrayList<>();
        World world = origin.getWorld();
        if (world == null) return result;

        Set<Block> visited = new HashSet<>();
        Deque<Block> queue = new ArrayDeque<>();
        queue.add(origin);
        visited.add(origin);

        while (!queue.isEmpty() && result.size() < MAX_BLOCKS) {
            Block current = queue.poll();
            if (current == null) continue;
            Material type = current.getType();
            if (!isLog(type)) continue;

            result.add(current);

            // Обходим соседей в кубе 3x3x3
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        Block nb = world.getBlockAt(
                                current.getX() + dx,
                                current.getY() + dy,
                                current.getZ() + dz
                        );
                        if (nb == null) continue;
                        if (visited.contains(nb)) continue;
                        if (!isLog(nb.getType())) continue;
                        visited.add(nb);
                        queue.add(nb);
                    }
                }
            }
        }

        return result;
    }

    private boolean isLog(Material type) {
        if (type == null) return false;
        String name = type.name();
        return name.endsWith("_LOG")
                || name.endsWith("_STEM")
                || name.endsWith("_WOOD")
                || name.endsWith("_HYPHAE")
                || name.startsWith("STRIPPED_") && (
                        name.endsWith("_LOG")
                        || name.endsWith("_STEM")
                        || name.endsWith("_WOOD")
                        || name.endsWith("_HYPHAE")
                );
    }
}
