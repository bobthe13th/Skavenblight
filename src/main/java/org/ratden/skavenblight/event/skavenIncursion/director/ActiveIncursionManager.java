package org.ratden.skavenblight.event.skavenIncursion.director;

import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.ratden.skavenblight.event.skavenIncursion.debug.DebugIncursionAnchorTracker;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.ActiveIncursionSourceReservationRegistry;
import org.ratden.skavenblight.event.skavenIncursion.runtime.event.SourceDestroyedEvent;
import org.ratden.skavenblight.event.skavenIncursion.runtime.persistence.LivePersistentIncursion;
import org.ratden.skavenblight.event.skavenIncursion.runtime.persistence.PersistentIncursionSnapshot;
import org.ratden.skavenblight.event.skavenIncursion.runtime.persistence.SkavenIncursionSavedData;
import org.ratden.skavenblight.event.skavenIncursion.scenario.ScenarioGoal;
import org.ratden.skavenblight.event.skavenIncursion.scenario.ScenarioPattern;
import org.ratden.skavenblight.event.skavenIncursion.scenario.SkavenScenario;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server-runtime owner of admitted Skaven incursions.
 *
 * Two runtime paths temporarily coexist:
 *
 * - legacy Scenarios are stored directly and have no SavedData record;
 * - persistence-aware planned Scenarios are stored inside
 *   LivePersistentIncursion and synchronised with
 *   SkavenIncursionSavedData.
 *
 * Persistence-aware incursions are keyed by their canonical incursion UUID.
 * Legacy Scenarios remain list-based until they are migrated onto the
 * planning-aware runtime.
 *
 * All methods are expected to be called from the logical server thread.
 */
public final class ActiveIncursionManager {

    /**
     * Temporary legacy runtime collection.
     *
     * These Scenarios continue to use the original lifecycle: tick until
     * finished, then remove immediately.
     */
    private static final List<SkavenScenario>
            LEGACY_ACTIVE_INCURSIONS =
            new ArrayList<>();

    /**
     * Persistence-aware planned incursions in deterministic admission order.
     */
    private static final Map<UUID, LivePersistentIncursion>
            PERSISTENT_INCURSIONS_BY_ID =
            new LinkedHashMap<>();

    /**
     * Adds a legacy Scenario to the runtime manager.
     *
     */
    public static void addIncursion(
            SkavenScenario incursion
    ) {
        requireScenario(
                incursion
        );

        UUID incursionId =
                requireScenarioInstanceId(
                        incursion
                );

        if (containsManagedIncursionId(
                incursionId
        )) {
            throw new IllegalStateException(
                    "An incursion using ID "
                            + incursionId
                            + " is already managed."
            );
        }

        LEGACY_ACTIVE_INCURSIONS.add(
                incursion
        );
    }

    /**
     * Admits a fresh persistence-aware planned incursion.
     *
     * Admission is transactional:
     *
     * 1. capture the complete persistent snapshot;
     * 2. add it to the level's SavedData;
     * 3. add the live owner to the runtime map.
     *
     * If live-map admission unexpectedly fails, the newly added SavedData
     * record is removed again.
     */
    public static void addPersistentIncursion(
            LivePersistentIncursion incursion
    ) {
        requirePersistentIncursion(
                incursion
        );

        UUID incursionId =
                incursion.getIncursionId();

        if (containsManagedIncursionId(
                incursionId
        )) {
            throw new IllegalStateException(
                    "An incursion using ID "
                            + incursionId
                            + " is already managed."
            );
        }

        PersistentIncursionSnapshot snapshot =
                incursion.createPersistentSnapshot();

        if (!snapshot.isTickable()) {
            throw new IllegalArgumentException(
                    "A freshly admitted persistent incursion must begin in "
                            + "the ACTIVE phase."
            );
        }

        SkavenIncursionSavedData savedData =
                SkavenIncursionSavedData.get(
                        incursion.getLevel()
                );

        if (savedData.containsIncursion(
                incursionId
        )) {
            throw new IllegalStateException(
                    "Persistent SavedData already contains incursion "
                            + incursionId
                            + "."
            );
        }

        savedData.addSnapshot(
                snapshot
        );

        LivePersistentIncursion previousIncursion =
                PERSISTENT_INCURSIONS_BY_ID.putIfAbsent(
                        incursionId,
                        incursion
                );

        if (previousIncursion != null) {
            /*
             * This should be unreachable on the logical server thread because
             * duplicate identity was checked immediately before admission.
             */
            savedData.removeSnapshot(
                    incursionId
            );

            throw new IllegalStateException(
                    "Persistent incursion "
                            + incursionId
                            + " entered SavedData but could not enter the "
                            + "live runtime map."
            );
        }
    }

