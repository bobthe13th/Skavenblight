# Siege-pathing instruction map — invariants

This document exists because five real bugs in one session (2026-08-04) all traced back to the
same handful of invariants about `Map<BlockPos, SiegeNode>` — the shape every flow-field
instruction map, macro-project trace, and `SiegeProject` uses — being scattered across comments in
individual files rather than written down anywhere a fresh session would read them first. Every
invariant below is either enforced by a specific test (cited) or documented as convention-only
(also stated explicitly, so nobody mistakes one for the other).

All classes named below live under `src/main/java/org/ratden/skavenblight/ai/pathing/` unless
stated otherwise.

## The central invariant: `SiegeNode.pos()` means two different things depending on context

This is the single most consequential thing to understand before touching this system, and it was
misdiagnosed at least twice this session before being pinned down precisely.

**The map itself is correct and does not need "fixing."** Every producer of an instruction map —
`FlowFieldCalculator`'s core Dijkstra flood (`nextInstructionMap.put(step.pos(), new
SiegeNode(current, action))`, `FlowFieldCalculator.java:195`) and `SiegeLineTracer.trace`'s macro
lines (`instructions.put(nextPos, new SiegeNode(currentTarget, action))`,
`SiegeLineTracer.java:118`) — uses the identical, deliberate convention:

- **The map KEY is the real position the action applies to.**
- **The stored `SiegeNode`'s `.action()` is the action to perform at that key.**
- **The stored `SiegeNode`'s `.pos()` is NOT the key's own position.** It's the next hop *toward
  the target* — one step closer to whatever this flow field is routing mobs to. This is a standard
  Dijkstra predecessor-tree pointer, read in the direction a mob actually needs it: "predecessor in
  the search" is "successor for the mob." For a mob standing at position P, `instructions.get(P)`
  telling it where to walk next is exactly the intended, correct behavior.

The footgun is that `SiegeNode(pos, action)` is used as **both** a map value (where `pos()` means
"next hop") **and** a standalone value passed around on its own — most importantly, the argument to
`TerrainEvaluator.isActionCompleted`, which expects `pos()` to mean "the real position this action
applies to." Those are different meanings of the same field, and nothing in the type system
distinguishes them.

**Rule: any completion/terrain check against a value pulled out of an instructions map must
reconstruct it first — `new SiegeNode(entry.getKey(), entry.getValue().action())` — never pass the
raw stored value.** Three separate production bugs this session were exactly this mistake:
`SiegeProject.isCompleted()`, `SiegeProject.getRemainingInstructions()`, and (implicitly, before a
prior fix) `nextUnbuiltInstruction()`'s completion check. All three now do the reconstruction
correctly — see their own doc comments in `SiegeProject.java`.

**Test-enforced:** `SiegeProjectTest.isCompletedTrueOnceBuildStairIsBuiltAtTheRealPositionDespiteAnUnbuiltPredecessor`,
`isCompletedFalseWhenMineStepsRealPositionIsStillSolidDespiteAnOpenPredecessor`,
`isCompletedTrueOnceWalkTargetIsWalkableDespiteASolidPredecessor` — each sets terrain so the key and
the stored value's `.pos()` disagree, proving the check reads the right one. Confirmed real by a
mutation-testing audit this session (reverting the fix makes all three fail).

**A previous version of `SiegeLineTracer.TraceResult`'s own javadoc called this an "anchor-ward"
convention "the same off-by-one the core flood has" and implied it needed correcting.** That
framing was wrong and is exactly what caused this session to spend real effort planning a
"convention swap" refactor that turned out to be unnecessary — the map was already correct; only
specific consumers were misusing it. The javadoc has been corrected in place
(`SiegeLineTracer.java`). If you find yourself reasoning about "which direction the pointers go" as
though it's a bug, stop and re-read this section first.

## Self-referential entries mark a local objective, not a cycle

An entry where `value.pos().equals(key)` means "this position IS the objective, nothing further to
route to" — not a degenerate/broken pointer. Two producers of this:

- The flood's own target: `nextInstructionMap.put(targetPos, new SiegeNode(targetPos, WALK))`
  (`FlowFieldCalculator.java:133`).
- A project's `exitPos` fallback: `nextInstructionMap.putIfAbsent(exit, new SiegeNode(exit, WALK))`
  (`SiegeProjectManager.java:178`).

`FlowFieldCalculator.detectMutualCyclePositions` deliberately skips these (`next.equals(current)`
check, `FlowFieldCalculator.java:424`) rather than reporting a 1-cycle. **Test-enforced:**
`FlowFieldCalculatorTest.selfReferencingLocalObjectiveIsNotACycle`.

## Build order runs entryPos → target, not target → entryPos

`SiegeProject.buildOrder` is deliberately **not** derived from `instructions` (whose values point
toward the target, the wrong direction for "what to build first"). It's built from
`SiegeLineTracer.TraceResult.orderedSteps()` — reversed and re-anchored on `endPos` — because
`orderedSteps` is recorded walking outward from the target-side anchor to the mob-side `endPos`, and
a mob physically climbing/crossing from `entryPos` (which *is* `endPos`) needs the step nearest
itself built first. This mirrors `RegionGraph.registerConnector`'s own `towardA`/`towardB`
direction, which already needed and got identical treatment for a connector traversed from either
end. See `SiegeProjectManager.evaluateSingleLine`'s own comment for the full reasoning, including
why the reversed list's leading terminal-WALK entry is dropped (left in place, it produces a
zero-length direction vector that breaks `SiegeProject.tryWiden`'s auto-widening for every
BUILD_STAIR/BUILD_BRIDGE project).

**Test-enforced:** `SiegeProjectManagerTest.reactiveCandidateBuildOrderProceedsFromEntryPosTowardAnchorNotTheOtherWayAround`
(asserts build order proceeds entryPos-first) plus its own `assertFalse(buildOrder.contains(entryPos))`
assertion (the degenerate-leading-entry fix).

## entryPos needs routing even once nothing needs building there

Once `TerrainEvaluator.isActionCompleted`'s `WALK` case correctly reports an already-standable
`entryPos` as done, `SiegeProject.getRemainingInstructions()` stops including it — correctly, since
nothing needs building there. But "nothing to build" is not the same as "nothing to route to": a
project's `entryPos` is, by construction, the far side of a gap the ordinary Dijkstra flood cannot
independently cross while the project's interior is still locked/unbuilt. Without a fallback, a mob
standing exactly on `entryPos` gets no instruction at all once its own WALK step reads complete,
stranding it. `SiegeProjectManager.injectActiveProjects` fixes this with a `putIfAbsent` fallback
(`SiegeProjectManager.java:158-161`) — deliberately `putIfAbsent`, not an overwrite, so a genuinely
cheaper real route the ordinary flood found elsewhere in the same pass is never clobbered.

**Test-enforced:** `SiegeProjectManagerTest.entryPosStaysRoutableOnceItsOwnWalkStepReadsCompleteButTheProjectsInteriorIsStillUnbuilt`.

## What a connector project does and doesn't self-key

**Correction (2026-08-04, later the same session):** an earlier version of this section claimed
`RegionGraph.registerConnector`'s connector projects don't key their own `entryPos`. That was
wrong, found by directly tracing `outboundInstructions`/`inboundInstructions` rather than trusting
the paraphrase that produced it — the exact same doc-drift failure mode this whole document exists
to stop, so it's worth recording the correction rather than quietly fixing it.

**What's actually true:** both `towardA` and `towardB` DO key their own `entryPos` — trace it
yourself before relying on this: `outboundInstructions(anchor, steps)` puts `anchor` (towardB's
entryPos) as its very first key; `inboundInstructions(anchor, steps)` puts `steps.get(size-1).pos()`
(towardA's entryPos, since `orderedSteps`' last element is `endPos`) at `i = size-1`. **What
neither one keys is its own `exitPos` (the far endpoint)** — `outboundInstructions` never keys
`steps.get(size-1).pos()`, and `inboundInstructions` never keys `anchor` — matching
`registerConnector`'s own doc comment ("neither outboundInstructions nor inboundInstructions keys
their own far endpoint"). That's why `exitPos` gets its own separate trivial self-WALK fallback in
`injectActiveProjects` (see `SiegeProjectManager.java:176-179`), distinct from the entryPos
fallback.

The `entryPos` fallback's null-guard (`SiegeProjectManager.java:158-161`) is still correct
defensive code — a `SiegeProject` CAN in principle be constructed without its `entryPos` keyed
(the null-guard test builds exactly that directly), so the guard should stay — but as of this
session's `RegionGraph.registerConnector`, no real connector project actually exercises that path.
If you're debugging a null-instruction-at-entryPos case, look elsewhere first.

**Test-enforced:** `SiegeProjectManagerTest.entryPosFallbackNeverInsertsNullForAConnectorProjectThatDoesNotKeyItsOwnEntryPos`
(proves the guard itself works, using a hand-built project shaped that way — not evidence that a
real connector produces one).

## A reactive project's own entryPos must not overwrite its own locked interior on re-entry

An active project's `entryPos` is unconditionally re-seeded into `nextInstructionMap`/`calcQueue`
every pass (see the fallback above), so ordinary Dijkstra can pop it again and re-fire
`evaluateMacroProjects` on it as a fresh obstacle anchor. Without a guard, one of the 14 fanned
directions could trace straight back over a cell that same project already owns, silently
overwriting that cell's correct instruction and forming a direct mutual 2-cycle with the anchor.
The guard in `SiegeProjectManager.evaluateSingleLine` is deliberately scoped to **self-collision
only** — `anchorPos` is that same project's own `entryPos`, AND the position being traced onto is
still locked as one of that same project's own cells — not "abort on any locked cell" (which would
also block a fresh line merely crossing a *different*, unrelated project's cell, which poses no
cycle risk and would otherwise stop real construction from ever being planned).

**Test-enforced:** `SiegeProjectManagerTest.reEvaluatingAnActiveProjectsEntryPosMustNotOverwriteThatSameProjectsOwnLockedInteriorCell`
(the guard fires), `freshTraceSucceedsWhenItCrossesAnUnrelatedActiveProjectsLockedCellWithoutFormingACycle`
(the guard does NOT over-block cross-project crossings).

## A degenerate, empty-build-order candidate line must not be committed

The self-collision guard above only covers an anchor re-entering its OWN project's cells at that
project's `entryPos`. It does not cover a broader case: **any** anchor that's already a locked cell
of an active project — including a region's own nexus/target position, which
`FlowFieldCalculator.startCalculation` seeds onto `calcQueue` unconditionally regardless of lock
state — can still have `evaluateMacroProjects` fire on it. If a genuinely-walkable cell sits one
step away in some direction, `SiegeLineTracer.trace` terminates in a single WALK hop immediately
(its own termination condition: the first step is already walkable), and after
`evaluateSingleLine`'s existing reversal + leading-WALK-drop, `buildOrderSteps` ends up **empty** —
a "successful" candidate with nothing to build. Before the fix below, this got committed anyway:
its one `instructions()` entry was `putAll`'d into `nextInstructionMap` with no cost comparison,
silently overwriting whatever the far cell already had with a new instruction pointing straight
back at the anchor — which, paired with the anchor's own pre-existing forward-pointing instruction,
forms a direct mutual 2-cycle. `FlowFieldCalculator`'s cycle-breaker then resolves it by dropping
the far cell's (unlocked) entry entirely, leaving it with no instruction at all.

Confirmed live via `testParentRegionGetsRealInstructionsForSharedConnectorCells`: the region's own
nexus/target sat one step from a connector's genuinely-walkable far/exit side, and this exact
sequence left that far side with no flow-field instruction. The fix (`evaluateSingleLine`): if
`buildOrderSteps.isEmpty()` after the leading-WALK-drop, return before committing the candidate at
all — don't add it to `candidateProjects`, don't touch `nextCostMap`/`nextInstructionMap`/`calcQueue`.

**Test-enforced:** `SiegeProjectManagerTest.evaluateMacroProjectsMustNotOverwriteAGenuinelyWalkableCellWithADegenerateEmptyBuildOrderLine`.

## Stacked stair columns cannot be re-traversed from below once built (known, unfixed limitation)

`BUILD_SPIRAL` and chained `BUILD_STAIR` lines both place one `cobblestone_stairs` block per Y
level in the same (x,z) column (`SiegeLineTracer` steps `dy` by exactly ±1 per node — see
`SiegeProjectManager`'s 14-direction fan; there is no "rise 2" or landing-per-turn option today).
`TerrainEvaluator.isFitForWalking`'s head-clearance check (fixed this session — see the commit
"stop treating scaffold materials as clear headroom overhead") no longer exempts stairs/slabs/
ladders/cobblestone from blocking overhead, and that fix is correct: it was verified against real
vanilla mob navigation, not just the abstracted model. **But fixing that predicate did not make the
column climbable** — a real `ClanratEntity` using unmodified vanilla `PathNavigation` still cannot
climb past the first couple of levels of such a column when approaching it from below, confirmed
empirically in `StackedStairColumnReTraversalGameTests`. This means:

- A rat that **builds** the column one step at a time, climbing as it goes, is fine — each step's
  completion resolves it to stand on its own newly-placed block (see
  `RegionFlowFieldClimbResolutionGameTests.testCompletedSpiralStepResolvesToOwnPosition`), and it
  never needs to navigate the *whole* column at once via ordinary pathfinding.
- A **second** rat (or the same rat backtracking) sent up an *already-completed* column from below
  gets stuck a couple of levels up. This is a plausible mechanism behind "the flow field routes a
  rat into the back of an existing stair" reports against live dumps.

**Not fixed.** Fixing it means changing how `BUILD_SPIRAL`/chained `BUILD_STAIR` emit geometry
(wider shaft, or periodic landings so a mob's hitbox actually clears each transition) — a
construction-geometry change, not a `TerrainEvaluator` predicate tweak. Left as an open item for a
deliberate follow-up decision.

## Open design debt: `SiegeNode`'s dual meaning is the underlying footgun

Every bug and near-miss in this document's central invariant traces to one root cause: `SiegeNode`
is used both as "the action at a known position" (unambiguous on its own) and as "a map value whose
`.pos()` means something else entirely" (ambiguous, and every reader has to already know which role
they're in).

**Correction (2026-08-05, a later session): it's not a two-shape problem, it's three.** The
paragraph originally here proposed a two-type split (a `FlowStep` map-value type, `SiegeNode`
narrowed to standalone) as the durable fix. A full-codebase Explore survey done to scope that split
found a THIRD shape hiding in `RegionGraph.outboundInstructions`/`inboundInstructions`: connector
projects pair `.pos()`/`.action()` the OPPOSITE way from the core flood and reactive macro-tracer
(forward pairing — `.pos()` is the real action position one step ahead — instead of the predecessor
pairing everything else uses), and that shape gets copied verbatim into the same
`Map<BlockPos, SiegeNode>` by `SiegeProjectManager.injectActiveProjects` with no tag distinguishing
it. A naive two-type split doesn't remove this — it just relocates the ambiguity to "is this map
entry actually a `FlowStep` or a `SiegeNode` wearing a `FlowStep`'s map slot?" The real fix
canonicalizes `RegionGraph` to emit the same predecessor-pairing everything else already uses,
*then* the two-type split becomes sound. Two runtime-unverified risks were also found in the same
survey and need resolving before or during the fix, not assumed away: whether
`SiegeProject.isCompleted`/`getRemainingInstructions`'s unconditional reconstruction is actually
correct for a connector-sourced (not reactively-traced) project, and whether
`RegionFlowField.getNextSiegeNode` — which reads a raw map value without reconstructing, the
single highest-traffic call site in the whole system — happens to be coincidentally correct today
only because of which shape currently reaches it in practice. See
`docs/superpowers/plans/2026-08-05-siegenode-dual-meaning-split-plan.md` for the full scoped plan,
file-by-file impact list, and migration order. Still **not started** — this correction only fixes
the scoping, not the code.

## Resolved (2026-08-06): root cause of the `StaircaseSiegeGroupGameTests` failures found

**This is the answer to the breadcrumb below.** Found while executing the pathing-rewrite plan's
Task 21 go/no-go gate (`docs/superpowers/plans/2026-08-05-pathing-rewrite-implementation-plan.md`,
which has the full writeup with log evidence — this note is a pointer, not a duplicate). Short
version: the "canonicalize `RegionGraph` to Shape A" fix this doc's "Open design debt" section and
its sibling `2026-08-05-siegenode-dual-meaning-split-plan.md` recommended DID get implemented, in the
current rewrite's Tasks 6 and 9 (confirmed by reading `FlowFieldCalculator.processNeighbors` and
`RegionGraph.outboundInstructions`/`inboundInstructions` directly — both now produce genuine Shape A:
`.pos()` always equals the map key, `.predecessorPos()` carries the next hop). **But the consumers
this fix was supposed to make unambiguous for — `FollowFlowFieldGoal`, `SiegeNodeLookahead` — were
only mechanically retyped when they were later ported (Tasks 10/17), never updated to read
`.predecessorPos()` instead of `.pos()` for "where do I go next."** They still read `.pos()`, which
was the OLD `SiegeNode`'s only field and secretly meant "next hop" on a map value — under the NEW,
disambiguated `FlowStep` convention this producer-side fix established, `.pos()` on a map value now
always equals the query key, never the next hop. Every hand-fed GameTest/unit-test fixture happens to
encode the OLD, consumer-compatible shape, which is exactly why nothing caught this until the first
real end-to-end GameTestServer run (Task 20/21 of the current rewrite, the first time `compileJava`
had been green since Task 5). Not fixed yet — see the plan doc's Task 21 section for the scoped
candidate fix and why it wasn't landed the same session.

## Next-session breadcrumb: the 4 remaining `StaircaseSiegeGroupGameTests` failures

As of this session's last full run, `testSingleRatBuildsStaircaseAcrossSmallGap`,
`testSmallGroupBuildsStaircaseAcrossSmallGap`, `testLargeGroupBuildsStaircaseAcrossSmallGap`, and
`testLargeGroupBuildsChainedStaircaseAcrossGiantGap` all still fail with **0 stairs built**, every
run, including with this session's head-clearance fix fully reverted (so that fix is not the
cause). All four fail identically — not "some rats are slow," a single shared root cause.

**Retracted (2026-08-05, later session):** the paragraph that used to sit here claimed the diagonal
`BUILD_STAIR` crossing line was "confirmed still being discovered" via a `"Cost: 5275"` log entry.
That entry was never bounds-checked against the actual test structure it was cited for — `pathing_test_giant`
gets placed at a different world offset every run, so proximity between a candidate's coordinates
and a test's own nexus/rat position in a *different* run's log proves nothing. Bounds-checking
properly (structure origin from the failure line, 96×96 footprint) in a later pass found no
in-bounds macro-line/candidate at all for the single-rat test in that run — a different result from
what's asserted above. See `docs/superpowers/plans/2026-08-04-staircase-siege-zero-stairs-investigation-plan.md`'s
"Step 1 result (corrected)" section for what's actually confirmed: this test's crossing is a
route-tree **connector** (already correctly shaped, real `WALK`/`MINE`/`BUILD_STAIR` actions,
entryPos exactly under the rat), not a reactively-discovered candidate — so the whole
discovery-vs-Stage-B framing below doesn't apply to this test. Don't cite the retracted claim; read
the plan doc's corrected section instead.

**A separate, likely-unrelated observation from the same run:** `StrandedGoal` logs many rats
"stalled ... heading toward <same X,Y, Z+1>" with `issued=false hasPath=true canReach=true
nextIdx==nodes` and "no obstacle to breach (trace is all WALK/LEAP)". The stall axis (Z) is
orthogonal to this test's actual gap-crossing axis (X/Y, Z constant) — these are rats doing
ordinary flat-ground lateral repositioning near spawn, not stuck at the gap edge, and "trace is all
WALK/LEAP" is the CORRECT thing to report on flat ground with nothing to build. `issued=false` from
vanilla `PathNavigation.moveTo` is also the documented return when the recomputed path is unchanged
from the existing one. Investigated and set aside as plausible benign noise, not chased further -
verify this reading (do any rats' logged positions ever approach the platform's X, or do they stay
in the spawn zone for the whole run?) before treating it as a lead.
