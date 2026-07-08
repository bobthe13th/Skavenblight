package org.ratden.skavenblight.ai.goal;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import org.ratden.skavenblight.ai.pathing.StandardFlowField;

import java.util.EnumSet;

public class FollowFlowFieldGoal extends Goal {
    private final PathfinderMob mob;
    private final double speedModifier;
    private StandardFlowField flowField;
    private int recalculateCooldown = 0;

    public FollowFlowFieldGoal(PathfinderMob mob, double speedModifier) {
        this.mob = mob;
        this.speedModifier = speedModifier;
        // Flag this as a movement goal so it doesn't conflict with wandering
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    public void setFlowField(StandardFlowField flowField) {
        this.flowField = flowField;
    }

    @Override
    public boolean canUse() {
        // Only run if we have a field and aren't currently fighting a player/golem
        return this.flowField != null && this.mob.getTarget() == null;
    }

    @Override
    public void tick() {
        // We don't need to spam the navigator every single tick.
        // Updating the path every 10 ticks (half a second) is plenty fast and saves CPU.
        if (--this.recalculateCooldown <= 0) {
            this.recalculateCooldown = 10;

            // Get the exact 3D node from our upgraded Flow Field
            BlockPos nextNode = this.flowField.getBestNextNode(this.mob.blockPosition());

            if (nextNode != null) {
                // Tell the vanilla navigator to handle the smooth diagonal walking!
                // We add 0.5 to X and Z to make them walk to the center of the block.
                this.mob.getNavigation().moveTo(
                        nextNode.getX() + 0.5D,
                        nextNode.getY(),
                        nextNode.getZ() + 0.5D,
                        this.speedModifier
                );
            }
        }
    }
}