package org.ratden.skavenblight.ai.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

public class StandardFlowField {
    // Stores how "expensive" it is to reach the target from any given block
    private final Map<BlockPos, Integer> costMap = new HashMap<>();
    private final BlockPos targetPos;
    private long lastCalculatedTick = 0;

    // The contoured boundary defined by the conduits + config radius
    private final Set<ChunkPos> territoryChunks;

    // --- NEW: 26-Way 3D Neighborhood Offsets ---
    private static final BlockPos[] NEIGHBORS;
    static {
        List<BlockPos> offsets = new ArrayList<>();
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    if (x == 0 && y == 0 && z == 0) continue; // Skip the center block
                    offsets.add(new BlockPos(x, y, z));
                }
            }
        }
        NEIGHBORS = offsets.toArray(new BlockPos[0]);
    }

    public StandardFlowField(BlockPos targetPos, Set<ChunkPos> territoryChunks) {
        this.targetPos = targetPos;
        this.territoryChunks = territoryChunks;
    }

    public void calculateMap(ServerLevel level) {
        calculateMap(level, 50000);
    }

    public void calculateMap(ServerLevel level, int maxNodes) {
        costMap.clear();
        Queue<BlockPos> queue = new java.util.ArrayDeque<>();

        queue.add(targetPos);
        costMap.put(targetPos, 0);

        int nodesProcessed = 0;

        while (!queue.isEmpty() && nodesProcessed < maxNodes) {
            BlockPos current = queue.poll();
            nodesProcessed++;

            int currentCost = costMap.get(current);

            // --- CHANGED: Loop over all 26 adjacent spatial blocks instead of 4 cardinal directions ---
            for (BlockPos offset : NEIGHBORS) {
                BlockPos neighbor = current.offset(offset);

                if (Math.abs(neighbor.getY() - targetPos.getY()) > 48) continue;
                if (level.isOutsideBuildHeight(neighbor)) continue;

                ChunkPos neighborChunk = new ChunkPos(neighbor);
                if (!territoryChunks.contains(neighborChunk)) continue;

                if (costMap.containsKey(neighbor)) continue;

                int stepCost = calculateStepCost(level, neighbor);

                if (stepCost < Integer.MAX_VALUE) {
                    costMap.put(neighbor, currentCost + stepCost);
                    queue.add(neighbor);
                }
            }
        }
    }

    public void calculateMapIfNeeded(ServerLevel level) {
        long currentTime = level.getGameTime();
        if (this.costMap.isEmpty() || currentTime - this.lastCalculatedTick >= 40) {
            calculateMap(level, 50000);
            this.lastCalculatedTick = currentTime;
        }
    }

    private int calculateStepCost(ServerLevel level, BlockPos footPos) {
        BlockPos supportPos = footPos.below();
        if (level.getBlockState(supportPos).getCollisionShape(level, supportPos).isEmpty()) {
            return Integer.MAX_VALUE;
        }

        BlockPos headPos = footPos.above();
        BlockState footState = level.getBlockState(footPos);
        BlockState headState = level.getBlockState(headPos);

        boolean footPassable = footState.isAir() || !footState.blocksMotion();
        boolean headPassable = headState.isAir() || !headState.blocksMotion();

        if (footPassable && headPassable) {
            return 1;
        }

        float footHardness = footPassable ? 0 : footState.getDestroySpeed(level, footPos);
        float headHardness = headPassable ? 0 : headState.getDestroySpeed(level, headPos);

        if (footHardness < 0 || headHardness < 0) {
            return Integer.MAX_VALUE;
        }

        float totalHardness = footHardness + headHardness;
        return (int) (totalHardness * 10) + 1;
    }

    // --- CHANGED: Return a BlockPos instead of a Direction ---
    public BlockPos getBestNextNode(BlockPos ratPos) {
        BlockPos bestNode = null;
        int lowestCost = Integer.MAX_VALUE;
        double bestDistance = Double.MAX_VALUE;

        for (BlockPos offset : NEIGHBORS) {
            BlockPos neighbor = ratPos.offset(offset);

            if (costMap.containsKey(neighbor)) {
                int neighborCost = costMap.get(neighbor);
                double distToTarget = neighbor.distSqr(targetPos);

                if (neighborCost < lowestCost) {
                    lowestCost = neighborCost;
                    bestDistance = distToTarget;
                    bestNode = neighbor;
                }
                else if (neighborCost == lowestCost && distToTarget < bestDistance) {
                    bestDistance = distToTarget;
                    bestNode = neighbor;
                }
            }
        }
        return bestNode; // Returns the exact coordinate the mob should walk toward
    }

    public BlockPos getTargetPos() {
        return this.targetPos;
    }

    public Map<BlockPos, Integer> getCostMap() {
        return this.costMap;
    }
}