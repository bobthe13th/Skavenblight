package org.ratden.skavenblight.command.debug;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.ratden.skavenblight.event.skavenIncursion.SkavenDifficultyTracker;
import org.ratden.skavenblight.event.skavenIncursion.SkavenIncursionHandler;
import org.ratden.skavenblight.event.skavenIncursion.scenario.WolfRatAssault;

public class DebugIncursionCommands {

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("incursion")
                .then(Commands.literal("start_wolfrat_assault")
                        .executes(context -> startWolfRatAssault(context.getSource())));
    }

    private static int startWolfRatAssault(CommandSourceStack source)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {

        ServerPlayer player = source.getPlayerOrException();

        int threat = SkavenDifficultyTracker.getThreat();
        int complexity = SkavenDifficultyTracker.getComplexity();
        int expectedWolfRats = WolfRatAssault.calculateWolfRatCount();

        SkavenIncursionHandler.startWolfRatAssault(
                player.serverLevel(),
                player.blockPosition()
        );

        source.sendSuccess(
                () -> Component.literal(
                        "Started Wolf Rat Assault | Threat: " + threat
                                + " | Complexity: " + complexity
                                + " | Formula: 2 + threat/10"
                                + " + 1 complexity bonus when complexity >= 1"
                                + " | Expected wolf rats: " + expectedWolfRats
                ),
                false
        );

        return 1;
    }
}