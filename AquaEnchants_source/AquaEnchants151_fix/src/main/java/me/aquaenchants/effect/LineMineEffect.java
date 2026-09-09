package me.aquaenchants.effect;

import me.aquaenchants.AquaEnchatsPlugin;
import me.aquaenchants.enchant.CustomEnchant;
import me.aquaenchants.enchant.EnchantManager;
import me.aquaenchants.hook.WorldGuardHook;
import me.aquaenchants.util.ProtectedBlockUtil;
import me.aquaenchants.util.BlockDropUtil;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.bukkit.enchantments.Enchantment;

import java.util.Map;

public class LineMineEffect {

    public void handle(EffectContext context, int depth) {
        Player player = context.getPlayer();
        ItemStack tool = context.getTool();
        Block origin = context.getBlock();
        BlockBreakEvent event = context.getBlockBreakEvent();

        if (event.isCancelled()) return;
        if (tool == null || ProtectedBlockUtil.isProtected(origin)) return;

        // Определяем, активна ли кастомная «Переплавка» на этом инструменте.
        boolean hasSmeltEnchant = false;
        try {
            AquaEnchatsPlugin plugin = AquaEnchatsPlugin.getInstance();
            if (plugin != null) {
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

        Vector dir = player.getEyeLocation().getDirection().normalize();
        double ax = Math.abs(dir.getX()), ay = Math.abs(dir.getY()), az = Math.abs(dir.getZ());
        boolean vertical = ay >= ax && ay >= az;

        me.aquaenchants.AquaEnchatsPlugin.getInstance().debug("[DEBUG] LineMine: vertical=" + vertical + " depth=" + depth
                + " origin=" + origin.getX()+","+origin.getY()+","+origin.getZ());

        Location baseLoc = origin.getLocation();
        int bx = baseLoc.getBlockX();
        int by = baseLoc.getBlockY();
        int bz = baseLoc.getBlockZ();

        int broken = 0;

        for (int i = 0; i < depth; i++) {
            int dx = (int) Math.round(dir.getX() * i);
            int dy = (int) Math.round(dir.getY() * i);
            int dz = (int) Math.round(dir.getZ() * i);

            int baseX = bx + dx;
            int baseY = by + dy;
            int baseZ = bz + dz;

            if (vertical) {
                for (int dz2 = -1; dz2 <= 1; dz2++) {
                    for (int dx2 = -1; dx2 <= 1; dx2++) {
                        broken += breakBlockIfAllowed(player, tool, origin,
                                baseX + dx2, baseY, baseZ + dz2, hasSmeltEnchant) ? 1 : 0;
                    }
                }
            } else {
                for (int dy2 = -1; dy2 <= 1; dy2++) {
                    for (int dx2 = -1; dx2 <= 1; dx2++) {
                        broken += breakBlockIfAllowed(player, tool, origin,
                                baseX + dx2, baseY + dy2, baseZ, hasSmeltEnchant) ? 1 : 0;
                    }
                }
            }
        }
        me.aquaenchants.AquaEnchatsPlugin.getInstance().debug("[DEBUG] LineMine: broken=" + broken + " blocks");
    }

    private boolean breakBlockIfAllowed(Player player, ItemStack tool, Block origin,
                                        int x, int y, int z, boolean smelt) {
        Block b = origin.getWorld().getBlockAt(x, y, z);
        if (b.equals(origin)) return false;
        if (!ProtectedBlockUtil.canBreakBlock(b)) return false;

        try {
            if (!WorldGuardHook.canUseSmelt(player, b)) return false;
        } catch (Throwable ignored) {}

        java.util.Collection<ItemStack> drops = BlockDropUtil.getDropsPreservingBlockState(b, tool, player);
        if (drops.isEmpty()) {
            b.setType(Material.AIR);
            return true;
        }

        b.setType(Material.AIR);

        // Если на инструменте есть кастомная «Переплавка», используем те же правила,
        // что и обычный эффект SMELT.
        if (smelt && !tool.containsEnchantment(Enchantment.SILK_TOUCH)) {
            SmeltEffect smeltEffect = new SmeltEffect();
            for (ItemStack drop : drops) {
                ItemStack out = smeltEffect.smeltOrKeepOriginal(drop);
                if (out == null || out.getType().isAir()) continue;
                origin.getWorld().dropItemNaturally(b.getLocation(), out);
            }
        } else {
            // Без зачарования переплавки — обычный дроп блоков.
            for (ItemStack drop : drops) {
                origin.getWorld().dropItemNaturally(b.getLocation(), drop);
            }
        }
        return true;
    }
}
