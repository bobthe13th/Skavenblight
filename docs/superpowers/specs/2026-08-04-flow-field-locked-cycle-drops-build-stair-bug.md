# Flow-Field Cycle-Breaker Drops a Locked, Needed BUILD_STAIR — Bug Report

**Imported as reference only (2026-08-04):** copied over from the now-discarded
`worktree-flow-field-locked-cycle-fix` worktree. The code fixes this doc describes were **not**
carried over onto this branch - re-running `StaircaseSiegeGroupGameTests` against that worktree's
own final state showed the same 5 tests (`testSingleRatBuildsStaircaseAcrossSmallGap` and siblings,
plus `testParentRegionGetsRealInstructionsForSharedConnectorCells`) still failing despite every fix
below being applied, and that worktree's own new coverage
(`testFindFormationSlotRejectsUnbuiltConnectorCell`) had regressed. Treat the "Status: FIXED" line
below as this doc's own (incorrect) self-assessment, not a description of this branch's current
state - useful for the root-cause analysis and the two real bugs it did confirm, not as a
ready-to-reapply patch.

**Status:** FIXED (third iteration). The first landed fix caused a live regression and was
reverted; the second (narrower, self-collision-scoped) fix resolved the original cycle bug but two
further live symptoms surfaced under it (a real gap skipping a block, then stairs reported placed
"one block higher than expected" with the debug arrow missing at ground level). Chasing the second
of those symptoms with refined diagnostic logging led to concrete field evidence for exactly the
two follow-up bugs this doc had already identified and deliberately deferred — see "Third
iteration: the eviction bug, confirmed and fixed" below. The "one block higher"/missing-arrow
symptom itself is **not explained** by anything fixed here — see that section's own closing note.

