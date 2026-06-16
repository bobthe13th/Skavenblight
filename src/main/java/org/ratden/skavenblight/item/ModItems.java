package org.ratden.skavenblight.item;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.item.custom.RatJuiceItem;

import java.util.List;

public class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Skavenblight.MODID);

    public static final DeferredItem<Item> RAW_WARPSTONE = ITEMS.register("raw_warpstone",
            () -> new Item(new Item.Properties()){
                @Override
                public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
                    tooltipComponents.add(Component.translatable("tooltip_info_item.raw_warpstone.description"));
                    super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
                }
            });

    public static final DeferredItem<Item> FUSED_WARPSTONE = ITEMS.register("fused_warpstone",
            () -> new Item(new Item.Properties()){
                @Override
                public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
                    tooltipComponents.add(Component.translatable("tooltip_info_item.fused_warpstone.description"));
                    super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
                }
            });

    public static final DeferredItem<Item> RAT_JUICE = ITEMS.register("rat_juice",
        () -> new RatJuiceItem(new Item.Properties().food(ModFoodProperties.RAT_JUICE)));




    public static void register(IEventBus eventBus) {
        ITEMS.register(eventBus);
    }
}
