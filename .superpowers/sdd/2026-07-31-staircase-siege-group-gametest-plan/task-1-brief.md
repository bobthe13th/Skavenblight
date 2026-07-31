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

