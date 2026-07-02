package org.ratden.skavenblight.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.ratden.skavenblight.block.custom.WarpFluxStorageBlock;
import org.ratden.skavenblight.capability.custom.WarpFluxStorage;

public class WarpFluxStorageBlockEntity extends BlockEntity {
    private final WarpFluxStorage fluxStorage;

    public WarpFluxStorageBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.WARP_FLUX_STORAGE.get(), pos, state);

        int cap = 10000;
        int rec = 100;
        int ext = 100;

        if (state.getBlock() instanceof WarpFluxStorageBlock storageBlock) {
            cap = storageBlock.getCapacity();
            rec = storageBlock.getMaxReceive();
            ext = storageBlock.getMaxExtract();
        }

        this.fluxStorage = new WarpFluxStorage(cap, rec, ext);
    }

    public WarpFluxStorage getFluxStorage() {
        return this.fluxStorage;
    }

    public void tick(Level level, BlockPos pos, BlockState state) {

    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        this.fluxStorage.saveNBTData(tag, registries);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.fluxStorage.loadNBTData(tag, registries);
    }
    @Override
    public void onLoad() {
        super.onLoad();
        // Check if we are on the server side
        if (this.getLevel() != null && !this.getLevel().isClientSide() && this.getLevel() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            org.ratden.skavenblight.network.WarpFluxGridManager manager =
                    org.ratden.skavenblight.network.WarpFluxGridManager.get(serverLevel);

            // Look around us for networks and tell them to rescan!
            for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
                net.minecraft.core.BlockPos neighborPos = this.getBlockPos().relative(dir);
                org.ratden.skavenblight.network.WarpFluxNetwork network = manager.getNetworkAt(neighborPos);

                if (network != null) {
                    // Our capability is ready! Tell the network to scan and add us.
                    network.scanForEndpoints(serverLevel);
                    manager.setDirty();
                }
            }
        }
    }

    @Override
    public void setRemoved() {
        if (this.getLevel() != null && !this.getLevel().isClientSide() && this.getLevel() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            org.ratden.skavenblight.network.WarpFluxGridManager manager =
                    org.ratden.skavenblight.network.WarpFluxGridManager.get(serverLevel);

            for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
                net.minecraft.core.BlockPos neighborPos = this.getBlockPos().relative(dir);
                org.ratden.skavenblight.network.WarpFluxNetwork network = manager.getNetworkAt(neighborPos);

                if (network != null) {
                    // Tell the network we are broken so it stops sending power here
                    network.getEndpoints().remove(this.getBlockPos());
                    manager.setDirty();
                }
            }
        }
        super.setRemoved();
    }
}