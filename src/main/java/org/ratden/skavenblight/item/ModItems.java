package org.ratden.skavenblight.item;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ArmorItem;
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

    public static final DeferredItem<ArmorItem> WARPSTONE_HELMET = ITEMS.register("warpstone_helmet",
            () -> new ArmorItem(ModArmorMaterials.WARPSTONE_ARMOR_MATERIAL,ArmorItem.Type.HELMET,
                    new Item.Properties().durability(ArmorItem.Type.HELMET.getDurability(19))));
    public static final DeferredItem<ArmorItem> WARPSTONE_CHESTPLATE = ITEMS.register("warpstone_chestplate",
            () -> new ArmorItem(ModArmorMaterials.WARPSTONE_ARMOR_MATERIAL,ArmorItem.Type.CHESTPLATE,
                    new Item.Properties().durability(ArmorItem.Type.CHESTPLATE.getDurability(19))));
    public static final DeferredItem<ArmorItem> WARPSTONE_LEGGINGS = ITEMS.register("warpstone_leggings",
            () -> new ArmorItem(ModArmorMaterials.WARPSTONE_ARMOR_MATERIAL,ArmorItem.Type.LEGGINGS,
                    new Item.Properties().durability(ArmorItem.Type.LEGGINGS.getDurability(19))));
    public static final DeferredItem<ArmorItem> WARPSTONE_BOOTS = ITEMS.register("warpstone_boots",
            () -> new ArmorItem(ModArmorMaterials.WARPSTONE_ARMOR_MATERIAL,ArmorItem.Type.BOOTS,
                    new Item.Properties().durability(ArmorItem.Type.BOOTS.getDurability(19))));
                    
    public static void register(IEventBus eventBus) {
        ITEMS.register(eventBus);
    }
}
