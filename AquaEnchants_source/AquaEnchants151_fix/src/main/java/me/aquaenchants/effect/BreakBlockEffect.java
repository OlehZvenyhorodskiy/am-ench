package me.aquaenchants.effect;

import me.aquaenchants.AquaEnchatsPlugin;
import me.aquaenchants.enchant.CustomEnchant;
import me.aquaenchants.enchant.EnchantManager;
import me.aquaenchants.hook.WorldGuardHook;
import me.aquaenchants.util.ProtectedBlockUtil;
import me.aquaenchants.util.BlockDropUtil;
import org.bukkit.Bukkit;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitScheduler;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Реализация эффекта BREAK_BLOCK.
 * Ломает тоннель 3x3xN по направлению взгляда игрока ступенями
 * в зависимости от уровня зачарования.
 */
public class BreakBlockEffect {

    private final Random random = new Random();



    /**
     * Вызов ступенчатого ломания.
     *
     * level:
     *  I   -> 3x3x1
     *  II  -> 3x3x1, затем 3x3x2
     *  III -> 3x3x1, 3x3x2, 3x3x3
     *  IV  -> 3x3x1..4
     *  V+  -> 3x3x1..5
     */
    public void handleStaged(EffectContext ctx, int level) {
        // Backwards-compatible default: 1 tick between stages
        handleStaged(ctx, level, 1);
    }

