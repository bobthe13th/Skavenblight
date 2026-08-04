package org.ratden.skavenblight.ai.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import static org.junit.jupiter.api.Assertions.*;

// Covers SiegeProjectManager.evaluateMacroProjects/evaluateSingleLine against the locked-cycle
// bug from docs/superpowers/specs/2026-08-04-flow-field-locked-cycle-drops-build-stair-bug.md.
//
// Root cause (confirmed here, not just hypothesized): an active project's own entryPos is
// unconditionally re-added to nextInstructionMap every pass by injectActiveProjects, AND is
// unconditionally re-added to calcQueue - so the ordinary Dijkstra loop can (and, once the
// project's own crossing is far enough along, routinely will) pop that entryPos again as
// `current` and re-fire evaluateMacroProjects on it as a brand-new obstacle anchor. Nothing in
// evaluateMacroProjects/evaluateSingleLine's own candidate-line tracer checks lockedPositions
// before writing: its only dedup guard, isNearExistingProject, is a same-pass anchor-proximity
// check with no awareness of already-active projects at all. So when one of the 14 fanned
// directions happens to trace straight back over a cell the SAME active project's own
// (unrelated, already-correct) instructions already claim, evaluateSingleLine's unconditional
// nextInstructionMap.putAll(...) (deliberately uncomparisoned - see FlowFieldCalculator's own
// comment above breakMutualCycles) overwrites that cell's instruction to point back at the
// anchor - producing a direct mutual 2-cycle with the anchor's own still-standing, entry-pos
// instruction.
//
// This is a different, more specific mechanism than the spec's original hypothesis ("two lines
// fanned from the same anchor collide with each other"): two rays diverging from one anchor in 14
// genuinely distinct directions can never revisit the same downstream cell (that would require
// one direction vector to be a positive scalar multiple of another, and none of the 14 are) - so
// sibling-vs-sibling collision within a single evaluateMacroProjects call is geometrically
// impossible. The only writer of an "outward" (away-from-target) edge is a stale active project's
// own re-injected instruction; the only way that collides into a cycle is a fresh trace
// overwriting one of that same project's own already-locked cells.
//
// The fix itself is scoped to exactly that self-collision case (not "any locked cell") - see the
// second block of tests below for why a broader "abort on any locked cell" guard was tried,
// reverted, and replaced after a live regression.
class SiegeProjectManagerTest {

    private static class FakeTerrain implements TerrainAccess {
        private final Map<BlockPos, BlockState> states = new HashMap<>();

        void set(BlockPos pos, BlockState state) { states.put(pos, state); }

        @Override
        public BlockState getBlockState(BlockPos pos) { return states.getOrDefault(pos, Blocks.AIR.defaultBlockState()); }

        @Override
        public boolean isLoaded(BlockPos pos) { return true; }

        @Override
        public boolean isOutsideBuildHeight(BlockPos pos) { return false; }

        @Override
        public boolean isSolidRender(BlockPos pos) { return getBlockState(pos).blocksMotion(); }

        @Override
        public float getDestroySpeed(BlockPos pos) { return 1.0F; }
    }

