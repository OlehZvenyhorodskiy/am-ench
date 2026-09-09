package me.aquaenchants.listener;

import me.aquaenchants.AquaEnchatsPlugin;
import me.aquaenchants.enchant.CustomEnchant;
import me.aquaenchants.enchant.EnchantManager;
import me.aquaenchants.hook.CmiEnchantLimits;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.HashMap;
import java.util.Map;
import me.aquaenchants.util.ShieldPatternUtil;

public class AnvilListener implements Listener {

    private final EnchantManager enchantManager;
    private final CmiEnchantLimits cmiEnchantLimits;

    public AnvilListener(EnchantManager enchantManager) {
        this.enchantManager = enchantManager;
        this.cmiEnchantLimits = AquaEnchatsPlugin.getInstance() != null
                ? AquaEnchatsPlugin.getInstance().getCmiEnchantLimits()
                : null;
    }

    @EventHandler
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        AnvilInventory inv = event.getInventory();
        ItemStack left = inv.getItem(0);
        ItemStack right = inv.getItem(1);

        if (left == null || right == null) {
            return;
        }

        // --- Shield hard-rule ---
        // Only custom enchants that explicitly declare SHIELD in their "applies" list
        // may be applied to a shield via anvil.
        // Vanilla enchanted books (Protection, Unbreaking, etc.) must NOT be applicable
        // to shields on this server (per your requirement).
        //
        // If the left item is a SHIELD and the right item is an enchanted book that
        // does NOT contain our custom enchants, we block the anvil result completely
        // (red X), so vanilla enchants cannot be added to shields.
        if (left.getType() == Material.SHIELD && right.getType() == Material.ENCHANTED_BOOK) {
            Map<CustomEnchant, Integer> rightCustom = enchantManager.getEnchantmentsOnItem(right);
            if ((rightCustom == null || rightCustom.isEmpty()) && looksLikeCustomEnchantBook(right)) {
                rightCustom = tryParseCustomEnchantsFromBookLore(right);
            }
            if (rightCustom == null || rightCustom.isEmpty()) {
                event.setResult(null);
                inv.setRepairCost(0);
                return;
            }
        }

        // --- Unsafe vanilla enchant levels (e.g. Efficiency 10) ---
        // Vanilla anvil clamps enchant levels to Enchantment#getMaxLevel().
        // On many servers admins/CMI give books with levels above max (Efficiency 10, etc.).
        // Those must transfer through the anvil without being downgraded.
        //
        // We do this *before* any custom-enchant logic and we do it even if the inputs
        // don't contain our custom enchants.
        applyUnsafeVanillaEnchantMerge(event, left, right);

        // Глобальное правило несовместимости:
        // кастомное зачарование "smelting" не может находиться на одном предмете
        // вместе с ванильным Silk Touch.
        if (isSmeltingSilkTouchIncompatible(left, right)) {
            event.setResult(null);
            inv.setRepairCost(0);
            return;
        }

        // Собираем кастомные зачарования на левом и правом предметах
        Map<CustomEnchant, Integer> leftEnchants = enchantManager.getEnchantmentsOnItem(left);
        Map<CustomEnchant, Integer> rightEnchants = enchantManager.getEnchantmentsOnItem(right);

        // Special case: enchanted book without PDC (legacy/other source) but still looks like our custom book.
        if ((rightEnchants == null || rightEnchants.isEmpty()) && looksLikeCustomEnchantBook(right)) {
            rightEnchants = tryParseCustomEnchantsFromBookLore(right);
        }

        boolean anyCustomInInputs = (leftEnchants != null && !leftEnchants.isEmpty())
                || (rightEnchants != null && !rightEnchants.isEmpty());
        if (!anyCustomInInputs) {
            // Ни одного нашего зачарования нет — оставляем ванильное поведение.
            return;
        }

        Map<CustomEnchant, Integer> resultEnchants = new HashMap<>(leftEnchants == null ? Map.of() : leftEnchants);
        boolean changed = false;

        // Правило объединения уровней:
        // - если на обоих предметах один и тот же кастомный энчант с одинаковым уровнем,
        //   уровни складываются (1+1=2, 2+2=3, ...), но не выше максимального, заданного в конфиге;
        // - если уровни разные, берётся больший.
        // If the right item is a book with our custom enchants, we must ensure at least one of them can be applied;
        // otherwise the anvil must show a red X (no result at all).
        boolean rightIsBook = right.getType() == Material.ENCHANTED_BOOK;
        if (rightIsBook && rightEnchants != null && !rightEnchants.isEmpty()) {
            boolean anyApplicable = false;
            for (CustomEnchant e : rightEnchants.keySet()) {
                if (e != null && enchantManager.canApply(e, left)) {
                    anyApplicable = true;
                    break;
                }
            }
            if (!anyApplicable) {
                event.setResult(null);
                inv.setRepairCost(0);
                return;
            }
        }

