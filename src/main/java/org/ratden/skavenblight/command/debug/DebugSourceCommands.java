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
import org.ratden.skavenblight.block.entity.SkavenTunnelSourceEntity;
import org.ratden.skavenblight.block.entity.state.SourceState;
import org.ratden.skavenblight.event.skavenIncursion.action.source.generic.CreateTunnelSource;
import org.ratden.skavenblight.event.skavenIncursion.action.source.generic.SetSourceState;
import org.ratden.skavenblight.event.skavenIncursion.leadership.LeaderGroup;
import org.ratden.skavenblight.event.skavenIncursion.leadership.LeaderGroupType;
import org.ratden.skavenblight.event.skavenIncursion.leadership.LeaderRank;
import org.ratden.skavenblight.event.skavenIncursion.leadership.LeadershipRegistry;

import java.util.UUID;

public class DebugSourceCommands {

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("source")

                .then(Commands.literal("create")
                        .then(Commands.literal("active")
                                .executes(context -> createSource(
                                        context.getSource(),
                                        SourceState.ACTIVE
                                )))
                        .then(Commands.literal("dormant")
                                .executes(context -> createSource(
                                        context.getSource(),
                                        SourceState.DORMANT
                                )))
                        .then(Commands.literal("collapsed")
                                .executes(context -> createSource(
                                        context.getSource(),
                                        SourceState.COLLAPSED
                                )))
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
                source.sendFailure(Component.literal("You must be looking at a block."));
                return 0;
            }

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

            UUID createdSourceId = CreateTunnelSource.execute(
                    player.serverLevel(),
                    hitResult.getBlockPos().above(),
                    sourceState,
                    leadershipRegistry.createContext(
                            vermintideGroup,
                            fangGroup,
                            clawGroup,
                            packGroup
                    )
            );

            if (createdSourceId != null) {
                source.sendSuccess(
                        () -> Component.literal(
                                "Created tunnel source in state: "
                                        + sourceState.getSerializedName()
                                        + "\nSource ID: " + createdSourceId
                        ),
                        false
                );

                return 1;
            }

            source.sendFailure(Component.literal("Failed to create tunnel source."));
            return 0;

        } catch (Exception exception) {
            source.sendFailure(Component.literal("Error: " + exception.getMessage()));
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
            var level = player.serverLevel();
            var blockState = level.getBlockState(pos);

            if (!blockState.hasProperty(SkavenTunnelSourceBlock.SOURCE_STATE)) {
                source.sendFailure(Component.literal("Target block is not a tunnel source."));
                return 0;
            }

            SourceState sourceState = blockState.getValue(SkavenTunnelSourceBlock.SOURCE_STATE);

            if (!(level.getBlockEntity(pos) instanceof SkavenTunnelSourceEntity tunnelSource)) {
                source.sendFailure(Component.literal("Target block has no tunnel source entity."));
                return 0;
            }

            source.sendSuccess(
                    () -> Component.literal(
                            "Tunnel Source Info"
                                    + "\nPosition: " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()
                                    + "\nState: " + sourceState.getSerializedName()
                                    + "\nSource ID: " + tunnelSource.getSourceId()
                                    + "\nScenario ID: " + tunnelSource.getScenarioId()
                                    + "\nVermintide ID: " + tunnelSource.getVermintideId()
                                    + "\nFang ID: " + tunnelSource.getFangId()
                                    + "\nClaw ID: " + tunnelSource.getClawId()
                                    + "\nPack ID: " + tunnelSource.getPackId()
                                    + "\nCreated Game Time: " + tunnelSource.getCreatedGameTime()
                    ),
                    false
            );

            return 1;

        } catch (Exception exception) {
            source.sendFailure(Component.literal("Error: " + exception.getMessage()));
            return 0;
        }
    }

    private DebugSourceCommands() {
    }
}