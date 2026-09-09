package me.aquaenchants.listener;

import me.aquaenchants.AquaEnchatsPlugin;
import me.aquaenchants.excavator.*;
import me.aquaenchants.hook.WorldGuardHook;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.bukkit.FluidCollisionMode;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Lightable;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Обновлённый слушатель для работы экскаватора
 */
public class ExcavatorListener implements Listener {

    private final AquaEnchatsPlugin plugin;
    private final ExcavatorManager manager;
    private final ExcavatorProcessor processor;
    private final NamespacedKey customItemIdKey;
    private final NamespacedKey excavatorCrystalKey;
    private final NamespacedKey excavatorHologramKey;
    
    // Открытые GUI
    private final Map<Player, ExcavatorGUI> openGuis = new HashMap<>();

    /**
     * При установке блока клиент делает взмах рукой (PlayerAnimationEvent),
     * из-за чего наш фоллбек-демонтаж мог срабатывать СРАЗУ после установки.
     * Делаем небольшой "грейс-период" после установки, когда взмах игнорируется.
     */
    private final Map<UUID, Long> swingIgnoreUntil = new ConcurrentHashMap<>();

    public ExcavatorListener(AquaEnchatsPlugin plugin, ExcavatorManager manager, ExcavatorProcessor processor) {
        this.plugin = plugin;
        this.manager = manager;
        this.processor = processor;
        this.customItemIdKey = new NamespacedKey(plugin, "custom_item_id");
        this.excavatorCrystalKey = new NamespacedKey(plugin, "excavator_crystal");
        this.excavatorHologramKey = new NamespacedKey(plugin, "excavator_hologram");
    }

    /**
     * Общая логика демонтажа экскаватора.
     * Должна работать в любой момент: сразу после установки и во время раскопки.
     */
    private void demountExcavator(Block block, ExcavatorData data, Player actor) {
        if (block == null || data == null) return;

        Location loc = block.getLocation();

        // Точку дропа делаем "на поверхность" над местом экскаватора
        Location dropLoc = getSurfaceDropLocation(loc);

        // Останавливаем процесс, если работает
        if (data.isWorking()) {
            processor.stopExcavation(data);
        }

        // Удаляем визуальные сущности/голограммы
        removeVisuals(data.getLocation());

        // Удаляем данные
        manager.removeExcavator(data.getLocation());

        // Убираем блок
        block.setType(Material.AIR);

        // Дропаем сам экскаватор
        ItemStack excavatorItem = plugin.getCustomItemManager().getItem("excavator");
        if (excavatorItem != null) {
            block.getWorld().dropItemNaturally(dropLoc, excavatorItem);
        }

        // Дропаем остаток топлива (аметист)
        int fuelLeft = Math.max(0, data.getFuel());
        while (fuelLeft > 0) {
            int stack = Math.min(64, fuelLeft);
            fuelLeft -= stack;
            block.getWorld().dropItemNaturally(dropLoc, new ItemStack(Material.AMETHYST_SHARD, stack));
        }

        // Дропаем всё, что успел накопать (руды/ресурсы) — тоже на поверхность
        dropCollectedOres(block.getWorld(), dropLoc, data);

        if (actor != null) {
            actor.sendMessage(ChatColor.YELLOW + "Экскаватор демонтирован.");
        }
    }

    /**
     * Находит локацию для дропа "на поверхности" (над самым верхним блоком).
     */
    private Location getSurfaceDropLocation(Location base) {
        if (base == null || base.getWorld() == null) return base;
        World w = base.getWorld();
        int x = base.getBlockX();
        int z = base.getBlockZ();
        int y = w.getHighestBlockYAt(x, z);
        return new Location(w, x + 0.5, y + 1.1, z + 0.5);
    }

