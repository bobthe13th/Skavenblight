package org.ratden.skavenblight.event.skavenIncursion.runtime;

import org.ratden.skavenblight.event.skavenIncursion.planning.IncursionPlan;
import org.ratden.skavenblight.event.skavenIncursion.planning.composition.SourceGroupComposition;
import org.ratden.skavenblight.event.skavenIncursion.planning.front.FrontPlan;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceGroupPlacementPlan;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourcePlacementPlan;
import org.ratden.skavenblight.event.skavenIncursion.runtime.source.SourceExecutionState;
import org.ratden.skavenblight.event.skavenIncursion.runtime.source.SourceWaveExecutionState;
import net.minecraft.world.entity.Entity;
import org.ratden.skavenblight.event.skavenIncursion.leadership.IncursionOwnedMob;
import org.ratden.skavenblight.event.skavenIncursion.runtime.entity.AttachedMobAssignmentEntity;
import org.ratden.skavenblight.event.skavenIncursion.runtime.entity.AttachedMobEntityReconnectionResult;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Mutable runtime state derived from one completed IncursionPlan.
 *
 * The IncursionPlan remains the immutable authority for:
 *
 * - physical source placements;
 * - source-composition identities;
 * - wave membership;
 * - source-to-composition bindings;
 * - canonical runtime assignment order.
 *
 * This class owns the shared runtime object graph:
 *
 * - one persistent SourceExecutionState for each physical source placement;
 * - one SourceWaveExecutionState for each source composition assigned during
 *   each wave.
 *
 * SourceExecutionState objects are shared across wave assignments. When one
 * physical source is reused by several waves, all of those assignments point
 * to the same persistent runtime state and therefore share:
 *
 * - physical incarnation history;
 * - current runtime source ID;
 * - destruction history;
 * - current SourceState.
 *
 * The complete mutable graph can be captured in an immutable Snapshot and
 * restored against the same immutable IncursionPlan.
 *
 * Restoration is intentionally strict:
 *
 * - the incursion ID must match;
 * - every planned physical source requires exactly one source snapshot;
 * - no unplanned physical source may appear;
 * - every planned source composition requires exactly one wave assignment;
 * - wave indexes and assignment ordering must match the plan;
 * - saved source-placement bindings must match the plan;
 * - shared physical sources are reconstructed before wave assignments.
 *
 * This class does not tick waves, create sources, spawn mobs, reconcile world
 * blocks, or determine wave-transition timing.
 */
public class IncursionExecutionState {

    private final IncursionPlan incursionPlan;

    private final Map<UUID, SourceExecutionState>
            sourceExecutionStatesByPlacementId;

    private final Map<Integer, List<SourceWaveExecutionState>>
            waveSourceStatesByIndex;

    private final List<Integer> waveIndexes;

    /**
     * Creates fresh runtime state from a completed immutable plan.
     */

    public IncursionExecutionState(
            IncursionPlan incursionPlan
    ) {
        this(
                incursionPlan,
                createFreshRuntimeGraph(
                        incursionPlan
                )
        );
    }

    /**
     * Receives the two halves of one freshly constructed shared runtime graph.
     *
     * This intermediate constructor allows the public constructor to delegate
     * while ensuring that wave assignments and the physical-source map use the
     * same SourceExecutionState objects.
     */
    private IncursionExecutionState(
            IncursionPlan incursionPlan,
            FreshRuntimeGraph freshRuntimeGraph
    ) {
        this(
                incursionPlan,
                freshRuntimeGraph
                        .sourceExecutionStates(),
                freshRuntimeGraph
                        .waveSourceStates()
        );
    }

    /**
     * Canonical constructor used by both fresh construction and restoration.
     *
     * Blank final fields must be assigned directly inside a constructor rather
     * than through an ordinary initialisation method.
     */
    private IncursionExecutionState(
            IncursionPlan incursionPlan,
            LinkedHashMap<UUID, SourceExecutionState>
                    sourceExecutionStates,
            TreeMap<Integer, List<SourceWaveExecutionState>>
                    waveSourceStates
    ) {
        validateCompletedRuntimeGraph(
                incursionPlan,
                sourceExecutionStates,
                waveSourceStates
        );

        this.incursionPlan =
                incursionPlan;

        this.sourceExecutionStatesByPlacementId =
                Collections.unmodifiableMap(
                        new LinkedHashMap<>(
                                sourceExecutionStates
                        )
                );

        LinkedHashMap<Integer, List<SourceWaveExecutionState>>
                immutableWaveStates =
                new LinkedHashMap<>();

        for (Map.Entry<Integer, List<SourceWaveExecutionState>>
                entry
                : waveSourceStates.entrySet()) {

            immutableWaveStates.put(
                    entry.getKey(),
                    List.copyOf(
                            entry.getValue()
                    )
            );
        }

        this.waveSourceStatesByIndex =
                Collections.unmodifiableMap(
                        immutableWaveStates
                );

        this.waveIndexes =
                List.copyOf(
                        waveSourceStates.keySet()
                );
    }


    /**
     * Restores the complete shared runtime graph against the supplied
     * immutable IncursionPlan.
     *
     * Physical source states are restored first. Wave assignments are then
     * reconstructed using those same restored objects.
     */
    public static IncursionExecutionState restore(
            IncursionPlan incursionPlan,
            Snapshot snapshot
    ) {
        if (snapshot == null) {
            throw new IllegalArgumentException(
                    "Incursion execution snapshot cannot be null."
            );
        }

        PlanRuntimeIndex planRuntimeIndex =
                createPlanRuntimeIndex(
                        incursionPlan
                );

        UUID plannedIncursionId =
                incursionPlan.getIncursionId();

        if (!plannedIncursionId.equals(
                snapshot.incursionId()
        )) {
            throw new IllegalArgumentException(
                    "Incursion execution snapshot belongs to incursion "
                            + snapshot.incursionId()
                            + " but supplied plan belongs to "
                            + plannedIncursionId
                            + "."
            );
        }

        LinkedHashMap<UUID, SourceExecutionState>
                restoredSourceStates =
                restoreSourceExecutionStates(
                        planRuntimeIndex,
                        snapshot
                );

        TreeMap<Integer, List<SourceWaveExecutionState>>
                restoredWaveStates =
                restoreWaveSourceStates(
                        planRuntimeIndex,
                        restoredSourceStates,
                        snapshot
                );

        IncursionExecutionState restoredExecutionState =
                new IncursionExecutionState(
                        incursionPlan,
                        restoredSourceStates,
                        restoredWaveStates
                );

        /*
         * Confirm that reconstruction produces the same canonical snapshot.
         *
         * The saved snapshot must already use the plan's canonical source and
         * wave-assignment order. This protects deterministic tick ordering.
         */
        Snapshot reconstructedSnapshot =
                restoredExecutionState.createSnapshot();

        if (!reconstructedSnapshot.equals(
                snapshot
        )) {
            throw new IllegalArgumentException(
                    "Restored incursion execution state does not exactly "
                            + "match its saved snapshot for incursion "
                            + snapshot.incursionId()
                            + "."
            );
        }

        return restoredExecutionState;
    }


    public IncursionPlan getIncursionPlan() {
        return incursionPlan;
    }

    public UUID getIncursionId() {
        return incursionPlan.getIncursionId();
    }

