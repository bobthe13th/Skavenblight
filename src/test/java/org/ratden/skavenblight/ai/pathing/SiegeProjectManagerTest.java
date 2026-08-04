package org.ratden.skavenblight.ai.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

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

        // Only `upstream` is locked here, not `anchor`: getRemainingInstructions() now checks
        // completion at each entry's REAL position (the predecessor-position fix), and anchor's own
        // WALK step reads as already-satisfied (anchor is genuinely walkable terrain - a walkable
        // scaffold block, per its own setup above), so it's correctly excluded as nothing-left-to-do.
        // upstream's BUILD_PILLAR is still genuinely unbuilt (furtherBack, its real position, is
        // untouched terrain), so it stays locked - that's the one this test's real guard behavior
        // below depends on; anchor no longer being locked doesn't affect it.
        assertEquals(Set.of(upstream), manager.getLockedPositions(),
                "setup sanity: only upstream (still genuinely unbuilt) should be locked before evaluateMacroProjects runs");

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

    // --- Eviction reachability (2026-08-04, SiegeProject.isCompleted()/getRemainingInstructions()
    // predecessor-position fix): confirms fixing isCompleted() to actually detect completion - so
    // a fully-built project is finally evicted from activeProjects instead of staying "active"
    // forever - doesn't just trade permanent staleness for a transient reachability gap. Once
    // evicted, injectActiveProjects stops writing this project's cells into nextInstructionMap/
    // lockedPositions AND stops seeding its getExitPos() fallback (see that method's own doc - it's
    // deliberately excluded from `instructions` for exactly this reason). A cell that's only ever
    // reachable via the evicted project's own instructions, with no ordinary terrain-driven WALK
    // path to it, would go dark the instant eviction started working - the same "no blue arrow"
    // symptom this whole investigation began with, produced from the opposite direction. Runs a
    // full FlowFieldCalculator pass (not just injectActiveProjects in isolation) over terrain where
    // the project's own cell is ALSO genuinely, independently walkable - proving the fix is safe
    // because the ordinary Dijkstra flood picks the cell up on its own merits, not because eviction
    // was skipped.
    @Test
    void fullyBuiltProjectIsEvictedAndItsCellStaysReachableViaOrdinaryWalkPropagation() {
        FakeTerrain terrain = new FakeTerrain();
        BlockPos target = new BlockPos(0, 64, 0);
        BlockPos entryPos = new BlockPos(0, 64, 2); // the (formerly locked) cell under test

        // A flat, fully-supported 3x6 plaza (x: -1..1, z: -1..4) around the target-to-entryPos
        // line.
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 4; z++) {
                terrain.set(new BlockPos(x, 63, z), Blocks.STONE.defaultBlockState());
            }
        }

        // A real, bounded territory - NOT Collections.emptySet() (which FlowFieldState.isOutOfBounds
        // treats as unbounded "global scope"). Without a bound, every plaza-edge cell has fewer than
        // 4 valid orthogonal steps (its neighbors past the plaza are unset/unsupported open air), so
        // hitObstacle fires there and evaluateMacroProjects happily bridges (BUILD_BRIDGE needs no
        // support) outward through that infinite open air, forever, one 32-block-capped line at a
        // time chaining into the next - confirmed by hand: an unbounded version of this test pegged
        // a CPU core for 10+ minutes without ever draining calcQueue. A real territory bound makes
        // every cell past this margin genuinely out-of-bounds, so the flood terminates normally.
        Set<ChunkPos> territoryChunks = new HashSet<>();
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 6; z++) {
                territoryChunks.add(new ChunkPos(new BlockPos(x, 64, z)));
            }
        }

        SiegeProjectManager manager = new SiegeProjectManager(new TerrainEvaluator());
        FlowFieldCalculator calculator = new FlowFieldCalculator(new TerrainEvaluator(), manager, new CalculationThrottler());
        FlowFieldState state = new FlowFieldState(target, territoryChunks);

        // A trivial one-cell "project" whose only instruction is a WALK entry at entryPos, exactly
        // matching how a real reactive SiegeProject's trace always terminates (SiegeLineTracer.trace
        // stops the instant isWalkableTerrain first becomes true) - already fully satisfied by the
        // plaza terrain above, so isCompleted() must now report true once it checks the real
        // position instead of the stored predecessor.
        BlockPos predecessor = new BlockPos(0, 64, 1);
        Map<BlockPos, SiegeNode> instructions = Map.of(entryPos, new SiegeNode(predecessor, SiegeNode.SiegeAction.WALK));
        SiegeProject project = new SiegeProject(instructions,
                List.of(new SiegeNode(entryPos, SiegeNode.SiegeAction.WALK)), predecessor, entryPos, 500);
        manager.addSharedConnectorProject(project);

        calculator.calculateFully(terrain, state, false);

        // Not getActiveProjectCount() == 0: the bounded-but-still-open plaza's own edges
        // legitimately trigger hitObstacle and produce unrelated incidental candidate projects of
        // their own (BUILD_BRIDGE chains into open air within territoryChunks but outside the
        // supported footprint) - real noise from this test's terrain shape, not a sign of what
        // we're actually checking. getLockedPositions() instead reflects locking as it stood right
        // after injectActiveProjects ran at the START of this pass (before any of that incidental
        // discovery), which is exactly "was MY project's cell evicted" - and stays a valid check
        // regardless of how many other projects get promoted later in the same pass, since any of
        // THEM claiming entryPos as their own remaining WALK cell would hit the exact same
        // real-terrain completion check and also read it as already-satisfied.
        assertFalse(manager.getLockedPositions().contains(entryPos),
                "setup sanity: isCompleted() must now evict this fully-built project - if this "
                        + "fails, the reachability assertion below proves nothing (the project's own "
                        + "getRemainingInstructions()/entry re-seeding could be masking a real gap)");

        SiegeNode instructionAtFormerlyLockedCell = state.getInstructionMap().get(entryPos);
        assertNotNull(instructionAtFormerlyLockedCell,
                "entryPos must still be covered by the ordinary Dijkstra flood after eviction - "
                        + "the project's own instruction AND its getExitPos() fallback both disappear "
                        + "once evicted, so genuine terrain-driven reachability is the only thing left "
                        + "that can cover this cell, or a rat here would fall back to wilderness");
        assertEquals(SiegeNode.SiegeAction.WALK, instructionAtFormerlyLockedCell.action());
    }
}
