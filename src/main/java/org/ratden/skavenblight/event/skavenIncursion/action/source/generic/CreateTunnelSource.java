package org.ratden.skavenblight.event.skavenIncursion.action.source.generic;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.ratden.skavenblight.block.ModBlocks;
import org.ratden.skavenblight.block.custom.SkavenTunnelSourceBlock;
import org.ratden.skavenblight.block.entity.SkavenTunnelSourceEntity;
import org.ratden.skavenblight.block.entity.state.SourceState;
import org.ratden.skavenblight.event.skavenIncursion.leadership.LeadershipContext;

import java.util.UUID;

public class CreateTunnelSource {

    public static UUID execute(
            ServerLevel level,
            BlockPos pos,
            SourceState sourceState,
            LeadershipContext leadershipContext
    ) {
        prepareTunnelArea(level, pos);

        BlockState blockState = ModBlocks.SKAVEN_TUNNEL_SOURCE.get()
                .defaultBlockState()
                .setValue(SkavenTunnelSourceBlock.SOURCE_STATE, sourceState);

        boolean success = level.setBlock(pos, blockState, 3);

        if (!success) {
            return null;
        }

        BlockEntity blockEntity = level.getBlockEntity(pos);

        if (blockEntity instanceof SkavenTunnelSourceEntity tunnelSource) {
            tunnelSource.setScenarioId(leadershipContext.scenarioId());
            tunnelSource.setVermintideId(leadershipContext.vermintideId());
            tunnelSource.setFangId(leadershipContext.fangId());
            tunnelSource.setClawId(leadershipContext.clawId());
            tunnelSource.setPackId(leadershipContext.packId());
            tunnelSource.setCreatedGameTime(level.getGameTime());

            return tunnelSource.getSourceId();
        }

        return null;
    }

    private static void prepareTunnelArea(ServerLevel level, BlockPos sourcePos) {
        /*for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {

                BlockPos floorPos = sourcePos.offset(x, -1, z);

                level.setBlock(
                        floorPos,
                        Blocks.DIRT.defaultBlockState(),
                        3
                );

                for (int y = 0; y <= 2; y++) {
                    BlockPos airPos = sourcePos.offset(x, y, z);

                    level.setBlock(
                            airPos,
                            Blocks.AIR.defaultBlockState(),
                            3
                    );
                }
            }
        }*/
    }

    private CreateTunnelSource() {
    }
}