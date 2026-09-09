package me.aquaenchants.enchant;

import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Holds references to the (Paper-registered) table-display enchantments.
 *
 * IMPORTANT:
 * On Paper 1.21.8 we register real enchantments in {@code AquaEnchatsBootstrap} during the
 * registry compose phase (before freeze). This is what makes them visible in the vanilla
 * enchanting table UI.
 *
 * This class only resolves and stores the canonical registry instances.
 */
public final class TableDisplayRegistry {

    private final Map<String, Enchantment> byId = new HashMap<>();

    public Enchantment get(String customId) {
        return byId.get(customId);
    }

    public Map<String, Enchantment> all() {
        return byId;
    }

    public void registerAll(EnchantManager manager) {
        byId.clear();
        for (CustomEnchant ce : manager.getAll()) {
            if (ce == null) continue;
            String id = ce.getId();
            if (id == null || id.isBlank()) continue;

            NamespacedKey key = new NamespacedKey("aquaenchants", sanitizeKey(id));
            Enchantment marker = Enchantment.getByKey(key);
            if (marker != null) {
                byId.put(id, marker);
            }
        }
    }

    private static String sanitizeKey(String id) {
        String s = id.toLowerCase(Locale.ROOT).replace(' ', '_');
        s = s.replaceAll("[^a-z0-9_./-]", "_");
        return s;
    }
}
