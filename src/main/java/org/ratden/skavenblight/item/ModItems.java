package org.ratden.skavenblight.item;

import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.item.custom.RatJuiceItem;

public class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Skavenblight.MODID);

    public static final DeferredItem<Item> RAW_WARPSTONE = ITEMS.register("raw_warpstone",
            () -> new Item(new Item.Properties()));

    public static final DeferredItem<Item> RAT_JUICE = ITEMS.register("rat_juice",
        () -> new RatJuiceItem(new Item.Properties().food(ModFoodProperties.RAT_JUICE)));


    public static void register(IEventBus eventBus) {
        ITEMS.register(eventBus);
    }
}
