package org.ratden.skavenblight.event.skavenIncursion.runtime.mob;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Persistent authority for mobs successfully delivered by one incursion.
 *
 * This state records mob identity and represented threat after an entity has
 * successfully entered the world.
 *
 * It does not record mobs still waiting in SourceSpawnQueue. Pending threat
 * remains authoritative in those queues.
 *
 * It also does not create records for cancelled queue entries. Cancelled
 * threat can be derived later from:
 *
 * planned threat - pending threat - successfully delivered threat
 *
 * Successfully delivered threat is divided into:
 *
 * - ACTIVE:
 *   the mob still contributes to the live battlefield;
 *
 * - DEFEATED:
 *   the mob was killed;
 *
 * - OTHER_TERMINAL_REMOVAL:
 *   the mob permanently left runtime for another reason.
 *
 * Ordinary chunk unloading is not terminal. An unloaded living mob remains
 * ACTIVE. A separate session-only registry will later maintain references to
 * currently loaded entities for dynamic chunk-ticket calculations.
 *
 * This class owns accounting state only. It does not:
 *
 * - decide wave progression;
 * - issue chunk tickets;
 * - display boss bars;
 * - search the world for entities;
 * - decide Director adaptation.
 *
 * Those systems consume summaries produced here.
 */
public class IncursionMobTrackingState {

    private final UUID incursionId;

    private final Map<UUID, MutableTrackedMob>
            trackedMobsByEntityId;

    private final MutableTotals totalTotals;

    private final TreeMap<Integer, MutableTotals>
            totalsByWaveIndex;

    /**
     * Creates an empty tracking state for a fresh incursion.
     */
    public IncursionMobTrackingState(
            UUID incursionId
    ) {
        if (incursionId == null) {
            throw new IllegalArgumentException(
                    "Incursion mob-tracking state requires an incursion ID."
            );
        }

        this.incursionId =
                incursionId;

        this.trackedMobsByEntityId =
                new LinkedHashMap<>();

        this.totalTotals =
                new MutableTotals();

        this.totalsByWaveIndex =
                new TreeMap<>();
    }

    /**
     * Restores an exact persistent mob-tracking snapshot.
     */
    public static IncursionMobTrackingState restore(
            Snapshot snapshot
    ) {
        if (snapshot == null) {
            throw new IllegalArgumentException(
                    "Incursion mob-tracking snapshot cannot be null."
            );
        }

        IncursionMobTrackingState restoredState =
                new IncursionMobTrackingState(
                        snapshot.incursionId()
                );

        for (TrackedMobSnapshot trackedMobSnapshot
                : snapshot.trackedMobs()) {

            restoredState.restoreTrackedMob(
                    trackedMobSnapshot
            );
        }

        Snapshot reconstructedSnapshot =
                restoredState.createSnapshot();

        if (!snapshot.equals(
                reconstructedSnapshot
        )) {
            throw new IllegalArgumentException(
                    "Restored incursion mob-tracking state does not exactly "
                            + "match its snapshot for incursion "
                            + snapshot.incursionId()
                            + "."
            );
        }

        return restoredState;
    }

    public UUID getIncursionId() {
        return incursionId;
    }

    public int getTrackedMobCount() {
        return trackedMobsByEntityId.size();
    }

    public boolean isEmpty() {
        return trackedMobsByEntityId.isEmpty();
    }

    public boolean containsEntity(
            UUID entityId
    ) {
        if (entityId == null) {
            return false;
        }

        return trackedMobsByEntityId.containsKey(
                entityId
        );
    }

