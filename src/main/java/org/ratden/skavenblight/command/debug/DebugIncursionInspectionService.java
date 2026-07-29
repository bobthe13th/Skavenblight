package org.ratden.skavenblight.command.debug;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.ratden.skavenblight.block.custom.SkavenTunnelSourceBlock;
import org.ratden.skavenblight.block.entity.SkavenTunnelSourceEntity;
import org.ratden.skavenblight.block.entity.state.SourceState;
import org.ratden.skavenblight.event.skavenIncursion.debug.IncursionRuntimeDebugFormatter;
import org.ratden.skavenblight.event.skavenIncursion.debug.IncursionSourceInspectionContext;
import org.ratden.skavenblight.event.skavenIncursion.director.ActiveIncursionManager;
import org.ratden.skavenblight.event.skavenIncursion.planning.IncursionPlan;
import org.ratden.skavenblight.event.skavenIncursion.planning.composition.SourceGroupComposition;
import org.ratden.skavenblight.event.skavenIncursion.planning.front.FrontPlan;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceGroupPlacementPlan;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourcePlacementPlan;
import org.ratden.skavenblight.event.skavenIncursion.runtime.IncursionExecutionState;
import org.ratden.skavenblight.event.skavenIncursion.runtime.IncursionWaveController;
import org.ratden.skavenblight.event.skavenIncursion.runtime.persistence.LivePersistentIncursion;
import org.ratden.skavenblight.event.skavenIncursion.runtime.persistence.PlannedScenarioRuntimeSnapshot;
import org.ratden.skavenblight.event.skavenIncursion.runtime.source.SourceExecutionState;
import org.ratden.skavenblight.event.skavenIncursion.runtime.source.SourceWaveExecutionState;

import java.util.List;
import java.util.UUID;

/**
 * Resolves target-based inspection context for live planning-aware
 * incursions.
 *
 * The selected world object supplies the relevant identity. Developers do not
 * need to enter incursion, source, composition or leadership UUIDs manually.
 *
 * This service owns:
 *
 * - identifying the looked-at physical source;
 * - resolving its owning live persistent incursion;
 * - capturing one coherent runtime checkpoint;
 * - finding the physical source placement;
 * - selecting the most relevant wave assignment;
 * - resolving the matching immutable composition;
 * - constructing IncursionSourceInspectionContext;
 * - sending the selected formatted report.
 *
 * It does not format individual report sections. That responsibility belongs
 * to IncursionRuntimeDebugFormatter.
 */
final class DebugIncursionInspectionService {

    private static final double SOURCE_PICK_RANGE =
            20.0D;

    /**
     * Compatibility route for the original bare inspection command.
     *
     * Bare inspection now produces the concise overview.
     */
    static int inspectLookedAtSource(
            CommandSourceStack source
    ) throws CommandSyntaxException {
        return inspectLookedAtSource(
                source,
                IncursionRuntimeDebugFormatter
                        .InspectionView
                        .OVERVIEW
        );
    }

