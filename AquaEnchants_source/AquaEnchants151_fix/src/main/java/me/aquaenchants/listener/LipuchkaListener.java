package me.aquaenchants.listener;

import me.aquaenchants.AquaEnchatsPlugin;
import me.aquaenchants.enchant.CustomEnchant;
import me.aquaenchants.enchant.EffectConfig;
import me.aquaenchants.enchant.EnchantLevel;
import me.aquaenchants.enchant.EnchantManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Trident;
import org.bukkit.entity.Item;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.lang.reflect.Method;
import java.util.regex.Pattern;

/**
 * Зачарование lipuchka.
 * Перемещает весь дроп с убитых мобов/игроков
 * сразу в инвентарь убийцы, а при полном инвентаре —
 * выбрасывает вещи рядом с ним.
 *
 * Источник шанса:
 *  - в первую очередь берётся поле "chance" уровня зачарования;
 *  - если chance == 0 или 100, то ищется эффект вида "LIPUCHKA: 20%"
 *    и используется указанное там значение.
 */
public class LipuchkaListener implements Listener {

    private final AquaEnchatsPlugin plugin;
    private final EnchantManager enchantManager;
    private final Random random = new Random();

    /**
     * MoneyFromMobs помечает денежные "монеты" скрытой строкой в лоре.
     * См. PickUpManager#isMoneyPickedUp в MoneyFromMobs: regex "([0-9]){6}mfm".
     *
     * Важно: такие предметы нельзя переносить в инвентарь напрямую (Lipuchka),
     * иначе они перестают конвертироваться в баланс и превращаются в обычный предмет.
     */
    private static final Pattern MONEY_FROM_MOBS_MARKER = Pattern.compile("([0-9]){6}mfm");

    // Рефлексия к MoneyFromMobs (если установлен), чтобы детект был 1-в-1 как у него.
    private boolean mfmInitDone = false;
    private Method mfmGetPickUpManager = null;
    private Method mfmIsMoneyPickedUp = null;
    private Method mfmGiveMoney = null;
    private Method mfmShouldOnlyKillerPickUpMoney = null;

    public LipuchkaListener(AquaEnchatsPlugin plugin, EnchantManager enchantManager) {
        this.plugin = plugin;
        this.enchantManager = enchantManager;
    }

    private void initMoneyFromMobsHooks() {
        if (mfmInitDone) return;
        mfmInitDone = true;
        try {
            var pl = Bukkit.getPluginManager().getPlugin("MoneyFromMobs");
            if (pl == null) return;

            // MoneyFromMobs имеет публичный метод getPickUpManager()
            mfmGetPickUpManager = pl.getClass().getMethod("getPickUpManager");
            Object pickUpManager = mfmGetPickUpManager.invoke(pl);
            if (pickUpManager == null) return;

            // PickUpManager имеет публичный метод isMoneyPickedUp(ItemStack)
            mfmIsMoneyPickedUp = pickUpManager.getClass().getMethod("isMoneyPickedUp", ItemStack.class);

            // И методы для начисления денег/проверок (как в их PickUpListeners)
            mfmGiveMoney = pickUpManager.getClass().getMethod("giveMoney", Double.class, Player.class);
            mfmShouldOnlyKillerPickUpMoney = pickUpManager.getClass().getMethod("shouldOnlyKillerPickUpMoney");
        } catch (Throwable ignored) {
            // fallback к локальному regex
            mfmGetPickUpManager = null;
            mfmIsMoneyPickedUp = null;
            mfmGiveMoney = null;
            mfmShouldOnlyKillerPickUpMoney = null;
        }
    }

