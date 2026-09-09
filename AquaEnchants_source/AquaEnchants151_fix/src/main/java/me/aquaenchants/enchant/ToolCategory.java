package me.aquaenchants.enchant;

import org.bukkit.inventory.ItemStack;
import org.bukkit.Material;

public enum ToolCategory {
    ALL_PICKAXE,
    ALL_SHOVEL,
    /** Alias of ALL_SHOVEL for compatibility with some configs. */
    ALL_SPADE,
    ALL_AXE,
    ALL_SWORD,
    /** Minecraft 1.21 weapon. */
    MACE,
    TRIDENT,
    BOW,
    CROSSBOW,
    SHIELD,
    ELYTRA,
    ALL_HELMET,
    ALL_CHESTPLATE,
    ALL_LEGGINGS,
    ALL_BOOTS,
    ALL_ARMOR,
    ALL_HOE,
    ALL_TOOLS,
    ALL_ITEMS;

    public static ToolCategory fromString(String s) {
        if (s == null) return null;
        String key = s.trim().toUpperCase();
        key = key.replace('-', '_').replace(' ', '_');
        try {
            return ToolCategory.valueOf(key);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public boolean matches(ItemStack item) {
        if (item == null) return false;
        Material mat = item.getType();
        String name = mat.name();

        switch (this) {
            case ALL_PICKAXE:
                return name.endsWith("_PICKAXE");
            case ALL_SHOVEL:
            case ALL_SPADE:
                return name.endsWith("_SHOVEL");
            case ALL_AXE:
                return name.endsWith("_AXE") && !name.endsWith("_PICKAXE");
            case ALL_SWORD:
                return name.endsWith("_SWORD");
            case MACE:
                return mat == Material.MACE;
            case TRIDENT:
                return mat == Material.TRIDENT;
            case BOW:
                return mat == Material.BOW;
            case CROSSBOW:
                return mat == Material.CROSSBOW;
            case SHIELD:
                return mat == Material.SHIELD;
            case ELYTRA:
                return mat == Material.ELYTRA;
            case ALL_HELMET:
                return name.endsWith("_HELMET");
            case ALL_CHESTPLATE:
                return name.endsWith("_CHESTPLATE");
            case ALL_LEGGINGS:
                return name.endsWith("_LEGGINGS");
            case ALL_BOOTS:
                return name.endsWith("_BOOTS");
            case ALL_ARMOR:
                return name.endsWith("_HELMET")
                        || name.endsWith("_CHESTPLATE")
                        || name.endsWith("_LEGGINGS")
                        || name.endsWith("_BOOTS");
            case ALL_HOE:
                return name.endsWith("_HOE");
            case ALL_TOOLS:
                // Tools = tools only (not weapons). Per config comments.
                return name.endsWith("_PICKAXE")
                        || name.endsWith("_SHOVEL")
                        || name.endsWith("_AXE")
                        || name.endsWith("_HOE");
            case ALL_ITEMS:
                // IMPORTANT:
                // Do NOT treat shields as a generic "all items" target.
                // Shields must be explicitly allow-listed via ToolCategory.SHIELD in config
                // (and via applies-to: Shield/SHIELD).
                return mat != Material.SHIELD;
            default:
                return false;
        }
    }
}