    /**
     * Resolves the looked-at source once and formats the requested focused
     * inspection view.
     */
    static int inspectLookedAtSource(
            CommandSourceStack source,
            IncursionRuntimeDebugFormatter.InspectionView inspectionView
    ) throws CommandSyntaxException {
        if (inspectionView == null) {
            throw new IllegalArgumentException(
                    "Incursion inspection view cannot be null."
            );
        }

        ServerPlayer player =
                source.getPlayerOrException();

        ServerLevel level =
                player.serverLevel();

        BlockHitResult hitResult =
                findLookedAtBlock(
                        player
                );

        if (hitResult == null) {
            source.sendFailure(
                    Component.literal(
                            "You must be looking at an incursion source."
                    )
            );

            return 0;
        }

        BlockPos sourcePos =
                hitResult.getBlockPos();

        BlockState blockState =
                level.getBlockState(
                        sourcePos
                );

        if (!blockState.hasProperty(
                SkavenTunnelSourceBlock.SOURCE_STATE
        )) {
            source.sendFailure(
                    Component.literal(
                            "Target block is not a tunnel source."
                    )
            );

            return 0;
        }

        if (!(level.getBlockEntity(
                sourcePos
        ) instanceof SkavenTunnelSourceEntity tunnelSource)) {

            source.sendFailure(
                    Component.literal(
                            "Target block has no tunnel source entity."
                    )
            );

            return 0;
        }

        UUID runtimeSourceId =
                tunnelSource.getSourceId();

        UUID incursionId =
                tunnelSource.getScenarioId();

        if (runtimeSourceId == null) {
            source.sendFailure(
                    Component.literal(
                            "Target source has no runtime source ID."
                    )
            );

            return 0;
        }

        if (incursionId == null) {
            source.sendFailure(
                    Component.literal(
                            "Target source has no owning incursion ID."
                    )
            );

            return 0;
        }

        LivePersistentIncursion liveIncursion =
                ActiveIncursionManager.getPersistentIncursion(
                        incursionId
                );

        if (liveIncursion == null) {
            source.sendFailure(
                    Component.literal(
                            "Target source belongs to incursion "
                                    + incursionId
                                    + ", but that incursion is not attached "
                                    + "as a live persistent runtime."
                    )
            );

            return 0;
        }

        if (liveIncursion.getLevel()
                != level) {

            source.sendFailure(
                    Component.literal(
                            "Target source belongs to an incursion attached "
                                    + "to another ServerLevel."
                    )
            );

            return 0;
        }

        /*
         * Command execution occurs on the server thread. Capturing the
         * Scenario and mob-tracking snapshots here therefore forms one
         * coherent inspection checkpoint without a concurrent incursion tick
         * mutating either branch between reads.
         */
        PlannedScenarioRuntimeSnapshot runtimeSnapshot =
                liveIncursion
                        .getScenario()
                        .createSnapshot();

        IncursionExecutionState.Snapshot executionSnapshot =
                runtimeSnapshot
                        .incursionExecutionSnapshot();

        SourceExecutionState.Snapshot sourceExecutionSnapshot =
                findCurrentPhysicalSourceSnapshot(
                        executionSnapshot,
                        runtimeSourceId
                );

        if (sourceExecutionSnapshot == null) {
            SourceExecutionState.Snapshot historicalSourceSnapshot =
                    findHistoricalPhysicalSourceSnapshot(
                            executionSnapshot,
                            runtimeSourceId
                    );

            if (historicalSourceSnapshot != null) {
                source.sendFailure(
                        Component.literal(
                                "Target block uses historical runtime source "
                                        + "ID "
                                        + runtimeSourceId
                                        + " for source placement "
                                        + historicalSourceSnapshot
                                        .sourcePlacementId()
                                        + ". The live runtime records a "
                                        + "different current incarnation."
                        )
                );

                return 0;
            }

            source.sendFailure(
                    Component.literal(
                            "Owning incursion "
                                    + incursionId
                                    + " contains no physical source state for "
                                    + "runtime source ID "
                                    + runtimeSourceId
                                    + "."
                    )
            );

            return 0;
        }

        IncursionWaveController.Snapshot controllerSnapshot =
                runtimeSnapshot
                        .waveControllerSnapshot();

        SourceAssignmentContext assignmentContext =
                findRelevantSourceAssignment(
                        executionSnapshot,
                        controllerSnapshot,
                        sourceExecutionSnapshot
                                .sourcePlacementId()
                );

        if (assignmentContext == null) {
            source.sendFailure(
                    Component.literal(
                            "Physical source placement "
                                    + sourceExecutionSnapshot
                                    .sourcePlacementId()
                                    + " has no wave assignment in its owning "
                                    + "incursion."
                    )
            );

            return 0;
        }

        IncursionPlan incursionPlan =
                liveIncursion.getIncursionPlan();

        PlannedCompositionContext plannedCompositionContext =
                findPlannedComposition(
                        incursionPlan,
                        assignmentContext.waveIndex(),
                        assignmentContext
                                .sourceWaveSnapshot()
                                .sourceCompositionId()
                );

        if (plannedCompositionContext == null) {
            source.sendFailure(
                    Component.literal(
                            "Could not resolve source composition "
                                    + assignmentContext
                                    .sourceWaveSnapshot()
                                    .sourceCompositionId()
                                    + " inside immutable incursion plan "
                                    + incursionId
                                    + "."
                    )
            );

            return 0;
        }

        SourcePlacementPlan sourcePlacementPlan =
                findSourcePlacementPlan(
                        incursionPlan,
                        sourceExecutionSnapshot
                                .sourcePlacementId()
                );

        if (sourcePlacementPlan == null) {
            source.sendFailure(
                    Component.literal(
                            "Could not resolve source placement "
                                    + sourceExecutionSnapshot
                                    .sourcePlacementId()
                                    + " inside immutable incursion plan "
                                    + incursionId
                                    + "."
                    )
            );

            return 0;
        }

        SourceState physicalSourceState =
                blockState.getValue(
                        SkavenTunnelSourceBlock.SOURCE_STATE
                );

        IncursionSourceInspectionContext inspectionContext =
                new IncursionSourceInspectionContext(
                        liveIncursion.getScenarioId(),
                        liveIncursion.getStratagemId(),
                        liveIncursion.getIncursionId(),
                        liveIncursion.getPhase(),
                        controllerSnapshot,
                        sourcePos,
                        physicalSourceState,
                        runtimeSourceId,
                        sourcePlacementPlan,
                        sourceExecutionSnapshot,
                        assignmentContext.waveIndex(),
                        assignmentContext.relation(),
                        plannedCompositionContext.frontIndex(),
                        plannedCompositionContext
                                .sourceGroupComposition(),
                        plannedCompositionContext
                                .sourceComposition(),
                        assignmentContext.sourceWaveSnapshot(),
                        liveIncursion
                                .getMobTrackingState()
                                .createSnapshot()
                );

        String report =
                IncursionRuntimeDebugFormatter.format(
                        inspectionView,
                        inspectionContext
                );

        source.sendSuccess(
                () -> Component.literal(
                        report
                ),
                false
        );

        return 1;
    }

