package org.ratden.skavenblight.datagen;

import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.tags.BlockTags;
import net.neoforged.neoforge.common.data.BlockTagsProvider;
import net.neoforged.neoforge.common.data.ExistingFileHelper;
import org.jetbrains.annotations.Nullable;
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.block.ModBlocks;

import java.util.concurrent.CompletableFuture;

public class ModBlockTagProvider extends BlockTagsProvider {
    public ModBlockTagProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> lookupProvider, @Nullable ExistingFileHelper existingFileHelper) {
        super(output, lookupProvider, Skavenblight.MODID, existingFileHelper);
    }

    @Override
    protected void addTags(HolderLookup.Provider provider) {
        tag(BlockTags.MINEABLE_WITH_PICKAXE)
                .add(ModBlocks.BLOCK_OF_WARPSTONE.get())
                .add(ModBlocks.WARPSTONE_ORE.get())
                .add(ModBlocks.WARPSTONE_ORE_DEEPSLATE.get())
                .add(ModBlocks.WARP_FLUX_CONDUIT.get())
                .add(ModBlocks.PISTON_SPIKE_TRAP.get());

        tag(BlockTags.NEEDS_DIAMOND_TOOL)
                .add(ModBlocks.WARPSTONE_ORE.get())
                .add(ModBlocks.WARPSTONE_ORE_DEEPSLATE.get());

        tag(BlockTags.FENCES).add(ModBlocks.WARPSTONE_FENCE.get());
        tag(BlockTags.FENCE_GATES).add(ModBlocks.WARPSTONE_FENCE_GATE.get());
        tag(BlockTags.WALLS).add(ModBlocks.WARPSTONE_WALL.get());

    }
}
