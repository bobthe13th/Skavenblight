package org.ratden.skavenblight.block.custom;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.ratden.skavenblight.block.entity.ModBlockEntities;
import org.ratden.skavenblight.block.entity.WarpFluxStorageBlockEntity;

public class WarpFluxStorageBlock extends Block implements EntityBlock {
    private final int capacity;
    private final int maxReceive;
    private final int maxExtract;

    public WarpFluxStorageBlock(Properties properties, int capacity, int maxReceive, int maxExtract) {
        super(properties);
        this.capacity = capacity;
        this.maxReceive = maxReceive;
        this.maxExtract = maxExtract;
    }

    public int getCapacity() { return capacity; }
    public int getMaxReceive() { return maxReceive; }
    public int getMaxExtract() { return maxExtract; }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new WarpFluxStorageBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide()) return null;
        return type == ModBlockEntities.WARP_FLUX_STORAGE.get() ?
                (lvl, pos, st, be) -> ((WarpFluxStorageBlockEntity) be).tick(lvl, pos, st) : null;
    }
}