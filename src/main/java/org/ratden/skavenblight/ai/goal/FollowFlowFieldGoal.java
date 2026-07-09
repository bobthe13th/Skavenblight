package org.ratden.skavenblight.ai.goal;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;
import org.ratden.skavenblight.ai.pathing.StandardFlowField;

import java.util.EnumSet;

public class FollowFlowFieldGoal extends Goal {
    private final PathfinderMob mob;
    private final double speedModifier;
    private StandardFlowField flowField;

    private int pathingUpdateTimer = 0;
    private Vec3 lastPosition = null;
    private int escapeHatchTicks = 0;

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
    public void start() {
        this.lastPosition = this.mob.position();
        this.escapeHatchTicks = 0;
        this.pathingUpdateTimer = 0;
    }
    @Override
    public void tick() {
        if (this.escapeHatchTicks > 0) {
            this.escapeHatchTicks--;
            if (this.mob.getNavigation().isDone()) {
                this.escapeHatchTicks = 0;
            }
            return;
        }

        if (--this.pathingUpdateTimer <= 0) {
            this.pathingUpdateTimer = 10;
            Vec3 currentPosition = this.mob.position();

            // 1. STUCK DETECTION (If they still manage to snag, force an escape)
            if (this.lastPosition != null && this.mob.getNavigation().isInProgress()) {
                double distanceMovedSqr = currentPosition.distanceToSqr(this.lastPosition);
                if (distanceMovedSqr < 0.04) {
                    BlockPos escapeNode = findEscapeNode(this.mob.blockPosition());
                    if (escapeNode != null) {
                        this.mob.getNavigation().moveTo(
                                escapeNode.getX() + 0.5D,
                                escapeNode.getY(),
                                escapeNode.getZ() + 0.5D,
                                this.speedModifier
                        );
                        this.escapeHatchTicks = 60;
                        this.lastPosition = currentPosition;
                        return;
                    }
                }
            }
            this.lastPosition = currentPosition;

            // 2. PATHING (Short-chaining for smooth, safe corners)
            BlockPos currentPos = this.mob.blockPosition();
            BlockPos targetNode = this.flowField.getBestNextNode(currentPos);

            // --- NEW: FALLBACK HOMING ---
            // If the rat is completely off the map, walk blindly toward the Nexus!
            if (targetNode == null) {
                this.mob.getNavigation().moveTo(
                        this.flowField.getTargetPos().getX() + 0.5D,
                        this.flowField.getTargetPos().getY(),
                        this.flowField.getTargetPos().getZ() + 0.5D,
                        this.speedModifier
                );
                return;
            }

            // If the immediate next node requires building or mining, stop moving and wait!
            if (this.mob.level().getBlockState(targetNode.below()).canBeReplaced() ||
                    this.mob.level().getBlockState(targetNode).blocksMotion() ||
                    this.mob.level().getBlockState(targetNode.above()).blocksMotion()) {
                this.mob.getNavigation().stop();
                return;
            }

            BlockPos nextInChain = targetNode;
            int maxLookAhead = 3; // Short look-ahead prevents raycast clipping!

            for (int i = 0; i < maxLookAhead; i++) {
                BlockPos next = this.flowField.getBestNextNode(nextInChain);
                if (next == null || next.equals(nextInChain)) break;

                // Stop chaining if we hit a future gap or wall
                if (this.mob.level().getBlockState(next.below()).canBeReplaced() ||
                        this.mob.level().getBlockState(next).blocksMotion() ||
                        this.mob.level().getBlockState(next.above()).blocksMotion()) {
                    break;
                }
                nextInChain = next;
            }

            this.mob.getNavigation().moveTo(
                    nextInChain.getX() + 0.5D,
                    nextInChain.getY(),
                    nextInChain.getZ() + 0.5D,
                    this.speedModifier
            );
        }
    }

    private BlockPos findEscapeNode(BlockPos startPos) {
        BlockPos current = startPos;
        for (int i = 0; i < 6; i++) {
            BlockPos next = this.flowField.getBestNextNode(current);
            if (next == null || next.equals(current)) break;
            current = next;
        }
        return current.equals(startPos) ? null : current;
    }
}