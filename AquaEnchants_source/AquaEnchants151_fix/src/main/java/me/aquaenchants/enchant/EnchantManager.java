package me.aquaenchants.enchant;

import me.aquaenchants.AquaEnchatsPlugin;
import me.aquaenchants.config.EnchantConfigLoader;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;
import java.util.stream.Collectors;

public class EnchantManager {

    private final AquaEnchatsPlugin plugin;
    private final EnchantConfigLoader loader;

    private final Map<String, CustomEnchant> enchantments = new HashMap<>();

    /**
     * Exposes all loaded custom enchants (for table GUI offer generation).
     */
    public Collection<CustomEnchant> getCustomEnchants() {
        return enchantments.values();
    }
    private List<String> groupOrder = new ArrayList<>();

    private final NamespacedKey keyEnchantList;

    /**
     * Marks plugin-generated enchanted books as "cannot be renamed in an anvil".
     */
    private final NamespacedKey keyUnrenamableBook;

    /**
     * Marker that we manage enchant-lore and HIDE_ENCHANTS ourselves.
     * Helps to reliably clean stale lore/flags on different client versions.
     */
    private final NamespacedKey keyManagedEnchantLore;

    // Bukkit enchantments used ONLY for enchanting table offer display
    private final TableDisplayRegistry tableDisplayRegistry = new TableDisplayRegistry();

    public EnchantManager(AquaEnchatsPlugin plugin, EnchantConfigLoader loader) {
        this.plugin = plugin;
        this.loader = loader;
        this.keyEnchantList = new NamespacedKey(plugin, "custom_enchants");
        this.keyUnrenamableBook = new NamespacedKey(plugin, "unrenamable_book");
        this.keyManagedEnchantLore = new NamespacedKey(plugin, "managed_enchant_lore");
        reload();
    }

    public void reload() {
        enchantments.clear();
        enchantments.putAll(loader.getEnchants());
        groupOrder = loader.getGroupOrder();

        // (Re)register display markers for enchanting table
        try {
            tableDisplayRegistry.all().clear();
            tableDisplayRegistry.registerAll(this);
        } catch (Throwable ignored) {
        }
    }

    public Enchantment getTableDisplayEnchant(String customId) {
        return tableDisplayRegistry.get(customId);
    }

    public CustomEnchant getEnchant(String id) {
        if (id == null) return null;
        return enchantments.get(id.toLowerCase(Locale.ROOT));
    }

    public Collection<CustomEnchant> getAll() {
        return enchantments.values();
    }

    /**
     * Returns parsed applies-to groups for an enchant.
     * If the config did not specify applies-to (null/empty), this returns an empty set.
     */
    public Set<ApplyToGroup> getAppliesToGroups(CustomEnchant enchant) {
        if (enchant == null) return Collections.emptySet();
        String raw = enchant.getAppliesTo();
        if (raw == null) return Collections.emptySet();
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return Collections.emptySet();

        // Allow explicit "Any" or "All" to mean all items.
        if ("ANY".equalsIgnoreCase(trimmed) || "ALL".equalsIgnoreCase(trimmed) || "ALL_ITEMS".equalsIgnoreCase(trimmed)) {
            return EnumSet.allOf(ApplyToGroup.class);
        }

        EnumSet<ApplyToGroup> out = EnumSet.noneOf(ApplyToGroup.class);
        String[] parts = trimmed.split("[;,]");
        if (parts.length == 1) {
            // If no separators were present, try whitespace-separated too.
            parts = trimmed.split("\\s+");
        }
        for (String p : parts) {
            if (p == null) continue;
            String token = p.trim();
            if (token.isEmpty()) continue;
            ApplyToGroup g = ApplyToGroup.fromString(token);
            if (g != null) out.add(g);
        }
        return out;
    }

