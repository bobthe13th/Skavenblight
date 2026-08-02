# First Automated Test: Debug Flow Field Item Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the repo's first NeoForge GameTest, targeting the already-applied fix to `DebugFlowFieldReaderItem` (it used to silently send no update at all when the active debug mode had no local `RegionFlowField` for the player's position — looked like the tool was stuck in Wilderness mode). Lock that fix in place with an automated regression test, without relying on the deprecated mock-player/packet-capture path.

**Spec:** `docs/superpowers/specs/2026-07-26-debug-item-gametest-design.md` (draft — review before executing).

**Architecture:** Extract the "which region field, what payload (if any)" decision out of `DebugFlowFieldReaderItem.inventoryTick` into a new `public static Optional<SyncFlowFieldDebugPayload> computeDebugPayload(...)` method (behavior-preserving refactor of the current, already-fixed logic). `inventoryTick` becomes a thin wrapper that sends the Optional's payload if present. A new GameTest class (`org.ratden.skavenblight.gametest.DebugFlowFieldItemGameTests`, `@GameTestHolder(Skavenblight.MODID)`) builds a minimal `WarpFluxNetwork` (one `WARP_FLUX_CONDUIT` adjacent to one `WARPSTONE_NEXUS`, placed via a GameTest structure template), waits for its `TerritoryRegionMap`'s async first rebuild to finish, then calls `computeDebugPayload` for a position inside the network's territory but outside any scanned region and asserts the `Optional` is present.

**Tech Stack:** Java 21, NeoForge 1.21.1 (`net.minecraft.gametest.framework`, `net.neoforged.neoforge.gametest`), existing `network`/`ai/pathing/region`/`item/custom` packages.

## Global Constraints

- No unit test framework exists in this repo — `./gradlew runGameTestServer` (already configured in `build.gradle`) is the only automated test runner available. Every task's "test" step is either `./gradlew compileJava` or `./gradlew runGameTestServer`, as noted per task.
- `computeDebugPayload` must be `public static` on `DebugFlowFieldReaderItem` — the GameTest class lives in a different package (`org.ratden.skavenblight.gametest`) and needs to call it directly, with no `ServerPlayer`/`Connection`/packet capture involved (see spec's rationale on `makeMockServerPlayerInLevel` being `@Deprecated(forRemoval = true)`).
- Default `territoryChunkRadius` is **2** chunks (`Config.java:41`) — this bounds how far from the built structure an "in territory, but unscanned" test position can be while still being genuinely inside the network's territory.
- Do not touch `PathingDebugFileWriter.java`'s uncommitted changes from a prior session (the deep-dump per-mob field lookup fix) — unrelated, out of scope.
- The GameTest structure file cannot be hand-authored blind — it must come from a live dev client's Structure Block save (see Task 2). Do not attempt to write raw NBT bytes directly.
- Follow existing code style: no comments explaining *what* code does, only non-obvious *why* (matching the rest of the file); `LogUtils.getLogger()`/SLF4J is this repo's logging convention if a test needs to log anything (it shouldn't).

---

## File Structure

| File | Change |
|---|---|
| `src/main/java/org/ratden/skavenblight/item/custom/DebugFlowFieldReaderItem.java` | Extract `computeDebugPayload`; `inventoryTick` becomes a thin wrapper around it. |
| `src/main/resources/data/skavenblight/structure/gametest/small_nexus_territory.nbt` | New GameTest structure (binary, authored live via Structure Block — not hand-written). |
| `src/main/java/org/ratden/skavenblight/gametest/DebugFlowFieldItemGameTests.java` | New GameTest class. |

---

### Task 1: Extract `computeDebugPayload` from `DebugFlowFieldReaderItem`

**Files:**
- Modify: `src/main/java/org/ratden/skavenblight/item/custom/DebugFlowFieldReaderItem.java`

**Interfaces:**
- Produces: `public static Optional<SyncFlowFieldDebugPayload> computeDebugPayload(WarpFluxGridManager gridManager, ServerLevel serverLevel, ChunkPos playerChunk, BlockPos playerPos, DebugMode currentMode)` — Task 3's GameTest calls this directly.

