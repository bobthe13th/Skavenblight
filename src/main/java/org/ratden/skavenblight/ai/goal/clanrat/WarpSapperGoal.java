package org.ratden.skavenblight.ai.goal.clanrat;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.phys.Vec3;
import org.ratden.skavenblight.ai.goal.SiegeGoal;
import org.ratden.skavenblight.ai.pathing.SiegeInteractionHandler;
import org.ratden.skavenblight.ai.pathing.SiegeNode;
import org.ratden.skavenblight.ai.pathing.region.RegionFlowField;
import org.slf4j.Logger;

import java.util.EnumSet;

public class WarpSapperGoal extends Goal implements SiegeGoal {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final PathfinderMob mob;
    private RegionFlowField flowField;

    private BlockPos targetMinePos;
    private SapperState state = SapperState.SEARCHING;

    private int actionTicks = 0;
    private long nextAllowedStartTime = 0;
    private static final int SAPPER_THRESHOLD_TICKS = 120;
    // APPROACHING has no natural completion signal other than distance - unlike
    // PLANTING_CHARGE/FLEEING, which are tick-count-bound, a mob that can never actually reach
    // within 4 blocks of targetMinePos (blocked path, unreachable position) would otherwise
    // call moveTo every tick forever. actionTicks is unused during APPROACHING today (reset to
    // 0 on entry into PLANTING_CHARGE/FLEEING), so it doubles as this phase's stall counter.
    private static final int MAX_APPROACH_TICKS = 200;
    private static final long GIVE_UP_COOLDOWN_TICKS = 100;

    private enum SapperState {
        SEARCHING,
        APPROACHING,
        PLANTING_CHARGE,
        FLEEING
    }

    public WarpSapperGoal(PathfinderMob mob) {
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

        if (this.mob.level() instanceof ServerLevel serverLevel) {
            BlockPos currentPos = this.mob.blockPosition();
            SiegeNode node = this.flowField.getNextSiegeNode(serverLevel, currentPos);

            if (node != null && node.action() == SiegeNode.SiegeAction.MINE
                    && !this.flowField.isTargetClaimed(node.pos())) {
                int requiredTicks = SiegeInteractionHandler.calculateMiningTicks(serverLevel, node.pos());

                if (requiredTicks > SAPPER_THRESHOLD_TICKS && requiredTicks < 10000) {
                    this.targetMinePos = node.pos();
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void start() {
        this.state = SapperState.APPROACHING;
        this.actionTicks = 0;
        if (this.flowField != null) {
            this.flowField.tryClaimTarget(this.targetMinePos, this.mob);
        }
    }

    @Override
    public void tick() {
        if (!(this.mob.level() instanceof ServerLevel serverLevel) || this.targetMinePos == null) {
            return;
        }

        switch (this.state) {
            case APPROACHING -> {
                this.mob.getLookControl().setLookAt(
                        this.targetMinePos.getX() + 0.5D,
                        this.targetMinePos.getY() + 0.5D,
                        this.targetMinePos.getZ() + 0.5D
                );

                double dist = this.mob.distanceToSqr(Vec3.atCenterOf(this.targetMinePos));
                if (dist < 4.0D) {
                    this.state = SapperState.PLANTING_CHARGE;
                    this.actionTicks = 0;
                } else {
                    this.actionTicks++;
                    if (this.actionTicks >= MAX_APPROACH_TICKS) {
                        LOGGER.info("[Skavenblight] {} giving up approaching warp-charge target {} after {} ticks - never got within range",
                                this.mob.getClass().getSimpleName(), this.targetMinePos.toShortString(), this.actionTicks);
                        this.nextAllowedStartTime = this.mob.level().getGameTime() + GIVE_UP_COOLDOWN_TICKS;
                        if (this.flowField != null) {
                            this.flowField.releaseTarget(this.targetMinePos);
                        }
                        this.targetMinePos = null;
                        this.state = SapperState.SEARCHING;
                        return;
                    }
                    this.mob.getNavigation().moveTo(this.targetMinePos.getX(), this.targetMinePos.getY(), this.targetMinePos.getZ(), 1.2D);
                }
            }
            case PLANTING_CHARGE -> {
                this.mob.getNavigation().stop();
                this.actionTicks++;

                SiegeActionAnimator.swingPeriodically(this.mob, this.actionTicks);

                if (this.actionTicks >= 20) {
                    plantAndIgniteExplosive(serverLevel);
                    this.state = SapperState.FLEEING;
                    this.actionTicks = 0;
                }
            }
            case FLEEING -> {
                this.actionTicks++;
                if (this.actionTicks == 1) {
                    Vec3 fleeVector = this.mob.position().subtract(Vec3.atCenterOf(this.targetMinePos)).normalize().scale(5.0D);
                    BlockPos fleePos = BlockPos.containing(this.mob.position().add(fleeVector));
                    this.mob.getNavigation().moveTo(fleePos.getX(), fleePos.getY(), fleePos.getZ(), 1.5D);
                }
            }
            case SEARCHING -> {}
        }
    }

    private void plantAndIgniteExplosive(ServerLevel level) {
        BlockPos spawnPos = this.targetMinePos.relative(this.mob.getDirection().getOpposite());
        if (!level.getBlockState(spawnPos).canBeReplaced()) {
            spawnPos = this.targetMinePos.above();
        }

        PrimedTnt warpCharge = new PrimedTnt(level, spawnPos.getX() + 0.5D, spawnPos.getY(), spawnPos.getZ() + 0.5D, this.mob);
        warpCharge.setFuse(40);
        level.addFreshEntity(warpCharge);

        level.playSound(null, spawnPos, SoundEvents.TNT_PRIMED, SoundSource.HOSTILE, 1.0F, 1.2F);

        // targetMinePos, not spawnPos: the TNT hasn't detonated yet (40-tick fuse), so this is
        // marking the eventual blast site dirty a little early rather than late - still the
        // right chunk, unlike the region's own unrelated local target the old no-arg version used.
        this.flowField.forceRecalculation(this.targetMinePos);
    }

    @Override
    public boolean canContinueToUse() {
        return this.targetMinePos != null && this.mob.isAlive() && (this.state != SapperState.FLEEING || this.actionTicks < 60);
    }

    @Override
    public void stop() {
        if (this.targetMinePos != null) {
            if (this.flowField != null) {
                this.flowField.releaseTarget(this.targetMinePos);
            }
        }
        this.targetMinePos = null;
        this.state = SapperState.SEARCHING;
        this.actionTicks = 0;
    }
}
