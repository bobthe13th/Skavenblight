package org.ratden.skavenblight.ai.pathing;

import net.minecraft.core.BlockPos;
import org.ratden.skavenblight.Config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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

    /**
     * @param instructions  the traced line as an instruction map in exactly FlowFieldCalculator's
     *                      own convention (see its {@code nextInstructionMap}, e.g.
     *                      {@code put(step.pos(), new SiegeNode(current, action))}): keyed by the
     *                      real position the action applies to, with the stored SiegeNode's own
     *                      {@code pos()} carrying the NEXT HOP TOWARD THE TARGET (one step closer
     *                      to {@code anchorPos}, which is itself closer to the target than
     *                      {@code endPos}) - a standard Dijkstra predecessor-tree pointer, correct
     *                      and intentional for mob traversal ("predecessor in the search" is
     *                      "successor for the mob"). This is NOT an off-by-one and nothing here
     *                      needs correcting for that reason. The actual footgun: {@code pos()}
     *                      means two different things depending on how a SiegeNode is used - as a
     *                      map VALUE it's "next hop", but as a standalone {@code SiegeNode(pos,
     *                      action)} pair (e.g. what {@link TerrainEvaluator#isActionCompleted}
     *                      expects) it means "the real position this action applies to". Any
     *                      completion/terrain check against an entry from this map MUST build
     *                      {@code new SiegeNode(entry.getKey(), entry.getValue().action())} first -
     *                      never pass the raw stored value. See the pathing invariants doc for the
     *                      full writeup; SiegeProject.isCompleted/getRemainingInstructions are the
     *                      reference-correct examples.
     * @param orderedSteps  the same walk in direction-neutral form: one entry per hop, in trace
     *                      order, each naming the position stepped INTO and the action
     *                      determineMacroAction computed FOR that position. {@code orderedSteps[0]}
     *                      is the first position past the anchor and the last one is {@code endPos}.
     *                      A caller that has to guide mobs along this line in a specific direction
     *                      (see RegionGraph, where a connector can be traversed from either end)
     *                      builds its own correctly-paired map from this instead of trying to reuse
     *                      {@code instructions}, which is locked to one orientation.
     */
    public record TraceResult(Map<BlockPos, SiegeNode> instructions, List<SiegeNode> orderedSteps,
                               BlockPos endPos, int totalCost, boolean completed) {
        static TraceResult aborted() {
            return new TraceResult(Map.of(), List.of(), null, Integer.MAX_VALUE, false);
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
        return trace(terrain, anchorPos, dx, dy, dz, costBiasTarget, startingCost, outOfBounds, costCeiling, MAX_PROJECT_LENGTH);
    }

    /**
     * Same as the 9-arg overload, but with the line's own length bound overridable instead of
     * always using {@code MAX_PROJECT_LENGTH} - see SiegeProjectManager.setMaxCandidateProjectLength
     * for why a region-scoped caller wants a shorter bound than RegionGraph's own territory-wide
     * connector discovery (which always calls the 9-arg overload above, keeping its full 32-block
     * reach regardless of this parameter).
     */
    public TraceResult trace(TerrainAccess terrain, BlockPos anchorPos, int dx, int dy, int dz,
                              BlockPos costBiasTarget, int startingCost, Predicate<BlockPos> outOfBounds,
                              ToIntFunction<BlockPos> costCeiling, int maxLength) {
        int projectCost = Config.buildingBasePenalty * COST_MULTIPLIER;
        int mineChainLength = 0;
        BlockPos currentTarget = anchorPos;

        Map<BlockPos, SiegeNode> instructions = new HashMap<>();
        // Accumulated in the same loop, no extra terrain work: each hop paired with the action
        // determineMacroAction computed for that very position - see TraceResult's doc.
        List<SiegeNode> orderedSteps = new ArrayList<>();

        for (int i = 1; i <= maxLength; i++) {
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
            orderedSteps.add(new SiegeNode(nextPos, action));

            if (terrainEvaluator.isWalkableTerrain(terrain, nextPos)) {
                return finishNaturalTermination(terrain, instructions, orderedSteps, nextPos, totalCost);
            }

            if (i == maxLength) {
                Map<BlockPos, SiegeNode> withLanding = new HashMap<>(instructions);
                withLanding.put(nextPos, new SiegeNode(currentTarget, SiegeNode.SiegeAction.BUILD_LANDING));
                // Mirror the substitution in the ordered form too, so every orientation derived
                // from it terminates in the same synthetic landing rather than the raw action.
                List<SiegeNode> stepsWithLanding = new ArrayList<>(orderedSteps);
                stepsWithLanding.set(stepsWithLanding.size() - 1, new SiegeNode(nextPos, SiegeNode.SiegeAction.BUILD_LANDING));
                return new TraceResult(withLanding, List.copyOf(stepsWithLanding), nextPos, totalCost, true);
            }

            currentTarget = nextPos;
        }

        return TraceResult.aborted();
    }

    /**
     * A trace's fixed (dx,dy,dz) stride can overshoot real terrain by exactly one tread: the
     * single step immediately before natural termination gets tagged BUILD_STAIR/BUILD_BRIDGE/
     * etc only because determineMacroAction's own support check (pos.below() open) never looks
     * any further down than that - but if genuinely walkable ground already exists one level
     * below THAT (i.e. two below the tread itself), the tread is unnecessary: a mob can already
     * walk there directly, one level lower, with no construction at all. Reported live as "the
     * first stair was built on top of a [fill] block, when it should have been placed on the
     * already-existing ground."
     *
     * Scoped to a trace that is EXACTLY this one tread plus its terminal WALK
     * (orderedSteps.size() == 2, i.e. nothing between the tread and the anchor). A longer
     * chain's own second-to-last tread would need the same check, but its corrected ground
     * position sits a dy=-2 hop from whatever tread precedes it - not an ordinary walkable step
     * - so trimming there would strand the remaining chain. No confirmed case needs that;
     * leaving longer chains untouched avoids introducing an unreachable gap for one that hasn't
     * been seen.
     *
     * Scoped to BUILD_STAIR specifically, not every support-triggered action. A one-segment
     * vertical trace (dx=0, dz=0) has tread.pos() == anchorPos.above(), so
     * tread.pos().below() == anchorPos itself - the cell the mob is already standing on, which
     * is walkable by construction (that's why the flood is here). Applying this check to
     * BUILD_PILLAR/BUILD_LADDER/BUILD_SPIRAL would abort every one-segment vertical climb,
     * mistaking "the anchor is walkable" (always true, no overshoot involved) for "the tread
     * overshot real terrain" (the actual, diagonal-only failure mode this fixes).
     */
    private TraceResult finishNaturalTermination(TerrainAccess terrain, Map<BlockPos, SiegeNode> instructions,
                                                  List<SiegeNode> orderedSteps, BlockPos endPos, int totalCost) {
        if (orderedSteps.size() == 2) {
            SiegeNode tread = orderedSteps.get(0);
            if (tread.action() == SiegeNode.SiegeAction.BUILD_STAIR
                    && terrainEvaluator.isWalkableTerrain(terrain, tread.pos().below())) {
                return TraceResult.aborted();
            }
        }
        return new TraceResult(instructions, List.copyOf(orderedSteps), endPos, totalCost, true);
    }
}
