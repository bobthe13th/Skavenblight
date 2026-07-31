# Task 2 Report: Shared helpers + Test 1 (single rat, small diagonal gap)

## Update (third attempt, after resizing `pathing_test_giant` to 96x64x96 + repositioning geometry): the specific encasement-cycle-break hazard IS fixed, but a DIFFERENT, new blocker surfaced - still BLOCKED

The coordinator's hypothesis was that the territory-precision fix alone wasn't enough because the
original templates' gaps consumed too much of the template's own width - so no amount of territory
precision could keep the scan clear of GameTest's encasement. The plan was revised again: Task 1
(a sibling task) resized `pathing_test_giant` to 96x64x96 (already committed at `86443cf`), and this
task's own geometry moved to `relativeGroundSpawn=(26,2,26)`/`relativeNexusPos=(40,16,26)` (same
14-block diagonal offset, now with >=16 blocks/one chunk of margin from every template edge).

Re-read the brief fresh, discarded my previous working copy, and recreated
`StaircaseSiegeGroupGameTests.java` from the current Step 1 code - verified byte-identical via
`diff` against the brief's fenced code block. Compiled clean:

```
./gradlew compileJava --console=plain   →   BUILD SUCCESSFUL in 1s
```

Ran the full suite three times as instructed:

```
run #1 (full 28-test suite): BUILD FAILED in ~8s.  1 GAME TESTS COMPLETE -> "2 required tests failed":
    testsingleratbuildsstaircaseacrosssmallgap, testparentregiongetsrealinstructionsforsharedconnectorcells
  (a third, unrelated test - testthreeregionsroutethroughcheaperintermediatehop - also failed this
  one run only; it derives its own coordinates from anchorChunkFor(helper), i.e. run-dependent chunk
  alignment, and did NOT reappear in run #2 or #3, so I'm treating it as an unrelated,
  alignment-sensitive flake, not a regression caused by this change.)
run #2 (full suite, immediate rerun, no code changes): BUILD FAILED. Same 2 failures as run #1, nothing else.
run #3 (full suite, immediate rerun, no code changes): BUILD FAILED. Same 2 failures as run #1, nothing else.
```

`testSingleRatBuildsStaircaseAcrossSmallGap` failed all 3 times via the same `succeedWhen` timeout
message shape (rat not yet within 3.0 blocks of the nexus). Per the coordinator's explicit
instruction, I did the same kind of precise relative-coordinate analysis as before rather than
reporting the same conclusion unchecked - and this time the analysis point to something genuinely
different.

### Good news first: the specific hazard from attempts 1-2 is confirmed gone

Checked every run's log for the `FlowFieldCalculator` "Broke flow-field cycle... dropped ...
(BUILD_STAIR, locked=true)" signature that caused every previous failure. Filtered strictly to
absolute coordinates actually inside this run's own structure bounds (origin to origin+95 on X and
Z, given the resized template) - in all 3 full-suite runs and a 4th isolated run (below), **zero**
such warnings occur inside our structure. The `TerritoryRegionMap` rebuild for our nexus now
reports a clean, minimal result every time: `RegionScanner found 2 regions (539 cells scanned)` /
`RegionGraph built: 2 regions, 1 connectors (0 chained, max 1 hops)`. This confirms the two-part fix
(precise territory override + a template genuinely larger than the gap) does exactly what the
coordinator diagnosed: GameTest's own encasement geometry is no longer reachable from this test's
minimal territory, so the cycle-breaker never has a bogus artifact to compete with the real
connector.

### But the test still fails - via a different symptom this time

To rule out cross-test interference (the same control I applied in the first investigation), I
re-ran in full isolation (temporarily commented out the other 4 gametest classes' `@GameTestHolder`
annotations - reverted immediately after, confirmed via `git status`/`git diff` showing no residual
changes) and bumped `timeoutTicks` to 60000 (10x) to rule out "just needs more time" - also reverted
after. Isolated result:

