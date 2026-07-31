# Region-pathing hardening — key findings

This document replaces in-code citations of `task-N-report.md` files. Those reports lived in an
untracked SDD working directory (`.superpowers/sdd/2026-07-27-region-pathing-hardening/`) used
during the `devJimmy_gius2_hardening` hardening effort and were never committed to this repository
- any citation pointing at them dangles for anyone who doesn't have that working directory. This
file captures, in the codebase itself, everything those citations were actually pointing at:
the two most significant findings from that effort (A and B below), a set of smaller measured
facts and conventions individual tasks relied on (C), and one finding from branch-finishing
verification after the effort concluded (D).

All classes/methods named below live under
`src/main/java/org/ratden/skavenblight/ai/pathing/region/` and
`src/main/java/org/ratden/skavenblight/gametest/` unless stated otherwise.

## Finding A: the steady-state "fast path" is structurally unreachable for any connector-bearing region

`TerritoryRegionMap.recomputeDirtyRegions` has two branches for a dirty region: a cheap,
region-local recompute (the "fast path", meant to be the common case - the entire point of the
incremental dirty-region design and the original spec's "block placing/breaking must not trigger
heavy recalculation" requirement), and an expensive full-network `rebuildRegionsAndGraph` (taken
whenever the dirty region's local rescan reports more than one connected sub-region -
`topologyChanged = rescanned.size() != 1`).

**In practice, the full rebuild path is unconditionally taken for any region that participates in
a connector - production territories included, not just GameTest.** The proof, traced directly
against source rather than inferred from test behavior:

1. `Region.addCell` unconditionally calls `expandBounds(pos)` on every call, growing the region's
   `min`/`max` bounding box to include whatever cell was just added - regardless of whether that
   cell came from `RegionScanner`'s own flood-fill or from a connector claim.
2. `SiegeLineTracer.trace`'s `orderedSteps` always includes the trace's landing position (`endPos`)
   as its last element, and the first position past the anchor as its first element - true for
   every completed trace, single-hop or chained, not an edge case.
3. `RegionGraph.registerConnector` iterates the full `orderedSteps` list and calls
   `fromRegion.addCell(step.pos())` **and** `toRegion.addCell(step.pos())` for every step,
   including the first and last - so `fromRegion`'s bounding box unconditionally grows to include
   the exact chunk containing `toRegion`'s own pre-existing, genuinely-natural landing cell (and
   symmetrically for `toRegion` and `fromRegion`'s boundary cell).
4. `TerritoryRegionMap.recomputeDirtyRegions`'s `localBounds` is the full inclusive chunk
   rectangle spanning `oldRegion.getMin()` to `oldRegion.getMax()` - not just the specific chunks
   the region's cells actually occupy. Given point 3, that rectangle is guaranteed to include the
   chunk containing the connector partner's landing cell, for every connector, regardless of how
   far apart the two regions' natural territories are.
5. `RegionScanner.scan` always sweeps the full vertical build-height column for whatever chunks are
   in scope - `localBounds` only restricts which chunks (X/Z) are scanned, not the Y range.

Composed: a dirty rescan of a connector-bearing region always re-sweeps its connector partner's
landing chunk too, always rediscovers it as a real, walkable, but flood-fill-disconnected seed
(the connector's intervening cells are speculative BUILD/MINE actions, not real solid ground, so
ordinary flood-fill adjacency never bridges the two), and so always reports
`rescanned.size() >= 2`. `topologyChanged` is therefore unconditionally `true` for any region
participating in at least one connector, in any territory shape.

**Implication**: for a siege-pathing system whose entire purpose is connecting otherwise-
disconnected regions, most or all regions with real connectivity needs permanently take the
expensive full-network-rebuild path on every dirty tick, never the cheap region-local one the
incremental design was built to provide. This was confirmed empirically via
`PathingRegionGameTests.testRepeatedConnectorCompletionsDontExplodeRebuildCount`, which drives a
sustained sequence of connector-adjacent block changes and observes `topologyRebuildCount`
incrementing on nearly every dirty tick until the connector's gap fully closes.

