package org.ratden.skavenblight.ai.goal.clanrat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.ratden.skavenblight.ai.pathing.SiegeInteractionHandler;
import org.ratden.skavenblight.ai.pathing.SiegeNode;

import java.util.Optional;

public class WidenStairsGoal extends AbstractSiegeConstructionGoal {

    private static final int[][] HORIZONTAL_OFFSETS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    public WidenStairsGoal(PathfinderMob mob) {
        super(mob);
    }

    @Override
    protected Optional<Target> findTarget() {
        if (this.flowField == null) return Optional.empty();

        BlockPos currentPos = this.mob.blockPosition();

        // Must NOT be on an active Flow Field path node (means we fell or got pushed off)
        if (this.flowField.getInstructionMap().containsKey(currentPos)) {
            return Optional.empty();
        }

        if (!this.mob.level().getBlockState(currentPos).canBeReplaced() ||
                !this.mob.level().getBlockState(currentPos.below()).blocksMotion()) {
            return Optional.empty();
        }

        if (this.mob.level().getBlockState(currentPos.above()).blocksMotion() ||
                this.mob.level().getBlockState(currentPos.above(2)).blocksMotion()) {
            return Optional.empty();
        }

        for (int[] offset : HORIZONTAL_OFFSETS) {
            for (int dy = 0; dy <= 1; dy++) {
                BlockPos checkPos = currentPos.offset(offset[0], dy, offset[1]);
                BlockState adjacentState = this.mob.level().getBlockState(checkPos);

                if (adjacentState.is(Blocks.COBBLESTONE_STAIRS)) {
                    return Optional.of(new Target(currentPos, SiegeNode.SiegeAction.BUILD_STAIR, adjacentState.getValue(StairBlock.FACING)));
                }
            }
        }

        return Optional.empty();
    }

    @Override
    protected int getActionDurationTicks() { return 15; }

    @Override
    protected long getPostActionCooldownTicks() { return 20; }

    @Override
    protected boolean isTargetStillValid(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).canBeReplaced();
    }

    @Override
    protected void execute(ServerLevel level, BlockPos pos, SiegeNode.SiegeAction action, Direction facing) {
        SiegeInteractionHandler.constructSiegeBlock(level, pos, facing, action, this.flowField);
    }
}