```
1 GAME TESTS COMPLETE IN 12.99 s -> 1 required tests failed: testsingleratbuildsstaircaseacrosssmallgap
```

Structure origin (from the failure line): `-913522, -60, 3561760`. Final rat position:
`-913480.60, -58.00, 3561786.50` -> **relative (41.4, 2, 26.5)** - i.e. almost exactly below the
nexus (`relativeNexusPos = (40, 16, 26)`): only ~1.4 blocks off in X, dead-on in Z, but `y` never
left 2 (ground level) for the whole 60000-tick run. This relative position is nowhere near the
96-wide template's own edges (0 or 95) - ruling out the previous hazard for this specific failure,
exactly as the coordinator asked me to check before concluding anything.

Grepped the isolated run's log (fully clean of other tests' noise) for
`SiegeProjectManager`/`SiegeInteractionHandler`/`WidenStairsGoal`/`BUILD_STAIR`/`COBBLESTONE_STAIRS`:
**zero matches**, for the entire 60000-tick run. Whatever construction pathway is supposed to fire
once a mob reaches the connector never fires at all here - not "fires and fails", just never
invoked.

To find out why, I added one temporary diagnostic log line to
`ClanratEntity.customServerAiStep` (immediately before the `routeTree.isReachable(...)` check;
reverted via `git checkout --` afterward, confirmed clean) printing the mob's own region id, whether
the route tree is null, `isReachable(...)`'s actual result, the resolved root region id, and the
region map's generation counter. Result, sampled across the whole run: **`isReachable=true`,
`routeTreeNull=false`, `rootRegionId=1`, `regionId=0`, consistent `regionMapGen=1` throughout** -
i.e. the mob correctly resolves to the ground region, the route tree correctly considers it
reachable from the nexus's region, and nothing about generation/staleness is at play. So the mob
does NOT take the "stranded" branch (contradicting what the raw `StrandedGoal` log spam suggested at
first glance - that goal's own logged `rawPos`/`distToTarget` fields turned out to be stale/wrong
diagnostic values that never change run-to-run-tick-to-tick, not the mob's real, correctly-updating
position, which is a separate, apparently pre-existing cosmetic bug in that goal's own logging, not
something I chased further since it isn't gating the actual failure here).

So: region resolution succeeds, reachability succeeds, a real `RegionFlowField` is handed to the
mob's goals (per `rebuildRegionsAndGraph`'s own log evidence - region 0's "flow field pass
completed" line only gets reached in code AFTER a non-null `target` is resolved via
`parentConnector.entryFor(...)`, which only happens if reachable) - yet the mob still walks to
within ~1 block of the nexus horizontally and then sits completely motionless (identical
`blockPosition()` across dozens of consecutive 40-tick checks) for the rest of the run, with no
macro-construction goal ever logging any activity. This looks like the real, remaining defect is
somewhere in how the connector's own traced `BUILD_STAIR` instructions get attached to the exact
cell a real mob's ordinary WALK progression actually arrives at (possibly an off-by-one between the
connector's traced anchor cell and where entity collision/navigation actually settles the mob, or a
goal-priority/gating condition in `WidenStairsGoal`/`BuildFlowFieldGoal` that never ends up
satisfied for this specific case) - not the encasement-territory issue from attempts 1-2, which is
now conclusively ruled out.

I did not chase this further: diagnosing it properly would mean reading
`WidenStairsGoal`/`BuildFlowFieldGoal`/`SiegeLineTracer`/`RegionGraph.registerConnector` in real
depth and very likely adding more temporary instrumentation to production pathing code, which goes
beyond "recreate this one test file and verify it" and risks scope creep into a different, deeper
investigation the coordinator may want to plan deliberately rather than have me improvise.

**All diagnostic changes were fully reverted** before finishing - confirmed via `git status --short`
(only the recreated `StaircaseSiegeGroupGameTests.java` shows as untracked) and `git diff --stat`
(empty, no tracked file has any residual change). The recreated file was re-diffed against the
brief's current Step 1 code one more time after re-reverting `timeoutTicks` back to `6000` (I had
bumped it to `60000` for the isolated timing check) and confirmed byte-identical again. **No commit
was made** - the test still does not pass.

