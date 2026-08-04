package org.ratden.skavenblight.ai.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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

    public List<EvaluatedStep> getValidOrthogonalSteps(TerrainAccess terrain, BlockPos current, Set<BlockPos> lockedPositions, FlowFieldState state) {
        List<EvaluatedStep> validSteps = new ArrayList<>();

        for (int[] offset : HORIZONTAL_OFFSETS) {
            for (int dy = -1; dy <= 1; dy++) {
                BlockPos neighbor = current.offset(offset[0], dy, offset[1]);

                if (isOutOfBounds(terrain, neighbor, state) || lockedPositions.contains(neighbor)) continue;

                BlockState neighborState = terrain.getBlockState(neighbor);

                if (isWalkableTerrain(terrain, neighbor)) {

                    if (offset[0] != 0 && offset[1] != 0) {
                        BlockPos corner1 = current.offset(offset[0], dy, 0);
                        BlockPos corner2 = current.offset(0, dy, offset[1]);

                        if (terrain.getBlockState(corner1).blocksMotion() || terrain.getBlockState(corner2).blocksMotion()) {
                            continue;
                        }
                    }

                    int stepCost = (offset[0] != 0 && offset[1] != 0) ? DIAGONAL_COST : ORTHOGONAL_COST;
                    validSteps.add(new EvaluatedStep(neighbor, stepCost, SiegeNode.SiegeAction.WALK));
                }
                // Only breach obstacles at the current level here. Full terrain columns mean
                // almost every ordinary walkable tile has solid ground diagonally-below its
                // neighbors - allowing dy != 0 here turned every such tile into up to 8
                // "tunnel down into undisturbed dirt" MINE candidates, none of which ever lead
                // anywhere useful. That flooded nextCostMap (the node-budget counter) with
                // wasted entries near the start of the search, starving WALK propagation
                // before it could reach the rest of the territory - see FlowFieldCalculator's
                // MAX_CONSECUTIVE_MINE_DEPTH comment for the matching depth-side half of this
                // problem. Deliberate vertical progress through solid material is already the
                // capped, structured job of evaluateMacroProjects (via hitObstacle) - the core
                // step doesn't need to also explore it ad hoc in every direction from every tile.
                else if (dy == 0 && neighborState.blocksMotion() && !isWalkableScaffold(neighborState)) {
                    SiegeNode tempMineNode = new SiegeNode(neighbor, SiegeNode.SiegeAction.MINE);
                    int baseMineCost = calculateActionCost(terrain, tempMineNode);

                    if (baseMineCost != Integer.MAX_VALUE) {
                        int stepCost = baseMineCost + ((offset[0] != 0 && offset[1] != 0) ? DIAGONAL_COST : ORTHOGONAL_COST);
                        validSteps.add(new EvaluatedStep(neighbor, stepCost, SiegeNode.SiegeAction.MINE));
                    }
                }
                else if (dy == 0) {
                    if (!neighborState.blocksMotion()) {
                        BlockPos groundNode = findGroundBelow(terrain, neighbor, state);

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

    public SiegeNode.SiegeAction determineMacroAction(TerrainAccess terrain, BlockPos pos, int dy, int dx, int dz, BlockPos targetPos) {
        BlockState foot = terrain.getBlockState(pos);
        BlockState head = terrain.getBlockState(pos.above());
        BlockState ceiling = terrain.getBlockState(pos.above(2));
        BlockState support = terrain.getBlockState(pos.below());

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
                    if (terrain.isSolidRender(adj)) {
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
    public int calculateActionCost(TerrainAccess terrain, SiegeNode node) {
        if (node == null) return ORTHOGONAL_COST;
        return calculateActionCostForAction(terrain, node.pos(), node.action());
    }

    /**
     * Calculates building and mining penalties based on position and action type.
     */
    public int calculateActionCostForAction(TerrainAccess terrain, BlockPos pos, SiegeNode.SiegeAction action) {
        if (action == SiegeNode.SiegeAction.MINE) {
            float hardness = terrain.getDestroySpeed(pos);
            if (hardness < 0) return Integer.MAX_VALUE;
            return (int)(hardness * Config.miningPenaltyMultiplier * COST_MULTIPLIER) + (Config.miningBasePenalty * COST_MULTIPLIER);
        } else if (action != SiegeNode.SiegeAction.WALK) {
            return Config.buildingBasePenalty * COST_MULTIPLIER;
        }
        return ORTHOGONAL_COST;
    }

    public boolean isActionCompleted(TerrainAccess terrain, SiegeNode node) {
        // BUILD_LANDING is the odd one out: SiegeInteractionHandler#constructSiegeBlock
        // deliberately leaves node.pos() itself (and the block above it) CLEAR - that's the
        // standing space the landing exists to provide - and fills the floor one block below
        // instead. Checking node.pos() the same way every other BUILD_* action is checked can
        // therefore never see a completed landing as done (it's supposed to stay open), so it
        // was never being marked complete - meaning a rat could never advance past a landing to
        // whatever instruction comes after it, and any rat that re-queried it would redo the
        // same construction again. Confirmed via SiegeActivityLog in testing: the same
        // BUILD_LANDING executed twice in a row at the same position.
        if (node.action() == SiegeNode.SiegeAction.BUILD_LANDING) {
            BlockState floorState = terrain.getBlockState(node.pos().below());
            return floorState.blocksMotion() || isWalkableScaffold(floorState);
        }

        BlockState state = terrain.getBlockState(node.pos());
        return switch (node.action()) {
            // determineMacroAction picks MINE when ANY of foot (node.pos() itself), head
            // (node.pos().above()), or ceiling (node.pos().above(2), only checked for a
            // vertical/diagonal step) blocks motion - not only the foot cell. Checking foot
            // alone here let a mine action approaching an overhang from below (e.g. a diagonal
            // staircase clipping a platform's own solid floor edge one or two blocks above the
            // foot) be reported "already completed" on its very first evaluation whenever the
            // foot happened to already be open air, even though the actual obstruction - the
            // overhang itself - had never been touched. getNextSiegeNode then substituted a
            // synthetic "walk up" instruction pointing at a position with no instruction of its
            // own at all, silently skipping past a still-solid obstruction and leaving the mob
            // with nowhere further to go. Checking all three cells this way is a superset of
            // determineMacroAction's own trigger conditions (harmless when ceiling/head were
            // never actually blocking - those checks then just trivially pass), so it can only
            // make completion detection stricter, never looser, than before this fix. Confirmed
            // via StaircaseSiegeGroupGameTests: a diagonal connector line approaching an elevated
            // platform produced exactly this MINE-marked-complete-with-nothing-mined sequence.
            case MINE -> (!state.blocksMotion() || isWalkableScaffold(state))
                    && isOpenOrWalkable(terrain, node.pos().above())
                    && isOpenOrWalkable(terrain, node.pos().above(2));
            case BUILD_STAIR, BUILD_BRIDGE, BUILD_PILLAR, BUILD_LADDER, BUILD_SPIRAL -> state.blocksMotion() || isWalkableScaffold(state);
            // WALK deliberately still falls to `default -> false` here - see
            // TerrainEvaluatorTest.walkStepOntoOpenSupportedGroundReportsCompleted (disabled) and
            // docs/superpowers/specs/2026-08-04-flow-field-locked-cycle-drops-build-stair-bug.md's
            // "Follow-ups found during this investigation" section for why this isn't a safe fix
            // to land here in isolation: isCompleted()/getRemainingInstructions() both call this
            // method with the raw stored SiegeNode value, whose .pos() is the PREDECESSOR position
            // (SiegeLineTracer's anchor-ward storage convention - see its own doc), not the real
            // position the map key names. Making WALK terrain-sensitive here would evaluate
            // walkability at the wrong cell for every caller that hasn't also been fixed to pass
            // the real position - a second, independent bug that needs its own fix first.
            default -> false;
        };
    }

    private boolean isOpenOrWalkable(TerrainAccess terrain, BlockPos pos) {
        BlockState state = terrain.getBlockState(pos);
        return !state.blocksMotion() || isWalkableScaffold(state);
    }

    // =================================================================================
    // BLOCK STATE HELPERS
    // =================================================================================

    public boolean isWalkableTerrain(TerrainAccess terrain, BlockPos pos) {
        BlockState foot = terrain.getBlockState(pos);

        boolean hasSupport = isWalkableScaffold(foot);
        if (!hasSupport) {
            BlockState support = terrain.getBlockState(pos.below());
            hasSupport = support.blocksMotion() || isWalkableScaffold(support);
        }

        return hasSupport && isFitForWalking(terrain, pos);
    }

    private boolean isFitForWalking(TerrainAccess terrain, BlockPos pos) {
        BlockState foot = terrain.getBlockState(pos);
        BlockState head = terrain.getBlockState(pos.above());
        return (!foot.blocksMotion() || isWalkableScaffold(foot)) && (!head.blocksMotion() || isWalkableScaffold(head));
    }

    private boolean isWalkableScaffold(BlockState state) {
        return state.getBlock() instanceof net.minecraft.world.level.block.StairBlock ||
                state.getBlock() instanceof net.minecraft.world.level.block.SlabBlock ||
                state.getBlock() instanceof net.minecraft.world.level.block.LadderBlock ||
                state.is(Blocks.COBBLESTONE);
    }

    public boolean isOutOfBounds(TerrainAccess terrain, BlockPos pos, FlowFieldState state) {
        if (terrain == null || terrain.isOutsideBuildHeight(pos)) return true;
        if (!terrain.isLoaded(pos)) return true;

        if (state.isOutOfBounds(pos)) return true;

        return false;
    }

    private BlockPos findGroundBelow(TerrainAccess terrain, BlockPos airPos, FlowFieldState state) {
        // Capped to exactly 1 block. The BUILD_PILLAR/BUILD_STAIR step built from this result
        // encodes a single block placement (see getValidOrthogonalSteps) - a mob can place one
        // block and hop onto it, not several stacked at once in one action. Scanning further
        // down (this used to go up to 4) let that same single-placement step get selected for
        // gaps of 2-4 blocks, producing an instruction that actually needs 2-4 sequential
        // placements to be climbable; in practice a construction goal executes it once and
        // leaves an unreachable block floating above the mob's head - observed in testing as a
        // stair/pillar block placed several blocks up with no way to reach it. Genuine
        // multi-level shafts are already handled correctly, one level per node, by
        // evaluateMacroProjects's chained macro-project lines.
        BlockPos checkPos = airPos.below();
        if (isOutOfBounds(terrain, checkPos, state)) return null;
        if (isWalkableTerrain(terrain, checkPos)) return checkPos;
        return null;
    }
}
