package org.ratden.skavenblight.ai.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.ratden.skavenblight.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class TerrainEvaluator {

    private static final int ORTHOGONAL_COST = 10;
    private static final int DIAGONAL_COST = 14;
    private static final int COST_MULTIPLIER = 10;

    private static final int[][] HORIZONTAL_OFFSETS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {-1, -1}, {1, -1}, {-1, 1}
    };

    public record EvaluatedStep(BlockPos pos, int cost, SiegeNode.SiegeAction action) {}

    // =================================================================================
    // ADJACENT MOVEMENT (DIJKSTRA)
    // =================================================================================

    public List<EvaluatedStep> getValidOrthogonalSteps(ServerLevel level, BlockPos current, Set<BlockPos> lockedPositions, FlowFieldState state) {
        List<EvaluatedStep> validSteps = new ArrayList<>();

        for (int[] offset : HORIZONTAL_OFFSETS) {
            for (int dy = -1; dy <= 1; dy++) {
                BlockPos neighbor = current.offset(offset[0], dy, offset[1]);

                if (isOutOfBounds(level, neighbor, state) || lockedPositions.contains(neighbor)) continue;

                BlockState neighborState = level.getBlockState(neighbor);

                if (isWalkableTerrain(level, neighbor)) {

                    if (offset[0] != 0 && offset[1] != 0) {
                        BlockPos corner1 = current.offset(offset[0], dy, 0);
                        BlockPos corner2 = current.offset(0, dy, offset[1]);

                        if (level.getBlockState(corner1).blocksMotion() || level.getBlockState(corner2).blocksMotion()) {
                            continue;
                        }
                    }

                    int stepCost = (offset[0] != 0 && offset[1] != 0) ? DIAGONAL_COST : ORTHOGONAL_COST;
                    validSteps.add(new EvaluatedStep(neighbor, stepCost, SiegeNode.SiegeAction.WALK));
                }
                else if (neighborState.blocksMotion() && !isWalkableScaffold(neighborState)) {
                    SiegeNode tempMineNode = new SiegeNode(neighbor, SiegeNode.SiegeAction.MINE);
                    int baseMineCost = calculateActionCost(level, tempMineNode);

                    if (baseMineCost != Integer.MAX_VALUE) {
                        int stepCost = baseMineCost + ((offset[0] != 0 && offset[1] != 0) ? DIAGONAL_COST : ORTHOGONAL_COST);
                        validSteps.add(new EvaluatedStep(neighbor, stepCost, SiegeNode.SiegeAction.MINE));
                    }
                }
                else if (dy == 0) {
                    if (!neighborState.blocksMotion()) {
                        BlockPos groundNode = findGroundBelow(level, neighbor, state);

                        if (groundNode != null && !lockedPositions.contains(groundNode)) {

                            int dropDistance = current.getY() - groundNode.getY();
                            int dropCost = ORTHOGONAL_COST + (dropDistance * Config.buildingBasePenalty * COST_MULTIPLIER);

                            SiegeNode.SiegeAction action = (offset[0] != 0 && offset[1] != 0) ?
                                    SiegeNode.SiegeAction.BUILD_STAIR : SiegeNode.SiegeAction.BUILD_PILLAR;

                            validSteps.add(new EvaluatedStep(groundNode, dropCost, action));
                        }
                    }
                }
            }
        }
        return validSteps;
    }

    // =================================================================================
    // MACRO PROJECT EVALUATION
    // =================================================================================

    public SiegeNode.SiegeAction determineMacroAction(ServerLevel level, BlockPos pos, int dy, int dx, int dz, BlockPos targetPos) {
        BlockState foot = level.getBlockState(pos);
        BlockState head = level.getBlockState(pos.above());
        BlockState ceiling = level.getBlockState(pos.above(2));
        BlockState support = level.getBlockState(pos.below());

        if (dy != 0 && ceiling.blocksMotion() && !isWalkableScaffold(ceiling)) {
            return SiegeNode.SiegeAction.MINE;
        }
        if (head.blocksMotion() && !isWalkableScaffold(head)) {
            return SiegeNode.SiegeAction.MINE;
        }
        if (foot.blocksMotion() && !isWalkableScaffold(foot)) {
            return SiegeNode.SiegeAction.MINE;
        }

        if (!support.blocksMotion() && !isWalkableScaffold(support)) {
            // Vertical Shaft / Column
            if (dx == 0 && dz == 0) {
                boolean hasWall = false;
                for (Direction dir : Direction.Plane.HORIZONTAL) {
                    BlockPos adj = pos.relative(dir);
                    if (level.getBlockState(adj).isSolidRender(level, adj)) {
                        hasWall = true;
                        break;
                    }
                }
                if (hasWall) {
                    return SiegeNode.SiegeAction.BUILD_LADDER;
                } else if (targetPos != null && Math.abs(targetPos.getY() - pos.getY()) > 3) {
                    return SiegeNode.SiegeAction.BUILD_SPIRAL;
                } else {
                    return SiegeNode.SiegeAction.BUILD_PILLAR;
                }
            }
            // Diagonal Stairs
            else if (dy != 0) {
                return SiegeNode.SiegeAction.BUILD_STAIR;
            }
            // Horizontal Bridge
            else {
                return SiegeNode.SiegeAction.BUILD_BRIDGE;
            }
        }

        return SiegeNode.SiegeAction.WALK;
    }

    /**
     * Overload to support legacy/direct calls using a SiegeNode.
     */
    public int calculateActionCost(ServerLevel level, SiegeNode node) {
        if (node == null) return ORTHOGONAL_COST;
        return calculateActionCostForAction(level, node.pos(), node.action());
    }

    /**
     * Calculates building and mining penalties based on position and action type.
     */
    public int calculateActionCostForAction(ServerLevel level, BlockPos pos, SiegeNode.SiegeAction action) {
        if (action == SiegeNode.SiegeAction.MINE) {
            float hardness = level.getBlockState(pos).getDestroySpeed(level, pos);
            if (hardness < 0) return Integer.MAX_VALUE;
            return (int)(hardness * Config.miningPenaltyMultiplier * COST_MULTIPLIER) + (Config.miningBasePenalty * COST_MULTIPLIER);
        } else if (action != SiegeNode.SiegeAction.WALK) {
            return Config.buildingBasePenalty * COST_MULTIPLIER;
        }
        return ORTHOGONAL_COST;
    }

    public boolean isActionCompleted(ServerLevel level, SiegeNode node) {
        BlockState state = level.getBlockState(node.pos());
        return switch (node.action()) {
            case MINE -> !state.blocksMotion() || isWalkableScaffold(state);
            case BUILD_STAIR, BUILD_BRIDGE, BUILD_PILLAR, BUILD_LANDING, BUILD_LADDER, BUILD_SPIRAL -> state.blocksMotion() || isWalkableScaffold(state);
            default -> false;
        };
    }

    // =================================================================================
    // BLOCK STATE HELPERS
    // =================================================================================

    public boolean isWalkableTerrain(ServerLevel level, BlockPos pos) {
        BlockState foot = level.getBlockState(pos);

        boolean hasSupport = isWalkableScaffold(foot);
        if (!hasSupport) {
            BlockState support = level.getBlockState(pos.below());
            hasSupport = support.blocksMotion() || isWalkableScaffold(support);
        }

        return hasSupport && isFitForWalking(level, pos);
    }

    private boolean isFitForWalking(ServerLevel level, BlockPos pos) {
        BlockState foot = level.getBlockState(pos);
        BlockState head = level.getBlockState(pos.above());
        return (!foot.blocksMotion() || isWalkableScaffold(foot)) && (!head.blocksMotion() || isWalkableScaffold(head));
    }

    private boolean isWalkableScaffold(BlockState state) {
        return state.getBlock() instanceof net.minecraft.world.level.block.StairBlock ||
                state.getBlock() instanceof net.minecraft.world.level.block.SlabBlock ||
                state.getBlock() instanceof net.minecraft.world.level.block.LadderBlock ||
                state.is(Blocks.COBBLESTONE);
    }

    public boolean isOutOfBounds(ServerLevel level, BlockPos pos, FlowFieldState state) {
        if (level == null || level.isOutsideBuildHeight(pos)) return true;
        if (!level.isLoaded(pos)) return true;

        // FIXED: Removed the hardcoded -30 Y restriction relative to target
        if (state.isOutOfBounds(pos)) return true;

        return false;
    }

    private BlockPos findGroundBelow(ServerLevel level, BlockPos airPos, FlowFieldState state) {
        // Limit direct drop searching to short 4-block drops
        for (int i = 1; i <= 4; i++) {
            BlockPos checkPos = airPos.below(i);
            if (isOutOfBounds(level, checkPos, state)) return null;
            if (isWalkableTerrain(level, checkPos)) return checkPos;
            if (level.getBlockState(checkPos).blocksMotion()) return null;
        }
        return null;
    }
}