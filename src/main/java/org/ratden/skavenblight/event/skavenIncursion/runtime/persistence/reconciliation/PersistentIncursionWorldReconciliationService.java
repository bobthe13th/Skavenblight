package org.ratden.skavenblight.event.skavenIncursion.runtime.persistence.reconciliation;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.ratden.skavenblight.block.ModBlocks;
import org.ratden.skavenblight.block.custom.SkavenTunnelSourceBlock;
import org.ratden.skavenblight.block.entity.SkavenTunnelSourceEntity;
import org.ratden.skavenblight.block.entity.state.SourceState;
import org.ratden.skavenblight.event.skavenIncursion.action.source.SetSourceState;
import org.ratden.skavenblight.event.skavenIncursion.director.ActiveIncursionManager;
import org.ratden.skavenblight.event.skavenIncursion.planning.front.FrontPlan;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceGroupPlacementPlan;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourcePlacementPlan;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceType;
import org.ratden.skavenblight.event.skavenIncursion.runtime.persistence.LivePersistentIncursion;
import org.ratden.skavenblight.event.skavenIncursion.runtime.persistence.PlannedScenarioRuntimeSnapshot;
import org.ratden.skavenblight.event.skavenIncursion.runtime.source.SourceExecutionState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Reconciles the persisted logical state of one restored incursion with the
 * physical source blocks currently present in its ServerLevel.
 *
 * Persisted logical runtime is authoritative. The world is inspected for each
 * planned physical source position and handled as follows:
 *
 * - a matching source incarnation is retained;
 * - a matching source with the wrong SourceState is repaired;
 * - a persisted current source missing from the world is marked destroyed in
 *   logical runtime;
 * - a stale tunnel belonging to the same incursion is removed when persistence
 *   says no physical incarnation should currently exist;
 * - a tunnel belonging to another Scenario is treated as an ownership
 *   conflict and is never modified.
 *
 * Reconciliation is deliberately split into inspection and application.
 * Nothing is modified when inspection finds any ownership conflict.
 *
 * This service must run before the LivePersistentIncursion is attached to
 * ActiveIncursionManager. Removing a stale source may invoke the block's
 * onRemove callback, and that callback must not be routed into the restored
 * Scenario as a new player-generated destruction event.
 *
 * Spawned-entity reconciliation and debug-anchor restoration remain separate
 * later responsibilities.
 */
public final class PersistentIncursionWorldReconciliationService {

    /**
     * Inspects and, when safe, reconciles every physical source belonging to
     * one logically restored incursion.
     */
    public static ReconciliationReport reconcile(
            ServerLevel level,
            LivePersistentIncursion incursion
    ) {
        validateArguments(
                level,
                incursion
        );

        Inspection inspection =
                inspect(
                        level,
                        incursion
                );

        if (!inspection.conflicts().isEmpty()) {
            return ReconciliationReport.conflicted(
                    incursion.getIncursionId(),
                    inspection.inspectedSourceCount(),
                    inspection.matchingSourceCount(),
                    inspection.expectedAbsentCount(),
                    inspection.conflicts()
            );
        }

        ApplicationCounts applicationCounts =
                applyActions(
                        level,
                        incursion,
                        inspection.actions()
                );

        /*
         * Capturing the complete persistent snapshot performs all normal
         * identity, lifecycle, cancellation and source-state validation after
         * reconciliation.
         */
        incursion.createPersistentSnapshot();

        return ReconciliationReport.successful(
                incursion.getIncursionId(),
                inspection.inspectedSourceCount(),
                inspection.matchingSourceCount(),
                inspection.expectedAbsentCount(),
                applicationCounts.repairedSourceStateCount(),
                applicationCounts.removedStaleSourceCount(),
                applicationCounts.markedMissingSourceCount()
        );
    }

