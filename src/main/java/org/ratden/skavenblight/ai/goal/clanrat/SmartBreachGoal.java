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

public class SmartBreachGoal extends Goal implements SiegeGoal {
    private final PathfinderMob mob;
    private StandardFlowField flowField;
    private long nextAllowedBreachTime = 0;

    private int mineTicks = 0;
    private final int maxMineTicks = 20; // Tune this to adjust Skaven mining speed

    private BlockPos targetPos;
    private SiegeNode.SiegeAction targetAction;

    public SmartBreachGoal(PathfinderMob mob) {
        this.mob = mob;
        // Lock out native movement/looking so the Kinematic Lock works cleanly
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public void setFlowField(StandardFlowField flowField) {
        this.flowField = flowField;
    }

    private boolean isBreachAction(SiegeNode.SiegeAction action) {
        // Adjust this if your enum has distinct mining actions (e.g., TUNNEL, BREACH)
        return action == SiegeNode.SiegeAction.MINE;
    }

    private SiegeNode getEffectiveNode(BlockPos currentPos) {
        ServerLevel serverLevel = (ServerLevel) this.mob.level();
        SiegeNode node = this.flowField.getNextSiegeNode(serverLevel, currentPos);

        // 1. NUDGE RECOVERY: Re-acquire the node if shoved horizontally by the swarm
        if (node == null) {
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                node = this.flowField.getNextSiegeNode(serverLevel, currentPos.relative(dir));
                if (node != null) break;
            }
        }

        // Look ahead to snap to the breach target if we are close enough
        if (node != null && node.action() == SiegeNode.SiegeAction.WALK) {
            SiegeNode nextNode = this.flowField.getNextSiegeNode(serverLevel, node.pos());
            if (nextNode != null && isBreachAction(nextNode.action())) {
                if (currentPos.closerThan(nextNode.pos(), 2.5D)) {
                    return nextNode;
                }
            }
        }
        return node;
    }

    @Override
    public boolean canUse() {
        if (this.flowField == null || this.mob.level().getGameTime() < this.nextAllowedBreachTime) {
            return false;
        }

        BlockPos currentPos = this.mob.blockPosition();
        SiegeNode node = getEffectiveNode(currentPos);

        if (node != null && isBreachAction(node.action())) {
            // Ensure there is actually a block to mine
            return !this.mob.level().getBlockState(node.pos()).isAir() && currentPos.closerThan(node.pos(), 2.5D);
        }
        return false;
    }

    @Override
    public boolean canContinueToUse() {
        // Stop if the block is already broken by another rat or time runs out
        return this.mineTicks <= this.maxMineTicks && this.targetPos != null && !this.mob.level().getBlockState(this.targetPos).isAir();
    }

    @Override
    public void start() {
        this.mineTicks = 0;
        BlockPos currentPos = this.mob.blockPosition();
        SiegeNode node = getEffectiveNode(currentPos);

        if (node == null) return;

        this.targetPos = node.pos();
        this.targetAction = node.action();
    }

    @Override
    public void tick() {
        if (this.targetPos != null && this.mob.level() instanceof ServerLevel serverLevel) {

            // 2. KINEMATIC EXECUTION LOCK: Drop horizontal momentum to 0 to resist getting pushed mid-swing
            this.mob.setDeltaMovement(0, this.mob.getDeltaMovement().y, 0);

            this.mob.getLookControl().setLookAt(
                    this.targetPos.getX() + 0.5D,
                    this.targetPos.getY() + 0.5D,
                    this.targetPos.getZ() + 0.5D
            );

            if (this.mineTicks % 5 == 0) {
                this.mob.swing(InteractionHand.MAIN_HAND);
            }

            this.mineTicks++;

            if (this.mineTicks >= this.maxMineTicks) {
                // Break the block and drop items. You can hand this off to SiegeInteractionHandler if you have custom logic.
                serverLevel.destroyBlock(this.targetPos, true, this.mob);

                // Short cooldown so they immediately move on to the next flow field node
                this.nextAllowedBreachTime = this.mob.level().getGameTime() + 5;
            }
        }
    }

    @Override
    public void stop() {
        this.targetPos = null;
        this.targetAction = null;
        this.mineTicks = 0;
    }
}