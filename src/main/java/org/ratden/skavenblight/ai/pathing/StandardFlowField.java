package org.ratden.skavenblight.ai.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

public class StandardFlowField {

    private Map<BlockPos, SiegeNode> instructionMap = new HashMap<>();
    private final Map<BlockPos, Integer> nextCostMap = new HashMap<>();
    private final Map<BlockPos, SiegeNode> nextInstructionMap = new HashMap<>();

    private final List<BlockPos> plannedProjects = new ArrayList<>();

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

    private static final int MAX_PROJECT_LENGTH = 16;
    private static final int PROJECT_BASE_COST = 80;
    private static final int TERRAFORM_COST_PER_BLOCK = 2;

    public StandardFlowField(BlockPos targetPos, Set<ChunkPos> territoryChunks) {
        this.targetPos = targetPos;
        this.territoryChunks = territoryChunks;
    }

    public void calculateMapIfNeeded(ServerLevel level) {
        long currentTime = level.getGameTime();

        if (!isCalculating && (this.instructionMap.isEmpty() || currentTime - lastCalculationStart >= 80)) {
            isCalculating = true;
            lastCalculationStart = currentTime;

            nextCostMap.clear();
            nextInstructionMap.clear();
            plannedProjects.clear();

            calcQueue = new PriorityQueue<>(Comparator.comparingInt(pos -> nextCostMap.getOrDefault(pos, Integer.MAX_VALUE)));

            calcQueue.add(targetPos);
            nextCostMap.put(targetPos, 0);
            nextInstructionMap.put(targetPos, new SiegeNode(targetPos, SiegeNode.SiegeAction.WALK));
        }

        if (isCalculating) {
            int nodesProcessed = 0;

            while (!calcQueue.isEmpty() && nodesProcessed < 2000) {
                BlockPos current = calcQueue.poll();
                nodesProcessed++;

                int currentCost = nextCostMap.get(current);

                for (int[] offset : HORIZONTAL_OFFSETS) {
                    for (int dy = -1; dy <= 4; dy++) {
                        BlockPos neighbor = current.offset(offset[0], dy, offset[1]);

                        if (isOutOfBounds(level, neighbor)) continue;

                        if (isWalkableTerrain(level, neighbor)) {
                            if (evaluateSimpleStep(level, neighbor, current, dy)) {
                                int stepCost = (offset[0] != 0 && offset[1] != 0) ? 2 : 1;
                                int totalCost = currentCost + stepCost;

                                if (totalCost < nextCostMap.getOrDefault(neighbor, Integer.MAX_VALUE)) {
                                    nextCostMap.put(neighbor, totalCost);
                                    nextInstructionMap.put(neighbor, new SiegeNode(current, SiegeNode.SiegeAction.WALK));
                                    calcQueue.add(neighbor);
                                }
                            }
                        }
                    }
                }

                if (isWalkableTerrain(level, current)) {
                    for (int[] dir : CARDINAL_OFFSETS) {
                        // FIX: Only evaluate bridges (dy=0) and stairs UP (dy=1).
                        // Mobs can fall/step down naturally.
                        for (int dy = 0; dy <= 1; dy++) {
                            evaluateMacroProject(level, current, currentCost, dir[0], dy, dir[1]);
                        }
                    }
                }
            }

            if (calcQueue.isEmpty()) {
                isCalculating = false;
                this.instructionMap = new HashMap<>(nextInstructionMap);
            }
        }
    }

