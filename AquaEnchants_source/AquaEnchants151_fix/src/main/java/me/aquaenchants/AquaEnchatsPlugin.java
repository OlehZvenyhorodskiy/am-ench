package me.aquaenchants;

import me.aquaenchants.command.AquaEnchantCommand;
import me.aquaenchants.command.AquaEnchantTabCompleter;
import me.aquaenchants.config.EnchantConfigLoader;
import me.aquaenchants.enchant.EnchantManager;
import me.aquaenchants.effect.EffectManager;
import me.aquaenchants.item.CustomItemManager;
import me.aquaenchants.listener.AnvilListener;
import me.aquaenchants.listener.UnrenamableBookAnvilListener;
import me.aquaenchants.listener.CustomEnchantingTableGUIListener;
import me.aquaenchants.listener.BlockBreakListener;
import me.aquaenchants.listener.EnchantCommandListener;
import me.aquaenchants.listener.VeinMinerListener;
import me.aquaenchants.listener.CombatListener;
import me.aquaenchants.listener.TimberEnergyListener;
import me.aquaenchants.listener.MomentumListener;
import me.aquaenchants.listener.InquisitiveListener;
import me.aquaenchants.listener.LipuchkaListener;
import me.aquaenchants.listener.PoisonRuneListener;
import me.aquaenchants.listener.BulovaRuneListener;
import me.aquaenchants.listener.IceshtormListener;
import me.aquaenchants.listener.WaterWalkerListener;
import me.aquaenchants.listener.LavaWalkerListener;
import me.aquaenchants.listener.PlanterListener;
import me.aquaenchants.listener.UnbreakableListener;
import me.aquaenchants.listener.WitherArmorListener;
import me.aquaenchants.listener.VillagerTradeListener;
import me.aquaenchants.listener.GlowingHelmetListener;
import me.aquaenchants.listener.DecapitationListener;
import me.aquaenchants.listener.IndikatorListener;
import me.aquaenchants.listener.SignalListener;
import me.aquaenchants.listener.PalirolListener;
import me.aquaenchants.listener.ExcavatorListener;
import me.aquaenchants.listener.ShieldEnchantListener;
import me.aquaenchants.excavator.*;
import me.aquaenchants.hook.CmiEnchantLimits;
import me.aquaenchants.separation.SeparationCommand;
import me.aquaenchants.separation.SeparationListener;
import me.aquaenchants.separation.SeparationManager;
import org.bukkit.configuration.serialization.ConfigurationSerialization;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import me.aquaenchants.listener.AnvilPreviewCompatListener;
import me.aquaenchants.listener.RepairHardFixListener;
import me.aquaenchants.listener.MendingCompatListener;

public class AquaEnchatsPlugin extends JavaPlugin {

    private static AquaEnchatsPlugin instance;

    private EnchantManager enchantManager;
    private EffectManager effectManager;
    private CustomItemManager customItemManager;
    private TimberEnergyListener timberEnergyListener;
    private WaterWalkerListener waterWalkerListener;
    private LavaWalkerListener lavaWalkerListener;
    private PalirolListener palirolListener;
    private ShieldEnchantListener shieldEnchantListener;
    private ExcavatorManager excavatorManager;
    private ExcavatorProcessor excavatorProcessor;
    private SeparationManager separationManager;
    private CmiEnchantLimits cmiEnchantLimits;
    private CustomEnchantingTableGUIListener customEnchantingTableGUIListener;
    private IndikatorListener indikatorListener;
    private me.aquaenchants.gui.AdminChanceGUI adminChanceGUI;
    private me.aquaenchants.config.TableSettingsManager tableSettingsManager;

    public CmiEnchantLimits getCmiEnchantLimits() {
        return cmiEnchantLimits;
    }

    public me.aquaenchants.gui.AdminChanceGUI getAdminChanceGUI() {
        return adminChanceGUI;
    }

