package me.aquaenchants.listener;

import me.aquaenchants.AquaEnchatsPlugin;
import me.aquaenchants.enchant.CustomEnchant;
import me.aquaenchants.enchant.EnchantManager;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.enchantments.EnchantmentOffer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.enchantment.PrepareItemEnchantEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/**
 * Enchanting table integration:
 * - Slot 0 (first offer) can display a real, registry-registered custom enchant (Paper bootstrap registry).
 * - Slots 1/2 remain fully vanilla (no sorting / no forcing), so randomness stays intact.
 * - When the player selects the custom offer, we replace the marker enchant with the real custom enchant data.
 */
public class EnchantTableListener implements Listener {

    private final AquaEnchatsPlugin plugin;
    private final EnchantManager enchantManager;
    private final Random random = new Random();

    private static class Session {
        String customIdSlot0;
        Enchantment markerSlot0;
    }

    private final Map<UUID, Session> sessions = new HashMap<>();

    // Offer refresh guard (fixes "blank list until click" on some 1.21.x combos)
    private final Set<UUID> offerRefreshCooldown = new HashSet<>();

    // Lapis loss guard
    private final Map<UUID, Integer> lapisTotalsBeforeClick = new HashMap<>();
    private final Set<UUID> skipLapisRestoreNextTick = new HashSet<>();
    private final Set<UUID> recentlyEnchanted = new HashSet<>();

    public EnchantTableListener(AquaEnchatsPlugin plugin, EnchantManager enchantManager) {
        this.plugin = plugin;
        this.enchantManager = enchantManager;
    }

    private static boolean isEnchantingView(Inventory top) {
        return top != null && top.getType() == InventoryType.ENCHANTING;
    }

