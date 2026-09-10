package me.aquaenchants.gui;

import me.aquaenchants.AquaEnchatsPlugin;
import me.aquaenchants.config.TableSettingsManager;
import me.aquaenchants.enchant.ApplyToGroup;
import me.aquaenchants.enchant.CustomEnchant;
import me.aquaenchants.enchant.EnchantManager;
import me.aquaenchants.enchant.ToolCategory;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

/**
 * Interactive Admin GUI for configuring enchantment drop chances and global table settings.
 */
public class AdminChanceGUI implements Listener {

    private final AquaEnchatsPlugin plugin;
    private final EnchantManager enchantManager;
    private final TableSettingsManager tableSettingsManager;

    private static final String MAIN_TITLE = ChatColor.DARK_GRAY + "✦ Настройка шансов зачарований ✦";
    private static final String TABLE_SETTINGS_TITLE = ChatColor.DARK_GRAY + "⚙ Настройки стола зачарований";

    public AdminChanceGUI(AquaEnchatsPlugin plugin, EnchantManager enchantManager, TableSettingsManager tableSettingsManager) {
        this.plugin = plugin;
        this.enchantManager = enchantManager;
        this.tableSettingsManager = tableSettingsManager;
    }

    public enum GuiType {
        MAIN,
        CATEGORY,
        EDITOR,
        TABLE_SETTINGS
    }

    public static class AdminHolder implements InventoryHolder {
        private final GuiType type;
        private final String categoryKey;
        private final String categoryName;
        private final CustomEnchant enchant;
        private Inventory inventory;

        public AdminHolder(GuiType type, String categoryKey, String categoryName, CustomEnchant enchant) {
            this.type = type;
            this.categoryKey = categoryKey;
            this.categoryName = categoryName;
            this.enchant = enchant;
        }

        public GuiType getType() {
            return type;
        }

        public String getCategoryKey() {
            return categoryKey;
        }

        public String getCategoryName() {
            return categoryName;
        }

        public CustomEnchant getEnchant() {
            return enchant;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        public void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }
    }

    // Sessions for open editors
    private final Map<UUID, EditSession> sessions = new HashMap<>();

    private static class EditSession {
        final CustomEnchant enchant;
        final String categoryKey;
        final String categoryName;
        int currentChance;
        final int originalChance;

        EditSession(CustomEnchant enchant, String categoryKey, String categoryName, int chance) {
            this.enchant = enchant;
            this.categoryKey = categoryKey;
            this.categoryName = categoryName;
            this.currentChance = chance;
            this.originalChance = chance;
        }
    }