    /**
     * Performs a read-only inspection and constructs the complete action list.
     *
     * No actions are applied unless every source position can be safely
     * classified.
     */
    private static Inspection inspect(
            ServerLevel level,
            LivePersistentIncursion incursion
    ) {
        PlannedScenarioRuntimeSnapshot runtimeSnapshot =
                incursion.getScenario()
                        .createSnapshot();

        Map<UUID, SourceExecutionState.Snapshot>
                sourceSnapshotsByPlacementId =
                indexSourceSnapshots(
                        runtimeSnapshot
                                .incursionExecutionSnapshot()
                                .sourceExecutionSnapshots()
                );

        List<ReconciliationAction> actions =
                new ArrayList<>();

        List<ReconciliationConflict> conflicts =
                new ArrayList<>();

        int inspectedSourceCount =
                0;

        int matchingSourceCount =
                0;

        int expectedAbsentCount =
                0;

        for (FrontPlan frontPlan
                : incursion.getIncursionPlan()
                .getFrontPlans()) {

            for (SourceGroupPlacementPlan groupPlacementPlan
                    : frontPlan.getSourceGroupPlacementPlans()) {

                for (SourcePlacementPlan sourcePlacementPlan
                        : groupPlacementPlan
                        .getSourcePlacementPlans()) {

                    inspectedSourceCount++;

                    UUID sourcePlacementId =
                            sourcePlacementPlan
                                    .getSourcePlacementId();

                    SourceExecutionState.Snapshot sourceSnapshot =
                            sourceSnapshotsByPlacementId.remove(
                                    sourcePlacementId
                            );

                    if (sourceSnapshot == null) {
                        throw new IllegalStateException(
                                "Restored runtime contains no source snapshot "
                                        + "for physical placement "
                                        + sourcePlacementId
                                        + "."
                        );
                    }

                    SourceInspectionResult result =
                            inspectSource(
                                    level,
                                    incursion.getIncursionId(),
                                    sourcePlacementPlan,
                                    sourceSnapshot
                            );

                    matchingSourceCount +=
                            result.matchingSourceCount();

                    expectedAbsentCount +=
                            result.expectedAbsentCount();

                    actions.addAll(
                            result.actions()
                    );

                    conflicts.addAll(
                            result.conflicts()
                    );
                }
            }
        }

        if (!sourceSnapshotsByPlacementId.isEmpty()) {
            throw new IllegalStateException(
                    "Restored runtime contains source snapshots that have no "
                            + "physical placement in the immutable plan: "
                            + sourceSnapshotsByPlacementId.keySet()
                            + "."
            );
        }

        return new Inspection(
                inspectedSourceCount,
                matchingSourceCount,
                expectedAbsentCount,
                actions,
                conflicts
        );
    }

    private static SourceInspectionResult inspectSource(
            ServerLevel level,
            UUID incursionId,
            SourcePlacementPlan sourcePlacementPlan,
            SourceExecutionState.Snapshot sourceSnapshot
    ) {
        UUID sourcePlacementId =
                sourcePlacementPlan.getSourcePlacementId();

        BlockPos sourcePos =
                sourcePlacementPlan.getPlacedPos();

        if (sourcePos == null) {
            throw new IllegalStateException(
                    "Physical source placement "
                            + sourcePlacementId
                            + " has no final position."
            );
        }

        if (sourcePlacementPlan.getSourceType()
                != SourceType.SKAVEN_TUNNEL) {

            return SourceInspectionResult.conflict(
                    new ReconciliationConflict(
                            sourcePlacementId,
                            sourcePos,
                            "No world reconciler exists for source type "
                                    + sourcePlacementPlan.getSourceType()
                                    + "."
                    )
            );
        }

        BlockState worldBlockState =
                level.getBlockState(
                        sourcePos
                );

        boolean worldContainsTunnel =
                worldBlockState.is(
                        ModBlocks.SKAVEN_TUNNEL_SOURCE.get()
                );

        UUID expectedRuntimeSourceId =
                sourceSnapshot.runtimeSourceId();

        if (expectedRuntimeSourceId == null) {
            return inspectExpectedAbsentSource(
                    level,
                    incursionId,
                    sourcePlacementId,
                    sourcePos,
                    worldContainsTunnel
            );
        }

        return inspectExpectedCurrentSource(
                level,
                incursionId,
                sourcePlacementId,
                sourcePos,
                expectedRuntimeSourceId,
                sourceSnapshot.currentSourceState(),
                worldBlockState,
                worldContainsTunnel
        );
    }

