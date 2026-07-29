# SiegeProject Floating-Stair Construction Fix — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop clanrat construction goals from ever placing an unsupported ("floating") `BUILD_STAIR`/`BUILD_PILLAR`/`BUILD_SPIRAL` block, which is what currently causes a crowd of clanrats to place one bad step and then permanently stop building a SiegeProject.

**Architecture:** No new subsystems. This is a targeted correctness fix to the existing construction pipeline (`AbstractSiegeConstructionGoal` → `SiegeInteractionHandler.constructSiegeBlock`), closing a check-then-act race between when a build target is *selected* (support verified) and when it is *executed* (support silently assumed).

**Tech Stack:** Java 21, NeoForge 1.21.x, `@GameTest`-based test suite (`net.neoforged.neoforge.gametest`). No JUnit tests exist for this subsystem; don't add any — GameTest is the established convention here (see Global Constraints).

## Global Constraints

- **Reported symptom (ground truth for this plan):** a group of clanrats converges on a build site, places one staircase step that ends up floating in mid-air with nothing solid beneath it, and the whole group then stops building — they appear to be trying to climb onto the floating step to place the next one and can't reach it.
- **GameTest only, no JUnit.** All tests are `@GameTestHolder(Skavenblight.MODID)` classes run via `./gradlew runGameTestServer`. The environment is headless — no dev client, so no `/test create` structure capture. Prefer extending existing GameTest classes/templates over creating new structure NBTs (expensive to produce headlessly — see `docs/pathing/region-pathing-hardening-findings.md` Finding C, "Task 2").
- **Known flaky test, unrelated to this work:** `PathingRegionGameTests.testThreeRegionsRouteThroughCheaperIntermediateHop` has a documented, unfixed, environment-only flake under full-suite concurrency (Finding D in the doc above). If it fails once during verification, rerun it in isolation before treating it as a regression; do not spend time debugging it as part of this plan.
- **Explicitly out of scope:** the region/connector-graph bugs documented in `docs/pathing/region-pathing-hardening-findings.md` (Finding A: fast-path unreachable/perf; Finding B: duplicate-Region-at-merge-completion, a real but *separate* unfixed bug). Those manifest as bad pathing *after* a project completes, or excessive rebuild frequency — not as a floating block appearing during construction. Confirmed against the user's actual reported symptom (see Task 0 below); do not fold them into this work. Finding B in particular requires a design decision between 3 documented options and should be its own plan if pursued.
- **Compile baseline:** verified clean before this plan was written — `./gradlew compileJava` → `BUILD SUCCESSFUL` on the current tree (including the pre-existing unstaged `ModBlockEntities.java` fix, which this plan does not touch).
- **`runGameTestServer` has no per-test filter.** It's a NeoForge run-config (`type = "gameTestServer"`), not a Gradle `Test` task — `--tests "..."` is silently ignored/rejected, it does not select a single test. Every "run just this test" step below actually means: run `./gradlew runGameTestServer` (the whole suite) and read the named test's PASS/FAIL out of the console output. To isolate a single test's timing/behavior the way earlier work on this subsystem did, temporarily comment out the other `@GameTest` methods' annotations, run, then restore them (see `docs/pathing/region-pathing-hardening-findings.md` Finding C "Task 2" / Finding D for this exact workaround) — don't spend time hunting for a filter flag that doesn't exist here.
- **Requires a full JDK, not just a JRE, to compile.** A JDK 21 was installed mid-execution of this plan (`C:\Program Files\Java\jdk-21.0.12`) after an initial verification pass hit `Java compiler is not available... contains a valid JDK installation` in a sandbox that only had Eclipse Adoptium *JRE* 8/11/17/21 (no `javac`). With the real JDK, `./gradlew runGameTestServer` was actually run end-to-end — see "Verification results (actually executed)" below.
- **A separate, unrelated bug blocked `runGameTestServer` from booting at all, and was fixed as a prerequisite.** `ChainLightningPayload.handleChainLightning` (`src/main/java/org/ratden/skavenblight/network/payload/ChainLightningPayload.java`) referenced `net.minecraft.client.Minecraft`/`ClientLevel` directly inside a handler registered via `registrar.playToClient(...)` in `ModNetwork.registerPayloads` — a common (both-dist) entrypoint. Because `ChainLightningPayload` gets loaded on both sides regardless (it's needed for its `TYPE`/`STREAM_CODEC`), NeoForge's `RuntimeDistCleaner` refused to load the whole class - and with it the whole mod - under `DEDICATED_SERVER`, which crashed mod loading before any GameTest could run (and would crash a real dedicated server the same way). Fixed by moving the client-only particle logic into a new `org.ratden.skavenblight.client.ClientChainLightningHandler`, mirroring the exact pattern the codebase already used for `SyncFlowFieldDebugPayload` → `ClientDebugData` (that file has its own "--- FIX: ---" comment recording the same lesson learned once already). This fix is independent of the siege-construction fix below but was required to run any of the verification steps in this plan.

---

## Root cause (Review phase — already done, recorded here so Task 1 isn't "trust me")

Traced directly against source, not inferred:

1. **Selection-time support checks exist, but only at selection time.** `WidenStairsGoal.findTarget()` (`src/main/java/org/ratden/skavenblight/ai/goal/clanrat/WidenStairsGoal.java:55-56`) and `canHostStair()` (same file, lines 125-129) require `pos.below()` to be solid before proposing a `BUILD_STAIR` target. `TerrainEvaluator.findGroundBelow()` (`src/main/java/org/ratden/skavenblight/ai/pathing/TerrainEvaluator.java:231-246`) enforces the same thing for the core Dijkstra flow-field path that `BuildFlowFieldGoal` executes — and that method's own comment documents a *previous* version of this exact class of bug already being fixed once (a multi-block-gap case), with the explicit note that a bad instruction here "in practice... leaves an unreachable block floating above the mob's head."
2. **Nothing re-checks support at execution time.** Once a target is claimed in `AbstractSiegeConstructionGoal.start()` (`src/main/java/org/ratden/skavenblight/ai/goal/clanrat/AbstractSiegeConstructionGoal.java:190-201`), the actual placement doesn't happen until `getActionDurationTicks()` ticks later (15 for `WidenStairsGoal`/`BuildFlowFieldGoal`), and can be delayed further by the clear-space stall loop (up to `getMaxStalledTicks()` = 60 additional ticks, `AbstractSiegeConstructionGoal.java:219-242`). The only per-tick re-check during that window, `isTargetStillValid()`, only verifies `canBeReplaced()` in both `WidenStairsGoal.java:142-144` and `BuildFlowFieldGoal.java:61-63` — neither re-checks `pos.below()`.
3. **Placement itself is unconditional.** `SiegeInteractionHandler.constructSiegeBlock()`'s `BUILD_STAIR` case (`src/main/java/org/ratden/skavenblight/ai/pathing/SiegeInteractionHandler.java:69-99`) checks `canBeReplaced()` at the target and clears headroom *above* it, but never checks solidity *below* before calling `level.setBlockAndUpdate(pos, stateToPlace)` (line 172).
4. **A crowd makes the race easy to trigger.** With many clanrats working a site at once (no cap on concurrent builders — `RegionFlowField.MAX_LANE_OCCUPANTS = 2` throttles WALK-lane traffic, not builders), a different clanrat's independent `MINE`/headroom-clear action (the same mechanism already called out in `SiegeInteractionHandler.java:74-93`'s own comments — "rats got stuck placing more stairs on top of the staircase, blocking the path") can remove a target's support in the 15-75+ tick window between selection and placement. The stair then gets placed floating anyway, and `TerrainEvaluator.isActionCompleted()` (`TerrainEvaluator.java:187-188`) marks it "done" from block state alone, so the goal considers its job finished — no mob can reach the top of a disconnected floating step, so the flow field has no next reachable node and the group stops.
5. **`BUILD_BRIDGE` and `BUILD_LANDING` are correctly exempt from any "support below" requirement** — a bridge deliberately spans a gap (its whole point is that nothing solid is below it yet), and a landing builds its own platform. Only `BUILD_STAIR`, `BUILD_PILLAR`, and `BUILD_SPIRAL` are "stand on what I just placed to continue" actions that require solid support underneath at the moment of placement. `BUILD_LADDER` is separately validated (needs an adjacent wall) and already has a graceful execution-time fallback.