    /**
     * Validates one proposed successful-spawn registration without changing
     * persistent tracking state.
     *
     * Source-wave runtime calls this before consuming the corresponding
     * SourceSpawnQueue delivery entry. Once this method succeeds, the normal
     * server-thread registration path has no expected validation or arithmetic
     * failure remaining.
     */
    public void validateSuccessfulSpawnRegistration(
            SpawnRegistration registration
    ) {
        if (registration == null) {
            throw new IllegalArgumentException(
                    "Successful mob-spawn registration cannot be null."
            );
        }

        /*
         * Confirm that the existing persistent graph is coherent before
         * preflighting another mutation.
         */
        validateAggregateCounts();

        if (trackedMobsByEntityId.containsKey(
                registration.entityId()
        )) {
            throw new IllegalArgumentException(
                    "Incursion "
                            + incursionId
                            + " already tracks entity "
                            + registration.entityId()
                            + "."
            );
        }

        int representedThreat =
                registration.representedThreat();

        /*
         * Preflight every arithmetic operation performed against the complete
         * incursion totals.
         */
        Math.addExact(
                totalTotals.spawnedMobCount,
                1
        );

        Math.addExact(
                totalTotals.spawnedThreat,
                representedThreat
        );

        Math.addExact(
                totalTotals.activeMobCount,
                1
        );

        Math.addExact(
                totalTotals.activeThreat,
                representedThreat
        );

        MutableTotals existingWaveTotals =
                totalsByWaveIndex.get(
                        registration.waveIndex()
                );

        if (existingWaveTotals != null) {
            existingWaveTotals.validate();

            /*
             * Preflight the same mutation against the affected wave totals.
             * A previously unseen wave begins from zero and therefore cannot
             * overflow while registering its first positive entry.
             */
            Math.addExact(
                    existingWaveTotals.spawnedMobCount,
                    1
            );

            Math.addExact(
                    existingWaveTotals.spawnedThreat,
                    representedThreat
            );

            Math.addExact(
                    existingWaveTotals.activeMobCount,
                    1
            );

            Math.addExact(
                    existingWaveTotals.activeThreat,
                    representedThreat
            );
        }
    }

    /**
     * Registers one entity only after it has successfully entered the world.
     *
     * The represented threat comes from the exact SourceSpawnQueue delivery
     * entry consumed by that successful spawn. It must not be recalculated
     * from the live mob catalogue.
     */
    public TrackedMobSnapshot registerSuccessfulSpawn(
            SpawnRegistration registration
    ) {
        validateSuccessfulSpawnRegistration(
                registration
        );

        MutableTrackedMob trackedMob =
                new MutableTrackedMob(
                        registration,
                        MobResolution.ACTIVE
                );

        trackedMobsByEntityId.put(
                registration.entityId(),
                trackedMob
        );

        totalTotals.registerSpawn(
                registration.representedThreat()
        );

        MutableTotals waveTotals =
                totalsByWaveIndex.computeIfAbsent(
                        registration.waveIndex(),
                        ignored -> new MutableTotals()
                );

        waveTotals.registerSpawn(
                registration.representedThreat()
        );

        validateAggregateCounts();

        return trackedMob.createSnapshot();
    }

    /**
     * Marks one tracked mob as defeated.
     *
     * ACTIVE mobs move to DEFEATED.
     *
     * OTHER_TERMINAL_REMOVAL may be upgraded to DEFEATED if a later lifecycle
     * event provides the more specific information that the entity actually
     * died.
     *
     * A mob already marked DEFEATED is unchanged.
     *
     * @return true when persistent tracking state changed
     */
    public boolean markDefeated(
            UUID entityId
    ) {
        MutableTrackedMob trackedMob =
                requireTrackedMob(
                        entityId
                );

        MobResolution previousResolution =
                trackedMob.getResolution();

        if (previousResolution
                == MobResolution.DEFEATED) {

            return false;
        }

        transitionResolution(
                trackedMob,
                MobResolution.DEFEATED
        );

        return true;
    }

    /**
     * Marks one active mob as permanently removed for a reason other than a
     * confirmed defeat.
     *
     * This must not be called for ordinary chunk unloading.
     *
     * A mob already in either terminal state remains unchanged. In
     * particular, a known defeat is never downgraded to a less specific
     * removal reason.
     *
     * @return true when persistent tracking state changed
     */
    public boolean markOtherTerminalRemoval(
            UUID entityId
    ) {
        MutableTrackedMob trackedMob =
                requireTrackedMob(
                        entityId
                );

        if (trackedMob.getResolution()
                != MobResolution.ACTIVE) {

            return false;
        }

        transitionResolution(
                trackedMob,
                MobResolution.OTHER_TERMINAL_REMOVAL
        );

        return true;
    }

    public TrackedMobSnapshot getTrackedMob(
            UUID entityId
    ) {
        if (entityId == null) {
            return null;
        }

        MutableTrackedMob trackedMob =
                trackedMobsByEntityId.get(
                        entityId
                );

        return trackedMob == null
                ? null
                : trackedMob.createSnapshot();
    }

