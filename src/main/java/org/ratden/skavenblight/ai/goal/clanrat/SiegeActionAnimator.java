package org.ratden.skavenblight.ai.goal.clanrat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.level.Level;

/**
 * Small shared helpers for the multi-state siege goals (SpiralSapperGoal, WarpSapperGoal,
 * DeployClimbableGoal). Their state machines are different enough from each other - and
 * from the simpler "approach and build once" goals - that forcing them onto one common
 * Goal base class would hurt clarity more than it helps. What they DO share verbatim is
 * how they animate an action, so that's what's pulled out here.
 */
public final class SiegeActionAnimator {

    private SiegeActionAnimator() {}

    /** Swings the mob's main hand every 5 ticks - the shared "looks busy" animation beat. */
    public static void swingPeriodically(PathfinderMob mob, int actionTicks) {
        if (actionTicks % 5 == 0) {
            mob.swing(InteractionHand.MAIN_HAND);
        }
    }

    /**
     * Renders the mining crack overlay for one tick and swings the mob's arm alongside it.
     * Returns true once actionTicks has reached maxActionTicks, i.e. the caller should now
     * actually break the block.
     */
    public static boolean tickMiningAnimation(Level level, PathfinderMob mob, BlockPos target, int actionTicks, int maxActionTicks) {
        swingPeriodically(mob, actionTicks);

        int progress = (int) ((float) actionTicks / maxActionTicks * 10.0F);
        level.destroyBlockProgress(mob.getId(), target, progress);

        return actionTicks >= maxActionTicks;
    }

    /** Clears the crack overlay. Call this both when a mine completes and from stop(), so it never lingers. */
    public static void clearMiningAnimation(Level level, PathfinderMob mob, BlockPos target) {
        level.destroyBlockProgress(mob.getId(), target, -1);
    }
}