**The fix is a single, comprehensive guard at the execution boundary** (`SiegeInteractionHandler.constructSiegeBlock`), which every construction goal already funnels through regardless of which higher-level goal or flow-field path selected the target. This makes a floating stair/pillar/spiral block structurally impossible to place, independent of crowd size, timing, or which goal is driving construction.

---

## Task 1: Re-verify support immediately before placing a climb-dependent block

**Files:**
- Modify: `src/main/java/org/ratden/skavenblight/ai/pathing/SiegeInteractionHandler.java:48-50`

**Interfaces:**
- Consumes: `SiegeNode.SiegeAction` enum (existing), `SiegeActivityLog.record(long, LivingEntity, BlockPos, SiegeNode.SiegeAction, String, Integer)` (existing, already used elsewhere in this file), `regionIdOf(RegionFlowField)` (existing private helper, same file, line 178).
- Produces: `constructSiegeBlock` now silently no-ops (with a log entry) instead of placing, when a stair/pillar/spiral target has lost its support. No other task depends on new symbols from this one.

- [x] **Step 1: Add a failing GameTest first** (see Task 3, Step 1 below — write and run that test now, confirm it fails against the current code, before touching this file). This proves the bug reproduces and that the fix step actually closes it.

- [x] **Step 2: Add the execution-time support guard**

