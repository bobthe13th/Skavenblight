package org.ratden.skavenblight.event.skavenIncursion.director;

import org.ratden.skavenblight.event.skavenIncursion.planning.IncursionPlan;
import org.ratden.skavenblight.event.skavenIncursion.planning.IncursionPlanner;
import org.ratden.skavenblight.event.skavenIncursion.planning.IncursionPlanningContext;
import org.ratden.skavenblight.event.skavenIncursion.planning.IncursionPlanningResult;
import org.ratden.skavenblight.event.skavenIncursion.planning.chunk.IncursionChunkLoadPlan;
import org.ratden.skavenblight.event.skavenIncursion.planning.chunk.IncursionChunkLoadPlanCalculator;
import org.ratden.skavenblight.event.skavenIncursion.planning.persistence.IncursionChunkLoadPlanSnapshot;
import org.ratden.skavenblight.event.skavenIncursion.planning.persistence.IncursionPlanSnapshot;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.ActiveIncursionSourceReservationRegistry;
import org.ratden.skavenblight.event.skavenIncursion.runtime.chunk.IncursionChunkTicketService;
import org.ratden.skavenblight.event.skavenIncursion.runtime.persistence.IncursionTargetSnapshot;
import org.ratden.skavenblight.event.skavenIncursion.runtime.persistence.LivePersistentIncursion;
import org.ratden.skavenblight.event.skavenIncursion.runtime.persistence.PersistableSkavenScenario;
import org.ratden.skavenblight.event.skavenIncursion.runtime.persistence.PlannedScenarioRuntimeSnapshot;
import org.ratden.skavenblight.event.skavenIncursion.scenario.ScenarioRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Executes the shared production route for starting one planning-aware
 * incursion after the Director or another authorised caller has selected:
 *
 * - Scenario;
 * - Stratagem;
 * - target;
 * - placement context;
 * - threat budget;
 * - complexity budget.
 *
 * This service owns:
 *
 * 1. complete incursion planning;
 * 2. deterministic chunk-load plan calculation;
 * 3. planned Scenario construction;
 * 4. immutable plan, chunk-plan and target snapshot capture;
 * 5. LivePersistentIncursion construction;
 * 6. authoritative source-reservation registration;
 * 7. initial authoritative chunk-ticket acquisition;
 * 8. transactional persistent-runtime admission;
 * 9. rollback of tickets and reservations when admission fails.
 *
 * It does not own:
 *
 * - Scenario or Stratagem selection;
 * - target selection;
 * - Director scheduling, cooldowns or overlap;
 * - world-data progression records;
 * - debug messages or debug-anchor placement;
 * - fallback to another Scenario or Stratagem after failure.
 *
 * Those responsibilities remain with the caller.
 */
public class PlannedIncursionStartService {

    private final IncursionPlanner incursionPlanner;
    private final IncursionChunkLoadPlanCalculator chunkLoadPlanCalculator;

    public PlannedIncursionStartService() {
        this(
                new IncursionPlanner(),
                new IncursionChunkLoadPlanCalculator()
        );
    }

    public PlannedIncursionStartService(
            IncursionPlanner incursionPlanner
    ) {
        this(
                incursionPlanner,
                new IncursionChunkLoadPlanCalculator()
        );
    }

    public PlannedIncursionStartService(
            IncursionPlanner incursionPlanner,
            IncursionChunkLoadPlanCalculator chunkLoadPlanCalculator
    ) {
        this.incursionPlanner =
                Objects.requireNonNull(
                        incursionPlanner,
                        "Incursion planner cannot be null."
                );

        this.chunkLoadPlanCalculator =
                Objects.requireNonNull(
                        chunkLoadPlanCalculator,
                        "Incursion chunk-load plan calculator cannot be null."
                );
    }