    public me.aquaenchants.config.TableSettingsManager getTableSettingsManager() {
        return tableSettingsManager;
    }


    public static AquaEnchatsPlugin getInstance() {
        return instance;
    }

    @Override
    public void onEnable() {
        // Compatibility fixes for 1.20.1 clients (anvil preview) + forced repair
        getServer().getPluginManager().registerEvents(new AnvilPreviewCompatListener(this), this);
        getServer().getPluginManager().registerEvents(new RepairHardFixListener(this), this);
        // Extra safety: make sure vanilla MENDING repairs items consistently (incl. shields)
        getServer().getPluginManager().registerEvents(new MendingCompatListener(), this);

        instance = this;

        // Load CMI vanilla enchant limits (optional).
        // We use our own plugin data folder to infer the server's "plugins" directory
        // (it is the parent folder of this plugin's data folder).
        File pluginsDir = getDataFolder().getParentFile();
        this.cmiEnchantLimits = CmiEnchantLimits.loadFromCmiConfig(pluginsDir);

        // Регистрация сериализации данных экскаватора
        try {
            ConfigurationSerialization.registerClass(ExcavatorData.class);
        } catch (IllegalArgumentException ignored) {
            // класс уже зарегистрирован — игнорируем
        }

        // Ensure plugin data folder exists
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }

        // Create items folder
        File itemsFolder = new File(getDataFolder(), "items");
        if (!itemsFolder.exists()) {
            itemsFolder.mkdirs();
        }

        // Save default config and other resources
        saveDefaultConfig();
        saveResourceSafe("enachants.yml");
        saveResourceSafe("items/example_item.yml");
        saveResourceSafe("items/excavator.yml");

        // Load configuration
        EnchantConfigLoader configLoader = new EnchantConfigLoader(this);

        this.effectManager = new EffectManager(this);
        this.enchantManager = new EnchantManager(this, configLoader);
        this.customItemManager = new CustomItemManager(this, itemsFolder, enchantManager);

        // Система снятия зачарований
        this.separationManager = new SeparationManager(this, enchantManager);

        // Инициализация системы экскаватора
        this.excavatorManager = new ExcavatorManager(this);
        this.excavatorProcessor = new ExcavatorProcessor(this, excavatorManager);

        // Возобновляем работу экскаваторов после перезапуска плагина
        this.excavatorProcessor.resumeAllRunningExcavators();

        // Table settings manager for configurable chances & bookshelf bonus
        this.tableSettingsManager = new me.aquaenchants.config.TableSettingsManager(this);

        // Register listeners
        Bukkit.getPluginManager().registerEvents(new BlockBreakListener(enchantManager, effectManager), this);
        Bukkit.getPluginManager().registerEvents(new VeinMinerListener(this, enchantManager), this);
        // Custom enchanting table GUI (stable on Paper 1.21.8, shows custom + vanilla names explicitly)
        this.customEnchantingTableGUIListener = new CustomEnchantingTableGUIListener(this, enchantManager, tableSettingsManager);
        Bukkit.getPluginManager().registerEvents(customEnchantingTableGUIListener, this);
        this.adminChanceGUI = new me.aquaenchants.gui.AdminChanceGUI(this, enchantManager, tableSettingsManager);
        Bukkit.getPluginManager().registerEvents(adminChanceGUI, this);
        Bukkit.getPluginManager().registerEvents(new VillagerTradeListener(this, enchantManager), this);
        Bukkit.getPluginManager().registerEvents(new AnvilListener(enchantManager), this);
        Bukkit.getPluginManager().registerEvents(new EnchantCommandListener(this, enchantManager), this);
        Bukkit.getPluginManager().registerEvents(new UnrenamableBookAnvilListener(enchantManager), this);
        Bukkit.getPluginManager().registerEvents(new CombatListener(this, enchantManager), this);
        Bukkit.getPluginManager().registerEvents(new MomentumListener(this, enchantManager), this);
        Bukkit.getPluginManager().registerEvents(new PlanterListener(this, enchantManager), this);
        Bukkit.getPluginManager().registerEvents(new UnbreakableListener(this, enchantManager), this);
        Bukkit.getPluginManager().registerEvents(new WitherArmorListener(this, enchantManager), this);
        Bukkit.getPluginManager().registerEvents(new GlowingHelmetListener(this, enchantManager), this);
        Bukkit.getPluginManager().registerEvents(new InquisitiveListener(this, enchantManager), this);
        Bukkit.getPluginManager().registerEvents(new LipuchkaListener(this, enchantManager), this);
        Bukkit.getPluginManager().registerEvents(new DecapitationListener(this, enchantManager), this);
        this.indikatorListener = new IndikatorListener(this, enchantManager);
        Bukkit.getPluginManager().registerEvents(indikatorListener, this);
        Bukkit.getPluginManager().registerEvents(new SignalListener(this, enchantManager), this);
        Bukkit.getPluginManager().registerEvents(new IceshtormListener(this, enchantManager), this);
        