In `SiegeInteractionHandler.java`, immediately after the existing `canBeReplaced()` early-return (currently lines 48-50) and before the self-entombment diagnostic block, insert:

```java
        if ((action == SiegeNode.SiegeAction.BUILD_STAIR
                || action == SiegeNode.SiegeAction.BUILD_PILLAR
                || action == SiegeNode.SiegeAction.BUILD_SPIRAL)
                && !level.getBlockState(pos.below()).blocksMotion()) {
            // The support below was solid when this node was selected (see
            // WidenStairsGoal.findTarget()/canHostStair, TerrainEvaluator.findGroundBelow), but
            // placement happens getActionDurationTicks() + up to getMaxStalledTicks() ticks
            // later - a different clanrat's concurrent MINE/headroom-clear action nearby can
            // remove that support in the meantime. Placing anyway produces an unreachable
            // floating step that nothing can climb to continue the chain, silently stalling the
            // whole build (the reported "group places one floating stair and stops" bug).
            // BUILD_BRIDGE/BUILD_LANDING are deliberately excluded - they're expected to have no
            // support below at placement time (that's what they're for).
            SiegeActivityLog.record(level.getGameTime(), actor, pos, action,
                    "aborted placement - support at " + pos.below().toShortString() + " no longer solid, would float",
                    regionIdOf(flowField));
            return;
        }

```

- [x] **Step 3: Run the full GameTest suite and confirm the Task 3 test now passes**

