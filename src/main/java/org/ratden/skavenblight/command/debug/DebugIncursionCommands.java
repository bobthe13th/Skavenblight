package org.ratden.skavenblight.command.debug;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

import java.util.concurrent.CompletableFuture;

/**
 * Brigadier command tree for incursion planning, execution and focused
 * runtime diagnostics.
 *
 * Scenario and Stratagem suggestions are read dynamically from the ordinary
 * content architecture rather than being handwritten into this class.
 *
 * Planning and runtime execution are delegated to
 * DebugIncursionCommandService.
 *
 * Target-based runtime inspection is delegated to
 * DebugIncursionInspectionService.
 *
 * This class remains focused only on command structure, argument parsing and
 * autocomplete.
 */
public class DebugIncursionCommands {

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("incursion")
                .then(
                        Commands.literal("start")
                                .then(
                                        createScenarioArgument(
                                                true
                                        )
                                )
                )
                .then(
                        Commands.literal("plan")
                                .then(
                                        createScenarioArgument(
                                                false
                                        )
                                )
                )
                .then(
                        Commands.literal("inspect")
                                .executes(context ->
                                        DebugIncursionInspectionService
                                                .inspectLookedAtSource(
                                                        context.getSource()
                                                )
                                )
                );
    }

    /**
     * Creates the shared argument structure used by both:
     *
     * /skavendebug incursion start <scenario> [stratagem]
     * /skavendebug incursion plan <scenario> [stratagem]
     */
    private static RequiredArgumentBuilder<CommandSourceStack, String>
    createScenarioArgument(
            boolean startScenario
    ) {
        return Commands.argument(
                        "scenario",
                        StringArgumentType.word()
                )
                .suggests(
                        DebugIncursionCommands::suggestScenarioIds
                )
                .executes(context ->
                        execute(
                                context,
                                startScenario,
                                null
                        )
                )
                .then(
                        Commands.argument(
                                        "stratagem",
                                        StringArgumentType.word()
                                )
                                .suggests(
                                        DebugIncursionCommands
                                                ::suggestAllowedStratagemIds
                                )
                                .executes(context ->
                                        execute(
                                                context,
                                                startScenario,
                                                StringArgumentType.getString(
                                                        context,
                                                        "stratagem"
                                                )
                                        )
                                )
                );
    }

    private static int execute(
            CommandContext<CommandSourceStack> context,
            boolean startScenario,
            String requestedStratagemId
    ) throws CommandSyntaxException {
        String scenarioId =
                StringArgumentType.getString(
                        context,
                        "scenario"
                );

        if (startScenario) {
            return DebugIncursionCommandService.start(
                    context.getSource(),
                    scenarioId,
                    requestedStratagemId
            );
        }

        return DebugIncursionCommandService.plan(
                context.getSource(),
                scenarioId,
                requestedStratagemId
        );
    }

    /**
     * Suggests every Scenario registered in the ordinary ScenarioRegistry.
     */
    private static CompletableFuture<Suggestions> suggestScenarioIds(
            CommandContext<CommandSourceStack> context,
            SuggestionsBuilder builder
    ) {
        for (String scenarioId
                : DebugIncursionCommandService.getScenarioIds()) {

            builder.suggest(
                    scenarioId
            );
        }

        return builder.buildFuture();
    }

    /**
     * Suggests only the Stratagems allowed by the Scenario already entered
     * into the command.
     */
    private static CompletableFuture<Suggestions>
    suggestAllowedStratagemIds(
            CommandContext<CommandSourceStack> context,
            SuggestionsBuilder builder
    ) {
        String scenarioId;

        try {
            scenarioId =
                    StringArgumentType.getString(
                            context,
                            "scenario"
                    );
        } catch (IllegalArgumentException exception) {
            return builder.buildFuture();
        }

        for (String stratagemId
                : DebugIncursionCommandService
                .getAllowedStratagemIds(
                        scenarioId
                )) {

            builder.suggest(
                    stratagemId
            );
        }

        return builder.buildFuture();
    }

    private DebugIncursionCommands() {
    }
}