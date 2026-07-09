package org.ratden.skavenblight.command.debug;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.ratden.skavenblight.event.skavenIncursion.SkavenIncursionHandler;

public class DebugIncursionCommands {

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("incursion")

                // Command: /skavendebug incursion start clanrat_assault
                .then(Commands.literal("start")
                        .then(Commands.literal("clanrat_assault")
                                .executes(context -> {
                                    CommandSourceStack source = context.getSource();
                                    ServerPlayer player = source.getPlayerOrException();
                                    ServerLevel level = source.getLevel();
                                    BlockPos pos = player.blockPosition();

                                    // Hand the assault off to the central ticking handler
                                    SkavenIncursionHandler.startClanratAssault(level, pos);

                                    source.sendSuccess(() -> Component.literal(
                                            "Initiated Clanrat Assault at " + pos.toShortString()
                                    ), false);
                                    return 1;
                                }))

                        // Command: /skavendebug incursion start wolf_rat_assault
                        .then(Commands.literal("wolf_rat_assault")
                                .executes(context -> {
                                    CommandSourceStack source = context.getSource();
                                    ServerPlayer player = source.getPlayerOrException();
                                    ServerLevel level = source.getLevel();
                                    BlockPos pos = player.blockPosition();

                                    // Hand the assault off to the central ticking handler
                                    SkavenIncursionHandler.startWolfRatAssault(level, pos);

                                    source.sendSuccess(() -> Component.literal(
                                            "Initiated Wolf Rat Assault at " + pos.toShortString()
                                    ), false);
                                    return 1;
                                })));
    }
}