package org.ratden.skavenblight.event.skavenIncursion.planning;

import java.util.Objects;

/**
 * Result returned by one focused planning stage.
 *
 * Focused planners modify the IncursionPlan supplied by the IncursionPlanner,
 * then return either:
 *
 * - success; or
 * - a structured reason that their stage could not complete.
 *
 * The focused planner does not choose another Scenario or Stratagem.
 */
public record PlanningStepResult(
        boolean successful,
        IncursionPlanningResult.PlanningStage failureStage,
        IncursionPlanningResult.PlanningFailureReason failureReason,
        String failureMessage
) {
    public PlanningStepResult {
        if (successful) {
            if (failureStage != null
                    || failureReason != null
                    || failureMessage != null) {
                throw new IllegalArgumentException(
                        "A successful planning step cannot contain failure information."
                );
            }
        } else {
            Objects.requireNonNull(
                    failureStage,
                    "A failed planning step requires a failure stage."
            );

            Objects.requireNonNull(
                    failureReason,
                    "A failed planning step requires a failure reason."
            );

            if (failureMessage == null || failureMessage.isBlank()) {
                throw new IllegalArgumentException(
                        "A failed planning step requires a failure message."
                );
            }
        }
    }

    public static PlanningStepResult success() {
        return new PlanningStepResult(
                true,
                null,
                null,
                null
        );
    }

    public static PlanningStepResult failure(
            IncursionPlanningResult.PlanningStage failureStage,
            IncursionPlanningResult.PlanningFailureReason failureReason,
            String failureMessage
    ) {
        return new PlanningStepResult(
                false,
                failureStage,
                failureReason,
                failureMessage
        );
    }

    public boolean hasFailed() {
        return !successful;
    }
}