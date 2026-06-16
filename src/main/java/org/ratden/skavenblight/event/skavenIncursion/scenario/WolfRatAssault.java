package org.ratden.skavenblight.event.skavenIncursion.scenario;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.ratden.skavenblight.event.skavenIncursion.SkavenDifficultyTracker;
import org.ratden.skavenblight.event.skavenIncursion.SkavenIncursion;
import org.ratden.skavenblight.event.skavenIncursion.action.SpawnWolfRats;

public class WolfRatAssault implements SkavenIncursion {
    private final ServerLevel level;
    private final BlockPos targetPos;
    private int elapsedTicks;
    private boolean finished;

    public WolfRatAssault(ServerLevel level, BlockPos targetPos) {
        this.level = level;
        this.targetPos = targetPos.immutable();
        this.elapsedTicks = 0;
        this.finished = false;
    }

    public static int calculateWolfRatCount() {
        int threat = SkavenDifficultyTracker.getThreat();
        int complexity = SkavenDifficultyTracker.getComplexity();

        int wolfRatCount = 2 + (threat / 10);

        if (complexity >= 1) {
            wolfRatCount += 1;
        }

        return wolfRatCount;
    }

    @Override
    public void tick() {
        elapsedTicks++;

        if (elapsedTicks == 100) {
            int wolfRatCount = calculateWolfRatCount();

            SpawnWolfRats.execute(level, targetPos, wolfRatCount);

            finished = true;
        }
    }

    @Override
    public boolean isFinished() {
        return finished;
    }
}