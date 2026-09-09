package me.aquaenchants.listener;

import me.aquaenchants.AquaEnchatsPlugin;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Locale;

public class PoisonRuneListener implements Listener {

    private final AquaEnchatsPlugin plugin;
    private final NamespacedKey runeTypeKey;
    private final NamespacedKey runeUnrenamableKey;
    private final NamespacedKey runeSlotKey;

    public PoisonRuneListener(AquaEnchatsPlugin plugin) {
        this.plugin = plugin;
        this.runeTypeKey = NamespacedKey.fromString("aquaenchants:rune_type");
        this.runeUnrenamableKey = NamespacedKey.fromString("aquaenchants:rune_unrenamable");
        this.runeSlotKey = NamespacedKey.fromString("aquaenchants:rune_slot");
    }

    /**
     * Защита от отравления: уменьшает длительность на 50 тиков и уровень на 1.
     * Работает, если руна находится в допустимом слоте у игрока.
     * Слот настраивается через PublicBukkitValues -> aquaenchats:rune_slot:
     *   inventory        - любые слоты инвентаря
     *   main_hand        - предмет в основной руке
     *   off_hand         - предмет во второй руке
     *   можно перечислять через запятую, например: "inventory,off_hand"
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPotionEffect(EntityPotionEffectEvent event) {
        Entity entity = event.getEntity();
        PotionEffect newEffect = event.getNewEffect();
        if (!(entity instanceof Player)) {
            return;
        }
        if (newEffect == null) {
            return;
        }
        if (!PotionEffectType.POISON.equals(newEffect.getType())) {
            return;
        }

        Player player = (Player) entity;
        if (!hasPoisonRune(player)) {
            return;
        }

        int originalDuration = newEffect.getDuration();
        int duration = originalDuration / 2; // 50% от исходного времени
        int amplifier = newEffect.getAmplifier() - 1;

        if (duration <= 0 && amplifier < 0) {
            // Полностью поглощаем эффект
            event.setCancelled(true);
            return;
        }

        if (duration <= 0) {
            duration = 1;
        }
        if (amplifier < 0) {
            amplifier = 0;
        }

        PotionEffect adjusted = new PotionEffect(
                newEffect.getType(),
                duration,
                amplifier,
                newEffect.isAmbient(),
                newEffect.hasParticles(),
                newEffect.hasIcon()
        );

        // Отменяем исходное применение и накладываем скорректированный эффект
        event.setCancelled(true);
        player.addPotionEffect(adjusted, true);
    }

    /**
     * Запрет ставить руну как блок.
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBlockPlace(BlockPlaceEvent event) {
        ItemStack item = event.getItemInHand();
        if (isPoisonRune(item)) {
            event.setCancelled(true);
            Player player = event.getPlayer();
            player.sendMessage(ChatColor.RED + "Эту руну нельзя поставить на землю.");
        }
    }

    /**
     * Запрет переименовывать руну в наковальне.
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        AnvilInventory inv = event.getInventory();
        // В 1.21.x нет getFirstItem(), поэтому берём слот 0
        ItemStack first = inv.getItem(0);
        if (first == null || !isUnrenamableRune(first)) {
            return;
        }

        // Если в первой ячейке лежит руна, не даём создать результат
        event.setResult(null);
    }

    /**
     * Проверка наличия рабочей руны у игрока с учётом настроек слота.
     */
    private boolean hasPoisonRune(Player player) {
        PlayerInventory inv = player.getInventory();

        // Основная рука
        ItemStack main = inv.getItemInMainHand();
        if (isRuneAllowedInSlot(main, "main_hand")) {
            return true;
        }

        // Левая рука
        ItemStack off = inv.getItemInOffHand();
        if (isRuneAllowedInSlot(off, "off_hand")) {
            return true;
        }

        // Любой слот инвентаря (кроме рук)
        for (ItemStack item : inv.getContents()) {
            if (isRuneAllowedInSlot(item, "inventory")) {
                return true;
            }
        }

        return false;
    }

    /**
     * Проверяет, является ли предмет руной нужного типа и разрешён ли указанный слот.
     */
    private boolean isRuneAllowedInSlot(ItemStack item, String slot) {
        if (!isPoisonRune(item)) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String slotConfig = pdc.get(runeSlotKey, PersistentDataType.STRING);

        // Если слот не указан - по умолчанию допускаем в любом месте
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

    private boolean isPoisonRune(ItemStack item) {
        if (item == null || item.getType() != Material.PLAYER_HEAD) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String type = pdc.get(runeTypeKey, PersistentDataType.STRING);
        return type != null && type.equalsIgnoreCase("poison_guard");
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
