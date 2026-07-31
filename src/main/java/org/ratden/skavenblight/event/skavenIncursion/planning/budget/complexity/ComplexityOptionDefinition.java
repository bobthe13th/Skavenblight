package org.ratden.skavenblight.event.skavenIncursion.planning.budget.complexity;

import java.util.Objects;

/**
 * Shared metadata for a complexity option.
 *
 * Detailed eligibility checks, target selection, planning effects, and execution
 * behaviour belong to the individual complexity-option implementation.
 */
public record ComplexityOptionDefinition(
        String id,
        int complexityCost,
        ComplexityOptionCategory category
) {
    public ComplexityOptionDefinition {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(
                    "Complexity option ID cannot be blank."
            );
        }

        if (complexityCost <= 0) {
            throw new IllegalArgumentException(
                    "Complexity cost must be greater than zero."
            );
        }

        category = Objects.requireNonNull(
                category,
                "Complexity option category cannot be null."
        );
    }
}