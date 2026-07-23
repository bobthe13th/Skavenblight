package org.ratden.skavenblight.ai.goal.clanrat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.Block;
import org.ratden.skavenblight.ai.goal.SiegeGoal;
import org.ratden.skavenblight.ai.pathing.SiegeInteractionHandler;
import org.ratden.skavenblight.ai.pathing.SiegeNode;
import org.ratden.skavenblight.ai.pathing.StandardFlowField;

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

        // Owns BUILD_SPIRAL nodes specifically - TerrainEvaluator emits BUILD_SPIRAL for tall
        // vertical shafts with no adjacent wall (see determineMacroAction), which need the
        // mine-ceiling / mine-ledge / build-stair sequence below rather than a single generic
        // block placement. DeployClimbableGoal owns the sibling BUILD_LADDER case (a shaft
        // with a wall to hang a ladder on); BuildFlowFieldGoal is the generic fallback for
        // both if these specialized goals decline.
        if (this.mob.level() instanceof ServerLevel serverLevel) {
            SiegeNode node = this.flowField.getNextSiegeNode(serverLevel, pos);
            if (node != null && node.action() == SiegeNode.SiegeAction.BUILD_SPIRAL) {
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
        if (this.actionTicks % 10 == 0) {
            level.levelEvent(2001, this.currentTarget, Block.getId(level.getBlockState(this.currentTarget)));
        }

        boolean complete = SiegeActionAnimator.tickMiningAnimation(level, this.mob, this.currentTarget, this.actionTicks, this.maxActionTicks);

        if (complete) {
            SiegeInteractionHandler.executeBreach(level, this.currentTarget, this.flowField);
            SiegeActionAnimator.clearMiningAnimation(level, this.mob, this.currentTarget);

            if (this.state == SapperState.MINE_CEILING) {
                this.transitionTo(SapperState.MINE_LEDGE);
            } else {
                this.transitionTo(SapperState.BUILD_STAIR);
            }
        }
    }

    private void handleBuildingTick(ServerLevel level) {
        SiegeActionAnimator.swingPeriodically(this.mob, this.actionTicks);

        if (this.actionTicks >= this.maxActionTicks) {
            if (SiegeInteractionHandler.isSpaceClear(level, this.currentTarget, this.mob)) {
                SiegeInteractionHandler.constructSiegeBlock(
                        level,
                        this.currentTarget,
                        this.currentFacing.getOpposite(),
                        SiegeNode.SiegeAction.BUILD_STAIR,
                        this.flowField
                );
                this.flowField.forceRecalculation();
                this.transitionTo(SapperState.WAITING);
            } else {
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
                this.currentTarget = headPos.above();
                this.maxActionTicks = SiegeInteractionHandler.calculateMiningTicks((ServerLevel) this.mob.level(), this.currentTarget);
            }
            case MINE_LEDGE -> {
                this.currentTarget = headPos.relative(this.currentFacing).above();
                this.maxActionTicks = SiegeInteractionHandler.calculateMiningTicks((ServerLevel) this.mob.level(), this.currentTarget);
            }
            case BUILD_STAIR -> {
                this.currentTarget = headPos.relative(this.currentFacing);
                this.maxActionTicks = 15;
            }
            case WAITING -> {
                this.currentTarget = null;
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
            SiegeActionAnimator.clearMiningAnimation(this.mob.level(), this.mob, this.currentTarget);
        }
        this.currentTarget = null;
        this.state = SapperState.WAITING;
        this.actionTicks = 0;
    }
}