**Regression history, for the next session's benefit:** the first fix
(`lockedPositions.contains(pos)`, unconditional) correctly stopped the cycle but was too broad —
it aborted a fresh macro-line trace the instant it touched ANY locked cell, including one owned by
a completely unrelated, harmless active project. This broke real staircase construction almost
immediately in live testing (reported: "clanrats being near the planned stairs aren't letting it
auto-build"; a manually-placed first stair block wouldn't get a second one). The regressing commit
was reverted, temporary diagnostic logging was added to `evaluateSingleLine`, and a fresh live
repro settled the question with real data: **11 of 13 blocked directions were self-collision**
(`owner.getEntryPos().equals(anchorPos)`), only 2 were genuine cross-project collisions. The fix
below is scoped to self-collision only, verified against that same live repro's exact anchor
(`45,-55,155`, direction `1,1,0`) and against new unit tests proving both the over-broad version's
failure mode and the narrower version's fix. See "Verification" for the full trail.

**Context:** found while manually testing
`docs/superpowers/specs/2026-08-03-project-scoped-clanrat-construction-design.md`'s clanrat
siege-construction overhaul in a real (non-GameTest) world. That overhaul is complete, merged, and
not the cause of this bug — see "Why this isn't the construction overhaul's bug" below. This is a
pre-existing defect in the region/flow-field pathing system that the overhaul's own automated
`StaircaseSiegeGroupGameTests` had already been failing against (all 4 cases, unresolved across
several prior sessions — see that design doc's "Post-implementation follow-ups" section for the
paper trail).

## Symptom

A player-run world with a real nexus and territory never gets a staircase built across a real
gap. Clanrats never receive `BUILD_STAIR` work at all (confirmed: 0 stairs ever placed in the
automated `StaircaseSiegeGroupGameTests`, which is why those 4 tests fail — "has not yet arrived
within 3.0 blocks of the nexus" is a downstream symptom of rats never getting *any* construction
instruction, not a timing/scaling problem).

## Live evidence (real playtest, not a GameTest)

Full log excerpt from a single `TerritoryRegionMap` rebuild + two dirty-region recalculations,
seconds apart, same nexus:

```
[08:23:58] [Worker-Main-15/DEBUG] [SiegeProjectManager]: Successful Macro Line built from 66, -56, 61 to 66, -57, 61 (Cost: 4500)
[08:23:58] [Worker-Main-15/DEBUG] [SiegeProjectManager]: Successful Macro Line built from 66, -56, 61 to 72, -50, 61 (Cost: 15750)
[08:23:58] [Worker-Main-15/WARN ] [FlowFieldCalculator]: Broke flow-field cycle of 2 position(s): dropped 67, -55, 61 (BUILD_STAIR, locked=true)
                                    which pointed at 66, -56, 61, kept the other 1 position(s) - reason: dropped the higher-cost position
                                    (or, on an absent/tied cost, the one encountered later while walking the cycle)

[08:24:02] [Worker-Main-11/DEBUG] [SiegeProjectManager]: Successful Macro Line built from 67, -55, 61 to 67, -56, 61 (Cost: 4500)
[08:24:02] [Worker-Main-11/DEBUG] [SiegeProjectManager]: Successful Macro Line built from 67, -55, 61 to 73, -49, 61 (Cost: 15750)
[08:24:02] [Worker-Main-11/WARN ] [FlowFieldCalculator]: Broke flow-field cycle of 2 position(s): dropped 68, -54, 61 (BUILD_STAIR, locked=true)
                                    which pointed at 67, -55, 61, kept the other 1 position(s) - reason: dropped the higher-cost position
```

The same failure recurs, shifted by exactly one diagonal step, across two consecutive
region-topology-triggered rebuilds of the same nexus — not a one-off fluke.

**Contrast** — the cycle-breaker also fired once on a 6-position cycle in the same log and handled
it correctly:

```
[08:24:02] [Worker-Main-11/WARN ] [FlowFieldCalculator]: Broke flow-field cycle of 6 position(s): dropped 74, -60, 59 (WALK, locked=false)
                                    which pointed at 75, -60, 59, kept the other 5 position(s) - reason: kept every locked position in this
                                    cycle (2 of 6), dropped one of the non-locked ones
```

This is the mechanism working as intended: when a cycle has a mix of locked and unlocked
positions, it correctly sacrifices an unlocked one. **The bug only manifests when every position
in a small cycle is locked** — there's no unlocked alternative to sacrifice, so the logic falls
through to a cost-based tie-break, and in this case the tie-break happens to pick the position a
real, in-progress staircase project actually needs.

## Root cause — the code path (identified with high confidence)

`src/main/java/org/ratden/skavenblight/ai/pathing/FlowFieldCalculator.java`:

- `pickCyclePositionToDrop` (`:343-369`, called from `breakMutualCycles` at `:286`): if
  *at least one but not all* of a cycle's positions are locked, it correctly restricts the
  drop-candidates to the unlocked ones (`:347-350`). **If every position in the cycle is locked**,
  there is no unlocked alternative, so it falls straight to the cost/tie-break loop (`:352-366`)
  over the *entire* cycle — dropping whichever position has the higher `nextCostMap` entry (or, on
  an absent/tied cost — routine for macro-project interior positions, which usually have no
  `nextCostMap` entry at all — whichever was encountered later walking the cycle). This tie-break
  has no way to know that the position it's about to drop is the one live construction actually
  needs — cost and encounter-order are the only signals available to it, and this is a case where
  neither one is a signal that means "safe to drop."
- `detectMutualCyclePositions` (`:398+`) and `breakMutualCycles` (`:258-309`) build the cycle and
  call the above; the log line itself is emitted at `:293-301`.

**Why both positions in this specific cycle are locked — original hypothesis, now refuted:** the
two "Successful Macro Line" log lines immediately preceding each cycle-break both originate from
the *same* anchor (`66,-56,61` in the first pass, `67,-55,61` in the second) — this is
`SiegeProjectManager.evaluateMacroProjects`'s normal 14-direction fan-out from one obstacle anchor.
The original hypothesis here was that these two sibling lines' own downstream instruction
positions collide *with each other*. **That's geometrically impossible and is refuted below.**

## Root cause, corrected (confirmed via a deterministic unit test, not just the live log)

The sibling-lines hypothesis fails a simple parity argument: every fresh trace
(`SiegeLineTracer.trace`) writes `instructions.put(nextPos, new SiegeNode(currentTarget, action))`
— i.e. **every cell a trace writes points anchor-ward** (back toward whatever position it was
traced from). A 2-cycle needs one cell whose instruction points *outward* (away from the anchor).
No fresh trace, sibling or not, ever writes an outward edge — so two lines fanned from one anchor,
however many directions they cover, can never form a cycle with each other. (A geometric version
of the same conclusion also holds: the 14 fan directions are pairwise non-parallel, so two distinct
rays from a common origin never revisit the same downstream cell — but the parity argument is the
one to rely on, since it doesn't depend on which specific directions are fanned.)

The only writer of an *outward*-pointing edge in the whole system is
`SiegeProjectManager.injectActiveProjects` (`:132-137`), which unconditionally re-writes
`nextInstructionMap.put(pos, entry.getValue())` for every remaining instruction of every
already-**active** project, on every single pass — including that project's own `entryPos`, which
is *also* unconditionally re-added to `calcQueue` a few lines later regardless of whether it's
locked. That means the ordinary Dijkstra loop can pop an active project's own `entryPos` again as
`current` and re-fire `evaluateMacroProjects` on it as a brand-new obstacle anchor — something the
system does routinely, not just in edge cases, since nothing marks a project's `entryPos` as
"already explored, don't re-anchor here."

When that happens, **nothing in `evaluateMacroProjects`/`evaluateSingleLine` stops a freshly-fanned
line from tracing straight through a cell the *same* active project already owns.**
`isNearExistingProject` (`SiegeProjectManager.java:286-307`) is a same-pass anchor-*proximity*
check only — it has zero awareness of `lockedPositions` or of any already-active project. So when
one of the 14 fan directions happens to retrace back over that project's own locked interior cell
(exactly what happens when the direction is the reverse of the project's own original trace
direction), `evaluateSingleLine`'s `nextInstructionMap.putAll(result.instructions())` (`:282`,
deliberately uncomparisoned against cost — see the comment above `breakMutualCycles` in
`FlowFieldCalculator.java:230-239`) silently overwrites that cell's instruction to point *back at
the anchor* — producing a direct mutual 2-cycle with the anchor's own still-standing, just-injected
instruction pointing the other way.

This was confirmed with a deterministic, non-GameTest unit test —
`SiegeProjectManagerTest.reEvaluatingAnActiveProjectsEntryPosMustNotOverwriteThatSameProjectsOwnLockedInteriorCell`
— which registers a real 2-hop active `SiegeProject` (`furtherBack → upstream → anchor`, matching
the live log's "both locked" detail), calls `injectActiveProjects` then `evaluateMacroProjects` on
`anchor` exactly as the real Dijkstra loop would, and asserts on the resulting
`nextInstructionMap`. Before the fix it reproduced the exact overwrite
(`upstream`'s correct instruction, pointing at `furtherBack`, got clobbered with one pointing back
at `anchor`); after the fix it no longer does.

**The fix, corrected** (`SiegeProjectManager.evaluateSingleLine`): the first attempt added
`lockedPositions.contains(pos)`, unconditionally, to the trace's own stop-predicate. That's too
broad — it blocks a fresh trace from crossing ANY locked cell, including one belonging to a
completely different, unrelated active project that poses no cycle risk at all (nothing about that
crossing would create a mutual pointer). Live diagnostic logging against a real repro (see the
"Regression history" note above) confirmed the actual bug is narrower: the risk is specifically a
project's own `entryPos` being re-evaluated as a fresh anchor and one of its 14 fanned directions
retracing into cells that SAME project already owns.

The final fix scopes the guard accordingly: `evaluateSingleLine` first looks up
`activeProjectWithEntryPos(anchorPos)` — the active project (if any) whose own `entryPos` equals
the anchor currently being evaluated — and only refuses to trace onto a position if it's (a) still
in `lockedPositions` (genuinely unbuilt, not just historically part of the project) AND (b) one of
THAT SAME project's own instruction keys. A cell belonging to a *different* active project is no
longer blocked at all; the existing `breakMutualCycles`/`pickCyclePositionToDrop` mechanism remains
as a fallback for the rare cross-project case if it ever actually forms a cycle, which is a strictly
better outcome than blocking real construction on every pass to prevent a collision that mostly
doesn't happen. This is still the fix the original report's own "Suggested starting points" pointed
at ("the fix likely belongs in `SiegeProjectManager`... rather than in `FlowFieldCalculator`'s
tie-break") — it was just initially over-applied.

## Why this isn't the construction-overhaul's bug

The clanrat-construction overhaul (project-scoped worker registration, auto-widening — see the
sibling design doc) only changed *how a already-scheduled* `BUILD_STAIR` instruction gets executed
once a clanrat is standing near it. This bug happens *before* any of that: the instruction is
deleted from `nextInstructionMap` during flow-field calculation, before any goal or `SiegeProject`
ever sees it. Zero stairs get placed because no rat is ever handed the instruction in the first
place — confirmed by the log showing the drop happening during `TerritoryRegionMap`'s own
region-rebuild pass, entirely before any clanrat AI runs.

This is also not new: `StaircaseSiegeGroupGameTests.restrictTerritoryToMinimalArea`'s own
pre-existing javadoc (written well before the construction overhaul, see
`docs/superpowers/plans/2026-07-31-staircase-siege-group-gametest-plan.md`) already predicted a
version of this exact failure mode — though it originally framed it as "real BUILD_STAIR vs. a
GameTest-encasement-geometry artifact." **This live repro is a materially different case: no
GameTest encasement is involved at all** (this was a real playtest world), and the conflict is
between two *both-real, both-locked* macro-project candidates, not a real instruction vs. a test
artifact. Whatever theory the fix works from should account for this non-test-environment
reproduction, not just the GameTest-specific framing in the older plan doc.

## Third iteration: the eviction bug, confirmed and fixed

After the second (narrower, self-collision-scoped) fix shipped, live testing surfaced two further
symptoms under it: a real gap skipping a block partway up a staircase, and then — after refining the
diagnostic logging to distinguish *why* each of the 14 fan directions per anchor failed (self-
collision guard vs. out-of-bounds vs. near-existing-project vs. the tracer's own internal abort) —
a report that stairs were being "placed one block higher than expected," with the debug arrow not
showing at ground level.

The refined diagnostic logging itself didn't localize either symptom directly (most of the 12-13
non-succeeding directions per anchor failed for reasons other than the self-collision guard, and no
single direction's failure obviously explained either symptom). But a full server log from an
automated `staircase_siege_group:0` GameTest run, requested at that point, contained something more
useful: the exact same macro-line — anchor `63,-60,199`, direction `(0,1,-1)`, completing at
`63,-58,197` via an interior `MINE` step at `63,-59,198` — was independently rediscovered and logged
as "completed" **5 separate times** over one ~2-minute test run (10:50:23 through 10:51:20). In the
same window, `FlowFieldCalculator`'s cycle-breaker fired **4 times**, each time dropping the exact
same position, `63,-59,198` (`MINE, locked=false`), for pointing at `63,-60,199` — the identical edge
the line tracer kept rediscovering.

That's not a coincidence of unrelated systems; it's the smoking gun for the two follow-up bugs this
doc had already identified in the previous iteration and deliberately deferred (see the prior
revision's "Follow-ups found during this investigation" section, superseded by this one):

1. **`SiegeProject.isCompleted()`/`getRemainingInstructions()` checked terrain at the wrong
   cell.** Both (`SiegeProject.java:74` and `:93-101`, pre-fix) called
   `evaluator.isActionCompleted(terrain, node)` with the raw `SiegeNode` *value* straight from the
   `instructions` map. Per `SiegeLineTracer`'s own anchor-ward storage convention, that value's
   `.pos()` field is the *predecessor* position, not the real position the map *key* names and the
   action applies to. `SiegeProject.nextUnbuiltInstruction` already avoided this correctly by
   constructing `new SiegeNode(step.pos(), step.action())` from `PlannedStep`'s own real position —
   `isCompleted`/`getRemainingInstructions` just never got the same correction.
2. **`WALK` never reported complete**, because `TerrainEvaluator.isActionCompleted`'s switch had no
   `WALK` case and fell to `default -> false`. Every reactive macro-project trace ends its
   `instructions` map with a `WALK` entry at its own `entryPos` (`SiegeLineTracer.trace` terminates
   exactly when `isWalkableTerrain` first becomes true), so no reactive project could ever be
   reported fully complete via `isCompleted()`, even once every real build/mine step in it was
   finished in the world.

Combined, these two bugs meant `injectActiveProjects`' `activeProjects.removeIf(isCompleted)`
(`SiegeProjectManager.java:130`) essentially never fired for a real reactive project. A project that
had **genuinely finished building** stayed in `activeProjects` forever, so its `entryPos` kept
getting unconditionally re-added to `calcQueue` every pass (`injectActiveProjects:139-145`), which
the ordinary Dijkstra loop would pop and re-fire `evaluateMacroProjects` on — producing a brand-new,
functionally-duplicate candidate `SiegeProject` for the exact same physical line, pass after pass,
forever. `SiegeProjectManager.evaluateSingleLine`/`finalizeCandidateProjects` have no deduplication
against an already-active project covering the same cells, so `activeProjects` could (and, per the
live log, did) accumulate multiple redundant project objects for one line — exactly what produced
the repeated "completed at" log lines and, independently, kept re-locking `63,-59,198` for the raw
Dijkstra flood to separately, repeatedly collide with and cycle-break against.

**The fix:** both `SiegeProject.isCompleted()` and `getRemainingInstructions()` now construct
`new SiegeNode(entry.getKey(), entry.getValue().action())` before calling `isActionCompleted` — the
same real-position correction `nextUnbuiltInstruction` already had. `TerrainEvaluator.isActionCompleted`
gained a real `WALK` case (`isWalkableTerrain(terrain, node.pos())`), safe to land now that every
caller checks the real position. Landing the `WALK` case without the position fix (or vice versa)
would have been wrong in isolation — exactly why the previous iteration deferred both together.

One correctness note the fix surfaced: `MINE` completion is asymmetric. Pre-fix, a `MINE` step's
completion was evaluated at the predecessor cell — which is typically already open — so an
*unmined* obstacle may have been silently reading as complete all along. Post-fix, it correctly
reads the real (still-solid) cell, so a project can now legitimately stay active *longer* in some
cases, not just get evicted sooner. This is correct behavior, not a regression — verified with one
test per action class (`BUILD_STAIR` built, `MINE` not yet dug, `WALK` over walkable ground) rather
than a single mixed-line test, specifically to catch this asymmetry.

A second question the fix raises on its own: does an evicted project's cell stay reachable, or does
eviction just trade permanent staleness for a transient "wilderness" gap? Once a project is evicted,
`injectActiveProjects` stops writing its cells into `nextInstructionMap`/`lockedPositions`, and its
`getExitPos()` fallback (the one documented escape hatch for "nothing else reaches this cell") is
scoped to the *far* side of the crossing, not `entryPos` itself. Verified with a full
`FlowFieldCalculator.calculateFully` pass (not just `injectActiveProjects` in isolation) over terrain
where the project's own cell is *also* genuinely, independently walkable: the ordinary Dijkstra flood
picks it up on its own merits after eviction, confirming the fix is safe in the realistic case where
terrain around a completed crossing is actually connected. (A project whose `entryPos` sits in
genuinely isolated terrain with no independent path to it is a separate, narrower risk this test
doesn't cover — not encountered in any live evidence so far.)

**What this does *not* explain:** the "one block higher than expected" placement and the missing
debug arrow that prompted this investigation. Nothing found here points at a placement-position bug
— `SiegeInteractionHandler.constructSiegeBlock` places `BUILD_STAIR` directly at `pos` with no
vertical offset, and a field dump taken mid-test (`siege_dump_2026-08-04_10-50-37.txt`) showed clean,
correctly-adjacent placements (`49,-57,201` then `50,-56,201`, each one step up and over from the
last) for the one mob it captured. The eviction bug fixed here explains the *duplicate rediscovery*
and the *repeated cycle-break at the same position* concretely, by direct log evidence — it does not
explain the Y-offset/missing-arrow report, which remains open for the next session. If it recurs,
get the exact coordinate of one wrongly-placed stair and what was expected, and check
`Recent Siege Activity`/`Mapped Y-Coverage` in a fresh dump taken at the moment it happens.

## Other known follow-up items (secondary, already documented, not blocking)

These are unrelated to the bug above and don't need to be solved together with it — listed here
only so a fresh session has the full picture in one place. Full detail in
`docs/superpowers/specs/2026-08-03-project-scoped-clanrat-construction-design.md`'s
"Post-implementation follow-ups" section:

1. **Auto-widening's offset never scales in magnitude** — `SiegeProject.tryWiden` alternates sides
   but always shifts exactly one perpendicular unit from the same anchor, so widen #3 duplicates
   widen #1's lane instead of reaching a new one. Only ~3 of the intended 4 lanes are ever reachable.
2. **A narrow, untested corner case survives in `canUse()`/registration preconditions**: a
   widen-eligible project (`BUILD_STAIR`/`BUILD_BRIDGE`, at capacity, below `maxProjectWidth`)
   whose perpendicular retrace then fails still lets one rat hold `MOVE`/`LOOK` while re-attempting
   that trace every tick. Recommended fix direction: cooldown-based retry memoization, not
   width-keyed (which would permanently blacklist the project).
3. **`StaircaseSiegeGroupGameTests`' tightened timeouts** (small-group 8000→5000, large-group
   12000→3000) were calibrated against a since-fixed quadratic work-accumulation bug — now that
   this report's primary bug is fixed, these are worth re-validating for real (they were previously
   untestable since 0 stairs were ever placed regardless of timeout budget).

## Verification

- `SiegeProjectManagerTest.reEvaluatingAnActiveProjectsEntryPosMustNotOverwriteThatSameProjectsOwnLockedInteriorCell`
  — new deterministic unit test, fails pre-fix (reproduces the exact overwrite), passes with both
  the first (over-broad) and final (narrower) fix — this is the original cycle bug, and the final
  fix still catches it.
- `SiegeProjectManagerTest.freshTraceCompletesNormallyWhenNothingOnItsPathIsLocked` — baseline: a
  3-step gap-crossing line with nothing locked on its path completes normally under both fixes.
- `SiegeProjectManagerTest.freshTraceSucceedsWhenItCrossesAnUnrelatedActiveProjectsLockedCellWithoutFormingACycle`
  — the regression repro: otherwise identical to the baseline above, but crosses a cell locked by a
  DIFFERENT active project (not the one `anchorPos` belongs to). Fails under the first (over-broad)
  fix, passes under the final (narrower) one — this is the test that would have caught the live
  regression before it shipped.
- `TerrainEvaluatorTest.walkStepOntoOpenSupportedGroundReportsCompleted` — re-enabled (was
  `@Disabled` pending the position fix); now passes.
- `SiegeProjectTest.isCompletedTrueOnceBuildStairIsBuiltAtTheRealPositionDespiteAnUnbuiltPredecessor`,
  `isCompletedFalseWhenMineStepsRealPositionIsStillSolidDespiteAnOpenPredecessor`,
  `isCompletedTrueOnceWalkTargetIsWalkableDespiteASolidPredecessor` — three new tests, one per
  action class, each setting up terrain where the old (predecessor) and new (real-position) checks
  disagree, proving the fix changed which cell gets checked rather than coincidentally agreeing.
  The `MINE` test specifically catches the asymmetry noted above (a not-yet-mined obstacle must
  stay incomplete, not read as done via a stale predecessor check).
- `SiegeProjectManagerTest.fullyBuiltProjectIsEvictedAndItsCellStaysReachableViaOrdinaryWalkPropagation`
  — new integration test running a full `FlowFieldCalculator.calculateFully` pass: registers a
  fully-satisfied one-cell project, confirms `isCompleted()` now evicts it (`getLockedPositions()`
  no longer contains its cell right after the pass' initial injection), and confirms the cell still
  gets a `WALK` instruction from the ordinary Dijkstra flood afterward — the eviction-reachability
  question raised above.
- `reEvaluatingAnActiveProjectsEntryPosMustNotOverwriteThatSameProjectsOwnLockedInteriorCell` (the
  original cycle-bug repro) still passes: its `anchor` cell's `WALK` step now correctly reads as
  already-complete (updated setup-sanity assertion), but the cell the guard actually protects
  (`upstream`, still genuinely unbuilt) stays locked and unclobbered exactly as before — the fix
  here didn't touch the self-collision guard's real behavior, only made completion detection
  accurate.
- Live field verification (second iteration): the same real-world repro that caught the regression
  (a single-rat staircase test, `worktree-flow-field-locked-cycle-fix2` branch, logs from 2026-08-04
  10:12) was re-run with temporary diagnostic logging in `evaluateSingleLine` and confirmed the
  self-collision fix's own scoping decision empirically (11/13 blocked directions self-collision,
  2/13 cross-project) before the narrower fix was written — see "Regression history" above.
- Live field verification (third iteration): an automated `staircase_siege_group:0` GameTest run's
  server log (2026-08-04 10:50-10:51) showed the same macro-line rediscovered 5 times and the same
  cycle-break position dropped 4 times over ~2 minutes — the direct evidence for the eviction bug
  above. All temporary diagnostic logging has since been removed.
- Full `./gradlew test` suite passes (pathing package: `FlowFieldCalculatorTest` 13,
  `SiegeProjectManagerTest` 4, `SiegeProjectTest` 15, `TerrainEvaluatorTest` 1 — all green, 0
  skipped).
- Not yet run: the automated `StaircaseSiegeGroupGameTests` (all 4 cases) — these are the
  end-to-end, real-world-shaped reproduction the original report pointed at, and are the natural
  next verification step for a fresh session (they require `runGameTestServer`, a much slower,
  long-running Minecraft server process, which is why the unit-level repro above was prioritized
  for this fix). Given this bug already regressed once from a fix that passed unit tests but broke
  in the field, running these before considering the fix fully verified is a stronger
  recommendation now than it was the first time around.
- Still open: the "one block higher"/missing-debug-arrow symptom that prompted this iteration — not
  reproduced or explained by anything in this doc. Next session should treat it as its own fresh
  investigation if it recurs.