    /**
     * Reattaches a logically restored incursion to an existing SavedData
     * record.
     *
     * The supplied live owner must reproduce the stored record exactly.
     * Unlike fresh admission, this method does not add another SavedData
     * snapshot.
     */
    public static void attachRestoredPersistentIncursion(
            LivePersistentIncursion incursion
    ) {
        requirePersistentIncursion(
                incursion
        );

        UUID incursionId =
                incursion.getIncursionId();

        if (containsManagedIncursionId(
                incursionId
        )) {
            throw new IllegalStateException(
                    "An incursion using ID "
                            + incursionId
                            + " is already managed."
            );
        }

        SkavenIncursionSavedData savedData =
                SkavenIncursionSavedData.get(
                        incursion.getLevel()
                );

        PersistentIncursionSnapshot storedSnapshot =
                savedData.getSnapshot(
                        incursionId
                );

        if (storedSnapshot == null) {
            throw new IllegalStateException(
                    "Cannot attach restored incursion "
                            + incursionId
                            + " because its SavedData record is absent."
            );
        }

        PersistentIncursionSnapshot reconstructedSnapshot =
                incursion.createPersistentSnapshot();

        if (!storedSnapshot.equals(
                reconstructedSnapshot
        )) {
            throw new IllegalArgumentException(
                    "Restored live incursion "
                            + incursionId
                            + " does not exactly match its SavedData record."
            );
        }

        PERSISTENT_INCURSIONS_BY_ID.put(
                incursionId,
                incursion
        );
    }

    /**
     * Routes a source-destruction event to its owning Scenario.
     *
     * Persistent incursions are resolved directly by UUID. Their changed
     * runtime state is synchronised with SavedData immediately after the
     * event is accepted.
     *
     * Legacy Scenarios retain the previous list-search route.
     *
     * @return true when an owning managed Scenario accepted the event
     */
    public static boolean reportSourceDestroyed(
            SourceDestroyedEvent event
    ) {
        if (event == null) {
            throw new IllegalArgumentException(
                    "Source destruction event cannot be null."
            );
        }

        UUID incursionId =
                event.scenarioInstanceId();

        LivePersistentIncursion persistentIncursion =
                PERSISTENT_INCURSIONS_BY_ID.get(
                        incursionId
                );

        if (persistentIncursion != null) {
            boolean accepted =
                    persistentIncursion.handleSourceDestroyed(
                            event
                    );

            if (accepted) {
                synchronisePersistentSnapshot(
                        persistentIncursion
                );
            }

            return accepted;
        }

        for (SkavenScenario incursion
                : LEGACY_ACTIVE_INCURSIONS) {

            if (!incursionId.equals(
                    incursion.getInstanceId()
            )) {
                continue;
            }

            incursion.onSourceDestroyed(
                    event
            );

            return true;
        }

        return false;
    }

    /**
     * Advances both temporary legacy runtime and persistence-aware runtime.
     */
    public static void onServerTick(
            ServerTickEvent.Post event
    ) {
        if (event == null) {
            throw new IllegalArgumentException(
                    "Server tick event cannot be null."
            );
        }

        tickLegacyIncursions();
        tickPersistentIncursions();
    }

