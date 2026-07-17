package org.ratden.skavenblight.command.debug;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.ratden.skavenblight.event.skavenIncursion.director.DirectorStartReason;
import org.ratden.skavenblight.event.skavenIncursion.director.SkavenDirector;
import org.ratden.skavenblight.event.skavenIncursion.scenario.generic.WolfRatAssault;

public class DebugIncursionCommands {

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("incursion")
                .then(Commands.literal("start")
                        .then(Commands.literal("wolf_rat_assault")
                                .executes(context -> {
                                    CommandSourceStack source = context.getSource();
                                    ServerPlayer player = source.getPlayerOrException();

                                    boolean started = SkavenDirector.tryStartSpecificScenario(
                                            player,
                                            WolfRatAssault.id(),
                                            DirectorStartReason.DEBUG
                                    );

                                    if (!started) {
                                        source.sendFailure(Component.literal(
                                                "Could not start Wolf Rat Assault. " +
                                                        "Check that an active Nexus or another valid target exists, " +
                                                        "and that the scenario definition is valid."
                                        ));
                                        return 0;
                                    }

                                    source.sendSuccess(
                                            () -> Component.literal(
                                                    "Started Wolf Rat Assault using current world threat " +
                                                            "and complexity."
                                            ),
                                            false
                                    );

                                    return 1;
                                })));
    }

    private DebugIncursionCommands() {
    }
}