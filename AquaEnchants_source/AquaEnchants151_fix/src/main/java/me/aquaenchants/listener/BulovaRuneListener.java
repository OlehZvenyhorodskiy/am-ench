package me.aquaenchants.listener;

import me.aquaenchants.AquaEnchatsPlugin;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.Locale;

/**
 * Руна защиты от булавы (bulova_guard).
 *
 * Если у игрока есть руна в допустимом слоте инвентаря, то входящий урон
 * от булавы (MACE) снижается на 60% (игрок получает 40% исходного урона).
 *
 * Также руна:
 *  - не может быть поставлена как блок;
 *  - не может быть переименована в наковальне (если пометить rune_unrenamable=true).
 */
public class BulovaRuneListener implements Listener {

    private final AquaEnchatsPlugin plugin;
    private final NamespacedKey runeTypeKey;
    private final NamespacedKey runeSlotKey;
    private final NamespacedKey runeUnrenamableKey;

    public BulovaRuneListener(AquaEnchatsPlugin plugin) {
        this.plugin = plugin;
        this.runeTypeKey = NamespacedKey.fromString("aquaenchants:rune_type");
        this.runeSlotKey = NamespacedKey.fromString("aquaenchants:rune_slot");
        this.runeUnrenamableKey = NamespacedKey.fromString("aquaenchants:rune_unrenamable");
    }

    /**
     * Блокируем установку головы-руны как блока.
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBlockPlace(BlockPlaceEvent event) {
        ItemStack item = event.getItemInHand();
        if (isBulovaRune(item)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cЭту руну нельзя устанавливать как блок.");
        }
    }

    /**
     * Запрещаем переименование руны в наковальне, если стоит флаг rune_unrenamable=true.
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        AnvilInventory inv = event.getInventory();
        ItemStack first = inv.getItem(0);
        if (!isBulovaRune(first)) {
            return;
        }
        if (!isUnrenamableRune(first)) {
            return;
        }
        // Если руна помечена как "нельзя переименовывать", то отменяем результат.
        event.setResult(null);
    }

    /**
     * Основная логика снижения урона от булавы.
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        Entity victim = event.getEntity();
        if (!(victim instanceof Player)) {
            return;
        }
        Player player = (Player) victim;

        Entity damager = event.getDamager();
        if (!(damager instanceof LivingEntity)) {
            return;
        }
        LivingEntity attacker = (LivingEntity) damager;

        ItemStack weapon = attacker.getEquipment() == null ? null : attacker.getEquipment().getItemInMainHand();
        if (weapon == null || weapon.getType() != Material.MACE) {
            return;
        }

        if (!hasBulovaRune(player)) {
            return;
        }

        double original = event.getDamage();
        double reduced = original * 0.4; // 60% снижения, остаётся 40% урона
        event.setDamage(reduced);
        plugin.getLogger().info("[bulova_rune] damage reduced from " + original + " to " + reduced + " for " + player.getName());
    }

    private boolean hasBulovaRune(Player player) {
        if (player == null) {
            return false;
        }
        PlayerInventory inv = player.getInventory();

        // Проверяем основной инвентарь
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack item = inv.getItem(i);
            if (isBulovaRune(item) && isRuneAllowedInSlot(item, "inventory")) {
                plugin.getLogger().info("[bulova_rune] found rune in inventory slot " + i + " for " + player.getName());
                return true;
            }
        }

        // Основная рука
        ItemStack main = inv.getItemInMainHand();
        if (isBulovaRune(main) && isRuneAllowedInSlot(main, "main_hand")) {
            plugin.getLogger().info("[bulova_rune] found rune in main hand for " + player.getName());
            return true;
        }

        // Вторая рука
        ItemStack off = inv.getItemInOffHand();
        if (isBulovaRune(off) && isRuneAllowedInSlot(off, "off_hand")) {
            plugin.getLogger().info("[bulova_rune] found rune in off hand for " + player.getName());
            return true;
        }

        return false;
    }

    private boolean isRuneAllowedInSlot(ItemStack item, String slot) {
        if (item == null) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String slotConfig = pdc.get(runeSlotKey, PersistentDataType.STRING);

        // Если слот не указан, по умолчанию разрешаем везде.
        if (slotConfig == null || slotConfig.trim().isEmpty()) {
            return true;
        }

        String[] parts = slotConfig.toLowerCase(Locale.ROOT).split(",");
        for (String part : parts) {
            String s = part.trim();
            if (s.isEmpty()) continue;

            if (s.equals("inventory") && slot.equals("inventory")) {
                return true;
            }
            if (s.equals("main_hand") && slot.equals("main_hand")) {
                return true;
            }
            if (s.equals("off_hand") && slot.equals("off_hand")) {
                return true;
            }
        }
        return false;
    }

    private boolean isBulovaRune(ItemStack item) {
        if (item == null || item.getType() != Material.PLAYER_HEAD) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String type = pdc.get(runeTypeKey, PersistentDataType.STRING);
        return type != null && type.equalsIgnoreCase("bulova_guard");
    }

    private boolean isUnrenamableRune(ItemStack item) {
        if (item == null) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String flag = pdc.get(runeUnrenamableKey, PersistentDataType.STRING);
        return flag != null && flag.equalsIgnoreCase("true");
    }
}