        for (Map.Entry<CustomEnchant, Integer> entry : (rightEnchants == null ? Map.<CustomEnchant, Integer>of() : rightEnchants).entrySet()) {
            CustomEnchant enchant = entry.getKey();
            int rightLevel = entry.getValue();
            if (enchant == null || rightLevel <= 0) continue;

            // Strict applicability: must satisfy applies-to AND applies.
            // If an enchant from the right item cannot be applied to the left item, skip it.
            if (!enchantManager.canApply(enchant, left)) {
                continue;
            }

            Integer current = resultEnchants.get(enchant);
            int targetLevel = rightLevel;

            if (current != null && current > 0) {
                if (current == rightLevel) {
                    int next = current + 1;
                    if (enchant.getLevel(next) != null) {
                        targetLevel = next;
                    } else {
                        targetLevel = current;
                    }
                } else {
                    targetLevel = Math.max(current, rightLevel);
                }
            }

            if (current == null || targetLevel != current) {
                resultEnchants.put(enchant, targetLevel);
                changed = true;
            }
        }

        if (!changed && (resultEnchants == null || resultEnchants.isEmpty())) {
            // Нечего накладывать — оставляем ванильный результат.
            return;
        }

        // Берём базовый результат, который уже посчитала сама Minecraft
        // (ванильные зачарования, стоимость и т.п.)
        ItemStack baseResult = event.getResult();
        ItemStack result;

        if (baseResult != null && baseResult.getType() != Material.AIR) {
            result = baseResult.clone();
        } else {
            // Если ванильного результата нет (часто из‑за конфликтов ванильных энчантов),
            // мы всё равно должны уметь применять ванильные книги поверх предмета,
            // если операция содержит наши кастомные зачарования.
            // Иначе игрок получает ситуацию: "книга + предмет" не даёт результат,
            // и вместе с этим пропадает возможность добавить ванильный энчант.
            result = left.clone();
            // Пытаемся вручную применить ванильные зачарования с правого предмета
            // (в т.ч. ENCHANTED_BOOK) без проверки конфликтов.
            applyVanillaEnchantsFromRight(result, right);
        }

        // Before writing, filter any remaining enchants that cannot apply to the final result item.
        // This also protects rename/repair scenarios.
        Map<CustomEnchant, Integer> filtered = new HashMap<>();
        for (Map.Entry<CustomEnchant, Integer> e : resultEnchants.entrySet()) {
            if (e.getKey() != null && e.getValue() != null && e.getValue() > 0 && enchantManager.canApply(e.getKey(), result)) {
                filtered.put(e.getKey(), e.getValue());
            }
        }

        // If the operation involved our custom enchantments but none survive for the result, block the anvil result.
        if (filtered.isEmpty() && rightIsBook && rightEnchants != null && !rightEnchants.isEmpty()) {
            event.setResult(null);
            inv.setRepairCost(0);
            return;
        }

        // Накладываем поверх наши кастомные зачарования
        enchantManager.setEnchantmentsOnItem(result, filtered);
        // If the SHIELD custom enchant is present on the resulting shield, apply the custom banner pattern.
        if (result.getType() == Material.SHIELD) {
            for (CustomEnchant ce : filtered.keySet()) {
                if (ce != null && "shield".equalsIgnoreCase(ce.getId())) {
                    ShieldPatternUtil.applyTulovikPattern(result);
                    break;
                }
            }
        }

        // Устанавливаем результат в наковальне
        event.setResult(result);

