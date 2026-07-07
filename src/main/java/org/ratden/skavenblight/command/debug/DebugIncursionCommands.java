package org.ratden.skavenblight.command.debug;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.ratden.skavenblight.event.skavenIncursion.director.ActiveIncursionManager;
import org.ratden.skavenblight.event.skavenIncursion.director.IncursionTargetType;
import org.ratden.skavenblight.event.skavenIncursion.scenario.ScenarioRegistry;
import org.ratden.skavenblight.event.skavenIncursion.scenario.SkavenScenario;
import org.ratden.skavenblight.world.NexusTracker;

public class DebugIncursionCommands {

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("incursion")
                .then(Commands.literal("start")
                        .then(Commands.argument("scenario_id", StringArgumentType.word())
                                .executes(context -> startScenario(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "scenario_id"),
                                        1
                                ))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 50))
                                        .executes(context -> startScenario(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "scenario_id"),
                                                IntegerArgumentType.getInteger(context, "count")
                                        )))))
                .then(DebugIncursionLoadTest.register());
    }

    private static int startScenario(CommandSourceStack source, String scenarioId, int count) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            ServerLevel level = player.serverLevel();

            int started = 0;
            int failed = 0;
            int playerTargets = 0;
            int nexusTargets = 0;

            for (int i = 0; i < count; i++) {
                IncursionTargetType targetType = chooseDebugTargetType(level);
                BlockPos targetPos = chooseDebugTargetPos(level, player.blockPosition(), targetType);

                SkavenScenario scenario = ScenarioRegistry.createScenario(
                        scenarioId,
                        level,
                        targetPos,
                        targetType
                );

                if (scenario == null) {
                    failed++;
                    continue;
                }

                ActiveIncursionManager.addIncursion(scenario);
                started++;

                if (targetType == IncursionTargetType.PLAYER) {
                    playerTargets++;
                }

                if (targetType == IncursionTargetType.NEXUS) {
                    nexusTargets++;
                }
            }

            int finalStarted = started;
            int finalFailed = failed;
            int finalPlayerTargets = playerTargets;
            int finalNexusTargets = nexusTargets;

            source.sendSuccess(
                    () -> Component.literal(
                            "Started scenario: " + scenarioId
                                    + "\nStarted: " + finalStarted
                                    + "\nFailed: " + finalFailed
                                    + "\nPlayer targets: " + finalPlayerTargets
                                    + "\nNexus targets: " + finalNexusTargets
                    ),
                    false
            );

            return started;

        } catch (Exception exception) {
            source.sendFailure(
                    Component.literal("Failed to start scenario: " + exception.getMessage())
            );

            return 0;
        }
    }

    private static IncursionTargetType chooseDebugTargetType(ServerLevel level) {
        if (NexusTracker.hasActiveNexus(level) && level.random.nextBoolean()) {
            return IncursionTargetType.NEXUS;
        }

        return IncursionTargetType.PLAYER;
    }

    private static BlockPos chooseDebugTargetPos(
            ServerLevel level,
            BlockPos fallbackPlayerPos,
            IncursionTargetType targetType
    ) {
        if (targetType == IncursionTargetType.NEXUS
                && NexusTracker.hasActiveNexus(level)) {
            return NexusTracker.getActiveNexusPos(level);
        }

        return fallbackPlayerPos;
    }
}