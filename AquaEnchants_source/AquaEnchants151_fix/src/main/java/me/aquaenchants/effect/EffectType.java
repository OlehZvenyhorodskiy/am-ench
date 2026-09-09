package me.aquaenchants.effect;

import java.util.Locale;

public enum EffectType {
    SMELT,
    LAVA,
    LINEMINE,
    BREAK_BLOCK,
    BREAK_TREE,
    TP_DROPS,
    EXP;

    public static EffectType fromString(String s) {
        if (s == null) return null;
        String key = s.trim().toUpperCase(Locale.ROOT);

        // Отрезаем параметры после двоеточия, пробела или '@'
        int idx = key.indexOf(':');
        if (idx != -1) key = key.substring(0, idx).trim();
        int at = key.indexOf('@');
        if (at != -1) key = key.substring(0, at).trim();
        int space = key.indexOf(' ');
        if (space != -1) key = key.substring(0, space).trim();

        // Синонимы
        if (key.equals("BREAK") || key.equals("BLOCK_BREAK")) key = "BREAK_BLOCK";
        if (key.equals("XP")) key = "EXP";

        try {
            return EffectType.valueOf(key);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
