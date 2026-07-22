package org.ratden.skavenblight.ai.goal;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.ratden.skavenblight.ai.pathing.StandardFlowField;
import org.ratden.skavenblight.ai.pathing.SiegeNode;

import java.util.EnumSet;

public class BuildFlowFieldGoal extends Goal {
    private final PathfinderMob mob;
    private StandardFlowField flowField;
    private long nextAllowedBuildTime = 0;

    private int buildTicks = 0;
    private final int maxBuildTicks = 15;
    private BlockPos placePos;
    private BlockState blockToPlace;
    private int recalculateCooldown = 0;

    public BuildFlowFieldGoal(PathfinderMob mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    public void setFlowField(StandardFlowField flowField) {
        this.flowField = flowField;
        if (this.flowField != null) {
            System.out.println("[BuildFlowFieldGoal] Assigned FlowField Hash to Mob " + this.mob.getUUID() + ": " + System.identityHashCode(this.flowField));
        }
    }

    // Helper method to check for ALL valid building actions
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

        if (node == null) return false;

        // Using the helper method to allow Pillars and Landings to trigger the goal
        if (isBuildAction(node.action())) {
            BlockPos targetPlacePos = node.pos();
            return this.mob.level().getBlockState(targetPlacePos).canBeReplaced() && currentPos.closerThan(targetPlacePos, 2.5D);
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
        BlockPos currentPos = this.mob.blockPosition();
        SiegeNode node = getEffectiveNode(currentPos);

        if (node == null) return;

        this.placePos = node.pos();

        if (node.action() == SiegeNode.SiegeAction.BUILD_STAIR) {
            int dx = this.placePos.getX() - currentPos.getX();
            int dz = this.placePos.getZ() - currentPos.getZ();

            Direction moveDir = this.mob.getDirection();
            if (Math.abs(dx) > Math.abs(dz)) {
                moveDir = dx > 0 ? Direction.EAST : Direction.WEST;
            } else if (dz != 0) {
                moveDir = dz > 0 ? Direction.SOUTH : Direction.NORTH;
            }

            this.blockToPlace = Blocks.COBBLESTONE_STAIRS.defaultBlockState()
                    .setValue(StairBlock.FACING, moveDir);
        } else {
            // Pillars, Bridges, and Landings will correctly fall through to Cobblestone
            this.blockToPlace = Blocks.COBBLESTONE.defaultBlockState();
        }
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

            // Process the cooldown
            if (this.recalculateCooldown > 0) {
                this.recalculateCooldown--;
            }

            if (this.buildTicks >= this.maxBuildTicks) {
                this.mob.level().setBlockAndUpdate(this.placePos, this.blockToPlace);
                this.nextAllowedBuildTime = this.mob.level().getGameTime() + 10;

                // 1. Check cooldown and calculating state before requesting a new map
                if (this.recalculateCooldown == 0 && !this.flowField.isCalculating()) {
                    this.flowField.forceRecalculation();
                    this.recalculateCooldown = 100; // 5-second cooldown at 20 TPS
                    System.out.println("[BuildFlowFieldGoal] Triggered Recalculation! Mob's FlowField Hash: " + System.identityHashCode(this.flowField));
                }
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