    @Test
    void reEvaluatingAnActiveProjectsEntryPosMustNotOverwriteThatSameProjectsOwnLockedInteriorCell() {
        TerrainEvaluator evaluator = new TerrainEvaluator();
        FakeTerrain terrain = new FakeTerrain();
        SiegeProjectManager manager = new SiegeProjectManager(evaluator);

        BlockPos furtherBack = new BlockPos(0, 66, 0); // the project's own original anchor
        BlockPos upstream = new BlockPos(0, 65, 0);    // interior cell, still locked/unbuilt
        BlockPos anchor = new BlockPos(0, 64, 0);      // the active project's own entryPos

        // anchor is a walkable scaffold block (a mob can already stand here - it's the completed,
        // walked-onto far end of the project's crossing) and also solid support for `upstream`
        // directly above it, so a fresh trace from `anchor` straight upward (dy=+1, the first
        // direction evaluateMacroProjects tries) completes in a single WALK hop onto `upstream` -
        // exactly mirroring how a real reactive project's own final step always resolves.
        terrain.set(anchor, Blocks.COBBLESTONE.defaultBlockState());

        // Register a pre-existing 2-hop active project whose OWN instructions already correctly
        // read furtherBack -> upstream -> anchor. Both `upstream` and `anchor` are therefore
        // locked cells of this SAME project (matching the live log, where both the dropped and
        // kept cycle members were reported locked=true).
        Map<BlockPos, SiegeNode> instructions = Map.of(
                upstream, new SiegeNode(furtherBack, SiegeNode.SiegeAction.BUILD_PILLAR),
                anchor, new SiegeNode(upstream, SiegeNode.SiegeAction.WALK));
        SiegeProject activeProject = new SiegeProject(instructions,
                List.of(new SiegeNode(upstream, SiegeNode.SiegeAction.BUILD_PILLAR), new SiegeNode(anchor, SiegeNode.SiegeAction.WALK)),
                furtherBack, anchor, 500);
        manager.addSharedConnectorProject(activeProject);

        FlowFieldState state = new FlowFieldState(new BlockPos(0, 0, 0), Collections.emptySet());
        PriorityQueue<FlowFieldCalculator.QueueNode> calcQueue = new PriorityQueue<>();
        Map<BlockPos, Integer> nextCostMap = new HashMap<>();
        Map<BlockPos, SiegeNode> nextInstructionMap = new HashMap<>();

        // Mirrors FlowFieldCalculator.startCalculation's own call, seeding lockedPositions/
        // nextInstructionMap/calcQueue from the active project exactly as a real pass would.
        manager.injectActiveProjects(terrain, calcQueue, nextCostMap, nextInstructionMap);

        assertEquals(Map.of(upstream, furtherBack, anchor, upstream).keySet(), manager.getLockedPositions(),
                "setup sanity: both of the project's own instruction keys must be locked before evaluateMacroProjects runs");
        assertEquals(new SiegeNode(upstream, SiegeNode.SiegeAction.WALK), nextInstructionMap.get(anchor),
                "setup sanity: the active project's own instruction must be seeded before evaluateMacroProjects runs");

        // The ordinary Dijkstra loop would pop `anchor` off calcQueue here and re-fire
        // evaluateMacroProjects on it exactly like this, once it's judged to have hit an obstacle.
        manager.evaluateMacroProjects(terrain, anchor, state, 500, calcQueue, nextCostMap, nextInstructionMap);

        SiegeNode upstreamInstruction = nextInstructionMap.get(upstream);
        assertEquals(new SiegeNode(furtherBack, SiegeNode.SiegeAction.BUILD_PILLAR), upstreamInstruction,
                "a fresh macro-line trace must never overwrite a cell that's already locked by an "
                        + "active project - here it clobbered `upstream`'s own correct instruction "
                        + "(furtherBack) with a brand-new one pointing back at `anchor`, which - "
                        + "combined with anchor's own still-standing instruction pointing at "
                        + "`upstream` - forms a direct mutual 2-cycle");
    }

    // --- Regression found live post-fix (2026-08-04 field report), now itself fixed: a clanrat
    // standing next to a freshly-placed BUILD_STAIR block stopped building the rest of the
    // staircase on its own. Live logs showed no cycle-break WARN (the self-collision fix above was
    // working) but also no "Successful Macro Line" for the long climbing direction that should have
    // produced the rest of the staircase - only the short one-hop line succeeded. A first version
    // of the guard aborted a trace the instant it touched ANY locked cell, with no way to tell
    // "this would form a cycle" apart from "this cell just happens to be on the path of a
    // different, unrelated, harmless active project". Live diagnostic logging against the same
    // repro confirmed the real bug is narrower (11 of 13 blocked directions were self-collision,
    // only 2 were cross-project) - see evaluateSingleLine's own doc for the fix this settled on.
    // The two tests below isolate the cross-project case: identical terrain and direction, the only
    // difference is whether an UNRELATED project (not the one anchorPos belongs to) has locked one
    // cell on the path - this must now succeed, matching the live fix.

    private static BlockPos horizontalGapPos(int x) { return new BlockPos(x, 50, 0); }

    private SiegeProjectManager buildManagerForHorizontalGapLine(FakeTerrain terrain) {
        // A 3-step horizontal gap: steps 1-2 are open air over open air (need BUILD_BRIDGE), step 3
        // lands on solid ground (support at y=49) and completes the trace.
        terrain.set(horizontalGapPos(3).below(), Blocks.STONE.defaultBlockState());
        return new SiegeProjectManager(new TerrainEvaluator());
    }

