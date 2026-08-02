# Region-Graph Connector Chaining Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix `RegionGraph`'s connector discovery so a region pair separated by more than `SiegeLineTracer.MAX_PROJECT_LENGTH` (32 steps) — or by a chain of synthetic mid-air `BUILD_LANDING`s — can still be bridged, by chaining multiple trace segments together in `RegionGraph.tryTrace` instead of discarding any trace that doesn't land in a known region within one call. Secondary fix: `StrandedGoal.attemptLocalBreach` currently hardcodes `dy=0`, so it can never attempt a vertical local breach even when the mob's heading target is mostly above or below it.

**Spec:** `docs/superpowers/specs/2026-07-25-region-graph-connector-chaining-design.md` (approved).

**Architecture:** `RegionGraph.tryTrace` becomes a bounded loop (`MAX_CHAIN_HOPS = 12`, ~384 blocks at 32 steps/hop). Each iteration calls `SiegeLineTracer.trace` once, from the previous iteration's landing position. If a trace aborts (`!result.completed()`), give up immediately — that's a genuine failure (out of bounds, invalid action, cost ceiling), not a mid-air landing. If a trace completes and lands in a real, already-known region, register the connector using the concatenated `orderedSteps` across all hops (this works unmodified because each hop's trace starts exactly where the previous one's `endPos()` was, so the ordered-step lists are already contiguous). If it completes but lands in mid-air (or degenerately back in the same region), treat the landing as the next anchor and keep going. No changes needed to `SiegeLineTracer` itself — it already returns everything the chaining caller needs.

**Tech Stack:** Java 21, NeoForge 1.21.1, existing `ai/pathing/region` package.

## Global Constraints

- No unit test framework exists in this repo — every task's "test" step is `./gradlew compileJava` plus a manual verification note (see spec's Testing/Validation section for the full manual check; not required to pass before committing, since there's no dev-world access from this plan's execution context).
- `MAX_CHAIN_HOPS` is a plain `private static final int` constant on `RegionGraph`, not user-configurable (no `Config` entry).
- Do not modify `SiegeLineTracer.java` — its `TraceResult` (`instructions`, `orderedSteps`, `endPos`, `totalCost`, `completed`) already carries everything the chaining loop needs.
- The raw anchor-ward `instructions` map returned by each `TraceResult` is not used anywhere in `RegionGraph` today (only `orderedSteps` feeds `outboundInstructions`/`inboundInstructions`) — do not thread a combined `instructions` map through the chaining loop; that would be dead code. Use `orderedSteps` concatenation only, and gate emptiness/success checks on that list.
- Follow existing code style in the touched files: no unit test scaffolding, `LogUtils.getLogger()` + SLF4J logging, package-private/private helpers where the class doesn't need a public API.

---

## File Structure

Modified existing files only, no new files:

| File | Change |
|---|---|
| `src/main/java/org/ratden/skavenblight/ai/pathing/region/RegionGraph.java` | `tryTrace` becomes a bounded chaining loop; `build()` tracks and logs chained-connector diagnostics. |
| `src/main/java/org/ratden/skavenblight/ai/goal/clanrat/StrandedGoal.java` | `attemptLocalBreach` derives `dy` from the heading instead of hardcoding 0. |

---

### Task 1: `RegionGraph` connector chaining + diagnostics

**Files:**
- Modify: `src/main/java/org/ratden/skavenblight/ai/pathing/region/RegionGraph.java`

**Interfaces:**
- No public method signatures change. `tryTrace`'s private signature gains a `Map<Long, Integer> hopsPerPair` parameter (internal bookkeeping only, mirrors `bestPerPair`).

**Why:** Today `tryTrace` makes exactly one `SiegeLineTracer.trace` call per (boundary cell, direction) pair and discards the result unless it lands in an already-known region (`regionIndex.regionAt(result.endPos()) == null` → silently dropped). A gap wider than `MAX_PROJECT_LENGTH` (32 steps), or one that requires crossing more than one synthetic `BUILD_LANDING`, can never be bridged. This starves the route tree of a connector between the affected regions, permanently stranding any region on the far side (e.g. a ground-level spawn region ~70 blocks below a floating-platform nexus).

- [ ] **Step 1: Add the `MAX_CHAIN_HOPS` constant**

Add alongside the existing `CARDINAL_OFFSETS` constant:

```java
private static final int MAX_CHAIN_HOPS = 12; // 12 * MAX_PROJECT_LENGTH(32) = 384 blocks, comfortably more than Minecraft's full build-height range
```

- [ ] **Step 2: Thread a `hopsPerPair` map through `build()`**

