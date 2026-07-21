package org.ratden.skavenblight.command.debug;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.ratden.skavenblight.event.skavenIncursion.director.ActiveIncursionManager;
import org.ratden.skavenblight.event.skavenIncursion.director.DirectorStartReason;
import org.ratden.skavenblight.event.skavenIncursion.director.IncursionTargetType;
import org.ratden.skavenblight.event.skavenIncursion.director.SkavenDirector;
import org.ratden.skavenblight.event.skavenIncursion.planning.IncursionPlan;
import org.ratden.skavenblight.event.skavenIncursion.planning.IncursionPlanner;
import org.ratden.skavenblight.event.skavenIncursion.planning.IncursionPlanningContext;
import org.ratden.skavenblight.event.skavenIncursion.planning.IncursionPlanningResult;
import org.ratden.skavenblight.event.skavenIncursion.planning.composition.SourceGroupComposition;
import org.ratden.skavenblight.event.skavenIncursion.planning.front.FrontPlan;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.BasePlacementContext;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceDistanceProfile;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceGroupPlacementPlan;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourcePlacementPlan;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceReservationArea;
import org.ratden.skavenblight.event.skavenIncursion.planning.stratagem.StratagemCatalogue;
import org.ratden.skavenblight.event.skavenIncursion.scenario.ScenarioDefinition;
import org.ratden.skavenblight.event.skavenIncursion.scenario.generic.WolfRatAssault;
import org.ratden.skavenblight.event.skavenIncursion.scenario.testing.CatDogRaid;
import org.ratden.skavenblight.world.NexusTracker;
import org.ratden.skavenblight.world.SkavenblightWorldData;

import java.util.LinkedHashMap;
import java.util.Map;

public class DebugIncursionCommands {

