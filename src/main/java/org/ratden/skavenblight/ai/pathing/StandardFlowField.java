package org.ratden.skavenblight.ai.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import org.ratden.skavenblight.Config;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;

public class StandardFlowField {
    // --- NEW: Double Buffer System ---
    private Map<BlockPos, Integer> costMap = new HashMap<>();     // What the rats read
    private final Map<BlockPos, Integer> nextCostMap = new HashMap<>(); // What the server builds

    private final BlockPos targetPos;
    private final Set<ChunkPos> territoryChunks;

    private Queue<BlockPos> calcQueue;
    private boolean isCalculating = false;
    private long lastCalculationStart = 0;

    private static final BlockPos[] NEIGHBORS;
    static {
        List<BlockPos> offsets = new ArrayList<>();
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    if (x == 0 && y == 0 && z == 0) continue;
                    if (x == 0 && z == 0) continue; // Prevent vertical elevator suffocation
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

    public void calculateMapIfNeeded(ServerLevel level) {
        long currentTime = level.getGameTime();

        // Start a new background calculation every 4 seconds (80 ticks) if one isn't running
        if (!isCalculating && (this.costMap.isEmpty() || currentTime - lastCalculationStart >= 80)) {
            isCalculating = true;
            lastCalculationStart = currentTime;

            nextCostMap.clear();
            calcQueue = new PriorityQueue<>(Comparator.comparingInt(pos -> nextCostMap.getOrDefault(pos, Integer.MAX_VALUE)));
            calcQueue.add(targetPos);
            nextCostMap.put(targetPos, 0);
        }

        // Process exactly 3000 blocks per tick. No lag, infinite map size!
        if (isCalculating) {
            int nodesProcessed = 0;

            while (!calcQueue.isEmpty() && nodesProcessed < 3000) {
                BlockPos current = calcQueue.poll();
                nodesProcessed++;

                int currentCost = nextCostMap.get(current);

                for (BlockPos offset : NEIGHBORS) {
                    BlockPos neighbor = current.offset(offset);

                    if (Math.abs(neighbor.getY() - targetPos.getY()) > 48) continue;
                    if (level.isOutsideBuildHeight(neighbor)) continue;

                    ChunkPos neighborChunk = new ChunkPos(neighbor);
                    if (!territoryChunks.isEmpty() && !territoryChunks.contains(neighborChunk)) continue;
                    if (territoryChunks.isEmpty() && current.distManhattan(targetPos) > 32) continue;

                    int stepCost = calculateStepCost(level, neighbor, offset);

                    if (stepCost < Integer.MAX_VALUE) {
                        int newCost = currentCost + stepCost;

                        if (newCost < nextCostMap.getOrDefault(neighbor, Integer.MAX_VALUE)) {
                            nextCostMap.put(neighbor, newCost);
                            calcQueue.add(neighbor);
                        }
                    }
                }
            }

            // Once the queue is empty, the entire territory is mapped! Swap the buffers.
            if (calcQueue.isEmpty()) {
                isCalculating = false;
                this.costMap = new HashMap<>(nextCostMap);
            }
        }
    }

    private int calculateStepCost(ServerLevel level, BlockPos footPos, BlockPos offset) {
        int engineeringCost = 0;
        BlockPos supportPos = footPos.below();

        if (level.getBlockState(supportPos).getCollisionShape(level, supportPos).isEmpty()) {
            if (level.getFluidState(supportPos).isSource()) {
                return Integer.MAX_VALUE;
            }
            if (offset.getX() == 0 && offset.getZ() == 0 && offset.getY() > 0) {
                return Integer.MAX_VALUE;
            }
            engineeringCost += Config.buildingBasePenalty;
        }

        BlockPos headPos = footPos.above();
        BlockState footState = level.getBlockState(footPos);
        BlockState headState = level.getBlockState(headPos);

        boolean footPassable = footState.isAir() || !footState.blocksMotion();
        boolean headPassable = headState.isAir() || !headState.blocksMotion();

        if (footPassable && headPassable) {
            return 1 + engineeringCost;
        }

        float footHardness = footPassable ? 0 : footState.getDestroySpeed(level, footPos);
        float headHardness = headPassable ? 0 : headState.getDestroySpeed(level, headPos);

        if (footHardness < 0 || headHardness < 0) {
            return Integer.MAX_VALUE;
        }

        float totalHardness = footHardness + headHardness;
        int miningCost = (int) (totalHardness * Config.miningPenaltyMultiplier) + Config.miningBasePenalty;

        return miningCost + engineeringCost;
    }

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
        return bestNode;
    }

    public BlockPos getTargetPos() {
        return this.targetPos;
    }

    public Map<BlockPos, Integer> getCostMap() {
        return this.costMap;
    }
}