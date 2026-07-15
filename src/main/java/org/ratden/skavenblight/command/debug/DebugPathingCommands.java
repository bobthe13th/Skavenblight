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
                                    Config.miningBasePenalty = val;
                                    context.getSource().sendSuccess(() -> Component.literal(
                                            "Mining base penalty temporarily set to " + val
                                    ), false);
                                    return 1;
                                })))

                // Command: /skavendebug pathing set_max_nodes <value>
                .then(Commands.literal("set_max_nodes")
                        .then(Commands.argument("value", IntegerArgumentType.integer(1000))
                                .executes(context -> {
                                    int val = IntegerArgumentType.getInteger(context, "value");
                                    Config.maxFlowFieldNodes = val;
                                    context.getSource().sendSuccess(() -> Component.literal(
                                            "Max Flow Field nodes temporarily set to " + val
                                    ), false);
                                    return 1;
                                })))

                // Command: /skavendebug pathing set_settle_delay <value>
                .then(Commands.literal("set_settle_delay")
                        .then(Commands.argument("value", IntegerArgumentType.integer(0))
                                .executes(context -> {
                                    int val = IntegerArgumentType.getInteger(context, "value");
                                    Config.minimumSettleDelayMs = val;
                                    context.getSource().sendSuccess(() -> Component.literal(
                                            "Minimum settle delay temporarily set to " + val + "ms"
                                    ), false);
                                    return 1;
                                })))

                // Command: /skavendebug pathing info
                .then(Commands.literal("info")
                        .executes(context -> {
                            context.getSource().sendSuccess(() -> Component.literal(
                                    "Flow Field Pathing Info"
                                            + "\nMultiplier: " + Config.miningPenaltyMultiplier
                                            + "\nBase Penalty: " + Config.miningBasePenalty
                                            + "\nMax Nodes (Field Size): " + Config.maxFlowFieldNodes
                                            + "\nMinimum Settle Delay: " + Config.minimumSettleDelayMs + "ms"
                            ), false);
                            return 1;
                        }));
    }
}