    /**
     * Handles a source position for which persistence records no current
     * physical incarnation.
     */
    private static SourceInspectionResult
    inspectExpectedAbsentSource(
            ServerLevel level,
            UUID incursionId,
            UUID sourcePlacementId,
            BlockPos sourcePos,
            boolean worldContainsTunnel
    ) {
        if (!worldContainsTunnel) {
            return SourceInspectionResult.expectedAbsent();
        }

        BlockEntity blockEntity =
                level.getBlockEntity(
                        sourcePos
                );

        if (!(blockEntity
                instanceof SkavenTunnelSourceEntity tunnelSource)) {

            return SourceInspectionResult.conflict(
                    new ReconciliationConflict(
                            sourcePlacementId,
                            sourcePos,
                            "The world contains a Skaven tunnel source block "
                                    + "without a valid tunnel source block "
                                    + "entity."
                    )
            );
        }

        UUID worldScenarioId =
                tunnelSource.getScenarioId();

        if (!incursionId.equals(
                worldScenarioId
        )) {
            return SourceInspectionResult.conflict(
                    new ReconciliationConflict(
                            sourcePlacementId,
                            sourcePos,
                            "Persistence expects no current source, but the "
                                    + "position contains a tunnel owned by "
                                    + "Scenario "
                                    + formatUuid(
                                    worldScenarioId
                            )
                                    + "."
                    )
            );
        }

        /*
         * The chunk may have saved a source incarnation after the SavedData
         * snapshot was written. Since the tunnel belongs to this same
         * incursion and occupies this exact planned source position, the
         * persisted absent state remains authoritative.
         */
        return SourceInspectionResult.action(
                ReconciliationAction.removeOwnedSource(
                        sourcePlacementId,
                        sourcePos,
                        tunnelSource.getSourceId()
                )
        );
    }

    /**
     * Handles a position for which persistence records one current physical
     * source incarnation.
     */
    private static SourceInspectionResult
    inspectExpectedCurrentSource(
            ServerLevel level,
            UUID incursionId,
            UUID sourcePlacementId,
            BlockPos sourcePos,
            UUID expectedRuntimeSourceId,
            SourceState expectedSourceState,
            BlockState worldBlockState,
            boolean worldContainsTunnel
    ) {
        if (expectedSourceState == null) {
            throw new IllegalStateException(
                    "Source placement "
                            + sourcePlacementId
                            + " has a current runtime source ID but no current "
                            + "SourceState."
            );
        }

        if (!worldContainsTunnel) {
            /*
             * Air, terrain or a player block means the recorded source
             * incarnation no longer exists. The replacement block is left
             * untouched.
             */
            return SourceInspectionResult.action(
                    ReconciliationAction.markMissingSource(
                            sourcePlacementId,
                            sourcePos,
                            expectedRuntimeSourceId
                    )
            );
        }

        BlockEntity blockEntity =
                level.getBlockEntity(
                        sourcePos
                );

        if (!(blockEntity
                instanceof SkavenTunnelSourceEntity tunnelSource)) {

            return SourceInspectionResult.conflict(
                    new ReconciliationConflict(
                            sourcePlacementId,
                            sourcePos,
                            "Persistence expects runtime source "
                                    + expectedRuntimeSourceId
                                    + ", but the tunnel block has no valid "
                                    + "tunnel source block entity."
                    )
            );
        }

        UUID worldScenarioId =
                tunnelSource.getScenarioId();

        if (!incursionId.equals(
                worldScenarioId
        )) {
            return SourceInspectionResult.conflict(
                    new ReconciliationConflict(
                            sourcePlacementId,
                            sourcePos,
                            "Persistence expects runtime source "
                                    + expectedRuntimeSourceId
                                    + ", but the world tunnel belongs to "
                                    + "Scenario "
                                    + formatUuid(
                                    worldScenarioId
                            )
                                    + "."
                    )
            );
        }

        UUID worldRuntimeSourceId =
                tunnelSource.getSourceId();

        if (!expectedRuntimeSourceId.equals(
                worldRuntimeSourceId
        )) {
            /*
             * The world contains a different incarnation owned by the same
             * incursion. Persistence remains authoritative: remove that
             * unexpected incarnation and mark the expected incarnation
             * missing.
             */
            return SourceInspectionResult.actions(
                    List.of(
                            ReconciliationAction.removeOwnedSource(
                                    sourcePlacementId,
                                    sourcePos,
                                    worldRuntimeSourceId
                            ),
                            ReconciliationAction.markMissingSource(
                                    sourcePlacementId,
                                    sourcePos,
                                    expectedRuntimeSourceId
                            )
                    )
            );
        }

        SourceState worldSourceState =
                worldBlockState.getValue(
                        SkavenTunnelSourceBlock.SOURCE_STATE
                );

        if (worldSourceState
                == expectedSourceState) {

            return SourceInspectionResult.matching();
        }

        return SourceInspectionResult.action(
                ReconciliationAction.repairSourceState(
                        sourcePlacementId,
                        sourcePos,
                        expectedRuntimeSourceId,
                        expectedSourceState
                )
        );
    }

