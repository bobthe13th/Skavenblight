package org.ratden.skavenblight.ai.goal.clanrat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.ratden.skavenblight.ai.goal.SiegeGoal;
import org.ratden.skavenblight.ai.pathing.SiegeInteractionHandler;
import org.ratden.skavenblight.ai.pathing.SiegeNode;
import org.ratden.skavenblight.ai.pathing.StandardFlowField;

import java.util.EnumSet;

public class WidenStairsGoal extends Goal implements SiegeGoal {
    private final PathfinderMob mob;
    private StandardFlowField flowField;
    private long nextAllowedBuildTime = 0;

    private int buildTicks = 0;
    private final int maxBuildTicks = 15;
    private BlockPos placePos;
    private Direction stairFacing;

    public WidenStairsGoal(PathfinderMob mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    public void setFlowField(StandardFlowField flowField) {
        this.flowField = flowField;
    }

    @Override
    public boolean canUse() {
        if (this.flowField == null || this.mob.level().getGameTime() < this.nextAllowedBuildTime) {
            return false;
        }

        BlockPos currentPos = this.mob.blockPosition();

        // 1. Must NOT be on an active Flow Field path node (means we fell or got pushed off)
        if (this.flowField.getInstructionMap().containsKey(currentPos)) {
            return false;
        }

        // 2. Foot block must be empty, solid ground beneath us
        if (!this.mob.level().getBlockState(currentPos).canBeReplaced() ||
                !this.mob.level().getBlockState(currentPos.below()).blocksMotion()) {
            return false;
        }

        // 3. Headroom clearance check
        if (this.mob.level().getBlockState(currentPos.above()).blocksMotion() ||
                this.mob.level().getBlockState(currentPos.above(2)).blocksMotion()) {
            return false;
        }

        // 4. Scan adjacent horizontal blocks for a stair to widen
        int[][] horizontalOffsets = {{1,0}, {-1,0}, {0,1}, {0,-1}};
        for (int[] offset : horizontalOffsets) {
            for (int dy = 0; dy <= 1; dy++) {
                BlockPos checkPos = currentPos.offset(offset[0], dy, offset[1]);
                BlockState adjacentState = this.mob.level().getBlockState(checkPos);

                if (adjacentState.is(Blocks.COBBLESTONE_STAIRS)) {
                    this.placePos = currentPos;
                    this.stairFacing = adjacentState.getValue(StairBlock.FACING);
                    return true;
                }
            }
        }

        return false;
    }

    @Override
    public boolean canContinueToUse() {
        return this.buildTicks <= this.maxBuildTicks && this.placePos != null && this.mob.level().getBlockState(this.placePos).canBeReplaced();
    }

    @Override
    public void start() {
        this.buildTicks = 0;
    }

    @Override
    public void tick() {
        if (this.placePos != null) {
            this.mob.getLookControl().setLookAt(
                    this.placePos.getX() + 0.5D,
                    this.placePos.getY() + 0.5D,
                    this.placePos.getZ() + 0.5D
            );

            if (this.buildTicks % 5 == 0) {
                this.mob.swing(InteractionHand.MAIN_HAND);
            }

            this.buildTicks++;

            if (this.buildTicks >= this.maxBuildTicks) {
                if (this.mob.level() instanceof ServerLevel serverLevel) {

                    // Safety Traffic Check before placement
                    if (!SiegeInteractionHandler.isSpaceClear(serverLevel, this.placePos)) {
                        this.buildTicks = this.maxBuildTicks - 5; // Wait for space to clear
                        return;
                    }

                    // Delegate wide construction to handler
                    SiegeInteractionHandler.constructSiegeBlock(
                            serverLevel,
                            this.placePos,
                            this.stairFacing,
                            SiegeNode.SiegeAction.BUILD_STAIR,
                            this.flowField
                    );
                }

                this.nextAllowedBuildTime = this.mob.level().getGameTime() + 20;
            }
        }
    }

    @Override
    public void stop() {
        this.placePos = null;
        this.stairFacing = null;
        this.buildTicks = 0;
    }
}