    /**
     * Strict applicability rule: enchant can be applied only if it is allowed by both
     * applies-to (high-level groups) and applies (specific categories).
     * If applies-to is empty/missing, enchant cannot be applied at all.
     */
    public boolean canApply(CustomEnchant enchant, ItemStack target) {
        if (enchant == null || target == null) return false;

        // IMPORTANT: treat broad wildcards (ALL_ITEMS / ALL_TOOLS / TOOLS) as fallback only.
        // If the config lists a specific family (e.g. ALL_PICKAXE), we must NOT allow the
        // enchant to spill onto other tools due to an accidentally present broad token.
        // This is exactly the issue users see with "indikator" being applicable to axes/shovels/hoes.

        // 1) applies-to must be specified (non-empty) and must match
        Set<ApplyToGroup> groups = sanitizeAppliesTo(getAppliesToGroups(enchant));
        if (groups.isEmpty()) return false;
        boolean groupOk = false;
        for (ApplyToGroup g : groups) {
            if (g != null && g.matches(target)) {
                groupOk = true;
                break;
            }
        }
        if (!groupOk) return false;

        // 2) applies must be specified and must match
        Set<ToolCategory> cats = sanitizeApplies(enchant.getApplies());
        if (cats == null || cats.isEmpty()) return false;
        for (ToolCategory c : cats) {
            if (c != null && c.matches(target)) return true;
        }
        return false;
    }

    /**
     * If a config contains a specific allow-list (e.g. ALL_PICKAXE) together with a broad
     * wildcard (ALL_ITEMS / ALL_TOOLS), the specific list must win.
     */
    private Set<ToolCategory> sanitizeApplies(Set<ToolCategory> raw) {
        if (raw == null || raw.isEmpty()) return raw;
        // Copy to be safe (CustomEnchant stores an unmodifiable set)
        EnumSet<ToolCategory> out = EnumSet.noneOf(ToolCategory.class);
        out.addAll(raw);

        boolean hasSpecific = false;
        for (ToolCategory c : out) {
            if (c == null) continue;
            if (c != ToolCategory.ALL_ITEMS && c != ToolCategory.ALL_TOOLS) {
                hasSpecific = true;
                break;
            }
        }

        if (hasSpecific) {
            out.remove(ToolCategory.ALL_ITEMS);
            out.remove(ToolCategory.ALL_TOOLS);
        }
        return out;
    }

    /**
     * If applies-to contains both a broad group (TOOLS/ARMOR) and a specific family (PICKAXES/etc),
     * the specific family must win.
     */
    private Set<ApplyToGroup> sanitizeAppliesTo(Set<ApplyToGroup> raw) {
        if (raw == null || raw.isEmpty()) return raw;
        EnumSet<ApplyToGroup> out = EnumSet.noneOf(ApplyToGroup.class);
        out.addAll(raw);

        boolean hasSpecific = false;
        for (ApplyToGroup g : out) {
            if (g == null) continue;
            if (g != ApplyToGroup.TOOLS && g != ApplyToGroup.ARMOR) {
                hasSpecific = true;
                break;
            }
        }
        if (hasSpecific) {
            out.remove(ApplyToGroup.TOOLS);
            out.remove(ApplyToGroup.ARMOR);
        }
        return out;
    }

    public ItemStack createEnchantBook(CustomEnchant enchant, int level, int amount) {
        ItemStack book = new ItemStack(org.bukkit.Material.ENCHANTED_BOOK, amount);
        EnchantmentStorageMeta meta = (EnchantmentStorageMeta) book.getItemMeta();
        if (meta == null) return book;

        // В некоторых сборках/плагинах при копировании ItemMeta на книгу могут
        // оставаться "stored" зачарования, что приводит к дублированию названия
        // в подсказке (отдельной строкой списка зачарований).
        if (!meta.getStoredEnchants().isEmpty()) {
            for (Enchantment e : new java.util.HashSet<>(meta.getStoredEnchants().keySet())) {
                meta.removeStoredEnchant(e);
            }
        }

        String levelRoman = toRoman(level);
        // Используем отображаемое имя зачарования как есть (с градацией цвета),
        // только уровень делаем серым.
        meta.setDisplayName(enchant.getDisplayName() + " " + ChatColor.GRAY + levelRoman);

        List<String> lore = new ArrayList<>();
        if (enchant.getDescription() != null && !enchant.getDescription().isEmpty()) {
            lore.add(ChatColor.GRAY + enchant.getDescription());
        }
        lore.add("");
        lore.add(ChatColor.DARK_GRAY + "Тип: " + ChatColor.YELLOW + enchant.getType().name());
        lore.add(ChatColor.DARK_GRAY + "Группа: " + ChatColor.YELLOW + enchant.getGroup());
        meta.setLore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);

        // Store data in PDC
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String value = enchant.getId() + ":" + level;
        pdc.set(keyEnchantList, PersistentDataType.STRING, value);

