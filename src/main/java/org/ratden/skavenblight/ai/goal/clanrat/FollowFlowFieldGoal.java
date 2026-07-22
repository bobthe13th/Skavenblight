package org.ratden.skavenblight.ai.goal.clanrat;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;
import org.ratden.skavenblight.ai.goal.SiegeGoal;
import org.ratden.skavenblight.ai.pathing.StandardFlowField;
import org.ratden.skavenblight.ai.pathing.SiegeNode;

import java.util.EnumSet;

public class FollowFlowFieldGoal extends Goal implements SiegeGoal {
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

        // --- CHANGED: Always return true if we have a flow field.
        // We will decide whether to use Flow Field or Vanilla pathing in the tick() method.
        return true;
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

            // --- CHANGED: Vanilla Pathfinding Fallback ---
            // If there is no SiegeNode, we are in the wilderness. Hand over to vanilla AI.
            if (targetNode == null) {
                this.mob.getNavigation().moveTo(
                        this.flowField.getTargetPos().getX() + 0.5D,
                        this.flowField.getTargetPos().getY(),
                        this.flowField.getTargetPos().getZ() + 0.5D,
                        this.speedModifier
                );
                return;
            }

            // If we DO have a node, but it isn't WALK, stop moving so the Builder/Miner goals can take over.
            if (targetNode.action() != SiegeNode.SiegeAction.WALK) {
                this.mob.getNavigation().stop();
                return;
            }

            // --- strict flow field execution below ---
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