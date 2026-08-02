# Automated Regression Tests for Tonight's Pathing/Region Fixes — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Supersedes:** `docs/superpowers/plans/2026-07-26-debug-item-gametest-plan.md` and its spec `docs/superpowers/specs/2026-07-26-debug-item-gametest-design.md`. Those documents cover the same `DebugFlowFieldReaderItem` regression (Task 3 below) but require authoring a structure NBT file via a live dev client's Structure Block — an interactive, human-in-the-loop step. This plan replaces that approach entirely with NeoForge's `testframework` module's `@EmptyTemplate` annotation, which builds the test's world state from pure code (`helper.setBlock(...)`), so the whole plan is executable with zero manual/GUI steps. The two old documents are left in place as historical record; do not execute them.

**Goal:** Lock in automated regression coverage, via NeoForge GameTests, for two of tonight's pathing/region bugfixes on branch `devJimmy_tech_gius_2`: (1) `DebugFlowFieldReaderItem`'s silent-mode bug (Detailed/Macro mode going silent and looking stuck on Wilderness mode), and (2) `TerritoryRegionMap.recomputeDirtyRegions`'s stale-`Region`-membership bug (a newly built cell never joining its region, causing siege construction to loop forever rebuilding the same spot). Both are already fixed in the current branch (commit `dbbce4f`); this plan adds the tests that prove it and guard against regressing it.

**Architecture:** Add the `net.neoforged:testframework` Gradle module (published at the same version as the pinned `neo_version`, confirmed to resolve) and bootstrap a `MutableTestFramework` in `Skavenblight`'s constructor. This unlocks `@net.neoforged.testframework.gametest.EmptyTemplate`, which registers a synthetic, all-air structure template of a given size at test-collection time (via reflection into `StructureTemplateManager`, done by the framework itself) — so a test method builds its entire scene with `ExtendedGameTestHelper.setBlock(...)` calls, no `.nbt` resource file needed. Extract `DebugFlowFieldReaderItem`'s inline decision logic into a `public static computeDebugPayload(...)` method so a GameTest can call it directly without a real `ServerPlayer`/`Connection`. Two new GameTest classes then build a minimal `WarpFluxNetwork` (one `WARPSTONE_NEXUS` + one adjacent `WARP_FLUX_CONDUIT` on a small stone platform) via a shared fixture helper, and assert against each bug's exact previously-broken behavior.

**Tech Stack:** Java 21, NeoForge 1.21.1 (`neo_version=21.1.228`), `net.minecraft.gametest.framework`, `net.neoforged.testframework.*` (new dependency), existing `network`/`ai/pathing/region`/`item/custom` packages.

## Global Constraints

- No unit test framework exists in this repo — `./gradlew runGameTestServer` (already configured in `build.gradle`) is the only automated test runner. Every task's "run the test" step is `./gradlew compileJava` and/or `./gradlew runGameTestServer`.
- The `net.neoforged:testframework:${neo_version}` dependency, the `MutableTestFramework` bootstrap in `Skavenblight`'s constructor, and a trivial `@EmptyTemplate` smoke test have already been manually verified end-to-end in this exact repo (compiled clean, `runGameTestServer` logged `Status of test 'scratch' has had status changed to [result=PASSED,message=GameTest passed]`) before this plan was written. Task 1 below reproduces that exact working code — do not deviate from the package names/method signatures given.
- `@EmptyTemplate`-annotated methods MUST be registered via `@TestHolder` (from `net.neoforged.testframework.annotation`), not the plain `@net.neoforged.neoforge.gametest.GameTestHolder`. The plain annotation does not process `@EmptyTemplate` at all (confirmed by reading `testframework`'s source — only `TestFrameworkImpl`'s own `@TestHolder` scan calls `AbstractTest.configureGameTest`, which is what actually registers the dynamic template).
- Default `Config.territoryChunkRadius` is **2** chunks (`Config.java:41`, 32 blocks) — this bounds how far from a built platform a GameTest's "in territory, but unscanned/unbuilt" test position can be while still genuinely being inside the network's territory.
- `Config.minimumSettleDelayMs` is **1000ms** real time (`Config.java:54`) and `TerritoryRegionMap.RECALC_COOLDOWN_TICKS` is **80 game ticks** (`TerritoryRegionMap.java:345`) — both gate `TerritoryRegionMap.tick`'s dirty-region recompute. Task 4's test must poll (`succeedWhen`) rather than assume a fixed tick count, and needs `timeoutTicks` generous enough to clear both gates plus async recompute time.
- Do not touch `PathingDebugFileWriter.java` — unrelated, uncommitted-elsewhere work from a prior session, out of scope.
- Follow existing code style: no comments explaining *what* code does, only non-obvious *why*; `LogUtils.getLogger()`/SLF4J is this repo's logging convention (tests here don't need it).
- This plan is scoped to two regressions, not a general test suite for the whole pathing subsystem. Do not add more tests beyond what's specified here without a new plan.

