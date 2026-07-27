package org.ratden.skavenblight.event.skavenIncursion.debug;

import net.minecraft.core.BlockPos;
import org.ratden.skavenblight.event.skavenIncursion.planning.composition.SourceGroupComposition;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourcePlacementPlan;
import org.ratden.skavenblight.event.skavenIncursion.runtime.IncursionWaveController;
import org.ratden.skavenblight.event.skavenIncursion.runtime.WaveExecutionState;
import org.ratden.skavenblight.event.skavenIncursion.runtime.mob.IncursionMobTrackingState;
import org.ratden.skavenblight.event.skavenIncursion.runtime.source.SourceSpawnQueue;
import org.ratden.skavenblight.event.skavenIncursion.runtime.source.SourceWaveExecutionState;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Formats focused runtime reports for one fully resolved incursion source.
 *
 * Target resolution is deliberately absent from this class. The command-side
 * inspection service resolves the looked-at source once and supplies an
 * IncursionSourceInspectionContext.
 *
 * Supported views:
 *
 * - OVERVIEW:
 *   concise identity, lifecycle and accounting status;
 *
 * - PLACEMENT:
 *   physical source structure, incarnation and placement information;
 *
 * - COMPOSITION:
 *   immutable purchased force, represented threat and attachments;
 *
 * - QUEUE:
 *   pending delivery order, threat and spawn timing;
 *
 * - TRACKING:
 *   delivered entity identity, threat and lifecycle resolution;
 *
 * - ALL:
 *   every section in a stable order.
 */
public final class IncursionRuntimeDebugFormatter {

    public static String format(
            InspectionView inspectionView,
            IncursionSourceInspectionContext context
    ) {
        if (inspectionView == null) {
            throw new IllegalArgumentException(
                    "Runtime inspection view cannot be null."
            );
        }

        if (context == null) {
            throw new IllegalArgumentException(
                    "Runtime source-inspection context cannot be null."
            );
        }

        String report =
                switch (inspectionView) {
                    case OVERVIEW ->
                            formatOverview(
                                    context
                            );

                    case PLACEMENT ->
                            formatPlacement(
                                    context
                            );

                    case COMPOSITION ->
                            formatComposition(
                                    context
                            );

                    case QUEUE ->
                            formatQueue(
                                    context
                            );

                    case TRACKING ->
                            formatTracking(
                                    context
                            );

                    case ALL ->
                            formatAll(
                                    context
                            );
                };

        /*
         * formatAll already gives every constituent section its own report
         * boundary. Wrapping the complete result again would add a redundant
         * outer ALL banner.
         */
        if (inspectionView
                == InspectionView.ALL) {

            return report;
        }

        return wrapReport(
                inspectionView,
                report
        );
    }
    public static String formatOverview(
            IncursionSourceInspectionContext context
    ) {
        requireContext(
                context
        );

        AssignmentTrackingSummary trackingSummary =
                createAssignmentTrackingSummary(
                        context
                );

        SourceWaveExecutionState.Snapshot sourceWaveSnapshot =
                context.sourceWaveSnapshot();

        StringBuilder report =
                new StringBuilder();

        report.append("Incursion Source Overview")
                .append("\nScenario: ")
                .append(context.scenarioId())
                .append("\nStratagem: ")
                .append(context.stratagemId())
                .append("\nIncursion ID: ")
                .append(context.incursionId())
                .append("\nPersistent Phase: ")
                .append(context.persistentPhase())
                .append("\nController State: ")
                .append(
                        context.controllerSnapshot()
                                .completionReason()
                )
                .append("\nCurrent Wave: ")
                .append(
                        formatCurrentWave(
                                context.controllerSnapshot()
                        )
                );

        report.append("\n\nSelected Source")
                .append("\nPosition: ")
                .append(
                        formatBlockPos(
                                context.sourcePos()
                        )
                )
                .append("\nPhysical / Logical State: ")
                .append(
                        context.physicalSourceState()
                                .getSerializedName()
                )
                .append(" / ")
                .append(
                        formatSourceState(
                                context
                                        .sourceExecutionSnapshot()
                                        .currentSourceState()
                        )
                )
                .append("\nAssignment: ")
                .append(
                        context.assignmentRelation()
                                .displayName()
                )
                .append(", wave ")
                .append(context.waveIndex() + 1)
                .append(", front ")
                .append(context.frontIndex() + 1);

        report.append("\n\nProgress: ")
                .append(sourceWaveSnapshot.successfulSpawnCount())
                .append(" spawned, ")
                .append(sourceWaveSnapshot.cancelledMobCount())
                .append(" cancelled, ")
                .append(sourceWaveSnapshot.getRemainingMobCount())
                .append(" pending, ")
                .append(sourceWaveSnapshot.plannedMobCount())
                .append(" planned")
                .append("\nTracking: ")
                .append(trackingSummary.trackedMobCount())
                .append(" tracked / ")
                .append(sourceWaveSnapshot.successfulSpawnCount())
                .append(" successful — ")
                .append(
                        trackingSummary.trackingCountValid()
                                ? "valid"
                                : "MISMATCH"
                )
                .append("\nThreat: ")
                .append(trackingSummary.deliveredThreat())
                .append(" delivered + ")
                .append(trackingSummary.pendingThreat())
                .append(" pending + ")
                .append(trackingSummary.cancelledThreat())
                .append(" cancelled = ")
                .append(trackingSummary.plannedThreat())
                .append(" planned — ")
                .append(
                        trackingSummary.threatAccountingValid()
                                ? "valid"
                                : "MISMATCH"
                );

        return report.toString();
    }