Replace the `bestPerPair` declaration and the two calls to `tryTrace` in `build()` (currently lines ~41-53) so a second map travels alongside it and is passed to every `tryTrace` call:

```java
// regionId pair -> cheapest connector found so far for that pair
Map<Long, RegionConnector> bestPerPair = new HashMap<>();
// regionId pair -> hop count the winning connector in bestPerPair took to discover
Map<Long, Integer> hopsPerPair = new HashMap<>();

for (Region region : regionIndex.getRegions()) {
    for (BlockPos boundaryCell : region.getBoundaryCells()) {
        for (int dy : new int[]{-1, 1}) {
            tryTrace(snapshot, evaluator, lineTracer, regionIndex, boundsState, region, boundaryCell, 0, dy, 0, bestPerPair, hopsPerPair);
        }
        for (int[] dir : CARDINAL_OFFSETS) {
            for (int dy : new int[]{-1, 0, 1}) {
                tryTrace(snapshot, evaluator, lineTracer, regionIndex, boundsState, region, boundaryCell, dir[0], dy, dir[1], bestPerPair, hopsPerPair);
            }
        }
    }
}
```

- [ ] **Step 3: Replace the connector-collection loop and log line to report chained-connector diagnostics**

Replace the existing loop that populates `graph.allConnectors`/`graph.connectorsByRegion` and the `LOGGER.info` call after it with:

```java
int chainedCount = 0;
int maxHops = 0;
for (Map.Entry<Long, RegionConnector> entry : bestPerPair.entrySet()) {
    RegionConnector connector = entry.getValue();
    graph.allConnectors.add(connector);
    graph.connectorsByRegion.computeIfAbsent(connector.regionA(), k -> new ArrayList<>()).add(connector);
    graph.connectorsByRegion.computeIfAbsent(connector.regionB(), k -> new ArrayList<>()).add(connector);

    int hops = hopsPerPair.getOrDefault(entry.getKey(), 1);
    if (hops > 1) chainedCount++;
    maxHops = Math.max(maxHops, hops);
}

LOGGER.info("[Skavenblight] RegionGraph built: {} regions, {} connectors ({} chained, max {} hops)",
        regionIndex.getRegions().size(), graph.allConnectors.size(), chainedCount, maxHops);
```

- [ ] **Step 4: Rewrite `tryTrace` as a bounded chaining loop**

Replace the entire existing `tryTrace` method with:

```java
private static void tryTrace(TerrainSnapshot snapshot, TerrainEvaluator evaluator, SiegeLineTracer lineTracer,
                              RegionIndex regionIndex, FlowFieldState boundsState, Region fromRegion,
                              BlockPos boundaryCell, int dx, int dy, int dz,
                              Map<Long, RegionConnector> bestPerPair, Map<Long, Integer> hopsPerPair) {

    List<SiegeNode> combinedOrderedSteps = new ArrayList<>();
    BlockPos currentAnchor = boundaryCell;
    int cost = 0;

    for (int hop = 1; hop <= MAX_CHAIN_HOPS; hop++) {
        SiegeLineTracer.TraceResult result = lineTracer.trace(snapshot, currentAnchor, dx, dy, dz, currentAnchor, cost,
                pos -> evaluator.isOutOfBounds(snapshot, pos, boundsState), pos -> Integer.MAX_VALUE);

        if (!result.completed()) return; // genuine abort (out of bounds, invalid action, cost ceiling) - give up entirely

        combinedOrderedSteps.addAll(result.orderedSteps());
        cost = result.totalCost();

        Region toRegion = regionIndex.regionAt(result.endPos());
        if (toRegion != null && toRegion.getId() != fromRegion.getId()) {
            registerConnector(fromRegion, toRegion, boundaryCell, result.endPos(), cost, combinedOrderedSteps, hop, bestPerPair, hopsPerPair);
            return;
        }

        // Landed in mid-air (or, degenerately, back inside the same region) - keep extending.
        currentAnchor = result.endPos();
    }
    // Hop cap exhausted without reaching a new region - no connector for this direction.
}

private static void registerConnector(Region fromRegion, Region toRegion, BlockPos boundaryCell, BlockPos endPos, int cost,
                                       List<SiegeNode> orderedSteps, int hops,
                                       Map<Long, RegionConnector> bestPerPair, Map<Long, Integer> hopsPerPair) {
    if (orderedSteps.isEmpty()) return;

    long pairKey = pairKey(fromRegion.getId(), toRegion.getId());
    RegionConnector existing = bestPerPair.get(pairKey);
    if (existing != null && existing.cost() <= cost) return;

    SiegeProject towardA = new SiegeProject(inboundInstructions(boundaryCell, orderedSteps), endPos, cost);
    SiegeProject towardB = new SiegeProject(outboundInstructions(boundaryCell, orderedSteps), boundaryCell, cost);
    RegionConnector connector = new RegionConnector(fromRegion.getId(), toRegion.getId(), boundaryCell, endPos, cost, towardA, towardB);
    bestPerPair.put(pairKey, connector);
    hopsPerPair.put(pairKey, hops);
}
```