    /**
     * Returns every tracked mob in stable successful-registration order.
     */
    public List<TrackedMobSnapshot> getTrackedMobs() {
        List<TrackedMobSnapshot> trackedMobs =
                new ArrayList<>(
                        trackedMobsByEntityId.size()
                );

        for (MutableTrackedMob trackedMob
                : trackedMobsByEntityId.values()) {

            trackedMobs.add(
                    trackedMob.createSnapshot()
            );
        }

        return List.copyOf(
                trackedMobs
        );
    }

    /**
     * Returns every mob still considered active.
     *
     * This is a persistent identity view, not a list of currently loaded
     * Entity objects.
     */
    public List<TrackedMobSnapshot> getActiveTrackedMobs() {
        List<TrackedMobSnapshot> activeMobs =
                new ArrayList<>();

        for (MutableTrackedMob trackedMob
                : trackedMobsByEntityId.values()) {

            if (trackedMob.getResolution()
                    == MobResolution.ACTIVE) {

                activeMobs.add(
                        trackedMob.createSnapshot()
                );
            }
        }

        return List.copyOf(
                activeMobs
        );
    }

    public Set<Integer> getTrackedWaveIndexes() {
        return Collections.unmodifiableSet(
                totalsByWaveIndex.navigableKeySet()
        );
    }

    public MobTrackingSummary getSummary() {
        return totalTotals.createSummary();
    }

    public MobTrackingSummary getWaveSummary(
            int waveIndex
    ) {
        if (waveIndex < 0) {
            throw new IllegalArgumentException(
                    "Wave index cannot be negative."
            );
        }

        MutableTotals waveTotals =
                totalsByWaveIndex.get(
                        waveIndex
                );

        if (waveTotals == null) {
            return MobTrackingSummary.empty();
        }

        return waveTotals.createSummary();
    }

    public int getSpawnedMobCount() {
        return totalTotals.spawnedMobCount;
    }

    public int getSpawnedThreat() {
        return totalTotals.spawnedThreat;
    }

    public int getActiveMobCount() {
        return totalTotals.activeMobCount;
    }

    public int getActiveThreat() {
        return totalTotals.activeThreat;
    }

    public int getDefeatedMobCount() {
        return totalTotals.defeatedMobCount;
    }

    public int getDefeatedThreat() {
        return totalTotals.defeatedThreat;
    }

    public int getOtherTerminalRemovalMobCount() {
        return totalTotals.otherTerminalRemovalMobCount;
    }

    public int getOtherTerminalRemovalThreat() {
        return totalTotals.otherTerminalRemovalThreat;
    }

    /**
     * Counts active mobs of one type across the complete incursion.
     */
    public int getActiveMobCount(
            String mobId
    ) {
        requireMobId(
                mobId
        );

        int activeCount =
                0;

        for (MutableTrackedMob trackedMob
                : trackedMobsByEntityId.values()) {

            if (trackedMob.getResolution()
                    == MobResolution.ACTIVE
                    && mobId.equals(
                    trackedMob.getMobId()
            )) {

                activeCount =
                        Math.addExact(
                                activeCount,
                                1
                        );
            }
        }

        return activeCount;
    }

    /**
     * Counts active mobs of one type in one wave.
     */
    public int getActiveMobCount(
            int waveIndex,
            String mobId
    ) {
        if (waveIndex < 0) {
            throw new IllegalArgumentException(
                    "Wave index cannot be negative."
            );
        }

        requireMobId(
                mobId
        );

        int activeCount =
                0;

        for (MutableTrackedMob trackedMob
                : trackedMobsByEntityId.values()) {

            if (trackedMob.getWaveIndex()
                    == waveIndex
                    && trackedMob.getResolution()
                    == MobResolution.ACTIVE
                    && mobId.equals(
                    trackedMob.getMobId()
            )) {

                activeCount =
                        Math.addExact(
                                activeCount,
                                1
                        );
            }
        }

        return activeCount;
    }

    /**
     * Captures complete persistent mob-tracking state.
     */
    public Snapshot createSnapshot() {
        List<TrackedMobSnapshot> trackedMobSnapshots =
                new ArrayList<>(
                        trackedMobsByEntityId.size()
                );

        for (MutableTrackedMob trackedMob
                : trackedMobsByEntityId.values()) {

            trackedMobSnapshots.add(
                    trackedMob.createSnapshot()
            );
        }

        return new Snapshot(
                incursionId,
                trackedMobSnapshots
        );
    }