    public static String formatPlacement(
            IncursionSourceInspectionContext context
    ) {
        requireContext(
                context
        );

        SourcePlacementPlan sourcePlacementPlan =
                context.sourcePlacementPlan();

        StringBuilder report =
                new StringBuilder();

        report.append("Incursion Source Placement")
                .append("\nIncursion ID: ")
                .append(context.incursionId())
                .append("\nFront: ")
                .append(context.frontIndex() + 1)
                .append("\nAssignment: ")
                .append(
                        context.assignmentRelation()
                                .displayName()
                )
                .append(", wave ")
                .append(context.waveIndex() + 1);

        report.append("\n\nPhysical Structure")
                .append("\nPosition: ")
                .append(
                        formatBlockPos(
                                context.sourcePos()
                        )
                )
                .append("\nAnchor Position: ")
                .append(
                        formatBlockPos(
                                sourcePlacementPlan.getAnchorPos()
                        )
                )
                .append("\nFacing: ")
                .append(sourcePlacementPlan.getFacing())
                .append("\nType: ")
                .append(sourcePlacementPlan.getSourceType())
                .append("\nSize: ")
                .append(sourcePlacementPlan.getSourceSize())
                .append("\nRole: ")
                .append(sourcePlacementPlan.getSourceRole())
                .append("\nCapacity: ")
                .append(sourcePlacementPlan.getCapacityUnits())
                .append("\nBound Wave Compositions: ")
                .append(sourcePlacementPlan.getBoundCompositionCount());

        report.append("\n\nPlacement Identity")
                .append("\nSource Group Placement ID: ")
                .append(context.sourceGroupPlacementId())
                .append("\nSource Placement ID: ")
                .append(context.sourcePlacementId())
                .append("\nCurrent Runtime Source ID: ")
                .append(context.runtimeSourceId());

        report.append("\n\nPhysical Runtime")
                .append("\nPhysical State: ")
                .append(
                        context.physicalSourceState()
                                .getSerializedName()
                )
                .append("\nLogical State: ")
                .append(
                        formatSourceState(
                                context
                                        .sourceExecutionSnapshot()
                                        .currentSourceState()
                        )
                )
                .append("\nCurrently Destroyed: ")
                .append(
                        context
                                .sourceExecutionSnapshot()
                                .currentlyDestroyed()
                )
                .append("\nDestruction Count: ")
                .append(
                        context
                                .sourceExecutionSnapshot()
                                .destructionCount()
                )
                .append("\nPhysical Incarnations: ")
                .append(
                        context
                                .sourceExecutionSnapshot()
                                .runtimeSourceIdHistory()
                                .size()
                );

        appendRuntimeSourceHistory(
                report,
                context
                        .sourceExecutionSnapshot()
                        .runtimeSourceIdHistory(),
                context.runtimeSourceId()
        );

        return report.toString();
    }

