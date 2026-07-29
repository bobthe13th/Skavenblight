package org.ratden.skavenblight.block;

import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import net.minecraft.world.level.block.state.properties.WoodType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.ratden.skavenblight.Skavenblight;

import org.ratden.skavenblight.block.custom.*;

import org.ratden.skavenblight.block.custom.WarpLightningCoilDummyBlock;
import org.ratden.skavenblight.item.ModItems;
import org.ratden.skavenblight.magic.Wind;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;

public class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(Skavenblight.MODID);

    public static final DeferredBlock<Block> WARPSTONE_ORE = registerBlock("warpstone_ore",
            () -> new DropExperienceBlock(UniformInt.of(2, 5),
                    BlockBehaviour.Properties.of()
                        .strength(4f)
                        .requiresCorrectToolForDrops()
                        .sound(SoundType.STONE)));

    public static final DeferredBlock<Block> WARPSTONE_ORE_DEEPSLATE = registerBlock("warpstone_ore_deepslate",
            () -> new DropExperienceBlock(UniformInt.of(2, 5),
                    BlockBehaviour.Properties.of()
                        .strength(4f)
                        .requiresCorrectToolForDrops()
                        .sound(SoundType.STONE)));

    public static final DeferredBlock<Block> BLOCK_OF_WARPSTONE = registerBlock("block_of_warpstone",
        () -> new Block(BlockBehaviour.Properties.of()
                         .strength(4f)
                         .requiresCorrectToolForDrops()
                         .sound(SoundType.AMETHYST)));

    public static final DeferredBlock<StairBlock> WARPSTONE_STAIRS = registerBlock("warpstone_stairs",
            () -> new StairBlock(ModBlocks.BLOCK_OF_WARPSTONE.get().defaultBlockState(),
                    BlockBehaviour.Properties.of().strength(2f).requiresCorrectToolForDrops()));

    public static final DeferredBlock<SlabBlock> WARPSTONE_SLAB = registerBlock("warpstone_slab",
            () -> new SlabBlock(BlockBehaviour.Properties.of().strength(2f).requiresCorrectToolForDrops()));

    public static final DeferredBlock<PressurePlateBlock> WARPSTONE_PRESSURE_PLATE = registerBlock("warpstone_pressure_plate",
            () -> new PressurePlateBlock(BlockSetType.IRON, BlockBehaviour.Properties.of().strength(2f).requiresCorrectToolForDrops()));

    public static final DeferredBlock<ButtonBlock> WARPSTONE_BUTTON = registerBlock("warpstone_button",
            () -> new ButtonBlock(BlockSetType.IRON, 5, BlockBehaviour.Properties.of().strength(2f).requiresCorrectToolForDrops().noCollission()));

    public static final DeferredBlock<FenceBlock> WARPSTONE_FENCE = registerBlock("warpstone_fence",
            () -> new FenceBlock(BlockBehaviour.Properties.of().strength(2f).requiresCorrectToolForDrops()));

    public static final DeferredBlock<FenceGateBlock> WARPSTONE_FENCE_GATE = registerBlock("warpstone_fence_gate",
            () -> new FenceGateBlock(WoodType.OAK, BlockBehaviour.Properties.of().strength(2f).requiresCorrectToolForDrops()));

    public static final DeferredBlock<WallBlock> WARPSTONE_WALL = registerBlock("warpstone_wall",
            () -> new WallBlock(BlockBehaviour.Properties.of().strength(2f).requiresCorrectToolForDrops()));

    public static final DeferredBlock<DoorBlock> WARPSTONE_DOOR = registerBlock("warpstone_door",
            () -> new DoorBlock(BlockSetType.IRON, BlockBehaviour.Properties.of().strength(2f).requiresCorrectToolForDrops().noOcclusion()));

    public static final DeferredBlock<TrapDoorBlock> WARPSTONE_TRAPDOOR = registerBlock("warpstone_trapdoor",
            () -> new TrapDoorBlock(BlockSetType.IRON, BlockBehaviour.Properties.of().strength(2f).requiresCorrectToolForDrops().noOcclusion()));

    public static final DeferredBlock<Block> WARPSTONE_NEXUS = registerBlock("warpstone_nexus",
            () -> new Block(BlockBehaviour.Properties.of()
                    .strength(10f).requiresCorrectToolForDrops().sound(SoundType.VAULT).lightLevel(state -> 9)));

    public static final DeferredBlock<Block> ACTIVE_WARPSTONE_NEXUS = registerBlock("active_warpstone_nexus",
            () -> new ActiveWarpstoneNexus(BlockBehaviour.Properties.of()
                    .strength(10f)
                    .requiresCorrectToolForDrops().sound(SoundType.VAULT)
                    .lightLevel(state -> state.getValue(ActiveWarpstoneNexus.LIT) ? 15 : 9)));

    public static final DeferredBlock<Block> SKAVEN_TUNNEL_SOURCE = registerBlock(
            "skaven_tunnel_source",
            () -> new SkavenTunnelSourceBlock(BlockBehaviour.Properties.of()
                    .strength(4.0f)
                    .requiresCorrectToolForDrops()
                    .sound(SoundType.STONE)
            )
    );

    public static final DeferredBlock<Block> SPAWN_TUNNEL_SMALL = registerBlock("spawn_tunnel_small",
        () -> new SpawnTunnelSmall(BlockBehaviour.Properties.of().noOcclusion()));

    public static final DeferredBlock<Block> WARP_FLUX_CONDUIT = registerBlock("warp_flux_conduit",
            () -> new WarpFluxConduitBlock(BlockBehaviour.Properties.of()
                    .strength(2.0f)
                    .requiresCorrectToolForDrops()
                    .sound(SoundType.METAL)
                    .noOcclusion() // Keeps your custom models from creating x-ray holes
                    .lightLevel(WarpFluxConduitBlock::getLightEmission) // Links the dynamic light!
            ));

    public static final DeferredBlock<Block> BASIC_WARP_FLUX_STORAGE = registerStorageBlock("basic_warp_flux_storage",
            () -> new org.ratden.skavenblight.block.custom.WarpFluxStorageBlock(
                    BlockBehaviour.Properties.of().strength(4.0f).sound(SoundType.METAL).requiresCorrectToolForDrops(),
                    50000, 500, 500
            ));

    public static final DeferredBlock<Block> WARP_FLUX_FURNACE = registerBlock("warp_flux_furnace",
            () -> new WarpFluxFurnaceBlock(BlockBehaviour.Properties.of()
                    .strength(3.5f)
                    .requiresCorrectToolForDrops()
                    .sound(SoundType.METAL)
                    // Dynamically emit light when LIT is true
                    .lightLevel(state -> state.getValue(WarpFluxFurnaceBlock.LIT) ? 13 : 0)
            ));
    public static final DeferredBlock<Block> PISTON_SPIKE_TRAP = registerBlock("piston_spike_trap",
            () -> new PistonSpikeTrapBlock(BlockBehaviour.Properties.of()
                    .strength(3.5f)
                    .requiresCorrectToolForDrops()
                    .sound(SoundType.METAL)
                    .noOcclusion() // Highly recommended for GeckoLib animated blocks!
            ));

    public static final Map<Wind, DeferredBlock<Block>> RESEARCH_TABLES = registerResearchTables();

    private static Map<Wind, DeferredBlock<Block>> registerResearchTables() {
        Map<Wind, DeferredBlock<Block>> map = new EnumMap<>(Wind.class);
        for (Wind wind : Wind.values()) {
            DeferredBlock<Block> table = registerBlock("research_table_" + wind.getSerializedName(),
                    () -> new ResearchTableBlock(wind, BlockBehaviour.Properties.of()
                            .strength(2.5f)
                            .sound(SoundType.STONE)
                            .lightLevel(state -> state.getValue(ResearchTableBlock.LIT) ? 10 : 0)
                    ));
            map.put(wind, table);
        }
        return Collections.unmodifiableMap(map);
    }

    public static final DeferredBlock<Block> WARP_LIGHTNING_COIL = registerBlock("warp_lightning_coil",
            () -> new WarpLightningCoilBlock(BlockBehaviour.Properties.of()
                    .strength(3.5f)
                    .requiresCorrectToolForDrops()
                    .sound(SoundType.METAL)
                    .noOcclusion() // Essential for GeckoLib models and custom VoxelShapes
            ));

    // The invisible dummy block that handles the upper hitboxes
    public static final DeferredBlock<Block> WARP_LIGHTNING_COIL_DUMMY = registerBlock("warp_lightning_coil_dummy",
            () -> new WarpLightningCoilDummyBlock(BlockBehaviour.Properties.of()
                    .strength(3.5f) // Matches the base block so mining the top feels the same
                    .sound(SoundType.METAL)
                    .noOcclusion() // Essential for invisible blocks
            ));



    // A specialized helper function just for your expandable storage block tiers
    private static <T extends Block> DeferredBlock<T> registerStorageBlock(String name, java.util.function.Supplier<T> block) {
        DeferredBlock<T> toReturn = BLOCKS.register(name, block);
        ModItems.ITEMS.register(name, () -> new org.ratden.skavenblight.item.custom.WarpFluxStorageBlockItem(toReturn.get(), new net.minecraft.world.item.Item.Properties()));
        return toReturn;
    }
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
