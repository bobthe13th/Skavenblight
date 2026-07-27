package org.ratden.skavenblight.event.skavenIncursion.runtime.source;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.ratden.skavenblight.block.entity.state.SourceState;
import org.ratden.skavenblight.event.skavenIncursion.leadership.LeadershipContext;
import org.ratden.skavenblight.event.skavenIncursion.planning.composition.SourceGroupComposition;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourcePlacementPlan;
import org.ratden.skavenblight.event.skavenIncursion.runtime.entity.AttachedMobEntityBindingState;
import org.ratden.skavenblight.event.skavenIncursion.action.mob.modifier.AttachedMobRuntimeModifierExecutor;
import org.ratden.skavenblight.event.skavenIncursion.runtime.entity.AttachedMobAssignmentEntity;
import org.ratden.skavenblight.event.skavenIncursion.leadership.IncursionOwnedMob;
import org.ratden.skavenblight.event.skavenIncursion.runtime.entity.AttachedMobEntityReconnectionResult;


import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Runtime state for one physical source's assignment in one wave.
 *
 * SourceExecutionState persists across waves and represents the planned
 * source location and its current physical incarnation.
 *
 * SourceSpawnQueue belongs only to this wave. Destroying the source therefore
 * cancels this queue without deleting assignments belonging to later waves.
 *
 * AttachedMobEntityBindingState also belongs to this wave-specific source
 * composition. It maps stable attached-mob assignment IDs from the immutable
 * SourceComposition to the entities that successfully fulfilled them.
 *
 * The parent SourceGroupComposition remains available because group-scoped
 * planning state, including the optional Pack assignment, must be interpreted
 * when attached mobs enter runtime. It is derived from the immutable
 * IncursionPlan and is therefore not duplicated in the mutable snapshot.
 */
public class SourceWaveExecutionState {

    private final int waveIndex;

    private final SourceExecutionState sourceExecutionState;

    /**
     * Exact immutable parent source-group composition backing this runtime
     * assignment.
     */
    private final SourceGroupComposition sourceGroupComposition;

    /**
     * Exact immutable child source composition backing this runtime
     * assignment.
     */
    private final SourceGroupComposition.SourceComposition
            sourceComposition;

    private final UUID sourceCompositionId;

    private final SourceSpawnQueue spawnQueue;

    private final AttachedMobEntityBindingState
            attachedMobEntityBindingState;

    private final int plannedMobCount;

    private int successfulSpawnCount;
    private int cancelledMobCount;

    private boolean cancelled;

    /**
     * Creates fresh runtime state for one planned source assignment.
     */
    public SourceWaveExecutionState(
            int waveIndex,
            SourceExecutionState sourceExecutionState,
            SourceGroupComposition sourceGroupComposition,
            SourceGroupComposition.SourceComposition sourceComposition
    ) {
        validatePlanBinding(
                waveIndex,
                sourceExecutionState,
                sourceGroupComposition,
                sourceComposition
        );

        this.waveIndex =
                waveIndex;

        this.sourceExecutionState =
                sourceExecutionState;

        this.sourceGroupComposition =
                sourceGroupComposition;

        this.sourceComposition =
                sourceComposition;

        this.sourceCompositionId =
                sourceComposition.getSourceCompositionId();

        this.spawnQueue =
                new SourceSpawnQueue(
                        sourceComposition
                );

        this.attachedMobEntityBindingState =
                new AttachedMobEntityBindingState(
                        getPlannedAttachedMobAssignmentIds(
                                sourceComposition
                        )
                );

        this.plannedMobCount =
                sourceComposition.getTotalMobCount();

        this.successfulSpawnCount =
                0;

        this.cancelledMobCount =
                0;

        this.cancelled =
                false;

        validateInternalState();
    }

    /**
     * Canonical constructor used by restoration.
     */
    private SourceWaveExecutionState(
            int waveIndex,
            SourceExecutionState sourceExecutionState,
            SourceGroupComposition sourceGroupComposition,
            SourceGroupComposition.SourceComposition sourceComposition,
            SourceSpawnQueue spawnQueue,
            AttachedMobEntityBindingState attachedMobEntityBindingState,
            int plannedMobCount,
            int successfulSpawnCount,
            int cancelledMobCount,
            boolean cancelled
    ) {
        validatePlanBinding(
                waveIndex,
                sourceExecutionState,
                sourceGroupComposition,
                sourceComposition
        );

        if (spawnQueue == null) {
            throw new IllegalArgumentException(
                    "Restored source spawn queue cannot be null."
            );
        }

        if (attachedMobEntityBindingState == null) {
            throw new IllegalArgumentException(
                    "Restored attached-mob entity-binding state cannot be "
                            + "null."
            );
        }

        this.waveIndex =
                waveIndex;

        this.sourceExecutionState =
                sourceExecutionState;

        this.sourceGroupComposition =
                sourceGroupComposition;

        this.sourceComposition =
                sourceComposition;

        this.sourceCompositionId =
                sourceComposition.getSourceCompositionId();

        this.spawnQueue =
                spawnQueue;

        this.attachedMobEntityBindingState =
                attachedMobEntityBindingState;

        this.plannedMobCount =
                plannedMobCount;

        this.successfulSpawnCount =
                successfulSpawnCount;

        this.cancelledMobCount =
                cancelledMobCount;

        this.cancelled =
                cancelled;

        validateInternalState();
    }