    /**
     * Attempts to plan and admit one persistence-aware incursion.
     *
     * Expected planning and admission failures are returned as structured
     * results. Invalid method arguments and defects outside the recognised
     * admission boundaries may still throw exceptions.
     */
    public StartResult start(
            IncursionPlanningContext planningContext
    ) {
        Objects.requireNonNull(
                planningContext,
                "Incursion planning context cannot be null."
        );

        IncursionPlanningResult planningResult =
                incursionPlanner.plan(
                        planningContext
                );

        if (planningResult.hasFailed()) {
            return StartResult.failed(
                    StartFailure.planningFailure(
                            planningResult.getFailure()
                    )
            );
        }

        IncursionPlan incursionPlan =
                planningResult.getIncursionPlan();

        IncursionChunkLoadPlan chunkLoadPlan;

        try {
            chunkLoadPlan =
                    chunkLoadPlanCalculator.calculate(
                            incursionPlan,
                            planningContext
                    );
        } catch (IllegalArgumentException
                 | IllegalStateException exception) {

            return StartResult.failed(
                    StartFailure.runtimeFailure(
                            FailureStage.CHUNK_LOAD_PLANNING,
                            "Could not calculate authoritative chunk-load "
                                    + "requirements for planned incursion "
                                    + incursionPlan.getIncursionId()
                                    + ": "
                                    + describeException(
                                    exception
                            )
                    )
            );
        }

        String scenarioId =
                planningContext
                        .scenarioDefinition()
                        .id();

        String stratagemId =
                planningContext
                        .stratagemDefinition()
                        .getId();

        PersistableSkavenScenario<
                PlannedScenarioRuntimeSnapshot
                > scenario =
                ScenarioRegistry.createPlannedScenario(
                        scenarioId,
                        planningContext.level(),
                        incursionPlan
                );

        if (scenario == null) {
            return StartResult.failed(
                    StartFailure.runtimeFailure(
                            FailureStage.SCENARIO_CREATION,
                            "Scenario "
                                    + scenarioId
                                    + " supports planned selection but could "
                                    + "not create planned runtime."
                    )
            );
        }

        LivePersistentIncursion livePersistentIncursion;

        try {
            IncursionPlanSnapshot incursionPlanSnapshot =
                    IncursionPlanSnapshot.capture(
                            incursionPlan
                    );

            IncursionChunkLoadPlanSnapshot chunkLoadPlanSnapshot =
                    IncursionChunkLoadPlanSnapshot.capture(
                            chunkLoadPlan
                    );

            IncursionTargetSnapshot targetSnapshot =
                    IncursionTargetSnapshot.capture(
                            planningContext
                                    .placementContext()
                    );

            livePersistentIncursion =
                    LivePersistentIncursion.createFresh(
                            planningContext.level(),
                            stratagemId,
                            targetSnapshot,
                            incursionPlan,
                            incursionPlanSnapshot,
                            chunkLoadPlan,
                            chunkLoadPlanSnapshot,
                            scenario
                    );
        } catch (IllegalArgumentException
                 | IllegalStateException exception) {

            return StartResult.failed(
                    StartFailure.runtimeFailure(
                            FailureStage.PERSISTENT_STATE_CREATION,
                            "Could not create persistent state for Scenario "
                                    + scenarioId
                                    + ": "
                                    + describeException(
                                    exception
                            )
                    )
            );
        }

        ActiveIncursionSourceReservationRegistry
                .IncursionReservationSnapshot
                reservationSnapshot;

        try {
            /*
             * Reservations are authoritative physical-planning state.
             *
             * They must exist before ticket acquisition and runtime admission
             * so another incursion cannot plan through this incursion's
             * future source positions.
             */
            reservationSnapshot =
                    ActiveIncursionSourceReservationRegistry.register(
                            planningContext.level(),
                            incursionPlan
                    );
        } catch (IllegalArgumentException
                 | IllegalStateException exception) {

            return StartResult.failed(
                    StartFailure.runtimeFailure(
                            FailureStage.RESERVATION_REGISTRATION,
                            "Could not register active source reservations "
                                    + "for Scenario "
                                    + scenarioId
                                    + ": "
                                    + describeException(
                                    exception
                            )
                    )
            );
        }

        /*
         * Fresh planning-aware Scenarios determine their current wave through
         * the same immutable runtime snapshot used by persistence.
         *
         * This avoids assuming that every future Scenario must begin at wave
         * index zero, while keeping admission independent of Scenario-specific
         * classes.
         */
        int initialWaveIndex;

        try {
            initialWaveIndex =
                    scenario.createSnapshot()
                            .getCurrentWaveIndex();
        } catch (RuntimeException exception) {
            String rollbackWarning =
                    rollbackReservationsOnly(
                            planningContext,
                            incursionPlan
                    );

            return StartResult.failed(
                    StartFailure.runtimeFailure(
                            FailureStage.CHUNK_TICKET_ACQUISITION,
                            "Could not determine the initial wave for "
                                    + "authoritative chunk-ticket acquisition "
                                    + "for Scenario "
                                    + scenarioId
                                    + ": "
                                    + describeException(
                                    exception
                            )
                                    + rollbackWarning
                    )
            );
        }

        IncursionChunkTicketService.TicketOperationResult
                ticketOperationResult;

        try {
            /*
             * The complete retained footprint is installed before the
             * incursion enters SavedData and live runtime.
             *
             * The protected base and current-wave source-group activation
             * footprint receive ticking tickets. Remaining planned chunks
             * receive retained non-ticking tickets.
             */
            ticketOperationResult =
                    IncursionChunkTicketService.acquireFreshTickets(
                            planningContext.level(),
                            chunkLoadPlan,
                            initialWaveIndex
                    );
        } catch (RuntimeException exception) {
            /*
             * acquireFreshTickets(...) owns rollback of every ticket it
             * successfully added before failing.
             *
             * This service still owns rollback of the separately registered
             * source reservations.
             */
            String rollbackWarning =
                    rollbackReservationsOnly(
                            planningContext,
                            incursionPlan
                    );

            return StartResult.failed(
                    StartFailure.runtimeFailure(
                            FailureStage.CHUNK_TICKET_ACQUISITION,
                            "Could not acquire authoritative chunk tickets "
                                    + "for Scenario "
                                    + scenarioId
                                    + ": "
                                    + describeException(
                                    exception
                            )
                                    + rollbackWarning
                    )
            );
        }

        try {
            /*
             * ActiveIncursionManager writes the initial SavedData record
             * before attaching the live owner.
             *
             * The manager owns rollback of a SavedData record if its own
             * live-map admission fails.
             */
            ActiveIncursionManager.addPersistentIncursion(
                    livePersistentIncursion
            );
        } catch (RuntimeException exception) {
            /*
             * Chunk tickets and the reservation registry exist outside
             * ActiveIncursionManager's transaction.
             *
             * This service therefore releases both after manager admission
             * fails.
             */
            String rollbackWarning =
                    rollbackTicketedAdmissionResources(
                            planningContext,
                            incursionPlan,
                            chunkLoadPlan
                    );

            return StartResult.failed(
                    StartFailure.runtimeFailure(
                            FailureStage.ACTIVE_ADMISSION,
                            "Could not admit planned Scenario "
                                    + scenarioId
                                    + " to persistent active-incursion "
                                    + "management: "
                                    + describeException(
                                    exception
                            )
                                    + rollbackWarning
                    )
            );
        }

        return StartResult.completed(
                new StartSuccess(
                        livePersistentIncursion,
                        reservationSnapshot,
                        ticketOperationResult
                )
        );
    }