    public static String formatComposition(
            IncursionSourceInspectionContext context
    ) {
        requireContext(
                context
        );

        SourceGroupComposition sourceGroupComposition =
                context.sourceGroupComposition();

        SourceGroupComposition.SourceComposition sourceComposition =
                context.sourceComposition();

        StringBuilder report =
                new StringBuilder();

        report.append("Incursion Source Composition")
                .append("\nIncursion ID: ")
                .append(context.incursionId())
                .append("\nWave: ")
                .append(context.waveIndex() + 1)
                .append("\nFront: ")
                .append(context.frontIndex() + 1)
                .append("\nRelation: ")
                .append(
                        context.assignmentRelation()
                                .displayName()
                );

        report.append("\n\nComposition Identity")
                .append("\nSource Group Composition ID: ")
                .append(context.sourceGroupCompositionId())
                .append("\nSource Composition ID: ")
                .append(context.sourceCompositionId());

        report.append("\n\nInfrastructure Requirement")
                .append("\nRequired Type: ")
                .append(sourceComposition.getRequiredSourceType())
                .append("\nRequired Size: ")
                .append(sourceComposition.getRequiredSourceSize())
                .append("\nSource Role: ")
                .append(sourceComposition.getSourceRole())
                .append("\nCapacity Used: ")
                .append(sourceComposition.getUsedCapacityUnits())
                .append(" / ")
                .append(
                        sourceComposition
                                .getRequiredSourceSize()
                                .getCapacityUnits()
                );

        report.append("\n\nBudget")
                .append("\nPlanned Threat: ")
                .append(sourceComposition.getThreatSpent())
                .append("\nCalculated Threat: ")
                .append(sourceComposition.getCalculatedThreatSpent())
                .append("\nComplexity Spent: ")
                .append(sourceComposition.getComplexitySpent());

        report.append("\n\nPlanned Mobs:");

        for (SourceGroupComposition.MobEntry mobEntry
                : sourceComposition.getMobEntries()) {

            int entryThreat =
                    Math.multiplyExact(
                            mobEntry.getCount(),
                            mobEntry.getRepresentedThreatPerMob()
                    );

            report.append("\n  ")
                    .append(mobEntry.getMobId())
                    .append(" x")
                    .append(mobEntry.getCount())
                    .append(" — ")
                    .append(mobEntry.getRepresentedThreatPerMob())
                    .append(" threat each, ")
                    .append(entryThreat)
                    .append(" total");
        }

        appendAttachedAssignments(
                report,
                context
        );

        appendPackAssignment(
                report,
                sourceGroupComposition,
                sourceComposition
        );

        appendEnumSet(
                report,
                "Modifiers",
                sourceComposition.getModifiers()
        );

        appendEnumSet(
                report,
                "Orders",
                sourceComposition.getOrders()
        );

        return report.toString();
    }