---

## File Structure

| File | Change |
|---|---|
| `build.gradle` | Add `net.neoforged:testframework` dependency. |
| `src/main/java/org/ratden/skavenblight/Skavenblight.java` | Bootstrap `MutableTestFramework` in the constructor. |
| `src/main/java/org/ratden/skavenblight/gametest/FrameworkSmokeTest.java` | New — trivial `@EmptyTemplate` test proving the framework wiring works. |
| `src/main/java/org/ratden/skavenblight/item/custom/DebugFlowFieldReaderItem.java` | Extract `computeDebugPayload`; `inventoryTick` becomes a thin wrapper. |
| `src/main/java/org/ratden/skavenblight/gametest/SkavenGameTestFixtures.java` | New — shared minimal-`WarpFluxNetwork` builder, reused by Tasks 3 and 4. |
| `src/main/java/org/ratden/skavenblight/gametest/DebugFlowFieldItemGameTests.java` | New — regression test for the silent-mode fix. |
| `src/main/java/org/ratden/skavenblight/gametest/RegionRecalculationGameTests.java` | New — regression test for the stale-membership fix. |

---

### Task 1: Bootstrap the GameTest framework and prove it end-to-end

**Files:**
- Modify: `build.gradle`
- Modify: `src/main/java/org/ratden/skavenblight/Skavenblight.java`
- Create: `src/main/java/org/ratden/skavenblight/gametest/FrameworkSmokeTest.java`

**Interfaces:**
- Produces: a live `MutableTestFramework` wired into the mod's `IEventBus`/`ModContainer`, and the working `@GameTest @EmptyTemplate @TestHolder` pattern that Tasks 3 and 4 both depend on.

**Why:** `@EmptyTemplate` (an all-air synthetic structure template built at test-collection time, letting a test build its scene purely via `helper.setBlock(...)`) only works if the mod bootstraps `net.neoforged.testframework`'s `MutableTestFramework` and registers its tests through `@TestHolder` — the plain `@net.neoforged.neoforge.gametest.GameTestHolder` never processes `@EmptyTemplate` at all. This is the foundational piece every other task builds on, so it gets its own task with its own end-to-end proof (a real `runGameTestServer` pass) before anything depends on it.

- [ ] **Step 1: Add the `testframework` dependency**

In `build.gradle`, in the `dependencies { ... }` block, add as the first line:

```groovy
dependencies {
    implementation "net.neoforged:testframework:${neo_version}"
    implementation "org.jetbrains.kotlin:kotlin-stdlib-jdk8"
    // ... existing dependencies unchanged below
```

- [ ] **Step 2: Bootstrap `MutableTestFramework` in `Skavenblight`'s constructor**

In `src/main/java/org/ratden/skavenblight/Skavenblight.java`, add these imports alongside the existing ones:

```java
import net.minecraft.resources.ResourceLocation;
import net.neoforged.testframework.conf.FrameworkConfiguration;
import net.neoforged.testframework.impl.MutableTestFramework;
```

Then, as the very first line of the constructor body (before `modEventBus.addListener(this::commonSetup);`):

```java
    public Skavenblight(IEventBus modEventBus, ModContainer modContainer) {
        MutableTestFramework testFramework = FrameworkConfiguration
                .builder(ResourceLocation.fromNamespaceAndPath(MODID, "tests"))
                .build().create();
        testFramework.init(modEventBus, modContainer);

        // Register the commonSetup method for modloading
        modEventBus.addListener(this::commonSetup);
```

- [ ] **Step 3: Write the smoke test**

Create `src/main/java/org/ratden/skavenblight/gametest/FrameworkSmokeTest.java`:

```java
package org.ratden.skavenblight.gametest;

import net.minecraft.gametest.framework.GameTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;

/** Proves the testframework bootstrap in Skavenblight's constructor actually works end-to-end. */
public class FrameworkSmokeTest {

    @GameTest
    @EmptyTemplate(value = "5x5x5", floor = true)
    @TestHolder(description = "the testframework bootstrap and @EmptyTemplate wiring work")
    static void frameworkBootstraps(final ExtendedGameTestHelper helper) {
        helper.succeed();
    }
}
```

- [ ] **Step 4: Compile check**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Run the GameTest server and confirm the smoke test passes**

Run: `./gradlew runGameTestServer`
Expected: console output includes a line like `Status of test 'frameworkBootstraps' has had status changed to [result=PASSED,message=GameTest passed]` and ends with `All 1 required tests passed :)` (or more, if run alongside later tasks' tests) and `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add build.gradle src/main/java/org/ratden/skavenblight/Skavenblight.java src/main/java/org/ratden/skavenblight/gametest/FrameworkSmokeTest.java
git commit -m "test(gametest): bootstrap net.neoforged.testframework for @EmptyTemplate support

Adds the testframework Gradle module and initializes a MutableTestFramework
in Skavenblight's constructor. This unlocks @EmptyTemplate, which builds a
GameTest's structure as a synthetic all-air template at test-collection
time instead of requiring a hand-authored structure NBT file saved via a
live dev client's Structure Block - every GameTest in this repo going
forward can build its scene purely in code via helper.setBlock(...).
Verified end-to-end with a trivial smoke test before writing any real
regression coverage on top of it."
```

---

### Task 2: Extract `computeDebugPayload` from `DebugFlowFieldReaderItem`

**Files:**
- Modify: `src/main/java/org/ratden/skavenblight/item/custom/DebugFlowFieldReaderItem.java`

**Interfaces:**
- Produces: `public static Optional<SyncFlowFieldDebugPayload> computeDebugPayload(WarpFluxGridManager gridManager, ServerLevel serverLevel, ChunkPos playerChunk, BlockPos playerPos, DebugMode currentMode)` — Task 3's GameTest calls this directly.

**Why:** This is a pure, behavior-preserving refactor of the logic currently living in `inventoryTick` — it does not change what packet gets sent in any scenario. It exists purely to make the already-fixed silent-mode decision testable without a `ServerPlayer`/`Connection` (the only helper that provides a real one, `GameTestHelper.makeMockServerPlayerInLevel()`, is `@Deprecated(forRemoval = true)`, and even then its mock `Connection`'s outbound channel has no packet-encoding pipeline configured, making packet-capture assertions unreliable).

- [ ] **Step 1: Add the `java.util.Optional` import**

In the existing import block, add alongside the other `java.util.*` imports:

```java
import java.util.Optional;
```

- [ ] **Step 2: Replace `inventoryTick`'s body with the extracted method plus a thin wrapper**

Replace the entire current `inventoryTick` method (from `@Override` through its closing brace, currently lines 154-263) with:

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

Note: `Region.getChunkCells()` is referenced above exactly as in the current file (unchanged) — check it still compiles against the current `Region` class as part of Step 3 below.

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
Connection, which the next task's GameTest relies on."
```

---

### Task 3: Shared GameTest fixture + regression test for the silent-mode fix

**Files:**
- Create: `src/main/java/org/ratden/skavenblight/gametest/SkavenGameTestFixtures.java`
- Create: `src/main/java/org/ratden/skavenblight/gametest/DebugFlowFieldItemGameTests.java`

**Interfaces:**
- Consumes: `DebugFlowFieldReaderItem.computeDebugPayload(...)` and `DebugFlowFieldReaderItem.DebugMode` from Task 2.
- Produces: `SkavenGameTestFixtures.placeMinimalNetwork(ExtendedGameTestHelper, BlockPos floorOrigin, int size)` and `SkavenGameTestFixtures.findValidNetwork(ServerLevel)` — Task 4 reuses both unchanged.

**Why:** This is the actual regression test for the bug described in the (now-superseded) spec: `DebugFlowFieldReaderItem` used to silently send no packet at all when the player's region had no field for the current mode, making the debug overlay look permanently stuck on Wilderness mode's last frame. It must fail if `computeDebugPayload`'s post-loop fallback is missing, and pass with it present.

- [ ] **Step 1: Write the shared fixture helper**

Create `src/main/java/org/ratden/skavenblight/gametest/SkavenGameTestFixtures.java`:

```java
package org.ratden.skavenblight.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;
import org.ratden.skavenblight.block.ModBlocks;
import org.ratden.skavenblight.network.WarpFluxGridManager;
import org.ratden.skavenblight.network.WarpFluxNetwork;

/** Shared world-building helpers for GameTests that need a minimal, bootstrapped WarpFluxNetwork. */
public final class SkavenGameTestFixtures {

    private SkavenGameTestFixtures() {}

    /**
     * Places a flat {@code size}x{@code size} stone floor at relative {@code floorOrigin} (y =
     * floorOrigin.getY()), then a WARPSTONE_NEXUS and an adjacent WARP_FLUX_CONDUIT one block
     * above the floor's center two cells. Nexus first, then conduit: WarpFluxGridManager.addConduit
     * (fired from WarpFluxConduitBlock.onPlace) only discovers an adjacent nexus as this network's
     * endpoint at the moment the conduit itself is placed - placing them in the other order would
     * register a network with no nexus, which WarpFluxNetwork.isValid/tick then treats as
     * permanently dormant.
     */
    public static void placeMinimalNetwork(ExtendedGameTestHelper helper, BlockPos floorOrigin, int size) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                helper.setBlock(floorOrigin.offset(x, 0, z), Blocks.STONE.defaultBlockState());
            }
        }
        BlockPos nexusPos = floorOrigin.offset(size / 2, 1, size / 2);
        helper.setBlock(nexusPos, ModBlocks.WARPSTONE_NEXUS.get().defaultBlockState());
        helper.setBlock(nexusPos.east(), ModBlocks.WARP_FLUX_CONDUIT.get().defaultBlockState());
    }

    /** The first network with a live nexus endpoint in the level, or null if none exists yet. */
    public static WarpFluxNetwork findValidNetwork(ServerLevel level) {
        return WarpFluxGridManager.get(level).getAllNetworks().stream()
                .filter(n -> n.isValid(level))
                .findFirst().orElse(null);
    }
}
```

- [ ] **Step 2: Write the regression test**

Create `src/main/java/org/ratden/skavenblight/gametest/DebugFlowFieldItemGameTests.java`:

```java
package org.ratden.skavenblight.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;
import org.ratden.skavenblight.item.custom.DebugFlowFieldReaderItem;
import org.ratden.skavenblight.network.WarpFluxGridManager;
import org.ratden.skavenblight.network.WarpFluxNetwork;
import org.ratden.skavenblight.network.payload.SyncFlowFieldDebugPayload;

import java.util.Optional;

public class DebugFlowFieldItemGameTests {

    private static final BlockPos FLOOR_ORIGIN = new BlockPos(2, 1, 2);
    private static final int FLOOR_SIZE = 5;

    @GameTest(timeoutTicks = 600)
    @EmptyTemplate("40x8x40")
    @TestHolder(description = "computeDebugPayload never returns empty for a position inside the network's territory")
    static void detailedModeNeverGoesSilentInTerritory(final ExtendedGameTestHelper helper) {
        SkavenGameTestFixtures.placeMinimalNetwork(helper, FLOOR_ORIGIN, FLOOR_SIZE);
        ServerLevel level = helper.getLevel();

        // 24 blocks past the platform, on the same Y - well inside the default 2-chunk (32 block)
        // territory radius, but never touched by placeMinimalNetwork, so it was never scanned into
        // any region. This is the exact condition that used to make computeDebugPayload return
        // Optional.empty() before the fix.
        BlockPos unmappedPos = helper.absolutePos(FLOOR_ORIGIN.offset(24, 1, 24));
        ChunkPos unmappedChunk = new ChunkPos(unmappedPos);

        helper.succeedWhen(() -> {
            WarpFluxGridManager gridManager = WarpFluxGridManager.get(level);
            WarpFluxNetwork network = SkavenGameTestFixtures.findValidNetwork(level);
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

- [ ] **Step 3: Compile check**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Run and confirm the test passes (fix is currently in place)**

Run: `./gradlew runGameTestServer`
Expected: `detailedModeNeverGoesSilentInTerritory` reported PASSED, alongside Task 1's `frameworkBootstraps` also PASSED. `All 2 required tests passed :)`.

If `unmappedPos` doesn't behave as expected (e.g. an assertion about the network/region-map never resolves within `timeoutTicks`), double-check `FLOOR_ORIGIN`/territory math against `Config.territoryChunkRadius` before changing anything else - the position must stay within 32 blocks of the nexus chunk.

- [ ] **Step 5: Temporarily reintroduce the pre-fix bug to prove the test catches it**

In `DebugFlowFieldReaderItem.computeDebugPayload` (from Task 2), temporarily replace the final two statements:

```java
        if (lastMatchedTerritory != null) {
            // ... (comment block) ...
            return Optional.of(new SyncFlowFieldDebugPayload(
                    lastMatchedTerritory, Map.of(), Set.of(), currentMode.ordinal()));
        }

        return Optional.empty();
