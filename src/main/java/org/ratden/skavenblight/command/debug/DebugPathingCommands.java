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
                                // Skip a network with no live nexus - it never bootstraps a real
                                // region map, and (since territory bubbles are flat 2D chunk radii
                                // with no Y-awareness) a stale/dead network's leftover territory
                                // can otherwise "win" this lookup by chunk collision alone.
                                if (!candidate.isValid(level)) continue;
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

                            org.ratden.skavenblight.ai.pathing.SiegeLineTracer lineTracer = new org.ratden.skavenblight.ai.pathing.SiegeLineTracer(evaluator);
                            org.ratden.skavenblight.ai.pathing.region.RegionIndex regionIndex = new org.ratden.skavenblight.ai.pathing.region.RegionIndex(regions);
                            org.ratden.skavenblight.ai.pathing.region.RegionGraph graph = org.ratden.skavenblight.ai.pathing.region.RegionGraph.build(
                                    result.snapshot(), regionIndex, network.getTerritoryChunks(), pos, evaluator, lineTracer);

                            sb.append("Connectors (").append(graph.getAllConnectors().size()).append("):\n");
                            for (org.ratden.skavenblight.ai.pathing.region.RegionConnector connector : graph.getAllConnectors()) {
                                sb.append(String.format("  region %d <-> region %d, cost %d, entry %s -> %s%n",
                                        connector.regionA(), connector.regionB(), connector.cost(),
                                        connector.entryInA().toShortString(), connector.entryInB().toShortString()));
                            }

                            org.ratden.skavenblight.ai.pathing.region.Region rootRegion = regionIndex.regionAt(pos);
                            if (rootRegion != null) {
                                org.ratden.skavenblight.ai.pathing.region.RegionRouteTree routeTree =
                                        org.ratden.skavenblight.ai.pathing.region.RegionRouteTree.compute(graph, rootRegion.getId());

                                sb.append("Route tree (rooted at region ").append(rootRegion.getId()).append("):\n");
                                for (org.ratden.skavenblight.ai.pathing.region.Region region : regions) {
                                    if (!routeTree.isReachable(region.getId())) {
                                        sb.append(String.format("  region %d: UNREACHABLE%n", region.getId()));
                                        continue;
                                    }
                                    Integer parent = routeTree.getParentRegion(region.getId());
                                    sb.append(String.format("  region %d: parent=%s cost=%d%n",
                                            region.getId(), parent != null ? parent.toString() : "<root>", routeTree.getHopCost(region.getId())));
                                }
                            }

                            String finalOutput = sb.toString();
                            source.sendSuccess(() -> Component.literal(finalOutput), false);
                            return 1;
                        }))

                // Command: /skavendebug pathing regions_live
                // Reports the LIVE TerritoryRegionMap for the network you're standing in - no
                // fresh snapshot/scan/graph build of any kind. The sibling "regions" command
                // always builds its own scan from scratch, so it looks healthy even when the
                // runtime pipeline never initialized; this one is the only way to tell
                // "the algorithm works" apart from "nothing ever called rebuild()".
                .then(Commands.literal("regions_live")
                        .executes(context -> {
                            var source = context.getSource();
                            var level = source.getLevel();
                            var pos = net.minecraft.core.BlockPos.containing(source.getPosition());

                            org.ratden.skavenblight.network.WarpFluxGridManager gridManager =
                                    org.ratden.skavenblight.network.WarpFluxGridManager.get(level);
                            org.ratden.skavenblight.network.WarpFluxNetwork network = null;
                            net.minecraft.world.level.ChunkPos currentChunk = new net.minecraft.world.level.ChunkPos(pos);

                            for (org.ratden.skavenblight.network.WarpFluxNetwork candidate : gridManager.getAllNetworks()) {
                                // Skip a network with no live nexus - it never bootstraps a real
                                // region map, and (since territory bubbles are flat 2D chunk radii
                                // with no Y-awareness) a stale/dead network's leftover territory
                                // can otherwise "win" this lookup by chunk collision alone.
                                if (!candidate.isValid(level)) continue;
                                if (candidate.getTerritoryChunks().contains(currentChunk)) {
                                    network = candidate;
                                    break;
                                }
                            }

                            if (network == null) {
                                source.sendFailure(Component.literal("No network territory found at your position."));
                                return 0;
                            }

                            org.ratden.skavenblight.ai.pathing.region.TerritoryRegionMap regionMap = network.getRegionMap();
                            org.ratden.skavenblight.ai.pathing.region.RegionIndex index = regionMap.getRegionIndex();
                            org.ratden.skavenblight.ai.pathing.region.RegionGraph graph = regionMap.getRegionGraph();
                            org.ratden.skavenblight.ai.pathing.region.RegionRouteTree routeTree = regionMap.getRouteTree();

                            StringBuilder sb = new StringBuilder();
                            sb.append(String.format("LIVE region map for network %s%n", network.getId()));
                            sb.append(String.format("  territory chunks: %d | calculating: %s | rebuild generation: %d%n",
                                    network.getTerritoryChunks().size(), regionMap.isCalculating(), regionMap.getGeneration()));
                            sb.append(String.format("  regions: %d | connectors: %d | route tree: %s%n",
                                    index.getRegions().size(),
                                    graph != null ? graph.getAllConnectors().size() : 0,
                                    routeTree != null ? ("rooted at region " + routeTree.getRootRegionId()) : "none"));

                            if (index.getRegions().isEmpty()) {
                                sb.append("  no regions scanned yet; the region map may not have initialized")
                                        .append(regionMap.isCalculating() ? " (a rebuild IS currently running - re-run in a moment)" : "")
                                        .append(".\n  Compare with /skavendebug pathing regions, which scans from scratch: if THAT finds regions and this doesn't, the runtime pipeline never rebuilt.\n");
                            } else {
                                for (org.ratden.skavenblight.ai.pathing.region.Region region : index.getRegions()) {
                                    boolean reachable = routeTree != null && routeTree.isReachable(region.getId());
                                    sb.append(String.format("  region %d: %d cells, %d boundary cells, reachable=%s, hopCost=%s, bounds %s -> %s%n",
                                            region.getId(), region.cellCount(), region.getBoundaryCells().size(), reachable,
                                            reachable ? String.valueOf(routeTree.getHopCost(region.getId())) : "n/a",
                                            region.getMin() != null ? region.getMin().toShortString() : "?",
                                            region.getMax() != null ? region.getMax().toShortString() : "?"));
                                }

                                if (graph != null) {
                                    for (org.ratden.skavenblight.ai.pathing.region.RegionConnector connector : graph.getAllConnectors()) {
                                        sb.append(String.format("  connector: region %d <-> region %d, cost %d, entry %s -> %s%n",
                                                connector.regionA(), connector.regionB(), connector.cost(),
                                                connector.entryInA().toShortString(), connector.entryInB().toShortString()));
                                    }
                                }

                                org.ratden.skavenblight.ai.pathing.region.Region here = index.regionAt(pos);
                                sb.append(String.format("  your position is in: %s%n",
                                        here != null ? ("region " + here.getId()) : "no region (wilderness/unmapped)"));
                            }

                            String finalOutput = sb.toString();
                            source.sendSuccess(() -> Component.literal(finalOutput), false);
                            return 1;
                        }));
    }
}