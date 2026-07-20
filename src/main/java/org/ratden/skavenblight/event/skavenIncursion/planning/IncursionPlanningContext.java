package org.ratden.skavenblight.event.skavenIncursion.planning;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.ratden.skavenblight.event.skavenIncursion.director.IncursionTargetType;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.BasePlacementContext;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceDistanceProfile;
import org.ratden.skavenblight.event.skavenIncursion.planning.stratagem.StratagemDefinition;
import org.ratden.skavenblight.event.skavenIncursion.scenario.ScenarioDefinition;

import java.util.Objects;

/**
 * Immutable input passed into the incursion planning pipeline.
 *
 * The Director selects the Scenario, Stratagem, target, placement context,
 * distance profile, and total budgets. Focused planners read those selections
 * but do not change them.
 */
public record IncursionPlanningContext(
        ServerLevel level,
        ScenarioDefinition scenarioDefinition,
        StratagemDefinition stratagemDefinition,
        BasePlacementContext placementContext,
        SourceDistanceProfile frontDistanceProfile,
        int totalThreatBudget,
        int totalComplexityBudget
) {
    public IncursionPlanningContext {
        Objects.requireNonNull(
                level,
                "Planning level cannot be null."
        );

        Objects.requireNonNull(
                scenarioDefinition,
                "Scenario definition cannot be null."
        );

        Objects.requireNonNull(
                stratagemDefinition,
                "Stratagem definition cannot be null."
        );

        Objects.requireNonNull(
                placementContext,
                "Base placement context cannot be null."
        );

        Objects.requireNonNull(
                frontDistanceProfile,
                "Front distance profile cannot be null."
        );

        if (!scenarioDefinition.allowsTargetType(
                placementContext.getTargetType()
        )) {
            throw new IllegalArgumentException(
                    "Selected Scenario does not allow target type "
                            + placementContext.getTargetType()
                            + "."
            );
        }

        if (totalThreatBudget < 0) {
            throw new IllegalArgumentException(
                    "Total threat budget cannot be negative."
            );
        }

        if (totalComplexityBudget < 0) {
            throw new IllegalArgumentException(
                    "Total complexity budget cannot be negative."
            );
        }
    }

    public IncursionTargetType targetType() {
        return placementContext.getTargetType();
    }

    public BlockPos targetPos() {
        return placementContext.getTargetPos();
    }

    public int baseRadius() {
        return placementContext.getBaseRadius();
    }

    public int minimumFrontDistance() {
        return frontDistanceProfile.getMinDistanceFromTarget(
                baseRadius()
        );
    }

    public int maximumFrontDistance() {
        return frontDistanceProfile.getMaxDistanceFromTarget(
                baseRadius()
        );
    }
}