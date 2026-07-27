package org.ratden.skavenblight.command.debug;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.ratden.skavenblight.event.skavenIncursion.debug.DebugIncursionAnchorPlacementService;
import org.ratden.skavenblight.event.skavenIncursion.debug.DebugIncursionAnchorTracker;
import org.ratden.skavenblight.event.skavenIncursion.debug.IncursionPlanDebugFormatter;
import org.ratden.skavenblight.event.skavenIncursion.director.DirectorStartReason;
import org.ratden.skavenblight.event.skavenIncursion.director.IncursionTargetType;
import org.ratden.skavenblight.event.skavenIncursion.director.SkavenDirector;
import org.ratden.skavenblight.event.skavenIncursion.planning.IncursionPlan;
import org.ratden.skavenblight.event.skavenIncursion.planning.IncursionPlanner;
import org.ratden.skavenblight.event.skavenIncursion.planning.IncursionPlanningContext;
import org.ratden.skavenblight.event.skavenIncursion.planning.IncursionPlanningResult;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.ActiveIncursionSourceReservationRegistry;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.BasePlacementContext;
import org.ratden.skavenblight.event.skavenIncursion.planning.stratagem.StratagemCatalogue;
import org.ratden.skavenblight.event.skavenIncursion.planning.stratagem.StratagemDefinition;
import org.ratden.skavenblight.event.skavenIncursion.scenario.ScenarioDefinition;
import org.ratden.skavenblight.event.skavenIncursion.scenario.ScenarioRegistry;
import org.ratden.skavenblight.world.NexusTracker;
import org.ratden.skavenblight.world.SkavenblightWorldData;
import org.ratden.skavenblight.event.skavenIncursion.director.PlannedIncursionStartService;

import java.util.List;

/**
 * Executes the debug incursion commands without embedding planning and runtime
 * orchestration inside the Brigadier command tree.
 *
 * This class uses the ordinary Scenario registry, Stratagem catalogue,
 * planning pipeline and runtime factories. It does not define a parallel set
 * of debug-only content registrations.
 *
 * Planning-aware starts also construct the complete persistent-incursion
 * record before entering live runtime.
 */
final class DebugIncursionCommandService {

    private static final int DEBUG_PLAYER_BASE_RADIUS =
            16;

    static int start(
            CommandSourceStack source,
            String scenarioId,
            String requestedStratagemId
    ) throws CommandSyntaxException {
        ScenarioDefinition scenarioDefinition =
                resolveScenarioDefinition(
                        source,
                        scenarioId
                );

        if (scenarioDefinition == null) {
            return 0;
        }

        if (ScenarioRegistry.supportsPlannedCreation(
                scenarioDefinition.id()
        )) {
            return startPlannedScenario(
                    source,
                    scenarioDefinition,
                    requestedStratagemId
            );
        }

        if (ScenarioRegistry.supportsLegacyCreation(
                scenarioDefinition.id()
        )) {
            return startLegacyScenario(
                    source,
                    scenarioDefinition,
                    requestedStratagemId
            );
        }

        source.sendFailure(
                Component.literal(
                        "Scenario "
                                + scenarioDefinition.id()
                                + " is registered, but it has no runtime "
                                + "factory."
                )
        );

        return 0;
    }

    static int plan(
            CommandSourceStack source,
            String scenarioId,
            String requestedStratagemId
    ) throws CommandSyntaxException {
        ScenarioDefinition scenarioDefinition =
                resolveScenarioDefinition(
                        source,
                        scenarioId
                );

        if (scenarioDefinition == null) {
            return 0;
        }

        ServerPlayer player =
                source.getPlayerOrException();

        ServerLevel level =
                player.serverLevel();

        StratagemDefinition stratagemDefinition =
                resolveStratagemDefinition(
                        source,
                        level,
                        scenarioDefinition,
                        requestedStratagemId
                );

        if (stratagemDefinition == null) {
            return 0;
        }

        PlanningRequest planningRequest =
                createPlanningRequest(
                        level,
                        player.blockPosition(),
                        scenarioDefinition,
                        stratagemDefinition
                );

        if (planningRequest == null) {
            sendNoValidTargetFailure(
                    source,
                    scenarioDefinition
            );

            return 0;
        }

        IncursionPlanningResult planningResult =
                new IncursionPlanner().plan(
                        planningRequest.planningContext()
                );

        if (planningResult.hasFailed()) {
            sendPlanningFailure(
                    source,
                    scenarioDefinition,
                    planningResult.getFailure()
            );

            return 0;
        }

        String summary =
                IncursionPlanDebugFormatter.format(
                        planningResult.getIncursionPlan(),
                        planningRequest.planningContext()
                );

        source.sendSuccess(
                () -> Component.literal(
                        summary
                ),
                false
        );

        return 1;
    }

