package me.aquaenchants.separation;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class SeparationCommand implements CommandExecutor {

    private final SeparationManager manager;

    public SeparationCommand(SeparationManager manager) {
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Только для игроков.");
            return true;
        }
        manager.openInput(player);
        return true;
    }
}
