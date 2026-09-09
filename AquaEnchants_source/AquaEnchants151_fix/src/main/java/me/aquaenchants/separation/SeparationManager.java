package me.aquaenchants.separation;

import me.aquaenchants.enchant.CustomEnchant;
import me.aquaenchants.enchant.EnchantManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.enchantments.Enchantment;

import java.io.IOException;
import java.util.*;

/**
 * Управляет процессом снятия зачарований.
 */
public final class SeparationManager {

    public static final String INPUT_GUI_TITLE_KEY = "separation.gui.input_title";
    public static final String SELECT_GUI_TITLE_KEY = "separation.gui.select_title";

    // Размер "окна ввода" по ТЗ
    public static final int INPUT_SIZE = 45;
    public static final int INPUT_SLOT = 22; // центр 45-слотового инвентаря

    private final JavaPlugin plugin;
    private final EnchantManager enchantManager;
    private final SeparationStorage storage;
    private final SeparationGuiConfig guiConfig;

    // active session: UUID -> selected ItemStack (полная копия)
    private final Map<UUID, ItemStack> sessionItem = new HashMap<>();
    // UUID -> mapping slot->enchant identifier
    private final Map<UUID, Map<Integer, EnchantRef>> sessionRefs = new HashMap<>();

    public SeparationManager(JavaPlugin plugin, EnchantManager enchantManager) {
        this.plugin = plugin;
        this.enchantManager = enchantManager;
        this.storage = new SeparationStorage(plugin);
        this.guiConfig = new SeparationGuiConfig(plugin);

        // На случай перезагрузки/краша: предмет может остаться в separation.yml.
        // Возврат делаем при входе игрока или при открытии /separation.
    }

    public JavaPlugin getPlugin() {
        return plugin;
    }

