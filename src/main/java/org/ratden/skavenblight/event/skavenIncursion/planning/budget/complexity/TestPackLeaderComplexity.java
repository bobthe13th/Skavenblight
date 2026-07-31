package org.ratden.skavenblight.event.skavenIncursion.planning.budget.complexity;

import org.ratden.skavenblight.event.skavenIncursion.planning.composition.AttachedMobComplexityOption;
import org.ratden.skavenblight.event.skavenIncursion.planning.composition.IncursionMobCatalogue;
import org.ratden.skavenblight.event.skavenIncursion.planning.composition.SourceGroupComposition;

import java.util.Set;

/**
 * Temporary Pack-leader complexity option used to test attached complexity,
 * leadership grouping and promoted-mob runtime execution.
 */
public final class TestPackLeaderComplexity {

    public static final ComplexityOptionDefinition DEFINITION =
            new ComplexityOptionDefinition(
                    "test_pack_leader",
                    1,
                    ComplexityOptionCategory.ATTACHED
            );

    /**
     * Promotes one already-budgeted Wolf Cat, schedules it before ordinary
     * mobs and creates a Pack identity for its complete source composition.
     */
    public static final AttachedMobComplexityOption OPTION =
            new AttachedMobComplexityOption(
                    DEFINITION,
                    Set.of(
                            IncursionMobCatalogue.WOLF_CAT.getMobId()
                    ),
                    SourceGroupComposition
                            .AttachedMobSpawnPriority
                            .BEFORE_ORDINARY,
                    true
            );

    private TestPackLeaderComplexity() {
    }
}