A debounce (`TerritoryRegionMap.TOPOLOGY_REBUILD_COOLDOWN_MS`, see `lastTopologyRebuildTime`) was
added to bound *how often* a detected topology change is acted on, but this does not restore the
fast path's original benefit - every rebuild it still lets through is exactly as expensive as
before, just less frequent. Direct arithmetic also shows this specific debounce constant (2000ms)
is likely inert at vanilla tick rate: the pre-existing `RECALC_COOLDOWN_TICKS` (80 ticks, ~4000ms
at vanilla 20 ticks/second) already gates every dispatch of `recomputeDirtyRegions` more strictly
than the 2000ms debounce window. The debounce is only observable in GameTest because that server
ticks roughly 17x faster than vanilla (measured ~2.9ms/tick), compressing the 80-tick gate to
~230ms, well inside the 2000ms window.

**Actual fix (not attempted in this effort - a real architectural change, not a small patch)**:
separate a region's "natural" flood-fill bounds from its full addCell-inclusive bounds, and use
only the former for `localBounds`'s chunk-selection purposes. A parking-note comment recording this
lives directly on `TerritoryRegionMap.reclaimConnectorCells`.

## Finding B: duplicate `Region` objects at merge completion (fixed)

**Resolution (2026-07-30):** fixed by making `recomputeDirtyRegions`'s own
`rescanned.size() != 1` check detect the merge directly (Option C below) - see
docs/superpowers/specs/2026-07-30-region-merge-detection-design.md for the full design, and the
`absorbedForeignRegion` check in `TerritoryRegionMap.recomputeDirtyRegions` for the shipped fix.
The rest of this section (below) documents the finding as it stood before that fix, for the
historical trace and empirical evidence - it is retained deliberately, not stale leftovers.

Distinct from, and more serious than, Finding A. `recomputeDirtyRegions`'s fast path (the
non-topology-changed branch) has no early `return` after processing a region id, unlike its
topology-changed sibling branch (which `return`s immediately after the first hit in a batch). When
a connector's gap fully closes, the closing block's dirty-detection neighbor check
(`neighborsAndSelf`) can resolve to **both** of the connector's endpoint region ids in the same
batch - a structural, not-rare consequence of checking neighbors around exactly the position where
two territories become contiguous.

When that happens, both ids independently take the fast path in the same batch: each rescans the
now-fully-merged cell set (finding exactly 1 piece - itself, having absorbed the other side, which
is exactly what makes `rescanned.size() != 1` read as "no topology change" even though a merge -
the most dramatic topology change possible - just happened), each builds its own `withId`-stamped
copy of that identical cell set, and both get written into `updatedRegions`. The result:

- Two `Region` objects with byte-identical `min`/`max`/`cellCount`.
- A stale connector still listed in `regionGraph` between them, pointing at a distinction that no
  longer physically exists (neither id triggered a full rebuild, so `regionGraph`/`routeTree` are
  never refreshed).
- `RegionIndex`'s last-write-wins per-cell construction (iterating regions in list order,
  unconditionally overwriting each cell's stamped id) makes whichever region was processed last in
  the list the only one any position resolves to - silently orphaning the other. It's still present
  in `getRegionIndex().getRegions()` (so region counts look superficially normal), but has zero
  resolvable cells anywhere.
- No `generation` bump occurs (that only happens in `rebuildRegionsAndGraph`, never called here),
  so nothing signals that anything unusual happened.

Confirmed reproducibly (6/6 runs) via
`PathingRegionGameTests.testRepeatedConnectorCompletionsDontExplodeRebuildCount`'s diagnostic
logging: two regions with identical bounds/cell counts, a connector still listed between them, and
two probes on opposite original sides of the (now-closed) gap both resolving to the same winning
region id.