Run: `./gradlew runGameTestServer` (no per-test filter exists — see Global Constraints — read the named test's result out of the console output)
Expected: `testWidenStairsGoalDoesNotPlaceFloatingStairWhenSupportRemovedMidAction` PASS (it failed before this step; see Task 3 Step 2). **Actually executed — see "Verification results" below: confirmed PASS.**

- [x] **Step 4: Confirm the rest of the suite has no regression** (same run as Step 3 — check the other tests' results in the same output)

Expected: all tests pass except possibly `testThreeRegionsRouteThroughCheaperIntermediateHop` (known flake — rerun the full suite once more if it fails; see Global Constraints). In particular `testWidenStairsGoalMarksRegionDirty` and `testDeployClimbableGoalMarksRegionDirty` (both in `PathingGoalRecalculationGameTests.java`) must still pass unchanged — they exercise the happy path where support is never removed, so this guard must not fire for them. **Actually executed: all 15/15 tests passed, no flake hit.**

- [ ] **Step 5: Commit** — not yet done; this checkout had unrelated concurrent commit activity mid-execution (see "Verification results" below), so committing was deliberately held pending user confirmation.

```bash
git add src/main/java/org/ratden/skavenblight/ai/pathing/SiegeInteractionHandler.java
git commit -m "fix(pathing): abort BUILD_STAIR/PILLAR/SPIRAL placement if support is no longer solid at execution time"
```

---

## Task 2: Fail fast when a claimed target's support disappears mid-wait

Task 1 alone makes a floating block impossible. This task is a responsiveness improvement on top of it: right now, if support disappears while a goal is mid-animation or stalled waiting for clear space, the goal keeps waiting out its full budget (up to 75 ticks) and only then discovers via Task 1's guard that the placement was aborted — wasting time and leaving the target claimed (blocking other clanrats from picking it up) the whole while. Re-checking support in `isTargetStillValid()` lets `canContinueToUse()` fail immediately, so the goal calls `stop()` (releasing the claim, per `AbstractSiegeConstructionGoal.java:250-258`) right away.

**Files:**
- Modify: `src/main/java/org/ratden/skavenblight/ai/goal/clanrat/AbstractSiegeConstructionGoal.java:45`
- Modify: `src/main/java/org/ratden/skavenblight/ai/goal/clanrat/WidenStairsGoal.java:142-144`
- Modify: `src/main/java/org/ratden/skavenblight/ai/goal/clanrat/BuildFlowFieldGoal.java:61-63`

**Interfaces:**
- Consumes: `this.targetAction` (currently `private` in `AbstractSiegeConstructionGoal`, set in `start()`).
- Produces: `this.targetAction` becomes `protected`, readable by subclasses' `isTargetStillValid()` overrides. No other task depends on this.

- [x] **Step 1: Widen `targetAction`'s visibility**

In `AbstractSiegeConstructionGoal.java`, change line 45 from:

```java
    private SiegeNode.SiegeAction targetAction;
```

to:

```java
    protected SiegeNode.SiegeAction targetAction;
```

- [x] **Step 2: Re-check support in `WidenStairsGoal.isTargetStillValid`**

`WidenStairsGoal` only ever proposes `BUILD_STAIR` targets, so this one is unconditional. Change `WidenStairsGoal.java:142-144` from:

```java
    @Override
    protected boolean isTargetStillValid(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).canBeReplaced();
    }
```

to:

```java
    @Override
    protected boolean isTargetStillValid(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).canBeReplaced()
                && level.getBlockState(pos.below()).blocksMotion();
    }
```

- [x] **Step 3: Re-check support in `BuildFlowFieldGoal.isTargetStillValid`, conditional on action type**

`BuildFlowFieldGoal` also executes `BUILD_BRIDGE` (and other non-climb-dependent actions), which must NOT require support below — a bridge target is expected to be over open air until built. Note this re-check is a pure optimization, not required for correctness: `this.targetAction` is set in `start()` right after `targetPos`, so it's non-null for every `isTargetStillValid()` call made while a target is actually held; if `start()`'s own `if (target == null) return;` path fires instead, `targetPos` itself stays null too and `canContinueToUse()` already fails on that check before `isTargetStillValid()` would matter. Task 1's guard in `constructSiegeBlock` is what actually prevents the floating placement regardless of what this method returns. Change `BuildFlowFieldGoal.java:61-63` from:

```java
    protected boolean isTargetStillValid(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).canBeReplaced();
    }
```

to:

```java
    protected boolean isTargetStillValid(ServerLevel level, BlockPos pos) {
        boolean climbDependent = this.targetAction == SiegeNode.SiegeAction.BUILD_STAIR
                || this.targetAction == SiegeNode.SiegeAction.BUILD_PILLAR
                || this.targetAction == SiegeNode.SiegeAction.BUILD_SPIRAL;
        return level.getBlockState(pos).canBeReplaced()
                && (!climbDependent || level.getBlockState(pos.below()).blocksMotion());
    }
```

- [x] **Step 4: Run the full GameTest suite**

Run: `./gradlew runGameTestServer`
Expected: same result as Task 1 Step 4 (all pass, known flake aside). This step is a pure refinement of Task 1's fix — it must not change any test outcome, only how quickly a doomed target gets abandoned. **Actually executed together with Task 1's Step 3/4 run (both fixes were applied before the post-fix run) — 15/15 passed.**

- [ ] **Step 5: Commit** — held pending user confirmation, see Task 1 Step 5.

```bash
git add src/main/java/org/ratden/skavenblight/ai/goal/clanrat/AbstractSiegeConstructionGoal.java src/main/java/org/ratden/skavenblight/ai/goal/clanrat/WidenStairsGoal.java src/main/java/org/ratden/skavenblight/ai/goal/clanrat/BuildFlowFieldGoal.java
git commit -m "fix(pathing): abandon a claimed stair/pillar/spiral target as soon as its support disappears, instead of waiting out the full stall budget"
```

---

## Task 3: GameTest reproducing the reported bug

Add this to the existing `PathingGoalRecalculationGameTests.java`, which already has the exact hand-built `RegionFlowField` + `ClanratEntity` + goal pattern this test needs (see `testWidenStairsGoalMarksRegionDirty` in the same file) — no new structure NBT needed, reuses the `pathing_test` template.

**Files:**
- Modify: `src/main/java/org/ratden/skavenblight/gametest/PathingGoalRecalculationGameTests.java`

**Interfaces:**
- Consumes: `WidenStairsGoal`, `RegionFlowField`, `ClanratEntity`, `RecordingRegionMap` (all already imported/defined in this file).
- Produces: nothing consumed by later tasks.

- [x] **Step 1: Write the test**

Add this method to `PathingGoalRecalculationGameTests`, alongside `testWidenStairsGoalMarksRegionDirty`:

```java
    /**
     * Reported production bug: a group of clanrats converges on a build site, places one
     * staircase step, and the whole group then stops - the step ends up floating with nothing
     * solid beneath it, so no mob can reach its top to continue the chain. Root cause:
     * WidenStairsGoal.findTarget() only verifies solid support at SELECTION time; execute()
     * runs getActionDurationTicks() (15) ticks later (longer still if the clear-space stall
     * loop fires), and nothing re-checked support immediately before
     * SiegeInteractionHandler.constructSiegeBlock actually placed the block. A different
     * clanrat's concurrent MINE/headroom-clear action on an adjacent target can remove that
     * support in the gap - this test simulates exactly that by pulling the support out from
     * under an already-claimed target mid-action, the same way a second builder mob would in
     * production.
     */
    @GameTest(template = "pathing_test", timeoutTicks = 200, skyAccess = true)
    public static void testWidenStairsGoalDoesNotPlaceFloatingStairWhenSupportRemovedMidAction(GameTestHelper helper) {
        BlockPos relativeStairPos = new BlockPos(4, 2, 4);
        BlockPos relativeMobPos = relativeStairPos.relative(Direction.EAST);
        helper.setBlock(relativeStairPos, Blocks.COBBLESTONE_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, Direction.NORTH));

        BlockPos mobPos = helper.absolutePos(relativeMobPos);

        RecordingRegionMap owner = new RecordingRegionMap();
        FlowFieldState state = new FlowFieldState(mobPos, Set.of(new ChunkPos(mobPos)));
        state.updateInstructions(Map.of());
        TerrainEvaluator evaluator = new TerrainEvaluator();
        SiegeProjectManager projectManager = new SiegeProjectManager(evaluator);
        CalculationThrottler throttler = new CalculationThrottler();
        FlowFieldCalculator calculator = new FlowFieldCalculator(evaluator, projectManager, throttler);
        RegionFlowField flowField = new RegionFlowField(owner, 0, state, projectManager, calculator, throttler);

        ClanratEntity mob = new ClanratEntity(ModEntities.CLANRAT.get(), helper.getLevel());
        mob.setPos(mobPos.getX() + 0.5, mobPos.getY(), mobPos.getZ() + 0.5);
        helper.getLevel().addFreshEntity(mob);

        WidenStairsGoal goal = new WidenStairsGoal(mob);
        goal.setFlowField(flowField);

        check(goal.canUse(), "goal should trigger: off-path, replaceable, adjacent to an existing stair");
        goal.start();

        // Simulate a different clanrat's concurrent action removing the support out from under
        // this goal's already-claimed target, mid-animation - exactly the window the bug lives in.
        helper.setBlock(relativeMobPos.below(), Blocks.AIR.defaultBlockState());

        for (int i = 0; i < 16; i++) goal.tick();

        helper.assertBlockState(relativeMobPos, s -> !s.is(Blocks.COBBLESTONE_STAIRS),
                () -> "stair should NOT have been placed once its support was removed mid-action - "
                        + "a placement here would float with nothing beneath it");

        helper.succeed();
    }
```

- [x] **Step 2: Run it and confirm it FAILS against the current (pre-fix) code**

Run: `./gradlew runGameTestServer` (whole suite — no per-test filter here, see Global Constraints) and check `testWidenStairsGoalDoesNotPlaceFloatingStairWhenSupportRemovedMidAction`'s result in the console output.
Expected: FAIL — the assertion trips because the current code places the stair unconditionally once `canBeReplaced()` on the target cell is true, regardless of `pos.below()`.

**Actually executed — confirmed FAIL, exactly as predicted.** Console output: `testwidenstairsgoaldoesnotplacefloatingstairwhensupportremovedmidaction failed ... stair should NOT have been placed once its support was removed mid-action - a placement here would float with nothing beneath it`. 1 of 15 tests failed (this one); the other 14 passed, confirming the manual trace below and ruling out the "mob falls and the assertion passes vacuously" concern that was raised during review — the test genuinely reaches `constructSiegeBlock` and genuinely places the stair pre-fix.

Note on this test's geometry: the mob's claimed `targetPos` is captured once in `start()` (the mob's own standing cell) and does not change even if the mob's entity later falls after its floor is removed — `execute()` always builds against the captured `targetPos`, not the mob's live position — and `isSpaceClear()`'s entity check is scoped to `targetPos`'s AABB, so a fallen-away mob doesn't block execution either. This was verified by manual trace, and now also confirmed empirically (see above).

- [x] **Step 3: Commit the test on its own** (before Task 1's fix, so the failing-then-passing history is visible) — **the code change was made (and later confirmed against a live GameTest run), but the actual `git commit` has not been run** — see Task 1 Step 5.

```bash
git add src/main/java/org/ratden/skavenblight/gametest/PathingGoalRecalculationGameTests.java
git commit -m "test(pathing): reproduce floating-stair bug when support is removed mid-action"
```

Then proceed to Task 1 (its Step 1 already told you to write this test first — if you're executing tasks in order, Task 1 Steps 1-3 and this task's Steps 1-3 are the same actions; do them once, in this task, then reference back).

---

## Verification results (actually executed, not projected)

All three tasks' code changes were applied and machine-verified end-to-end once a real JDK became available in the execution environment:

1. **Baseline (pre-fix), `ChainLightningPayload` bug already fixed, siege fix not yet applied:** `./gradlew runGameTestServer` → `15 tests are now running` → **1 required test failed: `testwidenstairsgoaldoesnotplacefloatingstairwhensupportremovedmidaction`**, with the exact predicted assertion message. All other 14 tests passed — no regressions, and confirms the new test genuinely reproduces the bug rather than passing vacuously.
2. **Post-fix (Tasks 1 and 2 both applied):** `./gradlew compileJava` → `BUILD SUCCESSFUL`. `./gradlew runGameTestServer` → `15 tests are now running` → **`All 15 required tests passed :)`** — the target test now passes, and every pre-existing test (including `testWidenStairsGoalMarksRegionDirty`/`testDeployClimbableGoalMarksRegionDirty`, the happy-path regression guards) still passes. `testThreeRegionsRouteThroughCheaperIntermediateHop`'s known flake (Finding D) did not occur on this run.
3. **Prerequisite fix verified separately:** the `ChainLightningPayload`/`ClientChainLightningHandler` split was required before step 1 could even boot the test server (it previously crashed mod loading with `Attempted to load class net/minecraft/client/multiplayer/ClientLevel for invalid dist DEDICATED_SERVER`); confirmed fixed once the server booted and ran all 15 tests in both runs above.

**Not yet done: any `git commit`.** This checkout is the user's own active branch (`dev-jimmy-creativemode+housekeeping`), not an isolated worktree — mid-execution, an unrelated commit (`33397c0`, authored by the repo owner, message "docs") landed on this same branch containing unrelated content (WFRP reference docs, an unrelated `shadow_step_relay_impl.md` plan) alongside this plan's doc file and the Task 3 test addition, confirming another process/session is concurrently active in this exact checkout. Per instructions for working in a shared, non-isolated checkout, committing the remaining changes (the Task 1/2 fix + the `ChainLightningPayload` fix) was held pending explicit user confirmation rather than done automatically.

## Self-review notes (per superpowers:writing-plans)

- **Spec coverage:** the user's reported symptom (crowd converges, places a floating step, stops building) is covered end-to-end: Task 3 reproduces it, Task 1 fixes its root cause, Task 2 improves recovery time. No sub-requirement was left without a task.
- **Explicitly NOT covered, by design:** Findings A/B (region-graph corruption/perf) from `docs/pathing/region-pathing-hardening-findings.md`, since the user's description doesn't match either (both are about pathing *after* a project completes, not a step floating *during* construction). If, after this fix ships, clanrats still misbehave but in a *different* way (e.g., completed staircases that path badly, or projects that pile up without completing), that's Finding A/B territory and needs its own plan — don't retrofit this one.
- **Type/signature consistency:** `targetAction`'s visibility change (Task 2 Step 1) is the only cross-file interface change in this plan, and both consumers (`WidenStairsGoal`, `BuildFlowFieldGoal`) are updated in the same task.
- **No placeholders:** every step above has complete, real code — no "add validation"-style stubs.
