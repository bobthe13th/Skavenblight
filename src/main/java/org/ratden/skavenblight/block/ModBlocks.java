package org.ratden.skavenblight.block;

import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DropExperienceBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.item.ModItems;

import java.util.function.Supplier;

public class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(Skavenblight.MODID);

    public static final DeferredBlock<Block> WARPSTONE_ORE = registerBlock("warpstone_ore",
            () -> new DropExperienceBlock(UniformInt.of(2, 5),
                    BlockBehaviour.Properties.of()
                        .strength(4f)
                        .requiresCorrectToolForDrops()
                        .sound(SoundType.AMETHYST)));

    public static final DeferredBlock<Block> WARPSTONE_ORE_DEEPSLATE = registerBlock("warpstone_ore_deepslate",
            () -> new DropExperienceBlock(UniformInt.of(2, 5),
                    BlockBehaviour.Properties.of()
                        .strength(4f)
                        .requiresCorrectToolForDrops()
                        .sound(SoundType.AMETHYST)));

    private static <T extends Block> DeferredBlock<T> registerBlock(String name, Supplier<T> block) {
        DeferredBlock<T> toReturn = BLOCKS.register(name, block);
        registerBlockItem(name, toReturn);
        return toReturn;
    }

    private static <T extends Block> void registerBlockItem(String name, DeferredBlock<T> block) {
        ModItems.ITEMS.register(name, () -> new BlockItem(block.get(), new Item.Properties()));
    }


    public static void register(IEventBus eventBus) {

        BLOCKS.register(eventBus);
    }



}
