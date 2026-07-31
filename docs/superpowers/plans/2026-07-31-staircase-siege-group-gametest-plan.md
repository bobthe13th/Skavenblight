# Staircase SiegeProject Group GameTest Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a real, end-to-end GameTest suite proving a group of clanrats can discover a
diagonal gap, build a staircase across it via the real region-discovery/connector/macro-project
system, and reach a nexus — using the fully real production stack (`WarpstoneNexus`/
`WarpFluxConduit` blocks, `WarpFluxGridManager`/`WarpFluxNetwork`'s self-healing region-map
bootstrap, unmodified `ClanratEntity` goal arbitration), with no hand-fed instructions anywhere.

**Spec:** `docs/superpowers/specs/2026-07-31-staircase-siege-project-group-gametest-design.md`
(approved).

**Architecture:** One new taller-and-wider GameTest structure template (`pathing_test_giant`,
generated via this codebase's already-established temporary-listener technique), plus one new
GameTest class (`StaircaseSiegeGroupGameTests`) with four `@GameTest` methods sharing a small set
of private helpers for terrain-building, mob-spawning, and the two-part pass assertion (physical
arrival + a bounding-box stair count, so a test can't silently "pass" via some other action or an
accidental shortcut through GameTest's own territory encasement).

**Tech Stack:** Java 21, NeoForge 1.21.1 GameTest framework, existing `network/` (`WarpFluxNetwork`,
`WarpFluxGridManager`), `block/custom/` (`ActiveWarpstoneNexus`, `WarpFluxConduitBlock`), and
`entity/custom/ClanratEntity`.

## Global Constraints

- No unit test framework is used here — GameTest only. Every task's "test" step is
  `./gradlew compileJava` plus `./gradlew runGameTestServer`, checking the specific test's own
  result line in the console output.
- Every `@GameTest` method in the new class MUST set `skyAccess = true` (suppresses the barrier
  roof; side-wall/floor encasement still exists — see the terrain-margin note in each task).
- All four `@GameTest` methods MUST share the same `batch` string (`"staircase_siege_group"`), so
  vanilla GameTest's own batching guarantees they run one at a time relative to each other, never
  concurrently. This matters because placing an `ACTIVE_WARPSTONE_NEXUS` block reverts to an inert
  placeholder (no block entity) if a DIFFERENT nexus is already the world's tracked "active nexus"
  (`NexusTracker`, a world-level singleton — see `ActiveWarpstoneNexus.onPlace`) — two of these
  tests placing their own nexus concurrently would corrupt each other.
- Every test method MUST call `NexusTracker.clearActiveNexus(helper.getLevel())` as its first
  action, before placing its own nexus block — defensive isolation against whatever ran earlier in
  the same GameTest server run (this test class's own earlier-batched tests, or any other file's
  test that happens to place a nexus).
- `GameTestHelper.setBlock` may not trigger `WarpFluxConduitBlock.onPlace()`'s neighbor-scan the
  same way real block placement does. The shared conduit-placement helper (Task 2) MUST verify the
  network was actually created and, if not, call `WarpFluxGridManager.get(level).addConduit(level, pos)`
  directly as a fallback — still real production code, just invoked explicitly.
- Keep all built geometry at least 6 blocks from every template edge on every test. GameTest's own
  encasement (side walls + a floor beneath the full territory bounding box) sits just outside the
  structure's own footprint and is a real, separately-scanned Region — geometry too close to the
  edge risks the encasement providing an accidental shortcut connector, which is exactly what the
  stair-count secondary assertion (Task 2) exists to catch, but keeping margin avoids relying on
  that safety net to do all the work.
- `Config.territoryChunkRadius` (a plain `public static int` field, default 2, valid range 0-8) is
  mutable at runtime — restore it to its original value in a `finally` block wherever a task
  changes it, exactly matching the `Config.minimumSettleDelayMs` restore pattern already used in
  `PathingRegionGameTests.testRepeatedConnectorCompletionsDontExplodeRebuildCount`.
- Follow existing code style in `org.ratden.skavenblight.gametest`: `@GameTestHolder(Skavenblight.MODID)`
  + `@PrefixGameTestTemplate(false)` on the class, the package-private `check(boolean, String)`
  helper already defined in `PathingRegionGameTests` (imported via static import) instead of raw
  assertions, real entity construction via `new ClanratEntity(...)` + `helper.getLevel().addFreshEntity(...)`
  matching every existing GameTest in this package.

---

## File Structure

| File | Change |
|---|---|
| `src/main/resources/data/skavenblight/structure/pathing_test_giant.nbt` | New GameTest structure template, 64×64×32, generated (not hand-authored) — see Task 1. |
| `src/main/java/org/ratden/skavenblight/gametest/StaircaseSiegeGroupGameTests.java` | New file: shared helpers + 4 `@GameTest` methods. |

No existing files are modified.

---

### Task 1: Generate the `pathing_test_giant` structure template

**Files:**
- Create (temporary, deleted at the end of this task): `src/main/java/org/ratden/skavenblight/gametest/GenerateGiantPathingTestStructure.java`
- Create (permanent output of running the above): `src/main/resources/data/skavenblight/structure/pathing_test_giant.nbt`

**Interfaces:**
- No public API. This task's only durable output is the `.nbt` file itself.

**Why:** The staircase's large-scale test (Task 5) needs a diagonal offset past
`SiegeLineTracer.MAX_PROJECT_LENGTH` (32 steps) to force `RegionGraph.tryTrace`'s hop-chaining to
actually engage. Every macro-project line trace moves exactly 1 block diagonally per step (a fixed
direction vector, never a general slope), so forcing e.g. a 44-block vertical offset means the
horizontal offset must *also* be 44 blocks — no existing template (`pathing_test`: 32×8×32,
`pathing_test_tall`: 32×24×32) is wide OR tall enough. This follows the exact same
already-proven, headless-safe generation technique this codebase used for `pathing_test_tall`
(documented in `docs/pathing/region-pathing-hardening-findings.md`'s "Task 2"): `/test create`
doesn't work in a headless dev environment, and no shipped vanilla/NeoForge structure template is
reusable, so a temporary `ServerStartedEvent` listener builds the platform directly in a running
dedicated server and captures it.

- [ ] **Step 1: Write the temporary generator**

Create `src/main/java/org/ratden/skavenblight/gametest/GenerateGiantPathingTestStructure.java`:

```java
package org.ratden.skavenblight.gametest;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.ratden.skavenblight.Skavenblight;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * ONE-SHOT, TEMPORARY generator for pathing_test_giant.nbt - run once via `./gradlew runServer`,
 * then delete this file (see docs/pathing/region-pathing-hardening-findings.md's Task 2 for why
 * this technique exists: /test create doesn't work headless, and no shipped vanilla/NeoForge
 * structure template is reusable). Builds a flat 64x64x32 platform (solid stone floor at
 * template-relative y=0, open air above, matching pathing_test_tall's own shape) at a fixed,
 * force-loaded world position, captures it, and saves it as this mod's own structure template.
 */
@EventBusSubscriber(modid = Skavenblight.MODID)
public final class GenerateGiantPathingTestStructure {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int SIZE_X = 64;
    private static final int SIZE_Y = 64;
    private static final int SIZE_Z = 32;
    private static final BlockPos ORIGIN = new BlockPos(0, 5, 0);

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        ServerLevel level = event.getServer().overworld();

        ChunkPos minChunk = new ChunkPos(ORIGIN);
        ChunkPos maxChunk = new ChunkPos(ORIGIN.offset(SIZE_X, 0, SIZE_Z));
        for (int cx = minChunk.x; cx <= maxChunk.x; cx++) {
            for (int cz = minChunk.z; cz <= maxChunk.z; cz++) {
                level.setChunkForced(cx, cz, true);
            }
        }

        for (int x = 0; x < SIZE_X; x++) {
            for (int z = 0; z < SIZE_Z; z++) {
                level.setBlockAndUpdate(ORIGIN.offset(x, 0, z), Blocks.STONE.defaultBlockState());
                for (int y = 1; y < SIZE_Y; y++) {
                    level.setBlockAndUpdate(ORIGIN.offset(x, y, z), Blocks.AIR.defaultBlockState());
                }
            }
        }

        StructureTemplateManager templateManager = level.getStructureManager();
        StructureTemplate template = templateManager.getOrCreate(
                ResourceLocation.fromNamespaceAndPath(Skavenblight.MODID, "pathing_test_giant"));
        template.fillFromWorld(level, ORIGIN, new net.minecraft.core.Vec3i(SIZE_X, SIZE_Y, SIZE_Z),
                false, Blocks.STRUCTURE_VOID);

        Path outputDir = Path.of("src/main/resources/data/skavenblight/structure");
        try {
            Files.createDirectories(outputDir);
            Path outputFile = outputDir.resolve("pathing_test_giant.nbt");
            try (OutputStream out = Files.newOutputStream(outputFile)) {
                net.minecraft.nbt.NbtIo.writeCompressed(
                        template.save(new net.minecraft.nbt.CompoundTag()), out);
            }
            LOGGER.info("[GenerateGiantPathingTestStructure] Wrote {}", outputFile.toAbsolutePath());
        } catch (IOException e) {
            throw new RuntimeException("Failed to write pathing_test_giant.nbt", e);
        }

        for (int cx = minChunk.x; cx <= maxChunk.x; cx++) {
            for (int cz = minChunk.z; cz <= maxChunk.z; cz++) {
                level.setChunkForced(cx, cz, false);
            }
        }

        event.getServer().halt(false);
    }
}
```

- [ ] **Step 2: Run the generator**

Run: `./gradlew runServer`

The server starts, builds the platform, writes the file, logs the absolute path, and halts itself
automatically. Confirm the log line appears and the server process exits on its own (no need to
Ctrl+C).

- [ ] **Step 3: Verify the file exists and is non-trivial**

Run: check `src/main/resources/data/skavenblight/structure/pathing_test_giant.nbt` exists and is
at least a few KB (a 64×64×32 mostly-air structure with instructions to place hundreds of stone
blocks compresses well, but should still be nontrivially sized — an empty/failed capture would be
suspiciously tiny, under 200 bytes).

- [ ] **Step 4: Delete the temporary generator**

Delete `src/main/java/org/ratden/skavenblight/gametest/GenerateGiantPathingTestStructure.java` —
its only job was producing the `.nbt` file, which now exists on disk. Do not leave it registered;
running it again would just overwrite the same file with an identical result and needlessly halt
any future `runServer` invocation.

- [ ] **Step 5: Smoke-test the new template loads in a real GameTest**

Temporarily add this method to any existing file in `org.ratden.skavenblight.gametest` (e.g.
append to `PathingRegionGameTests`, then remove it again after this step confirms success — it is
scaffolding for this task only, not a permanent test):

```java
@GameTest(template = "pathing_test_giant", timeoutTicks = 100, skyAccess = true)
public static void smokeTestPathingTestGiantLoads(GameTestHelper helper) {
    helper.succeed();
}
```

Run: `./gradlew runGameTestServer`, confirm this specific test passes (no "unknown template"
error, no crash). Then remove this scaffolding method — Task 2 forward builds the real tests in
their own file.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/data/skavenblight/structure/pathing_test_giant.nbt
git commit -m "feat(gametest): generate pathing_test_giant structure template

64x64x32 flat platform, generated via the same temporary-ServerStartedEvent-
listener technique already used for pathing_test_tall (see
docs/pathing/region-pathing-hardening-findings.md's Task 2) - /test create
doesn't work headless. Wider AND taller than any existing template: every
macro-project line trace moves exactly 1 block diagonally per step, so
forcing RegionGraph's hop-chaining to engage (past MAX_PROJECT_LENGTH=32
steps) needs the horizontal offset to match the vertical one, not just a
taller template."
```

---

### Task 2: Shared helpers + Test 1 (single rat, small diagonal gap)

**Files:**
- Create: `src/main/java/org/ratden/skavenblight/gametest/StaircaseSiegeGroupGameTests.java`

**Interfaces:**
- Produces (used by Tasks 3-5): `buildElevatedPlatform`, `placeNexusAndConduit`, `spawnClanrats`,
  `countStairsInZone`, `awaitArrivalAndStaircase` — all private static methods on
  `StaircaseSiegeGroupGameTests`, signatures fixed below. Later tasks call these exactly as
  declared here; do not rename or change parameter order.

**Why:** Establishes the file, the shared terrain/spawn/assertion helpers every later test reuses,
and the simplest possible real end-to-end proof: one rat, one small diagonal gap, real region
discovery, real staircase construction, real arrival.

- [ ] **Step 1: Create the file with shared helpers and the first test**

Create `src/main/java/org/ratden/skavenblight/gametest/StaircaseSiegeGroupGameTests.java`:

```java
package org.ratden.skavenblight.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.ratden.skavenblight.Config;
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.block.ModBlocks;
import org.ratden.skavenblight.entity.ModEntities;
import org.ratden.skavenblight.entity.custom.ClanratEntity;
import org.ratden.skavenblight.network.WarpFluxGridManager;
import org.ratden.skavenblight.world.NexusTracker;

import java.util.ArrayList;
import java.util.List;

import static org.ratden.skavenblight.gametest.PathingRegionGameTests.check;

/**
 * End-to-end coverage proving a GROUP of clanrats can discover a diagonal gap, build a staircase
 * across it via the real region-discovery/connector/macro-project system, and reach a nexus - no
 * hand-fed instructions anywhere. See
 * docs/superpowers/specs/2026-07-31-staircase-siege-project-group-gametest-design.md for the full
 * design, including why the gap must be diagonal (TerrainEvaluator.determineMacroAction only
 * produces BUILD_STAIR for a diagonal direction - pure vertical produces PILLAR/SPIRAL/LADDER,
 * pure horizontal produces BRIDGE) and why this is scoped to BUILD_STAIR only (other SiegeProject
 * types are deferred to future plans reusing this same shape).
 *
 * <p>Unlike {@code SiegeConstructionActionsGameTests} (which hand-injects one instruction per
 * action and manually drives one goal instance), every test here uses the REAL production path:
 * a real {@code ActiveWarpstoneNexus} + {@code WarpFluxConduitBlock} (whose {@code onPlace} really
 * does register a real {@code WarpFluxNetwork}), real ticks (the network's own self-healing
 * bootstrap fires the first {@code TerritoryRegionMap.rebuild()} automatically), and real,
 * completely unmodified {@code ClanratEntity} mobs whose own {@code customServerAiStep()}
 * discovers the network and assigns flow fields to their own real goal list - exactly like live
 * gameplay. Nothing here calls {@code assignFlowField}/{@code setFlowField} directly.
 */
@GameTestHolder(Skavenblight.MODID)
@PrefixGameTestTemplate(false)
public class StaircaseSiegeGroupGameTests {

    private static final String BATCH = "staircase_siege_group";

    /**
     * Carves a solid floor patch {@code size}x{@code size} centered on {@code relativeCenter},
     * at Y = {@code relativeCenter.getY() - 1} (so the walkable surface is {@code relativeCenter}'s
     * own Y) - an isolated elevated platform, disconnected from anything else by the open air
     * around/below it that this template already has by default.
     */
    private static void buildElevatedPlatform(GameTestHelper helper, BlockPos relativeCenter, int size) {
        int half = size / 2;
        int floorY = relativeCenter.getY() - 1;
        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
                helper.setBlock(new BlockPos(relativeCenter.getX() + dx, floorY, relativeCenter.getZ() + dz),
                        Blocks.STONE.defaultBlockState());
            }
        }
    }

    /**
     * Places a real nexus + a real conduit against it, matching real placement exactly:
     * {@code ActiveWarpstoneNexus} first, then {@code WarpFluxConduitBlock} one block over so its
     * own {@code onPlace} finds the nexus as an adjacent {@code WARP_FLUX} capability endpoint. If
     * {@code helper.setBlock} didn't trigger that callback the same way real placement does
     * (structure-paste update flags can skip it), falls back to calling
     * {@code WarpFluxGridManager.addConduit} directly - still real production code either way.
     */
    private static void placeNexusAndConduit(GameTestHelper helper, BlockPos relativeNexusPos) {
        helper.setBlock(relativeNexusPos, ModBlocks.ACTIVE_WARPSTONE_NEXUS.get().defaultBlockState());
        BlockPos relativeConduitPos = relativeNexusPos.relative(Direction.EAST);
        helper.setBlock(relativeConduitPos, ModBlocks.WARP_FLUX_CONDUIT.get().defaultBlockState());

        ServerLevel level = helper.getLevel();
        BlockPos absoluteConduitPos = helper.absolutePos(relativeConduitPos);
        WarpFluxGridManager manager = WarpFluxGridManager.get(level);
        if (manager.getNetworkAt(absoluteConduitPos) == null) {
            manager.addConduit(level, absoluteConduitPos);
        }
    }

    /**
     * Spawns {@code count} clanrats spaced {@code spacingZ} blocks apart along Z, starting at
     * {@code relativeFirstSpawn} - purely to avoid spawn-time overlap; every rat still has to
     * funnel through the same single diagonal connector to reach the nexus regardless of where on
     * the ground floor it starts, which is exactly what the contention tests (Tasks 3-4) rely on.
     */
    private static List<ClanratEntity> spawnClanrats(GameTestHelper helper, BlockPos relativeFirstSpawn,
                                                       int count, int spacingZ) {
        List<ClanratEntity> rats = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            BlockPos relativeSpawn = relativeFirstSpawn.offset(0, 0, i * spacingZ);
            BlockPos absoluteSpawn = helper.absolutePos(relativeSpawn);
            ClanratEntity rat = new ClanratEntity(ModEntities.CLANRAT.get(), helper.getLevel());
            rat.setPos(absoluteSpawn.getX() + 0.5, absoluteSpawn.getY(), absoluteSpawn.getZ() + 0.5);
            helper.getLevel().addFreshEntity(rat);
            rats.add(rat);
        }
        return rats;
    }

    /**
     * Counts {@code Blocks.COBBLESTONE_STAIRS} within the inclusive bounding box between
     * {@code relativeFrom} and {@code relativeTo} (order-independent per axis) - the secondary
     * pass condition. "Rats arrived" alone can't distinguish a real staircase crossing from some
     * other action winning the race, or from an accidental shortcut through GameTest's own
     * territory encasement (see this file's class javadoc / the design spec's terrain-margin
     * note) - either would leave this count far below the expected diagonal step count.
     */
    private static int countStairsInZone(GameTestHelper helper, BlockPos relativeFrom, BlockPos relativeTo) {
        int minX = Math.min(relativeFrom.getX(), relativeTo.getX());
        int maxX = Math.max(relativeFrom.getX(), relativeTo.getX());
        int minY = Math.min(relativeFrom.getY(), relativeTo.getY());
        int maxY = Math.max(relativeFrom.getY(), relativeTo.getY());
        int minZ = Math.min(relativeFrom.getZ(), relativeTo.getZ());
        int maxZ = Math.max(relativeFrom.getZ(), relativeTo.getZ());

        int count = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (helper.getBlockState(new BlockPos(x, y, z)).is(Blocks.COBBLESTONE_STAIRS)) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    /**
     * The shared pass condition for every test in this file: every rat alive and within
     * {@code arrivalRadius} of {@code relativeNexusPos}, AND at least {@code minStairsExpected}
     * stair blocks exist in the bounding box between {@code relativeGroundEdge} and
     * {@code relativeNexusPos}. Registered via {@code succeedWhen}, so it's retried every tick
     * (via {@code check}'s throw-to-retry convention already established in
     * {@code PathingRegionGameTests}) until either it holds or {@code timeoutTicks} is exceeded.
     */
    private static void awaitArrivalAndStaircase(GameTestHelper helper, List<ClanratEntity> rats,
                                                  BlockPos relativeNexusPos, BlockPos relativeGroundEdge,
                                                  double arrivalRadius, int minStairsExpected) {
        BlockPos absoluteNexusPos = helper.absolutePos(relativeNexusPos);

        helper.succeedWhen(() -> {
            for (ClanratEntity rat : rats) {
                check(rat.isAlive(), "every rat must still be alive - one dying mid-crossing is a failure, not a pass");
                check(rat.blockPosition().closerThan(absoluteNexusPos, arrivalRadius),
                        rat + " has not yet arrived within " + arrivalRadius + " blocks of the nexus at "
                                + absoluteNexusPos + " (currently at " + rat.blockPosition() + ")");
            }

            int stairsFound = countStairsInZone(helper, relativeGroundEdge, relativeNexusPos);
            check(stairsFound >= minStairsExpected,
                    "expected at least " + minStairsExpected + " COBBLESTONE_STAIRS blocks between "
                            + relativeGroundEdge + " and " + relativeNexusPos + ", found " + stairsFound
                            + " - rats arrived, but not clearly via a real staircase crossing");
        });
    }

    @GameTest(template = "pathing_test_tall", batch = BATCH, timeoutTicks = 6000, skyAccess = true)
    public static void testSingleRatBuildsStaircaseAcrossSmallGap(GameTestHelper helper) {
        NexusTracker.clearActiveNexus(helper.getLevel());

        BlockPos relativeGroundSpawn = new BlockPos(6, 2, 6);
        BlockPos relativeNexusPos = new BlockPos(20, 16, 6);

        buildElevatedPlatform(helper, relativeNexusPos, 5);
        placeNexusAndConduit(helper, relativeNexusPos);

        List<ClanratEntity> rats = spawnClanrats(helper, relativeGroundSpawn, 1, 2);

        awaitArrivalAndStaircase(helper, rats, relativeNexusPos, relativeGroundSpawn, 3.0, 7);
    }
}
```

- [ ] **Step 2: Compile check**

Run: `./gradlew compileJava`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Run the test**

Run: `./gradlew runGameTestServer`

Expected: `testSingleRatBuildsStaircaseAcrossSmallGap` (under `StaircaseSiegeGroupGameTests`)
passes. Confirm no NEW failures beyond the two pre-existing, unrelated ones already documented in
the design spec's "Known risks" section (`testparentregiongetsrealinstructionsforsharedconnectorcells`
always failing, `testRepeatedConnectorCompletionsDontExplodeRebuildCount` sometimes flaky on
real-time waits).

If it times out instead of passing: check the server log for `WarpFluxNetwork`/`TerritoryRegionMap`
activity — confirm a network was created (`WarpFluxGridManager.getNetworkAt` on the conduit
position, checked via a temporary log line if needed) and that `regionMap.rebuild` actually ran.
If the network never formed, `helper.setBlock` likely didn't trigger `onPlace`'s neighbor-scan the
way this task's `placeNexusAndConduit` fallback assumed — confirm the fallback branch
(`manager.addConduit(level, absoluteConduitPos)`) is actually being reached and not silently
short-circuited by a stale `getNetworkAt` check.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/gametest/StaircaseSiegeGroupGameTests.java
git commit -m "test(pathing): group GameTest proving real staircase discovery across a gap

Existing SiegeConstructionActionsGameTests proves a single mob can
EXECUTE one hand-injected instruction correctly, but nothing proves the
system can DISCOVER a path across a gap, chain multi-step construction,
or arbitrate between goals for real. This adds the first of 4 tests
(single rat, small diagonal gap) using the fully real production stack -
a real ActiveWarpstoneNexus + WarpFluxConduitBlock, real ticks (the
network's own self-healing bootstrap triggers the first region rebuild),
and a completely unmodified ClanratEntity whose own customServerAiStep
discovers the network and assigns its flow field - exactly like live
gameplay. Pass condition is two-part: physical arrival at the nexus AND
a minimum stair-block count in the crossing zone, so the test can't
silently pass via some other action or an accidental encasement
shortcut."
```

---

### Task 3: Test 2 (small group, 4 rats, small diagonal gap)

**Files:**
- Modify: `src/main/java/org/ratden/skavenblight/gametest/StaircaseSiegeGroupGameTests.java`

**Interfaces:**
- Consumes: every helper from Task 2, unchanged.

**Why:** Proves the claim system (`RegionFlowField.tryClaimTarget`/`isTargetClaimed`) and
`AwaitFormationGoal` hold up under light contention — multiple rats converging on the same
single-file staircase, none permanently stalling, none shoving another off mid-climb.

- [ ] **Step 1: Add the test method**

Add to `StaircaseSiegeGroupGameTests` (same file, after `testSingleRatBuildsStaircaseAcrossSmallGap`):

```java
    @GameTest(template = "pathing_test_tall", batch = BATCH, timeoutTicks = 8000, skyAccess = true)
    public static void testSmallGroupBuildsStaircaseAcrossSmallGap(GameTestHelper helper) {
        NexusTracker.clearActiveNexus(helper.getLevel());

        BlockPos relativeGroundSpawn = new BlockPos(6, 2, 6);
        BlockPos relativeNexusPos = new BlockPos(20, 16, 6);

        buildElevatedPlatform(helper, relativeNexusPos, 5);
        placeNexusAndConduit(helper, relativeNexusPos);

        List<ClanratEntity> rats = spawnClanrats(helper, relativeGroundSpawn, 4, 2);

        awaitArrivalAndStaircase(helper, rats, relativeNexusPos, relativeGroundSpawn, 3.0, 7);
    }
```

Identical geometry to Task 2's test (same platform, same gap) — only the rat count differs (4
instead of 1), and the timeout is larger to give a queued-up group more real ticks to fully cross
one at a time.