    /**
     * ВАЖНО:
     * Нельзя полагаться только на "item in main hand":
     * - убийство трезубцем происходит через сущность {@link Trident}, а предмет в руке
     *   у игрока в момент смерти цели может быть уже другим/пустым.
     * - некоторые плагины/механики могут убивать моба уроном не из main-hand.
     *
     * Поэтому мы определяем "оружие-источник" по последнему урону и также
     * проверяем off-hand как запасной вариант.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim == null) {
            return;
        }

        Player killer = victim.getKiller();
        if (killer == null) {
            return;
        }

        ItemStack weapon = resolveWeapon(event, killer);
        if (weapon == null || weapon.getType().isAir()) return;

        // 1) Сначала пробуем нормальный путь через PDC (как у остальных чар)
        CustomEnchant lipuchka = null;
        int level = 0;

        Map<CustomEnchant, Integer> enchants = enchantManager.getEnchantmentsOnItem(weapon);
        if (enchants != null && !enchants.isEmpty()) {
            for (Map.Entry<CustomEnchant, Integer> entry : enchants.entrySet()) {
                CustomEnchant ench = entry.getKey();
                Integer lvl = entry.getValue();
                if (ench == null || lvl == null || lvl <= 0) continue;
                if ("lipuchka".equalsIgnoreCase(ench.getId())) {
                    lipuchka = ench;
                    level = Math.max(level, lvl);
                }
            }
        }

        // 2) Если в "основном" оружии не нашли — попробуем вторую руку.
        if (lipuchka == null) {
            ItemStack off = killer.getInventory().getItemInOffHand();
            if (off != null && !off.getType().isAir()) {
                Map<CustomEnchant, Integer> offEnchants = enchantManager.getEnchantmentsOnItem(off);
                if (offEnchants != null && !offEnchants.isEmpty()) {
                    for (Map.Entry<CustomEnchant, Integer> entry : offEnchants.entrySet()) {
                        CustomEnchant ench = entry.getKey();
                        Integer lvl = entry.getValue();
                        if (ench == null || lvl == null || lvl <= 0) continue;
                        if ("lipuchka".equalsIgnoreCase(ench.getId())) {
                            weapon = off;
                            lipuchka = ench;
                            level = Math.max(level, lvl);
                        }
                    }
                }
            }
        }

        // 3) Fallback: если по какой-то причине предмет был зачарован "старым" способом
        // (есть лор, но нет PDC) — попробуем определить уровень по лору.
        if (lipuchka == null) {
            int loreLevel = detectLipuchkaLevelFromLore(weapon);
            if (loreLevel <= 0) {
                ItemStack off = killer.getInventory().getItemInOffHand();
                loreLevel = detectLipuchkaLevelFromLore(off);
                if (loreLevel > 0) weapon = off;
            }
            if (loreLevel > 0) {
                lipuchka = enchantManager.getEnchant("lipuchka");
                level = loreLevel;
            }
        }

        if (lipuchka == null || level <= 0) {
            return;
        }

        EnchantLevel data = lipuchka.getLevel(level);
        if (data == null) {
            return;
        }

        double chance = resolveChanceFromLevel(data);
        if (chance <= 0.0) {
            plugin.debug("[lipuchka] chance <= 0, nothing to do");
            return;
        }
        if (chance > 100.0) {
            chance = 100.0;
        }

        double roll = random.nextDouble() * 100.0;
        if (roll > chance) {
            plugin.debug("[lipuchka] roll=" + roll + " > chance=" + chance + ", skip");
            return;
        }

        List<ItemStack> drops = event.getDrops();
        if (drops == null || drops.isEmpty()) {
            plugin.debug("[lipuchka] no drops to move");
            return;
        }

        World world = victim.getWorld();
        Location deathLoc = victim.getLocation();
        List<ItemStack> leftovers = new ArrayList<>();
        List<ItemStack> keepOnGround = new ArrayList<>();

        // Копируем список, чтобы безопасно модифицировать event.getDrops()
        List<ItemStack> originalDrops = new ArrayList<>(drops);
        drops.clear();

        for (ItemStack stack : originalDrops) {
            if (stack == null || stack.getType().isAir()) {
                continue;
            }

            // Деньги из MoneyFromMobs: забираем "липучкой", но конвертируем в баланс (а не кладём предмет в инвентарь).
            if (isMoneyFromMobsDrop(stack)) {
                if (!tryGiveMoneyFromMobs(stack, killer)) {
                    // Если по какой-то причине не смогли корректно начислить — оставим на земле,
                    // чтобы MoneyFromMobs обработал при обычном подборе.
                    keepOnGround.add(stack);
                }
                continue;
            }

            Map<Integer, ItemStack> rest = killer.getInventory().addItem(stack);
            if (rest != null && !rest.isEmpty()) {
                leftovers.addAll(rest.values());
            }
        }

        // Возвращаем обратно в дроп то, что нельзя телепортировать в инвентарь
        if (!keepOnGround.isEmpty()) {
            drops.addAll(keepOnGround);
        }

        // Всё, что не влезло в инвентарь, выбрасываем рядом с игроком
        for (ItemStack rest : leftovers) {
            if (rest == null || rest.getType().isAir()) {
                continue;
            }
            world.dropItemNaturally(killer.getLocation(), rest);
        }

        plugin.debug("[lipuchka] moved loot for " + killer.getName() +
                ", level=" + level + ", chance=" + chance + ", roll=" + roll);

        // Доп. страховка: некоторые плагины/ядра могут создавать Item-энтити чуть позже,
        // уже после того как мы очистили event.getDrops().
        // Подбираем "свежие" предметы (ticksLived <= 2) рядом с местом смерти на следующем тике.
        Bukkit.getScheduler().runTask(plugin, () -> sweepFreshDropsNearDeath(killer, deathLoc));
    }

    private void sweepFreshDropsNearDeath(Player killer, Location deathLoc) {
        if (killer == null || deathLoc == null) return;
        World world = deathLoc.getWorld();
        if (world == null) return;

        // Небольшой радиус, чтобы не затрагивать чужие предметы.
        double radius = 2.0;
        // Spigot API не имеет World#getNearbyEntitiesByType(), поэтому фильтруем вручную.
        for (org.bukkit.entity.Entity ent : world.getNearbyEntities(deathLoc, radius, radius, radius,
                e -> e instanceof Item)) {
            Item itemEnt = (Item) ent;
            try {
                if (itemEnt.isDead() || !itemEnt.isValid()) continue;
                // Берём только "свежие" дропы.
                if (itemEnt.getTicksLived() > 2) continue;

                ItemStack stack = itemEnt.getItemStack();
                if (stack == null || stack.getType().isAir()) continue;

                // MoneyFromMobs — не кладём предмет в инвентарь, а конвертируем в баланс.
                if (isMoneyFromMobsDrop(stack)) {
                    if (tryGiveMoneyFromMobs(stack, killer)) {
                        itemEnt.remove();
                    }
                    continue;
                }

                // Пробуем положить в инвентарь.
                Map<Integer, ItemStack> rest = killer.getInventory().addItem(stack);
                itemEnt.remove();

                // Если не влезло — выбрасываем у игрока.
                if (rest != null && !rest.isEmpty()) {
                    for (ItemStack r : rest.values()) {
                        if (r == null || r.getType().isAir()) continue;
                        world.dropItemNaturally(killer.getLocation(), r);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Пытаемся определить предмет, которым был нанесён последний урон.
     * Для трезубца берём ItemStack из сущности Trident.
     */
    private ItemStack resolveWeapon(EntityDeathEvent deathEvent, Player killer) {
        if (killer == null) return null;

        // 1) По умолчанию — предмет в main-hand.
        ItemStack main = killer.getInventory().getItemInMainHand();

        // 2) Если последний урон был от сущности (EntityDamageByEntityEvent),
        //    пытаемся понять, чем именно ударили.
        if (deathEvent != null && deathEvent.getEntity() != null
                && deathEvent.getEntity().getLastDamageCause() instanceof EntityDamageByEntityEvent edbe) {
            var damager = edbe.getDamager();

            // Убийство трезубцем: дамагер = Trident.
            if (damager instanceof Trident trident) {
                try {
                    ItemStack it = trident.getItem();
                    if (it != null && !it.getType().isAir()) return it;
                } catch (Throwable ignored) {
                    // Paper/Bukkit API различались в прошлом, но на 1.21+ обычно есть getItem().
                }
                // В Spigot/Paper 1.21+ корректный метод — getItem().
                // getItemStack() отсутствует в используемом API.
            }

            // Если дамагер — сам игрок (обычный удар) — main-hand подходит.
            if (damager instanceof Player) {
                return main;
            }

            // Проектилы (стрелы и т.п.) нам не важны, так как lipuchka не применяется к лукам.
            if (damager instanceof Projectile) {
                return main;
            }
        }

        return main;
    }