    private static BlockHitResult findLookedAtBlock(
            ServerPlayer player
    ) {
        HitResult hitResult =
                player.pick(
                        SOURCE_PICK_RANGE,
                        0.0F,
                        false
                );

        if (!(hitResult
                instanceof BlockHitResult blockHitResult)) {

            return null;
        }

        if (blockHitResult.getType()
                != HitResult.Type.BLOCK) {

            return null;
        }

        return blockHitResult;
    }

    /**
     * Finds the logical source state whose current physical incarnation uses
     * the selected block entity's source UUID.
     */
    private static SourceExecutionState.Snapshot
    findCurrentPhysicalSourceSnapshot(
            IncursionExecutionState.Snapshot executionSnapshot,
            UUID runtimeSourceId
    ) {
        for (SourceExecutionState.Snapshot sourceSnapshot
                : executionSnapshot.sourceExecutionSnapshots()) {

            if (runtimeSourceId.equals(
                    sourceSnapshot.runtimeSourceId()
            )) {
                return sourceSnapshot;
            }
        }

        return null;
    }

    /**
     * Detects a stale physical block using an older source incarnation UUID.
     */
    private static SourceExecutionState.Snapshot
    findHistoricalPhysicalSourceSnapshot(
            IncursionExecutionState.Snapshot executionSnapshot,
            UUID runtimeSourceId
    ) {
        for (SourceExecutionState.Snapshot sourceSnapshot
                : executionSnapshot.sourceExecutionSnapshots()) {

            if (sourceSnapshot
                    .runtimeSourceIdHistory()
                    .contains(
                            runtimeSourceId
                    )) {

                return sourceSnapshot;
            }
        }

        return null;
    }

