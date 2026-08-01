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
- Every test method MUST call `restrictTerritoryToMinimalArea` (Task 2) immediately after
  `placeNexusAndConduit`, before spawning any rats or letting any level tick pass. GameTest's own
  encasement (side walls + a floor beneath the full territory bounding box) sits just outside the
  structure's own footprint and is a real, separately-scanned Region — `WarpFluxNetwork`'s default
  `updateTerritory` radius (2 chunks/32 blocks) reaches past it regardless of how much margin this
  test's own geometry keeps from the template edges, since these templates are only 2x2 (or, for
  `pathing_test_giant`, 4x2) chunks total. Discovered empirically during Task 2's first attempt:
  `FlowFieldCalculator`'s cycle-breaking safeguard can end up sacrificing the real, locked
  `BUILD_STAIR` instruction instead of the bogus encasement one when the scan reaches that geometry
  - precision (an exact minimal territory) fixes this at the source; margin alone cannot, and the
  stair-count secondary assertion is a check on the OUTCOME, not a substitute for avoiding the
  scan-contamination in the first place.
- Follow existing code style in `org.ratden.skavenblight.gametest`: `@GameTestHolder(Skavenblight.MODID)`
  + `@PrefixGameTestTemplate(false)` on the class, the package-private `check(boolean, String)`
  helper already defined in `PathingRegionGameTests` (imported via static import) instead of raw
  assertions, real entity construction via `new ClanratEntity(...)` + `helper.getLevel().addFreshEntity(...)`
  matching every existing GameTest in this package.

---

## File Structure

| File | Change |
|---|---|
| `src/main/resources/data/skavenblight/structure/pathing_test_giant.nbt` | New GameTest structure template, 96×64×96, generated (not hand-authored) — see Task 1. |
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
 * structure template is reusable). Builds a flat 96x64x96 platform (solid stone floor at
 * template-relative y=0, open air above, matching pathing_test_tall's own shape) at a fixed,
 * force-loaded world position, captures it, and saves it as this mod's own structure template.
 *
 * <p>96 wide/deep, not just 64: Task 2's first two attempts (see that task's own report) found
 * that a 64-wide template still left every one of the 4 GameTest methods in this plan with a gap
 * that consumed too much of the template's own width relative to a single chunk (16 blocks) -
 * whatever margin the test geometry kept from the template's edges, the region scan's own
 * territory (however precisely computed) still ended up reaching the structure's own edge, where
 * GameTest's un-suppressed side-wall encasement sits. 96 wide/deep gives every test in this plan's
 * geometry (see Tasks 2-5) at least 16 blocks (one full chunk) of margin from every edge,
 * regardless of GameTest's own non-chunk-aligned placement offset.
 */
