package me.aquaenchants.command;

import me.aquaenchants.AquaEnchatsPlugin;
import me.aquaenchants.enchant.CustomEnchant;
import me.aquaenchants.enchant.EnchantManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Map;

public class AquaEnchantCommand implements CommandExecutor {

    private final AquaEnchatsPlugin plugin;
    private final EnchantManager enchantManager;

    public AquaEnchantCommand(AquaEnchatsPlugin plugin, EnchantManager enchantManager) {
        this.plugin = plugin;
        this.enchantManager = enchantManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        if (!sender.hasPermission("aquaenchant.admin")) {
            sender.sendMessage(ChatColor.RED + "У вас нет прав на выполнение этой команды.");
            return true;
        }

        // GUI Admin Menu: opened directly for players with aquaenchant.admin permission
        if (sender instanceof Player p) {
            if (args.length > 0 && (args[0].equalsIgnoreCase("menu") || args[0].equalsIgnoreCase("admin") || args[0].equalsIgnoreCase("gui"))) {
                if (plugin.getAdminChanceGUI() != null) {
                    plugin.getAdminChanceGUI().openMainMenu(p);
                } else {
                    p.sendMessage(ChatColor.RED + "Админ-меню временно недоступно.");
                }
                return true;
            }

            // Требование: команды админки должны выполняться "от имени сервера".
            // Если команду вводит игрок с правами, проксируем её в консоль,
            // чтобы выполнение происходило как от ConsoleSender (при этом права проверяются выше).
            // Исключение: /separation (у него свой исполнитель) и GUI меню.
            String cmdLine = buildConsoleCommandLine(label, args, p.getName());
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmdLine);
            p.sendMessage(ChatColor.GREEN + "Команда выполнена от имени сервера.");
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sender.sendMessage(ChatColor.AQUA + "AquaEnchats команды:");
            sender.sendMessage(ChatColor.YELLOW + "/" + label + " menu" + ChatColor.GRAY + " - открыть интерактивное админ-меню настройки шансов");
            sender.sendMessage(ChatColor.YELLOW + "/" + label + " give <enchant> <amount> <level> [player]");
            sender.sendMessage(ChatColor.GRAY + "  Пример: /" + label + " give experience 1 1 - выдать книгу зачарования Опыт I");
            sender.sendMessage(ChatColor.GRAY + "  Пример: /" + label + " give smelting 1 1 - выдать книгу зачарования Переплавка руды I");
            sender.sendMessage(ChatColor.GRAY + "  Пример: /" + label + " give telepathy 1 1 - выдать книгу зачарования Телепатия дропа I");
            sender.sendMessage(ChatColor.GRAY + "  Пример: /" + label + " give trench 1 1 - выдать книгу зачарования Траншея 3x3 I");
            sender.sendMessage(ChatColor.GRAY + "  Пример: /" + label + " give veinminer 1 1 - выдать книгу зачарования Добыча жилы I");
            sender.sendMessage(ChatColor.GRAY + "  Пример: /" + label + " give indikator 1 1 - выдать книгу зачарования Индикатор руд I");
            sender.sendMessage(ChatColor.GRAY + "  Пример: /" + label + " give signal 1 1 - выдать книгу зачарования Магнит прочности I");
            sender.sendMessage(ChatColor.GRAY + "  Пример: /" + label + " give palirol 1 1 - выдать книгу зачарования Палироль I");
            sender.sendMessage(ChatColor.YELLOW + "/" + label + " reload");
            sender.sendMessage(ChatColor.GRAY + "  Перезагрузить конфиг и список кастомных зачарований.");
            return true;
        }


if (args[0].equalsIgnoreCase("giveitem")) {
            if (args.length < 4) {
                sender.sendMessage(ChatColor.RED + "Использование: /" + label + " giveitem <itemId> <player> <amount>");
                return true;
            }

            String itemId = args[1].toLowerCase();
            String playerName = args[2];
            int amount;
            try {
                amount = Integer.parseInt(args[3]);
            } catch (NumberFormatException ex) {
                sender.sendMessage(ChatColor.RED + "Количество должно быть числом.");
                return true;
            }

            if (amount <= 0) {
                sender.sendMessage(ChatColor.RED + "Количество должно быть больше нуля.");
                return true;
            }

            Player target = Bukkit.getPlayerExact(playerName);
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Игрок не найден: " + playerName);
                return true;
            }

