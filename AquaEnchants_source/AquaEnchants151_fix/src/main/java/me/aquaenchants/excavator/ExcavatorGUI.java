package me.aquaenchants.excavator;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Arrays;
import java.util.List;

/**
 * GUI для управления экскаватором
 */
public class ExcavatorGUI {
    
    private static final int[] FUEL_SLOTS = {10, 11, 12, 13, 14, 15}; // слоты для аметистов
    private static final int BUTTON_BELOW = 29; // левая кирка
    private static final int BUTTON_CHUNK = 33; // правая кирка
    private static final int INFO_SLOT = 4; // информационный слот
    
    private final Inventory inventory;
    private final ExcavatorData data;
    
    public ExcavatorGUI(ExcavatorData data) {
        this.data = data;
        this.inventory = Bukkit.createInventory(null, 54, ChatColor.DARK_GRAY + "Настройка экскаватора");
        setupGUI();
    }
    
    private void setupGUI() {
        // Заполняем фон
        ItemStack glass = createItem(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 54; i++) {
            inventory.setItem(i, glass);
        }
        
        // Заполняем топливные слоты текущим количеством топлива из данных.
        // Важно: топливо должно сохраняться между открытиями GUI и быть извлекаемым игроком.
        renderFuelFromData();
        
        // Информационный блок
        updateInfoSlot();
        
        // Левая кирка (удалить блоки ниже)
        ItemStack pickaxeBelow = createEnchantedPickaxe(
            ChatColor.GREEN + "Раскопать ниже",
            Arrays.asList(
                ChatColor.GRAY + "Нажмите, чтобы удалить все",
                ChatColor.GRAY + "блоки ниже экскаватора"
            )
        );
        inventory.setItem(BUTTON_BELOW, pickaxeBelow);
        
        // Правая кирка (удалить весь чанк)
        ItemStack pickaxeChunk = createEnchantedPickaxe(
            ChatColor.GOLD + "Раскопать чанк",
            Arrays.asList(
                ChatColor.GRAY + "Нажмите, чтобы удалить",
                ChatColor.GRAY + "все блоки в чанке"
            )
        );
        inventory.setItem(BUTTON_CHUNK, pickaxeChunk);
    }

    /**
     * Отрисовать топливо (аметисты) из данных экскаватора в топливные слоты GUI.
     */
    public void renderFuelFromData() {
        int fuel = Math.max(0, data.getFuel());

        // очищаем топливные слоты
        for (int slot : FUEL_SLOTS) {
            inventory.setItem(slot, null);
        }

        // раскладываем аметисты по слотам
        for (int slot : FUEL_SLOTS) {
            if (fuel <= 0) break;
            int stack = Math.min(64, fuel);
            fuel -= stack;
            inventory.setItem(slot, new ItemStack(Material.AMETHYST_SHARD, stack));
        }
    }
    
    /**
     * Обновить информационный слот с данными о топливе
     */
    public void updateInfoSlot() {
        int currentFuel = data.getFuel();
        int layersNeeded = calculateLayersNeeded();
        
        ItemStack info = createItem(
            Material.AMETHYST_SHARD,
            ChatColor.AQUA + "Топливо: " + ChatColor.WHITE + currentFuel + " аметистов",
            Arrays.asList(
                "",
                ChatColor.GRAY + "Для раскопки " + ChatColor.WHITE + layersNeeded + ChatColor.GRAY + " слоёв",
                ChatColor.GRAY + "требуется " + ChatColor.WHITE + layersNeeded + ChatColor.GRAY + " аметистов",
                "",
                ChatColor.YELLOW + "1 слой = 1 аметист"
            )
        );
        inventory.setItem(INFO_SLOT, info);
    }
    
    /**
     * Вычислить количество слоёв для раскопки
     */
    private int calculateLayersNeeded() {
        if (data.getLocation() == null) return 0;

        int minY = data.getLocation().getWorld().getMinHeight();

        // Если экскаватор уже работает - считаем оставшиеся слои от текущего уровня
        int startY;
        if (data.isWorking()) {
            startY = data.getCurrentLayer();
        } else {
            // Если не работает — считаем от уровня на 1 блок ниже его текущего положения
            startY = data.getLocation().getBlockY() - 1;
        }

        if (startY < minY) {
            return 0;
        }

        // Количество слоёв (и аметистов) до бедрока включительно
        return (startY - minY) + 1;
    }
    
    /**
     * Подсчитать аметисты в GUI
     */
    public int countAmethysts() {
        int count = 0;
        for (int slot : FUEL_SLOTS) {
            ItemStack item = inventory.getItem(slot);
            if (item != null && item.getType() == Material.AMETHYST_SHARD) {
                count += item.getAmount();
            }
        }
        return count;
    }

    /**
     * Синхронизировать топливо из GUI в данные экскаватора.
     * Ничего не "съедаем" и не очищаем — игрок должен иметь возможность
     * забрать аметисты обратно, а топливо должно сохраняться между открытиями.
     */
    public void syncFuelToData() {
        int total = countAmethysts();
        data.setFuel(total);
        updateInfoSlot();
    }
    
    /**
     * Открыть GUI для игрока
     */
    public void open(Player player) {
        player.openInventory(inventory);
    }
    
    public Inventory getInventory() {
        return inventory;
    }
    
    public ExcavatorData getData() {
        return data;
    }
    
    public static boolean isFuelSlot(int slot) {
        for (int fuelSlot : FUEL_SLOTS) {
            if (fuelSlot == slot) return true;
        }
        return false;
    }
    
    public static boolean isButtonSlot(int slot) {
        return slot == BUTTON_BELOW || slot == BUTTON_CHUNK;
    }
    
    public static boolean isBelowButton(int slot) {
        return slot == BUTTON_BELOW;
    }
    
    public static boolean isChunkButton(int slot) {
        return slot == BUTTON_CHUNK;
    }
    
    // Вспомогательные методы
    private ItemStack createItem(Material material, String name) {
        return createItem(material, name, null);
    }
    
    private ItemStack createItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore != null) {
                meta.setLore(lore);
            }
            item.setItemMeta(meta);
        }
        return item;
    }
    
    private ItemStack createEnchantedPickaxe(String name, List<String> lore) {
        ItemStack item = new ItemStack(Material.DIAMOND_PICKAXE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            meta.addEnchant(Enchantment.EFFICIENCY, 5, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            item.setItemMeta(meta);
        }
        return item;
    }
}
