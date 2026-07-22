package org.ratden.skavenblight.ai.goal.clanrat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import org.ratden.skavenblight.ai.goal.SiegeGoal;
import org.ratden.skavenblight.ai.pathing.SiegeInteractionHandler;
import org.ratden.skavenblight.ai.pathing.StandardFlowField;
import org.ratden.skavenblight.ai.pathing.SiegeNode;

import java.util.EnumSet;

public class BuildFlowFieldGoal extends Goal implements SiegeGoal {
    private final PathfinderMob mob;
    private StandardFlowField flowField;
    private long nextAllowedBuildTime = 0;

    private int buildTicks = 0;
    private final int maxBuildTicks = 15;

    // Deadlock Prevention Mechanics
    private int stalledTicks = 0;
    private final int maxStalledTicks = 60; // 3 seconds timeout

    private BlockPos placePos;
    private Direction moveDir;
    private SiegeNode.SiegeAction targetAction;
    private int recalculateCooldown = 0;

    public BuildFlowFieldGoal(PathfinderMob mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public void setFlowField(StandardFlowField flowField) {
        this.flowField = flowField;
    }

    private boolean isBuildAction(SiegeNode.SiegeAction action) {
        return action == SiegeNode.SiegeAction.BUILD_STAIR ||
                action == SiegeNode.SiegeAction.BUILD_BRIDGE ||
                action == SiegeNode.SiegeAction.BUILD_PILLAR ||
                action == SiegeNode.SiegeAction.BUILD_LANDING;
    }

    private SiegeNode getEffectiveNode(BlockPos currentPos) {
        ServerLevel serverLevel = (ServerLevel) this.mob.level();
        SiegeNode node = this.flowField.getNextSiegeNode(serverLevel, currentPos);

        if (node != null && node.action() == SiegeNode.SiegeAction.WALK) {
            SiegeNode nextNode = this.flowField.getNextSiegeNode(serverLevel, node.pos());
            if (nextNode != null && isBuildAction(nextNode.action())) {
                if (currentPos.closerThan(nextNode.pos(), 2.5D)) {
                    return nextNode;
                }
            }
        }
        return node;
    }

    @Override
    public boolean canUse() {
        if (this.flowField == null || this.mob.level().getGameTime() < this.nextAllowedBuildTime) {
            return false;
        }

        BlockPos currentPos = this.mob.blockPosition();
        SiegeNode node = getEffectiveNode(currentPos);

        if (node != null && isBuildAction(node.action())) {
            return this.mob.level().getBlockState(node.pos()).canBeReplaced() && currentPos.closerThan(node.pos(), 2.5D);
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
        this.stalledTicks = 0;
        BlockPos currentPos = this.mob.blockPosition();
        SiegeNode node = getEffectiveNode(currentPos);

        if (node == null) return;

        this.placePos = node.pos();
        this.targetAction = node.action();

        int dx = this.placePos.getX() - currentPos.getX();
        int dz = this.placePos.getZ() - currentPos.getZ();

        this.moveDir = this.mob.getDirection();
        if (Math.abs(dx) > Math.abs(dz)) {
            this.moveDir = dx > 0 ? Direction.EAST : Direction.WEST;
        } else if (dz != 0) {
            this.moveDir = dz > 0 ? Direction.SOUTH : Direction.NORTH;
        }
    }

    @Override
    public void tick() {
        if (this.placePos != null && this.mob.level() instanceof ServerLevel serverLevel) {
            this.mob.getLookControl().setLookAt(
                    this.placePos.getX() + 0.5D,
                    this.placePos.getY() + 0.5D,
                    this.placePos.getZ() + 0.5D
            );

            if (this.buildTicks % 5 == 0) this.mob.swing(InteractionHand.MAIN_HAND);
            this.buildTicks++;

            if (this.recalculateCooldown > 0) this.recalculateCooldown--;

            if (this.buildTicks >= this.maxBuildTicks) {

                // Deadlock check: Is the placement area obstructed by mobs?
                if (!SiegeInteractionHandler.isSpaceClear(serverLevel, this.placePos)) {
                    this.stalledTicks++;

                    // 1. Clear Space: Nudge obstructing entities out of the target block
                    SiegeInteractionHandler.pushOccupantsAway(serverLevel, this.placePos, this.mob);

                    // 2. Timeout & Abandon: If blocked for over 3 seconds, give up
                    if (this.stalledTicks >= this.maxStalledTicks) {
                        // Apply a 5-second penalty cooldown before this rat can attempt building again
                        this.nextAllowedBuildTime = this.mob.level().getGameTime() + 100;

                        // Nullifying placePos immediately fails canContinueToUse(), invoking stop()
                        this.placePos = null;
                        return;
                    }

                    // Stall build timer to stay in execution state while waiting/pushing
                    this.buildTicks = this.maxBuildTicks - 5;
                    return;
                }

                // Space is clear; construct block
                SiegeInteractionHandler.constructSiegeBlock(
                        serverLevel,
                        this.placePos,
                        this.moveDir,
                        this.targetAction,
                        this.flowField
                );

                this.nextAllowedBuildTime = this.mob.level().getGameTime() + 10;

                if (this.recalculateCooldown == 0 && !this.flowField.isCalculating()) {
                    this.flowField.forceRecalculation();
                    this.recalculateCooldown = 100;
                }
            }
        }
    }

    @Override
    public void stop() {
        this.placePos = null;
        this.moveDir = null;
        this.targetAction = null;
        this.buildTicks = 0;
        this.stalledTicks = 0;
    }
}