## Update (second attempt, after the coordinator's `restrictTerritoryToMinimalArea` fix): still BLOCKED

The coordinator revised the plan after reading the first attempt's root-cause writeup below, and
proposed a targeted fix: instead of relying on `WarpFluxNetwork#updateTerritory`'s default
`Config.territoryChunkRadius`-based bubble (which the first attempt showed reaches past this
32-wide/2x2-chunk template's own footprint into GameTest's own auto-encasement ledge, regardless
of margin), directly override the network's territory to the *exact* chunks the test's own
geometry needs, via the live mutable `Set<ChunkPos>` that `WarpFluxNetwork#getTerritoryChunks()`
returns. I re-read the revised brief fresh (not from memory), discarded the old file, and
recreated it exactly from the new Step 1 code: `placeNexusAndConduit` now returns the
`WarpFluxNetwork`, a new `restrictTerritoryToMinimalArea` helper clears and refills
`network.getTerritoryChunks()` with only the chunks spanning `relativeGroundSpawn` to
`relativeNexusPos`, and the test method calls it immediately after `placeNexusAndConduit`, before
spawning any rats. The `Config` import is gone (correctly - it's unused in this file now that the
radius constant is no longer referenced directly).

**Result: the fix does not resolve the failure.** Ran the full verification sequence three times:

```
./gradlew compileJava --console=plain   →  BUILD SUCCESSFUL in 1s   (ran once, compiles clean)
./gradlew runGameTestServer --console=plain   →  run #1: BUILD FAILED in 25s
./gradlew runGameTestServer --console=plain   →  run #2: BUILD FAILED in 25s (immediately rerun, no code changes)
./gradlew runGameTestServer --console=plain   →  run #3: BUILD FAILED in 24s (immediately rerun, no code changes)
```

`testSingleRatBuildsStaircaseAcrossSmallGap` failed identically in all 3 runs - same symptom as
the first attempt (rat walks to directly beneath the platform, correct X/Z, but `y` never leaves
ground level for the entire 6000-tick timeout):

```
run #1: ... has not yet arrived within 3.0 blocks of the nexus at BlockPos{x=-11656256, y=-44, z=-14277274} (currently at BlockPos{x=-11656259, y=-58, z=-14277276})
run #2: ... has not yet arrived within 3.0 blocks of the nexus at BlockPos{x=-5265039, y=-44, z=-13686472} (currently at BlockPos{x=-5265038, y=-58, z=-13686470})
run #3: ... has not yet arrived within 3.0 blocks of the nexus at BlockPos{x=9938390, y=-44, z=519510} (currently at BlockPos{x=9938391, y=-58, z=519509})
```

Failure counts per run (checked against the same two documented pre-existing baselines):

- Run #1: **3** required tests failed - mine, the always-failing
  `testparentregiongetsrealinstructionsforsharedconnectorcells`, **and** a third,
  `testthreeregionsroutethroughcheaperintermediatehop`, which had not appeared in any of the 5
  baseline runs from the first attempt. That test derives its coordinates from
  `anchorChunkFor(helper)` (run-dependent chunk alignment, per `PathingRegionGameTests`'s own
  javadoc), and did not reappear in runs #2 or #3 - consistent with it being an alignment-sensitive
  flake unrelated to this change, not something my fix caused. Flagging it here only for
  completeness; not treating it as a new regression given it didn't reproduce.
- Run #2: **2** required tests failed - mine + `testparentregiongetsrealinstructionsforsharedconnectorcells`.
  No `testRepeatedConnectorCompletionsDontExplodeRebuildCount` flake observed.
- Run #3: **2** required tests failed - same pair as run #2.

So: exactly the documented baseline plus mine, 2 out of 3 times, with one unrelated flake on the
third. `testsingleratbuildsstaircaseacrosssmallgap` itself failed **3 for 3**.