    public static String formatQueue(
            IncursionSourceInspectionContext context
    ) {
        requireContext(
                context
        );

        SourceWaveExecutionState.Snapshot sourceWaveSnapshot =
                context.sourceWaveSnapshot();

        SourceSpawnQueue.Snapshot queueSnapshot =
                sourceWaveSnapshot.spawnQueueSnapshot();

        AssignmentTrackingSummary trackingSummary =
                createAssignmentTrackingSummary(
                        context
                );

        StringBuilder report =
                new StringBuilder();

        report.append("Incursion Source Spawn Queue")
                .append("\nIncursion ID: ")
                .append(context.incursionId())
                .append("\nWave: ")
                .append(context.waveIndex() + 1)
                .append("\nRelation: ")
                .append(
                        context.assignmentRelation()
                                .displayName()
                )
                .append("\nSource Composition ID: ")
                .append(context.sourceCompositionId());

        report.append("\n\nProgress: ")
                .append(sourceWaveSnapshot.successfulSpawnCount())
                .append(" spawned, ")
                .append(sourceWaveSnapshot.cancelledMobCount())
                .append(" cancelled, ")
                .append(sourceWaveSnapshot.getRemainingMobCount())
                .append(" pending, ")
                .append(sourceWaveSnapshot.plannedMobCount())
                .append(" planned")
                .append("\nThreat: ")
                .append(trackingSummary.deliveredThreat())
                .append(" delivered, ")
                .append(trackingSummary.pendingThreat())
                .append(" pending, ")
                .append(trackingSummary.cancelledThreat())
                .append(" cancelled, ")
                .append(trackingSummary.plannedThreat())
                .append(" planned");

        appendWaveTiming(
                report,
                context
        );

        report.append("\n\nRemaining Delivery Order:");

        List<SourceSpawnQueue.DeliveryEntry> remainingEntries =
                queueSnapshot.remainingDeliveryEntries();

        if (remainingEntries.isEmpty()) {
            report.append("\n  none");
        } else {
            for (int queueIndex = 0;
                 queueIndex < remainingEntries.size();
                 queueIndex++) {

                SourceSpawnQueue.DeliveryEntry deliveryEntry =
                        remainingEntries.get(
                                queueIndex
                        );

                report.append("\n  ")
                        .append(queueIndex + 1)
                        .append(". ")
                        .append(deliveryEntry.mobId())
                        .append(" — ")
                        .append(deliveryEntry.representedThreat())
                        .append(" threat");
            }
        }

        return report.toString();
    }

    public static String formatTracking(
            IncursionSourceInspectionContext context
    ) {
        requireContext(
                context
        );

        AssignmentTrackingSummary trackingSummary =
                createAssignmentTrackingSummary(
                        context
                );

        StringBuilder report =
                new StringBuilder();

        report.append("Incursion Source Mob Tracking")
                .append("\nIncursion ID: ")
                .append(context.incursionId())
                .append("\nWave: ")
                .append(context.waveIndex() + 1)
                .append("\nSource Composition ID: ")
                .append(context.sourceCompositionId());

        report.append("\n\nConsistency")
                .append("\nTracked Records: ")
                .append(trackingSummary.trackedMobCount())
                .append("\nSuccessful Spawns: ")
                .append(
                        context
                                .sourceWaveSnapshot()
                                .successfulSpawnCount()
                )
                .append("\nCount Check: ")
                .append(
                        trackingSummary.trackingCountValid()
                                ? "valid"
                                : "MISMATCH"
                )
                .append("\nThreat Check: ")
                .append(
                        trackingSummary.threatAccountingValid()
                                ? "valid"
                                : "MISMATCH"
                );

        report.append("\n\nThreat Accounting")
                .append("\nDelivered: ")
                .append(trackingSummary.deliveredThreat())
                .append("\nPending: ")
                .append(trackingSummary.pendingThreat())
                .append("\nCancelled: ")
                .append(trackingSummary.cancelledThreat())
                .append("\nPlanned: ")
                .append(trackingSummary.plannedThreat());

        report.append("\n\nLifecycle")
                .append("\nActive: ")
                .append(trackingSummary.activeMobCount())
                .append(" mobs / ")
                .append(trackingSummary.activeThreat())
                .append(" threat")
                .append("\nDefeated: ")
                .append(trackingSummary.defeatedMobCount())
                .append(" mobs / ")
                .append(trackingSummary.defeatedThreat())
                .append(" threat")
                .append("\nOther Terminal: ")
                .append(trackingSummary.otherTerminalMobCount())
                .append(" mobs / ")
                .append(trackingSummary.otherTerminalThreat())
                .append(" threat");

        report.append("\n\nTracked Entities:");

        if (trackingSummary.trackedMobs().isEmpty()) {
            report.append("\n  none");
        } else {
            int recordNumber =
                    1;

            for (IncursionMobTrackingState.TrackedMobSnapshot trackedMob
                    : trackingSummary.trackedMobs()) {

                report.append("\n  ")
                        .append(recordNumber)
                        .append(". ")
                        .append(trackedMob.mobId())
                        .append(" — ")
                        .append(trackedMob.representedThreat())
                        .append(" threat — ")
                        .append(trackedMob.resolution())
                        .append("\n     Entity: ")
                        .append(trackedMob.entityId())
                        .append("\n     Runtime Source: ")
                        .append(trackedMob.runtimeSourceId())
                        .append("\n     Attached Assignment: ")
                        .append(
                                formatOptionalUuid(
                                        trackedMob
                                                .attachedMobAssignmentId()
                                )
                        );

                recordNumber++;
            }
        }

        return report.toString();
    }

