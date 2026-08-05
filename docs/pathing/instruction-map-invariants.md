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
they're in). The durable fix is a distinct map-value type that can't be confused with a standalone
`SiegeNode` — e.g. `record FlowStep(BlockPos next, SiegeAction actionHere)` — so the type system
itself rules out passing a map value where a real-position `SiegeNode` is expected. This was
investigated this session and deliberately **not started**: it spans all producers/consumers listed
above (at least `FlowFieldCalculator`, `SiegeLineTracer`, `SiegeProjectManager`, `SiegeProject`,
`RegionGraph`, plus every `TerrainEvaluator` call site that takes a `SiegeNode`) — real scope, not a
same-session patch. Until it happens, the central-invariant rule above (reconstruct before checking
completion) is the load-bearing safety net, and it's test-enforced (see citations above) — but it
depends on every future call site remembering to apply it by hand.
