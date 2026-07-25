package org.ratden.skavenblight.debug;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.ratden.skavenblight.ai.pathing.SiegeNode;
import org.ratden.skavenblight.ai.pathing.region.Region;
import org.ratden.skavenblight.ai.pathing.region.RegionFlowField;
import org.ratden.skavenblight.ai.pathing.region.RegionGraph;
import org.ratden.skavenblight.ai.pathing.region.RegionIndex;
import org.ratden.skavenblight.ai.pathing.region.RegionRouteTree;
import org.ratden.skavenblight.ai.pathing.region.TerritoryRegionMap;
import org.ratden.skavenblight.entity.custom.ClanratEntity;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Text dump of a RegionFlowField's state (plus its owning TerritoryRegionMap's region graph),
 * written for an LLM reading the file afterward to
 * diagnose pathing/AI bugs - not for a human skimming it in-game. Every section is plain,
 * labeled key/value text rather than a purely visual layout, and it deliberately front-loads
 * the numbers most likely to explain "why is this broken" (calculation progress, perf
 * counters, per-mob active goal) before the block-by-block grid, since the grid alone was
 * never enough to diagnose e.g. an O(n^2) slowdown or a rat with no goal claiming its target.
 *
 * Works whether the calculation is ready, empty, or actively running: while a calculation is
 * in progress the diagnostics section says so explicitly and the grid renders from
 * getLiveDebugMap() (the in-progress snapshot) instead of waiting for completion - useful for
 * troubleshooting a calculation that's slow or appears hung, since previously the only way to
 * inspect it was to wait for it to finish (or never get output at all if it never did).
 */
public class PathingDebugFileWriter {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    public static String exportDeepDump(ServerLevel level, RegionFlowField flowField, TerritoryRegionMap regionMap,
                                         BlockPos center, int radiusX, int heightY, int radiusZ) {
        File dumpDir = new File("skavenblight_dumps");
        if (!dumpDir.exists() && !dumpDir.mkdirs()) {
            System.err.println("[Skavenblight] Failed to create dump directory!");
            return null;
        }

        String fileName = "siege_dump_" + LocalDateTime.now().format(TIME_FORMATTER) + ".txt";
        File dumpFile = new File(dumpDir, fileName);

        try (FileWriter writer = new FileWriter(dumpFile)) {
            boolean calculating = flowField.isCalculating();
            // While a calculation is running, the finalized instructionMap is stale (it's
            // whatever the LAST completed pass produced, possibly empty for a brand-new
            // territory) - the live map is the actually-current picture. Once a calculation
            // is done, the live map has already been overwritten to match, so either would
            // do; use the finalized one since that's what rats are actually reading.
            Map<BlockPos, SiegeNode> renderMap = calculating ? flowField.getLiveDebugMap() : flowField.getInstructionMap();

            writeHeader(writer, flowField, regionMap, center, radiusX, heightY, radiusZ, calculating, renderMap);
            writeRegionGraph(writer, regionMap);
            writeMetrics(writer, renderMap);
            writeNearbyMobs(writer, level, flowField, center, Math.max(radiusX, radiusZ), heightY);
            writeRecentActivity(writer, center, Math.max(radiusX, radiusZ) * 2);
            writeGrid(writer, level, renderMap, center, radiusX, heightY, radiusZ);

            writer.write("\n================ END OF DIAGNOSTIC DUMP ================\n");
            return dumpFile.getAbsolutePath();

        } catch (IOException e) {
            System.err.println("[Skavenblight] Failed to write topology deep dump!");
            e.printStackTrace();
            return null;
        }
    }

