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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

// Covers SiegeProjectManager.evaluateMacroProjects/evaluateSingleLine against the locked-cycle bug
// from docs/superpowers/specs/2026-08-04-flow-field-locked-cycle-drops-build-stair-bug.md: an
// active project's own entryPos is unconditionally re-added to nextInstructionMap/calcQueue every
// pass by injectActiveProjects, so ordinary Dijkstra can pop it again and re-fire
// evaluateMacroProjects on it as a fresh obstacle anchor. Nothing in evaluateSingleLine's own
// tracer checked lockedPositions before writing, so a freshly-fanned line could trace straight
// back over a cell that SAME project already owns and silently overwrite its instruction to point
// back at the anchor - forming a direct mutual 2-cycle. The fix must be scoped to exactly that
// self-collision case, not "abort on any locked cell" - a blanket check also blocks a fresh line
// merely crossing a different, unrelated project's cell, which breaks real construction.
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

        // anchor is solid ground directly below `upstream`, so a fresh trace from `anchor` straight
        // upward (dy=+1, the first direction evaluateMacroProjects tries) completes in a single
        // WALK hop onto `upstream`. Deliberately STONE, not COBBLESTONE: isWalkableScaffold()
        // treats COBBLESTONE as trivially standable in its own cell, which would make anchor's own
        // WALK step read as already-complete (correctly, now that isActionCompleted checks the
        // real WALK position) and evict it from lockedPositions before this test even runs -
        // collapsing the self-collision scenario this test needs. STONE provides the same solid
        // support for `upstream` above it without making `anchor` itself walkable-in-place, so
        // `anchor`'s own WALK instruction stays genuinely incomplete/locked, matching the live bug's
        // own repro (both cycle members reported locked=true).
        terrain.set(anchor, Blocks.STONE.defaultBlockState());

        // A pre-existing 2-hop active project whose OWN instructions already correctly read
        // furtherBack -> upstream -> anchor. Both `upstream` and `anchor` are locked cells of this
        // SAME project.
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

    // --- A blanket "abort on ANY locked cell" guard also blocks a fresh line that merely crosses a
    // DIFFERENT, unrelated project's cell, with no cycle risk at all - the two tests below prove
    // the fix must be scoped to self-collision (the anchor re-entering its OWN project), not every
    // locked cell.

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

        // A completely unrelated active project has `crossedCell` as one of its OWN interior
        // (still-locked) cells - its own entryPos is `distantEntry`, well away from this line
        // entirely, so this is a genuine cross-project crossing, not the anchor re-entering its own
        // project. Overwriting crossedCell here would not create any cycle either (nothing in the
        // new line points back to it or to anything it points to).
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

    // --- injectActiveProjects must keep routing mobs TO an active project's entryPos even once
    // TerrainEvaluator.isActionCompleted correctly reports its WALK action as already done (see
    // that class's own doc on the WALK case). entryPos is, by construction, on the far side of a
    // gap the ordinary Dijkstra flood cannot independently cross while the project's own interior
    // cells are still unbuilt/locked - it's excluded from getRemainingInstructions precisely
    // because nothing needs building there, but that must not mean nothing ROUTES there either, or
    // a mob standing at entryPos is left with no instruction at all and can never be told to walk
    // onward into the project's still-unbuilt interior.

    @Test
    void entryPosStaysRoutableOnceItsOwnWalkStepReadsCompleteButTheProjectsInteriorIsStillUnbuilt() {
        FakeTerrain terrain = new FakeTerrain();
        SiegeProjectManager manager = new SiegeProjectManager(new TerrainEvaluator());

        BlockPos hop1 = new BlockPos(0, 50, 1);   // interior BUILD_STAIR step - still unbuilt
        BlockPos entryPos = new BlockPos(0, 49, 2); // far side of the gap - already genuinely walkable

        terrain.set(entryPos.below(), Blocks.STONE.defaultBlockState()); // real support under entryPos

        Map<BlockPos, SiegeNode> instructions = Map.of(
                hop1, new SiegeNode(new BlockPos(0, 50, 0), SiegeNode.SiegeAction.BUILD_STAIR),
                entryPos, new SiegeNode(hop1, SiegeNode.SiegeAction.WALK));
        SiegeProject project = new SiegeProject(instructions,
                List.of(new SiegeNode(hop1, SiegeNode.SiegeAction.BUILD_STAIR), new SiegeNode(entryPos, SiegeNode.SiegeAction.WALK)),
                new BlockPos(0, 50, 0), entryPos, 4500);
        manager.addSharedConnectorProject(project);

        // Ground truth matching the doc's own WALK-completion reasoning: entryPos genuinely is
        // walkable right now, and hop1 genuinely is not yet built.
        TerrainEvaluator evaluator = new TerrainEvaluator();
        assertTrue(evaluator.isWalkableTerrain(terrain, entryPos), "setup sanity: entryPos must actually be walkable");
        assertFalse(evaluator.isActionCompleted(terrain, new SiegeNode(hop1, SiegeNode.SiegeAction.BUILD_STAIR)),
                "setup sanity: hop1 must not yet be built, or this test proves nothing about the project staying active");

        PriorityQueue<FlowFieldCalculator.QueueNode> calcQueue = new PriorityQueue<>();
        Map<BlockPos, Integer> nextCostMap = new HashMap<>();
        Map<BlockPos, SiegeNode> nextInstructionMap = new HashMap<>();

        // No core flood runs in this test - deliberately, to isolate injectActiveProjects' own
        // seeding from whatever the ordinary Dijkstra flood might separately contribute. If the
        // ordinary gap really can't be crossed without this project, the flood wouldn't reach
        // entryPos either, so this is the realistic no-alternative-route case.
        manager.injectActiveProjects(terrain, calcQueue, nextCostMap, nextInstructionMap);

        assertEquals(Set.of(hop1), manager.getLockedPositions(),
                "entryPos needs no construction and must not be locked - only hop1, the genuinely unbuilt step, should be");
        assertEquals(new SiegeNode(new BlockPos(0, 50, 0), SiegeNode.SiegeAction.BUILD_STAIR), nextInstructionMap.get(hop1),
                "setup sanity: hop1's own unbuilt instruction must still be seeded");
        assertEquals(new SiegeNode(hop1, SiegeNode.SiegeAction.WALK), nextInstructionMap.get(entryPos),
                "a mob standing at entryPos must still be told to WALK onward to hop1 - entryPos being "
                        + "already-complete means nothing needs BUILDING there, not that nothing ROUTES there; "
                        + "with no instruction at all a mob here is stranded and can never reach hop1's real "
                        + "BUILD_STAIR work");
    }

    /**
     * The entryPos fallback above must not assume every project keys its own entryPos in
     * `instructions` - a route-tree connector project (the 6-arg constructor, built by
     * RegionGraph.registerConnector) deliberately does NOT: see that method's own doc, "neither
     * outboundInstructions nor inboundInstructions keys their own far endpoint... the far region's
     * own pass already covers it." A naive {@code Map.putIfAbsent(entry, instructions.get(entry))}
     * would silently insert a literal null value for such a project, which would NPE the first time
     * any caller (getNextSiegeNode, FollowFlowFieldGoal) reads it back out.
     */
    @Test
    void entryPosFallbackNeverInsertsNullForAConnectorProjectThatDoesNotKeyItsOwnEntryPos() {
        FakeTerrain terrain = new FakeTerrain();
        SiegeProjectManager manager = new SiegeProjectManager(new TerrainEvaluator());

        BlockPos boundaryCell = new BlockPos(0, 50, 0);
        BlockPos entryPos = new BlockPos(0, 50, 5); // deliberately NOT a key below - mirrors RegionGraph's connector shape

        Map<BlockPos, SiegeNode> instructions = Map.of(boundaryCell, new SiegeNode(entryPos, SiegeNode.SiegeAction.WALK));
        SiegeProject connectorProject = new SiegeProject(instructions,
                List.of(new SiegeNode(boundaryCell, SiegeNode.SiegeAction.WALK)),
                entryPos, entryPos, 500, boundaryCell);
        manager.addSharedConnectorProject(connectorProject);

        PriorityQueue<FlowFieldCalculator.QueueNode> calcQueue = new PriorityQueue<>();
        Map<BlockPos, Integer> nextCostMap = new HashMap<>();
        Map<BlockPos, SiegeNode> nextInstructionMap = new HashMap<>();

        manager.injectActiveProjects(terrain, calcQueue, nextCostMap, nextInstructionMap);

        assertFalse(nextInstructionMap.containsKey(entryPos) && nextInstructionMap.get(entryPos) == null,
                "must never insert a literal null instruction for entryPos just because the project's "
                        + "own instructions map has no entry for it - that would NPE the first caller "
                        + "that reads it back out");
    }
}
