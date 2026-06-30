package org.ratden.skavenblight.datagen;

import net.minecraft.core.Direction;
import net.minecraft.data.DataGenerator;
import net.minecraft.data.PackOutput;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.model.generators.BlockStateProvider;
import net.neoforged.neoforge.client.model.generators.ConfiguredModel;
import net.neoforged.neoforge.client.model.generators.ModelFile;
import net.neoforged.neoforge.client.model.generators.MultiPartBlockStateBuilder;
import net.neoforged.neoforge.common.data.ExistingFileHelper;
import net.neoforged.neoforge.registries.DeferredBlock;
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.block.ModBlocks;
import org.ratden.skavenblight.block.custom.SpawnTunnelSmall;
import net.minecraft.client.renderer.item.ItemProperties;
import org.ratden.skavenblight.block.custom.WarpFluxConduitBlock;

import static org.ratden.skavenblight.block.ModBlocks.SPAWN_TUNNEL_SMALL;

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

        customSpawnerBlock(ModBlocks.SPAWN_TUNNEL_SMALL);

        makeConduit(ModBlocks.WARP_FLUX_CONDUIT, "warp_flux_conduit");

        //blockWithItem(ModBlocks.BASIC_WARP_FLUX_STORAGE);

    }

    public void makeConduit(DeferredBlock<?> block, String baseName) {
        MultiPartBlockStateBuilder builder = getMultipartBuilder(block.get());

        // 1. Define the models we expect to exist in assets/skavenblight/models/block/
        // (You still need to create these geometry files in Blockbench!)
        ModelFile coreOff = models().getExistingFile(modLoc("block/" + baseName + "_core_off"));
        ModelFile corePale = models().getExistingFile(modLoc("block/" + baseName + "_core_pale"));
        ModelFile coreMedium = models().getExistingFile(modLoc("block/" + baseName + "_core_medium"));
        ModelFile coreStrong = models().getExistingFile(modLoc("block/" + baseName + "_core_strong"));
        ModelFile arm = models().getExistingFile(modLoc("block/" + baseName + "_arm"));

        // 2. Generate the Core states based on glow intensity
        builder.part().modelFile(coreOff).addModel().condition(WarpFluxConduitBlock.GLOW_INTENSITY, 0).end();
        builder.part().modelFile(corePale).addModel().condition(WarpFluxConduitBlock.GLOW_INTENSITY, 1).end();
        builder.part().modelFile(coreMedium).addModel().condition(WarpFluxConduitBlock.GLOW_INTENSITY, 2).end();
        builder.part().modelFile(coreStrong).addModel().condition(WarpFluxConduitBlock.GLOW_INTENSITY, 3).end();

        // 3. Generate the connecting Arms based on boolean direction states
        builder.part().modelFile(arm).addModel().condition(BlockStateProperties.NORTH, true).end();
        builder.part().modelFile(arm).rotationY(90).addModel().condition(BlockStateProperties.EAST, true).end();
        builder.part().modelFile(arm).rotationY(180).addModel().condition(BlockStateProperties.SOUTH, true).end();
        builder.part().modelFile(arm).rotationY(270).addModel().condition(BlockStateProperties.WEST, true).end();
        builder.part().modelFile(arm).rotationX(270).addModel().condition(BlockStateProperties.UP, true).end();
        builder.part().modelFile(arm).rotationX(90).addModel().condition(BlockStateProperties.DOWN, true).end();

        // 4. Generate the Item Model (usually just referencing a custom generated item texture or the core off model)
        simpleBlockItem(block.get(), coreOff);
    }

    private void customSpawnerBlock(DeferredBlock<?> deferredBlock) {
        // 1. Get references to your active and inactive models.
        // If your datagen also generates the block models, you'd define them here instead of using getExistingFile.
        ModelFile activeModel = models().getExistingFile(modLoc("block/spawn_tunnel_small_active"));
        ModelFile inactiveModel = models().getExistingFile(modLoc("block/spawn_tunnel_small_inactive"));

        // 2. Build variants for every possible combination of state properties
        getVariantBuilder(deferredBlock.get()).forAllStates(state -> {
            Direction dir = state.getValue(SpawnTunnelSmall.FACING);
            boolean isActive = state.getValue(SpawnTunnelSmall.ACTIVE);

            // Determine which model to use
            ModelFile currentModel = isActive ? activeModel : inactiveModel;

            // Determine rotation based on facing direction
            int yRot = switch (dir) {
                case EAST -> 90;
                case SOUTH -> 180;
                case WEST -> 270;
                default -> 0; // NORTH
            };

            // Build the specific variant
            return ConfiguredModel.builder()
                    .modelFile(currentModel)
                    .rotationY(yRot)
                    .build();


        });

        //Generate the Dynamic Item Model - NOT WORKING LOL, only outputs inactive
        itemModels().getBuilder(deferredBlock.getId().getPath())
                .parent(inactiveModel) // Default to inactive
                .override()
                // If the "active" predicate returns 1.0, switch to the active model
                .predicate(modLoc("is_active"), 1.0f)
                .model(activeModel)
                .end();
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
