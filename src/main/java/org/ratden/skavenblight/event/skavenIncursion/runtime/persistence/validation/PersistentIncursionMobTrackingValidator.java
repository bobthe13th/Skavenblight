package org.ratden.skavenblight.event.skavenIncursion.runtime.persistence.validation;

import org.ratden.skavenblight.event.skavenIncursion.runtime.IncursionExecutionState;
import org.ratden.skavenblight.event.skavenIncursion.runtime.entity.AttachedMobEntityBindingState;
import org.ratden.skavenblight.event.skavenIncursion.runtime.mob.IncursionMobTrackingState;
import org.ratden.skavenblight.event.skavenIncursion.runtime.persistence.PlannedScenarioRuntimeSnapshot;
import org.ratden.skavenblight.event.skavenIncursion.runtime.source.SourceExecutionState;
import org.ratden.skavenblight.event.skavenIncursion.runtime.source.SourceWaveExecutionState;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Validates the persistence boundary between planned Scenario execution and
 * authoritative tracking of successfully delivered mobs.
 *
 * Scenario runtime owns:
 *
 * - pending queue entries;
 * - successful-spawn counts;
 * - cancelled-spawn counts;
 * - physical source incarnation history;
 * - attached-assignment entity bindings.
 *
 * IncursionMobTrackingState owns:
 *
 * - the identity of every successfully delivered entity;
 * - the represented threat carried by that entity;
 * - the entity's persistent lifecycle resolution.
 *
 * These branches are persisted separately but describe the same successful
 * delivery transitions. This validator prevents them from drifting apart.
 *
 * It deliberately validates identities and counts available in runtime
 * persistence. Exact mob-and-threat multiset accounting against the immutable
 * IncursionPlan can be added as a separate planning-boundary validator.
 */
public final class PersistentIncursionMobTrackingValidator {

    private PersistentIncursionMobTrackingValidator() {
    }

    public static void validate(
            UUID incursionId,
            PlannedScenarioRuntimeSnapshot scenarioRuntimeSnapshot,
            IncursionMobTrackingState.Snapshot mobTrackingSnapshot
    ) {
        if (incursionId == null) {
            throw new IllegalArgumentException(
                    "Mob-tracking consistency validation requires an "
                            + "incursion ID."
            );
        }

        if (scenarioRuntimeSnapshot == null) {
            throw new IllegalArgumentException(
                    "Mob-tracking consistency validation requires a Scenario "
                            + "runtime snapshot."
            );
        }

        if (mobTrackingSnapshot == null) {
            throw new IllegalArgumentException(
                    "Mob-tracking consistency validation requires a "
                            + "mob-tracking snapshot."
            );
        }

        if (!incursionId.equals(
                scenarioRuntimeSnapshot.instanceId()
        )) {
            throw new IllegalArgumentException(
                    "Consistency-validation incursion ID "
                            + incursionId
                            + " does not match Scenario runtime instance ID "
                            + scenarioRuntimeSnapshot.instanceId()
                            + "."
            );
        }

        if (!incursionId.equals(
                mobTrackingSnapshot.incursionId()
        )) {
            throw new IllegalArgumentException(
                    "Consistency-validation incursion ID "
                            + incursionId
                            + " does not match mob-tracking incursion ID "
                            + mobTrackingSnapshot.incursionId()
                            + "."
            );
        }

        IncursionExecutionState.Snapshot executionSnapshot =
                scenarioRuntimeSnapshot.incursionExecutionSnapshot();

        Map<UUID, SourceExecutionState.Snapshot>
                sourceSnapshotsByPlacementId =
                indexPhysicalSourceSnapshots(
                        executionSnapshot
                );

        Map<AssignmentKey, SourceWaveExecutionState.Snapshot>
                assignmentsByKey =
                new LinkedHashMap<>();

        Map<UUID, BindingContext>
                bindingsByEntityId =
                new LinkedHashMap<>();

        Map<UUID, BindingContext>
                bindingsByAssignmentId =
                new LinkedHashMap<>();

        int expectedTrackedMobCount =
                indexSourceAssignments(
                        executionSnapshot,
                        assignmentsByKey,
                        bindingsByEntityId,
                        bindingsByAssignmentId
                );

        if (mobTrackingSnapshot.getTrackedMobCount()
                != expectedTrackedMobCount) {

            throw new IllegalArgumentException(
                    "Incursion "
                            + incursionId
                            + " runtime reports "
                            + expectedTrackedMobCount
                            + " successful spawns, but persistent mob "
                            + "tracking contains "
                            + mobTrackingSnapshot.getTrackedMobCount()
                            + " entity records."
            );
        }

        Map<AssignmentKey, Integer>
                trackedCountsByAssignment =
                new LinkedHashMap<>();

        Map<UUID, IncursionMobTrackingState.TrackedMobSnapshot>
                trackedMobsByEntityId =
                new LinkedHashMap<>();

        for (IncursionMobTrackingState.TrackedMobSnapshot trackedMob
                : mobTrackingSnapshot.trackedMobs()) {

            validateTrackedMob(
                    trackedMob,
                    assignmentsByKey,
                    sourceSnapshotsByPlacementId,
                    bindingsByEntityId,
                    bindingsByAssignmentId
            );

            trackedCountsByAssignment.merge(
                    new AssignmentKey(
                            trackedMob.waveIndex(),
                            trackedMob.sourceCompositionId()
                    ),
                    1,
                    Math::addExact
            );

            trackedMobsByEntityId.put(
                    trackedMob.entityId(),
                    trackedMob
            );
        }

        validateSuccessfulCountsByAssignment(
                assignmentsByKey,
                trackedCountsByAssignment
        );

        validateEveryBindingHasTrackedMob(
                bindingsByEntityId,
                trackedMobsByEntityId
        );
    }

