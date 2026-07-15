package org.ratden.skavenblight.ai.goal;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.ratden.skavenblight.ai.pathing.StandardFlowField;
import org.ratden.skavenblight.ai.pathing.SiegeNode;

import java.util.EnumSet;

public class SmartBreachGoal extends Goal {
    private final PathfinderMob mob;
    private StandardFlowField flowField;

    private BlockPos targetBlock = null;
    private int miningTicks = 0;
    private int maxMiningTicks = 0;

    public SmartBreachGoal(PathfinderMob mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    public void setFlowField(StandardFlowField flowField) {
        this.flowField = flowField;
        // --- DIAGNOSTIC LOG ---
        if (this.flowField != null) {
            System.out.println("[SmartBreachGoal] Assigned FlowField Hash to Mob " + this.mob.getUUID() + ": " + System.identityHashCode(this.flowField));
        }
    }

    private SiegeNode getEffectiveNode(BlockPos currentPos) {
        ServerLevel serverLevel = (ServerLevel) this.mob.level();
        SiegeNode node = this.flowField.getNextSiegeNode(serverLevel, currentPos);

        if (node != null && node.action() == SiegeNode.SiegeAction.WALK) {
            SiegeNode nextNode = this.flowField.getNextSiegeNode(serverLevel, node.pos());
            if (nextNode != null && nextNode.action() == SiegeNode.SiegeAction.MINE) {
                if (currentPos.closerThan(nextNode.pos(), 2.5D)) {
                    return nextNode;
                }
            }
        }
        return node;
    }

    @Override
    public boolean canUse() {
        if (this.flowField == null) return false;

        BlockPos pos = this.mob.blockPosition();
        SiegeNode node = getEffectiveNode(pos);

        if (node != null && node.action() == SiegeNode.SiegeAction.MINE) {
            this.targetBlock = node.pos();
            return this.mob.level().getBlockState(this.targetBlock).blocksMotion();
        }

        return false;
    }

    @Override
    public boolean canContinueToUse() {
        if (this.targetBlock == null || this.flowField == null || !this.mob.isAlive()) return false;
        return this.mob.level().getBlockState(this.targetBlock).blocksMotion();
    }

    @Override
    public void start() {
        this.miningTicks = 0;
        BlockState state = this.mob.level().getBlockState(this.targetBlock);
        float hardness = state.getDestroySpeed(this.mob.level(), this.targetBlock);

        this.maxMiningTicks = (int) (hardness * 20);
        if (this.maxMiningTicks <= 0) {
            this.maxMiningTicks = 5;
        }
    }

    @Override
    public void tick() {
        this.mob.getLookControl().setLookAt(
                this.targetBlock.getX() + 0.5D,
                this.targetBlock.getY() + 0.5D,
                this.targetBlock.getZ() + 0.5D
        );

        this.miningTicks++;

        if (this.miningTicks % 5 == 0) {
            this.mob.swing(InteractionHand.MAIN_HAND);
        }
        if (this.miningTicks % 10 == 0) {
            this.mob.level().levelEvent(2001, this.targetBlock, Block.getId(this.mob.level().getBlockState(this.targetBlock)));
        }

        int progress = (int) ((float) this.miningTicks / this.maxMiningTicks * 10.0F);
        this.mob.level().destroyBlockProgress(this.mob.getId(), this.targetBlock, progress);

        if (this.miningTicks >= this.maxMiningTicks) {
            if (this.mob.level() instanceof ServerLevel serverLevel) {
                serverLevel.destroyBlock(this.targetBlock, true, this.mob);
                this.mob.level().destroyBlockProgress(this.mob.getId(), this.targetBlock, -1);

                this.flowField.forceRecalculation();
                // --- DIAGNOSTIC LOG ---
                System.out.println("[SmartBreachGoal] Triggered Recalculation! Mob's FlowField Hash: " + System.identityHashCode(this.flowField));
            }
        }
    }

    @Override
    public void stop() {
        if (this.targetBlock != null) {
            this.mob.level().destroyBlockProgress(this.mob.getId(), this.targetBlock, -1);
        }
        this.targetBlock = null;
        this.miningTicks = 0;
    }
}