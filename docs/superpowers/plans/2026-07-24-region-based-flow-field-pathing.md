# Region-Based Flow-Field Pathing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the single global per-nexus flood-fill (`StandardFlowField`) with a region graph (discrete walkable areas + cheapest connectors between them) plus per-region local flow fields, so pathing stays correct on disconnected bases (floating platforms, bedrock-buried nexuses, chasm-split bases) and recalculation scope shrinks to just the region that changed.

**Architecture:** A `RegionScanner` flood-fills a network's territory into `Region`s (connectivity components, reusing the mob's own single-step movement rules so a region never contains an internal gap that would need a macro-project). Boundary cells feed a `RegionGraph` of candidate connectors (cost = distance + material effort, discovered via a `SiegeLineTracer` extracted from the existing macro-project code). A `RegionRouteTree` (Dijkstra over `RegionGraph`, rooted at the target's region) picks the cheapest connector into every reachable region. Each region gets its own bounded `FlowFieldCalculator` pass targeting its exit connector. `TerritoryRegionMap` (one per `WarpFluxNetwork`) owns all of this and replaces `StandardFlowField` entirely; mobs query it per-tick via a thin `RegionFlowField` facade that preserves `StandardFlowField`'s existing method surface so goal code barely changes.

**Tech Stack:** Java 21, NeoForge 1.21.1, existing `ai/pathing` package (`TerrainEvaluator`, `TerrainSnapshot`, `FlowFieldCalculator`, `SiegeProjectManager`, `SiegeProject`, `CalculationThrottler` — all reused, not rewritten).

## Global Constraints

- No unit test framework exists in this repo (no JUnit dependency, no GameTests registered) and this plan does not add one — see spec's Testing/Verification section. Every task's "test" step is `./gradlew compileJava` plus, where noted, a manual check via a `/skavendebug` command in a running dev world (`./gradlew runClient`).
- Full vertical column per territory chunk (build-height to bedrock) is the scan volume — no adaptive/partial-height scanning.
- Region-graph edge cost = distance + mining/building material effort only. No danger/exposure term.
- Construction stays opportunistic per assigned mob; only `ClanratEntity` builds today. Do not add other mob construction methods (sappers, etc.) — architecture must not preclude it, but it is out of scope.
- Change detection stays periodic-re-snapshot-diff based (matches existing `TerrainSnapshot`/dirty-chunk pattern). Do not add NeoForge `BlockEvent` listeners.
- Debug tooling (logging, `PathingDebugFileWriter`, the debug item/payload/modes) must be updated alongside the region system, not deferred — it is the only verification path available.
- Follow existing code style: no unit test scaffolding, `LogUtils.getLogger()` + SLF4J for logging, `record`s for immutable data, package-private helpers where the class doesn't need a public API.

---

## File Structure

New package `org.ratden.skavenblight.ai.pathing.region`:

| File | Responsibility |
|---|---|
| `Region.java` | One connectivity component: member cells (compact per-chunk `BitSet`), boundary cells, bounding box. |
| `RegionIndex.java` | Fast `BlockPos -> Region id` lookup, built from a scanned region list. |
| `RegionScanner.java` | Pure flood-fill: `TerrainSnapshot` + bounds -> `List<Region>`. |
| `RegionConnector.java` | One candidate/active edge between two regions; wraps a `SiegeProject` for construction-progress tracking. |
| `RegionGraph.java` | All regions + connectors for one territory snapshot; boundary-tracing edge discovery. |
| `RegionRouteTree.java` | Dijkstra over `RegionGraph` rooted at a target region; parent-connector-per-region. |
| `RegionFlowField.java` | Per-region query facade handed to `SiegeGoal`s; mirrors `StandardFlowField`'s existing public method surface. |
| `TerritoryRegionMap.java` | Per-network owner: scan/graph/route-tree/per-region-field lifecycle, dirty tracking, logging. |

Modified existing files (see per-task detail below): `FlowFieldState.java`, `SiegeProjectManager.java` (new `SiegeLineTracer.java` extracted alongside it), `WarpFluxNetwork.java`, `SiegeGoal.java`, `AbstractSiegeConstructionGoal.java`, `FollowFlowFieldGoal.java`, `WarpSapperGoal.java`, `SpiralSapperGoal.java`, `DeployClimbableGoal.java`, `WidenStairsGoal.java`, `ClanratEntity.java`, `PathingDebugFileWriter.java`, `TopologyExporter.java`, `DebugFlowFieldReaderItem.java`, `SyncFlowFieldDebugPayload.java`, `IServerDebugMode.java` + its 3 implementations, `DebugPathingCommands.java`, `SiegeActivityLog.java`. Deleted: `StandardFlowField.java`.

New file `ai/goal/clanrat/StrandedGoal.java`.

---

### Task 1: `FlowFieldState` region-cell bounds filter

**Files:**
- Modify: `src/main/java/org/ratden/skavenblight/ai/pathing/FlowFieldState.java`

**Interfaces:**
- Produces: `FlowFieldState(BlockPos targetPos, Set<ChunkPos> territoryChunks, Predicate<BlockPos> cellFilter)` — new constructor overload. Existing `FlowFieldState(BlockPos, Set<ChunkPos>)` unchanged (delegates with `null` filter).

**Why:** Today `FlowFieldState.isOutOfBounds(BlockPos)` only checks chunk membership. Two different regions can share a chunk (a wall splitting one chunk into two connectivity components), so a per-region `FlowFieldCalculator` pass needs a finer bounds check than "is this chunk in my territory" — otherwise its flood would leak across region boundaries within the same chunk. `isOutOfBounds(TerrainAccess, BlockPos, FlowFieldState)` in `TerrainEvaluator` already calls `state.isOutOfBounds(pos)` for every neighbor considered in `getValidOrthogonalSteps`, so adding the check here confines the flood at exactly the right granularity with no other changes needed.

- [ ] **Step 1: Add the cell-filter field and new constructor**

```java
// FlowFieldState.java - add near the top of the class, alongside targetPos/territoryChunks
private final java.util.function.Predicate<BlockPos> cellFilter;

public FlowFieldState(BlockPos targetPos, Set<ChunkPos> territoryChunks) {
    this(targetPos, territoryChunks, null);
}

public FlowFieldState(BlockPos targetPos, Set<ChunkPos> territoryChunks, java.util.function.Predicate<BlockPos> cellFilter) {
    this.targetPos = targetPos;
    this.territoryChunks = territoryChunks != null ? territoryChunks : Collections.emptySet();
    this.cellFilter = cellFilter;
}
```

Remove the old single-arg-delegating constructor body (the `this.targetPos = ...` lines) since it's now handled by the two-arg overload above; keep only the two constructors shown.

- [ ] **Step 2: Extend `isOutOfBounds` to consult the filter**

```java
// FlowFieldState.java
public boolean isOutOfBounds(BlockPos pos) {
    if (territoryChunks.isEmpty()) return false; // Global scope
    if (!territoryChunks.contains(new ChunkPos(pos))) return true;
    return cellFilter != null && !cellFilter.test(pos);
}
```

- [ ] **Step 3: Compile check**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL (no other file references the old single implicit constructor body, since the public signature `FlowFieldState(BlockPos, Set<ChunkPos>)` is preserved).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/ai/pathing/FlowFieldState.java
git commit -m "feat(pathing): add optional cell-membership filter to FlowFieldState

Chunk-level bounds checking isn't fine-grained enough once a chunk can
contain two different regions (e.g. split by a wall) - a per-region
FlowFieldCalculator pass needs to confine its flood to one region's
actual cells, not just its chunk footprint."
```

---

### Task 2: `Region` and `RegionIndex` data models

**Files:**
- Create: `src/main/java/org/ratden/skavenblight/ai/pathing/region/Region.java`
- Create: `src/main/java/org/ratden/skavenblight/ai/pathing/region/RegionIndex.java`

**Interfaces:**
- Consumes: nothing new (uses `net.minecraft.core.BlockPos`, `net.minecraft.world.level.ChunkPos` only).
- Produces:
  - `Region(int id, int minBuildHeight, int height)` constructor; `void addCell(BlockPos pos)`; `void addBoundaryCell(BlockPos pos)`; `boolean contains(BlockPos pos)`; `Set<BlockPos> getBoundaryCells()`; `int getId()`; `BlockPos getMin()`/`getMax()` (bounding box); `int cellCount()`.
  - `RegionIndex(List<Region> regions)` constructor; `Integer regionIdAt(BlockPos pos)` (null if none); `Region regionAt(BlockPos pos)`.

- [ ] **Step 1: Write `Region`**

```java
package org.ratden.skavenblight.ai.pathing.region;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * One connectivity component of walkable cells within a territory - the unit the region
 * graph routes between. Cell membership is stored as one BitSet per chunk column (same
 * flat/compact style as TerrainSnapshot) rather than a raw Set<BlockPos>, since a region
 * covering a full build-height column across many chunks can hold a very large number of
 * cells.
 */
public class Region {

    private final int id;
    private final int minBuildHeight;
    private final int height;

    private final Map<ChunkPos, BitSet> chunkCells = new HashMap<>();
    private final Set<BlockPos> boundaryCells = new HashSet<>();

    private BlockPos min;
    private BlockPos max;

    public Region(int id, int minBuildHeight, int height) {
        this.id = id;
        this.minBuildHeight = minBuildHeight;
        this.height = height;
    }

    public void addCell(BlockPos pos) {
        ChunkPos chunk = new ChunkPos(pos);
        BitSet bits = chunkCells.computeIfAbsent(chunk, c -> new BitSet(16 * 16 * height));
        bits.set(cellIndex(pos));
        expandBounds(pos);
    }

    public void addBoundaryCell(BlockPos pos) {
        boundaryCells.add(pos.immutable());
    }

    public boolean contains(BlockPos pos) {
        BitSet bits = chunkCells.get(new ChunkPos(pos));
        return bits != null && bits.get(cellIndex(pos));
    }

    public Set<BlockPos> getBoundaryCells() {
        return java.util.Collections.unmodifiableSet(boundaryCells);
    }

    public Map<ChunkPos, BitSet> getChunkCells() {
        return java.util.Collections.unmodifiableMap(chunkCells);
    }

    public int getId() {
        return id;
    }

    public BlockPos getMin() {
        return min;
    }

    public BlockPos getMax() {
        return max;
    }

    public int cellCount() {
        return chunkCells.values().stream().mapToInt(BitSet::cardinality).sum();
    }

    private int cellIndex(BlockPos pos) {
        int localX = pos.getX() & 15;
        int localZ = pos.getZ() & 15;
        int localY = pos.getY() - minBuildHeight;
        return (localX * 16 + localZ) * height + localY;
    }

    private void expandBounds(BlockPos pos) {
        if (min == null) {
            min = pos.immutable();
            max = pos.immutable();
            return;
        }
        min = new BlockPos(Math.min(min.getX(), pos.getX()), Math.min(min.getY(), pos.getY()), Math.min(min.getZ(), pos.getZ()));
        max = new BlockPos(Math.max(max.getX(), pos.getX()), Math.max(max.getY(), pos.getY()), Math.max(max.getZ(), pos.getZ()));
    }
}
```

- [ ] **Step 2: Write `RegionIndex`**

```java
package org.ratden.skavenblight.ai.pathing.region;

import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * O(1) "which region contains this position" lookup, built once after a RegionScanner pass.
 * Used both by mobs (find current region) and by dirty-tracking (map a changed block to its
 * owning region).
 */
public class RegionIndex {

    private final List<Region> regions;

    public RegionIndex(List<Region> regions) {
        this.regions = List.copyOf(regions);
    }

    public Region regionAt(BlockPos pos) {
        for (Region region : regions) {
            if (region.contains(pos)) {
                return region;
            }
        }
        return null;
    }

    public Integer regionIdAt(BlockPos pos) {
        Region region = regionAt(pos);
        return region != null ? region.getId() : null;
    }

    public List<Region> getRegions() {
        return regions;
    }
}
```

**Note for a future performance pass:** `regionAt` is a linear scan over all regions, each doing a chunk-keyed `BitSet` lookup. This is fine for the handful-to-dozens of regions a typical base produces (confirmed via the manual test bases in Task 3), but if a base with hundreds of tiny regions turns out to be slow, the fix is a `Map<ChunkPos, int[]>` (chunk -> region id per local cell, same index formula as `Region.cellIndex`) built alongside the region list — not a redesign. Not doing this now (YAGNI) since it needs the region-count data from actual manual testing to justify.

- [ ] **Step 3: Compile check**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/ai/pathing/region/Region.java src/main/java/org/ratden/skavenblight/ai/pathing/region/RegionIndex.java
git commit -m "feat(pathing): add Region and RegionIndex data models

First piece of the region-based pathing redesign - a Region is a
connectivity component of walkable cells; RegionIndex answers 'which
region contains this block' in O(regions) time."
```

---

### Task 3: `RegionScanner` + manual debug command

**Files:**
- Create: `src/main/java/org/ratden/skavenblight/ai/pathing/region/RegionScanner.java`
- Modify: `src/main/java/org/ratden/skavenblight/command/debug/DebugPathingCommands.java`

**Interfaces:**
- Consumes: `TerrainSnapshot` (existing, implements `TerrainAccess`), `TerrainEvaluator.getValidOrthogonalSteps(TerrainAccess, BlockPos, Set<BlockPos>, FlowFieldState)`, `TerrainEvaluator.isWalkableTerrain(TerrainAccess, BlockPos)`, `Region`/`RegionIndex` from Task 2, `FlowFieldState(BlockPos, Set<ChunkPos>)` from Task 1.
- Produces: `RegionScanner.scan(TerrainSnapshot snapshot, Set<ChunkPos> bounds, BlockPos boundsAnchor, int minBuildHeight, int maxBuildHeight) -> List<Region>` (static method).

**Why this shape:** A region must correspond exactly to "cells a mob can move between using its existing single-step rules" (`WALK`/single-block `MINE`/single-block `BUILD_PILLAR`/`BUILD_STAIR`) - not raw `isWalkableTerrain` adjacency - otherwise a 1-block gap that the core Dijkstra already bridges for free (via `getValidOrthogonalSteps`' `BUILD_PILLAR`/`MINE` branches) would incorrectly look like a region boundary requiring a whole macro-project connector. Reusing `getValidOrthogonalSteps` directly as the flood-fill's neighbor generator gets this for free with zero new terrain logic.

- [ ] **Step 1: Write `RegionScanner`**

```java
package org.ratden.skavenblight.ai.pathing.region;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.ratden.skavenblight.ai.pathing.FlowFieldState;
import org.ratden.skavenblight.ai.pathing.TerrainEvaluator;
import org.ratden.skavenblight.ai.pathing.TerrainSnapshot;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Flood-fills a captured TerrainSnapshot into discrete connectivity-component Regions.
 * Pure function: no mutable state, no threading concerns of its own (safe to run on the
 * same background thread FlowFieldCalculator already uses for terrain-snapshot-based work).
 */
public final class RegionScanner {

    private static final Logger LOGGER = LogUtils.getLogger();

    // Overall cell budget for one scan pass, mirroring Config.maxFlowFieldNodes' role for the
    // ordinary Dijkstra pass - without a cap, a very tall/wide territory's full build-height
    // scan has no upper bound on how long one pass can run.
    private static final int MAX_SCANNED_CELLS = 200_000;

    private final TerrainEvaluator terrainEvaluator;

    public RegionScanner(TerrainEvaluator terrainEvaluator) {
        this.terrainEvaluator = terrainEvaluator;
    }

    public List<Region> scan(TerrainSnapshot snapshot, Set<ChunkPos> bounds, BlockPos boundsAnchor,
                              int minBuildHeight, int maxBuildHeight) {
        FlowFieldState boundsState = new FlowFieldState(boundsAnchor, bounds);
        int height = maxBuildHeight - minBuildHeight;

        Set<BlockPos> visited = new HashSet<>();
        List<Region> regions = new ArrayList<>();
        int nextId = 0;
        int scannedCells = 0;

        for (ChunkPos chunk : bounds) {
            if (!snapshot.hasColumn(chunk)) continue;

            for (int y = minBuildHeight; y < maxBuildHeight; y++) {
                for (int localX = 0; localX < 16; localX++) {
                    for (int localZ = 0; localZ < 16; localZ++) {
                        BlockPos seed = new BlockPos(chunk.getMinBlockX() + localX, y, chunk.getMinBlockZ() + localZ);

                        if (visited.contains(seed) || !terrainEvaluator.isWalkableTerrain(snapshot, seed)) continue;

                        if (scannedCells >= MAX_SCANNED_CELLS) {
                            LOGGER.warn("[Skavenblight] RegionScanner hit MAX_SCANNED_CELLS ({}) - territory may be under-scanned this pass", MAX_SCANNED_CELLS);
                            return regions;
                        }

                        Region region = new Region(nextId++, minBuildHeight, height);
                        scannedCells += floodFill(snapshot, boundsState, seed, visited, region);
                        regions.add(region);
                    }
                }
            }
        }

        LOGGER.info("[Skavenblight] RegionScanner found {} regions ({} cells scanned)", regions.size(), scannedCells);
        return regions;
    }

    private int floodFill(TerrainSnapshot snapshot, FlowFieldState boundsState, BlockPos seed,
                           Set<BlockPos> visited, Region region) {
        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(seed);
        visited.add(seed);

        int cellsVisited = 0;

        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            region.addCell(current);
            cellsVisited++;

            List<TerrainEvaluator.EvaluatedStep> steps =
                    terrainEvaluator.getValidOrthogonalSteps(snapshot, current, Collections.emptySet(), boundsState);

            if (steps.size() < 4) {
                region.addBoundaryCell(current);
            }

            for (TerrainEvaluator.EvaluatedStep step : steps) {
                if (visited.add(step.pos())) {
                    queue.add(step.pos());
                }
            }
        }

        return cellsVisited;
    }
}
```

- [ ] **Step 2: Add a manual `regions` debug command**

Add a new subcommand to `DebugPathingCommands.register()`, alongside the existing `info` subcommand:

```java
// DebugPathingCommands.java - add this .then(...) block before the final closing `);` of register()
.then(Commands.literal("regions")
        .executes(context -> {
            var source = context.getSource();
            var level = source.getLevel();
            var pos = net.minecraft.core.BlockPos.containing(source.getPosition());

            org.ratden.skavenblight.network.WarpFluxGridManager gridManager =
                    org.ratden.skavenblight.network.WarpFluxGridManager.get(level);
            org.ratden.skavenblight.network.WarpFluxNetwork network = null;
            net.minecraft.world.level.ChunkPos currentChunk = new net.minecraft.world.level.ChunkPos(pos);

            for (org.ratden.skavenblight.network.WarpFluxNetwork candidate : gridManager.getAllNetworks()) {
                if (candidate.getTerritoryChunks().contains(currentChunk)) {
                    network = candidate;
                    break;
                }
            }

            if (network == null) {
                source.sendFailure(Component.literal("No network territory found at your position."));
                return 0;
            }

            org.ratden.skavenblight.ai.pathing.TerrainEvaluator evaluator = new org.ratden.skavenblight.ai.pathing.TerrainEvaluator();
            org.ratden.skavenblight.ai.pathing.TerrainSnapshot.RefreshResult result =
                    org.ratden.skavenblight.ai.pathing.TerrainSnapshot.refresh(
                            level, null, network.getTerritoryChunks(), new java.util.HashSet<>(network.getTerritoryChunks()),
                            level.getMinBuildHeight(), level.getMaxBuildHeight(), Integer.MAX_VALUE);

            org.ratden.skavenblight.ai.pathing.region.RegionScanner scanner =
                    new org.ratden.skavenblight.ai.pathing.region.RegionScanner(evaluator);
            java.util.List<org.ratden.skavenblight.ai.pathing.region.Region> regions =
                    scanner.scan(result.snapshot(), network.getTerritoryChunks(), pos, level.getMinBuildHeight(), level.getMaxBuildHeight());

            StringBuilder sb = new StringBuilder("Scanned ").append(regions.size()).append(" region(s):\n");
            for (org.ratden.skavenblight.ai.pathing.region.Region region : regions) {
                sb.append(String.format("  region %d: %d cells, %d boundary cells, bounds %s -> %s%n",
                        region.getId(), region.cellCount(), region.getBoundaryCells().size(),
                        region.getMin() != null ? region.getMin().toShortString() : "?",
                        region.getMax() != null ? region.getMax().toShortString() : "?"));
            }

            String finalOutput = sb.toString();
            source.sendSuccess(() -> Component.literal(finalOutput), false);
            return 1;
        }))
```

This is a synchronous, one-shot command (runs the scan on the calling thread, blocking) purely for manual verification during this build-out - `TerritoryRegionMap` (Task 7) is what actually runs this off-thread in normal operation.

- [ ] **Step 3: Compile check**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Manual verification in a dev world**

Run: `./gradlew runClient`, place a Warpstone Nexus and build a small test base with at least one deliberate gap (e.g. a 2-block-wide chasm) splitting it into two disconnected areas. Run `/skavendebug pathing regions` while standing in the territory.
Expected: output lists 2+ regions with plausible cell counts and bounding boxes matching the visual layout. A single unbroken room should report exactly 1 region.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/ai/pathing/region/RegionScanner.java src/main/java/org/ratden/skavenblight/command/debug/DebugPathingCommands.java
git commit -m "feat(pathing): add RegionScanner and /skavendebug pathing regions

Flood-fills a territory into connectivity-component regions, reusing
TerrainEvaluator's existing single-step movement rules as the
adjacency relation so a region never contains an internal gap that
would need a macro-project to cross."
```

---

### Task 4: Extract `SiegeLineTracer` from `SiegeProjectManager`

**Files:**
- Create: `src/main/java/org/ratden/skavenblight/ai/pathing/SiegeLineTracer.java`
- Modify: `src/main/java/org/ratden/skavenblight/ai/pathing/SiegeProjectManager.java:158-247` (`evaluateSingleLine`)

**Interfaces:**
- Produces: `SiegeLineTracer(TerrainEvaluator evaluator)`; `SiegeLineTracer.TraceResult trace(TerrainAccess terrain, BlockPos anchorPos, int dx, int dy, int dz, BlockPos costBiasTarget, int startingCost, java.util.function.Predicate<BlockPos> outOfBounds, java.util.function.Predicate<BlockPos> earlyAbort)` where `TraceResult` is `record TraceResult(java.util.Map<BlockPos, SiegeNode> instructions, BlockPos endPos, int totalCost, boolean completed)`.
- Consumed by: `SiegeProjectManager.evaluateSingleLine` (Step 2 below, behavior-preserving) and `RegionGraph` (Task 5).

**Why:** `evaluateSingleLine` today walks a line, accumulates cost/instructions via `TerrainEvaluator.determineMacroAction`/`calculateActionCostForAction`, and stops at `MAX_PROJECT_LENGTH` or on reaching walkable ground - that part is exactly what region-graph edge discovery needs too. But it also does flood-specific bookkeeping (bailing out early if `totalCost >= nextCostMap.getOrDefault(...)`, which only makes sense mid-flood) that a one-shot graph-edge trace doesn't have. Splitting the pure walk-the-line part out lets both call sites share it without either one carrying the other's assumptions.

- [ ] **Step 1: Write `SiegeLineTracer`**

```java
package org.ratden.skavenblight.ai.pathing;

import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Pure line-tracing core shared by SiegeProjectManager's reactive obstacle-bridging
 * (evaluateSingleLine) and the region graph's proactive connector discovery. Walks a
 * straight line from an anchor in one direction, accumulating cost/instructions via
 * TerrainEvaluator's existing action rules, until it reaches walkable ground, hits
 * maxLength (capped with a synthetic BUILD_LANDING), or aborts (out of bounds, invalid
 * action, or too many consecutive MINE steps).
 */
public class SiegeLineTracer {

    private static final int MAX_PROJECT_LENGTH = 32;
    private static final int COST_MULTIPLIER = 10;
    private static final int MAX_CONSECUTIVE_MINE = 5;

    private final TerrainEvaluator terrainEvaluator;

    public SiegeLineTracer(TerrainEvaluator terrainEvaluator) {
        this.terrainEvaluator = terrainEvaluator;
    }

    public record TraceResult(Map<BlockPos, SiegeNode> instructions, BlockPos endPos, int totalCost, boolean completed) {
        static TraceResult aborted() {
            return new TraceResult(Map.of(), null, Integer.MAX_VALUE, false);
        }
    }

    /**
     * @param costBiasTarget passed straight through to determineMacroAction's cost-bias logic (the
     *                        flow field's target position - unrelated to this trace's own endpoint).
     * @param outOfBounds     true if a position is outside whatever bounds this caller is tracing within.
     */
    public TraceResult trace(TerrainAccess terrain, BlockPos anchorPos, int dx, int dy, int dz,
                              BlockPos costBiasTarget, int startingCost, Predicate<BlockPos> outOfBounds) {
        int projectCost = Config.buildingBasePenalty * COST_MULTIPLIER;
        int mineChainLength = 0;
        BlockPos currentTarget = anchorPos;

        Map<BlockPos, SiegeNode> instructions = new HashMap<>();

        for (int i = 1; i <= MAX_PROJECT_LENGTH; i++) {
            BlockPos nextPos = currentTarget.offset(dx, dy, dz);

            if (outOfBounds.test(nextPos)) {
                return TraceResult.aborted();
            }

            SiegeNode.SiegeAction action = terrainEvaluator.determineMacroAction(terrain, nextPos, dy, dx, dz, costBiasTarget);
            if (action == null) {
                return TraceResult.aborted();
            }

            if (action == SiegeNode.SiegeAction.MINE) {
                mineChainLength++;
                if (mineChainLength > MAX_CONSECUTIVE_MINE) return TraceResult.aborted();
            } else {
                mineChainLength = 0;
            }

            projectCost += terrainEvaluator.calculateActionCostForAction(terrain, nextPos, action);
            int evaluatedProjectCost = (dy != 0) ? (int) ((projectCost * 2) * 0.75f) : (projectCost * 2);
            int totalCost = startingCost + evaluatedProjectCost;

            instructions.put(nextPos, new SiegeNode(currentTarget, action));

            if (terrainEvaluator.isWalkableTerrain(terrain, nextPos)) {
                return new TraceResult(instructions, nextPos, totalCost, true);
            }

            if (i == MAX_PROJECT_LENGTH) {
                Map<BlockPos, SiegeNode> withLanding = new HashMap<>(instructions);
                withLanding.put(nextPos, new SiegeNode(currentTarget, SiegeNode.SiegeAction.BUILD_LANDING));
                return new TraceResult(withLanding, nextPos, totalCost, true);
            }

            currentTarget = nextPos;
        }

        return TraceResult.aborted();
    }
}
```

- [ ] **Step 2: Rewrite `SiegeProjectManager.evaluateSingleLine` as a thin wrapper**

Replace the entire body of `evaluateSingleLine` (`SiegeProjectManager.java:158-247`) with:

```java
private void evaluateSingleLine(TerrainAccess terrain, BlockPos anchorPos, FlowFieldState state, int anchorCost,
                                int dx, int dy, int dz,
                                PriorityQueue<FlowFieldCalculator.QueueNode> calcQueue,
                                Map<BlockPos, Integer> nextCostMap,
                                Map<BlockPos, SiegeNode> nextInstructionMap) {

    SiegeLineTracer.TraceResult result = lineTracer.trace(terrain, anchorPos, dx, dy, dz, state.getTargetPos(), anchorCost,
            pos -> terrainEvaluator.isOutOfBounds(terrain, pos, state) || isNearExistingProject(pos, anchorPos));

    if (!result.completed() || result.instructions().isEmpty()) return;

    BlockPos endPos = result.endPos();
    int totalCost = result.totalCost();

    if (totalCost >= nextCostMap.getOrDefault(endPos, Integer.MAX_VALUE)) return;

    LOGGER.debug("[Pathfinder] Successful Macro Line built from {} to {} (Cost: {})",
            anchorPos.toShortString(), endPos.toShortString(), totalCost);

    candidateProjects.add(new SiegeProject(result.instructions(), endPos, totalCost));
    lastPassCandidatesGenerated++;

    nextCostMap.put(endPos, totalCost);
    nextInstructionMap.putAll(result.instructions());
    calcQueue.add(new FlowFieldCalculator.QueueNode(endPos, totalCost));
}
```

Note: `isNearExistingProject`'s original call site (`if (isNearExistingProject(nextPos, anchorPos)) return;` inside the per-step loop) is now folded into the `outOfBounds` predicate passed to `trace`, since `SiegeLineTracer` treats "should this trace stop here" uniformly via that predicate - preserves the exact same early-abort behavior (a trace stops as soon as it nears an existing project, same as before).

Also remove the now-dead `MAX_PROJECT_LENGTH`/`COST_MULTIPLIER` constants from `SiegeProjectManager` (moved to `SiegeLineTracer`) if nothing else in the file still references them - check with `grep -n "MAX_PROJECT_LENGTH\|COST_MULTIPLIER" src/main/java/org/ratden/skavenblight/ai/pathing/SiegeProjectManager.java` before removing.

Add the new field and constructor wiring:

```java
// SiegeProjectManager.java - add alongside the existing terrainEvaluator field
private final SiegeLineTracer lineTracer;

public SiegeProjectManager(TerrainEvaluator terrainEvaluator) {
    this.terrainEvaluator = terrainEvaluator;
    this.lineTracer = new SiegeLineTracer(terrainEvaluator);
}
```

- [ ] **Step 3: Compile check**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Manual regression check**

Run: `./gradlew runClient`, trigger a scenario or use `/skavendebug incursion` load test against a base with at least one obstacle (a wall/gap) forcing a macro-project. Confirm (via `/skavendebug pathing info` and watching rats path) that stairs/bridges/tunnels still get built across the obstacle exactly as before this refactor - this is a pure extraction, not a behavior change, so any difference here is a bug in the extraction.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/ai/pathing/SiegeLineTracer.java src/main/java/org/ratden/skavenblight/ai/pathing/SiegeProjectManager.java
git commit -m "refactor(pathing): extract SiegeLineTracer from SiegeProjectManager

Splits the pure line-walking/cost-accumulation core out of
evaluateSingleLine so the region graph's connector discovery (next
task) can reuse the exact same cost math and action rules instead of
reimplementing them, without inheriting the flood-specific
early-abort bookkeeping that doesn't apply to a one-shot graph-edge
trace."
```

---

### Task 5: `RegionConnector` + `RegionGraph` (boundary-tracing edge discovery)

**Files:**
- Create: `src/main/java/org/ratden/skavenblight/ai/pathing/region/RegionConnector.java`
- Create: `src/main/java/org/ratden/skavenblight/ai/pathing/region/RegionGraph.java`
- Modify: `src/main/java/org/ratden/skavenblight/command/debug/DebugPathingCommands.java` (extend `regions` command)

**Interfaces:**
- Consumes: `SiegeLineTracer` (Task 4), `Region`/`RegionIndex` (Task 2), existing `SiegeProject`.
- Produces: `RegionConnector(int regionA, int regionB, BlockPos entryInA, BlockPos entryInB, int cost, SiegeProject project)`; `RegionGraph.build(TerrainSnapshot, RegionIndex, Set<ChunkPos> bounds, BlockPos boundsAnchor, TerrainEvaluator, SiegeLineTracer) -> RegionGraph`; `RegionGraph.getConnectorsFor(int regionId) -> List<RegionConnector>`; `RegionGraph.getAllConnectors() -> List<RegionConnector>`.

**Why the 14-direction sweep matches `evaluateMacroProjects`:** boundary cells are traced in the same 14 directions (`{0,-1,0}`, `{0,1,0}`, and the 4 cardinal directions × `dy ∈ {-1,0,1}`) that `SiegeProjectManager.evaluateMacroProjects` already uses, so connector discovery finds exactly the same kinds of gaps (vertical shafts, diagonal drops, horizontal walls) the old reactive system did - just once, per boundary cell, instead of reactively per obstacle hit.

- [ ] **Step 1: Write `RegionConnector`**

```java
package org.ratden.skavenblight.ai.pathing.region;

import net.minecraft.core.BlockPos;
import org.ratden.skavenblight.ai.pathing.SiegeProject;
import org.ratden.skavenblight.ai.pathing.TerrainAccess;
import org.ratden.skavenblight.ai.pathing.TerrainEvaluator;

/**
 * One edge in the region graph: a candidate or active SiegeProject connecting two regions.
 * Wraps a plain SiegeProject for construction-progress tracking (isCompleted/getRemainingInstructions)
 * rather than reimplementing it - a connector IS a SiegeProject, just one chosen deliberately by
 * the route tree instead of discovered reactively mid-flood.
 */
public record RegionConnector(int regionA, int regionB, BlockPos entryInA, BlockPos entryInB, int cost, SiegeProject project) {

    public int other(int regionId) {
        if (regionId == regionA) return regionB;
        if (regionId == regionB) return regionA;
        throw new IllegalArgumentException("Region " + regionId + " is not part of this connector");
    }

    public BlockPos entryFor(int regionId) {
        if (regionId == regionA) return entryInA;
        if (regionId == regionB) return entryInB;
        throw new IllegalArgumentException("Region " + regionId + " is not part of this connector");
    }

    public boolean isCompleted(TerrainAccess terrain, TerrainEvaluator evaluator) {
        return project.isCompleted(terrain, evaluator);
    }
}
```

- [ ] **Step 2: Write `RegionGraph`**

```java
package org.ratden.skavenblight.ai.pathing.region;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.ratden.skavenblight.ai.pathing.FlowFieldState;
import org.ratden.skavenblight.ai.pathing.SiegeLineTracer;
import org.ratden.skavenblight.ai.pathing.SiegeProject;
import org.ratden.skavenblight.ai.pathing.TerrainEvaluator;
import org.ratden.skavenblight.ai.pathing.TerrainSnapshot;
import org.slf4j.Logger;

import java.util.*;

/**
 * All regions + the connectors between them for one territory snapshot. Edges are discovered
 * by tracing from every region's boundary cells in the same 14 directions
 * SiegeProjectManager.evaluateMacroProjects already uses, keeping only the cheapest connector
 * found per region pair.
 */
public class RegionGraph {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int[][] CARDINAL_OFFSETS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private final RegionIndex regionIndex;
    private final Map<Integer, List<RegionConnector>> connectorsByRegion = new HashMap<>();
    private final List<RegionConnector> allConnectors = new ArrayList<>();

    private RegionGraph(RegionIndex regionIndex) {
        this.regionIndex = regionIndex;
    }

    public static RegionGraph build(TerrainSnapshot snapshot, RegionIndex regionIndex, Set<ChunkPos> bounds,
                                     BlockPos boundsAnchor, TerrainEvaluator evaluator, SiegeLineTracer lineTracer) {
        RegionGraph graph = new RegionGraph(regionIndex);
        FlowFieldState boundsState = new FlowFieldState(boundsAnchor, bounds);

        // regionId pair -> cheapest connector found so far for that pair
        Map<Long, RegionConnector> bestPerPair = new HashMap<>();

        for (Region region : regionIndex.getRegions()) {
            for (BlockPos boundaryCell : region.getBoundaryCells()) {
                for (int dy : new int[]{-1, 1}) {
                    tryTrace(snapshot, evaluator, lineTracer, regionIndex, boundsState, region, boundaryCell, 0, dy, 0, bestPerPair);
                }
                for (int[] dir : CARDINAL_OFFSETS) {
                    for (int dy : new int[]{-1, 0, 1}) {
                        tryTrace(snapshot, evaluator, lineTracer, regionIndex, boundsState, region, boundaryCell, dir[0], dy, dir[1], bestPerPair);
                    }
                }
            }
        }

        for (RegionConnector connector : bestPerPair.values()) {
            graph.allConnectors.add(connector);
            graph.connectorsByRegion.computeIfAbsent(connector.regionA(), k -> new ArrayList<>()).add(connector);
            graph.connectorsByRegion.computeIfAbsent(connector.regionB(), k -> new ArrayList<>()).add(connector);
        }

        LOGGER.info("[Skavenblight] RegionGraph built: {} regions, {} connectors", regionIndex.getRegions().size(), graph.allConnectors.size());
        return graph;
    }

    private static void tryTrace(TerrainSnapshot snapshot, TerrainEvaluator evaluator, SiegeLineTracer lineTracer,
                                  RegionIndex regionIndex, FlowFieldState boundsState, Region fromRegion,
                                  BlockPos anchor, int dx, int dy, int dz, Map<Long, RegionConnector> bestPerPair) {

        SiegeLineTracer.TraceResult result = lineTracer.trace(snapshot, anchor, dx, dy, dz, anchor, 0,
                pos -> evaluator.isOutOfBounds(snapshot, pos, boundsState));

        if (!result.completed() || result.instructions().isEmpty()) return;

        Region toRegion = regionIndex.regionAt(result.endPos());
        if (toRegion == null || toRegion.getId() == fromRegion.getId()) return;

        long pairKey = pairKey(fromRegion.getId(), toRegion.getId());
        RegionConnector existing = bestPerPair.get(pairKey);
        if (existing != null && existing.cost() <= result.totalCost()) return;

        SiegeProject project = new SiegeProject(result.instructions(), result.endPos(), result.totalCost());
        RegionConnector connector = new RegionConnector(fromRegion.getId(), toRegion.getId(), anchor, result.endPos(), result.totalCost(), project);
        bestPerPair.put(pairKey, connector);
    }

    private static long pairKey(int a, int b) {
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        return (((long) lo) << 32) | (hi & 0xFFFFFFFFL);
    }

    public List<RegionConnector> getConnectorsFor(int regionId) {
        return connectorsByRegion.getOrDefault(regionId, List.of());
    }

    public List<RegionConnector> getAllConnectors() {
        return List.copyOf(allConnectors);
    }

    public RegionIndex getRegionIndex() {
        return regionIndex;
    }
}
```

- [ ] **Step 3: Extend the `regions` debug command to print connectors**

Add to the end of the command body written in Task 3, after the per-region loop:

```java
org.ratden.skavenblight.ai.pathing.SiegeLineTracer lineTracer = new org.ratden.skavenblight.ai.pathing.SiegeLineTracer(evaluator);
org.ratden.skavenblight.ai.pathing.region.RegionIndex regionIndex = new org.ratden.skavenblight.ai.pathing.region.RegionIndex(regions);
org.ratden.skavenblight.ai.pathing.region.RegionGraph graph = org.ratden.skavenblight.ai.pathing.region.RegionGraph.build(
        result.snapshot(), regionIndex, network.getTerritoryChunks(), pos, evaluator, lineTracer);

sb.append("Connectors (").append(graph.getAllConnectors().size()).append("):\n");
for (org.ratden.skavenblight.ai.pathing.region.RegionConnector connector : graph.getAllConnectors()) {
    sb.append(String.format("  region %d <-> region %d, cost %d, entry %s -> %s%n",
            connector.regionA(), connector.regionB(), connector.cost(),
            connector.entryInA().toShortString(), connector.entryInB().toShortString()));
}
```

(Replace the earlier `String finalOutput = sb.toString();`/`source.sendSuccess(...)` pair so it runs after this block instead of right after the region loop.)

- [ ] **Step 4: Compile check**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Manual verification**

In the same dev-world test base from Task 3 (two regions split by a chasm), run `/skavendebug pathing regions` again.
Expected: exactly one connector reported between the two regions, with a cost roughly proportional to the chasm's width, and entry points on each region's side of the gap matching the visible terrain.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/ai/pathing/region/RegionConnector.java src/main/java/org/ratden/skavenblight/ai/pathing/region/RegionGraph.java src/main/java/org/ratden/skavenblight/command/debug/DebugPathingCommands.java
git commit -m "feat(pathing): add RegionGraph boundary-tracing connector discovery

Traces from every region's boundary cells in the same 14 directions
evaluateMacroProjects already uses, keeping only the cheapest
connector per region pair - this is the 'compare candidates and pick
cheapest' logic the reactive obstacle-bridging system never had."
```

---

### Task 6: `RegionRouteTree` (Dijkstra over the region graph)

**Files:**
- Create: `src/main/java/org/ratden/skavenblight/ai/pathing/region/RegionRouteTree.java`
- Modify: `src/main/java/org/ratden/skavenblight/command/debug/DebugPathingCommands.java` (extend `regions` command)

**Interfaces:**
- Consumes: `RegionGraph` (Task 5).
- Produces: `RegionRouteTree.compute(RegionGraph graph, int rootRegionId) -> RegionRouteTree`; `boolean isReachable(int regionId)`; `RegionConnector getParentConnector(int regionId)` (null for root or unreachable); `Integer getParentRegion(int regionId)`; `int getHopCost(int regionId)`.

- [ ] **Step 1: Write `RegionRouteTree`**

```java
package org.ratden.skavenblight.ai.pathing.region;

import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Dijkstra over a RegionGraph, rooted at the region containing the current target (usually the
 * nexus). For every reachable region, records which connector to use and which neighboring
 * region it leads toward - the "main path" routing skeleton. A connector is treated as an
 * undirected edge: once built it's traversable both ways, even though it was discovered by
 * tracing outward from one specific region's boundary.
 */
public class RegionRouteTree {

    private final int rootRegionId;
    private final Map<Integer, RegionConnector> parentConnector = new HashMap<>();
    private final Map<Integer, Integer> parentRegion = new HashMap<>();
    private final Map<Integer, Integer> hopCost = new HashMap<>();

    private RegionRouteTree(int rootRegionId) {
        this.rootRegionId = rootRegionId;
    }

    public static RegionRouteTree compute(RegionGraph graph, int rootRegionId) {
        RegionRouteTree tree = new RegionRouteTree(rootRegionId);
        tree.hopCost.put(rootRegionId, 0);

        PriorityQueue<int[]> queue = new PriorityQueue<>((a, b) -> Integer.compare(a[1], b[1])); // [regionId, cost]
        queue.add(new int[]{rootRegionId, 0});

        while (!queue.isEmpty()) {
            int[] entry = queue.poll();
            int regionId = entry[0];
            int cost = entry[1];

            if (cost > tree.hopCost.getOrDefault(regionId, Integer.MAX_VALUE)) continue;

            for (RegionConnector connector : graph.getConnectorsFor(regionId)) {
                int neighbor = connector.other(regionId);
                int candidateCost = cost + connector.cost();

                if (candidateCost < tree.hopCost.getOrDefault(neighbor, Integer.MAX_VALUE)) {
                    tree.hopCost.put(neighbor, candidateCost);
                    tree.parentRegion.put(neighbor, regionId);
                    tree.parentConnector.put(neighbor, connector);
                    queue.add(new int[]{neighbor, candidateCost});
                }
            }
        }

        return tree;
    }

    public boolean isReachable(int regionId) {
        return regionId == rootRegionId || hopCost.containsKey(regionId);
    }

    public RegionConnector getParentConnector(int regionId) {
        return parentConnector.get(regionId);
    }

    public Integer getParentRegion(int regionId) {
        return parentRegion.get(regionId);
    }

    public int getHopCost(int regionId) {
        return hopCost.getOrDefault(regionId, Integer.MAX_VALUE);
    }

    public int getRootRegionId() {
        return rootRegionId;
    }
}
```

- [ ] **Step 2: Extend the `regions` debug command to print the route tree**

Add after the connector-printing block from Task 5 (using the first region containing the command-runner's position as a stand-in root, since a real nexus lookup comes in Task 7):

```java
org.ratden.skavenblight.ai.pathing.region.Region rootRegion = regionIndex.regionAt(pos);
if (rootRegion != null) {
    org.ratden.skavenblight.ai.pathing.region.RegionRouteTree routeTree =
            org.ratden.skavenblight.ai.pathing.region.RegionRouteTree.compute(graph, rootRegion.getId());

    sb.append("Route tree (rooted at region ").append(rootRegion.getId()).append("):\n");
    for (org.ratden.skavenblight.ai.pathing.region.Region region : regions) {
        if (!routeTree.isReachable(region.getId())) {
            sb.append(String.format("  region %d: UNREACHABLE%n", region.getId()));
            continue;
        }
        Integer parent = routeTree.getParentRegion(region.getId());
        sb.append(String.format("  region %d: parent=%s cost=%d%n",
                region.getId(), parent != null ? parent.toString() : "<root>", routeTree.getHopCost(region.getId())));
    }
}
```

- [ ] **Step 3: Compile check**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Manual verification**

Same test base. Run `/skavendebug pathing regions` while standing in the region you'll treat as root.
Expected: the region you're standing in reports `<root>`, the other region(s) report the correct parent and a hop cost matching the connector cost printed earlier. Add a third disconnected pocket with no possible connector (fully sealed) and confirm it reports `UNREACHABLE`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/ai/pathing/region/RegionRouteTree.java src/main/java/org/ratden/skavenblight/command/debug/DebugPathingCommands.java
git commit -m "feat(pathing): add RegionRouteTree Dijkstra over the region graph

Computes, for every region reachable from a root, which connector and
neighboring region gets a mob closer to the target - the coarse
routing skeleton ('main path') region-scoped local flow fields target."
```

---

### Task 7: `TerritoryRegionMap` core + `WarpFluxNetwork` wiring

**Files:**
- Create: `src/main/java/org/ratden/skavenblight/ai/pathing/region/TerritoryRegionMap.java`
- Modify: `src/main/java/org/ratden/skavenblight/network/WarpFluxNetwork.java`

**Interfaces:**
- Consumes: everything from Tasks 1-6, plus existing `TerrainSnapshot`, `FlowFieldCalculator`, `CalculationThrottler`, `SiegeProjectManager`.
- Produces:
  - `TerritoryRegionMap(ServerLevel level)` constructor.
  - `void rebuild(ServerLevel level, Set<ChunkPos> territoryChunks, BlockPos nexusPos)` - full scan + graph + route-tree + per-region field build.
  - `void tick(ServerLevel level)` - steady-state gating (settle delay + cooldown), called every network tick.
  - `RegionFlowField getRegionFlowFieldFor(BlockPos pos)` - the per-mob query entry point (returns `null` if `pos` isn't in any known region).
  - `BlockPos getWildernessHeadingTarget(BlockPos pos)` - true-wilderness fallback (nearest region's nearest cell).

**Design note on reuse:** each region gets its own `FlowFieldState` (target = its parent connector's entry point in this region, or the literal nexus if it's the root region; territoryChunks = the whole network's chunks so chunk-level ticket/snapshot bounds stay shared; cellFilter = `region::contains` from Task 1's addition) - `FlowFieldCalculator.calculateFully(TerrainAccess, FlowFieldState)` runs completely unmodified per region. Region recalculations are processed **one at a time**, reusing a single shared `TerrainEvaluator` (stateless) + `SiegeProjectManager` + `FlowFieldCalculator` instance per network, sequenced through a work queue - this avoids any concurrent-mutation risk from `FlowFieldCalculator`'s instance fields (`nextCostMap` etc.) without needing per-region calculator instances.

- [ ] **Step 1: Write `TerritoryRegionMap`**

```java
package org.ratden.skavenblight.ai.pathing.region;

import com.mojang.logging.LogUtils;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.ratden.skavenblight.Config;
import org.ratden.skavenblight.ai.pathing.*;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-network owner of the region graph, route tree, and per-region local flow fields.
 * Replaces StandardFlowField entirely: chunk-ticket management, dirty tracking, and
 * calculation gating all live here now, scoped per-region instead of per-nexus-wide-territory.
 */
public class TerritoryRegionMap {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final TerrainEvaluator terrainEvaluator = new TerrainEvaluator();
    private final SiegeLineTracer lineTracer = new SiegeLineTracer(terrainEvaluator);
    private final RegionScanner regionScanner = new RegionScanner(terrainEvaluator);
    private final SiegeProjectManager projectManager = new SiegeProjectManager(terrainEvaluator);
    private final CalculationThrottler throttler = new CalculationThrottler();
    private final FlowFieldCalculator calculator = new FlowFieldCalculator(terrainEvaluator, projectManager, throttler);

    private volatile TerrainSnapshot terrainSnapshot = null;
    private final Set<ChunkPos> dirtySnapshotChunks = new HashSet<>();
    private final Set<ChunkPos> forcedChunks = new HashSet<>();

    private volatile RegionIndex regionIndex = new RegionIndex(List.of());
    private volatile RegionGraph regionGraph = null;
    private volatile RegionRouteTree routeTree = null;
    private final Map<Integer, FlowFieldState> regionStates = new HashMap<>();

    private final AtomicBoolean isCalculatingAsync = new AtomicBoolean(false);
    private long lastCalculationStart = 0;
    private long lastBlockChangeTime = 0;
    private final Set<Integer> dirtyRegionIds = new HashSet<>();
    // Queued so onBlockChanged (any thread reachable from block-update handling today, though in
    // practice only ever called from the main thread) never blocks on the calculation lock.
    private final ConcurrentLinkedQueue<BlockPos> pendingBlockChanges = new ConcurrentLinkedQueue<>();

    public CalculationThrottler getThrottler() {
        return this.throttler;
    }

    public void syncTerritoryChunkTickets(ServerLevel level, Set<ChunkPos> newTerritory) {
        Iterator<ChunkPos> iterator = forcedChunks.iterator();
        while (iterator.hasNext()) {
            ChunkPos cp = iterator.next();
            if (!newTerritory.contains(cp)) {
                level.setChunkForced(cp.x, cp.z, false);
                iterator.remove();
            }
        }
        for (ChunkPos cp : newTerritory) {
            if (forcedChunks.add(cp)) {
                level.setChunkForced(cp.x, cp.z, true);
            }
        }
    }

    public void cleanup(ServerLevel level) {
        for (ChunkPos cp : forcedChunks) {
            level.setChunkForced(cp.x, cp.z, false);
        }
        forcedChunks.clear();
    }

    public void onBlockChanged(BlockPos pos) {
        pendingBlockChanges.add(pos.immutable());
    }

    /** Full rebuild: snapshot -> regions -> graph -> route tree -> per-region fields. Call once at network creation/territory change, then rely on tick() for steady state. */
    public void rebuild(ServerLevel level, Set<ChunkPos> territoryChunks, BlockPos nexusPos) {
        if (isCalculatingAsync.get()) return;
        if (!isCalculatingAsync.compareAndSet(false, true)) return;

        syncTerritoryChunkTickets(level, territoryChunks);
        dirtySnapshotChunks.addAll(territoryChunks);
        TerrainSnapshot.RefreshResult result = TerrainSnapshot.refresh(
                level, terrainSnapshot, territoryChunks, dirtySnapshotChunks,
                level.getMinBuildHeight(), level.getMaxBuildHeight(), Integer.MAX_VALUE);
        dirtySnapshotChunks.removeAll(result.capturedChunks());
        terrainSnapshot = result.snapshot();

        LOGGER.info("[Skavenblight] TerritoryRegionMap rebuild STARTED for nexus {}", nexusPos.toShortString());

        CompletableFuture.runAsync(() -> {
            try {
                rebuildRegionsAndGraph(territoryChunks, nexusPos);
            } catch (Exception e) {
                LOGGER.error("[Skavenblight] TerritoryRegionMap rebuild crashed!", e);
            } finally {
                isCalculatingAsync.set(false);
            }
        }, Util.backgroundExecutor()).thenAcceptAsync(v ->
                LOGGER.info("[Skavenblight] TerritoryRegionMap rebuild FINISHED: {} regions, {} connectors",
                        regionIndex.getRegions().size(), regionGraph != null ? regionGraph.getAllConnectors().size() : 0),
                level.getServer());
    }

    private void rebuildRegionsAndGraph(Set<ChunkPos> territoryChunks, BlockPos nexusPos) {
        TerrainSnapshot snapshot = this.terrainSnapshot;
        List<Region> regions = regionScanner.scan(snapshot, territoryChunks, nexusPos,
                snapshot.getMinBuildHeight(), snapshot.getMaxBuildHeight());
        RegionIndex newIndex = new RegionIndex(regions);
        RegionGraph newGraph = RegionGraph.build(snapshot, newIndex, territoryChunks, nexusPos, terrainEvaluator, lineTracer);

        Region rootRegion = newIndex.regionAt(nexusPos);
        RegionRouteTree newRouteTree = rootRegion != null ? RegionRouteTree.compute(newGraph, rootRegion.getId()) : null;

        Map<Integer, FlowFieldState> newStates = new HashMap<>();
        for (Region region : regions) {
            BlockPos target = region.getId() == (rootRegion != null ? rootRegion.getId() : -1)
                    ? nexusPos
                    : (newRouteTree != null && newRouteTree.getParentConnector(region.getId()) != null
                            ? newRouteTree.getParentConnector(region.getId()).entryFor(region.getId())
                            : null);

            if (target == null) continue; // unreachable region - no local field until a connector exists

            FlowFieldState state = new FlowFieldState(target, territoryChunks, region::contains);
            calculator.calculateFully(snapshot, state);
            newStates.put(region.getId(), state);
        }

        this.regionIndex = newIndex;
        this.regionGraph = newGraph;
        this.routeTree = newRouteTree;
        this.regionStates.clear();
        this.regionStates.putAll(newStates);
    }

    public boolean isCalculating() {
        return isCalculatingAsync.get();
    }

    public RegionFlowField getRegionFlowFieldFor(BlockPos pos) {
        Region region = regionIndex.regionAt(pos);
        if (region == null) return null;
        FlowFieldState state = regionStates.get(region.getId());
        if (state == null) return null;
        return new RegionFlowField(this, region.getId(), state, projectManager, calculator, throttler);
    }

    public BlockPos getWildernessHeadingTarget(BlockPos pos) {
        List<Region> regions = regionIndex.getRegions();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (Region region : regions) {
            if (region.getMin() == null) continue;
            BlockPos candidate = region.getMin(); // cheap stand-in for "somewhere in this region"
            double dist = candidate.distSqr(pos);
            if (dist < bestDist) {
                bestDist = dist;
                best = candidate;
            }
        }
        return best;
    }

    public RegionIndex getRegionIndex() {
        return regionIndex;
    }

    public RegionRouteTree getRouteTree() {
        return routeTree;
    }

    public RegionGraph getRegionGraph() {
        return regionGraph;
    }
}
```

**Note:** `tick(ServerLevel)` (steady-state settle-delay/cooldown gating + dirty-region rescan scheduling) and the finer `getWildernessHeadingTarget` (currently a cheap `region.getMin()` stand-in) are completed in Task 8, once dirty-region tracking exists to drive them. This task only wires up the initial full-rebuild path so `WarpFluxNetwork` has something real to call.

- [ ] **Step 2: Wire `TerritoryRegionMap` into `WarpFluxNetwork`**

Replace the `flowFields`/`getSharedFlowField`/`clearFlowFields` block (`WarpFluxNetwork.java:251-272`):

```java
// WarpFluxNetwork.java - replace the flowFields cache and its accessors with:
private final org.ratden.skavenblight.ai.pathing.region.TerritoryRegionMap regionMap =
        new org.ratden.skavenblight.ai.pathing.region.TerritoryRegionMap();

public org.ratden.skavenblight.ai.pathing.region.TerritoryRegionMap getRegionMap() {
    return this.regionMap;
}

/**
 * Call this whenever your base territory expands or shrinks, or a new nexus becomes active,
 * so the region graph gets rebuilt against the current layout.
 */
public void rebuildRegionMap(ServerLevel level, BlockPos nexusPos) {
    this.regionMap.rebuild(level, this.getTerritoryChunks(), nexusPos);
}
```

Update `tick(ServerLevel level)` (`WarpFluxNetwork.java:76-80`) to drive the region map instead of the old per-nexus field loop:

```java
// This ticks the region map tied to this network.
this.regionMap.tick(level);
```

(`tick` is added to `TerritoryRegionMap` as a no-op stub in this task - `public void tick(ServerLevel level) { /* steady-state recompute wiring lands in Task 8 */ }` - so this compiles now and gets real behavior next task.)

- [ ] **Step 3: Compile check**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL. If anything outside `WarpFluxNetwork` still calls `getSharedFlowField`/`clearFlowFields`, this will fail - that's expected and gets fixed in Task 9 (goal migration); note any such call sites here for that task rather than papering over them now.

Run: `grep -rn "getSharedFlowField\|clearFlowFields" src/main/java/org/ratden/skavenblight` and record the remaining call sites (expected: `ClanratEntity.java`, `DebugFlowFieldReaderItem.java`) - these are exactly what Task 9 and Task 11 fix. It's fine for `compileJava` to fail here as long as the only failures are in those two files; if it fails elsewhere, investigate before proceeding.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/ai/pathing/region/TerritoryRegionMap.java src/main/java/org/ratden/skavenblight/network/WarpFluxNetwork.java
git commit -m "feat(pathing): add TerritoryRegionMap, wire into WarpFluxNetwork

TerritoryRegionMap owns the full scan -> graph -> route-tree -> per-
region-field pipeline for one network, replacing StandardFlowField's
per-nexus cache. Downstream call sites (ClanratEntity, the debug item)
are migrated in later tasks - this task intentionally leaves the
build red at those two files."
```

---

### Task 8: Steady-state dirty tracking + region-graph incremental patch

**Files:**
- Modify: `src/main/java/org/ratden/skavenblight/ai/pathing/region/TerritoryRegionMap.java`
- Modify: `src/main/java/org/ratden/skavenblight/ai/pathing/SiegeProjectManager.java` (add `setActiveProject`)

**Interfaces:**
- Produces: `TerritoryRegionMap.tick(ServerLevel level)` (real implementation, replacing the Task 7 stub); `SiegeProjectManager.setActiveConnectorProject(SiegeProject project)`.

**Design:** On each `tick`, drain `pendingBlockChanges`, map each changed position to its owning region via `regionIndex.regionIdAt(pos)`, mark that region (and its immediate neighbors, to catch merge/split) dirty. Once settle-delay + cooldown allow (same gating values `StandardFlowField` used: `Config.minimumSettleDelayMs`, an 80-tick cooldown), re-scan just the dirty area: re-run `RegionScanner` scoped to the dirty region's old cell footprint plus a small buffer, compare the resulting region set against the old one:
- **Unchanged region set** (same single region found): just recompute that region's local flow field.
- **Changed** (split/merged): patch `RegionGraph` (drop connectors touching removed region ids, re-trace boundaries for the new/changed region(s)), recompute only the route-tree branch under the changed region(s), recompute affected local flow fields.

- [ ] **Step 1: Add `setActiveConnectorProject` to `SiegeProjectManager`**

```java
// SiegeProjectManager.java - add near injectActiveProjects
/**
 * Registers a region's chosen parent connector as a persistent active project, so
 * injectActiveProjects seeds its remaining (not-yet-built) instructions into every
 * subsequent calculation pass for this region - exactly like a reactively-discovered
 * SiegeProject, except this one is chosen once by the route tree and never re-discovered
 * via evaluateMacroProjects (a region's own internal flood never hits an obstacle, by
 * construction - see RegionScanner).
 */
public void setActiveConnectorProject(SiegeProject project) {
    this.activeProjects.clear();
    if (project != null) {
        this.activeProjects.add(project);
    }
}
```

- [ ] **Step 2: Implement `TerritoryRegionMap.tick`**

```java
// TerritoryRegionMap.java
private static final long RECALC_COOLDOWN_TICKS = 80;

public void tick(ServerLevel level) {
    if (isCalculatingAsync.get()) return;

    BlockPos changed;
    boolean anyChange = false;
    while ((changed = pendingBlockChanges.poll()) != null) {
        anyChange = true;
        dirtySnapshotChunks.add(new ChunkPos(changed));
        Integer regionId = regionIndex.regionIdAt(changed);
        if (regionId != null) {
            dirtyRegionIds.add(regionId);
        }
    }
    if (anyChange) {
        lastBlockChangeTime = System.currentTimeMillis();
    }

    if (dirtyRegionIds.isEmpty()) return;

    boolean terrainSettled = (System.currentTimeMillis() - lastBlockChangeTime) >= Config.minimumSettleDelayMs;
    boolean offCooldown = (level.getGameTime() - lastCalculationStart) >= RECALC_COOLDOWN_TICKS;
    if (!terrainSettled || !offCooldown) return;

    if (!isCalculatingAsync.compareAndSet(false, true)) return;
    lastCalculationStart = level.getGameTime();

    Set<Integer> regionsToProcess = Set.copyOf(dirtyRegionIds);
    dirtyRegionIds.clear();

    Set<ChunkPos> territoryChunks = terrainSnapshot != null ? Set.copyOf(getSnapshotChunks()) : Set.of();
    TerrainSnapshot.RefreshResult result = TerrainSnapshot.refresh(
            level, terrainSnapshot, territoryChunks, dirtySnapshotChunks,
            level.getMinBuildHeight(), level.getMaxBuildHeight(), 10);
    dirtySnapshotChunks.removeAll(result.capturedChunks());
    terrainSnapshot = result.snapshot();

    LOGGER.info("[Skavenblight] TerritoryRegionMap recalculating {} dirty region(s)", regionsToProcess.size());

    CompletableFuture.runAsync(() -> {
        try {
            recomputeDirtyRegions(regionsToProcess, territoryChunks);
        } catch (Exception e) {
            LOGGER.error("[Skavenblight] TerritoryRegionMap dirty-region recompute crashed!", e);
        } finally {
            isCalculatingAsync.set(false);
        }
    }, Util.backgroundExecutor());
}

private Set<ChunkPos> getSnapshotChunks() {
    Set<ChunkPos> chunks = new HashSet<>();
    for (Region region : regionIndex.getRegions()) {
        chunks.addAll(region.getChunkCells().keySet());
    }
    return chunks;
}

private void recomputeDirtyRegions(Set<Integer> dirtyIds, Set<ChunkPos> territoryChunks) {
    TerrainSnapshot snapshot = this.terrainSnapshot;

    for (int regionId : dirtyIds) {
        Region oldRegion = regionIndex.getRegions().stream().filter(r -> r.getId() == regionId).findFirst().orElse(null);
        if (oldRegion == null || oldRegion.getMin() == null || oldRegion.getMax() == null) continue;

        // Rescan just this region's old footprint (plus its neighbors would require a wider
        // bounding-box expansion - start with the region's own bounds; a merge/split that
        // reaches beyond it is caught on the NEXT tick when the newly-adjacent region's own
        // cells also get marked dirty by the same block-change event, since a merge implies a
        // shared boundary cell whose neighbor set changed too).
        Set<ChunkPos> localBounds = new HashSet<>();
        for (int x = oldRegion.getMin().getX() >> 4; x <= oldRegion.getMax().getX() >> 4; x++) {
            for (int z = oldRegion.getMin().getZ() >> 4; z <= oldRegion.getMax().getZ() >> 4; z++) {
                localBounds.add(new ChunkPos(x, z));
            }
        }

        List<Region> rescanned = regionScanner.scan(snapshot, localBounds, oldRegion.getMin(),
                snapshot.getMinBuildHeight(), snapshot.getMaxBuildHeight());

        boolean topologyChanged = rescanned.size() != 1;
        if (!topologyChanged) {
            // Same single region, just recompute its local field against the current route tree.
            FlowFieldState state = regionStates.get(regionId);
            if (state != null) {
                calculator.calculateFully(snapshot, state);
            }
            continue;
        }

        LOGGER.info("[Skavenblight] Region {} topology changed ({} sub-regions found) - full territory rebuild triggered", regionId, rescanned.size());
        // A genuine split/merge is rare and the region count for a typical base is small (see
        // RegionScanner's manual test notes) - falling back to a full rebuild here is simpler
        // and safer than hand-patching RegionGraph/RegionRouteTree, and still only runs when
        // topology actually changed, not on every terrain edit.
        BlockPos rootTarget = regionIndex.getRegions().stream()
                .filter(r -> routeTree != null && r.getId() == routeTree.getRootRegionId())
                .findFirst().map(Region::getMin).orElse(oldRegion.getMin());
        rebuildRegionsAndGraph(territoryChunks, rootTarget);
        return;
    }
}
```

**Scope note:** this collapses the spec's "patch the graph locally on split/merge" into "rescan the changed region; if it turns out to still be exactly one region, do the cheap per-region recompute; if not, fall back to a full rebuild." A full rebuild on genuine topology change is simpler and safer than hand-written graph patching, and per Task 3's manual testing a typical base's region count is small enough that this stays cheap - it only fires when topology actually changed, not on every terrain edit (the common case stays a single-region recompute). If manual load testing (Task 11) shows topology changes happening often enough for this to matter, that's a follow-up optimization, not a correctness requirement now.

- [ ] **Step 3: Compile check**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL (same expected-failures-in-ClanratEntity/DebugFlowFieldReaderItem caveat as Task 7, until Task 9/11 land).

- [ ] **Step 4: Manual verification**

In the dev world, place a nexus, let the region map build (`/skavendebug pathing regions`), then break/place a block inside one region only (not touching the boundary). Wait past the settle delay + cooldown.
Expected: `latest.log` shows a "recalculating 1 dirty region" line scoped to just that region, not a full rebuild. Then dig through the wall separating the two regions from Task 3's test base so they merge into one, and confirm the log shows a "topology changed... full territory rebuild triggered" line, and a subsequent `/skavendebug pathing regions` reports 1 region instead of 2.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/ai/pathing/region/TerritoryRegionMap.java src/main/java/org/ratden/skavenblight/ai/pathing/SiegeProjectManager.java
git commit -m "feat(pathing): add steady-state dirty-region recompute to TerritoryRegionMap

Block changes are mapped to their owning region and recomputed in
isolation on the same settle-delay/cooldown gating StandardFlowField
used. A rescan that finds the dirty region's topology changed (split
or merge) falls back to a full territory rebuild rather than
hand-patching the graph - simpler and still scoped to only fire when
topology genuinely changed."
```

---

### Task 9: `RegionFlowField` facade + migrate goal-facing code off `StandardFlowField`

**Files:**
- Create: `src/main/java/org/ratden/skavenblight/ai/pathing/region/RegionFlowField.java`
- Modify: `src/main/java/org/ratden/skavenblight/ai/goal/SiegeGoal.java`
- Modify: `src/main/java/org/ratden/skavenblight/ai/goal/clanrat/AbstractSiegeConstructionGoal.java:37,53-54`
- Modify: `src/main/java/org/ratden/skavenblight/ai/goal/clanrat/FollowFlowFieldGoal.java:10,21,33`
- Modify: `src/main/java/org/ratden/skavenblight/ai/goal/clanrat/WarpSapperGoal.java:15,24,53`
- Modify: `src/main/java/org/ratden/skavenblight/ai/goal/clanrat/SpiralSapperGoal.java:13,24,53`
- Modify: `src/main/java/org/ratden/skavenblight/ai/goal/clanrat/DeployClimbableGoal.java:17,27,53`
- Modify: `src/main/java/org/ratden/skavenblight/entity/custom/ClanratEntity.java`
- Delete: `src/main/java/org/ratden/skavenblight/ai/pathing/StandardFlowField.java`

**Interfaces:**
- Produces: `RegionFlowField(TerritoryRegionMap owner, int regionId, FlowFieldState state, SiegeProjectManager projectManager, FlowFieldCalculator calculator, CalculationThrottler throttler)`, exposing the exact method surface every goal file already calls on `StandardFlowField`: `getNextSiegeNode(ServerLevel, BlockPos)`, `tryClaimTarget(BlockPos, Mob)`, `releaseTarget(BlockPos)`, `isTargetClaimed(BlockPos)`, `forceRecalculation()`, `isCalculating()`, `getWildernessHeadingTarget(BlockPos)`, `getInstructionMap()`, `getTargetPos()`, `getLiveDebugMap()`.

**Why claims are per-region, not per-network:** `AbstractSiegeConstructionGoal`'s claim table exists to stop multiple mobs converging on the same construction target within one bottleneck. Since regions never share internal obstacles (Task 3's design), the only place claims matter now is a region's own connector entry point - keeping the claim table scoped per-`RegionFlowField` (i.e. per-region) rather than sharing one network-wide table is both simpler and correct: two different regions never contend for the same block.

- [ ] **Step 1: Write `RegionFlowField`**

```java
package org.ratden.skavenblight.ai.pathing.region;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import org.ratden.skavenblight.ai.pathing.*;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-region query facade handed to SiegeGoals, replacing StandardFlowField's role for
 * goal-facing code. Deliberately thin: chunk-ticket management and dirty tracking live on
 * TerritoryRegionMap (network-wide); this class only wraps one region's FlowFieldState plus
 * a claim table scoped to that region.
 */
public class RegionFlowField {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final TerritoryRegionMap owner;
    private final int regionId;
    private final FlowFieldState state;
    private final SiegeProjectManager projectManager;
    private final FlowFieldCalculator calculator;
    private final CalculationThrottler throttler;

    private final Map<BlockPos, Mob> claimedTargets = new HashMap<>();

    public RegionFlowField(TerritoryRegionMap owner, int regionId, FlowFieldState state,
                            SiegeProjectManager projectManager, FlowFieldCalculator calculator, CalculationThrottler throttler) {
        this.owner = owner;
        this.regionId = regionId;
        this.state = state;
        this.projectManager = projectManager;
        this.calculator = calculator;
        this.throttler = throttler;
    }

    public int getRegionId() {
        return regionId;
    }

    public SiegeNode getNextSiegeNode(ServerLevel level, BlockPos ratPos) {
        SiegeNode node = state.getInstruction(ratPos);
        if (node == null) return null;

        LiveTerrainAccess live = new LiveTerrainAccess(level);
        TerrainEvaluator evaluator = new TerrainEvaluator();
        return evaluator.isActionCompleted(live, node)
                ? new SiegeNode(node.action() == SiegeNode.SiegeAction.MINE ? node.pos() : node.pos().above(), SiegeNode.SiegeAction.WALK)
                : node;
    }

    public boolean tryClaimTarget(BlockPos pos, Mob claimant) {
        BlockPos key = pos.immutable();
        Mob current = claimedTargets.get(key);
        if (current != null && current != claimant && current.isAlive()) {
            return false;
        }
        claimedTargets.put(key, claimant);
        return true;
    }

    public void releaseTarget(BlockPos pos) {
        if (pos != null) claimedTargets.remove(pos);
    }

    public boolean isTargetClaimed(BlockPos pos) {
        Mob owner = claimedTargets.get(pos);
        return owner != null && owner.isAlive();
    }

    public void forceRecalculation() {
        // Region recalculation is driven by TerritoryRegionMap.tick's dirty-region tracking;
        // a construction goal finishing a build step marks its own position dirty the same way
        // an ordinary block change would, so the next tick's settle-delay/cooldown gating picks
        // it up naturally.
    }

    public boolean isCalculating() {
        return owner.isCalculating();
    }

    public BlockPos getWildernessHeadingTarget(BlockPos ratPos) {
        return owner.getWildernessHeadingTarget(ratPos);
    }

    public Map<BlockPos, SiegeNode> getInstructionMap() {
        return state.getInstructionMap();
    }

    public BlockPos getTargetPos() {
        return state.getTargetPos();
    }

    public Map<BlockPos, SiegeNode> getLiveDebugMap() {
        return calculator.getLiveDebugMap();
    }
}
```

- [ ] **Step 2: Rewrite `SiegeGoal.java`**

```java
package org.ratden.skavenblight.ai.goal;

import org.ratden.skavenblight.ai.pathing.region.RegionFlowField;

public interface SiegeGoal {
    void setFlowField(RegionFlowField flowField);
}
```

- [ ] **Step 3: Type-swap the 5 goal files**

In each of `AbstractSiegeConstructionGoal.java`, `FollowFlowFieldGoal.java`, `WarpSapperGoal.java`, `SpiralSapperGoal.java`, `DeployClimbableGoal.java`: replace the import `import org.ratden.skavenblight.ai.pathing.StandardFlowField;` with `import org.ratden.skavenblight.ai.pathing.region.RegionFlowField;`, and replace every `StandardFlowField` type reference (field declaration, `setFlowField` parameter type) with `RegionFlowField`. No method call changes needed - every method these files call (`getNextSiegeNode`, `isTargetClaimed`, `tryClaimTarget`, `releaseTarget`, `forceRecalculation`, `isCalculating`, `getInstructionMap`, `getWildernessHeadingTarget`) exists on `RegionFlowField` with an identical signature (see Task 9 Step 1).

Verify no file was missed:

```bash
grep -rln "StandardFlowField" src/main/java/org/ratden/skavenblight/ai/goal/
```

Expected after this step: no output.

- [ ] **Step 4: Migrate `ClanratEntity`**

Replace the field declaration and lookup logic (`ClanratEntity.java:39`, `76-131`):

```java
// ClanratEntity.java - replace `private StandardFlowField currentFlowField = null;` with:
private org.ratden.skavenblight.ai.pathing.region.RegionFlowField currentFlowField = null;
private int currentRegionId = -1;
```

```java
// ClanratEntity.java - replace the whole customServerAiStep() body's territory-check block
// (the `if (this.currentFlowField == null && --this.territoryCheckCooldown <= 0)` block) with a
// genuine periodic re-check, since a mob now needs to re-fetch its RegionFlowField every time it
// crosses into a different region, not just once at spawn:
@Override
protected void customServerAiStep() {
    super.customServerAiStep();

    if (--this.territoryCheckCooldown > 0) return;
    this.territoryCheckCooldown = 40;

    if (!(this.level() instanceof ServerLevel serverLevel)) return;

    WarpFluxGridManager gridManager = WarpFluxGridManager.get(serverLevel);
    ChunkPos currentChunk = this.chunkPosition();

    WarpFluxNetwork closestNetwork = null;
    double closestDist = Double.MAX_VALUE;

    for (WarpFluxNetwork network : gridManager.getAllNetworks()) {
        if (network.getTerritoryChunks().contains(currentChunk)) {
            closestNetwork = network;
            break;
        }
        for (BlockPos endpoint : network.getEndpoints()) {
            double dist = this.blockPosition().distSqr(endpoint);
            if (dist < closestDist) {
                closestDist = dist;
                closestNetwork = network;
            }
        }
    }

    if (closestNetwork == null) return;

    org.ratden.skavenblight.ai.pathing.region.RegionIndex regionIndex = closestNetwork.getRegionMap().getRegionIndex();
    org.ratden.skavenblight.ai.pathing.region.Region region = regionIndex.regionAt(this.blockPosition());

    if (region == null) {
        // True wilderness or stranded - handled by FollowFlowFieldGoal's/StrandedGoal's own
        // null-flowField fallback paths (see Task 10). Clear any stale assignment.
        if (this.currentRegionId != -1) {
            this.assignFlowField(null);
            this.currentRegionId = -1;
        }
        return;
    }

    if (region.getId() == this.currentRegionId) return; // still in the same region, no re-fetch needed

    org.ratden.skavenblight.ai.pathing.region.RegionFlowField field = closestNetwork.getRegionMap().getRegionFlowFieldFor(this.blockPosition());
    this.currentRegionId = region.getId();
    this.assignFlowField(field);
}

public void assignFlowField(org.ratden.skavenblight.ai.pathing.region.RegionFlowField field) {
    this.currentFlowField = field;
    this.goalSelector.getAvailableGoals().forEach(wrappedGoal -> {
        if (wrappedGoal.getGoal() instanceof SiegeGoal siegeGoal) {
            siegeGoal.setFlowField(field);
        }
    });
}
```

Remove the old `if (this.currentFlowField == null ...)` guard entirely - the new logic re-checks every `territoryCheckCooldown` cycle (every 40 ticks, same cadence as before) regardless of whether a field is currently assigned, since region membership can change at any time.

- [ ] **Step 5: Delete `StandardFlowField.java`**

```bash
git rm src/main/java/org/ratden/skavenblight/ai/pathing/StandardFlowField.java
```

- [ ] **Step 6: Compile check**

Run: `./gradlew compileJava`
Expected: failures only in files not yet migrated - `DebugFlowFieldReaderItem.java`, `TopologyExporter.java`, `PathingDebugFileWriter.java`, `IServerDebugMode.java` + its 3 implementations (all fixed in Task 11). Run `grep -rln "StandardFlowField" src/main/java/org/ratden/skavenblight` and confirm the remaining list matches exactly those files - if anything else appears, fix it here before moving on.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat(pathing): migrate goal-facing code from StandardFlowField to RegionFlowField

ClanratEntity now re-checks its region on every territory-check cycle
(not just once at spawn), since a mob crossing between regions needs
a fresh RegionFlowField each time rather than one field for its whole
lifetime. SiegeGoal and every goal implementation swap types with no
method-call changes, since RegionFlowField preserves StandardFlowField's
exact API surface. Debug-tooling call sites are migrated in Task 11 -
compileJava is expected to still fail there until then."
```

---

### Task 10: `StrandedGoal` + connector lane-occupancy (overcrowding guard)

**Files:**
- Create: `src/main/java/org/ratden/skavenblight/ai/goal/clanrat/StrandedGoal.java`
- Modify: `src/main/java/org/ratden/skavenblight/entity/custom/ClanratEntity.java` (register the new goal, expose a stranded flag)
- Modify: `src/main/java/org/ratden/skavenblight/ai/goal/clanrat/WidenStairsGoal.java`
- Modify: `src/main/java/org/ratden/skavenblight/ai/pathing/region/RegionFlowField.java` (lane-occupancy tracking)

**Interfaces:**
- Produces: `StrandedGoal(ClanratEntity mob)`; `ClanratEntity.isStranded()`/`markStranded(BlockPos heading)`; `RegionFlowField.tryOccupyLane(BlockPos connectorEntry, Mob mob) -> boolean` / `releaseLane(BlockPos connectorEntry, Mob mob)`.

**Why this is a new goal, not a `RegionFlowField` method:** a stranded mob (in-territory, region unreachable per the route tree) needs its own behavior loop - walk toward the nearest reachable region, and if blocked, attempt a local breach - which doesn't fit `SiegeGoal`'s "I have a flow field, follow it" contract. It runs when `ClanratEntity`'s region lookup finds a region with no route-tree entry, exactly the case `ClanratEntity.customServerAiStep` currently just returns early on (Task 9 Step 4).

- [ ] **Step 1: Add a stranded flag to `ClanratEntity`**

```java
// ClanratEntity.java - add alongside currentFlowField/currentRegionId
private BlockPos strandedHeading = null;

public boolean isStranded() {
    return this.strandedHeading != null;
}

public BlockPos getStrandedHeading() {
    return this.strandedHeading;
}
```

Update the `region == null` branch from Task 9 Step 4's `customServerAiStep` to distinguish "no region at all" (true wilderness - leave `strandedHeading` null, `FollowFlowFieldGoal` already handles this via `getWildernessHeadingTarget`) from "region found, but unreachable":

```java
// ClanratEntity.java - customServerAiStep, after `org.ratden...Region region = regionIndex.regionAt(...)`:
if (region == null) {
    if (this.currentRegionId != -1) {
        this.assignFlowField(null);
        this.currentRegionId = -1;
    }
    this.strandedHeading = null;
    return;
}

org.ratden.skavenblight.ai.pathing.region.RegionRouteTree routeTree = closestNetwork.getRegionMap().getRouteTree();
if (routeTree == null || !routeTree.isReachable(region.getId())) {
    this.assignFlowField(null);
    this.currentRegionId = -1;
    this.strandedHeading = closestNetwork.getRegionMap().getWildernessHeadingTarget(this.blockPosition());
    return;
}

this.strandedHeading = null;
if (region.getId() == this.currentRegionId) return;
```

- [ ] **Step 2: Write `StrandedGoal`**

```java
package org.ratden.skavenblight.ai.goal.clanrat;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import org.ratden.skavenblight.ai.pathing.LiveTerrainAccess;
import org.ratden.skavenblight.ai.pathing.SiegeInteractionHandler;
import org.ratden.skavenblight.ai.pathing.SiegeLineTracer;
import org.ratden.skavenblight.ai.pathing.TerrainEvaluator;
import org.ratden.skavenblight.entity.custom.ClanratEntity;

import java.util.EnumSet;

/**
 * Behavior for a mob whose current region has no route to the target (RegionRouteTree has no
 * entry for it - not yet scanned, or genuinely sealed off). Walks toward the nearest region
 * with a known route; if stalled at the boundary, attempts a local, reactive breach using the
 * same line-tracing cost math the region graph's own connector discovery uses, mirroring the
 * old reactive hitObstacle-triggered behavior as a fallback rather than the primary path.
 */
public class StrandedGoal extends Goal {

    private static final int STALL_TICKS_BEFORE_BREACH_ATTEMPT = 60;

    private final ClanratEntity mob;
    private final TerrainEvaluator terrainEvaluator = new TerrainEvaluator();
    private final SiegeLineTracer lineTracer = new SiegeLineTracer(terrainEvaluator);

    private BlockPos lastHeading = null;
    private int stalledTicks = 0;

    public StrandedGoal(ClanratEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        return this.mob.isStranded();
    }

    @Override
    public boolean canContinueToUse() {
        return this.mob.isStranded();
    }

    @Override
    public void tick() {
        BlockPos heading = this.mob.getStrandedHeading();
        if (heading == null) return;

        if (heading.equals(this.lastHeading)) {
            this.stalledTicks++;
        } else {
            this.stalledTicks = 0;
            this.lastHeading = heading;
        }

        this.mob.getNavigation().moveTo(heading.getX() + 0.5, heading.getY(), heading.getZ() + 0.5, 1.0D);

        if (this.stalledTicks < STALL_TICKS_BEFORE_BREACH_ATTEMPT) return;
        this.stalledTicks = 0;

        attemptLocalBreach(heading);
    }

    private void attemptLocalBreach(BlockPos heading) {
        if (!(this.mob.level() instanceof ServerLevel serverLevel)) return;

        BlockPos current = this.mob.blockPosition();
        int dx = Integer.compare(heading.getX(), current.getX());
        int dz = Integer.compare(heading.getZ(), current.getZ());
        if (dx == 0 && dz == 0) return;

        LiveTerrainAccess live = new LiveTerrainAccess(serverLevel);
        SiegeLineTracer.TraceResult result = lineTracer.trace(live, current, dx, 0, dz, heading, 0,
                pos -> terrainEvaluator.isOutOfBounds(live, pos, null) && false); // bounds irrelevant for a live, unbounded breach attempt

        if (!result.completed()) return;

        result.instructions().forEach((pos, node) ->
                SiegeInteractionHandler.constructSiegeBlock(serverLevel, pos, mob.getDirection(), node.action(), null, this.mob));
    }
}
```

**Note:** `terrainEvaluator.isOutOfBounds(live, pos, null)` will NPE against a `null` `FlowFieldState` (its body calls `state.isOutOfBounds(pos)` unconditionally per Task 1). Fix this in the same step by adding a null-safe check directly in `StrandedGoal` instead of relying on `isOutOfBounds`:

```java
// Replace the outOfBounds predicate passed to lineTracer.trace with a simple build-height/loaded check:
SiegeLineTracer.TraceResult result = lineTracer.trace(live, current, dx, 0, dz, heading, 0,
        pos -> live.isOutsideBuildHeight(pos) || !live.isLoaded(pos));
```

Register the goal in `ClanratEntity.registerGoals()` at a priority between the sapper/breach goals and the wander goal (so it only engages when stranded, and yields to ordinary siege goals which naturally `canUse() == false` while `currentFlowField == null`):

```java
// ClanratEntity.java registerGoals() - add after the existing siege goals, before WaterAvoidingRandomStrollGoal
this.goalSelector.addGoal(9, new StrandedGoal(this));
```

- [ ] **Step 3: Add lane-occupancy tracking to `RegionFlowField`**

```java
// RegionFlowField.java - add alongside claimedTargets
private final Map<BlockPos, java.util.Set<Mob>> laneOccupants = new HashMap<>();
private static final int MAX_LANE_OCCUPANTS = 2;

/** True if there's room for {@code mob} on the lane at {@code connectorEntry} - callers should widen (see WidenStairsGoal) once this starts returning false often. */
public boolean tryOccupyLane(BlockPos connectorEntry, Mob mob) {
    java.util.Set<Mob> occupants = laneOccupants.computeIfAbsent(connectorEntry.immutable(), k -> new java.util.HashSet<>());
    occupants.removeIf(m -> !m.isAlive());
    if (occupants.size() >= MAX_LANE_OCCUPANTS && !occupants.contains(mob)) {
        return false;
    }
    occupants.add(mob);
    return true;
}

public void releaseLane(BlockPos connectorEntry, Mob mob) {
    java.util.Set<Mob> occupants = laneOccupants.get(connectorEntry.immutable());
    if (occupants != null) occupants.remove(mob);
}

public boolean isLaneCrowded(BlockPos connectorEntry) {
    java.util.Set<Mob> occupants = laneOccupants.get(connectorEntry.immutable());
    return occupants != null && occupants.size() >= MAX_LANE_OCCUPANTS;
}
```

- [ ] **Step 4: Wire crowding into `WidenStairsGoal`**

`WidenStairsGoal.java:30` already checks `this.flowField.getInstructionMap().containsKey(currentPos)` to decide whether a rat standing off-path next to stairs should widen them. Add a crowding check so widening also triggers proactively when a lane is saturated, not just reactively when a rat is already standing off-path:

```java
// WidenStairsGoal.java - in canUse() (or wherever the existing containsKey check lives), add:
BlockPos nodePos = this.flowField.getNextSiegeNode(serverLevel, currentPos) != null
        ? this.flowField.getNextSiegeNode(serverLevel, currentPos).pos() : null;
boolean crowded = nodePos != null && this.flowField.isLaneCrowded(nodePos);
```

and OR this `crowded` flag into whatever the existing `canUse()` boolean expression already returns, so a crowded lane is an additional trigger alongside the existing off-path-adjacent-to-stairs check. (The exact insertion point depends on `WidenStairsGoal`'s current `canUse()` structure - read the method first and add `|| crowded` to its return expression.)

- [ ] **Step 5: Compile check**

Run: `./gradlew compileJava`
Expected: same expected-remaining-failures as Task 9 (debug-tooling files, fixed next task).

- [ ] **Step 6: Manual verification**

In the dev world, seal off part of a test base's territory with no possible connector (e.g. a fully bedrock-encased pocket) and spawn a clanrat inside it via `/skavendebug mobs`. Confirm it doesn't idle silently - it should walk toward the territory edge and periodically attempt (and fail) to breach toward it, visible in the log/debug dump. Separately, funnel a large group of clanrats over a single narrow bridge (`/skavendebug incursion` load test) and confirm `WidenStairsGoal` triggers more proactively than before once the lane saturates.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/ai/goal/clanrat/StrandedGoal.java src/main/java/org/ratden/skavenblight/entity/custom/ClanratEntity.java src/main/java/org/ratden/skavenblight/ai/goal/clanrat/WidenStairsGoal.java src/main/java/org/ratden/skavenblight/ai/pathing/region/RegionFlowField.java
git commit -m "feat(pathing): add StrandedGoal and connector lane-occupancy tracking

A mob in a region with no route-tree entry now heads toward the
nearest reachable region and attempts a local reactive breach if
stalled, instead of idling. RegionFlowField now tracks per-connector
lane occupancy so WidenStairsGoal can widen a saturated bridge/stair
proactively instead of only reacting after a rat is already stuck
off-path."
```

---

### Task 11: Debug tooling migration (viz payload/modes, dump writer, activity log, item)

**Files:**
- Modify: `src/main/java/org/ratden/skavenblight/debug/mode/server/IServerDebugMode.java`
- Modify: `src/main/java/org/ratden/skavenblight/debug/mode/server/DetailedServerMode.java`
- Modify: `src/main/java/org/ratden/skavenblight/debug/mode/server/MacroServerMode.java`
- Modify: `src/main/java/org/ratden/skavenblight/debug/mode/server/WildernessServerMode.java`
- Modify: `src/main/java/org/ratden/skavenblight/item/custom/DebugFlowFieldReaderItem.java`
- Modify: `src/main/java/org/ratden/skavenblight/debug/TopologyExporter.java`
- Modify: `src/main/java/org/ratden/skavenblight/debug/PathingDebugFileWriter.java`
- Modify: `src/main/java/org/ratden/skavenblight/debug/SiegeActivityLog.java`

**Interfaces:**
- Consumes: `RegionFlowField` (Task 9), `TerritoryRegionMap` (Task 7/8).

- [ ] **Step 1: Type-swap the 4 server-mode files + `TopologyExporter`**

In `IServerDebugMode.java`, `DetailedServerMode.java`, `MacroServerMode.java`, `WildernessServerMode.java`, `TopologyExporter.java`: replace `import org.ratden.skavenblight.ai.pathing.StandardFlowField;` with `import org.ratden.skavenblight.ai.pathing.region.RegionFlowField;`, and every `StandardFlowField`-typed parameter with `RegionFlowField`. `WildernessServerMode`'s `sharedField.getDynamicWildernessNode(level, evalPos)` call (line 17) has no equivalent on `RegionFlowField` (that method never got ported - only `getWildernessHeadingTarget` did, per Task 9's method-surface list). Replace it with:

```java
// WildernessServerMode.java - replace the getDynamicWildernessNode call with:
BlockPos heading = sharedField.getWildernessHeadingTarget(evalPos);
SiegeNode wildNode = heading != null ? new SiegeNode(heading, SiegeNode.SiegeAction.WALK) : null;
```

- [ ] **Step 2: Extend Macro mode to show region boundaries + route tree**

`MacroServerMode.collectData` currently just reads `sharedField.getInstructionMap()`. Since `RegionFlowField` doesn't expose the whole region graph (it's scoped to one region), have `MacroServerMode` also accept the owning `TerritoryRegionMap` so it can overlay region/route-tree info:

