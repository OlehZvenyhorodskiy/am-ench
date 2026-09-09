package me.aquaenchants.listener;

import me.aquaenchants.AquaEnchatsPlugin;
import me.aquaenchants.enchant.CustomEnchant;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;
import java.util.Locale;

/**
 * Fix for old clients (e.g. 1.20.1 via ViaVersion) where anvil PREVIEW shows only the first vanilla enchant.
 *
 * Key goals:
 *  - Show ALL vanilla enchants in preview (including from right slot)
 *  - No "double" lines (vanilla tooltip + our lore)
 *  - When player takes the result, they receive the REAL server item (with real enchants), not the preview dummy.
 *
 * Approach:
 *  1) Cache the real result per player on PrepareAnvilEvent.
 *  2) Replace preview with a DISPLAY item:
 *     - same type/meta, but REMOVE all enchants from meta (so vanilla tooltip lines disappear)
 *     - add full enchant list to lore (only once)
 *     - mark with PDC.
 *  3) On click of result slot, swap inventory slot #2 back to real item BEFORE Bukkit processes the click.
 */
public final class AnvilPreviewCompatListener implements Listener {

    private final AquaEnchatsPlugin plugin;
    private final NamespacedKey markerKey;

    // cache real results per player UUID
    private final Map<UUID, ItemStack> realResult = new HashMap<>();

