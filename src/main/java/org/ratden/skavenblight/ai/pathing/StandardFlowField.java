package org.ratden.skavenblight.ai.pathing;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.ratden.skavenblight.Config;
import org.slf4j.Logger;

import java.util.*;

public class StandardFlowField {

    private static final Logger LOGGER = LogUtils.getLogger();

    private Map<BlockPos, SiegeNode> instructionMap = new HashMap<>();
    private final Map<BlockPos, Integer> nextCostMap = new HashMap<>();
    private final Map<BlockPos, SiegeNode> nextInstructionMap = new HashMap<>();

    // Macro NavMesh Tracking
    private final Set<ChunkPos> mappedChunks = new HashSet<>();
    private final Map<ChunkPos, List<BlockPos>> chunkToBlocksIndex = new HashMap<>();

    private final List<BlockPos> plannedProjects = new ArrayList<>();

    // --- Persistent Blueprint Memory ---
    private final List<SiegeProject> activeProjects = new ArrayList<>();
    private final List<SiegeProject> candidateProjects = new ArrayList<>(); // Ghost project holding list
    private final Set<BlockPos> lockedPositions = new HashSet<>();

    private final BlockPos targetPos;
    private final Set<ChunkPos> territoryChunks;

    // THE FIX: Safe Priority Queue Record wrapper
    private record QueueNode(BlockPos pos, int cost) implements Comparable<QueueNode> {
        @Override
        public int compareTo(QueueNode o) {
            return Integer.compare(this.cost, o.cost);
        }
    }

    private Queue<QueueNode> calcQueue;
    private boolean isCalculating = false;
    private long lastCalculationStart = 0;

