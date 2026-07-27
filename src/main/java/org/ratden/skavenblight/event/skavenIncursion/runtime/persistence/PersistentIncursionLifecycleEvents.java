package org.ratden.skavenblight.event.skavenIncursion.runtime.persistence;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.event.skavenIncursion.director.ActiveIncursionManager;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.ActiveIncursionSourceReservationRegistry;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * GAME-bus adapter for persistent-incursion server lifecycle events.
 *
 * Server startup:
 *
 * - removes stale static runtime state retained by an earlier integrated
 *   server lifecycle;
 * - restores persistent incursions for every loaded ServerLevel;
 * - records ACTIVE incursions whose authoritative chunks are not ready yet;
 * - reports restoration failures and non-fatal warnings.
 *
 * Deferred restoration:
 *
 * - retries once per second on the logical server thread;
 * - retries only the incursion IDs that previously reported DEFERRED;
 * - removes an ID from retry ownership after success, failure, cleanup or
 *   external deletion;
 * - never changes an ACTIVE record to SUSPENDED merely because chunks are
 *   still loading.
 *
 * Server shutdown:
 *
 * - captures a final orderly checkpoint;
 * - detaches live Scenario and incursion objects;
 * - clears static physical-reservation and deferred-restoration state;
 * - leaves SavedData and NeoForge chunk tickets untouched so active
 *   incursions survive restart.
 *
 * This class owns only event adaptation, deferred retry scheduling and
 * diagnostics. Logical reconstruction remains in
 * PersistentIncursionRestorationService.
 */
@EventBusSubscriber(
        modid = Skavenblight.MODID
)
public final class PersistentIncursionLifecycleEvents {

    private static final Logger LOGGER =
            LogUtils.getLogger();

    private static final int RESTORATION_RETRY_INTERVAL_TICKS =
            20;

    /**
     * Deferred ACTIVE incursion IDs grouped by dimension.
     *
     * Linked collections keep retry and log ordering deterministic.
     */
    private static final Map<
            ResourceKey<Level>,
            Set<UUID>
            > DEFERRED_RESTORATIONS_BY_LEVEL =
            new LinkedHashMap<>();

    private static int ticksUntilRestorationRetry =
            RESTORATION_RETRY_INTERVAL_TICKS;

    /**
     * Restores all persistent incursions once the server and its levels are
     * available for gameplay.
     */
    @SubscribeEvent
    public static void onServerStarted(
            ServerStartedEvent event
    ) {
        /*
         * ServerStoppedEvent should already have removed these static values.
         * Repeating the reset here makes integrated-server startup robust
         * against an incomplete previous lifecycle.
         */
        int staleRuntimeCount =
                ActiveIncursionManager
                        .detachAllRuntimeIncursions();

        int staleReservationCount =
                ActiveIncursionSourceReservationRegistry
                        .clearAll();

        int staleDeferredCount =
                clearDeferredRestorations();

        ticksUntilRestorationRetry =
                RESTORATION_RETRY_INTERVAL_TICKS;

        if (staleRuntimeCount > 0
                || staleReservationCount > 0
                || staleDeferredCount > 0) {

            LOGGER.warn(
                    "Removed stale Skaven incursion startup state: {} live "
                            + "runtime entries, {} reservation snapshots and "
                            + "{} deferred restoration entries.",
                    staleRuntimeCount,
                    staleReservationCount,
                    staleDeferredCount
            );
        }

        for (ServerLevel level
                : event.getServer().getAllLevels()) {

            PersistentIncursionRestorationService.RestorationReport
                    restorationReport =
                    restoreLevel(
                            level
                    );

            updateDeferredRestorations(
                    level,
                    restorationReport
            );
        }
    }

