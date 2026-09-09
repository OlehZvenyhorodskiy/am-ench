package me.aquaenchants.listener;

import me.aquaenchants.AquaEnchatsPlugin;
import me.aquaenchants.enchant.CustomEnchant;
import me.aquaenchants.enchant.EnchantManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 5-Row (45 slots) Animated Custom Enchanting Table GUI for Paper 1.21.8.
 *
 * Features:
 * - 45-slot expansive layout (5 rows of 9 blocks).
 * - Smooth rotating cosmic wave animation on outer border.
 * - Bookshelf statistics indicator with graphical progress bar, percentage, and missing count to max.
 * - Balanced base chances (Slot 1: ~15-22%, Slot 2: ~28-40%, Slot 3: ~42-60%).
 * - Distinct enchanting ritual effects: custom enchants burst celestial stars; vanilla bursts glyphs.
 * - Complete book-to-enchanted-book conversion and safe item recovery.
 */
public final class CustomEnchantingTableGUIListener implements Listener {

    private static final String TITLE = ChatColor.DARK_AQUA + "✦ Стол зачарований ✦";
    private static final int SIZE = 45;

    // GUI slot coordinates
    private static final int SLOT_HEADER = 4;
    private static final int SLOT_ITEM = 10;
    private static final int SLOT_OFFER_1 = 12;
    private static final int SLOT_DIVIDER_1 = 13;
    private static final int SLOT_OFFER_2 = 14;
    private static final int SLOT_DIVIDER_2 = 15;
    private static final int SLOT_OFFER_3 = 16;

    private static final int SLOT_LAPIS = 19;
    private static final int SLOT_STATUS_1 = 21;
    private static final int SLOT_LAPIS_INFO = 22;
    private static final int SLOT_STATUS_2 = 23;
    private static final int SLOT_STATUS_3 = 25;

    private static final int SLOT_GUIDE = 30;
    private static final int SLOT_BOOKSHELF_STATS = 31;
    private static final int SLOT_RITUAL_CORE = 32;

    // Clockwise border slots for smooth rotating color wave
    private static final int[] BORDER_SLOTS = {
            0, 1, 2, 3, 5, 6, 7, 8,
            17, 26, 35, 44, 43, 42, 41, 40, 39, 38, 37, 36,
            27, 18, 9
    };

    // Cosmic color wave palette
    private static final Material[] WAVE_PALETTE = {
            Material.CYAN_STAINED_GLASS_PANE,
            Material.LIGHT_BLUE_STAINED_GLASS_PANE,
            Material.BLUE_STAINED_GLASS_PANE,
            Material.PURPLE_STAINED_GLASS_PANE,
            Material.MAGENTA_STAINED_GLASS_PANE,
            Material.PINK_STAINED_GLASS_PANE
    };

    private final AquaEnchatsPlugin plugin;
    private final EnchantManager enchantManager;
    private final me.aquaenchants.config.TableSettingsManager tableSettingsManager;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private BukkitTask animationTask;

    /**
     * "Зерно зачарования" игрока — аналог ванильного enchantment seed.
     * Хранится в PDC игрока (переживает перезаход) и меняется ТОЛЬКО после
     * успешного зачарования. Пока зерно не изменилось, один и тот же предмет
     * всегда показывает один и тот же список предложений — как в ванилле.
     */
    private final NamespacedKey keyEnchantSeed;

    public CustomEnchantingTableGUIListener(AquaEnchatsPlugin plugin, EnchantManager enchantManager, me.aquaenchants.config.TableSettingsManager tableSettingsManager) {
        this.plugin = plugin;
        this.enchantManager = enchantManager;
        this.tableSettingsManager = tableSettingsManager;
        this.keyEnchantSeed = new NamespacedKey(plugin, "enchant_seed");
        startAnimationTask();
    }

    private long getEnchantSeed(Player p) {
        try {
            PersistentDataContainer pdc = p.getPersistentDataContainer();
            Long seed = pdc.get(keyEnchantSeed, PersistentDataType.LONG);
            if (seed == null || seed == 0L) {
                long fresh = ThreadLocalRandom.current().nextLong();
                if (fresh == 0L) fresh = 1L;
                pdc.set(keyEnchantSeed, PersistentDataType.LONG, fresh);
                return fresh;
            }
            return seed;
        } catch (Throwable ignored) {
            return 1L;
        }
    }