    private static ApplicationCounts applyActions(
            ServerLevel level,
            LivePersistentIncursion incursion,
            List<ReconciliationAction> actions
    ) {
        int repairedSourceStateCount =
                0;

        int removedStaleSourceCount =
                0;

        int markedMissingSourceCount =
                0;

        for (ReconciliationAction action
                : actions) {

            switch (action.type()) {
                case REPAIR_SOURCE_STATE -> {
                    repairSourceState(
                            level,
                            incursion.getIncursionId(),
                            action
                    );

                    repairedSourceStateCount++;
                }

                case REMOVE_OWNED_SOURCE -> {
                    removeOwnedSource(
                            level,
                            incursion.getIncursionId(),
                            action
                    );

                    removedStaleSourceCount++;
                }

                case MARK_MISSING_SOURCE_DESTROYED -> {
                    boolean reconciled =
                            incursion.getScenario()
                                    .reconcileMissingPhysicalSource(
                                            action.runtimeSourceId()
                                    );

                    if (!reconciled) {
                        throw new IllegalStateException(
                                "Live Scenario did not recognise persisted "
                                        + "runtime source "
                                        + action.runtimeSourceId()
                                        + " for physical placement "
                                        + action.sourcePlacementId()
                                        + "."
                        );
                    }

                    markedMissingSourceCount++;
                }
            }
        }

        return new ApplicationCounts(
                repairedSourceStateCount,
                removedStaleSourceCount,
                markedMissingSourceCount
        );
    }

    private static void repairSourceState(
            ServerLevel level,
            UUID incursionId,
            ReconciliationAction action
    ) {
        SkavenTunnelSourceEntity tunnelSource =
                requireOwnedSource(
                        level,
                        incursionId,
                        action
                );

        if (!Objects.equals(
                action.runtimeSourceId(),
                tunnelSource.getSourceId()
        )) {
            throw new IllegalStateException(
                    "Runtime source identity changed before state repair at "
                            + action.sourcePos()
                            + "."
            );
        }

        boolean stateChanged =
                SetSourceState.execute(
                        level,
                        action.sourcePos(),
                        action.expectedSourceState()
                );

        if (!stateChanged) {
            throw new IllegalStateException(
                    "Could not restore SourceState "
                            + action.expectedSourceState()
                            + " for runtime source "
                            + action.runtimeSourceId()
                            + " at "
                            + action.sourcePos()
                            + "."
            );
        }
    }

    private static void removeOwnedSource(
            ServerLevel level,
            UUID incursionId,
            ReconciliationAction action
    ) {
        SkavenTunnelSourceEntity tunnelSource =
                requireOwnedSource(
                        level,
                        incursionId,
                        action
                );

        if (!Objects.equals(
                action.runtimeSourceId(),
                tunnelSource.getSourceId()
        )) {
            throw new IllegalStateException(
                    "Runtime source identity changed before stale-source "
                            + "removal at "
                            + action.sourcePos()
                            + "."
            );
        }

        boolean removed =
                level.removeBlock(
                        action.sourcePos(),
                        false
                );

        if (!removed
                || level.getBlockState(
                action.sourcePos()
        ).is(
                ModBlocks.SKAVEN_TUNNEL_SOURCE.get()
        )) {

            throw new IllegalStateException(
                    "Could not remove stale runtime source "
                            + formatUuid(
                            action.runtimeSourceId()
                    )
                            + " at "
                            + action.sourcePos()
                            + "."
            );
        }
    }