    static List<String> getScenarioIds() {
        return ScenarioRegistry.getDefinitions()
                .stream()
                .map(
                        ScenarioDefinition::id
                )
                .toList();
    }

    static List<String> getAllowedStratagemIds(
            String scenarioId
    ) {
        ScenarioDefinition scenarioDefinition =
                ScenarioRegistry.getDefinition(
                        scenarioId
                );

        if (scenarioDefinition == null) {
            return List.of();
        }

        return scenarioDefinition.allowedStratagems()
                .stream()
                .map(
                        StratagemDefinition::getId
                )
                .toList();
    }

    /**
     * Resolves debug-selected inputs, delegates authoritative planning and
     * persistent runtime admission to PlannedIncursionStartService, then adds
     * optional debug-only reporting and anchor markers.
     *
     * The shared start service owns:
     *
     * - complete planning;
     * - planned Scenario construction;
     * - immutable snapshot capture;
     * - source-reservation registration;
     * - persistent runtime admission;
     * - admission rollback.
     *
     * This debug command retains only:
     *
     * - explicit Scenario and Stratagem selection;
     * - debug target construction;
     * - command feedback;
     * - optional debug-anchor placement.
     */
    private static int startPlannedScenario(
            CommandSourceStack source,
            ScenarioDefinition scenarioDefinition,
            String requestedStratagemId
    ) throws CommandSyntaxException {
        ServerPlayer player =
                source.getPlayerOrException();

        ServerLevel level =
                player.serverLevel();

        StratagemDefinition stratagemDefinition =
                resolveStratagemDefinition(
                        source,
                        level,
                        scenarioDefinition,
                        requestedStratagemId
                );

        if (stratagemDefinition == null) {
            return 0;
        }

        PlanningRequest planningRequest =
                createPlanningRequest(
                        level,
                        player.blockPosition(),
                        scenarioDefinition,
                        stratagemDefinition
                );

        if (planningRequest == null) {
            sendNoValidTargetFailure(
                    source,
                    scenarioDefinition
            );

            return 0;
        }

        PlannedIncursionStartService.StartResult startResult =
                new PlannedIncursionStartService().start(
                        planningRequest.planningContext()
                );

        if (startResult.hasFailed()) {
            sendPlannedStartFailure(
                    source,
                    scenarioDefinition,
                    startResult.failure()
            );

            return 0;
        }

        PlannedIncursionStartService.StartSuccess startSuccess =
                startResult.success();

        IncursionPlan incursionPlan =
                startSuccess.incursionPlan();

        ActiveIncursionSourceReservationRegistry
                .IncursionReservationSnapshot
                reservationSnapshot =
                startSuccess.reservationSnapshot();

        /*
         * Debug anchors remain deliberately outside the authoritative
         * planned-start transaction.
         *
         * Failure to place optional inspection markers must not cancel a
         * successfully planned, reserved and persistently admitted incursion.
         */
        DebugIncursionAnchorPlacementService.PlacementResult
                debugAnchorPlacement =
                DebugIncursionAnchorTracker.placeAndTrack(
                        level,
                        incursionPlan
                );

        String planSummary =
                IncursionPlanDebugFormatter.format(
                        incursionPlan,
                        planningRequest.planningContext()
                );

        source.sendSuccess(
                () -> Component.literal(
                        "Started persistent planning-aware Scenario."
                                + "\n"
                                + planSummary
                                + formatReservationRegistration(
                                reservationSnapshot
                        )
                                + "\nPersistent record: admitted."
                                + formatDebugAnchorPlacement(
                                debugAnchorPlacement
                        )
                ),
                false
        );

        return 1;
    }

    private static int startLegacyScenario(
            CommandSourceStack source,
            ScenarioDefinition scenarioDefinition,
            String requestedStratagemId
    ) throws CommandSyntaxException {
        if (requestedStratagemId != null) {
            source.sendFailure(
                    Component.literal(
                            "Scenario "
                                    + scenarioDefinition.id()
                                    + " still uses the legacy runtime and "
                                    + "cannot yet be started with a forced "
                                    + "Stratagem. Use the command without the "
                                    + "Stratagem argument, or use plan to "
                                    + "inspect that pairing."
                    )
            );

            return 0;
        }

        ServerPlayer player =
                source.getPlayerOrException();

        boolean started =
                SkavenDirector.tryStartSpecificScenario(
                        player,
                        scenarioDefinition.id(),
                        DirectorStartReason.DEBUG
                );

        if (!started) {
            source.sendFailure(
                    Component.literal(
                            "Could not start legacy Scenario: "
                                    + scenarioDefinition.id()
                                    + ". Check that it has a valid target and "
                                    + "passes its Director eligibility rules."
                    )
            );

            return 0;
        }

        source.sendSuccess(
                () -> Component.literal(
                        "Started legacy Scenario through the Director: "
                                + scenarioDefinition.id()
                ),
                false
        );

        return 1;
    }

