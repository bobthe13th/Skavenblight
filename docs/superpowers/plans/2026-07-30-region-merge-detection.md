# Region Merge Detection (Finding B fix) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `TerritoryRegionMap.recomputeDirtyRegions` correctly recognize when a dirty-region rescan has silently absorbed a *different* region (a genuine merge), instead of misreading it as "no topology change" — the confirmed, previously-deliberately-unfixed Finding B in `docs/pathing/region-pathing-hardening-findings.md`, caught causing a real routing loop in production.

**Architecture:** One new method (`Region.cellsNotIn`) computes the cells a fresh rescan gained relative to the old region, via cheap per-chunk `BitSet` arithmetic. `recomputeDirtyRegions`'s existing `rescanned.size() != 1` check gets one more condition: if any of those newly-gained cells already belong (per the current `RegionIndex`) to a different region ID, that's proof of a merge, and the existing full-rebuild path — which already reconstructs regions and connectors from scratch — handles the rest for free.

**Tech Stack:** Java 21, NeoForge 1.21.x, `@GameTest`-based test suite. No JUnit — GameTest is the established convention in this codebase.

## Global Constraints

- **Spec:** `docs/superpowers/specs/2026-07-30-region-merge-detection-design.md` — read it before starting; this plan implements it exactly, not a reinterpretation.
- **No behavior change to provisional connector-cell dual-membership.** `RegionGraph.registerConnector`'s `addCell` calls on both endpoint regions for an unbuilt connector's traced path are correct and untouched — this fix only concerns detecting when those cells *become* genuinely walkable and two regions should collapse into one.
- **No new "which region ID wins" logic.** Confirmed by reading `rebuildRegionsAndGraph` directly (`RegionScanner.scan` + `RegionGraph.build`, both built fresh from the current terrain/region set every time) — once a merge is routed through the existing full-rebuild path, there is nothing stale left to clean up.
- **GameTest only, no JUnit**, run via `./gradlew runGameTestServer` (no per-test filter — read the whole suite's console output for the named test's result). Requires a real JDK on `PATH`/`JAVA_HOME` (a JRE-only environment reports `Java compiler is not available`).
- **`testRepeatedConnectorCompletionsDontExplodeRebuildCount`** (in `PathingRegionGameTests.java`) already reproduces the exact bug this plan fixes, and its own comments explicitly anticipate this fix changing its result — Task 2 updates that test's assertions rather than writing a parallel one from scratch.
- **Compile baseline:** verify `./gradlew compileJava` reports `BUILD SUCCESSFUL` before starting Task 1.

---

## Task 1: `Region.cellsNotIn` — the delta computation

**Files:**
- Modify: `src/main/java/org/ratden/skavenblight/ai/pathing/region/Region.java`
- Test: `src/main/java/org/ratden/skavenblight/gametest/PathingRegionGameTests.java` (add one test method)

**Interfaces:**
- Consumes: nothing new — `Region`'s existing `chunkCells: Map<ChunkPos, BitSet>` field, `height`/`minBuildHeight` fields, and `addCell(BlockPos)`.
- Produces: `public Set<BlockPos> cellsNotIn(Region other)` on `Region` — cells in `this` that are not in `other`. Task 2 consumes this exact signature.

- [ ] **Step 1: Write the failing test**

Add to `PathingRegionGameTests.java` (anywhere among the other region-focused tests — e.g. right after `testSingleConnectedRegion`, near the top of the class):

