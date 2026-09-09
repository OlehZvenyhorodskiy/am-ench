package me.aquaenchants.effect;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;

public class EffectContext {

    private final Player player;
    private final ItemStack tool;
    private final Block block;
    private final BlockBreakEvent blockBreakEvent;

    public EffectContext(Player player, ItemStack tool, Block block, BlockBreakEvent blockBreakEvent) {
        this.player = player;
        this.tool = tool;
        this.block = block;
        this.blockBreakEvent = blockBreakEvent;
    }

    public Player getPlayer() {
        return player;
    }

    public ItemStack getTool() {
        return tool;
    }

    public Block getBlock() {
        return block;
    }

    public BlockBreakEvent getBlockBreakEvent() {
        return blockBreakEvent;
    }
}