    private void restoreTrackedMob(
            TrackedMobSnapshot snapshot
    ) {
        if (snapshot == null) {
            throw new IllegalArgumentException(
                    "Restored tracked-mob snapshot cannot be null."
            );
        }

        if (trackedMobsByEntityId.containsKey(
                snapshot.entityId()
        )) {
            throw new IllegalArgumentException(
                    "Mob-tracking snapshot contains duplicate entity ID "
                            + snapshot.entityId()
                            + "."
            );
        }

        SpawnRegistration registration =
                new SpawnRegistration(
                        snapshot.entityId(),
                        snapshot.mobId(),
                        snapshot.representedThreat(),
                        snapshot.waveIndex(),
                        snapshot.sourceGroupCompositionId(),
                        snapshot.sourceCompositionId(),
                        snapshot.sourcePlacementId(),
                        snapshot.runtimeSourceId(),
                        snapshot.attachedMobAssignmentId()
                );

        MutableTrackedMob restoredMob =
                new MutableTrackedMob(
                        registration,
                        snapshot.resolution()
                );

        trackedMobsByEntityId.put(
                snapshot.entityId(),
                restoredMob
        );

        totalTotals.restoreMob(
                snapshot.representedThreat(),
                snapshot.resolution()
        );

        MutableTotals waveTotals =
                totalsByWaveIndex.computeIfAbsent(
                        snapshot.waveIndex(),
                        ignored -> new MutableTotals()
                );

        waveTotals.restoreMob(
                snapshot.representedThreat(),
                snapshot.resolution()
        );

        validateAggregateCounts();
    }

    private MutableTrackedMob requireTrackedMob(
            UUID entityId
    ) {
        if (entityId == null) {
            throw new IllegalArgumentException(
                    "Tracked entity ID cannot be null."
            );
        }

        MutableTrackedMob trackedMob =
                trackedMobsByEntityId.get(
                        entityId
                );

        if (trackedMob == null) {
            throw new IllegalArgumentException(
                    "Incursion "
                            + incursionId
                            + " does not track entity "
                            + entityId
                            + "."
            );
        }

        return trackedMob;
    }

    private void transitionResolution(
            MutableTrackedMob trackedMob,
            MobResolution newResolution
    ) {
        if (trackedMob == null) {
            throw new IllegalArgumentException(
                    "Tracked mob cannot be null."
            );
        }

        if (newResolution == null) {
            throw new IllegalArgumentException(
                    "New mob resolution cannot be null."
            );
        }

        if (newResolution == MobResolution.ACTIVE) {
            throw new IllegalArgumentException(
                    "A terminal mob cannot be returned to ACTIVE through "
                            + "resolution transition."
            );
        }

        MobResolution previousResolution =
                trackedMob.getResolution();

        if (previousResolution == newResolution) {
            return;
        }

        if (previousResolution == MobResolution.DEFEATED
                && newResolution
                == MobResolution.OTHER_TERMINAL_REMOVAL) {

            return;
        }

        int representedThreat =
                trackedMob.getRepresentedThreat();

        totalTotals.transition(
                representedThreat,
                previousResolution,
                newResolution
        );

        MutableTotals waveTotals =
                totalsByWaveIndex.get(
                        trackedMob.getWaveIndex()
                );

        if (waveTotals == null) {
            throw new IllegalStateException(
                    "Tracked mob "
                            + trackedMob.getEntityId()
                            + " belongs to wave "
                            + trackedMob.getWaveIndex()
                            + " with no aggregate tracking totals."
            );
        }

        waveTotals.transition(
                representedThreat,
                previousResolution,
                newResolution
        );

        trackedMob.setResolution(
                newResolution
        );

        validateAggregateCounts();
    }

