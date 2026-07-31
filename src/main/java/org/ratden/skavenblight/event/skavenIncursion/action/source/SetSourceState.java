package org.ratden.skavenblight.event.skavenIncursion.action.source;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.ratden.skavenblight.block.ModBlocks;
import org.ratden.skavenblight.block.custom.SkavenTunnelSourceBlock;
import org.ratden.skavenblight.block.entity.state.SourceState;

/**
 * Applies one operational SourceState to an existing Skaven tunnel source.
 *
 * The world block is authoritative for whether the operation can succeed.
 * Calling this with the state already present is idempotent and returns true
 * without issuing another block update.
 */
public final class SetSourceState {

    public static boolean execute(
            ServerLevel level,
            BlockPos pos,
            SourceState sourceState
    ) {
        if (level == null) {
            throw new IllegalArgumentException(
                    "Source-state level cannot be null."
            );
        }

        if (pos == null) {
            throw new IllegalArgumentException(
                    "Source-state position cannot be null."
            );
        }

        if (sourceState == null) {
            throw new IllegalArgumentException(
                    "Source state cannot be null."
            );
        }

        BlockState currentState =
                level.getBlockState(
                        pos
                );

        if (!currentState.is(
                ModBlocks.SKAVEN_TUNNEL_SOURCE.get()
        )) {
            return false;
        }

        SourceState currentWorldState =
                currentState.getValue(
                        SkavenTunnelSourceBlock.SOURCE_STATE
                );

        if (currentWorldState == sourceState) {
            return true;
        }

        BlockState updatedState =
                currentState.setValue(
                        SkavenTunnelSourceBlock.SOURCE_STATE,
                        sourceState
                );

        return level.setBlock(
                pos,
                updatedState,
                3
        );
    }

    private SetSourceState() {
    }
}