    public void openMainMenu(Player player) {
        AdminHolder holder = new AdminHolder(GuiType.MAIN, null, null, null);
        Inventory inv = Bukkit.createInventory(holder, 54, MAIN_TITLE);
        holder.setInventory(inv);

        fillFrame(inv);

        inv.setItem(10, categoryItem(Material.NETHERITE_SWORD, ChatColor.RED + "⚔ Зачарования для Мечей", "SWORDS", "Мечи"));
        inv.setItem(12, categoryItem(Material.NETHERITE_AXE, ChatColor.GOLD + "🪓 Зачарования для Топоров", "AXES", "Топоры"));
        inv.setItem(14, categoryItem(Material.NETHERITE_PICKAXE, ChatColor.AQUA + "⛏ Зачарования для Кирок", "PICKAXES", "Кирки"));
        inv.setItem(16, categoryItem(Material.NETHERITE_HOE, ChatColor.GREEN + "🌾 Зачарования для Мотыг", "HOES", "Мотыги"));

        inv.setItem(28, categoryItem(Material.NETHERITE_SHOVEL, ChatColor.YELLOW + "🪨 Зачарования для Лопат", "SHOVELS", "Лопаты"));
        inv.setItem(30, categoryItem(Material.NETHERITE_CHESTPLATE, ChatColor.LIGHT_PURPLE + "🛡 Зачарования для Брони", "ARMOR", "Броня"));
        inv.setItem(32, categoryItem(Material.BOW, ChatColor.DARK_AQUA + "🏹 Луки и Оружие дальнего боя", "RANGED", "Дальний бой"));
        inv.setItem(34, categoryItem(Material.ENCHANTED_BOOK, ChatColor.DARK_PURPLE + "📜 Все кастомные зачарования", "ALL", "Все чары"));

        // Global Table Settings button
        ItemStack tableSettingsBtn = new ItemStack(Material.ENCHANTING_TABLE);
        ItemMeta tsMeta = tableSettingsBtn.getItemMeta();
        if (tsMeta != null) {
            tsMeta.setDisplayName(ChatColor.GOLD + "⚙ Настройки стола зачарований");
            tsMeta.setLore(Arrays.asList(
                    ChatColor.GRAY + "Настройка бонусов от книжных полок",
                    ChatColor.GRAY + "и базовых шансов кастомных чар по слотам.",
                    "",
                    ChatColor.YELLOW + "▸ Нажмите для настройки"
            ));
            tableSettingsBtn.setItemMeta(tsMeta);
        }
        inv.setItem(48, tableSettingsBtn);

        ItemStack info = new ItemStack(Material.BOOK);
        ItemMeta meta = info.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + "✦ Панель управления шансами ✦");
            meta.setLore(Arrays.asList(
                    ChatColor.GRAY + "Выберите категорию снаряжения,",
                    ChatColor.GRAY + "чтобы настроить шанс выпадения каждого",
                    ChatColor.GRAY + "зачарования в столе зачарований.",
                    "",
                    ChatColor.YELLOW + "▸ " + ChatColor.WHITE + "Изменения сохраняются напрямую в конфиги"
            ));
            info.setItemMeta(meta);
        }
        inv.setItem(49, info);

        player.openInventory(inv);
        player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.8f, 1.2f);
    }

    private ItemStack categoryItem(Material mat, String name, String tag, String desc) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Категория: " + ChatColor.WHITE + desc);
            lore.add("");
            lore.add(ChatColor.YELLOW + "▸ Нажмите, чтобы открыть список чар");
            meta.setLore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    public void openCategoryList(Player player, String categoryKey, String categoryName) {
        List<CustomEnchant> list = getEnchantsForCategory(categoryKey);
        String title = ChatColor.DARK_GRAY + "Категория: " + ChatColor.DARK_PURPLE + categoryName;
        if (title.length() > 32) title = title.substring(0, 32);

        AdminHolder holder = new AdminHolder(GuiType.CATEGORY, categoryKey, categoryName, null);
        Inventory inv = Bukkit.createInventory(holder, 54, title);
        holder.setInventory(inv);

        fillFrame(inv);

        int slot = 10;
        for (CustomEnchant ce : list) {
            if (slot > 43) break;
            if (slot % 9 == 8) slot += 2; // skip border columns

            inv.setItem(slot, enchantIconItem(ce));
            slot++;
        }

        ItemStack back = new ItemStack(Material.ARROW);
        ItemMeta backMeta = back.getItemMeta();
        if (backMeta != null) {
            backMeta.setDisplayName(ChatColor.YELLOW + "« Назад в главное меню");
            back.setItemMeta(backMeta);
        }
        inv.setItem(45, back);

        player.openInventory(inv);
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.0f);
    }

    private List<CustomEnchant> getEnchantsForCategory(String categoryKey) {
        List<CustomEnchant> result = new ArrayList<>();
        Collection<CustomEnchant> all = enchantManager.getAll();

        if ("ALL".equalsIgnoreCase(categoryKey)) {
            result.addAll(all);
            result.sort(Comparator.comparing(CustomEnchant::getId));
            return result;
        }

        for (CustomEnchant ce : all) {
            if (ce == null) continue;
            Set<ToolCategory> cats = ce.getApplies();
            Set<ApplyToGroup> groups = enchantManager.getAppliesToGroups(ce);

            boolean matches = false;
            switch (categoryKey.toUpperCase(Locale.ROOT)) {
                case "SWORDS" -> matches = containsCategory(cats, "SWORD") || containsGroup(groups, "WEAPON") || containsGroup(groups, "SWORD");
                case "AXES" -> matches = containsCategory(cats, "AXE") || containsGroup(groups, "AXE");
                case "PICKAXES" -> matches = containsCategory(cats, "PICKAXE") || containsGroup(groups, "PICKAXE");
                case "HOES" -> matches = containsCategory(cats, "HOE") || containsGroup(groups, "HOE");
                case "SHOVELS" -> matches = containsCategory(cats, "SHOVEL") || containsGroup(groups, "SHOVEL");
                case "ARMOR" -> matches = containsCategory(cats, "HELMET") || containsCategory(cats, "CHESTPLATE") || containsCategory(cats, "LEGGINGS") || containsCategory(cats, "BOOTS") || containsGroup(groups, "ARMOR");
                case "RANGED" -> matches = containsCategory(cats, "BOW") || containsCategory(cats, "CROSSBOW") || containsCategory(cats, "TRIDENT") || containsGroup(groups, "BOW");
            }

            if (matches) {
                result.add(ce);
            }
        }

        result.sort(Comparator.comparing(CustomEnchant::getId));
        return result;
    }

    private boolean containsCategory(Set<ToolCategory> set, String keyword) {
        if (set == null) return false;
        for (ToolCategory tc : set) {
            if (tc != null && tc.name().contains(keyword)) return true;
        }
        return false;
    }

    private boolean containsGroup(Set<ApplyToGroup> set, String keyword) {
        if (set == null) return false;
        for (ApplyToGroup ag : set) {
            if (ag != null && ag.name().contains(keyword)) return true;
        }
        return false;
    }

    private ItemStack enchantIconItem(CustomEnchant ce) {
        ItemStack it = new ItemStack(Material.ENCHANTED_BOOK);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            String display = ce.getDisplayName() != null ? ce.getDisplayName() : ce.getId();
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', display));

            List<String> lore = new ArrayList<>();
            String groupBadge = switch (ce.getGroup() != null ? ce.getGroup().toUpperCase(Locale.ROOT) : "") {
                case "UNIKAL" -> ChatColor.GOLD + "✦ Уникальное зачарование";
                case "LEGENDA" -> ChatColor.LIGHT_PURPLE + "★ Легендарное зачарование";
                default -> ChatColor.AQUA + "✧ Обычное зачарование";
            };
            lore.add(groupBadge);

            if (ce.getDescription() != null && !ce.getDescription().isEmpty()) {
                lore.add(ChatColor.GRAY + "Эффект: " + ChatColor.WHITE + ChatColor.translateAlternateColorCodes('&', ce.getDescription()));
            }

            lore.add("");
            lore.add(ChatColor.BLUE + "Текущий шанс (chans): " + ChatColor.GREEN + ce.getEnchantTableChance() + "%");
            lore.add(ChatColor.GRAY + "В столе зачарований: " + (ce.isEnchantTableEnabled() ? ChatColor.GREEN + "Включено" : ChatColor.RED + "Отключено"));
            lore.add("");
            lore.add(ChatColor.YELLOW + "▸ Нажмите для изменения шанса");

            meta.setLore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    public void openEnchantEditor(Player player, CustomEnchant enchant, String categoryKey, String categoryName) {
        int chance = enchant.getEnchantTableChance();
        EditSession session = new EditSession(enchant, categoryKey, categoryName, chance);
        sessions.put(player.getUniqueId(), session);

        String title = ChatColor.DARK_GRAY + "Шанс: " + ChatColor.GOLD + session.currentChance + "%" + ChatColor.DARK_GRAY + " | " + ChatColor.DARK_PURPLE + enchant.getId();
        if (title.length() > 32) title = title.substring(0, 32);

        AdminHolder holder = new AdminHolder(GuiType.EDITOR, categoryKey, categoryName, enchant);
        Inventory inv = Bukkit.createInventory(holder, 45, title);
        holder.setInventory(inv);

        fillFrame(inv);

        // Buttons
        inv.setItem(23, adjustmentItem(Material.LIME_STAINED_GLASS_PANE, ChatColor.GREEN + "+1%", "+1"));
        inv.setItem(24, adjustmentItem(Material.LIME_STAINED_GLASS_PANE, ChatColor.GREEN + "+5%", "+5"));
        inv.setItem(25, adjustmentItem(Material.LIME_STAINED_GLASS_PANE, ChatColor.GREEN + "+10%", "+10"));

        inv.setItem(21, adjustmentItem(Material.RED_STAINED_GLASS_PANE, ChatColor.RED + "-1%", "-1"));
        inv.setItem(20, adjustmentItem(Material.RED_STAINED_GLASS_PANE, ChatColor.RED + "-5%", "-5"));
        inv.setItem(19, adjustmentItem(Material.RED_STAINED_GLASS_PANE, ChatColor.RED + "-10%", "-10"));

        inv.setItem(36, named(Material.ARROW, ChatColor.YELLOW + "« Назад к списку"));
        inv.setItem(38, named(Material.BARRIER, ChatColor.RED + "Сбросить к " + session.originalChance + "%"));

        updateEditorDynamicSlots(inv, session);

        player.openInventory(inv);
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.8f, 1.2f);
    }

    private void updateEditorDynamicSlots(Inventory inv, EditSession session) {
        CustomEnchant ce = session.enchant;

        // Center Info Item (slot 22)
        ItemStack centerInfo = new ItemStack(Material.PAPER);
        ItemMeta meta = centerInfo.getItemMeta();
        if (meta != null) {
            String display = ce.getDisplayName() != null ? ce.getDisplayName() : ce.getId();
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', display));

            List<String> lore = new ArrayList<>();
            String groupBadge = switch (ce.getGroup() != null ? ce.getGroup().toUpperCase(Locale.ROOT) : "") {
                case "UNIKAL" -> ChatColor.GOLD + "✦ Уникальное зачарование";
                case "LEGENDA" -> ChatColor.LIGHT_PURPLE + "★ Легендарное зачарование";
                default -> ChatColor.AQUA + "✧ Обычное зачарование";
            };
            lore.add(groupBadge);

            if (ce.getDescription() != null && !ce.getDescription().isEmpty()) {
                lore.add(ChatColor.GRAY + "Описание: " + ChatColor.WHITE + ChatColor.translateAlternateColorCodes('&', ce.getDescription()));
            }

            lore.add("");
            lore.add(ChatColor.YELLOW + "Шанс выпадения: " + ChatColor.GREEN + ChatColor.BOLD + session.currentChance + "%");
            lore.add(ChatColor.DARK_GRAY + "(Исходный шанс: " + session.originalChance + "%)");
            lore.add("");
            lore.add(ChatColor.GRAY + "Зеленые кнопки справа: " + ChatColor.GREEN + "+% к шансу");
            lore.add(ChatColor.GRAY + "Красные кнопки слева: " + ChatColor.RED + "-% от шанса");

            meta.setLore(lore);
            centerInfo.setItemMeta(meta);
        }
        inv.setItem(22, centerInfo);

        // Save Button (slot 40)
        ItemStack save = named(Material.EMERALD_BLOCK, ChatColor.GREEN + "" + ChatColor.BOLD + "✔ СОХРАНИТЬ В CONFIG");
        ItemMeta saveMeta = save.getItemMeta();
        if (saveMeta != null) {
            saveMeta.setLore(Arrays.asList(
                    ChatColor.GRAY + "Записать новый шанс " + ChatColor.GREEN + session.currentChance + "%",
                    ChatColor.GRAY + "в файл enachants.yml и обновить на сервере."
            ));
            save.setItemMeta(saveMeta);
        }
        inv.setItem(40, save);
    }

    public void openTableSettingsMenu(Player player) {
        AdminHolder holder = new AdminHolder(GuiType.TABLE_SETTINGS, null, null, null);
        Inventory inv = Bukkit.createInventory(holder, 45, TABLE_SETTINGS_TITLE);
        holder.setInventory(inv);

        fillFrame(inv);
        updateTableSettingsSlots(inv);

        inv.setItem(36, named(Material.ARROW, ChatColor.YELLOW + "« Назад в главное меню"));
        inv.setItem(40, named(Material.EMERALD_BLOCK, ChatColor.GREEN + "" + ChatColor.BOLD + "✔ СОХРАНИТЬ НАСТРОЙКИ"));

        player.openInventory(inv);
        player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.8f, 1.2f);
    }

    private void updateTableSettingsSlots(Inventory inv) {
        if (tableSettingsManager == null) return;

        // Min bonus (0 shelves) - Slot 11
        ItemStack minItem = named(Material.BOOK, ChatColor.AQUA + "Минимальный бонус полок (0 полок)");
        ItemMeta minMeta = minItem.getItemMeta();
        if (minMeta != null) {
            minMeta.setLore(Arrays.asList(
                    ChatColor.GRAY + "Бонус к шансу доп. зачарований без полок.",
                    ChatColor.YELLOW + "Текущее значение: " + ChatColor.GREEN + tableSettingsManager.getMinBonusChance() + "%",
                    "",
                    ChatColor.GRAY + "Используйте кнопки выше/ниже для изменения."
            ));
            minItem.setItemMeta(minMeta);
        }
        inv.setItem(11, minItem);
        inv.setItem(2, adjustmentItem(Material.LIME_STAINED_GLASS_PANE, ChatColor.GREEN + "+1% к мин. бонусу", "+1"));
        inv.setItem(20, adjustmentItem(Material.RED_STAINED_GLASS_PANE, ChatColor.RED + "-1% от мин. бонуса", "-1"));

        // Max bonus (15 shelves) - Slot 15
        ItemStack maxItem = named(Material.ENCHANTED_BOOK, ChatColor.GOLD + "Максимальный бонус полок (15 полок)");
        ItemMeta maxMeta = maxItem.getItemMeta();
        if (maxMeta != null) {
            maxMeta.setLore(Arrays.asList(
                    ChatColor.GRAY + "Бонус к шансу доп. зачарований при 15 полках.",
                    ChatColor.YELLOW + "Текущее значение: " + ChatColor.GREEN + tableSettingsManager.getMaxBonusChance() + "%",
                    "",
                    ChatColor.GRAY + "Используйте кнопки выше/ниже для изменения."
            ));
            maxItem.setItemMeta(maxMeta);
        }
        inv.setItem(15, maxItem);
        inv.setItem(6, adjustmentItem(Material.LIME_STAINED_GLASS_PANE, ChatColor.GREEN + "+1% к макс. бонусу", "+1"));
        inv.setItem(24, adjustmentItem(Material.RED_STAINED_GLASS_PANE, ChatColor.RED + "-1% от макс. бонуса", "-1"));

        // Slot 1 custom chance (устаревшее) - Slot 29
        ItemStack s1Item = named(Material.BARRIER, ChatColor.GREEN + "Кастомные чары на Слоте I");
        ItemMeta s1Meta = s1Item.getItemMeta();
        if (s1Meta != null) {
            s1Meta.setLore(Arrays.asList(
                    ChatColor.RED + "Отключено: кастомные зачарования больше",
                    ChatColor.RED + "не выпадают на 1-м тире стола.",
                    ChatColor.GRAY + "Они выпадают только на 3-м (максимальном) тире",
                    ChatColor.GRAY + "и только 1 уровня. Настройка — справа."
            ));
            s1Item.setItemMeta(s1Meta);
        }
        inv.setItem(29, s1Item);

        // Slot 2 custom chance (устаревшее) - Slot 31
        ItemStack s2Item = named(Material.BARRIER, ChatColor.YELLOW + "Кастомные чары на Слоте II");
        ItemMeta s2Meta = s2Item.getItemMeta();
        if (s2Meta != null) {
            s2Meta.setLore(Arrays.asList(
                    ChatColor.RED + "Отключено: кастомные зачарования больше",
                    ChatColor.RED + "не выпадают на 2-м тире стола.",
                    ChatColor.GRAY + "Они выпадают только на 3-м (максимальном) тире",
                    ChatColor.GRAY + "и только 1 уровня. Настройка — справа."
            ));
            s2Item.setItemMeta(s2Meta);
        }
        inv.setItem(31, s2Item);

        // Slot 3 custom chance - Slot 33 (единственный активный шанс кастомных чар)
        ItemStack s3Item = named(Material.NETHERITE_SWORD, ChatColor.LIGHT_PURPLE + "Шанс кастомных чар (Слот III)");
        ItemMeta s3Meta = s3Item.getItemMeta();
        if (s3Meta != null) {
            s3Meta.setLore(Arrays.asList(
                    ChatColor.GRAY + "Шанс выпадения кастомной чары на 3-м тире стола.",
                    ChatColor.GRAY + "Бонус от книжных полок: до " + ChatColor.GREEN + "+3%" + ChatColor.GRAY + " (0.2% за полку).",
                    ChatColor.GRAY + "0% в конфиге — полное отключение, полки",
                    ChatColor.GRAY + "отключённые кастомные чары не включат.",
                    ChatColor.GRAY + "На столе кастомные чары выпадают только 1 уровня.",
                    ChatColor.YELLOW + "Текущий шанс: " + ChatColor.GREEN + formatChance(tableSettingsManager.getCustomTier3Chance()) + "%",
                    "",
                    ChatColor.AQUA + "ЛКМ: +1% | ПКМ: -1% | Shift+ЛКМ: +0.5% | Shift+ПКМ: -0.5%"
            ));
            s3Item.setItemMeta(s3Meta);
        }
        inv.setItem(33, s3Item);
    }

    private static String formatChance(double value) {
        if (value == Math.rint(value)) {
            return String.valueOf((long) Math.rint(value));
        }
        return String.format(java.util.Locale.US, "%.1f", value);
    }

    private ItemStack adjustmentItem(Material mat, String name, String delta) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(Arrays.asList(
                    ChatColor.GRAY + "Нажмите, чтобы изменить шанс на " + delta + "%"
            ));
            it.setItemMeta(meta);
        }
        return it;
    }

    private ItemStack named(Material mat, String name) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            it.setItemMeta(meta);
        }
        return it;
    }

    private void fillFrame(Inventory inv) {
        ItemStack glass = named(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, glass);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player player)) return;
        if (!(e.getInventory().getHolder() instanceof AdminHolder holder)) return;

        e.setCancelled(true);
        int slot = e.getRawSlot();
        if (slot >= e.getInventory().getSize()) return;

        if (holder.getType() == GuiType.MAIN) {
            switch (slot) {
                case 10 -> openCategoryList(player, "SWORDS", "Мечи");
                case 12 -> openCategoryList(player, "AXES", "Топоры");
                case 14 -> openCategoryList(player, "PICKAXES", "Кирки");
                case 16 -> openCategoryList(player, "HOES", "Мотыги");
                case 28 -> openCategoryList(player, "SHOVELS", "Лопаты");
                case 30 -> openCategoryList(player, "ARMOR", "Броня");
                case 32 -> openCategoryList(player, "RANGED", "Дальний бой");
                case 34 -> openCategoryList(player, "ALL", "Все чары");
                case 48 -> openTableSettingsMenu(player);
            }
            return;
        }

        if (holder.getType() == GuiType.CATEGORY) {
            if (slot == 45) {
                openMainMenu(player);
                return;
            }

            ItemStack item = e.getCurrentItem();
            if (item != null && item.getType() == Material.ENCHANTED_BOOK) {
                ItemMeta meta = item.getItemMeta();
                if (meta != null && meta.hasDisplayName()) {
                    String displayName = meta.getDisplayName();
                    for (CustomEnchant ce : enchantManager.getAll()) {
                        String check = ChatColor.translateAlternateColorCodes('&', ce.getDisplayName() != null ? ce.getDisplayName() : ce.getId());
                        if (check.equalsIgnoreCase(displayName) || ce.getId().equalsIgnoreCase(displayName)) {
                            openEnchantEditor(player, ce, holder.getCategoryKey(), holder.getCategoryName());
                            return;
                        }
                    }
                }
            }
            return;
        }

        if (holder.getType() == GuiType.TABLE_SETTINGS) {
            if (tableSettingsManager == null) return;
            Inventory inv = e.getInventory();

            switch (slot) {
                case 2 -> { // +1% min
                    tableSettingsManager.setMinBonusChance(tableSettingsManager.getMinBonusChance() + 1);
                    player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.4f);
                    updateTableSettingsSlots(inv);
                }
                case 20 -> { // -1% min
                    tableSettingsManager.setMinBonusChance(tableSettingsManager.getMinBonusChance() - 1);
                    player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 0.9f);
                    updateTableSettingsSlots(inv);
                }
                case 6 -> { // +1% max
                    tableSettingsManager.setMaxBonusChance(tableSettingsManager.getMaxBonusChance() + 1);
                    player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.4f);
                    updateTableSettingsSlots(inv);
                }
                case 24 -> { // -1% max
                    tableSettingsManager.setMaxBonusChance(tableSettingsManager.getMaxBonusChance() - 1);
                    player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 0.9f);
                    updateTableSettingsSlots(inv);
                }
                case 29, 31 -> { // Кастомные чары на слотах I/II отключены (только III тир)
                    player.sendMessage(ChatColor.YELLOW + "Кастомные зачарования выпадают только на 3-м тире стола и только 1 уровня.");
                    player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 0.9f);
                    updateTableSettingsSlots(inv);
                }
                case 33 -> { // Slot 3 custom (единственный активный шанс)
                    double delta = e.isRightClick() ? -1.0 : 1.0;
                    if (e.isShiftClick()) {
                        delta = e.isRightClick() ? -0.5 : 0.5;
                    }
                    tableSettingsManager.setCustomTier3Chance(tableSettingsManager.getCustomTier3Chance() + delta);
                    player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
                    updateTableSettingsSlots(inv);
                }
                case 36 -> { // Back
                    openMainMenu(player);
                }
                case 40 -> { // Save
                    tableSettingsManager.save();
                    player.sendMessage(ChatColor.GREEN + "✔ Настройки стола зачарований успешно сохранены в config.yml!");
                    player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.5f);
                    openMainMenu(player);
                }
            }
            return;
        }

        if (holder.getType() == GuiType.EDITOR) {
            EditSession session = sessions.get(player.getUniqueId());
            if (session == null) {
                session = new EditSession(holder.getEnchant(), holder.getCategoryKey(), holder.getCategoryName(), holder.getEnchant().getEnchantTableChance());
                sessions.put(player.getUniqueId(), session);
            }

            Inventory inv = e.getInventory();

            switch (slot) {
                case 23 -> { // +1%
                    session.currentChance = Math.min(100, session.currentChance + 1);
                    player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.4f);
                    updateEditorDynamicSlots(inv, session);
                }
                case 24 -> { // +5%
                    session.currentChance = Math.min(100, session.currentChance + 5);
                    player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.5f);
                    updateEditorDynamicSlots(inv, session);
                }
                case 25 -> { // +10%
                    session.currentChance = Math.min(100, session.currentChance + 10);
                    player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.6f);
                    updateEditorDynamicSlots(inv, session);
                }
                case 21 -> { // -1%
                    session.currentChance = Math.max(0, session.currentChance - 1);
                    player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 0.9f);
                    updateEditorDynamicSlots(inv, session);
                }
                case 20 -> { // -5%
                    session.currentChance = Math.max(0, session.currentChance - 5);
                    player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 0.8f);
                    updateEditorDynamicSlots(inv, session);
                }
                case 19 -> { // -10%
                    session.currentChance = Math.max(0, session.currentChance - 10);
                    player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 0.7f);
                    updateEditorDynamicSlots(inv, session);
                }
                case 36 -> { // Back
                    String backKey = session.categoryKey != null ? session.categoryKey : "ALL";
                    String backName = session.categoryName != null ? session.categoryName : "Все чары";
                    sessions.remove(player.getUniqueId());
                    openCategoryList(player, backKey, backName);
                }
                case 38 -> { // Reset
                    session.currentChance = session.originalChance;
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.7f, 0.8f);
                    updateEditorDynamicSlots(inv, session);
                }
                case 40 -> { // Save
                    boolean ok = enchantManager.saveEnchantTableChance(session.enchant.getId(), session.currentChance);
                    if (ok) {
                        player.sendMessage(ChatColor.GREEN + "✔ Шанс для зачарования " + ChatColor.GOLD + session.enchant.getId()
                                + ChatColor.GREEN + " успешно сохранен: " + ChatColor.YELLOW + session.currentChance + "%");
                        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.5f);
                    } else {
                        player.sendMessage(ChatColor.RED + "✖ Ошибка сохранения в enachants.yml!");
                    }
                    String backKey = session.categoryKey != null ? session.categoryKey : "ALL";
                    String backName = session.categoryName != null ? session.categoryName : "Все чары";
                    sessions.remove(player.getUniqueId());
                    openCategoryList(player, backKey, backName);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof AdminHolder) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(org.bukkit.event.inventory.InventoryCloseEvent e) {
        if (e.getInventory().getHolder() instanceof AdminHolder) {
            sessions.remove(e.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent e) {
        sessions.remove(e.getPlayer().getUniqueId());
    }
}
