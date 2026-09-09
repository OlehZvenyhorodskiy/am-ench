package me.aquaenchants.enchant;

public enum EnchantType {
    MINING,
    COMBAT,
    ARMOR,
    TOOL,
    OTHER;

    public static EnchantType fromString(String s) {
        if (s == null) return OTHER;
        try {
            return EnchantType.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return OTHER;
        }
    }
}
