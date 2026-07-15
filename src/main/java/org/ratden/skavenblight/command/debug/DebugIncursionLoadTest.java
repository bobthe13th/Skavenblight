package org.ratden.skavenblight.command.debug;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.ratden.skavenblight.block.entity.state.SourceState;
import org.ratden.skavenblight.event.skavenIncursion.action.mob.generic.SpawnClanrats;
import org.ratden.skavenblight.event.skavenIncursion.action.mob.generic.SpawnWolfCats;
import org.ratden.skavenblight.event.skavenIncursion.action.mob.generic.SpawnWolfRats;
import org.ratden.skavenblight.event.skavenIncursion.action.source.BasePlacementContext;
import org.ratden.skavenblight.event.skavenIncursion.action.source.SourceGroupPlacement;
import org.ratden.skavenblight.event.skavenIncursion.action.source.SourceGroupPlan;
import org.ratden.skavenblight.event.skavenIncursion.action.source.SourcePlacementPattern;
import org.ratden.skavenblight.event.skavenIncursion.action.source.SourcePlan;
import org.ratden.skavenblight.event.skavenIncursion.action.source.SourceRole;
import org.ratden.skavenblight.event.skavenIncursion.action.source.SourceSize;
import org.ratden.skavenblight.event.skavenIncursion.action.source.SourceType;
import org.ratden.skavenblight.event.skavenIncursion.action.source.generic.CreateTunnelSource;
import org.ratden.skavenblight.event.skavenIncursion.director.IncursionTargetType;
import org.ratden.skavenblight.event.skavenIncursion.leadership.LeaderGroup;
import org.ratden.skavenblight.event.skavenIncursion.leadership.LeaderGroupType;
import org.ratden.skavenblight.event.skavenIncursion.leadership.LeaderRank;
import org.ratden.skavenblight.event.skavenIncursion.leadership.LeadershipRegistry;
import org.ratden.skavenblight.world.NexusTracker;

import java.util.List;
import java.util.UUID;

public class DebugIncursionLoadTest {

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("loadtest")
                .then(registerLoadTestSize("small", LoadTestSize.SMALL))
                .then(registerLoadTestSize("medium", LoadTestSize.MEDIUM))
                .then(registerLoadTestSize("siege", LoadTestSize.SIEGE));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> registerLoadTestSize(
            String commandName,
            LoadTestSize loadTestSize
    ) {
        return Commands.literal(commandName)
                .executes(context -> runLoadTest(
                        context.getSource(),
                        loadTestSize,
                        LoadTestMobType.WOLF_RAT
                ))
                .then(Commands.literal("wolf_rat")
                        .executes(context -> runLoadTest(
                                context.getSource(),
                                loadTestSize,
                                LoadTestMobType.WOLF_RAT
                        )))
                .then(Commands.literal("wolf_cat")
                        .executes(context -> runLoadTest(
                                context.getSource(),
                                loadTestSize,
                                LoadTestMobType.WOLF_CAT
                        )))
                .then(Commands.literal("clanrat")
                        .executes(context -> runLoadTest(
                                context.getSource(),
                                loadTestSize,
                                LoadTestMobType.CLANRAT
                        )));
    }

    private static int runLoadTest(
            CommandSourceStack source,
            LoadTestSize loadTestSize,
            LoadTestMobType mobType
    ) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            ServerLevel level = player.serverLevel();

            IncursionTargetType targetType = chooseDebugTargetType(level);
            BlockPos targetPos = chooseDebugTargetPos(
                    level,
                    player.blockPosition(),
                    targetType
            );

            BasePlacementContext placementContext = new BasePlacementContext(
                    targetPos,
                    targetType,
                    loadTestSize.baseRadius
            );

            List<SourceGroupPlan> sourceGroups = SourceGroupPlacement.createPlannedSources(
                    level,
                    placementContext,
                    loadTestSize.placementPattern,
                    SourceRole.COMBAT,
                    SourceType.SKAVEN_TUNNEL,
                    SourceSize.NORMAL,
                    loadTestSize.sourceCount
            );

            int plannedSources = countPlannedSources(sourceGroups);