    public static String formatAll(
            IncursionSourceInspectionContext context
    ) {
        requireContext(
                context
        );

        return wrapReport(
                InspectionView.OVERVIEW,
                formatOverview(
                        context
                )
        )
                + "\n\n"
                + wrapReport(
                InspectionView.PLACEMENT,
                formatPlacement(
                        context
                )
        )
                + "\n\n"
                + wrapReport(
                InspectionView.COMPOSITION,
                formatComposition(
                        context
                )
        )
                + "\n\n"
                + wrapReport(
                InspectionView.QUEUE,
                formatQueue(
                        context
                )
        )
                + "\n\n"
                + wrapReport(
                InspectionView.TRACKING,
                formatTracking(
                        context
                )
        );
    }

    /**
     * Gives every command result an unmistakable boundary in Minecraft chat
     * and copied log output.
     */
    private static String wrapReport(
            InspectionView inspectionView,
            String report
    ) {
        if (inspectionView == null) {
            throw new IllegalArgumentException(
                    "Wrapped inspection report requires a view."
            );
        }

        if (report == null
                || report.isBlank()) {

            throw new IllegalArgumentException(
                    "Wrapped inspection report cannot be blank."
            );
        }

        String heading =
                inspectionView
                        .commandName()
                        .toUpperCase(
                                Locale.ROOT
                        );

        return "========== "
                + heading
                + " ==========\n"
                + report
                + "\n========== END "
                + heading
                + " ==========";
    }