    private void validateAggregateCounts() {
        if (trackedMobsByEntityId.size()
                != totalTotals.spawnedMobCount) {

            throw new IllegalStateException(
                    "Incursion mob-tracking state contains "
                            + trackedMobsByEntityId.size()
                            + " entity records, but aggregate totals report "
                            + totalTotals.spawnedMobCount
                            + " spawned mobs."
            );
        }

        int countedWaveMobs =
                0;

        int countedWaveThreat =
                0;

        for (Map.Entry<Integer, MutableTotals> entry
                : totalsByWaveIndex.entrySet()) {

            if (entry.getKey() == null
                    || entry.getKey() < 0) {

                throw new IllegalStateException(
                        "Incursion mob tracking contains an invalid wave "
                                + "aggregate key."
                );
            }

            entry.getValue().validate();

            countedWaveMobs =
                    Math.addExact(
                            countedWaveMobs,
                            entry.getValue().spawnedMobCount
                    );

            countedWaveThreat =
                    Math.addExact(
                            countedWaveThreat,
                            entry.getValue().spawnedThreat
                    );
        }

        if (countedWaveMobs
                != totalTotals.spawnedMobCount) {

            throw new IllegalStateException(
                    "Per-wave mob totals report "
                            + countedWaveMobs
                            + " spawned mobs, but incursion totals report "
                            + totalTotals.spawnedMobCount
                            + "."
            );
        }

        if (countedWaveThreat
                != totalTotals.spawnedThreat) {

            throw new IllegalStateException(
                    "Per-wave threat totals report "
                            + countedWaveThreat
                            + " spawned threat, but incursion totals report "
                            + totalTotals.spawnedThreat
                            + "."
            );
        }

        totalTotals.validate();
    }

    private static void requireMobId(
            String mobId
    ) {
        if (mobId == null
                || mobId.isBlank()) {

            throw new IllegalArgumentException(
                    "Tracked mob ID cannot be blank."
            );
        }
    }

    /**
     * Data required when one successfully spawned entity enters persistent
     * mob tracking.
     *
     * attachedMobAssignmentId is optional because ordinary mobs have no
     * attached assignment.
     */
    public record SpawnRegistration(
            UUID entityId,
            String mobId,
            int representedThreat,
            int waveIndex,
            UUID sourceGroupCompositionId,
            UUID sourceCompositionId,
            UUID sourcePlacementId,
            UUID runtimeSourceId,
            UUID attachedMobAssignmentId
    ) {

        public SpawnRegistration {
            if (entityId == null) {
                throw new IllegalArgumentException(
                        "Spawn registration entity ID cannot be null."
                );
            }

            requireMobId(
                    mobId
            );

            if (representedThreat <= 0) {
                throw new IllegalArgumentException(
                        "Spawn registration represented threat must be "
                                + "greater than zero."
                );
            }

            if (waveIndex < 0) {
                throw new IllegalArgumentException(
                        "Spawn registration wave index cannot be negative."
                );
            }

            if (sourceGroupCompositionId == null) {
                throw new IllegalArgumentException(
                        "Spawn registration source-group composition ID "
                                + "cannot be null."
                );
            }

            if (sourceCompositionId == null) {
                throw new IllegalArgumentException(
                        "Spawn registration source-composition ID cannot be "
                                + "null."
                );
            }

            if (sourcePlacementId == null) {
                throw new IllegalArgumentException(
                        "Spawn registration source-placement ID cannot be "
                                + "null."
                );
            }

            if (runtimeSourceId == null) {
                throw new IllegalArgumentException(
                        "Spawn registration runtime-source ID cannot be null."
                );
            }
        }
    }

    /**
     * Persistent lifecycle state for one successfully delivered mob.
     */
    public enum MobResolution {

        /**
         * The mob still contributes to the battlefield.
         *
         * This remains true while the mob is temporarily unloaded.
         */
        ACTIVE,

        /**
         * The mob was confirmed killed.
         */
        DEFEATED,

        /**
         * The mob permanently left the incursion for another reason.
         *
         * Ordinary chunk unloading must not use this state.
         */
        OTHER_TERMINAL_REMOVAL
    }

