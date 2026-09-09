package me.aquaenchants.listener;

import me.aquaenchants.effect.EffectManager;
import me.aquaenchants.enchant.CustomEnchant;
import me.aquaenchants.enchant.EnchantManager;
import me.aquaenchants.enchant.EnchantType;
import me.aquaenchants.util.ProtectedBlockUtil;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;

public class BlockBreakListener implements Listener {

    private final EnchantManager enchantManager;
    private final EffectManager effectManager;
    private final me.aquaenchants.AquaEnchatsPlugin plugin;

    public BlockBreakListener(EnchantManager enchantManager, EffectManager effectManager) {
        this(enchantManager, effectManager, me.aquaenchants.AquaEnchatsPlugin.getInstance());
    }

    public BlockBreakListener(EnchantManager enchantManager, EffectManager effectManager, me.aquaenchants.AquaEnchatsPlugin plugin) {
        this.enchantManager = enchantManager;
        this.effectManager = effectManager;
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        ItemStack tool = player.getInventory().getItemInMainHand();
        if (tool == null) return;
        me.aquaenchants.AquaEnchatsPlugin.getInstance().debug("[DEBUG] onBlockBreak tool=" + tool.getType());

        Map<CustomEnchant, Integer> enchants = enchantManager.getEnchantmentsOnItem(tool);
        if (enchants == null || enchants.isEmpty()) return;
        me.aquaenchants.AquaEnchatsPlugin.getInstance().debug("[DEBUG] enchants.size=" + enchants.size());

        // Если игрок держит предмет с кастомными зачарованиями AquaEnchants,
        // не даём ломать запретные блоки даже одиночным ванильным ударом.
        if (ProtectedBlockUtil.isProtected(event.getBlock())) {
            event.setCancelled(true);
            event.setDropItems(false);
            event.setExpToDrop(0);
            me.aquaenchants.AquaEnchatsPlugin.getInstance().debug("[DEBUG] protected block break cancelled: " + event.getBlock().getType());
            return;
        }

        boolean anyExecuted = false; // объявлено ДО использования

        // Порядок из конфига
        java.util.List<String> seq = plugin.getConfig().getStringList("firstenchant");
        if (seq != null && !seq.isEmpty()) {
            me.aquaenchants.AquaEnchatsPlugin.getInstance().debug("[DEBUG] sequence=" + String.valueOf(seq));
            for (String step : seq) {
                for (Map.Entry<CustomEnchant, Integer> entry : enchants.entrySet()) {
                    CustomEnchant ench = entry.getKey();
                    int level = entry.getValue();
                    if (ench.getType() == EnchantType.MINING) {
                        String id = ench.getId();
                        if (id != null && id.equalsIgnoreCase("veinminer")) {
                            continue;
                        }
                        me.aquaenchants.AquaEnchatsPlugin.getInstance().debug("[DEBUG]  step=" + step + " ench=" + ench.getId() + " lvl=" + level);
                        effectManager.handleMiningEnchantStep(player, tool, ench, level, event, step);
                        anyExecuted = true;
                    }
                }
            }
        } else {
            // Старое поведение
            for (Map.Entry<CustomEnchant, Integer> entry : enchants.entrySet()) {
                CustomEnchant ench = entry.getKey();
                int level = entry.getValue();
                if (ench.getType() == EnchantType.MINING) {
                        String id = ench.getId();
                        if (id != null && id.equalsIgnoreCase("veinminer")) {
                            continue;
                        }
                    me.aquaenchants.AquaEnchatsPlugin.getInstance().debug("[DEBUG]  default-run ench=" + ench.getId() + " lvl=" + level);
                    effectManager.handleMiningEnchant(player, tool, ench, level, event);
                    anyExecuted = true;
                }
            }
        }

        // Fallback по лору (если PDC не прочитался)
        if (!anyExecuted) {
            me.aquaenchants.AquaEnchatsPlugin.getInstance().debug("[DEBUG] no effects executed, trying lore fallback");
            org.bukkit.inventory.meta.ItemMeta meta = tool.getItemMeta();
            if (meta != null && meta.hasLore()) {
                java.util.List<String> lore = meta.getLore();
                int lvlFromLore = 0;
                if (lore != null) {
                    for (String line : lore) {
                        if (line == null) continue;
                        String stripped = org.bukkit.ChatColor.stripColor(line).toUpperCase(java.util.Locale.ROOT);
                        if (stripped.contains("ТРАНШ") || stripped.contains("LINEMINE") || stripped.contains("LINE")) {
                            java.util.regex.Matcher mm = java.util.regex.Pattern.compile("([IVXLCDM]+)\s*$").matcher(stripped);
                            if (mm.find()) {
                                String roman = mm.group(1);
                                int val;
                                switch (roman) {
                                    case "I": val = 1; break;
                                    case "II": val = 2; break;
                                    case "III": val = 3; break;
                                    case "IV": val = 4; break;
                                    case "V": val = 5; break;
                                    default:  val = 1; break;
                                }
                                lvlFromLore = Math.max(lvlFromLore, val);
                            } else {
                                lvlFromLore = Math.max(lvlFromLore, 1);
                            }
                        }
                    }
                }
                if (lvlFromLore > 0) {
                    new me.aquaenchants.effect.LineMineEffect()
                        .handle(new me.aquaenchants.effect.EffectContext(player, tool, event.getBlock(), event),
                                Math.max(1, Math.min(5, lvlFromLore)));
                }
            }
        }

        // Опционально обновляем лор после слома
        if (plugin.getConfig().getBoolean("settings.updateLoreOnBlockBreak", false)) {
            enchantManager.setEnchantmentsOnItem(tool, enchants);
        }
    }
}