    private static void writeHeader(FileWriter writer, RegionFlowField flowField, TerritoryRegionMap regionMap, BlockPos center,
                                     int radiusX, int heightY, int radiusZ, boolean calculating, Map<BlockPos, SiegeNode> renderMap) throws IOException {
        writer.write("====================================================\n");
        writer.write("         SKAVENBLIGHT DEEP DIAGNOSTIC DUMP          \n");
        writer.write("====================================================\n\n");

        writer.write(String.format("Timestamp    : %s\n", LocalDateTime.now()));
        writer.write(String.format("Region Id    : %d\n", flowField.getRegionId()));
        writer.write(String.format("Target Pos   : %s\n", flowField.getTargetPos().toShortString()));
        writer.write(String.format("Center Pos   : %s\n", center.toShortString()));
        writer.write(String.format("Search Bounds: +/- %dx, %dy, %dz (Y: %d to %d)\n",
                radiusX, heightY, radiusZ, center.getY() - heightY, center.getY() + heightY));

        if (calculating) {
            // No network-wide equivalent of getLastCalculationStartGameTime() is exposed by
            // TerritoryRegionMap - it tracks a single lastCalculationStart privately and doesn't
            // surface it - so elapsed-ticks-since-start is dropped rather than guessed at.
            writer.write(String.format("FlowField State: CALCULATING (LIVE, in-progress snapshot) | Nodes so far: %d\n", renderMap.size()));
        } else {
            writer.write(String.format("FlowField State: READY (last completed pass) | Total Nodes: %d\n", renderMap.size()));
        }

        writer.write(String.format("Node Budget (throttled): %d nodes/pass\n", regionMap.getThrottler().getNodesPerTick()));
        // Snapshot Coverage / Dijkstra Budget Used / Siege Projects / Macro Evaluation used to
        // read StandardFlowField's own per-nexus SiegeProjectManager/FlowFieldCalculator/
        // TerrainSnapshot capture-count directly. Those objects are now shared network-wide on
        // TerritoryRegionMap (not per-region) and TerritoryRegionMap doesn't expose getters for
        // them - only getThrottler() above survived the migration as a network-wide equivalent -
        // so these per-pass perf counters are retired rather than guessed at.
        writer.write("Snapshot Coverage: (per-nexus chunk-capture stats retired - see region graph dump below)\n");
        writer.write("Dijkstra Budget Used: (per-nexus calculator diagnostics retired - see region graph dump below)\n");
        writer.write("Siege Projects: (per-nexus project-manager stats retired - see region graph dump below)\n");
        writer.write("Macro Evaluation: (per-nexus project-manager stats retired - see region graph dump below)\n\n");
    }

    /**
     * Region graph + route tree snapshot - added when this file migrated off StandardFlowField's
     * single-territory model, since region/connector/reachability info now lives on
     * TerritoryRegionMap rather than on any individual RegionFlowField.
     */
    private static void writeRegionGraph(FileWriter writer, TerritoryRegionMap regionMap) throws IOException {
        writer.write("--- REGION GRAPH ---\n");
        RegionIndex index = regionMap.getRegionIndex();
        RegionGraph graph = regionMap.getRegionGraph();
        RegionRouteTree routeTree = regionMap.getRouteTree();

        writer.write(String.format("Regions: %d | Connectors: %d\n", index.getRegions().size(), graph != null ? graph.getAllConnectors().size() : 0));

        for (Region region : index.getRegions()) {
            boolean reachable = routeTree != null && routeTree.isReachable(region.getId());
            writer.write(String.format("  region %d: %d cells, reachable=%s, hopCost=%s\n",
                    region.getId(), region.cellCount(), reachable,
                    reachable ? String.valueOf(routeTree.getHopCost(region.getId())) : "n/a"));
        }
        writer.write("\n");
    }

    private static void writeMetrics(FileWriter writer, Map<BlockPos, SiegeNode> renderMap) throws IOException {
        writer.write("--- FLOW FIELD METRICS ---\n");
        Map<SiegeNode.SiegeAction, Integer> actionCounts = new EnumMap<>(SiegeNode.SiegeAction.class);
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;

        for (Map.Entry<BlockPos, SiegeNode> entry : renderMap.entrySet()) {
            BlockPos pos = entry.getKey();
            SiegeNode node = entry.getValue();

            actionCounts.put(node.action(), actionCounts.getOrDefault(node.action(), 0) + 1);
            if (pos.getY() < minY) minY = pos.getY();
            if (pos.getY() > maxY) maxY = pos.getY();
        }

        if (!renderMap.isEmpty()) {
            writer.write(String.format("Mapped Y-Coverage : Min Y = %d | Max Y = %d (Delta: %d blocks)\n", minY, maxY, (maxY - minY)));
        } else {
            writer.write("Mapped Y-Coverage : EMPTY MAP\n");
        }

        writer.write("Action Counts     : ");
        if (actionCounts.isEmpty()) {
            writer.write("(none)");
        }
        for (Map.Entry<SiegeNode.SiegeAction, Integer> entry : actionCounts.entrySet()) {
            writer.write(String.format("[%s: %d] ", entry.getKey().name(), entry.getValue()));
        }
        writer.write("\n\n");
    }

