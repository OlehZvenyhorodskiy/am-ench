package me.aquaenchants.listener;

import me.aquaenchants.AquaEnchatsPlugin;
import me.aquaenchants.enchant.CustomEnchant;
import me.aquaenchants.enchant.EffectConfig;
import me.aquaenchants.enchant.EnchantLevel;
import me.aquaenchants.enchant.EnchantManager;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

import java.util.*;

public class PlanterListener implements Listener {

    private final AquaEnchatsPlugin plugin;
    private final EnchantManager enchantManager;
    private final Random random = new Random();
    private final Map<UUID, Long> cooldowns = new HashMap<>();

    public PlanterListener(AquaEnchatsPlugin plugin, EnchantManager enchantManager) {
        this.plugin = plugin;
        this.enchantManager = enchantManager;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Player player = event.getPlayer();
        if (player == null) return;
ItemStack tool = event.getItem();
        if (tool == null || tool.getType() == Material.AIR) {
            return;
        }

        Map<CustomEnchant, Integer> enchants = enchantManager.getEnchantmentsOnItem(tool);
        if (enchants == null || enchants.isEmpty()) return;

        CustomEnchant planter = null;
        int level = 0;
        for (Map.Entry<CustomEnchant, Integer> e : enchants.entrySet()) {
            CustomEnchant ench = e.getKey();
            Integer lvl = e.getValue();
            if (ench == null || lvl == null || lvl <= 0) continue;
            String id = ench.getId();
            if (id == null) continue;
            if ("planter".equalsIgnoreCase(id)) {
                planter = ench;
                level = lvl;
                break;
            }
        }

        if (planter == null || level <= 0) {
            return;
        }

        EnchantLevel data = planter.getLevel(level);
        if (data == null) {
            return;
        }

        int chance = data.getChance();
        if (chance > 0 && random.nextInt(100) >= chance) {
            return;
        }

        int cooldownSec = data.getCooldown();
        if (cooldownSec > 0) {
            long now = System.currentTimeMillis();
            long cdMs = cooldownSec * 1000L;
            UUID uuid = player.getUniqueId();
            Long last = cooldowns.get(uuid);
            if (last != null && now - last < cdMs) {
                return;
            }
            cooldowns.put(uuid, now);
        }

        int radius = 1;
        List<EffectConfig> effects = data.getEffects();
        if (effects != null) {
            for (EffectConfig ec : effects) {
                if (ec == null) continue;
                String eff = ec.getId();
                if (eff == null) continue;
                String trimmed = eff.trim();
                String upper = trimmed.toUpperCase(Locale.ROOT);
                if (upper.startsWith("PLANT")) {
                    String[] parts = trimmed.split(":", 3);
                    if (parts.length >= 2) {
                        try {
                            radius = Math.max(1, Integer.parseInt(parts[1]));
                        } catch (NumberFormatException ignored) {
                            radius = 1;
                        }
                    }
                    break;
                }
            }
        }

        Block clicked = event.getClickedBlock();
        if (clicked == null) {
            return;
        }

        Block base = clicked;
        Material clickedType = clicked.getType();
        if (isCrop(clickedType)) {
            base = clicked.getRelative(0, -1, 0);
        }

        World world = base.getWorld();
        int baseX = base.getX();
        int baseY = base.getY();
        int baseZ = base.getZ();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                Block soil = world.getBlockAt(baseX + dx, baseY, baseZ + dz);
                handleSoil(soil, player, tool);
            }
        }
    }

    private void handleSoil(Block soil, Player player, ItemStack tool) {
        Material soilType = soil.getType();
        boolean farmland = (soilType == Material.FARMLAND);
        boolean soulSand = (soilType == Material.SOUL_SAND);

        if (!farmland && !soulSand) {
            return;
        }

        Block cropBlock = soil.getRelative(0, 1, 0);
        Material cropType = cropBlock.getType();

        if (isCrop(cropType) && isMature(cropBlock)) {
            Collection<ItemStack> drops = cropBlock.getDrops(tool, player);
            for (ItemStack drop : drops) {
                if (drop == null || drop.getType() == Material.AIR || drop.getAmount() <= 0) continue;
                Map<Integer, ItemStack> leftover = player.getInventory().addItem(drop);
                if (!leftover.isEmpty()) {
                    for (ItemStack l : leftover.values()) {
                        if (l == null || l.getType() == Material.AIR || l.getAmount() <= 0) continue;
                        player.getWorld().dropItemNaturally(player.getLocation(), l);
                    }
                }
            }

            Material seedMat = getSeedMaterial(cropType);
            boolean planted = false;
            if (seedMat != null && removeOne(player, seedMat)) {
                plantCrop(soil, cropType);
                planted = true;
            }

            if (!planted) {
                cropBlock.setType(Material.AIR);
            }

            return;
        }

        if (cropType == Material.AIR) {
            Material[] seedsOrder = new Material[] {
                    Material.WHEAT_SEEDS,
                    Material.CARROT,
                    Material.POTATO,
                    Material.BEETROOT_SEEDS,
                    Material.NETHER_WART
            };
            for (Material seed : seedsOrder) {
                if (hasItem(player, seed) && canPlantOn(soilType, seed)) {
                    if (removeOne(player, seed)) {
                        Material targetCrop = getCropForSeed(seed);
                        if (targetCrop != null) {
                            plantCrop(soil, targetCrop);
                        }
                    }
                    break;
                }
            }
        }
    }

    private boolean isCrop(Material type) {
        if (type == null) return false;
        switch (type) {
            case WHEAT:
            case CARROTS:
            case POTATOES:
            case BEETROOTS:
            case NETHER_WART:
                return true;
            default:
                return false;
        }
    }

    private boolean isMature(Block cropBlock) {
        BlockData data = cropBlock.getBlockData();
        if (data instanceof Ageable) {
            Ageable ageable = (Ageable) data;
            return ageable.getAge() >= ageable.getMaximumAge();
        }
        return false;
    }

    private Material getSeedMaterial(Material cropType) {
        if (cropType == null) return null;
        switch (cropType) {
            case WHEAT:
                return Material.WHEAT_SEEDS;
            case CARROTS:
                return Material.CARROT;
            case POTATOES:
                return Material.POTATO;
            case BEETROOTS:
                return Material.BEETROOT_SEEDS;
            case NETHER_WART:
                return Material.NETHER_WART;
            default:
                return null;
        }
    }

    private Material getCropForSeed(Material seed) {
        if (seed == null) return null;
        switch (seed) {
            case WHEAT_SEEDS:
                return Material.WHEAT;
            case CARROT:
                return Material.CARROTS;
            case POTATO:
                return Material.POTATOES;
            case BEETROOT_SEEDS:
                return Material.BEETROOTS;
            case NETHER_WART:
                return Material.NETHER_WART;
            default:
                return null;
        }
    }

    private boolean hasItem(Player player, Material type) {
        if (player == null || type == null) return false;
        return player.getInventory().contains(type);
    }

    private boolean removeOne(Player player, Material type) {
        if (player == null || type == null) return false;
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item == null || item.getType() != type) continue;
            int amt = item.getAmount();
            if (amt <= 0) continue;
            if (amt == 1) {
                contents[i] = null;
            } else {
                item.setAmount(amt - 1);
                contents[i] = item;
            }
            player.getInventory().setContents(contents);
            return true;
        }
        return false;
    }

    private boolean canPlantOn(Material soilType, Material seed) {
        if (soilType == null || seed == null) return false;
        if (soilType == Material.FARMLAND) {
            return seed == Material.WHEAT_SEEDS
                    || seed == Material.CARROT
                    || seed == Material.POTATO
                    || seed == Material.BEETROOT_SEEDS;
        }
        if (soilType == Material.SOUL_SAND) {
            return seed == Material.NETHER_WART;
        }
        return false;
    }

    private void plantCrop(Block soil, Material cropType) {
        Block cropBlock = soil.getRelative(0, 1, 0);
        cropBlock.setType(cropType, false);
        BlockData data = cropBlock.getBlockData();
        if (data instanceof Ageable) {
            Ageable ageable = (Ageable) data;
            ageable.setAge(0);
            cropBlock.setBlockData(ageable, false);
        }
    }
}
