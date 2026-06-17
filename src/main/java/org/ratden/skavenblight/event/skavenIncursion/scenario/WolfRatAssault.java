package org.ratden.skavenblight.event.skavenIncursion.scenario;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import org.ratden.skavenblight.block.entity.state.SourceState;
import org.ratden.skavenblight.event.skavenIncursion.IncursionTargetType;
import org.ratden.skavenblight.event.skavenIncursion.SkavenDifficultyTracker;
import org.ratden.skavenblight.event.skavenIncursion.SkavenIncursion;
import org.ratden.skavenblight.event.skavenIncursion.action.CreateTunnelSource;
import org.ratden.skavenblight.event.skavenIncursion.action.SetSourceState;
import org.ratden.skavenblight.event.skavenIncursion.action.SpawnWolfRats;

public class WolfRatAssault implements SkavenIncursion {
    private final ServerLevel level;
    private final BlockPos targetPos;
    private final BlockPos sourcePos;
    private int elapsedTicks;
    private boolean finished;
    private final IncursionTargetType targetType;

    public WolfRatAssault(ServerLevel level, BlockPos targetPos, IncursionTargetType targetType) {
        this.level = level;
        this.targetPos = targetPos.immutable();
        this.sourcePos = targetPos.offset(5, 0, 0).immutable();
        this.targetType = targetType;
        this.elapsedTicks = 0;
        this.finished = false;
    }

    public static int getBaseWolfRatCount() {
        return 2;
    }

    public static int getThreatContribution() {
        return SkavenDifficultyTracker.getThreat() / 10;
    }

    public static int getComplexityContribution() {
        return SkavenDifficultyTracker.getComplexity() >= 1 ? 1 : 0;
    }

    public static int calculateWolfRatCount(IncursionTargetType targetType) {
        int wolfRatCount = getBaseWolfRatCount()
                + getThreatContribution()
                + getComplexityContribution();

        if (targetType == IncursionTargetType.NEXUS) {
            wolfRatCount += 2;
        }

        return wolfRatCount;
    }
    public static int calculateWolfRatCount() {
        return calculateWolfRatCount(IncursionTargetType.PLAYER);
    }

    public static String getDebugName() {
        return "Wolf Rat Assault";
    }

    public static String getDebugFormula() {
        return "Wolf rats = base "
                + getBaseWolfRatCount()
                + " + threat contribution "
                + getThreatContribution()
                + " + complexity contribution "
                + getComplexityContribution()
                + " = "
                + calculateWolfRatCount(IncursionTargetType.PLAYER);
    }

    public static String getDebugTimeline() {
        return "Timeline: "
                + "tick 1 digging sound, "
                + "tick 20 growl + tunnel appears, "
                + "tick 100 wolf rats spawn, "
                + "tick 160 tunnel collapses, "
                + "tick 180 assault ends";
    }

    @Override
    public void tick() {
        elapsedTicks++;

        if (elapsedTicks == 1) {
            level.playSound(
                    null,
                    targetPos,
                    SoundEvents.GRAVEL_BREAK,
                    SoundSource.HOSTILE,
                    2f,
                    0.8f
            );
        }

        if (elapsedTicks == 20) {
            level.playSound(
                    null,
                    sourcePos,
                    SoundEvents.WOLF_GROWL,
                    SoundSource.HOSTILE,
                    2f,
                    1f
            );

            CreateTunnelSource.execute(level, sourcePos, SourceState.ACTIVE);
        }

        if (elapsedTicks == 100) {
            SpawnWolfRats.execute(level, sourcePos, calculateWolfRatCount(targetType));
        }

        if (elapsedTicks == 160) {
            SetSourceState.execute(level, sourcePos, SourceState.COLLAPSED);
        }

        if (elapsedTicks == 180) {
            finished = true;
        }
    }

    @Override
    public boolean isFinished() {
        return finished;
    }
}