    /**
     * The "why is this rat just standing there" section - lists every Mob near center along
     * with whichever Goal(s) are actually RUNNING on it right now (via ClanratEntity's
     * getActiveGoalNames()) and what its own next flow-field instruction is. A rat with
     * "<idle>" and a non-WALK next instruction is a rat whose construction goal declined to
     * claim a target it should have - previously invisible from outside the entity itself.
     */
    private static void writeNearbyMobs(FileWriter writer, ServerLevel level, RegionFlowField flowField, BlockPos center, int radius, int heightY) throws IOException {
        writer.write("--- NEARBY MOBS (goal state) ---\n");

        AABB box = new AABB(center).inflate(radius, heightY, radius);
        List<Mob> mobs = level.getEntitiesOfClass(Mob.class, box);

        if (mobs.isEmpty()) {
            writer.write("(none within range)\n\n");
            return;
        }

        // Two entities occupying the same block is a jam, not a coincidence - vanilla movement
        // doesn't prevent mobs stacking on a single-block-wide bottleneck (a staircase, a
        // half-built bridge), and this was easy to miss by eye scanning a long mob list.
        // Flagging it directly caught a real jam in testing (two clanrats on the same block,
        // one running WidenStairsGoal and one BuildFlowFieldGoal, that would otherwise have
        // required noticing the duplicate position manually.
        Map<BlockPos, Integer> occupancy = new HashMap<>();
        for (Mob mob : mobs) {
            occupancy.merge(mob.blockPosition(), 1, Integer::sum);
        }

        for (Mob mob : mobs) {
            BlockPos pos = mob.blockPosition();
            String goals = (mob instanceof ClanratEntity clanrat) ? clanrat.getActiveGoalNames() : "n/a";

            SiegeNode next = flowField.getNextSiegeNode(level, pos);
            String nextDesc = next == null ? "no-instruction (wilderness)" : String.format("%s -> %s", next.action(), next.pos().toShortString());

            String jamFlag = occupancy.getOrDefault(pos, 1) > 1 ? String.format(" [JAM: %d mobs on this block]", occupancy.get(pos)) : "";

            writer.write(String.format("  %-22s @ %-16s | running: %-40s | next: %s%s\n",
                    BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()), pos.toShortString(), goals, nextDesc, jamFlag));
        }
        writer.write("\n");
    }

    /**
     * Recent siege construction/mining activity near this dump's center, regardless of exactly
     * when the dump was triggered - see SiegeActivityLog for why this exists (a dump is a
     * single instant; several bugs found in testing had already finished happening by the time
     * a dump was taken, leaving the grid/mob sections with no trace of what led there).
     */
    private static void writeRecentActivity(FileWriter writer, BlockPos center, int radius) throws IOException {
        writer.write("--- RECENT SIEGE ACTIVITY (executed, not planned) ---\n");

        List<SiegeActivityLog.Entry> nearby = SiegeActivityLog.recent(300).stream()
                .filter(e -> e.targetPos().closerThan(center, radius))
                .toList();

        if (nearby.isEmpty()) {
            writer.write("(no recorded activity within range - either nothing has been built/mined nearby yet,\n" +
                    " or SiegeActivityLog's buffer has already rolled past it - see MAX_ENTRIES)\n\n");
            return;
        }

        for (SiegeActivityLog.Entry entry : nearby) {
            writer.write(String.format("  t=%-8d %-22s [%s] mob@%-16s -> %-12s at %-16s region=%-4s (%s)\n",
                    entry.gameTime(), entry.mobType(), entry.mobId(),
                    entry.mobPos() != null ? entry.mobPos().toShortString() : "?",
                    entry.action(), entry.targetPos().toShortString(),
                    entry.regionId() != null ? entry.regionId().toString() : "?", entry.note()));
        }
        writer.write("\n");
    }

    private static void writeGrid(FileWriter writer, ServerLevel level, Map<BlockPos, SiegeNode> renderMap, BlockPos center,
                                   int radiusX, int heightY, int radiusZ) throws IOException {
        writer.write("LEGEND:\n");
        writer.write("  Blocks : [#] Solid   [.] Air   [/] Stair/Slab   [H] Ladder   [~] Fluid\n");
        writer.write("  Nodes  : [W] Walk    [S] Stair [B] Bridge       [M] Mine     [P] Pillar\n");
        writer.write("           [L] Landing [J] Leap  [H] Ladder       [R] Spiral   [x] Out of Bounds\n");
        writer.write("  Vectors: [v] Target Down   [^] Target Up    [=] Same Level   [?] Target Detached\n");
        writer.write("  Entities: [@] Entity Present\n");
        writer.write("====================================================\n");

        AABB searchBox = new AABB(
                center.getX() - radiusX, center.getY() - heightY, center.getZ() - radiusZ,
                center.getX() + radiusX, center.getY() + heightY, center.getZ() + radiusZ
        );
        List<LivingEntity> entitiesInArea = level.getEntitiesOfClass(LivingEntity.class, searchBox);

        for (int y = heightY; y >= -heightY; y--) {
            int currentY = center.getY() + y;
            writer.write(String.format("\n--- LAYER Y = %d ---\n", currentY));

            writer.write(String.format("%-" + (radiusX * 2 + 3) + "s | %-" + (radiusX * 2 + 3) + "s | %-" + (radiusX * 2 + 3) + "s | %s\n",
                    "PHYSICAL BLOCKS", "NODE INSTRUCTIONS", "VERTICAL VECTORS", "ENTITY MAP"));

            for (int z = -radiusZ; z <= radiusZ; z++) {
                StringBuilder rowBlocks = new StringBuilder();
                StringBuilder rowNodes = new StringBuilder();
                StringBuilder rowVectors = new StringBuilder();
                StringBuilder rowEntities = new StringBuilder();

                for (int x = -radiusX; x <= radiusX; x++) {
                    BlockPos pos = new BlockPos(center.getX() + x, currentY, center.getZ() + z);

                    BlockState state = level.getBlockState(pos);
                    char blockChar = '.';
                    if (state.isSolidRender(level, pos)) {
                        blockChar = '#';
                    } else if (state.getBlock() instanceof StairBlock || state.getBlock() instanceof SlabBlock) {
                        blockChar = '/';
                    } else if (state.getBlock() instanceof LadderBlock) {
                        blockChar = 'H';
                    } else if (!state.getFluidState().isEmpty()) {
                        blockChar = '~';
                    }
                    rowBlocks.append(blockChar);

                    SiegeNode node = renderMap.get(pos);
                    char nodeChar = '.';
                    boolean isOob = (pos.getY() < level.getMinBuildHeight() || pos.getY() > level.getMaxBuildHeight());

                    if (node != null) {
                        switch (node.action()) {
                            case WALK -> nodeChar = 'W';
                            case BUILD_STAIR -> nodeChar = 'S';
                            case BUILD_BRIDGE -> nodeChar = 'B';
                            case MINE -> nodeChar = 'M';
                            case BUILD_PILLAR -> nodeChar = 'P';
                            case BUILD_LANDING -> nodeChar = 'L';
                            case LEAP -> nodeChar = 'J';
                            case BUILD_LADDER -> nodeChar = 'H';
                            case BUILD_SPIRAL -> nodeChar = 'R';
                        }
                    } else if (isOob) {
                        nodeChar = 'x';
                    }
                    rowNodes.append(nodeChar);

                    char vectorChar = '.';
                    if (node != null) {
                        int targetY = node.pos().getY();
                        if (targetY < currentY) vectorChar = 'v';
                        else if (targetY > currentY) vectorChar = '^';
                        else vectorChar = '=';
                    }
                    rowVectors.append(vectorChar);

                    boolean hasEntity = entitiesInArea.stream().anyMatch(e -> e.blockPosition().equals(pos));
                    rowEntities.append(hasEntity ? '@' : '.');
                }

                writer.write(String.format("%-" + (radiusX * 2 + 3) + "s | %-" + (radiusX * 2 + 3) + "s | %-" + (radiusX * 2 + 3) + "s | %s\n",
                        rowBlocks.toString(), rowNodes.toString(), rowVectors.toString(), rowEntities.toString()));
            }
        }
    }
}