    /**
     * Staged BREAK_BLOCK handling with configurable delay between stages.
     *
     * @param ctx   effect context
     * @param level enchant level (defines how many stages)
     * @param delayTicks delay between stages in ticks (min 1)
     */
    public void handleStaged(EffectContext ctx, int level, int delayTicks) {
        Player player = ctx.getPlayer();
        Block origin = ctx.getBlock();

        if (player == null || origin == null || ProtectedBlockUtil.isProtected(origin)) {
            return;
        }

        World world = origin.getWorld();
        if (world == null) return;

        int[] stages;
        switch (level) {
            case 1:
                stages = new int[]{1};
                break;
            case 2:
                stages = new int[]{1, 2};
                break;
            case 3:
                stages = new int[]{1, 2, 3};
                break;
            case 4:
                stages = new int[]{1, 2, 3, 4};
                break;
            default:
                stages = new int[]{1, 2, 3, 4, 5};
                break;
        }

        BukkitScheduler scheduler = Bukkit.getScheduler();
        ItemStack tool = ctx.getTool();
        int stepDelay = Math.max(1, delayTicks);

        // Проверяем, есть ли на инструменте кастомная переплавка (smelting)
        boolean hasSmeltEnchant = false;
        try {
            AquaEnchatsPlugin plugin = AquaEnchatsPlugin.getInstance();
            if (plugin != null && tool != null) {
                EnchantManager em = plugin.getEnchantManager();
                if (em != null) {
                    Map<CustomEnchant, Integer> enchants = em.getEnchantmentsOnItem(tool);
                    for (Map.Entry<CustomEnchant, Integer> e : enchants.entrySet()) {
                        CustomEnchant ench = e.getKey();
                        int lvl = e.getValue();
                        if (ench != null && lvl > 0 && "smelting".equalsIgnoreCase(ench.getId())) {
                            hasSmeltEnchant = true;
                            break;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        final boolean smeltEnabled = hasSmeltEnchant;
        final SmeltEffect smeltEffect = smeltEnabled ? new SmeltEffect() : null;


        // Проверяем также наличие телепатии (telepathy) на инструменте
        boolean hasTelepathy = false;
        try {
            AquaEnchatsPlugin plugin = AquaEnchatsPlugin.getInstance();
            if (plugin != null && tool != null) {
                EnchantManager em = plugin.getEnchantManager();
                if (em != null) {
                    Map<CustomEnchant, Integer> enchants = em.getEnchantmentsOnItem(tool);
                    for (Map.Entry<CustomEnchant, Integer> e : enchants.entrySet()) {
                        CustomEnchant ench = e.getKey();
                        int lvl = e.getValue();
                        if (ench != null && lvl > 0 && "telepathy".equalsIgnoreCase(ench.getId())) {
                            hasTelepathy = true;
                            break;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        final boolean telepathyEnabled = hasTelepathy;
        final TelepathyEffect telepathyHelper = telepathyEnabled ? new TelepathyEffect() : null;

        for (int i = 0; i < stages.length; i++) {
            final int depth = stages[i];
            long delay = (long) (i + 1) * stepDelay; // configurable delay between stages

            scheduler.runTaskLater(AquaEnchatsPlugin.getInstance(), () -> {
                List<Block> blocks = collectTunnelBlocks(player, origin, depth);
                if (blocks.isEmpty()) {
                    return;
                }

                // Звук проигрываем только на первой ступени, чтобы не спамить сервер и игроков.
                if (depth == 1) {
                    world.playSound(origin.getLocation(), Sound.BLOCK_LAVA_EXTINGUISH, 1.0f, 1.0f);
                }

                // Ломаем сначала ближайшие к игроку
                blocks.sort(Comparator.comparingDouble(b -> b.getLocation().distanceSquared(player.getLocation())));

                for (Block b : blocks) {
                    if (b == null) continue;
                    Material type = b.getType();
                    if (type.isAir() || ProtectedBlockUtil.isProtected(type)) continue;

                    // Уважаем ограничения WorldGuard для каждого блока эффекта BreakBlock.
                    try {
                        if (!WorldGuardHook.canUseTimber(player, b)) {
                            continue;
                        }
                    } catch (Throwable ignored) {}

                    Collection<ItemStack> drops = BlockDropUtil.getDropsPreservingBlockState(b, tool, player);
                    // Опыт за дополнительные блоки (руды и т.п.)
                    if (!tool.containsEnchantment(Enchantment.SILK_TOUCH)) {
                        int exp = getExpForBrokenBlock(type);
                        if (exp > 0) {
                            ExperienceOrb orb = world.spawn(b.getLocation().add(0.5, 0.5, 0.5), ExperienceOrb.class);
                            orb.setExperience(exp);
                        }
                    }
                    b.setType(Material.AIR, false);
                    if (drops != null && !drops.isEmpty()) {
                        for (ItemStack drop : drops) {
                            if (drop == null || drop.getType().isAir()) continue;

                            if (smeltEnabled && smeltEffect != null && !tool.containsEnchantment(Enchantment.SILK_TOUCH)) {
                                // Используем те же правила переплавки, что и основной эффект SMELT.
                                // Если предмет не переплавляется, сохраняем его мету (например содержимое шалкера).
                                org.bukkit.inventory.ItemStack out = smeltEffect.smeltOrKeepOriginal(drop);
                                if (out == null || out.getType().isAir()) continue;
                                if (telepathyEnabled && telepathyHelper != null) {
                                    telepathyHelper.giveOrDropToPlayer(player, world, out);
                                } else {
                                    world.dropItemNaturally(b.getLocation(), out);
                                }
                            } else {
                                // Обычный дроп без переплавки
                                if (telepathyEnabled && telepathyHelper != null) {
                                    telepathyHelper.giveOrDropToPlayer(player, world, drop);
                                } else {
                                    world.dropItemNaturally(b.getLocation(), drop);
                                }
                            }
                        }
                    }
                }
            }, delay);
        }
    }


    /**
     * Подсчёт опыта за сломанный блок, аналогично ванильным значениям.
     */
    private int getExpForBrokenBlock(Material type) {
        if (type == null) {
            return 0;
        }
        switch (type) {
            case COAL_ORE:
            case DEEPSLATE_COAL_ORE:
            case NETHER_QUARTZ_ORE:
            case COPPER_ORE:
            case DEEPSLATE_COPPER_ORE:
                return random.nextInt(3); // 0–2

            case REDSTONE_ORE:
            case DEEPSLATE_REDSTONE_ORE:
            case LAPIS_ORE:
            case DEEPSLATE_LAPIS_ORE:
            case NETHER_GOLD_ORE:
                return 1 + random.nextInt(5); // 1–5

            case DIAMOND_ORE:
            case DEEPSLATE_DIAMOND_ORE:
            case EMERALD_ORE:
            case DEEPSLATE_EMERALD_ORE:
            case ANCIENT_DEBRIS:
                return 3 + random.nextInt(5); // 3–7

            default:
                return 0;
        }
    }

    /**
     * Собрать все блоки в тоннеле 3x3xdepth по направлению взгляда игрока.
     * Центр первого слоя — сломанный блок.
     */
    private List<Block> collectTunnelBlocks(Player player, Block origin, int depth) {
        List<Block> result = new ArrayList<>();
        if (player == null || origin == null || depth <= 0) {
            return result;
        }

        World w = origin.getWorld();
        if (w == null) return result;

        // Направление взгляда
        org.bukkit.util.Vector look = player.getLocation().getDirection();
        double ax = Math.abs(look.getX());
        double ay = Math.abs(look.getY());
        double az = Math.abs(look.getZ());

        // Базовые целочисленные векторы
        int fx = 0, fy = 0, fz = 0; // forward (вглубь)
        int rx = 0, ry = 0, rz = 0; // right
        int ux = 0, uy = 0, uz = 0; // up

        if (ay >= ax && ay >= az) {
            // Вертикальное копание: вперёд по Y, плоскость XZ
            fy = look.getY() >= 0 ? 1 : -1;
            ux = 0;  uy = 0;  uz = 1;   // up   -> Z
            rx = 1;  ry = 0;  rz = 0;   // right-> X
        } else {
            // Горизонтальное копание: вперёд по X или Z, плоскость перпендикуляр + Y
            if (ax >= az) {
                // Вперёд по X
                fx = look.getX() >= 0 ? 1 : -1;
                ux = 0;  uy = 1;  uz = 0;   // up   -> Y
                rx = 0;  ry = 0;  rz = 1;   // right-> Z
            } else {
                // Вперёд по Z
                fz = look.getZ() >= 0 ? 1 : -1;
                ux = 0;  uy = 1;  uz = 0;   // up   -> Y
                rx = 1;  ry = 0;  rz = 0;   // right-> X
            }
        }

        int ox = origin.getX();
        int oy = origin.getY();
        int oz = origin.getZ();

        // depth слоёв от 0 до depth-1
        for (int d = 0; d < depth; d++) {
            int bx = ox + fx * d;
            int by = oy + fy * d;
            int bz = oz + fz * d;

            for (int sx = -1; sx <= 1; sx++) {
                for (int sy = -1; sy <= 1; sy++) {
                    int x = bx + rx * sx + ux * sy;
                    int y = by + ry * sx + uy * sy;
                    int z = bz + rz * sx + uz * sy;

                    Block b = w.getBlockAt(x, y, z);
                    if (ProtectedBlockUtil.canBreakBlock(b)) {
                        result.add(b);
                    }
                }
            }
        }

        return result;
    }
}
