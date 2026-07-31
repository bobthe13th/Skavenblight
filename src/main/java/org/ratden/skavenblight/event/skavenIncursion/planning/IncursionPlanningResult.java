package org.ratden.skavenblight.event.skavenIncursion.planning;

import java.util.Objects;

/**
 * Result returned by the incursion planning pipeline.
 *
 * A result contains either:
 * - a successfully completed IncursionPlan; or
 * - a structured failure describing where and why planning stopped.
 *
 * Expected planning failures should use this result rather than exceptions.
 * Exceptions remain appropriate for invalid arguments and broken invariants.
 */
public class IncursionPlanningResult {

    private final IncursionPlan incursionPlan;
    private final PlanningFailure failure;

    private IncursionPlanningResult(
            IncursionPlan incursionPlan,
            PlanningFailure failure
    ) {
        this.incursionPlan = incursionPlan;
        this.failure = failure;
    }

    public static IncursionPlanningResult success(
            IncursionPlan incursionPlan
    ) {
        Objects.requireNonNull(
                incursionPlan,
                "Successful planning result requires an IncursionPlan."
        );

        return new IncursionPlanningResult(
                incursionPlan,
                null
        );
    }

    public static IncursionPlanningResult failure(
            PlanningStage stage,
            PlanningFailureReason reason,
            String message
    ) {
        return new IncursionPlanningResult(
                null,
                new PlanningFailure(
                        stage,
                        reason,
                        message
                )
        );
    }

    public boolean isSuccessful() {
        return incursionPlan != null;
    }

    public boolean hasFailed() {
        return failure != null;
    }

    public IncursionPlan getIncursionPlan() {
        return incursionPlan;
    }

    public PlanningFailure getFailure() {
        return failure;
    }

    /**
     * Identifies the planning stage that could not complete.
     */
    public enum PlanningStage {
        FRONT_PLANNING,
        COMPOSITION_PLANNING,
        SOURCE_PLACEMENT,
        VALIDATION
    }

    /**
     * Broad machine-readable reason for a failed planning attempt.
     *
     * The accompanying message should provide the detailed diagnostic
     * information needed by developers.
     */
    public enum PlanningFailureReason {
        NO_VIABLE_FRONT_PATTERN,
        INVALID_FRONT_ALLOCATION,
        REQUIRED_COMPLEXITY_INFEASIBLE,
        COMPOSITION_EXHAUSTED,
        SOURCE_PLACEMENT_FAILED,
        VALIDATION_FAILED,
        UNKNOWN
    }

    /**
     * Detailed description of one failed planning attempt.
     */
    public record PlanningFailure(
            PlanningStage stage,
            PlanningFailureReason reason,
            String message
    ) {
        public PlanningFailure {
            Objects.requireNonNull(
                    stage,
                    "Planning failure stage cannot be null."
            );

            Objects.requireNonNull(
                    reason,
                    "Planning failure reason cannot be null."
            );

            if (message == null || message.isBlank()) {
                throw new IllegalArgumentException(
                        "Planning failure message cannot be blank."
                );
            }
        }
    }
}