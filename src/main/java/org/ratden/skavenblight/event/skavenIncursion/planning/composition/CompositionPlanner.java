package org.ratden.skavenblight.event.skavenIncursion.planning.composition;

import net.minecraft.util.RandomSource;
import org.ratden.skavenblight.event.skavenIncursion.planning.IncursionPlan;
import org.ratden.skavenblight.event.skavenIncursion.planning.IncursionPlanningContext;
import org.ratden.skavenblight.event.skavenIncursion.planning.IncursionPlanningResult;
import org.ratden.skavenblight.event.skavenIncursion.planning.PlanningStepResult;
import org.ratden.skavenblight.event.skavenIncursion.planning.front.FrontPlan;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceRole;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceSize;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceType;
import org.ratden.skavenblight.event.skavenIncursion.scenario.ScenarioDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Spends wave threat budgets and creates pre-placement mob compositions.
 *
 * This first implementation handles baseline combat composition only:
 *
 * - Scenario mob-roster weights are treated as the effective weights;
 * - mobs are purchased through weighted random selection;
 * - purchases are packaged into source-sized compositions;
 * - the first source is always permitted for a non-empty wave;
 * - additional sources must satisfy the StructuralThreatTarget;
 * - normal combat tunnels are preferred unless a mob requires a larger
 *   source.
 *
 * Complexity purchases, Stratagem mob-weight adjustments, leadership,
 * support sources, and special source topology will be added later.
 */
public class CompositionPlanner {

    private final ThreatDensityCalculator threatDensityCalculator;

    public CompositionPlanner() {
        this(new ThreatDensityCalculator());
    }

    public CompositionPlanner(
            ThreatDensityCalculator threatDensityCalculator
    ) {
        if (threatDensityCalculator == null) {
            throw new IllegalArgumentException(
                    "Threat-density calculator cannot be null."
            );
        }

        this.threatDensityCalculator = threatDensityCalculator;
    }

    public PlanningStepResult planComposition(
            IncursionPlanningContext context,
            IncursionPlan incursionPlan
    ) {
        if (context == null) {
            throw new IllegalArgumentException(
                    "Planning context cannot be null."
            );
        }

        if (incursionPlan == null) {
            throw new IllegalArgumentException(
                    "Incursion plan cannot be null."
            );
        }

        if (!incursionPlan.hasFrontPlans()) {
            return failure(
                    "Composition planning cannot begin because incursion plan "
                            + incursionPlan.getIncursionId()
                            + " contains no fronts."
            );
        }

        List<ThreatDensityCalculator.WeightedMobDefinition>
                weightedMobDefinitions =
                createWeightedMobDefinitions(
                        context.scenarioDefinition()
                );

        if (!hasPositiveWeight(weightedMobDefinitions)) {
            return failure(
                    "Scenario "
                            + context.scenarioDefinition().id()
                            + " has no mobs with a positive effective weight."
            );
        }

        RandomSource random = context.level().getRandom();

        for (FrontPlan frontPlan : incursionPlan.getFrontPlans()) {
            for (FrontPlan.WavePlan wavePlan
                    : frontPlan.getWavePlans()) {

                if (wavePlan.hasSourceGroupCompositions()) {
                    return failure(
                            "Front "
                                    + frontPlan.getFrontId()
                                    + ", wave "
                                    + wavePlan.getWaveIndex()
                                    + " already contains composition results."
                    );
                }

                PlanningStepResult waveResult =
                        planWaveComposition(
                                weightedMobDefinitions,
                                random,
                                frontPlan,
                                wavePlan
                        );

                if (waveResult.hasFailed()) {
                    return waveResult;
                }
            }
        }

        return PlanningStepResult.success();
    }