    public List<Integer> getWaveIndexes() {
        return waveIndexes;
    }

    public int getWaveCount() {
        return waveIndexes.size();
    }

    public int getFirstWaveIndex() {
        return waveIndexes.getFirst();
    }

    public int getLastWaveIndex() {
        return waveIndexes.getLast();
    }

    public boolean hasWave(
            int waveIndex
    ) {
        return waveSourceStatesByIndex.containsKey(
                waveIndex
        );
    }

    /**
     * Returns every source assignment belonging to the supplied global wave
     * index across all fronts.
     *
     * The returned order is the canonical order established by the immutable
     * plan and used by WaveExecutionState during ticking.
     */
    public List<SourceWaveExecutionState> getWaveSourceStates(
            int waveIndex
    ) {
        return waveSourceStatesByIndex.getOrDefault(
                waveIndex,
                List.of()
        );
    }

    public SourceExecutionState getSourceExecutionState(
            UUID sourcePlacementId
    ) {
        if (sourcePlacementId == null) {
            return null;
        }

        return sourceExecutionStatesByPlacementId.get(
                sourcePlacementId
        );
    }

    public SourceWaveExecutionState getSourceWaveExecutionState(
            UUID sourceCompositionId
    ) {
        if (sourceCompositionId == null) {
            return null;
        }

        for (int waveIndex
                : waveIndexes) {

            for (SourceWaveExecutionState sourceWaveState
                    : getWaveSourceStates(
                    waveIndex
            )) {

                if (sourceCompositionId.equals(
                        sourceWaveState
                                .getSourceCompositionId()
                )) {
                    return sourceWaveState;
                }
            }
        }

        return null;
    }

    /**
     * Routes one naturally loaded attached entity to the unique source-wave
     * assignment that owns its immutable assignment ID.
     *
     * This performs a bounded traversal of this one incursion's planned
     * source-wave assignments only when a relevant entity loads. It is not a
     * recurring scan.
     */
    public AttachedMobEntityReconnectionResult
    reconnectLoadedAttachedMobEntity(
            Entity entity
    ) {
        if (entity == null) {
            throw new IllegalArgumentException(
                    "Loaded attached-mob entity cannot be null."
            );
        }

        UUID entityId =
                entity.getUUID();

        if (!(entity
                instanceof AttachedMobAssignmentEntity
                attachedAssignmentEntity)) {

            throw new IllegalArgumentException(
                    "Loaded entity does not implement "
                            + "AttachedMobAssignmentEntity."
            );
        }

        UUID attachedMobAssignmentId =
                attachedAssignmentEntity
                        .getAttachedMobAssignmentId();

        if (attachedMobAssignmentId == null) {
            throw new IllegalArgumentException(
                    "Loaded entity has no attached-mob assignment ID."
            );
        }

        if (!(entity
                instanceof IncursionOwnedMob incursionOwnedMob)) {

            return AttachedMobEntityReconnectionResult.rejected(
                    attachedMobAssignmentId,
                    entityId,
                    "Loaded attached entity does not implement "
                            + "IncursionOwnedMob."
            );
        }

        if (!getIncursionId().equals(
                incursionOwnedMob.getScenarioId()
        )) {
            return AttachedMobEntityReconnectionResult.rejected(
                    attachedMobAssignmentId,
                    entityId,
                    "Entity identifies incursion "
                            + incursionOwnedMob.getScenarioId()
                            + " rather than owning incursion "
                            + getIncursionId()
                            + "."
            );
        }

        SourceWaveExecutionState matchingSourceWaveState =
                null;

        for (int waveIndex
                : waveIndexes) {

            for (SourceWaveExecutionState sourceWaveExecutionState
                    : getWaveSourceStates(
                    waveIndex
            )) {

                if (!sourceWaveExecutionState
                        .getAttachedMobEntityBindingState()
                        .containsAssignment(
                                attachedMobAssignmentId
                        )) {

                    continue;
                }

                if (matchingSourceWaveState != null) {
                    return AttachedMobEntityReconnectionResult.rejected(
                            attachedMobAssignmentId,
                            entityId,
                            "Attached assignment appears in more than one "
                                    + "source-wave runtime state."
                    );
                }

                matchingSourceWaveState =
                        sourceWaveExecutionState;
            }
        }

        if (matchingSourceWaveState == null) {
            return AttachedMobEntityReconnectionResult.rejected(
                    attachedMobAssignmentId,
                    entityId,
                    "Incursion plan contains no source-wave assignment "
                            + "matching the entity's attached assignment ID."
            );
        }

        return matchingSourceWaveState
                .reconnectLoadedAttachedMobEntity(
                        entity
                );
    }

    public Collection<SourceExecutionState>
    getSourceExecutionStates() {
        return Collections.unmodifiableCollection(
                sourceExecutionStatesByPlacementId.values()
        );
    }

    public int getPhysicalSourceCount() {
        return sourceExecutionStatesByPlacementId.size();
    }

    public int getSourceWaveAssignmentCount() {
        int assignmentCount =
                0;

        for (List<SourceWaveExecutionState> waveSourceStates
                : waveSourceStatesByIndex.values()) {

            assignmentCount +=
                    waveSourceStates.size();
        }

        return assignmentCount;
    }


    /**
     * Captures the complete mutable runtime graph.
     *
     * Snapshot ordering is canonical:
     *
     * - physical sources follow physical placement order in the plan;
     * - waves follow ascending wave index;
     * - assignments within each wave follow plan traversal order.
     */
    public Snapshot createSnapshot() {
        List<SourceExecutionState.Snapshot>
                sourceExecutionSnapshots =
                new ArrayList<>();

        for (SourceExecutionState sourceExecutionState
                : sourceExecutionStatesByPlacementId.values()) {

            sourceExecutionSnapshots.add(
                    sourceExecutionState.createSnapshot()
            );
        }

        List<WaveSourceAssignmentsSnapshot>
                waveAssignmentSnapshots =
                new ArrayList<>();

        for (int waveIndex
                : waveIndexes) {

            List<SourceWaveExecutionState.Snapshot>
                    sourceWaveSnapshots =
                    new ArrayList<>();

            for (SourceWaveExecutionState sourceWaveState
                    : getWaveSourceStates(
                    waveIndex
            )) {

                sourceWaveSnapshots.add(
                        sourceWaveState.createSnapshot()
                );
            }

            waveAssignmentSnapshots.add(
                    new WaveSourceAssignmentsSnapshot(
                            waveIndex,
                            sourceWaveSnapshots
                    )
            );
        }

        return new Snapshot(
                getIncursionId(),
                sourceExecutionSnapshots,
                waveAssignmentSnapshots
        );
    }

    // =========================================================
    // Immutable plan indexing
    // =========================================================

