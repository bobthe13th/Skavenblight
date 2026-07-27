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
import org.ratden.skavenblight.event.skavenIncursion.director.ActiveIncursionManager;
import org.ratden.skavenblight.event.skavenIncursion.planning.IncursionPlan;
import org.ratden.skavenblight.event.skavenIncursion.planning.composition.SourceGroupComposition;
import org.ratden.skavenblight.event.skavenIncursion.planning.front.FrontPlan;
import org.ratden.skavenblight.event.skavenIncursion.runtime.IncursionExecutionState;
import org.ratden.skavenblight.event.skavenIncursion.runtime.IncursionWaveController;
import org.ratden.skavenblight.event.skavenIncursion.runtime.WaveExecutionState;
import org.ratden.skavenblight.event.skavenIncursion.runtime.persistence.LivePersistentIncursion;
import org.ratden.skavenblight.event.skavenIncursion.runtime.persistence.PlannedScenarioRuntimeSnapshot;
import org.ratden.skavenblight.event.skavenIncursion.runtime.source.SourceExecutionState;
import org.ratden.skavenblight.event.skavenIncursion.runtime.source.SourceSpawnQueue;
import org.ratden.skavenblight.event.skavenIncursion.runtime.source.SourceWaveExecutionState;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceGroupPlacementPlan;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourcePlacementPlan;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Focused target-based inspection for live planning-aware incursions.
 *
 * The selected world object supplies the relevant identity. This avoids
 * requiring developers to enter or choose from lists of incursion, source,
 * composition and leadership UUIDs.
 *
 * The first implementation supports looked-at physical source blocks.
 * It reports only that source's branch of the owning incursion:
 *
 * - owning Scenario and Stratagem;
 * - current controller state;
 * - physical and logical source state;
 * - the source's most relevant wave assignment;
 * - planned composition;
 * - successful, cancelled and remaining delivery counts;
 * - the exact remaining shuffled queue.
 *
 * It does not produce a complete report for every front, wave, group or
 * source in the incursion.
 */
final class DebugIncursionInspectionService {

    private static final double SOURCE_PICK_RANGE =
            20.0D;

