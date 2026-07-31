package org.ratden.skavenblight.block;

import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DropExperienceBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.PressurePlateBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import net.minecraft.world.level.block.state.properties.WoodType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.block.custom.*;
import org.ratden.skavenblight.item.ModItems;
import org.ratden.skavenblight.item.custom.WarpFluxStorageBlockItem;
import org.ratden.skavenblight.block.custom.debug.DebugIncursionAnchorBlock;
import org.ratden.skavenblight.block.entity.debug.DebugAnchorType;

import java.util.function.Supplier;

/**
 * Registers the Blocks added by Skavenblight.
 *
 * Player-obtainable blocks also receive matching BlockItems through the
 * ModItems registry. BlockEntityTypes, capability providers, and client
 * renderers are registered by their respective systems.
 */
public final class ModBlocks {

    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(
                    Skavenblight.MODID
            );

    public static final DeferredBlock<DropExperienceBlock>
            WARPSTONE_ORE =
            registerBlock(
                    "warpstone_ore",
                    () -> new DropExperienceBlock(
                            UniformInt.of(2, 5),
                            BlockBehaviour.Properties.of()
                                    .strength(4.0F)
                                    .requiresCorrectToolForDrops()
                                    .sound(SoundType.STONE)
                    )
            );

    public static final DeferredBlock<DropExperienceBlock>
            WARPSTONE_ORE_DEEPSLATE =
            registerBlock(
                    "warpstone_ore_deepslate",
                    () -> new DropExperienceBlock(
                            UniformInt.of(2, 5),
                            BlockBehaviour.Properties.of()
                                    .strength(4.0F)
                                    .requiresCorrectToolForDrops()
                                    .sound(SoundType.STONE)
                    )
            );

    public static final DeferredBlock<Block>
            BLOCK_OF_WARPSTONE =
            registerBlock(
                    "block_of_warpstone",
                    () -> new Block(
                            BlockBehaviour.Properties.of()
                                    .strength(4.0F)
                                    .requiresCorrectToolForDrops()
                                    .sound(SoundType.AMETHYST)
                    )
            );

    public static final DeferredBlock<StairBlock>
            WARPSTONE_STAIRS =
            registerBlock(
                    "warpstone_stairs",
                    () -> new StairBlock(
                            BLOCK_OF_WARPSTONE.get()
                                    .defaultBlockState(),
                            BlockBehaviour.Properties.of()
                                    .strength(2.0F)
                                    .requiresCorrectToolForDrops()
                    )
            );

    public static final DeferredBlock<SlabBlock>
            WARPSTONE_SLAB =
            registerBlock(
                    "warpstone_slab",
                    () -> new SlabBlock(
                            BlockBehaviour.Properties.of()
                                    .strength(2.0F)
                                    .requiresCorrectToolForDrops()
                    )
            );

    public static final DeferredBlock<PressurePlateBlock>
            WARPSTONE_PRESSURE_PLATE =
            registerBlock(
                    "warpstone_pressure_plate",
                    () -> new PressurePlateBlock(
                            BlockSetType.IRON,
                            BlockBehaviour.Properties.of()
                                    .strength(2.0F)
                                    .requiresCorrectToolForDrops()
                    )
            );

    public static final DeferredBlock<ButtonBlock>
            WARPSTONE_BUTTON =
            registerBlock(
                    "warpstone_button",
                    () -> new ButtonBlock(
                            BlockSetType.IRON,
                            5,
                            BlockBehaviour.Properties.of()
                                    .strength(2.0F)
                                    .requiresCorrectToolForDrops()
                                    .noCollission()
                    )
            );

    public static final DeferredBlock<FenceBlock>
            WARPSTONE_FENCE =
            registerBlock(
                    "warpstone_fence",
                    () -> new FenceBlock(
                            BlockBehaviour.Properties.of()
                                    .strength(2.0F)
                                    .requiresCorrectToolForDrops()
                    )
            );

    public static final DeferredBlock<FenceGateBlock>
            WARPSTONE_FENCE_GATE =
            registerBlock(
                    "warpstone_fence_gate",
                    () -> new FenceGateBlock(
                            WoodType.OAK,
                            BlockBehaviour.Properties.of()
                                    .strength(2.0F)
                                    .requiresCorrectToolForDrops()
                    )
            );