**Why:** This is a pure, behavior-preserving refactor of the logic already living in `inventoryTick` (lines 174-260 as currently written) — it does not change what packet gets sent in any scenario. It exists purely to make the decision testable without a `ServerPlayer`/`Connection`.

- [ ] **Step 1: Add the `java.util.Optional` import**

In the existing import block, add alongside the other `java.util.*` imports:

```java
import java.util.Optional;
```

- [ ] **Step 2: Replace `inventoryTick`'s body with the extracted method plus a thin wrapper**

Replace the entire current `inventoryTick` method (from `@Override` through its closing brace) with:

```java
@Override
public void inventoryTick(ItemStack stack, Level level, Entity entity, int slotId, boolean isSelected) {
    if (!level.isClientSide() && isSelected && entity instanceof ServerPlayer serverPlayer) {

        // Throttle updates to once per second (20 ticks)
        if (level.getGameTime() % 20 == 0) {
            ServerLevel serverLevel = (ServerLevel) level;
            WarpFluxGridManager gridManager = WarpFluxGridManager.get(serverLevel);
            BlockPos playerPos = serverPlayer.blockPosition();

            // Read the item's current active mode
            CustomData customData = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
            int currentModeIndex = customData.copyTag().getInt("DebugMode");
            DebugMode currentMode = DebugMode.values()[currentModeIndex % DebugMode.values().length];

            computeDebugPayload(gridManager, serverLevel, serverPlayer.chunkPosition(), playerPos, currentMode)
                    .ifPresent(payload -> serverPlayer.connection.send(payload));
        }
    }
}

/**
 * Given the current grid state and the observer's position/mode, decides which region's flow
 * field (if any) to visualize and builds the payload to send - or {@link Optional#empty()} only
 * when the position is outside every network's territory entirely. Pulled out of
 * {@link #inventoryTick} so it can be exercised directly by a GameTest without a real
 * {@code ServerPlayer}/{@code Connection}.
 */
public static Optional<SyncFlowFieldDebugPayload> computeDebugPayload(
        WarpFluxGridManager gridManager, ServerLevel serverLevel,
        ChunkPos playerChunk, BlockPos playerPos, DebugMode currentMode) {

    // Territory of the last network whose chunk geometrically matched the player,
    // even if it had no usable field for the current mode/position - used below to
    // send a clearing update if every match falls through empty-handed.
    Set<ChunkPos> lastMatchedTerritory = null;

    for (WarpFluxNetwork network : gridManager.getAllNetworks()) {
        // Skip a network with no live nexus - its region map never bootstraps (see
        // WarpFluxNetwork.tick), so it can never yield a real field here.
        if (!network.isValid(serverLevel)) continue;

        if (network.getTerritoryChunks().contains(playerChunk)) {
            lastMatchedTerritory = network.getTerritoryChunks();

            // Position-based lookup against the player's current region - replaces
            // the old nexus-lookup + network-wide getSharedFlowField call, since
            // pathing is now region-scoped rather than one field per whole territory.
            TerritoryRegionMap regionMap = network.getRegionMap();
            RegionFlowField sharedField = regionMap.getRegionFlowFieldFor(playerPos);
            boolean usingBorrowedWildernessField = false;

            if (sharedField == null && currentMode == DebugMode.WILDERNESS_PATH) {
                // Wilderness mode's whole purpose is showing a heading FROM outside
                // every scanned region - getWildernessHeadingTarget delegates
                // network-wide via TerritoryRegionMap regardless of which region's
                // RegionFlowField instance issues the call (see RegionFlowField and
                // FollowFlowFieldGoal's identical use of this for wandering mobs), so
                // grab any available region's field as a proxy instead of skipping
                // visualization entirely for the one mode that needs this most.
                sharedField = regionMap.getRegionIndex().getRegions().stream()
                        .map(r -> regionMap.getRegionFlowFieldFor(r.getMin()))
                        .filter(Objects::nonNull)
                        .findFirst().orElse(null);
                usingBorrowedWildernessField = sharedField != null;
            }

            if (sharedField == null) {
                // This network's territory geometrically contains the player's
                // current chunk, but it has no usable field here - its region map may
                // still be bootstrapping, or (since territory bubbles are flat 2D
                // chunk radii with no Y-awareness) a DIFFERENT network's territory
                // happens to overlap this exact spot at another height. Keep checking
                // other networks rather than giving up on the first geometric match.
                continue;
            }

            Map<BlockPos, SiegeNode> localNodes = new HashMap<>();
            currentMode.getServerLogic().collectData(serverLevel, playerPos, sharedField, regionMap, localNodes);

            // The borrowed field above is picked arbitrarily (whichever region's
            // field happened to exist first) purely so getWildernessHeadingTarget's
            // network-wide lookup can be made - it has nothing to do with which
            // region the computed heading actually points toward (that's resolved
            // independently, by nearest-distance, inside TerritoryRegionMap). Chunk
            // highlighting that region would mislead the client into lighting up an
            // unrelated, possibly-distant region, so skip highlighting entirely in
            // that case - a missing highlight is better than a wrong one. The normal
            // (non-wilderness-fallback) case is untouched: sharedField there really is
            // the region the player is standing in, so highlighting it is correct.
            Set<ChunkPos> highlightedChunks;
            if (usingBorrowedWildernessField) {
                highlightedChunks = Set.of();
            } else {
                RegionFlowField highlightField = sharedField;
                highlightedChunks = regionMap.getRegionIndex().getRegions().stream()
                        .filter(r -> r.getId() == highlightField.getRegionId())
                        .findFirst()
                        .map(r -> r.getChunkCells().keySet())
                        .orElse(Set.of());
            }

            return Optional.of(new SyncFlowFieldDebugPayload(
                    network.getTerritoryChunks(), localNodes, highlightedChunks, currentMode.ordinal()));
        }
    }

    if (lastMatchedTerritory != null) {
        // Every matching network fell through empty-handed (e.g. Detailed/Macro mode
        // while standing in an unmapped or route-unreachable region). Return an
        // empty-but-current-mode update so the caller clears whatever it last
        // rendered instead of leaving stale data on screen - previously this case sent
        // nothing at all, which left the display frozen on the last mode that DID
        // produce data (almost always Wilderness, since its borrow-fallback above
        // rarely fails), making the tool look permanently stuck in that mode.
        return Optional.of(new SyncFlowFieldDebugPayload(
                lastMatchedTerritory, Map.of(), Set.of(), currentMode.ordinal()));
    }

    return Optional.empty();
}
```

