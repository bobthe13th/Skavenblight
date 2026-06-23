package org.ratden.skavenblight.command.debug;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.ratden.skavenblight.block.custom.SkavenTunnelSourceBlock;
import org.ratden.skavenblight.block.entity.state.SourceState;
import org.ratden.skavenblight.event.skavenIncursion.action.source.generic.CreateTunnelSource;
import org.ratden.skavenblight.event.skavenIncursion.action.source.generic.SetSourceState;

public class DebugSourceCommands {

    public static LiteralArgumentBuilder<CommandSourceStack> register() {

        return Commands.literal("source")

                .then(Commands.literal("create")

                        .then(Commands.literal("active")
                                .executes(context ->
                                        createSource(
                                                context.getSource(),
                                                SourceState.ACTIVE
                                        )
                                ))

                        .then(Commands.literal("dormant")
                                .executes(context ->
                                        createSource(
                                                context.getSource(),
                                                SourceState.DORMANT
                                        )
                                ))

                        .then(Commands.literal("collapsed")
                                .executes(context ->
                                        createSource(
                                                context.getSource(),
                                                SourceState.COLLAPSED
                                        )
                                ))
                )
                .then(Commands.literal("set")
                        .then(Commands.literal("active")
                                .executes(context -> setSourceState(context.getSource(), SourceState.ACTIVE)))
                        .then(Commands.literal("dormant")
                                .executes(context -> setSourceState(context.getSource(), SourceState.DORMANT)))
                        .then(Commands.literal("collapsed")
                                .executes(context -> setSourceState(context.getSource(), SourceState.COLLAPSED)))
                )
                .then(Commands.literal("info")
                        .executes(context -> sourceInfo(context.getSource()))
                );
    }

    private static int createSource(
            CommandSourceStack source,
            SourceState sourceState
    ) {

        try {

            ServerPlayer player = source.getPlayerOrException();

            BlockHitResult hitResult = (BlockHitResult) player.pick(
                    20.0D,
                    0.0F,
                    false
            );

            if (hitResult.getType() != HitResult.Type.BLOCK) {

                source.sendFailure(
                        Component.literal(
                                "You must be looking at a block."
                        )
                );

                return 0;
            }

            boolean success = CreateTunnelSource.execute(
                    player.serverLevel(),
                    hitResult.getBlockPos().above(),
                    sourceState
            );

            if (success) {

                source.sendSuccess(
                        () -> Component.literal(
                                "Created tunnel source in state: "
                                        + sourceState.getSerializedName()
                        ),
                        false
                );

                return 1;
            }

            source.sendFailure(
                    Component.literal(
                            "Failed to create tunnel source."
                    )
            );

            return 0;

        } catch (Exception exception) {

            source.sendFailure(
                    Component.literal(
                            "Error: " + exception.getMessage()
                    )
            );

            return 0;
        }
    }
    private static int setSourceState(CommandSourceStack source, SourceState sourceState) {
        try {
            ServerPlayer player = source.getPlayerOrException();

            BlockHitResult hitResult = (BlockHitResult) player.pick(
                    20.0D,
                    0.0F,
                    false
            );

            if (hitResult.getType() != HitResult.Type.BLOCK) {
                source.sendFailure(Component.literal("You must be looking at a tunnel source."));
                return 0;
            }

            boolean success = SetSourceState.execute(
                    player.serverLevel(),
                    hitResult.getBlockPos(),
                    sourceState
            );

            if (!success) {
                source.sendFailure(Component.literal("Target block is not a tunnel source."));
                return 0;
            }

            source.sendSuccess(
                    () -> Component.literal(
                            "Set tunnel source state to: " + sourceState.getSerializedName()
                    ),
                    false
            );

            return 1;

        } catch (Exception exception) {
            source.sendFailure(Component.literal("Error: " + exception.getMessage()));
            return 0;
        }
    }

    private static int sourceInfo(CommandSourceStack source) {
        try {
            ServerPlayer player = source.getPlayerOrException();

            BlockHitResult hitResult = (BlockHitResult) player.pick(
                    20.0D,
                    0.0F,
                    false
            );

            if (hitResult.getType() != HitResult.Type.BLOCK) {
                source.sendFailure(Component.literal("You must be looking at a tunnel source."));
                return 0;
            }

            BlockPos pos = hitResult.getBlockPos();
            var blockState = player.serverLevel().getBlockState(pos);

            if (!blockState.hasProperty(SkavenTunnelSourceBlock.SOURCE_STATE)) {
                source.sendFailure(Component.literal("Target block is not a tunnel source."));
                return 0;
            }

            SourceState sourceState = blockState.getValue(SkavenTunnelSourceBlock.SOURCE_STATE);

            source.sendSuccess(
                    () -> Component.literal(
                            "Tunnel Source Info"
                                    + "\nPosition: " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()
                                    + "\nState: " + sourceState.getSerializedName()
                    ),
                    false
            );

            return 1;

        } catch (Exception exception) {
            source.sendFailure(Component.literal("Error: " + exception.getMessage()));
            return 0;
        }
    }

}