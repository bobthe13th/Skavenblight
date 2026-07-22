package org.ratden.skavenblight.ai.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.ratden.skavenblight.Config;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Stateless utility class handling all Minecraft-specific block lookups,
 * movement penalties, and determining required SiegeActions.
 */
public class TerrainEvaluator {

    // --- Pathing Weights ---
    private static final int ORTHOGONAL_COST = 10;
    private static final int DIAGONAL_COST = 14;
    private static final int LEAP_PENALTY = 10;
    private static final int COST_MULTIPLIER = 10;

    private static final int[][] HORIZONTAL_OFFSETS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {-1, -1}, {1, -1}, {-1, 1}
    };

    /**
     * DTO for returning evaluated adjacent blocks to the Dijkstra queue.
     */
    public record EvaluatedStep(BlockPos pos, int cost, SiegeNode.SiegeAction action) {}

    // =================================================================================
    // ADJACENT MOVEMENT (DIJKSTRA)
    // =================================================================================

    public List<EvaluatedStep> getValidOrthogonalSteps(ServerLevel level, BlockPos current, Set<BlockPos> lockedPositions, BlockPos targetPos) {
        List<EvaluatedStep> validSteps = new ArrayList<>();

        for (int[] offset : HORIZONTAL_OFFSETS) {
            // Constrain vertical bloat. We only check standard step up/down here.
            for (int dy = -1; dy <= 1; dy++) {
                BlockPos neighbor = current.offset(offset[0], dy, offset[1]);

                if (isOutOfBounds(level, neighbor, targetPos) || lockedPositions.contains(neighbor)) continue;

                if (isWalkableTerrain(level, neighbor) && evaluateSimpleStep(level, neighbor, current, dy)) {
                    // Standard walkable step
                    int stepCost = (offset[0] != 0 && offset[1] != 0) ? DIAGONAL_COST : ORTHOGONAL_COST;
                    validSteps.add(new EvaluatedStep(neighbor, stepCost, SiegeNode.SiegeAction.WALK));
                }
                else if (dy == 0) {
                    // We stepped horizontally into thin air. Raycast down to find the ground!
                    BlockPos groundNode = findGroundBelow(level, neighbor, targetPos);

                    if (groundNode != null && !lockedPositions.contains(groundNode)) {
                        // Flat drop cost to prevent the PriorityQueue from deferring ground evaluation
                        int dropCost = ORTHOGONAL_COST + 50;

                        // Since Dijkstra calculates backwards, assigning BUILD_STAIR here
                        // tells a rat on the ground that it must build up to reach the ledge.
                        SiegeNode.SiegeAction action = (offset[0] != 0 && offset[1] != 0) ?
                                SiegeNode.SiegeAction.BUILD_STAIR : SiegeNode.SiegeAction.BUILD_PILLAR;

                        validSteps.add(new EvaluatedStep(groundNode, dropCost, action));
                    }
                }
            }
        }
        return validSteps;
    }

    private boolean evaluateSimpleStep(ServerLevel level, BlockPos from, BlockPos to, int dy) {
        if (dy > 1) {
            // Check headroom when stepping up multiple blocks
            for (int y = to.getY() + 1; y <= from.getY() + 1; y++) {
                if (level.getBlockState(new BlockPos(to.getX(), y, to.getZ())).blocksMotion()) {
                    return false;
                }
            }
            return true;
        }
        return isFitForWalking(level, from);
    }

    // =================================================================================
    // MACRO PROJECT EVALUATION
    // =================================================================================

    public SiegeNode determineMacroAction(ServerLevel level, BlockPos pos, int dy, int dx, int dz) {
        BlockState foot = level.getBlockState(pos);
        BlockState head = level.getBlockState(pos.above());
        BlockState ceiling = level.getBlockState(pos.above(2));
        BlockState support = level.getBlockState(pos.below());

        // 1. MINE Check: Prioritize breaking obstructions first[cite: 10]
        if (dy != 0 && ceiling.blocksMotion() && !isWalkableScaffold(ceiling)) {
            return new SiegeNode(pos.above(2), SiegeNode.SiegeAction.MINE);
        }
        if (head.blocksMotion() && !isWalkableScaffold(head)) {
            return new SiegeNode(pos.above(), SiegeNode.SiegeAction.MINE);
        }
        if (foot.blocksMotion() && !isWalkableScaffold(foot)) {
            return new SiegeNode(pos, SiegeNode.SiegeAction.MINE);
        }

        // 2. BUILD Check: Construct necessary scaffolding[cite: 10]
        if (!support.blocksMotion() && !isWalkableScaffold(support)) {
            SiegeNode.SiegeAction action;
            if (dx == 0 && dz == 0 && dy > 0) {
                // ADDED: Check if there is a wall adjacent to us to place a ladder on
                boolean hasWall = false;
                for (Direction dir : Direction.Plane.HORIZONTAL) {
                    if (level.getBlockState(pos.relative(dir)).isSolidRender(level, pos.relative(dir))) {
                        hasWall = true;
                        break;
                    }
                }
                action = hasWall ? SiegeNode.SiegeAction.BUILD_LADDER : SiegeNode.SiegeAction.BUILD_PILLAR;
            } else if (dy != 0) {
                action = SiegeNode.SiegeAction.BUILD_STAIR;
            } else {
                action = SiegeNode.SiegeAction.BUILD_BRIDGE;
            }
            return new SiegeNode(pos.below(), action);
        }

        return new SiegeNode(pos, SiegeNode.SiegeAction.WALK);
    }

    public int calculateActionCost(ServerLevel level, SiegeNode node) {
        if (node.action() == SiegeNode.SiegeAction.MINE) {
            float hardness = level.getBlockState(node.pos()).getDestroySpeed(level, node.pos());
            if (hardness < 0) return Integer.MAX_VALUE; // Unbreakable (Bedrock)

            return (int)(hardness * Config.miningPenaltyMultiplier * COST_MULTIPLIER) + (Config.miningBasePenalty * COST_MULTIPLIER);
        } else if (node.action() != SiegeNode.SiegeAction.WALK) {
            return Config.buildingBasePenalty * COST_MULTIPLIER;
        }
        return ORTHOGONAL_COST;
    }

    public boolean isActionCompleted(ServerLevel level, SiegeNode node) {
        BlockState state = level.getBlockState(node.pos());
        return switch (node.action()) {
            case MINE -> !state.blocksMotion() || isWalkableScaffold(state);
            case BUILD_STAIR, BUILD_BRIDGE, BUILD_PILLAR, BUILD_LANDING -> state.blocksMotion() || isWalkableScaffold(state);
            default -> false;
        };
    }

    // =================================================================================
    // BLOCK STATE HELPERS
    // =================================================================================

    public boolean isWalkableTerrain(ServerLevel level, BlockPos pos) {
        BlockState support = level.getBlockState(pos.below());
        return (support.blocksMotion() || isWalkableScaffold(support)) && isFitForWalking(level, pos);
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

    public boolean isOutOfBounds(ServerLevel level, BlockPos pos, BlockPos targetPos) {
        if (level == null || level.isOutsideBuildHeight(pos)) return true;

        // Prevent chunk-loading deadlocks
        if (!level.isLoaded(pos)) return true;

        // Vertical Boundary Constraints relative to the Nexus target
        if (pos.getY() > targetPos.getY() + 15) return true; // Don't build to space
        if (pos.getY() < targetPos.getY() - 30) return true; // Don't map the deep underground

        return false;
    }

    /**
     * Shoots a ray straight down from an air block to find the nearest valid landing surface.
     */
    private BlockPos findGroundBelow(ServerLevel level, BlockPos airPos, BlockPos targetPos) {
        // Cast down up to 128 blocks to find a landing spot
        for (int i = 1; i < 128; i++) {
            BlockPos checkPos = airPos.below(i);

            if (isOutOfBounds(level, checkPos, targetPos)) return null;

            if (isWalkableTerrain(level, checkPos)) {
                return checkPos; // We found the dirt/floor!
            }

            // If we hit a solid block that ISN'T walkable (like landing on a fence post
            // or a 1x1 glass pane), we abort the drop so rats don't get stuck.
            if (level.getBlockState(checkPos).blocksMotion()) {
                return null;
            }
        }
        return null;
    }
}