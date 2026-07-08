package org.ratden.skavenblight.ai.goal;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import org.ratden.skavenblight.ai.pathing.StandardFlowField;

import java.util.EnumSet;

public class FollowFlowFieldGoal extends Goal {
    private final PathfinderMob mob;
    private final double speedModifier;

    // We leave this null until an Incursion assigns it
    private StandardFlowField flowField;
    private int recalculateTimer;

    public FollowFlowFieldGoal(PathfinderMob mob, double speedModifier) {
        this.mob = mob;
        this.speedModifier = speedModifier;
        // This tells Minecraft this goal controls movement,
        // preventing other movement goals from running at the same time.
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    /**
     * Called by the Incursion Manager to assign the map to this specific rat.
     */
    public void setFlowField(StandardFlowField flowField) {
        this.flowField = flowField;
    }

    @Override
    public boolean canUse() {
        // The mob will only try to follow a flow field if it actually has one.
        // Otherwise, it falls back to its other goals (like wandering or attacking).
        return this.flowField != null;
    }

    @Override
    public boolean canContinueToUse() {
        return this.flowField != null && this.mob.isAlive();
    }

    @Override
    public void start() {
        this.recalculateTimer = 0;
    }

    @Override
    public void tick() {
        if (this.flowField == null) return;

        // We only update the navigation target every 5 ticks (1/4th of a second).
        // Recalculating every single tick causes mobs to stutter.
        if (--this.recalculateTimer <= 0) {
            this.recalculateTimer = 5;

            BlockPos currentPos = this.mob.blockPosition();
            Direction bestDir = this.flowField.getBestDirection(currentPos);

            if (bestDir != null) {
                // Determine the exact center of the block we need to step into
                BlockPos targetPos = currentPos.relative(bestDir);

                // We use vanilla navigation here so the mob still auto-jumps up stairs
                // and steps over slabs naturally.
                this.mob.getNavigation().moveTo(
                        targetPos.getX() + 0.5,
                        targetPos.getY(),
                        targetPos.getZ() + 0.5,
                        this.speedModifier
                );
            } else {
                // If there is no arrow (either they reached the target or got stuck), stop walking.
                this.mob.getNavigation().stop();
            }
        }
    }
}