### Why the fix didn't work: the leak isn't about radius, it's about scan halo

Checked the `FlowFieldCalculator` "Broke flow-field cycle" warning in each run, matching it against
each run's own structure origin (logged in the failure line) to get the position *relative* to the
structure:

```
run #1 (origin -11656276,-60,-14277280): dropped -11656244,-34,-14277272 (BUILD_STAIR, locked=true) → relative (32, 26, 8)
run #2 (origin  -5265059, -60,-13686478): dropped  -5265027,-34,-13686470 (BUILD_STAIR, locked=true) → relative (32, 26, 8)
run #3 (origin   9938370, -60,  519504): dropped   9938370,-35,  519519  (BUILD_STAIR, locked=true) → relative ( 0, 25, 15)
```

Two things stand out:

1. **It's still dropping a `locked=true` BUILD_STAIR** - i.e., a real, needed macro-project
   instruction, not a bogus one - every single run, exactly like before the fix.
2. **The leaked position is right at the edge of whatever chunk range I restricted the territory
   to, not at the old default-radius distance.** With `relativeGroundSpawn=(6,_,6)` and
   `relativeNexusPos=(20,_,6)`, and given this structure's own chunk alignment (origin is not
   chunk-boundary-aligned - GameTest doesn't guarantee that), the minimal 2-chunk territory I
   compute spans local chunk boundaries at roughly relative x 3-18 and 19-31 in runs #1/#2 (so
   relative x=32 is exactly one chunk beyond the far edge of my restricted set - the "one ring
   outside footprint" encasement ledge documented in `PathingRegionGameTests`), and at a
   *different* boundary in run #3 where relative x=0 is one chunk beyond the *near* edge of my
   restricted set instead. Same mechanism, different side, purely because each run's structure
   lands at a different absolute-chunk offset.

This means the override **is taking effect** (confirmed indirectly: the leak position moves to
track wherever the edge of *my* restricted set is, not a fixed distance consistent with the old
default 2-chunk radius bubble; also, `network` was provably non-null in every run, since a null
network would have thrown an NPE inside the test method's own body at `restrictTerritoryToMinimalArea`,
producing a crash-style failure rather than the `succeedWhen`-timeout failures actually observed).
What it doesn't do is stop the region/connectivity scan from looking **one chunk past** whatever
territory set is given, on whichever side happens to be closest to a chunk boundary. That's
consistent with `RegionScanner`/`TerritoryRegionMap` deliberately peeking at territory-adjacent
chunks for connector-discovery purposes (a reasonable feature for real, adjacent-base connectivity
in live play) - which, for a small, fully-enclosed GameTest template, always has *some* side
sitting one chunk away from the encasement, no matter how tightly the territory set is drawn. In
other words: the fix replaces "how big is the radius" with "which chunk boundary is closest," but
either way there is still exactly one ring of GameTest's own bogus geometry within reach, and the
cycle-breaker keeps preferring to keep that bogus ledge and drop the genuinely-needed
`BUILD_STAIR` (its cost is lower every time, because it's a tiny local loop right at the boundary
versus the real ~14-block diagonal climb - not a coin flip, which is why this reproduced 3-for-3
rather than being intermittent).

### What I did NOT do

I did not attempt my own fix beyond what the revised brief specified (e.g., moving the platform
away from the template edges, walling off the gap per the design spec's "terrain design
principle" which this exact code still doesn't do, or touching `FlowFieldCalculator`'s
cycle-breaking logic itself). All three would be defensible next steps, but:
- Moving the test's own coordinates deviates from "recreate the file from the brief's current
  Step 1 code exactly."
- Walling off the gap's sides is a terrain-shape change to the one `@GameTest` method's body, which
  the brief also specifies verbatim.
- Fixing `FlowFieldCalculator`/`RegionScanner` production behavior is well outside "create this one
  test file," and I'd be modifying shared pathing code no other task in this plan asked me to
  touch, based on one failing GameTest.

