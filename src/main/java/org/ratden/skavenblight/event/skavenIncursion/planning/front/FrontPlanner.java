package org.ratden.skavenblight.event.skavenIncursion.planning.front;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import org.ratden.skavenblight.event.skavenIncursion.planning.IncursionPlan;
import org.ratden.skavenblight.event.skavenIncursion.planning.IncursionPlanningContext;
import org.ratden.skavenblight.event.skavenIncursion.planning.IncursionPlanningResult;
import org.ratden.skavenblight.event.skavenIncursion.planning.PlanningStepResult;
import org.ratden.skavenblight.event.skavenIncursion.planning.stratagem.StratagemDefinition;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Creates the fronts, front anchors, front allocations, and resolved
 * wave budgets for one IncursionPlan.
 *
 * This first implementation uses the placement and allocation patterns
 * selected by the StratagemDefinition. Front-pattern viability filtering
 * can be introduced later without changing FrontPlan.
 */
public class FrontPlanner {

    private static final int MAX_IRREGULAR_ANGLE_ATTEMPTS = 100;

    public PlanningStepResult planFronts(
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

        if (incursionPlan.hasFrontPlans()) {
            return PlanningStepResult.failure(
                    IncursionPlanningResult.PlanningStage.FRONT_PLANNING,
                    IncursionPlanningResult.PlanningFailureReason
                            .INVALID_FRONT_ALLOCATION,
                    "Front planning cannot begin because the incursion plan "
                            + incursionPlan.getIncursionId()
                            + " already contains fronts."
            );
        }

        StratagemDefinition stratagem =
                context.stratagemDefinition();

        FrontPlacementPattern placementPattern =
                stratagem.getFrontPlacementPattern();

        FrontAllocationPattern allocationPattern =
                stratagem.getFrontAllocationPattern();

        RandomSource random = context.level().getRandom();

        List<UUID> frontIds = createFrontIds(
                placementPattern.getFrontCount()
        );

        FrontAllocation frontAllocation;

        try {
            frontAllocation = FrontAllocation.fromPattern(
                    frontIds,
                    placementPattern,
                    allocationPattern,
                    random
            );
        } catch (IllegalArgumentException exception) {
            return PlanningStepResult.failure(
                    IncursionPlanningResult.PlanningStage.FRONT_PLANNING,
                    IncursionPlanningResult.PlanningFailureReason
                            .INVALID_FRONT_ALLOCATION,
                    "Could not create front allocation for Stratagem "
                            + stratagem.getId()
                            + ": "
                            + exception.getMessage()
            );
        }

        List<BlockPos> frontAnchors = createFrontAnchors(
                context,
                placementPattern,
                random
        );

        if (frontAnchors.size() != frontIds.size()) {
            return PlanningStepResult.failure(
                    IncursionPlanningResult.PlanningStage.FRONT_PLANNING,
                    IncursionPlanningResult.PlanningFailureReason
                            .NO_VIABLE_FRONT_PATTERN,
                    "Front anchor generation produced "
                            + frontAnchors.size()
                            + " anchors for "
                            + frontIds.size()
                            + " planned fronts."
            );
        }

        int waveCount = stratagem.getWaveCount();

        double[][] threatWeights = new double[frontIds.size()][waveCount];
        double[][] complexityWeights =
                new double[frontIds.size()][waveCount];

        for (int frontIndex = 0;
             frontIndex < frontIds.size();
             frontIndex++) {
            UUID frontId = frontIds.get(frontIndex);

            for (int waveIndex = 0;
                 waveIndex < waveCount;
                 waveIndex++) {
                StratagemDefinition.WaveProfile waveProfile =
                        stratagem.getWaveProfile(waveIndex);

                threatWeights[frontIndex][waveIndex] =
                        frontAllocation.getThreatShare(frontId)
                                * waveProfile.threatShare();

                complexityWeights[frontIndex][waveIndex] =
                        frontAllocation.getComplexityShare(frontId)
                                * waveProfile.complexityShare();
            }
        }

        int[][] threatBudgets = allocateIntegerBudget(
                context.totalThreatBudget(),
                threatWeights
        );

        int[][] complexityBudgets = allocateIntegerBudget(
                context.totalComplexityBudget(),
                complexityWeights
        );

        for (int frontIndex = 0;
             frontIndex < frontIds.size();
             frontIndex++) {
            UUID frontId = frontIds.get(frontIndex);

            FrontPlan frontPlan = new FrontPlan(
                    frontId,
                    frontIndex,
                    frontAnchors.get(frontIndex),
                    placementPattern,
                    frontAllocation.getThreatShare(frontId),
                    frontAllocation.getComplexityShare(frontId),
                    frontAllocation.isDominant(frontId)
            );

            for (int waveIndex = 0;
                 waveIndex < waveCount;
                 waveIndex++) {
                StratagemDefinition.WaveProfile waveProfile =
                        stratagem.getWaveProfile(waveIndex);

                frontPlan.addWavePlan(
                        new FrontPlan.WavePlan(
                                waveIndex,
                                waveProfile.threatShare(),
                                waveProfile.complexityShare(),
                                threatBudgets[frontIndex][waveIndex],
                                complexityBudgets[frontIndex][waveIndex]
                        )
                );
            }

            incursionPlan.addFrontPlan(frontPlan);
        }

        return PlanningStepResult.success();
    }

    private List<UUID> createFrontIds(int frontCount) {
        List<UUID> frontIds = new ArrayList<>();

        for (int index = 0; index < frontCount; index++) {
            frontIds.add(UUID.randomUUID());
        }

        return frontIds;
    }

