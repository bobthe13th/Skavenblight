package org.ratden.skavenblight.event.skavenIncursion.scenario.testing;

import org.ratden.skavenblight.event.skavenIncursion.planning.budget.complexity.ComplexityOptionCategory;
import org.ratden.skavenblight.event.skavenIncursion.planning.budget.complexity.ComplexityOptionDefinition;

public final class TestPackLeaderComplexity {

    public static final ComplexityOptionDefinition DEFINITION =
            new ComplexityOptionDefinition(
                    "test_pack_leader",
                    1,
                    ComplexityOptionCategory.ATTACHED
            );

    private TestPackLeaderComplexity() {
    }
}