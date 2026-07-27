package org.ratden.skavenblight.event.skavenIncursion.action.mob.modifier;

import net.minecraft.world.entity.Entity;
import org.ratden.skavenblight.entity.custom.wolfCat.WolfCat;
import org.ratden.skavenblight.event.skavenIncursion.planning.budget.complexity.TestPackLeaderComplexity;
import org.ratden.skavenblight.event.skavenIncursion.planning.composition.IncursionMobCatalogue;
import org.ratden.skavenblight.event.skavenIncursion.planning.composition.SourceGroupComposition;

/**
 * Runtime dispatcher for attached-mob complexity options.
 *
 * Composition planning decides:
 *
 * - which already-budgeted mob is promoted;
 * - which attached complexity option it receives;
 * - its spawn priority;
 * - any group-owned leadership identity associated with it.
 *
 * This executor receives the actual successfully spawned entity and applies
 * the authored runtime effect. It does not select mobs, consume queues or
 * create persistence bindings.
 *
 * The current implementation supports the temporary test Pack leader. Future
 * attached options should be added here or moved behind a registry without
 * placing option-specific entity logic inside SourceWaveExecutionState.
 */
public final class AttachedMobRuntimeModifierExecutor {

    /**
     * Validates that one attached assignment has a complete supported runtime
     * interpretation.
     *
     * This can run before spawning so unsupported or structurally incomplete
     * plans fail before an entity is created.
     */
    public static void validatePlanBinding(
            SourceGroupComposition sourceGroupComposition,
            SourceGroupComposition.SourceComposition sourceComposition,
            SourceGroupComposition.AttachedMobAssignment assignment
    ) {
        validateCommonPlanBinding(
                sourceGroupComposition,
                sourceComposition,
                assignment
        );

        String complexityOptionId =
                assignment
                        .complexityOption()
                        .id();

        if (TestPackLeaderComplexity.DEFINITION
                .id()
                .equals(
                        complexityOptionId
                )) {

            validateTestPackLeaderBinding(
                    sourceGroupComposition,
                    sourceComposition,
                    assignment
            );

            return;
        }

        throw new UnsupportedOperationException(
                "No runtime attached-mob modifier exists for complexity "
                        + "option ID "
                        + complexityOptionId
                        + "."
        );
    }

    /**
     * Applies one planned attached-mob modifier to the actual spawned entity.
     */
    public static void apply(
            SourceGroupComposition sourceGroupComposition,
            SourceGroupComposition.SourceComposition sourceComposition,
            SourceGroupComposition.AttachedMobAssignment assignment,
            String spawnedMobId,
            Entity spawnedEntity
    ) {
        validatePlanBinding(
                sourceGroupComposition,
                sourceComposition,
                assignment
        );

        if (spawnedMobId == null
                || spawnedMobId.isBlank()) {

            throw new IllegalArgumentException(
                    "Spawned attached-mob ID cannot be blank."
            );
        }

        if (spawnedEntity == null) {
            throw new IllegalArgumentException(
                    "Spawned attached-mob entity cannot be null."
            );
        }

        if (spawnedEntity.isRemoved()) {
            throw new IllegalArgumentException(
                    "A removed entity cannot receive an attached-mob "
                            + "modifier."
            );
        }

        if (!assignment.mobId().equals(
                spawnedMobId
        )) {
            throw new IllegalArgumentException(
                    "Attached assignment "
                            + assignment.attachedMobAssignmentId()
                            + " requires mob ID "
                            + assignment.mobId()
                            + " but runtime spawned "
                            + spawnedMobId
                            + "."
            );
        }

        String complexityOptionId =
                assignment
                        .complexityOption()
                        .id();

        if (TestPackLeaderComplexity.DEFINITION
                .id()
                .equals(
                        complexityOptionId
                )) {

            applyTestPackLeader(
                    sourceGroupComposition,
                    assignment,
                    spawnedEntity
            );

            return;
        }

        /*
         * validatePlanBinding(...) should have rejected this before reaching
         * the dispatch stage.
         */
        throw new UnsupportedOperationException(
                "No runtime attached-mob modifier exists for complexity "
                        + "option ID "
                        + complexityOptionId
                        + "."
        );
    }

