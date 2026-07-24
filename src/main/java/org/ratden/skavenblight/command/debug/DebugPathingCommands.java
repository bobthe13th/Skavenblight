package org.ratden.skavenblight.command.debug;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import org.ratden.skavenblight.Config;

public class DebugPathingCommands {

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("pathing")

                // Command: /skavendebug pathing set_multiplier <value>
                .then(Commands.literal("set_multiplier")
                        .then(Commands.argument("value", IntegerArgumentType.integer(1))
                                .executes(context -> {
                                    int val = IntegerArgumentType.getInteger(context, "value");
                                    Config.miningPenaltyMultiplier = val;
                                    context.getSource().sendSuccess(() -> Component.literal(
                                            "Mining penalty multiplier temporarily set to " + val
                                    ), false);
                                    return 1;
                                })))

                // Command: /skavendebug pathing set_base <value>
                .then(Commands.literal("set_base")
                        .then(Commands.argument("value", IntegerArgumentType.integer(0))
                                .executes(context -> {
                                    int val = IntegerArgumentType.getInteger(context, "value");
                                    Config.miningBasePenalty = val;
                                    context.getSource().sendSuccess(() -> Component.literal(
                                            "Mining base penalty temporarily set to " + val
                                    ), false);
                                    return 1;
                                })))

                // Command: /skavendebug pathing set_max_nodes <value>
                .then(Commands.literal("set_max_nodes")
                        .then(Commands.argument("value", IntegerArgumentType.integer(1000))
                                .executes(context -> {
                                    int val = IntegerArgumentType.getInteger(context, "value");
                                    Config.maxFlowFieldNodes = val;
                                    context.getSource().sendSuccess(() -> Component.literal(
                                            "Max Flow Field nodes temporarily set to " + val
                                    ), false);
                                    return 1;
                                })))

                // Command: /skavendebug pathing set_settle_delay <value>
                .then(Commands.literal("set_settle_delay")
                        .then(Commands.argument("value", IntegerArgumentType.integer(0))
                                .executes(context -> {
                                    int val = IntegerArgumentType.getInteger(context, "value");
                                    Config.minimumSettleDelayMs = val;
                                    context.getSource().sendSuccess(() -> Component.literal(
                                            "Minimum settle delay temporarily set to " + val + "ms"
                                    ), false);
                                    return 1;
                                })))

                // Command: /skavendebug pathing info
                .then(Commands.literal("info")
                        .executes(context -> {
                            context.getSource().sendSuccess(() -> Component.literal(
                                    "Flow Field Pathing Info"
                                            + "\nMultiplier: " + Config.miningPenaltyMultiplier
                                            + "\nBase Penalty: " + Config.miningBasePenalty
                                            + "\nMax Nodes (Field Size): " + Config.maxFlowFieldNodes
                                            + "\nMinimum Settle Delay: " + Config.minimumSettleDelayMs + "ms"
                            ), false);
                            return 1;
                        }))

                // Command: /skavendebug pathing regions
                .then(Commands.literal("regions")
                        .executes(context -> {
                            var source = context.getSource();
                            var level = source.getLevel();
                            var pos = net.minecraft.core.BlockPos.containing(source.getPosition());

                            org.ratden.skavenblight.network.WarpFluxGridManager gridManager =
                                    org.ratden.skavenblight.network.WarpFluxGridManager.get(level);
                            org.ratden.skavenblight.network.WarpFluxNetwork network = null;
                            net.minecraft.world.level.ChunkPos currentChunk = new net.minecraft.world.level.ChunkPos(pos);

                            for (org.ratden.skavenblight.network.WarpFluxNetwork candidate : gridManager.getAllNetworks()) {
                                if (candidate.getTerritoryChunks().contains(currentChunk)) {
                                    network = candidate;
                                    break;
                                }
                            }

                            if (network == null) {
                                source.sendFailure(Component.literal("No network territory found at your position."));
                                return 0;
                            }

                            org.ratden.skavenblight.ai.pathing.TerrainEvaluator evaluator = new org.ratden.skavenblight.ai.pathing.TerrainEvaluator();
                            org.ratden.skavenblight.ai.pathing.TerrainSnapshot.RefreshResult result =
                                    org.ratden.skavenblight.ai.pathing.TerrainSnapshot.refresh(
                                            level, null, network.getTerritoryChunks(), new java.util.HashSet<>(network.getTerritoryChunks()),
                                            level.getMinBuildHeight(), level.getMaxBuildHeight(), Integer.MAX_VALUE);

                            org.ratden.skavenblight.ai.pathing.region.RegionScanner scanner =
                                    new org.ratden.skavenblight.ai.pathing.region.RegionScanner(evaluator);
                            java.util.List<org.ratden.skavenblight.ai.pathing.region.Region> regions =
                                    scanner.scan(result.snapshot(), network.getTerritoryChunks(), pos, level.getMinBuildHeight(), level.getMaxBuildHeight());

                            StringBuilder sb = new StringBuilder("Scanned ").append(regions.size()).append(" region(s):\n");
                            for (org.ratden.skavenblight.ai.pathing.region.Region region : regions) {
                                sb.append(String.format("  region %d: %d cells, %d boundary cells, bounds %s -> %s%n",
                                        region.getId(), region.cellCount(), region.getBoundaryCells().size(),
                                        region.getMin() != null ? region.getMin().toShortString() : "?",
                                        region.getMax() != null ? region.getMax().toShortString() : "?"));
                            }

                            String finalOutput = sb.toString();
                            source.sendSuccess(() -> Component.literal(finalOutput), false);
                            return 1;
                        }));
    }
}