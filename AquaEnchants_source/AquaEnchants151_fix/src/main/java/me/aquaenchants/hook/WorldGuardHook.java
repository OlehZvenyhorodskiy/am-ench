package me.aquaenchants.hook;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Хук для WorldGuard.
 *
 * Важно:
 *  - Плагин компилируется с библиотекой WorldGuard (лежит в libs/),
 *    но при отсутствии WorldGuard на сервере все методы просто
 *    возвращают true и не ломают работу.
 *  - Проверка строится на RegionQuery#testBuild, как рекомендует WG 7.x.
 */
public class WorldGuardHook {

    /**
     * Можно ли игроку строить/ломать по указанному Location.
     * Если WorldGuard не установлен или что‑то идёт не так — возвращает true.
     */
    public static boolean canBuild(Player player, Location location) {
        if (player == null || location == null) {
            return true;
        }

        try {
            Plugin wg = Bukkit.getPluginManager().getPlugin("WorldGuard");
            if (wg == null || !wg.isEnabled()) {
                // WorldGuard не установлен или выключен — считаем, что запретов нет.
                return true;
            }

            // WorldGuard 7 API:
            // RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
            // RegionQuery query = container.createQuery();
            // return query.testBuild(BukkitAdapter.adapt(location), localPlayer);
            com.sk89q.worldguard.protection.regions.RegionContainer container =
                    com.sk89q.worldguard.WorldGuard.getInstance()
                            .getPlatform()
                            .getRegionContainer();

            com.sk89q.worldguard.protection.regions.RegionQuery query = container.createQuery();

            com.sk89q.worldedit.util.Location weLoc =
                    com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(location);

            com.sk89q.worldguard.LocalPlayer localPlayer =
                    com.sk89q.worldguard.bukkit.WorldGuardPlugin.inst().wrapPlayer(player);

            return query.testBuild(weLoc, localPlayer);
        } catch (NoClassDefFoundError e) {
            // API WorldGuard недоступен (плагин не установлен) — не мешаем другим плагинам.
            return true;
        } catch (Throwable ignored) {
            // Любая другая ошибка — по умолчанию разрешаем, чтобы не ломать механику.
            return true;
        }
    }

    /**
     * Разрешено ли использовать эффект переплавки / копания по блоку.
     * Сейчас это просто обёртка над canBuild().
     */
    public static boolean canUseSmelt(Player player, Block block) {
        if (block == null) {
            return true;
        }
        return canBuild(player, block.getLocation());
    }

    /**
     * Разрешено ли ломать блоки эффекта Timber (и другие массовые эффекты).
     * Тоже обёртка над canBuild(), добавлена для читабельности.
     */
    public static boolean canUseTimber(Player player, Block block) {
        if (block == null) {
            return true;
        }
        return canBuild(player, block.getLocation());
    }
}