    @Test
    void freshTraceCompletesNormallyWhenNothingOnItsPathIsLocked() {
        FakeTerrain terrain = new FakeTerrain();
        SiegeProjectManager manager = buildManagerForHorizontalGapLine(terrain);
        BlockPos anchor = horizontalGapPos(0);

        FlowFieldState state = new FlowFieldState(new BlockPos(0, 0, 0), Collections.emptySet());
        PriorityQueue<FlowFieldCalculator.QueueNode> calcQueue = new PriorityQueue<>();
        Map<BlockPos, Integer> nextCostMap = new HashMap<>();
        Map<BlockPos, SiegeNode> nextInstructionMap = new HashMap<>();

        manager.evaluateMacroProjects(terrain, anchor, state, 0, calcQueue, nextCostMap, nextInstructionMap);

        assertNotNull(nextInstructionMap.get(horizontalGapPos(3)),
                "setup sanity: with nothing locked on its path, this 3-step gap-crossing line must "
                        + "complete normally - the regression test below only means something if this baseline works");
    }

    @Test
    void freshTraceSucceedsWhenItCrossesAnUnrelatedActiveProjectsLockedCellWithoutFormingACycle() {
        FakeTerrain terrain = new FakeTerrain();
        SiegeProjectManager manager = buildManagerForHorizontalGapLine(terrain);
        BlockPos anchor = horizontalGapPos(0);
        BlockPos crossedCell = horizontalGapPos(2); // second step of the SAME line as the baseline above

        // A completely unrelated active project happens to have `crossedCell` as one of its OWN
        // interior (still-locked) cells - its own entryPos is `distantEntry`, well away from this
        // line entirely, so this is a genuine cross-project crossing, not the anchor re-entering
        // its own project. `crossedCell` is deliberately NOT this project's entryPos: injectActiveProjects
        // only seeds nextCostMap at a project's entryPos, so making entryPos itself the crossed cell
        // would trip the trace's own unrelated cost-ceiling guard (refusing to overwrite a cheaper
        // existing path) - a real but different protection this test isn't about. Overwriting
        // crossedCell here would not create any cycle either (nothing in the new line points back
        // to it or to anything it points to).
        BlockPos distantEntry = new BlockPos(2, 50, 500);
        Map<BlockPos, SiegeNode> unrelatedInstructions = Map.of(
                crossedCell, new SiegeNode(new BlockPos(2, 50, 501), SiegeNode.SiegeAction.BUILD_PILLAR),
                distantEntry, new SiegeNode(crossedCell, SiegeNode.SiegeAction.WALK));
        SiegeProject unrelatedProject = new SiegeProject(unrelatedInstructions,
                List.of(new SiegeNode(crossedCell, SiegeNode.SiegeAction.BUILD_PILLAR),
                        new SiegeNode(distantEntry, SiegeNode.SiegeAction.WALK)),
                new BlockPos(2, 50, 501), distantEntry, 500);
        manager.addSharedConnectorProject(unrelatedProject);

        FlowFieldState state = new FlowFieldState(new BlockPos(0, 0, 0), Collections.emptySet());
        PriorityQueue<FlowFieldCalculator.QueueNode> calcQueue = new PriorityQueue<>();
        Map<BlockPos, Integer> nextCostMap = new HashMap<>();
        Map<BlockPos, SiegeNode> nextInstructionMap = new HashMap<>();

        manager.injectActiveProjects(terrain, calcQueue, nextCostMap, nextInstructionMap);
        assertTrue(manager.getLockedPositions().contains(crossedCell), "setup sanity: crossedCell must be locked");

        manager.evaluateMacroProjects(terrain, anchor, state, 0, calcQueue, nextCostMap, nextInstructionMap);

        assertNotNull(nextInstructionMap.get(horizontalGapPos(3)),
                "the guard must only block a project re-entering its OWN cells via its own entryPos - "
                        + "`anchor` here is not `unrelatedProject`'s entryPos, so this otherwise-identical "
                        + "line (same terrain, same direction, same endpoint as the passing baseline above) "
                        + "must still complete even though it crosses a cell locked by a different project");
    }
}