    /**
     * Immutable persistent record for one successfully delivered mob.
     */
    public record TrackedMobSnapshot(
            UUID entityId,
            String mobId,
            int representedThreat,
            int waveIndex,
            UUID sourceGroupCompositionId,
            UUID sourceCompositionId,
            UUID sourcePlacementId,
            UUID runtimeSourceId,
            UUID attachedMobAssignmentId,
            MobResolution resolution
    ) {

        public TrackedMobSnapshot {
            if (entityId == null) {
                throw new IllegalArgumentException(
                        "Tracked-mob entity ID cannot be null."
                );
            }

            requireMobId(
                    mobId
            );

            if (representedThreat <= 0) {
                throw new IllegalArgumentException(
                        "Tracked-mob represented threat must be greater than "
                                + "zero."
                );
            }

            if (waveIndex < 0) {
                throw new IllegalArgumentException(
                        "Tracked-mob wave index cannot be negative."
                );
            }

            if (sourceGroupCompositionId == null) {
                throw new IllegalArgumentException(
                        "Tracked-mob source-group composition ID cannot be "
                                + "null."
                );
            }

            if (sourceCompositionId == null) {
                throw new IllegalArgumentException(
                        "Tracked-mob source-composition ID cannot be null."
                );
            }

            if (sourcePlacementId == null) {
                throw new IllegalArgumentException(
                        "Tracked-mob source-placement ID cannot be null."
                );
            }

            if (runtimeSourceId == null) {
                throw new IllegalArgumentException(
                        "Tracked-mob runtime-source ID cannot be null."
                );
            }

            if (resolution == null) {
                throw new IllegalArgumentException(
                        "Tracked-mob resolution cannot be null."
                );
            }
        }

        public boolean isActive() {
            return resolution == MobResolution.ACTIVE;
        }

        public boolean isDefeated() {
            return resolution == MobResolution.DEFEATED;
        }

        public boolean isTerminal() {
            return resolution != MobResolution.ACTIVE;
        }

        public boolean hasAttachedMobAssignment() {
            return attachedMobAssignmentId != null;
        }
    }

    /**
     * Immutable accounting summary for either one wave or the complete
     * incursion.
     */
    public record MobTrackingSummary(
            int spawnedMobCount,
            int spawnedThreat,
            int activeMobCount,
            int activeThreat,
            int defeatedMobCount,
            int defeatedThreat,
            int otherTerminalRemovalMobCount,
            int otherTerminalRemovalThreat
    ) {

        public MobTrackingSummary {
            if (spawnedMobCount < 0
                    || spawnedThreat < 0
                    || activeMobCount < 0
                    || activeThreat < 0
                    || defeatedMobCount < 0
                    || defeatedThreat < 0
                    || otherTerminalRemovalMobCount < 0
                    || otherTerminalRemovalThreat < 0) {

                throw new IllegalArgumentException(
                        "Mob-tracking summary values cannot be negative."
                );
            }

            int resolvedMobCount =
                    Math.addExact(
                            activeMobCount,
                            Math.addExact(
                                    defeatedMobCount,
                                    otherTerminalRemovalMobCount
                            )
                    );

            if (spawnedMobCount
                    != resolvedMobCount) {

                throw new IllegalArgumentException(
                        "Mob-tracking summary reports "
                                + spawnedMobCount
                                + " spawned mobs, but its lifecycle categories "
                                + "contain "
                                + resolvedMobCount
                                + "."
                );
            }

            int resolvedThreat =
                    Math.addExact(
                            activeThreat,
                            Math.addExact(
                                    defeatedThreat,
                                    otherTerminalRemovalThreat
                            )
                    );

            if (spawnedThreat
                    != resolvedThreat) {

                throw new IllegalArgumentException(
                        "Mob-tracking summary reports "
                                + spawnedThreat
                                + " spawned threat, but its lifecycle "
                                + "categories contain "
                                + resolvedThreat
                                + "."
                );
            }
        }

        public static MobTrackingSummary empty() {
            return new MobTrackingSummary(
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0
            );
        }

        public int getTerminalMobCount() {
            return Math.addExact(
                    defeatedMobCount,
                    otherTerminalRemovalMobCount
            );
        }

        public int getTerminalThreat() {
            return Math.addExact(
                    defeatedThreat,
                    otherTerminalRemovalThreat
            );
        }
    }

    /**
     * Exact persistent snapshot for one incursion's tracked mobs.
     */
    public record Snapshot(
            UUID incursionId,
            List<TrackedMobSnapshot> trackedMobs
    ) {

        public Snapshot {
            if (incursionId == null) {
                throw new IllegalArgumentException(
                        "Mob-tracking snapshot incursion ID cannot be null."
                );
            }

            if (trackedMobs == null) {
                throw new IllegalArgumentException(
                        "Mob-tracking snapshot records cannot be null."
                );
            }

            trackedMobs =
                    List.copyOf(
                            trackedMobs
                    );

            Set<UUID> entityIds =
                    new HashSet<>();

            for (TrackedMobSnapshot trackedMob
                    : trackedMobs) {

                if (trackedMob == null) {
                    throw new IllegalArgumentException(
                            "Mob-tracking snapshot cannot contain a null mob "
                                    + "record."
                    );
                }

                if (!entityIds.add(
                        trackedMob.entityId()
                )) {
                    throw new IllegalArgumentException(
                            "Mob-tracking snapshot contains duplicate entity "
                                    + "ID "
                                    + trackedMob.entityId()
                                    + "."
                    );
                }
            }
        }

        public int getTrackedMobCount() {
            return trackedMobs.size();
        }

        public boolean isEmpty() {
            return trackedMobs.isEmpty();
        }
    }