    private static Map<UUID, SourceExecutionState.Snapshot>
    indexPhysicalSourceSnapshots(
            IncursionExecutionState.Snapshot executionSnapshot
    ) {
        Map<UUID, SourceExecutionState.Snapshot>
                sourceSnapshotsByPlacementId =
                new LinkedHashMap<>();

        for (SourceExecutionState.Snapshot sourceSnapshot
                : executionSnapshot.sourceExecutionSnapshots()) {

            SourceExecutionState.Snapshot previousSnapshot =
                    sourceSnapshotsByPlacementId.putIfAbsent(
                            sourceSnapshot.sourcePlacementId(),
                            sourceSnapshot
                    );

            if (previousSnapshot != null) {
                throw new IllegalArgumentException(
                        "Incursion execution contains duplicate physical "
                                + "source-placement snapshot "
                                + sourceSnapshot.sourcePlacementId()
                                + "."
                );
            }
        }

        return sourceSnapshotsByPlacementId;
    }

    private static int indexSourceAssignments(
            IncursionExecutionState.Snapshot executionSnapshot,
            Map<AssignmentKey, SourceWaveExecutionState.Snapshot>
                    assignmentsByKey,
            Map<UUID, BindingContext> bindingsByEntityId,
            Map<UUID, BindingContext> bindingsByAssignmentId
    ) {
        int expectedTrackedMobCount =
                0;

        for (IncursionExecutionState.WaveSourceAssignmentsSnapshot
                waveSnapshot
                : executionSnapshot.waveSourceAssignmentSnapshots()) {

            int waveIndex =
                    waveSnapshot.waveIndex();

            for (SourceWaveExecutionState.Snapshot sourceWaveSnapshot
                    : waveSnapshot.sourceWaveExecutionSnapshots()) {

                AssignmentKey assignmentKey =
                        new AssignmentKey(
                                waveIndex,
                                sourceWaveSnapshot.sourceCompositionId()
                        );

                SourceWaveExecutionState.Snapshot previousAssignment =
                        assignmentsByKey.putIfAbsent(
                                assignmentKey,
                                sourceWaveSnapshot
                        );

                if (previousAssignment != null) {
                    throw new IllegalArgumentException(
                            "Incursion execution contains duplicate runtime "
                                    + "assignment for wave "
                                    + waveIndex
                                    + ", source composition "
                                    + sourceWaveSnapshot.sourceCompositionId()
                                    + "."
                    );
                }

                expectedTrackedMobCount =
                        Math.addExact(
                                expectedTrackedMobCount,
                                sourceWaveSnapshot.successfulSpawnCount()
                        );

                indexAttachedBindings(
                        assignmentKey,
                        sourceWaveSnapshot,
                        bindingsByEntityId,
                        bindingsByAssignmentId
                );
            }
        }

        return expectedTrackedMobCount;
    }

    private static void indexAttachedBindings(
            AssignmentKey assignmentKey,
            SourceWaveExecutionState.Snapshot sourceWaveSnapshot,
            Map<UUID, BindingContext> bindingsByEntityId,
            Map<UUID, BindingContext> bindingsByAssignmentId
    ) {
        for (AttachedMobEntityBindingState.BindingSnapshot bindingSnapshot
                : sourceWaveSnapshot
                .attachedMobEntityBindingSnapshot()
                .bindings()) {

            BindingContext bindingContext =
                    new BindingContext(
                            assignmentKey,
                            sourceWaveSnapshot.sourcePlacementId(),
                            bindingSnapshot.attachedMobAssignmentId(),
                            bindingSnapshot.entityId()
                    );

            BindingContext previousEntityBinding =
                    bindingsByEntityId.putIfAbsent(
                            bindingSnapshot.entityId(),
                            bindingContext
                    );

            if (previousEntityBinding != null) {
                throw new IllegalArgumentException(
                        "Entity "
                                + bindingSnapshot.entityId()
                                + " is bound to attached assignments in more "
                                + "than one persisted source-wave assignment."
                );
            }

            BindingContext previousAssignmentBinding =
                    bindingsByAssignmentId.putIfAbsent(
                            bindingSnapshot.attachedMobAssignmentId(),
                            bindingContext
                    );

            if (previousAssignmentBinding != null) {
                throw new IllegalArgumentException(
                        "Attached-mob assignment "
                                + bindingSnapshot.attachedMobAssignmentId()
                                + " appears in more than one persisted "
                                + "source-wave assignment."
                );
            }
        }
    }

