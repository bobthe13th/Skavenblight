# Flow-Field Planning Gap Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

## Execution status (2026-08-01)

Tasks 1 and 2 are complete, committed, reviewed clean, and pushed (`cb946b1`,
`4150465` on branch `worktree-staircase-bounce-physics-fix2`). The defect
described below is real, confirmed via a controlled instrumented capture,
and the fix is verified by its own test with no regression to the existing
sibling test.

**Task 3 (real-world verification) is BLOCKED, not complete.** Running
`testSingleRatBuildsStaircaseAcrossSmallGap` in isolation still fails 100%
of the time post-fix (6 runs: the Task 3 subagent's 5 + one controller
repro), and - critically - the *same* isolated-failure signature (zero
`SiegeProjectManager` activity, rat stuck exactly at its region's connector
entry, Y=-58 vs nexus Y=-44) already appears in `gametest_verify3.log`/
`gametest_verify4.log`, captured earlier in the same investigation session
*before* this plan's chaining defect was even found. Across all 8 relevant
isolated-run observations gathered across the whole investigation, only
1 showed the chaining defect this plan fixes; the other 7 show this
different, zero-activity signature.

**Follow-up investigation, localized but not yet root-caused:** the
zero-activity signature very likely shares a root cause with the
pre-existing, separate `PathingRegionGameTests.testParentRegionGetsRealInstructionsForSharedConnectorCells`
failure (a connector cell resolving to a real region but
`RegionFlowField.getNextSiegeNode` returning null there - "a mob standing
there would be stuck"). A follow-up isolated run of that test precisely
localized its failure: of ~39 cells in a chained/multi-hop vertical
connector, exactly one - the last cell, right at the boundary into the
root region - resolves to a region but gets no flow-field instruction; all
other cells in the same connector resolve fine. This lives in the
interaction between `RegionGraph`'s multi-hop chaining loop (`MAX_CHAIN_HOPS`)
and `TerritoryRegionMap.injectSharedConnectorProjects`'s shared-cell
tie-break handling - an area with a documented prior "hardening" effort
(`docs/pathing/region-pathing-hardening-findings.md`) that is evidently
still not fully closed. The exact mechanism (why that one boundary cell
specifically) has not yet been traced - this is a real, well-scoped
starting point for a dedicated follow-up investigation, not a completed
diagnosis.

This plan's own scope (the reactive-macro-project cumulative-chaining
defect) is complete and should not be reopened or re-litigated by that
follow-up - it is a separate, distinct bug in a related but different part
of the same subsystem.

**Goal:** Stop a region's own reactive macro-project search (`SiegeProjectManager.evaluateMacroProjects`) from chaining past its intended short local-gap budget when that region already has a route-tree-assigned parent connector, so a mob standing at a connector's own entry point actually gets the connector's BUILD_STAIR instructions instead of getting stuck behind a self-inflicted flow-field cycle.

**Architecture:** No new classes or data flow. One additional early-return check inside the existing `SiegeProjectManager.evaluateMacroProjects` method, using data (`nextInstructionMap`) that method already receives as a parameter. Zero changes to `RegionGraph`, `RegionConnector`, `TerritoryRegionMap`, or `FlowFieldCalculator`'s cycle-breaking logic - all of that is already correct and stays untouched.

**Tech Stack:** Java 21, NeoForge 1.21.1 GameTest framework (no unit-test framework exists in this repo - see CLAUDE.md). Verification is via `./gradlew runGameTestServer`, same as every other test in this codebase.

## Global Constraints

- Run `./gradlew compileJava` after every code change in this plan, before moving to the next step.
- No automated unit tests exist in this repo - all "tests" in this plan are NeoForge `@GameTest` methods, verified by running `./gradlew runGameTestServer` and reading the log output (there is no faster/filtered way to run a single GameTest - see the isolation technique in Task 1 Step 2 below).
- Do not modify `RegionGraph.java`, `RegionConnector.java`, `TerritoryRegionMap.java`, or `FlowFieldCalculator.java`'s cycle-detection/breaking logic (`breakMutualCycles`/`pickCyclePositionToDrop`) as part of this plan - the confirmed root cause and fix are entirely inside `SiegeProjectManager.java`.

---

## Background: the confirmed root cause