```java
    /**
     * Region.cellsNotIn in isolation, no scanner/rebuild needed - two hand-built Region objects
     * with a partial cell overlap, confirming the delta is exactly "cells in the first but not
     * the second" and nothing else. This is the primitive Task 2's merge-detection check is
     * built on (see docs/superpowers/specs/2026-07-30-region-merge-detection-design.md).
     */
    @GameTest(template = "pathing_test", timeoutTicks = 100, skyAccess = true)
    public static void testCellsNotInReturnsOnlyTheNewCells(GameTestHelper helper) {
        int minBuildHeight = helper.getLevel().getMinBuildHeight();
        int height = helper.getLevel().getMaxBuildHeight() - minBuildHeight;

        BlockPos shared = helper.absolutePos(new BlockPos(4, 2, 4));
        BlockPos onlyInA = helper.absolutePos(new BlockPos(5, 2, 4));
        BlockPos onlyInB = helper.absolutePos(new BlockPos(6, 2, 4));

        Region regionA = new Region(0, minBuildHeight, height);
        regionA.addCell(shared);
        regionA.addCell(onlyInA);

        Region regionB = new Region(1, minBuildHeight, height);
        regionB.addCell(shared);
        regionB.addCell(onlyInB);

        Set<BlockPos> delta = regionA.cellsNotIn(regionB);

        check(delta.size() == 1, "expected exactly 1 cell in A but not B, found " + delta.size() + ": " + delta);
        check(delta.contains(onlyInA), "delta should contain the cell only A has - found: " + delta);
        check(!delta.contains(shared), "delta should NOT contain the cell both regions share - found: " + delta);
        check(!delta.contains(onlyInB), "delta should NOT contain a cell only B has - found: " + delta);

        // Symmetry check: B's delta against A should be the mirror image.
        Set<BlockPos> reverseDelta = regionB.cellsNotIn(regionA);
        check(reverseDelta.size() == 1 && reverseDelta.contains(onlyInB),
                "expected exactly 1 cell in B but not A (onlyInB) - found: " + reverseDelta);

        helper.succeed();
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew runGameTestServer` (whole suite — no per-test filter exists in this codebase's setup)
Expected: compile failure — `cellsNotIn` doesn't exist on `Region` yet.

- [ ] **Step 3: Implement `cellsNotIn`**

In `Region.java`, add right after `cellCount()`:

```java
    /**
     * Cells in this region that are NOT in {@code other} - one BitSet.andNot() per chunk this
     * region touches, not a full re-scan. Used by TerritoryRegionMap's dirty-rescan merge
     * detection (see docs/superpowers/specs/2026-07-30-region-merge-detection-design.md): the
     * cells THIS region gained relative to the pre-rescan old region are exactly the cells worth
     * checking for foreign ownership - a genuine merge's absorbed territory shows up entirely as
     * "new" cells here, while an ordinary small change's delta stays small.
     */
    public Set<BlockPos> cellsNotIn(Region other) {
        Set<BlockPos> delta = new HashSet<>();
        for (Map.Entry<ChunkPos, BitSet> entry : this.chunkCells.entrySet()) {
            ChunkPos chunk = entry.getKey();
            BitSet newBits = (BitSet) entry.getValue().clone();
            BitSet otherBits = other.chunkCells.get(chunk);
            if (otherBits != null) {
                newBits.andNot(otherBits);
            }
            for (int i = newBits.nextSetBit(0); i >= 0; i = newBits.nextSetBit(i + 1)) {
                delta.add(posFromCellIndex(chunk, i));
            }
        }
        return delta;
    }

    /** Exact inverse of cellIndex(BlockPos) - same chunk-local packing, run backward. */
    private BlockPos posFromCellIndex(ChunkPos chunk, int index) {
        int localY = index % height;
        int remainder = index / height;
        int localX = remainder / 16;
        int localZ = remainder % 16;
        return new BlockPos(chunk.getMinBlockX() + localX, minBuildHeight + localY, chunk.getMinBlockZ() + localZ);
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew compileJava` then `./gradlew runGameTestServer`
Expected: `BUILD SUCCESSFUL`, then `testCellsNotInReturnsOnlyTheNewCells` passes, and every pre-existing test in the suite still passes.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/ai/pathing/region/Region.java src/main/java/org/ratden/skavenblight/gametest/PathingRegionGameTests.java
git commit -m "feat(pathing): add Region.cellsNotIn for cheap per-chunk cell-delta computation"
```

---

## Task 2: Wire merge detection into `recomputeDirtyRegions`

**Files:**
- Modify: `src/main/java/org/ratden/skavenblight/ai/pathing/region/TerritoryRegionMap.java:590`
- Modify: `src/main/java/org/ratden/skavenblight/gametest/PathingRegionGameTests.java` (update `testRepeatedConnectorCompletionsDontExplodeRebuildCount`'s final assertions, add one new small test)

**Interfaces:**
- Consumes: `Region.cellsNotIn(Region)` (Task 1), `RegionIndex.regionIdAt(BlockPos): Integer` (already exists, already O(1)).
- Produces: `recomputeDirtyRegions`'s fast path now only fires for a genuine non-merge change. No new public API — this task's effect is only observable via the tests below and via the existing counters (`getTopologyRebuildCount()`/`getBlockChangeRebuildCount()`).

- [ ] **Step 1: Update the existing bug-reproduction test's assertions to expect correct behavior**

In `PathingRegionGameTests.java`, find `testRepeatedConnectorCompletionsDontExplodeRebuildCount`'s final block (currently ends with a `check(regionMap.getTopologyRebuildCount() <= 3, ...)` call right before `regionMap.cleanup(helper.getLevel());`). Replace that single check with:

```java
            // Post-fix: the merge-completing placement must now correctly register as a
            // topology change (the whole point of this fix), so this bound is expected to be
            // one higher than the pre-fix threshold, not the same number - see
            // docs/superpowers/specs/2026-07-30-region-merge-detection-design.md for why this
            // moving is the fix working, not a regression.
            check(regionMap.getTopologyRebuildCount() <= 4,
                    "filling in one " + FILL_COLUMN_COUNT + "-block-wide connector triggered "
                            + regionMap.getTopologyRebuildCount()
                            + " full topology rebuilds - expected at most a handful, not one per block");

            // The actual regression guard for Finding B: exactly one region should now cover
            // both probes (no stale duplicate), and no connector should remain listed between
            // ids that no longer represent distinct regions.
            check(finalRegions.size() == 1,
                    "expected the near and far regions to have fully merged into exactly one "
                            + "region once the connector's gap closed - found " + finalRegions.size()
                            + " regions instead (extra regions mean the merge was missed)");
            check(nearProbeId != null && nearProbeId.equals(farProbeId),
                    "near and far probes should resolve to the SAME region after a genuine merge - "
                            + "nearProbeId=" + nearProbeId + " farProbeId=" + farProbeId);
            check(finalConnectors.stream().noneMatch(c ->
                            (c.regionA() == nearProbeId || c.regionB() == nearProbeId)
                                    && (c.regionA() == farProbeId || c.regionB() == farProbeId)),
                    "no connector should remain between the near and far region ids after they've "
                            + "genuinely merged into one region - found a stale one");
```

(`finalRegions`, `finalConnectors`, `nearProbeId`, `farProbeId` are all already computed earlier in this same `succeedWhen` callback — this step only replaces the trailing assertion, it doesn't need new setup.)

- [ ] **Step 2: Run the full suite and confirm this test now fails**

Run: `./gradlew runGameTestServer`
Expected: `testRepeatedConnectorCompletionsDontExplodeRebuildCount` FAILS — most likely on the `finalRegions.size() == 1` check (today it reports 2, the duplicate). This confirms the new assertions actually target the bug.

- [ ] **Step 3: Implement the merge-detection check**

In `TerritoryRegionMap.java`, change line 590 from:

```java
            boolean topologyChanged = rescanned.size() != 1;
```

to:

```java
            boolean absorbedForeignRegion = rescanned.size() == 1 && rescanned.get(0).cellsNotIn(oldRegion).stream()
                    .anyMatch(pos -> {
                        Integer owner = regionIndex.regionIdAt(pos);
                        return owner != null && owner != regionId;
                    });
            boolean topologyChanged = rescanned.size() != 1 || absorbedForeignRegion;
```

(Guarding with `rescanned.size() == 1` before computing the delta avoids doing that work at all in the already-handled split case, where `topologyChanged` is already `true` regardless.)

- [ ] **Step 4: Run the full suite and confirm the updated test now passes**

Run: `./gradlew compileJava` then `./gradlew runGameTestServer`
Expected: `BUILD SUCCESSFUL`, then `testRepeatedConnectorCompletionsDontExplodeRebuildCount` passes with the new assertions, and every other pre-existing test still passes. `testThreeRegionsRouteThroughCheaperIntermediateHop` may flake under full-suite concurrency per the documented Finding D — rerun the suite once if only that one fails.

- [ ] **Step 5: Add a small guard test confirming the fast path still fires for an ordinary, non-merging change**

Add to `PathingRegionGameTests.java`:

```java
    /**
     * Confirms Task 2's merge-detection check doesn't over-trigger: an ordinary block change with
     * no connector involved at all must still take the fast path (blockChangeRebuildCount
     * increments, topologyRebuildCount does NOT), exactly as before this fix. Mirrors
     * testConnectorCellsSurviveADirtyRegionRescan's geometry but WITHOUT a connector - a single
     * region, one unrelated interior block change.
     */
    @GameTest(template = "pathing_test", timeoutTicks = 8000, skyAccess = true)
    public static void testOrdinaryChangeWithNoConnectorStillTakesFastPath(GameTestHelper helper) {
        BlockPos relativeNexusPos = new BlockPos(4, 1, 4);
        helper.setBlock(relativeNexusPos, Blocks.STONE.defaultBlockState());
        BlockPos nexusPos = helper.absolutePos(relativeNexusPos);
        Set<ChunkPos> territory = Set.of(new ChunkPos(nexusPos));

        BlockPos unrelatedPos = helper.absolutePos(new BlockPos(10, 2, 10));

        TerritoryRegionMap regionMap = new TerritoryRegionMap();
        regionMap.rebuild(helper.getLevel(), territory, nexusPos);

        boolean[] changeReported = {false};

        helper.succeedWhen(() -> {
            regionMap.tick(helper.getLevel());
            check(!regionMap.isCalculating(), "region map still calculating");

            if (!changeReported[0]) {
                regionMap.onBlockChanged(unrelatedPos);
                changeReported[0] = true;
                check(false, "waiting for the dirty recompute to run");
            }

            check(regionMap.getBlockChangeRebuildCount() > 0,
                    "expected the fast path to have run at least once by now (blockChangeRebuildCount="
                            + regionMap.getBlockChangeRebuildCount() + ")");
            check(regionMap.getTopologyRebuildCount() == 0,
                    "an unrelated single-region change with no connector should never trigger a full "
                            + "topology rebuild - topologyRebuildCount=" + regionMap.getTopologyRebuildCount());

            regionMap.cleanup(helper.getLevel());
        });
    }
```

- [ ] **Step 6: Run the full suite once more to confirm everything passes together**

Run: `./gradlew runGameTestServer`
Expected: all tests pass, including the two from this task and the one from Task 1.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/ai/pathing/region/TerritoryRegionMap.java src/main/java/org/ratden/skavenblight/gametest/PathingRegionGameTests.java
git commit -m "fix(pathing): detect a completing region merge instead of silently missing it (Finding B)"
```

---

## Task 3: Harden `AwaitFormationGoal.findFormationSlot()` against unbuilt connector cells

**Files:**
- Modify: `src/main/java/org/ratden/skavenblight/ai/goal/clanrat/AwaitFormationGoal.java`
- Test: `src/main/java/org/ratden/skavenblight/gametest/AwaitFormationGoalGameTests.java`

**Interfaces:**
- Consumes: nothing new.
- Produces: `findFormationSlot()`'s candidate loop now requires live solid ground below the candidate, not just `region.contains()`.

- [ ] **Step 1: Write the failing test**

Add to `AwaitFormationGoalGameTests.java`:

```java
    /**
     * region.contains() returns true for a provisionally-claimed-but-unbuilt connector cell too
     * (same bitset as a genuinely walkable cell - see
     * docs/superpowers/specs/2026-07-30-region-merge-detection-design.md's background
     * invariants). This proves findFormationSlot() doesn't hand out such a cell as a "safe"
     * waiting spot just because region.contains() says yes - it must also have real solid ground
     * beneath it right now.
     */
    @GameTest(template = "pathing_test", timeoutTicks = 400, skyAccess = true)
    public static void testFindFormationSlotRejectsUnbuiltConnectorCell(GameTestHelper helper) {
        BlockPos relativeMobPos = new BlockPos(4, 2, 4);
        BlockPos relativeContestedTarget = relativeMobPos.offset(1, 1, 0);
        // Simulates an unbuilt connector cell right next to the mob: region-member (added below
        // via addCell, exactly like registerConnector would), but genuinely open air underneath.
        BlockPos relativeUnbuiltConnectorCell = relativeMobPos.offset(1, 0, 0);
        helper.setBlock(relativeUnbuiltConnectorCell, Blocks.AIR.defaultBlockState());
        helper.setBlock(relativeUnbuiltConnectorCell.below(), Blocks.AIR.defaultBlockState());

        helper.setBlock(relativeContestedTarget, Blocks.AIR.defaultBlockState());
        helper.setBlock(relativeContestedTarget.below(), Blocks.AIR.defaultBlockState());
        helper.setBlock(relativeContestedTarget.above(), Blocks.AIR.defaultBlockState());
        helper.setBlock(relativeContestedTarget.above(2), Blocks.AIR.defaultBlockState());

        BlockPos mobPos = helper.absolutePos(relativeMobPos);
        BlockPos contestedTarget = helper.absolutePos(relativeContestedTarget);
        BlockPos unbuiltConnectorCell = helper.absolutePos(relativeUnbuiltConnectorCell);

        TerritoryRegionMap regionMap = new TerritoryRegionMap();
        Set<ChunkPos> territory = Set.of(new ChunkPos(mobPos));
        regionMap.rebuild(helper.getLevel(), territory, mobPos);

        helper.succeedWhen(() -> {
            check(!regionMap.isCalculating(), "region map still calculating");
            List<Region> regions = regionMap.getRegionIndex().getRegions();
            check(!regions.isEmpty(), "test structure should scan into at least one region");
            Region region = regions.get(0);
            // Provisionally claim the unbuilt connector cell into the region - exactly what
            // RegionGraph.registerConnector does at connector-discovery time, before anything is
            // actually built.
            region.addCell(unbuiltConnectorCell);
            check(region.contains(unbuiltConnectorCell),
                    "sanity check - the provisionally-claimed cell must report contains()=true, "
                            + "matching production's registerConnector behavior");

            FlowFieldState state = new FlowFieldState(mobPos, territory);
            state.updateInstructions(Map.of(mobPos, new SiegeNode(contestedTarget, SiegeNode.SiegeAction.BUILD_STAIR)));
            TerrainEvaluator evaluator = new TerrainEvaluator();
            SiegeProjectManager projectManager = new SiegeProjectManager(evaluator);
            CalculationThrottler throttler = new CalculationThrottler();
            FlowFieldCalculator calculator = new FlowFieldCalculator(evaluator, projectManager, throttler);
            RegionFlowField flowField = new RegionFlowField(regionMap, region.getId(), state, projectManager, calculator, throttler);

            ClanratEntity mob = new ClanratEntity(ModEntities.CLANRAT.get(), helper.getLevel());
            mob.setPos(mobPos.getX() + 0.5, mobPos.getY(), mobPos.getZ() + 0.5);
            helper.getLevel().addFreshEntity(mob);
            mob.assignFlowField(flowField);

            ClanratEntity claimant = new ClanratEntity(ModEntities.CLANRAT.get(), helper.getLevel());
            claimant.setPos(contestedTarget.getX() + 0.5, contestedTarget.getY(), contestedTarget.getZ() + 0.5);
            helper.getLevel().addFreshEntity(claimant);
            flowField.tryClaimTarget(contestedTarget, claimant);

            AwaitFormationGoal goal = new AwaitFormationGoal(mob);
            goal.setFlowField(flowField);

            check(goal.canUse(), "goal should trigger: the mob's only nearby work is claimed by a different mob");
            goal.start();

            check(goal.canContinueToUse(), "the mob should still be seeking/holding a formation slot");
            check(!flowField.isFormationSlotClaimed(unbuiltConnectorCell),
                    "the unbuilt connector cell must NEVER be selected as a formation slot, even "
                            + "though region.contains() reports it as a member");

            regionMap.cleanup(helper.getLevel());
        });
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew runGameTestServer`
Expected: FAIL — today's `findFormationSlot()` has no live-terrain check, so it's free to select `unbuiltConnectorCell` (region-member, unclaimed, not an instruction-map key) as the very first ring candidate at radius 1 if the ring search reaches it before anything else.

- [ ] **Step 3: Add the live solid-ground check**

In `AwaitFormationGoal.java`'s `findFormationSlot()`, change:

```java
                    BlockPos candidate = searchOrigin.offset(dx, 0, dz);
                    if (!region.contains(candidate)) continue;
                    if (this.flowField.isFormationSlotClaimed(candidate)) continue;
                    if (this.flowField.getInstructionMap().containsKey(candidate)) continue;
                    return Optional.of(candidate);
```

to:

```java
                    BlockPos candidate = searchOrigin.offset(dx, 0, dz);
                    if (!region.contains(candidate)) continue;
                    // region.contains() alone isn't enough: a provisionally-claimed-but-unbuilt
                    // connector cell reports true too (see RegionGraph.registerConnector and
                    // docs/superpowers/specs/2026-07-30-region-merge-detection-design.md's
                    // background invariants) - require real, current solid ground beneath the
                    // candidate as well, the same live-terrain check
                    // TerrainEvaluator.isWalkableTerrain uses for the identical reason.
                    if (!this.mob.level().getBlockState(candidate.below()).blocksMotion()) continue;
                    if (this.flowField.isFormationSlotClaimed(candidate)) continue;
                    if (this.flowField.getInstructionMap().containsKey(candidate)) continue;
                    return Optional.of(candidate);
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew compileJava` then `./gradlew runGameTestServer`
Expected: `BUILD SUCCESSFUL`, all tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/ai/goal/clanrat/AwaitFormationGoal.java src/main/java/org/ratden/skavenblight/gametest/AwaitFormationGoalGameTests.java
git commit -m "fix(pathing): AwaitFormationGoal never selects an unbuilt connector cell as a waiting slot"
```

---

## Self-review notes (per superpowers:writing-plans)

- **Spec coverage:** the design's core fix (delta-based merge detection, Task 1+2), the follow-on `findFormationSlot()` hardening (Task 3), and the explicit non-goals (provisional dual-membership unchanged, no new ID-selection logic, Finding A's deeper architecture untouched, dynamic width-scaling not started) are all either implemented or explicitly restated as out of scope. No spec section lacks a task.
- **Placeholder scan:** every step has complete, real code (including the exact before/after for the one-line change in `TerritoryRegionMap.java` and the exact assertion block being replaced in the existing test) - no "add appropriate handling" language anywhere.
- **Type/signature consistency:** `Region.cellsNotIn(Region): Set<BlockPos>` (Task 1) is consumed with that exact signature in Task 2's `recomputeDirtyRegions` change. `RegionConnector.regionA()`/`regionB()` (verified against the actual record definition, not assumed) are used consistently in Task 2's Step 1 assertion.
- **Verified against actual code before writing, not assumed:** `rebuildRegionsAndGraph`'s fresh `RegionScanner.scan` + `RegionGraph.build` calls (confirming no separate connector cleanup is needed), `RegionIndex.regionIdAt`'s O(1) cost, `RegionConnector`'s exact accessor names, and the exact current line/text of `recomputeDirtyRegions`'s `topologyChanged` check and `testRepeatedConnectorCompletionsDontExplodeRebuildCount`'s current final assertion.