    /**
     * Explicitly clears all currently attached live incursions.
     *
     * Persistent cleanup removes:
     *
     * - tracked debug anchors;
     * - active physical reservation snapshots;
     * - the SavedData record;
     * - the live manager entry.
     *
     * This does not remove spawned mobs or arbitrary source blocks from the
     * world. Those remain separate debug or authored cleanup responsibilities.
     *
     * @return number of live manager entries removed
     */
    public static int clearIncursions() {
        int removedCount =
                LEGACY_ACTIVE_INCURSIONS.size()
                        + PERSISTENT_INCURSIONS_BY_ID.size();

        LEGACY_ACTIVE_INCURSIONS.clear();

        for (LivePersistentIncursion persistentIncursion
                : PERSISTENT_INCURSIONS_BY_ID.values()) {

            removePersistentWorldState(
                    persistentIncursion
            );
        }

        PERSISTENT_INCURSIONS_BY_ID.clear();

        return removedCount;
    }

    /**
     * Captures one final persistent snapshot for every live persistent
     * incursion belonging to the supplied server.
     *
     * This is intended for orderly shutdown before live runtime objects are
     * detached. Each incursion is attempted independently so one malformed
     * runtime does not prevent valid incursions from checkpointing.
     *
     * Legacy Scenarios are excluded because they have no persistent record.
     */
    public static CheckpointReport checkpointAllPersistentIncursions(
            MinecraftServer server
    ) {
        if (server == null) {
            throw new IllegalArgumentException(
                    "Persistent checkpoint server cannot be null."
            );
        }

        int attemptedCount =
                0;

        int checkpointedCount =
                0;

        List<CheckpointFailure> failures =
                new ArrayList<>();

        /*
         * Work from a stable copy. Checkpointing should not modify the live
         * manager map, but the copy prevents accidental iteration problems if
         * a future persistence implementation does so.
         */
        List<LivePersistentIncursion> persistentIncursions =
                List.copyOf(
                        PERSISTENT_INCURSIONS_BY_ID.values()
                );

        for (LivePersistentIncursion incursion
                : persistentIncursions) {

            attemptedCount++;

            try {
                if (incursion.getLevel()
                        .getServer()
                        != server) {

                    throw new IllegalStateException(
                            "Live incursion belongs to a different "
                                    + "MinecraftServer lifecycle."
                    );
                }

                synchronisePersistentSnapshot(
                        incursion
                );

                checkpointedCount++;
            } catch (RuntimeException exception) {
                failures.add(
                        new CheckpointFailure(
                                incursion.getIncursionId(),
                                incursion.getScenarioId(),
                                incursion.getLevel()
                                        .dimension()
                                        .location()
                                        .toString(),
                                describeCheckpointException(
                                        exception
                                )
                        )
                );
            }
        }

        return new CheckpointReport(
                attemptedCount,
                checkpointedCount,
                failures
        );
    }

    /**
     * Detaches every live runtime object without modifying SavedData or
     * performing world cleanup.
     *
     * This is the correct operation during normal server shutdown. Persistent
     * incursions must remain stored so they can be reconstructed when the
     * server starts again.
     *
     * Legacy Scenarios are also detached. They have no persistent
     * representation and therefore cannot be restored after restart.
     *
     * @return number of live manager entries detached
     */
    public static int detachAllRuntimeIncursions() {
        int detachedCount =
                LEGACY_ACTIVE_INCURSIONS.size()
                        + PERSISTENT_INCURSIONS_BY_ID.size();

        LEGACY_ACTIVE_INCURSIONS.clear();
        PERSISTENT_INCURSIONS_BY_ID.clear();

        return detachedCount;
    }

    /**
     * Returns the Scenarios currently occupying active-incursion management.
     *
     * CLEANUP_PENDING persistent records are excluded because their combat
     * runtime has finished.
     *
     * SUSPENDED persistent records remain included. They still own
     * reservations and should continue occupying overlap limits until they
     * are resumed or explicitly removed.
     */
    public static List<SkavenScenario> getActiveIncursions() {
        return collectManagedScenarios();
    }

    public static LivePersistentIncursion getPersistentIncursion(
            UUID incursionId
    ) {
        if (incursionId == null) {
            return null;
        }

        return PERSISTENT_INCURSIONS_BY_ID.get(
                incursionId
        );
    }

    /**
     * Includes ACTIVE, SUSPENDED and CLEANUP_PENDING live persistent records.
     */
    public static List<LivePersistentIncursion>
    getPersistentIncursions() {
        return List.copyOf(
                PERSISTENT_INCURSIONS_BY_ID.values()
        );
    }

