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
import org.ratden.skavenblight.ai.pathing.SiegeProjectManager;
import org.ratden.skavenblight.ai.pathing.StandardFlowField;
import org.ratden.skavenblight.entity.custom.ClanratEntity;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Text dump of a StandardFlowField's state, written for an LLM reading the file afterward to
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

    public static String exportDeepDump(ServerLevel level, StandardFlowField flowField, BlockPos center, int radiusX, int heightY, int radiusZ) {
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

            writeHeader(writer, level, flowField, center, radiusX, heightY, radiusZ, calculating, renderMap);
            writeMetrics(writer, renderMap);
            writeNearbyMobs(writer, level, flowField, center, Math.max(radiusX, radiusZ), heightY);
            writeGrid(writer, level, renderMap, center, radiusX, heightY, radiusZ);

            writer.write("\n================ END OF DIAGNOSTIC DUMP ================\n");
            return dumpFile.getAbsolutePath();

        } catch (IOException e) {
            System.err.println("[Skavenblight] Failed to write topology deep dump!");
            e.printStackTrace();
            return null;
        }
    }

    private static void writeHeader(FileWriter writer, ServerLevel level, StandardFlowField flowField, BlockPos center,
                                     int radiusX, int heightY, int radiusZ, boolean calculating, Map<BlockPos, SiegeNode> renderMap) throws IOException {
        writer.write("====================================================\n");
        writer.write("         SKAVENBLIGHT DEEP DIAGNOSTIC DUMP          \n");
        writer.write("====================================================\n\n");

        writer.write(String.format("Timestamp    : %s\n", LocalDateTime.now()));
        writer.write(String.format("Target Pos   : %s\n", flowField.getTargetPos().toShortString()));
        writer.write(String.format("Center Pos   : %s\n", center.toShortString()));
        writer.write(String.format("Search Bounds: +/- %dx, %dy, %dz (Y: %d to %d)\n",
                radiusX, heightY, radiusZ, center.getY() - heightY, center.getY() + heightY));

        if (calculating) {
            long elapsedTicks = level.getGameTime() - flowField.getLastCalculationStartGameTime();
            writer.write(String.format("FlowField State: CALCULATING (LIVE, in-progress snapshot) | Elapsed: %d ticks (%.1fs) | Nodes so far: %d\n",
                    elapsedTicks, elapsedTicks / 20.0, renderMap.size()));
        } else {
            writer.write(String.format("FlowField State: READY (last completed pass) | Total Nodes: %d\n", renderMap.size()));
        }

        SiegeProjectManager pm = flowField.getProjectManager();
        writer.write(String.format("Snapshot Coverage: %d / %d territory chunks captured\n",
                flowField.getCapturedChunkCount(), flowField.getTerritoryChunkCount()));
        writer.write(String.format("Node Budget (throttled): %d nodes/pass\n", flowField.getThrottler().getNodesPerTick()));
        writer.write(String.format("Siege Projects: %d active | %d candidate this pass\n",
                pm.getActiveProjectCount(), pm.getCandidateProjectCount()));
        writer.write(String.format("Macro Evaluation: triggered %d times | %d total line-steps evaluated this pass\n",
                pm.getMacroEvaluationCount(), pm.getLineStepsEvaluated()));
        writer.write("  (High macro-evaluation/line-step counts relative to node count is the signature of\n");
        writer.write("   the hitObstacle heuristic over-triggering - see FlowFieldCalculator - and is the\n");
        writer.write("   single biggest lever on calculation cost if a pass is slow or looks hung.)\n\n");
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
    private static void writeNearbyMobs(FileWriter writer, ServerLevel level, StandardFlowField flowField, BlockPos center, int radius, int heightY) throws IOException {
        writer.write("--- NEARBY MOBS (goal state) ---\n");

        AABB box = new AABB(center).inflate(radius, heightY, radius);
        List<Mob> mobs = level.getEntitiesOfClass(Mob.class, box);

        if (mobs.isEmpty()) {
            writer.write("(none within range)\n\n");
            return;
        }

        for (Mob mob : mobs) {
            BlockPos pos = mob.blockPosition();
            String goals = (mob instanceof ClanratEntity clanrat) ? clanrat.getActiveGoalNames() : "n/a";

            SiegeNode next = flowField.getNextSiegeNode(level, pos);
            String nextDesc = next == null ? "no-instruction (wilderness)" : String.format("%s -> %s", next.action(), next.pos().toShortString());

            writer.write(String.format("  %-22s @ %-16s | running: %-40s | next: %s\n",
                    BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()), pos.toShortString(), goals, nextDesc));
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
