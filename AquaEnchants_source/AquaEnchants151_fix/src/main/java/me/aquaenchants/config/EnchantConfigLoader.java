package me.aquaenchants.config;

import me.aquaenchants.AquaEnchatsPlugin;
import me.aquaenchants.enchant.CustomEnchant;
import me.aquaenchants.enchant.EffectConfig;
import me.aquaenchants.enchant.EnchantLevel;
import me.aquaenchants.enchant.EnchantType;
import me.aquaenchants.enchant.ToolCategory;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class EnchantConfigLoader {

    private final AquaEnchatsPlugin plugin;

    private List<String> groupOrder = new ArrayList<>();
    private Map<String, CustomEnchant> enchants = new HashMap<>();
    private Set<String> definedEffects = new HashSet<>();

    public EnchantConfigLoader(AquaEnchatsPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        enchants.clear();
        definedEffects.clear();
        groupOrder.clear();

        plugin.reloadConfig();
        FileConfiguration config = plugin.getConfig();

        if (config.isList("group")) {
            groupOrder = config.getStringList("group");
        }

        if (config.isList("effectenchant")) {
            definedEffects.addAll(config.getStringList("effectenchant"));
        }

        File enchantsFile = new File(plugin.getDataFolder(), "enachants.yml");
        if (!enchantsFile.exists()) {
            plugin.saveResource("enachants.yml", false);
        }

        // ВАЖНО: зачарования должны читаться ИЗ ФАЙЛА plugins/AquaEnchats/enachants.yml.
        // Встроенный ресурс используется только для первичного создания файла (saveResource выше).
        // Никаких попыток парсить/мержить ресурс из JAR на каждом reload, иначе при ошибке в ресурсе
        // плагин падает "Cannot load configuration from stream" и игнорирует внешний конфиг.
        FileConfiguration enchantsConfig = YamlConfiguration.loadConfiguration(enchantsFile);

        ConfigurationSection enchSection = enchantsConfig.getConfigurationSection("enchantments");
        if (enchSection != null) {
            for (String key : enchSection.getKeys(false)) {
                ConfigurationSection sec = enchSection.getConfigurationSection(key);
                if (sec == null) continue;

                String id = key.toLowerCase(Locale.ROOT);
                String rawDisplay = sec.getString("display", id);
                String rawDescription = sec.getString("description", "");
                String display = ChatColor.translateAlternateColorCodes('&', rawDisplay);
                String description = ChatColor.translateAlternateColorCodes('&', rawDescription);
                // applies-to can be a string or a YAML list. If not specified/empty -> cannot apply.
                String appliesTo = readAppliesTo(sec);
                String typeStr = sec.getString("type", "MINING");
                EnchantType type = EnchantType.fromString(typeStr);
                String group = sec.getString("group", "defoult");

                List<String> appliesRaw = readAppliesList(sec);
                Set<ToolCategory> applies = new HashSet<>();
                for (String s : appliesRaw) {
                    ToolCategory cat = ToolCategory.fromString(s);
                    if (cat != null) applies.add(cat);
                }

                boolean enchantTableEnabled = false;
                int enchantTableChance = 0;
                if (sec.isConfigurationSection("enchanttable")) {
                    ConfigurationSection t = sec.getConfigurationSection("enchanttable");
                    enchantTableEnabled = t.getBoolean("enabled", false);
                    enchantTableChance = t.getInt("chance", 0);
                } else {
                    enchantTableEnabled = sec.getBoolean("enchanttable", false);
                    enchantTableChance = sec.getInt("chans", 0);
                }

                boolean villagerEnabled = false;
                int villagerChance = 0;
                if (sec.isConfigurationSection("enchantvillager")) {
                    ConfigurationSection t = sec.getConfigurationSection("enchantvillager");
                    villagerEnabled = t.getBoolean("enabled", false);
                    villagerChance = t.getInt("chance", 0);
                } else {
                    villagerEnabled = sec.getBoolean("enchantvilager", false);
                    villagerChance = sec.getInt("chans", 0);
                }

                Map<Integer, EnchantLevel> levels = new HashMap<>();
                ConfigurationSection levelsSec = sec.getConfigurationSection("levels");
                if (levelsSec != null) {
                    for (String levelKey : levelsSec.getKeys(false)) {
                        int level;
                        try {
                            level = Integer.parseInt(levelKey);
                        } catch (NumberFormatException ex) {
                            continue;
                        }
                        ConfigurationSection lvSec = levelsSec.getConfigurationSection(levelKey);
                        if (lvSec == null) continue;

                        int chance = lvSec.getInt("chance", 100);
                        int progress = lvSec.getInt("progress", 0);
                        int cooldown = lvSec.getInt("cooldown", 0);
                        List<EffectConfig> effects = parseEffects(lvSec);

                        levels.put(level, new EnchantLevel(level, chance, effects, progress, cooldown));
                    }
                }

                int breakBlockDelayTicks = sec.getInt("break_block_delay_ticks", 1);
                int powerPercent = sec.getInt("powers", sec.getInt("power", 0));
                CustomEnchant enchant = new CustomEnchant(
                        id,
                        display,
                        description,
                        appliesTo,
                        type,
                        group,
                        applies,
                        enchantTableEnabled,
                        enchantTableChance,
                        villagerEnabled,
                        villagerChance,
                        levels,
                        breakBlockDelayTicks,
                        powerPercent
                );
                enchants.put(id, enchant);
            }

        }


        // Поддержка старого формата: зачарования, объявленные прямо в корне enachants.yml
        // (например, inquisitive: ...), а не только внутри секции enchantments:.
        for (String key : enchantsConfig.getKeys(false)) {
            if (key.equalsIgnoreCase("enchantments")) {
                continue;
            }
            // Если уже загружено из секции enchantments, пропускаем
            String id = key.toLowerCase(Locale.ROOT);
            if (enchants.containsKey(id)) {
                continue;
            }

            ConfigurationSection sec = enchantsConfig.getConfigurationSection(key);
            if (sec == null) {
                continue;
            }

            String rawDisplay = sec.getString("display", id);
            String rawDescription = sec.getString("description", "");
            String display = ChatColor.translateAlternateColorCodes('&', rawDisplay);
            String description = ChatColor.translateAlternateColorCodes('&', rawDescription);
            if ("frenzy".equalsIgnoreCase(id) && (rawDisplay == null || rawDisplay.equalsIgnoreCase(id))) {
                display = "§x§9§8§1§C§0§DП§x§A§5§1§9§0§BО§x§B§2§1§5§0§AД§x§B§F§1§2§0§8Р§x§C§C§0§E§0§7Ы§x§D§8§0§B§0§5В§x§E§5§0§7§0§3Н§x§F§2§0§4§0§2И§x§F§F§0§0§0§0К";
            }
            if ("frenzy".equalsIgnoreCase(id) && (rawDisplay == null || rawDisplay.equalsIgnoreCase(id))) {
                display = "§x§9§8§1§C§0§DП§x§A§5§1§9§0§BО§x§B§2§1§5§0§AД§x§B§F§1§2§0§8Р§x§C§C§0§E§0§7Ы§x§D§8§0§B§0§5В§x§E§5§0§7§0§3Н§x§F§2§0§4§0§2И§x§F§F§0§0§0§0К";
            }
            String appliesTo = readAppliesTo(sec);
            String typeStr = sec.getString("type", "MINING");
            EnchantType type = EnchantType.fromString(typeStr);
            if (type == EnchantType.OTHER && typeStr != null) {
                String u = typeStr.toUpperCase(java.util.Locale.ROOT);
                if (u.contains("MINE")) {
                    type = EnchantType.MINING;
                } else if (u.contains("ATTACK") || u.contains("KILL") || u.contains("COMBAT") || u.contains("SHOOT")) {
                    type = EnchantType.COMBAT;
                } else if (u.contains("ARMOR")) {
                    type = EnchantType.ARMOR;
                } else if (u.contains("TOOL")) {
                    type = EnchantType.TOOL;
                }
            }
            if (type == EnchantType.OTHER && typeStr != null) {
                String u = typeStr.toUpperCase(java.util.Locale.ROOT);
                if (u.contains("MINE")) {
                    type = EnchantType.MINING;
                } else if (u.contains("ATTACK") || u.contains("KILL") || u.contains("COMBAT") || u.contains("SHOOT")) {
                    type = EnchantType.COMBAT;
                } else if (u.contains("ARMOR")) {
                    type = EnchantType.ARMOR;
                } else if (u.contains("TOOL")) {
                    type = EnchantType.TOOL;
                }
            }
            String group = sec.getString("group", "defoult");

            List<String> appliesRaw = readAppliesList(sec);
            Set<ToolCategory> applies = new HashSet<>();
            for (String s : appliesRaw) {
                ToolCategory cat = ToolCategory.fromString(s);
                if (cat != null) applies.add(cat);
            }

            boolean enchantTableEnabled = false;
            int enchantTableChance = 0;
            if (sec.isConfigurationSection("enchanttable")) {
                ConfigurationSection t = sec.getConfigurationSection("enchanttable");
                enchantTableEnabled = t.getBoolean("enabled", false);
                enchantTableChance = t.getInt("chance", 0);
            } else {
                enchantTableEnabled = sec.getBoolean("enchanttable", false);
                enchantTableChance = sec.getInt("chans", 0);
            }

            boolean villagerEnabled = false;
            int villagerChance = 0;
            if (sec.isConfigurationSection("enchantvillager")) {
                ConfigurationSection t = sec.getConfigurationSection("enchantvillager");
                villagerEnabled = t.getBoolean("enabled", false);
                villagerChance = t.getInt("chance", 0);
            } else {
                villagerEnabled = sec.getBoolean("enchantvilager", false);
                villagerChance = sec.getInt("chans", 0);
            }

            Map<Integer, EnchantLevel> levels = new HashMap<>();
            ConfigurationSection levelsSec = sec.getConfigurationSection("levels");
            if (levelsSec != null) {
                for (String levelKey : levelsSec.getKeys(false)) {
                    int level;
                    try {
                        level = Integer.parseInt(levelKey);
                    } catch (NumberFormatException ex) {
                        continue;
                    }
                    ConfigurationSection lvSec = levelsSec.getConfigurationSection(levelKey);
                    if (lvSec == null) continue;

                    int chance = lvSec.getInt("chance", 100);
                    int progress = lvSec.getInt("progress", 0);
                    int cooldown = lvSec.getInt("cooldown", 0);
                    List<EffectConfig> effects = parseEffects(lvSec);

                    levels.put(level, new EnchantLevel(level, chance, effects, progress, cooldown));
                }
            }

            int breakBlockDelayTicks = sec.getInt("break_block_delay_ticks", 1);
            int powerPercent = sec.getInt("powers", sec.getInt("power", 0));
            CustomEnchant enchant = new CustomEnchant(
                    id,
                    display,
                    description,
                    appliesTo,
                    type,
                    group,
                    applies,
                    enchantTableEnabled,
                    enchantTableChance,
                    villagerEnabled,
                    villagerChance,
                    levels,
                    breakBlockDelayTicks,
                    powerPercent
            );
            enchants.put(id, enchant);
	        }
	    }

    private List<EffectConfig> parseEffects(ConfigurationSection lvSec) {
        List<EffectConfig> list = new ArrayList<>();

        if (lvSec.isList("effects")) {
            List<?> raw = lvSec.getList("effects");
            if (raw != null) {
                for (Object obj : raw) {
                    if (obj instanceof String) {
                        list.add(new EffectConfig((String) obj, null));
                    } else if (obj instanceof Map) {
                        Map<?, ?> map = (Map<?, ?>) obj;
                        for (Map.Entry<?, ?> entry : map.entrySet()) {
                            String key = String.valueOf(entry.getKey());
                            Map<String, Integer> particles = new HashMap<>();
                            Object val = entry.getValue();

                            String id;
                            // Если значение - строка, считаем что это хвост эффекта:
                            //   KEY: value
                            // -> например: LIGHTNING: (%power% -10) @Victim
                            if (val instanceof String) {
                                id = key + ": " + String.valueOf(val);
                            } else {
                                id = key;
                                if (val instanceof Map) {
                                    Object p = ((Map<?, ?>) val).get("partikle");
                                    if (p instanceof Iterable) {
                                        for (Object o : (Iterable<?>) p) {
                                            if (o instanceof String) {
                                                particles.put(String.valueOf(o), 1);
                                            } else if (o instanceof Map) {
                                                Map<?, ?> inner = (Map<?, ?>) o;
                                                for (Map.Entry<?, ?> e2 : inner.entrySet()) {
                                                    String pname = String.valueOf(e2.getKey());
                                                    int count = 0;
                                                    try {
                                                        count = Integer.parseInt(String.valueOf(e2.getValue()));
                                                    } catch (NumberFormatException ignored) {}
                                                    particles.put(pname, count);
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            list.add(new EffectConfig(id, particles));
                        }

                    }
                }
            }
        } else {
            List<String> raw = lvSec.getStringList("effects");
            for (String s : raw) {
                list.add(new EffectConfig(s, null));
            }
        }

        return list;
    }

    /**
     * Robustly reads "applies" from YAML.
     * Bukkit's ConfigurationSection#getStringList may return an empty list when the underlying
     * YAML contains a raw List with non-String entries (e.g. generated by some editors) or when
     * "applies" is provided as a single string.
     */
    private List<String> readAppliesList(ConfigurationSection sec) {
        if (sec == null) return java.util.Collections.emptyList();

        Object raw = sec.get("applies");
        if (raw == null) {
            return java.util.Collections.emptyList();
        }

        // Common case
        if (sec.isList("applies")) {
            List<String> fromBukkit = sec.getStringList("applies");
            if (fromBukkit != null && !fromBukkit.isEmpty()) {
                return fromBukkit;
            }

            // Fallback: read raw list and stringify
            List<?> list = sec.getList("applies");
            if (list == null) return java.util.Collections.emptyList();
            List<String> out = new java.util.ArrayList<>();
            for (Object o : list) {
                if (o == null) continue;
                String s = String.valueOf(o).trim();
                if (!s.isEmpty()) out.add(s);
            }
            return out;
        }

        // If provided as a single string like "ALL_PICKAXE" or "ALL_PICKAXE,ALL_SHOVEL"
        String s = sec.getString("applies", "");
        if (s == null) return java.util.Collections.emptyList();
        s = s.trim();
        if (s.isEmpty()) return java.util.Collections.emptyList();

        String[] parts = s.split(",");
        List<String> out = new java.util.ArrayList<>();
        for (String p : parts) {
            String t = p.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private String readAppliesTo(ConfigurationSection sec) {
        if (sec == null) return "";
        Object raw = sec.get("applies-to");
        if (raw == null) return "";
        if (raw instanceof java.util.List<?>) {
            java.util.List<?> list = (java.util.List<?>) raw;
            java.util.List<String> parts = new java.util.ArrayList<>();
            for (Object o : list) {
                if (o == null) continue;
                String s = String.valueOf(o).trim();
                if (!s.isEmpty()) parts.add(s);
            }
            return String.join(",", parts);
        }
        String s = sec.getString("applies-to", "");
        return s == null ? "" : s.trim();
    }

    public List<String> getGroupOrder() {
        return groupOrder;
    }

    public Map<String, CustomEnchant> getEnchants() {
        return enchants;
    }

    public Set<String> getDefinedEffects() {
        return definedEffects;
    }
}