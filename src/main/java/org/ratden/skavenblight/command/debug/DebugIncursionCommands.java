package org.ratden.skavenblight.command.debug;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.ratden.skavenblight.event.skavenIncursion.IncursionTargetType;
import org.ratden.skavenblight.event.skavenIncursion.SkavenIncursionHandler;
import org.ratden.skavenblight.event.skavenIncursion.scenario.WolfRatAssault;

public class DebugIncursionCommands {

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("incursion")
                .then(Commands.literal("start_wolfrat_assault")
                        .executes(context -> startWolfRatAssault(context.getSource(), 1))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 50))
                                .executes(context -> startWolfRatAssault(
                                        context.getSource(),
                                        IntegerArgumentType.getInteger(context, "count")
                                ))));
    }

    private static int startWolfRatAssault(CommandSourceStack source, int count) {
        try {
            ServerPlayer player = source.getPlayerOrException();

            int playerTargets = 0;
            int nexusTargets = 0;

            for (int i = 0; i < count; i++) {
                IncursionTargetType targetType = SkavenIncursionHandler.startWolfRatAssault(
                        player.serverLevel(),
                        player.blockPosition()
                );

                if (targetType == IncursionTargetType.PLAYER) {
                    playerTargets++;
                }

                if (targetType == IncursionTargetType.NEXUS) {
                    nexusTargets++;
                }
            }

            int finalPlayerTargets = playerTargets;
            int finalNexusTargets = nexusTargets;

            source.sendSuccess(
                    () -> Component.literal(
                            "Started " + count + " " + WolfRatAssault.getDebugName() + " incursions."
                                    + "\nPlayer targets: " + finalPlayerTargets
                                    + "\nNexus targets: " + finalNexusTargets
                                    + "\n" + WolfRatAssault.getDebugTimeline()
                    ),
                    false
            );

            return count;

        } catch (Exception exception) {
            source.sendFailure(
                    Component.literal("Failed to start Wolf Rat Assault: " + exception.getMessage())
            );

            return 0;
        }
    }
}