            if (plannedSources <= 0) {
                source.sendFailure(Component.literal(
                        "Load test failed: no sources were planned."
                ));
                return 0;
            }

            LeadershipRegistry leadershipRegistry =
                    new LeadershipRegistry(UUID.randomUUID());

            LeaderGroup vermintideGroup =
                    leadershipRegistry.createLeaderGroup(
                            LeaderGroupType.VERMINTIDE,
                            LeaderRank.NONE
                    );

            LeaderGroup fangGroup =
                    leadershipRegistry.createLeaderGroup(
                            LeaderGroupType.FANG,
                            LeaderRank.NONE
                    );

            int createdSources = 0;
            int spawnedMobs = 0;
            int sourceNumber = 0;

            int[] mobsPerSource = distributeCount(
                    loadTestSize.mobCount,
                    plannedSources
            );

            StringBuilder message = new StringBuilder();

            message.append("Debug incursion load test: ")
                    .append(loadTestSize.name())
                    .append("\nMob type: ")
                    .append(mobType.commandName)
                    .append("\nTarget type: ")
                    .append(targetType)
                    .append("\nTarget pos: ")
                    .append(formatBlockPos(targetPos))
                    .append("\nBase radius: ")
                    .append(loadTestSize.baseRadius)
                    .append("\nPattern: ")
                    .append(loadTestSize.placementPattern)
                    .append("\nPlanned sources: ")
                    .append(plannedSources)
                    .append("\nRequested mobs: ")
                    .append(loadTestSize.mobCount);

            for (
                    int groupIndex = 0;
                    groupIndex < sourceGroups.size();
                    groupIndex++
            ) {
                SourceGroupPlan sourceGroup = sourceGroups.get(groupIndex);

                LeaderGroup clawGroup =
                        leadershipRegistry.createLeaderGroup(
                                LeaderGroupType.CLAW,
                                LeaderRank.NONE
                        );

                for (SourcePlan sourcePlan : sourceGroup.getSourcePlans()) {
                    sourceNumber++;

                    LeaderGroup packGroup =
                            leadershipRegistry.createLeaderGroup(
                                    LeaderGroupType.PACK,
                                    LeaderRank.NONE
                            );

                    BlockPos placementPos = sourcePlan.hasPlacedPos()
                            ? sourcePlan.getPlacedPos()
                            : sourcePlan.getAnchorPos();

                    UUID createdSourceId = CreateTunnelSource.execute(
                            level,
                            placementPos,
                            SourceState.ACTIVE,
                            leadershipRegistry.createContext(
                                    vermintideGroup,
                                    fangGroup,
                                    clawGroup,
                                    packGroup
                            )
                    );

                    if (createdSourceId == null) {
                        message.append("\n\nSource ")
                                .append(sourceNumber)
                                .append(": failed to create at ")
                                .append(formatBlockPos(placementPos));

                        continue;
                    }

                    createdSources++;

                    int mobsForThisSource =
                            mobsPerSource[sourceNumber - 1];

                    int spawnedFromThisSource = spawnMobsForLoadTest(
                            level,
                            placementPos,
                            mobsForThisSource,
                            leadershipRegistry,
                            vermintideGroup,
                            fangGroup,
                            clawGroup,
                            packGroup,
                            createdSourceId,
                            mobType
                    );

                    spawnedMobs += spawnedFromThisSource;

                    message.append("\n\nSource ")
                            .append(sourceNumber)
                            .append(":")
                            .append("\nGroup: ")
                            .append(groupIndex + 1)
                            .append(" / ")
                            .append(sourceGroups.size())
                            .append("\nPosition: ")
                            .append(formatBlockPos(placementPos))
                            .append("\nSource ID: ")
                            .append(createdSourceId)
                            .append("\nRequested ")
                            .append(mobType.displayName)
                            .append(": ")
                            .append(mobsForThisSource)
                            .append("\nSpawned ")
                            .append(mobType.displayName)
                            .append(": ")
                            .append(spawnedFromThisSource);
                }
            }

            int finalCreatedSources = createdSources;
            int finalSpawnedMobs = spawnedMobs;

