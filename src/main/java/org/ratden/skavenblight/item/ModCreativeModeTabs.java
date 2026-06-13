package org.ratden.skavenblight.item;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.block.ModBlocks;

import java.util.function.Supplier;

public class ModCreativeModeTabs {
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TAB =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Skavenblight.MODID);

    public static final Supplier<CreativeModeTab> SKAVENBLIGHT_TAB = CREATIVE_MODE_TAB.register("skavenblight_tab",
            () -> CreativeModeTab.builder().icon(() -> new ItemStack(ModItems.RAT_JUICE.get()))
                    .title(Component.translatable("creativetab.skavenblight"))
                    .displayItems(((itemDisplayParameters, output) -> {
                        output.accept(ModBlocks.WARPSTONE_ORE);
                        output.accept(ModBlocks.WARPSTONE_ORE_DEEPSLATE);
                        output.accept(ModBlocks.BLOCK_OF_WARPSTONE.get());

                        output.accept(ModBlocks.WARPSTONE_STAIRS);
                        output.accept(ModBlocks.WARPSTONE_SLAB);
                        output.accept(ModBlocks.WARPSTONE_PRESSURE_PLATE);
                        output.accept(ModBlocks.WARPSTONE_BUTTON);
                        output.accept(ModBlocks.WARPSTONE_FENCE);
                        output.accept(ModBlocks.WARPSTONE_FENCE_GATE);
                        output.accept(ModBlocks.WARPSTONE_WALL);
                        output.accept(ModBlocks.WARPSTONE_DOOR);
                        output.accept(ModBlocks.WARPSTONE_TRAPDOOR);


                        output.accept(ModItems.RAW_WARPSTONE);
                        output.accept(ModItems.FUSED_WARPSTONE);
                        output.accept(ModItems.RAT_JUICE);

                        output.accept(ModBlocks.WARPSTONE_NEXUS);
                        output.accept(ModBlocks.ACTIVE_WARPSTONE_NEXUS);

                    })).build());


    public static void register(IEventBus eventBus) {
        CREATIVE_MODE_TAB.register(eventBus);
    }
}
