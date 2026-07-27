package org.ratden.skavenblight.event.skavenIncursion.director;

import org.ratden.skavenblight.event.skavenIncursion.planning.IncursionPlan;
import org.ratden.skavenblight.event.skavenIncursion.planning.IncursionPlanner;
import org.ratden.skavenblight.event.skavenIncursion.planning.IncursionPlanningContext;
import org.ratden.skavenblight.event.skavenIncursion.planning.IncursionPlanningResult;
import org.ratden.skavenblight.event.skavenIncursion.planning.persistence.IncursionPlanSnapshot;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.ActiveIncursionSourceReservationRegistry;
import org.ratden.skavenblight.event.skavenIncursion.runtime.persistence.IncursionTargetSnapshot;
import org.ratden.skavenblight.event.skavenIncursion.runtime.persistence.LivePersistentIncursion;
import org.ratden.skavenblight.event.skavenIncursion.runtime.persistence.PersistableSkavenScenario;
import org.ratden.skavenblight.event.skavenIncursion.runtime.persistence.PlannedScenarioRuntimeSnapshot;
import org.ratden.skavenblight.event.skavenIncursion.scenario.ScenarioRegistry;

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
 * 2. planned Scenario construction;
 * 3. immutable plan and target snapshot capture;
 * 4. LivePersistentIncursion construction;
 * 5. authoritative source-reservation registration;
 * 6. transactional persistent-runtime admission;
 * 7. reservation rollback when admission fails.
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

    public PlannedIncursionStartService() {
        this(
                new IncursionPlanner()
        );
    }

    public PlannedIncursionStartService(
            IncursionPlanner incursionPlanner
    ) {
        this.incursionPlanner =
                Objects.requireNonNull(
                        incursionPlanner,
                        "Incursion planner cannot be null."
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
             * They must exist before runtime admission so another incursion
             * cannot plan through this incursion's future source positions.
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
             * The reservation registry is outside
             * ActiveIncursionManager's transaction, so this service owns its
             * rollback when runtime admission fails.
             */
            ActiveIncursionSourceReservationRegistry.remove(
                    planningContext.level(),
                    incursionPlan.getIncursionId()
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
                    )
            );
        }

        return StartResult.completed(
                new StartSuccess(
                        livePersistentIncursion,
                        reservationSnapshot
                )
        );
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
         * The Scenario registry could not construct live runtime from the
         * completed IncursionPlan.
         */
        SCENARIO_CREATION,

        /**
         * The immutable plan, target or initial runtime could not form a
         * valid persistent-incursion record.
         */
        PERSISTENT_STATE_CREATION,

        /**
         * Authoritative physical source reservations could not be registered.
         */
        RESERVATION_REGISTRATION,

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
                    .IncursionReservationSnapshot reservationSnapshot
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
        }

        public IncursionPlan incursionPlan() {
            return livePersistentIncursion
                    .getIncursionPlan();
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