            source.sendSuccess(
                    () -> Component.literal(
                            message
                                    + "\n\nCreated sources: "
                                    + finalCreatedSources
                                    + " / "
                                    + plannedSources
                                    + "\nSpawned mobs: "
                                    + finalSpawnedMobs
                                    + " / "
                                    + loadTestSize.mobCount
                    ),
                    false
            );

            return spawnedMobs;

        } catch (Exception exception) {
            source.sendFailure(Component.literal(
                    "Load test failed: " + exception.getMessage()
            ));
            return 0;
        }
    }

    private static int spawnMobsForLoadTest(
            ServerLevel level,
            BlockPos placementPos,
            int count,
            LeadershipRegistry leadershipRegistry,
            LeaderGroup vermintideGroup,
            LeaderGroup fangGroup,
            LeaderGroup clawGroup,
            LeaderGroup packGroup,
            UUID createdSourceId,
            LoadTestMobType mobType
    ) {
        return switch (mobType) {
            case WOLF_RAT -> SpawnWolfRats.execute(
                    level,
                    placementPos,
                    count,
                    leadershipRegistry.createContext(
                            vermintideGroup,
                            fangGroup,
                            clawGroup,
                            packGroup
                    ),
                    createdSourceId
            ).size();

            case WOLF_CAT -> SpawnWolfCats.execute(
                    level,
                    placementPos,
                    count,
                    leadershipRegistry.createContext(
                            vermintideGroup,
                            fangGroup,
                            clawGroup,
                            packGroup
                    ),
                    createdSourceId
            ).size();

            case CLANRAT -> SpawnClanrats.execute(
                    level,
                    placementPos,
                    count,
                    leadershipRegistry.createContext(
                            vermintideGroup,
                            fangGroup,
                            clawGroup,
                            packGroup
                    ),
                    createdSourceId
            ).size();
        };
    }

    private static IncursionTargetType chooseDebugTargetType(
            ServerLevel level
    ) {
        if (NexusTracker.hasActiveNexus(level)) {
            return IncursionTargetType.NEXUS;
        }

        return IncursionTargetType.PLAYER;
    }

    private static BlockPos chooseDebugTargetPos(
            ServerLevel level,
            BlockPos fallbackPlayerPos,
            IncursionTargetType targetType
    ) {
        if (targetType == IncursionTargetType.NEXUS
                && NexusTracker.hasActiveNexus(level)) {
            return NexusTracker.getActiveNexusPos(level);
        }

        return fallbackPlayerPos;
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

    private static int[] distributeCount(
            int totalCount,
            int bucketCount
    ) {
        int safeBucketCount = Math.max(1, bucketCount);
        int[] counts = new int[safeBucketCount];

        for (int i = 0; i < totalCount; i++) {
            counts[i % safeBucketCount]++;
        }

        return counts;
    }

    private static String formatBlockPos(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    private enum LoadTestSize {
        SMALL(
                SourcePlacementPattern.CLUSTER,
                24,
                3,
                15
        ),
        MEDIUM(
                SourcePlacementPattern.PINCER,
                32,
                6,
                40
        ),
        SIEGE(
                SourcePlacementPattern.SURROUND_LIGHT,
                32,
                12,
                80
        );

        private final SourcePlacementPattern placementPattern;
        private final int baseRadius;
        private final int sourceCount;
        private final int mobCount;

        LoadTestSize(
                SourcePlacementPattern placementPattern,
                int baseRadius,
                int sourceCount,
                int mobCount
        ) {
            this.placementPattern = placementPattern;
            this.baseRadius = baseRadius;
            this.sourceCount = sourceCount;
            this.mobCount = mobCount;
        }
    }

    private enum LoadTestMobType {
        WOLF_RAT(
                "wolf_rat",
                "wolf rats"
        ),
        WOLF_CAT(
                "wolf_cat",
                "wolf cats"
        ),
        CLANRAT(
                "clanrat",
                "clanrats"
        );

        private final String commandName;
        private final String displayName;

        LoadTestMobType(
                String commandName,
                String displayName
        ) {
            this.commandName = commandName;
            this.displayName = displayName;
        }
    }

    private DebugIncursionLoadTest() {
    }
}