    public static final DeferredBlock<WallBlock>
            WARPSTONE_WALL =
            registerBlock(
                    "warpstone_wall",
                    () -> new WallBlock(
                            BlockBehaviour.Properties.of()
                                    .strength(2.0F)
                                    .requiresCorrectToolForDrops()
                    )
            );

    public static final DeferredBlock<DoorBlock>
            WARPSTONE_DOOR =
            registerBlock(
                    "warpstone_door",
                    () -> new DoorBlock(
                            BlockSetType.IRON,
                            BlockBehaviour.Properties.of()
                                    .strength(2.0F)
                                    .requiresCorrectToolForDrops()
                                    .noOcclusion()
                    )
            );

    public static final DeferredBlock<TrapDoorBlock>
            WARPSTONE_TRAPDOOR =
            registerBlock(
                    "warpstone_trapdoor",
                    () -> new TrapDoorBlock(
                            BlockSetType.IRON,
                            BlockBehaviour.Properties.of()
                                    .strength(2.0F)
                                    .requiresCorrectToolForDrops()
                                    .noOcclusion()
                    )
            );

    public static final DeferredBlock<Block>
            WARPSTONE_NEXUS =
            registerBlock(
                    "warpstone_nexus",
                    () -> new Block(
                            BlockBehaviour.Properties.of()
                                    .strength(10.0F)
                                    .requiresCorrectToolForDrops()
                                    .sound(SoundType.VAULT)
                                    .lightLevel(state -> 9)
                    )
            );

    public static final DeferredBlock<ActiveWarpstoneNexus>
            ACTIVE_WARPSTONE_NEXUS =
            registerBlock(
                    "active_warpstone_nexus",
                    () -> new ActiveWarpstoneNexus(
                            BlockBehaviour.Properties.of()
                                    .strength(10.0F)
                                    .requiresCorrectToolForDrops()
                                    .sound(SoundType.VAULT)
                                    .lightLevel(state ->
                                            state.getValue(
                                                    ActiveWarpstoneNexus.LIT
                                            )
                                                    ? 15
                                                    : 9
                                    )
                    )
            );

    public static final DeferredBlock<SkavenTunnelSourceBlock>
            SKAVEN_TUNNEL_SOURCE =
            registerBlock(
                    "skaven_tunnel_source",
                    () -> new SkavenTunnelSourceBlock(
                            BlockBehaviour.Properties.of()
                                    .strength(4.0F)
                                    .requiresCorrectToolForDrops()
                                    .sound(SoundType.STONE)
                    )
            );

    /**
     * Invisible debug representation of one front anchor.
     *
     * Registered without a BlockItem because it is created and removed only by
     * the incursion debug visualisation system.
     */
    public static final DeferredBlock<DebugIncursionAnchorBlock>
            DEBUG_FRONT_ANCHOR =
            BLOCKS.register(
                    "debug_front_anchor",
                    () -> new DebugIncursionAnchorBlock(
                            DebugAnchorType.FRONT,
                            createDebugAnchorProperties()
                    )
            );

    /**
     * Invisible debug representation of an additional physical source-group
     * anchor.
     *
     * The first source group uses its parent front anchor instead.
     */
    public static final DeferredBlock<DebugIncursionAnchorBlock>
            DEBUG_SOURCE_GROUP_ANCHOR =
            BLOCKS.register(
                    "debug_source_group_anchor",
                    () -> new DebugIncursionAnchorBlock(
                            DebugAnchorType.SOURCE_GROUP,
                            createDebugAnchorProperties()
                    )
            );

    public static final DeferredBlock<SpawnTunnelSmall>
            SPAWN_TUNNEL_SMALL =
            registerBlock(
                    "spawn_tunnel_small",
                    () -> new SpawnTunnelSmall(
                            BlockBehaviour.Properties.of()
                                    .noOcclusion()
                    )
            );

    public static final DeferredBlock<WarpFluxConduitBlock>
            WARP_FLUX_CONDUIT =
            registerBlock(
                    "warp_flux_conduit",
                    () -> new WarpFluxConduitBlock(
                            BlockBehaviour.Properties.of()
                                    .strength(2.0F)
                                    .requiresCorrectToolForDrops()
                                    .sound(SoundType.METAL)
                                    .noOcclusion()
                                    .lightLevel(
                                            WarpFluxConduitBlock
                                                    ::getLightEmission
                                    )
                    )
            );