- [ ] **Step 2: Compile check**

Run: `./gradlew compileJava`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Run the test**

Run: `./gradlew runGameTestServer`
Expected: `testSmallGroupBuildsStaircaseAcrossSmallGap` passes, all 4 rats end up near the nexus,
no NEW failures beyond the two pre-existing known ones.

If some rats never arrive: check whether they're permanently stalled at the staircase's base
(a claim-system deadlock) versus simply slow (still making progress) before treating this as a
real finding — increase `timeoutTicks` first to rule out "just needed more time" before suspecting
a genuine contention bug.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/gametest/StaircaseSiegeGroupGameTests.java
git commit -m "test(pathing): staircase GameTest with a small contending group (4 rats)

Same gap/geometry as the single-rat baseline - proves the claim system
and AwaitFormationGoal hold up when multiple rats converge on the same
single-file staircase at once, none permanently stalling."
```

---

### Task 4: Test 3 (large group, 10 rats, small diagonal gap)

**Files:**
- Modify: `src/main/java/org/ratden/skavenblight/gametest/StaircaseSiegeGroupGameTests.java`

**Interfaces:**
- Consumes: every helper from Task 2, unchanged.

**Why:** Stresses the same claim/queueing system harder — closer to the original bug report's
literal "a large group of rats" language — to surface a deadlock or livelock that a 4-rat group
might not trigger.

- [ ] **Step 1: Add the test method**

Add to `StaircaseSiegeGroupGameTests`:

```java
    @GameTest(template = "pathing_test_tall", batch = BATCH, timeoutTicks = 12000, skyAccess = true)
    public static void testLargeGroupBuildsStaircaseAcrossSmallGap(GameTestHelper helper) {
        NexusTracker.clearActiveNexus(helper.getLevel());

        BlockPos relativeGroundSpawn = new BlockPos(6, 2, 6);
        BlockPos relativeNexusPos = new BlockPos(20, 16, 6);

        buildElevatedPlatform(helper, relativeNexusPos, 5);
        placeNexusAndConduit(helper, relativeNexusPos);

        List<ClanratEntity> rats = spawnClanrats(helper, relativeGroundSpawn, 10, 2);

        awaitArrivalAndStaircase(helper, rats, relativeNexusPos, relativeGroundSpawn, 3.0, 7);
    }