    /**
     * Mutable internal record. Immutable snapshots are exposed externally.
     */
    private static class MutableTrackedMob {

        private final UUID entityId;
        private final String mobId;
        private final int representedThreat;
        private final int waveIndex;
        private final UUID sourceGroupCompositionId;
        private final UUID sourceCompositionId;
        private final UUID sourcePlacementId;
        private final UUID runtimeSourceId;
        private final UUID attachedMobAssignmentId;

        private MobResolution resolution;

        private MutableTrackedMob(
                SpawnRegistration registration,
                MobResolution resolution
        ) {
            if (registration == null) {
                throw new IllegalArgumentException(
                        "Mutable tracked mob requires spawn registration."
                );
            }

            if (resolution == null) {
                throw new IllegalArgumentException(
                        "Mutable tracked mob requires a resolution."
                );
            }

            this.entityId =
                    registration.entityId();

            this.mobId =
                    registration.mobId();

            this.representedThreat =
                    registration.representedThreat();

            this.waveIndex =
                    registration.waveIndex();

            this.sourceGroupCompositionId =
                    registration.sourceGroupCompositionId();

            this.sourceCompositionId =
                    registration.sourceCompositionId();

            this.sourcePlacementId =
                    registration.sourcePlacementId();

            this.runtimeSourceId =
                    registration.runtimeSourceId();

            this.attachedMobAssignmentId =
                    registration.attachedMobAssignmentId();

            this.resolution =
                    resolution;
        }

        private UUID getEntityId() {
            return entityId;
        }

        private String getMobId() {
            return mobId;
        }

        private int getRepresentedThreat() {
            return representedThreat;
        }

        private int getWaveIndex() {
            return waveIndex;
        }

        private MobResolution getResolution() {
            return resolution;
        }

        private void setResolution(
                MobResolution resolution
        ) {
            if (resolution == null) {
                throw new IllegalArgumentException(
                        "Tracked-mob resolution cannot be null."
                );
            }

            this.resolution =
                    resolution;
        }

        private TrackedMobSnapshot createSnapshot() {
            return new TrackedMobSnapshot(
                    entityId,
                    mobId,
                    representedThreat,
                    waveIndex,
                    sourceGroupCompositionId,
                    sourceCompositionId,
                    sourcePlacementId,
                    runtimeSourceId,
                    attachedMobAssignmentId,
                    resolution
            );
        }
    }

    /**
     * Cached event-driven totals.
     *
     * Registering a spawn or resolving one mob is O(1). Summaries therefore
     * do not require repeatedly scanning every tracked entity.
     */
    private static class MutableTotals {

        private int spawnedMobCount;
        private int spawnedThreat;

        private int activeMobCount;
        private int activeThreat;

        private int defeatedMobCount;
        private int defeatedThreat;

        private int otherTerminalRemovalMobCount;
        private int otherTerminalRemovalThreat;

        private MutableTotals() {
            this.spawnedMobCount =
                    0;

            this.spawnedThreat =
                    0;

            this.activeMobCount =
                    0;

            this.activeThreat =
                    0;

            this.defeatedMobCount =
                    0;

            this.defeatedThreat =
                    0;

            this.otherTerminalRemovalMobCount =
                    0;

            this.otherTerminalRemovalThreat =
                    0;
        }

        private void registerSpawn(
                int representedThreat
        ) {
            if (representedThreat <= 0) {
                throw new IllegalArgumentException(
                        "Registered represented threat must be greater than "
                                + "zero."
                );
            }

            spawnedMobCount =
                    Math.addExact(
                            spawnedMobCount,
                            1
                    );

            spawnedThreat =
                    Math.addExact(
                            spawnedThreat,
                            representedThreat
                    );

            activeMobCount =
                    Math.addExact(
                            activeMobCount,
                            1
                    );

            activeThreat =
                    Math.addExact(
                            activeThreat,
                            representedThreat
                    );

            validate();
        }

