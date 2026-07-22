package org.ratden.skavenblight.ai.goal.clanrat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.Block;
import org.ratden.skavenblight.ai.goal.SiegeGoal;
import org.ratden.skavenblight.ai.pathing.SiegeInteractionHandler;
import org.ratden.skavenblight.ai.pathing.StandardFlowField;
import org.ratden.skavenblight.ai.pathing.SiegeNode;

import java.util.EnumSet;

public class SpiralSapperGoal extends Goal implements SiegeGoal {
    private final PathfinderMob mob;
    private StandardFlowField flowField;

    private BlockPos currentTarget;
    private SapperState state;
    private Direction currentFacing;

    private int actionTicks = 0;
    private int maxActionTicks = 0;

    private enum SapperState {
        MINE_CEILING,
        MINE_LEDGE,
        BUILD_STAIR,
        WAITING
    }

    public SpiralSapperGoal(PathfinderMob mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public void setFlowField(StandardFlowField flowField) {
        this.flowField = flowField;
    }

    @Override
    public boolean canUse() {
        if (this.flowField == null || !this.mob.isAlive()) return false;

        BlockPos pos = this.mob.blockPosition();

        // For now, we piggyback on BUILD_PILLAR to trigger vertical sapping.
        // Later, we will add a dedicated SPIRAL_MINE action to SiegeNode.
        if (this.mob.level() instanceof ServerLevel serverLevel) {
            SiegeNode node = this.flowField.getNextSiegeNode(serverLevel, pos);
            if (node != null && node.action() == SiegeNode.SiegeAction.BUILD_PILLAR) {
                // Only trigger if we are entirely boxed in or hitting a solid ceiling
                return this.mob.level().getBlockState(pos.above(2)).blocksMotion();
            }
        }
        return false;
    }

    @Override
    public void start() {
        this.currentFacing = this.mob.getDirection();
        this.transitionTo(SapperState.MINE_CEILING);
    }

    @Override
    public void tick() {
        if (!(this.mob.level() instanceof ServerLevel serverLevel) || this.currentTarget == null) {
            return;
        }

        this.mob.getLookControl().setLookAt(
                this.currentTarget.getX() + 0.5D,
                this.currentTarget.getY() + 0.5D,
                this.currentTarget.getZ() + 0.5D
        );

        this.actionTicks++;

        if (this.state == SapperState.MINE_CEILING || this.state == SapperState.MINE_LEDGE) {
            handleMiningTick(serverLevel);
        } else if (this.state == SapperState.BUILD_STAIR) {
            handleBuildingTick(serverLevel);
        }
    }

    private void handleMiningTick(ServerLevel level) {
        if (this.actionTicks % 5 == 0) this.mob.swing(InteractionHand.MAIN_HAND);
        if (this.actionTicks % 10 == 0) {
            level.levelEvent(2001, this.currentTarget, Block.getId(level.getBlockState(this.currentTarget)));
        }

        int progress = (int) ((float) this.actionTicks / this.maxActionTicks * 10.0F);
        level.destroyBlockProgress(this.mob.getId(), this.currentTarget, progress);

        if (this.actionTicks >= this.maxActionTicks) {
            SiegeInteractionHandler.executeBreach(level, this.currentTarget, this.flowField);
            level.destroyBlockProgress(this.mob.getId(), this.currentTarget, -1);

            if (this.state == SapperState.MINE_CEILING) {
                this.transitionTo(SapperState.MINE_LEDGE);
            } else {
                this.transitionTo(SapperState.BUILD_STAIR);
            }
        }
    }

    private void handleBuildingTick(ServerLevel level) {
        if (this.actionTicks % 5 == 0) this.mob.swing(InteractionHand.MAIN_HAND);

        if (this.actionTicks >= this.maxActionTicks) {
            // Safety check: Don't place a block inside another entity
            if (SiegeInteractionHandler.isSpaceClear(level, this.currentTarget)) {
                SiegeInteractionHandler.constructSiegeBlock(
                        level,
                        this.currentTarget,
                        this.currentFacing.getOpposite(), // Stairs face opposite of travel
                        SiegeNode.SiegeAction.BUILD_STAIR,
                        this.flowField
                );
                this.flowField.forceRecalculation();
                this.transitionTo(SapperState.WAITING);
            } else {
                // Clear space if blocked
                SiegeInteractionHandler.pushOccupantsAway(level, this.currentTarget, this.mob);
                this.actionTicks = Math.max(0, this.maxActionTicks - 10);
            }
        }
    }

    private void transitionTo(SapperState nextState) {
        this.state = nextState;
        this.actionTicks = 0;
        BlockPos headPos = this.mob.blockPosition().above();

        switch (nextState) {
            case MINE_CEILING -> {
                this.currentTarget = headPos.above(); // 2 blocks above feet
                this.maxActionTicks = SiegeInteractionHandler.calculateMiningTicks((ServerLevel)this.mob.level(), this.currentTarget);
            }
            case MINE_LEDGE -> {
                // Look ahead 1 block in our current spiral direction, and 1 block up
                this.currentTarget = headPos.relative(this.currentFacing).above();
                this.maxActionTicks = SiegeInteractionHandler.calculateMiningTicks((ServerLevel)this.mob.level(), this.currentTarget);
            }
            case BUILD_STAIR -> {
                // Place stair where we just mined the ledge
                this.currentTarget = headPos.relative(this.currentFacing);
                this.maxActionTicks = 15; // Standard build animation time
            }
            case WAITING -> {
                this.currentTarget = null;
                // Rotate clockwise for the next iteration of the spiral
                this.currentFacing = this.currentFacing.getClockWise();
            }
        }
    }

    @Override
    public boolean canContinueToUse() {
        return this.state != SapperState.WAITING && this.currentTarget != null && this.mob.isAlive();
    }

    @Override
    public void stop() {
        if (this.currentTarget != null) {
            this.mob.level().destroyBlockProgress(this.mob.getId(), this.currentTarget, -1);
        }
        this.currentTarget = null;
        this.state = SapperState.WAITING;
        this.actionTicks = 0;
    }
}