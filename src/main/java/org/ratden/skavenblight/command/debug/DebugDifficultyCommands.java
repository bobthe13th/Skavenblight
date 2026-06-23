package org.ratden.skavenblight.command.debug;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import org.ratden.skavenblight.event.skavenIncursion.SkavenDifficultyTracker;

public class DebugDifficultyCommands {

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("difficulty")
                .then(Commands.literal("show")
                        .executes(context -> showDifficulty(context.getSource())))
                .then(Commands.literal("reset")
                        .executes(context -> resetDifficulty(context.getSource())))
                .then(Commands.literal("threat")
                        .then(Commands.literal("set")
                                .then(Commands.argument("value", IntegerArgumentType.integer(0))
                                        .executes(context -> setThreat(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "value")
                                        ))))
                        .then(Commands.literal("add")
                                .then(Commands.argument("amount", IntegerArgumentType.integer(0))
                                        .executes(context -> addThreat(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "amount")
                                        ))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("amount", IntegerArgumentType.integer(0))
                                        .executes(context -> removeThreat(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "amount")
                                        )))))
                .then(Commands.literal("complexity")
                        .then(Commands.literal("set")
                                .then(Commands.argument("value", IntegerArgumentType.integer(0))
                                        .executes(context -> setComplexity(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "value")
                                        ))))
                        .then(Commands.literal("add")
                                .then(Commands.argument("amount", IntegerArgumentType.integer(0))
                                        .executes(context -> addComplexity(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "amount")
                                        ))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("amount", IntegerArgumentType.integer(0))
                                        .executes(context -> removeComplexity(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "amount")
                                        )))));
    }

    private static int showDifficulty(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal(
                "Skaven Difficulty | Threat: " + SkavenblightWorldData.get(level).setThreat()
                        + " | Complexity: " + SkavenDifficultyTracker.getComplexity()
        ), false);

        return 1;
    }

    private static int resetDifficulty(CommandSourceStack source) {
        SkavenDifficultyTracker.setThreat(10);
        SkavenDifficultyTracker.setComplexity(0);

        source.sendSuccess(() -> Component.literal(
                "Skaven difficulty reset. Threat: 10 | Complexity: 0"
        ), false);

        return 1;
    }

    private static int setThreat(CommandSourceStack source, int value) {
        SkavenDifficultyTracker.setThreat(value);
        return showDifficulty(source);
    }

    private static int addThreat(CommandSourceStack source, int amount) {
        SkavenDifficultyTracker.setThreat(SkavenDifficultyTracker.getThreat() + amount);
        return showDifficulty(source);
    }

    private static int removeThreat(CommandSourceStack source, int amount) {
        SkavenDifficultyTracker.setThreat(SkavenDifficultyTracker.getThreat() - amount);
        return showDifficulty(source);
    }

    private static int setComplexity(CommandSourceStack source, int value) {
        SkavenDifficultyTracker.setComplexity(value);
        return showDifficulty(source);
    }

    private static int addComplexity(CommandSourceStack source, int amount) {
        SkavenDifficultyTracker.setComplexity(SkavenDifficultyTracker.getComplexity() + amount);
        return showDifficulty(source);
    }

    private static int removeComplexity(CommandSourceStack source, int amount) {
        SkavenDifficultyTracker.setComplexity(SkavenDifficultyTracker.getComplexity() - amount);
        return showDifficulty(source);
    }
}