    private void forceOfferRefreshNextTick(Player player) {
        UUID uuid = player.getUniqueId();
        if (offerRefreshCooldown.contains(uuid)) return;
        offerRefreshCooldown.add(uuid);

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            try {
                offerRefreshCooldown.remove(uuid);
                if (!player.isOnline()) return;

                Inventory top = player.getOpenInventory().getTopInventory();
                if (!isEnchantingView(top)) return;

                ItemStack it0 = top.getItem(0);
                ItemStack it1 = top.getItem(1);
                top.setItem(0, it0);
                top.setItem(1, it1);
                player.updateInventory();
            } catch (Throwable ignored) {
            }
        }, 1L);
    }

    private static int countLapis(ItemStack stack) {
        if (stack == null) return 0;
        if (stack.getType() != Material.LAPIS_LAZULI) return 0;
        return Math.max(0, stack.getAmount());
    }

    private static int totalLapis(Player player, Inventory enchantingTop, ItemStack cursor) {
        int total = 0;
        if (enchantingTop != null && enchantingTop.getSize() > 1) {
            total += countLapis(enchantingTop.getItem(1));
        }
        total += countLapis(cursor);
        for (ItemStack it : player.getInventory().getContents()) {
            total += countLapis(it);
        }
        return total;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepare(PrepareItemEnchantEvent event) {
        Player player = event.getEnchanter();
        if (player == null) return;

        ItemStack item = event.getItem();
        if (item == null || item.getType() == Material.AIR) return;

        EnchantmentOffer[] offers = event.getOffers();
        if (offers == null || offers.length < 3) return;

        // Fix: sometimes offers are all null until an extra click.
        if (offers[0] == null && offers[1] == null && offers[2] == null) {
            forceOfferRefreshNextTick(player);
            sessions.remove(player.getUniqueId());
            return;
        }

        // We only inject into the FIRST offer (slot 0). Slots 1/2 remain vanilla.
        EnchantmentOffer vanilla0 = offers[0];
        if (vanilla0 == null) {
            sessions.remove(player.getUniqueId());
            return;
        }

        boolean isBook = item.getType() == Material.BOOK || item.getType() == Material.ENCHANTED_BOOK;

        // Collect eligible custom enchants (same rules as before)
        List<CustomEnchant> eligible = new ArrayList<>();
        for (CustomEnchant ce : enchantManager.getAll()) {
            if (ce == null) continue;
            if (!"LEGENDA".equalsIgnoreCase(ce.getGroup())) continue;
            if (!ce.isEnchantTableEnabled()) continue;
            int chance = ce.getEnchantTableChance();
            if (chance <= 0) continue;
            if (!isBook && !enchantManager.canApply(ce, item)) continue;
            eligible.add(ce);
        }

        if (eligible.isEmpty()) {
            sessions.remove(player.getUniqueId());
            return;
        }

        // Choose ONE custom enchant for slot 0 by chance roll
        // (Do NOT sort, do NOT force, keep randomness natural.)
        List<CustomEnchant> passed = new ArrayList<>();
        for (CustomEnchant ce : eligible) {
            int roll = random.nextInt(100) + 1;
            if (roll <= ce.getEnchantTableChance()) {
                passed.add(ce);
            }
        }
        if (passed.isEmpty()) {
            sessions.remove(player.getUniqueId());
            return;
        }

        CustomEnchant chosen = passed.get(random.nextInt(passed.size()));
        Enchantment marker = enchantManager.getTableDisplayEnchant(chosen.getId());
        if (marker == null) {
            sessions.remove(player.getUniqueId());
            return;
        }

        // Replace ONLY offer #0 with our registered marker, keep vanilla cost
        offers[0] = new EnchantmentOffer(marker, 1, vanilla0.getCost());

        Session session = new Session();
        session.customIdSlot0 = chosen.getId();
        session.markerSlot0 = marker;
        sessions.put(player.getUniqueId(), session);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEnchant(EnchantItemEvent event) {
        Player player = event.getEnchanter();
        if (player == null) return;

        Session session = sessions.get(player.getUniqueId());
        if (session == null) return;

        // Only care about slot 0 marker
        if (event.getEnchantsToAdd() == null || session.markerSlot0 == null) return;
        if (!event.getEnchantsToAdd().containsKey(session.markerSlot0)) return;

        String customId = session.customIdSlot0;
        if (customId == null) return;

        CustomEnchant ce = enchantManager.getEnchant(customId);
        if (ce == null) return;

        ItemStack item = event.getItem();
        if (item == null || item.getType() == Material.AIR) return;

        boolean isBook = item.getType() == Material.BOOK || item.getType() == Material.ENCHANTED_BOOK;
        if (!isBook && !enchantManager.canApply(ce, item)) return;

        // Stop vanilla from applying the marker enchant to the item.
        event.getEnchantsToAdd().clear();

        // Vanilla will still consume XP/lapis, that's fine. We just change the enchant result.
        if (item.getType() == Material.BOOK) {
            item.setType(Material.ENCHANTED_BOOK);
        }

        Map<CustomEnchant, Integer> current = new HashMap<>(enchantManager.getEnchantmentsOnItem(item));
        current.put(ce, 1);
        enchantManager.setEnchantmentsOnItem(item, current);

        UUID uuid = player.getUniqueId();
        recentlyEnchanted.add(uuid);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> recentlyEnchanted.remove(uuid), 2L);

        sessions.remove(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onEnchantingClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory top = event.getView().getTopInventory();
        if (!isEnchantingView(top)) return;

        ClickType clickType = event.getClick();
        if (clickType == ClickType.DROP || clickType == ClickType.CONTROL_DROP) return;
        if (event.getRawSlot() == -999) return;

        UUID uuid = player.getUniqueId();
        if (skipLapisRestoreNextTick.contains(uuid)) return;

        int before = totalLapis(player, top, event.getCursor());
        lapisTotalsBeforeClick.put(uuid, before);

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            try {
                if (!player.isOnline()) return;
                if (recentlyEnchanted.contains(uuid)) return;

                Inventory currentTop = player.getOpenInventory().getTopInventory();
                if (!isEnchantingView(currentTop)) return;

                int after = totalLapis(player, currentTop, player.getItemOnCursor());
                Integer prev = lapisTotalsBeforeClick.remove(uuid);
                if (prev == null) return;
                if (after >= prev) return;

                int missing = prev - after;
                if (missing <= 0) return;

                player.getInventory().addItem(new ItemStack(Material.LAPIS_LAZULI, missing));
                player.updateInventory();
            } catch (Throwable ignored) {
            }
        }, 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onEnchantingDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory top = event.getView().getTopInventory();
        if (!isEnchantingView(top)) return;

        UUID uuid = player.getUniqueId();
        if (skipLapisRestoreNextTick.contains(uuid)) return;
        skipLapisRestoreNextTick.add(uuid);

        int before = totalLapis(player, top, event.getOldCursor());

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            try {
                skipLapisRestoreNextTick.remove(uuid);
                if (!player.isOnline()) return;
                if (recentlyEnchanted.contains(uuid)) return;

                Inventory currentTop = player.getOpenInventory().getTopInventory();
                if (!isEnchantingView(currentTop)) return;

                int after = totalLapis(player, currentTop, player.getItemOnCursor());
                if (after >= before) return;

                int missing = before - after;
                if (missing <= 0) return;

                player.getInventory().addItem(new ItemStack(Material.LAPIS_LAZULI, missing));
                player.updateInventory();
            } catch (Throwable ignored) {
            }
        }, 1L);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player p) {
            UUID uuid = p.getUniqueId();
            sessions.remove(uuid);
            lapisTotalsBeforeClick.remove(uuid);
            skipLapisRestoreNextTick.remove(uuid);
            recentlyEnchanted.remove(uuid);
            offerRefreshCooldown.remove(uuid);
        }
    }
}