    private static AssignmentTrackingSummary
    createAssignmentTrackingSummary(
            IncursionSourceInspectionContext context
    ) {
        UUID sourceGroupCompositionId =
                context.sourceGroupCompositionId();

        UUID sourceCompositionId =
                context.sourceCompositionId();

        UUID sourcePlacementId =
                context.sourcePlacementId();

        List<IncursionMobTrackingState.TrackedMobSnapshot>
                matchingTrackedMobs =
                new ArrayList<>();

        int deliveredThreat =
                0;

        int activeMobCount =
                0;

        int activeThreat =
                0;

        int defeatedMobCount =
                0;

        int defeatedThreat =
                0;

        int otherTerminalMobCount =
                0;

        int otherTerminalThreat =
                0;

        for (IncursionMobTrackingState.TrackedMobSnapshot trackedMob
                : context
                .mobTrackingSnapshot()
                .trackedMobs()) {

            if (trackedMob.waveIndex()
                    != context.waveIndex()) {

                continue;
            }

            if (!sourceGroupCompositionId.equals(
                    trackedMob.sourceGroupCompositionId()
            )) {
                continue;
            }

            if (!sourceCompositionId.equals(
                    trackedMob.sourceCompositionId()
            )) {
                continue;
            }

            if (!sourcePlacementId.equals(
                    trackedMob.sourcePlacementId()
            )) {
                continue;
            }

            matchingTrackedMobs.add(
                    trackedMob
            );

            deliveredThreat =
                    Math.addExact(
                            deliveredThreat,
                            trackedMob.representedThreat()
                    );

            switch (trackedMob.resolution()) {
                case ACTIVE -> {
                    activeMobCount =
                            Math.addExact(
                                    activeMobCount,
                                    1
                            );

                    activeThreat =
                            Math.addExact(
                                    activeThreat,
                                    trackedMob.representedThreat()
                            );
                }

                case DEFEATED -> {
                    defeatedMobCount =
                            Math.addExact(
                                    defeatedMobCount,
                                    1
                            );

                    defeatedThreat =
                            Math.addExact(
                                    defeatedThreat,
                                    trackedMob.representedThreat()
                            );
                }

                case OTHER_TERMINAL_REMOVAL -> {
                    otherTerminalMobCount =
                            Math.addExact(
                                    otherTerminalMobCount,
                                    1
                            );

                    otherTerminalThreat =
                            Math.addExact(
                                    otherTerminalThreat,
                                    trackedMob.representedThreat()
                            );
                }
            }
        }

        int plannedThreat =
                context
                        .sourceComposition()
                        .getThreatSpent();

        int pendingThreat =
                context
                        .sourceWaveSnapshot()
                        .spawnQueueSnapshot()
                        .remainingThreat();

        int cancelledThreat =
                plannedThreat
                        - deliveredThreat
                        - pendingThreat;

        boolean trackingCountValid =
                matchingTrackedMobs.size()
                        == context
                        .sourceWaveSnapshot()
                        .successfulSpawnCount();

        boolean threatAccountingValid =
                cancelledThreat >= 0
                        && plannedThreat
                        == deliveredThreat
                        + pendingThreat
                        + cancelledThreat;

        return new AssignmentTrackingSummary(
                matchingTrackedMobs,
                deliveredThreat,
                pendingThreat,
                cancelledThreat,
                plannedThreat,
                activeMobCount,
                activeThreat,
                defeatedMobCount,
                defeatedThreat,
                otherTerminalMobCount,
                otherTerminalThreat,
                trackingCountValid,
                threatAccountingValid
        );
    }

    private static void appendRuntimeSourceHistory(
            StringBuilder report,
            List<UUID> runtimeSourceIdHistory,
            UUID currentRuntimeSourceId
    ) {
        report.append("\nRuntime Source History:");

        if (runtimeSourceIdHistory.isEmpty()) {
            report.append("\n  none");

            return;
        }

        for (int sourceIndex = 0;
             sourceIndex < runtimeSourceIdHistory.size();
             sourceIndex++) {

            UUID historicalSourceId =
                    runtimeSourceIdHistory.get(
                            sourceIndex
                    );

            report.append("\n  ")
                    .append(sourceIndex + 1)
                    .append(". ")
                    .append(historicalSourceId);

            if (historicalSourceId.equals(
                    currentRuntimeSourceId
            )) {
                report.append(" — current");
            }
        }
    }

    private static void appendAttachedAssignments(
            StringBuilder report,
            IncursionSourceInspectionContext context
    ) {
        List<SourceGroupComposition.AttachedMobAssignment>
                assignments =
                context
                        .sourceComposition()
                        .getAttachedMobAssignments();

        report.append("\n\nAttached Assignments:");

        if (assignments.isEmpty()) {
            report.append("\n  none");

            return;
        }

        for (SourceGroupComposition.AttachedMobAssignment assignment
                : assignments) {

            UUID boundEntityId =
                    findBoundEntityId(
                            context.sourceWaveSnapshot(),
                            assignment.attachedMobAssignmentId()
                    );

            report.append("\n  ")
                    .append(assignment.mobId())
                    .append(" — ")
                    .append(assignment.complexityOption().id())
                    .append(" — ")
                    .append(assignment.spawnPriority())
                    .append("\n     Assignment ID: ")
                    .append(assignment.attachedMobAssignmentId())
                    .append("\n     Bound Entity: ")
                    .append(
                            formatOptionalUuid(
                                    boundEntityId
                            )
                    );
        }
    }

