package me.aquaenchants.listener;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Compatibility mending for edge cases where vanilla mending does not repair items as expected.
 *
 * Observed issues on some server stacks:
 *  - Items with hidden enchants + rewritten lore may not be selected by vanilla mending reliably.
 *  - Some plugins mark items as Unbreakable while they can still carry damage.
 *  - Players expect "mending" to repair shields/tools even when they are in inventory/hotbar.
 *
 * This listener uses gained XP (after vanilla mending has already consumed what it wants)
 * to additionally repair damaged items that have Enchantment.MENDING.
 *
 * Notes:
 *  - We only spend the XP that would be added to the player (PlayerExpChangeEvent).
 *  - 1 XP repairs 2 durability (vanilla ratio).
 */
public final class MendingCompatListener implements Listener {

    private static final int DURABILITY_PER_XP = 2;

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onExp(PlayerExpChangeEvent event) {
        int amount = event.getAmount();
        if (amount <= 0) return;

        Player player = event.getPlayer();
        if (player == null) return;

        PlayerInventory inv = player.getInventory();
        List<ItemStack> candidates = new ArrayList<>();

        // Priority similar-ish to vanilla, but includes inventory to meet player expectations.
        candidates.add(inv.getItemInOffHand());
        candidates.add(inv.getItemInMainHand());
        for (ItemStack it : inv.getArmorContents()) candidates.add(it);
        for (ItemStack it : inv.getContents()) candidates.add(it);

        int remainingXp = amount;

        for (ItemStack item : candidates) {
            if (remainingXp <= 0) break;
            if (item == null || item.getType().isAir()) continue;

            // Skip items that cannot take durability at all.
            if (item.getType().getMaxDurability() <= 0) continue;

            ItemMeta meta = item.getItemMeta();
            if (!(meta instanceof Damageable dmg)) continue;

            int damage = dmg.getDamage();
            if (damage <= 0) continue;

            // Must have real vanilla mending enchant.
            if (!meta.hasEnchant(Enchantment.MENDING)) continue;

            // Spend XP to repair.
            int maxRepair = remainingXp * DURABILITY_PER_XP;
            int repaired = Math.min(damage, maxRepair);
            int xpUsed = (int) Math.ceil(repaired / (double) DURABILITY_PER_XP);

            if (xpUsed <= 0) continue;

            dmg.setDamage(Math.max(0, damage - repaired));
            item.setItemMeta(meta);

            remainingXp -= xpUsed;
        }

        // Reduce gained XP by what we spent on repairs.
        int spent = amount - remainingXp;
        if (spent > 0) {
            event.setAmount(remainingXp);
        }
    }
}
