package org.ratden.skavenblight.command.debug;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.ratden.skavenblight.magic.corruption.Corruption;
import org.ratden.skavenblight.magic.corruption.CorruptionTier;

public class DebugCorruptionCommands {

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("corruption")
                .then(Commands.literal("grant")
                        .then(Commands.argument("amount", IntegerArgumentType.integer())
                                .executes(context -> grant(context.getSource(),
                                        IntegerArgumentType.getInteger(context, "amount")))))
                .then(Commands.literal("info")
                        .executes(context -> info(context.getSource())));
    }

    private static int grant(CommandSourceStack source, int amount) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("This command must be run by a player."));
            return 0;
        }
        CorruptionTier tier = Corruption.grant(player, amount);
        source.sendSuccess(() -> Component.literal(
                "Corruption now " + Corruption.getPoints(player) + " (tier: " + tier.getSerializedName() + ")"), true);
        return 1;
    }

    private static int info(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("This command must be run by a player."));
            return 0;
        }
        int points = Corruption.getPoints(player);
        CorruptionTier tier = Corruption.getTier(player);
        source.sendSuccess(() -> Component.literal(
                "Corruption: " + points + " points (tier: " + tier.getSerializedName() + ")"), false);
        return 1;
    }
}