    /**
     * Resolves the selected physical source's most useful wave assignment.
     *
     * Priority:
     *
     * 1. assignment in the currently executing wave;
     * 2. nearest upcoming assignment with undelivered mobs;
     * 3. most recent previous assignment.
     */
    private static SourceAssignmentContext
    findRelevantSourceAssignment(
            IncursionExecutionState.Snapshot executionSnapshot,
            IncursionWaveController.Snapshot controllerSnapshot,
            UUID sourcePlacementId
    ) {
        int currentWaveIndex =
                controllerSnapshot.getCurrentWaveIndex();

        if (currentWaveIndex >= 0) {
            SourceWaveExecutionState.Snapshot currentAssignment =
                    findSourceAssignment(
                            executionSnapshot,
                            currentWaveIndex,
                            sourcePlacementId
                    );

            if (currentAssignment != null) {
                return new SourceAssignmentContext(
                        currentWaveIndex,
                        IncursionSourceInspectionContext
                                .AssignmentRelation
                                .CURRENT,
                        currentAssignment
                );
            }
        }

        for (IncursionExecutionState.WaveSourceAssignmentsSnapshot
                waveSnapshot
                : executionSnapshot.waveSourceAssignmentSnapshots()) {

            if (waveSnapshot.waveIndex()
                    <= currentWaveIndex) {

                continue;
            }

            SourceWaveExecutionState.Snapshot upcomingAssignment =
                    findSourceAssignment(
                            waveSnapshot,
                            sourcePlacementId
                    );

            if (upcomingAssignment == null
                    || upcomingAssignment.getRemainingMobCount()
                    <= 0) {

                continue;
            }

            return new SourceAssignmentContext(
                    waveSnapshot.waveIndex(),
                    IncursionSourceInspectionContext
                            .AssignmentRelation
                            .UPCOMING,
                    upcomingAssignment
            );
        }

        List<IncursionExecutionState.WaveSourceAssignmentsSnapshot>
                waveSnapshots =
                executionSnapshot.waveSourceAssignmentSnapshots();

        for (int wavePosition =
             waveSnapshots.size() - 1;
             wavePosition >= 0;
             wavePosition--) {

            IncursionExecutionState.WaveSourceAssignmentsSnapshot
                    waveSnapshot =
                    waveSnapshots.get(
                            wavePosition
                    );

            if (currentWaveIndex >= 0
                    && waveSnapshot.waveIndex()
                    > currentWaveIndex) {

                continue;
            }

            SourceWaveExecutionState.Snapshot previousAssignment =
                    findSourceAssignment(
                            waveSnapshot,
                            sourcePlacementId
                    );

            if (previousAssignment != null) {
                return new SourceAssignmentContext(
                        waveSnapshot.waveIndex(),
                        IncursionSourceInspectionContext
                                .AssignmentRelation
                                .PREVIOUS,
                        previousAssignment
                );
            }
        }

        return null;
    }

    private static SourceWaveExecutionState.Snapshot
    findSourceAssignment(
            IncursionExecutionState.Snapshot executionSnapshot,
            int waveIndex,
            UUID sourcePlacementId
    ) {
        IncursionExecutionState.WaveSourceAssignmentsSnapshot waveSnapshot =
                executionSnapshot.getWaveSourceAssignmentSnapshot(
                        waveIndex
                );

        if (waveSnapshot == null) {
            return null;
        }

        return findSourceAssignment(
                waveSnapshot,
                sourcePlacementId
        );
    }

    private static SourceWaveExecutionState.Snapshot
    findSourceAssignment(
            IncursionExecutionState.WaveSourceAssignmentsSnapshot
                    waveSnapshot,
            UUID sourcePlacementId
    ) {
        for (SourceWaveExecutionState.Snapshot sourceWaveSnapshot
                : waveSnapshot.sourceWaveExecutionSnapshots()) {

            if (sourcePlacementId.equals(
                    sourceWaveSnapshot.sourcePlacementId()
            )) {
                return sourceWaveSnapshot;
            }
        }

        return null;
    }

    /**
     * Resolves the exact immutable physical source-placement object.
     */
    private static SourcePlacementPlan findSourcePlacementPlan(
            IncursionPlan incursionPlan,
            UUID sourcePlacementId
    ) {
        if (incursionPlan == null) {
            throw new IllegalArgumentException(
                    "Incursion plan cannot be null."
            );
        }

        if (sourcePlacementId == null) {
            throw new IllegalArgumentException(
                    "Source placement ID cannot be null."
            );
        }

        SourcePlacementPlan matchedSourcePlacementPlan =
                null;

        for (FrontPlan frontPlan
                : incursionPlan.getFrontPlans()) {

            for (SourceGroupPlacementPlan sourceGroupPlacementPlan
                    : frontPlan.getSourceGroupPlacementPlans()) {

                UUID containingGroupPlacementId =
                        sourceGroupPlacementPlan
                                .getSourceGroupPlacementId();

                for (SourcePlacementPlan candidateSourcePlacementPlan
                        : sourceGroupPlacementPlan
                        .getSourcePlacementPlans()) {

                    if (!sourcePlacementId.equals(
                            candidateSourcePlacementPlan
                                    .getSourcePlacementId()
                    )) {
                        continue;
                    }

                    if (!containingGroupPlacementId.equals(
                            candidateSourcePlacementPlan
                                    .getSourceGroupPlacementId()
                    )) {
                        throw new IllegalStateException(
                                "Source placement "
                                        + sourcePlacementId
                                        + " identifies source group "
                                        + candidateSourcePlacementPlan
                                        .getSourceGroupPlacementId()
                                        + ", but its containing placement "
                                        + "group is "
                                        + containingGroupPlacementId
                                        + "."
                        );
                    }

                    if (matchedSourcePlacementPlan != null) {
                        throw new IllegalStateException(
                                "Source placement "
                                        + sourcePlacementId
                                        + " appears more than once in the "
                                        + "immutable incursion plan."
                        );
                    }

                    matchedSourcePlacementPlan =
                            candidateSourcePlacementPlan;
                }
            }
        }

        return matchedSourcePlacementPlan;
    }