    private static SkavenTunnelSourceEntity requireOwnedSource(
            ServerLevel level,
            UUID incursionId,
            ReconciliationAction action
    ) {
        BlockState blockState =
                level.getBlockState(
                        action.sourcePos()
                );

        if (!blockState.is(
                ModBlocks.SKAVEN_TUNNEL_SOURCE.get()
        )) {
            throw new IllegalStateException(
                    "Expected an owned tunnel source at "
                            + action.sourcePos()
                            + " while applying reconciliation."
            );
        }

        BlockEntity blockEntity =
                level.getBlockEntity(
                        action.sourcePos()
                );

        if (!(blockEntity
                instanceof SkavenTunnelSourceEntity tunnelSource)) {

            throw new IllegalStateException(
                    "Tunnel source at "
                            + action.sourcePos()
                            + " lost its valid block entity before "
                            + "reconciliation could be applied."
            );
        }

        if (!incursionId.equals(
                tunnelSource.getScenarioId()
        )) {
            throw new IllegalStateException(
                    "Tunnel source ownership changed before reconciliation at "
                            + action.sourcePos()
                            + "."
            );
        }

        return tunnelSource;
    }

    private static Map<UUID, SourceExecutionState.Snapshot>
    indexSourceSnapshots(
            List<SourceExecutionState.Snapshot> sourceSnapshots
    ) {
        if (sourceSnapshots == null) {
            throw new IllegalArgumentException(
                    "Source snapshot list cannot be null."
            );
        }

        Map<UUID, SourceExecutionState.Snapshot>
                snapshotsByPlacementId =
                new LinkedHashMap<>();

        for (SourceExecutionState.Snapshot sourceSnapshot
                : sourceSnapshots) {

            if (sourceSnapshot == null) {
                throw new IllegalArgumentException(
                        "Source snapshot list cannot contain null."
                );
            }

            UUID sourcePlacementId =
                    sourceSnapshot.sourcePlacementId();

            SourceExecutionState.Snapshot previousSnapshot =
                    snapshotsByPlacementId.putIfAbsent(
                            sourcePlacementId,
                            sourceSnapshot
                    );

            if (previousSnapshot != null) {
                throw new IllegalArgumentException(
                        "Runtime snapshot contains duplicate physical source "
                                + "placement ID "
                                + sourcePlacementId
                                + "."
                );
            }
        }

        return snapshotsByPlacementId;
    }

    private static void validateArguments(
            ServerLevel level,
            LivePersistentIncursion incursion
    ) {
        if (level == null) {
            throw new IllegalArgumentException(
                    "Reconciliation level cannot be null."
            );
        }

        if (incursion == null) {
            throw new IllegalArgumentException(
                    "Live persistent incursion cannot be null."
            );
        }

        if (incursion.getLevel()
                != level) {

            throw new IllegalArgumentException(
                    "Live persistent incursion belongs to a different "
                            + "ServerLevel."
            );
        }

        if (ActiveIncursionManager.getPersistentIncursion(
                incursion.getIncursionId()
        ) != null) {

            throw new IllegalStateException(
                    "Physical-source reconciliation must occur before "
                            + "incursion "
                            + incursion.getIncursionId()
                            + " is attached to ActiveIncursionManager."
            );
        }
    }

    private static String formatUuid(
            UUID value
    ) {
        return value == null
                ? "none"
                : value.toString();
    }

    private enum ReconciliationActionType {
        REPAIR_SOURCE_STATE,
        REMOVE_OWNED_SOURCE,
        MARK_MISSING_SOURCE_DESTROYED
    }