- [ ] **Step 3: Compile check**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/item/custom/DebugFlowFieldReaderItem.java
git commit -m "refactor(debug): extract computeDebugPayload from DebugFlowFieldReaderItem

Pure extraction of inventoryTick's network/region-field decision logic
into a public static method taking plain data (grid manager, level,
chunk/pos, mode) and returning Optional<SyncFlowFieldDebugPayload>. No
behavior change - inventoryTick now just sends whatever the Optional
contains. Makes the decision testable without a real ServerPlayer or
Connection, which the next commit's GameTest relies on."
```

---

### Task 2: Author the GameTest structure

**Files:**
- Create: `src/main/resources/data/skavenblight/structure/gametest/small_nexus_territory.nbt`

**Interfaces:** None (binary data asset only).

**Why:** GameTests load their world state from a structure template; there's no reliable way to hand-author correct structure NBT bytes without a live client, and no existing datagen path in this repo for structure templates.

- [ ] **Step 1: Launch a dev client**

Run: `./gradlew runClient`

- [ ] **Step 2: Build the minimal network in creative mode**

In a fresh creative-mode area (away from anything else in the test world), place:
- A flat floor at least 5x5 blocks (any solid block).
- One `ModBlocks.WARPSTONE_NEXUS` block ("Warpstone Nexus" in the creative inventory) on the floor.
- One `ModBlocks.WARP_FLUX_CONDUIT` block ("Warp Flux Conduit") directly orthogonally adjacent to the nexus block (any of the 4 side directions or on top - not diagonal).

Remember the exact world coordinates of the nexus block - needed in Task 3 to compute the "in territory, unscanned" test position relative to it.

- [ ] **Step 3: Confirm the network actually bootstraps**

Wait a few seconds (or use `/skavendebug pathing regions` if available, or watch the server log for `"[Skavenblight] TerritoryRegionMap rebuild FINISHED"`), then confirm a region was created around the nexus - e.g. via the (already-fixed) `DebugFlowFieldReaderItem` in Detailed mode standing on the floor, or a deep dump (shift-right-click) - before proceeding, so the saved structure is known-good.

- [ ] **Step 4: Save the structure with a Structure Block**

Place a Structure Block (`/give @s minecraft:structure_block` if not in creative inventory) covering the floor + nexus + conduit with a few blocks of padding on every side (including a couple of blocks of air above and around, so the GameTest's own bounding box has room for the "unscanned" position used in Task 3). Set:
- Mode: `SAVE`
- Structure name: `skavenblight:gametest/small_nexus_territory`

Click "Save".

- [ ] **Step 5: Copy the saved file into the mod's resources**

The Structure Block writes to the currently-running world's save folder under `generated/skavenblight/structures/gametest/small_nexus_territory.nbt`. Copy that file to:

```
src/main/resources/data/skavenblight/structure/gametest/small_nexus_territory.nbt
```

Create the `data/skavenblight/structure/gametest/` directory if it doesn't exist yet.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/data/skavenblight/structure/gametest/small_nexus_territory.nbt
git commit -m "test(gametest): add small_nexus_territory structure template

Minimal floor + WARPSTONE_NEXUS + adjacent WARP_FLUX_CONDUIT, saved via
a live dev client's Structure Block - the smallest world state that
bootstraps a real WarpFluxNetwork with a working TerritoryRegionMap.
Used by DebugFlowFieldItemGameTests (next commit)."
```

