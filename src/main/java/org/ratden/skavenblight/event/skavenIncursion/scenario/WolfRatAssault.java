package org.ratden.skavenblight.event.skavenIncursion.scenario;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import org.ratden.skavenblight.block.entity.state.SourceState;
import org.ratden.skavenblight.event.skavenIncursion.IncursionTargetType;
import org.ratden.skavenblight.event.skavenIncursion.SkavenIncursion;
import org.ratden.skavenblight.event.skavenIncursion.action.CreateTunnelSource;
import org.ratden.skavenblight.event.skavenIncursion.action.SetSourceState;
import org.ratden.skavenblight.event.skavenIncursion.action.SourcePlacement;
import org.ratden.skavenblight.event.skavenIncursion.action.SpawnWolfRats;
import org.ratden.skavenblight.event.skavenIncursion.budget.IncursionCosts;
import org.ratden.skavenblight.world.SkavenblightWorldData;

public class WolfRatAssault implements SkavenIncursion {
    private final ServerLevel level;
    private final BlockPos targetPos;
    private final BlockPos sourcePos;
    private final IncursionTargetType targetType;

    private final int wolfRatCount;
    private final int poisonAttackChancePercent;

    private int elapsedTicks;
    private boolean finished;

    public WolfRatAssault(ServerLevel level, BlockPos targetPos, IncursionTargetType targetType) {
        this.level = level;
        this.targetPos = targetPos.immutable();
        this.sourcePos = SourcePlacement.forAssault(level, targetPos, targetType).immutable();
        this.targetType = targetType;

        this.wolfRatCount = calculateWolfRatCount(level, targetType);
        this.poisonAttackChancePercent = calculatePoisonAttackChancePercent(level);

        this.elapsedTicks = 0;
        this.finished = false;
    }

    public static String getDebugName() {
        return "Wolf Rat Assault";
    }

    public static int getThreatBudgetMultiplierPercent(IncursionTargetType targetType) {
        if (targetType == IncursionTargetType.NEXUS) {
            return 20;
        }

        return 15;
    }

    public static int calculateThreatBudget(ServerLevel level, IncursionTargetType targetType) {
        int threat = SkavenblightWorldData.get(level).getThreat();
        int multiplierPercent = getThreatBudgetMultiplierPercent(targetType);

        return Math.max(1, roundUpPercent(threat, multiplierPercent));
    }

    public static int calculateWolfRatCount(ServerLevel level, IncursionTargetType targetType) {
        int threatBudget = calculateThreatBudget(level, targetType);

        return Math.max(1, threatBudget / IncursionCosts.WOLF_RAT);
    }

    public static int getComplexityBudgetMultiplier() {
        return 2;
    }

    public static int calculateComplexityBudget(ServerLevel level) {
        int schemeComplexity = SkavenblightWorldData.get(level).getSchemeComplexity();

        return schemeComplexity * getComplexityBudgetMultiplier();
    }

    public static int calculatePoisonAttackChancePercent(ServerLevel level) {
        int complexityBudget = calculateComplexityBudget(level);

        return Math.min(100, complexityBudget * 10);
    }

    private static int roundUpPercent(int value, int percent) {
        return (value * percent + 99) / 100;
    }

    public static String getDebugSummary(ServerLevel level, IncursionTargetType targetType) {
        return "Wolf Rat Assault"
                + "\nTarget type: " + targetType
                + "\nThreat budget: " + calculateThreatBudget(level, targetType)
                + "\nWolf rat cost: " + IncursionCosts.WOLF_RAT
                + "\nWolf rats: " + calculateWolfRatCount(level, targetType)
                + "\nComplexity budget: " + calculateComplexityBudget(level)
                + "\nPoison attack chance: " + calculatePoisonAttackChancePercent(level) + "%"
                + "\nNote: poison attack chance is calculated but not implemented yet.";
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
            SpawnWolfRats.execute(level, sourcePos, wolfRatCount);

            // Future:
            // SpawnWolfRats.execute(level, sourcePos, wolfRatCount, poisonAttackChancePercent);
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