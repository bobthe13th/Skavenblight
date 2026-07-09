package org.ratden.skavenblight.ai.goal;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
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
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    public void setFlowField(StandardFlowField flowField) {
        this.flowField = flowField;
    }

    @Override
    public boolean canUse() {
        return this.flowField != null && this.mob.getTarget() == null;
    }

    @Override
    public void tick() {
        if (--this.recalculateCooldown <= 0) {
            this.recalculateCooldown = 10;

            if (this.mob.level() instanceof ServerLevel serverLevel) {
                this.flowField.calculateMapIfNeeded(serverLevel);
            }

            BlockPos currentPos = this.mob.blockPosition();
            BlockPos targetNode = this.flowField.getBestNextNode(currentPos);

            if (targetNode != null) {
                // --- NEW: STRING PULLING LOGIC ---
                BlockPos furthestVisibleNode = targetNode;
                BlockPos nextInChain = targetNode;
                int maxLookAhead = 12; // Check up to 12 blocks down the path

                // Shoot a raycast from the mob's chest level
                Vec3 startRay = this.mob.position().add(0, this.mob.getBbHeight() / 2.0, 0);

                for (int i = 0; i < maxLookAhead; i++) {
                    nextInChain = this.flowField.getBestNextNode(nextInChain);

                    // If we reached the end of the calculated path or it loops, stop looking
                    if (nextInChain == null || nextInChain.equals(furthestVisibleNode)) {
                        break;
                    }

                    // Aim the raycast at the center of the future block
                    Vec3 endRay = Vec3.atCenterOf(nextInChain);

                    ClipContext context = new ClipContext(
                            startRay, endRay,
                            ClipContext.Block.COLLIDER, // Only hit solid blocks
                            ClipContext.Fluid.NONE,
                            this.mob
                    );

                    HitResult result = this.mob.level().clip(context);

                    // If the raycast DID NOT hit a wall, we can see this block!
                    if (result.getType() == HitResult.Type.MISS) {
                        furthestVisibleNode = nextInChain;
                    } else {
                        // The line of sight is broken by a wall, stop extending the chain
                        break;
                    }
                }

                // Tell the navigator to walk to the furthest node we could see
                this.mob.getNavigation().moveTo(
                        furthestVisibleNode.getX() + 0.5D,
                        furthestVisibleNode.getY(),
                        furthestVisibleNode.getZ() + 0.5D,
                        this.speedModifier
                );
            }
        }
    }
}