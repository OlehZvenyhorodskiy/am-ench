package me.aquaenchants.effect;

import me.aquaenchants.enchant.EffectConfig;
import me.aquaenchants.util.BlockDropUtil;
import org.bukkit.World;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Collection;
import java.util.Map;

/**
 * Эффект телепатии: перемещает дроп сразу в инвентарь игрока.
 * Если инвентарь заполнен, остаток падает рядом с игроком.
 */
public class TelepathyEffect {

    public void handle(EffectContext context, EffectConfig config) {
        Player player = context.getPlayer();
        Block block = context.getBlock();
        BlockBreakEvent event = context.getBlockBreakEvent();
        ItemStack tool = context.getTool();

        if (player == null || block == null || event == null) {
            return;
        }

        World world = block.getWorld();

        // Если другие эффекты уже отключили ванильный дроп (например, SMELT),
        // забираем предметы, уже выпавшие рядом с блоком.
        if (!event.isDropItems()) {
            collectNearbyDropsToInventory(player, block.getLocation().add(0.5, 0.5, 0.5));
        } else {
            // Иначе сами считаем дроп и блокируем ванильный.
            Collection<ItemStack> drops = BlockDropUtil.getDropsPreservingBlockState(block, tool, player);
            if (drops == null || drops.isEmpty()) {
                return;
            }

            event.setDropItems(false);

            for (ItemStack drop : drops) {
                if (drop == null || drop.getType().isAir()) {
                    continue;
                }
                giveOrDropToPlayer(player, world, drop);
            }
        }

        // Звук "капли воды" рядом с игроком (максимально похожий)
        try {
            world.playSound(player.getLocation(), Sound.BLOCK_WATER_AMBIENT, 0.6f, 1.4f);
        } catch (IllegalArgumentException ignored) {
            // На случай, если конкретный звук отсутствует в версии сервера
        }
    }

    private void collectNearbyDropsToInventory(Player player, org.bukkit.Location center) {
        World world = center.getWorld();
        if (world == null) {
            return;
        }

        double radius = 2.0;
        for (Entity entity : world.getNearbyEntities(center, radius, radius, radius)) {
            if (!(entity instanceof Item)) {
                continue;
            }

            Item item = (Item) entity;
            ItemStack stack = item.getItemStack();
            if (stack == null || stack.getType().isAir()) {
                continue;
            }

            giveOrDropToPlayer(player, world, stack);
            item.remove();
        }
    }

    /**
     * Утилита: положить предмет сразу в инвентарь игрока.
     * Если инвентарь заполнен, остаток выбрасывается у ног игрока.
     */
    public void giveOrDropToPlayer(Player player, World world, ItemStack stack) {
        if (player == null || world == null || stack == null || stack.getType().isAir()) {
            return;
        }
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(stack);
        if (leftover != null && !leftover.isEmpty()) {
            for (ItemStack rest : leftover.values()) {
                if (rest == null || rest.getType().isAir()) continue;
                world.dropItemNaturally(player.getLocation(), rest);
            }
        }
    }

}