    /**
     * Restores exact wave-specific progress against the supplied immutable
     * source group, child source composition and shared physical source state.
     *
     * The saved snapshot identifies the child source composition. Its parent
     * group is reconstructed from the immutable IncursionPlan rather than
     * duplicated in mutable persistence.
     */
    public static SourceWaveExecutionState restore(
            SourceExecutionState sourceExecutionState,
            SourceGroupComposition sourceGroupComposition,
            SourceGroupComposition.SourceComposition sourceComposition,
            Snapshot snapshot
    ) {
        if (sourceExecutionState == null) {
            throw new IllegalArgumentException(
                    "Restored physical source state cannot be null."
            );
        }

        if (sourceGroupComposition == null) {
            throw new IllegalArgumentException(
                    "Restored source-group composition cannot be null."
            );
        }

        if (sourceComposition == null) {
            throw new IllegalArgumentException(
                    "Restored source composition cannot be null."
            );
        }

        if (snapshot == null) {
            throw new IllegalArgumentException(
                    "Source-wave execution snapshot cannot be null."
            );
        }

        if (!sourceExecutionState
                .getSourcePlacementId()
                .equals(
                        snapshot.sourcePlacementId()
                )) {

            throw new IllegalArgumentException(
                    "Source-wave snapshot belongs to physical source "
                            + "placement "
                            + snapshot.sourcePlacementId()
                            + " rather than supplied placement "
                            + sourceExecutionState.getSourcePlacementId()
                            + "."
            );
        }

        if (!sourceComposition
                .getSourceCompositionId()
                .equals(
                        snapshot.sourceCompositionId()
                )) {

            throw new IllegalArgumentException(
                    "Source-wave snapshot belongs to source composition "
                            + snapshot.sourceCompositionId()
                            + " rather than supplied composition "
                            + sourceComposition.getSourceCompositionId()
                            + "."
            );
        }

        if (snapshot.plannedMobCount()
                != sourceComposition.getTotalMobCount()) {

            throw new IllegalArgumentException(
                    "Source-wave snapshot contains planned mob count "
                            + snapshot.plannedMobCount()
                            + ", but immutable source composition "
                            + sourceComposition.getSourceCompositionId()
                            + " contains "
                            + sourceComposition.getTotalMobCount()
                            + "."
            );
        }

        SourceSpawnQueue restoredSpawnQueue =
                SourceSpawnQueue.restore(
                        sourceComposition,
                        snapshot.spawnQueueSnapshot()
                );

        AttachedMobEntityBindingState
                restoredBindingState =
                AttachedMobEntityBindingState.restore(
                        getPlannedAttachedMobAssignmentIds(
                                sourceComposition
                        ),
                        snapshot
                                .attachedMobEntityBindingSnapshot()
                );

        SourceWaveExecutionState restoredState =
                new SourceWaveExecutionState(
                        snapshot.waveIndex(),
                        sourceExecutionState,
                        sourceGroupComposition,
                        sourceComposition,
                        restoredSpawnQueue,
                        restoredBindingState,
                        snapshot.plannedMobCount(),
                        snapshot.successfulSpawnCount(),
                        snapshot.cancelledMobCount(),
                        snapshot.cancelled()
                );

        Snapshot reconstructedSnapshot =
                restoredState.createSnapshot();

        if (!snapshot.equals(
                reconstructedSnapshot
        )) {
            throw new IllegalArgumentException(
                    "Restored source-wave execution state does not exactly "
                            + "match its saved snapshot for source composition "
                            + snapshot.sourceCompositionId()
                            + "."
            );
        }

        return restoredState;
    }

    public int getWaveIndex() {
        return waveIndex;
    }

    public SourceExecutionState getSourceExecutionState() {
        return sourceExecutionState;
    }

    public SourceGroupComposition getSourceGroupComposition() {
        return sourceGroupComposition;
    }

    public UUID getSourceGroupCompositionId() {
        return sourceGroupComposition
                .getSourceGroupCompositionId();
    }

    public SourceGroupComposition.SourceComposition
    getSourceComposition() {
        return sourceComposition;
    }

    public UUID getSourcePlacementId() {
        return sourceExecutionState.getSourcePlacementId();
    }

    public UUID getSourceCompositionId() {
        return sourceCompositionId;
    }

    public SourceSpawnQueue getSpawnQueue() {
        return spawnQueue;
    }

    public AttachedMobEntityBindingState
    getAttachedMobEntityBindingState() {
        return attachedMobEntityBindingState;
    }

    public int getPlannedMobCount() {
        return plannedMobCount;
    }

    public int getSuccessfulSpawnCount() {
        return successfulSpawnCount;
    }

    public int getCancelledMobCount() {
        return cancelledMobCount;
    }

    public int getRemainingMobCount() {
        return spawnQueue.getRemainingMobCount();
    }

    public boolean hasRemainingMobs() {
        return spawnQueue.hasRemainingMobs();
    }

    /**
     * A wave-source assignment is complete when it has either spawned or
     * cancelled every planned mob.
     */
    public boolean isComplete() {
        return spawnQueue.isEmpty();
    }

    public boolean wasCancelled() {
        return cancelled;
    }

    public boolean completedNormally() {
        return isComplete()
                && !cancelled;
    }

    public boolean hasAttachedMobAssignments() {
        return attachedMobEntityBindingState
                .hasPlannedAssignments();
    }

    public int getPlannedAttachedMobAssignmentCount() {
        return attachedMobEntityBindingState
                .getPlannedAssignmentCount();
    }

    public int getBoundAttachedMobAssignmentCount() {
        return attachedMobEntityBindingState
                .getBoundAssignmentCount();
    }

    public boolean isAttachedMobAssignmentBound(
            UUID attachedMobAssignmentId
    ) {
        return attachedMobEntityBindingState.isBound(
                attachedMobAssignmentId
        );
    }