            ItemStack base = plugin.getCustomItemManager().getItem(itemId);
            if (base == null) {
                sender.sendMessage(ChatColor.RED + "Предмет не найден: " + itemId);
                return true;
            }

            ItemStack item = base.clone();
            item.setAmount(amount);

            Map<Integer, ItemStack> left = target.getInventory().addItem(item);
            for (ItemStack leftover : left.values()) {
                target.getWorld().dropItemNaturally(target.getLocation(), leftover);
            }

            sender.sendMessage(ChatColor.GREEN + "Выдал предмет " + itemId + " x" + amount + " игроку " + target.getName());
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            plugin.reloadConfig();
            plugin.getEnchantManager().reload();
            plugin.getCustomItemManager().reload();
            if (plugin.getTableSettingsManager() != null) {
                plugin.getTableSettingsManager().load();
            }
            sender.sendMessage(ChatColor.GREEN + "AquaEnchats конфиги перезагружены.");
            return true;
        }

        if (args[0].equalsIgnoreCase("give")) {
            if (args.length < 4) {
                sender.sendMessage(ChatColor.RED + "Использование: /" + label + " give <enchant> <amount> <level> [player]");
                return true;
            }

            String enchantId = args[1].toLowerCase();
            int amount;
            int level;
            try {
                amount = Integer.parseInt(args[2]);
                level = Integer.parseInt(args[3]);
            } catch (NumberFormatException ex) {
                sender.sendMessage(ChatColor.RED + "Количество и уровень должны быть числами.");
                return true;
            }

            String playerName;
            if (args.length >= 5) {
                playerName = args[4];
            } else if (sender instanceof Player) {
                playerName = ((Player) sender).getName();
            } else {
                sender.sendMessage(ChatColor.RED + "Не указан игрок.");
                return true;
            }

            Player target = Bukkit.getPlayerExact(playerName);
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Игрок не найден: " + playerName);
                return true;
            }

            CustomEnchant enchant = enchantManager.getEnchant(enchantId);
            if (enchant == null) {
                sender.sendMessage(ChatColor.RED + "Зачарование не найдено: " + enchantId);
                return true;
            }

            if (enchant.getLevel(level) == null) {
                sender.sendMessage(ChatColor.RED + "Уровень " + level + " не определён для зачарования " + enchantId);
                return true;
            }

            enchantManager.giveBookToPlayer(target, enchant, level, amount);
            sender.sendMessage(ChatColor.GREEN + "Выдал " + amount + "x " + enchant.getDisplayName() + " " + level + " уровен(ь/я) игроку " + target.getName());
            return true;
        }

        if (args[0].equalsIgnoreCase("enchant")) {
            if (args.length < 3) {
                sender.sendMessage(ChatColor.RED + "Использование: /" + label + " enchant <enchant> <level> [player]");
                return true;
            }

            String enchantId = args[1].toLowerCase();
            int level;
            try {
                level = Integer.parseInt(args[2]);
            } catch (NumberFormatException ex) {
                sender.sendMessage(ChatColor.RED + "Уровень должен быть числом.");
                return true;
            }

            Player target;
            if (args.length >= 4) {
                target = Bukkit.getPlayerExact(args[3]);
            } else if (sender instanceof Player) {
                target = (Player) sender;
            } else {
                sender.sendMessage(ChatColor.RED + "Укажите игрока.");
                return true;
            }

            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Игрок не найден.");
                return true;
            }

            ItemStack inHand = target.getInventory().getItemInMainHand();
            if (inHand == null || inHand.getType().isAir()) {
                sender.sendMessage(ChatColor.RED + "В руке нет предмета.");
                return true;
            }

            CustomEnchant enchant = enchantManager.getEnchant(enchantId);
            if (enchant == null) {
                sender.sendMessage(ChatColor.RED + "Зачарование не найдено: " + enchantId);
                return true;
            }

            if (enchant.getLevel(level) == null) {
                sender.sendMessage(ChatColor.RED + "Уровень " + level + " не определён для зачарования " + enchantId);
                return true;
            }

            Map<CustomEnchant, Integer> current = new java.util.HashMap<>(enchantManager.getEnchantmentsOnItem(inHand));
            current.put(enchant, level);
            enchantManager.setEnchantmentsOnItem(inHand, current);
            sender.sendMessage(ChatColor.GREEN + "Предмет в руке зачарован на " + enchant.getDisplayName() + " " + level + " (" + target.getName() + ")");
            return true;
        }

        if (args[0].equalsIgnoreCase("cast")) {
            if (args.length < 2) {
                sender.sendMessage(ChatColor.RED + "Использование: /" + label + " cast <enchant> [energy] [player]");
                return true;
            }

            String enchantId = args[1].toLowerCase();
            int energy = 5;
            if (args.length >= 3) {
                try {
                    energy = Integer.parseInt(args[2]);
                } catch (NumberFormatException ignored) {}
            }

            Player target;
            if (args.length >= 4) {
                target = Bukkit.getPlayerExact(args[3]);
            } else if (sender instanceof Player) {
                target = (Player) sender;
            } else {
                sender.sendMessage(ChatColor.RED + "Укажите игрока.");
                return true;
            }

            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Игрок не найден.");
                return true;
            }

            if ("iceshtorm".equalsIgnoreCase(enchantId)) {
                if (plugin.getIceshtormListener() != null) {
                    plugin.getIceshtormListener().castDirectly(target, energy);
                    sender.sendMessage(ChatColor.AQUA + "Ледяной шторм активирован для " + target.getName() + " с мощностью " + energy);
                    return true;
                }
            }

            sender.sendMessage(ChatColor.RED + "Каст для зачарования " + enchantId + " не поддерживается.");
            return true;
        }

        if (args[0].equalsIgnoreCase("charge")) {
            if (args.length < 2) {
                sender.sendMessage(ChatColor.RED + "Использование: /" + label + " charge <enchant> [seconds] [player]");
                return true;
            }

            String enchantId = args[1].toLowerCase();
            int seconds = 3;
            if (args.length >= 3) {
                try {
                    seconds = Integer.parseInt(args[2]);
                } catch (NumberFormatException ignored) {}
            }

            Player target;
            if (args.length >= 4) {
                target = Bukkit.getPlayerExact(args[3]);
            } else if (sender instanceof Player) {
                target = (Player) sender;
            } else {
                sender.sendMessage(ChatColor.RED + "Укажите игрока.");
                return true;
            }

            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Игрок не найден.");
                return true;
            }

            if ("iceshtorm".equalsIgnoreCase(enchantId)) {
                if (plugin.getIceshtormListener() != null) {
                    plugin.getIceshtormListener().startChargingDirectly(target, seconds);
                    sender.sendMessage(ChatColor.AQUA + "Зарядка ледяного шторма запущена для " + target.getName() + " на " + seconds + " сек");
                    return true;
                }
            }

            sender.sendMessage(ChatColor.RED + "Зарядка для зачарования " + enchantId + " не поддерживается.");
            return true;
        }

        sender.sendMessage(ChatColor.RED + "Неизвестная подкоманда. Используйте /" + label + " help");
        return true;
    }

    /**
     * Собирает строку команды для выполнения из консоли.
     * Если игрок ввёл `/aquaenchant give <enchant> <amount> <level>` без указания игрока,
     * добавляем его ник как 5-й аргумент, иначе консольная версия команды не сможет
     * определить получателя.
     */
    private String buildConsoleCommandLine(String label, String[] args, String playerName) {
        if (args == null || args.length == 0) {
            return label;
        }

        java.util.List<String> parts = new java.util.ArrayList<>();
        parts.add(label);

        // Копируем аргументы
        java.util.Collections.addAll(parts, args);

        // auto-target для give
        if (args.length == 4 && args[0].equalsIgnoreCase("give") && playerName != null && !playerName.isEmpty()) {
            parts.add(playerName);
        }

        return String.join(" ", parts);
    }
}