        // Listeners that need cleanup
        this.timberEnergyListener = new TimberEnergyListener(this, enchantManager);
        Bukkit.getPluginManager().registerEvents(timberEnergyListener, this);
        
        Bukkit.getPluginManager().registerEvents(new PoisonRuneListener(this), this);
        Bukkit.getPluginManager().registerEvents(new BulovaRuneListener(this), this);
        Bukkit.getPluginManager().registerEvents(new ExcavatorListener(this, excavatorManager, excavatorProcessor), this);
        
        this.waterWalkerListener = new WaterWalkerListener(this, enchantManager);
        Bukkit.getPluginManager().registerEvents(waterWalkerListener, this);
        
        this.lavaWalkerListener = new LavaWalkerListener(this, enchantManager);
        Bukkit.getPluginManager().registerEvents(lavaWalkerListener, this);
        
        // Palirol listener с cleanup
        this.palirolListener = new PalirolListener(this, enchantManager);
        Bukkit.getPluginManager().registerEvents(palirolListener, this);

        // Shield enchant (orbiting shields while holding RMB)
        this.shieldEnchantListener = new ShieldEnchantListener(this, enchantManager);
        Bukkit.getPluginManager().registerEvents(shieldEnchantListener, this);

        // Register commands and tab-completer
        PluginCommand cmd = getCommand("aquaenchant");
        if (cmd != null) {
            AquaEnchantCommand executor = new AquaEnchantCommand(this, enchantManager);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(new AquaEnchantTabCompleter(enchantManager));
        } else {
            getLogger().severe("Command 'aquaenchant' is not defined in plugin.yml!");
        }

        // /separation
        Bukkit.getPluginManager().registerEvents(new SeparationListener(separationManager), this);
        PluginCommand sepCmd = getCommand("separation");
        if (sepCmd != null) {
            sepCmd.setExecutor(new SeparationCommand(separationManager));
        } else {
            getLogger().severe("Command 'separation' is not defined in plugin.yml!");
        }

        // Если после краша/перезагрузки в separation.yml остались предметы — вернём их онлайн игрокам сразу.
        try {
            for (var p : Bukkit.getOnlinePlayers()) {
                separationManager.returnFromStorageIfPresent(p);
            }
        } catch (Throwable ignored) {}

		// Однократная нормализация лора предметов онлайн-игроков при старте/перезагрузке
		try {
			for (var p : Bukkit.getOnlinePlayers()) {
				if (p == null || !p.isOnline()) continue;
				for (var it : p.getInventory().getContents()) {
					if (it != null && !it.getType().isAir()) enchantManager.refreshLoreIfCustom(it);
				}
				for (var it : p.getInventory().getArmorContents()) {
					if (it != null && !it.getType().isAir()) enchantManager.refreshLoreIfCustom(it);
				}
				var offHand = p.getInventory().getItemInOffHand();
				if (offHand != null && !offHand.getType().isAir()) enchantManager.refreshLoreIfCustom(offHand);
			}
		} catch (Throwable ignored) {}