    public UUID getBoundEntityId(
            UUID attachedMobAssignmentId
    ) {
        return attachedMobEntityBindingState.getBoundEntityId(
                attachedMobAssignmentId
        );
    }

    /**
     * Records the entity that successfully fulfilled one attached-mob
     * assignment.
     *
     * The spawning layer must call this only after the corresponding mob was
     * successfully added to the world and the source queue recorded that
     * successful spawn.
     *
     * @return true when a new binding was created
     */
    public boolean bindAttachedMobEntity(
            UUID attachedMobAssignmentId,
            Entity entity
    ) {
        if (attachedMobEntityBindingState
                .getBoundAssignmentCount()
                >= successfulSpawnCount) {

            throw new IllegalStateException(
                    "Cannot bind another attached-mob entity because this "
                            + "source-wave assignment has recorded only "
                            + successfulSpawnCount
                            + " successful spawns."
            );
        }

        boolean newlyBound =
                attachedMobEntityBindingState.bind(
                        attachedMobAssignmentId,
                        entity
                );

        validateInternalState();

        return newlyBound;
    }


    /**
     * Reconnects one naturally loaded entity to its existing persistent
     * attached-mob binding.
     *
     * This method does not search for entities and does not create a new
     * binding. It verifies that both sides of the saved relationship already
     * agree:
     *
     * - the entity identifies the planned attached assignment;
     * - persistent runtime binds that assignment to this entity UUID;
     * - the entity belongs to a physical source incarnation used by this
     *   source placement;
     * - the entity carries the planned Pack ID;
     * - the runtime modifier remains correctly applied.
     *
     * The runtime modifier is reapplied idempotently during this load event.
     * Entity position remains owned by Minecraft's ordinary chunk
     * persistence, so central incursion state is not mutated.
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

        if (entity.isRemoved()) {
            throw new IllegalArgumentException(
                    "A removed entity cannot be reconnected."
            );
        }

        UUID entityId =
                entity.getUUID();

        if (!(entity
                instanceof AttachedMobAssignmentEntity
                attachedAssignmentEntity)) {

            throw new IllegalArgumentException(
                    "Loaded entity "
                            + entityId
                            + " does not implement "
                            + "AttachedMobAssignmentEntity."
            );
        }

        UUID attachedMobAssignmentId =
                attachedAssignmentEntity
                        .getAttachedMobAssignmentId();

        if (attachedMobAssignmentId == null) {
            throw new IllegalArgumentException(
                    "Loaded entity "
                            + entityId
                            + " has no attached-mob assignment ID."
            );
        }

        if (!attachedMobEntityBindingState.containsAssignment(
                attachedMobAssignmentId
        )) {
            return AttachedMobEntityReconnectionResult.rejected(
                    attachedMobAssignmentId,
                    entityId,
                    "Attached assignment does not belong to source "
                            + "composition "
                            + sourceCompositionId
                            + "."
            );
        }

        SourceGroupComposition.AttachedMobAssignment assignment =
                sourceComposition.getAttachedMobAssignment(
                        attachedMobAssignmentId
                );

        if (assignment == null) {
            return AttachedMobEntityReconnectionResult.rejected(
                    attachedMobAssignmentId,
                    entityId,
                    "Immutable source composition contains no matching "
                            + "attached assignment."
            );
        }

        AttachedMobEntityBindingState.BindingSnapshot bindingSnapshot =
                attachedMobEntityBindingState.getBinding(
                        attachedMobAssignmentId
                );

        if (bindingSnapshot == null) {
            return AttachedMobEntityReconnectionResult.rejected(
                    attachedMobAssignmentId,
                    entityId,
                    "Persistent runtime contains no entity binding for this "
                            + "attached assignment."
            );
        }

        if (!entityId.equals(
                bindingSnapshot.entityId()
        )) {
            return AttachedMobEntityReconnectionResult.rejected(
                    attachedMobAssignmentId,
                    entityId,
                    "Persistent runtime binds this assignment to entity "
                            + bindingSnapshot.entityId()
                            + " rather than loaded entity "
                            + entityId
                            + "."
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

        UUID entitySourceId =
                incursionOwnedMob.getSourceId();

        if (entitySourceId == null
                || !sourceExecutionState
                .getRuntimeSourceIdHistory()
                .contains(
                        entitySourceId
                )) {

            return AttachedMobEntityReconnectionResult.rejected(
                    attachedMobAssignmentId,
                    entityId,
                    "Entity source ID "
                            + formatOptionalUuid(
                            entitySourceId
                    )
                            + " does not belong to physical source placement "
                            + getSourcePlacementId()
                            + "."
            );
        }

        UUID plannedPackId =
                getPlannedPackId();

        if (!Objects.equals(
                plannedPackId,
                incursionOwnedMob.getPackId()
        )) {
            return AttachedMobEntityReconnectionResult.rejected(
                    attachedMobAssignmentId,
                    entityId,
                    "Entity Pack ID "
                            + formatOptionalUuid(
                            incursionOwnedMob.getPackId()
                    )
                            + " does not match planned Pack ID "
                            + formatOptionalUuid(
                            plannedPackId
                    )
                            + "."
            );
        }

        /*
         * Reapplying the modifier is intentionally idempotent. For the test
         * Pack leader this restores its marker and scale if another system or
         * an older save omitted part of the runtime presentation.
         */
        AttachedMobRuntimeModifierExecutor.apply(
                sourceGroupComposition,
                sourceComposition,
                assignment,
                assignment.mobId(),
                entity
        );

        validateInternalState();

