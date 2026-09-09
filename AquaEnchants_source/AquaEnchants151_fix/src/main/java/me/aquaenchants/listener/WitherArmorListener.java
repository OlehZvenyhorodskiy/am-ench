package me.aquaenchants.listener;

import me.aquaenchants.AquaEnchatsPlugin;
import me.aquaenchants.enchant.CustomEnchant;
import me.aquaenchants.enchant.EffectConfig;
import me.aquaenchants.enchant.EnchantLevel;
import me.aquaenchants.enchant.EnchantManager;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;

public class WitherArmorListener implements Listener {

    private final AquaEnchatsPlugin plugin;
    private final EnchantManager enchantManager;
    private final Random random = new Random();
    private final Map<UUID, Long> cooldowns = new HashMap<>();

    public WitherArmorListener(AquaEnchatsPlugin plugin, EnchantManager enchantManager) {
        this.plugin = plugin;
        this.enchantManager = enchantManager;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        LivingEntity attacker = null;
        if (damager instanceof LivingEntity) {
            attacker = (LivingEntity) damager;
        } else if (damager instanceof Projectile) {
            Projectile proj = (Projectile) damager;
            if (proj.getShooter() instanceof LivingEntity) {
                attacker = (LivingEntity) proj.getShooter();
            }
        }
        if (attacker == null) {
            return;
        }

        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        Player victim = (Player) event.getEntity();

        ItemStack[] armor = victim.getInventory().getArmorContents();
        CustomEnchant wither = null;
        int level = 0;

        if (armor != null) {
            for (ItemStack piece : armor) {
                if (piece == null || piece.getType().isAir()) continue;
                Map<CustomEnchant, Integer> enchants = enchantManager.getEnchantmentsOnItem(piece);
                if (enchants == null || enchants.isEmpty()) continue;
                for (Map.Entry<CustomEnchant, Integer> e : enchants.entrySet()) {
                    CustomEnchant ench = e.getKey();
                    Integer lvl = e.getValue();
                    if (ench == null || lvl == null || lvl <= 0) continue;
                    String id = ench.getId();
                    if (id == null) continue;
                    if ("wither".equalsIgnoreCase(id)) {
                        if (lvl > level) {
                            wither = ench;
                            level = lvl;
                        }
                    }
                }
            }
        }

        if (wither == null || level <= 0) {
            return;
        }

        EnchantLevel data = wither.getLevel(level);
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

        int cooldownSec = data.getCooldown();
        if (cooldownSec > 0) {
            long now = System.currentTimeMillis();
            long cdMs = cooldownSec * 1000L;
            UUID uuid = victim.getUniqueId();
            Long last = cooldowns.get(uuid);
            if (last != null && now - last < cdMs) {
                return;
            }
            cooldowns.put(uuid, now);
        }

        List<EffectConfig> effects = data.getEffects();
        if (effects == null || effects.isEmpty()) {
            return;
        }

        for (EffectConfig ec : effects) {
            if (ec == null) continue;
            String raw = ec.getId();
            if (raw == null) continue;
            String trimmed = raw.trim();
            String upper = trimmed.toUpperCase(Locale.ROOT);
            if (!upper.contains("@ATTACKER")) continue;
            String clean = trimmed.split("@")[0].trim();
            String upperClean = clean.toUpperCase(Locale.ROOT);
            if (!upperClean.startsWith("POTION:WITHER")) continue;

            String[] parts = clean.split(":", 4);
            int amplifier = 0;
            int duration = 60;
            if (parts.length >= 3) {
                try {
                    amplifier = Integer.parseInt(parts[2]);
                } catch (NumberFormatException ignored) {
                }
            }
            if (parts.length >= 4) {
                try {
                    duration = Integer.parseInt(parts[3]);
                } catch (NumberFormatException ignored) {
                }
            }

            int ticks = duration * 20;
            PotionEffectType type = PotionEffectType.WITHER;
            if (type != null && attacker.isValid()) {
                attacker.addPotionEffect(new PotionEffect(type, ticks, amplifier, true, true, true));
            }
            break;
        }
    }
}
