package org.ratden.skavenblight.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class SkavenTunnelSourceEntity extends BlockEntity {

    public SkavenTunnelSourceEntity(BlockPos pos, BlockState blockState) {
        super(ModBlockEntities.SKAVEN_TUNNEL_SOURCE.get(), pos, blockState);
    }
}
