# Pathing system rewrite — design draft

Scope: `org.ratden.skavenblight.ai.pathing` (currently 20 classes, 4545 lines), plus the
directly load-bearing consumers in `ai.goal.clanrat` that can't be correctness-separated from it
(`FollowFlowFieldGoal`, `AwaitFormationGoal`, `AbstractSiegeConstructionGoal`,
`AbstractSiegeProjectGoal`, `BuildFlowFieldGoal`, `SiegeNodeLookahead`, `SiegeActionAnimator`).

**Success metric, replacing the "≤10 classes" target:** the package must be comprehensible
inside a single context window. Concretely: **≤2500 lines total** across `ai.pathing`, and a
commenting rule — comment WHY only, one line, only where truly non-obvious. Bug-fight history
(what broke, what test caught it, what was tried and reverted) goes in commit messages and
`docs/superpowers/plans/`, never permanently into source comments. That history-in-comments habit
is a direct, named contributor to today's 4545 lines.

## Universal acceptance criterion

Climbing is removed. The flow field only ever tells a rat to *walk*. That collapses every part
type's real test to one sentence, which applies identically to all 16 GameTests below:

**A vanilla-navigating clanrat must traverse the completed structure using no special movement
code at all.** Headroom=4, treads instead of pillars, bridges flush with the floor — all of it
exists because vanilla pathfinding natively handles 0.5-block stair rises and 1-block step-ups
and nothing more exotic. If a completed structure needs anything beyond ordinary
`Navigation.moveTo`, the structure is wrong, not the movement code (there is no movement code
left to be wrong).

## Core algorithm: unified step search, not a fixed-stride line tracer

**Diagnosis, from this session's own bug history:** `SiegeLineTracer.trace()` commits to one
fixed `(dx,dy,dz)` stride for an entire line and hopes real terrain matches a constant slope. It
doesn't, reliably — this produced the spurious-tread bug (a diagonal stride overshooting real
ground by exactly one level) and is structurally why the climb-crossing mechanism needed a jump
hack in the first place. Real terrain needs a genuine step-by-step search, not a straight line.

