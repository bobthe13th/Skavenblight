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
import org.ratden.skavenblight.ai.pathing.FlowStep;
import org.ratden.skavenblight.ai.pathing.PathAction;
import org.ratden.skavenblight.ai.pathing.region.Region;
import org.ratden.skavenblight.ai.pathing.region.RegionFlowField;
import org.ratden.skavenblight.ai.pathing.region.RegionGraph;
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
            Map<BlockPos, FlowStep> renderMap = calculating ? flowField.getLiveDebugMap() : flowField.getInstructionMap();

            writeHeader(writer, flowField, regionMap, center, radiusX, heightY, radiusZ, calculating, renderMap);
            writeRegionGraph(writer, regionMap);
            writeMetrics(writer, renderMap);
            writeNearbyMobs(writer, level, regionMap, center, Math.max(radiusX, radiusZ), heightY);
            writeMobPathTraces(writer, regionMap, level, center, Math.max(radiusX, radiusZ), heightY);
            writeRecentActivity(writer, center, Math.max(radiusX, radiusZ) * 2);
            writeGrid(writer, level, renderMap, regionMap, calculating, center, radiusX, heightY, radiusZ);

            writer.write("\n================ END OF DIAGNOSTIC DUMP ================\n");
            return dumpFile.getAbsolutePath();

        } catch (IOException e) {
            System.err.println("[Skavenblight] Failed to write topology deep dump!");
            e.printStackTrace();
            return null;
        }
    }

    private static void writeHeader(FileWriter writer, RegionFlowField flowField, TerritoryRegionMap regionMap, BlockPos center,
                                     int radiusX, int heightY, int radiusZ, boolean calculating, Map<BlockPos, FlowStep> renderMap) throws IOException {
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

        // These four all read TerritoryRegionMap's network-wide SiegeProjectManager /
        // FlowFieldCalculator / TerrainSnapshot, which every region's pass shares. NOTE the scope
        // change from the pre-region version of this file: the per-pass counters below describe
        // the LAST region processed on this network, not this dump's region specifically (a full
        // rebuild walks every region through the same two objects in sequence).
        writer.write(String.format("Snapshot Coverage: %d / %d territory chunks captured\n",
                regionMap.getCapturedChunkCount(), regionMap.getTerritoryChunkCount()));
        writer.write(String.format("Node Budget (throttled): %d nodes/pass\n", regionMap.getThrottler().getNodesPerTick()));
        // Distinct from "Total Nodes" above (the published instruction map's size) - this is
        // nextCostMap's size, the counter the budget cutoff actually checks. A single successful
        // macro-project line costs this counter only ONE slot (its endpoint) while writing up to
        // 32 entries into the published instruction map, so "Total Nodes" can look enormous while
        // this - the real competition WALK propagation is up against - is quietly maxed out.
        writer.write(String.format("Dijkstra Budget Used: %d / %d nodes (%s)\n",
                regionMap.getCalculator().getLastPassNodeCount(), regionMap.getThrottler().getNodesPerTick(),
                regionMap.getCalculator().isLastPassBudgetExhausted() ? "EXHAUSTED - queue cut off early" : "queue drained naturally"));
        writer.write(String.format("Siege Projects: %d active | %d generated / %d survived this pass\n",
                regionMap.getProjectManager().getActiveProjectCount(),
                regionMap.getProjectManager().getLastPassCandidatesGenerated(),
                regionMap.getProjectManager().getLastPassCandidatesSurvived()));
        writer.write(String.format("Macro Evaluation: triggered %d times | %d total line-steps evaluated this pass\n",
                regionMap.getProjectManager().getMacroEvaluationCount(),
                regionMap.getProjectManager().getLineStepsEvaluated()));
        writer.write("  (High macro-evaluation/line-step counts relative to node count is the signature of\n");
        writer.write("   the hitObstacle heuristic over-triggering - see FlowFieldCalculator - and is the\n");
        writer.write("   single biggest lever on calculation cost if a pass is slow or looks hung.)\n\n");
    }

    /**
     * Region graph + route tree snapshot - added when this file migrated off StandardFlowField's
     * single-territory model, since region/connector/reachability info now lives on
     * TerritoryRegionMap rather than on any individual RegionFlowField.
     */
    private static void writeRegionGraph(FileWriter writer, TerritoryRegionMap regionMap) throws IOException {
        writer.write("--- REGION GRAPH ---\n");
        RegionGraph graph = regionMap.getRegionGraph();
        RegionRouteTree routeTree = regionMap.getRouteTree();
        List<Region> regions = graph != null ? graph.getRegions() : List.of();

        writer.write(String.format("Regions: %d | Connectors: %d | Rebuild generation: %d\n",
                regions.size(), graph != null ? graph.getAllConnectors().size() : 0, regionMap.getGeneration()));
        if (regions.isEmpty()) {
            writer.write("  (no regions scanned yet - the region map never initialized, or its first rebuild is still running)\n");
        }

        for (Region region : regions) {
            boolean reachable = routeTree != null && routeTree.isReachable(region.getId());
            writer.write(String.format("  region %d: %d cells, reachable=%s, hopCost=%s\n",
                    region.getId(), region.cellCount(), reachable,
                    reachable ? String.valueOf(routeTree.getHopCost(region.getId())) : "n/a"));
        }
        writer.write("\n");
    }

    private static void writeMetrics(FileWriter writer, Map<BlockPos, FlowStep> renderMap) throws IOException {
        writer.write("--- FLOW FIELD METRICS ---\n");
        Map<PathAction, Integer> actionCounts = new EnumMap<>(PathAction.class);
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;

        for (Map.Entry<BlockPos, FlowStep> entry : renderMap.entrySet()) {
            BlockPos pos = entry.getKey();
            FlowStep node = entry.getValue();

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
        for (Map.Entry<PathAction, Integer> entry : actionCounts.entrySet()) {
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
    private static void writeNearbyMobs(FileWriter writer, ServerLevel level, TerritoryRegionMap regionMap, BlockPos center, int radius, int heightY) throws IOException {
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

            // Each mob's OWN region field, not the single field this dump happens to be centered
            // on - a mob dozens of blocks away in a different region always fell through to
            // "no-instruction (wilderness)" here even when its actual assigned field had a real
            // instruction, because it was being asked the exported region's field instead of its own.
            RegionFlowField mobField = regionMap.getRegionFlowFieldFor(pos);
            FlowStep next = mobField != null ? mobField.getNextStep(level, pos) : null;
            String nextDesc = next == null ? "no-instruction (wilderness)" : String.format("%s -> %s", next.action(), next.pos().toShortString());

            String jamFlag = occupancy.getOrDefault(pos, 1) > 1 ? String.format(" [JAM: %d mobs on this block]", occupancy.get(pos)) : "";

            // "running" alone can't tell you WHY a higher-priority construction goal isn't the
            // one running - canUseState answers that directly (declining on its own merits vs.
            // never getting picked despite being able to run). claimant answers the other half:
            // if canUseNow=false because the target is claimed, WHO holds it and are they even
            // near it - a live-but-stuck-elsewhere claimant blocks everyone else identically to a
            // healthy in-progress build, and was invisible before this.
            String canUseState = (mob instanceof ClanratEntity clanrat) ? clanrat.describeSiegeGoalCanUseState() : "n/a";
            String claimantDesc = "";
            String claimantStateDesc = "";
            if (next != null && next.action() != PathAction.WALK && mobField != null) {
                Mob claimant = mobField.getClaimant(next.pos());
                if (claimant != null) {
                    double dist = Math.sqrt(claimant.blockPosition().distSqr(next.pos()));
                    claimantDesc = String.format(" | claimant of %s: %s (%s) @ %s alive=%s dist=%.1f",
                            next.pos().toShortString(), BuiltInRegistries.ENTITY_TYPE.getKey(claimant.getType()),
                            claimant.getUUID().toString().substring(0, 8), claimant.blockPosition().toShortString(),
                            claimant.isAlive(), dist);
                    // Compare across two dumps taken a few seconds apart: actionTicks/stalledTicks
                    // NOT advancing between them, or repeated identical targetPos, means the
                    // claimant is genuinely stuck - not just caught mid-animation on this one snapshot.
                    if (claimant instanceof ClanratEntity claimantRat) {
                        claimantStateDesc = "\n    claimant state: " + claimantRat.describeActiveSiegeGoalState();
                    }
                }
            }

            writer.write(String.format("  %-22s @ %-16s | running: %-40s | next: %s%s%s\n    canUse: %s%s\n",
                    BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()), pos.toShortString(), goals, nextDesc, jamFlag,
                    claimantDesc, canUseState, claimantStateDesc));
        }
        writer.write("\n");
    }

    // Bound on writeMobPathTraces's forward walk - generously above MAX_CHAIN_HOPS *
    // MAX_PROJECT_LENGTH (see RegionGraph) so a genuinely long but correct multi-region route
    // isn't mistaken for a stuck trace; a real loop reveals itself in a handful of steps anyway.
    private static final int MAX_TRACE_STEPS = 60;

    /**
     * Forward-simulates each nearby mob's raw instruction chain, one region-owned cell at a
     * time, independent of goal/claim-table/live-completion state - the ASCII grid only encodes
     * each cell's action (W/M/etc), not which neighbor it points to, so a genuine cycle in the
     * computed field (as opposed to goal-level oscillation from lane contention) was invisible
     * without actually walking the chain. Added after a live report of "arrows pointing in a
     * loop rather than a path that leads to the nexus" that the grid alone couldn't confirm.
     */
    private static void writeMobPathTraces(FileWriter writer, TerritoryRegionMap regionMap, ServerLevel level,
                                            BlockPos center, int radius, int heightY) throws IOException {
        writer.write("--- MOB PATH TRACE (raw instruction chain, ignores goal/claim state) ---\n");

        AABB box = new AABB(center).inflate(radius, heightY, radius);
        List<Mob> mobs = level.getEntitiesOfClass(Mob.class, box);

        if (mobs.isEmpty()) {
            writer.write("(none within range)\n\n");
            return;
        }

        for (Mob mob : mobs) {
            BlockPos start = mob.blockPosition();
            List<BlockPos> path = new ArrayList<>();
            Set<BlockPos> visited = new HashSet<>();
            BlockPos current = start;
            String outcome = "trace budget exhausted (" + MAX_TRACE_STEPS + " steps) without reaching a local objective";

            for (int step = 0; step < MAX_TRACE_STEPS; step++) {
                if (!visited.add(current)) {
                    outcome = "LOOP DETECTED - revisited " + current.toShortString();
                    break;
                }
                path.add(current);

                RegionFlowField field = regionMap.getRegionFlowFieldFor(current);
                if (field == null) {
                    outcome = "left mapped territory (wilderness) at " + current.toShortString();
                    break;
                }
                FlowStep node = field.getRawInstruction(current);
                if (node == null) {
                    outcome = "no instruction at " + current.toShortString();
                    break;
                }
                if (node.pos().equals(current)) {
                    // Every region's own local Dijkstra target self-references (see
                    // FlowFieldCalculator.startCalculation) - reaching one is the correct, expected
                    // end of this region's portion of the chain, not a bug. Actually crossing a
                    // connector into the next region is a separate active-project mechanic this
                    // pure data trace doesn't simulate.
                    outcome = "reached region " + field.getRegionId() + "'s local objective at " + current.toShortString();
                    break;
                }
                current = node.pos();
            }

            StringBuilder pathStr = new StringBuilder();
            for (int i = 0; i < path.size(); i++) {
                if (i > 0) pathStr.append(" -> ");
                pathStr.append(path.get(i).toShortString());
            }

            writer.write(String.format("  %s from %s (%d cells): %s\n",
                    BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()), start.toShortString(), path.size(), outcome));
            writer.write("    " + pathStr + "\n");
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

    /**
     * @param renderMap the dump's SINGLE targeted region's map (the region containing the nexus -
     * see DebugFlowFieldReaderItem) - used only as the live-progress fallback while a calculation
     * is running (getLiveDebugMap() reflects whichever one region the shared calculator happens to
     * be processing at this instant; there's no per-region live view to fall back to instead).
     * @param regionMap owner of every region's flow field - used once ready to look up each grid
     * cell's OWN region and read ITS instruction, not the single targeted region's. Grid cells
     * routinely belong to a completely different region than the one the debug item is aimed at
     * (e.g. the player standing in the ground region while the item targets the nexus's own tiny
     * platform region) - rendering only the targeted region's map left every other region's cells
     * blank, making a real flow field (arrows, including loops) invisible in the dump whenever it
     * wasn't in the exact region the item happened to be pointed at.
     */
    private static void writeGrid(FileWriter writer, ServerLevel level, Map<BlockPos, FlowStep> renderMap,
                                   TerritoryRegionMap regionMap, boolean calculating, BlockPos center,
                                   int radiusX, int heightY, int radiusZ) throws IOException {
        writer.write("LEGEND:\n");
        writer.write("  Blocks : [#] Solid   [.] Air   [/] Stair/Slab   [H] Ladder   [~] Fluid\n");
        writer.write("  Nodes  : [W] Walk    [S] Air-Stair [C] Carved-Stair [B] Bridge  [M] Tunnel   [x] Out of Bounds\n");
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

                    // While calculating there's only one live progress snapshot to show (whichever
                    // region the shared calculator is presently working through) - fall back to the
                    // targeted region's map, same as before this fix. Once ready, look up THIS
                    // cell's own region rather than assuming it belongs to the targeted region.
                    FlowStep node;
                    if (calculating) {
                        node = renderMap.get(pos);
                    } else {
                        RegionFlowField cellField = regionMap.getRegionFlowFieldFor(pos);
                        node = cellField != null ? cellField.getRawInstruction(pos) : null;
                    }
                    char nodeChar = '.';
                    boolean isOob = (pos.getY() < level.getMinBuildHeight() || pos.getY() > level.getMaxBuildHeight());

                    if (node != null) {
                        switch (node.action()) {
                            case WALK -> nodeChar = 'W';
                            case TUNNEL -> nodeChar = 'M';
                            case BRIDGE -> nodeChar = 'B';
                            case AIR_STAIR -> nodeChar = 'S';
                            case CARVED_STAIR -> nodeChar = 'C';
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
