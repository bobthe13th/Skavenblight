package org.ratden.skavenblight.entity.custom.wolfCat;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import org.ratden.skavenblight.world.NexusTracker;

import java.util.EnumSet;

public class MoveToActiveNexusGoal extends Goal {

    private static final int MIN_REPATH_COOLDOWN = 20;
    private static final int RANDOM_REPATH_COOLDOWN = 30;

    private static final double STOP_DISTANCE_SQR = 9.0D;

    private final PathfinderMob mob;
    private final double speedModifier;

    private BlockPos nexusPos;
    private int repathCooldown;

    public MoveToActiveNexusGoal(
            PathfinderMob mob,
            double speedModifier
    ) {
        this.mob = mob;
        this.speedModifier = speedModifier;
        this.repathCooldown = mob.getRandom().nextInt(RANDOM_REPATH_COOLDOWN + 1);

        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!(mob.level() instanceof ServerLevel level)) {
            return false;
        }

        if (!NexusTracker.hasActiveNexus(level)) {
            return false;
        }

        BlockPos activeNexusPos = NexusTracker.getActiveNexusPos(level);

        if (activeNexusPos == null) {
            return false;
        }

        if (mob.distanceToSqr(
                activeNexusPos.getX() + 0.5D,
                activeNexusPos.getY() + 0.5D,
                activeNexusPos.getZ() + 0.5D
        ) <= STOP_DISTANCE_SQR) {
            return false;
        }

        this.nexusPos = activeNexusPos.immutable();
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (!(mob.level() instanceof ServerLevel level)) {
            return false;
        }

        if (!NexusTracker.hasActiveNexus(level)) {
            return false;
        }

        BlockPos activeNexusPos = NexusTracker.getActiveNexusPos(level);

        if (activeNexusPos == null) {
            return false;
        }

        this.nexusPos = activeNexusPos.immutable();

        return mob.distanceToSqr(
                nexusPos.getX() + 0.5D,
                nexusPos.getY() + 0.5D,
                nexusPos.getZ() + 0.5D
        ) > STOP_DISTANCE_SQR;
    }

    @Override
    public void start() {
        moveToNexus();
    }

    @Override
    public void tick() {
        if (nexusPos == null) {
            return;
        }

        if (repathCooldown > 0) {
            repathCooldown--;
            return;
        }

        moveToNexus();
    }

    @Override
    public void stop() {
        nexusPos = null;
        mob.getNavigation().stop();
    }

    private void moveToNexus() {
        if (nexusPos == null) {
            return;
        }

        mob.getNavigation().moveTo(
                nexusPos.getX() + 0.5D,
                nexusPos.getY(),
                nexusPos.getZ() + 0.5D,
                speedModifier
        );

        resetRepathCooldown();
    }

    private void resetRepathCooldown() {
        repathCooldown = MIN_REPATH_COOLDOWN
                + mob.getRandom().nextInt(RANDOM_REPATH_COOLDOWN + 1);
    }
}