    /**
     * Builds one canonical index of every physical source placement and every
     * source-composition assignment in the immutable plan.
     *
     * Both fresh construction and restoration use this same index so they
     * cannot accidentally establish different ordering or binding rules.
     */
    private static PlanRuntimeIndex createPlanRuntimeIndex(
            IncursionPlan incursionPlan
    ) {
        validateIncursionPlan(
                incursionPlan
        );

        LinkedHashMap<UUID, SourcePlacementPlan>
                sourcePlacementPlans =
                new LinkedHashMap<>();

        TreeMap<Integer, List<PlannedSourceAssignment>>
                plannedAssignmentsByWave =
                new TreeMap<>();

        /*
         * First index every persistent physical source in structural plan
         * order.
         */
        for (FrontPlan frontPlan
                : incursionPlan.getFrontPlans()) {

            if (frontPlan == null) {
                throw new IllegalArgumentException(
                        "Incursion plan "
                                + incursionPlan.getIncursionId()
                                + " contains a null front."
                );
            }

            if (!frontPlan.hasSourceGroupPlacementPlans()) {
                throw new IllegalArgumentException(
                        "Front "
                                + frontPlan.getFrontId()
                                + " contains no physical source groups."
                );
            }

            for (SourceGroupPlacementPlan groupPlacementPlan
                    : frontPlan
                    .getSourceGroupPlacementPlans()) {

                if (groupPlacementPlan == null) {
                    throw new IllegalArgumentException(
                            "Front "
                                    + frontPlan.getFrontId()
                                    + " contains a null source-group "
                                    + "placement."
                    );
                }

                if (groupPlacementPlan.isEmpty()) {
                    throw new IllegalArgumentException(
                            "Source-group placement "
                                    + groupPlacementPlan
                                    .getSourceGroupPlacementId()
                                    + " contains no physical sources."
                    );
                }

                for (SourcePlacementPlan sourcePlacementPlan
                        : groupPlacementPlan
                        .getSourcePlacementPlans()) {

                    if (sourcePlacementPlan == null) {
                        throw new IllegalArgumentException(
                                "Source-group placement "
                                        + groupPlacementPlan
                                        .getSourceGroupPlacementId()
                                        + " contains a null source "
                                        + "placement."
                        );
                    }

                    UUID sourcePlacementId =
                            sourcePlacementPlan
                                    .getSourcePlacementId();

                    if (sourcePlacementId == null) {
                        throw new IllegalArgumentException(
                                "Incursion plan contains a physical source "
                                        + "placement with no ID."
                        );
                    }

                    if (!sourcePlacementPlan.hasPlacedPos()) {
                        throw new IllegalArgumentException(
                                "Source placement "
                                        + sourcePlacementId
                                        + " has no final placed position."
                        );
                    }

                    SourcePlacementPlan previousPlacement =
                            sourcePlacementPlans.putIfAbsent(
                                    sourcePlacementId,
                                    sourcePlacementPlan
                            );

                    if (previousPlacement != null) {
                        throw new IllegalArgumentException(
                                "Incursion plan contains duplicate source "
                                        + "placement ID "
                                        + sourcePlacementId
                                        + "."
                        );
                    }
                }
            }
        }

        if (sourcePlacementPlans.isEmpty()) {
            throw new IllegalArgumentException(
                    "Runtime execution requires at least one physical source "
                            + "placement."
            );
        }

        /*
         * Then establish the canonical source-composition order for every
         * global wave.
         *
         * Traversal order intentionally matches the original runtime:
         *
         * front → wave → source group → source composition.
         */
        Set<UUID> assignedSourceCompositionIds =
                new HashSet<>();

        for (FrontPlan frontPlan
                : incursionPlan.getFrontPlans()) {

            if (!frontPlan.hasWavePlans()) {
                throw new IllegalArgumentException(
                        "Front "
                                + frontPlan.getFrontId()
                                + " contains no wave plans."
                );
            }

            for (FrontPlan.WavePlan wavePlan
                    : frontPlan.getWavePlans()) {

                if (wavePlan == null) {
                    throw new IllegalArgumentException(
                            "Front "
                                    + frontPlan.getFrontId()
                                    + " contains a null wave plan."
                    );
                }

                if (!wavePlan.hasSourceGroupCompositions()) {
                    throw new IllegalArgumentException(
                            "Wave "
                                    + wavePlan.getWaveIndex()
                                    + " in front "
                                    + frontPlan.getFrontId()
                                    + " contains no source-group "
                                    + "compositions."
                    );
                }

                List<PlannedSourceAssignment>
                        assignmentsForWave =
                        plannedAssignmentsByWave
                                .computeIfAbsent(
                                        wavePlan.getWaveIndex(),
                                        ignored ->
                                                new ArrayList<>()
                                );

                for (SourceGroupComposition groupComposition
                        : wavePlan
                        .getSourceGroupCompositions()) {

                    if (groupComposition == null) {
                        throw new IllegalArgumentException(
                                "Wave "
                                        + wavePlan.getWaveIndex()
                                        + " in front "
                                        + frontPlan.getFrontId()
                                        + " contains a null source-group "
                                        + "composition."
                        );
                    }

                    if (groupComposition.isEmpty()) {
                        throw new IllegalArgumentException(
                                "Source-group composition "
                                        + groupComposition
                                        .getSourceGroupCompositionId()
                                        + " contains no source "
                                        + "compositions."
                        );
                    }

                    for (SourceGroupComposition.SourceComposition
                            sourceComposition
                            : groupComposition
                            .getSourceCompositions()) {

                        if (sourceComposition == null) {
                            throw new IllegalArgumentException(
                                    "Source-group composition "
                                            + groupComposition
                                            .getSourceGroupCompositionId()
                                            + " contains a null source "
                                            + "composition."
                            );
                        }

                        UUID sourceCompositionId =
                                sourceComposition
                                        .getSourceCompositionId();

                        if (sourceCompositionId == null) {
                            throw new IllegalArgumentException(
                                    "Incursion plan contains a source "
                                            + "composition with no ID."
                            );
                        }

                        if (!assignedSourceCompositionIds.add(
                                sourceCompositionId
                        )) {
                            throw new IllegalArgumentException(
                                    "Source composition "
                                            + sourceCompositionId
                                            + " has more than one runtime "
                                            + "wave assignment."
                            );
                        }

                        SourcePlacementPlan sourcePlacementPlan =
                                findBoundSourcePlacement(
                                        frontPlan,
                                        sourceCompositionId
                                );

                        if (!sourcePlacementPlans.containsKey(
                                sourcePlacementPlan
                                        .getSourcePlacementId()
                        )) {
                            throw new IllegalArgumentException(
                                    "Source composition "
                                            + sourceCompositionId
                                            + " resolved to unindexed source "
                                            + "placement "
                                            + sourcePlacementPlan
                                            .getSourcePlacementId()
                                            + "."
                            );
                        }

                        assignmentsForWave.add(
                                new PlannedSourceAssignment(
                                        wavePlan.getWaveIndex(),
                                        frontPlan.getFrontId(),
                                        groupComposition,
                                        sourcePlacementPlan,
                                        sourceComposition
                                )
                        );
                    }
                }
            }
        }

        if (plannedAssignmentsByWave.isEmpty()) {
            throw new IllegalArgumentException(
                    "Runtime execution requires an incursion plan "
                            + "containing at least one wave."
            );
        }

        /*
         * Every persistent physical source must be bound to at least one
         * actual planned source composition.
         */
        Set<UUID> assignedSourcePlacementIds =
                new HashSet<>();

        for (List<PlannedSourceAssignment> assignments
                : plannedAssignmentsByWave.values()) {

            for (PlannedSourceAssignment assignment
                    : assignments) {

                assignedSourcePlacementIds.add(
                        assignment
                                .sourcePlacementPlan()
                                .getSourcePlacementId()
                );
            }
        }

        for (UUID sourcePlacementId
                : sourcePlacementPlans.keySet()) {

            if (!assignedSourcePlacementIds.contains(
                    sourcePlacementId
            )) {
                throw new IllegalArgumentException(
                        "Physical source placement "
                                + sourcePlacementId
                                + " has no runtime source-composition "
                                + "assignment."
                );
            }
        }

        LinkedHashMap<Integer, List<PlannedSourceAssignment>>
                immutableAssignmentMap =
                new LinkedHashMap<>();

        for (Map.Entry<Integer, List<PlannedSourceAssignment>>
                entry
                : plannedAssignmentsByWave.entrySet()) {

            if (entry.getValue().isEmpty()) {
                throw new IllegalArgumentException(
                        "Wave "
                                + entry.getKey()
                                + " contains no runtime source assignments."
                );
            }

            immutableAssignmentMap.put(
                    entry.getKey(),
                    List.copyOf(
                            entry.getValue()
                    )
            );
        }

        return new PlanRuntimeIndex(
                Collections.unmodifiableMap(
                        new LinkedHashMap<>(
                                sourcePlacementPlans
                        )
                ),
                Collections.unmodifiableMap(
                        immutableAssignmentMap
                ),
                List.copyOf(
                        plannedAssignmentsByWave.keySet()
                )
        );
    }