    /**
     * Вызывается после успешного зачарования: как в ванилле, следующее
     * предложение генерируется заново только ПОСЛЕ использования стола.
     */
    private void rerollEnchantSeed(Player p) {
        try {
            long fresh = ThreadLocalRandom.current().nextLong();
            if (fresh == 0L) fresh = 1L;
            p.getPersistentDataContainer().set(keyEnchantSeed, PersistentDataType.LONG, fresh);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Идентичность предмета, влияющая на генерацию предложений: тип предмета
     * + его зачарования (кастомные, ванильные и записанные в книге).
     * Долговечность и прочие метаданные намеренно не учитываются (как в ванилле).
     */
    private long itemIdentity(ItemStack item) {
        List<String> parts = new ArrayList<>();
        parts.add(item.getType().name());
        try {
            if (item.hasItemMeta()) {
                ItemMeta meta = item.getItemMeta();
                if (meta != null) {
                    Map<Enchantment, Integer> vanilla = (meta instanceof EnchantmentStorageMeta esm)
                            ? esm.getStoredEnchants()
                            : meta.getEnchants();
                    if (vanilla != null) {
                        for (Map.Entry<Enchantment, Integer> e : vanilla.entrySet()) {
                            if (e.getKey() != null && e.getValue() != null) {
                                parts.add(e.getKey().getKey() + "=" + e.getValue());
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            for (Map.Entry<CustomEnchant, Integer> e : enchantManager.getEnchantmentsOnItem(item).entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    parts.add(e.getKey().getId() + "=" + e.getValue());
                }
            }
        } catch (Throwable ignored) {
        }
        Collections.sort(parts);
        // String#hashCode стабилен между запусками JVM — подходит для детерминизма
        return String.join(";", parts).hashCode();
    }

    private void startAnimationTask() {
        if (animationTask != null) {
            animationTask.cancel();
        }
        this.animationTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickAnimation, 4L, 4L);
    }

    private static final class Session {
        final UUID playerId;
        final Location tableLoc;
        final Inventory inv;
        Offer[] offers = new Offer[3];
        int animTick = 0;
        boolean inRitual = false;

        Session(UUID playerId, Location tableLoc, Inventory inv) {
            this.playerId = playerId;
            this.tableLoc = tableLoc;
            this.inv = inv;
        }
    }

    private static final class Offer {
        final String displayName;
        final String description;
        final String group;
        final int requiredLevel;
        final int lapisCost;
        final boolean isCustom;

        final Enchantment vanillaEnchant;
        final int vanillaLevel;
        final String customId;
        final int customLevel;

        private Offer(String displayName, String description, String group, int requiredLevel, int lapisCost,
                      Enchantment vanillaEnchant, int vanillaLevel,
                      String customId, int customLevel) {
            this.displayName = displayName;
            this.description = description;
            this.group = group;
            this.requiredLevel = requiredLevel;
            this.lapisCost = lapisCost;
            this.vanillaEnchant = vanillaEnchant;
            this.vanillaLevel = vanillaLevel;
            this.customId = customId;
            this.customLevel = customLevel;
            this.isCustom = customId != null;
        }

        static Offer vanilla(String displayName, int requiredLevel, int lapisCost, Enchantment ench, int level) {
            return new Offer(displayName, null, null, requiredLevel, lapisCost, ench, level, null, 0);
        }

        static Offer custom(String displayName, String description, String group, int requiredLevel, int lapisCost, String id, int level) {
            return new Offer(displayName, description, group, requiredLevel, lapisCost, null, 0, id, level);
        }
    }

    public void shutdown() {
        if (animationTask != null) {
            try {
                animationTask.cancel();
            } catch (Throwable ignored) {
            }
            animationTask = null;
        }

        for (Session s : new ArrayList<>(sessions.values())) {
            Player p = Bukkit.getPlayer(s.playerId);
            if (p != null && p.isOnline()) {
                ItemStack item = s.inv.getItem(SLOT_ITEM);
                ItemStack lapis = s.inv.getItem(SLOT_LAPIS);
                if (item != null && item.getType() != Material.AIR) giveOrDrop(p, item, s.tableLoc);
                if (lapis != null && lapis.getType() != Material.AIR) giveOrDrop(p, lapis, s.tableLoc);
                p.closeInventory();
            } else if (s.tableLoc != null && s.tableLoc.getWorld() != null) {
                ItemStack item = s.inv.getItem(SLOT_ITEM);
                ItemStack lapis = s.inv.getItem(SLOT_LAPIS);
                if (item != null && item.getType() != Material.AIR) s.tableLoc.getWorld().dropItemNaturally(s.tableLoc, item);
                if (lapis != null && lapis.getType() != Material.AIR) s.tableLoc.getWorld().dropItemNaturally(s.tableLoc, lapis);
            }
        }
        sessions.clear();
    }

    private void tickAnimation() {
        if (sessions.isEmpty()) return;

        for (Session s : sessions.values()) {
            s.animTick++;
            Inventory inv = s.inv;

            // Animate rotating border
            for (int i = 0; i < BORDER_SLOTS.length; i++) {
                int slot = BORDER_SLOTS[i];
                int paletteIndex = (s.animTick + i) % WAVE_PALETTE.length;
                Material mat = WAVE_PALETTE[paletteIndex];
                ItemStack current = inv.getItem(slot);
                if (current == null || current.getType() != mat) {
                    inv.setItem(slot, named(mat, " "));
                }
            }

            ItemStack targetItem = inv.getItem(SLOT_ITEM);
            boolean hasItem = targetItem != null && targetItem.getType() != Material.AIR && isEnchantable(targetItem);

            // Animate dividers (slots 13, 15)
            Material dividerMat = hasItem
                    ? ((s.animTick % 2 == 0) ? Material.PURPLE_STAINED_GLASS_PANE : Material.CYAN_STAINED_GLASS_PANE)
                    : Material.GRAY_STAINED_GLASS_PANE;
            inv.setItem(SLOT_DIVIDER_1, named(dividerMat, " "));
            inv.setItem(SLOT_DIVIDER_2, named(dividerMat, " "));

            // Animate Header (slot 4)
            int power = countBookshelves(s.tableLoc);
            if (!hasItem) {
                ItemStack info = named(Material.BOOK, ChatColor.GOLD + "✦ Стол зачарований ✦");
                ItemMeta meta = info.getItemMeta();
                if (meta != null) {
                    meta.setLore(Arrays.asList(
                            ChatColor.GRAY + "Положите предмет в слот слева,",
                            ChatColor.GRAY + "а лазурит в нижний слот.",
                            "",
                            ChatColor.DARK_GRAY + "Сила книжных полок: " + ChatColor.AQUA + power + " / 15"
                    ));
                    info.setItemMeta(meta);
                }
                inv.setItem(SLOT_HEADER, info);
            } else {
                String[] titles = {
                        ChatColor.AQUA + "✨ ВЫБЕРИТЕ ЗАЧАРОВАНИЕ ✨",
                        ChatColor.LIGHT_PURPLE + "✨ ВЫБЕРИТЕ ЗАЧАРОВАНИЕ ✨",
                        ChatColor.GOLD + "✨ ВЫБЕРИТЕ ЗАЧАРОВАНИЕ ✨"
                };
                String title = titles[(s.animTick / 3) % titles.length];
                ItemStack info = named(Material.ENCHANTED_BOOK, title);
                ItemMeta meta = info.getItemMeta();
                if (meta != null) {
                    meta.setLore(Arrays.asList(
                            ChatColor.GRAY + "Выберите один из трех свитков зачарования.",
                            ChatColor.GRAY + "Книжные полки повышают шанс получения",
                            ChatColor.GRAY + "редких и высокоуровневых чар.",
                            "",
                            ChatColor.DARK_GRAY + "Сила книжных полок: " + ChatColor.AQUA + power + " / 15"
                    ));
                    info.setItemMeta(meta);
                }
                inv.setItem(SLOT_HEADER, info);
            }

            // Bookshelf statistics item (slot 31)
            int percent = (int) Math.round((power / 15.0) * 100);
            int filledBars = (int) Math.round((power / 15.0) * 15);
            StringBuilder bar = new StringBuilder(ChatColor.GREEN.toString());
            for (int b = 0; b < 15; b++) {
                if (b == filledBars) bar.append(ChatColor.DARK_GRAY.toString());
                bar.append("■");
            }

            ItemStack stats = named(Material.BOOKSHELF, ChatColor.AQUA + "✦ Сила книжных полок ✦");
            ItemMeta statsMeta = stats.getItemMeta();
            if (statsMeta != null) {
                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.GRAY + "Обнаружено полок: " + ChatColor.YELLOW + power + ChatColor.GRAY + " из " + ChatColor.YELLOW + "15");
                lore.add(ChatColor.GRAY + "Заполнение: " + ChatColor.AQUA + percent + "%");
                lore.add(ChatColor.GRAY + "[" + bar + ChatColor.GRAY + "]");
                lore.add("");
                if (power >= 15) {
                    lore.add(ChatColor.GREEN + "✔ Достигнута максимальная сила (30 ур.)!");
                    lore.add(ChatColor.YELLOW + "✦ Шанс редких зачарований максимален.");
                } else {
                    int missing = 15 - power;
                    lore.add(ChatColor.YELLOW + "▸ Не хватает полок: " + ChatColor.WHITE + missing + " шт.");
                    lore.add(ChatColor.GRAY + "Добавьте полки для разблокировки чар 30 уровня.");
                }
                statsMeta.setLore(lore);
                stats.setItemMeta(statsMeta);
            }
            inv.setItem(SLOT_BOOKSHELF_STATS, stats);

            // Magic Potential item (slot 32)
            int bonusChance = tableSettingsManager != null ? tableSettingsManager.calculateBookshelfBonusChance(power) : (power * 2);
            ItemStack core = named(Material.AMETHYST_CLUSTER, ChatColor.LIGHT_PURPLE + "✧ Магический потенциал ✧");
            ItemMeta coreMeta = core.getItemMeta();
            if (coreMeta != null) {
                coreMeta.setLore(Arrays.asList(
                        ChatColor.GRAY + "Бонусы от книжных полок:",
                        ChatColor.AQUA + "▸ Шанс доп. зачарований: " + ChatColor.YELLOW + "+" + bonusChance + "%",
                        ChatColor.AQUA + "▸ Сила доступных чар: " + ChatColor.YELLOW + (power >= 15 ? "Максимальная (30 ур.)" : power * 2 + " ур.")
                ));
                core.setItemMeta(coreMeta);
            }
            inv.setItem(SLOT_RITUAL_CORE, core);

            // Guide slot (30)
            ItemStack guide = named(Material.KNOWLEDGE_BOOK, ChatColor.GOLD + "✦ Инструкция ✦");
            ItemMeta guideMeta = guide.getItemMeta();
            if (guideMeta != null) {
                guideMeta.setLore(Arrays.asList(
                        ChatColor.GRAY + "1. Поместите оружие/броню/книгу в слот (10)",
                        ChatColor.GRAY + "2. Поместите лазурит в слот (19)",
                        ChatColor.GRAY + "3. Выберите свиток нужного тира",
                        ChatColor.GRAY + "4. Нажмите для проведения ритуала"
                ));
                guide.setItemMeta(guideMeta);
            }
            inv.setItem(SLOT_GUIDE, guide);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTableInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block b = e.getClickedBlock();
        if (b == null || b.getType() != Material.ENCHANTING_TABLE) return;

        e.setCancelled(true);
        openGui(e.getPlayer(), b.getLocation());
    }

    private void openGui(Player p, Location tableLoc) {
        Session old = sessions.remove(p.getUniqueId());
        if (old != null) {
            returnSessionItems(p, old);
            p.closeInventory();
        }

        Inventory inv = Bukkit.createInventory(p, SIZE, TITLE);
        fillInitialFrame(inv);

        Session s = new Session(p.getUniqueId(), tableLoc, inv);
        sessions.put(p.getUniqueId(), s);

        renderOffers(s, p);
        p.openInventory(inv);
        p.playSound(p.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.8f, 1.2f);
    }

    private void fillInitialFrame(Inventory inv) {
        ItemStack glass = named(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, glass);
        }

        inv.setItem(SLOT_ITEM, null);
        inv.setItem(SLOT_LAPIS, null);
        inv.setItem(SLOT_OFFER_1, null);
        inv.setItem(SLOT_OFFER_2, null);
        inv.setItem(SLOT_OFFER_3, null);
        inv.setItem(SLOT_STATUS_1, null);
        inv.setItem(SLOT_STATUS_2, null);
        inv.setItem(SLOT_STATUS_3, null);

        inv.setItem(SLOT_HEADER, named(Material.BOOK, ChatColor.GOLD + "✦ Стол зачарований ✦"));
        inv.setItem(SLOT_LAPIS_INFO, named(Material.LAPIS_LAZULI, ChatColor.BLUE + "✦ Слот для лазурита ✦"));
    }

    private ItemStack named(Material mat, String name) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            it.setItemMeta(meta);
        }
        return it;
    }

    private boolean isOurGuiTitle(String title) {
        return TITLE.equals(title);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        Inventory top = e.getView().getTopInventory();
        if (!isOurGuiTitle(e.getView().getTitle())) return;

        Session s = sessions.get(p.getUniqueId());
        if (s == null) return;

        int raw = e.getRawSlot();

        if (raw < top.getSize()) {
            if (raw != SLOT_ITEM && raw != SLOT_LAPIS && raw != SLOT_OFFER_1 && raw != SLOT_OFFER_2 && raw != SLOT_OFFER_3) {
                e.setCancelled(true);
                return;
            }
        }

        if (raw == SLOT_OFFER_1 || raw == SLOT_OFFER_2 || raw == SLOT_OFFER_3) {
            e.setCancelled(true);
            if (s.inRitual) return;

            int idx = raw == SLOT_OFFER_1 ? 0 : raw == SLOT_OFFER_2 ? 1 : 2;
            Offer offer = s.offers[idx];
            if (offer == null) return;

            ItemStack item = top.getItem(SLOT_ITEM);
            if (item == null || item.getType() == Material.AIR || !isEnchantable(item)) return;

            int lapis = countLapis(top.getItem(SLOT_LAPIS));
            if (lapis < offer.lapisCost) {
                p.sendMessage(ChatColor.RED + "Недостаточно лазурита! Требуется: " + offer.lapisCost);
                p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                return;
            }
            if (p.getLevel() < offer.requiredLevel) {
                p.sendMessage(ChatColor.RED + "Недостаточно опыта! Требуется уровень: " + offer.requiredLevel);
                p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                return;
            }

            // Consume lapis and XP levels.
            // FIX: previously only lapisCost (1/2/3) levels were taken, while the UI
            // showed a requirement of up to 30 levels. Now the displayed requirement
            // IS the real cost: enchanting at tier III costs the full shown level.
            consumeLapis(top, offer.lapisCost);
            p.setLevel(Math.max(0, p.getLevel() - offer.requiredLevel));

            // Apply primary enchantment
            boolean wasBook = item.getType() == Material.BOOK;
            if (wasBook) {
                item = new ItemStack(Material.ENCHANTED_BOOK, 1);
            }

            if (offer.isCustom) {
                CustomEnchant ce = enchantManager.getEnchant(offer.customId);
                if (ce != null) {
                    Map<CustomEnchant, Integer> map = new HashMap<>(enchantManager.getEnchantmentsOnItem(item));
                    map.put(ce, offer.customLevel);
                    enchantManager.setEnchantmentsOnItem(item, map);
                }
            } else if (offer.vanillaEnchant != null) {
                if (wasBook) {
                    EnchantmentStorageMeta meta = (EnchantmentStorageMeta) item.getItemMeta();
                    if (meta != null) {
                        meta.addStoredEnchant(offer.vanillaEnchant, offer.vanillaLevel, true);
                        item.setItemMeta(meta);
                    }
                } else {
                    item.addUnsafeEnchantment(offer.vanillaEnchant, offer.vanillaLevel);
                    enchantManager.refreshLoreIfNeeded(item);
                }
            }

            // Bonus enchantment roll for higher tiers (slot 2 & 3)
            int power = countBookshelves(s.tableLoc);
            rollAndApplyBonusEnchantments(item, idx, power, offer);

            top.setItem(SLOT_ITEM, item);

            // Как в ванилле: после реального зачарования "зерно" меняется,
            // и следующие предложения будут новыми даже для того же предмета.
            rerollEnchantSeed(p);

            // Distinct audio & visual effects
            if (offer.isCustom) {
                p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.3f);
                p.playSound(p.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1.0f, 1.2f);
                p.spawnParticle(Particle.END_ROD, p.getLocation().add(0, 1.3, 0), 35, 0.6, 0.6, 0.6, 0.1);
                p.spawnParticle(Particle.SOUL_FIRE_FLAME, p.getLocation().add(0, 1.1, 0), 20, 0.4, 0.4, 0.4, 0.05);
            } else {
                p.playSound(p.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1.0f, 1.0f);
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.4f);
                p.spawnParticle(Particle.ENCHANT, p.getLocation().add(0, 1.2, 0), 30, 0.5, 0.5, 0.5, 0.1);
            }

            renderOffers(s, p);
            return;
        }

        if (raw == SLOT_ITEM || raw == SLOT_LAPIS) {
            Bukkit.getScheduler().runTask(plugin, () -> renderOffers(s, p));
            return;
        }

        if (raw >= top.getSize()) {
            if (e.getClick() == ClickType.SHIFT_LEFT || e.getClick() == ClickType.SHIFT_RIGHT) {
                ItemStack cur = e.getCurrentItem();
                if (cur == null || cur.getType() == Material.AIR) return;

                e.setCancelled(true);

                if (cur.getType() == Material.LAPIS_LAZULI) {
                    moveToSlot(cur, s, SLOT_LAPIS);
                } else if (isEnchantable(cur)) {
                    moveToSlot(cur, s, SLOT_ITEM);
                } else {
                    p.sendMessage(ChatColor.RED + "Этот предмет нельзя зачаровать на столе зачарований.");
                    return;
                }

                Bukkit.getScheduler().runTask(plugin, () -> renderOffers(s, p));
            }
        }
    }