**No commit was made.** Working tree still contains only the recreated (and still non-passing)
`StaircaseSiegeGroupGameTests.java`, matching the revised brief's Step 1 code exactly - confirmed
via `git status --short` showing only that one untracked file, and `git log -1` showing HEAD is
still the coordinator's plan-revision commit, not a new test commit.

## Status: BLOCKED

The file was created exactly as specified and compiles cleanly, but the one `@GameTest` method
(`testSingleRatBuildsStaircaseAcrossSmallGap`) does **not** pass. It fails deterministically -
5 out of 5 independent runs, under multiple different conditions (see below) - so this is not the
"pick another random seed / rerun once" kind of flake the brief's Known-risks section warned
about for two *other*, unrelated tests. Per this task's own instructions ("confirm the GameTest
actually ran and passed - don't just trust that it compiled"), I have not committed. The new file
is left in the working tree, uncommitted, exactly matching the brief's verbatim code.

## What was done

1. Created `src/main/java/org/ratden/skavenblight/gametest/StaircaseSiegeGroupGameTests.java`
   with the brief's Step 1 code **verbatim** - no changes to imports, helper signatures, javadoc,
   or the test method body. Diffing the final file against the brief's fenced code block shows no
   differences.
2. `./gradlew compileJava` → `BUILD SUCCESSFUL` on the first try. No API-shape fixes needed.
3. `./gradlew runGameTestServer` → the full 28-test suite ran; `testSingleRatBuildsStaircaseAcrossSmallGap`
   failed on a timeout (see below). Investigated the failure per the brief's own Step 3
   troubleshooting note, then went further once that note's specific diagnosis came back clean
   (see "Root-cause investigation").
4. Confirmed the file matches the brief exactly via a final `Read` after all diagnostic edits to
   *other* files were reverted (see "Diagnostic method" - nothing in the final state of this repo
   differs from a clean checkout plus this one new file).

## Exact commands run and key output

**Compile:**
```
./gradlew compileJava --console=plain
...
BUILD SUCCESSFUL in 1s
```