    public AnvilPreviewCompatListener(AquaEnchatsPlugin plugin) {
        this.plugin = plugin;
        this.markerKey = new NamespacedKey(plugin, "anvil_preview_dummy");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onPrepare(PrepareAnvilEvent event) {
        AnvilInventory inv = event.getInventory();
        ItemStack result = event.getResult();
        if (result == null || result.getType() == Material.AIR) return;

        // Viewer (anvil is per-player in practice; take first)
        Player viewer = null;
        for (HumanEntity he : inv.getViewers()) {
            if (he instanceof Player p) { viewer = p; break; }
        }
        if (viewer == null) return;

        // Cache real result
        realResult.put(viewer.getUniqueId(), result.clone());

        // Build dummy preview
        ItemStack dummy = buildDummyPreview(result);
        event.setResult(dummy);

        // Force resend slot 2 next tick (helps 1.20.1 clients update preview)
        Player finalViewer = viewer;
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                inv.setItem(2, dummy);
                finalViewer.updateInventory();
            } catch (Throwable ignored) {}
        });
    }

    private ItemStack buildDummyPreview(ItemStack real) {
        ItemStack dummy = real.clone();
        ItemMeta meta = dummy.getItemMeta();
        if (meta == null) return dummy;

        Map<Enchantment, Integer> enchants = new LinkedHashMap<>();

        if (meta instanceof EnchantmentStorageMeta storage) {
            enchants.putAll(storage.getStoredEnchants());
            for (Enchantment e : new ArrayList<>(storage.getStoredEnchants().keySet())) {
                try { storage.removeStoredEnchant(e); } catch (Throwable ignored) {}
            }
            meta = storage;
        }

        if (meta.hasEnchants()) {
            enchants.putAll(meta.getEnchants());
            for (Enchantment e : new ArrayList<>(meta.getEnchants().keySet())) {
                try { meta.removeEnchant(e); } catch (Throwable ignored) {}
            }
        }

        if (!enchants.isEmpty()) {
            List<String> lore = meta.hasLore() && meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();

            // Remove previous injected block (if any)
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            Byte marked = pdc.get(markerKey, PersistentDataType.BYTE);
            if (marked != null && marked == (byte) 1) {
                int removed = 0;
                while (!lore.isEmpty() && removed < 80) {
                    String line = lore.remove(0);
                    removed++;
                    if (line == null) continue;
                    if (line.isEmpty()) break;
                }
                pdc.remove(markerKey);
            }

            // We keep ONLY non-enchant lines as base lore.
            // Enchant lines inside lore can be stale/duplicated (especially after older preview fixes),
            // and vanilla anvil merge logic is already applied in the RESULT item by Bukkit.
            // So we rebuild enchant blocks ONLY from the real RESULT data:
            //  - custom enchants from PDC
            //  - vanilla enchants from ItemMeta.getEnchants()
            List<String> baseLore = new ArrayList<>();
            for (String line : lore) {
                if (line == null) continue;

                // Order matters: our generic custom-line detector can match vanilla lines like
                // "Добыча III". Always strip vanilla first.
                if (isVanillaEnchantLoreLine(line)) continue;
                if (isCustomEnchantLoreLine(line)) continue;

                baseLore.add(line);
            }

            // Trim leading empty lines from base lore (often left after we removed enchant blocks)
            while (!baseLore.isEmpty() && (baseLore.get(0) == null || baseLore.get(0).isEmpty())) {
                baseLore.remove(0);
            }

            // Build custom preview block from REAL custom enchants (from PDC), not from lore.
            List<String> customLines = new ArrayList<>();
            try {
                Map<CustomEnchant, Integer> custom = plugin.getEnchantManager().getEnchantmentsOnItem(real);
                if (custom != null && !custom.isEmpty()) {
                    for (Map.Entry<CustomEnchant, Integer> ce : custom.entrySet()) {
                        CustomEnchant c = ce.getKey();
                        int lvl = ce.getValue() == null ? 0 : ce.getValue();
                        if (c == null || lvl <= 0) continue;
                        String dn = c.getDisplayName();
                        if (dn == null) dn = "";
                        customLines.add(dn + " " + ChatColor.GRAY + toRoman(lvl));
                    }
                }
            } catch (Throwable ignored) {}

            // Build vanilla preview block from REAL enchants (from meta), not from lore.
            List<String> vanillaLines = new ArrayList<>();
            for (Map.Entry<Enchantment, Integer> e : enchants.entrySet()) {
                Enchantment ench = e.getKey();
                int lvl = e.getValue() == null ? 0 : e.getValue();
                if (ench == null || lvl <= 0) continue;

                // We use LUCK_OF_THE_SEA as a *fake* marker enchant only to force the glint.
                // It must NEVER be shown in anvil preview. Real LUCK is applicable only to fishing rods.
                if (ench == Enchantment.LUCK_OF_THE_SEA && !ench.canEnchantItem(real)) {
                    continue;
                }
                vanillaLines.add("§7" + russianName(ench) + (" " + toRoman(lvl)));
            }

            // De-duplicate while keeping order (very important when lore already had lines).
            List<String> cleanCustom = dedupByStripped(customLines);
            List<String> cleanVanilla = dedupByStripped(vanillaLines);

            List<String> newLore = new ArrayList<>(cleanCustom.size() + cleanVanilla.size() + 1 + baseLore.size());
            newLore.addAll(cleanCustom);
            newLore.addAll(cleanVanilla);

            if (!baseLore.isEmpty()) {
                if (!newLore.isEmpty() && !newLore.get(newLore.size() - 1).isEmpty()) newLore.add("");
                newLore.addAll(baseLore);
            }

            if (newLore.isEmpty()) {
                meta.setLore(null);
            } else {
                meta.setLore(newLore);
            }
            meta.getPersistentDataContainer().set(markerKey, PersistentDataType.BYTE, (byte) 1);
        }

        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        dummy.setItemMeta(meta);
        return dummy;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onTakeResult(InventoryClickEvent event) {
        Inventory inv = event.getInventory();
        if (!(inv instanceof AnvilInventory)) return;

        // result slot in anvil is raw slot 2 inside the top inventory
        if (event.getRawSlot() != 2) return;

        if (!(event.getWhoClicked() instanceof Player player)) return;

        ItemStack current = event.getCurrentItem();
        if (current == null || current.getType() == Material.AIR) return;

        ItemMeta meta = current.getItemMeta();
        if (meta == null) return;

        Byte marked = meta.getPersistentDataContainer().get(markerKey, PersistentDataType.BYTE);
        if (marked == null || marked != (byte) 1) return;

        ItemStack real = realResult.get(player.getUniqueId());
        if (real == null || real.getType() == Material.AIR) return;

        // Swap slot to real BEFORE click is applied
        inv.setItem(2, real);
        event.setCurrentItem(real);

        // Clear cache after take attempt
        Bukkit.getScheduler().runTask(plugin, () -> realResult.remove(player.getUniqueId()));
    }

    
    private List<String> dedupByStripped(List<String> lines) {
        if (lines == null || lines.isEmpty()) return Collections.emptyList();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        for (String l : lines) {
            if (l == null) continue;
            String key = ChatColor.stripColor(l);
            if (key == null) key = l;
            key = key.trim();
            if (seen.add(key.toLowerCase(Locale.ROOT))) {
                out.add(l);
            }
        }
        return out;
    }

    private boolean isVanillaEnchantLoreLine(String line) {
        if (line == null) return false;
        String stripped = ChatColor.stripColor(line);
        if (stripped == null) return false;
        stripped = stripped.trim();

        // Use the same translations as the plugin (most reliable)
        try {
            for (Enchantment ench : Enchantment.values()) {
                String name = plugin.getEnchantManager().getVanillaEnchantName(ench);
                if (name == null || name.isEmpty()) continue;
                if (stripped.toLowerCase(Locale.ROOT).startsWith(name.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}

        return false;
    }

    private boolean isCustomEnchantLoreLine(String line) {
        if (line == null) return false;

        String strippedLine = ChatColor.stripColor(line);
        if (strippedLine == null) return false;
        strippedLine = strippedLine.trim();

        // Remove trailing roman numeral level (" V", " III" ...), keep only the name.
        String nameOnly = strippedLine.replaceAll("\\s+[IVXLCDM]+\\s*$", "").trim();

        // Generic fallback: treat "Name V" (no ':') as enchant-like (helps clean stale items)
        if (strippedLine.indexOf(':') < 0
                && strippedLine.matches("(?iu)^[\\p{L}0-9 _\\-]{2,64}\\s+[IVXLCDM]+$")
                && !strippedLine.toLowerCase(Locale.ROOT).startsWith("когда ")) {
            return true;
        }

        try {
            for (CustomEnchant ench : plugin.getEnchantManager().getCustomEnchants()) {
                if (ench == null) continue;
                String display = ench.getDisplayName();
                if (display == null) continue;
                String strippedDisplay = ChatColor.stripColor(display);
                if (strippedDisplay == null) continue;
                strippedDisplay = strippedDisplay.trim();
                if (strippedDisplay.isEmpty()) continue;

                if (nameOnly.equalsIgnoreCase(strippedDisplay)) return true;
                if (nameOnly.toLowerCase(Locale.ROOT).startsWith(strippedDisplay.toLowerCase(Locale.ROOT))) return true;
            }
        } catch (Throwable ignored) {}

        return false;
    }

private String russianName(Enchantment ench) {
        try {
            String n = plugin.getEnchantManager().getVanillaEnchantName(ench);
            if (n != null && !n.isEmpty()) return n;
        } catch (Throwable ignored) {}
        String k = "";
        try { if (ench.getKey() != null) k = ench.getKey().getKey(); } catch (Throwable ignored) {}
        switch (k) {
            case "fire_aspect": return "Заговор огня";
            case "looting": return "Добыча";
            case "sweeping": return "Разящий клинок";
            case "sharpness": return "Острота";
            case "smite": return "Небесная кара";
            case "bane_of_arthropods": return "Бич членистоногих";
            case "knockback": return "Отдача";
            case "unbreaking": return "Прочность";
            case "mending": return "Починка";
            case "protection": return "Защита";
            case "projectile_protection": return "Защита от снарядов";
            case "blast_protection": return "Взрывоустойчивость";
            case "fire_protection": return "Огнеупорность";
            case "thorns": return "Шипы";
            case "efficiency": return "Эффективность";
            case "fortune": return "Удача";
            case "silk_touch": return "Шёлковое касание";
            case "power": return "Сила";
            case "punch": return "Отбрасывание";
            case "flame": return "Горящая стрела";
            case "infinity": return "Бесконечность";
            default:
                return humanize(k.isEmpty() ? "enchantment" : k);
        }
    }

    private String humanize(String key) {
        String s = key.replace('_', ' ').toLowerCase(Locale.ROOT);
        String[] parts = s.split("\\s+");
        StringBuilder b = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            b.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1)).append(' ');
        }
        return b.toString().trim();
    }

    private String toRoman(int number) {
        if (number <= 0) return String.valueOf(number);
        int[] values = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] romans = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder sb = new StringBuilder();
        int n = number;
        for (int i = 0; i < values.length; i++) {
            while (n >= values[i]) {
                n -= values[i];
                sb.append(romans[i]);
            }
        }
        return sb.toString();
    }
}