Note: `boundaryCell` here is the same value the original code called `anchor` — renamed only to distinguish it from the per-hop `currentAnchor` used inside the loop. `outboundInstructions`/`inboundInstructions` are unchanged (still take `(anchor, steps)`) and need no modification — passing the concatenated `combinedOrderedSteps` works because each hop's `orderedSteps` picks up exactly where the previous hop's left off (hop *N*'s trace starts at hop *N-1*'s `result.endPos()`), so the list is already one contiguous direction-neutral walk from `boundaryCell` to the final `endPos`.

- [ ] **Step 5: Compile check**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/ai/pathing/region/RegionGraph.java
git commit -m "feat(pathing): chain region-graph connector traces across multiple hops

RegionGraph.tryTrace made exactly one SiegeLineTracer.trace call per
boundary-cell/direction pair and discarded anything that didn't land in
an already-known region, so a gap wider than MAX_PROJECT_LENGTH (32
steps) - or one crossing more than one synthetic BUILD_LANDING - could
never get a connector. Loop up to MAX_CHAIN_HOPS(12) trace segments,
treating a mid-air landing as the next anchor, until real terrain is
reached or the cap is exhausted. Also extends the build-summary log
line to report how many discovered connectors needed more than one hop."
```

---

### Task 2: `StrandedGoal` vertical local breach

**Files:**
- Modify: `src/main/java/org/ratden/skavenblight/ai/goal/clanrat/StrandedGoal.java`

**Interfaces:** None (private method body only).

**Why:** `attemptLocalBreach` derives `dx`/`dz` from the sign of the heading-vs-current delta but hardcodes `dy=0`, so a stranded mob can never attempt a vertical local breach even when its heading target is mostly above or below it. This is a secondary safety net (most mobs shouldn't need it once Task 1 lands, since a real region-graph connector will exist for the platform/ground scenarios that motivated this) — not a required fix for the primary bug.

- [ ] **Step 1: Derive `dy` from the heading and pass it through**

In `attemptLocalBreach`, replace:

```java
BlockPos current = this.mob.blockPosition();
int dx = Integer.compare(heading.getX(), current.getX());
int dz = Integer.compare(heading.getZ(), current.getZ());
if (dx == 0 && dz == 0) return;
```

with:

```java
BlockPos current = this.mob.blockPosition();
int dx = Integer.compare(heading.getX(), current.getX());
int dy = Integer.compare(heading.getY(), current.getY());
int dz = Integer.compare(heading.getZ(), current.getZ());
if (dx == 0 && dy == 0 && dz == 0) return;
```

And update the `lineTracer.trace` call a few lines below (currently passing a literal `0` for the `dy` argument) to pass `dy` instead:

```java
SiegeLineTracer.TraceResult result = lineTracer.trace(live, current, dx, dy, dz, heading, 0,
        pos -> live.isOutsideBuildHeight(pos) || !live.isLoaded(pos),
        pos -> Integer.MAX_VALUE);
```

- [ ] **Step 2: Compile check**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/ai/goal/clanrat/StrandedGoal.java
git commit -m "fix(pathing): let StrandedGoal attempt vertical local breaches

attemptLocalBreach hardcoded dy=0, so a stranded mob could only ever
try a horizontal breach direction even when its heading target was
mostly above or below it. Derive dy from the heading the same way
dx/dz already are."
```

---

## Manual Validation (not part of task completion, informational only)

Per the spec's Testing/Validation section — requires a running dev world, out of scope for this plan's automated execution:

1. Reproduce the floating-platform scenario (nexus at Y≈11, nearest ground region ~70 blocks below).
2. `/skavendebug pathing regions` / `regions_live` plus a fresh deep dump — confirm the ground region is now `reachable=true` with nonzero hop cost, and the new log line reports at least one chained connector.
3. Confirm clanrats spawned on the ground region path up onto the platform (watch a live server or check `getActiveGoalNames()`/dump output for progression through `BuildFlowFieldGoal`/`FollowFlowFieldGoal` rather than `StrandedGoal`).
4. Re-run `DebugFlowFieldReaderItem` (Detailed and Macro modes) over the chained connector's path to sanity-check the rendered route.