    private void moveToSlot(ItemStack fromPlayerSlot, Session s, int targetSlot) {
        Inventory top = s.inv;
        ItemStack existing = top.getItem(targetSlot);

        if (targetSlot == SLOT_ITEM) {
            if (existing != null && existing.getType() != Material.AIR) return;
            ItemStack one = fromPlayerSlot.clone();
            one.setAmount(1);
            top.setItem(targetSlot, one);
            fromPlayerSlot.setAmount(fromPlayerSlot.getAmount() - 1);
            return;
        }

        // lapis
        if (existing == null || existing.getType() == Material.AIR) {
            top.setItem(targetSlot, fromPlayerSlot.clone());
            fromPlayerSlot.setAmount(0);
        } else if (existing.getType() == Material.LAPIS_LAZULI) {
            int max = existing.getMaxStackSize();
            int can = Math.min(max - existing.getAmount(), fromPlayerSlot.getAmount());
            existing.setAmount(existing.getAmount() + can);
            fromPlayerSlot.setAmount(fromPlayerSlot.getAmount() - can);
            top.setItem(targetSlot, existing);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!isOurGuiTitle(e.getView().getTitle())) return;

        for (int slot : e.getRawSlots()) {
            if (slot < e.getView().getTopInventory().getSize()) {
                if (slot != SLOT_ITEM && slot != SLOT_LAPIS) {
                    e.setCancelled(true);
                    return;
                }
            }
        }

        Session s = sessions.get(p.getUniqueId());
        if (s != null) Bukkit.getScheduler().runTask(plugin, () -> renderOffers(s, p));
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        if (!isOurGuiTitle(e.getView().getTitle())) return;

        Session s = sessions.remove(p.getUniqueId());
        if (s == null) return;

        returnSessionItems(p, s);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        Session s = sessions.remove(p.getUniqueId());
        if (s == null) return;

        returnSessionItems(p, s);
    }

