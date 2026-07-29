# Region-Based Flow-Field Pathing — Design

## Problem

The current siege pathing system (`ai/pathing/StandardFlowField` + `FlowFieldCalculator`) runs a single global Dijkstra flood-fill per target (usually the nexus) across a flat, geometric "territory" (a chunk-radius bubble around the base with zero walkability or connectivity awareness — see `WarpFluxNetwork.updateTerritory`). `SiegeProject`s (stairs/bridges/tunnels/spirals) are discovered reactively, mid-flood, the first time the flood hits an obstacle (`FlowFieldCalculator.hitObstacle` → `SiegeProjectManager.evaluateMacroProjects`).

This breaks down on bases with genuinely disconnected walkable areas — a nexus on a floating platform, a nexus buried near bedrock under a sprawling surface base, a base split by a chasm or moat. The flood-fill has no concept that two areas are disconnected until it happens to hit the gap between them, and any local terrain change anywhere in the territory can trigger a full-territory recalculation.

This design replaces that with a two-level system: a **region graph** (discrete walkable areas + the cheapest connectors between them) computed rarely, and **per-region local flow fields** computed often but cheaply, so recalculation scope shrinks to the region that actually changed.

## Architecture

Two levels, owned per `WarpFluxNetwork`:

1. **Region layer** (topology — changes rarely). The network's territory (still a chunk-radius bubble, unchanged) is scanned into discrete `Region`s: connectivity components over walkable cells, computed over the **full build-height-to-bedrock column** of every territory chunk (reusing `TerrainSnapshot`'s existing full-column capture and `TerrainEvaluator.isWalkableTerrain`). From each region's boundary cells (walkable cells adjacent to a non-walkable/void cell), the existing `SiegeProjectManager` line-tracing logic (`evaluateSingleLine`) is reused, unmodified in its cost math, to discover candidate connectors into neighboring regions. This produces a `RegionGraph`: regions are nodes, candidate `SiegeProject`s are edges, weighted by distance + mining/building material cost (no danger/exposure term, per scope).

2. **Route layer** (per target — changes when topology or target changes). A `RegionRouteTree` runs Dijkstra over `RegionGraph`, rooted at the target's (nexus's) region, picking the cheapest connector into every reachable region and recording each region's parent region + chosen connector. This is the "main path" — a coarse routing skeleton telling a region "which neighbor, and via which connector, gets you closer to the target" — not a block-by-block path.

3. **Local flow fields** (data volume — recalculated often, cheaply). Each region gets its own bounded `FlowFieldCalculator` pass, reusing all of today's Dijkstra/`TerrainEvaluator` machinery unchanged, scoped to just that region's cells, targeting the region's exit connector (or the literal nexus position if it's the root region).

Mobs no longer consume one nexus-wide flow field. A mob looks up its current region, fetches that region's local field + route-tree hop, and follows it exactly like today (`getNextSiegeNode`-equivalent). On crossing into a new region, it re-looks-up (no pre-fetch/handoff hinting) — matching the existing periodic-lookup pattern `ClanratEntity` already uses today, just keyed by region instead of by network.

`StandardFlowField` is retired. Its responsibilities (chunk-ticket management, dirty/claim tracking, calculation gating) are absorbed into `TerritoryRegionMap` (network-wide) plus lightweight per-region result state, rather than keeping two parallel "per-target pathing object" abstractions alive.

## Components

New package `ai/pathing/region/`:

