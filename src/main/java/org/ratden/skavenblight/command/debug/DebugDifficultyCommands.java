package org.ratden.skavenblight.command.debug;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import org.ratden.skavenblight.world.SkavenblightWorldData;

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
                                        .executes(context -> setThreat(context.getSource(), IntegerArgumentType.getInteger(context, "value")))))
                        .then(Commands.literal("add")
                                .then(Commands.argument("amount", IntegerArgumentType.integer(0))
                                        .executes(context -> addThreat(context.getSource(), IntegerArgumentType.getInteger(context, "amount")))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("amount", IntegerArgumentType.integer(0))
                                        .executes(context -> removeThreat(context.getSource(), IntegerArgumentType.getInteger(context, "amount"))))))
                .then(Commands.literal("complexity")
                        .then(Commands.literal("set")
                                .then(Commands.argument("value", IntegerArgumentType.integer(0))
                                        .executes(context -> setComplexity(context.getSource(), IntegerArgumentType.getInteger(context, "value")))))
                        .then(Commands.literal("add")
                                .then(Commands.argument("amount", IntegerArgumentType.integer(0))
                                        .executes(context -> addComplexity(context.getSource(), IntegerArgumentType.getInteger(context, "amount")))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("amount", IntegerArgumentType.integer(0))
                                        .executes(context -> removeComplexity(context.getSource(), IntegerArgumentType.getInteger(context, "amount"))))));
    }

    private static SkavenblightWorldData getData(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        return SkavenblightWorldData.get(level);
    }

    private static int showDifficulty(CommandSourceStack source) {
        SkavenblightWorldData data = getData(source);

        source.sendSuccess(() -> Component.literal(
                "Skaven Difficulty | Threat: " + data.getThreat()
                        + " | Complexity: " + data.getSchemeComplexity()
                        + " | Scheme Progress: " + data.getSchemeProgress()
        ), false);

        return 1;
    }

    private static int resetDifficulty(CommandSourceStack source) {
        SkavenblightWorldData data = getData(source);

        data.setThreat(10);
        data.setSchemeComplexity(0);
        data.setSchemeProgress(0);

        return showDifficulty(source);
    }

    private static int setThreat(CommandSourceStack source, int value) {
        getData(source).setThreat(value);
        return showDifficulty(source);
    }

    private static int addThreat(CommandSourceStack source, int amount) {
        SkavenblightWorldData data = getData(source);
        data.setThreat(data.getThreat() + amount);
        return showDifficulty(source);
    }

    private static int removeThreat(CommandSourceStack source, int amount) {
        SkavenblightWorldData data = getData(source);
        data.setThreat(data.getThreat() - amount);
        return showDifficulty(source);
    }

    private static int setComplexity(CommandSourceStack source, int value) {
        getData(source).setSchemeComplexity(value);
        return showDifficulty(source);
    }

    private static int addComplexity(CommandSourceStack source, int amount) {
        SkavenblightWorldData data = getData(source);
        data.setSchemeComplexity(data.getSchemeComplexity() + amount);
        return showDifficulty(source);
    }

    private static int removeComplexity(CommandSourceStack source, int amount) {
        SkavenblightWorldData data = getData(source);
        data.setSchemeComplexity(data.getSchemeComplexity() - amount);
        return showDifficulty(source);
    }
}