        return AttachedMobEntityReconnectionResult.reconnected(
                attachedMobAssignmentId,
                entityId
        );
    }


    /**
     * Creates the physical source when it does not currently exist.
     *
     * This may recreate a source destroyed during an earlier wave.
     */
    public boolean ensureSourceAvailable(
            ServerLevel level,
            SourceState sourceState,
            LeadershipContext leadershipContext
    ) {
        return sourceExecutionState.createSource(
                level,
                sourceState,
                leadershipContext
        );
    }

    /**
     * Attempts one streamed spawn from this wave's queue.
     *
     * Attached assignments promote mobs already contained in the immutable
     * source composition. Selection therefore protects one remaining queue
     * unit for every unbound attachment and observes the authored priority:
     *
     * - BEFORE_ORDINARY attachments spawn first;
     * - WITH_ORDINARY attachments enter ordinary shuffled delivery;
     * - ordinary mobs spawn without consuming reserved attached units;
     * - AFTER_ORDINARY attachments spawn last.
     *
     * Queue progress is committed only after the actual entity has received
     * its attached modifier and persistence binding successfully.
     */
    public SourceExecutionState.SpawnAttemptResult
    attemptNextSpawn(
            ServerLevel level,
            LeadershipContext leadershipContext
    ) {
        if (level == null) {
            throw new IllegalArgumentException(
                    "Source-wave spawn level cannot be null."
            );
        }

        if (leadershipContext == null) {
            throw new IllegalArgumentException(
                    "Source-wave leadership context cannot be null."
            );
        }

        if (!sourceExecutionState.isAvailable()) {
            return SourceExecutionState
                    .SpawnAttemptResult
                    .SOURCE_UNAVAILABLE;
        }

        if (!spawnQueue.hasRemainingMobs()) {
            return SourceExecutionState
                    .SpawnAttemptResult
                    .QUEUE_EMPTY;
        }

        SpawnSelection spawnSelection =
                selectNextSpawn();

        LeadershipContext sourceLeadershipContext =
                createSourceLeadershipContext(
                        leadershipContext
                );

        SourceExecutionState.SpawnAttempt spawnAttempt =
                sourceExecutionState.attemptSpawnMob(
                        level,
                        spawnSelection.mobId(),
                        sourceLeadershipContext
                );

        if (!spawnAttempt.successfullySpawned()) {
            return spawnAttempt.result();
        }

        Entity spawnedEntity =
                spawnAttempt.spawnedEntity();

        try {
            if (spawnSelection.isAttachedAssignment()) {
                completeAttachedSpawn(
                        spawnSelection,
                        spawnedEntity
                );
            } else {
                completeOrdinarySpawn(
                        spawnSelection,
                        spawnedEntity
                );
            }
        } catch (RuntimeException exception) {
            /*
             * The queue is committed only at the end of each completion
             * method. Discarding the newly created entity prevents a failed
             * modifier or binding from introducing an unplanned extra mob.
             */
            if (!spawnedEntity.isRemoved()) {
                spawnedEntity.discard();
            }

            throw exception;
        }

        validateInternalState();

        return SourceExecutionState
                .SpawnAttemptResult
                .SPAWNED;
    }

    /**
     * Produces the exact leadership context belonging to this immutable
     * source composition.
     *
     * Pack ownership is planned by the parent SourceGroupComposition, but a
     * Pack represents the mobs delivered by the particular child source
     * composition identified by its PackAssignment.
     *
     * This ensures:
     *
     * - every mob from the Pack's source receives the Pack ID;
     * - the promoted Pack leader receives the same Pack ID before its modifier
     *   is applied;
     * - unrelated source compositions do not receive an unused Pack ID;
     * - a broader caller cannot accidentally impose a conflicting Pack.
     */
    private LeadershipContext createSourceLeadershipContext(
            LeadershipContext leadershipContext
    ) {
        if (leadershipContext == null) {
            throw new IllegalArgumentException(
                    "Base leadership context cannot be null."
            );
        }

        UUID plannedPackId =
                getPlannedPackId();

        UUID suppliedPackId =
                leadershipContext.packId();

        if (suppliedPackId != null
                && !Objects.equals(
                suppliedPackId,
                plannedPackId
        )) {

            throw new IllegalArgumentException(
                    "Source composition "
                            + sourceCompositionId
                            + " was supplied Pack ID "
                            + suppliedPackId
                            + ", but its immutable composition plans "
                            + formatOptionalUuid(
                            plannedPackId
                    )
                            + "."
            );
        }

        if (Objects.equals(
                suppliedPackId,
                plannedPackId
        )) {
            return leadershipContext;
        }

        return new LeadershipContext(
                leadershipContext.scenarioId(),
                leadershipContext.vermintideId(),
                leadershipContext.fangId(),
                leadershipContext.clawId(),
                plannedPackId
        );
    }

    /**
     * Returns the optional Pack ID planned specifically for this child source
     * composition.
     */
    private UUID getPlannedPackId() {
        SourceGroupComposition.PackAssignment packAssignment =
                sourceGroupComposition.getPackAssignment();

        if (packAssignment == null
                || !sourceCompositionId.equals(
                packAssignment.sourceCompositionId()
        )) {

            return null;
        }

        return packAssignment.packId();
    }

    private static String formatOptionalUuid(
            UUID value
    ) {
        return value == null
                ? "no Pack"
                : "Pack ID " + value;
    }

    /**
     * Chooses the next ordinary or attached mob without mutating runtime.
     */
    private SpawnSelection selectNextSpawn() {
        List<SourceGroupComposition.AttachedMobAssignment>
                unboundAssignments =
                getUnboundAttachedMobAssignments();

        Map<String, Integer> unboundAssignmentCountsByMobId =
                countAssignmentsByMobId(
                        unboundAssignments
                );

        validateRemainingAttachedReservations(
                unboundAssignmentCountsByMobId
        );

        SourceGroupComposition.AttachedMobAssignment
                beforeOrdinaryAssignment =
                findFirstAssignment(
                        unboundAssignments,
                        SourceGroupComposition
                                .AttachedMobSpawnPriority
                                .BEFORE_ORDINARY,
                        null
                );

        if (beforeOrdinaryAssignment != null) {
            return SpawnSelection.attached(
                    beforeOrdinaryAssignment
            );
        }

        Set<String> middlePhaseMobIds =
                new LinkedHashSet<>();

        /*
         * A mob is ordinary only when its remaining queue count exceeds the
         * number of still-unbound attachments reserving that mob ID.
         */
        for (String mobId
                : spawnQueue.getMobOrder()) {

            int remainingCount =
                    spawnQueue.getRemainingCount(
                            mobId
                    );

            int reservedAttachedCount =
                    unboundAssignmentCountsByMobId
                            .getOrDefault(
                                    mobId,
                                    0
                            );

            if (remainingCount
                    > reservedAttachedCount) {

                middlePhaseMobIds.add(
                        mobId
                );
            }
        }

        /*
         * WITH_ORDINARY assignments are eligible whenever their mob ID is
         * reached in the shuffled delivery sequence, even when no ordinary
         * unit of that same type remains.
         */
        for (SourceGroupComposition.AttachedMobAssignment assignment
                : unboundAssignments) {

            if (assignment.spawnPriority()
                    == SourceGroupComposition
                    .AttachedMobSpawnPriority
                    .WITH_ORDINARY) {

                middlePhaseMobIds.add(
                        assignment.mobId()
                );
            }
        }

        String middlePhaseMobId =
                spawnQueue.peekNextMobId(
                        middlePhaseMobIds
                );

        if (middlePhaseMobId != null) {
            SourceGroupComposition.AttachedMobAssignment
                    withOrdinaryAssignment =
                    findFirstAssignment(
                            unboundAssignments,
                            SourceGroupComposition
                                    .AttachedMobSpawnPriority
                                    .WITH_ORDINARY,
                            middlePhaseMobId
                    );

            if (withOrdinaryAssignment != null) {
                return SpawnSelection.attached(
                        withOrdinaryAssignment
                );
            }

            return SpawnSelection.ordinary(
                    middlePhaseMobId
            );
        }

        SourceGroupComposition.AttachedMobAssignment
                afterOrdinaryAssignment =
                findFirstAssignment(
                        unboundAssignments,
                        SourceGroupComposition
                                .AttachedMobSpawnPriority
                                .AFTER_ORDINARY,
                        null
                );

        if (afterOrdinaryAssignment != null) {
            return SpawnSelection.attached(
                    afterOrdinaryAssignment
            );
        }

        throw new IllegalStateException(
                "Source spawn queue for composition "
                        + sourceCompositionId
                        + " contains "
                        + spawnQueue.getRemainingMobCount()
                        + " remaining mobs, but runtime could not select an "
                        + "ordinary or attached assignment."
        );
    }

    /**
     * Commits one ordinary spawn after the entity has entered the world.
     */
    private void completeOrdinarySpawn(
            SpawnSelection spawnSelection,
            Entity spawnedEntity
    ) {
        if (spawnSelection.isAttachedAssignment()) {
            throw new IllegalArgumentException(
                    "Attached spawn selection cannot use ordinary completion."
            );
        }

        if (spawnedEntity == null
                || spawnedEntity.isRemoved()) {

            throw new IllegalArgumentException(
                    "Ordinary spawn completion requires a live entity."
            );
        }

        spawnQueue.markMobSpawned(
                spawnSelection.mobId()
        );

        successfulSpawnCount++;
    }

    /**
     * Applies, identifies, binds and commits one attached promoted mob.
     *
     * The stable assignment ID is stored on both:
     *
     * - the central source-wave binding state;
     * - the actual entity's ordinary Minecraft NBT.
     *
     * This allows later entity-load events to reconnect the two sides without
     * continuously polling the entity's position.
     */
    private void completeAttachedSpawn(
            SpawnSelection spawnSelection,
            Entity spawnedEntity
    ) {
        SourceGroupComposition.AttachedMobAssignment assignment =
                spawnSelection.attachedMobAssignment();

        if (assignment == null) {
            throw new IllegalArgumentException(
                    "Attached spawn completion requires an assignment."
            );
        }

        if (!(spawnedEntity
                instanceof AttachedMobAssignmentEntity
                attachedAssignmentEntity)) {

            throw new IllegalArgumentException(
                    "Entity type "
                            + spawnedEntity.getType()
                            + " cannot fulfil attached assignment "
                            + assignment.attachedMobAssignmentId()
                            + " because it does not implement "
                            + "AttachedMobAssignmentEntity."
            );
        }

        AttachedMobRuntimeModifierExecutor.apply(
                sourceGroupComposition,
                sourceComposition,
                assignment,
                spawnSelection.mobId(),
                spawnedEntity
        );

        UUID attachedMobAssignmentId =
                assignment.attachedMobAssignmentId();

        UUID existingEntityAssignmentId =
                attachedAssignmentEntity
                        .getAttachedMobAssignmentId();

        if (existingEntityAssignmentId != null
                && !existingEntityAssignmentId.equals(
                attachedMobAssignmentId
        )) {

            throw new IllegalStateException(
                    "Spawned entity "
                            + spawnedEntity.getUUID()
                            + " already identifies attached assignment "
                            + existingEntityAssignmentId
                            + " rather than planned assignment "
                            + attachedMobAssignmentId
                            + "."
            );
        }

        boolean entityIdentityAdded =
                existingEntityAssignmentId == null;

        if (entityIdentityAdded) {
            attachedAssignmentEntity
                    .setAttachedMobAssignmentId(
                            attachedMobAssignmentId
                    );
        }

        boolean newlyBound =
                false;

        try {
            newlyBound =
                    attachedMobEntityBindingState.bind(
                            attachedMobAssignmentId,
                            spawnedEntity
                    );

            if (!newlyBound) {
                throw new IllegalStateException(
                        "Attached assignment "
                                + attachedMobAssignmentId
                                + " was selected despite already having an "
                                + "entity binding."
                );
            }

            spawnQueue.markMobSpawned(
                    spawnSelection.mobId()
            );

            successfulSpawnCount++;
        } catch (RuntimeException exception) {
            if (newlyBound) {
                attachedMobEntityBindingState.removeBinding(
                        attachedMobAssignmentId
                );
            }

            if (entityIdentityAdded) {
                attachedAssignmentEntity
                        .setAttachedMobAssignmentId(
                                null
                        );
            }

            throw exception;
        }
    }

    private List<SourceGroupComposition.AttachedMobAssignment>
    getUnboundAttachedMobAssignments() {
        List<SourceGroupComposition.AttachedMobAssignment>
                unboundAssignments =
                new ArrayList<>();

        for (SourceGroupComposition.AttachedMobAssignment assignment
                : sourceComposition.getAttachedMobAssignments()) {

            if (!attachedMobEntityBindingState.isBound(
                    assignment.attachedMobAssignmentId()
            )) {
                unboundAssignments.add(
                        assignment
                );
            }
        }

        return List.copyOf(
                unboundAssignments
        );
    }

    private static Map<String, Integer> countAssignmentsByMobId(
            List<SourceGroupComposition.AttachedMobAssignment>
                    assignments
    ) {
        Map<String, Integer> assignmentCounts =
                new LinkedHashMap<>();

        for (SourceGroupComposition.AttachedMobAssignment assignment
                : assignments) {

            assignmentCounts.merge(
                    assignment.mobId(),
                    1,
                    Integer::sum
            );
        }

        return assignmentCounts;
    }

    /**
     * Ensures ordinary delivery has not consumed a queue unit reserved by an
     * unbound attached assignment.
     *
     * A cancelled assignment may remain unbound after the whole queue is
     * cancelled, so this invariant applies only while the wave assignment is
     * still delivering mobs.
     */
    private void validateRemainingAttachedReservations(
            Map<String, Integer> unboundAssignmentCountsByMobId
    ) {
        if (cancelled) {
            return;
        }

        for (Map.Entry<String, Integer> entry
                : unboundAssignmentCountsByMobId.entrySet()) {

            String mobId =
                    entry.getKey();

            int unboundAssignmentCount =
                    entry.getValue();

            int remainingCount =
                    spawnQueue.getRemainingCount(
                            mobId
                    );

            if (remainingCount
                    < unboundAssignmentCount) {

                throw new IllegalStateException(
                        "Source composition "
                                + sourceCompositionId
                                + " has "
                                + unboundAssignmentCount
                                + " unbound attached assignments reserving "
                                + mobId
                                + ", but only "
                                + remainingCount
                                + " such mobs remain in its spawn queue."
                );
            }
        }
    }

    private static SourceGroupComposition.AttachedMobAssignment
    findFirstAssignment(
            List<SourceGroupComposition.AttachedMobAssignment>
                    assignments,
            SourceGroupComposition.AttachedMobSpawnPriority spawnPriority,
            String requiredMobId
    ) {
        for (SourceGroupComposition.AttachedMobAssignment assignment
                : assignments) {

            if (assignment.spawnPriority()
                    != spawnPriority) {
                continue;
            }

            if (requiredMobId != null
                    && !requiredMobId.equals(
                    assignment.mobId()
            )) {
                continue;
            }

            return assignment;
        }

        return null;
    }

    private record SpawnSelection(
            String mobId,
            SourceGroupComposition.AttachedMobAssignment
            attachedMobAssignment
    ) {

        private SpawnSelection {
            if (mobId == null
                    || mobId.isBlank()) {

                throw new IllegalArgumentException(
                        "Spawn-selection mob ID cannot be blank."
                );
            }

            if (attachedMobAssignment != null
                    && !mobId.equals(
                    attachedMobAssignment.mobId()
            )) {

                throw new IllegalArgumentException(
                        "Attached spawn-selection mob ID does not match its "
                                + "assignment."
                );
            }
        }

        private static SpawnSelection ordinary(
                String mobId
        ) {
            return new SpawnSelection(
                    mobId,
                    null
            );
        }

        private static SpawnSelection attached(
                SourceGroupComposition.AttachedMobAssignment assignment
        ) {
            if (assignment == null) {
                throw new IllegalArgumentException(
                        "Attached spawn selection requires an assignment."
                );
            }

            return new SpawnSelection(
                    assignment.mobId(),
                    assignment
            );
        }

        private boolean isAttachedAssignment() {
            return attachedMobAssignment != null;
        }
    }

    /**
     * Handles destruction of the physical source during this wave.
     *
     * The persistent source location remains valid for later waves, but all
     * mobs still pending in this wave are cancelled.
     *
     * Returns the number of newly cancelled mobs.
     */
    public int handleSourceDestroyed() {
        sourceExecutionState.markDestroyed();

        return cancelRemainingMobs();
    }

    /**
     * Cancels this wave's remaining source assignment.
     *
     * Calling this after the queue has already completed does not
     * retroactively mark a successfully completed assignment as cancelled.
     */
    public int cancelRemainingMobs() {
        int cancelledNow =
                sourceExecutionState.cancelCurrentWave(
                        spawnQueue
                );

        if (cancelledNow > 0) {
            cancelled =
                    true;

            cancelledMobCount +=
                    cancelledNow;
        }

        validateInternalState();

        return cancelledNow;
    }

    /**
     * Captures complete mutable state for this wave-specific source
     * assignment.
     *
     * Parent source-group identity is not duplicated here. The immutable
     * IncursionPlan restores the exact parent group before this mutable state
     * is reconstructed.
     */
    public Snapshot createSnapshot() {
        return new Snapshot(
                waveIndex,
                getSourcePlacementId(),
                sourceCompositionId,
                plannedMobCount,
                successfulSpawnCount,
                cancelledMobCount,
                cancelled,
                spawnQueue.createSnapshot(),
                attachedMobEntityBindingState.createSnapshot()
        );
    }

    private void validateInternalState() {
        validatePlanBinding(
                waveIndex,
                sourceExecutionState,
                sourceGroupComposition,
                sourceComposition
        );

        if (!sourceCompositionId.equals(
                sourceComposition.getSourceCompositionId()
        )) {
            throw new IllegalStateException(
                    "Runtime source-composition identity does not match its "
                            + "immutable SourceComposition."
            );
        }

        if (!sourceCompositionId.equals(
                spawnQueue.getSourceCompositionId()
        )) {
            throw new IllegalStateException(
                    "Source spawn queue belongs to source composition "
                            + spawnQueue.getSourceCompositionId()
                            + " rather than "
                            + sourceCompositionId
                            + "."
            );
        }

        if (plannedMobCount
                != sourceComposition.getTotalMobCount()) {

            throw new IllegalStateException(
                    "Runtime planned mob count does not match immutable source "
                            + "composition "
                            + sourceCompositionId
                            + "."
            );
        }

        if (successfulSpawnCount < 0
                || cancelledMobCount < 0) {

            throw new IllegalStateException(
                    "Source-wave execution counts cannot be negative."
            );
        }

        int accountedMobCount =
                successfulSpawnCount
                        + cancelledMobCount
                        + spawnQueue.getRemainingMobCount();

        if (accountedMobCount
                != plannedMobCount) {

            throw new IllegalStateException(
                    "Source-wave execution accounts for "
                            + accountedMobCount
                            + " mobs, but immutable source composition "
                            + sourceCompositionId
                            + " planned "
                            + plannedMobCount
                            + "."
            );
        }

        if (cancelled
                != (cancelledMobCount > 0)) {

            throw new IllegalStateException(
                    "Source-wave cancellation flag does not match its "
                            + "cancelled mob count."
            );
        }

        if (attachedMobEntityBindingState
                .getBoundAssignmentCount()
                > successfulSpawnCount) {

            throw new IllegalStateException(
                    "Source-wave assignment contains more attached-mob "
                            + "entity bindings than successful spawns."
            );
        }

        for (SourceGroupComposition.AttachedMobAssignment assignment
                : sourceComposition.getAttachedMobAssignments()) {

            AttachedMobRuntimeModifierExecutor.validatePlanBinding(
                    sourceGroupComposition,
                    sourceComposition,
                    assignment
            );
        }

        validateRemainingAttachedReservations(
                countAssignmentsByMobId(
                        getUnboundAttachedMobAssignments()
                )
        );

        List<UUID> expectedAttachedAssignmentIds =
                getPlannedAttachedMobAssignmentIds(
                        sourceComposition
                );

        if (!expectedAttachedAssignmentIds.equals(
                attachedMobEntityBindingState
                        .getPlannedAssignmentIds()
        )) {
            throw new IllegalStateException(
                    "Attached-mob binding state does not use the immutable "
                            + "source composition's canonical assignment IDs."
            );
        }

        /*
         * Snapshot construction performs another complete immutable-state
         * accounting validation.
         */
        new Snapshot(
                waveIndex,
                getSourcePlacementId(),
                sourceCompositionId,
                plannedMobCount,
                successfulSpawnCount,
                cancelledMobCount,
                cancelled,
                spawnQueue.createSnapshot(),
                attachedMobEntityBindingState.createSnapshot()
        );
    }

    private static void validatePlanBinding(
            int waveIndex,
            SourceExecutionState sourceExecutionState,
            SourceGroupComposition sourceGroupComposition,
            SourceGroupComposition.SourceComposition sourceComposition
    ) {
        if (waveIndex < 0) {
            throw new IllegalArgumentException(
                    "Wave index cannot be negative."
            );
        }

        if (sourceExecutionState == null) {
            throw new IllegalArgumentException(
                    "Source execution state cannot be null."
            );
        }

        if (sourceGroupComposition == null) {
            throw new IllegalArgumentException(
                    "Source-group composition cannot be null."
            );
        }

        if (sourceComposition == null) {
            throw new IllegalArgumentException(
                    "Source composition cannot be null."
            );
        }

        SourceGroupComposition.SourceComposition containedComposition =
                sourceGroupComposition.getSourceComposition(
                        sourceComposition.getSourceCompositionId()
                );

        if (containedComposition == null) {
            throw new IllegalArgumentException(
                    "Source composition "
                            + sourceComposition.getSourceCompositionId()
                            + " does not belong to source-group composition "
                            + sourceGroupComposition
                            .getSourceGroupCompositionId()
                            + "."
            );
        }

        if (containedComposition != sourceComposition) {
            throw new IllegalArgumentException(
                    "Runtime source assignment is not backed by the exact "
                            + "immutable child SourceComposition belonging to "
                            + "source-group composition "
                            + sourceGroupComposition
                            .getSourceGroupCompositionId()
                            + "."
            );
        }

        SourcePlacementPlan sourcePlacementPlan =
                sourceExecutionState.getSourcePlacementPlan();

        UUID sourceCompositionId =
                sourceComposition.getSourceCompositionId();

        if (!sourcePlacementPlan.isBoundToSourceComposition(
                sourceCompositionId
        )) {
            throw new IllegalArgumentException(
                    "Source placement "
                            + sourcePlacementPlan.getSourcePlacementId()
                            + " is not bound to source composition "
                            + sourceCompositionId
                            + "."
            );
        }

        if (sourcePlacementPlan.getSourceType()
                != sourceComposition.getRequiredSourceType()) {

            throw new IllegalArgumentException(
                    "Source placement type does not match the source "
                            + "composition requirement."
            );
        }

        if (sourcePlacementPlan.getSourceRole()
                != sourceComposition.getSourceRole()) {

            throw new IllegalArgumentException(
                    "Source placement role does not match the source "
                            + "composition requirement."
            );
        }

        if (!sourcePlacementPlan
                .getSourceSize()
                .canFit(
                        sourceComposition.getRequiredSourceSize()
                )) {

            throw new IllegalArgumentException(
                    "Source placement is too small for the source "
                            + "composition requirement."
            );
        }
    }

    private static List<UUID>
    getPlannedAttachedMobAssignmentIds(
            SourceGroupComposition.SourceComposition sourceComposition
    ) {
        List<UUID> assignmentIds =
                new ArrayList<>();

        for (SourceGroupComposition.AttachedMobAssignment assignment
                : sourceComposition.getAttachedMobAssignments()) {

            if (assignment == null) {
                throw new IllegalArgumentException(
                        "Source composition "
                                + sourceComposition
                                .getSourceCompositionId()
                                + " contains a null attached-mob assignment."
                );
            }

            assignmentIds.add(
                    assignment.attachedMobAssignmentId()
            );
        }

        return List.copyOf(
                assignmentIds
        );
    }

    /**
     * Exact immutable persistence snapshot for one wave-specific source
     * assignment.
     */
    public record Snapshot(
            int waveIndex,
            UUID sourcePlacementId,
            UUID sourceCompositionId,
            int plannedMobCount,
            int successfulSpawnCount,
            int cancelledMobCount,
            boolean cancelled,
            SourceSpawnQueue.Snapshot spawnQueueSnapshot,
            AttachedMobEntityBindingState.Snapshot
            attachedMobEntityBindingSnapshot
    ) {

        public Snapshot {
            if (waveIndex < 0) {
                throw new IllegalArgumentException(
                        "Source-wave snapshot index cannot be negative."
                );
            }

            if (sourcePlacementId == null) {
                throw new IllegalArgumentException(
                        "Source-wave snapshot placement ID cannot be null."
                );
            }

            if (sourceCompositionId == null) {
                throw new IllegalArgumentException(
                        "Source-wave snapshot composition ID cannot be null."
                );
            }

            if (plannedMobCount <= 0) {
                throw new IllegalArgumentException(
                        "Source-wave snapshot planned mob count must be "
                                + "greater than zero."
                );
            }

            if (successfulSpawnCount < 0) {
                throw new IllegalArgumentException(
                        "Source-wave successful spawn count cannot be "
                                + "negative."
                );
            }

            if (cancelledMobCount < 0) {
                throw new IllegalArgumentException(
                        "Source-wave cancelled mob count cannot be negative."
                );
            }

            if (spawnQueueSnapshot == null) {
                throw new IllegalArgumentException(
                        "Source-wave snapshot requires a spawn-queue "
                                + "snapshot."
                );
            }

            if (attachedMobEntityBindingSnapshot == null) {
                throw new IllegalArgumentException(
                        "Source-wave snapshot requires an attached-mob "
                                + "entity-binding snapshot."
                );
            }

            if (!sourceCompositionId.equals(
                    spawnQueueSnapshot.sourceCompositionId()
            )) {
                throw new IllegalArgumentException(
                        "Source-wave snapshot and spawn queue belong to "
                                + "different source compositions."
                );
            }

            int accountedMobCount =
                    successfulSpawnCount
                            + cancelledMobCount
                            + spawnQueueSnapshot.remainingMobCount();

            if (accountedMobCount
                    != plannedMobCount) {

                throw new IllegalArgumentException(
                        "Source-wave snapshot accounts for "
                                + accountedMobCount
                                + " mobs, but records "
                                + plannedMobCount
                                + " planned mobs."
                );
            }

            if (cancelled
                    != (cancelledMobCount > 0)) {

                throw new IllegalArgumentException(
                        "Source-wave snapshot cancellation flag does not "
                                + "match its cancelled mob count."
                );
            }

            if (attachedMobEntityBindingSnapshot
                    .getBindingCount()
                    > successfulSpawnCount) {

                throw new IllegalArgumentException(
                        "Source-wave snapshot contains more attached-mob "
                                + "entity bindings than successful spawns."
                );
            }
        }

        public int getRemainingMobCount() {
            return spawnQueueSnapshot.remainingMobCount();
        }

        public boolean hasRemainingMobs() {
            return getRemainingMobCount() > 0;
        }

        public boolean isComplete() {
            return getRemainingMobCount() == 0;
        }

        public boolean completedNormally() {
            return isComplete()
                    && !cancelled;
        }

        public int getAttachedMobEntityBindingCount() {
            return attachedMobEntityBindingSnapshot
                    .getBindingCount();
        }
    }
}