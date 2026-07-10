package org.ratden.skavenblight.entity.custom.wolfCat;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.state.BlockState;
import org.ratden.skavenblight.block.ModBlocks;
import org.ratden.skavenblight.world.NexusTracker;

import java.util.EnumSet;

public class BreakNexusObstructionGoal extends Goal {

    private static final int SEARCH_RANGE_XZ = 3;
    private static final int SEARCH_RANGE_UP = 2;
    private static final int SEARCH_RANGE_DOWN = 0;

    private static final int MIN_SEARCH_COOLDOWN = 20;
    private static final int RANDOM_SEARCH_COOLDOWN = 40;

    private static final int BREAK_TICKS_REQUIRED = 30;
    private static final int REPATH_INTERVAL = 15;

    private static final double BREAK_DISTANCE_SQR = 5.0D;
    private static final double MAX_TARGET_DISTANCE_SQR = 25.0D;

    private final PathfinderMob mob;
    private final double speedModifier;

    private BlockPos nexusPos;
    private BlockPos targetBlockPos;

    private int searchCooldown;
    private int breakTicks;
    private int repathCooldown;

    public BreakNexusObstructionGoal(
            PathfinderMob mob,
            double speedModifier
    ) {
        this.mob = mob;
        this.speedModifier = speedModifier;

        this.searchCooldown = mob.getRandom().nextInt(RANDOM_SEARCH_COOLDOWN + 1);
        this.breakTicks = 0;
        this.repathCooldown = 0;

        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!(mob.level() instanceof ServerLevel level)) {
            return false;
        }

        if (searchCooldown > 0) {
            searchCooldown--;
            return false;
        }

        if (!NexusTracker.hasActiveNexus(level)) {
            resetSearchCooldown();
            return false;
        }

        BlockPos activeNexusPos = NexusTracker.getActiveNexusPos(level);

        if (activeNexusPos == null) {
            resetSearchCooldown();
            return false;
        }

        this.nexusPos = activeNexusPos.immutable();
        this.targetBlockPos = findBestObstructionBlock(level, nexusPos);

        if (targetBlockPos == null) {
            resetSearchCooldown();
            return false;
        }

        this.breakTicks = 0;
        this.repathCooldown = 0;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (!(mob.level() instanceof ServerLevel level)) {
            return false;
        }

        if (targetBlockPos == null) {
            return false;
        }

