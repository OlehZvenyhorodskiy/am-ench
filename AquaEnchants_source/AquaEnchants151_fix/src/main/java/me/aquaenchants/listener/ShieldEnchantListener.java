package me.aquaenchants.listener;

import me.aquaenchants.AquaEnchatsPlugin;
import me.aquaenchants.enchant.CustomEnchant;
import me.aquaenchants.enchant.EnchantManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.EulerAngle;
import org.bukkit.util.Vector;

import java.util.*;
import me.aquaenchants.util.ShieldPatternUtil;

/**
 * SHIELD enchant runtime: spawns invisible armor stands holding shields that orbit the player
 * while the player is blocking with an enchanted shield in the off-hand.
 */
public final class ShieldEnchantListener implements Listener {


    private static final String ORBIT_STAND_TAG = "aqua_shield_orbit";

    // Visual tuning
    private static final double RADIUS = 0.5;          // requested: 0.5 blocks
    private static final double Y_OFFSET = -0.5;       // requested: 1 block lower
    private static final double ANGULAR_SPEED_RAD_PER_TICK = Math.toRadians(6.0); // ~60 ticks / rotation

    // XP drain
    private static final int XP_COST = 10;
    private static final int XP_PERIOD_TICKS = 20;

    private final AquaEnchatsPlugin plugin;
    private final EnchantManager enchantManager;
    private final Map<UUID, OrbitSession> sessions = new HashMap<>();

    public ShieldEnchantListener(AquaEnchatsPlugin plugin, EnchantManager enchantManager) {
        this.plugin = plugin;
        this.enchantManager = enchantManager;
    }

    /** Cleanup on disable. */
    public void cleanup() {
        for (UUID id : new ArrayList<>(sessions.keySet())) {
            stop(id);
        }
        sessions.clear();
    }