    public void openInput(Player player) {
        // Если после перезагрузки остался сохранённый предмет — сначала возвращаем его,
        // чтобы игрок не потерял вещь.
        returnFromStorageIfPresent(player);

        Inventory inv = Bukkit.createInventory(player, INPUT_SIZE, color(getInputTitle()));

        // Красивая рамка с цветными панелями
        Material[] outerPalette = {
                Material.CYAN_STAINED_GLASS_PANE,
                Material.LIGHT_BLUE_STAINED_GLASS_PANE,
                Material.BLUE_STAINED_GLASS_PANE,
                Material.PURPLE_STAINED_GLASS_PANE,
                Material.MAGENTA_STAINED_GLASS_PANE
        };

        for (int i = 0; i < INPUT_SIZE; i++) {
            if (i == INPUT_SLOT) continue;
            int row = i / 9;
            int col = i % 9;
            boolean isOuter = (row == 0 || row == 4 || col == 0 || col == 8);
            Material mat = isOuter ? outerPalette[(row + col) % outerPalette.length] : Material.PURPLE_STAINED_GLASS_PANE;
            inv.setItem(i, named(new ItemStack(mat), " "));
        }
        ItemStack info = new ItemStack(Material.PAPER);
        ItemMeta meta = info.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + "✦ " + ChatColor.YELLOW + "Положите предмет" + ChatColor.GOLD + " ✦");
            meta.setLore(Arrays.asList(
                    ChatColor.GRAY + "Переместите в центр",
                    ChatColor.GRAY + "зачарованный инструмент/броню/оружие,",
                    ChatColor.GRAY + "чтобы выбрать и снять зачарование.",
                    "",
                    ChatColor.YELLOW + "▸ " + ChatColor.WHITE + "Снятое зачарование превратится в книгу"
            ));
            info.setItemMeta(meta);
        }
        inv.setItem(INPUT_SLOT, info);

        player.openInventory(inv);
    }

    /**
     * Если в separation.yml есть сохранённый предмет для игрока — выдаём и очищаем запись.
     * Используется при входе игрока и перед открытием GUI.
     */
    public void returnFromStorageIfPresent(Player player) {
        if (player == null) return;
        UUID uuid = player.getUniqueId();

        // Если сессия уже активна — ничего не делаем.
        if (sessionItem.containsKey(uuid)) return;

        String b64 = storage.loadItem(uuid);
        if (b64 == null || b64.isEmpty()) return;

        try {
            ItemStack item = ItemStackBase64.fromBase64(b64);
            if (item != null) {
                giveOrDrop(player, item);
            }
        } catch (Exception ignored) {
            // Если предмет не распарсился — всё равно очищаем запись, чтобы не зациклиться.
        }

        // Чистим всё, что могло остаться.
        storage.remove(uuid);
        sessionItem.remove(uuid);
        sessionRefs.remove(uuid);
    }

    public boolean canSeparate(ItemStack item) {
        return isEligible(item);
    }

    public boolean isInputInventory(org.bukkit.inventory.InventoryView view) {
        if (view == null) return false;
        Inventory inv = view.getTopInventory();
        return inv.getSize() == INPUT_SIZE &&
                ChatColor.stripColor(view.getTitle()).equals(ChatColor.stripColor(color(getInputTitle())));
    }

    public boolean isSelectInventory(org.bukkit.inventory.InventoryView view) {
        if (view == null) return false;
        return ChatColor.stripColor(view.getTitle()).equals(ChatColor.stripColor(guiConfig.getSelectTitle()));
    }

    public void acceptItem(Player player, ItemStack item) {
        if (player == null || item == null) return;
        if (!isEligible(item)) {
            player.sendMessage(ChatColor.RED + "Этот предмет не содержит зачарований.");
            return;
        }

        // Сохраняем полную копию в память и yml
        ItemStack copy = item.clone();
        sessionItem.put(player.getUniqueId(), copy);
        try {
            storage.saveItem(player.getUniqueId(), ItemStackBase64.toBase64(copy));
        } catch (IOException e) {
            player.sendMessage(ChatColor.RED + "Не удалось сохранить предмет. Попробуйте ещё раз.");
            plugin.getLogger().warning("Separation: cannot serialize item: " + e.getMessage());
            return;
        }

        openSelect(player);
    }

    public void openSelect(Player player) {
        UUID uuid = player.getUniqueId();
        // Источник истины — separation.yml.
        // Это важно, потому что некоторые метаданные предмета (лоры/флаги/PDC)
        // могут «залипать» в объектах ItemStack/ItemMeta при частых перерисовках GUI.
        // Поэтому каждый раз при открытии выбора берём предмет из yml (если он там есть)
        // и синхронизируем in-memory сессию.
        ItemStack item = null;

        String b64 = storage.loadItem(uuid);
        if (b64 != null && !b64.isEmpty()) {
            try {
                item = ItemStackBase64.fromBase64(b64);
            } catch (Exception ignored) {
                item = null;
            }
        }

        if (item == null) {
            item = sessionItem.get(uuid);
        } else {
            sessionItem.put(uuid, item);
        }
        if (item == null) {
            player.sendMessage(ChatColor.RED + "Нет сохранённого предмета.");
            return;
        }

        Inventory inv = Bukkit.createInventory(player, guiConfig.getSelectSize(), guiConfig.getSelectTitle());

        // Панель (серое стекло) — настраивается в separation_gui.yml
        ItemStack filler = guiConfig.createFillerItem();
        for (Integer s : guiConfig.getFillerSlots()) {
            if (s == null) continue;
            if (s < 0 || s >= inv.getSize()) continue;
            inv.setItem(s, filler);
        }

        // Слот с предметом, с которого снимаем зачарование
        int baseSlot = guiConfig.getItemSlot();
        if (baseSlot >= 0 && baseSlot < inv.getSize()) {
            ItemStack show = item.clone();
            show.setAmount(1);
            inv.setItem(baseSlot, show);
        }

        // Слот со стоимостью (берётся из config.yml, отображение настраивается в separation_gui.yml)
        int costSlot = guiConfig.getCostSlot();
        if (costSlot >= 0 && costSlot < inv.getSize()) {
            Material curMat = getCurrencyMaterial();
            int curAmt = getCurrencyAmount();
            inv.setItem(costSlot, guiConfig.createCostItem(curMat, curAmt, prettify(curMat)));
        }

        // Формируем список всех чар: ваниль + кастом
        List<EnchantRef> refs = collectEnchantRefs(item);
        Map<Integer, EnchantRef> slotMap = new HashMap<>();

        // Доступные слоты под варианты начинаются с option_start (обычно 18)
        Set<Integer> fillerSlots = guiConfig.getFillerSlots();
        List<Integer> optionSlots = new ArrayList<>();
        int start = guiConfig.getOptionStartSlot();
        for (int s = Math.max(0, start); s < inv.getSize(); s++) {
            if (s == baseSlot) continue;
            if (s == costSlot) continue;
            if (fillerSlots.contains(s)) continue;
            optionSlots.add(s);
        }

        for (int i = 0; i < refs.size() && i < optionSlots.size(); i++) {
            int s = optionSlots.get(i);
            EnchantRef ref = refs.get(i);
            inv.setItem(s, buildOptionItem(item, ref));
            slotMap.put(s, ref);
        }

        sessionRefs.put(uuid, slotMap);
        player.openInventory(inv);
    }

    public void handleSelectClick(Player player, int slot) {
        UUID uuid = player.getUniqueId();
        Map<Integer, EnchantRef> slotMap = sessionRefs.get(uuid);
        if (slotMap == null) return;
        EnchantRef ref = slotMap.get(slot);
        if (ref == null) return;

        ItemStack stored = sessionItem.get(uuid);
        if (stored == null) {
            player.sendMessage(ChatColor.RED + "Предмет не найден.");
            return;
        }

        // Проверяем валюту (ванильные чары = базовая цена, кастомные = базовая * уровень)
        Material mat = getCurrencyMaterial();
        int need = getCost(ref);
        if (!hasCurrency(player, mat, need)) {
            player.sendMessage(ChatColor.RED + "Недостаточно предметов для оплаты: нужно " + need + " x " + prettify(mat));
            return;
        }
        takeCurrency(player, mat, need);

        // Выдаём книгу с зачарованием
        ItemStack book = createBookFor(ref);
        giveOrDrop(player, book);

        // Удаляем зачарование из сохранённого предмета
        boolean removed = removeEnchantFromItem(stored, ref);
        if (!removed) {
            player.sendMessage(ChatColor.RED + "Не удалось снять зачарование.");
            return;
        }

        // Если сняли последнее зачарование, предмет обязан стать полностью "пустым":
        // без лора от менеджера и без "фейкового" блеска.
        normalizeIfNoEnchantments(stored);

        // Сохраняем обновлённый предмет
        // ВАЖНО: после мутаций ItemMeta/PDC/лоpа Bukkit иногда оставляет
        // «старые» значения в отображаемом ItemStack, особенно при быстром
        // закрытии/открытии GUI. Поэтому нормализуем предмет через
        // сериализацию-десериализацию и уже эту версию считаем истиной:
        //  - её пишем в separation.yml
        //  - её кладём в sessionItem (её же игрок получит при закрытии)
        try {
            String updatedB64 = ItemStackBase64.toBase64(stored);
            storage.saveItem(uuid, updatedB64);

            // «Прогон» через Base64 гарантирует, что у предмета не останутся
            // старые лоры/флаги/энчанты из предыдущих состояний.
            ItemStack reloaded = ItemStackBase64.fromBase64(updatedB64);
            if (reloaded != null) {
                sessionItem.put(uuid, reloaded);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Separation: cannot persist updated item: " + e.getMessage());
        }

        // Обновляем меню
        openSelect(player);
    }

    public void returnAndClear(Player player) {
        if (player == null) return;
        UUID uuid = player.getUniqueId();
        ItemStack item = sessionItem.remove(uuid);

        // Если в памяти нет, пробуем из yml
        if (item == null) {
            String b64 = storage.loadItem(uuid);
            if (b64 != null && !b64.isEmpty()) {
                try {
                    item = ItemStackBase64.fromBase64(b64);
                } catch (Exception ignored) {}
            }
        }

        if (item != null) {
            giveOrDrop(player, item);
        }
        storage.remove(uuid);
        sessionRefs.remove(uuid);
    }

    public void returnAndClear(UUID uuid) {
        if (uuid == null) return;
        Player player = Bukkit.getPlayer(uuid);
        if (player != null && player.isOnline()) {
            returnAndClear(player);
            return;
        }
        // Если игрок оффлайн — НЕ удаляем запись.
        // Предмет должен вернуться игроку при следующем входе (требование ТЗ).
        sessionItem.remove(uuid);
        sessionRefs.remove(uuid);
    }

    public void shutdownReturnAll() {
        // При выключении/перезагрузке плагина:
        // - онлайн игрокам возвращаем предмет сразу
        // - оффлайн игрокам оставляем запись в separation.yml, чтобы вернуть при следующем входе
        for (UUID uuid : new HashSet<>(sessionItem.keySet())) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                returnAndClear(p);
            }
        }
        sessionItem.clear();
        sessionRefs.clear();
    }

    // ===== Helpers =====

    private boolean isEligible(ItemStack item) {
        if (item == null) return false;
        boolean hasVanilla = false;
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            hasVanilla = !meta.getEnchants().isEmpty();
        }
        boolean hasCustom = !enchantManager.getEnchantmentsOnItem(item).isEmpty();
        return hasVanilla || hasCustom;
    }


    /**
     * Полная очистка предмета, если после снятия чар на нём не осталось
     * ни ванильных, ни кастомных зачарований.
     *
     * Нужно, чтобы после снятия последнего чара предмет действительно стал
     * незачарованным (без фейкового LUCK_OF_THE_SEA, без лора и без скрывающих флагов).
     */
    private void normalizeIfNoEnchantments(ItemStack item) {
        if (item == null) return;

        // Сначала синхронизируем лор/"фейковый" блеск для кастомных чар.
        // Это важно, потому что removeEnchantFromItem() может убирать ванильные чары напрямую.
        enchantManager.setEnchantmentsOnItem(item, enchantManager.getEnchantmentsOnItem(item));

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        // Важно: в плагине может использоваться "фейковый" LUCK_OF_THE_SEA
        // как визуальный блеск для кастомных чар. Он НЕ должен считаться
        // "реальным" ванильным зачарованием при проверке "пустой/не пустой".
        boolean hasRealVanilla = false;
        if (meta.hasEnchants() && !meta.getEnchants().isEmpty()) {
            for (Enchantment e : meta.getEnchants().keySet()) {
                if (e == null) continue;
                if (e == Enchantment.LUCK_OF_THE_SEA && !e.canEnchantItem(item)) {
                    continue; // игнорируем фейковый блеск
                }
                hasRealVanilla = true;
                break;
            }
        }

        boolean hasCustom = !enchantManager.getEnchantmentsOnItem(item).isEmpty();
        if (hasRealVanilla || hasCustom) {
            return;
        }

        // На "чистом" предмете не должно быть ни фейкового LUCK_OF_THE_SEA,
        // ни лора от чар, ни флагов, скрывающих чары.
        if (meta.hasEnchant(Enchantment.LUCK_OF_THE_SEA) && !Enchantment.LUCK_OF_THE_SEA.canEnchantItem(item)) {
            meta.removeEnchant(Enchantment.LUCK_OF_THE_SEA);
        }

        meta.setLore(null);
        meta.removeItemFlags(ItemFlag.HIDE_ENCHANTS);

        item.setItemMeta(meta);
    }

    private List<EnchantRef> collectEnchantRefs(ItemStack item) {
        List<EnchantRef> out = new ArrayList<>();

        // custom
        Map<CustomEnchant, Integer> custom = enchantManager.getEnchantmentsOnItem(item);
        for (Map.Entry<CustomEnchant, Integer> e : custom.entrySet()) {
            CustomEnchant ce = e.getKey();
            int lvl = e.getValue();
            if (ce == null || lvl <= 0) continue;
            out.add(EnchantRef.custom(ce.getId(), lvl, ce.getDisplayName()));
        }

        // vanilla
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            for (Map.Entry<Enchantment, Integer> e : meta.getEnchants().entrySet()) {
                Enchantment ench = e.getKey();
                Integer lvl = e.getValue();
                if (ench == null || lvl == null || lvl <= 0) continue;

                // В проекте LUCK_OF_THE_SEA используется как "фейковое" зачарование
                // только для визуального эффекта (блеск) на предметах с кастомными чарами.
                // Для инструментов/брони оно не применимо (canEnchantItem=false) и не должно
                // предлагаться игроку как вариант для снятия.
                if (ench == Enchantment.LUCK_OF_THE_SEA && !ench.canEnchantItem(item)) {
                    continue;
                }

                out.add(EnchantRef.vanilla(ench.getKey(), lvl, enchantManager.getVanillaEnchantName(ench)));
            }
        }

        // стабильный порядок (по "чистому" имени, без цветов)
        out.sort(Comparator.comparing(a -> (a.displayNamePlain == null ? "" : a.displayNamePlain)
                .toLowerCase(Locale.ROOT)));
        return out;
    }

    private ItemStack buildOptionItem(ItemStack original, EnchantRef ref) {
        ItemStack it = original.clone();
        it.setAmount(1);

        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            // очищаем все ванильные энчанты, чтобы остался только выбранный
            for (Enchantment e : new HashSet<>(meta.getEnchants().keySet())) {
                meta.removeEnchant(e);
            }
            // очищаем кастомные
            enchantManager.setEnchantmentsOnItem(it, Collections.emptyMap());
            meta = it.getItemMeta();
        }

        if (ref.isCustom) {
            // добавляем выбранный кастом
            CustomEnchant ce = enchantManager.getEnchant(ref.customId);
            if (ce != null) {
                Map<CustomEnchant, Integer> m = new HashMap<>();
                m.put(ce, ref.level);
                enchantManager.setEnchantmentsOnItem(it, m);
            }
        } else {
            // добавляем выбранный ванильный
            ItemMeta m = it.getItemMeta();
            if (m != null) {
                Enchantment ench = Enchantment.getByKey(ref.vanillaKey);
                if (ench != null) {
                    m.addEnchant(ench, ref.level, true);
                }
                m.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
                // прячем "лишнее", оставляем только одну строку
                m.addItemFlags(ItemFlag.HIDE_ENCHANTS);
                it.setItemMeta(m);
            }
        }

        // Название/лоре для кнопки
        ItemMeta m2 = it.getItemMeta();
        if (m2 != null) {
            // Vanilla enchants are shown in vanilla-like yellow, custom enchants keep their configured colors.
            if (ref.isCustom) {
                m2.setDisplayName(ref.displayNameColored + " " + toRoman(ref.level));
            } else {
                // По требованию: ванильные зачарования в GUI должны быть серыми (&7)
                m2.setDisplayName(ChatColor.GRAY + ref.displayNamePlain + " " + toRoman(ref.level));
            }
            Material curMat = getCurrencyMaterial();
            int curAmt = getCost(ref);
            m2.setLore(Arrays.asList(
                    ChatColor.GRAY + "Нажмите, чтобы снять",
                    ChatColor.GRAY + "и получить книгу с зачарованием.",
                    ChatColor.DARK_GRAY + " ",
                    ChatColor.GOLD + "Стоимость: " + ChatColor.YELLOW + curAmt + " x " + ChatColor.WHITE + prettify(curMat)
            ));
            m2.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES);
            it.setItemMeta(m2);
        }
        return it;
    }

    /**
     * Рассчитать стоимость снятия зачарования.
     * Ванильные чары всегда по базовой цене, кастомные — базовая * уровень.
     */
    private int getCost(EnchantRef ref) {
        int base = getCurrencyAmount();
        if (base <= 0) return 0;
        if (ref == null) return base;
        if (!ref.isCustom) return base;
        int lvl = Math.max(1, ref.level);
        return base * lvl;
    }

    private ItemStack createBookFor(EnchantRef ref) {
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        org.bukkit.inventory.meta.EnchantmentStorageMeta meta = (org.bukkit.inventory.meta.EnchantmentStorageMeta) book.getItemMeta();
        if (meta == null) return book;

        if (ref.isCustom) {
            CustomEnchant ce = enchantManager.getEnchant(ref.customId);
            if (ce != null) {
                // используем штатный метод, он кладёт PDC
                return enchantManager.createEnchantBook(ce, ref.level, 1);
            }
        }

        Enchantment ench = Enchantment.getByKey(ref.vanillaKey);
        if (ench != null) {
            meta.addStoredEnchant(ench, ref.level, true);
            meta.setDisplayName(ChatColor.YELLOW + ref.displayNamePlain + " " + toRoman(ref.level));
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            book.setItemMeta(meta);
            // prevent renaming plugin-issued books
            enchantManager.markUnrenamableBook(book);
        }
        return book;
    }

    private boolean removeEnchantFromItem(ItemStack item, EnchantRef ref) {
        if (item == null) return false;

        if (ref.isCustom) {
            Map<CustomEnchant, Integer> cur = enchantManager.getEnchantmentsOnItem(item);
            CustomEnchant ce = enchantManager.getEnchant(ref.customId);
            if (ce == null) return false;
            if (!cur.containsKey(ce)) return false;
            cur.remove(ce);
            enchantManager.setEnchantmentsOnItem(item, cur);
            return true;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        Enchantment ench = Enchantment.getByKey(ref.vanillaKey);
        if (ench == null) return false;

        // Для обычных предметов
        if (meta.hasEnchant(ench)) {
            meta.removeEnchant(ench);
            item.setItemMeta(meta);

            // Пересобираем лор/"фейковый" блеск и чистим LUCK_OF_THE_SEA,
            // если он был добавлен плагином как визуальный эффект.
            enchantManager.setEnchantmentsOnItem(item, enchantManager.getEnchantmentsOnItem(item));
            return true;
        }
        // Для книг (если вдруг игрок положит книгу)
        if (meta instanceof org.bukkit.inventory.meta.EnchantmentStorageMeta esm) {
            if (esm.hasStoredEnchant(ench)) {
                esm.removeStoredEnchant(ench);
                item.setItemMeta(esm);

                // На всякий случай синхронизируем кастомные чары/лоры.
                enchantManager.setEnchantmentsOnItem(item, enchantManager.getEnchantmentsOnItem(item));
                return true;
            }
        }
        return false;
    }

    private boolean hasCurrency(Player player, Material mat, int amount) {
        if (amount <= 0) return true;
        int found = 0;
        for (ItemStack it : player.getInventory().getContents()) {
            if (it == null || it.getType() != mat) continue;
            found += it.getAmount();
            if (found >= amount) return true;
        }
        return false;
    }

    private void takeCurrency(Player player, Material mat, int amount) {
        if (amount <= 0) return;
        int remaining = amount;
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack it = contents[i];
            if (it == null || it.getType() != mat) continue;
            int take = Math.min(it.getAmount(), remaining);
            it.setAmount(it.getAmount() - take);
            if (it.getAmount() <= 0) contents[i] = null;
            remaining -= take;
            if (remaining <= 0) break;
        }
        player.getInventory().setContents(contents);
        player.updateInventory();
    }

    private void giveOrDrop(Player player, ItemStack item) {
        if (item == null) return;
        Map<Integer, ItemStack> left = player.getInventory().addItem(item);
        for (ItemStack it : left.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), it);
        }
    }

    private Material getCurrencyMaterial() {
        String s = plugin.getConfig().getString("separation.currency.material", "ECHO_SHARD");
        Material m = Material.matchMaterial(s == null ? "" : s);
        return m == null ? Material.ECHO_SHARD : m;
    }

    private int getCurrencyAmount() {
        return Math.max(0, plugin.getConfig().getInt("separation.currency.amount", 15));
    }

    private String getInputTitle() {
        return plugin.getConfig().getString(INPUT_GUI_TITLE_KEY, "&6Снятие зачарования");
    }

    private String getSelectTitle() {
        return plugin.getConfig().getString(SELECT_GUI_TITLE_KEY, "&6Выбор зачарования");
    }

    private static String color(String s) {
        if (s == null) return "";
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    private static ItemStack named(ItemStack item, String name) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            item.setItemMeta(meta);
        }
        return item;
    }

    private static String prettify(Material mat) {
        if (mat == null) return "";
        // Мини-локализация для наиболее частых валют, чтобы не светить английские названия.
        if (mat == Material.ECHO_SHARD) return "осколок эха";
        return mat.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static String toRoman(int number) {
        return switch (number) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            default -> String.valueOf(number);
        };
    }

    private static final class EnchantRef {
        final boolean isCustom;
        final NamespacedKey vanillaKey;
        final String customId;
        final int level;
        /** Colored name (for custom enchants). */
        final String displayNameColored;
        /** Plain name without color codes (handy for comparisons / vanilla display). */
        final String displayNamePlain;

        private EnchantRef(boolean isCustom, NamespacedKey vanillaKey, String customId, int level, String displayName) {
            this.isCustom = isCustom;
            this.vanillaKey = vanillaKey;
            this.customId = customId;
            this.level = level;
            String colored = displayName == null ? "" : ChatColor.translateAlternateColorCodes('&', displayName);
            this.displayNameColored = colored;
            this.displayNamePlain = ChatColor.stripColor(colored);
        }

        static EnchantRef vanilla(NamespacedKey key, int level, String displayName) {
            return new EnchantRef(false, key, null, level, displayName);
        }

        static EnchantRef custom(String id, int level, String displayName) {
            return new EnchantRef(true, null, id, level, displayName);
        }
    }
}
