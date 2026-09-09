package me.aquaenchants.util;

import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.block.Banner;
import org.bukkit.block.BlockState;
import org.bukkit.block.banner.Pattern;
import org.bukkit.block.banner.PatternType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Applies a predefined banner pattern to shields enchanted with our SHIELD enchant.
 * This is a best-effort approximation of a provided PNG using vanilla banner patterns.
 */
public final class ShieldPatternUtil {

    private ShieldPatternUtil() {}

    public static void applyTulovikPattern(ItemStack shield) {
        if (shield == null || shield.getType() != Material.SHIELD) return;

        if (!(shield.getItemMeta() instanceof BlockStateMeta meta)) return;

        BlockState state = meta.getBlockState();
        if (!(state instanceof Banner banner)) return;

        // Base color roughly matches the PNG background.
        try {
            banner.setBaseColor(DyeColor.ORANGE);
        } catch (Throwable ignored) {}

        List<Pattern> patterns = new ArrayList<>();

        // Black stripes
        add(patterns, DyeColor.BLACK, "STRIPE_TOP");
        add(patterns, DyeColor.BLACK, "STRIPE_MIDDLE");

        // Cyan "mask" around the face
        add(patterns, DyeColor.CYAN, "RHOMBUS_MIDDLE");
        add(patterns, DyeColor.CYAN, "HALF_HORIZONTAL_MIRROR");

        // Eyes: black squares then white highlights
        add(patterns, DyeColor.BLACK, "SQUARE_TOP_LEFT");
        add(patterns, DyeColor.BLACK, "SQUARE_TOP_RIGHT");
        add(patterns, DyeColor.WHITE, "SQUARE_TOP_LEFT");
        add(patterns, DyeColor.WHITE, "SQUARE_TOP_RIGHT");

        try {
            banner.setPatterns(patterns);
            banner.update();
            meta.setBlockState(banner);
            shield.setItemMeta(meta);
        } catch (Throwable ignored) {
        }
    }

    private static void add(List<Pattern> list, DyeColor color, String typeName) {
        try {
            PatternType type = PatternType.valueOf(typeName);
            list.add(new Pattern(color, type));
        } catch (Throwable ignored) {
            // Pattern not available on this server version; skip.
        }
    }
}