    /**
     * Retries ACTIVE records that were valid but could not yet inspect their
     * authoritative world footprint because chunks were still loading.
     *
     * Retrying once per second avoids reconstructing complete plans and
     * Scenario runtime graphs on every game tick.
     */
    @SubscribeEvent
    public static void onServerTick(
            ServerTickEvent.Post event
    ) {
        if (DEFERRED_RESTORATIONS_BY_LEVEL.isEmpty()) {
            ticksUntilRestorationRetry =
                    RESTORATION_RETRY_INTERVAL_TICKS;

            return;
        }

        ticksUntilRestorationRetry--;

        if (ticksUntilRestorationRetry > 0) {
            return;
        }

        ticksUntilRestorationRetry =
                RESTORATION_RETRY_INTERVAL_TICKS;

        List<Map.Entry<ResourceKey<Level>, Set<UUID>>>
                pendingLevels =
                new ArrayList<>();

        for (Map.Entry<ResourceKey<Level>, Set<UUID>> entry
                : DEFERRED_RESTORATIONS_BY_LEVEL.entrySet()) {

            pendingLevels.add(
                    Map.entry(
                            entry.getKey(),
                            Set.copyOf(
                                    entry.getValue()
                            )
                    )
            );
        }

        for (Map.Entry<ResourceKey<Level>, Set<UUID>> entry
                : pendingLevels) {

            ServerLevel level =
                    event.getServer()
                            .getLevel(
                                    entry.getKey()
                            );

            if (level == null) {
                DEFERRED_RESTORATIONS_BY_LEVEL.remove(
                        entry.getKey()
                );

                continue;
            }

            PersistentIncursionRestorationService.RestorationReport
                    restorationReport;

            try {
                restorationReport =
                        PersistentIncursionRestorationService
                                .retryDeferred(
                                        level,
                                        entry.getValue()
                                );
            } catch (RuntimeException exception) {
                LOGGER.error(
                        "Could not retry deferred persistent Skaven "
                                + "incursions for level {}.",
                        level.dimension().location(),
                        exception
                );

                throw exception;
            }

            updateDeferredRestorations(
                    level,
                    restorationReport
            );

            logDeferredRetryResult(
                    level,
                    restorationReport
            );
        }
    }

    /**
     * Captures final runtime progress before orderly shutdown detaches the
     * live incursion objects.
     *
     * Failures are logged independently. Shutdown must continue even when one
     * malformed incursion cannot create its final checkpoint.
     */
    @SubscribeEvent
    public static void onServerStopping(
            ServerStoppingEvent event
    ) {
        ActiveIncursionManager.CheckpointReport checkpointReport =
                ActiveIncursionManager
                        .checkpointAllPersistentIncursions(
                                event.getServer()
                        );

        if (!checkpointReport.hadPersistentIncursions()) {
            return;
        }

        if (checkpointReport.successful()) {
            LOGGER.info(
                    "Final-checkpointed {} persistent Skaven incursion "
                            + "record{} before server shutdown.",
                    checkpointReport.checkpointedCount(),
                    pluralSuffix(
                            checkpointReport.checkpointedCount()
                    )
            );

            return;
        }

        LOGGER.error(
                "Final Skaven incursion shutdown checkpoint processed {} "
                        + "record{}: {} succeeded and {} failed.",
                checkpointReport.attemptedCount(),
                pluralSuffix(
                        checkpointReport.attemptedCount()
                ),
                checkpointReport.checkpointedCount(),
                checkpointReport.getFailureCount()
        );

        for (ActiveIncursionManager.CheckpointFailure failure
                : checkpointReport.failures()) {

            LOGGER.error(
                    "Could not final-checkpoint persistent incursion {} ({}) "
                            + "in level {}: {}",
                    failure.incursionId(),
                    failure.scenarioId(),
                    failure.levelId(),
                    failure.message()
            );
        }
    }