    /**
     * Fallback для старых/внешних предметов, где по какой-то причине
     * не записался PDC (keyEnchantList), но остался лор.
     *
     * Ищем строку, где после снятия цветов встречается "ЛИПУЧКА",
     * а уровень берём из римской цифры в конце (I..X).
     */
    private int detectLipuchkaLevelFromLore(ItemStack item) {
        try {
            if (item == null || item.getType().isAir() || !item.hasItemMeta()) return 0;
            var meta = item.getItemMeta();
            if (meta == null || !meta.hasLore()) return 0;
            List<String> lore = meta.getLore();
            if (lore == null || lore.isEmpty()) return 0;

            for (String line : lore) {
                if (line == null || line.isEmpty()) continue;
                String plain = ChatColor.stripColor(line).toUpperCase(Locale.ROOT).trim();
                if (!plain.contains("ЛИПУЧКА")) continue;

                // Берём последний токен как возможный римский уровень
                String[] parts = plain.split("\\s+");
                if (parts.length == 0) return 1;
                String last = parts[parts.length - 1];
                int lvl = romanToInt(last);
                return Math.max(lvl, 1);
            }
            return 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private int romanToInt(String roman) {
        if (roman == null) return 0;
        String r = roman.trim().toUpperCase(Locale.ROOT);
        return switch (r) {
            case "I" -> 1;
            case "II" -> 2;
            case "III" -> 3;
            case "IV" -> 4;
            case "V" -> 5;
            case "VI" -> 6;
            case "VII" -> 7;
            case "VIII" -> 8;
            case "IX" -> 9;
            case "X" -> 10;
            default -> 0;
        };
    }

    private double resolveChanceFromLevel(EnchantLevel levelData) {
        if (levelData == null) {
            return 0.0;
        }

        int baseChance = levelData.getChance();
        double chance = baseChance;

        // Если chance не задан явно (0) или оставлен по умолчанию (100),
        // пробуем взять значение из эффекта LIPUCHKA: 20%
        if (baseChance <= 0 || baseChance >= 100) {
            List<EffectConfig> effects = levelData.getEffects();
            if (effects != null) {
                for (EffectConfig ec : effects) {
                    if (ec == null) continue;
                    String raw = ec.getId();
                    if (raw == null) continue;
                    String trimmed = raw.trim();
                    if (trimmed.isEmpty()) continue;

                    String upper = trimmed.toUpperCase(Locale.ROOT);
                    if (!upper.startsWith("LIPUCHKA")) {
                        continue;
                    }

                    String[] parts = trimmed.split(":", 2);
                    if (parts.length < 2) {
                        continue;
                    }
                    String numPart = parts[1].replace("%", "").trim();
                    if (numPart.isEmpty()) {
                        continue;
                    }
                    try {
                        chance = Double.parseDouble(numPart);
                        break;
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }

        return chance;
    }

    /**
     * Детектор "монет" MoneyFromMobs.
     * По умолчанию у предмета есть lore, а в первой строке лежит скрытый маркер вида "123456mfm".
     */
    private boolean isMoneyFromMobsDrop(ItemStack stack) {
        try {
            initMoneyFromMobsHooks();

            // Если MoneyFromMobs установлен — используем его же детектор.
            if (mfmGetPickUpManager != null && mfmIsMoneyPickedUp != null) {
                var pl = Bukkit.getPluginManager().getPlugin("MoneyFromMobs");
                if (pl != null) {
                    Object pickUpManager = mfmGetPickUpManager.invoke(pl);
                    if (pickUpManager != null) {
                        Object res = mfmIsMoneyPickedUp.invoke(pickUpManager, stack);
                        if (res instanceof Boolean) {
                            return (Boolean) res;
                        }
                    }
                }
            }

            if (stack == null || !stack.hasItemMeta()) return false;
            var meta = stack.getItemMeta();
            if (meta == null || !meta.hasLore()) return false;
            List<String> lore = meta.getLore();
            if (lore == null || lore.isEmpty()) return false;
            String first = lore.get(0);
            if (first == null) return false;
            return MONEY_FROM_MOBS_MARKER.matcher(first).find();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Если установлен MoneyFromMobs — пытаемся начислить баланс напрямую,
     * повторяя логику их PickUpListeners (EntityPickupItemEvent).
     *
     * Возвращает true, если деньги начислены и предмет НЕ нужно оставлять в дропе.
     */
    private boolean tryGiveMoneyFromMobs(ItemStack stack, Player killer) {
        try {
            initMoneyFromMobsHooks();
            if (killer == null || stack == null) return false;

            var pl = Bukkit.getPluginManager().getPlugin("MoneyFromMobs");
            if (pl == null) return false;
            if (mfmGetPickUpManager == null || mfmIsMoneyPickedUp == null || mfmGiveMoney == null) return false;

            Object pickUpManager = mfmGetPickUpManager.invoke(pl);
            if (pickUpManager == null) return false;

            Object res = mfmIsMoneyPickedUp.invoke(pickUpManager, stack);
            if (!(res instanceof Boolean) || !((Boolean) res)) return false;

            // У MoneyFromMobs подбор денег требует права MoneyFromMobs.use
            if (!killer.hasPermission("MoneyFromMobs.use")) {
                return false;
            }

            if (!stack.hasItemMeta()) return false;
            var meta = stack.getItemMeta();
            if (meta == null || !meta.hasLore()) return false;
            List<String> lore = meta.getLore();
            if (lore == null || lore.size() < 2) return false;

            // shouldOnlyKillerPickUpMoney + проверка имени убийцы в 3-й строке лора
            boolean onlyKiller = false;
            if (mfmShouldOnlyKillerPickUpMoney != null) {
                Object ok = mfmShouldOnlyKillerPickUpMoney.invoke(pickUpManager);
                if (ok instanceof Boolean) onlyKiller = (Boolean) ok;
            }
            if (onlyKiller && lore.size() > 2) {
                String allowedName = lore.get(2);
                if (allowedName != null && !allowedName.equals(killer.getName())) {
                    return false;
                }
            }

            // Сумма лежит во 2-й строке лора (index 1)
            String amountRaw = lore.get(1);
            if (amountRaw == null) return false;
            String amountStr = ChatColor.stripColor(amountRaw).trim();
            if (amountStr.isEmpty()) return false;

            double amount = Double.parseDouble(amountStr);
            mfmGiveMoney.invoke(pickUpManager, Double.valueOf(amount), killer);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