        return isBreakableObstruction(level, targetBlockPos);
    }

    @Override
    public void start() {
        moveToTargetBlock();
    }

    @Override
    public void tick() {
        if (!(mob.level() instanceof ServerLevel level)) {
            return;
        }

        if (targetBlockPos == null) {
            return;
        }

        mob.getLookControl().setLookAt(
                targetBlockPos.getX() + 0.5D,
                targetBlockPos.getY() + 0.5D,
                targetBlockPos.getZ() + 0.5D
        );

        double distanceToBlockSqr = mob.distanceToSqr(
                targetBlockPos.getX() + 0.5D,
                targetBlockPos.getY() + 0.5D,
                targetBlockPos.getZ() + 0.5D
        );

        if (distanceToBlockSqr <= BREAK_DISTANCE_SQR) {
            breakTicks++;

            if (breakTicks >= BREAK_TICKS_REQUIRED) {
                level.destroyBlock(targetBlockPos, true, mob);
                clearTarget();
                resetSearchCooldown();
            }

            return;
        }

        if (distanceToBlockSqr > MAX_TARGET_DISTANCE_SQR) {
            clearTarget();
            resetSearchCooldown();
            return;
        }

        if (repathCooldown > 0) {
            repathCooldown--;
            return;
        }

        moveToTargetBlock();
    }

    @Override
    public void stop() {
        clearTarget();
        mob.getNavigation().stop();
    }

    private BlockPos findBestObstructionBlock(
            ServerLevel level,
            BlockPos nexusPos
    ) {
        BlockPos mobPos = mob.blockPosition();

        double mobDistanceToNexusSqr = mob.distanceToSqr(
                nexusPos.getX() + 0.5D,
                nexusPos.getY() + 0.5D,
                nexusPos.getZ() + 0.5D
        );

        BlockPos bestPos = null;
        double bestScore = Double.MAX_VALUE;

        for (BlockPos candidatePos : BlockPos.betweenClosed(
                mobPos.offset(-SEARCH_RANGE_XZ, -SEARCH_RANGE_DOWN, -SEARCH_RANGE_XZ),
                mobPos.offset(SEARCH_RANGE_XZ, SEARCH_RANGE_UP, SEARCH_RANGE_XZ)
        )) {
            BlockPos immutableCandidate = candidatePos.immutable();

            if (!isBreakableObstruction(level, immutableCandidate)) {
                continue;
            }

            if (!isGenerallyTowardNexus(mobPos, immutableCandidate, nexusPos)) {
                continue;
            }

            double candidateDistanceToNexusSqr = distanceSqr(
                    immutableCandidate,
                    nexusPos
            );

            if (candidateDistanceToNexusSqr > mobDistanceToNexusSqr + 4.0D) {
                continue;
            }

            double candidateDistanceToMobSqr = distanceSqr(
                    immutableCandidate,
                    mobPos
            );

            double score = candidateDistanceToMobSqr
                    + candidateDistanceToNexusSqr * 0.05D;

            if (score < bestScore) {
                bestScore = score;
                bestPos = immutableCandidate;
            }
        }

        return bestPos;
    }

    private boolean isBreakableObstruction(
            ServerLevel level,
            BlockPos pos
    ) {
        BlockState blockState = level.getBlockState(pos);

        if (blockState.isAir()) {
            return false;
        }

        if (blockState.is(ModBlocks.WARPSTONE_NEXUS.get())) {
            return false;
        }

        if (blockState.hasBlockEntity()) {
            return false;
        }

        return blockState.getDestroySpeed(level, pos) >= 0.0F;
    }

    private boolean isGenerallyTowardNexus(
            BlockPos mobPos,
            BlockPos candidatePos,
            BlockPos nexusPos
    ) {
        double toCandidateX = candidatePos.getX() - mobPos.getX();
        double toCandidateZ = candidatePos.getZ() - mobPos.getZ();

        double toNexusX = nexusPos.getX() - mobPos.getX();
        double toNexusZ = nexusPos.getZ() - mobPos.getZ();

        double candidateLength = Math.sqrt(
                toCandidateX * toCandidateX
                        + toCandidateZ * toCandidateZ
        );

        double nexusLength = Math.sqrt(
                toNexusX * toNexusX
                        + toNexusZ * toNexusZ
        );

        if (candidateLength <= 0.0D || nexusLength <= 0.0D) {
            return true;
        }

        double dot =
                (toCandidateX / candidateLength) * (toNexusX / nexusLength)
                        + (toCandidateZ / candidateLength) * (toNexusZ / nexusLength);

        return dot > 0.15D;
    }

    private void moveToTargetBlock() {
        if (targetBlockPos == null) {
            return;
        }

        mob.getNavigation().moveTo(
                targetBlockPos.getX() + 0.5D,
                targetBlockPos.getY(),
                targetBlockPos.getZ() + 0.5D,
                speedModifier
        );

        repathCooldown = REPATH_INTERVAL;
    }

    private double distanceSqr(
            BlockPos first,
            BlockPos second
    ) {
        double x = first.getX() - second.getX();
        double y = first.getY() - second.getY();
        double z = first.getZ() - second.getZ();

        return x * x + y * y + z * z;
    }

    private void clearTarget() {
        targetBlockPos = null;
        breakTicks = 0;
        repathCooldown = 0;
    }

    private void resetSearchCooldown() {
        searchCooldown = MIN_SEARCH_COOLDOWN
                + mob.getRandom().nextInt(RANDOM_SEARCH_COOLDOWN + 1);
    }
}