@EventBusSubscriber(modid = Skavenblight.MODID)
public final class GenerateGiantPathingTestStructure {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int SIZE_X = 96;
    private static final int SIZE_Y = 64;
    private static final int SIZE_Z = 96;
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
at least a few KB (a 96×64×96 mostly-air structure with instructions to place thousands of stone
blocks compresses well, but should still be nontrivially sized — an empty/failed capture would be
suspiciously tiny, under 200 bytes). If this file already exists from an earlier, smaller
generation (64×64×32 — Task 1 was run once already before this size was revised upward, see this
task's own commit message below), this run's output REPLACES it; that's expected, not an error.

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
git commit -m "feat(gametest): resize pathing_test_giant to 96x64x96

Task 2's first two attempts (small-gap tests, not the chained-hop giant
gap this template was originally sized for) found that even a 64-wide
template still let the region scan's territory reach the structure's
own edge - the encasement geometry GameTest leaves un-suppressed there
(skyAccess only suppresses the roof) sits close enough that
FlowFieldCalculator's cycle-breaking safeguard could end up sacrificing
a real, locked BUILD_STAIR instruction in favor of a bogus one from that
edge geometry, regardless of how precisely territory was restricted.
96 wide/deep gives every test in this plan's geometry at least one full
chunk (16 blocks) of margin from every edge, not just a bigger radius
guess. Every macro-project line trace also still moves exactly 1 block
diagonally per step, so forcing RegionGraph's hop-chaining to engage
(past MAX_PROJECT_LENGTH=32 steps) needs the horizontal offset to match
the vertical one, not just a taller template - unchanged from the
original reasoning for this template's existence."
```

If this is a re-run overwriting an earlier, smaller `pathing_test_giant.nbt` that was already
committed, this commit will show as a modification to that same file path, not an addition - that
is expected.

---

### Task 2: Shared helpers + Test 1 (single rat, small diagonal gap)

**Files:**
- Create: `src/main/java/org/ratden/skavenblight/gametest/StaircaseSiegeGroupGameTests.java`

**Interfaces:**
- Produces (used by Tasks 3-5): `buildElevatedPlatform`, `placeNexusAndConduit` (returns
  `WarpFluxNetwork`), `restrictTerritoryToMinimalArea`, `spawnClanrats`, `countStairsInZone`,
  `awaitArrivalAndStaircase` — all private static methods on `StaircaseSiegeGroupGameTests`,
  signatures fixed below. Later tasks call these exactly as declared here, in the order shown in
  this task's own test method (`placeNexusAndConduit` then immediately
  `restrictTerritoryToMinimalArea`, before spawning any rats) — do not rename methods, change
  parameter order, or reorder those two calls relative to each other.

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
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.block.ModBlocks;
import org.ratden.skavenblight.entity.ModEntities;
import org.ratden.skavenblight.entity.custom.ClanratEntity;
import org.ratden.skavenblight.network.WarpFluxGridManager;
import org.ratden.skavenblight.network.WarpFluxNetwork;
import org.ratden.skavenblight.world.NexusTracker;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
     * Returns the resulting network so the caller can restrict its territory (see
     * {@link #restrictTerritoryToMinimalArea}) before any level tick lets it bootstrap a region
     * scan.
     */
    private static WarpFluxNetwork placeNexusAndConduit(GameTestHelper helper, BlockPos relativeNexusPos) {
        helper.setBlock(relativeNexusPos, ModBlocks.ACTIVE_WARPSTONE_NEXUS.get().defaultBlockState());
        BlockPos relativeConduitPos = relativeNexusPos.relative(Direction.EAST);
        helper.setBlock(relativeConduitPos, ModBlocks.WARP_FLUX_CONDUIT.get().defaultBlockState());

        ServerLevel level = helper.getLevel();
        BlockPos absoluteConduitPos = helper.absolutePos(relativeConduitPos);
        WarpFluxGridManager manager = WarpFluxGridManager.get(level);
        if (manager.getNetworkAt(absoluteConduitPos) == null) {
            manager.addConduit(level, absoluteConduitPos);
        }
        return manager.getNetworkAt(absoluteConduitPos);
    }

    /**
     * Overrides {@code network}'s territory (normally auto-computed by
     * {@code WarpFluxNetwork#updateTerritory} as a {@code Config.territoryChunkRadius}-chunk
     * "bubble" around its conduit/endpoints - 2 chunks/32 blocks by default) down to the EXACT
     * chunks spanning {@code relativeFrom} to {@code relativeTo}, with no extra buffer.
     *
     * <p>Discovered empirically (Task 2, first two attempts): the default radius reaches past a
     * template's own footprint into GameTest's own auto-encasement geometry (side walls + a
     * walkable ledge along their top, which {@code skyAccess = true} does NOT suppress - see
     * {@code PathingRegionGameTests}' class javadoc for the mechanism). That's a REAL,
     * separately-scanned Region purely because it's genuinely walkable terrain the region scanner
     * has no way to know is a test-framework artifact rather than intended geometry - and in that
     * geometry, {@code FlowFieldCalculator}'s cycle-breaking safeguard (which exists to stop mobs
     * looping forever on a genuine flow-field cycle) can end up choosing to drop the real, locked,
     * genuinely-needed {@code BUILD_STAIR} instruction instead of the bogus encasement-ledge one,
     * since cost alone can't tell "real" apart from "test-framework artifact".
     *
     * <p>This override alone is NOT sufficient on a template whose gap already consumes most of
     * the template's own width (confirmed the hard way on the original 32-wide, 2x2-chunk
     * `pathing_test_tall`, and again on an initial 64-wide `pathing_test_giant`): however precisely
     * the territory is computed, it still has to span the gap, and if the gap is a large fraction
     * of the template's own size, that span reaches the template's edge regardless. The real fix
     * had two parts together: (1) this precise override, so the territory is never bigger than it
     * needs to be, AND (2) a template generously larger than any single test's gap (see Task 1's
     * `pathing_test_giant`, 96x64x96, and every test's own geometry in Tasks 2-5, each kept at
     * least 16 blocks/one chunk from every template edge) - so the minimal territory this override
     * computes has genuine room to stay clear of the encasement. Neither alone was enough; both
     * together are. {@code WarpFluxNetwork#getTerritoryChunks} returns the live, mutable backing
     * set (not a defensive copy) specifically so this kind of direct test-side override is
     * possible without needing a new production API - confirmed by reading the field itself.
     *
     * <p>Must be called before the next level tick reaches {@code WarpFluxNetwork#tick}'s
     * self-healing bootstrap (i.e. immediately after {@link #placeNexusAndConduit}, synchronously,
     * within the same {@code @GameTest} method body) - the bootstrap takes a defensive
     * {@code Set.copyOf} snapshot of whatever territory is present at that moment.
     */
    private static void restrictTerritoryToMinimalArea(GameTestHelper helper, WarpFluxNetwork network,
                                                         BlockPos relativeFrom, BlockPos relativeTo) {
        ChunkPos chunkFrom = new ChunkPos(helper.absolutePos(relativeFrom));
        ChunkPos chunkTo = new ChunkPos(helper.absolutePos(relativeTo));

        int minX = Math.min(chunkFrom.x, chunkTo.x);
        int maxX = Math.max(chunkFrom.x, chunkTo.x);
        int minZ = Math.min(chunkFrom.z, chunkTo.z);
        int maxZ = Math.max(chunkFrom.z, chunkTo.z);

        Set<ChunkPos> minimalTerritory = new HashSet<>();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                minimalTerritory.add(new ChunkPos(x, z));
            }
        }

        network.getTerritoryChunks().clear();
        network.getTerritoryChunks().addAll(minimalTerritory);
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

    @GameTest(template = "pathing_test_giant", batch = BATCH, timeoutTicks = 6000, skyAccess = true)
    public static void testSingleRatBuildsStaircaseAcrossSmallGap(GameTestHelper helper) {
        NexusTracker.clearActiveNexus(helper.getLevel());

        BlockPos relativeGroundSpawn = new BlockPos(26, 2, 26);
        BlockPos relativeNexusPos = new BlockPos(40, 16, 26);

        buildElevatedPlatform(helper, relativeNexusPos, 5);
        WarpFluxNetwork network = placeNexusAndConduit(helper, relativeNexusPos);
        restrictTerritoryToMinimalArea(helper, network, relativeGroundSpawn, relativeNexusPos);

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

If the network DID form and the rebuild DID run, but the rat still walks to the base of the
platform and then never gains elevation: check the log for a `FlowFieldCalculator` line reading
"Broke flow-field cycle... dropped ... (BUILD_STAIR, locked=true)". First confirm `network` is
non-null and that `restrictTerritoryToMinimalArea` is actually called (immediately after
`placeNexusAndConduit`, same method body, before any tick passes — see that helper's own doc for
why the ordering matters). If it IS being called correctly and this still happens, compute the
dropped position's coordinates RELATIVE to this test's own structure origin (logged in the
`succeedWhen` failure message) rather than assuming the override itself is broken — if that
relative position sits at or beyond this template's own edge (0 or 95 on X/Z, for the 96-wide
`pathing_test_giant`), the actual cause is this test's OWN geometry not keeping the required
one-chunk (16-block) margin from every template edge, not the override mechanism — re-check the
exact coordinates in this task's own Step 1 code against that margin requirement before suspecting
anything else. This exact failure mode (leaked position sitting exactly at the template's own
edge) is what happened on this task's first two attempts, on two different, smaller templates.

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
shortcut.

Also restricts the network's territory to the exact chunks this test's
geometry needs (WarpFluxNetwork#getTerritoryChunks returns the live
mutable set, not a copy) instead of relying on updateTerritory's default
radius, AND keeps this test's own geometry at least one full chunk (16
blocks) from every edge of the (96x64x96) pathing_test_giant template -
found empirically, across three attempts, that neither alone is enough:
a template whose gap consumes most of its own width still reaches
GameTest's own un-suppressed side-wall encasement no matter how
precisely territory is computed, and FlowFieldCalculator's
cycle-breaking safeguard can end up sacrificing the real, locked
BUILD_STAIR instruction instead of the bogus encasement one when it
does. Both together - precise territory AND a template genuinely larger
than the gap - close the gap this hazard needs to reach through."
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
    @GameTest(template = "pathing_test_giant", batch = BATCH, timeoutTicks = 8000, skyAccess = true)
    public static void testSmallGroupBuildsStaircaseAcrossSmallGap(GameTestHelper helper) {
        NexusTracker.clearActiveNexus(helper.getLevel());

        BlockPos relativeGroundSpawn = new BlockPos(26, 2, 26);
        BlockPos relativeNexusPos = new BlockPos(40, 16, 26);

        buildElevatedPlatform(helper, relativeNexusPos, 5);
        WarpFluxNetwork network = placeNexusAndConduit(helper, relativeNexusPos);
        restrictTerritoryToMinimalArea(helper, network, relativeGroundSpawn, relativeNexusPos);

        List<ClanratEntity> rats = spawnClanrats(helper, relativeGroundSpawn, 4, 2);

        awaitArrivalAndStaircase(helper, rats, relativeNexusPos, relativeGroundSpawn, 3.0, 7);
    }
```

Identical geometry to Task 2's test (same platform, same gap, same territory restriction) — only the rat count differs (4
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
    @GameTest(template = "pathing_test_giant", batch = BATCH, timeoutTicks = 12000, skyAccess = true)
    public static void testLargeGroupBuildsStaircaseAcrossSmallGap(GameTestHelper helper) {
        NexusTracker.clearActiveNexus(helper.getLevel());

        BlockPos relativeGroundSpawn = new BlockPos(26, 2, 26);
        BlockPos relativeNexusPos = new BlockPos(40, 16, 26);

        buildElevatedPlatform(helper, relativeNexusPos, 5);
        WarpFluxNetwork network = placeNexusAndConduit(helper, relativeNexusPos);
        restrictTerritoryToMinimalArea(helper, network, relativeGroundSpawn, relativeNexusPos);

        List<ClanratEntity> rats = spawnClanrats(helper, relativeGroundSpawn, 10, 2);

        awaitArrivalAndStaircase(helper, rats, relativeNexusPos, relativeGroundSpawn, 3.0, 7);
    }
```

Same geometry again (including the same territory restriction); 10 rats spaced 2 apart along Z
starting at relative Z=26 span Z=26..44, still at least 16 blocks (one chunk) clear of both the
Z=0 edge and the Z=95 edge of the 96-deep `pathing_test_giant` template, and clear of the
platform's own Z=24..28 footprint.

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

        BlockPos relativeGroundSpawn = new BlockPos(26, 2, 26);
        BlockPos relativeNexusPos = new BlockPos(70, 46, 26);

        buildElevatedPlatform(helper, relativeNexusPos, 5);
        WarpFluxNetwork network = placeNexusAndConduit(helper, relativeNexusPos);
        restrictTerritoryToMinimalArea(helper, network, relativeGroundSpawn, relativeNexusPos);

        List<ClanratEntity> rats = spawnClanrats(helper, relativeGroundSpawn, 10, 2);

        awaitArrivalAndStaircase(helper, rats, relativeNexusPos, relativeGroundSpawn, 3.0, 20);
    }
```

Geometry: ground spawn at relative (26, 2, 26) (the same point Tasks 2-4 use), nexus platform
centered at relative (70, 46, 26) — a diagonal offset of dx=44, dy=44, dz=0, forcing at least 2
chained macro-project hops (32 + 12 steps). Margins on the 96-wide/deep `pathing_test_giant`: 26
blocks from the ground spawn to the X=0/Z=0 edges, and 23 blocks from the nexus platform's own far
edge (X=72) to the X=95 edge — both above the one-chunk (16-block) minimum every test in this file
needs (see `restrictTerritoryToMinimalArea`'s own doc for why margin alone, without also being
precise about which chunks are included, still wasn't enough on a smaller template). Same
`restrictTerritoryToMinimalArea` call as every other test in this file — no
`Config.territoryChunkRadius` mutation needed here (an earlier version of this task tried bumping
the radius instead; that alone didn't fix the underlying issue either, since the radius was never
the real lever - see the same doc).

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

If the region never connects (rats stay stranded on the ground floor forever): confirm `network` is
non-null and that `restrictTerritoryToMinimalArea` is called immediately after
`placeNexusAndConduit`, before `spawnClanrats` — same ordering requirement as every other test in
this file (see that helper's own doc for why the timing matters: the override must land before the
network's first tick reaches its self-healing bootstrap).

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