    private void returnSessionItems(Player p, Session s) {
        ItemStack item = s.inv.getItem(SLOT_ITEM);
        ItemStack lapis = s.inv.getItem(SLOT_LAPIS);
        if (item != null && item.getType() != Material.AIR) giveOrDrop(p, item, s.tableLoc);
        if (lapis != null && lapis.getType() != Material.AIR) giveOrDrop(p, lapis, s.tableLoc);
        s.inv.setItem(SLOT_ITEM, null);
        s.inv.setItem(SLOT_LAPIS, null);
    }

    private void giveOrDrop(Player p, ItemStack item, Location fallbackLoc) {
        if (p != null && p.isOnline()) {
            Map<Integer, ItemStack> overflow = p.getInventory().addItem(item);
            if (!overflow.isEmpty()) {
                for (ItemStack rem : overflow.values()) {
                    p.getWorld().dropItemNaturally(p.getLocation(), rem);
                }
            }
        } else if (fallbackLoc != null && fallbackLoc.getWorld() != null) {
            fallbackLoc.getWorld().dropItemNaturally(fallbackLoc, item);
        }
    }

    private int countLapis(ItemStack stack) {
        if (stack == null || stack.getType() != Material.LAPIS_LAZULI) return 0;
        return stack.getAmount();
    }

