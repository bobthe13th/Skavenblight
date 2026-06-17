package org.ratden.skavenblight.command.debug;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.ratden.skavenblight.event.skavenIncursion.action.SpawnWolfRats;

public class DebugSpawnCommands {

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("spawn")
                .then(Commands.literal("wolf_rat")
                        .executes(context -> spawnWolfRat(context.getSource(), 1))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 50))
                                .executes(context -> spawnWolfRat(
                                        context.getSource(),
                                        IntegerArgumentType.getInteger(context, "count")
                                ))));
    }

    private static int spawnWolfRat(CommandSourceStack source, int count)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {

        ServerPlayer player = source.getPlayerOrException();

        int spawned = SpawnWolfRats.execute(
                player.serverLevel(),
                player.blockPosition(),
                count
        );

        source.sendSuccess(
                () -> Component.literal("Debug spawned " + spawned + " wolf rats."),
                false
        );

        return spawned;
    }
}