    private static ScenarioDefinition resolveScenarioDefinition(
            CommandSourceStack source,
            String scenarioId
    ) {
        ScenarioDefinition scenarioDefinition =
                ScenarioRegistry.getDefinition(
                        scenarioId
                );

        if (scenarioDefinition != null) {
            return scenarioDefinition;
        }

        source.sendFailure(
                Component.literal(
                        "Unknown Scenario: "
                                + scenarioId
                                + "."
                )
        );

        return null;
    }

    /**
     * Resolves an explicitly requested legal Stratagem, or uniformly selects
     * one from the Scenario's allowed list when the command omits it.
     *
     * Uniform selection is temporary. The future Director selection policy
     * can replace it without changing the command syntax.
     */
    private static StratagemDefinition resolveStratagemDefinition(
            CommandSourceStack source,
            ServerLevel level,
            ScenarioDefinition scenarioDefinition,
            String requestedStratagemId
    ) {
        if (requestedStratagemId == null) {
            int selectedIndex =
                    level.random.nextInt(
                            scenarioDefinition
                                    .allowedStratagems()
                                    .size()
                    );

            return scenarioDefinition
                    .allowedStratagems()
                    .get(
                            selectedIndex
                    );
        }

        StratagemDefinition stratagemDefinition =
                StratagemCatalogue.getById(
                        requestedStratagemId
                );

        if (stratagemDefinition == null) {
            source.sendFailure(
                    Component.literal(
                            "Unknown Stratagem: "
                                    + requestedStratagemId
                                    + "."
                    )
            );

            return null;
        }

        if (scenarioDefinition.allowsStratagem(
                stratagemDefinition
        )) {
            return stratagemDefinition;
        }

        source.sendFailure(
                Component.literal(
                        "Stratagem "
                                + requestedStratagemId
                                + " is not allowed for Scenario "
                                + scenarioDefinition.id()
                                + ". Allowed Stratagems: "
                                + formatAllowedStratagemIds(
                                scenarioDefinition
                        )
                )
        );

        return null;
    }

    private static PlanningRequest createPlanningRequest(
            ServerLevel level,
            BlockPos playerPos,
            ScenarioDefinition scenarioDefinition,
            StratagemDefinition stratagemDefinition
    ) {
        if (scenarioDefinition.requiresActiveNexus()
                && !NexusTracker.hasActiveNexus(
                level
        )) {
            return null;
        }

        IncursionTargetType targetType =
                chooseTargetType(
                        level,
                        scenarioDefinition
                );

        if (targetType == null) {
            return null;
        }

        BlockPos targetPos =
                chooseTargetPos(
                        level,
                        playerPos,
                        targetType
                );

        int baseRadius =
                resolveDebugBaseRadius(
                        targetType
                );

        SkavenblightWorldData worldData =
                SkavenblightWorldData.get(
                        level
                );

        int threatBudget =
                worldData.getThreat();

        int complexityBudget =
                worldData.getSchemeComplexity();

        IncursionPlanningContext planningContext =
                new IncursionPlanningContext(
                        level,
                        scenarioDefinition,
                        stratagemDefinition,
                        new BasePlacementContext(
                                targetPos,
                                targetType,
                                baseRadius
                        ),
                        scenarioDefinition.sourceDistanceProfile(),
                        threatBudget,
                        complexityBudget
                );

        return new PlanningRequest(
                planningContext,
                targetType,
                targetPos,
                threatBudget,
                complexityBudget
        );
    }

    private static IncursionTargetType chooseTargetType(
            ServerLevel level,
            ScenarioDefinition scenarioDefinition
    ) {
        boolean canTargetNexus =
                scenarioDefinition.allowsTargetType(
                        IncursionTargetType.NEXUS
                )
                        && NexusTracker.hasActiveNexus(
                        level
                );

        if (canTargetNexus) {
            return IncursionTargetType.NEXUS;
        }

        if (scenarioDefinition.allowsTargetType(
                IncursionTargetType.PLAYER
        )) {
            return IncursionTargetType.PLAYER;
        }

        return null;
    }

    private static BlockPos chooseTargetPos(
            ServerLevel level,
            BlockPos playerPos,
            IncursionTargetType targetType
    ) {
        if (targetType == IncursionTargetType.NEXUS
                && NexusTracker.hasActiveNexus(
                level
        )) {
            return NexusTracker
                    .getActiveNexusPos(
                            level
                    )
                    .immutable();
        }

        return playerPos.immutable();
    }