    private void consumeLapis(Inventory inv, int amount) {
        ItemStack lapis = inv.getItem(SLOT_LAPIS);
        if (lapis == null || lapis.getType() != Material.LAPIS_LAZULI) return;
        int left = lapis.getAmount() - amount;
        if (left <= 0) inv.setItem(SLOT_LAPIS, null);
        else {
            lapis.setAmount(left);
            inv.setItem(SLOT_LAPIS, lapis);
        }
    }

    public static boolean isEnchantable(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        Material mat = item.getType();
        if (mat == Material.BOOK) return true;

        String name = mat.name();
        return name.endsWith("_SWORD")
                || name.endsWith("_PICKAXE")
                || name.endsWith("_AXE")
                || name.endsWith("_SHOVEL")
                || name.endsWith("_HOE")
                || name.endsWith("_HELMET")
                || name.endsWith("_CHESTPLATE")
                || name.endsWith("_LEGGINGS")
                || name.endsWith("_BOOTS")
                || mat == Material.BOW
                || mat == Material.CROSSBOW
                || mat == Material.TRIDENT
                || mat == Material.MACE
                || mat == Material.SHIELD
                || mat == Material.ELYTRA
                || mat == Material.TURTLE_HELMET;
    }

    private void renderOffers(Session s, Player p) {
        Inventory inv = s.inv;
        ItemStack item = inv.getItem(SLOT_ITEM);
        if (item == null || item.getType() == Material.AIR || !isEnchantable(item)) {
            clearOfferSlots(inv);
            s.offers = new Offer[3];
            return;
        }

        int bookshelves = countBookshelves(s.tableLoc);
        int power = Math.min(15, bookshelves);

        // FIX (anti "item re-place reroll"): offers are generated deterministically
        // from the player's enchant seed + the item's identity. Taking the same item
        // out and putting it back yields the SAME offers (as in vanilla). The list
        // changes only after an actual enchant (see rerollEnchantSeed) or for a
        // different item.
        Random rnd = new Random(getEnchantSeed(p) ^ itemIdentity(item));
        int base = rnd.nextInt(1, 9) + (power / 2) + rnd.nextInt(power + 1);
        int lvl1 = Math.max(1, base / 3);
        int lvl2 = Math.max(1, (base * 2) / 3 + 1);
        int lvl3 = Math.max(1, Math.max(base, power * 2));

        Offer o1 = generateSlotOffer(item, 1, lvl1, 0, power, rnd);
        Offer o2 = generateSlotOffer(item, 2, lvl2, 1, power, rnd);
        Offer o3 = generateSlotOffer(item, 3, lvl3, 2, power, rnd);

        s.offers[0] = o1;
        s.offers[1] = o2;
        s.offers[2] = o3;

        int playerLapis = countLapis(inv.getItem(SLOT_LAPIS));
        int playerLevel = p.getLevel();

        inv.setItem(SLOT_OFFER_1, offerItem(o1, 1, playerLevel, playerLapis));
        inv.setItem(SLOT_OFFER_2, offerItem(o2, 2, playerLevel, playerLapis));
        inv.setItem(SLOT_OFFER_3, offerItem(o3, 3, playerLevel, playerLapis));

        inv.setItem(SLOT_STATUS_1, statusItem(o1, 1, playerLevel, playerLapis));
        inv.setItem(SLOT_STATUS_2, statusItem(o2, 2, playerLevel, playerLapis));
        inv.setItem(SLOT_STATUS_3, statusItem(o3, 3, playerLevel, playerLapis));
    }

