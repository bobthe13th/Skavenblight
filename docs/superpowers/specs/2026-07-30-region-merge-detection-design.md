# Region Merge Detection (Finding B fix) — Design

## Overview

`TerritoryRegionMap.recomputeDirtyRegions`'s fast path (the non-topology-changed branch, taken
when a dirty region's local rescan finds exactly one connected piece) cannot currently tell "this
region grew a little" apart from "this region just silently absorbed a different, previously
distinct region." That second case — a genuine merge — is misread as "no topology change," which
corrupts the region graph: two duplicate `Region` objects with identical cells, a stale connector
between them pointing at a distinction that no longer exists, and `RegionIndex`'s last-write-wins
tie-break silently orphaning one of the two with no signal that anything went wrong.

This is Finding B in `docs/pathing/region-pathing-hardening-findings.md` — confirmed there, and
deliberately left unfixed because "a correct fix requires a real design decision, not a small
patch." This spec picks Option C from that document's three candidates ("fix `rescanned.size() !=
1`'s own merge-blindness directly") and works out exactly what that means.

**Why now:** a live diagnostic dump (2026-07-30, siege_dump_2026-07-30_11-46-02.txt) caught this
happening for real, well past the point of theoretical concern. A rat's raw instruction-chain
trace formed an actual cycle — `-1,-60,-13 → ... → -14,-57,3 → -15,-58,3 →` **"LOOP DETECTED -
revisited -14, -57, 3."** A mob following that chain walks in a circle forever: it never reaches a
real, unbuilt frontier, so nothing ever gets claimed or built, and the crowd never clears no matter
what `AwaitFormationGoal` does (it can only redirect a rat away from a target *claimed by someone*,
not fix a route that's fundamentally broken). Corroborated by the same dump's region graph (`4
regions | 5 connectors | rebuild generation 3`, real topological complexity) and by
`run/logs/latest.log` showing repeated `Region 0 topology changed (4 sub-regions found) - full
territory rebuild triggered` — the exact mechanism Finding A describes as firing on nearly every
dirty tick for any region with a connector.

## Goal

Make `recomputeDirtyRegions` correctly recognize a merge as a topology change, so it takes the
already-correct full-rebuild path instead of the buggy fast path. Nothing else needs to change:
`RegionGraph.build()` already reconstructs every connector from the current region set from
scratch, so once a merge is routed through a real rebuild, the stale connector and the duplicate
region simply can't survive it — there is no separate cleanup step.

## Background invariants (established during design discussion, not new work)

These aren't part of the fix — they're the ground truth the fix has to respect, confirmed against
the actual code before writing this spec:

- **A block belongs to zero or one *genuinely walkable* region.** This holds today for ordinary
  flood-filled membership.
- **A planned-but-unbuilt connector is the one deliberate exception.**
  `RegionGraph.registerConnector` calls `fromRegion.addCell(step.pos())` /
  `toRegion.addCell(step.pos())` on every traced step at connector-*discovery* time, before any of
  it is built — so those specific cells are intentionally claimed by both endpoint regions while
  still unwalkable. This is correct and unrelated to Finding B: it's how a mob assigned to either
  region finds the connector's `SiegeProject` instructions at all. **Once the connector is fully
  built, those cells become genuinely walkable — which is exactly the moment a merge should be
  detected.** The bug is that today it isn't.
- **`RegionScanner.scan()` renumbers every region ID from 0 on a full rebuild** (`nextId = 0`,
  assigned sequentially). The fast path is the only place an old ID is deliberately preserved
  (`rescanned.get(0).withId(regionId)`). So once a merge is correctly routed to a full rebuild, ID
  consolidation happens for free as part of the existing renumbering — no "which ID wins" decision
  needed.
- **Isolated pockets (a pit, a sealed room) are the existing `isStranded()` / route-tree-unreachable
  concept**, not something new. A pocket a rat should never voluntarily seek out, but must be able
  to build out of if knocked in (e.g. by combat), matches a region with no route-tree path to the
  nexus. `AwaitFormationGoal`'s alternative-work search already only ever searches inside the rat's
  *own* `RegionFlowField`'s instruction map, so it can never route a rat *into* a separate pocket
  region as "alternative work" — that boundary is already respected, confirmed by reading the code,
  not assumed.
- **"Prefer the exit further along the path" is already correct.** `RegionRouteTree` builds a
  Dijkstra shortest-hop-cost tree from the nexus's region outward (`if (candidateCost <
  tree.hopCost.getOrDefault(neighbor, MAX_VALUE))`) - a region's parent connector is, by
  construction, whichever one gives the lowest total hop cost to the nexus. If a pocket has two
  possible exits, the route tree already prefers the one making more progress. Worth a real test
  once this is in, but it is not new design work.

## The fix

### Where

`TerritoryRegionMap.recomputeDirtyRegions`, at the exact line that currently reads:

```java
boolean topologyChanged = rescanned.size() != 1;
```

This is the right (and only) place: every consumer downstream of this boolean already does the
right thing once it's `true` — the full-rebuild path is untouched.

### What to check, and why not "every cell"

`rescanned.get(0)` can be large — the main territory region was over 6500 cells in the dump that
caught this bug. Checking every one of its cells against the region index on every fast-path
attempt would trade today's bug (an unconditional full rebuild on nearly every dirty tick for any
connector-bearing region - Finding A) for a different, still-real performance problem: a
moderately expensive membership scan on every dirty tick instead. Finding A exists specifically
because of a past O(n²) blowup in this same subsystem — this fix must not reintroduce a variant of
it.

Instead, compute only the **delta**: cells present in this fresh rescan that were **not** present
in the *old* region before this update. A region's cells are stored as one `BitSet` per chunk
(`Region.chunkCells: Map<ChunkPos, BitSet>`), so the delta for any chunk both the old and new region
touch is one `BitSet.andNot()` — cheap, and bounded by how much chunk-local territory actually
changed, not by the region's total size. Then check *only* those delta cells against the current
`RegionIndex` for a different owning ID, short-circuiting the moment one is found.

This keeps the cost proportional to how much *new* ground this rescan actually picked up:

- **Ordinary small change, nothing merging:** the delta is small (a handful of cells around
  whatever block changed), and none of them resolve to a foreign ID. Cheap, as the fast path was
  always meant to be.
- **Genuine merge:** the delta is the *other* region's entire cell set (all of it is "new" from
  this region's perspective). Every one of those cells resolves to the foreign ID, so the
  short-circuit hits on the very first cell checked, not after scanning everything.

### New method: `Region.cellsNotIn(Region other)`

The delta computation belongs on `Region` itself, not reimplemented at the call site in
`TerritoryRegionMap` — `Region` already owns its own bit-packing scheme (`cellIndex()`'s
chunk-local `(x, z, y) → bit index` math is private today, and should stay private). Add:

```java
/**
 * Cells in this region that are NOT in {@code other} - one BitSet.andNot() per chunk this
 * region touches, not a full re-scan. Used by TerritoryRegionMap's dirty-rescan merge
 * detection (see docs/superpowers/specs/2026-07-30-region-merge-detection-design.md): the
 * cells THIS region gained relative to the pre-rescan old region are exactly the cells worth
 * checking for foreign ownership - a genuine merge's absorbed territory shows up entirely as
 * "new" cells here, while an ordinary small change's delta stays small.
 */