    // Use ignoreCancelled=false so other plugins cancelling interact won't prevent activation.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        switch (event.getAction()) {
            case RIGHT_CLICK_AIR, RIGHT_CLICK_BLOCK -> {}
            default -> { return; }
        }

        // Some servers fire interact for either hand; activation is based on the OFFHAND shield.
        if (event.getHand() != EquipmentSlot.HAND && event.getHand() != EquipmentSlot.OFF_HAND) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack off = player.getInventory().getItemInOffHand();
        if (off == null || off.getType() != Material.SHIELD) return;

        int level = getShieldEnchantLevel(off);
        if (level <= 0) return;

        // already running
        if (sessions.containsKey(player.getUniqueId())) return;

        if (player.getLevel() < XP_COST) {
            player.sendMessage(ChatColor.RED + "Недостаточно опыта.");
            return;
        }

        start(player, Math.min(level, 3));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        OrbitSession s = sessions.get(player.getUniqueId());
        if (s == null) return;

        // Active only while the player is blocking. If they stopped blocking, tear down the session.
        if (!isPlayerBlocking(player)) {
            stop(player.getUniqueId());
            return;
        }

        // Determine incoming direction in the horizontal plane (from player towards the source of damage).
        Vector incoming = null;

        if (event.getDamager() instanceof org.bukkit.entity.Projectile projectile) {
            Vector v = projectile.getVelocity();
            if (v != null && v.lengthSquared() > 1.0e-6) {
                incoming = v.clone().multiply(-1); // from player towards the projectile's origin
            }
        } else {
            Location damagerLoc = event.getDamager().getLocation();
            Location playerLoc = player.getLocation();
            incoming = damagerLoc.toVector().subtract(playerLoc.toVector());
        }

        if (incoming == null || incoming.lengthSquared() < 1.0e-6) return;

        // Flatten (ignore vertical component) so gaps work properly.
        incoming.setY(0);
        if (incoming.lengthSquared() < 1.0e-6) return;
        incoming.normalize();

        // If the hit line passes through (or very near) any rotating shield, block it.
        // Otherwise, allow damage through the gaps between shields.
        final double THRESHOLD_RAD = Math.toRadians(25.0);

        Location playerLoc = player.getLocation();
        Vector p = playerLoc.toVector();

        for (ArmorStand stand : s.stands) {
            if (stand == null || stand.isDead() || !stand.isValid()) continue;

            Vector sd = stand.getLocation().toVector().subtract(p);
            sd.setY(0);
            if (sd.lengthSquared() < 1.0e-6) continue;
            sd.normalize();

            if (sd.angle(incoming) <= THRESHOLD_RAD) {
                event.setCancelled(true);
                return;
            }
        }
        // Not covered by shields -> damage goes through.
    }


    /**
     * Prevent any interaction with orbit armor stands (players stealing shields or equipping items).
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onArmorStandManipulate(PlayerArmorStandManipulateEvent event) {
        if (event.getRightClicked() != null && event.getRightClicked().getScoreboardTags().contains(ORBIT_STAND_TAG)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteractAtEntity(PlayerInteractAtEntityEvent event) {
        if (event.getRightClicked() instanceof ArmorStand as && as.getScoreboardTags().contains(ORBIT_STAND_TAG)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onStandDamage(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof ArmorStand as && as.getScoreboardTags().contains(ORBIT_STAND_TAG)) {
            event.setCancelled(true);
        }
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) { stop(event.getPlayer().getUniqueId()); }
    @EventHandler public void onDeath(PlayerDeathEvent event) { stop(event.getEntity().getUniqueId()); }
    @EventHandler public void onTeleport(PlayerTeleportEvent event) { stop(event.getPlayer().getUniqueId()); }
    @EventHandler public void onSwap(PlayerSwapHandItemsEvent event) { stop(event.getPlayer().getUniqueId()); }

    private void start(Player player, int level) {
        OrbitSession session = new OrbitSession(player.getUniqueId(), level);
        sessions.put(player.getUniqueId(), session);

        // Spawn armor stands in a ring aligned to the player's facing direction.
        // One shield starts in front of the player so the protection behaves like a normal shield,
        // and the others are spaced by 120° leaving gaps where damage can pass through.
        double frontAngle = angleFromDirection(player.getLocation().getDirection());

        for (int i = 0; i < level; i++) {
            double angle = frontAngle + (2.0 * Math.PI) * ((double) i / (double) level);
            ArmorStand stand = spawnShieldStand(player.getLocation(), angle);
            session.stands.add(stand);
            session.baseAngles.add(angle);
        }

        // Important: on modern Paper versions the server-side "blocking" state for shields
        // is commonly updated one tick after the initial right-click.
        session.taskId = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(player), 1L, 1L).getTaskId();
    }

    private void tick(Player player) {
        UUID id = player.getUniqueId();
        OrbitSession s = sessions.get(id);
        if (s == null) return;

        if (!player.isOnline()) {
            stop(id);
            return;
        }

        // Still holding correct shield with enchant?
        ItemStack off = player.getInventory().getItemInOffHand();
        if (off == null || off.getType() != Material.SHIELD || getShieldEnchantLevel(off) <= 0) {
            stop(id);
            return;
        }

        s.ticks++;

        // Only active while RMB is held (blocking). Grace 5 ticks for state sync.
        boolean blocking = isPlayerBlocking(player);
        if (!blocking) {
            if (s.ticks <= 5) return;
            stop(id);
            return;
        }

        // XP drain
        if (s.ticks % XP_PERIOD_TICKS == 0) {
            if (player.getLevel() < XP_COST) {
                player.sendMessage(ChatColor.RED + "Недостаточно опыта.");
                stop(id);
                return;
            }
            player.giveExp(-XP_COST);
        }

        Location center = player.getLocation().clone();
        center.setY(center.getY() + Y_OFFSET);

        for (int i = 0; i < s.stands.size(); i++) {
            ArmorStand stand = s.stands.get(i);
            if (stand == null || stand.isDead() || !stand.isValid()) {
                stop(id);
                return;
            }

            double a = s.baseAngles.get(i) - (ANGULAR_SPEED_RAD_PER_TICK * s.ticks); // clockwise
            double x = center.getX() + (RADIUS * Math.cos(a));
            double z = center.getZ() + (RADIUS * Math.sin(a));
            Location loc = new Location(center.getWorld(), x, center.getY(), z);

            // Face the player (handle towards player) +90° to avoid sideways look.
            Vector toPlayer = center.toVector().subtract(loc.toVector());
            float yaw = (float) Math.toDegrees(Math.atan2(-toPlayer.getX(), toPlayer.getZ()));
            loc.setYaw(yaw + 90f);
            loc.setPitch(0f);

            stand.teleport(loc);
            stand.setRightArmPose(new EulerAngle(Math.toRadians(90), 0, 0));
        }
    }


    private double angleFromDirection(Vector dir) {
        // Convert a direction vector to an angle for our cos/sin orbit placement.
        // a=0 -> +X, a=pi/2 -> +Z
        Vector d = dir.clone();
        d.setY(0);
        if (d.lengthSquared() < 1.0e-6) return 0.0;
        d.normalize();
        return Math.atan2(d.getZ(), d.getX());
    }

    private ArmorStand spawnShieldStand(Location base, double angle) {
        Location center = base.clone();
        center.setY(center.getY() + Y_OFFSET);

        double x = center.getX() + (RADIUS * Math.cos(angle));
        double z = center.getZ() + (RADIUS * Math.sin(angle));
        Location loc = new Location(center.getWorld(), x, center.getY(), z);

        ArmorStand stand = Objects.requireNonNull(loc.getWorld()).spawn(loc, ArmorStand.class, as -> {
            as.setInvisible(true);
            as.setGravity(false);
            as.setMarker(true);
            as.setSmall(false);
            as.setBasePlate(false);
            as.setArms(true);
            as.setInvulnerable(true);
            as.setCollidable(false);
            as.setCanPickupItems(false);
            as.setRemoveWhenFarAway(false);
            // Attempt to scale up where supported (Paper 1.20.5+). No compile-time deps.
            trySetScaleReflective(as, 1.5);
        });
        stand.addScoreboardTag(ORBIT_STAND_TAG);

        ItemStack orbitShield = new ItemStack(Material.SHIELD);
        ShieldPatternUtil.applyTulovikPattern(orbitShield);
        stand.getEquipment().setItemInMainHand(orbitShield);
        stand.getEquipment().setItemInOffHand(null);
        stand.setSilent(true);
        stand.setPersistent(false);
        return stand;
    }

    private void stop(UUID playerId) {
        OrbitSession s = sessions.remove(playerId);
        if (s == null) return;

        if (s.taskId != -1) {
            Bukkit.getScheduler().cancelTask(s.taskId);
        }
        for (ArmorStand stand : s.stands) {
            if (stand == null) continue;
            try { stand.remove(); } catch (Throwable ignored) {}
        }
    }

    /**
     * Paper/Spigot update the shield blocking flag slightly differently across versions.
     * We accept either "isBlocking" or (if available) "isHandRaised".
     *
     * On some modern builds (notably Paper/Spigot 1.20+ / 1.21+), Player#isBlocking can be
     * unreliable for off-hand shields (depending on what the main-hand item is doing).
     * In those cases, the only stable indicator is the entity "active item".
     */
    private boolean isPlayerBlocking(Player player) {
        // 1) Fast path: vanilla API
        try {
            if (player.isBlocking()) return true;
        } catch (Throwable ignored) {}

        // 2) Some versions expose isHandRaised()
        try {
            Object v = Player.class.getMethod("isHandRaised").invoke(player);
            if (v instanceof Boolean b && b) return true;
        } catch (Throwable ignored) {}

        // 3) Robust fallback: check active/using item reflectively
        // LivingEntity#getActiveItem() (Paper/Spigot)
        try {
            Object active = player.getClass().getMethod("getActiveItem").invoke(player);
            if (active instanceof ItemStack it && it.getType() == Material.SHIELD) {
                return true;
            }
        } catch (Throwable ignored) {}

        // LivingEntity#isUsingItem() (Paper)
        try {
            Object using = player.getClass().getMethod("isUsingItem").invoke(player);
            if (using instanceof Boolean b && b) {
                // If the player is "using" an item and has a shield in either hand, assume blocking.
                ItemStack off = player.getInventory().getItemInOffHand();
                ItemStack main = player.getInventory().getItemInMainHand();
                if ((off != null && off.getType() == Material.SHIELD) || (main != null && main.getType() == Material.SHIELD)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}

        return false;
    }

    private int getShieldEnchantLevel(ItemStack item) {
        if (item == null || item.getType().isAir()) return 0;
        CustomEnchant shield = enchantManager.getEnchant("shield");
        if (shield == null) return 0;
        Integer lvl = enchantManager.getEnchantmentsOnItem(item).get(shield);
        return lvl == null ? 0 : lvl;
    }

    /**
     * Try to scale the armor stand entity on newer servers (1.20.5+).
     * Avoid compile-time references to Attribute/AttributeInstance.
     */
    private static void trySetScaleReflective(ArmorStand stand, double scaleValue) {
        try {
            Class<?> attributeClass = Class.forName("org.bukkit.attribute.Attribute");
            if (!attributeClass.isEnum()) return;

            Object scaleAttr = null;
            for (String name : new String[]{"GENERIC_SCALE", "SCALE"}) {
                try {
                    @SuppressWarnings({"rawtypes", "unchecked"})
                    Object val = Enum.valueOf((Class) attributeClass, name);
                    scaleAttr = val;
                    break;
                } catch (Throwable ignored) {
                }
            }
            if (scaleAttr == null) return;

            // stand.getAttribute(Attribute)
            java.lang.reflect.Method getAttribute = stand.getClass().getMethod("getAttribute", attributeClass);
            Object attrInstance = getAttribute.invoke(stand, scaleAttr);
            if (attrInstance == null) return;

            // AttributeInstance#setBaseValue(double)
            java.lang.reflect.Method setBaseValue = attrInstance.getClass().getMethod("setBaseValue", double.class);
            setBaseValue.invoke(attrInstance, scaleValue);
        } catch (Throwable ignored) {
        }
    }

    private static final class OrbitSession {
        final UUID playerId;
        final int level;
        final List<ArmorStand> stands = new ArrayList<>();
        final List<Double> baseAngles = new ArrayList<>();
        int taskId = -1;
        int ticks = 0;

        OrbitSession(UUID playerId, int level) {
            this.playerId = playerId;
            this.level = level;
        }
    }
}