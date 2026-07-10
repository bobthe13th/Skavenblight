package org.ratden.skavenblight.ai.goal;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;
import org.ratden.skavenblight.ai.pathing.StandardFlowField;
import org.ratden.skavenblight.ai.pathing.SiegeNode;

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
        if (this.flowField == null || this.mob.getTarget() != null) return false;

        BlockPos pos = this.mob.blockPosition();
        SiegeNode node = this.flowField.getNextSiegeNode((ServerLevel) this.mob.level(), pos);

        // --- CHANGED: Off-path fallback ---
        if (node == null && this.mob.level() instanceof ServerLevel serverLevel) {
            node = this.flowField.getDynamicWildernessNode(serverLevel, pos);
        }

        return node != null && node.action() == SiegeNode.SiegeAction.WALK;
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

            if (this.lastPosition != null && this.mob.getNavigation().isInProgress()) {
                double distanceMovedSqr = currentPosition.distanceToSqr(this.lastPosition);
                if (distanceMovedSqr < 0.04) {
                    BlockPos escapePos = findEscapePos(this.mob.blockPosition());
                    if (escapePos != null) {
                        this.mob.getNavigation().moveTo(
                                escapePos.getX() + 0.5D,
                                escapePos.getY(),
                                escapePos.getZ() + 0.5D,
                                this.speedModifier
                        );
                        this.escapeHatchTicks = 60;
                        this.lastPosition = currentPosition;
                        return;
                    }
                }
            }
            this.lastPosition = currentPosition;

            BlockPos currentPos = this.mob.blockPosition();
            SiegeNode targetNode = this.flowField.getNextSiegeNode((ServerLevel) this.mob.level(), currentPos);

            // Dynamically retrieve node if in wilderness
            if (targetNode == null && this.mob.level() instanceof ServerLevel serverLevel) {
                targetNode = this.flowField.getDynamicWildernessNode(serverLevel, currentPos);
            }

            if (targetNode == null) {
                this.mob.getNavigation().moveTo(
                        this.flowField.getTargetPos().getX() + 0.5D,
                        this.flowField.getTargetPos().getY(),
                        this.flowField.getTargetPos().getZ() + 0.5D,
                        this.speedModifier
                );
                return;
            }

            if (targetNode.action() != SiegeNode.SiegeAction.WALK) {
                this.mob.getNavigation().stop();
                return;
            }

            BlockPos nextInChain = targetNode.pos();
            int maxLookAhead = 3;

            for (int i = 0; i < maxLookAhead; i++) {
                SiegeNode next = this.flowField.getNextSiegeNode((ServerLevel) this.mob.level(), nextInChain);
                if (next == null || next.pos().equals(nextInChain) || next.action() != SiegeNode.SiegeAction.WALK) {
                    break;
                }
                nextInChain = next.pos();
            }

            this.mob.getNavigation().moveTo(
                    nextInChain.getX() + 0.5D,
                    nextInChain.getY(),
                    nextInChain.getZ() + 0.5D,
                    this.speedModifier
            );
        }
    }

    private BlockPos findEscapePos(BlockPos startPos) {
        BlockPos current = startPos;
        for (int i = 0; i < 6; i++) {
            SiegeNode next = this.flowField.getNextSiegeNode((ServerLevel) this.mob.level(), current);
            if (next == null || next.pos().equals(current)) break;
            current = next.pos();
        }
        return current.equals(startPos) ? null : current;
    }
}