public Set<BlockPos> cellsNotIn(Region other) {
    Set<BlockPos> delta = new HashSet<>();
    for (Map.Entry<ChunkPos, BitSet> entry : this.chunkCells.entrySet()) {
        ChunkPos chunk = entry.getKey();
        BitSet newBits = (BitSet) entry.getValue().clone();
        BitSet otherBits = other.chunkCells.get(chunk);
        if (otherBits != null) {
            newBits.andNot(otherBits);
        }
        for (int i = newBits.nextSetBit(0); i >= 0; i = newBits.nextSetBit(i + 1)) {
            delta.add(posFromCellIndex(chunk, i));
        }
    }
    return delta;
}

private BlockPos posFromCellIndex(ChunkPos chunk, int index) {
    int localY = index % height;
    int remainder = index / height;
    int localX = remainder / 16;
    int localZ = remainder % 16;
    return new BlockPos(chunk.getMinBlockX() + localX, minBuildHeight + localY, chunk.getMinBlockZ() + localZ);
}
```

`posFromCellIndex` is the exact inverse of the existing private `cellIndex(BlockPos)` — same
chunk-local packing, run backward.

### Updated check in `recomputeDirtyRegions`

```java
Set<BlockPos> newCells = rescanned.get(0).cellsNotIn(oldRegion);
boolean absorbedForeignRegion = newCells.stream()
        .anyMatch(pos -> {
            Integer owner = regionIndex.regionIdAt(pos);
            return owner != null && owner != regionId;
        });