    static int inspectLookedAtSource(
            CommandSourceStack source
    ) throws CommandSyntaxException {
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

        PlannedCompositionContext plannedCompositionContext =
                findPlannedComposition(
                        liveIncursion.getIncursionPlan(),
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

        UUID sourceGroupPlacementId =
                findSourceGroupPlacementId(
                        liveIncursion.getIncursionPlan(),
                        sourceExecutionSnapshot
                                .sourcePlacementId()
                );

        if (sourceGroupPlacementId == null) {
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

        String report =
                formatSourceInspection(
                        liveIncursion,
                        runtimeSnapshot,
                        sourcePos,
                        physicalSourceState,
                        runtimeSourceId,
                        sourceGroupPlacementId,
                        sourceExecutionSnapshot,
                        assignmentContext,
                        plannedCompositionContext
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
     * Priority is:
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
                        AssignmentRelation.CURRENT,
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
                    AssignmentRelation.UPCOMING,
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
                        AssignmentRelation.PREVIOUS,
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
     * Resolves the stable physical source-group placement that owns one
     * source-placement slot.
     *
     * SourceGroupPlacementPlan and SourcePlacementPlan persist across waves.
     * Their identities must therefore not be confused with the wave-specific
     * SourceGroupComposition and SourceComposition identities shown elsewhere
     * in the inspection report.
     */
    private static UUID findSourceGroupPlacementId(
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

        UUID matchedSourceGroupPlacementId =
                null;

        for (FrontPlan frontPlan
                : incursionPlan.getFrontPlans()) {

            for (SourceGroupPlacementPlan sourceGroupPlacementPlan
                    : frontPlan.getSourceGroupPlacementPlans()) {

                UUID sourceGroupPlacementId =
                        sourceGroupPlacementPlan
                                .getSourceGroupPlacementId();

                for (SourcePlacementPlan sourcePlacementPlan
                        : sourceGroupPlacementPlan
                        .getSourcePlacementPlans()) {

                    if (!sourcePlacementId.equals(
                            sourcePlacementPlan
                                    .getSourcePlacementId()
                    )) {
                        continue;
                    }

                    if (!sourceGroupPlacementId.equals(
                            sourcePlacementPlan
                                    .getSourceGroupPlacementId()
                    )) {
                        throw new IllegalStateException(
                                "Source placement "
                                        + sourcePlacementId
                                        + " identifies source group "
                                        + sourcePlacementPlan
                                        .getSourceGroupPlacementId()
                                        + ", but its containing placement "
                                        + "group is "
                                        + sourceGroupPlacementId
                                        + "."
                        );
                    }

                    if (matchedSourceGroupPlacementId != null
                            && !matchedSourceGroupPlacementId.equals(
                            sourceGroupPlacementId
                    )) {
                        throw new IllegalStateException(
                                "Source placement "
                                        + sourcePlacementId
                                        + " appears in multiple physical "
                                        + "source groups."
                        );
                    }

                    matchedSourceGroupPlacementId =
                            sourceGroupPlacementId;
                }
            }
        }

        return matchedSourceGroupPlacementId;
    }

    /**
     * Finds the immutable composition represented by one runtime assignment.
     */
    private static PlannedCompositionContext findPlannedComposition(
            IncursionPlan incursionPlan,
            int waveIndex,
            UUID sourceCompositionId
    ) {
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

                return new PlannedCompositionContext(
                        frontPlan.getFrontIndex(),
                        sourceGroupComposition,
                        sourceComposition
                );
            }
        }

        return null;
    }

    private static String formatSourceInspection(
            LivePersistentIncursion liveIncursion,
            PlannedScenarioRuntimeSnapshot runtimeSnapshot,
            BlockPos sourcePos,
            SourceState physicalSourceState,
            UUID runtimeSourceId,
            UUID sourceGroupPlacementId,
            SourceExecutionState.Snapshot sourceExecutionSnapshot,
            SourceAssignmentContext assignmentContext,
            PlannedCompositionContext plannedCompositionContext
    ) {
        IncursionWaveController.Snapshot controllerSnapshot =
                runtimeSnapshot.waveControllerSnapshot();

        SourceWaveExecutionState.Snapshot sourceWaveSnapshot =
                assignmentContext.sourceWaveSnapshot();

        SourceSpawnQueue.Snapshot queueSnapshot =
                sourceWaveSnapshot.spawnQueueSnapshot();

        StringBuilder report =
                new StringBuilder();

        report.append("Incursion Source Inspection")
                .append("\nScenario: ")
                .append(liveIncursion.getScenarioId())
                .append("\nStratagem: ")
                .append(liveIncursion.getStratagemId())
                .append("\nIncursion ID: ")
                .append(liveIncursion.getIncursionId())
                .append("\nPersistent Phase: ")
                .append(liveIncursion.getPhase())
                .append("\nController State: ")
                .append(controllerSnapshot.completionReason())
                .append("\nCurrent Wave: ")
                .append(
                        formatCurrentWave(
                                controllerSnapshot
                        )
                );

        report.append("\n\nSelected Physical Source")
                .append("\nPosition: ")
                .append(formatBlockPos(
                        sourcePos
                ))
                .append("\nSource Group Placement ID: ")
                .append(sourceGroupPlacementId)
                .append("\nSource Placement ID: ")
                .append(sourceExecutionSnapshot.sourcePlacementId())
                .append("\nRuntime Source ID: ")
                .append(runtimeSourceId)
                .append("\nPhysical State: ")
                .append(
                        physicalSourceState.getSerializedName()
                )
                .append("\nLogical State: ")
                .append(
                        formatSourceState(
                                sourceExecutionSnapshot.currentSourceState()
                        )
                )
                .append("\nCurrently Destroyed: ")
                .append(sourceExecutionSnapshot.currentlyDestroyed())
                .append("\nDestruction Count: ")
                .append(sourceExecutionSnapshot.destructionCount())
                .append("\nPhysical Incarnations: ")
                .append(
                        sourceExecutionSnapshot
                                .runtimeSourceIdHistory()
                                .size()
                );

        report.append("\n\nRelevant Assignment")
                .append("\nRelation: ")
                .append(
                        assignmentContext
                                .relation()
                                .displayName()
                )
                .append("\nWave: ")
                .append(assignmentContext.waveIndex() + 1)
                .append("\nFront: ")
                .append(
                        plannedCompositionContext.frontIndex()
                                + 1
                )
                .append("\nSource Group Composition ID: ")
                .append(
                        plannedCompositionContext
                                .sourceGroupComposition()
                                .getSourceGroupCompositionId()
                )
                .append("\nSource Composition ID: ")
                .append(
                        sourceWaveSnapshot.sourceCompositionId()
                );

        appendPlannedComposition(
                report,
                plannedCompositionContext.sourceComposition()
        );

        report.append("\nProgress: ")
                .append(sourceWaveSnapshot.successfulSpawnCount())
                .append(" spawned, ")
                .append(sourceWaveSnapshot.cancelledMobCount())
                .append(" cancelled, ")
                .append(sourceWaveSnapshot.getRemainingMobCount())
                .append(" remaining, ")
                .append(sourceWaveSnapshot.plannedMobCount())
                .append(" planned");

        appendWaveTiming(
                report,
                controllerSnapshot,
                assignmentContext
        );

        appendRemainingQueue(
                report,
                queueSnapshot
        );

        return report.toString();
    }

    private static void appendPlannedComposition(
            StringBuilder report,
            SourceGroupComposition.SourceComposition sourceComposition
    ) {
        report.append("\nPlanned Mobs:");

        for (SourceGroupComposition.MobEntry mobEntry
                : sourceComposition.getMobEntries()) {

            report.append("\n  ")
                    .append(mobEntry.getMobId())
                    .append(" x")
                    .append(mobEntry.getCount());
        }
    }

    private static void appendWaveTiming(
            StringBuilder report,
            IncursionWaveController.Snapshot controllerSnapshot,
            SourceAssignmentContext assignmentContext
    ) {
        if (assignmentContext.relation()
                != AssignmentRelation.CURRENT) {

            report.append("\nWave Timing: ")
                    .append(
                            switch (assignmentContext.relation()) {
                                case UPCOMING ->
                                        "wave has not started";
                                case PREVIOUS ->
                                        "wave assignment has completed";
                                case CURRENT ->
                                        throw new IllegalStateException(
                                                "Current assignment should "
                                                        + "have timing data."
                                        );
                            }
                    );

            return;
        }

        WaveExecutionState.Snapshot currentWaveSnapshot =
                controllerSnapshot.currentWaveExecutionSnapshot();

        if (currentWaveSnapshot == null
                || currentWaveSnapshot.waveIndex()
                != assignmentContext.waveIndex()) {

            report.append(
                    "\nWave Timing: current wave timing unavailable"
            );

            return;
        }

        report.append("\nWave Elapsed: ")
                .append(
                        formatTicks(
                                currentWaveSnapshot.elapsedTicks()
                        )
                );

        if (currentWaveSnapshot.spawnScheduleComplete()) {
            report.append("\nSpawn Schedule: complete");

            return;
        }

        int ticksUntilNextSpawn =
                Math.max(
                        0,
                        currentWaveSnapshot.nextSpawnTick()
                                - currentWaveSnapshot.elapsedTicks()
                );

        report.append("\nNext Spawn Eligible In: ")
                .append(
                        formatTicks(
                                ticksUntilNextSpawn
                        )
                );
    }

    private static void appendRemainingQueue(
            StringBuilder report,
            SourceSpawnQueue.Snapshot queueSnapshot
    ) {
        report.append("\nRemaining Queue:");

        List<String> remainingMobOrder =
                queueSnapshot.remainingMobOrder();

        if (remainingMobOrder.isEmpty()) {
            report.append("\n  none");

            return;
        }

        for (int queueIndex = 0;
             queueIndex < remainingMobOrder.size();
             queueIndex++) {

            report.append("\n  ")
                    .append(queueIndex + 1)
                    .append(". ")
                    .append(
                            remainingMobOrder.get(
                                    queueIndex
                            )
                    );
        }
    }

    private static String formatCurrentWave(
            IncursionWaveController.Snapshot controllerSnapshot
    ) {
        int currentWaveIndex =
                controllerSnapshot.getCurrentWaveIndex();

        if (currentWaveIndex < 0) {
            return "none";
        }

        return (controllerSnapshot.currentWavePosition() + 1)
                + " of "
                + controllerSnapshot.waveIndexes().size()
                + " (authored index "
                + currentWaveIndex
                + ")";
    }

    private static String formatSourceState(
            SourceState sourceState
    ) {
        return sourceState == null
                ? "none"
                : sourceState.getSerializedName();
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

    private static String formatTicks(
            int ticks
    ) {
        double seconds =
                ticks / 20.0D;

        return ticks
                + " ticks ("
                + String.format(
                Locale.ROOT,
                "%.1f",
                seconds
        )
                + " seconds)";
    }

    private enum AssignmentRelation {
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

        private String displayName() {
            return displayName;
        }
    }

    private record SourceAssignmentContext(
            int waveIndex,
            AssignmentRelation relation,
            SourceWaveExecutionState.Snapshot sourceWaveSnapshot
    ) {
    }

    private record PlannedCompositionContext(
            int frontIndex,
            SourceGroupComposition sourceGroupComposition,
            SourceGroupComposition.SourceComposition sourceComposition
    ) {
    }

    private DebugIncursionInspectionService() {
    }
}