    private static UUID findBoundEntityId(
            SourceWaveExecutionState.Snapshot sourceWaveSnapshot,
            UUID attachedMobAssignmentId
    ) {
        for (var bindingSnapshot
                : sourceWaveSnapshot
                .attachedMobEntityBindingSnapshot()
                .bindings()) {

            if (attachedMobAssignmentId.equals(
                    bindingSnapshot.attachedMobAssignmentId()
            )) {
                return bindingSnapshot.entityId();
            }
        }

        return null;
    }

    private static void appendPackAssignment(
            StringBuilder report,
            SourceGroupComposition sourceGroupComposition,
            SourceGroupComposition.SourceComposition sourceComposition
    ) {
        SourceGroupComposition.PackAssignment packAssignment =
                sourceGroupComposition.getPackAssignment();

        report.append("\n\nPack Assignment:");

        if (packAssignment == null) {
            report.append("\n  none");

            return;
        }

        report.append("\n  Pack ID: ")
                .append(packAssignment.packId())
                .append("\n  Leader Source Composition: ")
                .append(packAssignment.sourceCompositionId())
                .append("\n  Leader Attached Assignment: ")
                .append(packAssignment.attachedMobAssignmentId())
                .append("\n  Applies To Selected Composition: ")
                .append(
                        sourceComposition
                                .getSourceCompositionId()
                                .equals(
                                        packAssignment
                                                .sourceCompositionId()
                                )
                );
    }

    private static void appendEnumSet(
            StringBuilder report,
            String heading,
            Iterable<?> values
    ) {
        report.append("\n\n")
                .append(heading)
                .append(":");

        boolean foundValue =
                false;

        for (Object value
                : values) {

            foundValue =
                    true;

            report.append("\n  ")
                    .append(value);
        }

        if (!foundValue) {
            report.append("\n  none");
        }
    }

    private static void appendWaveTiming(
            StringBuilder report,
            IncursionSourceInspectionContext context
    ) {
        report.append("\n\nWave Timing:");

        if (context.assignmentRelation()
                != IncursionSourceInspectionContext
                .AssignmentRelation
                .CURRENT) {

            report.append("\n  ");

            switch (context.assignmentRelation()) {
                case UPCOMING ->
                        report.append(
                                "wave has not started"
                        );

                case PREVIOUS ->
                        report.append(
                                "wave assignment has completed"
                        );

                case CURRENT ->
                        throw new IllegalStateException(
                                "Current assignment unexpectedly entered "
                                        + "non-current timing branch."
                        );
            }

            return;
        }

        WaveExecutionState.Snapshot currentWaveSnapshot =
                context
                        .controllerSnapshot()
                        .currentWaveExecutionSnapshot();

        if (currentWaveSnapshot == null
                || currentWaveSnapshot.waveIndex()
                != context.waveIndex()) {

            report.append("\n  current wave timing unavailable");

            return;
        }

        report.append("\n  Elapsed: ")
                .append(
                        formatTicks(
                                currentWaveSnapshot.elapsedTicks()
                        )
                );

        if (currentWaveSnapshot.spawnScheduleComplete()) {
            report.append("\n  Spawn Schedule: complete");

            return;
        }

        int ticksUntilNextSpawn =
                Math.max(
                        0,
                        currentWaveSnapshot.nextSpawnTick()
                                - currentWaveSnapshot.elapsedTicks()
                );

        report.append("\n  Next Spawn Eligible In: ")
                .append(
                        formatTicks(
                                ticksUntilNextSpawn
                        )
                );
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
            org.ratden.skavenblight.block.entity.state.SourceState
                    sourceState
    ) {
        return sourceState == null
                ? "none"
                : sourceState.getSerializedName();
    }