        // Небольшая стоимость (можно донастроить)
        inv.setRepairCost(1);
    }

    /**
     * Применяет ванильные зачарования из правого предмета на целевой предмет.
     *
     * Важно: в режиме, когда участвуют кастомные зачарования, ванильный результат
     * может быть {@code null} (красный X) из‑за конфликтов (например, Ледоход vs Подводная ходьба).
     * Мы специально не блокируем такие комбинации и применяем энчанты "unsafe".
     */
    private void applyVanillaEnchantsFromRight(ItemStack target, ItemStack right) {
        if (target == null || right == null) return;

        ItemMeta targetMeta = target.getItemMeta();
        if (targetMeta == null) return;

        // 1) Книга
        if (right.getType() == Material.ENCHANTED_BOOK) {
            ItemMeta rightMeta = right.getItemMeta();
            if (rightMeta instanceof EnchantmentStorageMeta storage) {
                for (Map.Entry<Enchantment, Integer> e : storage.getStoredEnchants().entrySet()) {
                    Enchantment ench = e.getKey();
                    int lvl = e.getValue() == null ? 0 : e.getValue();
                    if (ench == null || lvl <= 0) continue;
                    int existing = targetMeta.getEnchantLevel(ench);

                    // Respect server limits for *upgrading* levels (3+3 -> 4),
                    // but if an admin already has a higher-level book (e.g. Fortune IV),
                    // allow transferring that exact level through the anvil.
                    int maxAllowed = Math.max(1, cmiEnchantLimits.getMaxLevel(ench));
                    int leftLevel = existing;
                    int rightLevel = lvl;

                    int out;
                    if (leftLevel > 0) {
                        if (leftLevel == rightLevel) {
                            // Only allow +1 upgrade inside the configured limit.
                            out = (leftLevel < maxAllowed) ? (leftLevel + 1) : leftLevel;
                        } else {
                            out = Math.max(leftLevel, rightLevel);
                        }
                    } else {
                        out = rightLevel;
                    }

                    // Never clamp down books that are already above the allowed max.
                    // But also never let the anvil create levels above the highest input.
                    int cap = Math.max(maxAllowed, Math.max(leftLevel, rightLevel));
                    out = Math.min(out, cap);

                    targetMeta.addEnchant(ench, out, true);
                }
                target.setItemMeta(targetMeta);
                return;
            }
        }

        // 2) Обычный предмет с энчантами
        if (right.hasItemMeta() && right.getItemMeta() != null && right.getItemMeta().hasEnchants()) {
            for (Map.Entry<Enchantment, Integer> e : right.getEnchantments().entrySet()) {
                Enchantment ench = e.getKey();
                int lvl = e.getValue() == null ? 0 : e.getValue();
                if (ench == null || lvl <= 0) continue;
                int existing = targetMeta.getEnchantLevel(ench);

                int maxAllowed = Math.max(1, cmiEnchantLimits.getMaxLevel(ench));
                int leftLevel = existing;
                int rightLevel = lvl;

                int out;
                if (leftLevel > 0) {
                    if (leftLevel == rightLevel) {
                        out = (leftLevel < maxAllowed) ? (leftLevel + 1) : leftLevel;
                    } else {
                        out = Math.max(leftLevel, rightLevel);
                    }
                } else {
                    out = rightLevel;
                }

                int cap = Math.max(maxAllowed, Math.max(leftLevel, rightLevel));
                out = Math.min(out, cap);

                targetMeta.addEnchant(ench, out, true);
            }
            target.setItemMeta(targetMeta);
        }
    }

    /**
     * Ensures vanilla anvil merge respects vanilla max levels.
     *
     * Previously the plugin forced "unsafe" merging via {@code addEnchant(..., true)},
     * which allowed raising levels (e.g. Sharpness) infinitely by repeatedly combining
     * two equal-level items in an anvil.
     */
    private void applyUnsafeVanillaEnchantMerge(PrepareAnvilEvent event, ItemStack left, ItemStack right) {
        ItemStack baseResult = event.getResult();
        if (baseResult == null || baseResult.getType() == Material.AIR) return;

        // Collect vanilla enchants from inputs.
        Map<Enchantment, Integer> leftVanilla = getAllVanillaEnchants(left);
        Map<Enchantment, Integer> rightVanilla = getAllVanillaEnchants(right);

        if ((leftVanilla == null || leftVanilla.isEmpty()) && (rightVanilla == null || rightVanilla.isEmpty())) {
            return;
        }

        // Compute merged levels.
        // We respect configured server limits for *upgrading* levels (3+3 -> 4),
        // but if an item/book already has a higher level (e.g. Fortune IV given by OP),
        // we allow transferring that exact level through the anvil.
        Map<Enchantment, Integer> merged = new HashMap<>();
        if (leftVanilla != null) merged.putAll(leftVanilla);

        if (rightVanilla != null) {
            for (Map.Entry<Enchantment, Integer> e : rightVanilla.entrySet()) {
                Enchantment ench = e.getKey();
                Integer lvlObj = e.getValue();
                if (ench == null || lvlObj == null || lvlObj <= 0) continue;

                // Only apply enchants that can go onto the result item (or store in book).
                if (baseResult.getType() != Material.ENCHANTED_BOOK && !ench.canEnchantItem(baseResult)) {
                    continue;
                }

                int maxAllowed = Math.max(1, cmiEnchantLimits.getMaxLevel(ench));
                int rightLevel = (lvlObj == null ? 0 : lvlObj);
                int leftLevel = merged.getOrDefault(ench, 0);

                int target;
                if (leftLevel > 0) {
                    if (leftLevel == rightLevel) {
                        // Only allow +1 upgrade inside the configured limit.
                        target = (leftLevel < maxAllowed) ? (leftLevel + 1) : leftLevel;
                    } else {
                        target = Math.max(leftLevel, rightLevel);
                    }
                } else {
                    target = rightLevel;
                }

                // Never clamp down items/books already above the allowed max,
                // but also never let the anvil create levels above the highest input.
                int cap = Math.max(maxAllowed, Math.max(leftLevel, rightLevel));
                target = Math.min(target, cap);

                if (target > merged.getOrDefault(ench, 0)) {
                    merged.put(ench, target);
                }
            }
        }

        // Only touch result if we would increase something compared to the vanilla result.
        boolean needsUpdate = false;
        ItemMeta baseMeta = baseResult.getItemMeta();
        Map<Enchantment, Integer> baseEnchants = (baseMeta != null) ? baseMeta.getEnchants() : Map.of();
        for (Map.Entry<Enchantment, Integer> e : merged.entrySet()) {
            Enchantment ench = e.getKey();
            int lvl = e.getValue();
            int current = baseEnchants.getOrDefault(ench, 0);

            // For our custom-glint marker: never auto-increase Luck of the Sea.
            if (ench == Enchantment.LUCK_OF_THE_SEA && !ench.canEnchantItem(baseResult)) {
                continue;
            }

            if (lvl > current) {
                needsUpdate = true;
                break;
            }
        }
        if (!needsUpdate) return;

        ItemStack result = baseResult.clone();
        if (result.getType() == Material.ENCHANTED_BOOK) {
            ItemMeta m = result.getItemMeta();
            if (!(m instanceof EnchantmentStorageMeta storage)) {
                return;
            }
            for (Map.Entry<Enchantment, Integer> e : merged.entrySet()) {
                Enchantment ench = e.getKey();
                Integer lvlObj = e.getValue();
                if (ench == null || lvlObj == null || lvlObj <= 0) continue;

                int maxAllowed = Math.max(1, cmiEnchantLimits.getMaxLevel(ench));
                int leftLevel = 0;
                int rightLevel = (lvlObj == null ? 0 : lvlObj);
                int cap = Math.max(maxAllowed, Math.max(leftLevel, rightLevel));
                int safeLvl = Math.min(rightLevel, cap);
                int current = storage.getStoredEnchants().getOrDefault(ench, 0);
                if (safeLvl > current) {
                    storage.removeStoredEnchant(ench);
                    storage.addStoredEnchant(ench, safeLvl, false);
                }
            }
            result.setItemMeta(storage);
        } else {
            ItemMeta m = result.getItemMeta();
            if (m == null) return;
            for (Map.Entry<Enchantment, Integer> e : merged.entrySet()) {
                Enchantment ench = e.getKey();
                Integer lvlObj = e.getValue();
                if (ench == null || lvlObj == null || lvlObj <= 0) continue;
                if (!ench.canEnchantItem(result)) continue;

                int maxAllowed = Math.max(1, cmiEnchantLimits.getMaxLevel(ench));
                int leftLevel = m.getEnchantLevel(ench);
                int rightLevel = (lvlObj == null ? 0 : lvlObj);
                int cap = Math.max(maxAllowed, Math.max(leftLevel, rightLevel));
                int safeLvl = Math.min(rightLevel, cap);
                int current = m.getEnchantLevel(ench);
                if (safeLvl > current) {
                    m.removeEnchant(ench);
                    // allow levels above vanilla max, but still capped by CMI limits
                    m.addEnchant(ench, safeLvl, true);
                }
            }
            result.setItemMeta(m);
        }

        event.setResult(result);
    }

    /**
     * Reads all vanilla enchantments from an item, including stored enchants on enchanted books.
     */
    private Map<Enchantment, Integer> getAllVanillaEnchants(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return Map.of();

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return Map.of();

        Map<Enchantment, Integer> out = new HashMap<>();
        if (meta.hasEnchants()) {
            for (Map.Entry<Enchantment, Integer> e : meta.getEnchants().entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                if (e.getValue() <= 0) continue;
                out.put(e.getKey(), e.getValue());
            }
        }
        if (meta instanceof EnchantmentStorageMeta storage) {
            for (Map.Entry<Enchantment, Integer> e : storage.getStoredEnchants().entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                if (e.getValue() <= 0) continue;
                out.put(e.getKey(), Math.max(out.getOrDefault(e.getKey(), 0), e.getValue()));
            }
        }
        return out;
    }

    /**
     * Проверка глобальной несовместимости: кастомный smelting + Silk Touch.
     * Если на любом из предметов/книг есть smelting, а на любом — Silk Touch,
     * объединять их в наковальне нельзя.
     */
    private boolean isSmeltingSilkTouchIncompatible(ItemStack left, ItemStack right) {
        boolean hasSmelt =
                hasCustomSmelting(left) || hasCustomSmelting(right);
        boolean hasSilk =
                hasSilkTouch(left) || hasSilkTouch(right);
        return hasSmelt && hasSilk;
    }

    private boolean hasCustomSmelting(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return false;
        Map<CustomEnchant, Integer> enchants = enchantManager.getEnchantmentsOnItem(item);
        if (enchants == null || enchants.isEmpty()) return false;

        for (Map.Entry<CustomEnchant, Integer> e : enchants.entrySet()) {
            CustomEnchant ench = e.getKey();
            Integer lvl = e.getValue();
            if (ench != null && lvl != null && lvl > 0
                    && "smelting".equalsIgnoreCase(ench.getId())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasSilkTouch(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;

        // Обычные предметы
        if (meta.hasEnchant(Enchantment.SILK_TOUCH)) {
            return true;
        }

        // Книги с зачарованиями
        if (meta instanceof EnchantmentStorageMeta) {
            EnchantmentStorageMeta em = (EnchantmentStorageMeta) meta;
            if (em.hasStoredEnchant(Enchantment.SILK_TOUCH)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Detects whether a book appears to be one of our custom enchant books (by lore markers).
     * Used as a fallback when PDC is missing.
     */
    private boolean looksLikeCustomEnchantBook(ItemStack item) {
        if (item == null || item.getType() != Material.ENCHANTED_BOOK) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        java.util.List<String> lore = meta.getLore();
        if (lore == null || lore.isEmpty()) return false;
        for (String line : lore) {
            if (line == null) continue;
            String plain = org.bukkit.ChatColor.stripColor(line);
            if (plain == null) continue;
            plain = plain.toLowerCase(java.util.Locale.ROOT);
            if (plain.contains("тип:") || plain.contains("группа:")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Best-effort parsing of a custom enchant from a book display name.
     * This supports legacy books where PDC was not present.
     */
    private Map<CustomEnchant, Integer> tryParseCustomEnchantsFromBookLore(ItemStack item) {
        Map<CustomEnchant, Integer> out = new HashMap<>();
        if (item == null || item.getType() != Material.ENCHANTED_BOOK) return out;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return out;
        String disp = meta.getDisplayName();
        if (disp == null || disp.isEmpty()) return out;
        String plain = org.bukkit.ChatColor.stripColor(disp);
        if (plain == null) return out;
        plain = plain.trim();

        // Try to match by display name prefix (without the roman level).
        // Example: "ГРОМО...ВОД III" -> match enchant display and parse level.
        int level = parseTrailingRomanLevel(plain);
        String baseName = plain;
        if (level > 0) {
            baseName = plain.replaceAll("\\s+[IVXLCDM]+$", "").trim();
        }

        for (CustomEnchant ench : enchantManager.getAll()) {
            if (ench == null) continue;
            String ed = ench.getDisplayName();
            if (ed == null) continue;
            String enchPlain = org.bukkit.ChatColor.stripColor(ed);
            if (enchPlain == null) continue;
            enchPlain = enchPlain.trim();
            if (!enchPlain.isEmpty() && enchPlain.equalsIgnoreCase(baseName)) {
                out.put(ench, Math.max(1, level));
                break;
            }
        }
        return out;
    }

    private int parseTrailingRomanLevel(String plainDisplay) {
        if (plainDisplay == null) return -1;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\s+([IVXLCDM]+)$", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(plainDisplay.trim());
        if (!m.find()) return -1;
        String roman = m.group(1);
        return romanToInt(roman);
    }

    private int romanToInt(String roman) {
        if (roman == null) return -1;
        String s = roman.toUpperCase(java.util.Locale.ROOT);
        java.util.Map<Character, Integer> map = java.util.Map.of(
                'I', 1,
                'V', 5,
                'X', 10,
                'L', 50,
                'C', 100,
                'D', 500,
                'M', 1000
        );
        int total = 0;
        int prev = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            Integer val = map.get(s.charAt(i));
            if (val == null) return -1;
            if (val < prev) total -= val; else total += val;
            prev = val;
        }
        return total;
    }
}