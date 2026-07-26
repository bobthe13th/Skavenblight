# Region-Graph Connector Chaining — Design Spec

**Status:** Approved
**Date:** 2026-07-25
**Author:** Claude (design session), approved by bobthe13th

## Problem

The region-based flow-field pathing redesign (see `2026-07-24-region-based-flow-field-design.md`) replaced the old single global per-nexus flood-fill with a region-scan → region-graph → route-tree → per-region-fields pipeline. That redesign has a real architectural gap discovered during live manual testing: bases with a large vertical or lateral separation between disconnected regions (concretely: a floating-platform nexus at Y≈11 with the nearest ground region ~70+ blocks below, at Y≈-60) never get a connector between those regions, leaving the ground region — where mobs actually spawn — permanently unreachable.

### Root cause

`RegionGraph.build()` discovers a connector between two regions via exactly one `SiegeLineTracer.trace()` call per (boundary cell, direction) pair. Two independent limitations compound:

1. **Length cap.** `SiegeLineTracer.MAX_PROJECT_LENGTH = 32` bounds a single trace to 32 steps. A gap wider than that can never be bridged by one trace call, no matter what's on the other side.
2. **Landing validation.** Even a trace that completes within 32 steps but terminates in a synthetic mid-air `BUILD_LANDING` (rather than real, previously-scanned terrain) gets discarded by `RegionGraph.tryTrace()`, because its endpoint (`result.endPos()`) isn't a member of any region `RegionIndex` already knows about (`regionIndex.regionAt(endPos) == null`).

### Why the old system didn't have this problem

The pre-redesign system's macro-project discovery (`SiegeProjectManager`/`evaluateMacroProjects`/`evaluateSingleLine`) ran *inside* one continuous Dijkstra flood-fill (`FlowFieldCalculator`'s `calcQueue`). When a trace landed on a synthetic `BUILD_LANDING`, that landing position was pushed back into the *same* work queue and processed on a later iteration of the same flood — which could trigger another macro-project trace from that position, chaining indefinitely (bounded only by the overall node budget) until real, walkable terrain was found.

The new region-graph's connector discovery is a one-shot process per region pair: one trace, pass or fail. It has no equivalent mechanism for chaining multiple trace segments together. This is a genuine loss of capability relative to the old system, not a simple oversight — the fix is to reintroduce that chaining capability inside the new architecture.

## Approach

Restructure `RegionGraph.tryTrace()`'s single trace call into a bounded loop. Each iteration traces one segment; if that segment lands in mid-air rather than a real region, treat the landing as a new anchor and trace again in the same direction, accumulating instructions across segments, until either a real region is reached (success) or a hop cap is exhausted (give up for that direction).

```java
Map<BlockPos, SiegeNode> combinedInstructions = new HashMap<>();
List<OrderedStep> combinedOrderedSteps = new ArrayList<>();
BlockPos currentAnchor = boundaryCell;
int cost = 0;

for (int hop = 0; hop < MAX_CHAIN_HOPS; hop++) {
    SiegeLineTracer.TraceResult result =
            lineTracer.trace(snapshot, currentAnchor, dx, dy, dz, currentAnchor, cost, outOfBoundsCheck);

    if (!result.completed()) {
        return; // genuinely aborted (out of bounds, invalid action, cost ceiling) - give up entirely
    }

    combinedInstructions.putAll(result.instructions());
    combinedOrderedSteps.addAll(result.orderedSteps());
    cost = result.totalCost();

    Region toRegion = regionIndex.regionAt(result.endPos());
    if (toRegion != null && toRegion.getId() != fromRegion.getId()) {
        // success - register the connector spanning all accumulated hops
        registerConnector(fromRegion, toRegion, combinedInstructions, combinedOrderedSteps,
                boundaryCell, result.endPos());
        return;
    }

    // landed in mid-air (or, degenerately, back inside the same region) - keep extending
    currentAnchor = result.endPos();
}
// hop cap exhausted without reaching a new region - no connector for this direction
```

Key points:

- **`MAX_CHAIN_HOPS = 12`.** At 32 steps/hop this covers up to 384 blocks in one direction — comfortably more than Minecraft's full build-height range (bedrock to build limit), which bounds the worst realistic vertical gap. This is a plain constant, not user-configurable.
- **Reuses `SiegeLineTracer` unchanged.** No modification needed to the tracer itself — it already returns everything a chaining caller needs (`instructions`, `orderedSteps`, `endPos`, `totalCost`, `completed`). All the new logic lives in the caller (`RegionGraph.tryTrace`).
- **Concatenation must cover both `instructions` and `orderedSteps`.** The final review's dual-orientation fix (Jul 25) added `orderedSteps` to `TraceResult` specifically so `RegionGraph` can derive both forward and reverse instruction maps from one direction-neutral list. A chained connector must concatenate `orderedSteps` across hops *in hop order* so that the existing `outboundInstructions()`/`inboundInstructions()` derivation helpers keep working unmodified on the combined list. This is the one place where correctness is subtle: hop 2's `orderedSteps` must pick up exactly where hop 1's left off, with no gap or overlap, or the derived reverse-direction map will be wrong.
- **`entryInA`/`entryInB` for a chained connector.** `entryInA` stays the original `boundaryCell` (first anchor); `entryInB` becomes the *final* segment's `result.endPos()` (the point where a real region was actually reached) — i.e., unchanged in meaning from the single-hop case, just computed after however many hops it took.
- **Cost bias target.** Each successive trace call passes `currentAnchor` as both the anchor and the cost-bias target (matching today's single-hop behavior, which passes `anchor` as its own bias target). There is no real "final destination" concept for a region-graph connector, so this is a low-stakes choice that only affects `BUILD_SPIRAL` vs `BUILD_PILLAR` tie-breaking within `SiegeLineTracer`'s existing macro-action logic — not worth a different scheme.
- **Abort vs. mid-air landing.** `TraceResult.completed()` is `false` only for genuine aborts (out of bounds, invalid action, cost ceiling exceeded) — those should stop the chain immediately, matching today's discard behavior. `completed() == true` covers *both* "reached real walkable terrain" and "hit `MAX_PROJECT_LENGTH` and deployed a synthetic landing" — the loop distinguishes between those two by checking `regionIndex.regionAt(result.endPos())` after the fact, exactly as `tryTrace` already does today; no new field is needed on `TraceResult`.

### Diagnostics

`RegionGraph.build()` already logs a summary line (region count, connector count) after each rebuild. Extend it to also report how many discovered connectors required more than one hop (e.g. `"RegionGraph built: 8 regions, 9 connectors (2 chained, max 4 hops)"`), so future manual testing can see at a glance whether a long-gap scenario is being bridged and how expensive it was, rather than this being invisible until a mob gets stranded.

### Cost tradeoff

Worst case, a single boundary-cell/direction pair that needs the full chain does up to 12× the trace work of today's single call. This only affects directions that actually need multiple hops — ordinary short connectors (the overwhelming majority) are unaffected and complete in their existing single hop. `RegionGraph.build()` already runs off the main thread as part of the async region rebuild pipeline (see `TerritoryRegionMap`), so this added worst-case cost does not risk blocking the server thread; it only extends how long a rebuild's background computation takes.

## Secondary fix: `StrandedGoal` vertical breach

`StrandedGoal.attemptLocalBreach()` currently hardcodes `dy=0` when a stranded mob attempts a local breach toward its heading target — it only ever tries a horizontal breach direction, never vertical, even when the heading target is mostly above or below the mob. With the primary fix above, most mobs should never need this fallback for the platform/bedrock scenarios that motivated this spec, since a real connector will now exist and the affected regions will become reachable at the next region-graph rebuild.

This fix is included as a smaller, lower-priority secondary task in the same plan — a safety net for genuinely sealed pockets that even chained connector discovery can't resolve — not a required fix for the primary bug. Update `attemptLocalBreach` to derive `dy` from the sign of `(heading.getY() - current.getY())`, the same way `dx`/`dz` are already derived, and pass it through to `lineTracer.trace(...)` instead of the hardcoded `0`.

## Out of scope

- WolfRat/WolfCat pathing. These mobs receive their flow field directly at spawn time (not via the region-graph network lookup) and are unaffected by this change; the debug dump's `running: n/a` for these mobs is a pre-existing, unrelated gap.
- Two previously-identified, non-blocking follow-ups from the region-based redesign's final review remain open and are explicitly not addressed by this plan:
  - Mine-type connectors don't get dirty-marked on completion, since `SmartBreachGoal`'s `level.destroyBlock` call bypasses the NeoForge `BlockEvent`s that `SiegeBlockEventHandler` listens for.
  - `BUILD_*`-type connectors can stall one step into crossing due to a `pos` vs `pos.above()` convention mismatch between flow-field keys and `RegionFlowField.getNextSiegeNode`'s post-build advancement.
- Tuning `MAX_CHAIN_HOPS` to be configurable. A fixed constant is sufficient; no evidence yet that different bases need different caps.

## Testing / validation

No automated tests exist in this repo (per `CLAUDE.md`). Validation is manual, via the same live-test loop used throughout the region-based redesign:

1. `./gradlew compileJava` after each change.
2. Reproduce the floating-platform scenario that originally surfaced this bug (nexus at Y≈11, nearest ground region ~70 blocks below) and confirm via `/skavendebug pathing regions`/`regions_live` and a fresh deep dump (`PathingDebugFileWriter.exportDeepDump`) that the ground region is now `reachable=true` with a nonzero hop cost, and that the new "N chained" log line reports at least one multi-hop connector for that base.
3. Confirm clanrats spawned on the ground region actually path up onto the platform (not just that the route tree marks it reachable) — watch a live server, or check `getActiveGoalNames()`/dump output for mobs progressing through `BuildFlowFieldGoal`/`FollowFlowFieldGoal` rather than sitting in `StrandedGoal`.
4. Re-run the existing debug visualization tooling (`DebugFlowFieldReaderItem`, both Detailed and Macro modes) over the chained connector's path to sanity-check the rendered route looks physically sane (no teleporting instruction jumps between hops).
