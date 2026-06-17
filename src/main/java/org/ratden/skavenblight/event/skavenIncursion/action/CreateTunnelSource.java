package org.ratden.skavenblight.event.skavenIncursion.action;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.ratden.skavenblight.block.ModBlocks;
import org.ratden.skavenblight.block.custom.SkavenTunnelSourceBlock;
import org.ratden.skavenblight.block.entity.SkavenTunnelSourceEntity;
import org.ratden.skavenblight.block.entity.state.SourceState;

public class CreateTunnelSource {

    public static boolean execute(ServerLevel level, BlockPos pos, SourceState sourceState) {
        return execute(level, pos, sourceState, "unknown");
    }

    public static boolean execute(
            ServerLevel level,
            BlockPos pos,
            SourceState sourceState,
            String parentIncursionName
    ) {
        BlockState blockState = ModBlocks.SKAVEN_TUNNEL_SOURCE.get()
                .defaultBlockState()
                .setValue(SkavenTunnelSourceBlock.SOURCE_STATE, sourceState);

        boolean success = level.setBlock(pos, blockState, 3);

        if (!success) {
            return false;
        }

        BlockEntity blockEntity = level.getBlockEntity(pos);

        if (blockEntity instanceof SkavenTunnelSourceEntity tunnelSource) {
            tunnelSource.setParentIncursionName(parentIncursionName);
            tunnelSource.setCreatedGameTime(level.getGameTime());
        }

        return true;
    }
}