```

Same geometry again; 10 rats spaced 2 apart along Z starting at relative Z=6 span Z=6..24, still
comfortably inside the template's 0-31 Z range and clear of the platform's own Z=4..8 footprint
margin.

- [ ] **Step 2: Compile check**

Run: `./gradlew compileJava`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Run the test**

Run: `./gradlew runGameTestServer`
Expected: `testLargeGroupBuildsStaircaseAcrossSmallGap` passes, all 10 rats arrive, no NEW failures
beyond the two pre-existing known ones.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/gametest/StaircaseSiegeGroupGameTests.java
git commit -m "test(pathing): staircase GameTest with a large contending group (10 rats)

Same gap/geometry as the earlier two tests, closer to the original bug
report's literal group size - stresses the claim/queueing system harder
to surface a deadlock/livelock a smaller group might not trigger."
```

---

### Task 5: Test 4 (large group, 10 rats, giant diagonal gap forcing chained hops)

**Files:**
- Modify: `src/main/java/org/ratden/skavenblight/gametest/StaircaseSiegeGroupGameTests.java`

**Interfaces:**
- Consumes: every helper from Task 2, unchanged, plus `pathing_test_giant` from Task 1.

**Why:** The heaviest test — a diagonal gap wide/tall enough to force `RegionGraph.tryTrace`'s own
`MAX_CHAIN_HOPS` chaining to actually engage (44-block offset > `MAX_PROJECT_LENGTH`'s 32), with
a large group, directly mirroring the *shape* of the original bug report (a long multi-segment
staircase, a large group) at a scale existing templates can't hold.

- [ ] **Step 1: Add the test method**

Add to `StaircaseSiegeGroupGameTests`:

```java
    @GameTest(template = "pathing_test_giant", batch = BATCH, timeoutTicks = 20000, skyAccess = true)
    public static void testLargeGroupBuildsChainedStaircaseAcrossGiantGap(GameTestHelper helper) {
        NexusTracker.clearActiveNexus(helper.getLevel());

        BlockPos relativeGroundSpawn = new BlockPos(6, 2, 6);
        BlockPos relativeNexusPos = new BlockPos(50, 46, 6);

        buildElevatedPlatform(helper, relativeNexusPos, 5);
        placeNexusAndConduit(helper, relativeNexusPos);

        int originalTerritoryChunkRadius = Config.territoryChunkRadius;
        Config.territoryChunkRadius = 5;
        try {
            List<ClanratEntity> rats = spawnClanrats(helper, relativeGroundSpawn, 10, 2);
            awaitArrivalAndStaircase(helper, rats, relativeNexusPos, relativeGroundSpawn, 3.0, 20);
        } finally {
            Config.territoryChunkRadius = originalTerritoryChunkRadius;
        }
    }
```

Geometry: ground spawn at relative (6, 2, 6), nexus platform centered at relative (50, 46, 6) — a
diagonal offset of dx=44, dy=44, dz=0, forcing at least 2 chained macro-project hops (32 + 12
steps). `Config.territoryChunkRadius` is temporarily raised to 5 (80 blocks) so the network's
territory comfortably covers both platforms regardless of GameTest's own non-chunk-aligned
placement offset (a 44-block span could span up to 4 chunk boundaries depending on alignment; 5
chunks of radius leaves generous margin), then restored in the `finally` block per this plan's
Global Constraints — note the restore happens whether or not `awaitArrivalAndStaircase` throws
(a timeout), matching the exact `Config.minimumSettleDelayMs` try/finally pattern already used in
`PathingRegionGameTests`.

The stairs-count threshold (20) is lower than a literal "half of 44" would suggest, because one of
the ~44 steps legitimately becomes a synthetic `BUILD_LANDING` (not a stair block) at the
hop-chaining boundary around step 32 — 20 is comfortably below the ~43 stair steps a fully-built
crossing would have, while still being far above what any accidental non-staircase shortcut could
produce.

- [ ] **Step 2: Compile check**

Run: `./gradlew compileJava`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Run the test**

Run: `./gradlew runGameTestServer`
Expected: `testLargeGroupBuildsChainedStaircaseAcrossGiantGap` passes, all 10 rats arrive at the
elevated nexus platform, no NEW failures beyond the two pre-existing known ones.

If the region never connects (rats stay stranded on the ground floor forever): check
`Config.territoryChunkRadius` actually took effect before the network's first tick (the field must
be set BEFORE `placeNexusAndConduit`/the first level tick after placement, not after — the network's
`updateTerritory` call happens synchronously inside `addConduit`, so setting the radius after
placing the conduit is too late). If needed, move the `Config.territoryChunkRadius` assignment
before `placeNexusAndConduit` instead of after, keeping the rest of the try/finally structure the
same.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/gametest/StaircaseSiegeGroupGameTests.java
git commit -m "test(pathing): staircase GameTest at giant scale, forcing chained macro-project hops

