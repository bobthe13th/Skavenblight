package org.ratden.skavenblight.command.debug;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.ratden.skavenblight.event.skavenIncursion.action.mob.generic.SpawnWolfRats;
import org.ratden.skavenblight.event.skavenIncursion.leadership.LeaderGroup;
import org.ratden.skavenblight.event.skavenIncursion.leadership.LeaderGroupType;
import org.ratden.skavenblight.event.skavenIncursion.leadership.LeaderRank;
import org.ratden.skavenblight.event.skavenIncursion.leadership.LeadershipRegistry;

import java.util.UUID;

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

        LeadershipRegistry leadershipRegistry =
                new LeadershipRegistry(UUID.randomUUID());

        LeaderGroup vermintideGroup = leadershipRegistry.createLeaderGroup(
                LeaderGroupType.VERMINTIDE,
                LeaderRank.NONE
        );

        LeaderGroup fangGroup = leadershipRegistry.createLeaderGroup(
                LeaderGroupType.FANG,
                LeaderRank.NONE
        );

        LeaderGroup clawGroup = leadershipRegistry.createLeaderGroup(
                LeaderGroupType.CLAW,
                LeaderRank.NONE
        );

        LeaderGroup packGroup = leadershipRegistry.createLeaderGroup(
                LeaderGroupType.PACK,
                LeaderRank.NONE
        );

        int spawned = SpawnWolfRats.execute(
                player.serverLevel(),
                player.blockPosition(),
                count,
                leadershipRegistry.createContext(
                        vermintideGroup,
                        fangGroup,
                        clawGroup,
                        packGroup
                )
        ).size();

        source.sendSuccess(
                () -> Component.literal("Debug spawned " + spawned + " wolf rats."),
                false
        );

        return spawned;
    }
}