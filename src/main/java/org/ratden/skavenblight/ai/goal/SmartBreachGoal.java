package org.ratden.skavenblight.ai.goal;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.ratden.skavenblight.ai.pathing.StandardFlowField;

import java.util.EnumSet;

public class SmartBreachGoal extends Goal {
    private final PathfinderMob mob;
    private StandardFlowField flowField;

    private BlockPos targetBlock = null;
    private int miningTicks = 0;
    private int maxMiningTicks = 0;

    public SmartBreachGoal(PathfinderMob mob) {
        this.mob = mob;
        // Flag.MOVE prevents the mob from walking while digging
        // Flag.LOOK forces the mob to stare at the block it's mining
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    public void setFlowField(StandardFlowField flowField) {
        this.flowField = flowField;
    }

    @Override
    public boolean canUse() {
        if (this.flowField == null) return false;

        BlockPos currentPos = this.mob.blockPosition();
        BlockPos nextFootPos = this.flowField.getBestNextNode(currentPos);

        if (nextFootPos == null) return false;

        BlockPos nextHeadPos = nextFootPos.above();

        // --- FIXED: Do not mine blocks that are meant for walking on! ---
        BlockState footState = this.mob.level().getBlockState(nextFootPos);
        boolean footBlocked = footState.blocksMotion()
                && !footState.is(net.minecraft.world.level.block.Blocks.COBBLESTONE_STAIRS)
                && !footState.is(net.minecraft.world.level.block.Blocks.COBBLESTONE);

        BlockState headState = this.mob.level().getBlockState(nextHeadPos);
        boolean headBlocked = headState.blocksMotion()
                && !headState.is(net.minecraft.world.level.block.Blocks.COBBLESTONE_STAIRS)
                && !headState.is(net.minecraft.world.level.block.Blocks.COBBLESTONE);

        if (footBlocked || headBlocked) {
            this.targetBlock = headBlocked ? nextHeadPos : nextFootPos;
            return true;
        }

        return false;
    }

    @Override
    public boolean canContinueToUse() {
        if (this.targetBlock == null || this.flowField == null || !this.mob.isAlive()) return false;

        BlockState state = this.mob.level().getBlockState(this.targetBlock);
        return state.blocksMotion(); // Keep digging until the block is gone
    }

    @Override
    public void start() {
        this.miningTicks = 0;

        BlockState state = this.mob.level().getBlockState(this.targetBlock);
        float hardness = state.getDestroySpeed(this.mob.level(), this.targetBlock);

        // Calculate breaking time. Hardness 1.5 (Stone) * 20 = 30 ticks (1.5 seconds)
        this.maxMiningTicks = (int) (hardness * 20);

        // Failsafe for instant-break blocks like tall grass
        if (this.maxMiningTicks <= 0) {
            this.maxMiningTicks = 5;
        }
    }

    @Override
    public void tick() {
        // Stare at the block
        this.mob.getLookControl().setLookAt(
                this.targetBlock.getX() + 0.5,
                this.targetBlock.getY() + 0.5,
                this.targetBlock.getZ() + 0.5
        );

        this.miningTicks++;

        // Play block breaking particles and sound every half-second
        if (this.miningTicks % 10 == 0) {
            this.mob.level().levelEvent(2001, this.targetBlock, Block.getId(this.mob.level().getBlockState(this.targetBlock)));
        }

        // Render the cracking overlay on the block (values 0-9)
        int progress = (int) ((float) this.miningTicks / this.maxMiningTicks * 10.0F);
        this.mob.level().destroyBlockProgress(this.mob.getId(), this.targetBlock, progress);

        // Break the block!
        if (this.miningTicks >= this.maxMiningTicks) {
            if (this.mob.level() instanceof ServerLevel serverLevel) {
                serverLevel.destroyBlock(this.targetBlock, true, this.mob);
                this.mob.level().destroyBlockProgress(this.mob.getId(), this.targetBlock, -1);
            }
        }
    }

    @Override
    public void stop() {
        // Clear the cracking overlay if the rat is killed or pushed away
        if (this.targetBlock != null) {
            this.mob.level().destroyBlockProgress(this.mob.getId(), this.targetBlock, -1);
        }
        this.targetBlock = null;
        this.miningTicks = 0;
    }
}