```

with the pre-fix behavior:

```java
        return Optional.empty();
```

- [ ] **Step 6: Run the test again and confirm it now fails**

Run: `./gradlew runGameTestServer`
Expected: `detailedModeNeverGoesSilentInTerritory` FAILS, with the assertion message "computeDebugPayload returned empty for an in-territory position - the exact regression this test guards against" in the output.

- [ ] **Step 7: Revert the temporary change**

Restore the real fallback block from Step 5 exactly as Task 2 left it.

- [ ] **Step 8: Run the test one final time and confirm it passes again**

Run: `./gradlew runGameTestServer`
Expected: `detailedModeNeverGoesSilentInTerritory` passes again, alongside `frameworkBootstraps`.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/gametest/SkavenGameTestFixtures.java src/main/java/org/ratden/skavenblight/gametest/DebugFlowFieldItemGameTests.java
git commit -m "test(gametest): add regression test for DebugFlowFieldReaderItem's silent-mode fix

Builds a minimal WarpFluxNetwork from a synthetic @EmptyTemplate scene
(no structure NBT resource needed), waits for its TerritoryRegionMap's
first async rebuild, then asserts computeDebugPayload never returns
empty for a position inside the network's territory - the bug that made
Detailed/Macro mode go silent and look permanently stuck on Wilderness
mode's last frame. Verified this test fails without the fix and passes
with it (see plan Task 3 steps 5-8). Adds SkavenGameTestFixtures, a
shared minimal-network builder reused by Task 4's test."
```