    /**
     * Counts legacy Scenarios and unfinished persistent Scenarios.
     *
     * CLEANUP_PENDING records are excluded because their Scenario runtime has
     * already finished.
     */
    public static int getActiveIncursionCount() {
        return collectManagedScenarios()
                .size();
    }

    /**
     * Includes cleanup-pending persistent records that have not completed
     * manager cleanup yet.
     */
    public static int getManagedIncursionCount() {
        return LEGACY_ACTIVE_INCURSIONS.size()
                + PERSISTENT_INCURSIONS_BY_ID.size();
    }

    public static int getPersistentIncursionCount() {
        return PERSISTENT_INCURSIONS_BY_ID.size();
    }

    public static boolean hasActiveIncursions() {
        return getActiveIncursionCount() > 0;
    }

    public static boolean hasPersistentIncursions() {
        return !PERSISTENT_INCURSIONS_BY_ID.isEmpty();
    }

    public static boolean hasActiveScenarioId(
            String scenarioId
    ) {
        if (scenarioId == null
                || scenarioId.isBlank()) {

            return false;
        }

        for (SkavenScenario incursion
                : collectManagedScenarios()) {

            if (incursion.getId().equals(
                    scenarioId
            )) {
                return true;
            }
        }

        return false;
    }

    public static boolean hasActivePattern(
            ScenarioPattern pattern
    ) {
        if (pattern == null) {
            return false;
        }

        for (SkavenScenario incursion
                : collectManagedScenarios()) {

            if (incursion.getDefinition().pattern()
                    == pattern) {

                return true;
            }
        }

        return false;
    }

    public static boolean hasActiveGoal(
            ScenarioGoal goal
    ) {
        if (goal == null) {
            return false;
        }

        for (SkavenScenario incursion
                : collectManagedScenarios()) {

            if (incursion.getDefinition().goal()
                    == goal) {

                return true;
            }
        }

        return false;
    }

    public static boolean hasActiveOverlapType(
            OverlapType overlapType
    ) {
        if (overlapType == null) {
            return false;
        }

        for (SkavenScenario incursion
                : collectManagedScenarios()) {

            if (incursion.getDefinition().overlapType()
                    == overlapType) {

                return true;
            }
        }

        return false;
    }

    public static boolean hasActivePressureProfile(
            PressureProfile pressureProfile
    ) {
        if (pressureProfile == null) {
            return false;
        }

        for (SkavenScenario incursion
                : collectManagedScenarios()) {

            if (incursion.getDefinition().pressureProfile()
                    == pressureProfile) {

                return true;
            }
        }

        return false;
    }

    public static int countActiveOverlapType(
            OverlapType overlapType
    ) {
        if (overlapType == null) {
            return 0;
        }

        int count =
                0;

        for (SkavenScenario incursion
                : collectManagedScenarios()) {

            if (incursion.getDefinition().overlapType()
                    == overlapType) {

                count++;
            }
        }

        return count;
    }

    public static int countActivePressureProfile(
            PressureProfile pressureProfile
    ) {
        if (pressureProfile == null) {
            return 0;
        }

        int count =
                0;

        for (SkavenScenario incursion
                : collectManagedScenarios()) {

            if (incursion.getDefinition().pressureProfile()
                    == pressureProfile) {

                count++;
            }
        }

        return count;
    }

    public static int getActiveMajorIncursionCount() {
        return countActiveOverlapType(
                OverlapType.MAJOR
        );
    }

    public static int getActiveMinorIncursionCount() {
        return countActiveOverlapType(
                OverlapType.MINOR
        );
    }

    public static boolean hasActiveCombatPressure() {
        return hasActivePressureProfile(
                PressureProfile.COMBAT
        )
                || hasActivePressureProfile(
                PressureProfile.SET_PIECE
        );
    }

    public static boolean hasActiveSetPiece() {
        return hasActivePressureProfile(
                PressureProfile.SET_PIECE
        )
                || hasActiveOverlapType(
                OverlapType.EXCLUSIVE
        );
    }

    private static void tickLegacyIncursions() {
        Iterator<SkavenScenario> iterator =
                LEGACY_ACTIVE_INCURSIONS.iterator();

        while (iterator.hasNext()) {
            SkavenScenario incursion =
                    iterator.next();

            incursion.tick();

            if (incursion.isFinished()) {
                iterator.remove();
            }
        }
    }

