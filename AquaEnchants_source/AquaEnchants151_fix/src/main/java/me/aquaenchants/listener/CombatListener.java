package me.aquaenchants.listener;

import me.aquaenchants.AquaEnchatsPlugin;
import me.aquaenchants.enchant.CustomEnchant;
import me.aquaenchants.enchant.EnchantLevel;
import me.aquaenchants.enchant.EnchantManager;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Trident;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * Обработчик боевых кастомных зачарований (в т.ч. permafrost).
 *
 * Логика:
 *  - ищем на оружии энчант с id `permafrost`;
 *  - берём конфигурацию уровня (chance, cooldown, effects);
 *  - по шансy применяем последовательность эффектов:
 *      DO_HARM:x @Victim  - наносит x единиц урона (x * 0.5 сердечка);
 *      BLOOD @Victim      - частицы крови / разбитых сердец вокруг жертвы;
 *      POTION:TYPE:amp:dur @Victim - накладывает эффект зелья;
 *      WAIT:ticks         - пауза перед следующими шагами.
 */
public class CombatListener implements Listener {

    private final AquaEnchatsPlugin plugin;
    private final EnchantManager enchantManager;
    private final Random random = new Random();
    // Кулдауны по атакующему игроку для боевых зачарований
    private final Map<UUID, Long> permafrostCooldowns = new HashMap<>();
    private final Map<UUID, Long> lightsCooldowns = new HashMap<>();
    private final Map<UUID, Long> allureCooldowns = new HashMap<>();
    private final Map<UUID, Long> frenzyCooldowns = new HashMap<>();
    private final Map<UUID, Long> paralyzeCooldowns = new HashMap<>();
    private final Map<UUID, Long> strifeCooldowns = new HashMap<>();

