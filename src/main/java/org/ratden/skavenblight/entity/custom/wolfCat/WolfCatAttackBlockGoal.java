package org.ratden.skavenblight.entity.custom.wolfCat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

public class WolfCatAttackBlockGoal extends Goal {

    private static final int SEARCH_RANGE_XZ = 32;
    private static final int SEARCH_RANGE_Y = 8;

    private static final int INITIAL_SEARCH_COOLDOWN_RANDOM = 20;
    private static final int IDLE_SEARCH_COOLDOWN_MIN = 20;
    private static final int IDLE_SEARCH_COOLDOWN_RANDOM = 30;

    private static final int ACTIVE_RECONSIDER_INTERVAL = 80;
    private static final int REPATH_INTERVAL = 20;

    private static final int MAX_TARGET_AGE = 160;
    private static final int MAX_STUCK_TICKS = 80;

    private static final int MAX_CANDIDATES_TO_KEEP = 12;

    private static final double BREAK_DISTANCE_SQR = 4.0D;
    private static final double MEANINGFUL_PROGRESS_SQR = 2.25D;

    private final WolfCat wolfCat;
    private final Block targetBlock;

    private BlockPos targetPos;

    private int searchCooldown;
    private int repathCooldown;
    private int reconsiderCooldown;
    private int targetAge;
    private int stuckTicks;

    private double bestDistanceToTargetSqr;

    public WolfCatAttackBlockGoal(WolfCat wolfCat) {
        this.wolfCat = wolfCat;
        this.targetBlock = Blocks.DIAMOND_BLOCK;

        this.searchCooldown = wolfCat.getRandom().nextInt(INITIAL_SEARCH_COOLDOWN_RANDOM + 1);
        this.repathCooldown = 0;
        this.reconsiderCooldown = wolfCat.getRandom().nextInt(ACTIVE_RECONSIDER_INTERVAL + 1);
        this.targetAge = 0;
        this.stuckTicks = 0;
        this.bestDistanceToTargetSqr = Double.MAX_VALUE;

        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (hasValidTarget()) {
            return true;
        }

        if (searchCooldown > 0) {
            searchCooldown--;
            return false;
        }

        targetPos = findTargetBlock();

        if (targetPos == null) {
            resetIdleSearchCooldown();
            return false;
        }

        initialiseTargetTracking();
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return hasValidTarget();
    }

    @Override
    public void start() {
        moveToTarget();
    }

    @Override
    public void tick() {
        if (!hasValidTarget()) {
            clearTarget();
            return;
        }

        targetAge++;

        wolfCat.getLookControl().setLookAt(
                targetPos.getX() + 0.5D,
                targetPos.getY() + 0.5D,
                targetPos.getZ() + 0.5D
        );

        double currentDistanceSqr = distanceToTargetSqr();

        if (currentDistanceSqr < BREAK_DISTANCE_SQR) {
            wolfCat.level().destroyBlock(targetPos, true);
            clearTarget();
            resetIdleSearchCooldown();
            return;
        }

        updateProgressTracking(currentDistanceSqr);

        if (shouldAbandonTarget()) {
            clearTarget();
            resetIdleSearchCooldown();
            return;
        }

        if (shouldReconsiderTarget()) {
            BlockPos betterTarget = findTargetBlock();

            if (betterTarget != null && shouldSwitchToTarget(betterTarget)) {
                targetPos = betterTarget;
                initialiseTargetTracking();
                moveToTarget();
                return;
            }

            resetReconsiderCooldown();
        }

        if (repathCooldown > 0) {
            repathCooldown--;
            return;
        }

        moveToTarget();
    }

    @Override
    public void stop() {
        wolfCat.getNavigation().stop();
    }

    private boolean hasValidTarget() {
        return targetPos != null
                && wolfCat.level().getBlockState(targetPos).is(targetBlock);
    }

