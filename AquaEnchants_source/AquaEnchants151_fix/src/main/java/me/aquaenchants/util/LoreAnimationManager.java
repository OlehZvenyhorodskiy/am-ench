package me.aquaenchants.util;

import org.bukkit.ChatColor;

import java.awt.Color;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * High-performance, ultra-smooth lore shimmer manager that preserves each enchantment's
 * original configured colors from enachants.yml while adding a silky glowing light wave.
 */
public final class LoreAnimationManager {

    private static int globalTick = 0;
    private static volatile boolean animationEnabled = false;

    private static final Map<String, List<ColoredChar>> parsedDisplayCache = new ConcurrentHashMap<>();

    public static class ColoredChar {
        public final char ch;
        public final Color color;

        public ColoredChar(char ch, Color color) {
            this.ch = ch;
            this.color = color;
        }
    }

    private LoreAnimationManager() {}

    public static boolean isAnimationEnabled() {
        return animationEnabled;
    }

    public static void setAnimationEnabled(boolean enabled) {
        animationEnabled = enabled;
    }

    public static int getGlobalTick() {
        return globalTick;
    }

    public static void incrementTick() {
        globalTick++;
    }

    /**
     * Shimmers the enchantment using its OWN original colors from config.
     */
    public static String formatAnimatedOriginal(String rawDisplay, int tick) {
        if (rawDisplay == null || rawDisplay.isEmpty()) return "";
        if (!animationEnabled) return rawDisplay;

        List<ColoredChar> list = parsedDisplayCache.computeIfAbsent(rawDisplay, LoreAnimationManager::parseRawDisplay);
        if (list.isEmpty()) return rawDisplay;

        StringBuilder sb = new StringBuilder(list.size() * 16);

        for (int i = 0; i < list.size(); i++) {
            ColoredChar cc = list.get(i);
            if (Character.isWhitespace(cc.ch)) {
                sb.append(cc.ch);
                continue;
            }

            Color base = cc.color;
            if (base == null) {
                sb.append(cc.ch);
                continue;
            }

            // Convert to HSB to smoothly modulate brightness and saturation
            float[] hsb = Color.RGBtoHSB(base.getRed(), base.getGreen(), base.getBlue(), null);
            
            // Traveling sine wave along the word for ultra-smooth light reflection
            double wave = Math.sin((i * 0.40) - (tick * 0.18));
            
            // Subtle brightness boost (+0.25 to -0.15) and light saturation shine
            float brightnessOffset = (float) (wave * 0.22);
            float newBrightness = Math.max(0.25f, Math.min(1.0f, hsb[2] + brightnessOffset));
            float newSaturation = Math.max(0.15f, Math.min(1.0f, hsb[1] - (brightnessOffset * 0.20f)));

            Color shimmered = Color.getHSBColor(hsb[0], newSaturation, newBrightness);
            sb.append(toHexChatColor(shimmered)).append(cc.ch);
        }

        return sb.toString();
    }

    /**
     * Parses Minecraft formatted strings (including §x§r§r§g§g§b§b and §0..§f) into ColoredChar tokens.
     */
    private static List<ColoredChar> parseRawDisplay(String raw) {
        List<ColoredChar> result = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return result;

        Color currentColor = new Color(85, 255, 255); // Default cyan
        int len = raw.length();

        for (int i = 0; i < len; i++) {
            char c = raw.charAt(i);

            if ((c == '§' || c == '&') && i + 1 < len) {
                char code = raw.charAt(i + 1);

                // Check for hex pattern §x§r§r§g§g§b§b or &x&r&r&g&g&b&b
                if ((code == 'x' || code == 'X') && i + 13 < len) {
                    try {
                        String hexR = "" + raw.charAt(i + 3) + raw.charAt(i + 5);
                        String hexG = "" + raw.charAt(i + 7) + raw.charAt(i + 9);
                        String hexB = "" + raw.charAt(i + 11) + raw.charAt(i + 13);
                        int r = Integer.parseInt(hexR, 16);
                        int g = Integer.parseInt(hexG, 16);
                        int b = Integer.parseInt(hexB, 16);
                        currentColor = new Color(r, g, b);
                        i += 13;
                        continue;
                    } catch (Exception ignored) {
                    }
                }

                // Check for standard color code §0..§f
                Color standard = getStandardMinecraftColor(code);
                if (standard != null) {
                    currentColor = standard;
                    i++;
                    continue;
                }

                // Formatting codes like §l, §o, §r
                if (code == 'r' || code == 'R') {
                    currentColor = new Color(255, 255, 255);
                    i++;
                    continue;
                }

                i++;
                continue;
            }

            result.add(new ColoredChar(c, currentColor));
        }

        return result;
    }

    private static Color getStandardMinecraftColor(char code) {
        return switch (Character.toLowerCase(code)) {
            case '0' -> new Color(0, 0, 0);
            case '1' -> new Color(0, 0, 170);
            case '2' -> new Color(0, 170, 0);
            case '3' -> new Color(0, 170, 170);
            case '4' -> new Color(170, 0, 0);
            case '5' -> new Color(170, 0, 170);
            case '6' -> new Color(255, 170, 0);
            case '7' -> new Color(170, 170, 170);
            case '8' -> new Color(85, 85, 85);
            case '9' -> new Color(85, 85, 255);
            case 'a' -> new Color(85, 255, 85);
            case 'b' -> new Color(85, 255, 255);
            case 'c' -> new Color(255, 85, 85);
            case 'd' -> new Color(255, 85, 255);
            case 'e' -> new Color(255, 255, 85);
            case 'f' -> new Color(255, 255, 255);
            default -> null;
        };
    }

    private static String toHexChatColor(Color c) {
        int r = c.getRed();
        int g = c.getGreen();
        int b = c.getBlue();
        return String.format("§x§%x§%x§%x§%x§%x§%x",
                (r >> 4) & 0xF, r & 0xF,
                (g >> 4) & 0xF, g & 0xF,
                (b >> 4) & 0xF, b & 0xF);
    }
}
