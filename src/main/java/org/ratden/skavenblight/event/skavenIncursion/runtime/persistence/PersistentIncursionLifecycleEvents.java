package org.ratden.skavenblight.event.skavenIncursion.runtime.persistence;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.event.skavenIncursion.director.ActiveIncursionManager;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.ActiveIncursionSourceReservationRegistry;
import org.slf4j.Logger;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * GAME-bus adapter for persistent-incursion server lifecycle events.
 *
 * Server startup:
 *
 * - removes any stale static runtime state retained by an earlier integrated
 *   server lifecycle;
 * - restores persistent incursions for every loaded ServerLevel;
 * - reports restoration failures and non-fatal warnings.
 *
 * Server shutdown:
 *
 * - detaches live Scenario and incursion objects;
 * - clears static physical-reservation snapshots;
 * - leaves SavedData untouched so persistent incursions survive restart.
 *
 * This class owns only event adaptation and diagnostics. Logical
 * reconstruction remains in PersistentIncursionRestorationService.
 */
@EventBusSubscriber(
        modid = Skavenblight.MODID
)
public final class PersistentIncursionLifecycleEvents {

    private static final Logger LOGGER =
            LogUtils.getLogger();

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

        if (staleRuntimeCount > 0
                || staleReservationCount > 0) {

            LOGGER.warn(
                    "Removed stale Skaven incursion startup state: {} live "
                            + "runtime entries and {} reservation snapshots.",
                    staleRuntimeCount,
                    staleReservationCount
            );
        }

        for (ServerLevel level
                : event.getServer().getAllLevels()) {

            restoreLevel(
                    level
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
     * Releases static live-runtime state without deleting persistent records.
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

        if (detachedRuntimeCount > 0
                || clearedReservationCount > 0) {

            LOGGER.debug(
                    "Detached Skaven incursion shutdown state: {} live "
                            + "runtime entries and {} reservation snapshots.",
                    detachedRuntimeCount,
                    clearedReservationCount
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
    private static void restoreLevel(
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
            return;
        }

        if (!restorationReport.hadFailures()) {
            LOGGER.info(
                    "Restored {} persistent Skaven incursion record{} in "
                            + "level {}. {} record{} already attached. "
                            + "{} non-fatal warning{}.",
                    restorationReport.restoredCount(),
                    pluralSuffix(
                            restorationReport.restoredCount()
                    ),
                    level.dimension().location(),
                    restorationReport.skippedCount(),
                    pluralSuffix(
                            restorationReport.skippedCount()
                    ),
                    restorationReport.getWarningCount(),
                    pluralSuffix(
                            restorationReport.getWarningCount()
                    )
            );

            logRestorationWarnings(
                    level,
                    restorationReport
            );

            return;
        }

        LOGGER.warn(
                "Persistent Skaven incursion restoration in level {} "
                        + "processed {} record{}: {} restored, {} skipped, "
                        + "{} failed, {} newly suspended and {} non-fatal "
                        + "warning{}.",
                level.dimension().location(),
                restorationReport.storedRecordCount(),
                pluralSuffix(
                        restorationReport.storedRecordCount()
                ),
                restorationReport.restoredCount(),
                restorationReport.skippedCount(),
                restorationReport.getFailureCount(),
                restorationReport.newlySuspendedCount(),
                restorationReport.getWarningCount(),
                pluralSuffix(
                        restorationReport.getWarningCount()
                )
        );

        for (PersistentIncursionRestorationService.RestorationFailure failure
                : restorationReport.failures()) {

            LOGGER.error(
                    "Persistent incursion {} ({}) could not be restored. "
                            + "Original phase: {}. Newly suspended: {}. "
                            + "Reservations restored: {}. Reason: {}",
                    failure.incursionId(),
                    failure.scenarioId(),
                    failure.originalPhase(),
                    failure.markedSuspended(),
                    failure.reservationsRestored(),
                    failure.message()
            );
        }

        logRestorationWarnings(
                level,
                restorationReport
        );
    }

    /**
     * Reports optional subsystem failures without treating them as failures of
     * the authoritative incursion restoration.
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