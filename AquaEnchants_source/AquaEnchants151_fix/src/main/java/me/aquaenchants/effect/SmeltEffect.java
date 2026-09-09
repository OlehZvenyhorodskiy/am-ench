
package me.aquaenchants.effect;

import me.aquaenchants.enchant.EffectConfig;
import me.aquaenchants.hook.WorldGuardHook;
import me.aquaenchants.util.ProtectedBlockUtil;
import me.aquaenchants.util.BlockDropUtil;
import org.bukkit.Material;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Collection;

/**
 * Надёжная переплавка дропа.
 * - Отключает ванильные дропы и дропает переплавленный результат.
 * - Учитывает инструмент/шёлковое касание.
 * - Умеет работать даже если конфиг «урезан».
 */
public class SmeltEffect {

    public Material getSmeltedByRecipe(ItemStack in) {
        try {
            java.util.Iterator<Recipe> it = Bukkit.recipeIterator();
            while (it.hasNext()) {
                Recipe r = it.next();
                if (r instanceof CookingRecipe) {
                    CookingRecipe<?> cr = (CookingRecipe<?>) r;
                    RecipeChoice ch = cr.getInputChoice();
                    if (ch != null && ch.test(in)) {
                        return cr.getResult().getType();
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Converts a drop if there is a smelting result; otherwise returns a clone of the
     * original drop so metadata (for example shulker contents) is not lost.
     */
    public ItemStack smeltOrKeepOriginal(ItemStack drop) {
        if (drop == null || drop.getType().isAir()) {
            return drop;
        }
        Material smelt = getSmeltedByRecipe(drop);
        if (smelt == null) {
            smelt = getSmeltedPublic(drop.getType());
        }
        if (smelt == null || smelt == drop.getType()) {
            return drop.clone();
        }
        return new ItemStack(smelt, drop.getAmount());
    }


    public void handle(EffectContext context, EffectConfig config) {
        Block block = context.getBlock();
        Player player = context.getPlayer();
        ItemStack tool = context.getTool();
        BlockBreakEvent event = context.getBlockBreakEvent();

        if (block == null || player == null || tool == null || event == null) return;
        if (ProtectedBlockUtil.isProtected(block)) return;
        // WorldGuard: уважаем запрет
        if (!WorldGuardHook.canUseSmelt(player, block)) return;

        // При шёлковом касании переплавка не применяется
        if (tool.containsEnchantment(Enchantment.SILK_TOUCH)) return;

        // Считаем дроп, а затем заменяем его переплавленным
        Collection<ItemStack> drops = BlockDropUtil.getDropsPreservingBlockState(block, tool, player);
        if (drops.isEmpty()) return;

        // Выключаем ванильный дроп и "ломаем" блок
        event.setDropItems(false);

        World world = block.getWorld();
        for (ItemStack drop : drops) {
            ItemStack out = smeltOrKeepOriginal(drop);
            if (out == null || out.getType().isAir()) continue;
            world.dropItemNaturally(block.getLocation(), out);
        }

        // Лавовые партиклы отключены: на массовой добыче они сильно грузят сервер.
    }

    public Material getSmeltedPublic(Material in) {
        switch (in) {
            // Руды -> слитки / ломтики
            case IRON_ORE:
            case DEEPSLATE_IRON_ORE: return Material.IRON_INGOT;
            case GOLD_ORE:
            case DEEPSLATE_GOLD_ORE: return Material.GOLD_INGOT;
            case COPPER_ORE:
            case DEEPSLATE_COPPER_ORE: return Material.COPPER_INGOT;
            case ANCIENT_DEBRIS: return Material.NETHERITE_SCRAP;

            // Камни / прочее
            case COBBLESTONE:
            case MOSSY_COBBLESTONE:
            case BLACKSTONE: return Material.STONE;

            case SAND:
            case RED_SAND: return Material.GLASS;

            case CLAY_BALL: return Material.BRICK;
            case CLAY: return Material.TERRACOTTA;

            case WET_SPONGE: return Material.SPONGE;

            // Древесина -> уголь
            case OAK_LOG:
            case SPRUCE_LOG:
            case BIRCH_LOG:
            case JUNGLE_LOG:
            case ACACIA_LOG:
            case DARK_OAK_LOG:
            case MANGROVE_LOG:
            case CHERRY_LOG:
            case CRIMSON_STEM:
            case WARPED_STEM:
            case STRIPPED_OAK_LOG:
            case STRIPPED_SPRUCE_LOG:
            case STRIPPED_BIRCH_LOG:
            case STRIPPED_JUNGLE_LOG:
            case STRIPPED_ACACIA_LOG:
            case STRIPPED_DARK_OAK_LOG:
            case STRIPPED_MANGROVE_LOG:
            case STRIPPED_CHERRY_LOG:
            case STRIPPED_CRIMSON_STEM:
            case STRIPPED_WARPED_STEM:
                return Material.CHARCOAL;
            default:
                return null;
        }
    }
}
