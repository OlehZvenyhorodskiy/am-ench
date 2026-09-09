package me.aquaenchants.enchant;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;

/**
 * High-level "applies-to" groups from enachants.yml.
 * These act as an allow-list: if an enchant does not list any applies-to groups,
 * it cannot be applied at all.
 */
public enum ApplyToGroup {
    ARMOR,
    SWORDS,
    AXES,
    MACE,
    TRIDENT,
    TOOLS,
    BOW,
    CROSSBOW,
    SHIELD,
    BOOTS,
    ELYTRA,
    HELMET,
    HOES,
    PICKAXES,
    SHOVELS;

    public static ApplyToGroup fromString(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        // Common singular/plural/aliases
        if ("ARMOR".equals(s)) return ARMOR;
        if ("SWORD".equals(s) || "SWORDS".equals(s)) return SWORDS;
        if ("AXE".equals(s) || "AXES".equals(s)) return AXES;
        if ("MACE".equals(s)) return MACE;
        if ("TRIDENT".equals(s) || "TRIDENTS".equals(s)) return TRIDENT;
        if ("TOOL".equals(s) || "TOOLS".equals(s)) return TOOLS;
        if ("BOW".equals(s) || "BOWS".equals(s)) return BOW;
        if ("CROSSBOW".equals(s) || "CROSSBOWS".equals(s)) return CROSSBOW;
        if ("SHIELD".equals(s) || "SHIELDS".equals(s)) return SHIELD;
        if ("BOOT".equals(s) || "BOOTS".equals(s)) return BOOTS;
        if ("ELYTRA".equals(s)) return ELYTRA;
        if ("HELMET".equals(s) || "HELMETS".equals(s)) return HELMET;
        if ("HOE".equals(s) || "HOES".equals(s)) return HOES;
        if ("PICKAXE".equals(s) || "PICKAXES".equals(s)) return PICKAXES;
        if ("SHOVEL".equals(s) || "SHOVELS".equals(s) || "SPADE".equals(s) || "SPADES".equals(s)) return SHOVELS;
        return null;
    }

    public boolean matches(ItemStack item) {
        if (item == null) return false;
        Material mat = item.getType();
        String n = mat.name();

        return switch (this) {
            case ARMOR -> n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE") || n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS");
            case SWORDS -> n.endsWith("_SWORD");
            case AXES -> n.endsWith("_AXE") && !n.endsWith("_PICKAXE");
            case MACE -> mat == Material.MACE;
            case TRIDENT -> mat == Material.TRIDENT;
            case TOOLS -> n.endsWith("_PICKAXE") || n.endsWith("_SHOVEL") || n.endsWith("_AXE") || n.endsWith("_HOE");
            case BOW -> mat == Material.BOW;
            case CROSSBOW -> mat == Material.CROSSBOW;
            case SHIELD -> mat == Material.SHIELD;
            case BOOTS -> n.endsWith("_BOOTS");
            case ELYTRA -> mat == Material.ELYTRA;
            case HELMET -> n.endsWith("_HELMET");
            case HOES -> n.endsWith("_HOE");
            case PICKAXES -> n.endsWith("_PICKAXE");
            case SHOVELS -> n.endsWith("_SHOVEL");
        };
    }
}
