package org.ratden.skavenblight.datagen;

import net.minecraft.data.DataGenerator;
import net.minecraft.data.PackOutput;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.model.generators.BlockStateProvider;
import net.neoforged.neoforge.client.model.generators.ModelFile;
import net.neoforged.neoforge.common.data.ExistingFileHelper;
import net.neoforged.neoforge.registries.DeferredBlock;
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.block.ModBlocks;

public class ModBlockStateProvider extends BlockStateProvider {
    public ModBlockStateProvider(PackOutput output, ExistingFileHelper exFileHelper) {
        super(output, Skavenblight.MODID, exFileHelper);
    }

    @Override
    protected void registerStatesAndModels() {
        blockWithItem(ModBlocks.BLOCK_OF_WARPSTONE);
        blockWithItem(ModBlocks.WARPSTONE_ORE);
        blockWithItem(ModBlocks.WARPSTONE_ORE_DEEPSLATE);

        stairsBlock(ModBlocks.WARPSTONE_STAIRS.get(), blockTexture(ModBlocks.BLOCK_OF_WARPSTONE.get()));
        blockItem(ModBlocks.WARPSTONE_STAIRS);
        slabBlock(ModBlocks.WARPSTONE_SLAB.get(), blockTexture(ModBlocks.BLOCK_OF_WARPSTONE.get()), blockTexture(ModBlocks.BLOCK_OF_WARPSTONE.get()));
        blockItem(ModBlocks.WARPSTONE_SLAB);

        buttonBlock(ModBlocks.WARPSTONE_BUTTON.get(), blockTexture(ModBlocks.BLOCK_OF_WARPSTONE.get()));

        pressurePlateBlock(ModBlocks.WARPSTONE_PRESSURE_PLATE.get(), blockTexture(ModBlocks.BLOCK_OF_WARPSTONE.get()));
        blockItem(ModBlocks.WARPSTONE_PRESSURE_PLATE);

        fenceBlock(ModBlocks.WARPSTONE_FENCE.get(), blockTexture(ModBlocks.BLOCK_OF_WARPSTONE.get()));

        fenceGateBlock(ModBlocks.WARPSTONE_FENCE_GATE.get(), blockTexture(ModBlocks.BLOCK_OF_WARPSTONE.get()));
        blockItem(ModBlocks.WARPSTONE_FENCE_GATE);

        wallBlock(ModBlocks.WARPSTONE_WALL.get(), blockTexture(ModBlocks.BLOCK_OF_WARPSTONE.get()));

        doorBlockWithRenderType(ModBlocks.WARPSTONE_DOOR.get(), modLoc("block/warpstone_door_bottom"), modLoc("block/warpstone_door_top"), "cutout");

        trapdoorBlockWithRenderType(ModBlocks.WARPSTONE_TRAPDOOR.get(), modLoc("block/warpstone_trapdoor"),true, "cutout");
        blockItem(ModBlocks.WARPSTONE_TRAPDOOR, "_bottom");



    }

    private void blockWithItem(DeferredBlock<?> deferredBlock){
        simpleBlockWithItem(deferredBlock.get(), cubeAll(deferredBlock.get()));
    }

    private void blockItem(DeferredBlock<?> deferredBlock) {
        simpleBlockItem(deferredBlock.get(), new ModelFile.UncheckedModelFile("skavenblight:block/" + deferredBlock.getId().getPath()));
    }

    private void blockItem(DeferredBlock<?> deferredBlock, String appendix) {
        simpleBlockItem(deferredBlock.get(), new ModelFile.UncheckedModelFile("skavenblight:block/" + deferredBlock.getId().getPath() + appendix));
    }
}
