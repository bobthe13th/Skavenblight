package org.ratden.skavenblight.ai.goal.clanrat;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.ratden.skavenblight.ai.goal.SiegeGoal;
import org.ratden.skavenblight.ai.pathing.SiegeInteractionHandler;
import org.ratden.skavenblight.ai.pathing.SiegeNode;
import org.ratden.skavenblight.ai.pathing.region.RegionFlowField;
import org.ratden.skavenblight.debug.SiegeActivityLog;
import org.slf4j.Logger;

import java.util.EnumSet;

public class DeployClimbableGoal extends Goal implements SiegeGoal {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final PathfinderMob mob;
    private RegionFlowField flowField;

    private BlockPos targetWallPos;
    private Direction wallFacing;
    private ClimbState state = ClimbState.SEARCHING;

    private int actionTicks = 0;
    private int maxActionTicks = 0;
    private int stalledTicks = 0;
    private long nextAllowedStartTime = 0;
    private static final int MAX_STALLED_TICKS = 60;
    private static final long GIVE_UP_COOLDOWN_TICKS = 100;

    private enum ClimbState {
        SEARCHING,
        MINING_OVERHANG,
        PLACING_LADDER,
        DISMOUNTING
    }

    public DeployClimbableGoal(PathfinderMob mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public void setFlowField(RegionFlowField flowField) {
        this.flowField = flowField;
    }

    @Override
    public boolean canUse() {
        if (this.flowField == null || !this.mob.isAlive()) return false;
        if (this.mob.level().getGameTime() < this.nextAllowedStartTime) return false;

        // Owns BUILD_LADDER nodes specifically - TerrainEvaluator emits BUILD_LADDER for a
        // vertical shaft that has a wall to hang the ladder on (see determineMacroAction).
        // SpiralSapperGoal owns the sibling BUILD_SPIRAL case (no wall present); BuildFlowFieldGoal
        // is the generic fallback for both if these specialized goals decline.
        if (this.mob.level() instanceof ServerLevel serverLevel) {
            BlockPos pos = this.mob.blockPosition();
            SiegeNode node = this.flowField.getNextSiegeNode(serverLevel, pos);

            if (node != null && node.action() == SiegeNode.SiegeAction.BUILD_LADDER) {
                // Match the planner's own hasWall scan (TerrainEvaluator#determineMacroAction) -
                // all 4 horizontal directions, not just whichever way the mob happens to be
                // facing on arrival. A mob approaches from wherever the flow field routed it,
                // rarely the exact side the planner found a wall on; checking only
                // mob.getDirection() made this goal decline far more often than the plan
                // justified, silently pushing BUILD_LADDER nodes to BuildFlowFieldGoal's generic
                // executor instead - which places a real ladder too, but skips this goal's
                // overhang-clearing step.
                for (Direction dir : Direction.Plane.HORIZONTAL) {
                    BlockPos candidateWall = pos.relative(dir);
                    if (serverLevel.getBlockState(candidateWall).isSolidRender(serverLevel, candidateWall)
                            && !this.flowField.isTargetClaimed(candidateWall)) {
                        this.targetWallPos = candidateWall;
                        this.wallFacing = dir.getOpposite();
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    public void start() {
        this.actionTicks = 0;
        this.stalledTicks = 0;
        if (this.flowField != null) {
            this.flowField.tryClaimTarget(this.targetWallPos, this.mob);
        }
        checkOverhang();
    }

    private void checkOverhang() {
        ServerLevel level = (ServerLevel) this.mob.level();
        BlockPos headPos = this.mob.blockPosition().above();
        BlockPos overhangPos = headPos.above();

        if (!level.getBlockState(overhangPos).canBeReplaced()) {
            this.state = ClimbState.MINING_OVERHANG;
            this.maxActionTicks = SiegeInteractionHandler.calculateMiningTicks(level, overhangPos);
        } else {
            this.state = ClimbState.PLACING_LADDER;
            this.maxActionTicks = 10;
        }
    }

    @Override
    public void tick() {
        if (!(this.mob.level() instanceof ServerLevel serverLevel) || this.targetWallPos == null) return;

        BlockPos placePos = this.targetWallPos.relative(this.wallFacing);

        this.mob.getLookControl().setLookAt(
                placePos.getX() + 0.5D,
                placePos.getY() + 0.5D,
                placePos.getZ() + 0.5D
        );

        this.actionTicks++;

        switch (this.state) {
            case MINING_OVERHANG -> {
                BlockPos overhangPos = this.mob.blockPosition().above(2);
                boolean complete = SiegeActionAnimator.tickMiningAnimation(serverLevel, this.mob, overhangPos, this.actionTicks, this.maxActionTicks);

                if (complete) {
                    SiegeInteractionHandler.executeBreach(serverLevel, overhangPos, this.flowField, this.mob);
                    SiegeActionAnimator.clearMiningAnimation(serverLevel, this.mob, overhangPos);
                    if (this.flowField != null) this.flowField.forceRecalculation(overhangPos);

                    this.actionTicks = 0;
                    this.state = ClimbState.PLACING_LADDER;
                    this.maxActionTicks = 10;
                }
            }
            case PLACING_LADDER -> {
                SiegeActionAnimator.swingPeriodically(this.mob, this.actionTicks);

                if (this.actionTicks >= this.maxActionTicks) {
                    if (SiegeInteractionHandler.isSpaceClear(serverLevel, placePos, this.mob)) {
                        // Same self-entombment risk as SiegeInteractionHandler#constructSiegeBlock -
                        // this placement bypasses that method entirely (a ladder goes on the wall
                        // beside the mob, not underfoot, but placePos can still end up
                        // overlapping the mob's own hitbox depending on approach angle).
                        if (this.mob.getBoundingBox().intersects(new AABB(placePos))) {
                            LOGGER.warn("[Skavenblight] {} ({}) placing ladder at {} while its own hitbox overlaps the target - " +
                                            "risk of self-entombment. Mob pos: {}",
                                    this.mob.getClass().getSimpleName(), this.mob.getUUID().toString().substring(0, 8),
                                    placePos.toShortString(), this.mob.blockPosition().toShortString());
                        }

                        serverLevel.setBlockAndUpdate(placePos, Blocks.LADDER.defaultBlockState()
                                .setValue(LadderBlock.FACING, this.wallFacing));
                        serverLevel.levelEvent(2001, placePos, Block.getId(Blocks.LADDER.defaultBlockState()));
                        SiegeActivityLog.record(serverLevel.getGameTime(), this.mob, placePos, SiegeNode.SiegeAction.BUILD_LADDER,
                                "ladder placed", this.flowField != null ? this.flowField.getRegionId() : null);
                        if (this.flowField != null) this.flowField.forceRecalculation(placePos);

                        BlockPos targetTunnel = placePos.above().relative(this.wallFacing.getOpposite());
                        if (serverLevel.getBlockState(targetTunnel).canBeReplaced()) {
                            this.state = ClimbState.DISMOUNTING;
                            this.actionTicks = 0;
                        } else {
                            this.state = ClimbState.SEARCHING;
                        }
                        this.stalledTicks = 0;
                    } else {
                        SiegeInteractionHandler.pushOccupantsAway(serverLevel, placePos, this.mob);
                        this.stalledTicks++;

                        if (SiegeActionAnimator.stalledPastLimit(this.stalledTicks, MAX_STALLED_TICKS)) {
                            LOGGER.info("[Skavenblight] {} giving up placing ladder at {} after {} stalled ticks - space never cleared",
                                    this.mob.getClass().getSimpleName(), placePos.toShortString(), this.stalledTicks);
                            this.nextAllowedStartTime = serverLevel.getGameTime() + GIVE_UP_COOLDOWN_TICKS;
                            if (this.flowField != null) {
                                this.flowField.releaseTarget(this.targetWallPos);
                            }
                            this.targetWallPos = null;
                            this.state = ClimbState.SEARCHING;
                            return;
                        }
                        this.actionTicks = Math.max(0, this.maxActionTicks - 5);
                    }
                }
            }
            case DISMOUNTING -> {
                Vec3 forward = Vec3.atLowerCornerOf(this.wallFacing.getOpposite().getNormal());
                Vec3 impulse = forward.scale(0.4D).add(0.0D, 0.25D, 0.0D);

                this.mob.setDeltaMovement(this.mob.getDeltaMovement().add(impulse));
                this.mob.hasImpulse = true;

                this.state = ClimbState.SEARCHING;
            }
            case SEARCHING -> {}
        }
    }

    @Override
    public boolean canContinueToUse() {
        return this.targetWallPos != null && this.state != ClimbState.SEARCHING && this.mob.isAlive();
    }

    @Override
    public void stop() {
        if (this.state == ClimbState.MINING_OVERHANG) {
            SiegeActionAnimator.clearMiningAnimation(this.mob.level(), this.mob, this.mob.blockPosition().above(2));
        }
        if (this.targetWallPos != null) {
            if (this.flowField != null) {
                this.flowField.releaseTarget(this.targetWallPos);
            }
        }
        this.targetWallPos = null;
        this.state = ClimbState.SEARCHING;
        this.actionTicks = 0;
        this.stalledTicks = 0;
    }
}