    private void clearOfferSlots(Inventory inv) {
        inv.setItem(SLOT_OFFER_1, null);
        inv.setItem(SLOT_OFFER_2, null);
        inv.setItem(SLOT_OFFER_3, null);
        inv.setItem(SLOT_STATUS_1, null);
        inv.setItem(SLOT_STATUS_2, null);
        inv.setItem(SLOT_STATUS_3, null);
    }

    private ItemStack offerItem(Offer offer, int tierNumber, int playerLevel, int playerLapis) {
        if (offer == null) return null;

        Material icon = offer.isCustom ? Material.NETHER_STAR : Material.ENCHANTED_BOOK;
        ItemStack it = new ItemStack(icon);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            String tierBadge = switch (tierNumber) {
                case 1 -> ChatColor.GREEN + "✦ Уровень I (Базовый)";
                case 2 -> ChatColor.YELLOW + "✦ Уровень II (Средний)";
                case 3 -> ChatColor.GOLD + "✦ Уровень III (Высший)";
                default -> "";
            };

            if (!offer.isCustom && offer.vanillaEnchant != null) {
                final Component title = vanillaDisplayNameComponent(offer.vanillaEnchant, offer.vanillaLevel)
                        .color(NamedTextColor.AQUA)
                        .decoration(TextDecoration.ITALIC, false);
                setMetaDisplayName(meta, title);
            } else {
                String animated = me.aquaenchants.util.LoreAnimationManager.formatAnimatedOriginal(offer.displayName, me.aquaenchants.util.LoreAnimationManager.getGlobalTick());
                meta.setDisplayName(animated);
            }

            List<String> lore = new ArrayList<>();
            lore.add(tierBadge);
            lore.add(ChatColor.DARK_GRAY + "----------------------------");

            if (offer.isCustom) {
                String groupLabel = switch (offer.group != null ? offer.group.toUpperCase(Locale.ROOT) : "") {
                    case "UNIKAL" -> ChatColor.GOLD + "✦ Редкость: " + ChatColor.YELLOW + "Уникальное";
                    case "LEGENDA" -> ChatColor.LIGHT_PURPLE + "★ Редкость: " + ChatColor.DARK_PURPLE + "Легендарное";
                    default -> ChatColor.AQUA + "✧ Редкость: " + ChatColor.DARK_AQUA + "Кастомное";
                };
                lore.add(groupLabel);

                if (offer.description != null && !offer.description.isEmpty()) {
                    lore.add(ChatColor.GRAY + "Эффект: " + ChatColor.WHITE + ChatColor.translateAlternateColorCodes('&', offer.description));
                }
                lore.add(ChatColor.DARK_GRAY + "----------------------------");
            }

            lore.add(ChatColor.BLUE + "Лазурит: " + (playerLapis >= offer.lapisCost ? ChatColor.GREEN : ChatColor.RED) + playerLapis + "/" + offer.lapisCost + " шт.");
            lore.add(ChatColor.GREEN + "Требуемый уровень: " + (playerLevel >= offer.requiredLevel ? ChatColor.GREEN : ChatColor.RED) + playerLevel + "/" + offer.requiredLevel + " ур.");
            lore.add(ChatColor.YELLOW + "Стоимость: " + ChatColor.WHITE + offer.requiredLevel + " ур. опыта" + ChatColor.DARK_GRAY + " (будет списано полностью)");
            lore.add(ChatColor.DARK_GRAY + "----------------------------");

            if (playerLevel >= offer.requiredLevel && playerLapis >= offer.lapisCost) {
                lore.add(ChatColor.GREEN + "✔ Нажмите для зачарования!");
            } else if (playerLevel < offer.requiredLevel) {
                lore.add(ChatColor.RED + "✖ Недостаточно уровня опыта");
            } else {
                lore.add(ChatColor.RED + "✖ Недостаточно лазурита в слоте");
            }

            meta.setLore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    private ItemStack statusItem(Offer offer, int tierNumber, int playerLevel, int playerLapis) {
        if (offer == null) return null;
        boolean ready = playerLevel >= offer.requiredLevel && playerLapis >= offer.lapisCost;

        Material mat = ready ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE;
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            if (ready) {
                meta.setDisplayName(ChatColor.GREEN + "✔ Готово к зачарованию (Уровень " + tierNumber + ")");
                meta.setLore(Arrays.asList(
                        ChatColor.GRAY + "Требуется: " + ChatColor.WHITE + offer.lapisCost + " лаз. / " + offer.requiredLevel + " ур.",
                        ChatColor.YELLOW + "▸ Нажмите на свиток выше"
                ));
            } else {
                meta.setDisplayName(ChatColor.RED + "✖ Недоступно (Уровень " + tierNumber + ")");
                meta.setLore(Arrays.asList(
                        ChatColor.GRAY + "Требуется: " + ChatColor.WHITE + offer.lapisCost + " лаз. / " + offer.requiredLevel + " ур.",
                        playerLevel < offer.requiredLevel ? ChatColor.RED + "Нужно еще " + (offer.requiredLevel - playerLevel) + " ур. опыта" : ChatColor.RED + "Нужно еще лазурита"
                ));
            }
            it.setItemMeta(meta);
        }
        return it;
    }

    private Offer generateSlotOffer(ItemStack item, int lapisCost, int requiredLevel, int slotIndex, int power, Random rnd) {
        // Кастомные зачарования выпадают ТОЛЬКО на 3-м тире стола (slotIndex == 2)
        // и с очень маленьким шансом. На 1-м и 2-м тирах — всегда ваниль.
        boolean customAllowedHere = slotIndex == 2;
        double customRollThreshold = customAllowedHere
                ? (tableSettingsManager != null
                    ? tableSettingsManager.calculateSlotCustomChance(slotIndex, power)
                    : 3.0 + (power * 0.2))
                : 0.0;

        boolean shouldTryCustom = customRollThreshold > 0 && (rnd.nextDouble() * 100.0) < customRollThreshold;

        if (shouldTryCustom) {
            Offer custom = pickCustomOffer(item, lapisCost, requiredLevel, rnd);
            if (custom != null) return custom;
        }

        Offer vanilla = pickVanillaOffer(item, lapisCost, requiredLevel, slotIndex, power, rnd);
        if (vanilla != null) return vanilla;

        // Запасной вариант: если на 3-м тире ванильных чар не осталось (например,
        // предмет уже зачарован всем подходящим) — можно предложить кастомную чару.
        // На 1-м/2-м тирах кастомных чар нет никогда.
        if (customAllowedHere) {
            Offer customFallback = pickCustomOffer(item, lapisCost, requiredLevel, rnd);
            if (customFallback != null) return customFallback;
        }

        return Offer.vanilla(ChatColor.GRAY + "Нет доступных чар", requiredLevel, lapisCost, null, 0);
    }

