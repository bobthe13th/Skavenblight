package org.ratden.skavenblight.event.skavenIncursion.planning;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.ratden.skavenblight.event.skavenIncursion.director.IncursionTargetType;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.BasePlacementContext;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceDistanceProfile;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.WarpFluxNetworkGeometry;
import org.ratden.skavenblight.event.skavenIncursion.planning.stratagem.StratagemDefinition;
import org.ratden.skavenblight.event.skavenIncursion.scenario.ScenarioDefinition;
import org.ratden.skavenblight.world.NexusTracker;

import java.util.Objects;

/**
 * Immutable input passed into the incursion-planning pipeline.
 *
 * The Director selects the Scenario, Stratagem, target, placement context,
 * distance profile and total budgets. Focused planners read those selections
 * but do not change them.
 *
 * When an active Nexus exists, this context also captures an immutable
 * snapshot of its connected Warp Flux network. Every planning stage and every
 * whole-plan retry belonging to this context must use that same snapshot.
 *
 * Changes made to the live network after this context is created therefore do
 * not move front anchors or invalidate later-wave source placements.
 */
public record IncursionPlanningContext(
        ServerLevel level,
        ScenarioDefinition scenarioDefinition,
        StratagemDefinition stratagemDefinition,
        BasePlacementContext placementContext,
        SourceDistanceProfile frontDistanceProfile,
        WarpFluxNetworkGeometry protectedNetworkGeometrySnapshot,
        int totalThreatBudget,
        int totalComplexityBudget
) {

    /**
     * Backwards-compatible constructor used by existing Director and debug
     * code.
     *
     * The protected network snapshot is resolved automatically by the
     * canonical constructor.
     */
    public IncursionPlanningContext(
            ServerLevel level,
            ScenarioDefinition scenarioDefinition,
            StratagemDefinition stratagemDefinition,
            BasePlacementContext placementContext,
            SourceDistanceProfile frontDistanceProfile,
            int totalThreatBudget,
            int totalComplexityBudget
    ) {
        this(
                level,
                scenarioDefinition,
                stratagemDefinition,
                placementContext,
                frontDistanceProfile,
                null,
                totalThreatBudget,
                totalComplexityBudget
        );
    }

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

        /*
         * Resolve the snapshot exactly once when the context is created.
         *
         * A non-null supplied snapshot is retained, allowing future replay,
         * deserialisation or deterministic testing code to provide an
         * already-captured geometry.
         */
        if (protectedNetworkGeometrySnapshot == null) {
            protectedNetworkGeometrySnapshot =
                    resolveProtectedNetworkGeometrySnapshot(
                            level,
                            placementContext
                    );
        }

        if (!scenarioDefinition.allowsTargetType(
                placementContext.getTargetType()
        )) {
            throw new IllegalArgumentException(
                    "Selected Scenario does not allow target type "
                            + placementContext.getTargetType()
                            + "."
            );
        }

        if (!scenarioDefinition.allowsStratagem(
                stratagemDefinition
        )) {
            throw new IllegalArgumentException(
                    "Selected Scenario does not allow Stratagem "
                            + stratagemDefinition.getId()
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

    public boolean hasProtectedNetworkGeometrySnapshot() {
        return protectedNetworkGeometrySnapshot != null;
    }

    /**
     * Captures the network belonging to the active Nexus.
     *
     * This is independent of the current incursion target. A player-targeted
     * incursion must still respect an existing defended base.
     *
     * The target position is used as a fallback when this is explicitly a
     * Nexus-targeted context but the NexusTracker has not yet exposed that
     * Nexus as active.
     */
    private static WarpFluxNetworkGeometry
    resolveProtectedNetworkGeometrySnapshot(
            ServerLevel level,
            BasePlacementContext placementContext
    ) {
        if (NexusTracker.hasActiveNexus(level)) {
            BlockPos activeNexusPos =
                    NexusTracker
                            .getActiveNexusPos(level)
                            .immutable();

            return WarpFluxNetworkGeometry.resolve(
                    level,
                    activeNexusPos
            );
        }

        if (placementContext.getTargetType()
                == IncursionTargetType.NEXUS) {
            return WarpFluxNetworkGeometry.resolve(
                    level,
                    placementContext.getTargetPos()
            );
        }

        return null;
    }
}