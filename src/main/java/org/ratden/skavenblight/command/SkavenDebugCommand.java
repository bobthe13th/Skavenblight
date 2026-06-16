package org.ratden.skavenblight.command;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import org.ratden.skavenblight.command.debug.DebugCleanupCommands;
import org.ratden.skavenblight.command.debug.DebugDifficultyCommands;
import org.ratden.skavenblight.command.debug.DebugIncursionCommands;
import org.ratden.skavenblight.command.debug.DebugSpawnCommands;

public class SkavenDebugCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("skavendebug")
                        .then(DebugSpawnCommands.register())
                        .then(DebugDifficultyCommands.register())
                        .then(DebugIncursionCommands.register())
                        .then(DebugCleanupCommands.register())
        );
    }
}