**Production reachability is scenario-dependent, not uniform.** The GameTest reaches this bug via a
floor-support-type merge (filling a trench's floor one column at a time), but only by deliberately
reporting `onBlockChanged` against the newly-walkable cell one Y above the placed floor block, not
the floor block's own position that `SiegeBlockEventHandler.handleBlockChange` actually reports in
production. `tick()`'s `neighborsAndSelf` does check `above()`/`below()` (all 6 face-adjacent
neighbors plus the position itself, not just same-Y ones) - the gap is a stale-index/timing one,
not a directional blind spot: every one of those 7 candidates resolves against a `regionIndex`
snapshot from *before* the change, and for a floor placement, all 7 were unwalkable/unindexed
terrain prior to it. So a production-faithful report of a floor block's own position never
resolves to any dirty region for a floor-support change, and `recomputeDirtyRegions` is never even
reached for that case. For a same-level wall-break-type merge, this gap does not apply (the
same-Y flanking cells on either side of the wall were already indexed region members before the
break), so production's real event path reaches this bug the same way the GameTest's workaround
does. In short: confirmed reproducible in GameTest for a floor-support merge (via a non-production
reporting convention); confirmed-plausible for a production wall-break merge; not yet confirmed
reachable via production's actual event path for a floor-support merge specifically.

**Was not fixed in this effort** - deliberately, because a correct fix required a real design
decision, not a small patch. Three options were identified (Option C is the one that later
shipped - see the resolution note at the top of this section):

- **Option A**: mirror the topology-changed branch's early-`return`-after-first-hit semantics onto
  the fast path, so at most one id per batch can complete a fast-path pass - but this changes
  behavior for the (presumably common, and currently correct) case where multiple *unrelated*
  regions are legitimately, independently dirty in the same batch and should each get their own
  fast-path update.
- **Option B**: after the loop (or as each fast-path region completes), detect when two regions in
  `updatedRegions` have identical/overlapping cell sets and collapse them into one before
  publishing - correct in spirit, but non-trivial to implement efficiently and raises its own
  question of which id "wins" and what happens to the losing id's `regionStates`/
  `regionFlowFields`/`regionGraph` entries.
- **Option C** (shipped): fix `rescanned.size() != 1`'s own merge-blindness directly - detect that
  a rescan's single resulting piece is larger than the old region's own cell count by more than the
  region's own dirty change could plausibly explain, and treat that as a topology change too. The
  most principled option, and the reliable heuristic it needed turned out to be the delta-based
  `absorbedForeignRegion` check: a rescan's cells that weren't in the old region, checked against
  the current `RegionIndex` for foreign ownership (see
  docs/superpowers/specs/2026-07-30-region-merge-detection-design.md).

A parking-note comment previously recorded this on `recomputeDirtyRegions`'s
`boolean topologyChanged = rescanned.size() != 1;` line and on `reclaimConnectorCells`'s javadoc;
both have since been updated to reflect the fix above.

## Finding C: other measured facts and conventions from individual task dispatches

Smaller, non-headline facts that were also only recorded in the untracked reports. Grouped by the
task that produced them.

**Task 2 - structure generation without a dev client.** `pathing_test.nbt` (and `pathing_test_tall.nbt`,
Task 12) could not be generated via `/test create` in a running dev client (this environment is
headless). Neither of the originally-considered fallbacks worked: no shipped vanilla/NeoForge jar
contains a reusable GameTest structure template, and a `@GameTest`-based generator can't bootstrap
itself (`GameTestServer.create()` refuses to start with zero registered `@GameTest` methods, and any
registered test still needs an already-existing template to place its structure block against).
What actually worked: a temporary `@EventBusSubscriber` `ServerStartedEvent` listener (not a
`@GameTest`), run once via a plain `./gradlew runServer`, which force-loads the target chunks,
builds the platform directly with `ServerLevel#setBlockAndUpdate`, captures it with
`StructureTemplate#fillFromWorld`, and saves it via `NbtIo.writeCompressed` to an absolute path
under `src/main/resources/data/skavenblight/structure/`.

