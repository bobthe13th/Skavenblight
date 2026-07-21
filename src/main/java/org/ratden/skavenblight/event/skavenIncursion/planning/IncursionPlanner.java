package org.ratden.skavenblight.event.skavenIncursion.planning;

import org.ratden.skavenblight.event.skavenIncursion.planning.composition.CompositionPlanner;
import org.ratden.skavenblight.event.skavenIncursion.planning.front.FrontPlanner;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourcePlacementPlanner;
import org.ratden.skavenblight.event.skavenIncursion.planning.validation.IncursionPlanValidator;
import org.ratden.skavenblight.event.skavenIncursion.planning.validation.PlanValidationResult;

import java.util.Objects;

/**
 * Coordinates the focused incursion-planning stages.
 *
 * The IncursionPlanner does not select the Scenario, Stratagem, target, or
 * budgets. Those decisions are supplied through IncursionPlanningContext.
 *
 * Each planning attempt creates a completely new IncursionPlan and runs:
 *
 * 1. front planning;
 * 2. composition planning;
 * 3. source-placement planning;
 * 4. final validation.
 *
 * Retryable failures discard the incomplete plan and begin again. No source
 * blocks or mobs are created during planning, so failed attempts do not need
 * to undo world changes.
 */
public class IncursionPlanner {

    private static final int DEFAULT_MAX_PLAN_ATTEMPTS = 3;

    private final FrontPlanner frontPlanner;
    private final CompositionPlanner compositionPlanner;
    private final SourcePlacementPlanner sourcePlacementPlanner;
    private final IncursionPlanValidator planValidator;

    private final int maxPlanAttempts;

    public IncursionPlanner() {
        this(
                new FrontPlanner(),
                new CompositionPlanner(),
                new SourcePlacementPlanner(),
                new IncursionPlanValidator(),
                DEFAULT_MAX_PLAN_ATTEMPTS
        );
    }

    public IncursionPlanner(
            FrontPlanner frontPlanner,
            CompositionPlanner compositionPlanner,
            SourcePlacementPlanner sourcePlacementPlanner,
            IncursionPlanValidator planValidator,
            int maxPlanAttempts
    ) {
        this.frontPlanner = Objects.requireNonNull(
                frontPlanner,
                "Front planner cannot be null."
        );

        this.compositionPlanner = Objects.requireNonNull(
                compositionPlanner,
                "Composition planner cannot be null."
        );

        this.sourcePlacementPlanner = Objects.requireNonNull(
                sourcePlacementPlanner,
                "Source-placement planner cannot be null."
        );

        this.planValidator = Objects.requireNonNull(
                planValidator,
                "Incursion-plan validator cannot be null."
        );

        if (maxPlanAttempts <= 0) {
            throw new IllegalArgumentException(
                    "Maximum planning attempts must be greater than zero."
            );
        }

        this.maxPlanAttempts = maxPlanAttempts;
    }

    /**
     * Attempts to create a complete, validated IncursionPlan.
     *
     * Expected planning dead ends are returned as structured failures.
     * Invalid arguments and broken implementation invariants may still throw
     * exceptions.
     */
    public IncursionPlanningResult plan(
            IncursionPlanningContext context
    ) {
        Objects.requireNonNull(
                context,
                "Planning context cannot be null."
        );

        for (int attempt = 1;
             attempt <= maxPlanAttempts;
             attempt++) {

            IncursionPlan incursionPlan =
                    new IncursionPlan();

            PlanningStepResult planningStepResult =
                    populatePlan(
                            context,
                            incursionPlan
                    );

            if (planningStepResult.hasFailed()) {
                boolean attemptsRemain =
                        attempt < maxPlanAttempts;

                if (attemptsRemain
                        && isRetryable(planningStepResult)) {
                    continue;
                }

                return createFocusedFailure(
                        planningStepResult,
                        attempt
                );
            }

            PlanValidationResult validationResult =
                    planValidator.validate(
                            context,
                            incursionPlan
                    );

            if (!validationResult.valid()) {
                /*
                 * Validation failures are not retried.
                 *
                 * The focused planners should already report ordinary
                 * environmental or planning dead ends. A completed plan that
                 * fails validation normally indicates a broken invariant or
                 * implementation defect that retrying could conceal.
                 */
                return IncursionPlanningResult.failure(
                        IncursionPlanningResult.PlanningStage
                                .VALIDATION,
                        IncursionPlanningResult.PlanningFailureReason
                                .VALIDATION_FAILED,
                        "Incursion plan "
                                + incursionPlan.getIncursionId()
                                + " failed validation on planning attempt "
                                + attempt
                                + " of "
                                + maxPlanAttempts
                                + ": "
                                + validationResult.failureMessage()
                );
            }

            return IncursionPlanningResult.success(
                    incursionPlan
            );
        }

        /*
         * The loop can only finish through success or one of the structured
         * failure returns above. This remains as defensive protection if the
         * control flow is changed later.
         */
        return IncursionPlanningResult.failure(
                IncursionPlanningResult.PlanningStage
                        .VALIDATION,
                IncursionPlanningResult.PlanningFailureReason
                        .UNKNOWN,
                "Incursion planning ended without producing either a plan "
                        + "or a specific planning failure."
        );
    }

    private PlanningStepResult populatePlan(
            IncursionPlanningContext context,
            IncursionPlan incursionPlan
    ) {
        PlanningStepResult frontResult =
                frontPlanner.planFronts(
                        context,
                        incursionPlan
                );

        if (frontResult.hasFailed()) {
            return frontResult;
        }

        PlanningStepResult compositionResult =
                compositionPlanner.planComposition(
                        context,
                        incursionPlan
                );

        if (compositionResult.hasFailed()) {
            return compositionResult;
        }

        return sourcePlacementPlanner.planSourcePlacements(
                context,
                incursionPlan
        );
    }

    /**
     * Returns whether a fresh plan may reasonably succeed without changing
     * the selected Scenario, Stratagem, target, or budgets.
     */
    private boolean isRetryable(
            PlanningStepResult planningStepResult
    ) {
        return switch (planningStepResult.failureReason()) {
            /*
             * These failures may change when front angles, mob selections,
             * source positions, or other random planning decisions are
             * generated again.
             */
            case NO_VIABLE_FRONT_PATTERN,
                 COMPOSITION_EXHAUSTED,
                 SOURCE_PLACEMENT_FAILED -> true;

            /*
             * These normally represent invalid authored inputs,
             * deterministic infeasibility, or an implementation problem.
             */
            case INVALID_FRONT_ALLOCATION,
                 REQUIRED_COMPLEXITY_INFEASIBLE,
                 VALIDATION_FAILED,
                 UNKNOWN -> false;
        };
    }

    private IncursionPlanningResult createFocusedFailure(
            PlanningStepResult planningStepResult,
            int finalAttempt
    ) {
        String attemptDescription;

        if (isRetryable(planningStepResult)
                && finalAttempt >= maxPlanAttempts) {
            attemptDescription =
                    "Incursion planning exhausted "
                            + maxPlanAttempts
                            + " attempts. Final failure: ";
        } else {
            attemptDescription =
                    "Incursion planning stopped on attempt "
                            + finalAttempt
                            + " of "
                            + maxPlanAttempts
                            + ": ";
        }

        return IncursionPlanningResult.failure(
                planningStepResult.failureStage(),
                planningStepResult.failureReason(),
                attemptDescription
                        + planningStepResult.failureMessage()
        );
    }

    public int getMaxPlanAttempts() {
        return maxPlanAttempts;
    }
}