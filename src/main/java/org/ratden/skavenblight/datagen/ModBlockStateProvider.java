package org.ratden.skavenblight.datagen;

import net.minecraft.core.Direction;
import net.minecraft.data.DataGenerator;
import net.minecraft.data.PackOutput;
import net.minecraft.world.level.block.Block;
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

        // 1. Generate an empty model for the base coil so Geckolib can draw over it
        simpleBlock(ModBlocks.WARP_LIGHTNING_COIL.get(),
                models().getBuilder(ModBlocks.WARP_LIGHTNING_COIL.getId().getPath()));

        // 2. Generate an empty model for the dummy block (so the top parts are invisible)
        simpleBlock(ModBlocks.WARP_LIGHTNING_COIL_DUMMY.get(),
                models().getBuilder(ModBlocks.WARP_LIGHTNING_COIL_DUMMY.getId().getPath()));

        //Basic warp flux storage datagen
        // 1. Tell datagen to grab the custom model you manually placed in the resources folder
        ModelFile basicWarpFluxStorageModel = models().getExistingFile(modLoc("block/basic_warp_flux_storage"));
        // 2. Generate the block state using that existing model
        simpleBlock(ModBlocks.BASIC_WARP_FLUX_STORAGE.get(), basicWarpFluxStorageModel);
        // 3. Generate the item model pointing to that same existing block model
        simpleBlockItem(ModBlocks.BASIC_WARP_FLUX_STORAGE.get(), basicWarpFluxStorageModel);

        standardMachineBlock(ModBlocks.WARP_FLUX_FURNACE,
                "warp_flux_furnace_side",
                "warp_flux_furnace_top",
                "warp_flux_furnace_front",
                "warp_flux_furnace_front_on");

        standardMachineBlock(ModBlocks.RESEARCH_TABLE,
                "research_table_side",
                "research_table_top",
                "research_table_front",
                "research_table_front_on");

        // Create an empty "dummy" model for the Spike Trap since GeckoLib renders the real one.
        // We assign a texture to it purely so Minecraft knows what particles to spawn when you break it!
        ModelFile trapModel = models().getBuilder("piston_spike_trap")
                .texture("particle", modLoc("block/spike_piston")); // Use the name of your trap's texture file!

        // Generate the blockstate allowing it to face all 6 directions
        directionalBlock(ModBlocks.PISTON_SPIKE_TRAP.get(), trapModel);
    }

    public void makeConduit(DeferredBlock<?> block, String baseName) {
        MultiPartBlockStateBuilder builder = getMultipartBuilder(block.get());

        ModelFile[] cores = new ModelFile[] {
                models().getExistingFile(modLoc("block/" + baseName + "_core_off")),
                models().getExistingFile(modLoc("block/" + baseName + "_core_pale")),
                models().getExistingFile(modLoc("block/" + baseName + "_core_medium")),
                models().getExistingFile(modLoc("block/" + baseName + "_core_strong"))
        };

        ModelFile[] arms = new ModelFile[] {
                models().getExistingFile(modLoc("block/" + baseName + "_arm_off")),
                models().getExistingFile(modLoc("block/" + baseName + "_arm_pale")),
                models().getExistingFile(modLoc("block/" + baseName + "_arm_medium")),
                models().getExistingFile(modLoc("block/" + baseName + "_arm_strong"))
        };

        // 1. INACTIVE ARMS: Connected, but no power flowing. Always use the 'Off' arm!
        builder.part().modelFile(arms[0]).addModel().condition(BlockStateProperties.NORTH, true).condition(WarpFluxConduitBlock.NORTH_ACTIVE, false).end();
        builder.part().modelFile(arms[0]).rotationY(90).addModel().condition(BlockStateProperties.EAST, true).condition(WarpFluxConduitBlock.EAST_ACTIVE, false).end();
        builder.part().modelFile(arms[0]).rotationY(180).addModel().condition(BlockStateProperties.SOUTH, true).condition(WarpFluxConduitBlock.SOUTH_ACTIVE, false).end();
        builder.part().modelFile(arms[0]).rotationY(270).addModel().condition(BlockStateProperties.WEST, true).condition(WarpFluxConduitBlock.WEST_ACTIVE, false).end();
        builder.part().modelFile(arms[0]).rotationX(270).addModel().condition(BlockStateProperties.UP, true).condition(WarpFluxConduitBlock.UP_ACTIVE, false).end();
        builder.part().modelFile(arms[0]).rotationX(90).addModel().condition(BlockStateProperties.DOWN, true).condition(WarpFluxConduitBlock.DOWN_ACTIVE, false).end();

        // 2. ACTIVE ARMS AND CORES: Tie them to the glow intensity!
        for (int i = 0; i < 4; i++) {
            builder.part().modelFile(cores[i]).addModel()
                    .condition(WarpFluxConduitBlock.GLOW_INTENSITY, i).end();

            builder.part().modelFile(arms[i]).addModel()
                    .condition(BlockStateProperties.NORTH, true).condition(WarpFluxConduitBlock.NORTH_ACTIVE, true)
                    .condition(WarpFluxConduitBlock.GLOW_INTENSITY, i).end();

            builder.part().modelFile(arms[i]).rotationY(90).addModel()
                    .condition(BlockStateProperties.EAST, true).condition(WarpFluxConduitBlock.EAST_ACTIVE, true)
                    .condition(WarpFluxConduitBlock.GLOW_INTENSITY, i).end();

            builder.part().modelFile(arms[i]).rotationY(180).addModel()
                    .condition(BlockStateProperties.SOUTH, true).condition(WarpFluxConduitBlock.SOUTH_ACTIVE, true)
                    .condition(WarpFluxConduitBlock.GLOW_INTENSITY, i).end();

            builder.part().modelFile(arms[i]).rotationY(270).addModel()
                    .condition(BlockStateProperties.WEST, true).condition(WarpFluxConduitBlock.WEST_ACTIVE, true)
                    .condition(WarpFluxConduitBlock.GLOW_INTENSITY, i).end();

            builder.part().modelFile(arms[i]).rotationX(270).addModel()
                    .condition(BlockStateProperties.UP, true).condition(WarpFluxConduitBlock.UP_ACTIVE, true)
                    .condition(WarpFluxConduitBlock.GLOW_INTENSITY, i).end();

            builder.part().modelFile(arms[i]).rotationX(90).addModel()
                    .condition(BlockStateProperties.DOWN, true).condition(WarpFluxConduitBlock.DOWN_ACTIVE, true)
                    .condition(WarpFluxConduitBlock.GLOW_INTENSITY, i).end();
        }

        simpleBlockItem(block.get(), cores[0]);
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

    /**
     * Creates standard blockstates and models for a horizontal-directional machine that can be turned on and off.
     * Future machines can call this method with their respective textures to completely generate all models instantly.
     */
    private void standardMachineBlock(DeferredBlock<Block> block, String sideTex, String topTex, String frontInactiveTex, String frontActiveTex) {
        // 1. Create the 3D model for the INACTIVE state
        ModelFile inactiveModel = models().cube("block/" + block.getId().getPath(),
                modLoc("block/" + sideTex),    // Bottom
                modLoc("block/" + topTex),     // Top
                modLoc("block/" + frontInactiveTex), // Front (North default)
                modLoc("block/" + sideTex),    // South
                modLoc("block/" + sideTex),    // West
                modLoc("block/" + sideTex)     // East
        ).renderType("minecraft:solid");

        // 2. Create the 3D model for the ACTIVE state (with glowing front)
        ModelFile activeModel = models().cube("block/" + block.getId().getPath() + "_on",
                modLoc("block/" + sideTex),    // Bottom
                modLoc("block/" + topTex),     // Top
                modLoc("block/" + frontActiveTex), // Front (Glowing!)
                modLoc("block/" + sideTex),    // South
                modLoc("block/" + sideTex),    // West
                modLoc("block/" + sideTex)     // East
        ).renderType("minecraft:solid");

        // 3. Assemble the variant map combining Facing and Lit properties
        getVariantBuilder(block.get()).forAllStates(state -> {
            Direction dir = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
            boolean isLit = state.getValue(BlockStateProperties.LIT);

            ModelFile currentModel = isLit ? activeModel : inactiveModel;

            int yRot = switch (dir) {
                case EAST -> 90;
                case SOUTH -> 180;
                case WEST -> 270;
                default -> 0; // NORTH
            };

            return ConfiguredModel.builder()
                    .modelFile(currentModel)
                    .rotationY(yRot)
                    .build();
        });

        // 4. Generate a clean 3D block item model for the player's hand/inventory (defaults to the inactive look)
        simpleBlockItem(block.get(), inactiveModel);
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