    /**
     * Дропнуть накопанные предметы (агрегируем в стаки).
     */
    private void dropCollectedOres(World world, Location dropLoc, ExcavatorData data) {
        if (world == null || dropLoc == null || data == null) return;

        java.util.Map<Material, Integer> counts = new java.util.HashMap<>();
        for (String name : data.getCollectedItems()) {
            if (name == null || name.isEmpty()) continue;
            try {
                Material m = Material.valueOf(name);
                if (m == Material.AIR) continue;
                counts.put(m, counts.getOrDefault(m, 0) + 1);
            } catch (IllegalArgumentException ignored) {
            }
        }

        for (java.util.Map.Entry<Material, Integer> e : counts.entrySet()) {
            Material m = e.getKey();
            int amount = e.getValue();
            while (amount > 0) {
                int stack = Math.min(64, amount);
                amount -= stack;
                world.dropItemNaturally(dropLoc, new ItemStack(m, stack));
            }
        }
    }

    /**
     * Включить/выключить "свет" у блока, если у его BlockData есть свойство lit.
     * Нужен для COPPER_BULB / REDSTONE_LAMP и т.п.
     */
    private void setLitIfPossible(Block block, boolean lit) {
        if (block == null) return;
        try {
            BlockData bd = block.getBlockData();
            if (bd instanceof Lightable lightable) {
                lightable.setLit(lit);
                block.setBlockData(lightable, false);
                return;
            }

            // Фоллбек через рефлексию: некоторые типы BlockData имеют setLit(boolean),
            // но не реализуют интерфейс Lightable.
            Method m = bd.getClass().getMethod("setLit", boolean.class);
            m.invoke(bd, lit);
            block.setBlockData(bd, false);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Попытка найти экскаватор по позиции сущности (кристалл/голограмма может быть выше блока).
     */
    private ExcavatorData findExcavatorByEntityLocation(Location entityLoc) {
        if (entityLoc == null) return null;
        // Проверяем текущий блок и несколько блоков ниже
        Location base = entityLoc.getBlock().getLocation();
        for (int dy = 0; dy <= 3; dy++) {
            ExcavatorData data = manager.getExcavator(base.clone().subtract(0, dy, 0));
            if (data != null) return data;
        }
        return null;
    }

    private boolean isExcavatorItem(ItemStack item) {
        if (item == null) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String id = pdc.get(customItemIdKey, PersistentDataType.STRING);
        return "excavator".equalsIgnoreCase(id);
    }

    /**
     * Запрет переименования экскаватора в наковальне
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        AnvilInventory inv = event.getInventory();
        ItemStack first = inv.getItem(0);
        if (isExcavatorItem(first)) {
            event.setResult(null);
        }
    }

    /**
     * Установка экскаватора
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPlace(BlockPlaceEvent event) {
        ItemStack inHand = event.getItemInHand();
        if (!isExcavatorItem(inHand)) {
            return;
        }

        Player player = event.getPlayer();
        Block block = event.getBlockPlaced();

        // На всякий случай принудительно делаем блоком экскаватора COPPER_BULB и включаем "свет".
        // (Чтобы он выглядел как "запитанный фонарь".)
        try {
            block.setType(Material.COPPER_BULB, false);
            setLitIfPossible(block, true);
        } catch (Throwable ignored) {
        }
        Chunk chunk = block.getChunk();

        // Проверка доступа к чанку
        if (!canUseExcavatorInChunk(player, chunk)) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "Установка невозможна: чанк пересекается с чужим регионом.");
            return;
        }

        // Создаём данные экскаватора
        Location loc = block.getLocation();
        manager.addExcavator(loc, 0); // начальное топливо 0
        
        // Создаём визуальные эффекты
        Bukkit.getScheduler().runTask(plugin, () -> spawnVisuals(loc));

        // После установки клиент может послать взмах рукой (анимацию),
        // поэтому на короткое время игнорируем фоллбек-демонтаж по swing.
        swingIgnoreUntil.put(player.getUniqueId(), System.currentTimeMillis() + 650L);
        
        player.sendMessage(ChatColor.GREEN + "Экскаватор установлен! ПКМ для открытия меню.");
    }

    /**
     * Проверка доступа к чанку через WorldGuard
     */
    private boolean canUseExcavatorInChunk(Player player, Chunk chunk) {
        if (player == null || chunk == null) return false;

        World world = chunk.getWorld();
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minY; y < maxY; y++) {
                    Block b = chunk.getBlock(x, y, z);
                    if (!WorldGuardHook.canBuild(player, b.getLocation())) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * Клик ПКМ по экскаватору - открытие GUI
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        
        Block block = event.getClickedBlock();
        if (block == null) return;
        
        // Проверяем, экскаватор ли это
        ExcavatorData data = manager.getExcavator(block.getLocation());
        if (data == null) return;
        
        event.setCancelled(true);
        
        Player player = event.getPlayer();
        
        // Открываем GUI (топливо должно отображаться сразу)
        ExcavatorGUI gui = new ExcavatorGUI(data);
        openGuis.put(player, gui);
        gui.open(player);
    }

    
    /**
     * Демонтаж экскаватора по ЛКМ (работает и в survival, и во время копки).
     * Не зависит от того, будет ли BlockBreakEvent отменён другими плагинами.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onLeftClickDemount(PlayerInteractEvent event) {
        if (event.getAction() != Action.LEFT_CLICK_BLOCK) return;

        Block block = event.getClickedBlock();
        if (block == null) return;

        Location loc = block.getLocation();
        ExcavatorData data = manager.getExcavator(loc);
        if (data == null) return;

        // Не даём обычному блоку "получать урон", сами демонтируем.
        event.setCancelled(true);

        demountExcavator(block, data, event.getPlayer());
    }

    /**
     * Некоторые сборки/режимы могут присылать LEFT_CLICK_AIR вместо LEFT_CLICK_BLOCK.
     * Поэтому делаем фоллбек: лучом ищем блок перед игроком и демонтируем, если это экскаватор.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onLeftClickAirDemount(PlayerInteractEvent event) {
        if (event.getAction() != Action.LEFT_CLICK_AIR) return;
        Player player = event.getPlayer();
        Block hit = rayTraceBlock(player, 5.0);
        if (hit == null) return;
        ExcavatorData data = manager.getExcavator(hit.getLocation());
        if (data == null) return;
        demountExcavator(hit, data, player);
    }

    /**
     * Фоллбек демонтажа по "взмаху" (анимации удара).
     * Нужен для серверов, где урон по кристаллу/сущностям запрещён (и EntityDamageByEntityEvent не приходит),
     * но игрок всё равно может ЛКМ пытаться "сломать" экскаватор.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onSwingDemount(PlayerAnimationEvent event) {
        Player player = event.getPlayer();
        if (player == null) return;

        // При установке/ПКМ взаимодействии клиент тоже делает взмах рукой,
        // поэтому если экскаватор только что был установлен, не демонтируем.
        Long until = swingIgnoreUntil.get(player.getUniqueId());
        if (until != null && System.currentTimeMillis() < until) {
            return;
        }

        // Сначала пытаемся попасть в визуальную сущность лучом.
        // В некоторых Bukkit/Spigot API нет Player#rayTraceEntities, поэтому используем World#rayTraceEntities.
        try {
            Location eye = player.getEyeLocation();
            Vector dir = eye.getDirection();
            RayTraceResult ray = player.getWorld().rayTraceEntities(
                    eye,
                    dir,
                    5.0,
                    entity -> entity != null && entity != player
            );
            if (ray != null && ray.getHitEntity() != null) {
                Entity hit = ray.getHitEntity();
                PersistentDataContainer pdc = hit.getPersistentDataContainer();
                boolean isExcavatorVisual = pdc.has(excavatorCrystalKey, PersistentDataType.STRING)
                        || pdc.has(excavatorHologramKey, PersistentDataType.STRING);
                if (isExcavatorVisual) {
                    ExcavatorData data = findExcavatorByEntityLocation(hit.getLocation());
                    if (data != null && data.getLocation() != null) {
                        demountExcavator(data.getLocation().getBlock(), data, player);
                    }
                }
            }

            // Если по сущностям не попали — пробуем демонтировать по блоку (лучом).
            Block hitBlock = rayTraceBlock(player, 5.0);
            if (hitBlock != null) {
                ExcavatorData data = manager.getExcavator(hitBlock.getLocation());
                if (data != null) {
                    demountExcavator(hitBlock, data, player);
                }
            }
        } catch (Throwable ignored) {
            // некоторые ядра могут не поддерживать rayTraceEntities в старых сборках
        }
    }

    private Block rayTraceBlock(Player player, double maxDistance) {
        if (player == null) return null;
        World w = player.getWorld();
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection();
        RayTraceResult res = w.rayTraceBlocks(eye, dir, maxDistance, FluidCollisionMode.NEVER, true);
        if (res == null) return null;
        return res.getHitBlock();
    }

    /**
     * Демонтаж при ударе по визуальным сущностям (кристалл/голограмма).
     * Это нужно, потому что игрок часто "попадает" именно в кристалл, а не в блок.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onExcavatorEntityHit(EntityDamageByEntityEvent event) {
        Entity entity = event.getEntity();
        if (entity == null) return;

        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        boolean isExcavatorVisual = pdc.has(excavatorCrystalKey, PersistentDataType.STRING)
                || pdc.has(excavatorHologramKey, PersistentDataType.STRING);
        if (!isExcavatorVisual) return;

        // Не даём ломать визуалки обычным способом
        event.setCancelled(true);

        if (!(event.getDamager() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getDamager();

        ExcavatorData data = findExcavatorByEntityLocation(entity.getLocation());
        if (data == null || data.getLocation() == null) return;

        Block block = data.getLocation().getBlock();
        demountExcavator(block, data, player);
    }

/**
     * Обработка кликов в GUI экскаватора
     */
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        
        Player player = (Player) event.getWhoClicked();
        ExcavatorGUI gui = openGuis.get(player);
        
        if (gui == null) return;
        if (!event.getInventory().equals(gui.getInventory())) return;
        
        int slot = event.getRawSlot();
        
        // Разрешаем класть/брать аметисты только в топливные слоты
        if (ExcavatorGUI.isFuelSlot(slot)) {
            ItemStack cursor = event.getCursor();
            ItemStack current = event.getCurrentItem();
            
            // Проверяем, что кладут именно аметисты
            if (cursor != null && cursor.getType() != Material.AIR) {
                if (cursor.getType() != Material.AMETHYST_SHARD) {
                    event.setCancelled(true);
                    player.sendMessage(ChatColor.RED + "Сюда можно класть только аметисты!");
                    return;
                }
            }
            
            // После клика синхронизируем топливо и обновляем голограмму сразу
            Bukkit.getScheduler().runTask(plugin, () -> {
                gui.syncFuelToData();
                ExcavatorData data = gui.getData();
                manager.updateExcavator(data);
                processor.updateHologram(data);
            });
            return;
        }
        
        // Обработка кнопок
        if (ExcavatorGUI.isButtonSlot(slot)) {
            event.setCancelled(true);
            
            ExcavatorData data = gui.getData();
            
            // Сохраняем текущие аметисты как топливо (без удаления предметов)
            gui.syncFuelToData();
            manager.updateExcavator(data);
            processor.updateHologram(data);
            
            // Определяем режим
            ExcavatorData.ExcavatorMode mode;
            if (ExcavatorGUI.isBelowButton(slot)) {
                mode = ExcavatorData.ExcavatorMode.BELOW;
            } else {
                mode = ExcavatorData.ExcavatorMode.CHUNK;
            }
            
            // Закрываем GUI
            player.closeInventory();
            openGuis.remove(player);
            
            // Запускаем процесс
            processor.startExcavation(player, data, mode);
            return;
        }
        
        // Все остальные слоты заблокированы
        if (slot < gui.getInventory().getSize()) {
            event.setCancelled(true);
        }
    }

    /**
     * Перетаскивание предметов в GUI (drag) — тоже должно обновлять топливо/голограмму.
     */
    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        ExcavatorGUI gui = openGuis.get(player);
        if (gui == null) return;
        if (!event.getInventory().equals(gui.getInventory())) return;

        // Если драг затрагивает топливные слоты — синхронизируем в следующий тик
        boolean touchesFuel = event.getRawSlots().stream().anyMatch(ExcavatorGUI::isFuelSlot);
        if (!touchesFuel) return;

        Bukkit.getScheduler().runTask(plugin, () -> {
            gui.syncFuelToData();
            ExcavatorData data = gui.getData();
            manager.updateExcavator(data);
            processor.updateHologram(data);
        });
    }