    /**
     * Advances persistence-aware incursions.
     *
     * CLEANUP_PENDING deliberately survives for one manager tick.
     *
     * When an ACTIVE Scenario finishes during this tick, its finished runtime
     * and CLEANUP_PENDING phase are written into SavedData immediately.
     * Cleanup is performed when the manager sees that phase at the beginning
     * of a later tick.
     *
     * Ordinary in-progress runtime is checkpointed through
     * PersistentIncursionCheckpointPolicy rather than rebuilding the complete
     * persistence graph every tick.
     */
    private static void tickPersistentIncursions() {
        Iterator<
                Map.Entry<UUID, LivePersistentIncursion>
                > iterator =
                PERSISTENT_INCURSIONS_BY_ID
                        .entrySet()
                        .iterator();

        while (iterator.hasNext()) {
            Map.Entry<UUID, LivePersistentIncursion> entry =
                    iterator.next();

            LivePersistentIncursion incursion =
                    entry.getValue();

            if (incursion.requiresCleanup()) {
                removePersistentWorldState(
                        incursion
                );

                iterator.remove();

                continue;
            }

            if (!incursion.isTickable()) {
                /*
                 * SUSPENDED runtime remains admitted and persisted without
                 * advancing.
                 */
                continue;
            }

            incursion.tick();

            /*
             * Entering CLEANUP_PENDING is a critical lifecycle change and is
             * checkpointed immediately.
             *
             * Ordinary active progress uses the staggered one-second
             * checkpoint schedule.
             */
            if (incursion.requiresCleanup()
                    || PersistentIncursionCheckpointPolicy
                    .isPeriodicCheckpointDue(
                            incursion
                    )) {

                synchronisePersistentSnapshot(
                        incursion
                );
            }

            /*
             * Do not clean up a Scenario that entered CLEANUP_PENDING during
             * this tick. Its finished record must remain stored until the
             * following manager tick.
             */
        }
    }

    private static void synchronisePersistentSnapshot(
            LivePersistentIncursion incursion
    ) {
        PersistentIncursionSnapshot currentSnapshot =
                incursion.createPersistentSnapshot();

        SkavenIncursionSavedData savedData =
                SkavenIncursionSavedData.get(
                        incursion.getLevel()
                );

        if (!savedData.containsIncursion(
                incursion.getIncursionId()
        )) {
            throw new IllegalStateException(
                    "Cannot synchronise live persistent incursion "
                            + incursion.getIncursionId()
                            + " because its SavedData record is absent."
            );
        }

        savedData.replaceSnapshot(
                currentSnapshot
        );
    }

    /**
     * Performs idempotent world-side cleanup before deleting the persistent
     * record.
     *
     * Debug anchors and static reservation entries may already be absent,
     * particularly after a restart. Their removal methods therefore tolerate
     * a missing entry.
     *
     * The SavedData record is authoritative and must still be present.
     */
    private static void removePersistentWorldState(
            LivePersistentIncursion incursion
    ) {
        DebugIncursionAnchorTracker.removeAndForget(
                incursion.getLevel(),
                incursion.getIncursionId()
        );

        ActiveIncursionSourceReservationRegistry.remove(
                incursion.getLevel(),
                incursion.getIncursionId()
        );

        SkavenIncursionSavedData savedData =
                SkavenIncursionSavedData.get(
                        incursion.getLevel()
                );

        PersistentIncursionSnapshot removedSnapshot =
                savedData.removeSnapshot(
                        incursion.getIncursionId()
                );

        if (removedSnapshot == null) {
            throw new IllegalStateException(
                    "Persistent cleanup could not remove SavedData record "
                            + incursion.getIncursionId()
                            + " because it was absent."
            );
        }
    }

    /**
     * Returns legacy Scenarios plus unfinished persistent Scenarios.
     */
    private static List<SkavenScenario> collectManagedScenarios() {
        List<SkavenScenario> scenarios =
                new ArrayList<>(
                        LEGACY_ACTIVE_INCURSIONS
                );

        for (LivePersistentIncursion persistentIncursion
                : PERSISTENT_INCURSIONS_BY_ID.values()) {

            if (persistentIncursion.requiresCleanup()) {
                continue;
            }

            scenarios.add(
                    persistentIncursion.getScenario()
            );
        }

        return List.copyOf(
                scenarios
        );
    }