**Full-suite run (brief's exact code, default config, all other gametests enabled) - run #1:**
```
./gradlew runGameTestServer --console=plain
```
Key output:
```
[minecraft/GameTestServer]: 28 tests are now running ...
[minecraft/LogTestReporter]: testsingleratbuildsstaircaseacrosssmallgap failed at -7154567, -60, -631556!
  ClanratEntity[...] has not yet arrived within 3.0 blocks of the nexus at BlockPos{x=-7154547, y=-44, z=-631550}
  (currently at BlockPos{x=-7154550, y=-58, z=-631552})
[minecraft/LogTestReporter]: testparentregiongetsrealinstructionsforsharedconnectorcells failed at ...
  connector cell ... resolved to region 1 but got no flow-field instruction - a mob standing there would be stuck
[minecraft/GameTestServer]: 2 required tests failed :(
   - testsingleratbuildsstaircaseacrosssmallgap
   - testparentregiongetsrealinstructionsforsharedconnectorcells
BUILD FAILED in 24s
```
`testRepeatedConnectorCompletionsDontExplodeRebuildCount` (the other documented pre-existing,
sometimes-flaky test) did **not** appear in the failure list in this run or the final
re-verification run below - it passed both times. So: exactly the two documented pre-existing
failures, plus my new test, and nothing else new.

**Final re-verification run (after reverting every diagnostic change - see below - back to the
exact brief state):**
```
./gradlew runGameTestServer --console=plain
```
Same result, same two failing test names, same "2 required tests failed", `BUILD FAILED in 24s`.
Confirms the first run wasn't a one-off fluke of that particular session.

## Root-cause investigation (why it times out)

The brief's Step 3 troubleshooting note says: if it times out, first check whether the
`WarpFluxNetwork` actually formed and `TerritoryRegionMap.rebuild` ran. I checked that first:

```
[or.ra.sk.ai.pa.re.TerritoryRegionMap/]: TerritoryRegionMap rebuild STARTED for nexus -7154547, -44, -631550
[Worker-Main-30/INFO] ... Region 0 flow field pass completed: 5244 nodes for 5338 region cells
[Worker-Main-30/INFO] ... Region 1 flow field pass completed: 1024 nodes for 1062 region cells
[Worker-Main-30/INFO] ... Region 2 flow field pass completed: 133 nodes for 241 region cells
[Worker-Main-30/INFO] ... Region 3 flow field pass completed: 30 nodes for 66 region cells
[or.ra.sk.ai.pa.re.TerritoryRegionMap/]: TerritoryRegionMap rebuild FINISHED: 4 regions, 6 connectors
```

The network formed and the rebuild ran, so the specific failure mode the brief anticipated is
*not* what's happening. I went further, since the caller's brief explicitly told me to follow this
check first before assuming something more exotic.

The rat's own final position is the key clue, and it is nearly identical across every run: the rat
consistently walks from ground spawn all the way to directly beneath the elevated platform (same
X/Z as the nexus, well within the 5x5 platform footprint) but never gains any elevation - `y`
stays at the ground floor's Y for the entire timeout, run after run. It approaches the gap
correctly and then simply never crosses it.

Every run also logs this warning from the same subsystem, at a position consistently near the
template's own edge/top boundary (relative to each run's own structure origin, this is around
relative `(31-32, 25-26, z≈6-8)` - i.e. X at the template's max width (32-wide template, 0-31) and
Y *above* the template's own 24-block height):

```
[or.ra.sk.ai.pa.FlowFieldCalculator/]: Broke flow-field cycle of 2 position(s): dropped
  ... (BUILD_STAIR, locked=true) which pointed at ..., kept the other 1 position(s) -
  reason: dropped the higher-cost position (...) - dropped so mobs fall back to local
  breach instead of looping forever
```

This coordinate signature - X at the template's own edge, Y above the template's own build
height - matches the exact bogus-region mechanism already documented in this same package's
`PathingRegionGameTests` class javadoc: `skyAccess = true` suppresses only the *roof* of
GameTest's auto-encasement; the side walls and the walkable ledge along their top remain, and
`TerritoryRegionMap`/`RegionScanner` (by design) scan the full vertical column, so that ledge shows
up as real, walkable, connected terrain. Here, `WarpFluxNetwork.updateTerritory` (called with
`Config.territoryChunkRadius`, default 2) builds a multi-chunk "bubble" around the nexus/conduit
that extends past the structure's own 32x32 (2x2-chunk) footprint into that encasement geometry,
so `RegionGraph`/`FlowFieldCalculator` finds an extra, spurious cyclic macro-project relationship
there. The cycle-breaking safeguard picks a position to drop by cost, and in this geometry it drops
a **locked** `BUILD_STAIR` instruction - i.e., an instruction actively needed by the real crossing,
not a bogus one - leaving the ground→nexus connector without a usable macro-action. That would
explain exactly what's observed: the rat reaches the base of the gap (region discovery/goal
assignment did work) and then has nothing to execute to climb it.

### Diagnostic method (all reverted before the final state)

To rule out other explanations before concluding this, I ran four additional throwaway
experiments, each followed by a full revert (`git checkout --` on the touched files, confirmed
clean via `git diff`/`git status`) before the final re-verification run above:

1. **Cross-test contamination?** Temporarily commented out `@GameTestHolder` on the other three
   existing gametest classes (`PathingRegionGameTests`, `PathingGoalRecalculationGameTests`,
   `AwaitFormationGoalGameTests`, `SiegeConstructionActionsGameTests`) so only my new test ran (1
   test instead of 28). **Same failure**, same "Broke flow-field cycle...dropped locked
   BUILD_STAIR" warning, same stuck-at-ground-level final position. Rules out interference from
   the other 27 tests sharing the same world/CPU.