        private void restoreMob(
                int representedThreat,
                MobResolution resolution
        ) {
            registerSpawn(
                    representedThreat
            );

            if (resolution != MobResolution.ACTIVE) {
                transition(
                        representedThreat,
                        MobResolution.ACTIVE,
                        resolution
                );
            }
        }

        private void transition(
                int representedThreat,
                MobResolution previousResolution,
                MobResolution newResolution
        ) {
            if (representedThreat <= 0) {
                throw new IllegalArgumentException(
                        "Transition represented threat must be greater than "
                                + "zero."
                );
            }

            if (previousResolution == null
                    || newResolution == null) {

                throw new IllegalArgumentException(
                        "Mob resolution transition values cannot be null."
                );
            }

            if (previousResolution == newResolution) {
                return;
            }

            removeFromResolution(
                    representedThreat,
                    previousResolution
            );

            addToResolution(
                    representedThreat,
                    newResolution
            );

            validate();
        }

        private void removeFromResolution(
                int representedThreat,
                MobResolution resolution
        ) {
            switch (resolution) {
                case ACTIVE -> {
                    activeMobCount =
                            Math.subtractExact(
                                    activeMobCount,
                                    1
                            );

                    activeThreat =
                            Math.subtractExact(
                                    activeThreat,
                                    representedThreat
                            );
                }

                case DEFEATED -> {
                    defeatedMobCount =
                            Math.subtractExact(
                                    defeatedMobCount,
                                    1
                            );

                    defeatedThreat =
                            Math.subtractExact(
                                    defeatedThreat,
                                    representedThreat
                            );
                }

                case OTHER_TERMINAL_REMOVAL -> {
                    otherTerminalRemovalMobCount =
                            Math.subtractExact(
                                    otherTerminalRemovalMobCount,
                                    1
                            );

                    otherTerminalRemovalThreat =
                            Math.subtractExact(
                                    otherTerminalRemovalThreat,
                                    representedThreat
                            );
                }
            }
        }

        private void addToResolution(
                int representedThreat,
                MobResolution resolution
        ) {
            switch (resolution) {
                case ACTIVE -> {
                    activeMobCount =
                            Math.addExact(
                                    activeMobCount,
                                    1
                            );

                    activeThreat =
                            Math.addExact(
                                    activeThreat,
                                    representedThreat
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
                                    representedThreat
                            );
                }

                case OTHER_TERMINAL_REMOVAL -> {
                    otherTerminalRemovalMobCount =
                            Math.addExact(
                                    otherTerminalRemovalMobCount,
                                    1
                            );

                    otherTerminalRemovalThreat =
                            Math.addExact(
                                    otherTerminalRemovalThreat,
                                    representedThreat
                            );
                }
            }
        }

        private MobTrackingSummary createSummary() {
            return new MobTrackingSummary(
                    spawnedMobCount,
                    spawnedThreat,
                    activeMobCount,
                    activeThreat,
                    defeatedMobCount,
                    defeatedThreat,
                    otherTerminalRemovalMobCount,
                    otherTerminalRemovalThreat
            );
        }

        private void validate() {
            if (spawnedMobCount < 0
                    || spawnedThreat < 0
                    || activeMobCount < 0
                    || activeThreat < 0
                    || defeatedMobCount < 0
                    || defeatedThreat < 0
                    || otherTerminalRemovalMobCount < 0
                    || otherTerminalRemovalThreat < 0) {

                throw new IllegalStateException(
                        "Mob-tracking aggregate values cannot be negative."
                );
            }

            int accountedMobCount =
                    Math.addExact(
                            activeMobCount,
                            Math.addExact(
                                    defeatedMobCount,
                                    otherTerminalRemovalMobCount
                            )
                    );

            if (accountedMobCount
                    != spawnedMobCount) {

                throw new IllegalStateException(
                        "Mob-tracking totals account for "
                                + accountedMobCount
                                + " mobs, but record "
                                + spawnedMobCount
                                + " successful spawns."
                );
            }

            int accountedThreat =
                    Math.addExact(
                            activeThreat,
                            Math.addExact(
                                    defeatedThreat,
                                    otherTerminalRemovalThreat
                            )
                    );

            if (accountedThreat
                    != spawnedThreat) {

                throw new IllegalStateException(
                        "Mob-tracking totals account for "
                                + accountedThreat
                                + " threat, but record "
                                + spawnedThreat
                                + " successfully delivered threat."
                );
            }
        }
    }
}