    public CombatListener(AquaEnchatsPlugin plugin, EnchantManager enchantManager) {
        this.plugin = plugin;
        this.enchantManager = enchantManager;
    }

    
        /**
     * Применяет защиту от зачарования reinforced на элитрах жертвы.
     */
    private void applyReinforcedDefense(LivingEntity victim, EntityDamageByEntityEvent event) {
        if (!(victim instanceof Player)) return;
        Player player = (Player) victim;
        ItemStack chest = player.getInventory().getChestplate();
        if (chest == null || chest.getType() == Material.AIR) return;

        Map<CustomEnchant, Integer> armorEnchants = enchantManager.getEnchantmentsOnItem(chest);
        if (armorEnchants == null || armorEnchants.isEmpty()) return;

        CustomEnchant reinforcedEnchant = null;
        int reinforcedLevel = 0;
        for (Map.Entry<CustomEnchant, Integer> e : armorEnchants.entrySet()) {
            CustomEnchant ench = e.getKey();
            Integer lvl = e.getValue();
            if (ench == null || lvl == null || lvl <= 0) continue;
            String id = ench.getId();
            if (id == null) continue;
            if ("reinforced".equalsIgnoreCase(id)) {
                reinforcedEnchant = ench;
                reinforcedLevel = lvl;
                break;
            }
        }
        if (reinforcedEnchant == null || reinforcedLevel <= 0) return;

        EnchantLevel data = reinforcedEnchant.getLevel(reinforcedLevel);
        if (data == null) return;

        int chance = data.getChance();
        if (chance > 0 && random.nextInt(100) >= chance) {
            plugin.debug("[reinforced] victim=" + player.getName() + " level=" + reinforcedLevel +
                    " chance=" + chance + " -> not triggered");
            return;
        }

        int percent = 0;
        for (me.aquaenchants.enchant.EffectConfig ec : data.getEffects()) {
            if (ec == null) continue;
            String eff = ec.getId();
            if (eff == null) continue;
            String trimmed = eff.trim();
            if (trimmed.toUpperCase(Locale.ROOT).startsWith("DECREASE_DAMAGE")) {
                String[] parts = trimmed.split(":", 3);
                if (parts.length >= 2) {
                    try {
                        percent = Integer.parseInt(parts[1]);
                    } catch (NumberFormatException ignored) {
                    }
                }
                break;
            }
        }
        if (percent <= 0) return;

        double baseDamage = event.getDamage();
        double clampedPercent = Math.max(0.0, Math.min(100.0, percent));
        double newDamage = baseDamage * (1.0 - clampedPercent / 100.0);
        event.setDamage(newDamage);
        plugin.debug("[reinforced] victim=" + player.getName() +
                " level=" + reinforcedLevel +
                " chance=" + chance +
                " percent=" + clampedPercent +
                " oldDamage=" + baseDamage +
                " newDamage=" + newDamage);
    }

@EventHandler(ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        LivingEntity victim = null;
        if (event.getEntity() instanceof LivingEntity) {
            victim = (LivingEntity) event.getEntity();
        }
        if (victim == null) return;

        // Защита от зачарования reinforced на элитрах жертвы.
        applyReinforcedDefense(victim, event);

        // Манёвр — шанс полностью избежать входящего урона.
        applyManevrDefense(victim, event);

        LivingEntity attacker = null;
        ItemStack weapon = null;

        // Поддержка не только игроков, но и мобов (например, хранителей из других плагинов).
        if (damager instanceof LivingEntity) {
            attacker = (LivingEntity) damager;
            if (attacker.getEquipment() != null) {
                weapon = attacker.getEquipment().getItemInMainHand();
            }
        } else if (damager instanceof Trident) {
            Trident trident = (Trident) damager;
            if (trident.getShooter() instanceof LivingEntity) {
                attacker = (LivingEntity) trident.getShooter();
                // Если у трезубца есть ItemStack — используем его, иначе берём оружие в руке.
                weapon = trident.getItem();
                if ((weapon == null || weapon.getType() == Material.AIR) && attacker.getEquipment() != null) {
                    weapon = attacker.getEquipment().getItemInMainHand();
                }
            }
        } else if (damager instanceof Projectile) {
            Projectile proj = (Projectile) damager;
            if (proj.getShooter() instanceof LivingEntity) {
                attacker = (LivingEntity) proj.getShooter();
                if (attacker.getEquipment() != null) {
                    weapon = attacker.getEquipment().getItemInMainHand();
                }
            }
        }

        if (attacker == null) return;
        if (weapon == null || weapon.getType() == Material.AIR) return;

        boolean meleeTridentAttack = (event.getDamager() instanceof LivingEntity)
                && weapon.getType() == Material.TRIDENT;

        Map<CustomEnchant, Integer> enchants = enchantManager.getEnchantmentsOnItem(weapon);
        if (enchants == null || enchants.isEmpty()) return;

        CustomEnchant permafrost = null;
        int permafrostLevel = 0;
        CustomEnchant lights = null;
        int lightsLevel = 0;
        CustomEnchant allure = null;
        int allureLevel = 0;
        CustomEnchant frenzy = null;
        int frenzyLevel = 0;
        CustomEnchant paralyze = null;
        int paralyzeLevel = 0;
        CustomEnchant strife = null;
        int strifeLevel = 0;

        for (Map.Entry<CustomEnchant, Integer> e : enchants.entrySet()) {
            CustomEnchant ench = e.getKey();
            Integer lvl = e.getValue();
            if (ench == null || lvl == null || lvl <= 0) continue;
            String id = ench.getId();
            if (id == null) continue;

            if ("permafrost".equalsIgnoreCase(id)) {
                permafrost = ench;
                permafrostLevel = lvl;
            } else if ("lights".equalsIgnoreCase(id)) {
                lights = ench;
                lightsLevel = lvl;
            } else if ("allure".equalsIgnoreCase(id)) {
                allure = ench;
                allureLevel = lvl;
            } else if ("frenzy".equalsIgnoreCase(id)) {
                frenzy = ench;
                frenzyLevel = lvl;
            } else if ("paralyze".equalsIgnoreCase(id)) {
                paralyze = ench;
                paralyzeLevel = lvl;
            } else if ("strife".equalsIgnoreCase(id)) {
                strife = ench;
                strifeLevel = lvl;
            }
        }
if (permafrost != null && permafrostLevel > 0) {
            processCombatEnchant(permafrost, permafrostLevel, attacker, victim, permafrostCooldowns, "permafrost");
        }

        if (lights != null && lightsLevel > 0) {
            processCombatEnchant(lights, lightsLevel, attacker, victim, lightsCooldowns, "lights");
        }

        if (allure != null && allureLevel > 0) {
            processCombatEnchant(allure, allureLevel, attacker, victim, allureCooldowns, "allure");
        }

        if (frenzy != null && frenzyLevel > 0) {
            processCombatEnchant(frenzy, frenzyLevel, attacker, victim, frenzyCooldowns, "frenzy");
        }

        if (paralyze != null && paralyzeLevel > 0) {
            processCombatEnchant(paralyze, paralyzeLevel, attacker, victim, paralyzeCooldowns, "paralyze");
        }

        if (meleeTridentAttack && strife != null && strifeLevel > 0) {
            applyStrifeDamageBonus(strife, strifeLevel, attacker, event, strifeCooldowns);
        }
    }
/**
     * Обрабатывает боевые кастомные зачарования, основанные на списке effects.
     * Поддерживает DO_HARM, BLOOD, POTION, WAIT, LIGHTNING, TNT, PARTICLE и другие.
     */
    private void processCombatEnchant(CustomEnchant enchant,
                                      int level,
                                      LivingEntity attacker,
                                      LivingEntity victim,
                                      Map<UUID, Long> cooldowns,
                                      String debugId) {
        if (enchant == null || level <= 0) return;

        EnchantLevel data = enchant.getLevel(level);
        if (data == null) return;

        plugin.debug("[" + debugId + "] attacker=" + attacker.getName() + " level=" + level + " chance=" + data.getChance());

        int chance = data.getChance();
        if (chance <= 0) return;
        if (chance < 100 && random.nextInt(100) >= chance) {
            return;
        }

        int cooldownSeconds = data.getCooldown();
        if (cooldownSeconds > 0) {
            long now = System.currentTimeMillis();
            long cdMs = cooldownSeconds * 1000L;
            Long last = cooldowns.get(attacker.getUniqueId());
            if (last != null && now - last < cdMs) {
                return;
            }
            cooldowns.put(attacker.getUniqueId(), now);
        }

        java.util.List<me.aquaenchants.enchant.EffectConfig> effects = data.getEffects();
        if (effects == null || effects.isEmpty()) return;

        // Выполняем эффекты по очереди, учитывая WAIT как задержку.
        long delay = 0L;
        for (me.aquaenchants.enchant.EffectConfig ec : effects) {
            final String raw = ec.getId();
            if (raw == null || raw.isEmpty()) continue;

            String main = raw.trim();
            int space = main.indexOf(' ');
            if (space != -1) {
                main = main.substring(0, space).trim();
            }
            int at = main.indexOf('@');
            if (at != -1) {
                main = main.substring(0, at).trim();
            }

            String[] parts = main.split(":");
            if (parts.length == 0) continue;
            String key = parts[0].toUpperCase(java.util.Locale.ROOT);

            if (key.equals("WAIT")) {
                int ticks = 0;
                if (parts.length >= 2) {
                    try {
                        ticks = Integer.parseInt(parts[1]);
                    } catch (NumberFormatException ignored) {}
                }
                if (ticks > 0) {
                    delay += ticks;
                }
                continue;
            }

            final LivingEntity target = victim;
            final Entity src = attacker;
            final long runDelay = delay;

            switch (key) {
                case "DO_HARM": {
                    int amount = 1;
                    if (parts.length >= 2) {
                        try {
                            amount = Integer.parseInt(parts[1]);
                        } catch (NumberFormatException ignored) {}
                    }
                    final double damage = amount; // 1 = пол-сердца

                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (!target.isValid() || target.isDead()) return;
                        try {
                            target.damage(damage, src);
                        } catch (Throwable ignored) {
                            target.damage(damage);
                        }
                        spawnBloodParticles(target);
                    }, runDelay);
                    break;
                }
                case "BLOOD": {
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (!target.isValid() || target.isDead()) return;
                        spawnBloodParticles(target);
                    }, runDelay);
                    break;
                }
                case "POTION": {
                    if (parts.length >= 4) {
                        String typeName = parts[1].toUpperCase(java.util.Locale.ROOT);
                        int amplifier = 0;
                        int duration = 0;
                        try {
                            amplifier = Integer.parseInt(parts[2]);
                        } catch (NumberFormatException ignored) {}
                        try {
                            duration = Integer.parseInt(parts[3]);
                        } catch (NumberFormatException ignored) {}

                        final int ampFinal = Math.max(0, amplifier);
                        final int durFinal = Math.max(1, duration);

                        Bukkit.getScheduler().runTaskLater(plugin, () -> {
                            if (!target.isValid() || target.isDead()) return;
                            PotionEffectType pet = PotionEffectType.getByName(typeName);
                            if (pet != null) {
                                target.addPotionEffect(new PotionEffect(pet, durFinal, ampFinal));
                            }
                        }, runDelay);
                    }
                    break;
                }
                
                case "TNT": {
                    // Взрыв без разрушения блоков: звук, частицы и прямой урон по жертве.
                    final int lvlCopy = level;
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (!target.isValid() || target.isDead()) return;
                        try {
                            org.bukkit.Location loc = target.getLocation();
                            org.bukkit.World world = loc.getWorld();
                            if (world == null) return;

                            // Лог для отладки
                            plugin.debug("[" + debugId + "] TNT effect (custom damage) at "
                                    + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ()
                                    + " level=" + lvlCopy);

                            // Звук взрыва
                            try {
                                world.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 1.2f, 0.9f);
                                world.playSound(loc, Sound.ENTITY_WIND_CHARGE_WIND_BURST, 1.0f, 1.2f);
                            } catch (Throwable ignored) {
                            }

                            // Частицы взрыва
                            try {
                                world.spawnParticle(Particle.EXPLOSION_EMITTER, loc, 1, 0.0, 0.0, 0.0, 0.0);
                                world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, loc.clone().add(0, 0.3, 0), 12, 0.35, 0.25, 0.35, 0.04);
                            } catch (Throwable ignored) {
                            }

                            // Прямой урон по цели (усиливается с уровнем)
                            double base = 6.0; // 3 сердца
                            double perLevel = 2.0; // +1 сердце за уровень поверх первого
                            int effLevel = Math.max(1, lvlCopy);
                            double damage = base + perLevel * (effLevel - 1);

                            try {
                                if (src != null) {
                                    target.damage(damage, src);
                                } else {
                                    target.damage(damage);
                                }
                            } catch (Throwable ignored) {
                            }
                        } catch (Throwable ignored) {
                        }
                    }, runDelay);
                    break;
                }

                case "PARTICLE": {
                    String particleName = "SONIC_BOOM";
                    int count = 50;
                    double radius = 1.5;
                    if (parts.length >= 2) {
                        particleName = parts[1];
                    }
                    if (parts.length >= 3) {
                        try {
                            count = Integer.parseInt(parts[2]);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    if (parts.length >= 4) {
                        try {
                            radius = Double.parseDouble(parts[3]);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    final String pName = particleName.toUpperCase(java.util.Locale.ROOT);
                    final int pCount = Math.max(1, count);
                    final double rad = Math.max(0.0, radius);

                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (!target.isValid() || target.isDead()) return;
                        try {
                            org.bukkit.Particle particle;
                            try {
                                particle = org.bukkit.Particle.valueOf(pName);
                            } catch (IllegalArgumentException ex) {
                                particle = org.bukkit.Particle.CRIT;
                            }
                            org.bukkit.Location loc = target.getLocation().add(0, target.getHeight() * 0.5, 0);
                            double off = rad;
                            plugin.debug("[" + debugId + "] PARTICLE effect " + pName + " count=" + pCount + " radius=" + rad);
                            target.getWorld().spawnParticle(particle, loc, pCount, off, off, off, 0.0);
                        } catch (Throwable ignored) {
                        }
                    }, runDelay);
                    break;
                }


                case "EXTINGUISH": {
                    // Снимает поджигание с цели (огонь, лаву и т.п.).
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (!target.isValid() || target.isDead()) return;
                        try {
                            target.setFireTicks(0);
                        } catch (Throwable ignored) {
                        }
                    }, runDelay);
                    break;
                }

                case "LIGHTNING": {
                    final int percent = computeLightningPercent(enchant, raw);
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (!target.isValid() || target.isDead()) return;
                        try {
                            Location tLoc = target.getLocation();
                            World tw = target.getWorld();
                            tw.strikeLightningEffect(tLoc);
                            tw.spawnParticle(Particle.ELECTRIC_SPARK, tLoc.clone().add(0, 1.0, 0), 25, 0.4, 0.5, 0.4, 0.15);
                            tw.spawnParticle(Particle.FLASH, tLoc.clone().add(0, 1.5, 0), 1, 0, 0, 0, 0);
                            tw.playSound(tLoc, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 1.2f, 1.1f);
                        } catch (Throwable ignored) {
                        }

                        int effectivePercent = percent;
                        int defensePercent = computeDefLightningPercent(target);
                        if (defensePercent > 0) {
                            effectivePercent = Math.max(0, percent - defensePercent);
                        }

                        if (effectivePercent > 0) {
                            double currentHp;
                            try {
                                currentHp = target.getHealth();
                            } catch (Throwable ignored) {
                                currentHp = target.getHealth();
                            }
                            double factor = 1.0 - (effectivePercent / 100.0);
                            double newHp = currentHp * factor;
                            double maxHp;
                            try {
                                maxHp = target.getMaxHealth();
                            } catch (Throwable ignored) {
                                maxHp = currentHp;
                            }
                            if (newHp < 0.0) newHp = 0.0;
                            if (newHp > maxHp) newHp = maxHp;
                            plugin.getLogger().info("[AquaEnchats] [lights] victim=" + target.getName()
                                    + " hpBefore=" + currentHp
                                    + " hpAfter=" + newHp
                                    + " removed=" + (currentHp - newHp)
                                    + " basePercent=" + percent
                                    + " defensePercent=" + defensePercent
                                    + " effectivePercent=" + effectivePercent);
                            try {
                                target.setHealth(newHp);
                            } catch (Throwable ignored) {
                            }
                        } else {
                            // Полностью заблокирован deflights, лог для отладки
                            plugin.getLogger().info("[AquaEnchats] [lights] victim=" + target.getName()
                                    + " hpBefore=" + target.getHealth()
                                    + " hpAfter=" + target.getHealth()
                                    + " removed=0.0"
                                    + " basePercent=" + percent
                                    + " defensePercent=" + defensePercent
                                    + " effectivePercent=0");
                        }
                    }, runDelay);
                    break;
                }


                case "PULL_CLOSER": {
                    double blocks = 1.0;
                    if (parts.length >= 2) {
                        try {
                            blocks = Double.parseDouble(parts[1]);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    final double pullBlocks = Math.max(0.5, Math.min(32.0, blocks));

                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (src == null) return;
                        // src may be a mob; isOnline() exists only for Player
                        if (src instanceof Player && !((Player) src).isOnline()) return;
                        if (!src.isValid()) return;
                        if (src instanceof LivingEntity && ((LivingEntity) src).isDead()) return;
                        if (!target.isValid() || target.isDead()) return;
                        try {
                            Vector dir = src.getLocation().toVector().subtract(target.getLocation().toVector());
                            double distance = dir.length();
                            if (distance < 0.1) {
                                return;
                            }
                            dir.normalize();

                            // сколько реально тянуть за это срабатывание
                            double step = Math.min(pullBlocks, distance);
                            if (step < 0.25) {
                                step = Math.max(distance * 0.5, 0.25);
                            }

                            plugin.debug("[allure] pullCloser src=" + src.getName()
                                    + " target=" + target.getName()
                                    + " blocks=" + pullBlocks
                                    + " dist=" + distance
                                    + " step=" + step);

                            Vector move = dir.multiply(step);
                            org.bukkit.Location newLoc = target.getLocation().clone().add(move);
                            target.teleport(newLoc);

                            // небольшой дополнительный импульс вперёд к игроку
                            try {
                                target.setVelocity(dir.multiply(0.3));
                            } catch (Throwable ignored) {
                            }
                        } catch (Throwable ignored) {
                        }
                    }, runDelay);
                    break;
                }
                default:
                    break;
            }
        }
    }

    /**
     * Вычисляет процент дополнительного урона для LIGHTNING
     * на основе параметра powers в enachants.yml и выражения в effects
     * вида LIGHTNING: (%power% -10) @Victim.
     */
    private int computeLightningPercent(CustomEnchant enchant, String raw) {
        int base = enchant.getPowerPercent();
        if (base <= 0) base = 25;
        if (raw == null) return base;

        // Ищем смещение вида "%power% -10" или "%power% -1"
        int idx = raw.indexOf("%power%");
        if (idx != -1) {
            int minusIndex = raw.indexOf('-', idx);
            if (minusIndex != -1) {
                int startNum = minusIndex + 1;
                int endNum = startNum;
                while (endNum < raw.length() && Character.isDigit(raw.charAt(endNum))) {
                    endNum++;
                }
                if (endNum > startNum) {
                    try {
                        int delta = Integer.parseInt(raw.substring(startNum, endNum).trim());
                        base = Math.max(0, base - delta);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        return base;
    }


    /**
     * Вычисляет процент снижения урона молнии для защиты deflights на жертве.
     * Возвращает значение, полученное из конфигурации deflights (число после %power% -N).
     */
    private int computeDefLightningPercent(LivingEntity victim) {
        if (!(victim instanceof Player)) {
            return 0;
        }
        Player player = (Player) victim;
        ItemStack[] armor = player.getInventory().getArmorContents();
        if (armor == null || armor.length == 0) {
            return 0;
        }

        CustomEnchant def = enchantManager.getEnchant("deflights");
        if (def == null) {
            return 0;
        }

        int maxLevel = 0;
        for (ItemStack piece : armor) {
            if (piece == null || piece.getType() == Material.AIR) {
                continue;
            }
            Map<CustomEnchant, Integer> map = enchantManager.getEnchantmentsOnItem(piece);
            if (map == null || map.isEmpty()) {
                continue;
            }
            Integer lvl = map.get(def);
            if (lvl != null && lvl > maxLevel) {
                maxLevel = lvl;
            }
        }

        if (maxLevel <= 0) {
            return 0;
        }

        EnchantLevel level = def.getLevel(maxLevel);
        if (level == null) {
            return 0;
        }
        java.util.List<me.aquaenchants.enchant.EffectConfig> effects = level.getEffects();
        if (effects == null || effects.isEmpty()) {
            return 0;
        }

        for (me.aquaenchants.enchant.EffectConfig ec : effects) {
            String raw = ec.getId();
            if (raw == null) {
                continue;
            }
            String main = raw.trim();
            int space = main.indexOf(' ');
            if (space != -1) {
                main = main.substring(0, space).trim();
            }
            String upper = main.toUpperCase(java.util.Locale.ROOT);
            if (!upper.startsWith("DEFENSE")) {
                continue;
            }

            int idx = raw.indexOf("%power%");
            if (idx != -1) {
                int minusIndex = raw.indexOf('-', idx);
                if (minusIndex != -1) {
                    int startNum = minusIndex + 1;
                    int endNum = startNum;
                    while (endNum < raw.length() && Character.isDigit(raw.charAt(endNum))) {
                        endNum++;
                    }
                    if (endNum > startNum) {
                        try {
                            int val = Integer.parseInt(raw.substring(startNum, endNum).trim());
                            return Math.max(0, val);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }
        }

        return 0;
    }

    private void spawnBloodParticles(LivingEntity target) {
        try {
            Location loc = target.getLocation().add(0, target.getHeight() * 0.5, 0);
            World world = target.getWorld();
            Particle.DustOptions bloodDust = new Particle.DustOptions(Color.fromRGB(180, 0, 0), 1.2f);
            world.spawnParticle(Particle.DUST, loc, 25, 0.35, 0.35, 0.35, 0.08, bloodDust);
            world.spawnParticle(Particle.BLOCK, loc, 15, 0.25, 0.25, 0.25, 0.05, Material.REDSTONE_BLOCK.createBlockData());
            world.spawnParticle(Particle.DAMAGE_INDICATOR, loc, 8, 0.4, 0.4, 0.4, 0.1);
            world.playSound(loc, Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.8f, 0.7f);
            world.playSound(loc, Sound.ENTITY_PLAYER_HURT_SWEET_BERRY_BUSH, 0.7f, 0.9f);
        } catch (Throwable ignored) {
        }
    }

/**
 * Дополнительный урон для зачарования strife (боевой трезубец).
 * Берёт шанс, кулдаун и процент INCREASE_DAMAGE из конфига уровня.
 */
private void applyStrifeDamageBonus(CustomEnchant enchant,
                                    int level,
                                    LivingEntity attacker,
                                    EntityDamageByEntityEvent event,
                                    Map<UUID, Long> cooldowns) {
    if (enchant == null || level <= 0 || attacker == null || event == null) {
        return;
    }

    EnchantLevel data = enchant.getLevel(level);
    if (data == null) {
        return;
    }

    int chance = data.getChance();
    if (chance <= 0) {
        return;
    }
    if (chance < 100 && random.nextInt(100) >= chance) {
        return;
    }

    int cooldownSeconds = data.getCooldown();
    if (cooldownSeconds > 0) {
        long now = System.currentTimeMillis();
        long cdMs = cooldownSeconds * 1000L;
        Long last = cooldowns.get(attacker.getUniqueId());
        if (last != null && now - last < cdMs) {
            return;
        }
        cooldowns.put(attacker.getUniqueId(), now);
    }

    int percent = 0;
    java.util.List<me.aquaenchants.enchant.EffectConfig> effects = data.getEffects();
    if (effects != null) {
        for (me.aquaenchants.enchant.EffectConfig ec : effects) {
            if (ec == null) continue;
            String eff = ec.getId();
            if (eff == null) continue;
            String trimmed = eff.trim();
            if (trimmed.toUpperCase(java.util.Locale.ROOT).startsWith("INCREASE_DAMAGE")) {
                String[] parts = trimmed.split(":", 3);
                if (parts.length >= 2) {
                    try {
                        percent = Integer.parseInt(parts[1]);
                    } catch (NumberFormatException ignored) {
                    }
                }
                break;
            }
        }
    }

    if (percent <= 0) {
        return;
    }

    double baseDamage = event.getDamage();
    double clampedPercent = Math.max(0.0, Math.min(1000.0, percent));
    double newDamage = baseDamage * (1.0 + clampedPercent / 100.0);
    event.setDamage(newDamage);
}


    /**
     * Манёвр — защитное зачарование на броню.
     * Даёт шанс полностью отменить входящий урон от мобов или игроков.
     * Проценты берутся из effects уровня (строки вида "MANEVR: 10%%").
     */
    private void applyManevrDefense(LivingEntity victim, EntityDamageByEntityEvent event) {
        if (!(victim instanceof Player)) {
            return;
        }
        Player player = (Player) victim;
        ItemStack[] armor = player.getInventory().getArmorContents();
        if (armor == null || armor.length == 0) {
            return;
        }

        CustomEnchant manevr = enchantManager.getEnchant("manevr");
        if (manevr == null) {
            return;
        }

        int maxLevel = 0;
        for (ItemStack piece : armor) {
            if (piece == null || piece.getType() == Material.AIR) {
                continue;
            }
            java.util.Map<CustomEnchant, Integer> map = enchantManager.getEnchantmentsOnItem(piece);
            if (map == null || map.isEmpty()) {
                continue;
            }
            Integer lvl = map.get(manevr);
            if (lvl != null && lvl > maxLevel) {
                maxLevel = lvl;
            }
        }

        if (maxLevel <= 0) {
            return;
        }

        EnchantLevel level = manevr.getLevel(maxLevel);
        if (level == null) {
            return;
        }

        java.util.List<me.aquaenchants.enchant.EffectConfig> effects = level.getEffects();
        if (effects == null || effects.isEmpty()) {
            return;
        }

        int chancePercent = 0;
        for (me.aquaenchants.enchant.EffectConfig ec : effects) {
            if (ec == null) {
                continue;
            }
            String raw = ec.getId();
            if (raw == null) {
                continue;
            }
            String trimmed = raw.trim();
            if (!trimmed.toUpperCase(java.util.Locale.ROOT).startsWith("MANEVR")) {
                continue;
            }
            String[] parts = trimmed.split(":", 2);
            if (parts.length >= 2) {
                String val = parts[1].trim();
                if (val.endsWith("%")) {
                    val = val.substring(0, val.length() - 1).trim();
                }
                try {
                    chancePercent = Integer.parseInt(val);
                } catch (NumberFormatException ignored) {
                    chancePercent = 0;
                }
            }
            break;
        }

        if (chancePercent <= 0) {
            return;
        }

        int clamped = Math.max(0, Math.min(100, chancePercent));
        if (random.nextInt(100) < clamped) {
            event.setCancelled(true);
            plugin.debug("[manevr] victim=" + player.getName()
                    + " level=" + maxLevel
                    + " chance=" + clamped
                    + " -> damage cancelled");
        }
    }

    @EventHandler
    public void onPlayerQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        permafrostCooldowns.remove(uuid);
        lightsCooldowns.remove(uuid);
        allureCooldowns.remove(uuid);
        frenzyCooldowns.remove(uuid);
        paralyzeCooldowns.remove(uuid);
        strifeCooldowns.remove(uuid);
    }
}