**Proposal:** fold macro-project discovery into the *same* per-cell Dijkstra flood that already
does ordinary walking (`FlowFieldCalculator`). One step-generator
(`PathStepEvaluator`), one flood, five action types: `WALK`, `TUNNEL`, `BRIDGE`, `CARVED_STAIR`,
`AIR_STAIR` (ladder/spiral/pillar/leap are gone — never observed to produce a working path in
this mod's history, not carried forward). The flood's own "always expand cheapest first"
Dijkstra behavior finds the right combination of these without a separate line-tracing pass or
`determineMacroAction`/`getValidOrthogonalSteps` duplication (today's two-code-path split is
itself a confirmed bug source this session).

**Mandatory invariant — frontier-gating, not "offer every action from every cell":**
`getValidOrthogonalSteps`'s own existing comment documents an empirically-hit blowup: allowing
construction candidates from every visited cell in all 8 directions × 3 `dy` flooded the
node-budget counter with useless candidates and starved WALK propagation before it reached the
rest of the territory. The unified evaluator must reproduce today's gating, not remove it:
**construction edges (TUNNEL/BRIDGE/CARVED_STAIR/AIR_STAIR) are only generated at obstacle
frontiers** — cells where ordinary WALK expansion found no valid neighbor. This is what
`hitObstacle`/`evaluateMacroProjects` already restricts to today. Keep it as a hard invariant in
the new evaluator, not an optimization to bolt on after the fact — retrofitting it later means
rewriting the flood a second time.

**Frontier is a per-cell predicate, not a one-shot gate.** Once construction starts at a
frontier, each subsequent tread/tunnel-block/bridge-segment cell *also* has no walkable neighbor
of its own — so it is *also* a frontier, and legitimately generates the next construction edge in
the same chain. That's correct and required for multi-segment chains to form at all; don't read
"only at frontiers" as "one construction edge per obstacle," which would rebuild the single-hop
limitation `isChainedLanding` exists to patch around today. The budget protection comes from
**WALK-reachable cells never generating construction edges**, not from limiting how long a
construction chain can run once started.

**Cost ordering is per-step, not per-solution.** "Tunnel < bridge < carved-stair < air-stair"
means: for one unit of gap/elevation crossed, TUNNEL's base cost per step is cheapest, AIR_STAIR's
is priciest, in that fixed order — bake this as a tested invariant on the four base costs
themselves. It does **not** mean a 40-block tunnel must always beat a 3-block air-stair on total
path cost — Dijkstra optimizes the *solution* total, and a long cheap tunnel legitimately losing
to a short expensive stair is correct, not a bug. Test the four base-cost constants directly; do
not test "tunnel-shaped paths always win."

**PLATFORM is a first-class junction type, not a maxLength fallback.** (Correction accepted:
`BUILD_LANDING` was always meant to be the deliberate seam between different part types — treating
it as an edge-case cap was a drift from the original intent, not the intent itself.) Don't have
the flood consider PLATFORM as a candidate move at every cell — that reintroduces the branching-
factor blowup the frontier-gating rule above exists to prevent. Instead, insert it as a
post-processing pass over the discovered path: wherever two adjacent recovered steps change part
type in a way that needs a safe seam (vertical→horizontal, carved→open-air, etc.), splice in a
platform segment (fill floor, clear 4-block headroom, guarantee flush footing for whatever
follows).

## Cost model: hardness/toughness-driven mining, with a real bedrock failsafe

Today's `TerrainEvaluator.calculateActionCostForAction` returns `Integer.MAX_VALUE` for any block
with `getDestroySpeed() < 0` — bedrock is a wall, not merely expensive. That directly contradicts
this system's stated purpose. Replace it:

- Mining cost is driven by vanilla hardness by default, with an explicit extension point for a
  future Skavenblight "block toughness" attribute to override/add to it per-block later — don't
  hardcode the source to `getDestroySpeed` only, so that attribute can be added without
  re-touching the cost model.
- **Bedrock/unbreakable-by-player failsafe:** total mining work for such a block is fixed at
  25 rat-*minutes* — 1 rat takes 25 minutes, 25 rats (the existing build-speed cap) take 1 minute,
  linear in between. Express this as a `Config`-tunable work-unit constant in the same units
  `SiegeProject`'s existing "combined build speed capped at 25" mechanism already uses, so cost
  accounting and construction-time accounting are the same number, not two models that can drift
  apart.
- **Guarantee the failsafe can't be defeated by the search's own limits.** A fully bedrock-encased
  nexus makes the bedrock-breaching path the *only* route, at tens of thousands of cost units. Any
  cost-ceiling comparison, node-budget cap, or `maxLength`-style bound that can discard the *last
  remaining route to an otherwise-unreachable region* silently reintroduces "unbeatable bedrock"
  through the back door, even with the failsafe cost nominally implemented. The rule must be
  "worse than an already-connected alternative → discard," never "worse than some absolute
  threshold" — those are different rules and only the first one is safe here.
- **Persistence.** 25 real-world minutes means an in-progress bedrock-breach `SiegeProject` and
  its rat assignments must survive chunk unload and server restart. Today everything is in-memory
  only. This is a real requirement the timing number implies, not a nice-to-have — flag it now,
  design it before implementation, don't discover it three weeks in.

## The `onBlockChanged` fix is a deletion, not a new filter

The "actor distinction" (clanrat activity vs. everything else) the bug report asks for **already
exists for free**: `SiegeInteractionHandler` mutates the world via raw `level.setBlockAndUpdate`/
`destroyBlock`, which never fires the NeoForge `BlockEvent`s `SiegeBlockEventHandler` listens for.
The only leak is `SiegeProject`'s own explicit `flowField.forceRecalculation(step.pos())` call
after every placement — added specifically to work around planned terrain staying marked
"wilderness" forever. **Fix: delete that call, and make an active project's planned final state
authoritative for terrain evaluation** (both before and during construction) — the system already
knows exactly what a project will place, so nothing about executing the plan should ever need to
be "discovered." This is strictly simpler than today's system, not an added filter.

**This authority only covers the project's own planned cells.** A player breaking a half-built
staircase, or lava flowing into a project's footprint, is non-clanrat activity and **must** still
invalidate through the existing `onBlockChanged`/dirty-region path — that detection already works
correctly for real external changes; nothing here should suppress it. Get this wrong and the flow
field permanently lies about a griefed project instead of ever recovering.

Incremental (sub-region) recalculation is explicitly **deferred** — today's region-granularity
dirty tracking (`TerritoryRegionMap`'s `dirtyRegionIds`, already async/throttled/settle-delayed)
carries forward unchanged; finer-grained recalc is a follow-up once the acceptance tests pass, not
part of this rewrite.

## Proposed class list (one line each — see above for the algorithm; recreating full prose per
class here would just rebuild the comprehension problem this rewrite exists to fix)

Terrain access (~2 files): `TerrainAccess` (interface) + `TerrainSnapshot`/`LiveTerrainAccess`
(kept, both already tiny — 46 and 214 lines).

Region layer (5, was 8): `Region` (data), `RegionScanner` (flood-fill discovery), `RegionGraph`
(absorbs `RegionIndex`'s spatial lookup, `RegionConnector` as a nested record, and
`RegionRouteTree`'s parent/child structure), `RegionFlowField` (per-region runtime state + lane
crowding for formation), `TerritoryRegionMap` (top-level orchestrator, owns dirty tracking).

Planning/construction layer (6-8, was 9, plus 2 small value types replacing `SiegeNode`):
`PathStepEvaluator` (replaces `TerrainEvaluator`; the one unified step-generator), `FlowFieldCalculator`
(the flood; absorbs `SiegeLineTracer` and `FlowFieldState` as a nested record), `SiegeProjectManager`
(discovers/manages projects from the flood's own frontier results), `SiegeProject` (domain object:
boundary, ordered steps, rat assignment, build-speed accumulation, tick execution),
`SiegeInteractionHandler` (sole world-mutator, unchanged responsibility), `CalculationThrottler`
(kept, genuinely separate, reusable).

**`SiegeNode` is deliberately split into two small records, not merged into one:** its historical
single-type-three-meanings ambiguity caused two confirmed bugs this session. `FlowStep(pos,
action, predecessorPos)` for flood/routing data (carries its own position, so map-key vs.
node-owned-position can never mismatch); `PlannedStep(pos, action, facing)` for a project's own
ordered build list (no predecessor needed — order comes from list position). This is a deliberate
exception to minimizing file count: a few-line record is not a comprehension cost the way an
overloaded behavior-bearing class is.

Total: 13-15 files. Honest count, not the old "≤10" target — the new metric is line count and
single-meaning types, not file count, and this both cuts total lines hard (whole classes disappear:
`SiegeLineTracer`, `RegionIndex`, `RegionConnector`, `RegionRouteTree`, `FlowFieldState`) and
removes every known duplicate/overloaded-meaning source from this session's bug history.

Touched outside the budget (not counted, but load-bearing): `FollowFlowFieldGoal` loses
`tryClimb`/`activeClimbTarget`/`climbCyclesElapsed` entirely — once every part type is walkable by
construction, this goal is pure ordinary-navigation-following. `AwaitFormationGoal` needs real
dynamic row/column slot computation (see tests below) — never observed working; this is real,
non-trivial new scope, not a rename.

## Test matrix

Universal criterion (stated once, applies to every row): completed structure must be crossable by
a vanilla-navigating clanrat with zero special movement code.

| Part type | single rat | small group | large group | chained multi-segment |
|---|---|---|---|---|
| Air-stair (existing 4) | must pass | must pass | must pass | must pass |
| Carved-stair | new | new | new | new |
| Tunnel | new | new | new | new |
| Bridge | new | new | new | new |

16 GameTests total, mirroring `StaircaseSiegeGroupGameTests`'s existing 4-shape. The 4 existing
air-stair tests passing is the go/no-go gate before the other 12 are written — same as previously
agreed.

Additional, non-part-type tests:
- **Formation:** dynamic row/column computation for N rats assigned to a project, fit to the
  available space — proves `AwaitFormationGoal` actually works, which has never been observed.
- **Cost-ordering invariant:** unit tests pinning the four base per-step costs directly
  (tunnel < bridge < carved-stair < air-stair), not path-shape-dependent.
- **Bedrock failsafe timing:** 1 rat vs. 25 rats mining an unbreakable block converges on the
  25-rat-minute total work constant.
- **Grief recovery:** a player breaking a half-built project's block, or lava flowing into its
  footprint, still triggers real recalculation (proves the authority-for-planned-cells rule above
  doesn't swallow legitimate invalidation).

## Open questions to settle before implementation starts

1. **Cost-ceiling-never-discards-only-route — recommended mechanism:** track "no alternative
   exists" explicitly per region-pair, using what `RegionGraph`'s route tree already knows once
   built (which region pairs are still unconnected). Rejected alternative: a second flood pass
   with the ceiling relaxed once ordinary search is exhausted — every flood potentially running
   twice, with no ceiling left to prune the second pass, is exactly the large-base lag spike
   requirement 7 exists to prevent. Don't reach for it.
2. **Persistence** for long-running (bedrock-failsafe-scale) `SiegeProject`s across chunk
   unload/server restart — needs a design before implementation, not an afterthought.
3. **PLATFORM insertion rule** — confirm the exact adjacency/part-type-change condition that
   triggers splicing one in, since it's a post-process over the recovered path, not a flood-time
   candidate.

## Confirmed, not open: stair facing

`PlannedStep(pos, action, facing)` stores facing as its own field, computed once at plan time —
it is not re-derived from a predecessor at placement time (there is no predecessor on this record
by design; that's `FlowStep`'s job). `SiegeInteractionHandler` must read `facing` directly off the
`PlannedStep` it's given, never recompute it from neighboring cells at construction time.