    private void moveToTarget() {
        if (targetPos == null) {
            return;
        }

        wolfCat.getNavigation().moveTo(
                targetPos.getX() + 0.5D,
                targetPos.getY(),
                targetPos.getZ() + 0.5D,
                1.25D
        );

        repathCooldown = REPATH_INTERVAL + wolfCat.getRandom().nextInt(10);
    }

    private void updateProgressTracking(double currentDistanceSqr) {
        if (currentDistanceSqr + MEANINGFUL_PROGRESS_SQR < bestDistanceToTargetSqr) {
            bestDistanceToTargetSqr = currentDistanceSqr;
            stuckTicks = 0;
            return;
        }

        stuckTicks++;
    }

    private boolean shouldAbandonTarget() {
        if (targetAge >= MAX_TARGET_AGE) {
            return true;
        }

        return stuckTicks >= MAX_STUCK_TICKS;
    }

    private boolean shouldReconsiderTarget() {
        if (reconsiderCooldown > 0) {
            reconsiderCooldown--;
            return false;
        }

        return true;
    }

    private boolean shouldSwitchToTarget(BlockPos candidateTarget) {
        if (targetPos == null) {
            return true;
        }

        if (candidateTarget.equals(targetPos)) {
            return false;
        }

        double currentTargetDistanceSqr = distanceToBlockSqr(targetPos);
        double candidateTargetDistanceSqr = distanceToBlockSqr(candidateTarget);

        return candidateTargetDistanceSqr + 16.0D < currentTargetDistanceSqr;
    }

    private BlockPos findTargetBlock() {
        BlockPos center = wolfCat.blockPosition();
        List<BlockPos> candidates = new ArrayList<>();

        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-SEARCH_RANGE_XZ, -SEARCH_RANGE_Y, -SEARCH_RANGE_XZ),
                center.offset(SEARCH_RANGE_XZ, SEARCH_RANGE_Y, SEARCH_RANGE_XZ)
        )) {
            if (!wolfCat.level().getBlockState(pos).is(targetBlock)) {
                continue;
            }

            addCandidate(candidates, pos.immutable());
        }

        if (candidates.isEmpty()) {
            return null;
        }

        return candidates.get(wolfCat.getRandom().nextInt(candidates.size()));
    }

    private void addCandidate(List<BlockPos> candidates, BlockPos candidate) {
        if (candidates.size() < MAX_CANDIDATES_TO_KEEP) {
            candidates.add(candidate);
            return;
        }

        int replacementRoll = wolfCat.getRandom().nextInt(MAX_CANDIDATES_TO_KEEP * 2);

        if (replacementRoll < MAX_CANDIDATES_TO_KEEP) {
            candidates.set(replacementRoll, candidate);
        }
    }

    private double distanceToTargetSqr() {
        if (targetPos == null) {
            return Double.MAX_VALUE;
        }

        return distanceToBlockSqr(targetPos);
    }

    private double distanceToBlockSqr(BlockPos pos) {
        return wolfCat.distanceToSqr(
                pos.getX() + 0.5D,
                pos.getY(),
                pos.getZ() + 0.5D
        );
    }

    private void initialiseTargetTracking() {
        targetAge = 0;
        stuckTicks = 0;
        bestDistanceToTargetSqr = distanceToTargetSqr();
        repathCooldown = 0;
        resetReconsiderCooldown();
    }

    private void clearTarget() {
        targetPos = null;
        targetAge = 0;
        stuckTicks = 0;
        bestDistanceToTargetSqr = Double.MAX_VALUE;
        wolfCat.getNavigation().stop();
    }

    private void resetIdleSearchCooldown() {
        searchCooldown = IDLE_SEARCH_COOLDOWN_MIN
                + wolfCat.getRandom().nextInt(IDLE_SEARCH_COOLDOWN_RANDOM + 1);
    }

    private void resetReconsiderCooldown() {
        reconsiderCooldown = ACTIVE_RECONSIDER_INTERVAL
                + wolfCat.getRandom().nextInt(40);
    }
}