```java
// IServerDebugMode.java - add an overload (default method delegating to the existing one, so
// the other 3 modes that don't need it aren't forced to implement it):
default void collectData(ServerLevel level, BlockPos playerPos, RegionFlowField sharedField,
                          org.ratden.skavenblight.ai.pathing.region.TerritoryRegionMap regionMap,
                          Map<BlockPos, SiegeNode> localNodes) {
    collectData(level, playerPos, sharedField, localNodes);
}
```

```java
// MacroServerMode.java - override the new default to also emit boundary-cell markers
@Override
public void collectData(ServerLevel level, BlockPos playerPos, RegionFlowField sharedField,
                         org.ratden.skavenblight.ai.pathing.region.TerritoryRegionMap regionMap,
                         Map<BlockPos, SiegeNode> localNodes) {
    localNodes.putAll(sharedField.getInstructionMap());

    org.ratden.skavenblight.ai.pathing.region.Region region = regionMap.getRegionIndex().getRegions().stream()
            .filter(r -> r.getId() == sharedField.getRegionId()).findFirst().orElse(null);
    if (region != null) {
        for (BlockPos boundaryCell : region.getBoundaryCells()) {
            localNodes.putIfAbsent(boundaryCell, new SiegeNode(boundaryCell, SiegeNode.SiegeAction.WALK));
        }
    }
}
```

