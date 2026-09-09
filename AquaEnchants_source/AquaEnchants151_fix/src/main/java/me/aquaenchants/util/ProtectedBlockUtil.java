package me.aquaenchants.util;

import org.bukkit.Material;
import org.bukkit.block.Block;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Единая защита блоков, которые нельзя ломать/заменять кастомными эффектами.
 *
 * Список хранится по именам материалов, а не прямыми ссылками на enum-константы:
 * так проверка остаётся устойчивой для разных сборок Paper/Spigot.
 */
public final class ProtectedBlockUtil {

    private static final Set<String> PROTECTED_MATERIAL_NAMES;

    static {
        Set<String> names = new HashSet<>();

        // Запрошенные запретные блоки.
        names.add("BEDROCK");
        names.add("END_PORTAL_FRAME");
        names.add("SPAWNER");

        // Порталы и служебные/админские блоки, которые нельзя удалять массовыми эффектами.
        names.add("END_PORTAL");
        names.add("END_GATEWAY");
        names.add("NETHER_PORTAL");
        names.add("COMMAND_BLOCK");
        names.add("CHAIN_COMMAND_BLOCK");
        names.add("REPEATING_COMMAND_BLOCK");
        names.add("BARRIER");
        names.add("STRUCTURE_BLOCK");
        names.add("STRUCTURE_VOID");
        names.add("JIGSAW");
        names.add("LIGHT");

        // Специальные блоки новых версий Minecraft.
        names.add("TRIAL_SPAWNER");
        names.add("VAULT");
        names.add("REINFORCED_DEEPSLATE");

        PROTECTED_MATERIAL_NAMES = Collections.unmodifiableSet(names);
    }

    private ProtectedBlockUtil() {
    }

    public static boolean isProtected(Material type) {
        if (type == null) {
            return false;
        }
        return PROTECTED_MATERIAL_NAMES.contains(type.name().toUpperCase(Locale.ROOT));
    }

    public static boolean isProtected(Block block) {
        return block != null && isProtected(block.getType());
    }

    public static boolean canBreakBlock(Block block) {
        return block != null && !block.getType().isAir() && !isProtected(block);
    }

    public static boolean canModifyBlock(Block block) {
        return block != null && !isProtected(block);
    }

    public static Set<String> getProtectedMaterialNames() {
        return PROTECTED_MATERIAL_NAMES;
    }
}