    /**
     * Releases static live-runtime state without deleting persistent records
     * or persistent NeoForge chunk tickets.
     */
    @SubscribeEvent
    public static void onServerStopped(
            ServerStoppedEvent event
    ) {
        int detachedRuntimeCount =
                ActiveIncursionManager
                        .detachAllRuntimeIncursions();

        int clearedReservationCount =
                ActiveIncursionSourceReservationRegistry
                        .clearAll();

        int clearedDeferredCount =
                clearDeferredRestorations();

        ticksUntilRestorationRetry =
                RESTORATION_RETRY_INTERVAL_TICKS;

        if (detachedRuntimeCount > 0
                || clearedReservationCount > 0
                || clearedDeferredCount > 0) {

            LOGGER.debug(
                    "Detached Skaven incursion shutdown state: {} live "
                            + "runtime entries, {} reservation snapshots and "
                            + "{} deferred restoration entries.",
                    detachedRuntimeCount,
                    clearedReservationCount,
                    clearedDeferredCount
            );
        }
    }

    /**
     * Runs one complete level restoration pass and reports its result.
     *
     * A malformed SavedData container remains a fatal restoration problem.
     * The exception is logged with level context and then propagated rather
     * than allowing the server to run while silently ignoring authoritative
     * incursion state.
     */
    private static PersistentIncursionRestorationService.RestorationReport
    restoreLevel(
            ServerLevel level
    ) {
        PersistentIncursionRestorationService.RestorationReport
                restorationReport;

        try {
            restorationReport =
                    PersistentIncursionRestorationService.restoreLevel(
                            level
                    );
        } catch (RuntimeException exception) {
            LOGGER.error(
                    "Could not load persistent Skaven incursions for level "
                            + "{}.",
                    level.dimension().location(),
                    exception
            );

            throw exception;
        }

        if (!restorationReport.hadStoredRecords()) {
            return restorationReport;
        }

        if (!restorationReport.hadFailures()) {
            LOGGER.info(
                    "Persistent Skaven incursion restoration in level {} "
                            + "processed {} record{}: {} restored, {} already "
                            + "attached, {} deferred and {} non-fatal "
                            + "warning{}.",
                    level.dimension().location(),
                    restorationReport.storedRecordCount(),
                    pluralSuffix(
                            restorationReport.storedRecordCount()
                    ),
                    restorationReport.restoredCount(),
                    restorationReport.skippedCount(),
                    restorationReport.getDeferredCount(),
                    restorationReport.getWarningCount(),
                    pluralSuffix(
                            restorationReport.getWarningCount()
                    )
            );

            logRestorationWarnings(
                    level,
                    restorationReport
            );

            logDeferredRestorations(
                    level,
                    restorationReport
            );

            return restorationReport;
        }

        LOGGER.warn(
                "Persistent Skaven incursion restoration in level {} "
                        + "processed {} record{}: {} restored, {} skipped, "
                        + "{} deferred, {} failed, {} newly suspended and {} "
                        + "non-fatal warning{}.",
                level.dimension().location(),
                restorationReport.storedRecordCount(),
                pluralSuffix(
                        restorationReport.storedRecordCount()
                ),
                restorationReport.restoredCount(),
                restorationReport.skippedCount(),
                restorationReport.getDeferredCount(),
                restorationReport.getFailureCount(),
                restorationReport.newlySuspendedCount(),
                restorationReport.getWarningCount(),
                pluralSuffix(
                        restorationReport.getWarningCount()
                )
        );

        logRestorationFailures(
                level,
                restorationReport
        );

        logRestorationWarnings(
                level,
                restorationReport
        );

        logDeferredRestorations(
                level,
                restorationReport
        );

        return restorationReport;
    }

    /**
     * Replaces the pending set for one level with the IDs still deferred by
     * the most recent attempt.
     */
    private static void updateDeferredRestorations(
            ServerLevel level,
            PersistentIncursionRestorationService.RestorationReport
                    restorationReport
    ) {
        Set<UUID> deferredIncursionIds =
                restorationReport.getDeferredIncursionIds();

        if (deferredIncursionIds.isEmpty()) {
            DEFERRED_RESTORATIONS_BY_LEVEL.remove(
                    level.dimension()
            );

            return;
        }

        DEFERRED_RESTORATIONS_BY_LEVEL.put(
                level.dimension(),
                new LinkedHashSet<>(
                        deferredIncursionIds
                )
        );
    }