2. **Just needs more time?** With the other tests still disabled, bumped `timeoutTicks` from 6000
   to 60000 (10x). **Same failure**, rat still never left ground level. Rules out "the construction
   pace is just slow, give it more ticks" - if the mob were making incremental progress this would
   have shown a different final position or an eventual pass.
3. **`territoryChunkRadius` too wide?** Temporarily changed `Config.java`'s default
   `territoryChunkRadius` from 2 to 1 (still isolated, `timeoutTicks` back to the brief's 6000).
   **Same failure**, same warning at essentially the same relative coordinates. Reducing (but not
   eliminating) how far the territory bubble extends past the structure's own footprint did not
   help - suggesting the trigger is the structure's own edge proximity (the nexus/platform are
   positioned close enough to the template's max-X edge that even a radius-1 bubble still reaches
   the encasement ledge), not simply "how big is the radius."
4. Reverted `Config.java` (`git diff` shows no changes) and all four gametest files
   (`git checkout --`, confirmed clean) back to their original committed state, then reran
   `compileJava` + `runGameTestServer` one final time with the brief's *exact*, unmodified code -
   this is the "final re-verification run" quoted above. Same two failures, nothing else.

## Deviation from the brief's exact code, and why

None in the committed-candidate file itself - `StaircaseSiegeGroupGameTests.java` is byte-for-byte
the code given in the brief. The only deviations were the four temporary, fully-reverted
diagnostic experiments above (never intended to ship, and confirmed not present in the final
`git status`/`git diff`).

## Current repo state

```
git status --short
?? src/main/java/org/ratden/skavenblight/gametest/StaircaseSiegeGroupGameTests.java
```
Nothing else changed. **No commit was made** - per this task's own self-review requirement to
confirm the test actually passed before committing, and because committing the brief's Step 4
message ("...Pass condition is two-part... rats arrived, but not clearly via a real staircase
crossing") would misrepresent a test that does not currently do what that message says it proves.

## Concerns / recommendation for the plan owner

This looks like a real, reproducible interaction between `Config.territoryChunkRadius`'s
multi-chunk territory bubble, this test's specific geometry (nexus platform positioned close to
the 32-wide template's own X edge, elevated to the middle of its 24-tall Y range), and
`FlowFieldCalculator`'s cycle-breaking logic incorrectly dropping a *locked* (i.e. genuinely
needed) `BUILD_STAIR` instruction instead of only bogus ones from GameTest's own encasement
geometry. That's a production-code question (`ai/pathing/FlowFieldCalculator.java` and/or
`ai/pathing/region/RegionGraph`), not something fixable by changing only this new test file - and
this task's own interface contract (helper signatures fixed for Tasks 3-5) means I should not
improvise a workaround inside this file without the plan owner's sign-off, since Tasks 3-5 are
specified to reuse these exact helpers as-is.

Possible directions for whoever picks this up (not attempted, since they're out of this task's
scope): (a) fix `FlowFieldCalculator`'s cycle-breaking to never drop a `locked=true` position in
favor of an unlocked/bogus one; (b) shrink the gap between the platform/nexus position and the
template's edges enough that even a radius-2 territory bubble can't reach the encasement ledge
(would require re-deriving the brief's exact coordinates, which are specified verbatim and shared
with Tasks 3-5); (c) wall off the sides of the gap per the design spec's own "Terrain design
principle" section (which this exact brief code does not currently do) so `RegionGraph` has no
alternate connector to trace at all, removing the cycle. I did not attempt any of these, since (a)
and (c) are behavior/design changes beyond "create this one file," and (b) would break the
Tasks-3-5 contract.

## Files touched (final state)

- Created (uncommitted):
  `C:\Users\bobth\IdeaProjects\Skavenblight\.claude\worktrees\staircase-siege-group-gametest-spec\src\main\java\org\ratden\skavenblight\gametest\StaircaseSiegeGroupGameTests.java`
- Everything else in the repo is unchanged from before this task started (verified via `git status`/`git diff`).
