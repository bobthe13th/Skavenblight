package org.ratden.skavenblight.event.skavenIncursion.debug;

import net.minecraft.core.BlockPos;
import org.ratden.skavenblight.block.entity.state.SourceState;
import org.ratden.skavenblight.event.skavenIncursion.planning.composition.SourceGroupComposition;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourcePlacementPlan;
import org.ratden.skavenblight.event.skavenIncursion.runtime.IncursionWaveController;
import org.ratden.skavenblight.event.skavenIncursion.runtime.mob.IncursionMobTrackingState;
import org.ratden.skavenblight.event.skavenIncursion.runtime.persistence.PersistentIncursionPhase;
import org.ratden.skavenblight.event.skavenIncursion.runtime.source.SourceExecutionState;
import org.ratden.skavenblight.event.skavenIncursion.runtime.source.SourceWaveExecutionState;

import java.util.Objects;
import java.util.UUID;

/**
 * Fully resolved immutable context for inspecting one looked-at physical
 * incursion source.
 *
 * Target resolution belongs to the command-side inspection service. Report
 * formatting belongs to IncursionRuntimeDebugFormatter. This record forms the
 * boundary between those responsibilities.
 *
 * Every focused inspection view receives the same captured runtime state:
 *
 * - root incursion and Scenario identity;
 * - wave-controller state;
 * - physical source placement and incarnation state;
 * - the most relevant wave-specific source assignment;
 * - immutable composition state;
 * - authoritative persistent mob tracking.
 *
 * Capturing the values once prevents separate inspection views from resolving
 * subtly different assignments or observing different runtime checkpoints.
 */
