package org.ratden.skavenblight.datagen;

import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.data.BlockTagsProvider;
import net.neoforged.neoforge.common.data.ExistingFileHelper;
import org.jetbrains.annotations.Nullable;
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.block.ModBlocks;
import org.ratden.skavenblight.magic.Wind;
import org.ratden.skavenblight.magic.wind.ModWindBlockTags;

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

        tag(ModWindBlockTags.windSource(Wind.HYSH))
                .add(Blocks.GLOWSTONE, Blocks.SEA_LANTERN, Blocks.BEACON, Blocks.END_ROD);
        tag(ModWindBlockTags.windSource(Wind.AZYR))
                .add(Blocks.LAPIS_BLOCK, Blocks.AMETHYST_BLOCK, Blocks.CONDUIT, Blocks.LODESTONE);
        tag(ModWindBlockTags.windSource(Wind.CHAMON))
                .add(Blocks.IRON_BLOCK, Blocks.GOLD_BLOCK, Blocks.COPPER_BLOCK, Blocks.NETHERITE_BLOCK,
                        Blocks.EXPOSED_COPPER, Blocks.WEATHERED_COPPER, Blocks.OXIDIZED_COPPER,
                        Blocks.WAXED_COPPER_BLOCK, Blocks.WAXED_EXPOSED_COPPER,
                        Blocks.WAXED_WEATHERED_COPPER, Blocks.WAXED_OXIDIZED_COPPER);
        tag(ModWindBlockTags.windSource(Wind.GHYRAN))
                .add(Blocks.MOSS_BLOCK, Blocks.FLOWERING_AZALEA, Blocks.BEEHIVE, Blocks.COMPOSTER);
        tag(ModWindBlockTags.windSource(Wind.AQSHY))
                .add(Blocks.MAGMA_BLOCK, Blocks.NETHERRACK, Blocks.CAMPFIRE, Blocks.BLAST_FURNACE);
        tag(ModWindBlockTags.windSource(Wind.GHUR))
                .add(Blocks.HAY_BLOCK, Blocks.COBWEB, Blocks.MUD, Blocks.ROOTED_DIRT);
        tag(ModWindBlockTags.windSource(Wind.ULGU))
                .add(Blocks.OBSIDIAN, Blocks.SOUL_SAND, Blocks.BLACK_CONCRETE, Blocks.ENDER_CHEST);
        tag(ModWindBlockTags.windSource(Wind.SHYISH))
                .add(Blocks.SOUL_SOIL, Blocks.WITHER_ROSE, Blocks.CRYING_OBSIDIAN, Blocks.SKELETON_SKULL);

    }
}