    private PlanningStepResult planWaveComposition(
            List<ThreatDensityCalculator.WeightedMobDefinition>
                    weightedMobDefinitions,
            RandomSource random,
            FrontPlan frontPlan,
            FrontPlan.WavePlan wavePlan
    ) {
        int waveThreatBudget = wavePlan.getThreatBudget();

        if (waveThreatBudget == 0) {
            return PlanningStepResult.success();
        }

        SourceGroupComposition sourceGroupComposition =
                new SourceGroupComposition();

        int remainingThreat = waveThreatBudget;
        int plannedSourceCapacity = 0;

        while (remainingThreat > 0) {
            IncursionMobDefinition sourceSeed =
                    chooseSourceSeed(
                            weightedMobDefinitions,
                            random,
                            remainingThreat,
                            waveThreatBudget,
                            plannedSourceCapacity
                    );

            if (sourceSeed == null) {
                break;
            }

            SourceSize sourceSize =
                    resolveSourceSize(sourceSeed);

            PlannedSourceContents plannedSourceContents =
                    planSourceContents(
                            weightedMobDefinitions,
                            random,
                            sourceSeed,
                            sourceSize,
                            remainingThreat
                    );

            if (plannedSourceContents == null
                    || plannedSourceContents.threatSpent() <= 0) {
                return failure(
                        "Front "
                                + frontPlan.getFrontId()
                                + ", wave "
                                + wavePlan.getWaveIndex()
                                + " could not create a valid source-sized "
                                + "composition."
                );
            }

            SourceGroupComposition.SourceComposition
                    sourceComposition =
                    new SourceGroupComposition.SourceComposition(
                            SourceType.SKAVEN_TUNNEL,
                            sourceSize,
                            SourceRole.COMBAT,
                            plannedSourceContents.threatSpent()
                    );

            for (Map.Entry<IncursionMobDefinition, Integer> entry
                    : plannedSourceContents.mobCounts().entrySet()) {
                IncursionMobDefinition mobDefinition =
                        entry.getKey();

                sourceComposition.addMob(
                        mobDefinition.getMobId(),
                        entry.getValue(),
                        mobDefinition.getCapacityCost(),
                        mobDefinition.getMinimumSourceSize()
                );
            }

            sourceGroupComposition.addSourceComposition(
                    sourceComposition
            );

            remainingThreat -=
                    plannedSourceContents.threatSpent();

            plannedSourceCapacity +=
                    sourceSize.getCapacityUnits();
        }

        if (sourceGroupComposition.isEmpty()) {
            return failure(
                    "Front "
                            + frontPlan.getFrontId()
                            + ", wave "
                            + wavePlan.getWaveIndex()
                            + " received "
                            + waveThreatBudget
                            + " threat but could not afford any legal mob."
            );
        }

        wavePlan.addSourceGroupComposition(
                sourceGroupComposition
        );

        return PlanningStepResult.success();
    }

    /**
     * Chooses the first mob for a proposed source.
     *
     * The first source in a wave is always allowed because the wave requires
     * some delivery structure. Additional sources are only eligible when the
     * complete proposed source structure satisfies StructuralThreatTarget.
     */
    private IncursionMobDefinition chooseSourceSeed(
            List<ThreatDensityCalculator.WeightedMobDefinition>
                    weightedMobDefinitions,
            RandomSource random,
            int remainingThreat,
            int waveThreatBudget,
            int plannedSourceCapacity
    ) {
        List<ThreatDensityCalculator.WeightedMobDefinition>
                eligibleDefinitions =
                new ArrayList<>();

        for (ThreatDensityCalculator.WeightedMobDefinition weightedDefinition
                : weightedMobDefinitions) {
            IncursionMobDefinition mobDefinition =
                    weightedDefinition.mobDefinition();

            if (weightedDefinition.weight() <= 0.0D) {
                continue;
            }

            if (mobDefinition.getThreatCost()
                    > remainingThreat) {
                continue;
            }

            SourceSize resolvedSourceSize =
                    resolveSourceSize(mobDefinition);

            if (mobDefinition.getCapacityCost()
                    > resolvedSourceSize.getCapacityUnits()) {
                continue;
            }

            if (plannedSourceCapacity > 0) {
                int proposedCapacity =
                        plannedSourceCapacity
                                + resolvedSourceSize
                                .getCapacityUnits();

                ThreatDensityCalculator.ThreatDensityResult
                        threatDensityResult =
                        threatDensityCalculator
                                .calculateStructuralThreatTarget(
                                        weightedMobDefinitions,
                                        proposedCapacity
                                );

                if (!threatDensityResult.isSatisfiedBy(
                        waveThreatBudget
                )) {
                    continue;
                }
            }

            eligibleDefinitions.add(weightedDefinition);
        }

        return chooseWeightedMob(
                eligibleDefinitions,
                random
        );
    }

