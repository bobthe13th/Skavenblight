package org.ratden.skavenblight.event.skavenIncursion.action;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.ratden.skavenblight.block.ModBlocks;
import org.ratden.skavenblight.block.custom.SkavenTunnelSourceBlock;
import org.ratden.skavenblight.block.entity.state.SourceState;

public class SetSourceState {

    public static boolean execute(ServerLevel level, BlockPos pos, SourceState sourceState) {

        BlockState currentState = level.getBlockState(pos);

        if (!currentState.is(ModBlocks.SKAVEN_TUNNEL_SOURCE.get())) {
            return false;
        }

        BlockState updatedState = currentState.setValue(
                SkavenTunnelSourceBlock.SOURCE_STATE,
                sourceState
        );

        return level.setBlock(pos, updatedState, 3);
    }
}