    private record ReconciliationAction(
            ReconciliationActionType type,
            UUID sourcePlacementId,
            BlockPos sourcePos,
            UUID runtimeSourceId,
            SourceState expectedSourceState
    ) {

        private ReconciliationAction {
            if (type == null) {
                throw new IllegalArgumentException(
                        "Reconciliation action type cannot be null."
                );
            }

            if (sourcePlacementId == null) {
                throw new IllegalArgumentException(
                        "Reconciliation source-placement ID cannot be null."
                );
            }

            if (sourcePos == null) {
                throw new IllegalArgumentException(
                        "Reconciliation source position cannot be null."
                );
            }

            sourcePos =
                    sourcePos.immutable();

            switch (type) {
                case REPAIR_SOURCE_STATE -> {
                    if (runtimeSourceId == null
                            || expectedSourceState == null) {

                        throw new IllegalArgumentException(
                                "Source-state repair requires a runtime source "
                                        + "ID and expected SourceState."
                        );
                    }
                }

                case REMOVE_OWNED_SOURCE -> {
                    if (expectedSourceState != null) {
                        throw new IllegalArgumentException(
                                "Stale-source removal cannot contain an "
                                        + "expected SourceState."
                        );
                    }
                }

                case MARK_MISSING_SOURCE_DESTROYED -> {
                    if (runtimeSourceId == null) {
                        throw new IllegalArgumentException(
                                "Missing-source reconciliation requires a "
                                        + "runtime source ID."
                        );
                    }

                    if (expectedSourceState != null) {
                        throw new IllegalArgumentException(
                                "Missing-source reconciliation cannot contain "
                                        + "an expected SourceState."
                        );
                    }
                }
            }
        }

        private static ReconciliationAction repairSourceState(
                UUID sourcePlacementId,
                BlockPos sourcePos,
                UUID runtimeSourceId,
                SourceState expectedSourceState
        ) {
            return new ReconciliationAction(
                    ReconciliationActionType.REPAIR_SOURCE_STATE,
                    sourcePlacementId,
                    sourcePos,
                    runtimeSourceId,
                    expectedSourceState
            );
        }

        private static ReconciliationAction removeOwnedSource(
                UUID sourcePlacementId,
                BlockPos sourcePos,
                UUID runtimeSourceId
        ) {
            return new ReconciliationAction(
                    ReconciliationActionType.REMOVE_OWNED_SOURCE,
                    sourcePlacementId,
                    sourcePos,
                    runtimeSourceId,
                    null
            );
        }

        private static ReconciliationAction markMissingSource(
                UUID sourcePlacementId,
                BlockPos sourcePos,
                UUID runtimeSourceId
        ) {
            return new ReconciliationAction(
                    ReconciliationActionType
                            .MARK_MISSING_SOURCE_DESTROYED,
                    sourcePlacementId,
                    sourcePos,
                    runtimeSourceId,
                    null
            );
        }
    }

    private record SourceInspectionResult(
            int matchingSourceCount,
            int expectedAbsentCount,
            List<ReconciliationAction> actions,
            List<ReconciliationConflict> conflicts
    ) {

        private SourceInspectionResult {
            if (matchingSourceCount < 0
                    || expectedAbsentCount < 0) {

                throw new IllegalArgumentException(
                        "Source-inspection counts cannot be negative."
                );
            }

            actions =
                    List.copyOf(
                            actions
                    );

            conflicts =
                    List.copyOf(
                            conflicts
                    );
        }

        private static SourceInspectionResult matching() {
            return new SourceInspectionResult(
                    1,
                    0,
                    List.of(),
                    List.of()
            );
        }

        private static SourceInspectionResult expectedAbsent() {
            return new SourceInspectionResult(
                    0,
                    1,
                    List.of(),
                    List.of()
            );
        }

        private static SourceInspectionResult action(
                ReconciliationAction action
        ) {
            return actions(
                    List.of(
                            action
                    )
            );
        }

        private static SourceInspectionResult actions(
                List<ReconciliationAction> actions
        ) {
            return new SourceInspectionResult(
                    0,
                    0,
                    actions,
                    List.of()
            );
        }

        private static SourceInspectionResult conflict(
                ReconciliationConflict conflict
        ) {
            return new SourceInspectionResult(
                    0,
                    0,
                    List.of(),
                    List.of(
                            conflict
                    )
            );
        }
    }

    private record Inspection(
            int inspectedSourceCount,
            int matchingSourceCount,
            int expectedAbsentCount,
            List<ReconciliationAction> actions,
            List<ReconciliationConflict> conflicts
    ) {

        private Inspection {
            if (inspectedSourceCount < 0
                    || matchingSourceCount < 0
                    || expectedAbsentCount < 0) {

                throw new IllegalArgumentException(
                        "Reconciliation inspection counts cannot be negative."
                );
            }

            actions =
                    List.copyOf(
                            actions
                    );

            conflicts =
                    List.copyOf(
                            conflicts
                    );
        }
    }

