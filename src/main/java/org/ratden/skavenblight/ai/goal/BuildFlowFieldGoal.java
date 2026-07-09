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

public class BuildFlowFieldGoal extends Goal {
    private final PathfinderMob mob;
    private StandardFlowField flowField;
    private long nextAllowedBuildTime = 0;

    public BuildFlowFieldGoal(PathfinderMob mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.TARGET)); // Use a non-movement flag so it runs alongside pathing
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
        BlockPos targetNode = this.flowField.getBestNextNode(currentPos);

        if (targetNode == null) return false;

        BlockPos supportPos = targetNode.below();

        // --- FIXED: Never place a block inside your own body ---
        if (supportPos.equals(currentPos) || supportPos.equals(currentPos.above())) {
            return false;
        }

        return this.mob.level().getBlockState(supportPos).canBeReplaced() && currentPos.closerThan(supportPos, 2.5D);
    }

    @Override
    public void start() {
        BlockPos currentPos = this.mob.blockPosition();
        BlockPos targetNode = this.flowField.getBestNextNode(currentPos);
        if (targetNode == null) return;

        BlockPos supportPos = targetNode.below();
        int dx = targetNode.getX() - currentPos.getX();
        int dy = targetNode.getY() - currentPos.getY();
        int dz = targetNode.getZ() - currentPos.getZ();

        BlockState blockToPlace;

        if (dy > 0) {
            Direction moveDir = Direction.getNearest(dx, 0, dz);
            blockToPlace = Blocks.COBBLESTONE_STAIRS.defaultBlockState()
                    .setValue(StairBlock.FACING, moveDir);
        } else {
            blockToPlace = Blocks.COBBLESTONE.defaultBlockState();
        }

        this.mob.level().setBlockAndUpdate(supportPos, blockToPlace);
        this.mob.swing(InteractionHand.MAIN_HAND);

        this.nextAllowedBuildTime = this.mob.level().getGameTime() + 10; // 10 tick cooldown
    }
}