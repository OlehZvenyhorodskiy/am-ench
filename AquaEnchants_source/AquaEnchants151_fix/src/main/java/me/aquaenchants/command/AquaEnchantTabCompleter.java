package me.aquaenchants.command;

import me.aquaenchants.enchant.CustomEnchant;
import me.aquaenchants.enchant.EnchantManager;
import me.aquaenchants.AquaEnchatsPlugin;
import me.aquaenchants.item.CustomItemManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

public class AquaEnchantTabCompleter implements TabCompleter {

    private final EnchantManager enchantManager;

    public AquaEnchantTabCompleter(EnchantManager enchantManager) {
        this.enchantManager = enchantManager;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<>();

        if (!sender.hasPermission("aquaenchant.admin")) {
            return result;
        }

        // /aquaenchant <subcommand>
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);

            // Если уже введено ровно 'giveitem' — сразу подсказка id предметов
            if (prefix.equals("giveitem")) {
                CustomItemManager itemManager = AquaEnchatsPlugin.getInstance().getCustomItemManager();
                if (itemManager != null) {
                    result.addAll(itemManager.getItemIds());
                }
                return result;
            }

            addIfStartsWith(result, "menu", prefix);
            addIfStartsWith(result, "admin", prefix);
            addIfStartsWith(result, "give", prefix);
            addIfStartsWith(result, "enchant", prefix);
            addIfStartsWith(result, "cast", prefix);
            addIfStartsWith(result, "charge", prefix);
            addIfStartsWith(result, "giveitem", prefix);
            addIfStartsWith(result, "reload", prefix);
            addIfStartsWith(result, "help", prefix);
            return result;
        }

        // /aquaenchant cast <enchant> [energy] [player]
        if (args[0].equalsIgnoreCase("cast") || args[0].equalsIgnoreCase("charge")) {
            if (args.length == 2) {
                String prefix = args[1].toLowerCase(Locale.ROOT);
                for (String ench : new String[]{"iceshtorm"}) {
                    if (ench.startsWith(prefix)) result.add(ench);
                }
                return result;
            }
            if (args.length == 3) {
                String prefix = args[2];
                for (String v : new String[]{"1", "2", "3", "4", "5"}) {
                    if (v.startsWith(prefix)) result.add(v);
                }
                return result;
            }
            if (args.length == 4) {
                String prefix = args[3].toLowerCase(Locale.ROOT);
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                        result.add(p.getName());
                    }
                }
                return result;
            }
            return result;
        }

        // /aquaenchant enchant <enchant> <level> [player]
        if (args[0].equalsIgnoreCase("enchant")) {
            if (args.length == 2) {
                String prefix = args[1].toLowerCase(Locale.ROOT);
                Collection<CustomEnchant> enchants = enchantManager.getAll();
                for (CustomEnchant ench : enchants) {
                    String id = ench.getId().toLowerCase(Locale.ROOT);
                    if (id.startsWith(prefix)) {
                        result.add(id);
                    }
                }
                return result;
            }

            if (args.length == 3) {
                String prefix = args[2];
                CustomEnchant ench = enchantManager.getEnchant(args[1]);
                if (ench != null && ench.getLevels() != null) {
                    List<Integer> levels = ench.getLevels().keySet().stream().sorted().collect(Collectors.toList());
                    for (Integer lvl : levels) {
                        String s = String.valueOf(lvl);
                        if (s.startsWith(prefix)) {
                            result.add(s);
                        }
                    }
                } else {
                    for (String v : new String[] {"1", "2", "3", "4", "5"}) {
                        if (v.startsWith(prefix)) {
                            result.add(v);
                        }
                    }
                }
                return result;
            }

            if (args.length == 4) {
                String prefix = args[3].toLowerCase(Locale.ROOT);
                for (Player p : Bukkit.getOnlinePlayers()) {
                    String name = p.getName();
                    if (name.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                        result.add(name);
                    }
                }
                return result;
            }
            return result;
        }

        // /aquaenchant give <enchant> <amount> <level> [player]
        if (args[0].equalsIgnoreCase("give")) {
            // 2-й аргумент: id зачарования (из config/enachants.yml)
            if (args.length == 2) {
                String prefix = args[1].toLowerCase(Locale.ROOT);
                Collection<CustomEnchant> enchants = enchantManager.getAll();
                for (CustomEnchant ench : enchants) {
                    String id = ench.getId().toLowerCase(Locale.ROOT);
                    if (id.startsWith(prefix)) {
                        result.add(id);
                    }
                }
                return result;
            }

            // 3-й аргумент: количество
            if (args.length == 3) {
                String prefix = args[2];
                for (String v : new String[] {"1", "2", "3", "5", "10", "16", "32", "64"}) {
                    if (v.startsWith(prefix)) {
                        result.add(v);
                    }
                }
                return result;
            }

            // 4-й аргумент: уровень зачарования
            if (args.length == 4) {
                String prefix = args[3];
                CustomEnchant ench = enchantManager.getEnchant(args[1]);
                if (ench != null && ench.getLevels() != null) {
                    List<Integer> levels = ench.getLevels().keySet().stream().sorted().collect(Collectors.toList());
                    for (Integer lvl : levels) {
                        String s = String.valueOf(lvl);
                        if (s.startsWith(prefix)) {
                            result.add(s);
                        }
                    }
                } else {
                    // если не нашли зачарование, предложим уровни по умолчанию
                    for (String v : new String[] {"1", "2", "3"}) {
                        if (v.startsWith(prefix)) {
                            result.add(v);
                        }
                    }
                }
                return result;
            }

            // 5-й аргумент: ник игрока
            if (args.length == 5) {
                String prefix = args[4].toLowerCase(Locale.ROOT);
                for (Player p : Bukkit.getOnlinePlayers()) {
                    String name = p.getName();
                    if (name.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                        result.add(name);
                    }
                }
                return result;
            }
        }

        // /aquaenchant giveitem <itemId> <player> <amount>
        if (args[0].equalsIgnoreCase("giveitem")) {
            CustomItemManager itemManager = AquaEnchatsPlugin.getInstance().getCustomItemManager();

            // 2-й аргумент: id предмета
            if (args.length == 2) {
                String prefix = args[1].toLowerCase(Locale.ROOT);
                if (itemManager != null) {
                    for (String id : itemManager.getItemIds()) {
                        if (id.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                            result.add(id);
                        }
                    }
                }
                return result;
            }

            // 3-й аргумент: ник игрока
            if (args.length == 3) {
                String prefix = args[2].toLowerCase(Locale.ROOT);
                for (Player p : Bukkit.getOnlinePlayers()) {
                    String name = p.getName();
                    if (name.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                        result.add(name);
                    }
                }
                return result;
            }

            // 4-й аргумент: количество
            if (args.length == 4) {
                String prefix = args[3];
                for (String v : new String[] {"1", "2", "3", "5", "10", "16", "32", "64"}) {
                    if (v.startsWith(prefix)) {
                        result.add(v);
                    }
                }
                return result;
            }
        }

        return result;
    }

    private void addIfStartsWith(List<String> list, String value, String prefix) {
        if (value.toLowerCase(Locale.ROOT).startsWith(prefix)) {
            list.add(value);
        }
    }
}