    private static void validateIncursionPlan(
            IncursionPlan incursionPlan
    ) {
        if (incursionPlan == null) {
            throw new IllegalArgumentException(
                    "Incursion plan cannot be null."
            );
        }

        if (incursionPlan.getIncursionId() == null) {
            throw new IllegalArgumentException(
                    "Incursion plan has no incursion ID."
            );
        }

        if (!incursionPlan.hasFrontPlans()) {
            throw new IllegalArgumentException(
                    "Runtime execution requires an incursion plan "
                            + "containing at least one front."
            );
        }
    }

    /**
     * Finds the unique physical source bound to one source composition within
     * its parent front.
     */
    private static SourcePlacementPlan findBoundSourcePlacement(
            FrontPlan frontPlan,
            UUID sourceCompositionId
    ) {
        SourcePlacementPlan matchingPlacement =
                null;

        for (SourceGroupPlacementPlan groupPlacementPlan
                : frontPlan
                .getSourceGroupPlacementPlans()) {

            for (SourcePlacementPlan sourcePlacementPlan
                    : groupPlacementPlan
                    .getSourcePlacementPlans()) {

                if (!sourcePlacementPlan
                        .isBoundToSourceComposition(
                                sourceCompositionId
                        )) {
                    continue;
                }

                if (matchingPlacement != null) {
                    throw new IllegalArgumentException(
                            "Source composition "
                                    + sourceCompositionId
                                    + " is bound to more than one physical "
                                    + "source placement in front "
                                    + frontPlan.getFrontId()
                                    + "."
                    );
                }

                matchingPlacement =
                        sourcePlacementPlan;
            }
        }

        if (matchingPlacement == null) {
            throw new IllegalArgumentException(
                    "Source composition "
                            + sourceCompositionId
                            + " has no physical source placement in front "
                            + frontPlan.getFrontId()
                            + "."
            );
        }

        return matchingPlacement;
    }

    /**
     * Constructs both halves of a fresh runtime graph together.
     *
     * The wave assignments must receive the same SourceExecutionState objects
     * stored in the physical-source map. Constructing them in one helper prevents
     * accidental duplication of those shared objects during constructor
     * delegation.
     */
    private static FreshRuntimeGraph createFreshRuntimeGraph(
            IncursionPlan incursionPlan
    ) {
        PlanRuntimeIndex planRuntimeIndex =
                createPlanRuntimeIndex(
                        incursionPlan
                );

        LinkedHashMap<UUID, SourceExecutionState>
                sourceExecutionStates =
                createFreshSourceExecutionStates(
                        planRuntimeIndex
                );

        TreeMap<Integer, List<SourceWaveExecutionState>>
                waveSourceStates =
                createFreshWaveSourceStates(
                        planRuntimeIndex,
                        sourceExecutionStates
                );

        return new FreshRuntimeGraph(
                sourceExecutionStates,
                waveSourceStates
        );
    }

    // =========================================================
    // Fresh runtime graph
    // =========================================================

    private static LinkedHashMap<UUID, SourceExecutionState>
    createFreshSourceExecutionStates(
            PlanRuntimeIndex planRuntimeIndex
    ) {
        LinkedHashMap<UUID, SourceExecutionState>
                sourceExecutionStates =
                new LinkedHashMap<>();

        for (SourcePlacementPlan sourcePlacementPlan
                : planRuntimeIndex
                .sourcePlacementPlansById()
                .values()) {

            SourceExecutionState sourceExecutionState =
                    new SourceExecutionState(
                            sourcePlacementPlan
                    );

            sourceExecutionStates.put(
                    sourcePlacementPlan
                            .getSourcePlacementId(),
                    sourceExecutionState
            );
        }

        return sourceExecutionStates;
    }

    private static TreeMap<Integer, List<SourceWaveExecutionState>>
    createFreshWaveSourceStates(
            PlanRuntimeIndex planRuntimeIndex,
            Map<UUID, SourceExecutionState> sourceExecutionStates
    ) {
        TreeMap<Integer, List<SourceWaveExecutionState>>
                waveSourceStates =
                new TreeMap<>();

        for (int waveIndex
                : planRuntimeIndex.waveIndexes()) {

            List<SourceWaveExecutionState>
                    assignmentsForWave =
                    new ArrayList<>();

            for (PlannedSourceAssignment plannedAssignment
                    : planRuntimeIndex
                    .plannedAssignmentsByWave()
                    .get(
                            waveIndex
                    )) {

                UUID sourcePlacementId =
                        plannedAssignment
                                .sourcePlacementPlan()
                                .getSourcePlacementId();

                SourceExecutionState sourceExecutionState =
                        sourceExecutionStates.get(
                                sourcePlacementId
                        );

                if (sourceExecutionState == null) {
                    throw new IllegalArgumentException(
                            "No runtime source state exists for source "
                                    + "placement "
                                    + sourcePlacementId
                                    + "."
                    );
                }

                assignmentsForWave.add(
                        new SourceWaveExecutionState(
                                waveIndex,
                                sourceExecutionState,
                                plannedAssignment
                                        .sourceGroupComposition(),
                                plannedAssignment
                                        .sourceComposition()
                        )
                );
            }

            waveSourceStates.put(
                    waveIndex,
                    assignmentsForWave
            );
        }

        return waveSourceStates;
    }

    // =========================================================
    // Runtime graph restoration
    // =========================================================

