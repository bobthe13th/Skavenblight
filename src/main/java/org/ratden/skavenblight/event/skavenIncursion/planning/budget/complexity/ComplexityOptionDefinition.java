package org.ratden.skavenblight.event.skavenIncursion.planning.budget.complexity;

public record ComplexityOptionDefinition(
        String id,
        int complexityCost
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
    }
}