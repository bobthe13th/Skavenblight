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

        // Proactive case: the rat is standing ON an active path node, so currentPos IS part of the
        // lane - the reactive logic below (which always targets currentPos) would "widen" the path
        // by building a stair in the middle of it, which widens nothing. Hand off to a variant
        // that proposes an adjacent, off-path cell instead.
        if (onPath && crowdedAhead) {
            return findParallelLaneTarget(currentPos);
        }

        // Reactive trigger (original behavior): must NOT be on an active Flow Field path node
        // (means we fell or got pushed off).
        if (onPath) {
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

    /**
     * Proactive (crowded-lane) target selection. The rat is still on the path, so the cell to build
     * in is an ADJACENT one - the parallel lane beside the jammed one - never currentPos itself.
     * Candidates must be off-path (a cell that's already a path node is the same lane, not a
     * parallel one) and must satisfy the same physical checks the reactive branch applies to
     * currentPos: replaceable, solid support beneath, two blocks of headroom above.
     */
    private Optional<Target> findParallelLaneTarget(BlockPos currentPos) {
        Direction laneFacing = findLaneFacing(currentPos);
        if (laneFacing == null) return Optional.empty();

        for (int[] offset : HORIZONTAL_OFFSETS) {
            BlockPos candidate = currentPos.offset(offset[0], 0, offset[1]);

            if (this.flowField.getInstructionMap().containsKey(candidate)) continue;
            if (!canHostStair(candidate)) continue;
            if (this.flowField.isTargetClaimed(candidate)) continue;

            return Optional.of(new Target(candidate, SiegeNode.SiegeAction.BUILD_STAIR, laneFacing));
        }
        return Optional.empty();
    }

    /** Facing of the lane the rat is currently on, so the new lane runs alongside it instead of crossing it. */
    private Direction findLaneFacing(BlockPos currentPos) {
        BlockState self = this.mob.level().getBlockState(currentPos);
        if (self.is(Blocks.COBBLESTONE_STAIRS)) return self.getValue(StairBlock.FACING);

        BlockState below = this.mob.level().getBlockState(currentPos.below());
        if (below.is(Blocks.COBBLESTONE_STAIRS)) return below.getValue(StairBlock.FACING);

        for (int[] offset : HORIZONTAL_OFFSETS) {
            BlockState adjacent = this.mob.level().getBlockState(currentPos.offset(offset[0], 0, offset[1]));
            if (adjacent.is(Blocks.COBBLESTONE_STAIRS)) return adjacent.getValue(StairBlock.FACING);
        }
        return null;
    }

    private boolean canHostStair(BlockPos pos) {
        return this.mob.level().getBlockState(pos).canBeReplaced()
                && this.mob.level().getBlockState(pos.below()).blocksMotion()
                && !this.mob.level().getBlockState(pos.above()).blocksMotion()
                && !this.mob.level().getBlockState(pos.above(2)).blocksMotion();
    }

    @Override
    protected int getActionDurationTicks() { return 15; }

    @Override
    protected long getPostActionCooldownTicks() { return 20; }

    @Override
    protected int getMaxStalledTicks() { return 60; }

    @Override
    protected boolean isTargetStillValid(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).canBeReplaced()
                && (!this.supportSolidAtClaim || level.getBlockState(pos.below()).blocksMotion());
    }

    @Override
    protected void execute(ServerLevel level, BlockPos pos, SiegeNode.SiegeAction action, Direction facing) {
        SiegeInteractionHandler.constructSiegeBlock(level, pos, facing, action, this.flowField, this.mob, this.supportSolidAtClaim);
    }
}