**Task 3 - test assertion deviation.** The original brief's `testDeployClimbableGoalMarksRegionDirty`
assertion (checking `regionIdAt`/`getGeneration()` on a `TerritoryRegionMap` that was never
`rebuild()`-ed) could never discriminate the bug it was meant to catch, since a fresh map's empty
`RegionIndex` can never produce a non-empty dirty set regardless of whether `forceRecalculation` is
called. Replaced with a `RecordingRegionMap` subclass that observes `onBlockChanged` directly -
verified equivalent to observing `forceRecalculation` itself (a one-line delegate with no other
caller).

**Task 4 - chunk-alignment / territory-shape empirical proof.** GameTest places its structure at an
arbitrary, non-chunk-aligned world offset on every run. A territory derived from a raw position
(`Set.of(new ChunkPos(nexusPos))`) can straddle the structure's own boundary and pick up GameTest's
invisible barrier-roof encasement as bogus extra regions. `anchorChunkFor`/`minRelY` (in
`PathingRegionGameTests`) exist because of this and were verified safe across multiple runs at
different alignment offsets, each producing a single clean chunk fully inside the structure's own
footprint.

**Task 5 - connector cost measurements.** The three-region routing test's actual logged connector
costs (with `base=1500`) were 18020 for the C-B hop (n=5, 6 base-units) and 9020 for the B-A hop
(n=2, 3 base-units) - confirming the route tree picks the cheaper multi-hop path using live
`getHopCost()`/`connector.cost()` values, not a hand-derived formula (an earlier hand-derived
formula for these costs had a small residual discrepancy and was not relied upon by the test
itself).

**Task 7 - why no region-scoped GameTest can discriminate `setMaxCandidateProjectLength`.** Region
membership is immutable after `RegionScanner`'s initial flood (`Region.addCell`'s only callers are
listed on its own javadoc). Every region-scoped trace's `outOfBounds` check (delegating to
`region::contains` via `FlowFieldState`'s `cellFilter`) fires on every step of
`SiegeLineTracer.trace`'s loop, not just at the landing. A genuine open-air gap has cells that
belong to no region on either side, and `SiegeLineTracer` itself has an unconditional, pre-existing
abort past 5 consecutive MINE steps. Composed, a region-scoped trace can never exceed ~6 steps
regardless of any cap - so `setMaxCandidateProjectLength`'s cap is correct but currently
unobservable via any region-scoped black-box test; it was instead proven directly at the
`SiegeLineTracer`/`SiegeProjectManager` level (see
`PathingGoalRecalculationGameTests.testMaxCandidateProjectLengthCapsMacroProjectReach`), and its
wiring into `TerritoryRegionMap`'s two call sites is verified by inspection only. It becomes
load-bearing only if region membership is ever extended (e.g. connector "provisional membership")
or the mine-depth caps change.

**Task 8 - shared connector cell tie-break.** A connector's traced cells are claimed into *both*
endpoint regions (see Finding A's point 3), so a shared cell can resolve, via `RegionIndex`'s
scan-order tie-break, to either endpoint - independent of which side is the route-tree parent or
child. `TerritoryRegionMap.injectSharedConnectorProjects` exists specifically so the answer for a
shared cell doesn't depend on which side wins that tie-break: it injects the same crossing project
into both the parent's and child's active-project list.

**Task 12 - structure sizing and MINE-margin trace.** `pathing_test_tall.nbt` is 32 wide, not the
original brief's 16, because a footprint exactly one chunk wide has alignment that depends entirely
on GameTest's own (non-deterministic) placement offset - `anchorChunkFor`'s alignment proof (Task
4) only holds for a footprint comfortably larger than one chunk. The bedrock-buried-nexus scenario
was traced step-by-step and produces exactly 4 consecutive MINE-classified steps through its 2-thick
plug, comfortably within `SiegeLineTracer.MAX_CONSECUTIVE_MINE`'s real abort threshold (the tracer
aborts on the *6th* consecutive MINE step, i.e. a margin of 2 steps, not 1 - `RegionScanner`'s own
`MAX_CONSECUTIVE_MINE_DEPTH` does not govern vertical descent at all, since MINE steps only fire at
`dy == 0` in `TerrainEvaluator.getValidOrthogonalSteps`).

