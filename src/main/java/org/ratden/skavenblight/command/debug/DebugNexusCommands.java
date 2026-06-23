package org.ratden.skavenblight.command.debug;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import org.ratden.skavenblight.block.entity.WarpstoneNexusEntity;
import org.ratden.skavenblight.world.NexusTracker;

public class DebugNexusCommands {

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("nexus")
                .then(Commands.literal("info")
                        .executes(context -> nexusInfo(context.getSource())));
    }

    private static int nexusInfo(CommandSourceStack source) {
        ServerLevel level = source.getLevel();

        if (!NexusTracker.hasActiveNexus(level)) {
            source.sendFailure(
                    Component.literal("No active Nexus is currently tracked.")
            );
            return 0;
        }

        BlockPos pos = NexusTracker.getActiveNexusPos(level);
        WarpstoneNexusEntity nexus = NexusTracker.getActiveNexusEntity(level);

        if (nexus == null) {
            source.sendFailure(
                    Component.literal(
                            "Tracked Nexus position exists, but no active Nexus block entity was found at "
                                    + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()
                    )
            );
            return 0;
        }

        source.sendSuccess(
                () -> Component.literal(
                        "Tracked Active Nexus"
                                + "\nPosition: " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()
                                + "\nTier: " + nexus.getNexusTier()
                                + "\nStability: " + nexus.getStability()
                                + "\nFlux: " + nexus.getFluxStorage().getFlux() + " / " + nexus.getFluxStorage().getMaxFlux()
                ),
                false
        );

        return 1;
    }
}