    private static LinkedHashMap<UUID, SourceExecutionState>
    restoreSourceExecutionStates(
            PlanRuntimeIndex planRuntimeIndex,
            Snapshot snapshot
    ) {
        if (snapshot.sourceExecutionSnapshots().size()
                != planRuntimeIndex
                .sourcePlacementPlansById()
                .size()) {

            throw new IllegalArgumentException(
                    "Incursion execution snapshot contains "
                            + snapshot
                            .sourceExecutionSnapshots()
                            .size()
                            + " physical source snapshots, but the immutable "
                            + "plan contains "
                            + planRuntimeIndex
                            .sourcePlacementPlansById()
                            .size()
                            + "."
            );
        }

        Map<UUID, SourceExecutionState.Snapshot>
                savedSnapshotsByPlacementId =
                new HashMap<>();

        for (SourceExecutionState.Snapshot sourceSnapshot
                : snapshot.sourceExecutionSnapshots()) {

            SourceExecutionState.Snapshot previousSnapshot =
                    savedSnapshotsByPlacementId.putIfAbsent(
                            sourceSnapshot.sourcePlacementId(),
                            sourceSnapshot
                    );

            if (previousSnapshot != null) {
                throw new IllegalArgumentException(
                        "Incursion execution snapshot contains duplicate "
                                + "physical source placement ID "
                                + sourceSnapshot.sourcePlacementId()
                                + "."
                );
            }
        }

        LinkedHashMap<UUID, SourceExecutionState>
                restoredSourceStates =
                new LinkedHashMap<>();

        for (SourcePlacementPlan sourcePlacementPlan
                : planRuntimeIndex
                .sourcePlacementPlansById()
                .values()) {

            UUID sourcePlacementId =
                    sourcePlacementPlan
                            .getSourcePlacementId();

            SourceExecutionState.Snapshot savedSnapshot =
                    savedSnapshotsByPlacementId.remove(
                            sourcePlacementId
                    );

            if (savedSnapshot == null) {
                throw new IllegalArgumentException(
                        "Incursion execution snapshot contains no physical "
                                + "source state for planned placement "
                                + sourcePlacementId
                                + "."
                );
            }

            restoredSourceStates.put(
                    sourcePlacementId,
                    SourceExecutionState.restore(
                            sourcePlacementPlan,
                            savedSnapshot
                    )
            );
        }

        if (!savedSnapshotsByPlacementId.isEmpty()) {
            throw new IllegalArgumentException(
                    "Incursion execution snapshot contains unplanned physical "
                            + "source placements "
                            + savedSnapshotsByPlacementId.keySet()
                            + "."
            );
        }

        return restoredSourceStates;
    }

    private static TreeMap<Integer, List<SourceWaveExecutionState>>
    restoreWaveSourceStates(
            PlanRuntimeIndex planRuntimeIndex,
            Map<UUID, SourceExecutionState> restoredSourceStates,
            Snapshot snapshot
    ) {
        Map<Integer, WaveSourceAssignmentsSnapshot>
                savedWavesByIndex =
                new HashMap<>();

        for (WaveSourceAssignmentsSnapshot waveSnapshot
                : snapshot.waveSourceAssignmentSnapshots()) {

            WaveSourceAssignmentsSnapshot previousSnapshot =
                    savedWavesByIndex.putIfAbsent(
                            waveSnapshot.waveIndex(),
                            waveSnapshot
                    );

            if (previousSnapshot != null) {
                throw new IllegalArgumentException(
                        "Incursion execution snapshot contains duplicate wave "
                                + "index "
                                + waveSnapshot.waveIndex()
                                + "."
                );
            }
        }

        if (savedWavesByIndex.size()
                != planRuntimeIndex.waveIndexes().size()) {

            throw new IllegalArgumentException(
                    "Incursion execution snapshot contains "
                            + savedWavesByIndex.size()
                            + " wave assignment groups, but the immutable "
                            + "plan contains "
                            + planRuntimeIndex.waveIndexes().size()
                            + "."
            );
        }

        TreeMap<Integer, List<SourceWaveExecutionState>>
                restoredWaveStates =
                new TreeMap<>();

        for (int waveIndex
                : planRuntimeIndex.waveIndexes()) {

            WaveSourceAssignmentsSnapshot savedWave =
                    savedWavesByIndex.remove(
                            waveIndex
                    );

            if (savedWave == null) {
                throw new IllegalArgumentException(
                        "Incursion execution snapshot contains no assignment "
                                + "state for planned wave "
                                + waveIndex
                                + "."
                );
            }

            List<PlannedSourceAssignment>
                    plannedAssignments =
                    planRuntimeIndex
                            .plannedAssignmentsByWave()
                            .get(
                                    waveIndex
                            );

            List<SourceWaveExecutionState.Snapshot>
                    savedAssignments =
                    savedWave.sourceWaveExecutionSnapshots();

            if (savedAssignments.size()
                    != plannedAssignments.size()) {

                throw new IllegalArgumentException(
                        "Wave "
                                + waveIndex
                                + " contains "
                                + savedAssignments.size()
                                + " saved source assignments, but the "
                                + "immutable plan contains "
                                + plannedAssignments.size()
                                + "."
                );
            }

            List<SourceWaveExecutionState>
                    restoredAssignments =
                    new ArrayList<>();

            for (int assignmentIndex = 0;
                 assignmentIndex < plannedAssignments.size();
                 assignmentIndex++) {

                PlannedSourceAssignment plannedAssignment =
                        plannedAssignments.get(
                                assignmentIndex
                        );

                SourceWaveExecutionState.Snapshot savedAssignment =
                        savedAssignments.get(
                                assignmentIndex
                        );

                UUID plannedSourcePlacementId =
                        plannedAssignment
                                .sourcePlacementPlan()
                                .getSourcePlacementId();

                UUID plannedSourceCompositionId =
                        plannedAssignment
                                .sourceComposition()
                                .getSourceCompositionId();

                if (savedAssignment.waveIndex()
                        != waveIndex) {

                    throw new IllegalArgumentException(
                            "Saved assignment at position "
                                    + assignmentIndex
                                    + " in wave "
                                    + waveIndex
                                    + " claims to belong to wave "
                                    + savedAssignment.waveIndex()
                                    + "."
                    );
                }

                if (!plannedSourcePlacementId.equals(
                        savedAssignment.sourcePlacementId()
                )) {
                    throw new IllegalArgumentException(
                            "Saved assignment at position "
                                    + assignmentIndex
                                    + " in wave "
                                    + waveIndex
                                    + " belongs to source placement "
                                    + savedAssignment.sourcePlacementId()
                                    + " but the immutable plan expects "
                                    + plannedSourcePlacementId
                                    + "."
                    );
                }

                if (!plannedSourceCompositionId.equals(
                        savedAssignment.sourceCompositionId()
                )) {
                    throw new IllegalArgumentException(
                            "Saved assignment at position "
                                    + assignmentIndex
                                    + " in wave "
                                    + waveIndex
                                    + " belongs to source composition "
                                    + savedAssignment.sourceCompositionId()
                                    + " but the immutable plan expects "
                                    + plannedSourceCompositionId
                                    + "."
                    );
                }

                SourceExecutionState restoredSourceState =
                        restoredSourceStates.get(
                                plannedSourcePlacementId
                        );

                if (restoredSourceState == null) {
                    throw new IllegalArgumentException(
                            "No restored physical source state exists for "
                                    + "planned placement "
                                    + plannedSourcePlacementId
                                    + "."
                    );
                }

                restoredAssignments.add(
                        SourceWaveExecutionState.restore(
                                restoredSourceState,
                                plannedAssignment
                                        .sourceGroupComposition(),
                                plannedAssignment
                                        .sourceComposition(),
                                savedAssignment
                        )
                );
            }

            restoredWaveStates.put(
                    waveIndex,
                    restoredAssignments
            );
        }

        if (!savedWavesByIndex.isEmpty()) {
            throw new IllegalArgumentException(
                    "Incursion execution snapshot contains unplanned wave "
                            + "indexes "
                            + savedWavesByIndex.keySet()
                            + "."
            );
        }

        return restoredWaveStates;
    }