    /**
     * Resolves the immutable composition represented by one runtime
     * assignment.
     */
    private static PlannedCompositionContext findPlannedComposition(
            IncursionPlan incursionPlan,
            int waveIndex,
            UUID sourceCompositionId
    ) {
        if (incursionPlan == null) {
            throw new IllegalArgumentException(
                    "Incursion plan cannot be null."
            );
        }

        if (waveIndex < 0) {
            throw new IllegalArgumentException(
                    "Wave index cannot be negative."
            );
        }

        if (sourceCompositionId == null) {
            throw new IllegalArgumentException(
                    "Source composition ID cannot be null."
            );
        }

        PlannedCompositionContext matchedComposition =
                null;

        for (FrontPlan frontPlan
                : incursionPlan.getFrontPlans()) {

            FrontPlan.WavePlan wavePlan =
                    frontPlan.getWavePlan(
                            waveIndex
                    );

            if (wavePlan == null) {
                continue;
            }

            for (SourceGroupComposition sourceGroupComposition
                    : wavePlan.getSourceGroupCompositions()) {

                SourceGroupComposition.SourceComposition sourceComposition =
                        sourceGroupComposition.getSourceComposition(
                                sourceCompositionId
                        );

                if (sourceComposition == null) {
                    continue;
                }

                if (matchedComposition != null) {
                    throw new IllegalStateException(
                            "Source composition "
                                    + sourceCompositionId
                                    + " appears more than once in wave "
                                    + waveIndex
                                    + " of the immutable incursion plan."
                    );
                }

                matchedComposition =
                        new PlannedCompositionContext(
                                frontPlan.getFrontIndex(),
                                sourceGroupComposition,
                                sourceComposition
                        );
            }
        }

        return matchedComposition;
    }

    private record SourceAssignmentContext(
            int waveIndex,
            IncursionSourceInspectionContext.AssignmentRelation relation,
            SourceWaveExecutionState.Snapshot sourceWaveSnapshot
    ) {

        private SourceAssignmentContext {
            if (waveIndex < 0) {
                throw new IllegalArgumentException(
                        "Source-assignment context wave index cannot be "
                                + "negative."
                );
            }

            if (relation == null) {
                throw new IllegalArgumentException(
                        "Source-assignment context relation cannot be null."
                );
            }

            if (sourceWaveSnapshot == null) {
                throw new IllegalArgumentException(
                        "Source-assignment context snapshot cannot be null."
                );
            }

            if (sourceWaveSnapshot.waveIndex()
                    != waveIndex) {

                throw new IllegalArgumentException(
                        "Source-assignment context wave index "
                                + waveIndex
                                + " does not match source-wave snapshot "
                                + sourceWaveSnapshot.waveIndex()
                                + "."
                );
            }
        }
    }

    private record PlannedCompositionContext(
            int frontIndex,
            SourceGroupComposition sourceGroupComposition,
            SourceGroupComposition.SourceComposition sourceComposition
    ) {

        private PlannedCompositionContext {
            if (frontIndex < 0) {
                throw new IllegalArgumentException(
                        "Planned-composition context front index cannot be "
                                + "negative."
                );
            }

            if (sourceGroupComposition == null) {
                throw new IllegalArgumentException(
                        "Planned-composition context requires a source-group "
                                + "composition."
                );
            }

            if (sourceComposition == null) {
                throw new IllegalArgumentException(
                        "Planned-composition context requires a source "
                                + "composition."
                );
            }

            if (sourceGroupComposition.getSourceComposition(
                    sourceComposition.getSourceCompositionId()
            ) != sourceComposition) {
                throw new IllegalArgumentException(
                        "Planned-composition context source composition is not "
                                + "the exact immutable child of its source "
                                + "group."
                );
            }
        }
    }

    private DebugIncursionInspectionService() {
    }
}