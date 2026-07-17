package org.ratden.skavenblight.command;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import org.ratden.skavenblight.command.debug.*;

public class SkavenDebugCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("skavendebug")
                        .then(DebugCleanupCommands.register())
                        .then(DebugDifficultyCommands.register())
                        .then(DebugIncursionCommands.register())
                        .then(DebugIncursionLoadTest.register())
                        .then(DebugMobCommands.register())
                        .then(DebugNexusCommands.register())
                        .then(DebugPathingCommands.register())
                        .then(DebugSourceCommands.register())


        );
    }
}