- **`Region`** — id (scoped to its `TerritoryRegionMap`), member cells (`Map<ChunkPos, BitSet>` over local column indices — same flat/compact style as `TerrainSnapshot`, not a raw `Set<BlockPos>`), boundary cells, bounding box.
- **`RegionScanner`** — pure function `scan(TerrainSnapshot, Set<ChunkPos> bounds) -> List<Region>`. Connectivity flood-fill reusing `TerrainEvaluator.isWalkableTerrain`. Runs off-thread, same threading model as today's calculator.
- **`RegionConnector`** — candidate/active edge between two regions: both region ids, entry/exit `BlockPos`, cost, and the `SiegeProject`-shaped instructions to realize it (directly reuses `SiegeProjectManager.evaluateSingleLine`'s output).
- **`RegionGraph`** — regions + connectors for one territory snapshot, built by boundary-tracing from every region.
- **`RegionRouteTree`** — Dijkstra over `RegionGraph` rooted at a target region. Exposes parent connector + hop cost per region. One instance per live target `BlockPos`, cached the way `WarpFluxNetwork.flowFields` caches `StandardFlowField` today.
- **`TerritoryRegionMap`** — per-network owner of the `RegionGraph`, the per-region local `FlowFieldCalculator` instances (keyed by region id), and the `RegionRouteTree` cache (keyed by target). Replaces `WarpFluxNetwork.flowFields` and `StandardFlowField` entirely.
- **`RegionIndex`** — fast "which region contains this `BlockPos`" lookup (chunk → local cell → region id, same indexing style as `TerrainSnapshot`). Used by mobs (current region) and by dirty-tracking (map a changed block to its owning region(s)).
- **`StrandedGoal`** (new, `ai/goal/clanrat/`) — behavior for a mob in a region with no route-tree entry yet (unscanned or genuinely unreachable): head toward the nearest region with a known route; if blocked at the boundary, fall back to reactive, locally-scoped `SiegeProjectManager.evaluateMacroProjects` (mirroring today's `hitObstacle`-triggered path) to attempt a breakthrough.

Existing classes reused unchanged, just invoked at region scope instead of territory scope: `TerrainEvaluator`, `SiegeProjectManager`, `FlowFieldCalculator`, `TerrainSnapshot`, `CalculationThrottler`, `SiegeInteractionHandler`, `AbstractSiegeConstructionGoal` and its subclasses, `SiegeActivityLog`.

**Debug tooling** (extended, not replaced):
- `PathingDebugFileWriter` gains: a region graph summary (region count, connector count/cost, active vs candidate), the route tree (parent region + connector per region), and per-region stats (node count, last-pass budget usage) — the per-region analogue of today's per-nexus `summarizeActions` histogram. `SiegeActivityLog` entry dumps additionally show which region each entry occurred in.
- `TerritoryRegionMap` gets the same STARTED/FINISHED `LOGGER.info` lines `StandardFlowField` has today, but per-region-recalc and per-graph-rebuild/patch, so region splits/merges are visible in `latest.log`.
- `SyncFlowFieldDebugPayload` + `debug/mode/{client,server}/{Detailed,Macro,Wilderness}*Mode.java`: extended in place rather than replaced. Macro mode shows region boundaries + the route tree; Detailed mode shows one region's local field; Wilderness mode shows the out-of-territory heading (a 4th "Stranded" concept is visualized as part of Wilderness mode, not a new mode).

## Data flow

**Initial build** (network created / territory first computed): `WarpFluxNetwork.updateTerritory()` → `TerritoryRegionMap.rebuild(level)`: refresh `TerrainSnapshot` for the territory (existing capped/ramp-up logic, unchanged) → `RegionScanner.scan()` → `RegionGraph` built via boundary-tracing → `RegionIndex` built → for each live target, `RegionRouteTree` computed → for each region, its local flow field computed with target = exit connector (or nexus if root). All off-thread except the snapshot refresh, same threading model as today.

**Steady state** (per-network tick): periodic re-snapshot (existing dirty-chunk diffing) detects changed cells → `RegionIndex` maps each changed cell to its owning region(s) → those regions marked dirty. On the next gated pass (same settle-delay + cooldown as today, now per-region): re-run `RegionScanner` scoped to the dirty region's cells plus immediate neighbors (to catch merge/split). If the region set there is unchanged, just recompute that region's local flow field. If it changed, patch `RegionGraph` locally (drop stale connectors touching the old region id, re-trace boundaries for the new region set), recompute only the affected branch of `RegionRouteTree`, and recompute affected local flow fields.

**Mob query** (per-tick, per-mob): `RegionIndex.regionAt(mob.blockPosition())` → if unchanged since last tick, keep using the cached local field; if changed, fetch the new region's local field + route hop → `getNextSiegeNode`-equivalent exactly as today.

**Construction**: unchanged in spirit. A `RegionConnector`'s instructions are injected/locked/claimed exactly like today's `SiegeProject.activeProjects` — chosen up front by the route tree rather than discovered reactively by `hitObstacle`. Construction ownership stays opportunistic per assigned mob (ClanRat only today; the goal/action split already supports adding alternate construction methods later, e.g. a sapper placing explosive charges instead of mining, following the existing `WarpSapperGoal` pattern).

## Error handling / edge cases

- **Stranded mobs** (in-territory, region not yet reachable): `StrandedGoal` heads toward the nearest region with a known route (nearest-block heuristic via `RegionIndex`, same idea as today's wilderness fallback); if it stalls at the boundary, falls back to reactive, locally-scoped `evaluateMacroProjects` to punch through.
- **True wilderness** (outside territory bounds): unchanged in spirit — head toward the nearest region's boundary cell.
- **Sealed/unreachable regions** (no connector possible, e.g. bedrock-encased vault): `RegionRouteTree` has no entry; any mob there is "stranded" and will loop on construction attempts against truly unbreakable terrain — bounded by the same cost-abandonment/`MAX_CONSECUTIVE_MINE_DEPTH`-style guards that already exist.
- **Overcrowding on narrow connectors** ("rats pushing each other off mid-air staircases"): extend `tryClaimTarget`-style single-block claiming to connector-wide lane-occupancy awareness, feeding `WidenStairsGoal`'s existing widening trigger so a saturated connector proactively widens.
- **Region graph churn during active construction**: in-progress `RegionConnector` instructions stay valid (same `isCompleted`/`getRemainingInstructions` pattern as today's `SiegeProject`) even if the region graph around them changes — only the route tree needs to react to topology changes, not in-flight construction.
- **Nexus destroyed/moved**: `RegionRouteTree` cache just gets a new entry computed against the already-current `RegionGraph` — no region rescan needed, since regions don't depend on target.

## Testing / verification

No unit tests or GameTests exist in this repo (by design, per CLAUDE.md), and this logic is server-runtime-dependent, so verification is manual: `/skavendebug` commands + the debug item against deliberately awkward test bases (floating-platform nexus, bedrock-buried nexus, a base split by a chasm/moat, a sealed pocket) to confirm regions/connectors/route trees look right, plus a load test (`DebugIncursionLoadTest`) with hundreds of mobs to confirm per-region recompute scoping actually reduces recalculation cost versus today's baseline. Because there's no automated safety net, the debug-tooling extensions above (region/route-tree dumps, per-region logging) are load-bearing, not optional — they're the only way to confirm correctness during manual testing, so they ship alongside the region system itself rather than as follow-up polish.

## Out of scope (for this design)

- Danger/exposure-aware connector costing (distance + material effort only, per decision).
- Non-ClanRat construction methods (sapper explosives, etc.) — architecture supports adding them later, not implemented now.
- Event-driven (NeoForge `BlockEvent`) dirty-tracking — sticking with periodic re-snapshot diffing, matching the existing pattern.