    private static void validateCommonPlanBinding(
            SourceGroupComposition sourceGroupComposition,
            SourceGroupComposition.SourceComposition sourceComposition,
            SourceGroupComposition.AttachedMobAssignment assignment
    ) {
        if (sourceGroupComposition == null) {
            throw new IllegalArgumentException(
                    "Attached-mob source-group composition cannot be null."
            );
        }

        if (sourceComposition == null) {
            throw new IllegalArgumentException(
                    "Attached-mob source composition cannot be null."
            );
        }

        if (assignment == null) {
            throw new IllegalArgumentException(
                    "Attached-mob assignment cannot be null."
            );
        }

        SourceGroupComposition.SourceComposition
                containedSourceComposition =
                sourceGroupComposition.getSourceComposition(
                        sourceComposition.getSourceCompositionId()
                );

        if (containedSourceComposition != sourceComposition) {
            throw new IllegalArgumentException(
                    "Attached-mob source composition "
                            + sourceComposition.getSourceCompositionId()
                            + " is not the exact child composition owned by "
                            + "source group "
                            + sourceGroupComposition
                            .getSourceGroupCompositionId()
                            + "."
            );
        }

        SourceGroupComposition.AttachedMobAssignment
                containedAssignment =
                sourceComposition.getAttachedMobAssignment(
                        assignment.attachedMobAssignmentId()
                );

        if (containedAssignment != assignment) {
            throw new IllegalArgumentException(
                    "Attached-mob assignment "
                            + assignment.attachedMobAssignmentId()
                            + " is not the exact assignment owned by source "
                            + "composition "
                            + sourceComposition.getSourceCompositionId()
                            + "."
            );
        }

        if (sourceComposition.getMobCount(
                assignment.mobId()
        ) <= 0) {
            throw new IllegalArgumentException(
                    "Attached-mob assignment "
                            + assignment.attachedMobAssignmentId()
                            + " refers to mob ID "
                            + assignment.mobId()
                            + " that does not exist in its source "
                            + "composition."
            );
        }
    }

    private static void validateTestPackLeaderBinding(
            SourceGroupComposition sourceGroupComposition,
            SourceGroupComposition.SourceComposition sourceComposition,
            SourceGroupComposition.AttachedMobAssignment assignment
    ) {
        String wolfCatMobId =
                IncursionMobCatalogue.WOLF_CAT
                        .getMobId();

        if (!wolfCatMobId.equals(
                assignment.mobId()
        )) {
            throw new IllegalArgumentException(
                    "Test Pack leader assignment "
                            + assignment.attachedMobAssignmentId()
                            + " requires mob ID "
                            + wolfCatMobId
                            + " rather than "
                            + assignment.mobId()
                            + "."
            );
        }

        SourceGroupComposition.PackAssignment packAssignment =
                sourceGroupComposition.getPackAssignment();

        if (packAssignment == null) {
            throw new IllegalArgumentException(
                    "Test Pack leader assignment "
                            + assignment.attachedMobAssignmentId()
                            + " has no group-owned Pack assignment."
            );
        }

        if (!sourceComposition
                .getSourceCompositionId()
                .equals(
                        packAssignment.sourceCompositionId()
                )) {

            throw new IllegalArgumentException(
                    "Pack "
                            + packAssignment.packId()
                            + " identifies source composition "
                            + packAssignment.sourceCompositionId()
                            + " rather than the Pack leader's composition "
                            + sourceComposition.getSourceCompositionId()
                            + "."
            );
        }

        if (!assignment
                .attachedMobAssignmentId()
                .equals(
                        packAssignment.attachedMobAssignmentId()
                )) {

            throw new IllegalArgumentException(
                    "Pack "
                            + packAssignment.packId()
                            + " identifies attached assignment "
                            + packAssignment.attachedMobAssignmentId()
                            + " rather than test Pack leader assignment "
                            + assignment.attachedMobAssignmentId()
                            + "."
            );
        }
    }

    private static void applyTestPackLeader(
            SourceGroupComposition sourceGroupComposition,
            SourceGroupComposition.AttachedMobAssignment assignment,
            Entity spawnedEntity
    ) {
        if (!(spawnedEntity
                instanceof WolfCat wolfCat)) {

            throw new IllegalArgumentException(
                    "Test Pack leader assignment "
                            + assignment.attachedMobAssignmentId()
                            + " spawned entity type "
                            + spawnedEntity.getType()
                            + " rather than WolfCat."
            );
        }

        SourceGroupComposition.PackAssignment packAssignment =
                sourceGroupComposition.getPackAssignment();

        /*
         * Plan validation has already established that this Pack assignment
         * exists and identifies the supplied attached assignment.
         */
        TestPackLeader.apply(
                wolfCat,
                packAssignment.packId()
        );
    }

    private AttachedMobRuntimeModifierExecutor() {
    }
}