		// Периодическое обновление лора запускается ТОЛЬКО если в настройках включено lore_animation.
		// По умолчанию ВЫКЛЮЧЕНО, чтобы исключить постоянное дёргание оружия в руке из-за пакетов экипировки.
		Bukkit.getScheduler().runTaskTimer(this, () -> {
			if (!me.aquaenchants.util.LoreAnimationManager.isAnimationEnabled()) {
				return;
			}
			try {
				me.aquaenchants.util.LoreAnimationManager.incrementTick();
				for (var p : Bukkit.getOnlinePlayers()) {
					if (p == null || !p.isOnline()) continue;
					for (var it : p.getInventory().getContents()) {
						if (it != null && !it.getType().isAir()) enchantManager.refreshLoreIfCustom(it);
					}
					for (var it : p.getInventory().getArmorContents()) {
						if (it != null && !it.getType().isAir()) enchantManager.refreshLoreIfCustom(it);
					}
					var offHand = p.getInventory().getItemInOffHand();
					if (offHand != null && !offHand.getType().isAir()) enchantManager.refreshLoreIfCustom(offHand);

					if (p.getOpenInventory() != null && p.getOpenInventory().getTopInventory() != null) {
						var top = p.getOpenInventory().getTopInventory();
						if (top.getHolder() == null || !(top.getHolder() instanceof me.aquaenchants.gui.AdminChanceGUI.AdminHolder)) {
							for (var it : top.getContents()) {
								if (it != null && !it.getType().isAir()) enchantManager.refreshLoreIfCustom(it);
							}
						}
					}
				}
			} catch (Throwable ignored) {}
		}, 10L, 10L);

        getLogger().info("AquaEnchats enabled. Loaded " + enchantManager.getAll().size() + " custom enchantments.");
    }

    private void saveResourceSafe(String path) {
        File target = new File(getDataFolder(), path);
        if (!target.exists()) {
            target.getParentFile().mkdirs();
            saveResource(path, false);
        }
    }

    @Override
    public void onDisable() {
        if (customEnchantingTableGUIListener != null) {
            customEnchantingTableGUIListener.shutdown();
        }
        if (indikatorListener != null) {
            indikatorListener.shutdown();
        }
        if (separationManager != null) {
            separationManager.shutdownReturnAll();
        }
        // Cleanup listeners в правильном порядке
        if (palirolListener != null) {
            palirolListener.shutdown();
        }
        if (waterWalkerListener != null) {
            waterWalkerListener.shutdown();
        }
        if (lavaWalkerListener != null) {
            lavaWalkerListener.shutdown();
        }
        if (timberEnergyListener != null) {
            timberEnergyListener.shutdown();
        }
        if (shieldEnchantListener != null) {
            shieldEnchantListener.cleanup();
        }
        if (excavatorProcessor != null) {
            excavatorProcessor.cleanupOnDisable();
        }
        if (excavatorManager != null) {
            excavatorManager.saveAll();
            getLogger().info("Saved all excavator data");
        }
        getLogger().info("AquaEnchats disabled.");
    }

    public ExcavatorManager getExcavatorManager() {
        return excavatorManager;
    }

    public ExcavatorProcessor getExcavatorProcessor() {
        return excavatorProcessor;
    }

    public EnchantManager getEnchantManager() {
        return enchantManager;
    }

    public EffectManager getEffectManager() {
        return effectManager;
    }

    public CustomItemManager getCustomItemManager() {
        return customItemManager;
    }
    
    public void debug(String msg) {
        try {
            if (getConfig().getBoolean("settings.debug", false)) {
                getLogger().info(msg);
            }
        } catch (Throwable ignored) {}
    }
}