    private static void validateTrackedMob(
            IncursionMobTrackingState.TrackedMobSnapshot trackedMob,
            Map<AssignmentKey, SourceWaveExecutionState.Snapshot>
                    assignmentsByKey,
            Map<UUID, SourceExecutionState.Snapshot>
                    sourceSnapshotsByPlacementId,
            Map<UUID, BindingContext> bindingsByEntityId,
            Map<UUID, BindingContext> bindingsByAssignmentId
    ) {
        AssignmentKey assignmentKey =
                new AssignmentKey(
                        trackedMob.waveIndex(),
                        trackedMob.sourceCompositionId()
                );

        SourceWaveExecutionState.Snapshot assignmentSnapshot =
                assignmentsByKey.get(
                        assignmentKey
                );

        if (assignmentSnapshot == null) {
            throw new IllegalArgumentException(
                    "Tracked entity "
                            + trackedMob.entityId()
                            + " refers to wave "
                            + trackedMob.waveIndex()
                            + ", source composition "
                            + trackedMob.sourceCompositionId()
                            + ", but no matching runtime assignment exists."
            );
        }

        if (!trackedMob
                .sourcePlacementId()
                .equals(
                        assignmentSnapshot.sourcePlacementId()
                )) {

            throw new IllegalArgumentException(
                    "Tracked entity "
                            + trackedMob.entityId()
                            + " records source placement "
                            + trackedMob.sourcePlacementId()
                            + ", but its runtime assignment uses "
                            + assignmentSnapshot.sourcePlacementId()
                            + "."
            );
        }

        SourceExecutionState.Snapshot physicalSourceSnapshot =
                sourceSnapshotsByPlacementId.get(
                        trackedMob.sourcePlacementId()
                );

        if (physicalSourceSnapshot == null) {
            throw new IllegalArgumentException(
                    "Tracked entity "
                            + trackedMob.entityId()
                            + " refers to unknown physical source placement "
                            + trackedMob.sourcePlacementId()
                            + "."
            );
        }

        if (!physicalSourceSnapshot
                .runtimeSourceIdHistory()
                .contains(
                        trackedMob.runtimeSourceId()
                )) {

            throw new IllegalArgumentException(
                    "Tracked entity "
                            + trackedMob.entityId()
                            + " records runtime source ID "
                            + trackedMob.runtimeSourceId()
                            + ", but that incarnation does not belong to "
                            + "source placement "
                            + trackedMob.sourcePlacementId()
                            + "."
            );
        }

        validateAttachedBinding(
                trackedMob,
                assignmentKey,
                bindingsByEntityId,
                bindingsByAssignmentId
        );
    }

    private static void validateAttachedBinding(
            IncursionMobTrackingState.TrackedMobSnapshot trackedMob,
            AssignmentKey assignmentKey,
            Map<UUID, BindingContext> bindingsByEntityId,
            Map<UUID, BindingContext> bindingsByAssignmentId
    ) {
        BindingContext entityBinding =
                bindingsByEntityId.get(
                        trackedMob.entityId()
                );

        UUID attachedMobAssignmentId =
                trackedMob.attachedMobAssignmentId();

        if (attachedMobAssignmentId == null) {
            if (entityBinding != null) {
                throw new IllegalArgumentException(
                        "Tracked entity "
                                + trackedMob.entityId()
                                + " is recorded as an ordinary mob, but "
                                + "attached-assignment persistence binds it to "
                                + entityBinding.attachedMobAssignmentId()
                                + "."
                );
            }

            return;
        }

        BindingContext assignmentBinding =
                bindingsByAssignmentId.get(
                        attachedMobAssignmentId
                );

        if (assignmentBinding == null) {
            throw new IllegalArgumentException(
                    "Tracked entity "
                            + trackedMob.entityId()
                            + " identifies attached assignment "
                            + attachedMobAssignmentId
                            + ", but no persistent attachment binding exists."
            );
        }

        if (!trackedMob
                .entityId()
                .equals(
                        assignmentBinding.entityId()
                )) {

            throw new IllegalArgumentException(
                    "Tracked attached assignment "
                            + attachedMobAssignmentId
                            + " belongs to entity "
                            + trackedMob.entityId()
                            + ", but attachment persistence binds it to "
                            + assignmentBinding.entityId()
                            + "."
            );
        }

        if (!assignmentKey.equals(
                assignmentBinding.assignmentKey()
        )) {
            throw new IllegalArgumentException(
                    "Tracked entity "
                            + trackedMob.entityId()
                            + " and its attached-assignment binding belong to "
                            + "different source-wave assignments."
            );
        }

        if (!trackedMob
                .sourcePlacementId()
                .equals(
                        assignmentBinding.sourcePlacementId()
                )) {

            throw new IllegalArgumentException(
                    "Tracked entity "
                            + trackedMob.entityId()
                            + " and its attached-assignment binding refer to "
                            + "different physical source placements."
            );
        }

        if (entityBinding == null
                || !entityBinding.equals(
                assignmentBinding
        )) {

            throw new IllegalArgumentException(
                    "Attached-assignment indexes disagree for tracked entity "
                            + trackedMob.entityId()
                            + "."
            );
        }
    }

