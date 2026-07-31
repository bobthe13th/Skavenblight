package org.ratden.skavenblight.command.debug;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.ratden.skavenblight.magic.Wind;
import org.ratden.skavenblight.magic.wind.ChunkWindState;
import org.ratden.skavenblight.magic.wind.WindGridManager;

public class DebugWindCommands {

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("wind")
                .then(Commands.literal("get")
                        .executes(context -> windGet(context.getSource())));
    }

    private static int windGet(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("This command must be run by a player."));
            return 0;
        }

        ServerLevel level = source.getLevel();
        ChunkPos pos = new ChunkPos(player.blockPosition());
        ChunkWindState state = WindGridManager.get(level).getOrCreate(pos);

        StringBuilder sb = new StringBuilder("Wind levels at chunk " + pos + ":");
        for (Wind wind : Wind.values()) {
            sb.append("\n").append(wind.getLoreName())
                    .append(": current=").append(String.format("%.1f", state.getCurrent(wind)))
                    .append(", baseline=").append(String.format("%.1f", state.getBaseline(wind)));
        }
        String message = sb.toString();
        source.sendSuccess(() -> Component.literal(message), false);
        return 1;
    }
}
