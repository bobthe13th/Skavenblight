# Flow-Field Cycle-Breaker Drops a Locked, Needed BUILD_STAIR — Bug Report

**Status:** FIXED. Root cause confirmed with a deterministic unit repro (not just the live log),
and the original "two lines from the same anchor collide with each other" hypothesis below is
**refuted** — see "Root cause, corrected" for what actually produces the cycle and why. The fix
landed in `SiegeProjectManager.evaluateSingleLine`. Two secondary bugs surfaced during the
investigation that are real but deliberately NOT fixed here (larger blast radius, no repro tying
them to this symptom) — see "Follow-ups found during this investigation."

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

**The fix** (`SiegeProjectManager.evaluateSingleLine`): add `lockedPositions.contains(pos)` to the
trace's own stop-predicate, alongside the existing `isOutOfBounds`/`isNearExistingProject` checks.
This makes a locked cell's instruction immutable within a pass — a fresh trace now aborts the
instant it would step onto any position an active project already owns, the same "never touch a
locked cell" invariant `TerrainEvaluator.getValidOrthogonalSteps` already enforces for the ordinary
core-flood step (`lockedPositions.contains(neighbor)` at line 37). This is the fix the original
report's own "Suggested starting points" pointed at ("the fix likely belongs in
`SiegeProjectManager`... rather than in `FlowFieldCalculator`'s tie-break") — it was just aimed at
the wrong collision (sibling-vs-sibling instead of fresh-trace-vs-stale-active-project).

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

## Follow-ups found during this investigation (not fixed here — see each item for why)

Two more bugs surfaced while confirming the root cause above. Both are real, but deliberately left
unfixed in this change: fixing either changes lock/build-completion timing for every active
`SiegeProject` in the mod, a materially larger blast radius than the confirmed cycle fix, and
neither has a repro tying it directly to "0 stairs ever placed." A fresh session should tackle them
as their own investigation, in this order (the second depends on understanding the first):

1. **A reactive `SiegeProject` can never be evicted via `isCompleted()`, because `WALK` never
   reports complete.** `TerrainEvaluator.isActionCompleted`'s switch has cases for `MINE` and every
   `BUILD_*` action but falls to `default -> false` for `WALK` (`TerrainEvaluator.java:186-208`
   pre-fix). Every reactive macro-project trace ends its `instructions` map with a `WALK` entry at
   its own `entryPos` — `SiegeLineTracer.trace` terminates exactly when `isWalkableTerrain` first
   becomes true, and that final hop's own `determineMacroAction` call sees already-open, supported
   ground and returns `WALK` (`TerrainEvaluator.java:100-146`). Since `SiegeProject.isCompleted()`
   requires `instructions.values().stream().allMatch(...)`, and the `WALK` entry's own check can
   never pass, **no reactive project with a normal (non-`BUILD_LANDING`) landing can ever be
   reported complete**, even once every real build/mine step in it is finished in the world. That
   leaves it in `SiegeProjectManager.activeProjects` forever (`injectActiveProjects`'
   `removeIf(isCompleted)` never fires for it), continuously re-locking its cells and re-injecting
   its stale instructions on every pass — plausibly a large part of why `lockedPositions` grows
   large enough for the `MAX_ACTIVE_PROJECTS` eviction-by-age cap's own comment to note "803 active
   projects in one test." It's also the precondition this bug's whole reproduction depends on: an
   active project must persist indefinitely for its `entryPos` to keep getting re-processed as a
   fresh anchor pass after pass. `TerrainEvaluatorTest.walkStepOntoOpenSupportedGroundReportsCompleted`
   reproduces this in isolation (currently `@Disabled` — see its class doc for why fixing it isn't
   safe to do in isolation, per item 2 below).
2. **`isCompleted()`/`getRemainingInstructions()` check terrain at the wrong cell.** Both
   (`SiegeProject.java:74` and `:93-101`) call `evaluator.isActionCompleted(terrain, node)` with the
   raw `SiegeNode` *value* straight from the `instructions` map. But per `SiegeLineTracer`'s own
   anchor-ward storage convention (see its doc on `TraceResult.instructions`), that value's `.pos()`
   field is the *predecessor* position, not the real position the map *key* names and the action
   applies to — confirmed live by the setup-sanity assertion in this investigation's own draft
   `SiegeProjectManagerTest`, which failed once the WALK case (item 1) was patched, because
   `isActionCompleted` was evaluating walkability at `upstream` (the value's `.pos()`) instead of
   `anchor` (the real key). `SiegeProject.nextUnbuiltInstruction` avoids this correctly by
   constructing `new SiegeNode(step.pos(), step.action())` from `PlannedStep`'s own real position
   before calling `isActionCompleted` (`SiegeProject.java:191-201`) — `isCompleted`/
   `getRemainingInstructions` need the same correction. Whether this makes completion detection
   *looser* or *stricter* isn't yet known and depends on action type (a `BUILD_STAIR` chain's
   predecessor cell is often exactly what the chain just built, which could make cells unlock
   *before* they're actually built — the opposite failure mode from item 1) — this needs its own
   investigation and its own test (a `SiegeProjectTest` fixture with a real multi-step chain and
   terrain that's built at the predecessor cell but not the real one, verifying
   `getRemainingInstructions` doesn't drop the still-unbuilt entry, is a good starting point).
   **Fix item 1 only after this is resolved** — landing the `WALK` fix first, on top of the
   predecessor-position bug, would make `isActionCompleted` terrain-sensitive at the wrong cell for
   every caller.

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
  — new deterministic unit test, fails pre-fix (reproduces the exact overwrite), passes post-fix.
- `TerrainEvaluatorTest.walkStepOntoOpenSupportedGroundReportsCompleted` — new unit test capturing
  follow-up item 1, `@Disabled` pending item 2's own fix.
- Full `./gradlew test` suite passes.
- Not yet run: the automated `StaircaseSiegeGroupGameTests` (all 4 cases) — these are the
  end-to-end, real-world-shaped reproduction the original report pointed at, and are the natural
  next verification step for a fresh session (they require `runGameTestServer`, a much slower,
  long-running Minecraft server process, which is why the unit-level repro above was prioritized
  for this fix).