    private static void validateSuccessfulCountsByAssignment(
            Map<AssignmentKey, SourceWaveExecutionState.Snapshot>
                    assignmentsByKey,
            Map<AssignmentKey, Integer> trackedCountsByAssignment
    ) {
        for (Map.Entry<
                AssignmentKey,
                SourceWaveExecutionState.Snapshot
                > entry
                : assignmentsByKey.entrySet()) {

            int trackedCount =
                    trackedCountsByAssignment.getOrDefault(
                            entry.getKey(),
                            0
                    );

            int successfulSpawnCount =
                    entry.getValue().successfulSpawnCount();

            if (trackedCount
                    != successfulSpawnCount) {

                throw new IllegalArgumentException(
                        "Wave "
                                + entry.getKey().waveIndex()
                                + ", source composition "
                                + entry.getKey().sourceCompositionId()
                                + " reports "
                                + successfulSpawnCount
                                + " successful spawns, but mob tracking "
                                + "contains "
                                + trackedCount
                                + " matching entity records."
                );
            }
        }
    }

    private static void validateEveryBindingHasTrackedMob(
            Map<UUID, BindingContext> bindingsByEntityId,
            Map<UUID, IncursionMobTrackingState.TrackedMobSnapshot>
                    trackedMobsByEntityId
    ) {
        for (BindingContext bindingContext
                : bindingsByEntityId.values()) {

            IncursionMobTrackingState.TrackedMobSnapshot trackedMob =
                    trackedMobsByEntityId.get(
                            bindingContext.entityId()
                    );

            if (trackedMob == null) {
                throw new IllegalArgumentException(
                        "Attached assignment "
                                + bindingContext.attachedMobAssignmentId()
                                + " is bound to entity "
                                + bindingContext.entityId()
                                + ", but that entity is absent from "
                                + "persistent mob tracking."
                );
            }

            if (!bindingContext
                    .attachedMobAssignmentId()
                    .equals(
                            trackedMob.attachedMobAssignmentId()
                    )) {

                throw new IllegalArgumentException(
                        "Attached binding for entity "
                                + bindingContext.entityId()
                                + " uses assignment "
                                + bindingContext.attachedMobAssignmentId()
                                + ", but mob tracking records "
                                + trackedMob.attachedMobAssignmentId()
                                + "."
                );
            }
        }
    }

    private record AssignmentKey(
            int waveIndex,
            UUID sourceCompositionId
    ) {

        private AssignmentKey {
            if (waveIndex < 0) {
                throw new IllegalArgumentException(
                        "Mob-tracking assignment key wave index cannot be "
                                + "negative."
                );
            }

            if (sourceCompositionId == null) {
                throw new IllegalArgumentException(
                        "Mob-tracking assignment key requires a source-"
                                + "composition ID."
                );
            }
        }
    }

    private record BindingContext(
            AssignmentKey assignmentKey,
            UUID sourcePlacementId,
            UUID attachedMobAssignmentId,
            UUID entityId
    ) {

        private BindingContext {
            if (assignmentKey == null) {
                throw new IllegalArgumentException(
                        "Attached binding context requires an assignment key."
                );
            }

            if (sourcePlacementId == null) {
                throw new IllegalArgumentException(
                        "Attached binding context requires a source-placement "
                                + "ID."
                );
            }

            if (attachedMobAssignmentId == null) {
                throw new IllegalArgumentException(
                        "Attached binding context requires an attached "
                                + "assignment ID."
                );
            }

            if (entityId == null) {
                throw new IllegalArgumentException(
                        "Attached binding context requires an entity ID."
                );
            }
        }
    }
}