    /**
     * Reports only meaningful changes from a periodic deferred retry.
     *
     * A pass in which every record remains deferred produces no repeated log
     * spam. Initial deferred reasons were already reported during startup.
     */
    private static void logDeferredRetryResult(
            ServerLevel level,
            PersistentIncursionRestorationService.RestorationReport
                    restorationReport
    ) {
        if (restorationReport.restoredCount() > 0
                || restorationReport.skippedCount() > 0) {

            LOGGER.info(
                    "Deferred Skaven incursion restoration retry in level {} "
                            + "restored {} record{} and found {} already "
                            + "attached. {} record{} remain deferred.",
                    level.dimension().location(),
                    restorationReport.restoredCount(),
                    pluralSuffix(
                            restorationReport.restoredCount()
                    ),
                    restorationReport.skippedCount(),
                    restorationReport.getDeferredCount(),
                    pluralSuffix(
                            restorationReport.getDeferredCount()
                    )
            );
        }

        if (restorationReport.hadFailures()) {
            LOGGER.warn(
                    "Deferred Skaven incursion restoration retry in level {} "
                            + "encountered {} failure{}.",
                    level.dimension().location(),
                    restorationReport.getFailureCount(),
                    pluralSuffix(
                            restorationReport.getFailureCount()
                    )
            );

            logRestorationFailures(
                    level,
                    restorationReport
            );
        }

        logRestorationWarnings(
                level,
                restorationReport
        );
    }

    /**
     * Reports optional subsystem failures without treating them as failures of
     * authoritative incursion restoration.
     */
    private static void logRestorationWarnings(
            ServerLevel level,
            PersistentIncursionRestorationService.RestorationReport
                    restorationReport
    ) {
        for (PersistentIncursionRestorationService.RestorationWarning warning
                : restorationReport.warnings()) {

            LOGGER.warn(
                    "Persistent incursion {} ({}) restored in level {}, but "
                            + "reported a non-fatal warning: {}",
                    warning.incursionId(),
                    warning.scenarioId(),
                    level.dimension().location(),
                    warning.message()
            );
        }
    }

    private static void logDeferredRestorations(
            ServerLevel level,
            PersistentIncursionRestorationService.RestorationReport
                    restorationReport
    ) {
        for (PersistentIncursionRestorationService.RestorationDeferred deferred
                : restorationReport.deferred()) {

            LOGGER.info(
                    "Persistent incursion {} ({}) restoration is deferred in "
                            + "level {}. Reservations restored: {}. Reason: "
                            + "{}",
                    deferred.incursionId(),
                    deferred.scenarioId(),
                    level.dimension().location(),
                    deferred.reservationsRestored(),
                    deferred.message()
            );
        }
    }

    private static void logRestorationFailures(
            ServerLevel level,
            PersistentIncursionRestorationService.RestorationReport
                    restorationReport
    ) {
        for (PersistentIncursionRestorationService.RestorationFailure failure
                : restorationReport.failures()) {

            LOGGER.error(
                    "Persistent incursion {} ({}) could not be restored. "
                            + "Original phase: {}. Newly suspended: {}. "
                            + "Reservations restored: {}. Level: {}. "
                            + "Reason: {}",
                    failure.incursionId(),
                    failure.scenarioId(),
                    failure.originalPhase(),
                    failure.markedSuspended(),
                    failure.reservationsRestored(),
                    level.dimension().location(),
                    failure.message()
            );
        }
    }

    private static int clearDeferredRestorations() {
        int clearedCount =
                0;

        for (Set<UUID> deferredIncursionIds
                : DEFERRED_RESTORATIONS_BY_LEVEL.values()) {

            clearedCount +=
                    deferredIncursionIds.size();
        }

        DEFERRED_RESTORATIONS_BY_LEVEL.clear();

        return clearedCount;
    }

    private static String pluralSuffix(
            int count
    ) {
        return count == 1
                ? ""
                : "s";
    }

    private PersistentIncursionLifecycleEvents() {
    }
}