    private static int resolveDebugBaseRadius(
            IncursionTargetType targetType
    ) {
        if (targetType == IncursionTargetType.PLAYER) {
            return DEBUG_PLAYER_BASE_RADIUS;
        }

        /*
         * Nexus-targeted planning measures from the exact protected Warp Flux
         * network snapshot rather than an assumed circular base radius.
         */
        return 0;
    }

    /**
     * Converts the shared planned-start service's structured failure into
     * command feedback.
     *
     * Focused planner failures retain their detailed planning stage and
     * reason. Failures after planning report the broader admission stage
     * owned by PlannedIncursionStartService.
     */
    private static void sendPlannedStartFailure(
            CommandSourceStack source,
            ScenarioDefinition scenarioDefinition,
            PlannedIncursionStartService.StartFailure failure
    ) {
        if (failure == null) {
            throw new IllegalArgumentException(
                    "Planned-incursion start failure cannot be null."
            );
        }

        if (failure.isPlanningFailure()) {
            sendPlanningFailure(
                    source,
                    scenarioDefinition,
                    failure.planningFailure()
            );

            return;
        }

        source.sendFailure(
                Component.literal(
                        "Could not start planning-aware Scenario."
                                + "\nScenario: "
                                + scenarioDefinition.id()
                                + "\nStage: "
                                + failure.stage()
                                + "\nDetails: "
                                + failure.message()
                )
        );
    }

    private static void sendPlanningFailure(
            CommandSourceStack source,
            ScenarioDefinition scenarioDefinition,
            IncursionPlanningResult.PlanningFailure failure
    ) {
        source.sendFailure(
                Component.literal(
                        "Incursion planning failed."
                                + "\nScenario: "
                                + scenarioDefinition.id()
                                + "\nStage: "
                                + failure.stage()
                                + "\nReason: "
                                + failure.reason()
                                + "\nDetails: "
                                + failure.message()
                )
        );
    }

    private static void sendNoValidTargetFailure(
            CommandSourceStack source,
            ScenarioDefinition scenarioDefinition
    ) {
        source.sendFailure(
                Component.literal(
                        "Scenario "
                                + scenarioDefinition.id()
                                + " has no currently valid debug target. "
                                + "Check its allowed target types and active "
                                + "Nexus requirement."
                )
        );
    }

    private static String formatDebugAnchorPlacement(
            DebugIncursionAnchorPlacementService.PlacementResult
                    placementResult
    ) {
        if (placementResult.successful()) {
            return "\nDebug anchors: "
                    + placementResult.getAnchorCount()
                    + " invisible marker"
                    + (placementResult.getAnchorCount() == 1
                    ? ""
                    : "s")
                    + " placed.";
        }

        return "\nDebug anchors: placement failed. "
                + placementResult.failureMessage()
                + " The Scenario was still started because debug markers "
                + "do not own or control runtime execution.";
    }

    private static String formatReservationRegistration(
            ActiveIncursionSourceReservationRegistry
                    .IncursionReservationSnapshot reservationSnapshot
    ) {
        if (reservationSnapshot == null) {
            return "\nActive reservations: none.";
        }

        int sourceGroupCount =
                reservationSnapshot.getSourceGroupCount();

        int sourceCount =
                reservationSnapshot.getSourceCount();

        return "\nActive reservations: "
                + sourceGroupCount
                + " source group"
                + (sourceGroupCount == 1
                ? ""
                : "s")
                + ", "
                + sourceCount
                + " physical source"
                + (sourceCount == 1
                ? ""
                : "s")
                + ".";
    }

    private static String formatAllowedStratagemIds(
            ScenarioDefinition scenarioDefinition
    ) {
        return String.join(
                ", ",
                scenarioDefinition.allowedStratagems()
                        .stream()
                        .map(
                                StratagemDefinition::getId
                        )
                        .toList()
        );
    }

    private record PlanningRequest(
            IncursionPlanningContext planningContext,
            IncursionTargetType targetType,
            BlockPos targetPos,
            int threatBudget,
            int complexityBudget
    ) {

        private PlanningRequest {
            if (planningContext == null) {
                throw new IllegalArgumentException(
                        "Planning context cannot be null."
                );
            }

            if (targetType == null) {
                throw new IllegalArgumentException(
                        "Planning target type cannot be null."
                );
            }

            if (targetPos == null) {
                throw new IllegalArgumentException(
                        "Planning target position cannot be null."
                );
            }

            targetPos =
                    targetPos.immutable();
        }
    }

    private DebugIncursionCommandService() {
    }
}