    private static boolean containsManagedIncursionId(
            UUID incursionId
    ) {
        if (PERSISTENT_INCURSIONS_BY_ID.containsKey(
                incursionId
        )) {
            return true;
        }

        for (SkavenScenario legacyIncursion
                : LEGACY_ACTIVE_INCURSIONS) {

            if (incursionId.equals(
                    legacyIncursion.getInstanceId()
            )) {
                return true;
            }
        }

        return false;
    }

    private static String describeCheckpointException(
            RuntimeException exception
    ) {
        if (exception == null) {
            return "Unknown persistent-incursion checkpoint failure.";
        }

        String message =
                exception.getMessage();

        if (message == null
                || message.isBlank()) {

            return exception
                    .getClass()
                    .getSimpleName();
        }

        return exception
                .getClass()
                .getSimpleName()
                + ": "
                + message;
    }

    private static void requireScenario(
            SkavenScenario scenario
    ) {
        if (scenario == null) {
            throw new IllegalArgumentException(
                    "Active incursion cannot be null."
            );
        }
    }

    private static UUID requireScenarioInstanceId(
            SkavenScenario scenario
    ) {
        UUID instanceId =
                scenario.getInstanceId();

        if (instanceId == null) {
            throw new IllegalArgumentException(
                    "Active Scenario "
                            + scenario.getId()
                            + " has no instance ID."
            );
        }

        return instanceId;
    }

    private static void requirePersistentIncursion(
            LivePersistentIncursion incursion
    ) {
        if (incursion == null) {
            throw new IllegalArgumentException(
                    "Live persistent incursion cannot be null."
            );
        }

        if (incursion.getIncursionId() == null) {
            throw new IllegalArgumentException(
                    "Live persistent incursion has no incursion ID."
            );
        }
    }

    /**
     * Result of one server-wide persistent-incursion checkpoint pass.
     */
    public record CheckpointReport(
            int attemptedCount,
            int checkpointedCount,
            List<CheckpointFailure> failures
    ) {

        public CheckpointReport {
            if (attemptedCount < 0) {
                throw new IllegalArgumentException(
                        "Checkpoint attempted count cannot be negative."
                );
            }

            if (checkpointedCount < 0) {
                throw new IllegalArgumentException(
                        "Checkpoint success count cannot be negative."
                );
            }

            if (failures == null) {
                throw new IllegalArgumentException(
                        "Checkpoint failure list cannot be null."
                );
            }

            failures =
                    List.copyOf(
                            failures
                    );

            if (checkpointedCount
                    + failures.size()
                    != attemptedCount) {

                throw new IllegalArgumentException(
                        "Checkpoint report does not account for every "
                                + "attempted persistent incursion."
                );
            }
        }

        public int getFailureCount() {
            return failures.size();
        }

        public boolean successful() {
            return failures.isEmpty();
        }

        public boolean hadPersistentIncursions() {
            return attemptedCount > 0;
        }
    }

    /**
     * Diagnostic information for one incursion that could not complete its
     * final checkpoint.
     */
    public record CheckpointFailure(
            UUID incursionId,
            String scenarioId,
            String levelId,
            String message
    ) {

        public CheckpointFailure {
            if (incursionId == null) {
                throw new IllegalArgumentException(
                        "Checkpoint failure incursion ID cannot be null."
                );
            }

            if (scenarioId == null
                    || scenarioId.isBlank()) {

                throw new IllegalArgumentException(
                        "Checkpoint failure Scenario ID cannot be blank."
                );
            }

            if (levelId == null
                    || levelId.isBlank()) {

                throw new IllegalArgumentException(
                        "Checkpoint failure level ID cannot be blank."
                );
            }

            if (message == null
                    || message.isBlank()) {

                throw new IllegalArgumentException(
                        "Checkpoint failure message cannot be blank."
                );
            }
        }
    }

    private ActiveIncursionManager() {
    }
}