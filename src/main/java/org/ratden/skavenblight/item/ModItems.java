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
import org.ratden.skavenblight.item.custom.CleansingWardItem;
import org.ratden.skavenblight.item.custom.DebugFlowFieldReaderItem;
import org.ratden.skavenblight.item.custom.RatJuiceItem;
import org.ratden.skavenblight.item.custom.SkavenblightBookItem;
import org.ratden.skavenblight.item.custom.TomeOfCorruptionItem;

import java.util.List;

/**
 * Registers the Items added by Skavenblight.
 *
 * Matching BlockItems for registered blocks are added to this same item
 * registry by ModBlocks.
 */
public final class ModItems {

    private static final int WARPSTONE_ARMOUR_DURABILITY_MULTIPLIER = 19;

    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(
                    Skavenblight.MODID
            );

    public static final DeferredItem<Item> RAW_WARPSTONE =
            registerTooltipItem(
                    "raw_warpstone",
                    "tooltip_info_item.raw_warpstone.description"
            );

    public static final DeferredItem<Item> FUSED_WARPSTONE =
            registerTooltipItem(
                    "fused_warpstone",
                    "tooltip_info_item.fused_warpstone.description"
            );

    public static final DeferredItem<RatJuiceItem> RAT_JUICE =
            ITEMS.register(
                    "rat_juice",
                    () -> new RatJuiceItem(
                            new Item.Properties()
                                    .food(ModFoodProperties.RAT_JUICE)
                    )
            );

    public static final DeferredItem<ArmorItem> WARPSTONE_HELMET =
            ITEMS.register(
                    "warpstone_helmet",
                    () -> new ArmorItem(
                            ModArmorMaterials.WARPSTONE_ARMOR_MATERIAL,
                            ArmorItem.Type.HELMET,
                            new Item.Properties().durability(
                                    ArmorItem.Type.HELMET.getDurability(
                                            WARPSTONE_ARMOUR_DURABILITY_MULTIPLIER
                                    )
                            )
                    )
            );

    public static final DeferredItem<ArmorItem> WARPSTONE_CHESTPLATE =
            ITEMS.register(
                    "warpstone_chestplate",
                    () -> new ArmorItem(
                            ModArmorMaterials.WARPSTONE_ARMOR_MATERIAL,
                            ArmorItem.Type.CHESTPLATE,
                            new Item.Properties().durability(
                                    ArmorItem.Type.CHESTPLATE.getDurability(
                                            WARPSTONE_ARMOUR_DURABILITY_MULTIPLIER
                                    )
                            )
                    )
            );

    public static final DeferredItem<ArmorItem> WARPSTONE_LEGGINGS =
            ITEMS.register(
                    "warpstone_leggings",
                    () -> new ArmorItem(
                            ModArmorMaterials.WARPSTONE_ARMOR_MATERIAL,
                            ArmorItem.Type.LEGGINGS,
                            new Item.Properties().durability(
                                    ArmorItem.Type.LEGGINGS.getDurability(
                                            WARPSTONE_ARMOUR_DURABILITY_MULTIPLIER
                                    )
                            )
                    )
            );

    public static final DeferredItem<ArmorItem> WARPSTONE_BOOTS =
            ITEMS.register(
                    "warpstone_boots",
                    () -> new ArmorItem(
                            ModArmorMaterials.WARPSTONE_ARMOR_MATERIAL,
                            ArmorItem.Type.BOOTS,
                            new Item.Properties().durability(
                                    ArmorItem.Type.BOOTS.getDurability(
                                            WARPSTONE_ARMOUR_DURABILITY_MULTIPLIER
                                    )
                            )
                    )
            );

    public static final DeferredItem<DebugFlowFieldReaderItem>
            DEBUG_FLOW_FIELD_READER =
            ITEMS.register(
                    "debug_flow_field_reader",
                    () -> new DebugFlowFieldReaderItem(
                            new Item.Properties()
                                    .stacksTo(1)
                    )
            );

    /**
     * Registers a normal item with one translated tooltip line.
     */
    private static DeferredItem<Item> registerTooltipItem(
            String name,
            String tooltipTranslationKey
    ) {
        return ITEMS.register(
                name,
                () -> new Item(new Item.Properties()) {

                    @Override
                    public void appendHoverText(
                            ItemStack stack,
                            TooltipContext context,
                            List<Component> tooltipComponents,
                            TooltipFlag tooltipFlag
                    ) {
                        tooltipComponents.add(
                                Component.translatable(
                                        tooltipTranslationKey
                                )
                        );

                        super.appendHoverText(
                                stack,
                                context,
                                tooltipComponents,
                                tooltipFlag
                        );
                    }
                }
        );
    }

    public static final DeferredItem<Item> SKAVENBLIGHT_BOOK =
            ITEMS.register(
                    "skavenblight_book",
                    () -> new SkavenblightBookItem(
                            new Item.Properties()
                                    .stacksTo(1)
                    )
            );

    public static final DeferredItem<Item> TOME_OF_CORRUPTION = ITEMS.register("tome_of_corruption",
            () -> new TomeOfCorruptionItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<Item> CLEANSING_WARD = ITEMS.register("cleansing_ward",
            () -> new CleansingWardItem(new Item.Properties()));

    public static void register(IEventBus eventBus) {
        ITEMS.register(eventBus);
    }

    private ModItems() {
    }
}