(The existing single-region-field-only overload on the other 3 modes keeps working unchanged via the default method.)

- [ ] **Step 3: Migrate `DebugFlowFieldReaderItem`**

Replace the `network.getSharedFlowField(serverLevel, activeNexus)` call (`DebugFlowFieldReaderItem.java:156`) with a position-based lookup against the player's current region:

```java
// DebugFlowFieldReaderItem.java
org.ratden.skavenblight.ai.pathing.region.TerritoryRegionMap regionMap = network.getRegionMap();
org.ratden.skavenblight.ai.pathing.region.RegionFlowField sharedField = regionMap.getRegionFlowFieldFor(playerPos);

if (sharedField == null) {
    // player standing outside every scanned region - nothing to visualize here
    return;
}

currentMode.getServerLogic().collectData(serverLevel, playerPos, sharedField, regionMap, localNodes);
```

Update the `StandardFlowField activeField = null;`/`sharedField.calculateMapIfNeeded(serverLevel);`/`sharedField.getMappedChunks()` lines (83, 157, 165) accordingly: `calculateMapIfNeeded` has no `RegionFlowField` equivalent (recalculation is driven by `TerritoryRegionMap.tick`, not triggerable per-field) - remove that call. `getMappedChunks()` (used for the payload's chunk highlighting) becomes `regionMap.getRegionIndex().getRegions().stream().filter(r -> r.getId() == sharedField.getRegionId()).findFirst().map(r -> r.getChunkCells().keySet()).orElse(Set.of())`.

- [ ] **Step 4: Extend `PathingDebugFileWriter`**

Change its `StandardFlowField flowField` parameters to `RegionFlowField flowField, TerritoryRegionMap regionMap` (both needed now, since region/route-tree info lives on the map, not the per-region field). Update the direct-field-method calls (`isCalculating`, `getLiveDebugMap`, `getInstructionMap`, `getTargetPos`) - all still exist on `RegionFlowField` with identical names. Replace the calls to methods that only existed on `StandardFlowField` (`getProjectManager`, `getCapturedChunkCount`, `getTerritoryChunkCount`, `getThrottler`, `getCalculator`, `getLastCalculationStartGameTime`) with the equivalent network-wide values from `TerritoryRegionMap` (`regionMap.getThrottler()` still exists; the rest were per-nexus stats that are now per-region or network-wide - pull them from `regionMap` where a network-wide equivalent exists, and drop any that no longer have one, replacing that line of the dump with a note like `"(per-nexus chunk-capture stats retired - see region graph dump below)"`).

Add a new section (near the existing header-writing code) dumping the region graph + route tree, mirroring the debug command from Tasks 3/5/6:

```java
// PathingDebugFileWriter.java - new private method, called from exportDeepDump
private static void writeRegionGraph(FileWriter writer, org.ratden.skavenblight.ai.pathing.region.TerritoryRegionMap regionMap) throws IOException {
    writer.write("--- REGION GRAPH ---\n");
    var index = regionMap.getRegionIndex();
    var graph = regionMap.getRegionGraph();
    var routeTree = regionMap.getRouteTree();

    writer.write(String.format("Regions: %d | Connectors: %d\n", index.getRegions().size(), graph != null ? graph.getAllConnectors().size() : 0));

    for (var region : index.getRegions()) {
        boolean reachable = routeTree != null && routeTree.isReachable(region.getId());
        writer.write(String.format("  region %d: %d cells, reachable=%s, hopCost=%s\n",
                region.getId(), region.cellCount(), reachable,
                reachable ? String.valueOf(routeTree.getHopCost(region.getId())) : "n/a"));
    }
    writer.write("\n");
}
```

- [ ] **Step 5: Annotate `SiegeActivityLog` entries with region id**

```java
// SiegeActivityLog.java - add regionId to the Entry record and record() signature
public static synchronized void record(long gameTime, LivingEntity actor, BlockPos targetPos,
                                        SiegeNode.SiegeAction action, String note, Integer regionId) {
    // ... existing body, passing regionId through to the new Entry field
}

public record Entry(long gameTime, String mobType, String mobId, BlockPos mobPos, BlockPos targetPos,
                     SiegeNode.SiegeAction action, String note, Integer regionId) {
}
```

Add a backward-compatible overload so existing call sites (`SiegeInteractionHandler`, `DeployClimbableGoal`) don't all need region-lookup plumbing immediately:

```java
public static void record(long gameTime, LivingEntity actor, BlockPos targetPos, SiegeNode.SiegeAction action, String note) {
    record(gameTime, actor, targetPos, action, note, null);
}
```

Update `PathingDebugFileWriter`'s entry-formatting loop (`writeRecentActivity`) to print `entry.regionId()` (or `"?"` if null) alongside the existing fields.

- [ ] **Step 6: Compile check**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL. Run `grep -rln "StandardFlowField" src/main/java/org/ratden/skavenblight` and confirm zero matches anywhere in the codebase now.

- [ ] **Step 7: Manual end-to-end verification**

Run: `./gradlew runClient`. Build a test base with 3+ regions (chasm-split, one sealed pocket), place a nexus, spawn clanrats via `/skavendebug mobs`, and:
1. Use the debug item in Macro mode - confirm region boundaries render alongside the existing instruction-vector overlay.
2. Use the debug item in Detailed mode - confirm it shows one region's local field.
3. Use the debug item in Wilderness mode while standing outside the territory - confirm it shows a heading toward the nearest region.
4. Run the deep-dump command and open the output file - confirm the new "REGION GRAPH" section lists correct region/connector/reachability info, and `SiegeActivityLog` entries show a region id.
5. Run a full incursion load test (`/skavendebug incursion`) with the awkward test bases from earlier tasks (floating platform, bedrock-buried nexus) and confirm mobs successfully path to the nexus in both.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat(pathing): migrate debug tooling to the region system

SyncFlowFieldDebugPayload/debug modes/PathingDebugFileWriter/the debug
item all now read from RegionFlowField + TerritoryRegionMap instead of
StandardFlowField. Macro mode overlays region boundaries; the deep
dump gains a region-graph/route-tree section; SiegeActivityLog entries
are tagged with the region they occurred in. This closes out the
region-based pathing redesign - StandardFlowField no longer exists
anywhere in the codebase."
```

---

## Self-Review Notes

**Spec coverage:** every section of `docs/superpowers/specs/2026-07-24-region-based-flow-field-design.md` maps to a task - Architecture/Components → Tasks 1-9; Data flow (initial build, steady state, mob query, construction) → Tasks 7-9; Error handling (stranded, wilderness, sealed regions, overcrowding, graph churn, nexus moved) → Tasks 8, 10; Testing/verification tooling → Task 11.

**Deviations from the spec's high-level sketch, resolved during planning (documented here since the spec itself is now slightly stale on these three points):**
1. Region-scoped floods need `FlowFieldState`'s new cell-filter (Task 1), not just chunk-set scoping - two regions can share a chunk.
2. `RegionScanner`'s adjacency relation is `TerrainEvaluator.getValidOrthogonalSteps`-based, not raw `isWalkableTerrain` adjacency - otherwise ordinary single-block gaps the core Dijkstra already handles would wrongly become region boundaries.
3. Region-graph incremental patching (Task 8) is a "rescan, and fall back to full rebuild only if topology actually changed" strategy rather than hand-patching `RegionGraph`/`RegionRouteTree` in place - simpler, and the spec's own testing section already anticipates tuning this kind of thing manually if it proves too coarse.

**Placeholder scan:** no TBDs; every step has real code or an exact grep/manual-test command. Task 10 Step 4 (`WidenStairsGoal` crowding wire-up) is intentionally left as "add `|| crowded` to the existing `canUse()` expression" rather than a full rewrite, since the exact expression wasn't captured verbatim during planning - this is a one-line mechanical insertion, not a missing design decision.

**Type consistency:** `RegionFlowField`'s method names (`getNextSiegeNode`, `tryClaimTarget`, `releaseTarget`, `isTargetClaimed`, `forceRecalculation`, `isCalculating`, `getWildernessHeadingTarget`, `getInstructionMap`, `getTargetPos`, `getLiveDebugMap`) are used identically across Tasks 9-11 wherever goal/debug code calls them - verified against the actual `StandardFlowField`-call grep performed during planning, not assumed.
