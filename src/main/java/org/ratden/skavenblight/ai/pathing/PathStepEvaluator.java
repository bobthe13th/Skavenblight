package org.ratden.skavenblight.ai.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.ratden.skavenblight.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The one unified step-generator: produces both ordinary WALK candidates and construction
 * candidates (TUNNEL/BRIDGE/CARVED_STAIR/AIR_STAIR) from the same per-cell evaluation, so the same
 * Dijkstra flood (FlowFieldCalculator) can use it for both. Construction candidates are only ever
 * generated for a neighbor that already failed the WALK check - the mandatory frontier-gating
 * invariant is a property of this method's own branch order, not separate state to maintain.
 */
public class PathStepEvaluator {

    private static final int ORTHOGONAL_COST = 10;
    private static final int DIAGONAL_COST = 14;

    private static final int[][] HORIZONTAL_OFFSETS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {-1, -1}, {1, -1}, {-1, 1}
    };

    public record EvaluatedStep(BlockPos pos, int cost, PathAction action) {}

    // =================================================================================
    // COST MODEL
    // =================================================================================

    public int baseCostFor(PathAction action) {
        return switch (action) {
            case WALK -> ORTHOGONAL_COST;
            case TUNNEL -> Config.tunnelBaseCost;
            case BRIDGE -> Config.bridgeBaseCost;
            case CARVED_STAIR -> Config.carvedStairBaseCost;
            case AIR_STAIR -> Config.airStairBaseCost;
        };
    }

    /** Extension point: today this is vanilla getDestroySpeed() < 0. A future Skavenblight
     * block-toughness attribute overrides THIS method, not miningCost's formula. */
    public boolean isBedrockLike(TerrainAccess terrain, BlockPos pos) {
        return terrain.getDestroySpeed(pos) < 0;
    }

    public int miningCost(TerrainAccess terrain, BlockPos pos) {
        if (isBedrockLike(terrain, pos)) {
            return bedrockFailsafeWorkUnits();
        }
        float hardness = terrain.getDestroySpeed(pos);
        return (int) (hardness * Config.miningPenaltyMultiplier) + Config.miningBasePenalty;
    }

    public static int bedrockFailsafeWorkUnits() {
        return (int) (Config.bedrockFailsafeRatMinutes * 1200 * Config.workPerRatPerTick);
    }

    // =================================================================================
    // STEP GENERATION
    // =================================================================================

    public List<EvaluatedStep> candidateSteps(TerrainAccess terrain, BlockPos current,
                                               Set<BlockPos> lockedPositions, Predicate<BlockPos> outOfBounds) {
        List<EvaluatedStep> steps = new ArrayList<>();

        for (int[] offset : HORIZONTAL_OFFSETS) {
            for (int dy = -1; dy <= 1; dy++) {
                int dx = offset[0];
                int dz = offset[1];
                BlockPos neighbor = current.offset(dx, dy, dz);

                if (outOfBounds.test(neighbor) || lockedPositions.contains(neighbor)) continue;

                if (isWalkableTerrain(terrain, neighbor)) {
                    boolean diagonal = dx != 0 && dz != 0;
                    if (diagonal) {
                        BlockPos corner1 = current.offset(dx, dy, 0);
                        BlockPos corner2 = current.offset(0, dy, dz);
                        if (terrain.getBlockState(corner1).blocksMotion() || terrain.getBlockState(corner2).blocksMotion()) {
                            continue;
                        }
                    }
                    steps.add(new EvaluatedStep(neighbor, diagonal ? DIAGONAL_COST : ORTHOGONAL_COST, PathAction.WALK));
                    continue;
                }

                // Pure vertical (no horizontal component at all): climbing is permanently removed -
                // never offer a step here, regardless of what's blocking or missing below.
                if (dy != 0 && dx == 0 && dz == 0) continue;

                boolean footBlocked = isBlockingObstacle(terrain, neighbor);
                boolean headBlocked = isBlockingObstacle(terrain, neighbor.above());
                boolean isMiningCase = footBlocked || headBlocked;

                PathAction action;
                if (dy != 0) {
                    action = isMiningCase ? PathAction.CARVED_STAIR : PathAction.AIR_STAIR;
                } else {
                    action = isMiningCase ? PathAction.TUNNEL : PathAction.BRIDGE;
                }

                int cost = baseCostFor(action) + (isMiningCase ? miningCost(terrain, neighbor) : 0);
                steps.add(new EvaluatedStep(neighbor, cost, action));
            }
        }
        return steps;
    }

    private boolean isBlockingObstacle(TerrainAccess terrain, BlockPos pos) {
        BlockState state = terrain.getBlockState(pos);
        return state.blocksMotion() && !isWalkableScaffold(state);
    }

    /**
     * Is the construction step at {@code pos} already done in the real world? TUNNEL checks all
     * three of foot/head/ceiling (not just foot) - checking foot alone let a mining step
     * approaching an overhang from below be reported "already completed" the moment the foot cell
     * happened to already be open air, even though the actual obstruction (the overhang itself) was
     * untouched (see the old TerrainEvaluator.isActionCompleted's identical MINE-branch fix, ported
     * verbatim here since a TUNNEL step's "done" condition is exactly the same check).
     *
     * <p>AIR_STAIR/CARVED_STAIR ask "is {@code pos} standable" ({@link #isWalkableTerrain}), NOT "is
     * {@code pos} solid" - deliberately different from BRIDGE, which places its own block directly
     * AT {@code pos} and so can just check solidity there. An ascending AIR_STAIR/CARVED_STAIR's
     * PHYSICAL stair sits one cell BELOW {@code pos} (see {@code SiegeProject.placementPositionFor}
     * - {@code pos} stays the logical cell a mob ends up standing IN, the stair itself is the
     * SUPPORT under that cell, not a block occupying it), so checking solidity at {@code pos}
     * directly would never resolve true even once a real stair is built and climbable.
     * {@code isWalkableTerrain} already treats "solid or scaffold support one cell below" as
     * satisfying standability, so it transparently covers both the shifted-ascend case and the
     * unshifted case (a stair placed directly at {@code pos}, e.g. a hand-fed fixture, or CARVED_STAIR
     * biting into solid rock without needing a placed block there at all) without needing to know
     * which one applies. CARVED_STAIR keeps TUNNEL's extra ceiling check (one cell higher than
     * {@code isWalkableTerrain} itself looks) - see clearStairHeadroom's own doc for why construction
     * clears exactly that cell too.
     */
    public boolean isActionCompleted(TerrainAccess terrain, BlockPos pos, PathAction action) {
        BlockState state = terrain.getBlockState(pos);
        return switch (action) {
            case TUNNEL -> (!state.blocksMotion() || isWalkableScaffold(state))
                    && isOpenOrWalkable(terrain, pos.above())
                    && isOpenOrWalkable(terrain, pos.above(2));
            case CARVED_STAIR -> isWalkableTerrain(terrain, pos) && isOpenOrWalkable(terrain, pos.above(2));
            case BRIDGE -> state.blocksMotion() || isWalkableScaffold(state);
            case AIR_STAIR -> isWalkableTerrain(terrain, pos);
            case WALK -> isWalkableTerrain(terrain, pos);
        };
    }

    private boolean isOpenOrWalkable(TerrainAccess terrain, BlockPos pos) {
        BlockState state = terrain.getBlockState(pos);
        return !state.blocksMotion() || isWalkableScaffold(state);
    }

    // =================================================================================
    // TERRAIN PREDICATES (ported verbatim from TerrainEvaluator - unrelated to the
    // WALK/construction duplication this class exists to unify)
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
        return (!foot.blocksMotion() || isWalkableScaffold(foot)) && isOverheadClear(head);
    }

    private boolean isOverheadClear(BlockState state) {
        return !state.blocksMotion();
    }

    private boolean isWalkableScaffold(BlockState state) {
        return state.getBlock() instanceof net.minecraft.world.level.block.StairBlock
                || state.getBlock() instanceof net.minecraft.world.level.block.SlabBlock
                || state.getBlock() instanceof net.minecraft.world.level.block.LadderBlock
                || state.is(Blocks.COBBLESTONE);
    }

    public boolean isOutOfBounds(TerrainAccess terrain, BlockPos pos, FlowFieldState state) {
        if (terrain == null || terrain.isOutsideBuildHeight(pos)) return true;
        if (!terrain.isLoaded(pos)) return true;
        return state.isOutOfBounds(pos);
    }
}