    /**
     * Removes the reservation snapshot after failure before ticket
     * acquisition completed.
     *
     * The returned text is empty when rollback succeeded and provides a
     * diagnostic suffix when it did not.
     */
    private static String rollbackReservationsOnly(
            IncursionPlanningContext planningContext,
            IncursionPlan incursionPlan
    ) {
        try {
            ActiveIncursionSourceReservationRegistry.remove(
                    planningContext.level(),
                    incursionPlan.getIncursionId()
            );

            return "";
        } catch (RuntimeException rollbackException) {
            return " Reservation rollback also failed: "
                    + describeException(
                    rollbackException
            );
        }
    }

    /**
     * Releases tickets and reservations after persistent-runtime admission
     * failed.
     *
     * Both rollback actions are attempted independently so one failure does
     * not prevent the other cleanup action.
     */
    private static String rollbackTicketedAdmissionResources(
            IncursionPlanningContext planningContext,
            IncursionPlan incursionPlan,
            IncursionChunkLoadPlan chunkLoadPlan
    ) {
        List<String> rollbackFailures =
                new ArrayList<>();

        try {
            IncursionChunkTicketService.releaseAllTickets(
                    planningContext.level(),
                    chunkLoadPlan
            );
        } catch (RuntimeException rollbackException) {
            rollbackFailures.add(
                    "chunk-ticket rollback failed: "
                            + describeException(
                            rollbackException
                    )
            );
        }

        try {
            ActiveIncursionSourceReservationRegistry.remove(
                    planningContext.level(),
                    incursionPlan.getIncursionId()
            );
        } catch (RuntimeException rollbackException) {
            rollbackFailures.add(
                    "reservation rollback failed: "
                            + describeException(
                            rollbackException
                    )
            );
        }

        if (rollbackFailures.isEmpty()) {
            return "";
        }

        return " Rollback warning: "
                + String.join(
                "; ",
                rollbackFailures
        )
                + ".";
    }

