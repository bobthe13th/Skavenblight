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
    private final Set<BlockPos> lockedPositions = new HashSet<>();

    private final BlockPos targetPos;
    private final Set<ChunkPos> territoryChunks;

    private Queue<BlockPos> calcQueue;
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
    private long lastTickRealTime = System.currentTimeMillis();
    private float smoothedMSPT = 50.0f;
    private int currentRefreshRate = 80;
    private int currentNodesPerTick = 2000;
    private long lastThrottleUpdateTime = 0;

    private boolean isDirty = true;

    private class SiegeProject {
        // Now maps the exact block the rat stands in to the instruction it needs
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
        if (this.isDirty) return;
        if (!isOutOfBounds(null, pos)) {
            this.isDirty = true;
        }
    }

    public void calculateMapIfNeeded(ServerLevel level) {
        long currentTime = level.getGameTime();

        // Throttle updates: Check the server's ACTUAL native MSPT every 10 seconds
        if (currentTime - lastThrottleUpdateTime >= 200) {
            this.lastThrottleUpdateTime = currentTime;

            // Pull the true average tick time directly from the Minecraft Server
            float realMSPT = level.getServer().getAverageTickTimeNanos() / 1000000.0f;

            int targetRefreshRate = 80;
            int targetNodesPerTick = 2000;

            if (realMSPT >= 50.0f) {
                targetRefreshRate = 300;
                targetNodesPerTick = 500;
            } else if (realMSPT >= 40.0f) {
                targetRefreshRate = 160;
                targetNodesPerTick = 1000;
            }

            if (targetRefreshRate != this.currentRefreshRate || targetNodesPerTick != this.currentNodesPerTick) {
                this.currentRefreshRate = targetRefreshRate;
                this.currentNodesPerTick = targetNodesPerTick;

                LOGGER.info("[Skavenblight AI] Lag Mitigation adjusted! MSPT: {}ms | New Refresh Rate: {} ticks | Max Nodes/Tick: {}",
                        String.format("%.2f", realMSPT), this.currentRefreshRate, this.currentNodesPerTick);
            }
        }

        if (!isCalculating && (this.instructionMap.isEmpty() || (this.isDirty && currentTime - lastCalculationStart >= this.currentRefreshRate))) {
            isCalculating = true;
            lastCalculationStart = currentTime;

            nextCostMap.clear();
            nextInstructionMap.clear();
            plannedProjects.clear();

            calcQueue = new PriorityQueue<>(Comparator.comparingInt(pos -> nextCostMap.getOrDefault(pos, Integer.MAX_VALUE)));

            calcQueue.add(targetPos);
            nextCostMap.put(targetPos, 0);
            nextInstructionMap.put(targetPos, new SiegeNode(targetPos, SiegeNode.SiegeAction.WALK));

            lockedPositions.clear();

            activeProjects.removeIf(project -> !project.isStarted(level) || project.isCompleted(level));

            for (SiegeProject project : activeProjects) {
                for (Map.Entry<BlockPos, SiegeNode> entry : project.getRemainingInstructions(level).entrySet()) {
                    BlockPos pos = entry.getKey(); // Inject perfectly back into ratPos
                    lockedPositions.add(pos);
                    nextInstructionMap.put(pos, entry.getValue());
                    nextCostMap.put(pos, 10);
                    calcQueue.add(pos);
                }
            }
        }

        if (isCalculating) {
            int nodesProcessed = 0;

            while (!calcQueue.isEmpty() && nodesProcessed < this.currentNodesPerTick) {

                if (nextCostMap.size() >= Config.maxFlowFieldNodes) {
                    calcQueue.clear();
                    break;
                }

                BlockPos current = calcQueue.poll();
                nodesProcessed++;

                int currentCost = nextCostMap.get(current);

                for (int[] offset : HORIZONTAL_OFFSETS) {
                    for (int dy = -1; dy <= 4; dy++) {
                        BlockPos neighbor = current.offset(offset[0], dy, offset[1]);

                        if (isOutOfBounds(level, neighbor)) continue;

                        if (isWalkableTerrain(level, neighbor)) {
                            if (evaluateSimpleStep(level, neighbor, current, dy)) {

                                boolean isGap = !level.getBlockState(current.offset(offset[0], 0, offset[1]).below()).blocksMotion();
                                boolean targetIsSolid = level.getBlockState(neighbor.below()).blocksMotion();

                                int stepCost = (offset[0] != 0 && offset[1] != 0) ? 2 : 1;
                                SiegeNode.SiegeAction stepAction = SiegeNode.SiegeAction.WALK;

                                if (isGap && targetIsSolid && dy == 0) {
                                    stepAction = SiegeNode.SiegeAction.LEAP;
                                    stepCost += 1;
                                }

                                int totalCost = currentCost + stepCost;

                                if (totalCost < nextCostMap.getOrDefault(neighbor, Integer.MAX_VALUE)) {
                                    if (!lockedPositions.contains(neighbor)) {
                                        nextCostMap.put(neighbor, totalCost);
                                        nextInstructionMap.put(neighbor, new SiegeNode(current, stepAction));
                                        calcQueue.add(neighbor);
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

            if (calcQueue.isEmpty()) {
                isCalculating = false;
                this.instructionMap = new HashMap<>(nextInstructionMap);

                this.mappedChunks.clear();
                this.chunkToBlocksIndex.clear();
                for (BlockPos pos : this.instructionMap.keySet()) {
                    ChunkPos cp = new ChunkPos(pos);
                    this.mappedChunks.add(cp);
                    this.chunkToBlocksIndex.computeIfAbsent(cp, k -> new ArrayList<>()).add(pos);
                }

                this.isDirty = false;
            }
        }
    }

    private void evaluateMacroProject(ServerLevel level, BlockPos anchorPos, int anchorCost, int dx, int dy, int dz) {
        int projectCost = Config.buildingBasePenalty;
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

            if (needsMiningFoot) {
                float hardness = level.getBlockState(currentTarget).getDestroySpeed(level, currentTarget);
                if (hardness < 0) return;
                projectCost += (int)(hardness * Config.miningPenaltyMultiplier) + Config.miningBasePenalty;
                actionNode = new SiegeNode(currentTarget, SiegeNode.SiegeAction.MINE);
            } else if (needsMiningHead) {
                float hardness = level.getBlockState(currentTarget.above()).getDestroySpeed(level, currentTarget.above());
                if (hardness < 0) return;
                projectCost += (int)(hardness * Config.miningPenaltyMultiplier) + Config.miningBasePenalty;
                actionNode = new SiegeNode(currentTarget.above(), SiegeNode.SiegeAction.MINE);
            } else if (needsMiningCeiling) {
                float hardness = level.getBlockState(currentTarget.above(2)).getDestroySpeed(level, currentTarget.above(2));
                if (hardness < 0) return;
                projectCost += (int)(hardness * Config.miningPenaltyMultiplier) + Config.miningBasePenalty;
                actionNode = new SiegeNode(currentTarget.above(2), SiegeNode.SiegeAction.MINE);
            } else if (dy != 0 && i % 10 == 0) {
                projectCost += Config.buildingBasePenalty;
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
                projectCost += Config.buildingBasePenalty;
                actionNode = new SiegeNode(currentTarget.below(), buildAction);
            } else {
                projectCost += 1;
                actionNode = new SiegeNode(currentTarget, SiegeNode.SiegeAction.WALK);
            }

            int evaluatedProjectCost = projectCost * 2;
            int totalCost = anchorCost + evaluatedProjectCost;

            if (totalCost < nextCostMap.getOrDefault(ratPos, Integer.MAX_VALUE)) {

                // Assign to ratPos AND ratPos.below() so half-block rounding won't confuse the AI
                tempCostMap.put(ratPos, totalCost);
                tempInstructionMap.put(ratPos, actionNode);

                tempCostMap.put(ratPos.below(), totalCost);
                tempInstructionMap.put(ratPos.below(), actionNode);

                if (actionNode.action() != SiegeNode.SiegeAction.WALK) {
                    currentProjectInstructions.put(ratPos, actionNode);
                    currentProjectInstructions.put(ratPos.below(), actionNode);
                }

                if (isWalkableTerrain(level, ratPos)) {
                    nextCostMap.putAll(tempCostMap);
                    nextInstructionMap.putAll(tempInstructionMap);

                    if (!currentProjectInstructions.isEmpty()) {
                        activeProjects.add(new SiegeProject(currentProjectInstructions));
                    }

                    calcQueue.add(ratPos);
                    plannedProjects.add(anchorPos);
                    return;
                }
            } else {
                return;
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
        if (Math.abs(pos.getY() - targetPos.getY()) > 48 || (level != null && level.isOutsideBuildHeight(pos))) return true;
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

        if (foot.blocksMotion() && !isWalkableScaffold(foot)) return new SiegeNode(nextPos, SiegeNode.SiegeAction.MINE);
        if (head.blocksMotion() && !isWalkableScaffold(head)) return new SiegeNode(nextPos.above(), SiegeNode.SiegeAction.MINE);
        if (dy != 0 && ceiling.blocksMotion() && !isWalkableScaffold(ceiling)) return new SiegeNode(nextPos.above(2), SiegeNode.SiegeAction.MINE);

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
        return state.is(Blocks.COBBLESTONE) || state.is(Blocks.COBBLESTONE_STAIRS);
    }

    public SiegeNode getNextSiegeNode(ServerLevel level, BlockPos ratPos) {
        SiegeNode node = instructionMap.get(ratPos);
        if (node == null) return null;

        if (node.action() == SiegeNode.SiegeAction.BUILD_STAIR || node.action() == SiegeNode.SiegeAction.BUILD_BRIDGE || node.action() == SiegeNode.SiegeAction.BUILD_PILLAR || node.action() == SiegeNode.SiegeAction.BUILD_LANDING) {
            BlockPos placePos = node.pos();
            BlockState state = level.getBlockState(placePos);
            if (state.blocksMotion() || isWalkableScaffold(state)) {
                return new SiegeNode(placePos, SiegeNode.SiegeAction.WALK);
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