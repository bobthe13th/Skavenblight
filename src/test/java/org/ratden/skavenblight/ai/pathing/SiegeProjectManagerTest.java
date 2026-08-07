package org.ratden.skavenblight.ai.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

// Covers SiegeProjectManager.evaluateMacroProjects/evaluateSingleLine against the locked-cycle bug
// from docs/superpowers/specs/2026-08-04-flow-field-locked-cycle-drops-build-stair-bug.md: an
// active project's own entryPos is unconditionally re-added to nextInstructionMap/calcQueue every
// pass by injectActiveProjects, so ordinary Dijkstra can pop it again and re-fire
// evaluateMacroProjects on it as a fresh obstacle anchor. Nothing in evaluateSingleLine's own
// chained-hop trace checked lockedPositions before writing, so a freshly-fanned line could trace
// straight back over a cell that SAME project already owns and silently overwrite its instruction
// to point back at the anchor - forming a direct mutual 2-cycle. The fix must be scoped to exactly
// that self-collision case, not "abort on any locked cell" - a blanket check also blocks a fresh
// line merely crossing a different, unrelated project's cell, which breaks real construction.
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
        // Pure-vertical construction no longer produces any candidate step at all (see
        // PathStepEvaluator.candidateSteps' dx==0&&dz==0 skip), so the old BUILD_PILLAR-straight-up
        // fixture this test used can no longer occur - re-anchored on the (1,1,0) diagonal fan
        // direction instead, which is still one of the 14 directions evaluateMacroProjects tries and
        // still exercises the identical self-collision scenario (anchor re-entering its own active
        // project's locked interior cell one hop away).
        PathStepEvaluator evaluator = new PathStepEvaluator();
        FakeTerrain terrain = new FakeTerrain();
        SiegeProjectManager manager = new SiegeProjectManager(evaluator);

        BlockPos furtherBack = new BlockPos(2, 66, 0); // the project's own original build anchor
        BlockPos upstream = new BlockPos(1, 65, 0);    // interior cell, one (1,1,0) hop from anchor - still locked/unbuilt
        BlockPos anchor = new BlockPos(0, 64, 0);      // the active project's own entryPos

        // anchor is solid ground, so anchor's own WALK instruction stays genuinely incomplete/locked
        // (isWalkableTerrain requires the cell's OWN foot to be non-blocking) - matching the live
        // bug's own repro, where both cycle members reported locked=true. upstream is left open air:
        // no support below it (dy!=0, dx==0&&dz==0 isn't the case here since dx=1) and nothing
        // blocking at upstream/upstream.above() classifies it as AIR_STAIR, not CARVED_STAIR - the
        // exact classification doesn't matter to this test, only that it's a real non-WALK action.
        terrain.set(anchor, Blocks.STONE.defaultBlockState());

        // A pre-existing 2-hop active project whose OWN instructions already correctly read
        // furtherBack -> upstream -> anchor. Both `upstream` and `anchor` are locked cells of this
        // SAME project. FlowStep.pos() always equals its own map key now (Task 1's fix for
        // SiegeNode's confirmed map-key-vs-predecessor bug) - predecessorPos carries the next hop.
        Map<BlockPos, FlowStep> instructions = Map.of(
                upstream, new FlowStep(upstream, PathAction.AIR_STAIR, furtherBack),
                anchor, new FlowStep(anchor, PathAction.WALK, upstream));
        SiegeProject activeProject = new SiegeProject(instructions,
                List.of(new FlowStep(upstream, PathAction.AIR_STAIR, furtherBack), new FlowStep(anchor, PathAction.WALK, upstream)),
                furtherBack, anchor, 500, UUID.randomUUID());
        manager.addSharedConnectorProject(activeProject);

        FlowFieldState state = new FlowFieldState(new BlockPos(0, 0, 0), Collections.emptySet());
        PriorityQueue<FlowFieldCalculator.QueueNode> calcQueue = new PriorityQueue<>();
        Map<BlockPos, Integer> nextCostMap = new HashMap<>();
        Map<BlockPos, FlowStep> nextInstructionMap = new HashMap<>();

        // Mirrors FlowFieldCalculator.startCalculation's own call, seeding lockedPositions/
        // nextInstructionMap/calcQueue from the active project exactly as a real pass would.
        manager.injectActiveProjects(terrain, calcQueue, nextCostMap, nextInstructionMap);

        assertEquals(Set.of(upstream, anchor), manager.getLockedPositions(),
                "setup sanity: both of the project's own instruction keys must be locked before evaluateMacroProjects runs");
        assertEquals(new FlowStep(anchor, PathAction.WALK, upstream), nextInstructionMap.get(anchor),
                "setup sanity: the active project's own instruction must be seeded before evaluateMacroProjects runs");

        // The ordinary Dijkstra loop would pop `anchor` off calcQueue here and re-fire
        // evaluateMacroProjects on it exactly like this, once it's judged to have hit an obstacle.
        manager.evaluateMacroProjects(terrain, anchor, state, 500, calcQueue, nextCostMap, nextInstructionMap);

        FlowStep upstreamInstruction = nextInstructionMap.get(upstream);
        assertEquals(new FlowStep(upstream, PathAction.AIR_STAIR, furtherBack), upstreamInstruction,
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
        // A 3-step horizontal gap: steps 1-2 are open air over open air (need BRIDGE), step 3
        // lands on solid ground (support at y=49) and completes the trace.
        terrain.set(horizontalGapPos(3).below(), Blocks.STONE.defaultBlockState());
        return new SiegeProjectManager(new PathStepEvaluator());
    }

    @Test
    void freshTraceCompletesNormallyWhenNothingOnItsPathIsLocked() {
        FakeTerrain terrain = new FakeTerrain();
        SiegeProjectManager manager = buildManagerForHorizontalGapLine(terrain);
        BlockPos anchor = horizontalGapPos(0);

        FlowFieldState state = new FlowFieldState(new BlockPos(0, 0, 0), Collections.emptySet());
        PriorityQueue<FlowFieldCalculator.QueueNode> calcQueue = new PriorityQueue<>();
        Map<BlockPos, Integer> nextCostMap = new HashMap<>();
        Map<BlockPos, FlowStep> nextInstructionMap = new HashMap<>();

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
        // new line points back to it or to anything it points to). BRIDGE (not TUNNEL/CARVED_STAIR)
        // deliberately: on untouched open-air terrain, isActionCompleted for TUNNEL/CARVED_STAIR
        // reads true (nothing to mine - "already clear"), which would collapse this fixture's own
        // "still locked" premise; BRIDGE/AIR_STAIR's completion check ("has something been placed
        // here") correctly reads false on default terrain.
        BlockPos distantEntry = new BlockPos(2, 50, 500);
        Map<BlockPos, FlowStep> unrelatedInstructions = Map.of(
                crossedCell, new FlowStep(crossedCell, PathAction.BRIDGE, new BlockPos(2, 50, 501)),
                distantEntry, new FlowStep(distantEntry, PathAction.WALK, crossedCell));
        SiegeProject unrelatedProject = new SiegeProject(unrelatedInstructions,
                List.of(new FlowStep(crossedCell, PathAction.BRIDGE, new BlockPos(2, 50, 501)),
                        new FlowStep(distantEntry, PathAction.WALK, crossedCell)),
                new BlockPos(2, 50, 501), distantEntry, 500, UUID.randomUUID());
        manager.addSharedConnectorProject(unrelatedProject);

        FlowFieldState state = new FlowFieldState(new BlockPos(0, 0, 0), Collections.emptySet());
        PriorityQueue<FlowFieldCalculator.QueueNode> calcQueue = new PriorityQueue<>();
        Map<BlockPos, Integer> nextCostMap = new HashMap<>();
        Map<BlockPos, FlowStep> nextInstructionMap = new HashMap<>();

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
    // PathStepEvaluator.isActionCompleted correctly reports its WALK action as already done - see
    // that class's own doc on the WALK case. entryPos is, by construction, on the far side of a gap
    // the ordinary Dijkstra flood cannot independently cross while the project's own interior cells
    // are still unbuilt/locked - it's excluded from getRemainingInstructions precisely because
    // nothing needs building there, but that must not mean nothing ROUTES there either, or a mob
    // standing at entryPos is left with no instruction at all and can never be told to walk onward
    // into the project's still-unbuilt interior.

    @Test
    void entryPosStaysRoutableOnceItsOwnWalkStepReadsCompleteButTheProjectsInteriorIsStillUnbuilt() {
        FakeTerrain terrain = new FakeTerrain();
        SiegeProjectManager manager = new SiegeProjectManager(new PathStepEvaluator());

        BlockPos hop1 = new BlockPos(0, 50, 1);   // interior AIR_STAIR step - still unbuilt
        BlockPos entryPos = new BlockPos(0, 49, 2); // far side of the gap - already genuinely walkable

        terrain.set(entryPos.below(), Blocks.STONE.defaultBlockState()); // real support under entryPos

        Map<BlockPos, FlowStep> instructions = Map.of(
                hop1, new FlowStep(hop1, PathAction.AIR_STAIR, new BlockPos(0, 50, 0)),
                entryPos, new FlowStep(entryPos, PathAction.WALK, hop1));
        SiegeProject project = new SiegeProject(instructions,
                List.of(new FlowStep(hop1, PathAction.AIR_STAIR, new BlockPos(0, 50, 0)), new FlowStep(entryPos, PathAction.WALK, hop1)),
                new BlockPos(0, 50, 0), entryPos, 4500, UUID.randomUUID());
        manager.addSharedConnectorProject(project);

        // Ground truth matching the doc's own WALK-completion reasoning: entryPos genuinely is
        // walkable right now, and hop1 genuinely is not yet built.
        PathStepEvaluator evaluator = new PathStepEvaluator();
        assertTrue(evaluator.isWalkableTerrain(terrain, entryPos), "setup sanity: entryPos must actually be walkable");
        assertFalse(evaluator.isActionCompleted(terrain, hop1, PathAction.AIR_STAIR),
                "setup sanity: hop1 must not yet be built, or this test proves nothing about the project staying active");

        PriorityQueue<FlowFieldCalculator.QueueNode> calcQueue = new PriorityQueue<>();
        Map<BlockPos, Integer> nextCostMap = new HashMap<>();
        Map<BlockPos, FlowStep> nextInstructionMap = new HashMap<>();

        // No core flood runs in this test - deliberately, to isolate injectActiveProjects' own
        // seeding from whatever the ordinary Dijkstra flood might separately contribute. If the
        // ordinary gap really can't be crossed without this project, the flood wouldn't reach
        // entryPos either, so this is the realistic no-alternative-route case.
        manager.injectActiveProjects(terrain, calcQueue, nextCostMap, nextInstructionMap);

        assertEquals(Set.of(hop1), manager.getLockedPositions(),
                "entryPos needs no construction and must not be locked - only hop1, the genuinely unbuilt step, should be");
        assertEquals(new FlowStep(hop1, PathAction.AIR_STAIR, new BlockPos(0, 50, 0)), nextInstructionMap.get(hop1),
                "setup sanity: hop1's own unbuilt instruction must still be seeded");
        assertEquals(new FlowStep(entryPos, PathAction.WALK, hop1), nextInstructionMap.get(entryPos),
                "a mob standing at entryPos must still be told to WALK onward to hop1 - entryPos being "
                        + "already-complete means nothing needs BUILDING there, not that nothing ROUTES there; "
                        + "with no instruction at all a mob here is stranded and can never reach hop1's real "
                        + "AIR_STAIR work");
    }

    /**
     * The entryPos fallback above must not assume every project keys its own entryPos in
     * `instructions` - a route-tree connector project (RegionGraph.registerConnector's outbound/
     * inbound orientation) deliberately does NOT: "the far region's own pass already covers it." A
     * naive {@code Map.putIfAbsent(entry, instructions.get(entry))} would silently insert a literal
     * null value for such a project, which would NPE the first time any caller (getNextSiegeNode,
     * FollowFlowFieldGoal) reads it back out.
     */
    @Test
    void entryPosFallbackNeverInsertsNullForAConnectorProjectThatDoesNotKeyItsOwnEntryPos() {
        FakeTerrain terrain = new FakeTerrain();
        SiegeProjectManager manager = new SiegeProjectManager(new PathStepEvaluator());

        BlockPos boundaryCell = new BlockPos(0, 50, 0);
        BlockPos entryPos = new BlockPos(0, 50, 5); // deliberately NOT a key below - mirrors RegionGraph's connector shape

        Map<BlockPos, FlowStep> instructions = Map.of(boundaryCell, new FlowStep(boundaryCell, PathAction.WALK, entryPos));
        SiegeProject connectorProject = new SiegeProject(instructions,
                List.of(new FlowStep(boundaryCell, PathAction.WALK, entryPos)),
                entryPos, entryPos, 500, boundaryCell, UUID.randomUUID());
        manager.addSharedConnectorProject(connectorProject);

        PriorityQueue<FlowFieldCalculator.QueueNode> calcQueue = new PriorityQueue<>();
        Map<BlockPos, Integer> nextCostMap = new HashMap<>();
        Map<BlockPos, FlowStep> nextInstructionMap = new HashMap<>();

        manager.injectActiveProjects(terrain, calcQueue, nextCostMap, nextInstructionMap);

        assertFalse(nextInstructionMap.containsKey(entryPos) && nextInstructionMap.get(entryPos) == null,
                "must never insert a literal null instruction for entryPos just because the project's "
                        + "own instructions map has no entry for it - that would NPE the first caller "
                        + "that reads it back out");
    }

    /**
     * A reactive candidate project's build order must proceed from entryPos (where rats ENTER the
     * project - see SiegeProject's own field doc) toward anchorPos (the target/already-reached
     * side), not the other way around. evaluateSingleLine's chained-hop trace walks FROM anchorPos
     * (the target side, reached first by Dijkstra flooding backward from the goal) OUT to entryPos
     * (the mob's side, discovered last): since {@code nextUnbuiltInstruction()} just returns build
     * order's first unbuilt entry, leaving that order unreversed would always prefer whichever
     * unbuilt step is nearest the TARGET, not nearest the mob. For a physical climbing structure a
     * mob approaches from entryPos, that's backwards: the step nearest entryPos must be built FIRST
     * or a mob standing on the ground can never reach the steps above it.
     *
     * <p>RegionGraph.registerConnector already gets this right for its own "towardA" direction
     * (mob entering from the far side): it reverses orderedSteps and re-anchors on that same far
     * endpoint. This test proves evaluateSingleLine's reactive candidate needs the identical
     * treatment, since entryPos plays the exact same "where mobs enter" role there.
     */
    @Test
    void reactiveCandidateBuildOrderProceedsFromEntryPosTowardAnchorNotTheOtherWayAround() {
        FakeTerrain terrain = new FakeTerrain();
        SiegeProjectManager manager = new SiegeProjectManager(new PathStepEvaluator());

        BlockPos anchor = new BlockPos(0, 52, 0);
        BlockPos hop1 = anchor.offset(1, -1, 0);   // nearest the TARGET/anchor - must be built SECOND
        BlockPos hop2 = hop1.offset(1, -1, 0);     // nearest the MOB/entryPos - must be built FIRST
        BlockPos entryPos = hop2.offset(1, -1, 0); // genuinely walkable - where the mob stands

        terrain.set(entryPos.below(), Blocks.STONE.defaultBlockState());

        FlowFieldState state = new FlowFieldState(new BlockPos(0, 0, 0), Collections.emptySet());
        PriorityQueue<FlowFieldCalculator.QueueNode> calcQueue = new PriorityQueue<>();
        Map<BlockPos, Integer> nextCostMap = new HashMap<>();
        Map<BlockPos, FlowStep> nextInstructionMap = new HashMap<>();

        manager.evaluateMacroProjects(terrain, anchor, state, 0, calcQueue, nextCostMap, nextInstructionMap);
        manager.finalizeCandidateProjects(nextCostMap, nextInstructionMap);

        SiegeProject project = manager.findProjectContaining(hop1).orElse(null);
        assertNotNull(project, "setup sanity: the diagonal (1,-1,0) line must have produced a surviving candidate covering hop1");
        assertEquals(Optional.of(project), manager.findProjectContaining(hop2),
                "setup sanity: hop1 and hop2 must belong to the SAME candidate line, or build order between them proves nothing");

        List<BlockPos> buildOrder = project.getBuildOrderPositions();
        int hop1Index = buildOrder.indexOf(hop1);
        int hop2Index = buildOrder.indexOf(hop2);
        assertTrue(hop1Index >= 0 && hop2Index >= 0, "setup sanity: both hops must appear in the build order");

        assertTrue(hop2Index < hop1Index,
                "hop2 (nearest entryPos, where the mob actually stands) must be built BEFORE hop1 "
                        + "(nearest the target) - got build order " + buildOrder + ", which builds the "
                        + "far/target-side step first, leaving the mob unable to reach it from the ground");

        assertFalse(buildOrder.contains(entryPos),
                "the reversed build order must not include the trace's own degenerate terminal WALK "
                        + "entry at entryPos - its position equals buildOrderAnchor (also entryPos), so "
                        + "leaving it as buildOrder.get(0) would make SiegeProject.tryWiden's own "
                        + "dirFrom/dirTo direction vector degenerate to zero, breaking auto-widening for "
                        + "this exact construction project shape");
    }

    /**
     * Reproduces the bug behind testParentRegionGetsRealInstructionsForSharedConnectorCells:
     * evaluateMacroProjects can fire on an anchor that is ALREADY a locked cell of an active
     * project - not just at that project's own entryPos (already guarded, see the self-collision
     * test above), but at any locked interior cell, including a region's own nexus/target position
     * (which injectActiveProjects/startCalculation seed onto calcQueue unconditionally, regardless
     * of lock state). If a genuinely-walkable cell sits one step away in some direction, the fresh
     * trace terminates in a single WALK hop, producing a "successful" candidate whose buildOrder,
     * after evaluateSingleLine's existing reversal + leading-WALK-drop, is EMPTY: a project with
     * nothing to build. This specific repro needs the PURE-VERTICAL fan direction (farSide is
     * directly above upstream) - it is the one direction PathStepEvaluator still offers a WALK
     * candidate for without also offering a construction candidate first (a walkable pure-vertical
     * neighbor short-circuits to WALK before the "no pure-vertical construction" skip is ever
     * reached), so it remains the sole way to reach this exact degenerate-single-WALK-hop shape now
     * that pure-vertical construction itself is gone. Confirmed live via siege_dump/GameTest log:
     * such a candidate's single instructions entry still gets putAll'd into nextInstructionMap with
     * no cost comparison, silently overwriting whatever the far cell already had (here, its own
     * genuinely-correct fallback instruction) with a brand-new one pointing straight back at the
     * anchor - which, paired with the anchor's own pre-existing instruction pointing forward at that
     * same far cell, forms a direct mutual 2-cycle. FlowFieldCalculator's cycle-breaker then drops
     * the (unlocked) far cell's entry entirely, leaving it with no instruction at all.
     *
     * <p>Unlike the other tests in this file, this one's MECHANISM (not just its final assertion) is
     * unverified until Task 14 restores a compiling state: it depends on {@code isWalkableTerrain
     * (farSide)} evaluating true via the trace-through in this task's own commit message (support
     * from {@code upstream} being STONE, open foot/head). If that trace is wrong, this test would
     * still pass, but VACUOUSLY - by aborting the line for some other reason rather than by
     * discarding a genuinely single-WALK-hop empty build order. A green run at Task 14 confirms the
     * assertion; it does not by itself confirm this test exercised the intended path - check the
     * mechanism, not just the result, when that batch run lands.
     */
    @Test
    void evaluateMacroProjectsMustNotOverwriteAGenuinelyWalkableCellWithADegenerateEmptyBuildOrderLine() {
        FakeTerrain terrain = new FakeTerrain();
        SiegeProjectManager manager = new SiegeProjectManager(new PathStepEvaluator());

        BlockPos upstream = new BlockPos(0, 64, 0); // locked cell of an active project; a mob stands ON it
        BlockPos farSide = new BlockPos(0, 65, 0);   // directly above upstream - genuinely walkable from there

        // upstream is solid (a mob stands ON TOP of it, at farSide, not inside it) and is itself
        // supported - matches the real bug's geometry (a connector's own second-to-last cell, one
        // step below its genuinely-open far/exit side).
        terrain.set(upstream.below(), Blocks.STONE.defaultBlockState());
        terrain.set(upstream, Blocks.STONE.defaultBlockState());

        // An active project already correctly instructs upstream -> farSide (mirrors a connector's
        // own outboundInstructions entry). upstream is locked via this project - its own WALK action
        // reads incomplete because upstream itself isn't a standable cell (solid), matching
        // isActionCompleted(WALK, upstream) == isWalkableTerrain(upstream) == false.
        BlockPos entryPos = new BlockPos(0, 63, 0);
        Map<BlockPos, FlowStep> instructions = Map.of(
                upstream, new FlowStep(upstream, PathAction.WALK, farSide),
                entryPos, new FlowStep(entryPos, PathAction.WALK, upstream));
        SiegeProject activeProject = new SiegeProject(instructions,
                List.of(new FlowStep(upstream, PathAction.WALK, farSide), new FlowStep(entryPos, PathAction.WALK, upstream)),
                farSide, entryPos, 500, UUID.randomUUID());
        manager.addSharedConnectorProject(activeProject);

        FlowFieldState state = new FlowFieldState(new BlockPos(0, 0, 0), Collections.emptySet());
        PriorityQueue<FlowFieldCalculator.QueueNode> calcQueue = new PriorityQueue<>();
        Map<BlockPos, Integer> nextCostMap = new HashMap<>();
        Map<BlockPos, FlowStep> nextInstructionMap = new HashMap<>();

        manager.injectActiveProjects(terrain, calcQueue, nextCostMap, nextInstructionMap);
        assertTrue(manager.getLockedPositions().contains(upstream), "setup sanity: upstream must be locked");

        // farSide's own genuinely-correct existing instruction (e.g. a trivial self-referencing
        // fallback, as a region's own target/exitPos gets from injectActiveProjects/
        // startCalculation) - must survive evaluateMacroProjects firing AT upstream. Deliberately no
        // matching nextCostMap entry for farSide, mirroring the live bug's own repro.
        FlowStep farSideExistingInstruction = new FlowStep(farSide, PathAction.WALK, farSide);
        nextInstructionMap.put(farSide, farSideExistingInstruction);

        // Mirrors a region's own target/nexus position being seeded onto calcQueue unconditionally
        // (FlowFieldCalculator.startCalculation) and then popped by the ordinary Dijkstra loop,
        // re-firing evaluateMacroProjects on it even though it's already a locked cell.
        manager.evaluateMacroProjects(terrain, upstream, state, 500, calcQueue, nextCostMap, nextInstructionMap);

        assertEquals(farSideExistingInstruction, nextInstructionMap.get(farSide),
                "a macro-line trace that terminates in a single WALK hop (nothing to build) must not "
                        + "overwrite farSide's own existing instruction - doing so here replaced a correct "
                        + "instruction with one pointing straight back at `upstream`, which combined with "
                        + "upstream's own pre-existing forward-pointing instruction forms a direct mutual "
                        + "2-cycle that the cycle-breaker then resolves by deleting farSide's entry entirely");
    }
}