    // =========================================================
    // Completed graph validation
    // =========================================================

    private static void validateCompletedRuntimeGraph(
            IncursionPlan incursionPlan,
            Map<UUID, SourceExecutionState> sourceExecutionStates,
            Map<Integer, List<SourceWaveExecutionState>>
                    waveSourceStates
    ) {
        validateIncursionPlan(
                incursionPlan
        );

        if (sourceExecutionStates == null
                || sourceExecutionStates.isEmpty()) {

            throw new IllegalArgumentException(
                    "Incursion runtime requires at least one persistent "
                            + "physical source state."
            );
        }

        if (waveSourceStates == null
                || waveSourceStates.isEmpty()) {

            throw new IllegalArgumentException(
                    "Incursion runtime requires at least one wave assignment."
            );
        }

        PlanRuntimeIndex planRuntimeIndex =
                createPlanRuntimeIndex(
                        incursionPlan
                );

        if (!sourceExecutionStates.keySet().equals(
                planRuntimeIndex
                        .sourcePlacementPlansById()
                        .keySet()
        )) {
            throw new IllegalArgumentException(
                    "Runtime physical source IDs do not exactly match the "
                            + "immutable incursion plan."
            );
        }

        for (Map.Entry<UUID, SourceExecutionState> entry
                : sourceExecutionStates.entrySet()) {

            UUID sourcePlacementId =
                    entry.getKey();

            SourceExecutionState sourceExecutionState =
                    entry.getValue();

            if (sourceExecutionState == null) {
                throw new IllegalArgumentException(
                        "Runtime source map contains a null state for "
                                + "placement "
                                + sourcePlacementId
                                + "."
                );
            }

            if (!sourcePlacementId.equals(
                    sourceExecutionState
                            .getSourcePlacementId()
            )) {
                throw new IllegalArgumentException(
                        "Runtime source map key "
                                + sourcePlacementId
                                + " does not match contained source state "
                                + sourceExecutionState
                                .getSourcePlacementId()
                                + "."
                );
            }

            SourcePlacementPlan plannedPlacement =
                    planRuntimeIndex
                            .sourcePlacementPlansById()
                            .get(
                                    sourcePlacementId
                            );

            if (sourceExecutionState
                    .getSourcePlacementPlan()
                    != plannedPlacement) {

                throw new IllegalArgumentException(
                        "Runtime source state "
                                + sourcePlacementId
                                + " is not backed by the exact immutable "
                                + "SourcePlacementPlan belonging to this "
                                + "IncursionPlan."
                );
            }
        }

        List<Integer> runtimeWaveIndexes =
                new ArrayList<>(
                        waveSourceStates.keySet()
                );

        if (!runtimeWaveIndexes.equals(
                planRuntimeIndex.waveIndexes()
        )) {
            throw new IllegalArgumentException(
                    "Runtime wave indexes "
                            + runtimeWaveIndexes
                            + " do not match planned wave indexes "
                            + planRuntimeIndex.waveIndexes()
                            + "."
            );
        }

        Set<UUID> seenSourceCompositionIds =
                new HashSet<>();

        for (int waveIndex
                : planRuntimeIndex.waveIndexes()) {

            List<PlannedSourceAssignment>
                    plannedAssignments =
                    planRuntimeIndex
                            .plannedAssignmentsByWave()
                            .get(
                                    waveIndex
                            );

            List<SourceWaveExecutionState>
                    runtimeAssignments =
                    waveSourceStates.get(
                            waveIndex
                    );

            if (runtimeAssignments == null) {
                throw new IllegalArgumentException(
                        "Runtime contains no assignments for wave "
                                + waveIndex
                                + "."
                );
            }

            if (runtimeAssignments.size()
                    != plannedAssignments.size()) {

                throw new IllegalArgumentException(
                        "Runtime wave "
                                + waveIndex
                                + " contains "
                                + runtimeAssignments.size()
                                + " source assignments, but the immutable "
                                + "plan contains "
                                + plannedAssignments.size()
                                + "."
                );
            }

            for (int assignmentIndex = 0;
                 assignmentIndex < plannedAssignments.size();
                 assignmentIndex++) {

                PlannedSourceAssignment plannedAssignment =
                        plannedAssignments.get(
                                assignmentIndex
                        );

                SourceWaveExecutionState runtimeAssignment =
                        runtimeAssignments.get(
                                assignmentIndex
                        );

                if (runtimeAssignment == null) {
                    throw new IllegalArgumentException(
                            "Runtime wave "
                                    + waveIndex
                                    + " contains a null source assignment at "
                                    + "position "
                                    + assignmentIndex
                                    + "."
                    );
                }

                UUID plannedSourcePlacementId =
                        plannedAssignment
                                .sourcePlacementPlan()
                                .getSourcePlacementId();

                UUID plannedSourceCompositionId =
                        plannedAssignment
                                .sourceComposition()
                                .getSourceCompositionId();

                if (runtimeAssignment.getWaveIndex()
                        != waveIndex) {

                    throw new IllegalArgumentException(
                            "Runtime assignment at position "
                                    + assignmentIndex
                                    + " belongs to wave "
                                    + runtimeAssignment.getWaveIndex()
                                    + " rather than wave "
                                    + waveIndex
                                    + "."
                    );
                }

                if (!plannedSourcePlacementId.equals(
                        runtimeAssignment
                                .getSourcePlacementId()
                )) {
                    throw new IllegalArgumentException(
                            "Runtime assignment at position "
                                    + assignmentIndex
                                    + " in wave "
                                    + waveIndex
                                    + " uses source placement "
                                    + runtimeAssignment
                                    .getSourcePlacementId()
                                    + " rather than planned placement "
                                    + plannedSourcePlacementId
                                    + "."
                    );
                }

                if (!plannedSourceCompositionId.equals(
                        runtimeAssignment
                                .getSourceCompositionId()
                )) {
                    throw new IllegalArgumentException(
                            "Runtime assignment at position "
                                    + assignmentIndex
                                    + " in wave "
                                    + waveIndex
                                    + " uses source composition "
                                    + runtimeAssignment
                                    .getSourceCompositionId()
                                    + " rather than planned composition "
                                    + plannedSourceCompositionId
                                    + "."
                    );
                }
                if (runtimeAssignment.getSourceGroupComposition()
                        != plannedAssignment.sourceGroupComposition()) {

                    throw new IllegalArgumentException(
                            "Runtime assignment for source composition "
                                    + plannedSourceCompositionId
                                    + " is not backed by the exact immutable parent "
                                    + "source-group composition "
                                    + plannedAssignment
                                    .sourceGroupComposition()
                                    .getSourceGroupCompositionId()
                                    + "."
                    );
                }

                SourceExecutionState sharedSourceState =
                        sourceExecutionStates.get(
                                plannedSourcePlacementId
                        );

                if (runtimeAssignment
                        .getSourceExecutionState()
                        != sharedSourceState) {

                    throw new IllegalArgumentException(
                            "Runtime assignment for source composition "
                                    + plannedSourceCompositionId
                                    + " does not share the persistent source "
                                    + "state belonging to placement "
                                    + plannedSourcePlacementId
                                    + "."
                    );
                }

                if (!seenSourceCompositionIds.add(
                        plannedSourceCompositionId
                )) {
                    throw new IllegalArgumentException(
                            "Runtime graph contains duplicate source "
                                    + "composition assignment "
                                    + plannedSourceCompositionId
                                    + "."
                    );
                }
            }
        }
    }

