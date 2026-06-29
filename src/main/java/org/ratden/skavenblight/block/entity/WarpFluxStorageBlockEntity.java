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
        if (level.isClientSide()) return;

        int currentFlux = this.fluxStorage.getFlux();
        if (currentFlux <= 0) return;

        // Query our capability to see how much we are allowed to pull out this tick
        int amountToPush = this.fluxStorage.extractFlux(currentFlux, true);
        if (amountToPush <= 0) return;

        net.minecraft.server.level.ServerLevel serverLevel = (net.minecraft.server.level.ServerLevel) level;
        org.ratden.skavenblight.network.WarpFluxGridManager manager =
                org.ratden.skavenblight.network.WarpFluxGridManager.get(serverLevel);

        // Auto-discharge into any cables/networks touching our 6 sides
        for (Direction dir : Direction.values()) {
            BlockPos neighborPos = pos.relative(dir);
            org.ratden.skavenblight.network.WarpFluxNetwork network = manager.getNetworkAt(neighborPos);

            if (network != null) {
                int transferred = network.pushFlux(serverLevel, amountToPush, pos);
                if (transferred > 0) {
                    this.fluxStorage.extractFlux(transferred, false);
                    this.setChanged();
                    amountToPush -= transferred;
                    if (amountToPush <= 0) break;
                }
            }
        }
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
}