package org.ratden.skavenblight.item;

import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.ratden.skavenblight.Skavenblight;

public class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Skavenblight.MODID);

    public static final DeferredItem<Item> RAW_WARPSTONE = ITEMS.register("raw_warpstone",
            () -> new Item(new Item.Properties()));

    public static final DeferredItem<Item> WARPSTONE_ORE = ITEMS.register("warpstone_ore",
            () -> new Item(new Item.Properties()));

    public static final DeferredItem<Item> WARPSTONE_ORE_DEEPSLATE = ITEMS.register("warpstone_ore_deepslate",
            () -> new Item(new Item.Properties()));

    public static void register(IEventBus eventBus) {
        ITEMS.register(eventBus);
    }
}
