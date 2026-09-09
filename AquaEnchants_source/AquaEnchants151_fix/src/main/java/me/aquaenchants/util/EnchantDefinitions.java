package me.aquaenchants.util;

import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads enchant definitions from the bundled enachants.yml resource.
 * We do this in Paper bootstrap so the registry can be populated before freeze.
 *
 * Note: If you edit the enchants config in the plugin data folder, you must restart
 * to update the registry entries (reload cannot add new registry entries safely).
 */
public final class EnchantDefinitions {

    private EnchantDefinitions() {}

    public record Def(String id, String displayNamePlain, int maxLevel, int weight) {}

    public static List<Def> loadFromResource(final String resourcePath) {
        final List<Def> out = new ArrayList<>();
        try (InputStream in = EnchantDefinitions.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) return out;

            final YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8)
            );

            // Support two common formats:
            // 1) root.enchants.<id>.display / max-level / weight
            // 2) <id>.display ...
            ConfigurationSection root = yaml;
            ConfigurationSection enchants = yaml.getConfigurationSection("enchants");
            if (enchants == null) enchants = yaml.getConfigurationSection("enchantments");

            if (enchants != null) root = enchants;

            for (String id : root.getKeys(false)) {
                ConfigurationSection sec = root.getConfigurationSection(id);
                if (sec == null) continue;

                String display = sec.getString("display", sec.getString("name", id));
                int maxLevel = sec.getInt("max-level", sec.getInt("maxLevel", sec.getInt("max_level", 1)));
                int weight = sec.getInt("weight", 1);

                if (display == null) display = id;
                String plain = ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', display));
                if (plain == null || plain.isBlank()) plain = id;

                out.add(new Def(id, plain, maxLevel, weight));
            }
        } catch (Throwable ignored) {
            // Never fail bootstrap because of config parsing
        }
        return out;
    }
}