    private List<BlockPos> createFrontAnchors(
            IncursionPlanningContext context,
            FrontPlacementPattern placementPattern,
            RandomSource random
    ) {
        List<Double> angles = placementPattern.isEvenlySpaced()
                ? createEvenlySpacedAngles(placementPattern, random)
                : createIrregularAngles(placementPattern, random);

        List<BlockPos> anchors = new ArrayList<>();

        for (double angleDegrees : angles) {
            int distance = chooseDistance(
                    context.minimumFrontDistance(),
                    context.maximumFrontDistance(),
                    random
            );

            anchors.add(calculateAnchor(
                    context.targetPos(),
                    angleDegrees,
                    distance
            ));
        }

        return anchors;
    }

    private List<Double> createEvenlySpacedAngles(
            FrontPlacementPattern placementPattern,
            RandomSource random
    ) {
        List<Double> angles = new ArrayList<>();

        double startingAngle = random.nextDouble() * 360.0D;

        for (int index = 0;
             index < placementPattern.getFrontCount();
             index++) {
            angles.add(normaliseAngle(
                    startingAngle
                            + placementPattern.getSeparationDegrees()
                            * index
            ));
        }

        return angles;
    }

    private List<Double> createIrregularAngles(
            FrontPlacementPattern placementPattern,
            RandomSource random
    ) {
        List<Double> angles = new ArrayList<>();

        if (placementPattern.getFrontCount() == 1) {
            angles.add(random.nextDouble() * 360.0D);
            return angles;
        }

        for (int frontIndex = 0;
             frontIndex < placementPattern.getFrontCount();
             frontIndex++) {
            boolean angleFound = false;

            for (int attempt = 0;
                 attempt < MAX_IRREGULAR_ANGLE_ATTEMPTS;
                 attempt++) {
                double candidateAngle =
                        random.nextDouble() * 360.0D;

                if (isSeparatedFromExistingAngles(
                        candidateAngle,
                        angles,
                        placementPattern.getSeparationDegrees()
                )) {
                    angles.add(candidateAngle);
                    angleFound = true;
                    break;
                }
            }

            if (!angleFound) {
                return createFallbackAngles(
                        placementPattern.getFrontCount(),
                        random
                );
            }
        }

        return angles;
    }

    private boolean isSeparatedFromExistingAngles(
            double candidateAngle,
            List<Double> existingAngles,
            double minimumSeparation
    ) {
        for (double existingAngle : existingAngles) {
            double difference = Math.abs(
                    normaliseAngle(candidateAngle)
                            - normaliseAngle(existingAngle)
            );

            difference = Math.min(
                    difference,
                    360.0D - difference
            );

            if (difference < minimumSeparation) {
                return false;
            }
        }

        return true;
    }

    private List<Double> createFallbackAngles(
            int frontCount,
            RandomSource random
    ) {
        List<Double> angles = new ArrayList<>();

        double startingAngle = random.nextDouble() * 360.0D;
        double separation = 360.0D / frontCount;

        for (int index = 0; index < frontCount; index++) {
            angles.add(normaliseAngle(
                    startingAngle + separation * index
            ));
        }

        return angles;
    }

    private int chooseDistance(
            int minimumDistance,
            int maximumDistance,
            RandomSource random
    ) {
        if (maximumDistance <= minimumDistance) {
            return minimumDistance;
        }

        return minimumDistance
                + random.nextInt(
                maximumDistance - minimumDistance + 1
        );
    }

    private BlockPos calculateAnchor(
            BlockPos targetPos,
            double angleDegrees,
            int distance
    ) {
        double radians = Math.toRadians(angleDegrees);

        int offsetX = (int) Math.round(
                Math.cos(radians) * distance
        );

        int offsetZ = (int) Math.round(
                Math.sin(radians) * distance
        );

        return targetPos.offset(offsetX, 0, offsetZ).immutable();
    }

    private double normaliseAngle(double angle) {
        double normalised = angle % 360.0D;

        if (normalised < 0.0D) {
            normalised += 360.0D;
        }

        return normalised;
    }

    /**
     * Converts proportional front/wave weights into exact integer budgets.
     *
     * Floors every allocation first, then distributes any remaining points
     * to the cells with the largest fractional remainders. This ensures the
     * completed allocations exactly equal the supplied total budget.
     */
    private int[][] allocateIntegerBudget(
            int totalBudget,
            double[][] weights
    ) {
        int frontCount = weights.length;
        int waveCount = frontCount == 0
                ? 0
                : weights[0].length;

        int[][] allocations = new int[frontCount][waveCount];
        List<BudgetRemainder> remainders = new ArrayList<>();

        int allocatedBudget = 0;

        for (int frontIndex = 0;
             frontIndex < frontCount;
             frontIndex++) {
            for (int waveIndex = 0;
                 waveIndex < waveCount;
                 waveIndex++) {
                double exactAllocation =
                        totalBudget
                                * weights[frontIndex][waveIndex];

                int floorAllocation =
                        (int) Math.floor(exactAllocation);

                allocations[frontIndex][waveIndex] =
                        floorAllocation;

                allocatedBudget += floorAllocation;

                remainders.add(
                        new BudgetRemainder(
                                frontIndex,
                                waveIndex,
                                exactAllocation - floorAllocation
                        )
                );
            }
        }

        remainders.sort(
                Comparator.comparingDouble(
                        BudgetRemainder::fractionalRemainder
                ).reversed()
        );

        int remainingBudget = totalBudget - allocatedBudget;

        for (int index = 0;
             index < remainingBudget;
             index++) {
            BudgetRemainder remainder =
                    remainders.get(index % remainders.size());

            allocations[remainder.frontIndex()]
                    [remainder.waveIndex()]++;
        }

        return allocations;
    }

    private record BudgetRemainder(
            int frontIndex,
            int waveIndex,
            double fractionalRemainder
    ) {
    }
}