    // =========================================================
    // Persistence snapshots
    // =========================================================

    /**
     * Immutable persistence snapshot for the complete shared execution graph
     * belonging to one incursion.
     *
     * The immutable IncursionPlan itself will be persisted separately by the
     * eventual top-level persistent incursion record.
     */
    public record Snapshot(
            UUID incursionId,
            List<SourceExecutionState.Snapshot>
            sourceExecutionSnapshots,
            List<WaveSourceAssignmentsSnapshot>
            waveSourceAssignmentSnapshots
    ) {

        public Snapshot {
            if (incursionId == null) {
                throw new IllegalArgumentException(
                        "Incursion execution snapshot ID cannot be null."
                );
            }

            if (sourceExecutionSnapshots == null
                    || sourceExecutionSnapshots.isEmpty()) {

                throw new IllegalArgumentException(
                        "Incursion execution snapshot requires at least one "
                                + "physical source snapshot."
                );
            }

            if (waveSourceAssignmentSnapshots == null
                    || waveSourceAssignmentSnapshots.isEmpty()) {

                throw new IllegalArgumentException(
                        "Incursion execution snapshot requires at least one "
                                + "wave assignment snapshot."
                );
            }

            sourceExecutionSnapshots =
                    List.copyOf(
                            sourceExecutionSnapshots
                    );

            waveSourceAssignmentSnapshots =
                    List.copyOf(
                            waveSourceAssignmentSnapshots
                    );

            validateSnapshotGraph(
                    sourceExecutionSnapshots,
                    waveSourceAssignmentSnapshots
            );
        }

        public int getPhysicalSourceCount() {
            return sourceExecutionSnapshots.size();
        }

        public int getWaveCount() {
            return waveSourceAssignmentSnapshots.size();
        }

        public int getSourceWaveAssignmentCount() {
            int assignmentCount =
                    0;

            for (WaveSourceAssignmentsSnapshot waveSnapshot
                    : waveSourceAssignmentSnapshots) {

                assignmentCount +=
                        waveSnapshot
                                .sourceWaveExecutionSnapshots()
                                .size();
            }

            return assignmentCount;
        }

        public WaveSourceAssignmentsSnapshot
        getWaveSourceAssignmentSnapshot(
                int waveIndex
        ) {
            for (WaveSourceAssignmentsSnapshot waveSnapshot
                    : waveSourceAssignmentSnapshots) {

                if (waveSnapshot.waveIndex()
                        == waveIndex) {

                    return waveSnapshot;
                }
            }

            return null;
        }

        private static void validateSnapshotGraph(
                List<SourceExecutionState.Snapshot>
                        sourceExecutionSnapshots,
                List<WaveSourceAssignmentsSnapshot>
                        waveSourceAssignmentSnapshots
        ) {
            Set<UUID> physicalSourcePlacementIds =
                    new LinkedHashSet<>();

            for (SourceExecutionState.Snapshot sourceSnapshot
                    : sourceExecutionSnapshots) {

                if (sourceSnapshot == null) {
                    throw new IllegalArgumentException(
                            "Incursion execution snapshot cannot contain a "
                                    + "null physical source snapshot."
                    );
                }

                if (!physicalSourcePlacementIds.add(
                        sourceSnapshot.sourcePlacementId()
                )) {
                    throw new IllegalArgumentException(
                            "Incursion execution snapshot contains duplicate "
                                    + "physical source placement ID "
                                    + sourceSnapshot.sourcePlacementId()
                                    + "."
                    );
                }
            }

            Set<Integer> waveIndexes =
                    new LinkedHashSet<>();

            Set<UUID> sourceCompositionIds =
                    new HashSet<>();

            Set<UUID> referencedSourcePlacementIds =
                    new HashSet<>();

            int previousWaveIndex =
                    -1;

            for (WaveSourceAssignmentsSnapshot waveSnapshot
                    : waveSourceAssignmentSnapshots) {

                if (waveSnapshot == null) {
                    throw new IllegalArgumentException(
                            "Incursion execution snapshot cannot contain a "
                                    + "null wave assignment snapshot."
                    );
                }

                if (!waveIndexes.add(
                        waveSnapshot.waveIndex()
                )) {
                    throw new IllegalArgumentException(
                            "Incursion execution snapshot contains duplicate "
                                    + "wave index "
                                    + waveSnapshot.waveIndex()
                                    + "."
                    );
                }

                if (waveSnapshot.waveIndex()
                        <= previousWaveIndex) {

                    throw new IllegalArgumentException(
                            "Incursion execution wave snapshots must appear "
                                    + "in strictly ascending wave-index "
                                    + "order."
                    );
                }

                previousWaveIndex =
                        waveSnapshot.waveIndex();

                for (SourceWaveExecutionState.Snapshot sourceWaveSnapshot
                        : waveSnapshot
                        .sourceWaveExecutionSnapshots()) {

                    UUID sourcePlacementId =
                            sourceWaveSnapshot
                                    .sourcePlacementId();

                    UUID sourceCompositionId =
                            sourceWaveSnapshot
                                    .sourceCompositionId();

                    if (!physicalSourcePlacementIds.contains(
                            sourcePlacementId
                    )) {
                        throw new IllegalArgumentException(
                                "Wave "
                                        + waveSnapshot.waveIndex()
                                        + " refers to physical source "
                                        + "placement "
                                        + sourcePlacementId
                                        + " that has no source execution "
                                        + "snapshot."
                        );
                    }

                    if (!sourceCompositionIds.add(
                            sourceCompositionId
                    )) {
                        throw new IllegalArgumentException(
                                "Incursion execution snapshot contains "
                                        + "duplicate source-composition "
                                        + "assignment "
                                        + sourceCompositionId
                                        + "."
                        );
                    }

                    referencedSourcePlacementIds.add(
                            sourcePlacementId
                    );
                }
            }

            if (!referencedSourcePlacementIds.equals(
                    physicalSourcePlacementIds
            )) {
                Set<UUID> unreferencedSourcePlacementIds =
                        new LinkedHashSet<>(
                                physicalSourcePlacementIds
                        );

                unreferencedSourcePlacementIds.removeAll(
                        referencedSourcePlacementIds
                );

                throw new IllegalArgumentException(
                        "Incursion execution snapshot contains physical "
                                + "sources with no wave assignments: "
                                + unreferencedSourcePlacementIds
                                + "."
                );
            }
        }
    }

