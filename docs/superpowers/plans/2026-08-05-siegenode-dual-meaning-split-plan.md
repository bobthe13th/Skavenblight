# Plan: eliminate `SiegeNode`'s dual meaning

**Status: plan only, nothing in this document has been implemented.** Written in response to a
direct request to scope this refactor, after a full-suite Explore survey of every `.pos()` call
site, `new SiegeNode(...)` construction, and `Map<BlockPos, SiegeNode>` read/write in
`src/main/java`. No code changes were made while writing this.

## Why this matters now

`docs/pathing/instruction-map-invariants.md`'s "Open design debt" section already named
`SiegeNode`'s dual meaning as the root cause behind five real bugs fixed in one session
(2026-08-04) and proposed a fix — a distinct map-value type — but deliberately didn't start it,
estimating "at least `FlowFieldCalculator`, `SiegeLineTracer`, `SiegeProjectManager`,
`SiegeProject`, `RegionGraph`, plus every `TerrainEvaluator` call site" as scope.

That estimate underestimates the problem in one specific way: it assumes there are two shapes
(map-value vs. standalone). **There are three**, and the third one — produced by `RegionGraph`,
consumed by `RegionFlowField.getNextSiegeNode` — is not what the original two-type proposal
(`FlowStep(BlockPos nextHop, SiegeAction actionHere)` + keep `SiegeNode` for standalone) would fix.
Under that proposal, `RegionGraph`'s connector values would still be `SiegeNode`-shaped data sitting
in a `FlowStep`-shaped map slot — the ambiguity moves, it doesn't disappear. This plan corrects that
and scopes the real fix.

## The three shapes, precisely

All three currently share the single type `record SiegeNode(BlockPos pos, SiegeAction action)`.