    private static final int[][] CARDINAL_OFFSETS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}
    };

    private static final int[][] HORIZONTAL_OFFSETS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {-1, -1}, {1, -1}, {-1, 1}
    };

    private static final int MAX_PROJECT_LENGTH = 32;

    // --- Throttling & Performance ---
    private int currentRefreshRate = 80;
    private int currentNodesPerTick = 1000;
    private long lastThrottleUpdateTime = 0;
    private long lastBlockChangeTime = 0;

    private boolean isDirty = true;

    private class SiegeProject {
        private final Map<BlockPos, SiegeNode> instructions;

        public SiegeProject(Map<BlockPos, SiegeNode> instructions) {
            this.instructions = instructions;
        }

        public boolean isStarted(ServerLevel level) {
            for (SiegeNode node : instructions.values()) {
                if (isNodeCompleted(level, node)) {
                    return true;
                }
            }
            return false;
        }

        public boolean isCompleted(ServerLevel level) {
            for (SiegeNode node : instructions.values()) {
                if (!isNodeCompleted(level, node)) {
                    return false;
                }
            }
            return true;
        }

        public Map<BlockPos, SiegeNode> getRemainingInstructions(ServerLevel level) {
            Map<BlockPos, SiegeNode> remaining = new HashMap<>();
            for (Map.Entry<BlockPos, SiegeNode> entry : instructions.entrySet()) {
                if (!isNodeCompleted(level, entry.getValue())) {
                    remaining.put(entry.getKey(), entry.getValue());
                }
            }
            return remaining;
        }

        private boolean isNodeCompleted(ServerLevel level, SiegeNode node) {
            BlockState targetState = level.getBlockState(node.pos());
            if (node.action() == SiegeNode.SiegeAction.MINE) {
                return !targetState.blocksMotion() || isWalkableScaffold(targetState);
            } else {
                return !targetState.canBeReplaced();
            }
        }
    }

    public StandardFlowField(BlockPos targetPos, Set<ChunkPos> territoryChunks) {
        this.targetPos = targetPos;
        this.territoryChunks = territoryChunks;
    }

    public void onBlockChanged(BlockPos pos) {
        if (!isOutOfBounds(null, pos)) {
            this.isDirty = true;
            this.lastBlockChangeTime = System.currentTimeMillis(); // Log the time of the edit
        }
    }

    public void calculateMapIfNeeded(ServerLevel level) {
        long currentTime = level.getGameTime();

        // 1. Dynamic Throttling - Drastically reduced node limits to prevent massive server lag spikes
        if (currentTime - lastThrottleUpdateTime >= 200) {
            this.lastThrottleUpdateTime = currentTime;
            float realMSPT = level.getServer().getAverageTickTimeNanos() / 1000000.0f;

            int targetRefreshRate = 80;
            int targetNodesPerTick = 250; // Normal load: Process 250 nodes per tick (reduced from 2000)

            if (realMSPT >= 50.0f) {
                targetRefreshRate = 300;
                targetNodesPerTick = 50;  // Heavy lag: Trickle at 50 nodes per tick (reduced from 500)
            } else if (realMSPT >= 40.0f) {
                targetRefreshRate = 160;
                targetNodesPerTick = 100; // Moderate lag: Process 100 nodes per tick (reduced from 1000)
            }

            if (targetRefreshRate != this.currentRefreshRate || targetNodesPerTick != this.currentNodesPerTick) {
                this.currentRefreshRate = targetRefreshRate;
                this.currentNodesPerTick = targetNodesPerTick;
            }
        }

        // 2. The Settle Delay: Ensure 1 full second (1000ms) has passed since the player last touched a block
        boolean terrainHasSettled = (System.currentTimeMillis() - this.lastBlockChangeTime) >= Config.minimumSettleDelayMs;

        // 3. Start calculation sequence only if dirty, settled, and off cooldown
        if (!isCalculating && (this.instructionMap.isEmpty() || (this.isDirty && terrainHasSettled && currentTime - lastCalculationStart >= this.currentRefreshRate))) {
            isCalculating = true;
            this.isDirty = false;
            lastCalculationStart = currentTime;

            // --- DIAGNOSTIC LOG START ---
            System.out.println("[Skavenblight] FlowField calculation STARTED! Target: " + this.targetPos + " | FlowField Hash: " + System.identityHashCode(this));

            nextCostMap.clear();
            nextInstructionMap.clear();
            plannedProjects.clear();

            calcQueue = new PriorityQueue<>();

            calcQueue.add(new QueueNode(targetPos, 0));
            nextCostMap.put(targetPos, 0);
            nextInstructionMap.put(targetPos, new SiegeNode(targetPos, SiegeNode.SiegeAction.WALK));

            lockedPositions.clear();

            // THE FIX: Only remove projects if they are actually finished.
            activeProjects.removeIf(project -> project.isCompleted(level));
            candidateProjects.clear(); // Clear candidates on new calculation

            for (SiegeProject project : activeProjects) {
                for (Map.Entry<BlockPos, SiegeNode> entry : project.getRemainingInstructions(level).entrySet()) {
                    BlockPos pos = entry.getKey();
                    lockedPositions.add(pos);
                    nextInstructionMap.put(pos, entry.getValue());
                    nextCostMap.put(pos, 100); // Scaled for the 10x cost logic
                    calcQueue.add(new QueueNode(pos, 100));
                }
            }
        }

        // 4. Spread calculation logic over multiple ticks to prevent freezing the server thread
        if (isCalculating) {
            int nodesProcessed = 0;

            while (!calcQueue.isEmpty() && nodesProcessed < this.currentNodesPerTick) {

                if (nextCostMap.size() >= Config.maxFlowFieldNodes) {
                    calcQueue.clear();
                    break;
                }

                QueueNode qNode = calcQueue.poll();
                BlockPos current = qNode.pos();
                int currentCost = qNode.cost();
                nodesProcessed++;

                // THE FIX: Stale Node Skip logic for the uncorrupted PriorityQueue
                if (currentCost > nextCostMap.getOrDefault(current, Integer.MAX_VALUE)) {
                    continue;
                }

                for (int[] offset : HORIZONTAL_OFFSETS) {
                    for (int dy = -1; dy <= 4; dy++) {
                        BlockPos neighbor = current.offset(offset[0], dy, offset[1]);

                        if (isOutOfBounds(level, neighbor)) continue;

                        if (isWalkableTerrain(level, neighbor)) {
                            if (evaluateSimpleStep(level, neighbor, current, dy)) {

                                boolean isGap = !level.getBlockState(current.offset(offset[0], 0, offset[1]).below()).blocksMotion();
                                boolean targetIsSolid = level.getBlockState(neighbor.below()).blocksMotion();

                                // THE FIX: Diagonal Math Smoothing (10x scaling)
                                int stepCost = (offset[0] != 0 && offset[1] != 0) ? 14 : 10;
                                SiegeNode.SiegeAction stepAction = SiegeNode.SiegeAction.WALK;

                                if (isGap && targetIsSolid && dy == 0) {
                                    stepAction = SiegeNode.SiegeAction.LEAP;
                                    stepCost += 10; // Scaled for 10x logic
                                }

                                int totalCost = currentCost + stepCost;

                                if (totalCost < nextCostMap.getOrDefault(neighbor, Integer.MAX_VALUE)) {
                                    if (!lockedPositions.contains(neighbor)) {
                                        nextCostMap.put(neighbor, totalCost);
                                        nextInstructionMap.put(neighbor, new SiegeNode(current, stepAction));
                                        calcQueue.add(new QueueNode(neighbor, totalCost));
                                    }
                                }
                            }
                        }
                    }
                }

                if (isWalkableTerrain(level, current) || current.equals(targetPos)) {
                    for (int[] dir : CARDINAL_OFFSETS) {
                        for (int dy = -1; dy <= 1; dy++) {
                            evaluateMacroProject(level, current, currentCost, dir[0], dy, dir[1]);
                        }
                    }
                }
            }

            // Calculations are finished! Assign values to the active map
            if (calcQueue.isEmpty()) {
                isCalculating = false;
                this.instructionMap = new HashMap<>(nextInstructionMap);

                // --- GHOST PROJECT FILTERING ---
                // Only commit macro projects if they weren't overwritten by a cheaper walk path
                for (SiegeProject project : candidateProjects) {
                    boolean survived = true;
                    for (Map.Entry<BlockPos, SiegeNode> entry : project.instructions.entrySet()) {
                        SiegeNode finalNode = this.instructionMap.get(entry.getKey());

                        // If the node was overwritten (e.g., replaced by a WALK node), discard the project
                        if (finalNode == null || finalNode.action() != entry.getValue().action()) {
                            survived = false;
                            break;
                        }
                    }
                    if (survived) {
                        this.activeProjects.add(project);
                    }
                }
                candidateProjects.clear();

                this.mappedChunks.clear();
                this.chunkToBlocksIndex.clear();
                for (BlockPos pos : this.instructionMap.keySet()) {
                    ChunkPos cp = new ChunkPos(pos);
                    this.mappedChunks.add(cp);
                    this.chunkToBlocksIndex.computeIfAbsent(cp, k -> new ArrayList<>()).add(pos);
                }

                // --- DIAGNOSTIC LOG END ---
                System.out.println("[Skavenblight] FlowField calculation FINISHED! Total Nodes: " + this.instructionMap.size() + " | FlowField Hash: " + System.identityHashCode(this));
            }
        }
    }

    private void evaluateMacroProject(ServerLevel level, BlockPos anchorPos, int anchorCost, int dx, int dy, int dz) {
        int projectCost = Config.buildingBasePenalty * 10; // Scaled to match the 10x walk cost
        BlockPos currentTarget = anchorPos;

        Map<BlockPos, Integer> tempCostMap = new HashMap<>();
        Map<BlockPos, SiegeNode> tempInstructionMap = new HashMap<>();
        Map<BlockPos, SiegeNode> currentProjectInstructions = new HashMap<>();

        for (int i = 1; i <= MAX_PROJECT_LENGTH; i++) {
            BlockPos ratPos = currentTarget.offset(-dx, -dy, -dz);

            if (isOutOfBounds(level, ratPos)) break;
            if (lockedPositions.contains(ratPos)) return;

            for (BlockPos existing : plannedProjects) {
                if (existing.distSqr(ratPos) < 256) return;
            }

            BlockState targetFoot = level.getBlockState(currentTarget);
            BlockState targetHead = level.getBlockState(currentTarget.above());
            BlockState targetCeiling = level.getBlockState(currentTarget.above(2));
            BlockState targetSupport = level.getBlockState(currentTarget.below());

            boolean isTargetBlockFoot = currentTarget.equals(this.targetPos);
            boolean isTargetBlockHead = currentTarget.above().equals(this.targetPos);
            boolean isTargetBlockCeiling = currentTarget.above(2).equals(this.targetPos);

            boolean needsMiningFoot = targetFoot.blocksMotion() && !isWalkableScaffold(targetFoot) && !isTargetBlockFoot;
            boolean needsMiningHead = targetHead.blocksMotion() && !isWalkableScaffold(targetHead) && !isTargetBlockHead;
            boolean needsMiningCeiling = (dy != 0) && targetCeiling.blocksMotion() && !isWalkableScaffold(targetCeiling) && !isTargetBlockCeiling;
            boolean needsSupport = !targetSupport.blocksMotion() && !isWalkableScaffold(targetSupport);

            SiegeNode actionNode;

            // Prioritize top-down mining so mobs don't try to mine through solid blocks above them
            if (needsMiningCeiling) {
                float hardness = level.getBlockState(currentTarget.above(2)).getDestroySpeed(level, currentTarget.above(2));
                if (hardness < 0) return;
                projectCost += (int)(hardness * Config.miningPenaltyMultiplier * 10) + (Config.miningBasePenalty * 10);
                actionNode = new SiegeNode(currentTarget.above(2), SiegeNode.SiegeAction.MINE);
            } else if (needsMiningHead) {
                float hardness = level.getBlockState(currentTarget.above()).getDestroySpeed(level, currentTarget.above());
                if (hardness < 0) return;
                projectCost += (int)(hardness * Config.miningPenaltyMultiplier * 10) + (Config.miningBasePenalty * 10);
                actionNode = new SiegeNode(currentTarget.above(), SiegeNode.SiegeAction.MINE);
            } else if (needsMiningFoot) {
                float hardness = level.getBlockState(currentTarget).getDestroySpeed(level, currentTarget);
                if (hardness < 0) return;
                projectCost += (int)(hardness * Config.miningPenaltyMultiplier * 10) + (Config.miningBasePenalty * 10);
                actionNode = new SiegeNode(currentTarget, SiegeNode.SiegeAction.MINE);
            } else if (dy != 0 && i % 10 == 0) {
                projectCost += Config.buildingBasePenalty * 10;
                actionNode = new SiegeNode(currentTarget, SiegeNode.SiegeAction.BUILD_LANDING);
            } else if (needsSupport) {
                SiegeNode.SiegeAction buildAction;
                if (dx == 0 && dz == 0 && dy > 0) {
                    buildAction = SiegeNode.SiegeAction.BUILD_PILLAR;
                } else if (dy != 0) {
                    buildAction = SiegeNode.SiegeAction.BUILD_STAIR;
                } else {
                    buildAction = SiegeNode.SiegeAction.BUILD_BRIDGE;
                }
                projectCost += Config.buildingBasePenalty * 10;
                actionNode = new SiegeNode(currentTarget.below(), buildAction);
            } else {
                projectCost += 10; // Scaled to match the 10x orthogonal cost
                actionNode = new SiegeNode(currentTarget, SiegeNode.SiegeAction.WALK);
            }

            int evaluatedProjectCost = projectCost * 2;

            // Prioritize vertical shafts and stairs by discounting their cost
            if (dy != 0) {
                evaluatedProjectCost = (int) (evaluatedProjectCost * 0.75f);
            }

            int totalCost = anchorCost + evaluatedProjectCost;
            int currentBestCost = nextCostMap.getOrDefault(ratPos, Integer.MAX_VALUE);

            // Strict cost check. No safety bypasses. Math dictates planning.
            if (totalCost < currentBestCost) {

                tempCostMap.put(ratPos, totalCost);
                tempInstructionMap.put(ratPos, actionNode);

                tempCostMap.put(ratPos.below(), totalCost);
                tempInstructionMap.put(ratPos.below(), actionNode);

                if (actionNode.action() != SiegeNode.SiegeAction.WALK) {
                    // Only lock blocks if they aren't already walkable terrain.
                    if (!isWalkableTerrain(level, ratPos)) {
                        currentProjectInstructions.put(ratPos, actionNode);
                    }
                    if (!isWalkableTerrain(level, ratPos.below())) {
                        currentProjectInstructions.put(ratPos.below(), actionNode);
                    }
                }

                if (isWalkableTerrain(level, ratPos)) {
                    nextCostMap.putAll(tempCostMap);
                    nextInstructionMap.putAll(tempInstructionMap);

                    if (!currentProjectInstructions.isEmpty()) {
                        candidateProjects.add(new SiegeProject(currentProjectInstructions)); // Save to candidates
                    }

                    calcQueue.add(new QueueNode(ratPos, totalCost));
                    plannedProjects.add(anchorPos);
                    return;
                }
            } else {
                return; // Walk path is cheaper, discard macro project pathing!
            }

            currentTarget = ratPos;
        }
    }

    private boolean evaluateSimpleStep(ServerLevel level, BlockPos from, BlockPos to, int dy) {
        if (dy > 1) {
            for (int y = to.getY() + 1; y <= from.getY() + 1; y++) {
                BlockPos shaft = new BlockPos(to.getX(), y, to.getZ());
                if (level.getBlockState(shaft).blocksMotion()) return false;
            }
            return true;
        }

        BlockState fromFoot = level.getBlockState(from);
        BlockState fromHead = level.getBlockState(from.above());
        return (!fromFoot.blocksMotion() || isWalkableScaffold(fromFoot)) && (!fromHead.blocksMotion() || isWalkableScaffold(fromHead));
    }

    private boolean isWalkableTerrain(ServerLevel level, BlockPos pos) {
        BlockState foot = level.getBlockState(pos);
        BlockState head = level.getBlockState(pos.above());
        BlockState support = level.getBlockState(pos.below());

        boolean hasSupport = support.blocksMotion() || isWalkableScaffold(support);
        boolean fits = (!foot.blocksMotion() || isWalkableScaffold(foot)) && (!head.blocksMotion() || isWalkableScaffold(head));

        return hasSupport && fits;
    }

    private boolean isOutOfBounds(ServerLevel level, BlockPos pos) {
        if (level != null && level.isOutsideBuildHeight(pos)) return true;

        ChunkPos chunk = new ChunkPos(pos);
        return !territoryChunks.isEmpty() && !territoryChunks.contains(chunk);
    }

    public SiegeNode getDynamicWildernessNode(ServerLevel level, BlockPos ratPos) {
        if (instructionMap.isEmpty()) return new SiegeNode(targetPos, SiegeNode.SiegeAction.WALK);

        ChunkPos ratChunk = new ChunkPos(ratPos);
        BlockPos targetHeadingBlock = null;

        if (mappedChunks.contains(ratChunk)) {
            targetHeadingBlock = findClosestBlockInChunk(ratPos, ratChunk);
        } else {
            ChunkPos nextStepChunk = findMacroNavMeshRoute(ratChunk);
            if (nextStepChunk != null) {
                targetHeadingBlock = new BlockPos((nextStepChunk.x << 4) + 8, ratPos.getY(), (nextStepChunk.z << 4) + 8);
            }
        }

        if (targetHeadingBlock == null) {
            targetHeadingBlock = this.targetPos;
        }

        int dx = Integer.compare(targetHeadingBlock.getX(), ratPos.getX());
        int dy = Integer.compare(targetHeadingBlock.getY(), ratPos.getY());
        int dz = Integer.compare(targetHeadingBlock.getZ(), ratPos.getZ());

        if (dx == 0 && dy == 0 && dz == 0) return new SiegeNode(ratPos, SiegeNode.SiegeAction.WALK);

        BlockPos nextPos = ratPos.offset(dx, dy, dz);
        BlockState foot = level.getBlockState(nextPos);
        BlockState head = level.getBlockState(nextPos.above());
        BlockState ceiling = level.getBlockState(nextPos.above(2));
        BlockState support = level.getBlockState(nextPos.below());

        // Top-down priority for wilderness mining too (Ceiling -> Head -> Foot)
        if (dy != 0 && ceiling.blocksMotion() && !isWalkableScaffold(ceiling)) return new SiegeNode(nextPos.above(2), SiegeNode.SiegeAction.MINE);
        if (head.blocksMotion() && !isWalkableScaffold(head)) return new SiegeNode(nextPos.above(), SiegeNode.SiegeAction.MINE);
        if (foot.blocksMotion() && !isWalkableScaffold(foot)) return new SiegeNode(nextPos, SiegeNode.SiegeAction.MINE);

        if (!support.blocksMotion() && !isWalkableScaffold(support)) {
            SiegeNode.SiegeAction action = (dx == 0 && dz == 0 && dy > 0) ? SiegeNode.SiegeAction.BUILD_PILLAR :
                    ((dy != 0) ? SiegeNode.SiegeAction.BUILD_STAIR : SiegeNode.SiegeAction.BUILD_BRIDGE);
            return new SiegeNode(nextPos.below(), action);
        }

        return new SiegeNode(nextPos, SiegeNode.SiegeAction.WALK);
    }

    private BlockPos findClosestBlockInChunk(BlockPos ratPos, ChunkPos chunk) {
        List<BlockPos> blocks = chunkToBlocksIndex.get(chunk);
        if (blocks == null || blocks.isEmpty()) return null;

        BlockPos closest = null;
        double minDist = Double.MAX_VALUE;
        for (BlockPos pos : blocks) {
            double d = ratPos.distSqr(pos);
            if (d < minDist) {
                minDist = d;
                closest = pos;
            }
        }
        return closest;
    }

    private ChunkPos findMacroNavMeshRoute(ChunkPos startChunk) {
        if (mappedChunks.isEmpty()) return null;

        Queue<ChunkPos> queue = new LinkedList<>();
        Map<ChunkPos, ChunkPos> cameFrom = new HashMap<>();
        Set<ChunkPos> visited = new HashSet<>();

        queue.add(startChunk);
        visited.add(startChunk);

        ChunkPos targetChunk = null;

        while (!queue.isEmpty()) {
            ChunkPos current = queue.poll();

            if (mappedChunks.contains(current)) {
                targetChunk = current;
                break;
            }

            int[][] neighbors = {{1,0}, {-1,0}, {0,1}, {0,-1}};
            for (int[] offset : neighbors) {
                ChunkPos next = new ChunkPos(current.x + offset[0], current.z + offset[1]);

                if (territoryChunks.contains(next) && !visited.contains(next)) {
                    visited.add(next);
                    cameFrom.put(next, current);
                    queue.add(next);
                }
            }
        }

        if (targetChunk != null) {
            ChunkPos curr = targetChunk;
            while (cameFrom.get(curr) != null && !cameFrom.get(curr).equals(startChunk)) {
                curr = cameFrom.get(curr);
            }
            return curr;
        }

        return null;
    }

    private boolean isWalkableScaffold(BlockState state) {
        return state.getBlock() instanceof net.minecraft.world.level.block.StairBlock ||
                state.getBlock() instanceof net.minecraft.world.level.block.SlabBlock ||
                state.is(Blocks.COBBLESTONE);
    }

    public SiegeNode getNextSiegeNode(ServerLevel level, BlockPos ratPos) {
        SiegeNode node = instructionMap.get(ratPos);
        if (node == null) return null;

        if (node.action() == SiegeNode.SiegeAction.BUILD_STAIR || node.action() == SiegeNode.SiegeAction.BUILD_BRIDGE || node.action() == SiegeNode.SiegeAction.BUILD_PILLAR || node.action() == SiegeNode.SiegeAction.BUILD_LANDING) {
            BlockPos placePos = node.pos();
            BlockState state = level.getBlockState(placePos);
            if (state.blocksMotion() || isWalkableScaffold(state)) {
                // Direct the rat to walk to the block ABOVE the newly placed floor
                return new SiegeNode(placePos.above(), SiegeNode.SiegeAction.WALK);
            }
        } else if (node.action() == SiegeNode.SiegeAction.MINE) {
            BlockPos minePos = node.pos();
            BlockState state = level.getBlockState(minePos);
            if (!state.blocksMotion() || isWalkableScaffold(state)) {
                return new SiegeNode(ratPos, SiegeNode.SiegeAction.WALK);
            }
        }

        return node;
    }

    public Map<BlockPos, SiegeNode> getInstructionMap() {
        return this.instructionMap;
    }

    public BlockPos getTargetPos() {
        return this.targetPos;
    }

    public void forceRecalculation() {
        this.isDirty = true;
        this.lastCalculationStart = 0;
    }

    public Set<ChunkPos> getMappedChunks() {
        return this.mappedChunks;
    }
}