---

### Task 4: Regression test for the stale-region-membership fix

**Files:**
- Create: `src/main/java/org/ratden/skavenblight/gametest/RegionRecalculationGameTests.java`

**Interfaces:**
- Consumes: `SkavenGameTestFixtures.placeMinimalNetwork`/`findValidNetwork` from Task 3; `RegionFlowField.forceRecalculation(BlockPos)`; `TerritoryRegionMap.getRegionFlowFieldFor(BlockPos)`/`isCalculating()`/`getGeneration()`/`getRegionIndex()`; `RegionIndex.regionIdAt(BlockPos)`.

**Why:** Tonight's other major fix: `TerritoryRegionMap.recomputeDirtyRegions` used to discard the rescanned `Region` object (`rescanned.get(0)`) and keep recomputing against the OLD region's stale membership forever, so a cell that became newly walkable (e.g. a rat-built pillar) never actually joined a region - the mob kept rebuilding the same spot in an endless loop even though the block placement itself succeeded. This test builds a small platform, waits for its first rebuild, extends it by one cell, calls `forceRecalculation` on the new cell (exactly what `BuildFlowFieldGoal`/`WarpSapperGoal`/`SpiralSapperGoal` do after finishing a build step), and asserts the new cell eventually resolves to a region instead of staying permanently unmapped.

- [ ] **Step 1: Write the regression test**

Create `src/main/java/org/ratden/skavenblight/gametest/RegionRecalculationGameTests.java`:

```java
package org.ratden.skavenblight.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;
import org.ratden.skavenblight.ai.pathing.region.RegionFlowField;
import org.ratden.skavenblight.ai.pathing.region.TerritoryRegionMap;
import org.ratden.skavenblight.network.WarpFluxNetwork;

public class RegionRecalculationGameTests {

    private static final BlockPos FLOOR_ORIGIN = new BlockPos(2, 1, 2);
    private static final int FLOOR_SIZE = 5;
    // A known floor cell away from the nexus/conduit (which occupy the floor's center two cells
    // and are themselves solid, so never members of any region) - used to confirm the initial
    // platform resolved to a region at all before extending it.
    private static final BlockPos INITIAL_QUERY_CELL = FLOOR_ORIGIN.offset(0, 1, 0);
    // One cell past the floor's east edge, same Y - untouched until Step "extend" below, so it's
    // not walkable terrain at all until we place a floor block there.
    private static final BlockPos NEW_FLOOR_CELL = FLOOR_ORIGIN.offset(FLOOR_SIZE, 0, 2);
    private static final BlockPos NEW_QUERY_CELL = NEW_FLOOR_CELL.above();

    @GameTest(timeoutTicks = 600)
    @EmptyTemplate("40x8x40")
    @TestHolder(description = "a cell built after the first rebuild joins its region once forceRecalculation is called on it")
    static void newlyBuiltCellJoinsRegionAfterForceRecalculation(final ExtendedGameTestHelper helper) {
        SkavenGameTestFixtures.placeMinimalNetwork(helper, FLOOR_ORIGIN, FLOOR_SIZE);
        ServerLevel level = helper.getLevel();

        BlockPos newQueryAbsolute = helper.absolutePos(NEW_QUERY_CELL);
        boolean[] extended = {false};

        helper.succeedWhen(() -> {
            WarpFluxNetwork network = SkavenGameTestFixtures.findValidNetwork(level);
            helper.assertTrue(network != null, "network never registered from the placed nexus/conduit");

            TerritoryRegionMap regionMap = network.getRegionMap();
            helper.assertTrue(!regionMap.isCalculating() && regionMap.getGeneration() > 0,
                    "region map never finished its first rebuild");

            if (!extended[0]) {
                RegionFlowField initialField = regionMap.getRegionFlowFieldFor(helper.absolutePos(INITIAL_QUERY_CELL));
                helper.assertTrue(initialField != null, "initial floor never resolved to a region");

                // Simulates a construction goal finishing a build step: place the new block, then
                // tell the region map exactly where the change happened (not this region's own
                // target - see RegionFlowField.forceRecalculation's doc on why that distinction
                // matters).
                helper.setBlock(NEW_FLOOR_CELL, Blocks.STONE.defaultBlockState());
                initialField.forceRecalculation(newQueryAbsolute);
                extended[0] = true;
                throw new GameTestAssertException(
                        "just placed the new cell and called forceRecalculation - waiting for the dirty recompute to pick it up");
            }

            helper.assertTrue(!regionMap.isCalculating(), "dirty recompute triggered by forceRecalculation still running");

            Integer regionId = regionMap.getRegionIndex().regionIdAt(newQueryAbsolute);
            helper.assertTrue(regionId != null,
                    "newly built cell was never absorbed into a region after forceRecalculation - the exact stale-membership regression this test guards against");
        });
    }
}
```

- [ ] **Step 2: Compile check**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run and confirm the test passes (fix is currently in place)**

Run: `./gradlew runGameTestServer`
Expected: `newlyBuiltCellJoinsRegionAfterForceRecalculation` PASSED, alongside both earlier tests. `All 3 required tests passed :)`.

If the test times out waiting for the region map's first rebuild or the post-extension recompute, confirm real time between the `forceRecalculation` call and the timeout comfortably exceeds `Config.minimumSettleDelayMs` (1000ms) plus `TerritoryRegionMap.RECALC_COOLDOWN_TICKS` (80 ticks, ~4s) - raise `timeoutTicks` rather than changing the production code.

- [ ] **Step 4: Temporarily reintroduce the pre-fix bug to prove the test catches it**

In `TerritoryRegionMap.recomputeDirtyRegions` (`src/main/java/org/ratden/skavenblight/ai/pathing/region/TerritoryRegionMap.java`), temporarily replace the `if (!topologyChanged)` block (currently around lines 438-478):

```java
            if (!topologyChanged) {
                // Same single region, just recompute its local field against the current route tree.
                //
                // Critical fix: ...
                Region freshRegion = rescanned.get(0).withId(regionId);
                List<Region> updatedRegions = new ArrayList<>(regionIndex.getRegions());
                updatedRegions.replaceAll(r -> r.getId() == regionId ? freshRegion : r);
                this.regionIndex = new RegionIndex(updatedRegions);

                FlowFieldState state = regionStates.get(regionId);
                if (state != null) {
                    state.updateCellFilter(freshRegion::contains);

                    RegionConnector parentConnector = routeTree != null ? routeTree.getParentConnector(regionId) : null;
                    projectManager.setActiveConnectorProject(parentConnector != null ? parentConnector.projectFor(regionId) : null);
                    calculator.calculateFully(snapshot, state);
                }
                continue;
            }
```

