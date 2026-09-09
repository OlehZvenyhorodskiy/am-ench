package me.aquaenchants.listener;

import me.aquaenchants.enchant.CustomEnchant;
import me.aquaenchants.enchant.EnchantManager;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.AbstractVillager;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.VillagerAcquireTradeEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.ChatColor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Adds custom enchant books (level 1 only) to librarian trades WITHOUT removing vanilla trades.
 * when enchant config has:
 *   enchantvilager: true
 *
 * Currency is Echo Shards (ECHO_SHARD) instead of emeralds.
 *
 * IMPORTANT: keeps the SAME chance logic as before (percent roll 1..100 <= chans).
 */
public final class VillagerTradeListener implements Listener {

    private final Plugin plugin;
    private final EnchantManager enchantManager;
    private final NamespacedKey keyCustomEnchant;
    private final NamespacedKey keyVillagerOffered;

    public VillagerTradeListener(Plugin plugin, EnchantManager enchantManager) {
        this.plugin = plugin;
        this.enchantManager = enchantManager;
        this.keyCustomEnchant = new NamespacedKey(plugin, "custom_enchants");
        this.keyVillagerOffered = new NamespacedKey(plugin, "villager_custom_offers");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAcquireTrade(VillagerAcquireTradeEvent event) {
        AbstractVillager av = event.getEntity();
        if (!(av instanceof Villager villager)) return;

        if (villager.getProfession() != Villager.Profession.LIBRARIAN) return;

        // IMPORTANT:
        // Some server builds / datapacks / mods change when librarians acquire enchanted-book trades.
        // If we only hook vanilla ENCHANTED_BOOK trades, custom enchants may "never appear".
        // So we *inject* our custom enchanted-book trade on ANY newly acquired librarian trade.
        // This keeps chance logic intact and makes feature reliable.
        MerchantRecipe original = event.getRecipe();
        if (original == null) return;

        // --- FIX: русификация ванильных книг зачарований в торгах библиотекаря ---
        // На некоторых сборках/клиентах часть ванильных зачарований может отображаться на английском.
        // Сервер не может заменить клиентские lang-файлы, поэтому делаем отображение через lore:
        // 1) скрываем стандартный вывод StoredEnchantments (чтобы не было ДУБЛЯ строк);
        // 2) пишем список зачарований в lore на русском.
        // Функционал книги не меняется (StoredEnchantments остаются на месте).
        original = fixVanillaEnchantedBookRecipe(original, true);
        if (original != null) event.setRecipe(original);

        // Build candidate list using the SAME percent chance rule as enchanting table:
        // roll 1..100 <= chans
        List<CustomEnchant> candidates = new ArrayList<>();
        for (CustomEnchant ench : enchantManager.getAll()) {
            if (!ench.isVillagerEnabled()) continue;
            // В торгах должны попадать только зачарования из группы LEGENDA
            if (ench.getGroup() == null || !ench.getGroup().equalsIgnoreCase("LEGENDA")) continue;
            // Не допускаем дубликаты: если это зачарование уже было предложено этим жителем,
            // больше его не добавляем (и это также убирает гонку, когда event ...
            if (villagerAlreadyOffered(villager, ench.getId())) continue;
            if (hasCustomEnchantTrade(villager, ench.getId())) continue;
            if (ench.getLevels() == null || !ench.getLevels().containsKey(1)) continue;

            int chance = Math.max(0, ench.getVillagerChance()); // from 'chans'
            if (chance <= 0) continue;

            int roll = ThreadLocalRandom.current().nextInt(100) + 1; // 1..100
            if (roll <= chance) candidates.add(ench);
        }

        if (candidates.isEmpty()) return;

        CustomEnchant picked = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        ItemStack result = enchantManager.createEnchantBook(picked, 1, 1);

        // Keep vanilla-ish properties (uses/xp/price), but swap currency to Echo Shards
        int cost = 24;
        int maxUses = original.getMaxUses();
        int villagerXp = original.getVillagerExperience();
        float priceMultiplier = original.getPriceMultiplier();

        // Try to preserve the original "price" amount if the trade had a currency-like first ingredient.
        // Otherwise, keep default 24.
        List<ItemStack> ing = original.getIngredients();
        if (!ing.isEmpty()) {
            ItemStack first = ing.get(0);
            if (first != null && first.getAmount() > 0) {
                cost = first.getAmount();
            }
        }

        MerchantRecipe recipe = new MerchantRecipe(result, maxUses);
        recipe.setVillagerExperience(villagerXp);
        recipe.setPriceMultiplier(priceMultiplier);

        // The trade is: Echo Shards + Book -> Custom Enchanted Book
        recipe.addIngredient(new ItemStack(Material.ECHO_SHARD, cost));
        recipe.addIngredient(new ItemStack(Material.BOOK, 1));

        // Reserve this enchant ID on the villager immediately to avoid duplicated offers when
        // VillagerAcquireTradeEvent fires multiple times in quick succession.
        if (villagerAlreadyOffered(villager, picked.getId())) {
            return;
        }
        markVillagerOffered(villager, picked.getId());

        // IMPORTANT: do NOT replace vanilla trade. Add our custom trade alongside.
        // AcquireTrade fires while the villager is mutating its recipe list, so we schedule 1 tick later.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                // Prevent duplicates of the same custom enchant per villager.
                if (hasCustomEnchantTrade(villager, picked.getId())) return;

                List<MerchantRecipe> recipes = new ArrayList<>(villager.getRecipes());
                recipes.add(recipe);
                villager.setRecipes(recipes);
            } catch (Throwable ignored) {
            }
        });
    }

    /**
     * Дополнительно русифицируем уже существующие торги у жителя при открытии торговли.
     * Это нужно, потому что VillagerAcquireTradeEvent срабатывает только при получении новых трейдов,
     * а старые (созданные до установки/обновления плагина) останутся с английскими строками.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Villager villager)) return;
        if (villager.getProfession() != Villager.Profession.LIBRARIAN) return;

        try {
            List<MerchantRecipe> src = villager.getRecipes();
            if (src == null || src.isEmpty()) return;

            boolean changed = false;
            List<MerchantRecipe> out = new ArrayList<>(src.size());
            for (MerchantRecipe r : src) {
                MerchantRecipe fixed = fixVanillaEnchantedBookRecipe(r, false);
                if (fixed != r) changed = true;
                out.add(fixed);
            }
            if (changed) villager.setRecipes(out);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Русификация результата рецепта, если это ванильная книга зачарований.
     *
     * @param original    исходный рецепт
     * @param cloneAlways если true — всегда возвращаем НОВЫЙ объект рецепта (для event.setRecipe)
     */
    private MerchantRecipe fixVanillaEnchantedBookRecipe(MerchantRecipe original, boolean cloneAlways) {
        if (original == null) return null;
        try {
            final ItemStack res = original.getResult();
            if (res == null || res.getType() != Material.ENCHANTED_BOOK) return original;

            final ItemMeta meta = res.getItemMeta();
            if (!(meta instanceof EnchantmentStorageMeta storage)) return original;
            if (storage.getStoredEnchants().isEmpty()) return original;

            final ItemStack fixed = res.clone();
            final EnchantmentStorageMeta newMeta = (EnchantmentStorageMeta) fixed.getItemMeta();
            if (newMeta == null) return original;

            final List<String> lore = new ArrayList<>();
            for (var e : newMeta.getStoredEnchants().entrySet()) {
                Enchantment ench = e.getKey();
                int lvl = e.getValue() == null ? 1 : e.getValue();
                String name = enchantManager.getVanillaEnchantName(ench);
                if (name == null || name.isBlank()) name = "Неизвестное зачарование";
                lore.add(ChatColor.GRAY + name + " " + roman(lvl));
            }
            newMeta.setLore(lore);

            // Скрываем стандартный вывод зачарований (иначе будут дубли/английский текст).
            addItemFlagIfExists(newMeta, "HIDE_STORED_ENCHANTS");
            addItemFlagIfExists(newMeta, "HIDE_ENCHANTS");
            addItemFlagIfExists(newMeta, "HIDE_ADDITIONAL_TOOLTIP");

            fixed.setItemMeta(newMeta);

            // Если ничего не меняли, можно вернуть исходный рецепт.
            // Но в AcquireTradeEvent лучше всегда возвращать новый объект.
            if (!cloneAlways && fixed.isSimilar(res)) return original;

            return cloneRecipeWithNewResult(original, fixed);
        } catch (Throwable ignored) {
            return original;
        }
    }

    private boolean hasCustomEnchantTrade(Villager villager, String enchantId) {
        if (villager == null || enchantId == null) return false;
        for (MerchantRecipe r : villager.getRecipes()) {
            if (r == null) continue;
            ItemStack res = r.getResult();
            if (res == null) continue;
            String value = readCustomEnchantValue(res);
            if (value == null || value.isEmpty()) continue;
            // value format: id:level or multiple: id:lvl;id:lvl
            // We only ever create single-enchant books, but be safe.
            for (String part : value.split(";")) {
                if (part == null || part.isEmpty()) continue;
                int idx = part.indexOf(':');
                String id = idx >= 0 ? part.substring(0, idx) : part;
                if (enchantId.equalsIgnoreCase(id)) return true;
            }
        }
        return false;
    }

    private String readCustomEnchantValue(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        return pdc.get(keyCustomEnchant, PersistentDataType.STRING);
    }

    private boolean villagerAlreadyOffered(Villager villager, String enchantId) {
        if (villager == null || enchantId == null || enchantId.isEmpty()) return true;
        PersistentDataContainer pdc = villager.getPersistentDataContainer();
        String raw = pdc.get(keyVillagerOffered, PersistentDataType.STRING);
        if (raw == null || raw.isEmpty()) return false;
        for (String part : raw.split(";")) {
            if (enchantId.equalsIgnoreCase(part)) return true;
        }
        return false;
    }

    private void markVillagerOffered(Villager villager, String enchantId) {
        if (villager == null || enchantId == null || enchantId.isEmpty()) return;
        PersistentDataContainer pdc = villager.getPersistentDataContainer();
        String raw = pdc.get(keyVillagerOffered, PersistentDataType.STRING);
        if (raw == null || raw.isEmpty()) {
            pdc.set(keyVillagerOffered, PersistentDataType.STRING, enchantId);
            return;
        }
        // Avoid duplicates in the stored list.
        for (String part : raw.split(";")) {
            if (enchantId.equalsIgnoreCase(part)) return;
        }
        pdc.set(keyVillagerOffered, PersistentDataType.STRING, raw + ";" + enchantId);
    }

    /**
     * На разных версиях Bukkit/Paper набор ItemFlag отличается.
     * Чтобы проект компилировался и на API, где нет некоторых флагов (например HIDE_STORED_ENCHANTS),
     * добавляем их безопасно через valueOf.
     */
    private static void addItemFlagIfExists(ItemMeta meta, String flagName) {
        if (meta == null || flagName == null || flagName.isEmpty()) return;
        try {
            ItemFlag flag = ItemFlag.valueOf(flagName);
            meta.addItemFlags(flag);
        } catch (Throwable ignored) {
            // flag отсутствует в данной версии API
        }
    }

    private String roman(int n) {
        return switch (n) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            case 6 -> "VI";
            case 7 -> "VII";
            case 8 -> "VIII";
            case 9 -> "IX";
            case 10 -> "X";
            default -> String.valueOf(n);
        };
    }

    /**
     * Создаёт полную копию рецепта с заменой результата.
     *
     * Важно: MerchantRecipe в разных версиях Bukkit/Paper имеет разные конструкторы,
     * поэтому стараемся использовать максимально совместимые методы.
     */
    private MerchantRecipe cloneRecipeWithNewResult(MerchantRecipe original, ItemStack newResult) {
        MerchantRecipe recipe = new MerchantRecipe(newResult, original.getMaxUses());

        recipe.setVillagerExperience(original.getVillagerExperience());
        recipe.setPriceMultiplier(original.getPriceMultiplier());
        recipe.setExperienceReward(original.hasExperienceReward());
        recipe.setUses(original.getUses());

        // Копируем ингредиенты
        for (ItemStack ing : original.getIngredients()) {
            if (ing == null) continue;
            recipe.addIngredient(ing.clone());
        }
        return recipe;
    }
}
