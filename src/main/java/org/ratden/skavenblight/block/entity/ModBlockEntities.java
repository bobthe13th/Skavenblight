package org.ratden.skavenblight.block.entity;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.block.ModBlocks;

public class ModBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Skavenblight.MODID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SkavenTunnelSourceEntity>>
            SKAVEN_TUNNEL_SOURCE =
            BLOCK_ENTITIES.register("skaven_tunnel_source",
                    () -> BlockEntityType.Builder.of(
                            SkavenTunnelSourceEntity::new,
                            ModBlocks.SKAVEN_TUNNEL_SOURCE.get()
                    ).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<WarpstoneNexusEntity>>
            WARPSTONE_NEXUS =
            BLOCK_ENTITIES.register("warpstone_nexus",
                    () -> BlockEntityType.Builder.of(
                            WarpstoneNexusEntity::new,
                            ModBlocks.ACTIVE_WARPSTONE_NEXUS.get()
                    ).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<WarpFluxConduitBlockEntity>>
            WARP_FLUX_CONDUIT =
            BLOCK_ENTITIES.register("warp_flux_conduit",
                    () -> BlockEntityType.Builder.of(
                            WarpFluxConduitBlockEntity::new,
                            ModBlocks.WARP_FLUX_CONDUIT.get()
                    ).build(null));

    public static final net.neoforged.neoforge.registries.DeferredHolder<BlockEntityType<?>, BlockEntityType<WarpFluxStorageBlockEntity>>
            WARP_FLUX_STORAGE =
            BLOCK_ENTITIES.register("warp_flux_storage",
                    () -> BlockEntityType.Builder.of(
                            WarpFluxStorageBlockEntity::new,
                            ModBlocks.BASIC_WARP_FLUX_STORAGE.get() // To add more tiers later, comma-separate them here!
                    ).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<WarpFluxFurnaceBlockEntity>> WARP_FLUX_FURNACE =
            BLOCK_ENTITIES.register("warp_flux_furnace", () ->
                    BlockEntityType.Builder.of(WarpFluxFurnaceBlockEntity::new, ModBlocks.WARP_FLUX_FURNACE.get())
                            .build(null));

    public static final java.util.function.Supplier<net.minecraft.world.level.block.entity.BlockEntityType<org.ratden.skavenblight.block.entity.PistonSpikeTrapBlockEntity>> PISTON_SPIKE_TRAP =
            BLOCK_ENTITIES.register("piston_spike_trap", () ->
                    net.minecraft.world.level.block.entity.BlockEntityType.Builder.of(
                            org.ratden.skavenblight.block.entity.PistonSpikeTrapBlockEntity::new,
                            org.ratden.skavenblight.block.ModBlocks.PISTON_SPIKE_TRAP.get()
                    ).build(null));

    public static void register(IEventBus eventBus) {
        BLOCK_ENTITIES.register(eventBus);
    }
}
