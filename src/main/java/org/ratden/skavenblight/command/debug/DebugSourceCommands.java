package org.ratden.skavenblight.command.debug;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.ratden.skavenblight.block.custom.SkavenTunnelSourceBlock;
import org.ratden.skavenblight.block.entity.SkavenTunnelSourceEntity;
import org.ratden.skavenblight.block.entity.state.SourceState;
import org.ratden.skavenblight.event.skavenIncursion.action.source.SetSourceState;
import org.ratden.skavenblight.event.skavenIncursion.action.source.SourceGroupPlacement;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceGroupPlan;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourcePlacementPattern;
import org.ratden.skavenblight.event.skavenIncursion.action.source.SourcePlan;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceRole;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceSize;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceType;
import org.ratden.skavenblight.event.skavenIncursion.action.source.generic.CreateTunnelSource;
import org.ratden.skavenblight.event.skavenIncursion.leadership.LeaderGroup;
import org.ratden.skavenblight.event.skavenIncursion.leadership.LeaderGroupType;
import org.ratden.skavenblight.event.skavenIncursion.leadership.LeaderRank;
import org.ratden.skavenblight.event.skavenIncursion.leadership.LeadershipRegistry;

import java.util.List;
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

                .then(Commands.literal("pattern")
                        .then(registerPatternCommands("single", SourcePlacementPattern.SINGLE))
                        .then(registerPatternCommands("cluster", SourcePlacementPattern.CLUSTER))
                        .then(registerPatternCommands("pincer", SourcePlacementPattern.PINCER))
                        .then(registerPatternCommands("surround_light", SourcePlacementPattern.SURROUND_LIGHT))
                        .then(registerPatternCommands("distant_support", SourcePlacementPattern.DISTANT_SUPPORT))
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

    private static LiteralArgumentBuilder<CommandSourceStack> registerPatternCommands(
            String commandName,
            SourcePlacementPattern placementPattern
    ) {
        return Commands.literal(commandName)
                .then(Commands.literal("active")
                        .executes(context -> createSourcePattern(
                                context.getSource(),
                                placementPattern,
                                SourceState.ACTIVE,
                                getDefaultSourceCountForPattern(placementPattern)
                        ))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 32))
                                .executes(context -> createSourcePattern(
                                        context.getSource(),
                                        placementPattern,
                                        SourceState.ACTIVE,
                                        IntegerArgumentType.getInteger(context, "count")
                                ))))
                .then(Commands.literal("dormant")
                        .executes(context -> createSourcePattern(
                                context.getSource(),
                                placementPattern,
                                SourceState.DORMANT,
                                getDefaultSourceCountForPattern(placementPattern)
                        ))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 32))
                                .executes(context -> createSourcePattern(
                                        context.getSource(),
                                        placementPattern,
                                        SourceState.DORMANT,
                                        IntegerArgumentType.getInteger(context, "count")
                                ))))
                .then(Commands.literal("collapsed")
                        .executes(context -> createSourcePattern(
                                context.getSource(),
                                placementPattern,
                                SourceState.COLLAPSED,
                                getDefaultSourceCountForPattern(placementPattern)
                        ))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 32))
                                .executes(context -> createSourcePattern(
                                        context.getSource(),
                                        placementPattern,
                                        SourceState.COLLAPSED,
                                        IntegerArgumentType.getInteger(context, "count")
                                ))));
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

            LeadershipRegistry leadershipRegistry = createDebugLeadershipRegistry();

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

    private static int createSourcePattern(
            CommandSourceStack source,
            SourcePlacementPattern placementPattern,
            SourceState sourceState,
            int sourceCount
    ) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            ServerLevel level = player.serverLevel();
            BlockPos targetPos = player.blockPosition();

            List<SourceGroupPlan> sourceGroups = SourceGroupPlacement.createPlannedSources(
                    level,
                    targetPos,
                    placementPattern,
                    SourceRole.COMBAT,
                    SourceType.SKAVEN_TUNNEL,
                    SourceSize.NORMAL,
                    sourceCount
            );

            if (sourceGroups.isEmpty()) {
                source.sendFailure(Component.literal("Pattern created no source groups."));
                return 0;
            }

            LeadershipRegistry leadershipRegistry = createDebugLeadershipRegistry();

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

            int plannedCount = countPlannedSources(sourceGroups);
            int createdCount = 0;
            int sourceNumber = 0;

            StringBuilder message = new StringBuilder();

            message.append("Created source pattern: ")
                    .append(placementPattern)
                    .append("\nState: ")
                    .append(sourceState.getSerializedName())
                    .append("\nRequested Sources: ")
                    .append(sourceCount)
                    .append("\nPlanned Sources: ")
                    .append(plannedCount)
                    .append("\nTarget: ")
                    .append(formatBlockPos(targetPos))
                    .append("\nGroups: ")
                    .append(sourceGroups.size());

            for (int groupIndex = 0; groupIndex < sourceGroups.size(); groupIndex++) {
                SourceGroupPlan sourceGroup = sourceGroups.get(groupIndex);

                for (SourcePlan sourcePlan : sourceGroup.getSourcePlans()) {
                    sourceNumber++;

                    LeaderGroup packGroup = leadershipRegistry.createLeaderGroup(
                            LeaderGroupType.PACK,
                            LeaderRank.NONE
                    );

                    BlockPos placementPos = sourcePlan.hasPlacedPos()
                            ? sourcePlan.getPlacedPos()
                            : sourcePlan.getAnchorPos();

                    UUID createdSourceId = CreateTunnelSource.execute(
                            level,
                            placementPos,
                            sourceState,
                            leadershipRegistry.createContext(
                                    vermintideGroup,
                                    fangGroup,
                                    clawGroup,
                                    packGroup
                            )
                    );

                    message.append("\n\nSource ")
                            .append(sourceNumber)
                            .append(":")
                            .append("\nGroup: ")
                            .append(groupIndex + 1)
                            .append(" / ")
                            .append(sourceGroups.size())
                            .append("\nGroup Role: ")
                            .append(sourceGroup.getSourceRole())
                            .append("\nSource Role: ")
                            .append(sourcePlan.getSourceRole())
                            .append("\nSource Type: ")
                            .append(sourcePlan.getSourceType())
                            .append("\nSource Size: ")
                            .append(sourcePlan.getSourceSize())
                            .append("\nGroup Anchor: ")
                            .append(formatBlockPos(sourceGroup.getAnchorPos()))
                            .append("\nPlanned Position: ")
                            .append(formatBlockPos(placementPos));

                    if (createdSourceId != null) {
                        createdCount++;

                        message.append("\nSource ID: ")
                                .append(createdSourceId);
                    } else {
                        message.append("\nSource ID: failed");
                    }
                }
            }

            if (createdCount <= 0) {
                source.sendFailure(Component.literal("Failed to create any tunnel sources for pattern."));
                return 0;
            }

            int finalCreatedCount = createdCount;

            source.sendSuccess(
                    () -> Component.literal(
                            message
                                    + "\n\nCreated: "
                                    + finalCreatedCount
                                    + " / "
                                    + plannedCount
                    ),
                    false
            );

            return createdCount;

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

    private static int countPlannedSources(
            List<SourceGroupPlan> sourceGroups
    ) {
        int total = 0;

        for (SourceGroupPlan sourceGroup : sourceGroups) {
            total += sourceGroup.getSourcePlans().size();
        }

        return total;
    }

    private static int getDefaultSourceCountForPattern(
            SourcePlacementPattern placementPattern
    ) {
        return switch (placementPattern) {
            case SINGLE -> 1;
            case CLUSTER -> 3;
            case PINCER -> 2;
            case SURROUND_LIGHT -> 4;
            case DISTANT_SUPPORT -> 2;
        };
    }

    private static LeadershipRegistry createDebugLeadershipRegistry() {
        return new LeadershipRegistry(UUID.randomUUID());
    }

    private static String formatBlockPos(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    private DebugSourceCommands() {
    }
}