with the pre-fix behavior (discarding the rescan result entirely, recomputing against the OLD region's stale membership):

```java
            if (!topologyChanged) {
                FlowFieldState state = regionStates.get(regionId);
                if (state != null) {
                    RegionConnector parentConnector = routeTree != null ? routeTree.getParentConnector(regionId) : null;
                    projectManager.setActiveConnectorProject(parentConnector != null ? parentConnector.projectFor(regionId) : null);
                    calculator.calculateFully(snapshot, state);
                }
                continue;
            }
```

- [ ] **Step 5: Run the test again and confirm it now fails**

Run: `./gradlew runGameTestServer`
Expected: `newlyBuiltCellJoinsRegionAfterForceRecalculation` FAILS at `timeoutTicks`, with the assertion message "newly built cell was never absorbed into a region after forceRecalculation - the exact stale-membership regression this test guards against" in the output.

- [ ] **Step 6: Revert the temporary change**

Restore the real fix exactly as it exists in `dbbce4f` (the `Region freshRegion = ...` / `this.regionIndex = new RegionIndex(updatedRegions)` / `state.updateCellFilter(freshRegion::contains)` version from Step 4's "before" block).

- [ ] **Step 7: Run the test one final time and confirm it passes again**

Run: `./gradlew runGameTestServer`
Expected: all three tests (`frameworkBootstraps`, `detailedModeNeverGoesSilentInTerritory`, `newlyBuiltCellJoinsRegionAfterForceRecalculation`) PASSED. `All 3 required tests passed :)`.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/gametest/RegionRecalculationGameTests.java
git commit -m "test(gametest): add regression test for TerritoryRegionMap's stale-region-membership fix

Builds a small platform, waits for its first rebuild, extends it by one
cell, and calls RegionFlowField.forceRecalculation on the new cell -
exactly what BuildFlowFieldGoal/WarpSapperGoal/SpiralSapperGoal do after
finishing a build step. Asserts the new cell eventually joins a region
instead of staying permanently unmapped, which is what recomputeDirtyRegions
used to do by discarding its own rescan result and recomputing against the
OLD Region object's stale membership forever. Verified this test fails
without the fix and passes with it (see plan Task 4 steps 4-7)."
```

---

## Explicitly out of scope

- The region-graph connector-chaining fix (`docs/superpowers/plans/2026-07-25-region-graph-connector-chaining-plan.md`, already implemented and committed as `c5a4f51`/`760acba`/`b441ec4`) and the claim-race-condition fixes (session S58, sitting on a separate not-yet-merged worktree/branch per prior session notes) are NOT covered by this plan. Both are real candidates for future GameTest coverage using the same `@EmptyTemplate` pattern established here, but scoping them in was judged too large for one pass - do not expand this plan's tasks to cover them without explicit sign-off.
- `PathingDebugFileWriter`'s deep-dump export path and the `BUILD_LADDER`/`BUILD_SPIRAL` debug-rendering fix (`f512f5d`) are client-rendering/file-export concerns respectively - GameTests run server-only and can't validate either meaningfully.
- Merging the separate claim-race-condition worktree/branch into `devJimmy_tech_gius_2` - that is a merge-strategy decision flagged as awaiting explicit user instruction in a prior session; nothing in this plan touches it.

## Self-review notes

- **Spec coverage:** every regression named in the Goal (silent-mode fix, stale-membership fix) has a task with real, working code behind it (Tasks 1-2 are prerequisites; Task 3 covers the first regression; Task 4 covers the second). The framework bootstrap itself (Task 1) is proven by an actual `runGameTestServer` pass recorded during this plan's authoring, not assumed.
- **Placeholder scan:** no TBD/TODO/"add error handling"-style steps; every code block is complete and copy-pasteable.
- **Type consistency:** `SkavenGameTestFixtures.placeMinimalNetwork(ExtendedGameTestHelper, BlockPos, int)` and `findValidNetwork(ServerLevel)` are defined once in Task 3 and consumed unchanged (same signatures) in Task 4.
