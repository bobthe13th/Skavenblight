package org.ratden.skavenblight.command.debug;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.ratden.skavenblight.block.ModBlocks;
import org.ratden.skavenblight.entity.custom.RatWolf;
import org.ratden.skavenblight.event.skavenIncursion.SkavenIncursionHandler;

public class DebugCleanupCommands {

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("cleanup")
                .then(
                        Commands.literal("wolf_rats")
                                .executes(DebugCleanupCommands::removeWolfRats)
                )
                .then(
                        Commands.literal("incursions")
                                .executes(DebugCleanupCommands::clearIncursions)
                )
                .then(Commands.literal("sources")
                        .executes(context -> cleanupSources(context.getSource()))
                );
    }

    private static int removeWolfRats(
            com.mojang.brigadier.context.CommandContext<CommandSourceStack> context
    ) throws com.mojang.brigadier.exceptions.CommandSyntaxException {

        ServerPlayer player = context.getSource().getPlayerOrException();
        ServerLevel level = player.serverLevel();

        int removed = 0;

        for (RatWolf ratWolf : level.getEntitiesOfClass(
                RatWolf.class,
                player.getBoundingBox().inflate(1000)
        )) {

            ratWolf.discard();
            removed++;
        }

        int finalRemoved = removed;

        context.getSource().sendSuccess(
                () -> net.minecraft.network.chat.Component.literal(
                        "Removed " + finalRemoved + " Wolf Rats."
                ),
                false
        );

        return removed;
    }

    private static int clearIncursions(
            com.mojang.brigadier.context.CommandContext<CommandSourceStack> context
    ) {

        int removed = SkavenIncursionHandler.clearIncursions();

        context.getSource().sendSuccess(
                () -> net.minecraft.network.chat.Component.literal(
                        "Removed " + removed + " active incursions."
                ),
                false
        );

        return removed;
    }
    private static int cleanupSources(CommandSourceStack source) {
        try {

            ServerPlayer player = source.getPlayerOrException();
            ServerLevel level = player.serverLevel();

            int radius = 100;
            int removed = 0;

            BlockPos playerPos = player.blockPosition();

            for (BlockPos pos : BlockPos.betweenClosed(
                    playerPos.offset(-radius, -radius, -radius),
                    playerPos.offset(radius, radius, radius))) {

                if (level.getBlockState(pos)
                        .is(ModBlocks.SKAVEN_TUNNEL_SOURCE.get())) {

                    level.removeBlock(pos, false);
                    removed++;
                }
            }

            int finalRemoved = removed;

            source.sendSuccess(
                    () -> Component.literal(
                            "Removed "
                                    + finalRemoved
                                    + " tunnel sources."
                    ),
                    false
            );

            return removed;

        } catch (Exception exception) {

            source.sendFailure(
                    Component.literal(
                            "Failed to remove tunnel sources: "
                                    + exception.getMessage()
                    )
            );

            return 0;
        }
    }
}