    private static String describeException(
            RuntimeException exception
    ) {
        String message =
                exception.getMessage();

        if (message == null
                || message.isBlank()) {

            return exception
                    .getClass()
                    .getSimpleName();
        }

        return message;
    }

    /**
     * Broad starting stage that prevented one selected planned incursion from
     * entering live runtime.
     */
    public enum FailureStage {

        /**
         * One of the focused planners or final validation could not produce a
         * complete IncursionPlan.
         */
        PLANNING,

        /**
         * The completed tactical plan could not produce a valid immutable
         * chunk-load plan.
         */
        CHUNK_LOAD_PLANNING,

        /**
         * The Scenario registry could not construct live runtime from the
         * completed IncursionPlan.
         */
        SCENARIO_CREATION,

        /**
         * The immutable plan, chunk plan, target or initial runtime could not
         * form a valid persistent-incursion record.
         */
        PERSISTENT_STATE_CREATION,

        /**
         * Authoritative physical source reservations could not be registered.
         */
        RESERVATION_REGISTRATION,

        /**
         * The retained and current-wave chunk tickets could not be acquired.
         */
        CHUNK_TICKET_ACQUISITION,

        /**
         * The complete persistent incursion could not enter SavedData and
         * active runtime management.
         */
        ACTIVE_ADMISSION
    }

