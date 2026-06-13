package org.ratden.skavenblight.item;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.SpawnEggItem;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.ratden.skavenblight.ModEntities;
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.item.custom.RatJuiceItem;

public class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Skavenblight.MODID);

    public static final DeferredItem<Item> RAW_WARPSTONE = ITEMS.register("raw_warpstone",
        () -> new Item(new Item.Properties()));

    public static final DeferredItem<Item> RAT_JUICE = ITEMS.register("rat_juice",
        () -> new RatJuiceItem(new Item.Properties().food(ModFoodProperties.RAT_JUICE)));

    public static final DeferredHolder<Item, SpawnEggItem> RAT_WOLF_SPAWN_EGG = ITEMS.register("rat_wolf_spawn_egg",
        () -> new SpawnEggItem(ModEntities.RAT_WOLF.get(), 0x4A3728, 0x8B000, new Item.Properties()));

    public static void register(IEventBus eventBus) {
        ITEMS.register(eventBus);
    }
}
