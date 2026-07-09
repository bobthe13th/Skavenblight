package org.ratden.skavenblight.command.debug;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import org.ratden.skavenblight.Config;

public class DebugPathingCommands {

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("pathing")

                // Command: /skavendebug pathing set_multiplier <value>
                .then(Commands.literal("set_multiplier")
                        .then(Commands.argument("value", IntegerArgumentType.integer(1))
                                .executes(context -> {
                                    int val = IntegerArgumentType.getInteger(context, "value");
                                    // Update the live variable for immediate testing
                                    Config.miningPenaltyMultiplier = val;
                                    context.getSource().sendSuccess(() -> Component.literal(
                                            "Mining penalty multiplier temporarily set to " + val
                                    ), false);
                                    return 1;
                                })))

                // Command: /skavendebug pathing set_base <value>
                .then(Commands.literal("set_base")
                        .then(Commands.argument("value", IntegerArgumentType.integer(0))
                                .executes(context -> {
                                    int val = IntegerArgumentType.getInteger(context, "value");
                                    // Update the live variable for immediate testing
                                    Config.miningBasePenalty = val;
                                    context.getSource().sendSuccess(() -> Component.literal(
                                            "Mining base penalty temporarily set to " + val
                                    ), false);
                                    return 1;
                                })))

                // Command: /skavendebug pathing info
                .then(Commands.literal("info")
                        .executes(context -> {
                            context.getSource().sendSuccess(() -> Component.literal(
                                    "Flow Field Pathing Costs"
                                            + "\nMultiplier: " + Config.miningPenaltyMultiplier
                                            + "\nBase Penalty: " + Config.miningBasePenalty
                            ), false);
                            return 1;
                        }));
    }
}