**Symptom:** In some runs of `testSingleRatBuildsStaircaseAcrossSmallGap` (and presumably its siblings), the rat successfully walks all the way to its own region's connector entry point - the exact position the flow field is supposed to hand off a `BUILD_STAIR` chain from - and then never receives any construction instruction at all. No `BUILD_*` action ever fires there for the rest of the test. This was confirmed via an instrumented GameTest run: the rat's final stuck position (`11805279, -58, 4312478`) exactly matched its own region's computed connector target, logged as:

```
[DIAG] region 0 min=... max=... cells=502 isRoot=false parentConnector=A=0 B=2 target=11805279, -58, 4312478
```

**Confirmed mechanism, traced directly against source (not inferred):**

1. `TerritoryRegionMap.rebuildRegionsAndGraph` correctly identifies region 0 (the ground region, 502 cells) as a non-root region with a valid parent connector to region 2 (the root/nexus region), and correctly seeds that connector's own crossing project (14 `BUILD_STAIR` instructions) as region 0's active connector project before computing region 0's own flow field. This part of the pipeline is correct and is not touched by this plan.

2. Per `SiegeProjectManager.setMaxCandidateProjectLength`'s own doc, region 0's *own reactive* macro-project search (`evaluateMacroProjects`, triggered whenever `FlowFieldCalculator`'s Dijkstra flood hits an obstacle) is capped to a short 6-block budget specifically so it doesn't substitute for the connector graph's job of long-range connectivity - "long-range connectivity is that connector's job now, not this region's own reactive search."

3. That 6-block cap is enforced **per macro-project line, not per region-scoped pass.** `SiegeProjectManager.evaluateSingleLine` (called from `evaluateMacroProjects`, once per one of 14 directions) writes a successful line's result **directly** into the same pass's live state - `nextInstructionMap.putAll(result.instructions())`, `nextCostMap.put(endPos, totalCost)`, and pushes `endPos` back onto the **same pass's own Dijkstra `calcQueue`** (`SiegeProjectManager.java:233-235`). When a line hits its length cap without finding real terrain, `SiegeLineTracer.trace` terminates it with a synthetic `BUILD_LANDING` at that cap distance (not a genuine failure - `result.completed()` is still `true`).

4. Because that synthetic landing gets pushed back onto the **same** pass's `calcQueue`, `FlowFieldCalculator`'s main Dijkstra loop pops it as an ordinary frontier node shortly after, sees it still "hits an obstacle" (nothing real to walk onto yet), and fires `evaluateMacroProjects` again - **from that landing as a brand-new anchor, with `maxCandidateProjectLength` re-checked from zero every time.** Nothing tracks that this anchor is itself the tail end of an already-capped line.

5. Confirmed via the same instrumented run: this produced **three separate chained 6-block macro lines** in one single region-0 pass (`Successful Macro Line built from Y=-58 to Y=-52`, then `Y=-52 to Y=-46`, then `Y=-46 to Y=-44`), reaching a cumulative 14 blocks - all the way from region 0's own connector-entry area into region 2's own territory (the nexus platform, Y=-44). This produces a second, independently-discovered path to the same destination the connector already handles, competing with the connector's own 14 already-`locked` instructions for the same physical space.

6. `FlowFieldCalculator`'s existing (and correct) cycle-breaking logic detects the resulting graph cycle and resolves it in favor of the locked (connector) positions - `"kept every locked position in this cycle (14 of 30), dropped one of the non-locked ones"` - but dropping a single node from the reactively-discovered competing path is not sufficient to prevent the underlying conflict from leaving the mob's actual standing position (the connector's own entry point) without a resolvable next instruction.

**The fix:** stop the chain at its source. A region with a route-tree-assigned parent connector (a capped `maxCandidateProjectLength`) should get **at most one** local macro-project line per obstacle, never a multi-hop chain built from successive synthetic landings - multi-hop long-range connectivity is exactly what the connector graph (`RegionGraph`'s own `MAX_CHAIN_HOPS` loop, a completely separate and already-correct mechanism - see `docs/superpowers/specs/2026-07-25-region-graph-connector-chaining-design.md`) already exists to provide. This is done by checking, at the top of `evaluateMacroProjects`, whether the anchor position already carries a synthetic `BUILD_LANDING` instruction from an earlier line **this same pass** - if so, and this region is capped, refuse to continue the chain. Regions with no parent connector yet (the default, uncapped 32-length budget) are completely unaffected - they still need to chain freely to establish their own connectivity in the first place.

This is a single, minimal, targeted change confined to `SiegeProjectManager.evaluateMacroProjects`. It does not touch the 14-direction fan-out from a single anchor (so the existing `testMaxCandidateProjectLengthCapsMacroProjectReach` test's assumptions are unaffected), and does not touch uncapped/default-length regions at all.

---

## File Structure

- **Modify:** `src/main/java/org/ratden/skavenblight/ai/pathing/SiegeProjectManager.java` - the fix itself (one early-return check in `evaluateMacroProjects`).
- **Modify:** `src/main/java/org/ratden/skavenblight/gametest/PathingGoalRecalculationGameTests.java` - add one new `@GameTest` method, immediately after the existing `testMaxCandidateProjectLengthCapsMacroProjectReach` (same file, same class, same hand-built-pathing-objects pattern already established there - see that file's own class javadoc).

No new files. No changes to `RegionGraph.java`, `RegionConnector.java`, `TerritoryRegionMap.java`, or `FlowFieldCalculator.java`.

---

### Task 1: Prove the cross-call chaining defect with a failing GameTest

**Files:**
- Modify: `src/main/java/org/ratden/skavenblight/gametest/PathingGoalRecalculationGameTests.java` (add new test method after `testMaxCandidateProjectLengthCapsMacroProjectReach`, which currently ends at line 460 with `helper.succeed(); }` followed by the class's closing `}` at line 461)

**Interfaces:**
- Consumes: `SiegeProjectManager` (existing, no changes yet this task), `SiegeProjectManager.evaluateMacroProjects(TerrainAccess, BlockPos, FlowFieldState, int, PriorityQueue<FlowFieldCalculator.QueueNode>, Map<BlockPos,Integer>, Map<BlockPos,SiegeNode>)`, `SiegeProjectManager.setMaxCandidateProjectLength(int)`, `LiveTerrainAccess` (existing, constructed from a `ServerLevel`), `FlowFieldState(BlockPos, Set<ChunkPos>)` 2-arg constructor (existing), `SiegeNode.SiegeAction.BUILD_LANDING` (existing enum constant), `check(boolean, String)` (existing static helper already imported/used in this file).
- Produces: nothing new consumed by later tasks - this is a standalone proof test.

- [ ] **Step 1: Write the failing test**

Insert this new method into `PathingGoalRecalculationGameTests.java` immediately after `testMaxCandidateProjectLengthCapsMacroProjectReach`'s closing brace (i.e., right after the line `helper.succeed(); }` that ends that method, before the class's own closing `}`):

```java
    /**
     * Proves setMaxCandidateProjectLength's cap applies to the WHOLE region-scoped reactive
     * search across a pass, not just to a single macro-project line. FlowFieldCalculator's own
     * Dijkstra loop fires evaluateMacroProjects again on any frontier node that still "hits an
     * obstacle" - including a synthetic BUILD_LANDING a previous capped line just terminated on
     * (see evaluateSingleLine, which pushes a successful line's own endPos back onto the SAME
     * pass's calcQueue). Before the fix, each such chained call got a completely fresh
     * maxCandidateProjectLength budget, letting a region capped to 6 blocks discover a path far
     * longer than 6 blocks by chaining several 6-block-capped lines end to end - confirmed via a
     * live GameTest capture reaching 14 blocks across 3 chained hops, well past a 6-block cap,
     * reaching all the way into a different region's own territory (see
     * docs/superpowers/plans/2026-08-01-flow-field-planning-gap-fix.md for the full trace).
     *
     * <p>Reuses testMaxCandidateProjectLengthCapsMacroProjectReach's own geometry technique
     * (remove the floor east of the anchor to force BUILD_BRIDGE), but doubles the open span to
     * 20 blocks so BOTH a first 6-block-capped line AND a hypothetical (bugged) second chained
     * 6-block line reaching 12 blocks out would still land in open air, not real ground - the
     * only way to prove the SECOND call's budget is actually constrained by the first call's own
     * usage, rather than simply being re-granted a fresh 6 blocks.
     */
    @GameTest(template = "pathing_test", timeoutTicks = 100, skyAccess = true)
    public static void testMaxCandidateProjectLengthCapsWholeSearchNotJustOneLine(GameTestHelper helper) {
        BlockPos relativeAnchor = new BlockPos(4, 2, 4);
        BlockPos anchorPos = helper.absolutePos(relativeAnchor);

        for (int i = 1; i <= 20; i++) {
            helper.setBlock(relativeAnchor.offset(i, -1, 0), Blocks.AIR.defaultBlockState());
        }

        BlockPos firstCappedLanding = helper.absolutePos(relativeAnchor.offset(6, 0, 0));
        BlockPos secondChainedLanding = helper.absolutePos(relativeAnchor.offset(12, 0, 0));

        TerrainEvaluator evaluator = new TerrainEvaluator();
        LiveTerrainAccess terrain = new LiveTerrainAccess(helper.getLevel());
        FlowFieldState state = new FlowFieldState(anchorPos, Set.of());

        Map<BlockPos, Integer> costMap = new HashMap<>();
        Map<BlockPos, SiegeNode> instructionMap = new HashMap<>();
        PriorityQueue<FlowFieldCalculator.QueueNode> queue = new PriorityQueue<>();

        // One shared manager across BOTH calls - mirrors how FlowFieldCalculator's own Dijkstra
        // loop reuses a single SiegeProjectManager instance across every evaluateMacroProjects
        // call within one region-scoped pass (see FlowFieldCalculator.calculate's hitObstacle
        // branch), which is exactly the scenario this test exercises.
        SiegeProjectManager manager = new SiegeProjectManager(evaluator);
        manager.setMaxCandidateProjectLength(6);

        manager.evaluateMacroProjects(terrain, anchorPos, state, 0, queue, costMap, instructionMap);

        SiegeNode firstLandingNode = instructionMap.get(firstCappedLanding);
        check(firstLandingNode != null && firstLandingNode.action() == SiegeNode.SiegeAction.BUILD_LANDING,
                "first call should cap at 6 blocks east with a synthetic BUILD_LANDING at " + firstCappedLanding
                        + " (found: " + firstLandingNode + ")");

        // Simulate the Dijkstra flood reaching that landing next and firing another macro
        // evaluation from there - exactly what FlowFieldCalculator's own loop does for any
        // frontier node that still hits an obstacle (a synthetic BUILD_LANDING always does,
        // since there's nothing real to walk onto yet).
        int firstLandingCost = costMap.getOrDefault(firstCappedLanding, 0);
        manager.evaluateMacroProjects(terrain, firstCappedLanding, state, firstLandingCost, queue, costMap, instructionMap);

        check(!instructionMap.containsKey(secondChainedLanding),
                "setMaxCandidateProjectLength(6) should cap the region's WHOLE reactive search to 6 blocks total "
                        + "per pass, not 6 blocks PER macro-project call - a second chained call from the first "
                        + "call's own landing should not extend the search any further, but found instructions "
                        + "reaching a second landing 12 blocks out at " + secondChainedLanding + ": "
                        + instructionMap.keySet());

        helper.succeed();
    }
```

- [ ] **Step 2: Run it to confirm it fails**

This repo has no way to run a single `@GameTest` in isolation via Gradle args, and running the full suite is slow. Temporarily disable every OTHER `@GameTestHolder`-annotated class so only `PathingGoalRecalculationGameTests` runs, restoring them immediately after:

```bash
# From the repo root, comment out every OTHER GameTestHolder class-level annotation (do NOT
# touch PathingGoalRecalculationGameTests.java itself):
#   AwaitFormationGoalGameTests.java
#   SiegeConstructionActionsGameTests.java
#   PathingRegionGameTests.java
#   StaircaseSiegeGroupGameTests.java
# For each, change:  @GameTestHolder(Skavenblight.MODID)
# to:                // @GameTestHolder(Skavenblight.MODID)   (plus a one-line comment noting
#                    // this is temporary and must be restored before committing)
./gradlew compileJava
./gradlew runGameTestServer --console=plain > /tmp/gap-fix-task1.log 2>&1
grep -n "testmaxcandidateprojectlengthcapswholesearchnotjustoneline" /tmp/gap-fix-task1.log
```

Expected: a `LogTestReporter` ERROR line containing `"setMaxCandidateProjectLength(6) should cap the region's WHOLE reactive search..."` - the test fails, confirming the defect exists. If it unexpectedly passes, stop and re-verify the geometry/assertions against the actual mechanism described above before proceeding - do not continue to Task 2 without a confirmed-red test.

Immediately after confirming the failure, restore the 4 temporarily-disabled `@GameTestHolder` annotations (`git checkout -- src/main/java/org/ratden/skavenblight/gametest/AwaitFormationGoalGameTests.java src/main/java/org/ratden/skavenblight/gametest/SiegeConstructionActionsGameTests.java src/main/java/org/ratden/skavenblight/gametest/PathingRegionGameTests.java src/main/java/org/ratden/skavenblight/gametest/StaircaseSiegeGroupGameTests.java`) - `git status` should show only `PathingGoalRecalculationGameTests.java` modified before continuing.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/gametest/PathingGoalRecalculationGameTests.java
git commit -m "test(pathing): prove SiegeProjectManager's project-length cap doesn't apply cumulatively across chained macro-project calls"
```

---

### Task 2: Fix `SiegeProjectManager.evaluateMacroProjects` to refuse to extend an already-capped chain

**Files:**
- Modify: `src/main/java/org/ratden/skavenblight/ai/pathing/SiegeProjectManager.java:176-209` (the `evaluateMacroProjects` method body)

**Interfaces:**
- Consumes: `SiegeNode.SiegeAction.BUILD_LANDING` (existing), `SiegeProjectManager.DEFAULT_MAX_CANDIDATE_PROJECT_LENGTH` (existing `public static final int`, already defined at line 34), `this.maxCandidateProjectLength` (existing private field).
- Produces: no new public methods or fields - `evaluateMacroProjects`'s existing signature is unchanged, only its internal behavior changes. Task 1's test is the only caller/consumer that observes this behavior difference.

- [ ] **Step 1: Confirm Task 1's test is still failing (sanity check before fixing)**

Skip if you just finished Task 1 in the same session and already confirmed the failure. Otherwise repeat Task 1 Step 2's isolation-run procedure once to reconfirm red before making any production code change.

- [ ] **Step 2: Implement the fix**

In `src/main/java/org/ratden/skavenblight/ai/pathing/SiegeProjectManager.java`, change the start of `evaluateMacroProjects` from:

```java
    public void evaluateMacroProjects(TerrainAccess terrain, BlockPos anchorPos, FlowFieldState state, int anchorCost,
                                      PriorityQueue<FlowFieldCalculator.QueueNode> calcQueue,
                                      Map<BlockPos, Integer> nextCostMap,
                                      Map<BlockPos, SiegeNode> nextInstructionMap) {

        macroEvaluationCount++;
```

to:

```java
    public void evaluateMacroProjects(TerrainAccess terrain, BlockPos anchorPos, FlowFieldState state, int anchorCost,
                                      PriorityQueue<FlowFieldCalculator.QueueNode> calcQueue,
                                      Map<BlockPos, Integer> nextCostMap,
                                      Map<BlockPos, SiegeNode> nextInstructionMap) {

        // A region with a route-tree-assigned parent connector (see setMaxCandidateProjectLength's
        // own doc) gets a short local-gap budget specifically so its OWN reactive search never
        // substitutes for the connector graph's job of long-range connectivity. That intent held
        // for a single macro-project line, but not for a CHAIN of them: evaluateSingleLine writes
        // a successful line's own endPos directly back onto this SAME pass's calcQueue (see its own
        // body), so FlowFieldCalculator's Dijkstra loop pops it as an ordinary frontier node and
        // fires evaluateMacroProjects again from there - a synthetic BUILD_LANDING always "hits an
        // obstacle" (see FlowFieldCalculator's isPlannedLanding check), so this repeats. Each such
        // chained call previously got a completely fresh maxCandidateProjectLength budget, letting
        // a region capped to (say) 6 blocks discover a path far longer than 6 blocks by chaining
        // several 6-block-capped lines end to end - confirmed via GameTest reaching 14 blocks
        // across 3 chained hops, all the way into a DIFFERENT region's own territory, producing a
        // flow-field cycle against that region's own route-tree-assigned connector project (see
        // docs/superpowers/plans/2026-08-01-flow-field-planning-gap-fix.md for the full trace).
        // Refusing to continue a chain - rather than tracking a cumulative step budget across the
        // 14-direction fan-out below, which would also restrict how far a single legitimate
        // obstacle's own multi-direction search can reach in one call - keeps a capped region's
        // search to "one obstacle, one line, one hop": exactly the "short local-gap" scope the cap
        // was always meant to provide. Regions with no parent connector yet (the default, uncapped
        // budget) are unaffected - they still need to chain freely to establish their own
        // connectivity in the first place.
        SiegeNode existingInstruction = nextInstructionMap.get(anchorPos);
        boolean isChainedLanding = existingInstruction != null
                && existingInstruction.action() == SiegeNode.SiegeAction.BUILD_LANDING;
        if (isChainedLanding && this.maxCandidateProjectLength < DEFAULT_MAX_CANDIDATE_PROJECT_LENGTH) {
            return;
        }

        macroEvaluationCount++;
```

Every line after `macroEvaluationCount++;` in the existing method body is unchanged.

- [ ] **Step 3: Compile**

```bash
./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Run Task 1's test again to confirm it now passes**

Repeat the same isolation procedure from Task 1 Step 2 (disable the other 4 `@GameTestHolder` classes, run, check the log, restore them):

```bash
./gradlew runGameTestServer --console=plain > /tmp/gap-fix-task2.log 2>&1
grep -n "testmaxcandidateprojectlengthcapswholesearchnotjustoneline" /tmp/gap-fix-task2.log
```

Expected: no ERROR line for this test in the log (a passing `@GameTest` produces no `LogTestReporter` ERROR entry - absence of the failure line, plus the run's own final summary not listing this test under "required tests failed", is the pass signal). Also re-run `testMaxCandidateProjectLengthCapsMacroProjectReach` in the same log and confirm it *also* still passes unchanged - this fix must not regress the single-call case.

Restore the 4 temporarily-disabled `@GameTestHolder` annotations before continuing, exactly as in Task 1 Step 2.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/ai/pathing/SiegeProjectManager.java
git commit -m "fix(pathing): stop a capped region's reactive macro-project search from chaining past its own budget across synthetic landings"
```

---

### Task 3: Verify the real-world scenario - `testSingleRatBuildsStaircaseAcrossSmallGap` and its siblings

**Files:** none modified - this task is verification only, using the existing `src/main/java/org/ratden/skavenblight/gametest/StaircaseSiegeGroupGameTests.java` (already committed, unmodified by this plan).

**Interfaces:**
- Consumes: the fix from Task 2. No new interfaces.
- Produces: nothing consumed by later tasks - this is the plan's final verification gate.

- [ ] **Step 1: Run the single-rat test in isolation, repeated**

This specific test/subsystem has documented pre-existing flakiness unrelated to this fix (see `docs/pathing/region-pathing-hardening-findings.md`, Finding D, and the separate `testParentRegionGetsRealInstructionsForSharedConnectorCells` failure) - a single run passing or failing is not conclusive on its own. Using the same isolation technique as Tasks 1-2 (temporarily disable the other 4 `@GameTestHolder` classes, but this time also temporarily disable `StaircaseSiegeGroupGameTests`'s OTHER 3 test methods - `testSmallGroupBuildsStaircaseAcrossSmallGap`, `testLargeGroupBuildsStaircaseAcrossSmallGap`, `testLargeGroupBuildsChainedStaircaseAcrossGiantGap` - by commenting out their `@GameTest(...)` annotations, restoring afterward), run the isolated single test 5 times in a row:

```bash
for i in 1 2 3 4 5; do
  ./gradlew runGameTestServer --console=plain > "/tmp/gap-fix-task3-run$i.log" 2>&1
  echo "=== run $i ==="
  grep -n "testsingleratbuildsstaircaseacrosssmallgap\|clearing before placement\|Broke flow-field cycle" "/tmp/gap-fix-task3-run$i.log"
done
```

Expected: no more occurrences of the "zero construction activity, mob stuck exactly at its own region's connector target" signature from this plan's Background section. A `Broke flow-field cycle` line may still appear for unrelated reasons (it's a general-purpose safety net, not specific to this bug) - what matters is whether the mob's own connector-crossing chain fires (`BUILD_STAIR` at the connector's own entry point) rather than the mob sitting there forever with zero logged activity.

Restore all temporarily-disabled test annotations (`git checkout -- src/main/java/org/ratden/skavenblight/gametest/`) before continuing - `git status` should show a clean working tree relative to Task 2's commit.

- [ ] **Step 2: Record the outcome honestly**

This plan fixes one confirmed, specific defect. It does not claim to fix `testParentRegionGetsRealInstructionsForSharedConnectorCells` (a separate pre-existing failure, not investigated as part of this plan) or Finding D's documented full-suite concurrency flakiness. If Step 1's 5 runs still show occasional unrelated failures, that is expected and out of scope - only a recurrence of THIS plan's specific signature (zero construction activity at a region's own connector target) indicates this fix is incomplete.

No commit for this step - it's a verification record, not a code change. If the plan's target defect does recur, stop and re-open investigation (do not attempt a second blind patch on top of this one - see the systematic-debugging skill's guidance on 3+ fix attempts).
