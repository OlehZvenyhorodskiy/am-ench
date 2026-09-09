package me.aquaenchants.bootstrap;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.TypedKey;
import io.papermc.paper.registry.data.EnchantmentRegistryEntry;
import io.papermc.paper.registry.event.RegistryComposeEvent;
import io.papermc.paper.registry.event.RegistryEvents;
import io.papermc.paper.registry.set.RegistrySet;
import me.aquaenchants.util.EnchantDefinitions;
import me.aquaenchants.util.EnchantDefinitions.Def;
import net.kyori.adventure.text.Component;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Paper bootstrap registration for custom table-display enchantments.
 * Registers real Enchantments into the server registry BEFORE it is frozen,
 * so the vanilla client can render them in the enchanting table UI.
 *
 * Paper 1.21.8 (build 60) compatible.
 */
public final class AquaEnchatsBootstrap implements PluginBootstrap {

    @Override
    public void bootstrap(final BootstrapContext context) {
        context.getLifecycleManager().registerEventHandler(
                RegistryEvents.ENCHANTMENT.compose(),
                (RegistryComposeEvent<Enchantment, EnchantmentRegistryEntry.Builder> event) -> {
                    // Build supported items set once
                    final Collection<ItemType> allItems = new ArrayList<>();
                    try {
                        for (ItemType it : Registry.ITEM) {
                            allItems.add(it);
                        }
                    } catch (Throwable t) {
                        // If Registry.ITEM isn't iterable for some reason, we'll still register with empty sets;
                        // the enchant may not show for items, but at least boot won't crash.
                    }

                    final var allItemKeys = RegistrySet.keySetFromValues(RegistryKey.ITEM, allItems);
                    final var emptyExclusive = RegistrySet.keySetFromValues(RegistryKey.ENCHANTMENT, List.of());

                    final List<Def> defs = EnchantDefinitions.loadFromResource("enachants.yml");
                    for (Def def : defs) {
                        final String id = def.id();
                        final String keyStr = "aquaenchants:" + id.toLowerCase();

                        final TypedKey<Enchantment> typedKey = TypedKey.create(RegistryKey.ENCHANTMENT, keyStr);

                        event.registry().register(typedKey, (EnchantmentRegistryEntry.Builder b) -> {
                            b.description(Component.text(def.displayNamePlain()));
                            b.weight(Math.max(1, def.weight()));
                            b.maxLevel(Math.max(1, def.maxLevel()));

                            // Reasonable enchanting cost curve; vanilla will still decide what to offer.
                            b.minimumCost(EnchantmentRegistryEntry.EnchantmentCost.of(1, 8));
                            b.maximumCost(EnchantmentRegistryEntry.EnchantmentCost.of(10, 12));
                            b.anvilCost(1);

                            b.supportedItems(allItemKeys);
                            b.primaryItems(allItemKeys);
                            b.activeSlots(EquipmentSlotGroup.ANY);
                            b.exclusiveWith(emptyExclusive);
                        });
                    }
                }
        );
    }
}
