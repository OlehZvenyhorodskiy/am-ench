package me.aquaenchants.separation;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Обработчики GUI для /separation.
 */
public final class SeparationListener implements Listener {

    private final SeparationManager manager;

    public SeparationListener(SeparationManager manager) {
        this.manager = manager;
    }

    @EventHandler
    public void onInvClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player player)) return;

        var view = e.getView();
        Inventory top = view.getTopInventory();
        if (manager.isInputInventory(view)) {
            // Разрешаем игроку класть предмет только в центральный слот
            if (e.getClickedInventory() == null) return;

            int slot = e.getRawSlot();
            if (slot < top.getSize()) {
                if (slot != SeparationManager.INPUT_SLOT) {
                    e.setCancelled(true);
                    return;
                }

                // Клик по центральному слоту
                ItemStack cursor = e.getCursor();
                ItemStack current = e.getCurrentItem();

                // Если игрок пытается положить предмет курсором
                if (cursor != null && cursor.getType() != org.bukkit.Material.AIR) {
                    e.setCancelled(true);
                    // Берём 1 предмет
                    ItemStack one = cursor.clone();
                    one.setAmount(1);
                    if (!manager.canSeparate(one)) {
                        player.sendMessage(ChatColor.RED + "Этот предмет не содержит зачарований.");
                        return;
                    }
                    cursor.setAmount(cursor.getAmount() - 1);
                    if (cursor.getAmount() <= 0) {
                        e.setCursor(null);
                    } else {
                        e.setCursor(cursor);
                    }
                    manager.acceptItem(player, one);
                    return;
                }

                // Shift-click из своего инвентаря
                if (e.isShiftClick() && e.getClickedInventory() != top) {
                    ItemStack clicked = e.getCurrentItem();
                    if (clicked != null && clicked.getType() != org.bukkit.Material.AIR) {
                        e.setCancelled(true);
                        ItemStack one = clicked.clone();
                        one.setAmount(1);
                        if (!manager.canSeparate(one)) {
                            player.sendMessage(ChatColor.RED + "Этот предмет не содержит зачарований.");
                            return;
                        }
                        clicked.setAmount(clicked.getAmount() - 1);
                        if (clicked.getAmount() <= 0) {
                            e.setCurrentItem(null);
                        } else {
                            e.setCurrentItem(clicked);
                        }
                        manager.acceptItem(player, one);
                    }
                    return;
                }

                // Удалять/перетаскивать из GUI не даём
                e.setCancelled(true);
                return;
            }

            // Клики в нижнем инвентаре: если shift-click — перехватываем
            if (e.isShiftClick()) {
                ItemStack clicked = e.getCurrentItem();
                if (clicked != null && clicked.getType() != org.bukkit.Material.AIR) {
                    e.setCancelled(true);
                    ItemStack one = clicked.clone();
                    one.setAmount(1);
                    if (!manager.canSeparate(one)) {
                        player.sendMessage(ChatColor.RED + "Этот предмет не содержит зачарований.");
                        return;
                    }
                    clicked.setAmount(clicked.getAmount() - 1);
                    if (clicked.getAmount() <= 0) {
                        e.setCurrentItem(null);
                    } else {
                        e.setCurrentItem(clicked);
                    }
                    manager.acceptItem(player, one);
                }
            }
            return;
        }

        if (manager.isSelectInventory(view)) {
            // Запрещаем любые перемещения в этом GUI, кроме выбора слота с зачарованием
            if (e.getRawSlot() < top.getSize()) {
                e.setCancelled(true);
                manager.handleSelectClick(player, e.getRawSlot());
            } else {
                e.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onInvDrag(InventoryDragEvent e) {
        var view = e.getView();
        if (manager.isInputInventory(view) || manager.isSelectInventory(view)) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onInvClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player player)) return;
        var view = e.getView();
        // Закрытие любого из наших GUI = возврат предмета и очистка.
        // ВАЖНО: при обновлении/перерисовке мы тоже закрываем и открываем инвентарь,
        // поэтому делаем проверку с задержкой 1 тик.
        if (manager.isInputInventory(view) || manager.isSelectInventory(view)) {
            org.bukkit.Bukkit.getScheduler().runTask(manager.getPlugin(), () -> {
                if (!player.isOnline()) return;
                var nowView = player.getOpenInventory();
                if (manager.isInputInventory(nowView) || manager.isSelectInventory(nowView)) {
                    return; // игрок всё ещё в процессе
                }
                manager.returnAndClear(player);
            });
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        manager.returnAndClear(e.getPlayer());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        // Если после перезагрузки/краша остался сохранённый предмет — возвращаем его игроку.
        manager.returnFromStorageIfPresent(e.getPlayer());
    }
}