## Finding D: `testThreeRegionsRouteThroughCheaperIntermediateHop` is flaky under full-suite concurrency (unfixed, not production-reachable as far as tested)

Discovered during branch-finishing verification (running `runGameTestServer` before merge), not during
the original task dispatches. `PathingRegionGameTests.testThreeRegionsRouteThroughCheaperIntermediateHop`
failed once with `expected a real direct C-A connector (more expensive than the via-B route)`, but the
same source, same commit, passed on a rerun with no changes.

**The connector logic itself is not the bug.** The failing run's own evidence dump showed all three
connectors built at exactly the costs the test's javadoc predicts (C-B=18020, B-A=9020, direct
C-A=33020) and the route tree correctly preferring the cheaper via-B hop (27040 < 33020). The only
wrong value was `regionIdAt(probeC)`, which resolved to `2` - the same id as `getRouteTree().getRootRegionId()`
- instead of the region actually containing `probeC` (id `0`, per its `hopCost`/`parent` matching the
via-B total exactly). That made the test's own `directCA` lookup search for a self-connector
(`regionA()==2 && regionB()==2`), which can't exist, producing the observed failure message.

**Ruled out by direct code reading, not just non-reproduction:**
- `RegionScanner.scan` is a pure function over a single-chunk `Set.of(...)` bound, with region ids
  assigned by a fixed ascending `y`/`localX`/`localZ` scan (`nextId++`) - fully deterministic for
  this test's fixed geometry.
- `RegionIndex`'s per-cell tie-break ("iterate `regions` in list order, last match wins") is
  documented and applied identically in both the constructor and `refreshChunks` - deterministic
  given a deterministic `regions` list, which the point above establishes.
- `RegionGraph.build` is single-threaded and sequential (plain nested loops, no executor, no shared
  mutable state across region pairs) - not a source of intra-call races.
- `SiegeProjectManager.setMaxCandidateProjectLength` (the connector-length cap added for the
  route-tree-parent case) is an instance field mutated only *after* `RegionGraph.build` already
  returned, and `RegionGraph.tryTrace` calls `SiegeLineTracer.trace`'s original overload, which
  always uses the fixed 32-block default regardless of that cap - it cannot affect connector
  discovery at all.
- `TerritoryRegionMap.rebuildRegionsAndGraph` writes `regionIndex`/`regionGraph`/`routeTree` (all
  `volatile`) before the async task's `finally` block flips `isCalculatingAsync` to `false` - the
  JMM happens-before edge through that flag holds, ruling out a stale-publication race for a
  `succeedWhen` poller that gates on `!isCalculating()`.

**Confirmed environment-dependent, not logic-dependent:** run in isolation (all other 13 GameTests'
`@GameTest` annotations temporarily removed, then restored - not committed), this test passed 7/7
times with the correct `regionC=0` every time. It has also passed as part of a full, freshly-compiled
14-test suite run. The failure has only been observed once, specifically when all 14 GameTests run
concurrently in the same JVM.

**Not fixed in this effort.** The mechanism is narrowed to "something affected by concurrent
GameTest execution," but not to a specific class - `FlowFieldState`, `TerrainEvaluator`,
`CalculationThrottler`, and Minecraft/NeoForge's own shared `ServerLevel`/background-executor
infrastructure under concurrent load are the remaining candidates, and distinguishing between them
needs runtime instrumentation (temporary logging across several more full-suite runs, watching for
the failure to reoccur), not further static reading. Until then, treat this test as flaky in CI:
a single-run failure on this specific test should be retried before treating it as a real
regression, since every reproduction so far has coincided with concurrent full-suite execution and
never with isolated or production-shaped execution.
