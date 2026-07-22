package org.ratden.skavenblight.ai.goal.clanrat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.phys.Vec3;
import org.ratden.skavenblight.ai.goal.SiegeGoal;
import org.ratden.skavenblight.ai.pathing.SiegeInteractionHandler;
import org.ratden.skavenblight.ai.pathing.SiegeNode;
import org.ratden.skavenblight.ai.pathing.StandardFlowField;

import java.util.EnumSet;

public class DeployClimbableGoal extends Goal implements SiegeGoal {
    private final PathfinderMob mob;
    private StandardFlowField flowField;

    private BlockPos targetWallPos;
    private Direction wallFacing;
    private ClimbState state = ClimbState.SEARCHING;

    private int actionTicks = 0;
    private int maxActionTicks = 0;

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
    public void setFlowField(StandardFlowField flowField) {
        this.flowField = flowField;
    }

    @Override
    public boolean canUse() {
        if (this.flowField == null || !this.mob.isAlive()) return false;

        if (this.mob.level() instanceof ServerLevel serverLevel) {
            BlockPos pos = this.mob.blockPosition();
            SiegeNode node = this.flowField.getNextSiegeNode(serverLevel, pos);

            // Assuming we add BUILD_LADDER to SiegeNode.SiegeAction
            if (node != null && node.action() == SiegeNode.SiegeAction.BUILD_PILLAR) { // Proxy until BUILD_LADDER exists
                Direction lookDir = this.mob.getDirection();
                BlockPos blockInFront = pos.relative(lookDir);

                // If there is a solid wall in front of us, we want to climb it
                if (serverLevel.getBlockState(blockInFront).isSolidRender(serverLevel, blockInFront)) {
                    this.targetWallPos = blockInFront;
                    this.wallFacing = lookDir.getOpposite(); // Ladder attaches facing AWAY from the wall
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void start() {
        this.actionTicks = 0;
        checkOverhang();
    }

    private void checkOverhang() {
        ServerLevel level = (ServerLevel) this.mob.level();
        BlockPos headPos = this.mob.blockPosition().above();
        BlockPos overhangPos = headPos.above(); // 2 blocks above feet

        if (!level.getBlockState(overhangPos).canBeReplaced()) {
            this.state = ClimbState.MINING_OVERHANG;
            this.maxActionTicks = SiegeInteractionHandler.calculateMiningTicks(level, overhangPos); //[cite: 27]
        } else {
            this.state = ClimbState.PLACING_LADDER;
            this.maxActionTicks = 10;
        }
    }

    @Override
    public void tick() {
        if (!(this.mob.level() instanceof ServerLevel serverLevel) || this.targetWallPos == null) return;

        BlockPos placePos = this.targetWallPos.relative(this.wallFacing); // Air block in front of the wall

        this.mob.getLookControl().setLookAt(
                placePos.getX() + 0.5D,
                placePos.getY() + 0.5D,
                placePos.getZ() + 0.5D
        );

        this.actionTicks++;

        switch (this.state) {
            case MINING_OVERHANG -> {
                BlockPos overhangPos = this.mob.blockPosition().above(2);
                if (this.actionTicks % 5 == 0) this.mob.swing(InteractionHand.MAIN_HAND);

                int progress = (int) ((float) this.actionTicks / this.maxActionTicks * 10.0F);
                serverLevel.destroyBlockProgress(this.mob.getId(), overhangPos, progress);

                if (this.actionTicks >= this.maxActionTicks) {
                    SiegeInteractionHandler.executeBreach(serverLevel, overhangPos, this.flowField); //[cite: 27]
                    serverLevel.destroyBlockProgress(this.mob.getId(), overhangPos, -1);

                    // Re-evaluate to see if we can place the ladder now
                    this.actionTicks = 0;
                    this.state = ClimbState.PLACING_LADDER;
                    this.maxActionTicks = 10;
                }
            }
            case PLACING_LADDER -> {
                if (this.actionTicks % 5 == 0) this.mob.swing(InteractionHand.MAIN_HAND);

                if (this.actionTicks >= this.maxActionTicks) {
                    if (SiegeInteractionHandler.isSpaceClear(serverLevel, placePos)) { //[cite: 27]
                        serverLevel.setBlockAndUpdate(placePos, Blocks.LADDER.defaultBlockState()
                                .setValue(LadderBlock.FACING, this.wallFacing));
                        serverLevel.levelEvent(2001, placePos, net.minecraft.world.level.block.Block.getId(Blocks.LADDER.defaultBlockState()));

                        // Check if we need to dismount into a hole at this Y-level
                        BlockPos targetTunnel = placePos.above().relative(this.wallFacing.getOpposite());
                        if (serverLevel.getBlockState(targetTunnel).canBeReplaced()) {
                            this.state = ClimbState.DISMOUNTING;
                            this.actionTicks = 0;
                        } else {
                            this.state = ClimbState.SEARCHING; // Move up and repeat
                        }
                    } else {
                        SiegeInteractionHandler.pushOccupantsAway(serverLevel, placePos, this.mob); //[cite: 27]
                        this.actionTicks = Math.max(0, this.maxActionTicks - 5);
                    }
                }
            }
            case DISMOUNTING -> {
                // The Ledge Grab: Apply physical impulse to throw the rat into the tunnel
                Vec3 forward = Vec3.atLowerCornerOf(this.wallFacing.getOpposite().getNormal());
                Vec3 impulse = forward.scale(0.4D).add(0.0D, 0.25D, 0.0D); // Forward and slightly up

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
            this.mob.level().destroyBlockProgress(this.mob.getId(), this.mob.blockPosition().above(2), -1);
        }
        this.targetWallPos = null;
        this.state = ClimbState.SEARCHING;
        this.actionTicks = 0;
    }
}