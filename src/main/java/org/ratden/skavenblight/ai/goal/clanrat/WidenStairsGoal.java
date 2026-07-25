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

        boolean onPath = this.flowField.getInstructionMap().containsKey(currentPos);

        // Proactive trigger: even while still ON the active path, notice if the very next
        // flow-field instruction sits on a connector lane that's already saturated with other
        // mobs (see RegionFlowField.isLaneCrowded) - no need to wait until a rat actually falls
        // off before widening a bridge/staircase that's clearly jammed.
        boolean crowdedAhead = false;
        if (onPath && this.mob.level() instanceof ServerLevel serverLevel) {
            SiegeNode nextNode = this.flowField.getNextSiegeNode(serverLevel, currentPos);
            crowdedAhead = nextNode != null && this.flowField.isLaneCrowded(nextNode.pos());
        }

        // Reactive trigger (original behavior): must NOT be on an active Flow Field path node
        // (means we fell or got pushed off). Skip this precondition when the proactive trigger
        // above already fired - a rat still on-path can usefully notice its upcoming lane is
        // jammed and start widening it now instead of waiting to fall off first.
        if (onPath && !crowdedAhead) {
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

        // Same-level (dy=0) only. This used to also check dy=1 (a neighbor one block above
        // currentPos) but always targeted currentPos regardless of which dy matched - when
        // the dy=1 case fired, the new stair landed one block too low relative to the
        // neighbor it copied the facing from, breaking the parallel lane's continuity right
        // where it was built. Confirmed via SiegeActivityLog in testing: "placed a stair
        // block too high, blocking them from being able to continue building" traced to
        // exactly this mismatch. currentPos.above() isn't a fix either - currentPos itself is
        // confirmed air/replaceable above, so a stair placed one row up would have nothing
        // solid beneath it. Only dy=0 is a position this method has already verified
        // currentPos can correctly support (see the canBeReplaced/blocksMotion checks above).
        for (int[] offset : HORIZONTAL_OFFSETS) {
            BlockPos checkPos = currentPos.offset(offset[0], 0, offset[1]);
            BlockState adjacentState = this.mob.level().getBlockState(checkPos);

            if (adjacentState.is(Blocks.COBBLESTONE_STAIRS)) {
                return Optional.of(new Target(currentPos, SiegeNode.SiegeAction.BUILD_STAIR, adjacentState.getValue(StairBlock.FACING)));
            }
        }

        return Optional.empty();
    }

    @Override
    protected int getActionDurationTicks() { return 15; }

    @Override
    protected long getPostActionCooldownTicks() { return 20; }

    @Override
    protected int getMaxStalledTicks() { return 60; }

    @Override
    protected boolean isTargetStillValid(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).canBeReplaced();
    }

    @Override
    protected void execute(ServerLevel level, BlockPos pos, SiegeNode.SiegeAction action, Direction facing) {
        SiegeInteractionHandler.constructSiegeBlock(level, pos, facing, action, this.flowField, this.mob);
    }
}
