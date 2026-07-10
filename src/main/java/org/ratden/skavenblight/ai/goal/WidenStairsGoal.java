package org.ratden.skavenblight.ai.goal;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.ratden.skavenblight.ai.pathing.StandardFlowField;

import java.util.EnumSet;

public class WidenStairsGoal extends Goal {
    private final PathfinderMob mob;
    private StandardFlowField flowField;
    private long nextAllowedBuildTime = 0;

    private int buildTicks = 0;
    private final int maxBuildTicks = 15;
    private BlockPos placePos;
    private BlockState blockToPlace;

    public WidenStairsGoal(PathfinderMob mob) {
        this.mob = mob;
        // Suspend standard movement while placing the block
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

        // Condition 1: We must NOT be on a mapped Flow Field node.
        // If we are on the map, we are successfully following the path.
        // If we aren't, it means we fell off or got pushed into the wilderness.
        if (this.flowField.getInstructionMap().containsKey(currentPos)) {
            return false;
        }

        // Condition 2: The block at our feet must be empty, and we need solid ground beneath us.
        if (!this.mob.level().getBlockState(currentPos).canBeReplaced() ||
                !this.mob.level().getBlockState(currentPos.below()).blocksMotion()) {
            return false;
        }

        // Condition 3: We need 2 blocks of clearance above us.
        // We do not want to blindly build a stair into a solid ceiling we can't climb.
        if (this.mob.level().getBlockState(currentPos.above()).blocksMotion() ||
                this.mob.level().getBlockState(currentPos.above(2)).blocksMotion()) {
            return false;
        }

        // Condition 4: Scan adjacent horizontal blocks for an existing staircase to clone.
        int[][] horizontalOffsets = {{1,0}, {-1,0}, {0,1}, {0,-1}};
        for (int[] offset : horizontalOffsets) {
            // Check both our foot level and eye level to catch stairs we just fell off of
            for (int dy = 0; dy <= 1; dy++) {
                BlockPos checkPos = currentPos.offset(offset[0], dy, offset[1]);
                BlockState adjacentState = this.mob.level().getBlockState(checkPos);

                if (adjacentState.is(Blocks.COBBLESTONE_STAIRS)) {
                    Direction stairFacing = adjacentState.getValue(StairBlock.FACING);

                    this.placePos = currentPos;
                    // Clone the exact facing direction so the stairs link up cleanly side-by-side
                    this.blockToPlace = Blocks.COBBLESTONE_STAIRS.defaultBlockState()
                            .setValue(StairBlock.FACING, stairFacing);
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
                this.mob.level().setBlockAndUpdate(this.placePos, this.blockToPlace);
                // 20 tick (1 second) cooldown so a single rat doesn't spam a massive flat platform
                this.nextAllowedBuildTime = this.mob.level().getGameTime() + 20;

                // Optional: We do NOT force a Flow Field recalculation here.
                // Since this is off-map widening, the main path is still valid for the rest of the swarm.
            }
        }
    }

    @Override
    public void stop() {
        this.placePos = null;
        this.blockToPlace = null;
        this.buildTicks = 0;
    }
}