Final test of the 4: a 44-block diagonal gap (dx=dy=44) exceeds
SiegeLineTracer.MAX_PROJECT_LENGTH (32 steps), forcing RegionGraph's own
MAX_CHAIN_HOPS chaining to actually engage - a long, multi-segment
staircase discovered and built by a large group, mirroring the original
bug report's shape (not its literal ~70-block scale - see the design
spec for why exact scale isn't the property worth proving) at a size no
existing GameTest template could hold, hence pathing_test_giant (Task 1)."
```

---

## Manual Validation (not part of task completion, informational only)

1. Watch a full `./gradlew runGameTestServer` run's console output end-to-end at least once,
   confirming all four new tests report a passing result and that the two pre-existing known
   failures are the ONLY other failures in the run.
2. If any of the 4 tests is flaky (passes sometimes, times out other times) across 2-3 repeated
   `runGameTestServer` invocations, capture the specific failure mode (rats stalled vs. rats
   never discovered a field vs. staircase partially built) before concluding it's a genuine
   pathing bug versus a timing/margin issue in this plan's own chosen numbers (arrival radius,
   stair-count threshold, timeoutTicks) — the design spec's "Known risks" section already flags
   this whole test suite as likely to surface real, pre-existing region-scanning edge cases, which
   is a feature of this work, not a defect in it.
3. If Task 1's `pathing_test_giant` template ever needs regenerating (e.g. a different size is
   needed later), rewrite `GenerateGiantPathingTestStructure.java` from this task's Step 1 code,
   adjust `SIZE_X`/`SIZE_Y`/`SIZE_Z`, rerun, and delete it again afterward — it is not meant to be
   a permanent part of the codebase.