    private void evaluateMacroProject(ServerLevel level, BlockPos anchorPos, int anchorCost, int dx, int dy, int dz) {
        for (BlockPos existing : plannedProjects) {
            if (existing.distSqr(anchorPos) < 100) {
                return;
            }
        }

        int projectCost = PROJECT_BASE_COST;
        BlockPos currentTarget = anchorPos;

        Map<BlockPos, Integer> tempCostMap = new HashMap<>();
        Map<BlockPos, SiegeNode> tempInstructionMap = new HashMap<>();

        for (int i = 1; i <= MAX_PROJECT_LENGTH; i++) {
            BlockPos ratPos = currentTarget.offset(-dx, -dy, -dz);

            if (isOutOfBounds(level, ratPos)) break;

            BlockState targetFoot = level.getBlockState(currentTarget);
            BlockState targetHead = level.getBlockState(currentTarget.above());
            // --- NEW: Add the ceiling block to the radar ---
            BlockState targetCeiling = level.getBlockState(currentTarget.above(2));
            BlockState targetSupport = level.getBlockState(currentTarget.below());

            boolean needsMiningFoot = targetFoot.blocksMotion() && !isWalkableScaffold(targetFoot);
            boolean needsMiningHead = targetHead.blocksMotion() && !isWalkableScaffold(targetHead);
            // --- NEW: Require 3-block clearance ONLY if we are climbing/descending (dy != 0) ---
            boolean needsMiningCeiling = (dy != 0) && targetCeiling.blocksMotion() && !isWalkableScaffold(targetCeiling);
            boolean needsSupport = !targetSupport.blocksMotion() && !isWalkableScaffold(targetSupport);

            SiegeNode actionNode;

            if (needsMiningFoot) {
                float hardness = level.getBlockState(currentTarget).getDestroySpeed(level, currentTarget);
                if (hardness < 0) return;
                projectCost += TERRAFORM_COST_PER_BLOCK;
                actionNode = new SiegeNode(currentTarget, SiegeNode.SiegeAction.MINE);
            } else if (needsMiningHead) {
                float hardness = level.getBlockState(currentTarget.above()).getDestroySpeed(level, currentTarget.above());
                if (hardness < 0) return;
                projectCost += TERRAFORM_COST_PER_BLOCK;
                actionNode = new SiegeNode(currentTarget.above(), SiegeNode.SiegeAction.MINE);
                // --- NEW: Map the MINE instruction for the ceiling block if needed ---
            } else if (needsMiningCeiling) {
                float hardness = level.getBlockState(currentTarget.above(2)).getDestroySpeed(level, currentTarget.above(2));
                if (hardness < 0) return;
                projectCost += TERRAFORM_COST_PER_BLOCK;
                actionNode = new SiegeNode(currentTarget.above(2), SiegeNode.SiegeAction.MINE);
            } else if (needsSupport) {
                SiegeNode.SiegeAction buildAction = (dy != 0) ? SiegeNode.SiegeAction.BUILD_STAIR : SiegeNode.SiegeAction.BUILD_BRIDGE;
                projectCost += TERRAFORM_COST_PER_BLOCK;
                actionNode = new SiegeNode(currentTarget.below(), buildAction);
            } else {
                projectCost += 1;
                actionNode = new SiegeNode(currentTarget, SiegeNode.SiegeAction.WALK);
            }

            int totalCost = anchorCost + projectCost;

            if (totalCost < nextCostMap.getOrDefault(ratPos, Integer.MAX_VALUE)) {
                tempCostMap.put(ratPos, totalCost);
                tempInstructionMap.put(ratPos, actionNode);

                if (isWalkableTerrain(level, ratPos)) {
                    nextCostMap.putAll(tempCostMap);
                    nextInstructionMap.putAll(tempInstructionMap);
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
        if (Math.abs(pos.getY() - targetPos.getY()) > 48 || level.isOutsideBuildHeight(pos)) return true;
        ChunkPos chunk = new ChunkPos(pos);
        return !territoryChunks.isEmpty() && !territoryChunks.contains(chunk);
    }

    public SiegeNode getDynamicWildernessNode(ServerLevel level, BlockPos ratPos) {
        if (instructionMap.isEmpty()) return new SiegeNode(targetPos, SiegeNode.SiegeAction.WALK);

        BlockPos closest = targetPos;
        double minDistance = Double.MAX_VALUE;
        for (BlockPos mappedPos : instructionMap.keySet()) {
            double dist = ratPos.distSqr(mappedPos);
            if (dist < minDistance) {
                minDistance = dist;
                closest = mappedPos;
            }
        }

        int dx = Integer.compare(closest.getX(), ratPos.getX());
        int dy = Integer.compare(closest.getY(), ratPos.getY());
        int dz = Integer.compare(closest.getZ(), ratPos.getZ());

        if (dx == 0 && dy == 0 && dz == 0) return new SiegeNode(ratPos, SiegeNode.SiegeAction.WALK);

        BlockPos nextPos = ratPos.offset(dx, dy, dz);

        BlockState foot = level.getBlockState(nextPos);
        BlockState head = level.getBlockState(nextPos.above());
        // --- NEW: Add dynamic ceiling check ---
        BlockState ceiling = level.getBlockState(nextPos.above(2));
        BlockState support = level.getBlockState(nextPos.below());

        if (foot.blocksMotion() && !isWalkableScaffold(foot)) return new SiegeNode(nextPos, SiegeNode.SiegeAction.MINE);
        if (head.blocksMotion() && !isWalkableScaffold(head)) return new SiegeNode(nextPos.above(), SiegeNode.SiegeAction.MINE);
        // --- NEW: Catch the ceiling dynamically if moving vertically ---
        if (dy != 0 && ceiling.blocksMotion() && !isWalkableScaffold(ceiling)) return new SiegeNode(nextPos.above(2), SiegeNode.SiegeAction.MINE);

        if (!support.blocksMotion() && !isWalkableScaffold(support)) {
            return new SiegeNode(nextPos.below(), (dy != 0) ? SiegeNode.SiegeAction.BUILD_STAIR : SiegeNode.SiegeAction.BUILD_BRIDGE);
        }

        return new SiegeNode(nextPos, SiegeNode.SiegeAction.WALK);
    }

    private boolean isWalkableScaffold(BlockState state) {
        return state.is(Blocks.COBBLESTONE) || state.is(Blocks.COBBLESTONE_STAIRS);
    }

    public SiegeNode getNextSiegeNode(ServerLevel level, BlockPos ratPos) {
        SiegeNode node = instructionMap.get(ratPos);
        if (node == null) return null;

        if (node.action() == SiegeNode.SiegeAction.BUILD_STAIR || node.action() == SiegeNode.SiegeAction.BUILD_BRIDGE) {
            BlockPos placePos = node.pos();
            BlockState state = level.getBlockState(placePos);
            if (state.blocksMotion() || isWalkableScaffold(state)) {
                return new SiegeNode(placePos, SiegeNode.SiegeAction.WALK);
            }
        } else if (node.action() == SiegeNode.SiegeAction.MINE) {
            BlockPos minePos = node.pos();
            BlockState state = level.getBlockState(minePos);
            if (!state.blocksMotion() || isWalkableScaffold(state)) {
                // FIX: Instead of trying to path into the empty sky, tell the rat
                // to stand still for 1 tick while the map forces a recalculation.
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
        this.lastCalculationStart = 0; // Triggers an instant map refresh on the very next tick
    }
}