    private Offer pickCustomOffer(ItemStack item, int lapisCost, int requiredLevel, Random rnd) {
        boolean isBook = item.getType() == Material.BOOK;
        Map<CustomEnchant, Integer> existingCustom = enchantManager.getEnchantmentsOnItem(item);

        List<CustomEnchant> candidates = new ArrayList<>();
        for (CustomEnchant e : enchantManager.getCustomEnchants()) {
            if (e == null) continue;
            if (!e.isEnchantTableEnabled()) continue;
            if (e.getEnchantTableChance() <= 0) continue;

            // Глобально запрещённые для стола зачарования (по умолчанию — "trench",
            // Экскаватор гномов 3x3 не выпадает на столе совсем).
            if (tableSettingsManager != null && tableSettingsManager.isTableDisabled(e.getId())) continue;

            if (!isBook && !enchantManager.canApply(e, item)) continue;

            // Стол не предлагает "апгрейд" уже наложенных кастомных чар:
            // выпадают только новые чары 1-го уровня.
            if (!isBook && existingCustom.containsKey(e)) continue;

            candidates.add(e);
        }

        if (candidates.isEmpty()) return null;

        int totalWeight = 0;
        for (CustomEnchant e : candidates) {
            totalWeight += Math.max(1, e.getEnchantTableChance());
        }
        if (totalWeight <= 0) return null;

        int roll = rnd.nextInt(totalWeight);
        CustomEnchant chosen = null;
        int acc = 0;
        for (CustomEnchant e : candidates) {
            acc += Math.max(1, e.getEnchantTableChance());
            if (roll < acc) {
                chosen = e;
                break;
            }
        }
        if (chosen == null) chosen = candidates.get(0);

        // Кастомные чары со стола выпадают ТОЛЬКО 1-го уровня (как и просили:
        // "выпадать только маленького уровня"). Повышение уровня — через наковальню/книги.
        int level = 1;

        String display = chosen.getDisplayName() != null ? chosen.getDisplayName() : chosen.getId();
        return Offer.custom(display + " " + ChatColor.GRAY + roman(level), chosen.getDescription(), chosen.getGroup(), requiredLevel, lapisCost, chosen.getId(), level);
    }

    private int getMaxLevelForEnchant(CustomEnchant e) {
        if (e.getLevels() == null || e.getLevels().isEmpty()) return 1;
        return Collections.max(e.getLevels().keySet());
    }

    private int calculateBalancedCustomLevel(int maxLevel, int slotIndex, int requiredLevel, int power, Random rnd) {
        if (maxLevel <= 1) return 1;

        if (maxLevel == 2) {
            return switch (slotIndex) {
                case 0 -> 1;
                case 1 -> (rnd.nextInt(100) < 30) ? 2 : 1;
                case 2 -> (requiredLevel >= 18 && rnd.nextInt(100) < 70) ? 2 : 1;
                default -> 1;
            };
        }

        if (maxLevel == 3) {
            return switch (slotIndex) {
                case 0 -> 1;
                case 1 -> (rnd.nextInt(100) < 40) ? 2 : 1;
                case 2 -> {
                    if (requiredLevel >= 25 && power >= 10 && rnd.nextInt(100) < 65) yield 3;
                    if (requiredLevel >= 16) yield 2;
                    yield 1;
                }
                default -> 1;
            };
        }

        if (maxLevel == 4) {
            return switch (slotIndex) {
                case 0 -> 1;
                case 1 -> (rnd.nextInt(100) < 45) ? 2 : 1;
                case 2 -> {
                    if (requiredLevel >= 27 && power >= 12 && rnd.nextInt(100) < 45) yield 4;
                    if (requiredLevel >= 22 && rnd.nextInt(100) < 60) yield 3;
                    if (requiredLevel >= 15) yield 2;
                    yield 1;
                }
                default -> 1;
            };
        }

        // maxLevel >= 5
        return switch (slotIndex) {
            case 0 -> 1;
            case 1 -> {
                int r = rnd.nextInt(100);
                if (r < 25) yield 3;
                if (r < 65) yield 2;
                yield 1;
            }
            case 2 -> {
                if (requiredLevel >= 28 && power >= 14 && rnd.nextInt(100) < 30) yield 5;
                if (requiredLevel >= 24 && rnd.nextInt(100) < 50) yield 4;
                if (requiredLevel >= 18 && rnd.nextInt(100) < 70) yield 3;
                yield 2;
            }
            default -> 1;
        };
    }

