package org.ratden.skavenblight.ai.pathing;

import net.minecraft.core.BlockPos;
import org.ratden.skavenblight.Config;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * Pure line-tracing core shared by SiegeProjectManager's reactive obstacle-bridging
 * (evaluateSingleLine) and the region graph's proactive connector discovery. Walks a
 * straight line from an anchor in one direction, accumulating cost/instructions via
 * TerrainEvaluator's existing action rules, until it reaches walkable ground, hits
 * maxLength (capped with a synthetic BUILD_LANDING), or aborts (out of bounds, invalid
 * action, or too many consecutive MINE steps).
 */
public class SiegeLineTracer {

    private static final int MAX_PROJECT_LENGTH = 32;
    private static final int COST_MULTIPLIER = 10;
    private static final int MAX_CONSECUTIVE_MINE = 5;

    private final TerrainEvaluator terrainEvaluator;

    public SiegeLineTracer(TerrainEvaluator terrainEvaluator) {
        this.terrainEvaluator = terrainEvaluator;
    }

    public record TraceResult(Map<BlockPos, SiegeNode> instructions, BlockPos endPos, int totalCost, boolean completed) {
        static TraceResult aborted() {
            return new TraceResult(Map.of(), null, Integer.MAX_VALUE, false);
        }
    }

    /**
     * @param costBiasTarget passed straight through to determineMacroAction's cost-bias logic (the
     *                        flow field's target position - unrelated to this trace's own endpoint).
     * @param outOfBounds     true if a position is outside whatever bounds this caller is tracing within.
     * @param costCeiling     per-step early-abort ceiling, mirroring nextCostMap's role in the flood fill:
     *                        the trace aborts as soon as its running cost at a step is no better than
     *                        whatever the caller already knows about that position, so a completed trace
     *                        can never overwrite an already-cheaper position with a worse instruction.
     */
    public TraceResult trace(TerrainAccess terrain, BlockPos anchorPos, int dx, int dy, int dz,
                              BlockPos costBiasTarget, int startingCost, Predicate<BlockPos> outOfBounds,
                              ToIntFunction<BlockPos> costCeiling) {
        int projectCost = Config.buildingBasePenalty * COST_MULTIPLIER;
        int mineChainLength = 0;
        BlockPos currentTarget = anchorPos;

        Map<BlockPos, SiegeNode> instructions = new HashMap<>();

        for (int i = 1; i <= MAX_PROJECT_LENGTH; i++) {
            BlockPos nextPos = currentTarget.offset(dx, dy, dz);

            if (outOfBounds.test(nextPos)) {
                return TraceResult.aborted();
            }

            SiegeNode.SiegeAction action = terrainEvaluator.determineMacroAction(terrain, nextPos, dy, dx, dz, costBiasTarget);
            if (action == null) {
                return TraceResult.aborted();
            }

            if (action == SiegeNode.SiegeAction.MINE) {
                mineChainLength++;
                if (mineChainLength > MAX_CONSECUTIVE_MINE) return TraceResult.aborted();
            } else {
                mineChainLength = 0;
            }

            projectCost += terrainEvaluator.calculateActionCostForAction(terrain, nextPos, action);
            int evaluatedProjectCost = (dy != 0) ? (int) ((projectCost * 2) * 0.75f) : (projectCost * 2);
            int totalCost = startingCost + evaluatedProjectCost;

            if (totalCost >= costCeiling.applyAsInt(nextPos)) {
                return TraceResult.aborted();
            }

            instructions.put(nextPos, new SiegeNode(currentTarget, action));

            if (terrainEvaluator.isWalkableTerrain(terrain, nextPos)) {
                return new TraceResult(instructions, nextPos, totalCost, true);
            }

            if (i == MAX_PROJECT_LENGTH) {
                Map<BlockPos, SiegeNode> withLanding = new HashMap<>(instructions);
                withLanding.put(nextPos, new SiegeNode(currentTarget, SiegeNode.SiegeAction.BUILD_LANDING));
                return new TraceResult(withLanding, nextPos, totalCost, true);
            }

            currentTarget = nextPos;
        }

        return TraceResult.aborted();
    }
}
