package org.ratden.skavenblight.block.entity;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.block.ModBlocks;
import org.ratden.skavenblight.block.entity.debug.DebugIncursionAnchorEntity;

/**
 * Registers the BlockEntityTypes added by Skavenblight.
 *
 * Capability providers and client renderers are registered separately by
 * ModCapabilityProviders and ModBlockEntityRenderers.
 */
public final class ModBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>>
            BLOCK_ENTITY_TYPES =
            DeferredRegister.create(
                    Registries.BLOCK_ENTITY_TYPE,
                    Skavenblight.MODID
            );

    public static final DeferredHolder<
            BlockEntityType<?>,
            BlockEntityType<SkavenTunnelSourceEntity>
            > SKAVEN_TUNNEL_SOURCE =
            BLOCK_ENTITY_TYPES.register(
                    "skaven_tunnel_source",
                    () -> BlockEntityType.Builder.of(
                            SkavenTunnelSourceEntity::new,
                            ModBlocks.SKAVEN_TUNNEL_SOURCE.get()
                    ).build(null)
            );

    public static final DeferredHolder<
            BlockEntityType<?>,
            BlockEntityType<DebugIncursionAnchorEntity>
            > DEBUG_INCURSION_ANCHOR =
            BLOCK_ENTITY_TYPES.register(
                    "debug_incursion_anchor",
                    () -> BlockEntityType.Builder.of(
                            DebugIncursionAnchorEntity::new,
                            ModBlocks.DEBUG_FRONT_ANCHOR.get(),
                            ModBlocks.DEBUG_SOURCE_GROUP_ANCHOR.get()
                    ).build(null)
            );

    public static final DeferredHolder<
            BlockEntityType<?>,
            BlockEntityType<WarpstoneNexusEntity>
            > WARPSTONE_NEXUS =
            BLOCK_ENTITY_TYPES.register(
                    "warpstone_nexus",
                    () -> BlockEntityType.Builder.of(
                            WarpstoneNexusEntity::new,
                            ModBlocks.ACTIVE_WARPSTONE_NEXUS.get()
                    ).build(null)
            );

    public static final DeferredHolder<
            BlockEntityType<?>,
            BlockEntityType<WarpFluxConduitBlockEntity>
            > WARP_FLUX_CONDUIT =
            BLOCK_ENTITY_TYPES.register(
                    "warp_flux_conduit",
                    () -> BlockEntityType.Builder.of(
                            WarpFluxConduitBlockEntity::new,
                            ModBlocks.WARP_FLUX_CONDUIT.get()
                    ).build(null)
            );

    public static final DeferredHolder<
            BlockEntityType<?>,
            BlockEntityType<WarpFluxStorageBlockEntity>
            > WARP_FLUX_STORAGE =
            BLOCK_ENTITY_TYPES.register(
                    "warp_flux_storage",
                    () -> BlockEntityType.Builder.of(
                            WarpFluxStorageBlockEntity::new,
                            ModBlocks.BASIC_WARP_FLUX_STORAGE.get()
                    ).build(null)
            );

    public static final DeferredHolder<
            BlockEntityType<?>,
            BlockEntityType<WarpFluxFurnaceBlockEntity>
            > WARP_FLUX_FURNACE =
            BLOCK_ENTITY_TYPES.register(
                    "warp_flux_furnace",
                    () -> BlockEntityType.Builder.of(
                            WarpFluxFurnaceBlockEntity::new,
                            ModBlocks.WARP_FLUX_FURNACE.get()
                    ).build(null)
            );

    public static final DeferredHolder<
            BlockEntityType<?>,
            BlockEntityType<PistonSpikeTrapBlockEntity>
            > PISTON_SPIKE_TRAP =
            BLOCK_ENTITY_TYPES.register(
                    "piston_spike_trap",
                    () -> BlockEntityType.Builder.of(
                            PistonSpikeTrapBlockEntity::new,
                            ModBlocks.PISTON_SPIKE_TRAP.get()
                    ).build(null)
            );

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ResearchTableBlockEntity>> RESEARCH_TABLE =
            BLOCK_ENTITY_TYPES.register("research_table", () ->
                    BlockEntityType.Builder.of(ResearchTableBlockEntity::new,
                            ModBlocks.RESEARCH_TABLES.values().stream()
                                    .map(DeferredBlock::get)
                                    .toArray(Block[]::new)
                    ).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<WarpLightningCoilBlockEntity>> WARP_LIGHTNING_COIL_BE =
            BLOCK_ENTITY_TYPES.register("warp_lightning_coil", () ->
                    BlockEntityType.Builder.of(WarpLightningCoilBlockEntity::new,
                            ModBlocks.WARP_LIGHTNING_COIL.get()).build(null)
            );

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<AlchemicalLaboratoryBlockEntity>> ALCHEMICAL_LABORATORY =
            BLOCK_ENTITY_TYPES.register("alchemical_laboratory", () ->
                    BlockEntityType.Builder.of(AlchemicalLaboratoryBlockEntity::new,
                            ModBlocks.ALCHEMICAL_LABORATORY.get()).build(null)
            );

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<org.ratden.skavenblight.block.entity.ChaosMonolithBlockEntity>> CHAOS_MONOLITH =
            BLOCK_ENTITY_TYPES.register("chaos_monolith", () ->
                    BlockEntityType.Builder.of(org.ratden.skavenblight.block.entity.ChaosMonolithBlockEntity::new,
                            ModBlocks.CHAOS_MONOLITH.get()).build(null));

    public static void register(IEventBus eventBus) {
        BLOCK_ENTITY_TYPES.register(eventBus);
    }

    private ModBlockEntities() {
    }
}