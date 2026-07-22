package org.ratden.skavenblight.debug;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.ratden.skavenblight.ai.pathing.SiegeNode;
import org.ratden.skavenblight.ai.pathing.StandardFlowField;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

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
            Map<BlockPos, SiegeNode> instructionMap = flowField.getInstructionMap();

            writer.write("====================================================\n");
            writer.write("         SKAVENBLIGHT DEEP DIAGNOSTIC DUMP          \n");
            writer.write("====================================================\n\n");

            writer.write(String.format("Timestamp    : %s\n", LocalDateTime.now()));
            writer.write(String.format("Target Pos   : %s\n", flowField.getTargetPos().toShortString()));
            writer.write(String.format("Center Pos   : %s\n", center.toShortString()));
            writer.write(String.format("Search Bounds: +/- %dx, %dy, %dz (Y: %d to %d)\n",
                    radiusX, heightY, radiusZ, center.getY() - heightY, center.getY() + heightY));
            writer.write(String.format("FlowField State: %s | Total Nodes: %d\n\n",
                    flowField.isCalculating() ? "CALCULATING (Async)" : "READY", instructionMap.size()));

            // =========================================================================
            // 1. STATISTICAL BREAKDOWN
            // =========================================================================
            writer.write("--- FLOW FIELD METRICS ---\n");
            Map<SiegeNode.SiegeAction, Integer> actionCounts = new EnumMap<>(SiegeNode.SiegeAction.class);
            int minY = Integer.MAX_VALUE;
            int maxY = Integer.MIN_VALUE;

            for (Map.Entry<BlockPos, SiegeNode> entry : instructionMap.entrySet()) {
                BlockPos pos = entry.getKey();
                SiegeNode node = entry.getValue();

                actionCounts.put(node.action(), actionCounts.getOrDefault(node.action(), 0) + 1);
                if (pos.getY() < minY) minY = pos.getY();
                if (pos.getY() > maxY) maxY = pos.getY();
            }

            if (!instructionMap.isEmpty()) {
                writer.write(String.format("Mapped Y-Coverage : Min Y = %d | Max Y = %d (Delta: %d blocks)\n",
                        minY, maxY, (maxY - minY)));
            } else {
                writer.write("Mapped Y-Coverage : EMPTY MAP\n");
            }

            writer.write("Action Counts     : ");
            for (Map.Entry<SiegeNode.SiegeAction, Integer> entry : actionCounts.entrySet()) {
                writer.write(String.format("[%s: %d] ", entry.getKey().name(), entry.getValue()));
            }
            writer.write("\n\n");

            // =========================================================================
            // 2. LEGEND
            // =========================================================================
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

            // =========================================================================
            // 3. LAYER BY LAYER DUMP
            // =========================================================================
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

                        // --- 1. Block Representation ---
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

                        // --- 2. Node Instruction & Bounds Check ---
                        SiegeNode node = instructionMap.get(pos);
                        char nodeChar = '.';

                        // Check if position is considered out of bounds by flowfield state
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

                        // --- 3. Action Target Vector Delta ---
                        char vectorChar = '.';
                        if (node != null) {
                            int targetY = node.pos().getY();
                            if (targetY < currentY) vectorChar = 'v';      // Points downwards
                            else if (targetY > currentY) vectorChar = '^'; // Points upwards
                            else vectorChar = '=';                        // Points horizontal
                        }
                        rowVectors.append(vectorChar);

                        // --- 4. Entity Presence ---
                        boolean hasEntity = entitiesInArea.stream().anyMatch(e -> e.blockPosition().equals(pos));
                        rowEntities.append(hasEntity ? '@' : '.');
                    }

                    writer.write(String.format("%-" + (radiusX * 2 + 3) + "s | %-" + (radiusX * 2 + 3) + "s | %-" + (radiusX * 2 + 3) + "s | %s\n",
                            rowBlocks.toString(), rowNodes.toString(), rowVectors.toString(), rowEntities.toString()));
                }
            }

            writer.write("\n================ END OF DIAGNOSTIC DUMP ================\n");
            return dumpFile.getAbsolutePath();

        } catch (IOException e) {
            System.err.println("[Skavenblight] Failed to write topology deep dump!");
            e.printStackTrace();
            return null;
        }
    }
}