    private PlannedSourceContents planSourceContents(
            List<ThreatDensityCalculator.WeightedMobDefinition>
                    weightedMobDefinitions,
            RandomSource random,
            IncursionMobDefinition sourceSeed,
            SourceSize sourceSize,
            int availableThreat
    ) {
        Map<IncursionMobDefinition, Integer> mobCounts =
                new LinkedHashMap<>();

        int threatSpent =
                sourceSeed.getThreatCost();

        int capacityUsed =
                sourceSeed.getCapacityCost();

        if (threatSpent > availableThreat
                || capacityUsed
                > sourceSize.getCapacityUnits()) {
            return null;
        }

        mobCounts.put(sourceSeed, 1);

        while (true) {
            int remainingThreat =
                    availableThreat - threatSpent;

            int remainingCapacity =
                    sourceSize.getCapacityUnits()
                            - capacityUsed;

            List<ThreatDensityCalculator.WeightedMobDefinition>
                    eligibleDefinitions =
                    new ArrayList<>();

            for (ThreatDensityCalculator.WeightedMobDefinition
                    weightedDefinition : weightedMobDefinitions) {
                IncursionMobDefinition mobDefinition =
                        weightedDefinition.mobDefinition();

                if (weightedDefinition.weight() <= 0.0D) {
                    continue;
                }

                if (mobDefinition.getThreatCost()
                        > remainingThreat) {
                    continue;
                }

                if (mobDefinition.getCapacityCost()
                        > remainingCapacity) {
                    continue;
                }

                if (!sourceSize.canFit(
                        mobDefinition.getMinimumSourceSize()
                )) {
                    continue;
                }

                eligibleDefinitions.add(weightedDefinition);
            }

            IncursionMobDefinition selectedMob =
                    chooseWeightedMob(
                            eligibleDefinitions,
                            random
                    );

            if (selectedMob == null) {
                break;
            }

            mobCounts.merge(
                    selectedMob,
                    1,
                    Integer::sum
            );

            threatSpent +=
                    selectedMob.getThreatCost();

            capacityUsed +=
                    selectedMob.getCapacityCost();
        }

        return new PlannedSourceContents(
                mobCounts,
                threatSpent
        );
    }

    /**
     * Resolves the actual physical source size used for a mob package.
     *
     * Minimum source size describes the smallest source capable of handling
     * the mob. It does not require the planner to create the smallest
     * possible source.
     *
     * Baseline combat composition therefore uses NORMAL tunnels for mobs
     * whose minimum requirements fit within NORMAL. Mobs requiring a larger
     * source elevate the result to their minimum required size.
     */
    private SourceSize resolveSourceSize(
            IncursionMobDefinition mobDefinition
    ) {
        SourceSize minimumSourceSize =
                mobDefinition.getMinimumSourceSize();

        if (SourceSize.NORMAL.canFit(
                minimumSourceSize
        )) {
            return SourceSize.NORMAL;
        }

        return minimumSourceSize;
    }

    private IncursionMobDefinition chooseWeightedMob(
            List<ThreatDensityCalculator.WeightedMobDefinition>
                    eligibleDefinitions,
            RandomSource random
    ) {
        if (eligibleDefinitions == null
                || eligibleDefinitions.isEmpty()) {
            return null;
        }

        double totalWeight = 0.0D;

        for (ThreatDensityCalculator.WeightedMobDefinition
                weightedDefinition : eligibleDefinitions) {
            totalWeight += weightedDefinition.weight();
        }

        if (totalWeight <= 0.0D) {
            return null;
        }

        double roll =
                random.nextDouble() * totalWeight;

        for (ThreatDensityCalculator.WeightedMobDefinition
                weightedDefinition : eligibleDefinitions) {
            roll -= weightedDefinition.weight();

            if (roll <= 0.0D) {
                return weightedDefinition.mobDefinition();
            }
        }

        return eligibleDefinitions
                .get(eligibleDefinitions.size() - 1)
                .mobDefinition();
    }

    private List<ThreatDensityCalculator.WeightedMobDefinition>
    createWeightedMobDefinitions(
            ScenarioDefinition scenarioDefinition
    ) {
        List<ThreatDensityCalculator.WeightedMobDefinition>
                weightedMobDefinitions =
                new ArrayList<>();

        for (ScenarioDefinition.MobRosterEntry rosterEntry
                : scenarioDefinition.mobRoster()) {
            weightedMobDefinitions.add(
                    new ThreatDensityCalculator.WeightedMobDefinition(
                            rosterEntry.mobDefinition(),
                            rosterEntry.baseWeight()
                    )
            );
        }

        return List.copyOf(weightedMobDefinitions);
    }

    private boolean hasPositiveWeight(
            List<ThreatDensityCalculator.WeightedMobDefinition>
                    weightedMobDefinitions
    ) {
        for (ThreatDensityCalculator.WeightedMobDefinition
                weightedDefinition : weightedMobDefinitions) {
            if (weightedDefinition.weight() > 0.0D) {
                return true;
            }
        }

        return false;
    }

    private PlanningStepResult failure(
            String message
    ) {
        return PlanningStepResult.failure(
                IncursionPlanningResult.PlanningStage
                        .COMPOSITION_PLANNING,
                IncursionPlanningResult.PlanningFailureReason
                        .COMPOSITION_EXHAUSTED,
                message
        );
    }

    private record PlannedSourceContents(
            Map<IncursionMobDefinition, Integer> mobCounts,
            int threatSpent
    ) {
        private PlannedSourceContents {
            mobCounts = Map.copyOf(mobCounts);

            if (threatSpent <= 0) {
                throw new IllegalArgumentException(
                        "Planned source threat must be positive."
                );
            }
        }
    }
}