    private static final int DEBUG_PLAYER_BASE_RADIUS = 16;
    private static final int DEBUG_NEXUS_BASE_RADIUS = 32;

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("incursion")
                .then(Commands.literal("start")
                        .then(Commands.literal("wolf_rat_assault")
                                .executes(context ->
                                        startWolfRatAssault(
                                                context.getSource()
                                        )
                                ))
                        .then(Commands.literal("cat_dog_raid")
                                .executes(context ->
                                        startCatDogRaid(
                                                context.getSource()
                                        )
                                )))
                .then(Commands.literal("plan")
                        .then(Commands.literal("wolf_rat_assault")
                                .executes(context ->
                                        planScenario(
                                                context.getSource(),
                                                WolfRatAssault.DEFINITION
                                        )
                                ))
                        .then(Commands.literal("cat_dog_raid")
                                .executes(context ->
                                        planScenario(
                                                context.getSource(),
                                                CatDogRaid.DEFINITION
                                        )
                                )));
    }

    /**
     * Starts the existing legacy runtime Scenario through the Director.
     */
    private static int startWolfRatAssault(
            CommandSourceStack source
    ) throws CommandSyntaxException {
        ServerPlayer player =
                source.getPlayerOrException();

        boolean started =
                SkavenDirector.tryStartSpecificScenario(
                        player,
                        WolfRatAssault.id(),
                        DirectorStartReason.DEBUG
                );

        if (!started) {
            source.sendFailure(
                    Component.literal(
                            "Could not start Wolf Rat Assault. "
                                    + "Check that an active Nexus or another "
                                    + "valid target exists, and that the "
                                    + "Scenario definition is valid."
                    )
            );

            return 0;
        }

        source.sendSuccess(
                () -> Component.literal(
                        "Started Wolf Rat Assault using current world "
                                + "threat and complexity."
                ),
                false
        );

        return 1;
    }

    /**
     * Plans and starts the development-only planning-aware runtime Scenario.
     *
     * This deliberately bypasses normal Director selection because
     * CatDogRaid is test content and is not registered as a production
     * Scenario.
     */
    private static int startCatDogRaid(
            CommandSourceStack source
    ) throws CommandSyntaxException {
        ServerPlayer player =
                source.getPlayerOrException();

        ServerLevel level =
                player.serverLevel();

        PlanningRequest planningRequest =
                createPlanningRequest(
                        level,
                        player.blockPosition(),
                        CatDogRaid.DEFINITION
                );

        IncursionPlanningResult planningResult =
                new IncursionPlanner().plan(
                        planningRequest.planningContext()
                );

        if (planningResult.hasFailed()) {
            sendPlanningFailure(
                    source,
                    CatDogRaid.DEFINITION,
                    planningResult.getFailure()
            );

            return 0;
        }

        IncursionPlan incursionPlan =
                planningResult.getIncursionPlan();

        CatDogRaid catDogRaid =
                new CatDogRaid(
                        level,
                        incursionPlan
                );

        ActiveIncursionManager.addIncursion(
                catDogRaid
        );

        source.sendSuccess(
                () -> Component.literal(
                        "Started planning-aware Cat Dog Raid."
                                + "\nIncursion ID: "
                                + incursionPlan.getIncursionId()
                                + "\nTarget type: "
                                + planningRequest.targetType()
                                + "\nTarget position: "
                                + formatBlockPos(
                                planningRequest.targetPos()
                        )
                                + "\nPlanned threat budget: "
                                + planningRequest.threatBudget()
                                + "\nPlanned complexity budget: "
                                + planningRequest.complexityBudget()
                ),
                false
        );

        return 1;
    }

    /**
     * Runs the planning pipeline without executing the resulting plan.
     *
     * No source blocks are created and no mobs are spawned.
     */
    private static int planScenario(
            CommandSourceStack source,
            ScenarioDefinition scenarioDefinition
    ) throws CommandSyntaxException {
        if (scenarioDefinition == null) {
            throw new IllegalArgumentException(
                    "Debug Scenario definition cannot be null."
            );
        }

        ServerPlayer player =
                source.getPlayerOrException();

        ServerLevel level =
                player.serverLevel();

        PlanningRequest planningRequest =
                createPlanningRequest(
                        level,
                        player.blockPosition(),
                        scenarioDefinition
                );

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

        IncursionPlan incursionPlan =
                planningResult.getIncursionPlan();

        String summary =
                createPlanSummary(
                        incursionPlan,
                        scenarioDefinition,
                        planningRequest.targetType(),
                        planningRequest.targetPos(),
                        planningRequest.baseRadius(),
                        planningRequest.threatBudget(),
                        planningRequest.complexityBudget()
                );

        source.sendSuccess(
                () -> Component.literal(summary),
                false
        );

        return 1;
    }

    private static PlanningRequest createPlanningRequest(
            ServerLevel level,
            BlockPos playerPos,
            ScenarioDefinition scenarioDefinition
    ) {
        IncursionTargetType targetType =
                chooseTargetType(level);

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
                SkavenblightWorldData.get(level);

        int threatBudget =
                worldData.getThreat();

        int complexityBudget =
                worldData.getSchemeComplexity();

        IncursionPlanningContext planningContext =
                new IncursionPlanningContext(
                        level,
                        scenarioDefinition,
                        StratagemCatalogue.STEADY_1,
                        new BasePlacementContext(
                                targetPos,
                                targetType,
                                baseRadius
                        ),
                        SourceDistanceProfile.STANDARD,
                        threatBudget,
                        complexityBudget
                );

        return new PlanningRequest(
                planningContext,
                targetType,
                targetPos,
                baseRadius,
                threatBudget,
                complexityBudget
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

    private static IncursionTargetType chooseTargetType(
            ServerLevel level
    ) {
        if (NexusTracker.hasActiveNexus(level)) {
            return IncursionTargetType.NEXUS;
        }

        return IncursionTargetType.PLAYER;
    }

    private static BlockPos chooseTargetPos(
            ServerLevel level,
            BlockPos playerPos,
            IncursionTargetType targetType
    ) {
        if (targetType == IncursionTargetType.NEXUS
                && NexusTracker.hasActiveNexus(level)) {
            return NexusTracker
                    .getActiveNexusPos(level)
                    .immutable();
        }

        return playerPos.immutable();
    }

    private static int resolveDebugBaseRadius(
            IncursionTargetType targetType
    ) {
        if (targetType == IncursionTargetType.NEXUS) {
            return DEBUG_NEXUS_BASE_RADIUS;
        }

        return DEBUG_PLAYER_BASE_RADIUS;
    }

    private static String createPlanSummary(
            IncursionPlan incursionPlan,
            ScenarioDefinition scenarioDefinition,
            IncursionTargetType targetType,
            BlockPos targetPos,
            int baseRadius,
            int threatBudget,
            int complexityBudget
    ) {
        int totalWaves = 0;
        int totalSourceGroups = 0;
        int totalSources = 0;
        int totalMobs = 0;
        int totalThreatSpent = 0;

        Map<String, Integer> mobCounts =
                new LinkedHashMap<>();

        for (FrontPlan frontPlan
                : incursionPlan.getFrontPlans()) {

            totalWaves +=
                    frontPlan.getWavePlans().size();

            totalSourceGroups +=
                    frontPlan
                            .getSourceGroupPlacementPlans()
                            .size();

            for (FrontPlan.WavePlan wavePlan
                    : frontPlan.getWavePlans()) {

                totalThreatSpent +=
                        wavePlan.getThreatSpent();

                for (SourceGroupComposition groupComposition
                        : wavePlan
                        .getSourceGroupCompositions()) {

                    for (SourceGroupComposition.SourceComposition
                            sourceComposition
                            : groupComposition
                            .getSourceCompositions()) {

                        totalMobs +=
                                sourceComposition
                                        .getTotalMobCount();

                        for (SourceGroupComposition.MobEntry mobEntry
                                : sourceComposition.getMobEntries()) {

                            mobCounts.merge(
                                    mobEntry.getMobId(),
                                    mobEntry.getCount(),
                                    Integer::sum
                            );
                        }
                    }
                }
            }

            for (SourceGroupPlacementPlan groupPlacement
                    : frontPlan
                    .getSourceGroupPlacementPlans()) {

                totalSources +=
                        groupPlacement
                                .getSourcePlacementPlans()
                                .size();
            }
        }

        StringBuilder message =
                new StringBuilder();

        message.append("Incursion planning succeeded.")
                .append("\nIncursion ID: ")
                .append(incursionPlan.getIncursionId())
                .append("\nScenario: ")
                .append(scenarioDefinition.id())
                .append("\nStratagem: ")
                .append(StratagemCatalogue.STEADY_1.getId())
                .append("\nTarget type: ")
                .append(targetType)
                .append("\nTarget position: ")
                .append(formatBlockPos(targetPos))
                .append("\nDebug base radius: ")
                .append(baseRadius)
                .append("\nThreat: ")
                .append(totalThreatSpent)
                .append(" / ")
                .append(threatBudget)
                .append("\nComplexity budget: ")
                .append(complexityBudget)
                .append("\nFronts: ")
                .append(incursionPlan.getFrontCount())
                .append("\nWaves: ")
                .append(totalWaves)
                .append("\nPhysical source groups: ")
                .append(totalSourceGroups)
                .append("\nPhysical sources: ")
                .append(totalSources)
                .append("\nPlanned mobs: ")
                .append(totalMobs)
                .append("\nMob composition:");

        for (Map.Entry<String, Integer> entry
                : mobCounts.entrySet()) {
            message.append("\n  ")
                    .append(entry.getKey())
                    .append(": ")
                    .append(entry.getValue());
        }

        for (FrontPlan frontPlan
                : incursionPlan.getFrontPlans()) {

            message.append("\n\nF")
                    .append(frontPlan.getFrontIndex() + 1)
                    .append(" anchor: ")
                    .append(
                            formatBlockPos(
                                    frontPlan.getAnchorPos()
                            )
                    )
                    .append("\nPlacement pattern: ")
                    .append(frontPlan.getPlacementPattern())
                    .append("\nThreat share: ")
                    .append(
                            String.format(
                                    "%.2f",
                                    frontPlan.getThreatShare()
                            )
                    );

            int groupNumber = 0;

            for (SourceGroupPlacementPlan groupPlacement
                    : frontPlan
                    .getSourceGroupPlacementPlans()) {

                groupNumber++;

                message.append("\nGroup ")
                        .append(groupNumber)
                        .append(" anchor: ")
                        .append(
                                formatBlockPos(
                                        groupPlacement.getAnchorPos()
                                )
                        )
                        .append(" | Role: ")
                        .append(groupPlacement.getSourceRole());

                int sourceNumber = 0;

                for (SourcePlacementPlan sourcePlacement
                        : groupPlacement
                        .getSourcePlacementPlans()) {

                    sourceNumber++;

                    SourceReservationArea.WorldBounds bounds =
                            sourcePlacement
                                    .getReservationBounds();

                    message.append("\n  Source ")
                            .append(sourceNumber)
                            .append(": ")
                            .append(
                                    formatBlockPos(
                                            sourcePlacement
                                                    .getPlacedPos()
                                    )
                            )
                            .append(" | ")
                            .append(sourcePlacement.getSourceSize())
                            .append(" ")
                            .append(sourcePlacement.getSourceType())
                            .append(" | Facing ")
                            .append(sourcePlacement.getFacing())
                            .append(" | Reservation ")
                            .append(bounds.width())
                            .append("x")
                            .append(bounds.depth())
                            .append(" | Bound compositions: ")
                            .append(
                                    sourcePlacement
                                            .getBoundCompositionCount()
                            );
                }
            }
        }

        return message.toString();
    }

    private static String formatBlockPos(
            BlockPos pos
    ) {
        return pos.getX()
                + ", "
                + pos.getY()
                + ", "
                + pos.getZ();
    }

    private record PlanningRequest(
            IncursionPlanningContext planningContext,
            IncursionTargetType targetType,
            BlockPos targetPos,
            int baseRadius,
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

    private DebugIncursionCommands() {
    }
}