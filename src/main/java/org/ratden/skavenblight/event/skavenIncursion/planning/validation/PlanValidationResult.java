package org.ratden.skavenblight.event.skavenIncursion.planning.validation;

/**
 * Result of validating an IncursionPlan.
 *
 * Validation only needs to report whether the existing plan is valid and,
 * when invalid, explain why. It does not create a new plan or repair the
 * existing one.
 */
public record PlanValidationResult(
        boolean valid,
        String failureMessage
) {
    public PlanValidationResult {
        if (valid && failureMessage != null) {
            throw new IllegalArgumentException(
                    "A valid result cannot contain a failure message."
            );
        }

        if (!valid && (failureMessage == null || failureMessage.isBlank())) {
            throw new IllegalArgumentException(
                    "An invalid result requires a failure message."
            );
        }
    }

    public static PlanValidationResult success() {
        return new PlanValidationResult(true, null);
    }

    public static PlanValidationResult failure(String failureMessage) {
        return new PlanValidationResult(false, failureMessage);
    }
}