    /**
     * Закрытие GUI - сохраняем топливо
     */
    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) return;

        Player player = (Player) event.getPlayer();
        ExcavatorGUI gui = openGuis.remove(player);

        if (gui != null) {
            // Просто сохраняем текущее количество аметистов как топливо.
            // Ничего не удаляем/"съедаем".
            gui.syncFuelToData();
            ExcavatorData data = gui.getData();
            manager.updateExcavator(data);
            // Обновляем голограмму с топливом
            processor.updateHologram(data);
        }
    }

    /**
     * Разрушение блока экскаватора
     */
    @EventHandler(ignoreCancelled = false, priority = EventPriority.HIGHEST)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Location loc = block.getLocation();

        ExcavatorData data = manager.getExcavator(loc);
        if (data == null) return;

        // Отменяем стандартную логику ломания и делаем демонтаж сами
        event.setCancelled(true);
        event.setDropItems(false);

        demountExcavator(block, data, event.getPlayer());
    }

    /**
     * Создать визуальные эффекты (кристалл и голограмма)
     */
    private void spawnVisuals(Location blockLoc) {
        World world = blockLoc.getWorld();
        if (world == null) return;

        Location base = blockLoc.clone().add(0.5, 0.0, 0.5);

        // Кристалл энда внутри блока
        world.spawn(base.clone().add(0, -0.2, 0), EnderCrystal.class, c -> {
            c.setShowingBottom(false);
            // ВАЖНО: не делаем invulnerable, иначе на некоторых ядрах удар по кристаллу
            // может не генерировать EntityDamageByEntityEvent в survival.
            // Мы сами отменяем урон в обработчике и демонтируем экскаватор.
            c.setInvulnerable(false);
            c.setGlowing(true);
            c.setCustomNameVisible(false);
            try {
                c.setGravity(false);
            } catch (Throwable ignored) {}
            PersistentDataContainer pdc = c.getPersistentDataContainer();
            pdc.set(excavatorCrystalKey, PersistentDataType.STRING, "true");
        });

        // Голограмма
        ExcavatorData data = manager.getExcavator(blockLoc);
        String text = ChatColor.AQUA + "Топливо: " + ChatColor.WHITE + (data != null ? data.getFuel() : 0);

        world.spawn(base.clone().add(0, 1.2, 0), ArmorStand.class, as -> {
            as.setInvisible(true);
            as.setMarker(true);
            as.setSmall(true);
            as.setGravity(false);
            as.setCustomNameVisible(true);
            as.setCustomName(text);
            // Аналогично: не делаем invulnerable ради корректного перехвата удара.
            as.setInvulnerable(false);
            PersistentDataContainer pdc = as.getPersistentDataContainer();
            pdc.set(excavatorHologramKey, PersistentDataType.STRING, "true");
        });
    }

    /**
     * Удалить визуальные эффекты
     */
    private void removeVisuals(Location loc) {
        World world = loc.getWorld();
        if (world == null) return;

        Location center = loc.clone().add(0.5, 0.5, 0.5);
        for (Entity entity : world.getNearbyEntities(center, 2.0, 3.0, 2.0)) {
            PersistentDataContainer pdc = entity.getPersistentDataContainer();
            if (pdc.has(excavatorCrystalKey, PersistentDataType.STRING)
                || pdc.has(excavatorHologramKey, PersistentDataType.STRING)) {
                entity.remove();
            }
        }
    }


}