    /**
     * Immutable ordered collection of source assignments belonging to one
     * global wave.
     */
    public record WaveSourceAssignmentsSnapshot(
            int waveIndex,
            List<SourceWaveExecutionState.Snapshot>
            sourceWaveExecutionSnapshots
    ) {

        public WaveSourceAssignmentsSnapshot {
            if (waveIndex < 0) {
                throw new IllegalArgumentException(
                        "Wave assignment snapshot index cannot be negative."
                );
            }

            if (sourceWaveExecutionSnapshots == null
                    || sourceWaveExecutionSnapshots.isEmpty()) {

                throw new IllegalArgumentException(
                        "Wave "
                                + waveIndex
                                + " requires at least one source assignment "
                                + "snapshot."
                );
            }

            sourceWaveExecutionSnapshots =
                    List.copyOf(
                            sourceWaveExecutionSnapshots
                    );

            Set<UUID> sourceCompositionIds =
                    new HashSet<>();

            for (SourceWaveExecutionState.Snapshot sourceWaveSnapshot
                    : sourceWaveExecutionSnapshots) {

                if (sourceWaveSnapshot == null) {
                    throw new IllegalArgumentException(
                            "Wave "
                                    + waveIndex
                                    + " cannot contain a null source "
                                    + "assignment snapshot."
                    );
                }

                if (sourceWaveSnapshot.waveIndex()
                        != waveIndex) {

                    throw new IllegalArgumentException(
                            "Source assignment snapshot belongs to wave "
                                    + sourceWaveSnapshot.waveIndex()
                                    + " rather than containing wave "
                                    + waveIndex
                                    + "."
                    );
                }

                if (!sourceCompositionIds.add(
                        sourceWaveSnapshot
                                .sourceCompositionId()
                )) {
                    throw new IllegalArgumentException(
                            "Wave "
                                    + waveIndex
                                    + " contains duplicate source "
                                    + "composition ID "
                                    + sourceWaveSnapshot
                                    .sourceCompositionId()
                                    + "."
                    );
                }
            }
        }

        public int getPlannedMobCount() {
            int plannedMobCount =
                    0;

            for (SourceWaveExecutionState.Snapshot sourceWaveSnapshot
                    : sourceWaveExecutionSnapshots) {

                plannedMobCount +=
                        sourceWaveSnapshot.plannedMobCount();
            }

            return plannedMobCount;
        }

        public int getRemainingMobCount() {
            int remainingMobCount =
                    0;

            for (SourceWaveExecutionState.Snapshot sourceWaveSnapshot
                    : sourceWaveExecutionSnapshots) {

                remainingMobCount +=
                        sourceWaveSnapshot.getRemainingMobCount();
            }

            return remainingMobCount;
        }
    }

    // =========================================================
    // Internal immutable indexes
    // =========================================================

    /**
     * Temporary construction result containing both halves of one fresh shared
     * runtime graph.
     */
    private record FreshRuntimeGraph(
            LinkedHashMap<UUID, SourceExecutionState>
            sourceExecutionStates,
            TreeMap<Integer, List<SourceWaveExecutionState>>
            waveSourceStates
    ) {

        private FreshRuntimeGraph {
            if (sourceExecutionStates == null
                    || sourceExecutionStates.isEmpty()) {

                throw new IllegalArgumentException(
                        "Fresh runtime graph requires physical source states."
                );
            }

            if (waveSourceStates == null
                    || waveSourceStates.isEmpty()) {

                throw new IllegalArgumentException(
                        "Fresh runtime graph requires wave source assignments."
                );
            }
        }
    }

    private record PlanRuntimeIndex(
            Map<UUID, SourcePlacementPlan>
            sourcePlacementPlansById,
            Map<Integer, List<PlannedSourceAssignment>>
            plannedAssignmentsByWave,
            List<Integer> waveIndexes
    ) {

        private PlanRuntimeIndex {
            if (sourcePlacementPlansById == null
                    || sourcePlacementPlansById.isEmpty()) {

                throw new IllegalArgumentException(
                        "Plan runtime index requires physical source "
                                + "placements."
                );
            }

            if (plannedAssignmentsByWave == null
                    || plannedAssignmentsByWave.isEmpty()) {

                throw new IllegalArgumentException(
                        "Plan runtime index requires wave assignments."
                );
            }

            if (waveIndexes == null
                    || waveIndexes.isEmpty()) {

                throw new IllegalArgumentException(
                        "Plan runtime index requires wave indexes."
                );
            }

            sourcePlacementPlansById =
                    Collections.unmodifiableMap(
                            new LinkedHashMap<>(
                                    sourcePlacementPlansById
                            )
                    );

            LinkedHashMap<Integer, List<PlannedSourceAssignment>>
                    copiedAssignments =
                    new LinkedHashMap<>();

            for (Map.Entry<Integer, List<PlannedSourceAssignment>>
                    entry
                    : plannedAssignmentsByWave.entrySet()) {

                copiedAssignments.put(
                        entry.getKey(),
                        List.copyOf(
                                entry.getValue()
                        )
                );
            }

            plannedAssignmentsByWave =
                    Collections.unmodifiableMap(
                            copiedAssignments
                    );

            waveIndexes =
                    List.copyOf(
                            waveIndexes
                    );
        }
    }

    private record PlannedSourceAssignment(
            int waveIndex,
            UUID frontId,
            SourceGroupComposition sourceGroupComposition,
            SourcePlacementPlan sourcePlacementPlan,
            SourceGroupComposition.SourceComposition
            sourceComposition
    ) {

        private PlannedSourceAssignment {
            if (waveIndex < 0) {
                throw new IllegalArgumentException(
                        "Planned source-assignment wave index cannot be "
                                + "negative."
                );
            }

            if (frontId == null) {
                throw new IllegalArgumentException(
                        "Planned source-assignment front ID cannot be null."
                );
            }

            if (sourceGroupComposition == null) {
                throw new IllegalArgumentException(
                        "Planned source assignment requires a parent "
                                + "source-group composition."
                );
            }

            if (sourcePlacementPlan == null) {
                throw new IllegalArgumentException(
                        "Planned source assignment requires a physical source "
                                + "placement."
                );
            }

            if (sourceComposition == null) {
                throw new IllegalArgumentException(
                        "Planned source assignment requires a source "
                                + "composition."
                );
            }

            SourceGroupComposition.SourceComposition
                    containedComposition =
                    sourceGroupComposition.getSourceComposition(
                            sourceComposition.getSourceCompositionId()
                    );

            if (containedComposition == null) {
                throw new IllegalArgumentException(
                        "Source composition "
                                + sourceComposition
                                .getSourceCompositionId()
                                + " does not belong to parent source-group "
                                + "composition "
                                + sourceGroupComposition
                                .getSourceGroupCompositionId()
                                + "."
                );
            }

            if (containedComposition != sourceComposition) {
                throw new IllegalArgumentException(
                        "Planned source assignment does not use the exact child "
                                + "SourceComposition stored by parent group "
                                + sourceGroupComposition
                                .getSourceGroupCompositionId()
                                + "."
                );
            }

            if (!sourcePlacementPlan
                    .isBoundToSourceComposition(
                            sourceComposition
                                    .getSourceCompositionId()
                    )) {

                throw new IllegalArgumentException(
                        "Source placement "
                                + sourcePlacementPlan
                                .getSourcePlacementId()
                                + " is not bound to source composition "
                                + sourceComposition
                                .getSourceCompositionId()
                                + "."
                );
            }
        }
    }
}