    public static final DeferredBlock<WarpFluxStorageBlock>
            BASIC_WARP_FLUX_STORAGE =
            registerStorageBlock(
                    "basic_warp_flux_storage",
                    () -> new WarpFluxStorageBlock(
                            BlockBehaviour.Properties.of()
                                    .strength(4.0F)
                                    .requiresCorrectToolForDrops()
                                    .sound(SoundType.METAL),
                            50_000,
                            500,
                            500
                    )
            );

    public static final DeferredBlock<WarpFluxFurnaceBlock>
            WARP_FLUX_FURNACE =
            registerBlock(
                    "warp_flux_furnace",
                    () -> new WarpFluxFurnaceBlock(
                            BlockBehaviour.Properties.of()
                                    .strength(3.5F)
                                    .requiresCorrectToolForDrops()
                                    .sound(SoundType.METAL)
                                    .lightLevel(state ->
                                            state.getValue(
                                                    WarpFluxFurnaceBlock.LIT
                                            )
                                                    ? 13
                                                    : 0
                                    )
                    )
            );

    public static final DeferredBlock<PistonSpikeTrapBlock>
            PISTON_SPIKE_TRAP =
            registerBlock(
                    "piston_spike_trap",
                    () -> new PistonSpikeTrapBlock(
                            BlockBehaviour.Properties.of()
                                    .strength(3.5F)
                                    .requiresCorrectToolForDrops()
                                    .sound(SoundType.METAL)
                                    .noOcclusion()
                    )
            );

    public static final DeferredBlock<Block>
            WARP_LIGHTNING_COIL =
            registerBlock(
                    "warp_lightning_coil",
                    () -> new WarpLightningCoilBlock(
                            BlockBehaviour.Properties.of()
                                    .strength(3.5F)
                                    .requiresCorrectToolForDrops()
                                    .sound(SoundType.METAL)
                                    .noOcclusion()
                    )
            );

    // The invisible dummy block that handles the upper hitboxes
    public static final DeferredBlock<Block>
            WARP_LIGHTNING_COIL_DUMMY =
            registerBlock(
                    "warp_lightning_coil_dummy",
                    () -> new WarpLightningCoilDummyBlock(
                            BlockBehaviour.Properties.of()
                                    .strength(3.5F)
                                    .sound(SoundType.METAL)
                                    .noOcclusion()
                    )
            );

    public static final DeferredBlock<Block>
            ALCHEMICAL_LABORATORY =
            registerBlock(
                    "alchemical_laboratory",
                    () -> new AlchemicalLaboratoryBlock(
                            BlockBehaviour.Properties.of()
                                    .strength(3.0F)
                                    .sound(SoundType.GLASS)
                                    .noOcclusion()
                    )
            );

    private static BlockBehaviour.Properties
    createDebugAnchorProperties() {
        return BlockBehaviour.Properties.of()
                .noCollission()
                .noOcclusion()
                .strength(
                        -1.0F,
                        3_600_000.0F
                );
    }

    /**
     * Registers a block with its specialised storage BlockItem.
     */
    private static <T extends Block> DeferredBlock<T>
    registerStorageBlock(
            String name,
            Supplier<T> blockSupplier
    ) {
        DeferredBlock<T> registeredBlock =
                BLOCKS.register(
                        name,
                        blockSupplier
                );

        ModItems.ITEMS.register(
                name,
                () -> new WarpFluxStorageBlockItem(
                        registeredBlock.get(),
                        new Item.Properties()
                )
        );

        return registeredBlock;
    }

    /**
     * Registers a block together with a normal matching BlockItem.
     */
    private static <T extends Block> DeferredBlock<T> registerBlock(
            String name,
            Supplier<T> blockSupplier
    ) {
        DeferredBlock<T> registeredBlock =
                BLOCKS.register(
                        name,
                        blockSupplier
                );

        registerBlockItem(
                name,
                registeredBlock
        );

        return registeredBlock;
    }

    private static <T extends Block> void registerBlockItem(
            String name,
            DeferredBlock<T> block
    ) {
        ModItems.ITEMS.register(
                name,
                () -> new BlockItem(
                        block.get(),
                        new Item.Properties()
                )
        );
    }

    public static void register(IEventBus eventBus) {
        BLOCKS.register(eventBus);
    }

    private ModBlocks() {
    }
}