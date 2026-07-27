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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Spends wave threat budgets and creates pre-placement mob compositions.
 *
 * Current responsibilities:
 *
 * - Scenario mob-roster weights are treated as the effective weights;
 * - mobs are purchased through weighted random selection;
 * - purchases are packaged into source-sized compositions;
 * - the first source is always permitted for a non-empty wave;
 * - additional sources must satisfy the StructuralThreatTarget;
 * - normal combat tunnels are preferred unless a mob requires a larger
 *   source;
 * - source-sized compositions are divided into load-limited source groups.
 *
 * Source grouping currently uses SourceGroupRules.STANDARD and a
 * largest-first, first-fit pass. Stratagem-specific grouping rules and
 * authored source topology can replace that baseline later.
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

        RandomSource random =
                context.level().getRandom();

        for (FrontPlan frontPlan
                : incursionPlan.getFrontPlans()) {

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
        int waveThreatBudget =
                wavePlan.getThreatBudget();

        if (waveThreatBudget == 0) {
            return PlanningStepResult.success();
        }

        List<SourceGroupComposition.SourceComposition>
                plannedSourceCompositions =
                new ArrayList<>();

        int remainingThreat =
                waveThreatBudget;

        int plannedSourceCapacity =
                0;

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
                    resolveSourceSize(
                            sourceSeed
                    );

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

            plannedSourceCompositions.add(
                    sourceComposition
            );

            remainingThreat -=
                    plannedSourceContents.threatSpent();

            plannedSourceCapacity +=
                    sourceSize.getCapacityUnits();
        }

        if (plannedSourceCompositions.isEmpty()) {
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

        SourceGroupingResult groupingResult =
                groupSourceCompositions(
                        plannedSourceCompositions,
                        SourceGroupRules.STANDARD
                );

        if (groupingResult.hasFailed()) {
            return failure(
                    "Front "
                            + frontPlan.getFrontId()
                            + ", wave "
                            + wavePlan.getWaveIndex()
                            + " could not group its planned sources. "
                            + groupingResult.failureMessage()
            );
        }

        for (SourceGroupComposition sourceGroupComposition
                : groupingResult.sourceGroupCompositions()) {
            wavePlan.addSourceGroupComposition(
                    sourceGroupComposition
            );
        }

        return PlanningStepResult.success();
    }

    /**
     * Divides source-sized compositions into source groups without exceeding
     * the supplied group-load limit.
     *
     * Sources are considered from highest load to lowest load, then inserted
     * into the first existing compatible group with enough remaining load.
     * A new group is created when no existing group can accept the source.
     *
     * SourceGroupComposition also prevents mixed SourceRole values, so this
     * baseline naturally creates separate groups for combat, support and
     * other source roles.
     */
    private SourceGroupingResult groupSourceCompositions(
            List<SourceGroupComposition.SourceComposition>
                    sourceCompositions,
            SourceGroupRules sourceGroupRules
    ) {
        if (sourceCompositions == null
                || sourceCompositions.isEmpty()) {
            return SourceGroupingResult.failure(
                    "No source compositions were supplied."
            );
        }

        if (sourceGroupRules == null) {
            return SourceGroupingResult.failure(
                    "Source-group rules were not supplied."
            );
        }

        List<SourceGroupComposition.SourceComposition>
                sortedSourceCompositions =
                new ArrayList<>(
                        sourceCompositions
                );

        sortedSourceCompositions.sort(
                Comparator.comparingInt(
                                SourceGroupComposition
                                        .SourceComposition
                                        ::getSourceGroupLoadCost
                        )
                        .reversed()
        );

        List<SourceGroupComposition> sourceGroups =
                new ArrayList<>();

        for (SourceGroupComposition.SourceComposition
                sourceComposition
                : sortedSourceCompositions) {

            int sourceLoad =
                    sourceComposition.getSourceGroupLoadCost();

            if (sourceLoad > sourceGroupRules.maximumLoad()) {
                return SourceGroupingResult.failure(
                        "Source composition "
                                + sourceComposition
                                .getSourceCompositionId()
                                + " requires "
                                + sourceLoad
                                + " load, exceeding the group maximum of "
                                + sourceGroupRules.maximumLoad()
                                + "."
                );
            }

            SourceGroupComposition selectedGroup =
                    findFirstCompatibleGroup(
                            sourceGroups,
                            sourceComposition
                    );

            if (selectedGroup == null) {
                selectedGroup =
                        new SourceGroupComposition(
                                sourceGroupRules
                        );

                sourceGroups.add(
                        selectedGroup
                );
            }

            if (!selectedGroup.canFitSourceComposition(
                    sourceComposition
            )) {
                return SourceGroupingResult.failure(
                        "Selected source group "
                                + selectedGroup
                                .getSourceGroupCompositionId()
                                + " cannot fit source composition "
                                + sourceComposition
                                .getSourceCompositionId()
                                + "."
                );
            }

            selectedGroup.addSourceComposition(
                    sourceComposition
            );

            if (!selectedGroup.isWithinSourceGroupLoadLimit()) {
                return SourceGroupingResult.failure(
                        "Source group "
                                + selectedGroup
                                .getSourceGroupCompositionId()
                                + " exceeded its maximum load after adding "
                                + "source composition "
                                + sourceComposition
                                .getSourceCompositionId()
                                + "."
                );
            }
        }

        if (sourceGroups.isEmpty()) {
            return SourceGroupingResult.failure(
                    "Grouping produced no source groups."
            );
        }

        int originalThreat =
                getTotalThreatSpent(
                        sourceCompositions
                );

        int groupedThreat =
                getTotalThreatSpentAcrossGroups(
                        sourceGroups
                );

        if (groupedThreat != originalThreat) {
            return SourceGroupingResult.failure(
                    "Grouping changed planned threat from "
                            + originalThreat
                            + " to "
                            + groupedThreat
                            + "."
            );
        }

        return SourceGroupingResult.success(
                sourceGroups
        );
    }

    private SourceGroupComposition findFirstCompatibleGroup(
            List<SourceGroupComposition> sourceGroups,
            SourceGroupComposition.SourceComposition sourceComposition
    ) {
        for (SourceGroupComposition sourceGroup
                : sourceGroups) {
            if (sourceGroup.canFitSourceComposition(
                    sourceComposition
            )) {
                return sourceGroup;
            }
        }

        return null;
    }

    private int getTotalThreatSpent(
            List<SourceGroupComposition.SourceComposition>
                    sourceCompositions
    ) {
        int total =
                0;

        for (SourceGroupComposition.SourceComposition
                sourceComposition
                : sourceCompositions) {
            total +=
                    sourceComposition.getThreatSpent();
        }

        return total;
    }

    private int getTotalThreatSpentAcrossGroups(
            List<SourceGroupComposition> sourceGroups
    ) {
        int total =
                0;

        for (SourceGroupComposition sourceGroup
                : sourceGroups) {
            total +=
                    sourceGroup.getTotalThreatSpent();
        }

        return total;
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

        for (ThreatDensityCalculator.WeightedMobDefinition
                weightedDefinition
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
                    resolveSourceSize(
                            mobDefinition
                    );

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

            eligibleDefinitions.add(
                    weightedDefinition
            );
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

        mobCounts.put(
                sourceSeed,
                1
        );

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
                    weightedDefinition
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

                if (mobDefinition.getCapacityCost()
                        > remainingCapacity) {
                    continue;
                }

                if (!sourceSize.canFit(
                        mobDefinition.getMinimumSourceSize()
                )) {
                    continue;
                }

                eligibleDefinitions.add(
                        weightedDefinition
                );
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

        double totalWeight =
                0.0D;

        for (ThreatDensityCalculator.WeightedMobDefinition
                weightedDefinition
                : eligibleDefinitions) {
            totalWeight +=
                    weightedDefinition.weight();
        }

        if (totalWeight <= 0.0D) {
            return null;
        }

        double roll =
                random.nextDouble()
                        * totalWeight;

        for (ThreatDensityCalculator.WeightedMobDefinition
                weightedDefinition
                : eligibleDefinitions) {
            roll -=
                    weightedDefinition.weight();

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

        return List.copyOf(
                weightedMobDefinitions
        );
    }

    private boolean hasPositiveWeight(
            List<ThreatDensityCalculator.WeightedMobDefinition>
                    weightedMobDefinitions
    ) {
        for (ThreatDensityCalculator.WeightedMobDefinition
                weightedDefinition
                : weightedMobDefinitions) {
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
            mobCounts =
                    Map.copyOf(
                            mobCounts
                    );

            if (threatSpent <= 0) {
                throw new IllegalArgumentException(
                        "Planned source threat must be positive."
                );
            }
        }
    }

    private record SourceGroupingResult(
            List<SourceGroupComposition> sourceGroupCompositions,
            String failureMessage
    ) {
        private SourceGroupingResult {
            if (sourceGroupCompositions == null) {
                throw new IllegalArgumentException(
                        "Grouped source-composition list cannot be null."
                );
            }

            sourceGroupCompositions =
                    List.copyOf(
                            sourceGroupCompositions
                    );

            boolean hasGroups =
                    !sourceGroupCompositions.isEmpty();

            boolean hasFailureMessage =
                    failureMessage != null
                            && !failureMessage.isBlank();

            if (hasGroups == hasFailureMessage) {
                throw new IllegalArgumentException(
                        "Source grouping result must contain either groups or "
                                + "a failure message."
                );
            }
        }

        private static SourceGroupingResult success(
                List<SourceGroupComposition> sourceGroupCompositions
        ) {
            return new SourceGroupingResult(
                    sourceGroupCompositions,
                    null
            );
        }

        private static SourceGroupingResult failure(
                String failureMessage
        ) {
            return new SourceGroupingResult(
                    List.of(),
                    failureMessage
            );
        }

        private boolean hasFailed() {
            return failureMessage != null;
        }
    }
}