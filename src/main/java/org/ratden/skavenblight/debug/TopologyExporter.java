package org.ratden.skavenblight.debug;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.ratden.skavenblight.ai.pathing.SiegeNode;
import org.ratden.skavenblight.ai.pathing.StandardFlowField;

import java.util.Map;

public class TopologyExporter {

    public static String exportSlice(ServerLevel level, StandardFlowField flowField, BlockPos center, int radiusX, int heightY, int radiusZ) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== SKAVENBLIGHT TOPOLOGY EXPORT ===\n");
        sb.append(String.format("Center: %s | Bounds: +/- %dx, %dy, %dz\n", center.toShortString(), radiusX, heightY, radiusZ));

        Map<BlockPos, SiegeNode> instructionMap = flowField.getInstructionMap();

        for (int y = heightY; y >= -heightY; y--) {
            int currentY = center.getY() + y;
            sb.append(String.format("\n--- LAYER Y = %d ---\n", currentY));

            for (int z = -radiusZ; z <= radiusZ; z++) {
                StringBuilder rowBlocks = new StringBuilder();
                StringBuilder rowNodes = new StringBuilder();

                for (int x = -radiusX; x <= radiusX; x++) {
                    BlockPos pos = new BlockPos(center.getX() + x, currentY, center.getZ() + z);
                    BlockState state = level.getBlockState(pos);
                    SiegeNode node = instructionMap.get(pos);

                    // Block Legend: '#' Solid, '.' Air, '/' Stair, '~' Fluid
                    char blockChar = '.';
                    if (state.isSolidRender(level, pos)) blockChar = '#';
                    else if (state.getBlock().getName().getString().contains("stair")) blockChar = '/';
                    else if (!state.getFluidState().isEmpty()) blockChar = '~';

                    rowBlocks.append(blockChar);

                    // Node Action Legend: '.' None, 'W' Walk, 'S' Stair, 'B' Bridge, 'M' Mine
                    char nodeChar = '.';
                    if (node != null) {
                        switch (node.action()) {
                            case WALK -> nodeChar = 'W';
                            case BUILD_STAIR -> nodeChar = 'S';
                            case BUILD_BRIDGE -> nodeChar = 'B';
                            case MINE -> nodeChar = 'M';
                            case BUILD_PILLAR -> nodeChar = 'P';
                            case BUILD_LANDING -> nodeChar = 'L';
                            case LEAP -> nodeChar = 'J';
                        }
                    }
                    rowNodes.append(nodeChar);
                }
                sb.append(String.format("%-15s | %s\n", rowBlocks.toString(), rowNodes.toString()));
            }
        }
        sb.append("\nBlocks: #=Solid, .=Air, /=Stair, ~=Fluid\n");
        sb.append("Nodes : W=Walk, S=Stair, B=Bridge, M=Mine, P=Pillar\n");
        return sb.toString();
    }
}