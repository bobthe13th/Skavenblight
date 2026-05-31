package org.ratden.skavenblight.block;

import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.ratden.skavenblight.Skavenblight;

public class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(Skavenblight.MODID);

    private static <T extends Block> void registerBlock(String name, DeferredBlock<T> block) {

    }


    public static void register(IEventBus eventBus) {
        BLOCKS.register(eventBus);
    }



}
