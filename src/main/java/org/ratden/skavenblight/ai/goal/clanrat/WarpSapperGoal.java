package org.ratden.skavenblight.ai.goal.clanrat;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.ratden.skavenblight.ai.goal.SiegeGoal;
import org.ratden.skavenblight.ai.pathing.SiegeInteractionHandler;
import org.ratden.skavenblight.ai.pathing.SiegeNode;
import org.ratden.skavenblight.ai.pathing.StandardFlowField;

import java.util.EnumSet;

public class WarpSapperGoal extends Goal implements SiegeGoal {
    private final PathfinderMob mob;
    private StandardFlowField flowField;

    private BlockPos targetMinePos;
    private SapperState state = SapperState.SEARCHING;

    private int actionTicks = 0;
    // Blocks that take longer than ~6 seconds (120 ticks) to mine will trigger explosives
    private static final int SAPPER_THRESHOLD_TICKS = 120;

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
    public void setFlowField(StandardFlowField flowField) {
        this.flowField = flowField;
    }

    @Override
    public boolean canUse() {
        if (this.flowField == null || !this.mob.isAlive()) return false;

        if (this.mob.level() instanceof ServerLevel serverLevel) {
            BlockPos currentPos = this.mob.blockPosition();
            SiegeNode node = this.flowField.getNextSiegeNode(serverLevel, currentPos);

            // Check if the next action is a MINE action
            if (node != null && node.action() == SiegeNode.SiegeAction.MINE) {
                int requiredTicks = SiegeInteractionHandler.calculateMiningTicks(serverLevel, node.pos());

                // If it's a highly durable block (but not unbreakable/bedrock), go loud.
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
                if (dist < 4.0D) { // Within 2 blocks
                    this.state = SapperState.PLANTING_CHARGE;
                    this.actionTicks = 0;
                } else {
                    // Move closer if slightly out of range
                    this.mob.getNavigation().moveTo(this.targetMinePos.getX(), this.targetMinePos.getY(), this.targetMinePos.getZ(), 1.2D);
                }
            }
            case PLANTING_CHARGE -> {
                this.mob.getNavigation().stop();
                this.actionTicks++;

                if (this.actionTicks % 5 == 0) {
                    this.mob.swing(InteractionHand.MAIN_HAND);
                }

                // 1-second planting animation
                if (this.actionTicks >= 20) {
                    plantAndIgniteExplosive(serverLevel);
                    this.state = SapperState.FLEEING;
                    this.actionTicks = 0;
                }
            }
            case FLEEING -> {
                this.actionTicks++;
                // Skaven are cowardly—run away from the bomb!
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
        // Find an air block adjacent to the target to spawn the TNT
        BlockPos spawnPos = this.targetMinePos.relative(this.mob.getDirection().getOpposite());
        if (!level.getBlockState(spawnPos).canBeReplaced()) {
            spawnPos = this.targetMinePos.above(); // Fallback to above
        }

        // Using Vanilla TNT as a proxy for the Warp Charge
        PrimedTnt warpCharge = new PrimedTnt(level, spawnPos.getX() + 0.5D, spawnPos.getY(), spawnPos.getZ() + 0.5D, this.mob);
        warpCharge.setFuse(40); // 2-second fuse for frantic fleeing
        level.addFreshEntity(warpCharge);

        level.playSound(null, spawnPos, SoundEvents.TNT_PRIMED, SoundSource.HOSTILE, 1.0F, 1.2F);

        // Force the flow field to recalculate soon so the horde routes through the new breach
        this.flowField.forceRecalculation();
    }

    @Override
    public boolean canContinueToUse() {
        // Stop fleeing after 3 seconds (60 ticks), resetting the goal
        return this.targetMinePos != null && this.mob.isAlive() && (this.state != SapperState.FLEEING || this.actionTicks < 60);
    }

    @Override
    public void stop() {
        this.targetMinePos = null;
        this.state = SapperState.SEARCHING;
        this.actionTicks = 0;
    }
}