        // Mark as unrenamable in an anvil
        pdc.set(keyUnrenamableBook, PersistentDataType.BYTE, (byte) 1);

        book.setItemMeta(meta);
        return book;
    }

    /**
     * Returns true if the item is a plugin-generated enchanted book that must not be renamed.
     */
    public boolean isUnrenamableBook(ItemStack item) {
        if (item == null) return false;
        if (item.getType() != Material.ENCHANTED_BOOK) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        Byte b = meta.getPersistentDataContainer().get(keyUnrenamableBook, PersistentDataType.BYTE);
        return b != null && b == (byte) 1;
    }

    /**
     * Marks an item (usually an enchanted book) as unrenamable in an anvil.
     */
    public void markUnrenamableBook(ItemStack item) {
        if (item == null) return;
        if (item.getType() != Material.ENCHANTED_BOOK) return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        meta.getPersistentDataContainer().set(keyUnrenamableBook, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
    }

    /**
     * Команды типа /enchant и некоторые плагины могут добавлять ванильные зачарования
     * напрямую в ItemMeta, минуя события на которые мы реагируем. Из-за этого, если
     * на предмете есть кастомные зачарования AquaEnchants и включено скрытие ванильных
     * энчантов, игрок может не видеть добавленное зачарование до тех пор, пока предмет
     * не попадёт, например, в наковальню.
     *
     * Этот метод безопасно пере-собирает лор для предмета, если на нём есть кастомные
     * энчанты (данные в PDC).
     */
    public void refreshLoreIfCustom(ItemStack item) {
        if (item == null || item.getType().isAir()) return;
        Map<CustomEnchant, Integer> custom = getEnchantmentsOnItem(item);
        if (custom == null || custom.isEmpty()) return;
        setEnchantmentsOnItem(item, custom);
    }

    /**
     * Пересобирает лор, если предмет "под управлением" AquaEnchants или если
     * в нём есть ванильные/кастомные зачарования.
     *
     * Нужен для случаев, когда игроки заходят с разных клиентов (1.20.1 / 1.21+),
     * и у них в подсказке остаются старые строки зачарований.
     */
    public void refreshLoreIfNeeded(ItemStack item) {
        if (item == null || item.getType().isAir()) return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();

        boolean managed = pdc.get(keyManagedEnchantLore, PersistentDataType.BYTE) != null;
        boolean hasVanilla = !(meta instanceof EnchantmentStorageMeta) && meta.hasEnchants();

        boolean hasEnchantLikeLore = false;
        if (meta.hasLore()) {
            List<String> lore = meta.getLore();
            if (lore != null) {
                for (String line : lore) {
                    if (isCustomEnchantLoreLine(line) || isVanillaEnchantLoreLine(line)) {
                        hasEnchantLikeLore = true;
                        break;
                    }
                }
            }
        }

        Map<CustomEnchant, Integer> custom = getEnchantmentsOnItem(item);
        boolean hasCustom = custom != null && !custom.isEmpty();

        if (managed || hasVanilla || hasCustom || hasEnchantLikeLore) {
            setEnchantmentsOnItem(item, custom);
        }
    }

    public Map<CustomEnchant, Integer> getEnchantmentsOnItem(ItemStack item) {
        Map<CustomEnchant, Integer> result = new HashMap<>();
        if (item == null) return result;

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return result;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String value = pdc.get(keyEnchantList, PersistentDataType.STRING);
        if (value == null || value.isEmpty()) return result;

        String[] parts = value.split(";");
        for (String p : parts) {
            if (p.isEmpty()) continue;
            String[] kv = p.split(":");
            if (kv.length != 2) continue;
            String id = kv[0];
            int level;
            try {
                level = Integer.parseInt(kv[1]);
            } catch (NumberFormatException ex) {
                continue;
            }
            CustomEnchant ench = getEnchant(id);
            if (ench != null) {
                result.put(ench, level);
            }
        }
        return result;
    }

    public void setEnchantmentsOnItem(ItemStack item, Map<CustomEnchant, Integer> enchants) {
        if (item == null) return;

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        // Удаляем "маркерные" зачарования AquaEnchants (Enchantment с namespace нашего плагина),
        // которые иногда остаются после GUI/офферов и на разных клиентах могут отображаться как
        // "лишние" зачарования.
        if (meta.hasEnchants()) {
            String selfNs = plugin.getName() == null ? "" : plugin.getName().toLowerCase(Locale.ROOT);
            for (Enchantment e : new HashSet<>(meta.getEnchants().keySet())) {
                if (e == null || e.getKey() == null) continue;
                String ns = e.getKey().getNamespace();
                if (ns == null) continue;
                String lns = ns.toLowerCase(Locale.ROOT);
                if (lns.equals(selfNs) || lns.equals("aquaenchats") || lns.equals("aquaenchants")) {
                    meta.removeEnchant(e);
                }
            }
        }

        /*
         * IMPORTANT (bugfix):
         * We use LUCK_OF_THE_SEA as a "fake" enchantment only to force the
         * vanilla enchanting glint on items that have *custom* enchants.
         *
         * When a player removes all custom enchants (and there are no real
         * vanilla enchants left), that fake enchant must be removed.
         *
         * Real LUCK_OF_THE_SEA is applicable only to fishing rods, so if an
         * item cannot be enchanted with it (canEnchantItem=false) we treat it
         * as our fake marker and clean it up whenever there are no custom
         * enchants.
         */
        boolean hasCustomInput = enchants != null && !enchants.isEmpty();
        if (!hasCustomInput && meta.hasEnchant(Enchantment.LUCK_OF_THE_SEA) && !Enchantment.LUCK_OF_THE_SEA.canEnchantItem(item)) {
            meta.removeEnchant(Enchantment.LUCK_OF_THE_SEA);
        }

        // --- Сериализуем кастомные зачарования в PDC ---
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (enchants == null || enchants.isEmpty()) {
            pdc.remove(keyEnchantList);
        } else {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<CustomEnchant, Integer> e : enchants.entrySet()) {
                CustomEnchant ench = e.getKey();
                Integer lvlObj = e.getValue();
                if (ench == null || lvlObj == null || lvlObj <= 0) continue;
                // Не даём записывать кастомные чары на неподходящие предметы (кроме книг).
                if (item.getType() != Material.BOOK && item.getType() != Material.ENCHANTED_BOOK) {
                    if (!canApply(ench, item)) continue;
                }
                if (sb.length() > 0) sb.append(";");
                sb.append(ench.getId()).append(":").append(lvlObj);
            }
            if (sb.length() == 0) {
                pdc.remove(keyEnchantList);
            } else {
                pdc.set(keyEnchantList, PersistentDataType.STRING, sb.toString());
            }
        }

        // --- Базовый лор (сохраняем не-enchant строки, чтобы не ломать другие плагины) ---
        // Важно: строки зачарований вырезаем только если мы уверены, что это именно
        // наш "управляемый" блок (или если на предмете реально есть зачарования).
        boolean managed = pdc.get(keyManagedEnchantLore, PersistentDataType.BYTE) != null;
        boolean hasRealVanillaNow = (!(meta instanceof EnchantmentStorageMeta) && meta.hasEnchants());

        List<String> baseLore = new ArrayList<>();
        if (meta.hasLore()) {
            List<String> current = meta.getLore();
            if (current != null) {
                for (String line : current) {
                    if (line == null) continue;
                    if ((managed || hasRealVanillaNow || hasCustomInput)
                            && (isCustomEnchantLoreLine(line) || isVanillaEnchantLoreLine(line))) {
                        continue;
                    }
                    baseLore.add(line);
                }
            }
        }

        // BUGFIX (book lore runaway / tooltip grows):
        // When we rebuild lore we insert a separator empty line between the enchant block and the
        // "base" lore. On the next refresh tick that separator becomes part of baseLore (because
        // it's not an enchant-like line) and we add yet another separator again -> empty lines
        // accumulate forever, making the tooltip taller and pushing text down.
        //
        // If we're in a mode where we manage enchant display (managed/has enchants), strip leading
        // empty lines from baseLore and collapse repeated empty lines.
        if (managed || hasRealVanillaNow || hasCustomInput) {
            while (!baseLore.isEmpty() && ChatColor.stripColor(baseLore.get(0)).trim().isEmpty()) {
                baseLore.remove(0);
            }

            List<String> collapsed = new ArrayList<>(baseLore.size());
            boolean prevEmpty = false;
            for (String line : baseLore) {
                if (line == null) continue;
                boolean empty = ChatColor.stripColor(line).trim().isEmpty();
                if (empty && prevEmpty) continue;
                collapsed.add(line);
                prevEmpty = empty;
            }
            baseLore = collapsed;
        }

        // --- Перестраиваем лор ---
        List<String> newLore = new ArrayList<>();

        // Special case: plugin-generated enchanted books.
        // Such books already have the enchant name+level in the display name.
        // If we also render custom enchant lines in lore, the enchant appears twice.
        // For these books we keep the display name and only keep the descriptive lore.
        boolean isPluginEnchantBook = item.getType() == Material.ENCHANTED_BOOK
                && pdc.get(keyEnchantList, PersistentDataType.STRING) != null
                && isUnrenamableBook(item);

        // 1) Кастомные зачарования.
        //    Порядок жёстко задан:
        //    UNIKAL  -> сверху,
        //    LEGENDA -> ниже,
        //    остальные кастомные группы (если появятся) -> ещё ниже.
        List<Map.Entry<CustomEnchant, Integer>> unikal = new ArrayList<>();
        List<Map.Entry<CustomEnchant, Integer>> legenda = new ArrayList<>();
        List<Map.Entry<CustomEnchant, Integer>> other   = new ArrayList<>();

        if (enchants != null && !enchants.isEmpty()) {
            for (Map.Entry<CustomEnchant, Integer> e : enchants.entrySet()) {
                CustomEnchant ench = e.getKey();
                Integer lvlObj = e.getValue();
                if (ench == null || lvlObj == null || lvlObj <= 0) continue;

                String group = ench.getGroup() == null ? "" : ench.getGroup().toUpperCase(java.util.Locale.ROOT);
                if ("UNIKAL".equals(group)) {
                    unikal.add(e);
                } else if ("LEGENDA".equals(group)) {
                    legenda.add(e);
                } else {
                    other.add(e);
                }
            }
        }

        java.util.function.Consumer<java.util.List<Map.Entry<CustomEnchant, Integer>>> appendGroup =
                list -> {
                    // Для стабильности сортируем внутри группы по имени дисплея.
                    list.sort(Comparator.comparing(e -> {
                        CustomEnchant ench = e.getKey();
                        String name = ench.getDisplayName();
                        return name == null ? "" : name;
                    }, String.CASE_INSENSITIVE_ORDER));

                    for (Map.Entry<CustomEnchant, Integer> e : list) {
                        CustomEnchant ench = e.getKey();
                        int level = e.getValue();
                        String display = ench.getDisplayName();
                        String animatedName = me.aquaenchants.util.LoreAnimationManager.formatAnimatedOriginal(display, me.aquaenchants.util.LoreAnimationManager.getGlobalTick());
                        String levelRoman = toRoman(level);
                        String lastColor = ChatColor.getLastColors(animatedName);
                        if (lastColor == null || lastColor.isEmpty()) lastColor = ChatColor.GRAY.toString();
                        // Показываем переливающееся имя зачарования с его родными цветами.
                        newLore.add(animatedName + " " + lastColor + levelRoman);
                    }
                };

        if (!isPluginEnchantBook) {
            appendGroup.accept(unikal);
            appendGroup.accept(legenda);
            appendGroup.accept(other);
        }

        // --- Важная логика отображения ванильных чар ---
        // LUCK_OF_THE_SEA мы используем как "фейковое" зачарование только ради блеска
        // (когда на предмете есть только кастомные зачарования).
        // Если на предмете присутствуют реальные ванильные зачарования, "фейковый" LUCK
        // нужно удалить, иначе он начинает светиться в лоре/подсказке (и выглядит как бред).
        boolean hasCustom = enchants != null && !enchants.isEmpty();
        boolean isBook = meta instanceof org.bukkit.inventory.meta.EnchantmentStorageMeta;

        // Determine whether the item already has any *real* vanilla enchants.
        // IMPORTANT: this must NOT depend on ItemFlags.
        // In an anvil preview the base item may have vanilla enchants but no HIDE_ENCHANTS yet,
        // and we still must treat that as "real vanilla" to avoid adding our fake marker enchant.
        boolean hasRealVanilla = false;
        if (!(meta instanceof EnchantmentStorageMeta) && meta.hasEnchants()) {
            for (var e : meta.getEnchants().keySet()) {
                if (e != null && e != org.bukkit.enchantments.Enchantment.LUCK_OF_THE_SEA) {
                    hasRealVanilla = true;
                    break;
                }
            }
        }
        if (!hasRealVanilla && isBook) {
            var esm = (org.bukkit.inventory.meta.EnchantmentStorageMeta) meta;
            // Stored enchants on books are always "real".
            hasRealVanilla = esm.hasStoredEnchants();
        }

        // Мы ВСЕГДА контролируем отображение зачарований через lore.
        // Это важно для поддержки разных клиентов (например, 1.20.1), где
        // отображение "ванильного списка" и ItemFlags может отличаться.
        boolean shouldHideVanillaList = !(meta instanceof EnchantmentStorageMeta) && (meta.hasEnchants() || hasCustom);

        boolean useFakeLuck = hasCustom && !hasRealVanilla;
        // Фейковый LUCK (только ради блеска) допустим ТОЛЬКО если нет реальных ванильных чар.
        if (!useFakeLuck && meta.hasEnchant(org.bukkit.enchantments.Enchantment.LUCK_OF_THE_SEA)
                && !org.bukkit.enchantments.Enchantment.LUCK_OF_THE_SEA.canEnchantItem(item)) {
            meta.removeEnchant(org.bukkit.enchantments.Enchantment.LUCK_OF_THE_SEA);
        }

        if (useFakeLuck) {
            // Ensure our fake marker is always exactly level 1.
            // Vanilla anvil logic can accidentally upgrade it (1+1 -> 2) when combining two items.
            if (!org.bukkit.enchantments.Enchantment.LUCK_OF_THE_SEA.canEnchantItem(item)) {
                int current = meta.getEnchantLevel(org.bukkit.enchantments.Enchantment.LUCK_OF_THE_SEA);
                if (current != 1) {
                    meta.removeEnchant(org.bukkit.enchantments.Enchantment.LUCK_OF_THE_SEA);
                }
                if (!meta.hasEnchant(org.bukkit.enchantments.Enchantment.LUCK_OF_THE_SEA)) {
                    meta.addEnchant(org.bukkit.enchantments.Enchantment.LUCK_OF_THE_SEA, 1, true);
                }
            }
        }

        if (shouldHideVanillaList) {
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
            pdc.set(keyManagedEnchantLore, PersistentDataType.BYTE, (byte) 1);
        } else {
            // Если на предмете нет ни ванильных, ни кастомных чар — и флаг был поставлен нами,
            // убираем его, чтобы не ломать отображение в других местах.
			Byte managedFlag = pdc.get(keyManagedEnchantLore, PersistentDataType.BYTE);
			if (managedFlag != null && managedFlag == (byte) 1) {
                meta.removeItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
                pdc.remove(keyManagedEnchantLore);
            }
        }

        // 2) Ванильные зачарования = группа defoult, всегда в самом низу.
        //    defoult в config.yml трактуем как "ванильные".
        //
        // Ванильные зачарования всегда выводим в lore серым (&7), чтобы:
        // 1) они были видны на старых клиентах (1.20.1) независимо от ...
        // 2) цвет был одинаковым и контролируемым.
        if (!(meta instanceof EnchantmentStorageMeta) && meta.hasEnchants()) {
            Map<Enchantment, Integer> vanilla = meta.getEnchants();
            for (Map.Entry<Enchantment, Integer> ve : vanilla.entrySet()) {
                Enchantment ench = ve.getKey();
                int lvl = ve.getValue();

                // Для предметов с кастомными зачарованиями мы используем LUCK_OF_THE_SEA
                // как "фейковое" зачарование только ради визуального эффекта (блеск).
                // Его не нужно выводить в описании предмета, чтобы игроки не видели
                // лишнюю строку "Luck Of The Sea".
                if (ench == Enchantment.LUCK_OF_THE_SEA && !Enchantment.LUCK_OF_THE_SEA.canEnchantItem(item)) {
                    continue;
                }

                String name = getVanillaEnchantName(ench);
                if (name == null || name.isEmpty()) continue;

                String suffix = "";
                if (ench.getMaxLevel() > 1 || lvl > 1) {
                    suffix = " " + toRoman(lvl);
                }

                newLore.add(ChatColor.GRAY + name + suffix);
            }
        }

        // В конец возвращаем "прочий" лор, который не является зачарованиями.
        if (!baseLore.isEmpty()) {
            if (!newLore.isEmpty()) {
                // Разделяем, если пользовательский лор есть.
                if (!newLore.get(newLore.size() - 1).isEmpty()) newLore.add("");
            }
            newLore.addAll(baseLore);
        }

        // 3) Применяем лор, если он действительно изменился
        if (newLore.isEmpty()) {
            meta.setLore(null);
        } else {
            List<String> currentLore = meta.hasLore() ? meta.getLore() : null;
            if (!(currentLore != null && currentLore.equals(newLore))) {
                meta.setLore(newLore);
            }
        }

        item.setItemMeta(meta);
    }

    /**
     * Принудительно пересобирает лор для предмета, если на нём есть кастомные энчанты AquaEnchants.
     * Нужно, чтобы ванильные зачарования, добавленные командой (/enchant) или сторонними плагинами,
     * сразу становились видимыми (мы прячем ванильный список через ItemFlag.HIDE_ENCHANTS).
     */
    public void refreshLoreIfHasCustomEnchants(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return;
        Map<CustomEnchant, Integer> custom = getEnchantmentsOnItem(item);
        if (custom == null || custom.isEmpty()) return;
        setEnchantmentsOnItem(item, custom);
    }

    private boolean isCustomEnchantLoreLine(String line) {
        if (line == null) return false;
        // IMPORTANT (bugfix):
        // Old lore lines may contain gradients/hex colors (\u00a7x...), and the previous
        // implementation compared them against the raw displayName (with colors).
        // As a result, we failed to recognize our own previous enchant lines and
        // kept appending new ones every refresh tick -> tooltip "grows" infinitely.
        String strippedLine = ChatColor.stripColor(line);
        if (strippedLine == null) return false;
        strippedLine = strippedLine.trim();
        // Remove trailing roman numeral level (" V", " III" ...), keep only the name.
        String nameOnly = strippedLine.replaceAll("\\s+[IVXLCDM]+\\s*$", "").trim();

        // Generic fallback (very important for legacy/stale items):
        // If an enchant was removed/renamed in config, we still must be able to
        // recognize and clean the old lore line. Most enchant lines look like:
        //   "Some Name V"  (no ':'), with a roman numeral suffix.
        // We treat such lines as enchant-like even if we can't match them to a
        // currently loaded CustomEnchant.
        if (strippedLine.indexOf(':') < 0
                && strippedLine.matches("(?iu)^[\\p{L}0-9 _\\-]{2,64}\\s+[IVXLCDM]+$")
                && !strippedLine.toLowerCase(java.util.Locale.ROOT).startsWith("когда ")) {
            return true;
        }

        for (CustomEnchant ench : enchantments.values()) {
            if (ench == null) continue;
            String display = ench.getDisplayName();
            if (display == null) continue;
            String strippedDisplay = ChatColor.stripColor(display);
            if (strippedDisplay == null) continue;
            strippedDisplay = strippedDisplay.trim();
            if (strippedDisplay.isEmpty()) continue;

            // Match by exact name (case-insensitive) after stripping colors.
            if (nameOnly.equalsIgnoreCase(strippedDisplay)) {
                return true;
            }

            // Some display names can contain extra symbols or spacing; be a bit lenient.
            if (nameOnly.toLowerCase(java.util.Locale.ROOT)
                    .startsWith(strippedDisplay.toLowerCase(java.util.Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean isVanillaEnchantLoreLine(String line) {
        if (line == null) return false;
        String stripped = ChatColor.stripColor(line).trim();
        for (Enchantment ench : Enchantment.values()) {
            String name = getVanillaEnchantName(ench);
            if (name == null || name.isEmpty()) continue;
            if (stripped.toLowerCase(java.util.Locale.ROOT).startsWith(name.toLowerCase(java.util.Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }


    
    /**
     * Русское имя ванильного зачарования по его ключу.
     *
     * В некоторых интерфейсах (например, торги жителей/кастомные GUI) мы выводим
     * список зачарований как обычный текст, поэтому делаем перевод на стороне сервера.
     */
    public String getVanillaEnchantName(Enchantment ench) {
        if (ench == null) return "";
        String key = ench.getKey().getKey().toLowerCase(Locale.ROOT);
        switch (key) {
            // Универсальные (броня)
            case "protection":
                return "Защита";
            case "fire_protection":
                return "Огнестойкость";
            case "feather_falling":
                return "Невесомость";
            case "blast_protection":
                return "Защита от взрывов";
            case "projectile_protection":
                return "Защита от снарядов";
            case "respiration":
                return "Подводное дыхание";
            case "aqua_affinity":
                return "Подводник";
            case "thorns":
                return "Шипы";
            case "depth_strider":
                return "Подводная ходьба";
            case "frost_walker":
                return "Ледоход";
            case "binding_curse":
                return "Проклятие несъёмности";
            case "vanishing_curse":
                return "Проклятие утраты";
            case "soul_speed":
                return "Скорость души";
            case "swift_sneak":
                return "Проворство";

            // Оружие
            case "sharpness":
                return "Острота";
            case "smite":
                return "Небесная кара";
            case "bane_of_arthropods":
                return "Бич членистоногих";
            case "knockback":
                return "Отдача";
            case "fire_aspect":
                return "Заговор огня";
            case "looting":
                return "Добыча";
            case "sweeping":
            case "sweeping_edge":
                return "Разящий клинок";

            // Лук / арбалет / трезубец / инструмент
            case "power":
                return "Сила";
            case "punch":
                return "Откидывание";
            case "flame":
                return "Воспламенение";
            case "infinity":
                return "Бесконечность";
            case "multishot":
                return "Тройной выстрел";
            case "piercing":
                return "Пронзающая стрела";
            case "quick_charge":
                return "Быстрая перезарядка";
            case "channeling":
                return "Громовержец";
            case "loyalty":
                return "Верность";
            case "riptide":
                return "Тягун";
            case "impaling":
                return "Пронзание";

            // Булава (1.21+)
            case "density":
                return "Плотность";
            case "breach":
                return "Пробой";
            case "wind_burst":
                return "Порыв ветра";

            // Починка / прочее
            case "vanishing":
                // На случай нестандартных ключей в сборках
                return "Проклятие утраты";

            // Рыбалка
            case "luck_of_the_sea":
                return "Удача моря";
            case "lure":
                return "Приманка";

            // Инструменты
            case "efficiency":
                return "Эффективность";
            case "fortune":
                return "Удача";
            case "silk_touch":
                return "Шёлковое касание";
            case "unbreaking":
                return "Прочность";
            case "mending":
                return "Починка";
            default:
                // Если встретится неизвестный ключ (датапак/мод), лучше показать нейтральную русскую строку,
                // чем английское имя.
                return "Неизвестное зачарование";
        }
    }

    private int getGroupPriority(String group) {
        if (group == null) return Integer.MAX_VALUE;
        int idx = groupOrder.indexOf(group);
        if (idx == -1) return Integer.MAX_VALUE - 1;
        return idx;
    }

    private String toRoman(int number) {
        switch (number) {
            case 1: return "I";
            case 2: return "II";
            case 3: return "III";
            case 4: return "IV";
            case 5: return "V";
            default: return String.valueOf(number);
        }
    }

    public void giveBookToPlayer(Player player, CustomEnchant enchant, int level, int amount) {
        ItemStack book = createEnchantBook(enchant, level, amount);
        HashMap<Integer, ItemStack> left = player.getInventory().addItem(book);
        for (ItemStack it : left.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), it);
        }
    }

    public boolean saveEnchantTableChance(String enchantId, int newChance) {
        if (enchantId == null) return false;
        CustomEnchant ce = getEnchant(enchantId);
        if (ce != null) {
            ce.setEnchantTableChance(newChance);
        }

        try {
            java.io.File file = new java.io.File(plugin.getDataFolder(), "enachants.yml");
            if (!file.exists()) return false;
            org.bukkit.configuration.file.FileConfiguration cfg = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);

            String idLower = enchantId.toLowerCase(Locale.ROOT);
            if (cfg.isConfigurationSection("enchantments." + idLower)) {
                if (cfg.isConfigurationSection("enchantments." + idLower + ".enchanttable")) {
                    cfg.set("enchantments." + idLower + ".enchanttable.chance", newChance);
                } else {
                    cfg.set("enchantments." + idLower + ".chans", newChance);
                }
            } else if (cfg.isConfigurationSection(idLower)) {
                if (cfg.isConfigurationSection(idLower + ".enchanttable")) {
                    cfg.set(idLower + ".enchanttable.chance", newChance);
                } else {
                    cfg.set(idLower + ".chans", newChance);
                }
            } else {
                cfg.set(idLower + ".chans", newChance);
            }

            cfg.save(file);
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to save enchant table chance for " + enchantId + ": " + e.getMessage());
            return false;
        }
    }
}