public record IncursionSourceInspectionContext(
        String scenarioId,
        String stratagemId,
        UUID incursionId,
        PersistentIncursionPhase persistentPhase,
        IncursionWaveController.Snapshot controllerSnapshot,
        BlockPos sourcePos,
        SourceState physicalSourceState,
        UUID runtimeSourceId,
        SourcePlacementPlan sourcePlacementPlan,
        SourceExecutionState.Snapshot sourceExecutionSnapshot,
        int waveIndex,
        AssignmentRelation assignmentRelation,
        int frontIndex,
        SourceGroupComposition sourceGroupComposition,
        SourceGroupComposition.SourceComposition sourceComposition,
        SourceWaveExecutionState.Snapshot sourceWaveSnapshot,
        IncursionMobTrackingState.Snapshot mobTrackingSnapshot
) {

    public IncursionSourceInspectionContext {
        requireNonBlank(
                scenarioId,
                "Inspection Scenario ID cannot be blank."
        );

        requireNonBlank(
                stratagemId,
                "Inspection Stratagem ID cannot be blank."
        );

        Objects.requireNonNull(
                incursionId,
                "Inspection incursion ID cannot be null."
        );

        Objects.requireNonNull(
                persistentPhase,
                "Inspection persistence phase cannot be null."
        );

        Objects.requireNonNull(
                controllerSnapshot,
                "Inspection wave-controller snapshot cannot be null."
        );

        Objects.requireNonNull(
                sourcePos,
                "Inspection source position cannot be null."
        );

        sourcePos =
                sourcePos.immutable();

        Objects.requireNonNull(
                physicalSourceState,
                "Inspection physical source state cannot be null."
        );

        Objects.requireNonNull(
                runtimeSourceId,
                "Inspection runtime source ID cannot be null."
        );

        Objects.requireNonNull(
                sourcePlacementPlan,
                "Inspection source-placement plan cannot be null."
        );

        Objects.requireNonNull(
                sourceExecutionSnapshot,
                "Inspection source-execution snapshot cannot be null."
        );

        if (waveIndex < 0) {
            throw new IllegalArgumentException(
                    "Inspection wave index cannot be negative."
            );
        }

        Objects.requireNonNull(
                assignmentRelation,
                "Inspection assignment relation cannot be null."
        );

        if (frontIndex < 0) {
            throw new IllegalArgumentException(
                    "Inspection front index cannot be negative."
            );
        }

        Objects.requireNonNull(
                sourceGroupComposition,
                "Inspection source-group composition cannot be null."
        );

        Objects.requireNonNull(
                sourceComposition,
                "Inspection source composition cannot be null."
        );

        Objects.requireNonNull(
                sourceWaveSnapshot,
                "Inspection source-wave snapshot cannot be null."
        );

        Objects.requireNonNull(
                mobTrackingSnapshot,
                "Inspection mob-tracking snapshot cannot be null."
        );

        validateIncursionIdentities(
                incursionId,
                controllerSnapshot,
                mobTrackingSnapshot
        );

        validatePhysicalSourceBinding(
                sourcePos,
                runtimeSourceId,
                sourcePlacementPlan,
                sourceExecutionSnapshot
        );

        validateCompositionBinding(
                waveIndex,
                sourcePlacementPlan,
                sourceGroupComposition,
                sourceComposition,
                sourceWaveSnapshot
        );

        if (assignmentRelation
                == AssignmentRelation.CURRENT
                && controllerSnapshot.getCurrentWaveIndex()
                != waveIndex) {

            throw new IllegalArgumentException(
                    "Inspection marks wave "
                            + waveIndex
                            + " as current, but controller reports wave "
                            + controllerSnapshot.getCurrentWaveIndex()
                            + "."
            );
        }
    }

    public UUID sourceGroupPlacementId() {
        return sourcePlacementPlan.getSourceGroupPlacementId();
    }

    public UUID sourcePlacementId() {
        return sourcePlacementPlan.getSourcePlacementId();
    }

    public UUID sourceGroupCompositionId() {
        return sourceGroupComposition.getSourceGroupCompositionId();
    }

    public UUID sourceCompositionId() {
        return sourceComposition.getSourceCompositionId();
    }

    private static void validateIncursionIdentities(
            UUID incursionId,
            IncursionWaveController.Snapshot controllerSnapshot,
            IncursionMobTrackingState.Snapshot mobTrackingSnapshot
    ) {
        if (!incursionId.equals(
                controllerSnapshot.incursionId()
        )) {
            throw new IllegalArgumentException(
                    "Inspection incursion ID "
                            + incursionId
                            + " does not match wave-controller incursion ID "
                            + controllerSnapshot.incursionId()
                            + "."
            );
        }

        if (!incursionId.equals(
                mobTrackingSnapshot.incursionId()
        )) {
            throw new IllegalArgumentException(
                    "Inspection incursion ID "
                            + incursionId
                            + " does not match mob-tracking incursion ID "
                            + mobTrackingSnapshot.incursionId()
                            + "."
            );
        }
    }

    private static void validatePhysicalSourceBinding(
            BlockPos sourcePos,
            UUID runtimeSourceId,
            SourcePlacementPlan sourcePlacementPlan,
            SourceExecutionState.Snapshot sourceExecutionSnapshot
    ) {
        UUID sourcePlacementId =
                sourcePlacementPlan.getSourcePlacementId();

        if (!sourcePlacementId.equals(
                sourceExecutionSnapshot.sourcePlacementId()
        )) {
            throw new IllegalArgumentException(
                    "Source-placement plan ID "
                            + sourcePlacementId
                            + " does not match source-execution placement ID "
                            + sourceExecutionSnapshot.sourcePlacementId()
                            + "."
            );
        }

        if (!runtimeSourceId.equals(
                sourceExecutionSnapshot.runtimeSourceId()
        )) {
            throw new IllegalArgumentException(
                    "Looked-at runtime source ID "
                            + runtimeSourceId
                            + " does not match current logical incarnation "
                            + sourceExecutionSnapshot.runtimeSourceId()
                            + "."
            );
        }

        if (!sourceExecutionSnapshot
                .runtimeSourceIdHistory()
                .contains(
                        runtimeSourceId
                )) {

            throw new IllegalArgumentException(
                    "Current runtime source ID "
                            + runtimeSourceId
                            + " is absent from the physical source's "
                            + "incarnation history."
            );
        }

        if (sourcePlacementPlan.hasPlacedPos()
                && !sourcePos.equals(
                sourcePlacementPlan.getPlacedPos()
        )) {

            throw new IllegalArgumentException(
                    "Looked-at source position "
                            + formatBlockPos(
                            sourcePos
                    )
                            + " does not match planned placed position "
                            + formatBlockPos(
                            sourcePlacementPlan.getPlacedPos()
                    )
                            + "."
            );
        }
    }

    private static void validateCompositionBinding(
            int waveIndex,
            SourcePlacementPlan sourcePlacementPlan,
            SourceGroupComposition sourceGroupComposition,
            SourceGroupComposition.SourceComposition sourceComposition,
            SourceWaveExecutionState.Snapshot sourceWaveSnapshot
    ) {
        UUID sourceCompositionId =
                sourceComposition.getSourceCompositionId();

        if (sourceWaveSnapshot.waveIndex()
                != waveIndex) {

            throw new IllegalArgumentException(
                    "Source-wave snapshot belongs to wave "
                            + sourceWaveSnapshot.waveIndex()
                            + " rather than resolved wave "
                            + waveIndex
                            + "."
            );
        }

        if (!sourcePlacementPlan
                .getSourcePlacementId()
                .equals(
                        sourceWaveSnapshot.sourcePlacementId()
                )) {

            throw new IllegalArgumentException(
                    "Source-wave snapshot placement ID "
                            + sourceWaveSnapshot.sourcePlacementId()
                            + " does not match resolved source placement "
                            + sourcePlacementPlan.getSourcePlacementId()
                            + "."
            );
        }

        if (!sourceCompositionId.equals(
                sourceWaveSnapshot.sourceCompositionId()
        )) {
            throw new IllegalArgumentException(
                    "Source-wave snapshot composition ID "
                            + sourceWaveSnapshot.sourceCompositionId()
                            + " does not match immutable composition "
                            + sourceCompositionId
                            + "."
            );
        }

        if (sourceGroupComposition.getSourceComposition(
                sourceCompositionId
        ) != sourceComposition) {
            throw new IllegalArgumentException(
                    "Resolved source composition is not the exact immutable "
                            + "child of source-group composition "
                            + sourceGroupComposition
                            .getSourceGroupCompositionId()
                            + "."
            );
        }

        if (!sourcePlacementPlan.isBoundToSourceComposition(
                sourceCompositionId
        )) {
            throw new IllegalArgumentException(
                    "Physical source placement "
                            + sourcePlacementPlan.getSourcePlacementId()
                            + " is not bound to source composition "
                            + sourceCompositionId
                            + "."
            );
        }

        if (sourcePlacementPlan.getSourceType()
                != sourceComposition.getRequiredSourceType()) {

            throw new IllegalArgumentException(
                    "Source placement type does not match immutable "
                            + "composition requirement."
            );
        }

        if (sourcePlacementPlan.getSourceRole()
                != sourceComposition.getSourceRole()) {

            throw new IllegalArgumentException(
                    "Source placement role does not match immutable "
                            + "composition requirement."
            );
        }

        if (!sourcePlacementPlan
                .getSourceSize()
                .canFit(
                        sourceComposition.getRequiredSourceSize()
                )) {

            throw new IllegalArgumentException(
                    "Source placement is too small for immutable composition "
                            + sourceCompositionId
                            + "."
            );
        }
    }

    private static String formatBlockPos(
            BlockPos blockPos
    ) {
        return blockPos.getX()
                + ", "
                + blockPos.getY()
                + ", "
                + blockPos.getZ();
    }

    private static void requireNonBlank(
            String value,
            String message
    ) {
        if (value == null
                || value.isBlank()) {

            throw new IllegalArgumentException(
                    message
            );
        }
    }

    /**
     * Relationship between the selected physical source and the assignment
     * chosen for inspection.
     */
    public enum AssignmentRelation {
        CURRENT("current wave"),
        UPCOMING("upcoming wave"),
        PREVIOUS("previous wave");

        private final String displayName;

        AssignmentRelation(
                String displayName
        ) {
            this.displayName =
                    displayName;
        }

        public String displayName() {
            return displayName;
        }
    }
}