---

### Task 3: Write the GameTest and verify it catches the regression

**Files:**
- Create: `src/main/java/org/ratden/skavenblight/gametest/DebugFlowFieldItemGameTests.java`

**Interfaces:**
- Consumes: `DebugFlowFieldReaderItem.computeDebugPayload(WarpFluxGridManager, ServerLevel, ChunkPos, BlockPos, DebugFlowFieldReaderItem.DebugMode)` from Task 1; `WarpFluxGridManager.get(ServerLevel)`/`getAllNetworks()`; `WarpFluxNetwork.isValid(ServerLevel)`/`getRegionMap()`; `TerritoryRegionMap.isCalculating()`/`getGeneration()`.

**Why:** This is the actual regression test. It must fail if `computeDebugPayload`'s post-loop fallback (added by the earlier live fix, preserved verbatim by Task 1's extraction) is missing, and pass with it present - Steps 3-6 below prove that directly rather than assuming it.

- [ ] **Step 1: Write the test class**

```java
package org.ratden.skavenblight.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.item.custom.DebugFlowFieldReaderItem;
import org.ratden.skavenblight.network.WarpFluxGridManager;
import org.ratden.skavenblight.network.WarpFluxNetwork;
import org.ratden.skavenblight.network.payload.SyncFlowFieldDebugPayload;

import java.util.Optional;

@GameTestHolder(Skavenblight.MODID)
@PrefixGameTestTemplate(false)
public class DebugFlowFieldItemGameTests {

    @GameTest(template = "gametest/small_nexus_territory", timeoutTicks = 400)
    public static void detailedModeNeverGoesSilentInTerritory(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();

        // Position well inside the network's 2-chunk-radius territory (see Config.territoryChunkRadius)
        // but far enough from the built floor that RegionScanner never walked it into any region -
        // this is the exact condition that used to make computeDebugPayload return Optional.empty().
        BlockPos unmappedPos = helper.absolutePos(new BlockPos(24, 1, 24));
        ChunkPos unmappedChunk = new ChunkPos(unmappedPos);

        helper.succeedWhen(() -> {
            WarpFluxGridManager gridManager = WarpFluxGridManager.get(level);
            WarpFluxNetwork network = gridManager.getAllNetworks().stream()
                    .filter(n -> n.isValid(level))
                    .findFirst()
                    .orElse(null);
            helper.assertTrue(network != null, "network never registered from the placed nexus/conduit");
            helper.assertTrue(!network.getRegionMap().isCalculating() && network.getRegionMap().getGeneration() > 0,
                    "region map never finished its first rebuild");

            Optional<SyncFlowFieldDebugPayload> result = DebugFlowFieldReaderItem.computeDebugPayload(
                    gridManager, level, unmappedChunk, unmappedPos, DebugFlowFieldReaderItem.DebugMode.DETAILED_NODES);

            helper.assertTrue(result.isPresent(),
                    "computeDebugPayload returned empty for an in-territory position - the exact regression this test guards against");
        });
    }
}
```

