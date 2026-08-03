# Project-Scoped Clanrat Construction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace clanrat construction's per-block claiming with project-scoped worker
registration, so a `SiegeProject`'s build speed scales with how many nearby rats are registered
to it (capped, with staircases/bridges auto-widening under demand), instead of one rat per exact
claimed block at a fixed rate.

**Architecture:** `SiegeProject` gains a deterministic, geometry-derived build order (sourced from
`SiegeLineTracer`'s existing `orderedSteps`, not from its own backpointer-oriented `instructions`
map — see Task 1's Background), a worker registry, and an accumulated-work counter that places
instructions in order as work crosses each one's cost. A new `AbstractSiegeProjectGoal` base
replaces per-block claiming with project registration for `BuildFlowFieldGoal`. `WidenStairsGoal`
is deleted; a `SiegeProject`'s own width grows with worker demand instead.

**Tech Stack:** Java 21, NeoForge (Minecraft 1.21.x), JUnit 5 (plain unit tests under
`src/test/java`) + NeoForge `@GameTest` (world-backed integration tests under
`src/main/java/.../gametest`, since there's no JUnit-in-Minecraft harness in this project).

## Global Constraints

- Every new/changed public method must compile against the exact signatures used elsewhere in this
  plan — cross-check Task N's "Produces" against the task that "Consumes" it before considering a
  task done.
- No behavior change to `SmartBreachGoal`, `WidenStairsGoal`'s deletion aside, `SpiralSapperGoal`,
  or `DeployClimbableGoal` — these keep their existing per-block `flowField.tryClaimTarget` calls
  untouched (see the design doc's Scope section for why).
- `SiegeLineTracer`, `TerrainEvaluator`, `FlowFieldCalculator`, and `RegionFlowField.getNextSiegeNode`
  are NOT modified by this plan — every new mechanism is additive, built from data these already
  produce (`SiegeLineTracer.TraceResult.orderedSteps()`).
- Design reference: `docs/superpowers/specs/2026-08-03-project-scoped-clanrat-construction-design.md`.

---

## Task 1: `SiegeProject` gains a geometry-ordered build plan

**Files:**
- Modify: `src/main/java/org/ratden/skavenblight/ai/pathing/SiegeProject.java`
- Test: `src/test/java/org/ratden/skavenblight/ai/pathing/SiegeProjectTest.java` (new file)

**Background (why `orderedSteps`, not `instructions`):** `SiegeProject.instructions` pairs each
key position with a `SiegeNode` whose `.pos()` points to the position ONE STEP BEFORE it in trace
order (confirmed by `RegionGraph.outboundInstructions`'s own doc, which explicitly avoids reusing
`SiegeLineTracer`'s `instructions` map for this exact reason: "that one pairs a position's action
with the position one step BEFORE it... a mob there asks a breach goal for nothing and stalls").
`SiegeLineTracer.TraceResult.orderedSteps()` has no such offset — each entry's `.pos()` is the real
position the action applies to, in trace order, which is exactly what `RegionGraph` already builds
its own `outboundInstructions`/`inboundInstructions` maps from instead. This task does the same:
build a new, separate, correctly-paired sequence from `orderedSteps`, leaving `instructions` (and
everything that reads it: `isCompleted`, `getRemainingInstructions`, `survivedMapOverwrite`,
`SiegeProjectManager.injectActiveProjects`) completely untouched.

**Interfaces:**
- Produces: `SiegeProject.PlannedStep(BlockPos pos, SiegeNode.SiegeAction action, Direction
  facing)` (package-private record); `static List<PlannedStep> SiegeProject.planSteps(List<SiegeNode>
  orderedSteps, BlockPos anchor)`; `static int SiegeProject.countCompletable(List<Integer>
  orderedRemainingCosts, double availableWork)`; `static int
  SiegeProject.effectiveCapFor(SiegeNode.SiegeAction action, int width, int maxProjectWorkers, int
  workersPerWidenStep)`; new constructor params `List<SiegeNode> orderedSteps, BlockPos
  buildOrderAnchor` inserted after `instructions`.
- Consumes: nothing new (uses only `net.minecraft.core.BlockPos`/`Direction` and the existing
  `SiegeNode`).

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/org/ratden/skavenblight/ai/pathing/SiegeProjectTest.java`:

```java
package org.ratden.skavenblight.ai.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SiegeProjectTest {

    @Test
    void planStepsComputesFacingFromAnchorForFirstStep() {
        BlockPos anchor = new BlockPos(0, 64, 0);
        List<SiegeNode> orderedSteps = List.of(
                new SiegeNode(new BlockPos(1, 65, 0), SiegeNode.SiegeAction.BUILD_STAIR)
        );

        List<SiegeProject.PlannedStep> planned = SiegeProject.planSteps(orderedSteps, anchor);

        assertEquals(1, planned.size());
        assertEquals(new BlockPos(1, 65, 0), planned.get(0).pos());
        assertEquals(SiegeNode.SiegeAction.BUILD_STAIR, planned.get(0).action());
        assertEquals(Direction.EAST, planned.get(0).facing());
    }

    @Test
    void planStepsComputesFacingFromPredecessorForLaterSteps() {
        BlockPos anchor = new BlockPos(0, 64, 0);
        List<SiegeNode> orderedSteps = List.of(
                new SiegeNode(new BlockPos(1, 65, 0), SiegeNode.SiegeAction.BUILD_STAIR),
                new SiegeNode(new BlockPos(1, 66, -1), SiegeNode.SiegeAction.BUILD_STAIR)
        );

        List<SiegeProject.PlannedStep> planned = SiegeProject.planSteps(orderedSteps, anchor);

        assertEquals(Direction.EAST, planned.get(0).facing());
        // step 1 moves from (1,65,0) to (1,66,-1): dz=-1, dx=0 -> NORTH
        assertEquals(Direction.NORTH, planned.get(1).facing());
    }

    @Test
    void planStepsPreservesPosAndActionUnchanged() {
        BlockPos anchor = new BlockPos(5, 5, 5);
        SiegeNode step = new SiegeNode(new BlockPos(5, 4, 5), SiegeNode.SiegeAction.BUILD_PILLAR);

        List<SiegeProject.PlannedStep> planned = SiegeProject.planSteps(List.of(step), anchor);

        assertEquals(step.pos(), planned.get(0).pos());
        assertEquals(step.action(), planned.get(0).action());
    }

    @Test
    void countCompletableCountsWhileWorkCoversCost() {
        assertEquals(0, SiegeProject.countCompletable(List.of(150, 150, 150), 100.0));
        assertEquals(1, SiegeProject.countCompletable(List.of(150, 150, 150), 150.0));
        assertEquals(2, SiegeProject.countCompletable(List.of(150, 150, 150), 300.0));
        assertEquals(3, SiegeProject.countCompletable(List.of(150, 150, 150), 999.0));
    }

    @Test
    void countCompletableStopsAtFirstUncoverableCost() {
        // A large second cost blocks the third even though total work would otherwise cover it.
        assertEquals(1, SiegeProject.countCompletable(List.of(100, 5000, 100), 250.0));
    }

    @Test
    void countCompletableHandlesEmptyList() {
        assertEquals(0, SiegeProject.countCompletable(List.of(), 999.0));
    }

    @Test
    void effectiveCapForBuildStairScalesWithWidth() {
        assertEquals(10, SiegeProject.effectiveCapFor(SiegeNode.SiegeAction.BUILD_STAIR, 1, 4, 10));
        assertEquals(30, SiegeProject.effectiveCapFor(SiegeNode.SiegeAction.BUILD_STAIR, 3, 4, 10));
    }

    @Test
    void effectiveCapForBuildBridgeScalesWithWidth() {
        assertEquals(20, SiegeProject.effectiveCapFor(SiegeNode.SiegeAction.BUILD_BRIDGE, 2, 4, 10));
    }

    @Test
    void effectiveCapForNonWidenableActionIgnoresWidth() {
        assertEquals(4, SiegeProject.effectiveCapFor(SiegeNode.SiegeAction.BUILD_PILLAR, 3, 4, 10));
        assertEquals(4, SiegeProject.effectiveCapFor(SiegeNode.SiegeAction.BUILD_LANDING, 1, 4, 10));
    }

    @Test
    void constructorAcceptsOrderedStepsAndAnchorWithoutError() {
        BlockPos anchor = new BlockPos(0, 64, 0);
        BlockPos entryPos = new BlockPos(1, 65, 0);
        List<SiegeNode> orderedSteps = List.of(new SiegeNode(entryPos, SiegeNode.SiegeAction.BUILD_STAIR));
        Map<BlockPos, SiegeNode> instructions = Map.of(entryPos, new SiegeNode(anchor, SiegeNode.SiegeAction.BUILD_STAIR));

        SiegeProject project = new SiegeProject(instructions, orderedSteps, anchor, entryPos, 500);

        assertEquals(entryPos, project.getEntryPos());
        assertEquals(instructions, project.getInstructions());
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "org.ratden.skavenblight.ai.pathing.SiegeProjectTest"`
Expected: compile failure — `SiegeProject.PlannedStep`, `planSteps`, `countCompletable`,
`effectiveCapFor` don't exist yet, and the 5-arg constructor used in the last test doesn't exist.

- [ ] **Step 3: Implement `PlannedStep`, the pure static helpers, and the constructor change**

Read the current `src/main/java/org/ratden/skavenblight/ai/pathing/SiegeProject.java` first (it's
short, ~97 lines) so the edit below is a precise diff, not a guess. Apply these changes:

Add imports at the top (alongside the existing `BlockPos`/`Collections`/`HashMap`/`Map` imports):

```java
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.List;
```

Add the new field, alongside the existing `instructions`/`entryPos`/`expectedEntryCost`/`exitPos`
fields:

```java
    // Geometry-ordered build sequence, sourced from SiegeLineTracer.TraceResult.orderedSteps() -
    // NOT derived from `instructions` above, whose values point one step BACKWARD in trace order
    // (see this class's own Javadoc / the design doc's Background for why that convention is
    // wrong for "what to build, in what order, facing which way", and why RegionGraph's own
    // outboundInstructions/inboundInstructions already avoid it for the identical reason).
    private final List<PlannedStep> buildOrder;
```

Change both constructors to accept `orderedSteps`/`buildOrderAnchor` (inserted right after
`instructions`, before `entryPos`):

```java
    public SiegeProject(Map<BlockPos, SiegeNode> instructions, List<SiegeNode> orderedSteps, BlockPos buildOrderAnchor,
                         BlockPos entryPos, int expectedEntryCost) {
        this(instructions, orderedSteps, buildOrderAnchor, entryPos, expectedEntryCost, null);
    }

    public SiegeProject(Map<BlockPos, SiegeNode> instructions, List<SiegeNode> orderedSteps, BlockPos buildOrderAnchor,
                         BlockPos entryPos, int expectedEntryCost, BlockPos exitPos) {
        this.instructions = new HashMap<>(instructions);
        this.buildOrder = planSteps(orderedSteps, buildOrderAnchor);
        this.entryPos = entryPos;
        this.expectedEntryCost = expectedEntryCost;
        this.exitPos = exitPos;
    }
```

Add the new type and pure static helpers at the bottom of the class, before the closing brace:

```java
    /**
     * One position this project still needs to act on, in build order: the real position the
     * action applies to (unlike `instructions`' values - see this class's own background doc),
     * the action, and the facing derived purely from trace geometry (predecessor step -> this
     * step), never from any mob's position - so placement can be centralized and driven by
     * whichever/however many workers are registered, not tied to whoever happens to execute it.
     */
    record PlannedStep(BlockPos pos, SiegeNode.SiegeAction action, Direction facing) {}

    /** Package-private + static for direct unit testing, mirroring FlowFieldCalculator's own
     * detectMutualCyclePositions/pickCyclePositionToDrop pattern for pure logic extracted out of
     * a Minecraft-coupled class. */
    static List<PlannedStep> planSteps(List<SiegeNode> orderedSteps, BlockPos anchor) {
        List<PlannedStep> planned = new ArrayList<>(orderedSteps.size());
        BlockPos previous = anchor;
        for (SiegeNode step : orderedSteps) {
            planned.add(new PlannedStep(step.pos(), step.action(), approachFacing(previous, step.pos())));
            previous = step.pos();
        }
        return planned;
    }

    /** Same dx/dz-comparison logic as BuildFlowFieldGoal.computeApproachFacing, fed geometry
     * instead of a mob's live position. */
    private static Direction approachFacing(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        if (Math.abs(dx) > Math.abs(dz)) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        } else if (dz != 0) {
            return dz > 0 ? Direction.SOUTH : Direction.NORTH;
        }
        return Direction.NORTH; // pure-vertical step (BUILD_PILLAR/SPIRAL/LADDER) - facing unused for these.
    }

    /** How many of `orderedRemainingCosts` (in build order) `availableWork` fully covers, stopping
     * at the first one it can't - a large mid-sequence cost blocks everything after it even if the
     * total would otherwise suffice, matching "build in order" semantics. */
    static int countCompletable(List<Integer> orderedRemainingCosts, double availableWork) {
        int completed = 0;
        double remaining = availableWork;
        for (int cost : orderedRemainingCosts) {
            if (remaining < cost) break;
            remaining -= cost;
            completed++;
        }
        return completed;
    }

    /** BUILD_STAIR/BUILD_BRIDGE scale their worker cap with `width` (see the auto-widening design);
     * every other action type gets a flat cap regardless of `width`. */
    static int effectiveCapFor(SiegeNode.SiegeAction action, int width, int maxProjectWorkers, int workersPerWidenStep) {
        boolean widenable = action == SiegeNode.SiegeAction.BUILD_STAIR || action == SiegeNode.SiegeAction.BUILD_BRIDGE;
        return widenable ? width * workersPerWidenStep : maxProjectWorkers;
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests "org.ratden.skavenblight.ai.pathing.SiegeProjectTest"`
Expected: PASS (all 10 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/ai/pathing/SiegeProject.java src/test/java/org/ratden/skavenblight/ai/pathing/SiegeProjectTest.java
git commit -m "feat(pathing): give SiegeProject a geometry-ordered build plan"
```

---

## Task 2: Thread the new constructor params through every production call site

Task 1 changed `SiegeProject`'s constructor signature. Three call sites now fail to compile.

**Files:**
- Modify: `src/main/java/org/ratden/skavenblight/ai/pathing/SiegeProjectManager.java:271`
- Modify: `src/main/java/org/ratden/skavenblight/ai/pathing/region/RegionGraph.java:154-155`

**Interfaces:**
- Consumes: `SiegeProject`'s new constructor from Task 1.
- Produces: nothing new — this task only restores compilation with the correct ordered-step data
  wired through.

- [ ] **Step 1: Fix `SiegeProjectManager.evaluateSingleLine`**

In `SiegeProjectManager.java`, `evaluateSingleLine` already has `anchorPos` and `result` (the
`SiegeLineTracer.TraceResult`) in scope. Change line 271 from:

```java
        candidateProjects.add(new SiegeProject(result.instructions(), endPos, totalCost));
```

to:

```java
        candidateProjects.add(new SiegeProject(result.instructions(), result.orderedSteps(), anchorPos, endPos, totalCost));
```

- [ ] **Step 2: Fix `RegionGraph.registerConnector`'s two connector projects**

In `RegionGraph.java`, `registerConnector` receives `boundaryCell`, `endPos`, and `orderedSteps`
(the full, possibly multi-hop-chained list already accumulated by `tryTrace`). `towardA` crosses
INWARD (endPos -> boundaryCell), so its build order must run in that same direction: the reverse
of `orderedSteps`. `towardB` crosses OUTWARD (boundaryCell -> endPos), matching `orderedSteps` as
recorded. Change lines 154-155 from:

```java
        SiegeProject towardA = new SiegeProject(inboundInstructions(boundaryCell, orderedSteps), endPos, cost, boundaryCell);
        SiegeProject towardB = new SiegeProject(outboundInstructions(boundaryCell, orderedSteps), boundaryCell, cost, endPos);
```

to:

```java
        List<SiegeNode> reversedSteps = new ArrayList<>(orderedSteps);
        Collections.reverse(reversedSteps);

        SiegeProject towardA = new SiegeProject(inboundInstructions(boundaryCell, orderedSteps), reversedSteps, endPos,
                endPos, cost, boundaryCell);
        SiegeProject towardB = new SiegeProject(outboundInstructions(boundaryCell, orderedSteps), orderedSteps, boundaryCell,
                boundaryCell, cost, endPos);
```

Add `java.util.Collections` to `RegionGraph.java`'s imports if not already present (check the
existing import block first — `ArrayList`/`List` are almost certainly already imported given
`combinedOrderedSteps` uses them at line 104).

- [ ] **Step 3: Compile and run the existing region/pathing test suite**

Run: `./gradlew compileJava compileTestJava`
Expected: BUILD SUCCESSFUL — this task is pure plumbing, no new behavior, so no test assertions
should change. Then run:

Run: `./gradlew test`
Expected: all existing JUnit tests (`FlowFieldCalculatorTest`, `SiegeProjectTest`) still PASS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/ai/pathing/SiegeProjectManager.java src/main/java/org/ratden/skavenblight/ai/pathing/region/RegionGraph.java
git commit -m "fix(pathing): thread geometry-ordered build steps through every SiegeProject call site"
```

---

## Task 3: `SiegeProject` worker registration

**Files:**
- Modify: `src/main/java/org/ratden/skavenblight/ai/pathing/SiegeProject.java`
- Modify: `src/test/java/org/ratden/skavenblight/ai/pathing/SiegeProjectTest.java`

**Interfaces:**
- Consumes: `PlannedStep`/`countCompletable`/`effectiveCapFor` from Task 1; `TerrainEvaluator`,
  `TerrainAccess` (existing).
- Produces: `SiegeProject.nextUnbuiltInstruction(TerrainAccess, TerrainEvaluator):
  Optional<PlannedStep>`; `SiegeProject.tryRegisterWorker(Mob, TerrainAccess, TerrainEvaluator,
  double workRadius, int maxProjectWorkers, int workersPerWidenStep): boolean` (6 params — matches
  Step 3's own code block below; later tasks call it with exactly this shape);
  `SiegeProject.unregisterWorker(Mob): void`; `SiegeProject.isAtCapacity(): boolean` (cheap,
  terrain-free, reads a cached cap updated by `tryRegisterWorker`/Task 4's `tick()`).

A test-only fake `TerrainAccess` is needed since these tests run outside a real `ServerLevel`.

- [ ] **Step 1: Write the failing tests**

Add to `SiegeProjectTest.java` (new imports: `net.minecraft.world.level.block.state.BlockState`,
`net.minecraft.world.level.block.Blocks`, `net.minecraft.world.entity.Mob`, and Mockito or a hand
-rolled fake — this codebase has no Mockito dependency per `build.gradle`, so hand-roll a minimal
fake `TerrainAccess` and use a real-but-unspawned `Mob` is not possible without a `Level`, so tests
that need a "mob" use only its `blockPosition()` — model it with a tiny local test double instead
of a real entity):

```java
    /** Minimal fake so pure registration/cap logic can run with no real ServerLevel. Every
     * position not explicitly set reports as open air, i.e. every action is "not yet built". */
    private static class FakeTerrain implements TerrainAccess {
        private final Map<BlockPos, BlockState> states = new java.util.HashMap<>();

        void set(BlockPos pos, BlockState state) { states.put(pos, state); }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return states.getOrDefault(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        }

        @Override
        public boolean isLoaded(BlockPos pos) { return true; }

        @Override
        public boolean isOutsideBuildHeight(BlockPos pos) { return false; }

        @Override
        public boolean isSolidRender(BlockPos pos) { return getBlockState(pos).blocksMotion(); }

        @Override
        public float getDestroySpeed(BlockPos pos) { return 1.0F; }
    }

    private static SiegeProject freshSingleStepProject(BlockPos anchor, BlockPos target, SiegeNode.SiegeAction action) {
        List<SiegeNode> orderedSteps = List.of(new SiegeNode(target, action));
        Map<BlockPos, SiegeNode> instructions = Map.of(target, new SiegeNode(anchor, action));
        return new SiegeProject(instructions, orderedSteps, anchor, target, 500);
    }

    @Test
    void nextUnbuiltInstructionReturnsFirstIncompleteStep() {
        BlockPos anchor = new BlockPos(0, 64, 0);
        BlockPos target = new BlockPos(1, 64, 0);
        SiegeProject project = freshSingleStepProject(anchor, target, SiegeNode.SiegeAction.BUILD_BRIDGE);
        TerrainEvaluator evaluator = new TerrainEvaluator();
        FakeTerrain terrain = new FakeTerrain();

        assertTrue(project.nextUnbuiltInstruction(terrain, evaluator).isPresent());
        assertEquals(target, project.nextUnbuiltInstruction(terrain, evaluator).get().pos());
    }

    @Test
    void nextUnbuiltInstructionEmptyOnceBuilt() {
        BlockPos anchor = new BlockPos(0, 64, 0);
        BlockPos target = new BlockPos(1, 64, 0);
        SiegeProject project = freshSingleStepProject(anchor, target, SiegeNode.SiegeAction.BUILD_BRIDGE);
        TerrainEvaluator evaluator = new TerrainEvaluator();
        FakeTerrain terrain = new FakeTerrain();
        terrain.set(target, net.minecraft.world.level.block.Blocks.COBBLESTONE.defaultBlockState());

        assertTrue(project.nextUnbuiltInstruction(terrain, evaluator).isEmpty());
    }

    @Test
    void nextUnbuiltInstructionStopsAtAnIncompleteMineStep() {
        BlockPos anchor = new BlockPos(0, 64, 0);
        BlockPos minePos = new BlockPos(1, 64, 0);
        BlockPos buildPos = new BlockPos(2, 64, 0);
        List<SiegeNode> orderedSteps = List.of(
                new SiegeNode(minePos, SiegeNode.SiegeAction.MINE),
                new SiegeNode(buildPos, SiegeNode.SiegeAction.BUILD_BRIDGE)
        );
        Map<BlockPos, SiegeNode> instructions = Map.of(
                minePos, new SiegeNode(anchor, SiegeNode.SiegeAction.MINE),
                buildPos, new SiegeNode(minePos, SiegeNode.SiegeAction.BUILD_BRIDGE)
        );
        SiegeProject project = new SiegeProject(instructions, orderedSteps, anchor, buildPos, 500);
        TerrainEvaluator evaluator = new TerrainEvaluator();
        FakeTerrain terrain = new FakeTerrain();
        terrain.set(minePos, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState()); // not yet mined

        // Blocked on the still-solid MINE step (handled by the old per-rat SmartBreachGoal path,
        // untouched by this overhaul) - must not skip ahead to the BUILD_BRIDGE step past it.
        assertTrue(project.nextUnbuiltInstruction(terrain, evaluator).isEmpty());
    }

    @Test
    void isAtCapacityFalseBeforeAnyRegistration() {
        BlockPos anchor = new BlockPos(0, 64, 0);
        BlockPos target = new BlockPos(1, 64, 0);
        SiegeProject project = freshSingleStepProject(anchor, target, SiegeNode.SiegeAction.BUILD_PILLAR);

        assertFalse(project.isAtCapacity());
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "org.ratden.skavenblight.ai.pathing.SiegeProjectTest"`
Expected: compile failure — `nextUnbuiltInstruction`/`isAtCapacity` don't exist yet.

- [ ] **Step 3: Implement worker registration**

Add imports to `SiegeProject.java`: `net.minecraft.world.entity.Mob`, `java.util.HashSet`,
`java.util.Optional`, `java.util.Set`.

Add fields:

```java
    private final Set<Mob> workers = new HashSet<>();
    // Updated by nextUnbuiltInstruction()'s callers (tryRegisterWorker, Task 4's tick()) each time
    // they have real terrain access; isAtCapacity() reads this cheaply for callers (AwaitFormationGoal)
    // that don't want to thread a TerrainAccess/TerrainEvaluator through just to ask "is this full".
    private int cachedEffectiveCap = Integer.MAX_VALUE;
    private int width = 1;
```

Add methods:

```java
    /** The first not-yet-built step in build order, or empty if the project is fully built OR
     * currently blocked on an incomplete MINE step (handled entirely by the old per-rat
     * SmartBreachGoal/flowField.tryClaimTarget path - out of scope for this mechanism; skipping
     * PAST an unmined obstacle to a build step beyond it would be physically wrong, since that
     * later step may depend on the obstacle already being cleared). */
    public Optional<PlannedStep> nextUnbuiltInstruction(TerrainAccess terrain, TerrainEvaluator evaluator) {
        for (PlannedStep step : buildOrder) {
            if (step.action() == SiegeNode.SiegeAction.WALK || step.action() == SiegeNode.SiegeAction.LEAP) continue;
            if (step.action() == SiegeNode.SiegeAction.MINE) return Optional.empty();
            if (!evaluator.isActionCompleted(terrain, new SiegeNode(step.pos(), step.action()))) return Optional.of(step);
        }
        return Optional.empty();
    }

    /** Registers `mob` as a worker if there's real build work nearby (within `workRadius` of the
     * next unbuilt step) and the project isn't already at capacity - see effectiveCapFor for how
     * capacity scales with width for BUILD_STAIR/BUILD_BRIDGE. Idempotent: re-registering an
     * already-registered mob succeeds trivially. Widening on a rejected registration is added in
     * a later task; for now a full project simply refuses. */
    public boolean tryRegisterWorker(Mob mob, TerrainAccess terrain, TerrainEvaluator evaluator, double workRadius,
                                      int maxProjectWorkers, int workersPerWidenStep) {
        Optional<PlannedStep> next = nextUnbuiltInstruction(terrain, evaluator);
        if (next.isEmpty()) return false;
        PlannedStep step = next.get();
        if (!mob.blockPosition().closerThan(step.pos(), workRadius)) return false;

        this.cachedEffectiveCap = effectiveCapFor(step.action(), this.width, maxProjectWorkers, workersPerWidenStep);
        if (workers.contains(mob)) return true;
        if (workers.size() >= this.cachedEffectiveCap) return false;

        workers.add(mob);
        return true;
    }

    public void unregisterWorker(Mob mob) {
        workers.remove(mob);
    }

    /** Cheap, terrain-free capacity check for callers (AwaitFormationGoal) that just need "is
     * there room here right now" without re-deriving the next unbuilt step - reads whatever
     * tryRegisterWorker/Task 4's tick() last computed, so it can lag by up to one tick. */
    public boolean isAtCapacity() {
        return workers.size() >= this.cachedEffectiveCap;
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests "org.ratden.skavenblight.ai.pathing.SiegeProjectTest"`
Expected: PASS (all tests, including the 4 new ones).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/ai/pathing/SiegeProject.java src/test/java/org/ratden/skavenblight/ai/pathing/SiegeProjectTest.java
git commit -m "feat(pathing): add worker registration to SiegeProject"
```

---

## Task 4: `SiegeProject.tick()` places instructions; project lookup by position

**Files:**
- Modify: `src/main/java/org/ratden/skavenblight/ai/pathing/SiegeProject.java`
- Modify: `src/main/java/org/ratden/skavenblight/ai/pathing/SiegeProjectManager.java`
- Modify: `src/main/java/org/ratden/skavenblight/ai/pathing/region/RegionFlowField.java`
- Test: `src/main/java/org/ratden/skavenblight/gametest/SiegeProjectTickGameTests.java` (new file
  — real block placement needs a real `ServerLevel`, so this is a GameTest, not a JUnit test)

**Interfaces:**
- Consumes: `SiegeProject.nextUnbuiltInstruction`/`countCompletable` (Tasks 1/3);
  `SiegeInteractionHandler.constructSiegeBlock` (existing, 7-arg); `TerrainEvaluator.
  calculateActionCostForAction` (existing).
- Produces: `SiegeProject.tick(ServerLevel level, RegionFlowField flowField, TerrainEvaluator
  evaluator): void`; `SiegeProjectManager.findProjectContaining(BlockPos): Optional<SiegeProject>`;
  `RegionFlowField.findProjectFor(BlockPos): Optional<SiegeProject>`.

- [ ] **Step 1: Write the failing GameTest**

Create `src/main/java/org/ratden/skavenblight/gametest/SiegeProjectTickGameTests.java`, following
the same hand-built-pathing-objects pattern as `SiegeConstructionActionsGameTests`:

```java
package org.ratden.skavenblight.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.ai.pathing.*;
import org.ratden.skavenblight.ai.pathing.region.RegionFlowField;
import org.ratden.skavenblight.ai.pathing.region.TerritoryRegionMap;
import org.ratden.skavenblight.entity.ModEntities;
import org.ratden.skavenblight.entity.custom.ClanratEntity;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.ratden.skavenblight.gametest.PathingRegionGameTests.check;

/**
 * Proves SiegeProject.tick() itself - the new centralized, worker-count-scaled placement
 * mechanism - independent of any goal, since Task 8's goal migration hasn't landed yet when this
 * test is written (TDD: this test exercises tick()/findProjectContaining directly).
 */
@GameTestHolder(Skavenblight.MODID)
@PrefixGameTestTemplate(false)
public class SiegeProjectTickGameTests {

    @GameTest(template = "pathing_test", timeoutTicks = 100, skyAccess = true)
    public static void testTickPlacesInstructionOnceWorkAccumulates(GameTestHelper helper) {
        TerrainEvaluator evaluator = new TerrainEvaluator();
        SiegeProjectManager projectManager = new SiegeProjectManager(evaluator);
        CalculationThrottler throttler = new CalculationThrottler();
        FlowFieldCalculator calculator = new FlowFieldCalculator(evaluator, projectManager, throttler);
        TerritoryRegionMap owner = new TerritoryRegionMap();

        BlockPos relativeAnchor = new BlockPos(4, 2, 4);
        BlockPos relativeTarget = relativeAnchor.relative(net.minecraft.core.Direction.EAST);
        BlockPos anchor = helper.absolutePos(relativeAnchor);
        BlockPos target = helper.absolutePos(relativeTarget);

        FlowFieldState state = new FlowFieldState(anchor, Set.of());
        RegionFlowField flowField = new RegionFlowField(owner, 0, state, projectManager, calculator, throttler);

        List<SiegeNode> orderedSteps = List.of(new SiegeNode(target, SiegeNode.SiegeAction.BUILD_BRIDGE));
        Map<BlockPos, SiegeNode> instructions = Map.of(target, new SiegeNode(anchor, SiegeNode.SiegeAction.BUILD_BRIDGE));
        SiegeProject project = new SiegeProject(instructions, orderedSteps, anchor, target, 500);
        projectManager.addSharedConnectorProject(project);

        ClanratEntity mob = new ClanratEntity(ModEntities.CLANRAT.get(), helper.getLevel());
        mob.setPos(anchor.getX() + 0.5, anchor.getY(), anchor.getZ() + 0.5);
        helper.getLevel().addFreshEntity(mob);

        check(project.tryRegisterWorker(mob, new LiveTerrainAccess(helper.getLevel()), evaluator, 3.5, 4, 10),
                "worker should register: mob is within radius of the only unbuilt step");

        // buildingBasePenalty * 10 = cost (default Config.buildingBasePenalty=150 -> cost 1500);
        // tick() until it's covered. 200 ticks * workPerRatPerTick is plenty regardless of the
        // exact default chosen in Task 5 - this test only needs "eventually placed", not a tick count.
        for (int i = 0; i < 200 && !helper.getLevel().getBlockState(target).is(Blocks.COBBLESTONE); i++) {
            project.tick(helper.getLevel(), flowField, evaluator);
        }

        helper.assertBlockState(relativeTarget, s -> s.is(Blocks.COBBLESTONE),
                () -> "tick() should have placed cobblestone at " + relativeTarget + " once enough work accumulated");

        helper.succeed();
    }

    @GameTest(template = "pathing_test", timeoutTicks = 100, skyAccess = true)
    public static void testFindProjectContainingLocatesRegisteredProject(GameTestHelper helper) {
        TerrainEvaluator evaluator = new TerrainEvaluator();
        SiegeProjectManager projectManager = new SiegeProjectManager(evaluator);

        BlockPos relativeAnchor = new BlockPos(4, 2, 4);
        BlockPos relativeTarget = relativeAnchor.relative(net.minecraft.core.Direction.EAST);
        BlockPos anchor = helper.absolutePos(relativeAnchor);
        BlockPos target = helper.absolutePos(relativeTarget);

        List<SiegeNode> orderedSteps = List.of(new SiegeNode(target, SiegeNode.SiegeAction.BUILD_BRIDGE));
        Map<BlockPos, SiegeNode> instructions = Map.of(target, new SiegeNode(anchor, SiegeNode.SiegeAction.BUILD_BRIDGE));
        SiegeProject project = new SiegeProject(instructions, orderedSteps, anchor, target, 500);
        projectManager.addSharedConnectorProject(project);

        check(projectManager.findProjectContaining(target).isPresent(), "should find the project owning target");
        check(projectManager.findProjectContaining(anchor).isEmpty(), "anchor itself is not one of the project's build positions");

        helper.succeed();
    }
}
```

- [ ] **Step 2: Run the GameTest to verify it fails**

Run: `./gradlew runGameTestServer --tests "*SiegeProjectTickGameTests*"` (adjust to this project's
actual GameTest run task if different — check `build.gradle`'s `runGameTestServer`/neoforge run
configs; `PathingRegionGameTests`' own comments confirm this project already has a working GameTest
run path).
Expected: compile failure — `SiegeProject.tick`, `SiegeProjectManager.findProjectContaining`,
`RegionFlowField.findProjectFor` don't exist yet.

- [ ] **Step 3: Implement `tick()`, `findProjectContaining`, `findProjectFor`**

In `SiegeProject.java`, add fields and imports (`net.minecraft.server.level.ServerLevel`,
`org.ratden.skavenblight.ai.pathing.region.RegionFlowField`):

```java
    private double accumulatedWork = 0.0;
```

Add the method:

```java
    /** Advances this project's construction by one tick: adds work proportional to registered
     * worker count (capped), then places as many now-affordable not-yet-built steps as the
     * accumulated work covers, in build order. A project whose next step is blocked on an
     * incomplete MINE (see nextUnbuiltInstruction) or that's fully built is a no-op - callers
     * don't need to check either case first. */
    public void tick(ServerLevel level, RegionFlowField flowField, TerrainEvaluator evaluator) {
        workers.removeIf(mob -> !mob.isAlive());

        LiveTerrainAccess terrain = new LiveTerrainAccess(level);
        Optional<PlannedStep> next = nextUnbuiltInstruction(terrain, evaluator);
        if (next.isEmpty()) return;

        this.cachedEffectiveCap = effectiveCapFor(next.get().action(), this.width,
                org.ratden.skavenblight.Config.maxProjectWorkers, org.ratden.skavenblight.Config.workersPerWidenStep);

        int activeWorkers = Math.min(workers.size(), this.cachedEffectiveCap);
        if (activeWorkers == 0) return;

        this.accumulatedWork += activeWorkers * org.ratden.skavenblight.Config.workPerRatPerTick;

        PlannedStep step = next.get();
        while (step != null) {
            int cost = evaluator.calculateActionCostForAction(terrain, step.pos(), step.action());
            if (this.accumulatedWork < cost) break;

            this.accumulatedWork -= cost;
            // supportSolidAtClaim=true: unlike the old per-mob claim-then-execute window (a real
            // multi-tick gap the flag exists to guard), this placement is synchronous with the
            // "is it next in build order" check above - by definition every earlier step is
            // already built, so support is verified fresh right now, not snapshotted earlier.
            SiegeInteractionHandler.constructSiegeBlock(level, step.pos(), step.facing(), step.action(), flowField, null, true);

            Optional<PlannedStep> following = nextUnbuiltInstruction(terrain, evaluator);
            step = following.orElse(null);
        }
    }
```

(Config fields `maxProjectWorkers`/`workersPerWidenStep`/`workPerRatPerTick` are added in Task 5;
this task's own GameTest doesn't depend on their exact values, only that they exist and are
positive, so implement Task 5 immediately after this step if the build doesn't yet compile — the
two tasks are ordered this way specifically because `tick()`'s correctness doesn't depend on the
config values chosen, only their presence.)

In `SiegeProjectManager.java`, add:

```java
    public Optional<SiegeProject> findProjectContaining(BlockPos pos) {
        for (SiegeProject project : activeProjects) {
            if (project.getInstructions().containsKey(pos)) return Optional.of(project);
        }
        return Optional.empty();
    }
```

(add `import java.util.Optional;` if not already covered by the existing `import java.util.*;`).

In `RegionFlowField.java`, add:

```java
    public Optional<SiegeProject> findProjectFor(BlockPos pos) {
        return projectManager.findProjectContaining(pos);
    }
```

(add `import java.util.Optional;`).

- [ ] **Step 4: Add Config fields (see Task 5) if not already done, then run the GameTest**

Run: `./gradlew runGameTestServer --tests "*SiegeProjectTickGameTests*"`
Expected: PASS (both tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/ai/pathing/SiegeProject.java src/main/java/org/ratden/skavenblight/ai/pathing/SiegeProjectManager.java src/main/java/org/ratden/skavenblight/ai/pathing/region/RegionFlowField.java src/main/java/org/ratden/skavenblight/gametest/SiegeProjectTickGameTests.java
git commit -m "feat(pathing): SiegeProject.tick() places instructions as accumulated work covers their cost"
```

---

## Task 5: Config additions

**Files:**
- Modify: `src/main/java/org/ratden/skavenblight/Config.java`

**Interfaces:**
- Produces: `Config.projectWorkRadius: double`, `Config.maxProjectWorkers: int`,
  `Config.workersPerWidenStep: int`, `Config.workPerRatPerTick: double`, `Config.maxProjectWidth:
  int` — all public static fields, refreshed in `onLoad`, following the exact pattern every
  existing `Config` field already uses.

`workPerRatPerTick` needs a default that reproduces roughly today's solo pace: one rat alone
placed one block every `getActionDurationTicks() + getPostActionCooldownTicks()` = 15 + 10 = 25
ticks previously. `Config.buildingBasePenalty` defaults to 150, and `TerrainEvaluator.
calculateActionCostForAction`'s non-MINE, non-WALK branch returns `buildingBasePenalty *
COST_MULTIPLIER(10)` = 1500 for any build action. `1500 / 15` (matching just the animation window,
not the cooldown, since the new model has no per-instruction cooldown) = 100 work/tick for a solo
rat to place one instruction every ~15 ticks. Default `workPerRatPerTick = 100`.

- [ ] **Step 1: Add the new `ModConfigSpec` fields and runtime values**

In `Config.java`, add a new comment section after the existing "Flow-field pathfinding settings"
block (after `MAX_FLOW_FIELD_NODES`'s declaration, before `REGION_SCAN_MAX_CELLS`, keeping every
siege-project-scaling setting grouped together):

```java
    /*
     * Project-scoped siege construction settings
     */

    private static final ModConfigSpec.DoubleValue PROJECT_WORK_RADIUS =
            BUILDER.comment(
                    "How close a rat must be to a siege project's next "
                            + "unbuilt step to register as a worker on it."
            ).defineInRange(
                    "projectWorkRadius",
                    3.5,
                    1.0,
                    16.0
            );

    private static final ModConfigSpec.IntValue MAX_PROJECT_WORKERS =
            BUILDER.comment(
                    "Worker cap for siege project actions that don't "
                            + "auto-widen (pillars, landings, ladders, "
                            + "spirals via the generic fallback)."
            ).defineInRange(
                    "maxProjectWorkers",
                    4,
                    1,
                    100
            );

    private static final ModConfigSpec.IntValue WORKERS_PER_WIDEN_STEP =
            BUILDER.comment(
                    "Worker cap per lane for staircase/bridge projects "
                            + "before they auto-widen by one lane, up to "
                            + "maxProjectWidth."
            ).defineInRange(
                    "workersPerWidenStep",
                    10,
                    1,
                    100
            );

    private static final ModConfigSpec.DoubleValue WORK_PER_RAT_PER_TICK =
            BUILDER.comment(
                    "Build-progress work one registered rat contributes "
                            + "to its siege project per tick."
            ).defineInRange(
                    "workPerRatPerTick",
                    100.0,
                    1.0,
                    10_000.0
            );

    private static final ModConfigSpec.IntValue MAX_PROJECT_WIDTH =
            BUILDER.comment(
                    "Maximum number of lanes a staircase/bridge project "
                            + "may auto-widen to."
            ).defineInRange(
                    "maxProjectWidth",
                    4,
                    1,
                    20
            );
```

Add the public runtime fields, next to `minimumSettleDelayMs`:

```java
    public static double projectWorkRadius;
    public static int maxProjectWorkers;
    public static int workersPerWidenStep;
    public static double workPerRatPerTick;
    public static int maxProjectWidth;
```

Add the loading assignments in `onLoad`, next to `minimumSettleDelayMs`'s:

```java
        projectWorkRadius =
                PROJECT_WORK_RADIUS.get();

        maxProjectWorkers =
                MAX_PROJECT_WORKERS.get();

        workersPerWidenStep =
                WORKERS_PER_WIDEN_STEP.get();

        workPerRatPerTick =
                WORK_PER_RAT_PER_TICK.get();

        maxProjectWidth =
                MAX_PROJECT_WIDTH.get();
```

- [ ] **Step 2: Compile**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL. Config has no dedicated unit test in this codebase (it's a thin
NeoForge `ModConfigSpec` wrapper) — its correctness is exercised implicitly by every GameTest that
now runs against these new defaults (Task 4 onward).

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/Config.java
git commit -m "feat(config): add project-scoped siege construction tunables"
```

---

## Task 6: Auto-widening

**Files:**
- Modify: `src/main/java/org/ratden/skavenblight/ai/pathing/SiegeProject.java`
- Test: `src/main/java/org/ratden/skavenblight/gametest/SiegeProjectAutoWidenGameTests.java` (new
  file — widening re-traces real terrain via `SiegeLineTracer`, needs a real `ServerLevel`)

**Interfaces:**
- Consumes: `SiegeLineTracer` (existing, constructed fresh with a `TerrainEvaluator` — it has no
  other state, see `SiegeProjectManager`'s own `new SiegeLineTracer(terrainEvaluator)`);
  `PlannedStep`/`planSteps` (Task 1); `Config.maxProjectWidth` (Task 5).
- Produces: widened `tryRegisterWorker` (same signature as Task 3 — behavior change only: retries
  once against a freshly-widened cap before giving up).

Widening reuses `SiegeLineTracer.trace` directly rather than adding new tracing logic: given the
current widest lane's own first step and its perpendicular horizontal axis (derived from the
original build order's first two positions, or its single position if length 1), trace one more
line one step further out along that axis, alternating sides on successive widens.

- [ ] **Step 1: Write the failing GameTest**

Create `src/main/java/org/ratden/skavenblight/gametest/SiegeProjectAutoWidenGameTests.java`:

```java
package org.ratden.skavenblight.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.ai.pathing.*;
import org.ratden.skavenblight.entity.ModEntities;
import org.ratden.skavenblight.entity.custom.ClanratEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.ratden.skavenblight.gametest.PathingRegionGameTests.check;

@GameTestHolder(Skavenblight.MODID)
@PrefixGameTestTemplate(false)
public class SiegeProjectAutoWidenGameTests {

    /** Registers `count` distinct mobs, one at a time, at ever-so-slightly different but all
     * within-radius positions near `target`, to simulate several rats converging simultaneously. */
    private static List<ClanratEntity> registerNRatsNear(GameTestHelper helper, SiegeProject project, BlockPos target,
                                                          TerrainEvaluator evaluator, int count) {
        List<ClanratEntity> rats = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ClanratEntity rat = new ClanratEntity(ModEntities.CLANRAT.get(), helper.getLevel());
            rat.setPos(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
            helper.getLevel().addFreshEntity(rat);
            rats.add(rat);
            project.tryRegisterWorker(rat, new LiveTerrainAccess(helper.getLevel()), evaluator,
                    org.ratden.skavenblight.Config.projectWorkRadius,
                    org.ratden.skavenblight.Config.maxProjectWorkers, org.ratden.skavenblight.Config.workersPerWidenStep);
        }
        return rats;
    }

    @GameTest(template = "pathing_test", timeoutTicks = 100, skyAccess = true)
    public static void testWidensWhenRegistrationRejectedAtCap(GameTestHelper helper) {
        TerrainEvaluator evaluator = new TerrainEvaluator();
        BlockPos relativeAnchor = new BlockPos(4, 2, 4);
        BlockPos relativeTarget = relativeAnchor.relative(Direction.EAST);
        BlockPos anchor = helper.absolutePos(relativeAnchor);
        BlockPos target = helper.absolutePos(relativeTarget);

        // Flat, open floor on both sides of the lane (Z-1 and Z+1) so a widened lane has
        // somewhere valid to go - default template floor already provides this.
        List<SiegeNode> orderedSteps = List.of(new SiegeNode(target, SiegeNode.SiegeAction.BUILD_STAIR));
        Map<BlockPos, SiegeNode> instructions = Map.of(target, new SiegeNode(anchor, SiegeNode.SiegeAction.BUILD_STAIR));
        SiegeProject project = new SiegeProject(instructions, orderedSteps, anchor, target, 500);

        // Config.workersPerWidenStep default is 10 - register exactly that many first (all should
        // succeed against width=1's cap), then one more, which must trigger a widen.
        List<ClanratEntity> firstBatch = registerNRatsNear(helper, project, target, evaluator,
                org.ratden.skavenblight.Config.workersPerWidenStep);

        ClanratEntity extraRat = new ClanratEntity(ModEntities.CLANRAT.get(), helper.getLevel());
        extraRat.setPos(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
        helper.getLevel().addFreshEntity(extraRat);

        boolean registered = project.tryRegisterWorker(extraRat, new LiveTerrainAccess(helper.getLevel()), evaluator,
                org.ratden.skavenblight.Config.projectWorkRadius,
                org.ratden.skavenblight.Config.maxProjectWorkers, org.ratden.skavenblight.Config.workersPerWidenStep);

        check(registered, "the (workersPerWidenStep + 1)th rat should trigger a widen and then successfully register");
        check(project.getWidth() > 1, "project width should have grown past 1 after the widen");

        helper.succeed();
    }

    @GameTest(template = "pathing_test", timeoutTicks = 100, skyAccess = true)
    public static void testDoesNotWidenWhenActionIsNotLaterallyWidenable(GameTestHelper helper) {
        TerrainEvaluator evaluator = new TerrainEvaluator();
        BlockPos relativeAnchor = new BlockPos(4, 2, 4);
        BlockPos relativeTarget = relativeAnchor.above();
        BlockPos anchor = helper.absolutePos(relativeAnchor);
        BlockPos target = helper.absolutePos(relativeTarget);

        List<SiegeNode> orderedSteps = List.of(new SiegeNode(target, SiegeNode.SiegeAction.BUILD_PILLAR));
        Map<BlockPos, SiegeNode> instructions = Map.of(target, new SiegeNode(anchor, SiegeNode.SiegeAction.BUILD_PILLAR));
        SiegeProject project = new SiegeProject(instructions, orderedSteps, anchor, target, 500);

        registerNRatsNear(helper, project, target, evaluator, org.ratden.skavenblight.Config.maxProjectWorkers);

        ClanratEntity extraRat = new ClanratEntity(ModEntities.CLANRAT.get(), helper.getLevel());
        extraRat.setPos(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
        helper.getLevel().addFreshEntity(extraRat);

        boolean registered = project.tryRegisterWorker(extraRat, new LiveTerrainAccess(helper.getLevel()), evaluator,
                org.ratden.skavenblight.Config.projectWorkRadius,
                org.ratden.skavenblight.Config.maxProjectWorkers, org.ratden.skavenblight.Config.workersPerWidenStep);

        check(!registered, "BUILD_PILLAR is not laterally widenable - an over-cap registration must stay rejected");
        check(project.getWidth() == 1, "width must not have changed for a non-widenable action");

        helper.succeed();
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew runGameTestServer --tests "*SiegeProjectAutoWidenGameTests*"`
Expected: compile failure — `SiegeProject.getWidth()` doesn't exist yet, and the current
`tryRegisterWorker` never widens, so `testWidensWhenRegistrationRejectedAtCap` would fail its
`registered`/`getWidth() > 1` checks even once it compiles.

- [ ] **Step 3: Implement widening**

In `SiegeProject.java`, add a `getWidth()` accessor and a `SiegeLineTracer` field, and change
`tryRegisterWorker` to attempt a widen on rejection:

```java
    public int getWidth() { return this.width; }
```

Replace the body of `tryRegisterWorker` (from Task 3) with:

```java
    public boolean tryRegisterWorker(Mob mob, TerrainAccess terrain, TerrainEvaluator evaluator, double workRadius,
                                      int maxProjectWorkers, int workersPerWidenStep) {
        Optional<PlannedStep> next = nextUnbuiltInstruction(terrain, evaluator);
        if (next.isEmpty()) return false;
        PlannedStep step = next.get();
        if (!mob.blockPosition().closerThan(step.pos(), workRadius)) return false;

        this.cachedEffectiveCap = effectiveCapFor(step.action(), this.width, maxProjectWorkers, workersPerWidenStep);
        if (workers.contains(mob)) return true;

        if (workers.size() >= this.cachedEffectiveCap) {
            if (!tryWiden(step.action(), terrain, evaluator)) return false;
            this.cachedEffectiveCap = effectiveCapFor(step.action(), this.width, maxProjectWorkers, workersPerWidenStep);
            if (workers.size() >= this.cachedEffectiveCap) return false;
        }

        workers.add(mob);
        return true;
    }

    /** Attempts to trace one more parallel lane, alternating sides on successive widens, when a
     * BUILD_STAIR/BUILD_BRIDGE project is at capacity - see the design doc's Auto-widening
     * section. No-ops (returns false) for non-widenable actions, once maxProjectWidth is reached,
     * or when the trace itself fails (terrain doesn't support it, out of bounds, too much mining)
     * - a failed attempt leaves width unchanged and is simply retried on the next rejected
     * registration, never per-tick, bounding retry frequency to actual demand. */
    private boolean tryWiden(SiegeNode.SiegeAction currentAction, TerrainAccess terrain, TerrainEvaluator evaluator) {
        boolean widenable = currentAction == SiegeNode.SiegeAction.BUILD_STAIR || currentAction == SiegeNode.SiegeAction.BUILD_BRIDGE;
        if (!widenable || this.width >= org.ratden.skavenblight.Config.maxProjectWidth || buildOrder.isEmpty()) return false;

        PlannedStep first = buildOrder.get(0);
        BlockPos traceDirectionSource = buildOrder.size() > 1 ? buildOrder.get(1).pos() : first.pos();
        int dx = traceDirectionSource.getX() - first.pos().getX();
        int dz = traceDirectionSource.getZ() - first.pos().getZ();
        // Perpendicular horizontal axis to the trace direction, alternating sides per widen:
        // even widths go one way, odd the other, so the structure grows outward on both sides.
        int perpX = -dz;
        int perpZ = dx;
        int side = (this.width % 2 == 0) ? 1 : -1;
        BlockPos offset = new BlockPos(perpX * side, 0, perpZ * side);

        BlockPos newAnchor = this.entryAnchorForWidenTrace().offset(offset.getX(), 0, offset.getZ());
        int traceDy = first.pos().getY() - this.entryAnchorForWidenTrace().getY();

        SiegeLineTracer tracer = new SiegeLineTracer(evaluator);
        SiegeLineTracer.TraceResult result = tracer.trace(terrain, newAnchor,
                Integer.signum(first.pos().getX() - this.entryAnchorForWidenTrace().getX()),
                Integer.signum(traceDy),
                Integer.signum(first.pos().getZ() - this.entryAnchorForWidenTrace().getZ()),
                newAnchor, 0, pos -> false, pos -> Integer.MAX_VALUE, buildOrder.size());

        if (!result.completed() || result.orderedSteps().isEmpty()) return false;

        buildOrder.addAll(planSteps(result.orderedSteps(), newAnchor));
        this.width++;
        return true;
    }

    /** The original build order's own starting anchor (the position its first step was traced
     * from) - stored so tryWiden can reconstruct the same trace direction from a shifted anchor.
     * Deliberately distinct from `entryPos` (the project's far-side entry, used for flow-field
     * routing) - see the constructor. */
    private BlockPos entryAnchorForWidenTrace() {
        return this.widenAnchor;
    }
```

This last helper references a new field `widenAnchor` that must be captured at construction time
(it's the same `buildOrderAnchor` constructor parameter already threaded through in Task 1 — store
it instead of only feeding it to `planSteps`). Update the constructor:

```java
    private final BlockPos widenAnchor;
```

and in the 6-arg constructor body, alongside `this.buildOrder = planSteps(orderedSteps,
buildOrderAnchor);`, add:

```java
        this.widenAnchor = buildOrderAnchor;
```

`buildOrder` must become a mutable field for `tryWiden`'s `buildOrder.addAll(...)` to work — change
its declaration from `private final List<PlannedStep> buildOrder;` (Task 1) to `private final
List<PlannedStep> buildOrder;` initialized via `new ArrayList<>(planSteps(...))` instead of
`planSteps(...)` directly (an `ArrayList` is mutable; `planSteps` already returns a plain
`ArrayList` per Task 1's implementation, so wrapping isn't even required — just confirm `planSteps`
isn't wrapped in `List.copyOf`/`Collections.unmodifiableList` anywhere, which it isn't per Task 1's
code above).

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew runGameTestServer --tests "*SiegeProjectAutoWidenGameTests*"`
Expected: PASS (both tests).

- [ ] **Step 5: Re-run every earlier task's tests to confirm no regression**

Run: `./gradlew test` (JUnit) and `./gradlew runGameTestServer --tests "*SiegeProject*"` (GameTest)
Expected: all still PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/ai/pathing/SiegeProject.java src/main/java/org/ratden/skavenblight/gametest/SiegeProjectAutoWidenGameTests.java
git commit -m "feat(pathing): auto-widen staircase/bridge projects under worker demand"
```

---

## Task 7: Shared lookahead helper + `AbstractSiegeProjectGoal` base class

**Files:**
- Create: `src/main/java/org/ratden/skavenblight/ai/goal/clanrat/SiegeNodeLookahead.java`
- Modify: `src/main/java/org/ratden/skavenblight/ai/goal/clanrat/AbstractSiegeConstructionGoal.java`
- Create: `src/main/java/org/ratden/skavenblight/ai/goal/clanrat/AbstractSiegeProjectGoal.java`

**Interfaces:**
- Consumes: `RegionFlowField.getNextSiegeNode` (existing); `RegionFlowField.findProjectFor` (Task
  4); `SiegeProject.tryRegisterWorker`/`unregisterWorker`/`tick`/`isAtCapacity` (Tasks 3/4/6);
  `SiegeGoal` interface (existing).
- Produces: `static Optional<SiegeNode> SiegeNodeLookahead.findEffectiveNode(RegionFlowField
  flowField, PathfinderMob mob, Predicate<SiegeNode.SiegeAction> lookAheadMatch)` — extracted,
  byte-for-byte-behavior-preserving, from `AbstractSiegeConstructionGoal.findEffectiveNode`'s
  current body; `AbstractSiegeProjectGoal` — `canUse()`, `start()`, `tick()`, `stop()`,
  `canContinueToUse()` skeleton; abstract hook `matchesAction(SiegeNode.SiegeAction)`.

`AbstractSiegeConstructionGoal.findEffectiveNode` and the new `AbstractSiegeProjectGoal` both need
the identical "look at the node ahead, snap through a WALK hop to whatever's past it" lookup.
Rather than duplicating it (flagged and rejected during this plan's pre-flight review — extraction
was chosen over duplication despite touching the existing, heavily-hardened
`AbstractSiegeConstructionGoal`), this task extracts it once into a small shared static helper both
classes call. This is a pure extract-method refactor: `AbstractSiegeConstructionGoal`'s own
observable behavior must not change, since `SmartBreachGoal`/`WidenStairsGoal`(-until-deleted) rely
on it unmodified.

This is a new file, not a GameTest-testable-in-isolation unit (goal plumbing needs a live
`ServerLevel` + `ClanratEntity`), so its correctness is verified through Task 8's own GameTest
updates (`SiegeConstructionActionsGameTests`, `PathingGoalRecalculationGameTests`) which drive it
through `BuildFlowFieldGoal`, AND through re-running the existing GameTest suite (Step 3 below) to
confirm the extraction didn't change `AbstractSiegeConstructionGoal`'s behavior for `SmartBreachGoal`.

- [ ] **Step 1: Extract the shared helper**

Create `src/main/java/org/ratden/skavenblight/ai/goal/clanrat/SiegeNodeLookahead.java`:

```java
package org.ratden.skavenblight.ai.goal.clanrat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.PathfinderMob;
import org.ratden.skavenblight.ai.pathing.SiegeNode;
import org.ratden.skavenblight.ai.pathing.region.RegionFlowField;

import java.util.Optional;
import java.util.function.Predicate;

/**
 * Shared "look at the node in front of us, and if it's a WALK step immediately before a node the
 * caller cares about, snap to that node instead" lookup, used by both AbstractSiegeConstructionGoal
 * (the per-block-claim goal family) and AbstractSiegeProjectGoal (the project-scoped family) - the
 * same lookahead applies regardless of what a matching node's own claim/registration model is.
 * Extracted from AbstractSiegeConstructionGoal.findEffectiveNode without behavior change; see that
 * class's own historical Javadoc (MAX_TARGET_CLAIM_DISTANCE / LOOKAHEAD_SNAP_DISTANCE) for the full
 * history of why this exact shape (1.5-block peek, WALK-hop-only, self-overlap guard) is correct.
 */
final class SiegeNodeLookahead {

    private static final double LOOKAHEAD_SNAP_DISTANCE = 1.5D;

    private SiegeNodeLookahead() {}

    static Optional<SiegeNode> findEffectiveNode(RegionFlowField flowField, PathfinderMob mob,
                                                  Predicate<SiegeNode.SiegeAction> lookAheadMatch) {
        if (flowField == null || !(mob.level() instanceof ServerLevel serverLevel)) return Optional.empty();
        BlockPos currentPos = mob.blockPosition();

        SiegeNode node = flowField.getNextSiegeNode(serverLevel, currentPos);
        if (node == null) {
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                node = flowField.getNextSiegeNode(serverLevel, currentPos.relative(dir));
                if (node != null) break;
            }
        }

        if (node != null && node.action() == SiegeNode.SiegeAction.WALK) {
            SiegeNode nextNode = flowField.getNextSiegeNode(serverLevel, node.pos());
            if (nextNode != null && lookAheadMatch.test(nextNode.action())
                    && !nextNode.pos().equals(currentPos)
                    && currentPos.closerThan(nextNode.pos(), LOOKAHEAD_SNAP_DISTANCE)) {
                return Optional.of(nextNode);
            }
        }

        if (node != null && node.action() != SiegeNode.SiegeAction.WALK && node.pos().equals(currentPos)) {
            return Optional.empty();
        }

        return Optional.ofNullable(node);
    }
}
```

- [ ] **Step 2: Point `AbstractSiegeConstructionGoal.findEffectiveNode` at the shared helper**

In `AbstractSiegeConstructionGoal.java`, replace the body of `findEffectiveNode` (currently ~35
lines implementing this logic inline) with a one-line delegation:

```java
    protected final Optional<SiegeNode> findEffectiveNode(Predicate<SiegeNode.SiegeAction> lookAheadMatch) {
        return SiegeNodeLookahead.findEffectiveNode(this.flowField, this.mob, lookAheadMatch);
    }
```

Delete the now-unused `LOOKAHEAD_SNAP_DISTANCE` constant from `AbstractSiegeConstructionGoal` (it
moved into `SiegeNodeLookahead`) — confirm nothing else in the file references it first (it's
private and only used inside the method being replaced, so this is safe).

- [ ] **Step 3: Run the existing GameTest suite to confirm the extraction is behavior-preserving**

Run: `./gradlew runGameTestServer`
Expected: identical pass/fail set to this plan's recorded baseline (32 tests, the same 5
pre-existing failures — no new failures, since this step is a pure refactor of code
`SmartBreachGoal`/`WidenStairsGoal` depend on and neither goal's behavior should change).

- [ ] **Step 4: Write the `AbstractSiegeProjectGoal` class**

```java
package org.ratden.skavenblight.ai.goal.clanrat;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import org.ratden.skavenblight.Config;
import org.ratden.skavenblight.ai.goal.SiegeGoal;
import org.ratden.skavenblight.ai.pathing.LiveTerrainAccess;
import org.ratden.skavenblight.ai.pathing.SiegeNode;
import org.ratden.skavenblight.ai.pathing.SiegeProject;
import org.ratden.skavenblight.ai.pathing.TerrainEvaluator;
import org.ratden.skavenblight.ai.pathing.region.RegionFlowField;

import java.util.EnumSet;
import java.util.Optional;

/**
 * Shared skeleton for project-scoped construction goals: a rat registers as a worker on the
 * SiegeProject nearest unbuilt work belongs to, keeps station (approach + animate) near it while
 * registered, and the project itself - not this goal - places blocks as accumulated work covers
 * their cost (see SiegeProject.tick()). Sibling to AbstractSiegeConstructionGoal, not a subclass:
 * the claim/fixed-duration-execute core those goals share is exactly what this class replaces, so
 * inheriting from it would mean overriding away most of what it provides. Shares only the
 * lookahead lookup (SiegeNodeLookahead) with that class. WidenStairsGoal, SmartBreachGoal,
 * SpiralSapperGoal, and DeployClimbableGoal are unaffected by this class - see the design doc's
 * Scope section for why the latter two stay on the old per-block claim.
 */
public abstract class AbstractSiegeProjectGoal extends Goal implements SiegeGoal {

    protected final PathfinderMob mob;
    protected RegionFlowField flowField;
    private final TerrainEvaluator terrainEvaluator = new TerrainEvaluator();

    private SiegeProject registeredProject;

    /** Same tolerance AbstractSiegeConstructionGoal uses for post-claim crowd-shove - this class
     * doesn't claim a single block, but a rat drifting this far from the project's own next
     * unbuilt step while registered is exactly the same "can't close the gap under its own MOVE
     * flag being zeroed every tick" situation that constant is sized for. */
    public static final double MAX_PROJECT_DRIFT_DISTANCE = 2.5D;

    protected AbstractSiegeProjectGoal(PathfinderMob mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public void setFlowField(RegionFlowField flowField) {
        this.flowField = flowField;
    }

    /** Which SiegeNode.SiegeAction values this goal handles - e.g. BuildFlowFieldGoal's
     * BUILD_STAIR/BUILD_BRIDGE/BUILD_PILLAR/BUILD_LANDING/BUILD_LADDER/BUILD_SPIRAL. */
    protected abstract boolean matchesAction(SiegeNode.SiegeAction action);

    private Optional<SiegeNode> findEffectiveNode() {
        return SiegeNodeLookahead.findEffectiveNode(this.flowField, this.mob, this::matchesAction);
    }

    @Override
    public boolean canUse() {
        if (this.flowField == null || !this.mob.isAlive()) return false;
        // Mid-leap guard - see AbstractSiegeConstructionGoal.canUse()'s own identical check for
        // the full history of why this specific condition (not bare onGround()) is correct.
        if (!this.mob.onGround() && this.mob.getDeltaMovement().y > 1.0E-2) return false;

        return findEffectiveNode()
                .filter(node -> matchesAction(node.action()))
                .flatMap(node -> this.flowField.findProjectFor(node.pos()))
                .isPresent();
    }

    @Override
    public void start() {
        findEffectiveNode()
                .filter(node -> matchesAction(node.action()))
                .flatMap(node -> this.flowField.findProjectFor(node.pos()))
                .ifPresent(project -> {
                    if (this.mob.level() instanceof ServerLevel serverLevel
                            && project.tryRegisterWorker(this.mob, new LiveTerrainAccess(serverLevel), this.terrainEvaluator,
                            Config.projectWorkRadius, Config.maxProjectWorkers, Config.workersPerWidenStep)) {
                        this.registeredProject = project;
                    }
                });
    }

    @Override
    public boolean canContinueToUse() {
        if (this.registeredProject == null || !this.mob.isAlive() || !(this.mob.level() instanceof ServerLevel serverLevel)) {
            return false;
        }
        return findEffectiveNode()
                .filter(node -> matchesAction(node.action()))
                .map(node -> this.mob.blockPosition().closerThan(node.pos(), MAX_PROJECT_DRIFT_DISTANCE))
                .orElse(false);
    }

    @Override
    public void tick() {
        if (this.registeredProject == null || !(this.mob.level() instanceof ServerLevel serverLevel)) return;

        this.mob.setDeltaMovement(0, this.mob.getDeltaMovement().y, 0);
        findEffectiveNode().ifPresent(node -> this.mob.getLookControl().setLookAt(
                node.pos().getX() + 0.5D, node.pos().getY() + 0.5D, node.pos().getZ() + 0.5D));

        if (this.mob.tickCount % 5 == 0) this.mob.swing(InteractionHand.MAIN_HAND);

        this.registeredProject.tick(serverLevel, this.flowField, this.terrainEvaluator);
    }

    @Override
    public void stop() {
        if (this.registeredProject != null) {
            this.registeredProject.unregisterWorker(this.mob);
        }
        this.registeredProject = null;
    }

    /** For AwaitFormationGoal (Task 9): is there build work nearby whose owning project is
     * currently full? Mirrors AbstractSiegeConstructionGoal.peekClaimedTarget()'s role for the
     * old per-block claim. */
    public Optional<BlockPos> peekAtCapacityTarget() {
        if (this.flowField == null) return Optional.empty();
        return findEffectiveNode()
                .filter(node -> matchesAction(node.action()))
                .filter(node -> this.flowField.findProjectFor(node.pos()).map(SiegeProject::isAtCapacity).orElse(false))
                .map(SiegeNode::pos);
    }
}
```

- [ ] **Step 5: Compile**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL (this class has no concrete subclass yet, so nothing exercises it, but
it must compile standalone).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/ai/goal/clanrat/SiegeNodeLookahead.java src/main/java/org/ratden/skavenblight/ai/goal/clanrat/AbstractSiegeConstructionGoal.java src/main/java/org/ratden/skavenblight/ai/goal/clanrat/AbstractSiegeProjectGoal.java
git commit -m "refactor(ai): extract shared node-lookahead helper; add AbstractSiegeProjectGoal"
```

---

## Task 8: Migrate `BuildFlowFieldGoal`; retire `WidenStairsGoal`

**Files:**
- Modify: `src/main/java/org/ratden/skavenblight/ai/goal/clanrat/BuildFlowFieldGoal.java`
- Modify: `src/main/java/org/ratden/skavenblight/entity/custom/ClanratEntity.java`
- Delete: `src/main/java/org/ratden/skavenblight/ai/goal/clanrat/WidenStairsGoal.java`

**Interfaces:**
- Consumes: `AbstractSiegeProjectGoal` (Task 7).
- Produces: `BuildFlowFieldGoal` now extends `AbstractSiegeProjectGoal` instead of
  `AbstractSiegeConstructionGoal`; `ClanratEntity.registerGoals()` no longer registers
  `WidenStairsGoal`; `ClanratEntity.peekAnyClaimedConstructionTarget()` updated to the new type.

- [ ] **Step 1: Rewrite `BuildFlowFieldGoal`**

Replace the whole file:

```java
package org.ratden.skavenblight.ai.goal.clanrat;

import net.minecraft.world.entity.PathfinderMob;
import org.ratden.skavenblight.ai.pathing.SiegeNode;

public class BuildFlowFieldGoal extends AbstractSiegeProjectGoal {

    public BuildFlowFieldGoal(PathfinderMob mob) {
        super(mob);
    }

    @Override
    protected boolean matchesAction(SiegeNode.SiegeAction action) {
        return action == SiegeNode.SiegeAction.BUILD_STAIR ||
                action == SiegeNode.SiegeAction.BUILD_BRIDGE ||
                action == SiegeNode.SiegeAction.BUILD_PILLAR ||
                action == SiegeNode.SiegeAction.BUILD_LANDING ||
                action == SiegeNode.SiegeAction.BUILD_SPIRAL ||
                action == SiegeNode.SiegeAction.BUILD_LADDER;
    }
}
```

(Facing, action-duration, cooldown, stall-handling, and execute() all moved into
`SiegeProject`/`AbstractSiegeProjectGoal` in earlier tasks — this class is now just "which actions
do I claim.")

- [ ] **Step 2: Update `ClanratEntity`**

Remove the `WidenStairsGoal` import and its registration. In `registerGoals()`, delete:

```java
        this.goalSelector.addGoal(7, new WidenStairsGoal(this));
```

and renumber nothing else (vanilla `GoalSelector` priorities don't need to be contiguous — leaving
a gap at 7 is harmless and avoids an unrelated diff touching every goal below it). Remove the now-
unused import `org.ratden.skavenblight.ai.goal.clanrat.WidenStairsGoal;`.

Update `peekAnyClaimedConstructionTarget()` (it currently filters
`AbstractSiegeConstructionGoal siegeGoal && (siegeGoal instanceof BuildFlowFieldGoal ||
siegeGoal instanceof WidenStairsGoal)` — `BuildFlowFieldGoal` is no longer an
`AbstractSiegeConstructionGoal`, and `WidenStairsGoal` no longer exists):

```java
    /**
     * The nearest target BuildFlowFieldGoal on this rat would want to build, if that target
     * exists but its owning SiegeProject is already at capacity. Used by AwaitFormationGoal to
     * decide whether "someone else already has the spot I'd otherwise go queue at" - the
     * project-scoped equivalent of the old per-block claim check (WidenStairsGoal is gone; its
     * own crowd-relief role is now the auto-widening built into SiegeProject itself).
     * Deliberately excludes SmartBreachGoal - breach/MINE contention is out of scope for
     * formation-waiting (see docs/superpowers/plans/2026-07-30-formation-waiting-goal.md's
     * Global Constraints).
     */
    public Optional<BlockPos> peekAnyClaimedConstructionTarget() {
        for (WrappedGoal wrapped : this.goalSelector.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof AbstractSiegeProjectGoal siegeGoal) {
                Optional<BlockPos> claimed = siegeGoal.peekAtCapacityTarget();
                if (claimed.isPresent()) return claimed;
            }
        }
        return Optional.empty();
    }
```

Add the import `org.ratden.skavenblight.ai.goal.clanrat.AbstractSiegeProjectGoal;` (the existing
`AbstractSiegeConstructionGoal` import stays — `describeSiegeGoalCanUseState`/
`describeActiveSiegeGoalState` still reference it for `SmartBreachGoal`'s diagnostics).

- [ ] **Step 3: Delete `WidenStairsGoal.java`**

```bash
git rm src/main/java/org/ratden/skavenblight/ai/goal/clanrat/WidenStairsGoal.java
```

- [ ] **Step 4: Compile**

Run: `./gradlew compileJava`
Expected: compile FAILURE at this point — `PathingGoalRecalculationGameTests` (Task 11) still
references the deleted `WidenStairsGoal`, and `SiegeConstructionActionsGameTests` (Task 10) still
drives the old `BuildFlowFieldGoal` shape with hand-fed instructions and no `SiegeProject`. This is
expected and resolved by Tasks 10-11 — do not attempt to make this task's own compile pass in
isolation; proceed directly to Task 9, then 10, then 11, then compile/test the whole set together.

- [ ] **Step 5: Commit (staged together with the compile-breaking downstream files still pending)**

Do not commit yet — Task 8 alone leaves the build broken. Commit once Task 11 is also complete;
see Task 11's own commit step, which stages Tasks 8-11 together as one coherent, compiling change.

---

## Task 9: `AwaitFormationGoal`'s alternative-target search respects project capacity

**Files:**
- Modify: `src/main/java/org/ratden/skavenblight/ai/goal/clanrat/AwaitFormationGoal.java`

**Interfaces:**
- Consumes: `RegionFlowField.findProjectFor` (Task 4), `SiegeProject.isAtCapacity` (Task 3).

`findUnclaimedAlternative` currently treats every `isClimbDependent()` position (`BUILD_STAIR`,
`BUILD_PILLAR`, `BUILD_SPIRAL`) as "claimed" via `field.isTargetClaimed(pos)`. `BUILD_STAIR`/
`BUILD_PILLAR` no longer go through that claim table (`BuildFlowFieldGoal` moved to project
registration in Task 8) — `isTargetClaimed` would now always report `false` for them regardless of
real availability, silently breaking this goal's whole "avoid an already-contested spot" purpose
for those two action types. `BUILD_SPIRAL` is untouched (`SpiralSapperGoal` still claims via the
old table) and must keep using `isTargetClaimed` exactly as today.

- [ ] **Step 1: Fix `findUnclaimedAlternative`**

Replace the `.filter(pos -> !field.isTargetClaimed(pos))` line in `findUnclaimedAlternative` (the
method reads `field.getInstructionMap().values().stream().filter(node ->
node.action().isClimbDependent())...`) with an action-aware availability check. Since the stream is
over `SiegeNode` values (which have both `.pos()` and `.action()`) before mapping down to just
`.pos()`, restructure slightly to keep the action available at filter time:

```java
    private Optional<BlockPos> findUnclaimedAlternative(ServerLevel level) {
        RegionFlowField field = this.flowField;
        if (field == null) return Optional.empty();
        BlockPos mobPos = this.mob.blockPosition();
        return field.getInstructionMap().values().stream()
                .filter(node -> node.action().isClimbDependent())
                .distinct()
                .filter(node -> isPositionAvailable(field, node))
                .map(SiegeNode::pos)
                .filter(pos -> level.getBlockState(pos).canBeReplaced())
                .min(Comparator.comparingDouble(pos -> pos.distSqr(mobPos)));
    }

    /**
     * BUILD_SPIRAL still goes through the old per-block claim table (SpiralSapperGoal is
     * untouched by the project-scoped overhaul - see the design doc's Scope). BUILD_STAIR and
     * BUILD_PILLAR moved to project-worker registration (Task 8) - "available" for those means
     * their owning SiegeProject isn't at capacity, not "unclaimed" (that table no longer reflects
     * them at all).
     */
    private boolean isPositionAvailable(RegionFlowField field, SiegeNode node) {
        if (node.action() == SiegeNode.SiegeAction.BUILD_SPIRAL) {
            return !field.isTargetClaimed(node.pos());
        }
        return field.findProjectFor(node.pos()).map(project -> !project.isAtCapacity()).orElse(true);
    }
```

Note the `.distinct()` moved earlier (it originally ran on the mapped `BlockPos` stream via
`.map(SiegeNode::pos).distinct()`) — keep it working on the `SiegeNode` values here instead, since
`SiegeNode` is a record with structural equality and the same dedup effect holds; if the compiler
or a quick manual check shows position-level dedup is needed instead (unlikely, since keys are
unique per `instructionMap` position and values are queried once per position already), add
`.map(SiegeNode::pos).distinct()` after the final `.map(SiegeNode::pos)` line instead and drop the
one above — verify by running Step 2's test either way.

- [ ] **Step 2: Run the existing formation-waiting GameTest suite**

Run: `./gradlew runGameTestServer --tests "*AwaitFormationGoalGameTests*"`
Expected: PASS, unchanged — this is a pure logic fix to how "available" is determined, not a
behavior change to the test's own claim setup (verify the test file itself doesn't hand-construct
`BUILD_STAIR`/`BUILD_PILLAR` claims via `tryClaimTarget` expecting `findUnclaimedAlternative` to
still honor them; if it does, that specific setup needs updating to register a full `SiegeProject`
instead — read `AwaitFormationGoalGameTests.java` first if this run fails, and adjust its setup to
match, not this method's logic).

- [ ] **Step 3: Commit (staged with Task 8, per Task 8's own note)**

Do not commit yet — continue to Task 10.

---

## Task 10: Update `SiegeConstructionActionsGameTests`

**Files:**
- Modify: `src/main/java/org/ratden/skavenblight/gametest/SiegeConstructionActionsGameTests.java`

**Interfaces:**
- Consumes: `SiegeProjectManager.addSharedConnectorProject` (existing), `SiegeProject`'s
  constructor (Task 1), `BuildFlowFieldGoal` (Task 8), `AbstractSiegeProjectGoal` (Task 7).

Every lane except `MINE` uses `BuildFlowFieldGoal`, which now requires an owning `SiegeProject` to
register against (`findProjectFor` must resolve). Inject one minimal, single-instruction project
per non-MINE lane before driving the goal. The tick-count-based drive loop changes too: there's no
more fixed `getActionDurationTicks()` to tick exactly — instead, tick the goal (which registers,
then each tick advances the underlying project) until the target is placed or a generous cap is
hit, matching how a real rat would behave.

- [ ] **Step 1: Rewrite the test**

Replace `runBuildLane`'s goal-driving section (from `AbstractSiegeConstructionGoal goal = ...`
through the end of the tick loop) with:

```java
        BlockPos mobPos = helper.absolutePos(relativeMobPos);
        BlockPos targetPos = helper.absolutePos(relativeTargetPos);
        mob.setPos(mobPos.getX() + 0.5, mobPos.getY(), mobPos.getZ() + 0.5);

        state.updateInstructions(Map.of(mobPos, new SiegeNode(targetPos, action)));

        if (action == SiegeNode.SiegeAction.MINE) {
            SmartBreachGoal goal = new SmartBreachGoal(mob);
            goal.setFlowField(flowField);
            check(goal.canUse(), action + " lane: goal should trigger for target " + targetPos.toShortString());
            goal.start();
            for (int i = 0; i < 20; i++) goal.tick();
        } else {
            // Every real BUILD_* node belongs to a SiegeProject - only SiegeProjectManager ever
            // writes them in production. A single-instruction project here mirrors that
            // invariant instead of adding a "no owning project" fallback to production code for a
            // case that can't happen for real.
            List<SiegeNode> orderedSteps = List.of(new SiegeNode(targetPos, action));
            Map<BlockPos, SiegeNode> instructions = Map.of(targetPos, new SiegeNode(mobPos, action));
            SiegeProject project = new SiegeProject(instructions, orderedSteps, mobPos, targetPos, 500);
            projectManagerRef.addSharedConnectorProject(project);

            BuildFlowFieldGoal goal = new BuildFlowFieldGoal(mob);
            goal.setFlowField(flowField);

            check(goal.canUse(), action + " lane: goal should trigger for target " + targetPos.toShortString());
            goal.start();
            for (int i = 0; i < 200 && !project.nextUnbuiltInstruction(new org.ratden.skavenblight.ai.pathing.LiveTerrainAccess(helper.getLevel()), evaluatorRef).isPresent(); i++) {
                goal.tick();
            }
        }
```

This requires `runBuildLane` to receive the `SiegeProjectManager` and `TerrainEvaluator` the test
method already constructs (`projectManager`/`evaluator` at the top of
`testClanratExecutesEverySiegeConstructionAction`) — add two parameters to `runBuildLane`'s
signature: `SiegeProjectManager projectManagerRef, TerrainEvaluator evaluatorRef`, and pass them at
each of the 7 call sites in `testClanratExecutesEverySiegeConstructionAction` (`runBuildLane(helper,
mob, flowField, state, projectManager, evaluator, 2, SiegeNode.SiegeAction.MINE)`, etc. — insert
the two new args right after `state` at every call).

Also remove the now-unused `AbstractSiegeConstructionGoal` import and the `int
actionDurationTicks = ...` line this replaces; add `java.util.List` to imports if not already
present (it likely is, given `Set` is already imported from `java.util`).

- [ ] **Step 2: Run**

Run: `./gradlew runGameTestServer --tests "*SiegeConstructionActionsGameTests*"`
Expected: PASS — all 7 lanes still assert the correct final block state, now via project-driven
placement instead of fixed-duration single-goal execution.

- [ ] **Step 3: Commit (staged with Task 8, per its note)**

Do not commit yet — continue to Task 11.

---

## Task 11: Update `PathingGoalRecalculationGameTests`

**Files:**
- Modify: `src/main/java/org/ratden/skavenblight/gametest/PathingGoalRecalculationGameTests.java`

**Interfaces:**
- Consumes: same as Task 10.

- [ ] **Step 1: Delete the two `WidenStairsGoal` tests**

Delete `testWidenStairsGoalMarksRegionDirty` and
`testWidenStairsGoalDoesNotPlaceFloatingStairWhenSupportRemovedMidAction` in their entirety (both
methods shown in this plan's research — they reference the now-deleted `WidenStairsGoal` class and
have no project-scoped equivalent test needed here, since Task 6's
`SiegeProjectAutoWidenGameTests` already covers the widen-trigger and non-widenable-action cases;
the "doesn't place a floating stair when support is removed mid-action" safety property is already
covered structurally by `SiegeProject.tick()`'s `nextUnbuiltInstruction`/`isActionCompleted`
re-check on every call, which is exercised by Task 4's own `SiegeProjectTickGameTests` — no new
test needed here for that specific property; do not skip verifying this claim, though: after
deleting, confirm by reading `SiegeProject.tick()`'s loop from Task 4 that each iteration re-derives
`nextUnbuiltInstruction` fresh from live terrain rather than a stale snapshot, which it does).

- [ ] **Step 2: Rework `testBuildFlowFieldGoalCompletesMultiStepMacroChainWithNoPriorSupport`**

Replace the whole method (shown in full in this plan's research) with a version that injects one
`SiegeProject` covering the entire 4-step chain up front (instead of relying on per-block claiming
across 4 fresh goal instances), then drives 4 fresh goal instances against that SAME project
(fresh instances are still needed — `getPostActionCooldownTicks()`-equivalent state no longer
exists on this goal, but a fresh instance per step keeps the test's own step-by-step assertions
clean and mirrors the mob's real per-tick `canUse()`/`start()` re-evaluation as it moves):

```java
    @GameTest(template = "pathing_test", timeoutTicks = 400, skyAccess = true)
    public static void testBuildFlowFieldGoalCompletesMultiStepMacroChainWithNoPriorSupport(GameTestHelper helper) {
        BlockPos relativeStart = new BlockPos(4, 2, 4);

        BlockPos[] relativeChain = new BlockPos[5];
        relativeChain[0] = relativeStart;
        for (int i = 1; i <= 4; i++) {
            relativeChain[i] = relativeStart.offset(i, i, 0);
        }

        for (int i = 1; i <= 4; i++) {
            BlockPos step = relativeChain[i];
            helper.setBlock(step, Blocks.AIR.defaultBlockState());
            helper.setBlock(step.below(), Blocks.AIR.defaultBlockState());
            helper.setBlock(step.above(), Blocks.AIR.defaultBlockState());
            helper.setBlock(step.above(2), Blocks.AIR.defaultBlockState());
        }

        BlockPos startPos = helper.absolutePos(relativeStart);

        RecordingRegionMap owner = new RecordingRegionMap();
        FlowFieldState state = new FlowFieldState(startPos, Set.of(new ChunkPos(startPos)));

        Map<BlockPos, SiegeNode> instructions = new HashMap<>();
        List<SiegeNode> orderedSteps = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            BlockPos from = helper.absolutePos(relativeChain[i]);
            BlockPos to = helper.absolutePos(relativeChain[i + 1]);
            instructions.put(from, new SiegeNode(to, SiegeNode.SiegeAction.BUILD_STAIR));
            orderedSteps.add(new SiegeNode(to, SiegeNode.SiegeAction.BUILD_STAIR));
        }
        state.updateInstructions(instructions);

        TerrainEvaluator evaluator = new TerrainEvaluator();
        SiegeProjectManager projectManager = new SiegeProjectManager(evaluator);
        CalculationThrottler throttler = new CalculationThrottler();
        FlowFieldCalculator calculator = new FlowFieldCalculator(evaluator, projectManager, throttler);
        RegionFlowField flowField = new RegionFlowField(owner, 0, state, projectManager, calculator, throttler);

        BlockPos chainEndPos = helper.absolutePos(relativeChain[4]);
        SiegeProject project = new SiegeProject(instructions, orderedSteps, startPos, chainEndPos, 500);
        projectManager.addSharedConnectorProject(project);

        ClanratEntity mob = new ClanratEntity(ModEntities.CLANRAT.get(), helper.getLevel());
        mob.setPos(startPos.getX() + 0.5, startPos.getY(), startPos.getZ() + 0.5);
        helper.getLevel().addFreshEntity(mob);

        for (int i = 0; i < 4; i++) {
            BlockPos fromPos = helper.absolutePos(relativeChain[i]);
            BlockPos toPos = helper.absolutePos(relativeChain[i + 1]);
            mob.setPos(fromPos.getX() + 0.5, fromPos.getY(), fromPos.getZ() + 0.5);

            check(!helper.getLevel().getBlockState(toPos.below()).blocksMotion(),
                    "step " + i + "'s support at " + toPos.below() + " must be air BEFORE building - "
                            + "this is the whole point of the test (no support ever appears)");

            BuildFlowFieldGoal goal = new BuildFlowFieldGoal(mob);
            goal.setFlowField(flowField);
            check(goal.canUse(), "step " + i + ": goal should trigger for the next unbuilt chain step");
            goal.start();

            LiveTerrainAccess live = new LiveTerrainAccess(helper.getLevel());
            for (int t = 0; t < 60 && project.nextUnbuiltInstruction(live, evaluator)
                    .map(s -> s.pos().equals(toPos)).orElse(false); t++) {
                goal.tick();
            }
            goal.stop();

            helper.assertBlockState(relativeChain[i + 1], s -> s.is(Blocks.COBBLESTONE_STAIRS),
                    () -> "step " + i + " should have placed a stair at " + relativeChain[i + 1]);
        }

        helper.succeed();
    }
```

Add imports if not already present: `org.ratden.skavenblight.ai.pathing.LiveTerrainAccess`,
`org.ratden.skavenblight.ai.pathing.SiegeProject`, `org.ratden.skavenblight.ai.goal.clanrat.
BuildFlowFieldGoal`, `java.util.ArrayList`.

- [ ] **Step 3: Add the two new auto-widen regression tests this file's own deletions leave uncovered**

These duplicate `SiegeProjectAutoWidenGameTests`' coverage at the `SiegeProject` level but are
worth having at the goal-driven level too (mirroring how the deleted `WidenStairsGoal` tests
exercised the mechanism through a real goal, not just the underlying data class) — add:

```java
    @GameTest(template = "pathing_test", timeoutTicks = 200, skyAccess = true)
    public static void testBuildFlowFieldGoalRegistersOnAWidenedLane(GameTestHelper helper) {
        BlockPos relativeAnchor = new BlockPos(4, 2, 4);
        BlockPos relativeTarget = relativeAnchor.relative(Direction.EAST);
        BlockPos anchor = helper.absolutePos(relativeAnchor);
        BlockPos target = helper.absolutePos(relativeTarget);

        RecordingRegionMap owner = new RecordingRegionMap();
        FlowFieldState state = new FlowFieldState(anchor, Set.of(new ChunkPos(anchor)));
        state.updateInstructions(Map.of(anchor, new SiegeNode(target, SiegeNode.SiegeAction.BUILD_STAIR)));

        TerrainEvaluator evaluator = new TerrainEvaluator();
        SiegeProjectManager projectManager = new SiegeProjectManager(evaluator);
        CalculationThrottler throttler = new CalculationThrottler();
        FlowFieldCalculator calculator = new FlowFieldCalculator(evaluator, projectManager, throttler);
        RegionFlowField flowField = new RegionFlowField(owner, 0, state, projectManager, calculator, throttler);

        List<SiegeNode> orderedSteps = List.of(new SiegeNode(target, SiegeNode.SiegeAction.BUILD_STAIR));
        Map<BlockPos, SiegeNode> instructions = Map.of(target, new SiegeNode(anchor, SiegeNode.SiegeAction.BUILD_STAIR));
        SiegeProject project = new SiegeProject(instructions, orderedSteps, anchor, target, 500);
        projectManager.addSharedConnectorProject(project);

        LiveTerrainAccess live = new LiveTerrainAccess(helper.getLevel());
        List<ClanratEntity> filler = new ArrayList<>();
        for (int i = 0; i < org.ratden.skavenblight.Config.workersPerWidenStep; i++) {
            ClanratEntity rat = new ClanratEntity(ModEntities.CLANRAT.get(), helper.getLevel());
            rat.setPos(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
            helper.getLevel().addFreshEntity(rat);
            filler.add(rat);
            project.tryRegisterWorker(rat, live, evaluator, org.ratden.skavenblight.Config.projectWorkRadius,
                    org.ratden.skavenblight.Config.maxProjectWorkers, org.ratden.skavenblight.Config.workersPerWidenStep);
        }

        ClanratEntity extraRat = new ClanratEntity(ModEntities.CLANRAT.get(), helper.getLevel());
        extraRat.setPos(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
        helper.getLevel().addFreshEntity(extraRat);

        BuildFlowFieldGoal goal = new BuildFlowFieldGoal(extraRat);
        goal.setFlowField(flowField);

        check(goal.canUse(), "goal should trigger even though the project is nominally at its width-1 cap");
        goal.start();

        check(project.getWidth() > 1, "starting the goal on a full project should have triggered a widen");

        helper.succeed();
    }
```

Add imports: `org.ratden.skavenblight.ai.pathing.SiegeProject`,
`org.ratden.skavenblight.ai.pathing.LiveTerrainAccess`,
`org.ratden.skavenblight.ai.goal.clanrat.BuildFlowFieldGoal`, `java.util.ArrayList`.

- [ ] **Step 4: Compile and run the whole GameTest suite (Tasks 8-11 together)**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL — this is the first point since Task 8 where the build should compile
clean again.

Run: `./gradlew test && ./gradlew runGameTestServer`
Expected: every JUnit test PASSES; every GameTest in the whole project PASSES, specifically
including (but not limited to) `SiegeConstructionActionsGameTests`,
`PathingGoalRecalculationGameTests` (including its unrelated, pre-existing
`testMaxCandidateProjectLengthCaps*` cases — these must still pass unmodified, confirming this
overhaul didn't touch project-discovery/capping logic), `SiegeProjectTickGameTests`,
`SiegeProjectAutoWidenGameTests`, and `AwaitFormationGoalGameTests`. If
`PathingRegionGameTests.testParentRegionGetsRealInstructionsForSharedConnectorCells` still fails,
that's the pre-existing, already-documented failure (see the design doc's Known risks) — not a
regression from this plan.

- [ ] **Step 5: Commit Tasks 8-11 together**

```bash
git add src/main/java/org/ratden/skavenblight/ai/goal/clanrat/BuildFlowFieldGoal.java src/main/java/org/ratden/skavenblight/entity/custom/ClanratEntity.java src/main/java/org/ratden/skavenblight/ai/goal/clanrat/AwaitFormationGoal.java src/main/java/org/ratden/skavenblight/gametest/SiegeConstructionActionsGameTests.java src/main/java/org/ratden/skavenblight/gametest/PathingGoalRecalculationGameTests.java
git rm src/main/java/org/ratden/skavenblight/ai/goal/clanrat/WidenStairsGoal.java 2>/dev/null || true
git commit -m "feat(ai): migrate BuildFlowFieldGoal to project-worker registration, retire WidenStairsGoal"
```

---

## Task 12: Tighten `StaircaseSiegeGroupGameTests` timeouts to prove scaling

**Files:**
- Modify: `src/main/java/org/ratden/skavenblight/gametest/StaircaseSiegeGroupGameTests.java`

**Interfaces:**
- Consumes: nothing new — this task only changes `@GameTest(timeoutTicks = ...)` values on
  existing tests.

Today, `testSmallGroupBuildsStaircaseAcrossSmallGap` (4 rats) is allowed 8000 ticks and
`testLargeGroupBuildsStaircaseAcrossSmallGap` (10 rats) is allowed 12000 — MORE time than the
single rat's 6000, i.e. the current suite explicitly tolerates a group being slower than solo. That
was reasonable under the old per-block-claim contention model; under project-scoped worker
registration, a group should be at least as fast as solo, ideally much faster. Tightening these
timeouts to sit clearly BELOW the single-rat baseline is a concrete, deterministic proof that
scaling is real — if a larger group can't finish within a tighter budget than solo, that's scaling
NOT working, and the test correctly fails.

- [ ] **Step 1: Tighten the timeouts**

Change `testSmallGroupBuildsStaircaseAcrossSmallGap`'s `@GameTest` annotation from
`timeoutTicks = 8000` to `timeoutTicks = 5000` (below the single rat's 6000 — a small group should
be at least somewhat faster, four rats sharing one lane still contend for the same physical space
even with worker registration, so this is a conservative tightening, not an aggressive one).

Change `testLargeGroupBuildsStaircaseAcrossSmallGap`'s `@GameTest` annotation from
`timeoutTicks = 12000` to `timeoutTicks = 3000` (well below 6000 — 10 rats is exactly
`Config.workersPerWidenStep`'s default, i.e. this test's own rat count was already sized to match
one full lane's worker cap, so this is the primary scaling-proof case).

Leave `testSingleRatBuildsStaircaseAcrossSmallGap`'s 6000 and
`testLargeGroupBuildsChainedStaircaseAcrossGiantGap`'s 20000 unchanged — the single rat is the
baseline being compared against, and the giant-gap chained test is a different, harder scenario
(multi-hop chained crossing) not the primary target of this specific scaling claim.

- [ ] **Step 2: Run the whole suite repeatedly, per the design doc's verification bar**

`testSingleRatBuildsStaircaseAcrossSmallGap` has a documented history of non-deterministic failures
(see the design doc's Known risks / Background). A single green run proves nothing for this suite.

Run: `./gradlew runGameTestServer --tests "*StaircaseSiegeGroupGameTests*"` **10 consecutive times**.
Expected: all 4 tests pass in all 10 runs. If any run fails:
- If it's `testSingleRatBuildsStaircaseAcrossSmallGap` specifically failing with the previously
  documented "zero construction activity" symptom, this overhaul did NOT resolve that pre-existing
  bug — report this as a real, honest finding (per this plan's own Global Constraints and the
  design doc's Known risks), do not weaken the test or silently retry past it.
- If it's `testSmallGroupBuildsStaircaseAcrossSmallGap`/`testLargeGroupBuildsStaircaseAcrossSmallGap`
  timing out under their newly-tightened budgets, that means either the tightened numbers were too
  aggressive (loosen them somewhat and re-run the full 10) or worker registration/widening isn't
  actually scaling speed as intended (a real bug to investigate via `systematic-debugging`, not a
  test-tuning problem) — distinguish the two by checking whether the test's own failure message
  shows rats stuck (bug) vs. slowly-but-steadily progressing past the timeout (tuning).

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/gametest/StaircaseSiegeGroupGameTests.java
git commit -m "test(pathing): tighten group-staircase timeouts to prove worker-count scaling"
```