    /**
     * Successful result of the complete planned-incursion start route.
     */
    public record StartSuccess(
            LivePersistentIncursion livePersistentIncursion,
            ActiveIncursionSourceReservationRegistry
                    .IncursionReservationSnapshot reservationSnapshot,
            IncursionChunkTicketService
                    .TicketOperationResult ticketOperationResult
    ) {

        public StartSuccess {
            Objects.requireNonNull(
                    livePersistentIncursion,
                    "Successful planned start requires a live persistent "
                            + "incursion."
            );

            Objects.requireNonNull(
                    reservationSnapshot,
                    "Successful planned start requires a reservation "
                            + "snapshot."
            );

            Objects.requireNonNull(
                    ticketOperationResult,
                    "Successful planned start requires a chunk-ticket "
                            + "operation result."
            );

            if (!livePersistentIncursion
                    .getIncursionId()
                    .equals(
                            reservationSnapshot.incursionId()
                    )) {

                throw new IllegalArgumentException(
                        "Successful planned start has live incursion ID "
                                + livePersistentIncursion.getIncursionId()
                                + " but reservation snapshot ID "
                                + reservationSnapshot.incursionId()
                                + "."
                );
            }

            if (!livePersistentIncursion
                    .getIncursionId()
                    .equals(
                            ticketOperationResult.incursionId()
                    )) {

                throw new IllegalArgumentException(
                        "Successful planned start has live incursion ID "
                                + livePersistentIncursion.getIncursionId()
                                + " but chunk-ticket operation ID "
                                + ticketOperationResult.incursionId()
                                + "."
                );
            }
        }

        public IncursionPlan incursionPlan() {
            return livePersistentIncursion
                    .getIncursionPlan();
        }

        public IncursionChunkLoadPlan chunkLoadPlan() {
            return livePersistentIncursion
                    .getChunkLoadPlan();
        }
    }

    /**
     * Structured failure returned before live runtime was successfully
     * admitted.
     *
     * Detailed focused-planner information is retained only for PLANNING
     * failures. Other stages provide their own diagnostic message.
     */
    public record StartFailure(
            FailureStage stage,
            IncursionPlanningResult.PlanningFailure planningFailure,
            String message
    ) {

        public StartFailure {
            Objects.requireNonNull(
                    stage,
                    "Planned-incursion failure stage cannot be null."
            );

            if (message == null
                    || message.isBlank()) {

                throw new IllegalArgumentException(
                        "Planned-incursion failure message cannot be blank."
                );
            }

            if (stage == FailureStage.PLANNING
                    && planningFailure == null) {

                throw new IllegalArgumentException(
                        "Planning-stage start failure requires its focused "
                                + "planning failure."
                );
            }

            if (stage != FailureStage.PLANNING
                    && planningFailure != null) {

                throw new IllegalArgumentException(
                        "Only a planning-stage start failure may contain a "
                                + "focused planning failure."
                );
            }
        }

        public static StartFailure planningFailure(
                IncursionPlanningResult.PlanningFailure planningFailure
        ) {
            Objects.requireNonNull(
                    planningFailure,
                    "Planning failure cannot be null."
            );

            return new StartFailure(
                    FailureStage.PLANNING,
                    planningFailure,
                    planningFailure.message()
            );
        }

        public static StartFailure runtimeFailure(
                FailureStage stage,
                String message
        ) {
            if (stage == FailureStage.PLANNING) {
                throw new IllegalArgumentException(
                        "Use planningFailure(...) for planning-stage "
                                + "failures."
                );
            }

            return new StartFailure(
                    stage,
                    null,
                    message
            );
        }

        public boolean isPlanningFailure() {
            return stage == FailureStage.PLANNING;
        }
    }

    /**
     * Complete result of one planned-incursion start attempt.
     *
     * Exactly one of success or failure is present.
     */
    public record StartResult(
            StartSuccess success,
            StartFailure failure
    ) {

        public StartResult {
            boolean hasSuccess =
                    success != null;

            boolean hasFailure =
                    failure != null;

            if (hasSuccess == hasFailure) {
                throw new IllegalArgumentException(
                        "Planned-incursion start result must contain exactly "
                                + "one success or one failure."
                );
            }
        }

        public static StartResult completed(
                StartSuccess success
        ) {
            return new StartResult(
                    Objects.requireNonNull(
                            success,
                            "Completed planned start requires success data."
                    ),
                    null
            );
        }

        public static StartResult failed(
                StartFailure failure
        ) {
            return new StartResult(
                    null,
                    Objects.requireNonNull(
                            failure,
                            "Failed planned start requires failure data."
                    )
            );
        }

        public boolean isSuccessful() {
            return success != null;
        }

        public boolean hasFailed() {
            return failure != null;
        }
    }
}