    private Offer pickVanillaOffer(ItemStack item, int lapisCost, int requiredLevel, int slotIndex, int power, Random rnd) {
        boolean isBook = item.getType() == Material.BOOK;

        List<Enchantment> candidates = new ArrayList<>();
        for (Enchantment e : Registry.ENCHANTMENT) {
            if (e == null || e.getKey() == null) continue;
            if (e.isTreasure() || e.isCursed()) continue;

            String ns = e.getKey().getNamespace();
            if ("aquaenchants".equalsIgnoreCase(ns) || "aquaenchats".equalsIgnoreCase(ns)) continue;

            if (isBook) {
                candidates.add(e);
            } else {
                if (e.canEnchantItem(item) && !item.containsEnchantment(e)) {
                    boolean conflicts = false;
                    for (Enchantment ex : item.getEnchantments().keySet()) {
                        if (ex.conflictsWith(e)) {
                            conflicts = true;
                            break;
                        }
                    }
                    if (!conflicts) candidates.add(e);
                }
            }
        }

        if (candidates.isEmpty()) return null;

        Enchantment ench = candidates.get(rnd.nextInt(candidates.size()));
        int maxLevel = ench.getMaxLevel();
        int level = calculateBalancedCustomLevel(maxLevel, slotIndex, requiredLevel, power, rnd);

        String display = prettifyKey(ench.getKey());
        return Offer.vanilla(display + " " + ChatColor.GRAY + roman(level), requiredLevel, lapisCost, ench, level);
    }

    private void rollAndApplyBonusEnchantments(ItemStack item, int slotIndex, int power, Offer primaryOffer) {
        if (slotIndex < 1) return;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        boolean isBook = item.getType() == Material.ENCHANTED_BOOK;

        int bonusChance = tableSettingsManager != null
                ? tableSettingsManager.calculateBookshelfBonusChance(power)
                : (5 + power);

        // If primary offer was custom, guarantee at least 1 vanilla enchant, plus extra chance
        int vanillaBonusCount = primaryOffer.isCustom ? (rnd.nextInt(100) < bonusChance ? 2 : 1)
                : (slotIndex == 2 ? (rnd.nextInt(100) < bonusChance + 30 ? 2 : (rnd.nextInt(100) < bonusChance + 50 ? 1 : 0)) : (rnd.nextInt(100) < bonusChance + 20 ? 1 : 0));

        for (int i = 0; i < vanillaBonusCount; i++) {
            Offer bonusVanilla = pickVanillaOffer(item, 1, 15, slotIndex - 1, power, rnd);
            if (bonusVanilla != null && bonusVanilla.vanillaEnchant != null && !bonusVanilla.vanillaEnchant.equals(primaryOffer.vanillaEnchant)) {
                if (isBook) {
                    EnchantmentStorageMeta sm = (EnchantmentStorageMeta) item.getItemMeta();
                    if (sm != null) {
                        sm.addStoredEnchant(bonusVanilla.vanillaEnchant, bonusVanilla.vanillaLevel, true);
                        item.setItemMeta(sm);
                    }
                } else {
                    item.addUnsafeEnchantment(bonusVanilla.vanillaEnchant, bonusVanilla.vanillaLevel);
                    enchantManager.refreshLoreIfNeeded(item);
                }
            }
        }

        // Additional chance for a bonus custom enchant if primary was vanilla.
        // FIX: bonus custom enchants, just like offers, are allowed ONLY on tier III
        // and only with the same tiny chance (previously ~5..20% from bookshelf bonus
        // on tiers II and III made custom enchants way too common).
        if (!primaryOffer.isCustom && slotIndex == 2) {
            double tier3Chance = tableSettingsManager != null
                    ? tableSettingsManager.calculateSlotCustomChance(2, power)
                    : 3.0 + (power * 0.2);
            if ((rnd.nextDouble() * 100.0) < tier3Chance) {
                Offer bonusCustom = pickCustomOffer(item, 1, 15, rnd);
                if (bonusCustom != null && bonusCustom.isCustom) {
                    CustomEnchant ce = enchantManager.getEnchant(bonusCustom.customId);
                    if (ce != null) {
                        Map<CustomEnchant, Integer> map = new HashMap<>(enchantManager.getEnchantmentsOnItem(item));
                        map.put(ce, bonusCustom.customLevel);
                        enchantManager.setEnchantmentsOnItem(item, map);
                    }
                }
            }
        }
    }

    private String prettifyKey(NamespacedKey key) {
        String k = key.getKey().replace('_', ' ');
        String[] parts = k.split(" ");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1)).append(' ');
        }
        return sb.toString().trim();
    }

    private String roman(int n) {
        return switch (n) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            case 6 -> "VI";
            case 7 -> "VII";
            case 8 -> "VIII";
            case 9 -> "IX";
            case 10 -> "X";
            default -> String.valueOf(n);
        };
    }

    private Component vanillaDisplayNameComponent(Enchantment ench, int level) {
        NamespacedKey key = ench.getKey();
        Component base = Component.translatable("enchantment." + key.getNamespace() + "." + key.getKey());

        if (level > 1 || ench.getMaxLevel() > 1) {
            Component lvl = Component.translatable("enchantment.level." + level);
            base = base.append(Component.space()).append(lvl);
        }
        return base;
    }

    private void setMetaDisplayName(ItemMeta meta, Component name) {
        try {
            Method m = meta.getClass().getMethod("displayName", Component.class);
            m.invoke(meta, name);
            return;
        } catch (Throwable ignored) {
        }
        meta.setDisplayName(extractPlain(name));
    }

    private String extractPlain(Component c) {
        if (c instanceof net.kyori.adventure.text.TextComponent tc) {
            return tc.content();
        }
        return c.toString();
    }

    private int countBookshelves(Location tableLoc) {
        if (tableLoc == null || tableLoc.getWorld() == null) return 0;
        int count = 0;

        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                if (Math.abs(x) != 2 && Math.abs(z) != 2) continue;

                for (int y = 0; y <= 1; y++) {
                    int midX = x == 0 ? 0 : (x > 0 ? x - 1 : x + 1);
                    int midZ = z == 0 ? 0 : (z > 0 ? z - 1 : z + 1);
                    Block between = tableLoc.clone().add(midX, y, midZ).getBlock();
                    if (!between.getType().isAir()) continue;

                    Block b = tableLoc.clone().add(x, y, z).getBlock();
                    if (b.getType() == Material.BOOKSHELF || b.getType() == Material.CHISELED_BOOKSHELF) {
                        count++;
                    }
                }
            }
        }
        return Math.min(15, count);
    }
}