**Shape A — predecessor/next-hop** (map value). Key = the real position the action applies to.
`value.pos()` = the next hop *toward the target* (a Dijkstra predecessor pointer — "predecessor in
the search" is "successor for the mob"). `value.action()` = the real action **at the key**.
Producers: `FlowFieldCalculator.nextInstructionMap` (core Dijkstra flood, `FlowFieldCalculator.java:195`)
and `SiegeLineTracer.instructions` (macro-project traces, `SiegeLineTracer.java:130`), injected
verbatim into `nextInstructionMap` by `SiegeProjectManager.evaluateSingleLine`. This is the
convention `SiegeLineTracer`'s own Javadoc and `SiegeProject.isCompleted`/`getRemainingInstructions`
already document and correctly reconstruct against.

**Shape B — forward pairing** (also a map value, but the *opposite* pairing). Key = a position a
mob can currently occupy. `value.pos()` = the real position **one step ahead**, where the action
physically happens. `value.action()` = the action **for `value.pos()`, not the key**. Producer:
`RegionGraph.outboundInstructions`/`inboundInstructions` (`RegionGraph.java:203-228`) — the
persistent region-to-region connector projects, explicitly documented in `RegionGraph`'s own
Javadoc as deliberately different from `SiegeLineTracer`'s convention "because that convention is
wrong for what to build ... in what order."

**Standalone** — a freshly-constructed `SiegeNode(realPos, action)`, not sourced from any map
lookup. `pos()` = real position, `action()` = the real action for it. This is what
`TerrainEvaluator.isActionCompleted`/`calculateActionCost` expect as an argument, and what
`SiegeLineTracer.orderedSteps`, `AbstractSiegeConstructionGoal.Target`, and `SiegeProject.PlannedStep`
already are (as different, unambiguous record types — see "Existing precedent" below).

**The actual bug surface**: `SiegeProjectManager.injectActiveProjects` copies whatever shape
`SiegeProject.getRemainingInstructions()` returns — Shape A or Shape B, depending on whether the
project came from a reactive macro-trace or a route-tree connector — straight into
`nextInstructionMap` with **no reconstruction and no shape tag** (`SiegeProjectManager.java:136`,
`:158-160`). Both shapes end up published together in the same `FlowFieldState` instruction map.
Nothing at the type level, or the map level, marks which shape a given entry is. Every consumer
picks a shape by convention and is right for whichever provenance it was written against — and
silently wrong, or coincidentally right, for the other.

## Two runtime-unverified risks — resolve these before or during the refactor, not by reading alone

The survey could not settle these by inspection; they need a targeted test or trace:

1. **`SiegeProject.isCompleted`/`getRemainingInstructions`/`nextUnbuiltInstruction`
   (`SiegeProject.java:80,104`)** unconditionally reconstruct via `new SiegeNode(entry.getKey(),
   entry.getValue().action())` — correct for Shape-A-sourced `instructions`, but a project's
   `instructions` map can ALSO come from `RegionGraph.registerConnector`'s Shape-B-shaped
   `outboundInstructions`/`inboundInstructions` (`RegionGraph.java:157-160`, passed straight into
   `SiegeProject`'s constructor). For a Shape-B-sourced project, this reconstruction would pair the
   wrong action with the wrong position. **Write a test that constructs a `SiegeProject` from a
   genuinely Shape-B-shaped instructions map (mirroring what `registerConnector` actually builds,
   not a hand-simplified Shape-A fixture) and check whether `isCompleted`/`getRemainingInstructions`
   report the right thing.** This determines whether route-tree connectors have a *second*, still-
   latent version of the exact bug class fixed in `SiegeProject` for reactive projects on
   2026-08-04.
2. **`RegionFlowField.getNextSiegeNode` (`RegionFlowField.java:92-99`)** reads the raw map value and
   passes it directly to `isActionCompleted` without reconstructing `new SiegeNode(ratPos,
   node.action())`. This is correct for Shape-B entries (where `.pos()` genuinely is the real action
   position, matching this method's own doc comment) and wrong for Shape-A entries (core-flood
   WALK/MINE/BUILD_* and reactive-tracer nodes, where `.pos()` is the predecessor). Since the source
   map mixes both shapes, **this single call site has the widest blast radius of anything found** —
   it's the hot path behind `SiegeNodeLookahead.findEffectiveNode`, `FollowFlowFieldGoal`,
   `WarpSapperGoal`, and `PathingDebugFileWriter`, called per mob per tick. Given this session's own
   trace found a real, confirmed bug in `FollowFlowFieldGoal`'s climb mechanism sitting exactly
   downstream of this method (see the staircase investigation plan's "Root cause found" section),
   this is the single highest-value site to instrument or test next, independent of when the wider
   refactor happens.

## Recommended approach: canonicalize at the source, don't add a third type

Two options were identified; recommending the first:

**(a) Canonicalize `RegionGraph` to emit genuine Shape-A values.** Rewrite
`outboundInstructions`/`inboundInstructions` construction so connector projects produce the same
predecessor-pointer shape the reactive macro-tracer already does — i.e., fix the mismatch at the
one place it's introduced, so every `Map<BlockPos, FlowStep>` in the codebase becomes unambiguous
by construction. This is less invasive than it sounds: `RegionGraph` already has the ordered
`orderedSteps`/`Target`-shaped step list (Standalone shape) it built the Shape-B map FROM — the fix
reuses that same list with the OTHER pairing (predecessor, not forward), mirroring exactly what
`SiegeLineTracer` itself does for its own two parallel outputs (`instructions` vs. `orderedSteps`,
`SiegeLineTracer.java:130-131`). Every downstream consumer (`RegionFlowField.getNextSiegeNode`,
`SiegeProject.isCompleted`, `AwaitFormationGoal.findUnclaimedAlternative`) then has exactly one
convention to assume, matching what most of them already assume today.

**(b) Introduce a third distinct type for connector-sourced maps**, with every consumer branching
on provenance. Rejected as the primary approach: it doesn't remove the ambiguity, it relocates it
to "which of three types is this," and every one of the ~15 consumer files would need a branch
instead of a single, uniform assumption. Worth falling back to only if (a) turns out to be
impossible for a reason not yet discovered (e.g. some consumer genuinely needs Shape B's own
pairing and can't be rewritten) — re-derive from evidence if (a) stalls, don't default to (b).

## The new type

```java
public record FlowStep(BlockPos nextHop, SiegeNode.SiegeAction actionHere) {}
```

Replaces every Shape-A map value (`Map<BlockPos, SiegeNode>` → `Map<BlockPos, FlowStep>`) once (a)
above makes Shape A the only map-value shape in the codebase. `SiegeNode` itself stays, narrowed to
mean only "standalone: a real position paired with its own action" — matching what
`SiegeLineTracer.orderedSteps`, `AbstractSiegeConstructionGoal.Target`, and `SiegeProject.PlannedStep`
already independently converged on as a pattern, without knowing it was the right shape all along.

**Existing precedent worth reusing, not re-inventing:** the codebase already has THREE separate
"standalone position+action" records doing the same job with different field names —
`SiegeLineTracer`'s `orderedSteps` elements (raw `SiegeNode`, used standalone),
`AbstractSiegeConstructionGoal.Target(BlockPos pos, SiegeAction action, Direction facing)`, and
`SiegeProject.PlannedStep(BlockPos pos, SiegeAction action, Direction facing)`. Consider whether the
narrowed `SiegeNode` and `Target`/`PlannedStep` should actually unify (the latter two only differ by
an added `facing` field) as a *separate*, smaller cleanup — not required for this refactor to
succeed, but worth a deliberate yes/no rather than leaving three near-duplicates in place by
accident.

## File impact (from the survey; ~25-28 production files, 8+ test files)

Grouped by role, not file order — this is what actually changes, not just what mentions `SiegeNode`:

**Shape A/B unification (the real fix, do this first):**
- `RegionGraph.java` — rewrite `outboundInstructions`/`inboundInstructions` construction to emit
  Shape-A-paired values instead of Shape-B.

**Type rename/split (mechanical, but the compiler does the finding for you):**
- `SiegeNode.java` — add `FlowStep`, narrow `SiegeNode`'s own doc to "standalone only."
- `FlowFieldCalculator.java`, `SiegeLineTracer.java`, `SiegeProjectManager.java`,
  `SiegeProject.java`, `FlowFieldState.java`, `RegionFlowField.java` — every
  `Map<BlockPos, SiegeNode>` becomes `Map<BlockPos, FlowStep>`; every reconstruction
  (`new SiegeNode(entry.getKey(), entry.getValue().action())`) becomes a plain, type-safe read with
  no reconstruction needed (`entry.getValue()` is already the right shape once callers ask for a
  `FlowStep`'s `nextHop()`, or the genuinely-standalone position when a real `SiegeNode` is what's
  needed for a completion check).
- `TerrainEvaluator.java` — retype `isActionCompleted`/`calculateActionCost`/
  `calculateActionCostForAction` to take `SiegeNode` explicitly (narrowed meaning), so passing a
  `FlowStep` where a real position is expected becomes a compile error, not a silent bug.
- `SiegeNodeLookahead.java`, `AbstractSiegeProjectGoal.java`, `AbstractSiegeConstructionGoal.java`,
  `SmartBreachGoal.java`, `WarpSapperGoal.java`, `AwaitFormationGoal.java` — each currently reads
  `getNextSiegeNode`'s output as standalone; once `getNextSiegeNode` genuinely returns a resolved
  real-position `SiegeNode` (see below), these need no logic change, only the type update.
- `RegionFlowField.getNextSiegeNode` — becomes the one place that converts a `FlowStep` (map value)
  into the real-position `SiegeNode` its callers already assume it returns — i.e., this method
  becomes the SINGLE reconstruction point for the entire codebase, replacing the "every caller must
  remember" rule with "one method already did it correctly, by construction."
- `PathingDebugFileWriter.java`, `ClientDebugData.java`, `ClientRenderHandler.java`,
  `SyncFlowFieldDebugPayload.java`, `DetailedServerMode.java`, `MacroServerMode.java`,
  `WildernessServerMode.java`, `IServerDebugMode.java`, `DebugFlowFieldReaderItem.java` — all
  currently shape-agnostic passthroughs/chain-walkers; update their generic types
  (`Map<BlockPos, SiegeNode>` → `Map<BlockPos, FlowStep>` for the raw-map cases, unchanged for the
  `getNextSiegeNode`-sourced cases). No logic changes expected here — the survey found these are
  already internally consistent chain-walks or passthroughs.
- Every `gametest/*GameTests.java` file that hand-builds `Map<BlockPos, SiegeNode>`/
  `List<SiegeNode>` fixtures (at least 8 files, per the survey) — mechanical fixture-type updates.

## Suggested migration order (TDD-per-step, matching this session's established convention)

1. **Resolve the two runtime-unverified risks first** (above) — they inform whether step 2 is a
   pure refactor or also a bug fix.
2. **Fix `RegionGraph` to emit Shape A** (the actual behavior change). Write the failing test first:
   construct a connector project via `registerConnector`, assert its `instructions` map matches
   `SiegeLineTracer`'s own Shape-A convention at every key (predecessor-pointer, not forward-pairing).
3. **Introduce `FlowStep`, migrate one producer/consumer pair at a time**, letting the compiler
   surface every site that needs to change (this is exactly what a genuine type split buys you —
   don't try to plan the full call graph by hand when the compiler will enumerate it exhaustively).
   Suggested order: `FlowFieldCalculator` → `SiegeLineTracer` → `SiegeProjectManager` →
   `SiegeProject` → `FlowFieldState` → `RegionFlowField` (the reconstruction point) → everything
   downstream of `getNextSiegeNode` (which needs no logic changes once step's done) → debug/client
   plumbing → test fixtures last.
4. **Re-run the full GameTest suite after each producer/consumer pair**, not just at the end — this
   session's own experience is that a single silent shape mismatch reproduces as "0 stairs built,"
   not a compile error, so don't rely on "it compiled" as sufficient evidence between steps.

## What this plan deliberately does not do

- Does not implement any part of the refactor. Every section above is scope/sequencing, not a diff.
- Does not resolve the two runtime-unverified risks itself — flagged as the mandatory first step,
  not pre-answered.
- Does not decide the `SiegeNode`/`Target`/`PlannedStep` unification question — named as a separate,
  optional cleanup, deliberately left to a future explicit decision.
- Does not attempt to fix the confirmed `FollowFlowFieldGoal` climb-mechanism bug from the same
  session's staircase investigation — that bug sits downstream of `RegionFlowField.getNextSiegeNode`
  but reproduces even for a single, already-correct Shape-B connector value in this session's own
  trace, so it is not itself evidence of a shape-mismatch bug. Track it separately (see
  `docs/superpowers/plans/2026-08-04-staircase-siege-zero-stairs-investigation-plan.md`).