- [ ] **Step 2: Compile check**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run the test and confirm it passes (fix is currently in place)**

Run: `./gradlew runGameTestServer`
Expected: console reports `detailedModeNeverGoesSilentInTerritory` (namespaced as `skavenblight:detailedmodenevergoessilentinterritory` or similar, per the `@PrefixGameTestTemplate(false)` + method-name-derived id) as passed, 0 failures.

If `unmappedPos` resolves inside a scanned region instead of empty air (so the test would trivially pass even with the bug present), move it further out - still within the 2-chunk territory radius of the nexus placed in Task 2 - and re-run until step 3's `assertTrue` on generation/isCalculating passes reliably and you've confirmed via `/skavendebug` or a deep dump that this exact position is genuinely outside every region.

- [ ] **Step 4: Temporarily reintroduce the pre-fix bug to prove the test catches it**

In `DebugFlowFieldReaderItem.computeDebugPayload` (from Task 1), temporarily replace:

```java
    if (lastMatchedTerritory != null) {
        // Every matching network fell through empty-handed (e.g. Detailed/Macro mode
        // while standing in an unmapped or route-unreachable region). Return an
        // empty-but-current-mode update so the caller clears whatever it last
        // rendered instead of leaving stale data on screen - previously this case sent
        // nothing at all, which left the display frozen on the last mode that DID
        // produce data (almost always Wilderness, since its borrow-fallback above
        // rarely fails), making the tool look permanently stuck in that mode.
        return Optional.of(new SyncFlowFieldDebugPayload(
                lastMatchedTerritory, Map.of(), Set.of(), currentMode.ordinal()));
    }

    return Optional.empty();
```

with the pre-fix behavior:

```java
    return Optional.empty();
```

- [ ] **Step 5: Run the test again and confirm it now fails**

Run: `./gradlew runGameTestServer`
Expected: `detailedModeNeverGoesSilentInTerritory` FAILS, with the assertion message "computeDebugPayload returned empty for an in-territory position - the exact regression this test guards against" in the output. This confirms the test genuinely exercises the fix rather than passing vacuously.

- [ ] **Step 6: Revert the temporary change**

Restore the real fallback block from Step 4 (undo the edit - re-add the `if (lastMatchedTerritory != null) { ... }` block exactly as Task 1 left it).

- [ ] **Step 7: Run the test one final time and confirm it passes again**

Run: `./gradlew runGameTestServer`
Expected: `detailedModeNeverGoesSilentInTerritory` passes, 0 failures.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/gametest/DebugFlowFieldItemGameTests.java
git commit -m "test(gametest): add first GameTest, covering DebugFlowFieldReaderItem's silent-mode regression

Builds a minimal WarpFluxNetwork from the small_nexus_territory
structure, waits for its TerritoryRegionMap's first async rebuild, then
asserts computeDebugPayload never returns empty for a position inside
the network's territory - the exact bug fixed earlier (Detailed/Macro
mode going silent and appearing stuck on Wilderness mode's last
successful frame). Verified this test fails without the fix and passes
with it (see plan Task 3 steps 3-7)."
```

---

## Manual Validation (not part of task completion, informational only)

1. In a real dev world (not just the GameTest world), reproduce the original bug scenario one more time end-to-end: cycle the item to `DETAILED_NODES` while standing somewhere with no local region field, confirm the overlay now clears instead of showing stale Wilderness arrows (this was already confirmed once during the live fix - this is a final sanity check now that the logic has moved into `computeDebugPayload`).
2. Confirm `./gradlew build` (full build, not just `compileJava`) still succeeds with the new `gametest` package and structure resource included.