    private static String formatOptionalUuid(
            UUID value
    ) {
        return value == null
                ? "none"
                : value.toString();
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

    private static void requireContext(
            IncursionSourceInspectionContext context
    ) {
        if (context == null) {
            throw new IllegalArgumentException(
                    "Runtime source-inspection context cannot be null."
            );
        }
    }

    /**
     * Focused report selected by the debug command.
     */
    public enum InspectionView {
        OVERVIEW("overview"),
        PLACEMENT("placement"),
        COMPOSITION("composition"),
        QUEUE("queue"),
        TRACKING("tracking"),
        ALL("all");

        private final String commandName;

        InspectionView(
                String commandName
        ) {
            this.commandName =
                    commandName;
        }

        public String commandName() {
            return commandName;
        }

        public static InspectionView fromCommandName(
                String commandName
        ) {
            if (commandName == null
                    || commandName.isBlank()) {

                throw new IllegalArgumentException(
                        "Inspection view name cannot be blank."
                );
            }

            for (InspectionView inspectionView
                    : values()) {

                if (inspectionView.commandName.equals(
                        commandName
                )) {
                    return inspectionView;
                }
            }

            throw new IllegalArgumentException(
                    "Unknown incursion inspection view '"
                            + commandName
                            + "'."
            );
        }
    }

    private record AssignmentTrackingSummary(
            List<IncursionMobTrackingState.TrackedMobSnapshot> trackedMobs,
            int deliveredThreat,
            int pendingThreat,
            int cancelledThreat,
            int plannedThreat,
            int activeMobCount,
            int activeThreat,
            int defeatedMobCount,
            int defeatedThreat,
            int otherTerminalMobCount,
            int otherTerminalThreat,
            boolean trackingCountValid,
            boolean threatAccountingValid
    ) {

        private AssignmentTrackingSummary {
            if (trackedMobs == null) {
                throw new IllegalArgumentException(
                        "Assignment tracking summary mob records cannot be "
                                + "null."
                );
            }

            trackedMobs =
                    List.copyOf(
                            trackedMobs
                    );

            if (deliveredThreat < 0
                    || pendingThreat < 0
                    || plannedThreat <= 0
                    || activeMobCount < 0
                    || activeThreat < 0
                    || defeatedMobCount < 0
                    || defeatedThreat < 0
                    || otherTerminalMobCount < 0
                    || otherTerminalThreat < 0) {

                throw new IllegalArgumentException(
                        "Assignment tracking summary contains invalid "
                                + "negative values."
                );
            }

            int lifecycleMobCount =
                    Math.addExact(
                            activeMobCount,
                            Math.addExact(
                                    defeatedMobCount,
                                    otherTerminalMobCount
                            )
                    );

            if (lifecycleMobCount
                    != trackedMobs.size()) {

                throw new IllegalArgumentException(
                        "Assignment lifecycle categories contain "
                                + lifecycleMobCount
                                + " mobs, but summary contains "
                                + trackedMobs.size()
                                + " tracked records."
                );
            }

            int lifecycleThreat =
                    Math.addExact(
                            activeThreat,
                            Math.addExact(
                                    defeatedThreat,
                                    otherTerminalThreat
                            )
                    );

            if (lifecycleThreat
                    != deliveredThreat) {

                throw new IllegalArgumentException(
                        "Assignment lifecycle categories contain "
                                + lifecycleThreat
                                + " threat, but summary contains "
                                + deliveredThreat
                                + " delivered threat."
                );
            }
        }

        private int trackedMobCount() {
            return trackedMobs.size();
        }
    }

    private IncursionRuntimeDebugFormatter() {
    }
}