    private record ApplicationCounts(
            int repairedSourceStateCount,
            int removedStaleSourceCount,
            int markedMissingSourceCount
    ) {

        private ApplicationCounts {
            if (repairedSourceStateCount < 0
                    || removedStaleSourceCount < 0
                    || markedMissingSourceCount < 0) {

                throw new IllegalArgumentException(
                        "Reconciliation application counts cannot be "
                                + "negative."
                );
            }
        }
    }

    /**
     * One source position that cannot be safely reconciled automatically.
     */
    public record ReconciliationConflict(
            UUID sourcePlacementId,
            BlockPos sourcePos,
            String message
    ) {

        public ReconciliationConflict {
            if (sourcePlacementId == null) {
                throw new IllegalArgumentException(
                        "Reconciliation conflict source-placement ID cannot "
                                + "be null."
                );
            }

            if (sourcePos == null) {
                throw new IllegalArgumentException(
                        "Reconciliation conflict source position cannot be "
                                + "null."
                );
            }

            if (message == null
                    || message.isBlank()) {

                throw new IllegalArgumentException(
                        "Reconciliation conflict message cannot be blank."
                );
            }

            sourcePos =
                    sourcePos.immutable();
        }
    }

    /**
     * Result of reconciling one complete persistent incursion.
     *
     * A conflicted report means no reconciliation actions were applied.
     */
    public record ReconciliationReport(
            UUID incursionId,
            int inspectedSourceCount,
            int matchingSourceCount,
            int expectedAbsentCount,
            int repairedSourceStateCount,
            int removedStaleSourceCount,
            int markedMissingSourceCount,
            List<ReconciliationConflict> conflicts
    ) {

        public ReconciliationReport {
            if (incursionId == null) {
                throw new IllegalArgumentException(
                        "Reconciliation report incursion ID cannot be null."
                );
            }

            if (inspectedSourceCount < 0
                    || matchingSourceCount < 0
                    || expectedAbsentCount < 0
                    || repairedSourceStateCount < 0
                    || removedStaleSourceCount < 0
                    || markedMissingSourceCount < 0) {

                throw new IllegalArgumentException(
                        "Reconciliation report counts cannot be negative."
                );
            }

            conflicts =
                    List.copyOf(
                            conflicts
                    );

            if (!conflicts.isEmpty()
                    && (repairedSourceStateCount > 0
                    || removedStaleSourceCount > 0
                    || markedMissingSourceCount > 0)) {

                throw new IllegalArgumentException(
                        "A conflicted reconciliation report cannot contain "
                                + "applied repair counts."
                );
            }
        }

        public boolean successful() {
            return conflicts.isEmpty();
        }

        public boolean hadConflicts() {
            return !conflicts.isEmpty();
        }

        public int getAppliedActionCount() {
            return repairedSourceStateCount
                    + removedStaleSourceCount
                    + markedMissingSourceCount;
        }

        private static ReconciliationReport successful(
                UUID incursionId,
                int inspectedSourceCount,
                int matchingSourceCount,
                int expectedAbsentCount,
                int repairedSourceStateCount,
                int removedStaleSourceCount,
                int markedMissingSourceCount
        ) {
            return new ReconciliationReport(
                    incursionId,
                    inspectedSourceCount,
                    matchingSourceCount,
                    expectedAbsentCount,
                    repairedSourceStateCount,
                    removedStaleSourceCount,
                    markedMissingSourceCount,
                    List.of()
            );
        }

        private static ReconciliationReport conflicted(
                UUID incursionId,
                int inspectedSourceCount,
                int matchingSourceCount,
                int expectedAbsentCount,
                List<ReconciliationConflict> conflicts
        ) {
            return new ReconciliationReport(
                    incursionId,
                    inspectedSourceCount,
                    matchingSourceCount,
                    expectedAbsentCount,
                    0,
                    0,
                    0,
                    conflicts
            );
        }
    }

    private PersistentIncursionWorldReconciliationService() {
    }
}