boolean topologyChanged = rescanned.size() != 1 || absorbedForeignRegion;
```

`regionIndex.regionIdAt(pos)` is already O(1) (per-chunk lookup, not a linear scan — an earlier,
separate perf fix). `regionIndex` here is the **pre-rebuild** index (this check must run before
anything downstream mutates it), which is exactly what "does this cell already belong to someone
else, right now" needs to mean.

## Explicitly out of scope

- **Provisional connector-cell dual-membership is not being changed.** It's the correct, deliberate
  mechanism Finding A/Task 8 already documents; this fix only concerns detecting when unbuilt
  cells that were provisionally shared *become* genuinely walkable and two regions should collapse
  into one.
- **No new "which region ID wins" logic.** The full rebuild already renumbers everything from
  scratch; there is nothing to decide.
- **Finding A's performance concern (the fast path being unreachable at all for connector-bearing
  regions) is being fixed as a side effect of correctly reaching the fast path more often**, but
  the deeper architectural change Finding A itself proposes (separating a region's "natural"
  flood-fill bounds from its full addCell-inclusive bounds) is not part of this fix. If the fast
  path is still taken less often than expected after this ships, that's the next thing to look at,
  not a sign this fix is wrong.
- **Dynamic staircase-width scaling** (tying lane count to queue depth) — still its own, separate,
  not-yet-started follow-up plan.

## Small follow-on fix: `AwaitFormationGoal.findFormationSlot()` hardening

Discovered during this design discussion, not a new symptom report: `findFormationSlot()` uses
`region.contains(candidate)` as its only safety check for a waiting slot. Per the invariant above,
`contains()` returns `true` for a provisionally-claimed-but-not-yet-built connector cell too (same
bitset, no distinction from a genuinely walkable cell) - so the search could currently hand a
waiting rat an unbuilt connector cell (open air over a gap) as a "safe" spot.

Fix: in `findFormationSlot()`'s candidate loop, add a live block-state check alongside the existing
`region.contains(candidate)` check - specifically, require `level.getBlockState(candidate.below())
.blocksMotion()` (real, current solid ground beneath the candidate), not just region membership.
This is the same kind of live-terrain check `TerrainEvaluator.isWalkableTerrain` already uses
elsewhere in this codebase for the identical reason.

## Testing approach

GameTest only, matching every other test in this codebase (see `PathingRegionGameTests.java` for
the established real-`Region`/`TerritoryRegionMap` construction pattern: `rebuild()` is async, wait
via `succeedWhen()` for `!isCalculating()`).

1. **Unit-shaped test for `Region.cellsNotIn`**: build two small hand-constructed `Region` objects
   with overlapping and non-overlapping cells (via `addCell` directly, no scanner needed), assert
   the delta contains exactly the expected cells and no others. Cheapest, most direct test of the
   new method in isolation.
2. **Reproduce the merge-misdetection directly**: adapt the existing
   `PathingRegionGameTests.testRepeatedConnectorCompletionsDontExplodeRebuildCount` scenario (or a
   new, smaller GameTest built the same way) that closes a connector's gap and drives
   `recomputeDirtyRegions` - assert there is exactly one region covering both former territories
   afterward (not two duplicates), and that the region graph has no stale connector between the
   now-merged IDs. This is the actual regression guard for Finding B.
3. **Confirm the fast path still fires for ordinary, non-merging changes** (a plain block
   build/mine with no connector involved) - assert `recomputeDirtyRegions` takes the fast path
   (region ID preserved, no full rebuild triggered) exactly as before this fix, so the delta check
   isn't accidentally over-triggering the expensive path for the common case.
4. **`AwaitFormationGoal.findFormationSlot()` hardening test**: a candidate cell that's
   `region.contains()`-true but has no solid ground below it (simulating an unbuilt connector cell)
   must be rejected; a genuinely walkable candidate at the same distance must still be found.
