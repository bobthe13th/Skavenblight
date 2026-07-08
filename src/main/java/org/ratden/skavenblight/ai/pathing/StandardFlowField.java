package org.ratden.skavenblight.ai.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

public class StandardFlowField {
    // Stores how "expensive" it is to reach the target from any given block
    private final Map<BlockPos, Integer> costMap = new HashMap<>();
    private final BlockPos targetPos;

    // The contoured boundary defined by the conduits + config radius
    private final Set<ChunkPos> territoryChunks;

    // UPDATE: The constructor now requires the territory map to be passed in!
    public StandardFlowField(BlockPos targetPos, Set<ChunkPos> territoryChunks) {
        this.targetPos = targetPos;
        this.territoryChunks = territoryChunks;
    }

    public void calculateMap(ServerLevel level) {
        costMap.clear();
        Queue<BlockPos> queue = new LinkedList<>();

        // 1. Initialize the target block
        queue.add(targetPos);
        costMap.put(targetPos, 0); // The target itself is 0 steps away

        // 2. Flood-fill outward
        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            int currentCost = costMap.get(current);

            for (Direction dir : Direction.values()) {
                BlockPos neighbor = current.relative(dir);

                // BOUNDARY CHECK: If the neighbor steps out of the base's territory, stop!
                ChunkPos neighborChunk = new ChunkPos(neighbor);
                if (!territoryChunks.contains(neighborChunk)) {
                    continue;
                }

                if (costMap.containsKey(neighbor)) continue;

                int stepCost = calculateStepCost(level, neighbor);

                if (stepCost < Integer.MAX_VALUE) {
                    costMap.put(neighbor, currentCost + stepCost);
                    queue.add(neighbor);
                }
            }
        }
    }

    // --- HELPER METHODS ---

    private int calculateStepCost(ServerLevel level, BlockPos footPos) {
        BlockPos headPos = footPos.above();
        BlockState footState = level.getBlockState(footPos);
        BlockState headState = level.getBlockState(headPos);

        boolean footPassable = footState.isAir() || !footState.blocksMotion();
        boolean headPassable = headState.isAir() || !headState.blocksMotion();

        // 1. The Ideal Path: Both blocks are empty space
        if (footPassable && headPassable) {
            return 1;
        }

        // 2. The Mining Path: Get the hardness of both blocks
        float footHardness = footPassable ? 0 : footState.getDestroySpeed(level, footPos);
        float headHardness = headPassable ? 0 : headState.getDestroySpeed(level, headPos);

        // If either block is unbreakable (like Bedrock), this path is impossible
        if (footHardness < 0 || headHardness < 0) {
            return Integer.MAX_VALUE;
        }

        // 3. Combine the penalty: The mob has to mine through whatever is in the way
        float totalHardness = footHardness + headHardness;

        return (int) (totalHardness * 10) + 1;
    }

    // Mobs will call this to figure out which way to step!
    public Direction getBestDirection(BlockPos ratPos) {
        Direction bestDir = null;
        int lowestCost = Integer.MAX_VALUE;

        for (Direction dir : Direction.values()) {
            BlockPos neighbor = ratPos.relative(dir);
            if (costMap.containsKey(neighbor)) {
                int neighborCost = costMap.get(neighbor);
                if (neighborCost < lowestCost) {
                    lowestCost = neighborCost;
                    bestDir = dir;
                }
            }
        }
        return bestDir;
    }
}