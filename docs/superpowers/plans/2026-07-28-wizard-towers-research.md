# Wizard Towers — Per-School Research Tables & Tagged-Block Wind Influence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split the single generic Research Table into 8 per-Wind "wizard tower" tables, make
research speed scale continuously with local Wind abundance instead of a binary gate, make higher-tier
spells require proportionally more Wind to research, and give players a way to actually raise that
local Wind by decorating with themed blocks — closing the loop the original Winds of Magic design
called "Tier 0 player control" and explicitly deferred.

**Architecture:** Two independent halves that compose into one feature. **Part A** (Tasks 1-3) adds
`TaggedBlockInfluence`, a new `WindBaselineInfluence` that reads a per-chunk, per-Wind counter of
placed `#skavenblight:wind_source/<wind>`-tagged blocks — maintained incrementally on block
place/break, never rescanned — and feeds it into `WindGridManager`'s existing baseline computation
exactly like `TimeOfDayInfluence` already does. This half is fully testable on its own via the
existing `/skavendebug wind get` command, no Research Table involved. **Part B** (Tasks 4-7)
refactors `ResearchTableBlock` from one generic block into 8 Wind-locked variants (one per school),
changes the research-speed math from a hard binary gate to a continuous multiplier, and scales the
minimum-Wind requirement by spell tier. Both halves share nothing except `WindGridManager`/
`ChunkWindState`, which already exist — no new SavedData, no new Codec, no new network payload.

**Tech Stack:** Same as the rest of this mod — NeoForge 1.21.1 (`21.1.228`), Java 21, JUnit 5
(already set up, see `build.gradle`'s `sourceSets.test` + `neoForge.unitTest` blocks from the Winds
of Magic Foundation plan).

## Global Constraints

- Minecraft 1.21.1 / NeoForge `21.1.228`, Java 21, mod id `skavenblight`, base package
  `org.ratden.skavenblight`.
- New Wind-system classes go under `org.ratden.skavenblight.magic.wind` (Part A). The Research
  Table already lives under `org.ratden.skavenblight.block.custom` /
  `org.ratden.skavenblight.block.entity` / `org.ratden.skavenblight.screen` — Part B's changes stay
  in those same packages.
- Reuse existing systems, do not duplicate them: `WindGridManager`/`ChunkWindState` (per-chunk wind
  state), `Wind` enum (8 winds, `getSerializedName()`/`getColor()`/`getLoreName()`), `Spell`/
  `SpellManager` (spell catalog), `PlayerMagicData` (tier/knownSpells), `Config.java` (all new
  tunables go here, following its existing `ModConfigSpec.Builder` pattern exactly).
- Pure-math logic must stay dependency-free and unit-testable, matching this codebase's own
  established precedent (`CastingResolver` — zero Minecraft-type imports, config values passed in
  as parameters, never read from `Config` directly inside the pure function). `ResearchFormulas`
  (Task 4) follows this exact shape.
- `WindBaselineInfluence.apply(ServerLevel, ChunkPos, float[] baselineDeltaOut)` is the existing
  extension-point interface (`magic/wind/WindBaselineInfluence.java`) — `TaggedBlockInfluence` must
  implement it unchanged, and gets registered with one added line in `WindGridManager.INFLUENCES`
  (a comment already sitting there says `/* Extension point: Phase 2 tasks append BiomeInfluence,
  TaggedBlockInfluence, etc. here. */` — this plan is that Phase 2 task).
- Global block-placement/break events use `net.neoforged.neoforge.event.level.BlockEvent`
  (`EntityPlaceEvent`/`BreakEvent`) on the default game event bus via `@EventBusSubscriber`, exactly
  like the existing `event/SiegeBlockEventHandler.java` — not per-block `onPlace`/`onRemove`
  overrides, since tagged blocks must work for **any** vanilla block, not just ones this mod defines.
- No automated test framework exists for anything requiring a running game (no GameTest harness) —
  behavior that needs a live `ServerLevel`/`ChunkWindState`/`WindGridManager` is verified manually via
  `./gradlew runClient` and the existing debug commands, exactly as established throughout the whole
  Winds of Magic Foundation plan. Pure-logic pieces (Task 1, Task 4) get real JUnit tests.
- Do not touch `CastingResolver.java`, `SpellManager.java`, `SpellEffect.java`, the 16 seed spell
  JSON files, or any Modonomicon book/category/entry files — none of that changes in this plan.

---

## File Structure

```
src/main/java/org/ratden/skavenblight/magic/wind/
├── ChunkWindState.java                MODIFY — add per-Wind tagged-block counters (int[8], native NBT int-array)
├── ModWindBlockTags.java              NEW — 8 TagKey<Block>, one per Wind (#skavenblight:wind_source/<wind>)
├── TaggedBlockWindHandler.java         NEW — BlockEvent.EntityPlaceEvent/BreakEvent listener, increments/decrements counters
├── WindGridManager.java                MODIFY — one line: register TaggedBlockInfluence in INFLUENCES
└── influence/
    └── TaggedBlockInfluence.java       NEW — WindBaselineInfluence reading the counters

src/main/java/org/ratden/skavenblight/block/entity/
├── ResearchFormulas.java              NEW — pure math: tier-scaled Wind requirement, continuous speed multiplier
└── ResearchTableBlockEntity.java       MODIFY — wind field, wind-locked research, uses ResearchFormulas

src/main/java/org/ratden/skavenblight/block/custom/
└── ResearchTableBlock.java             MODIFY — gains a `Wind wind` field, constructor takes Wind

src/main/java/org/ratden/skavenblight/block/ModBlocks.java              MODIFY — 8 blocks (EnumMap<Wind, DeferredBlock<Block>>) replacing the 1 generic block
src/main/java/org/ratden/skavenblight/block/entity/ModBlockEntities.java MODIFY — 1 BlockEntityType valid for all 8 blocks
src/main/java/org/ratden/skavenblight/item/ModCreativeModeTabs.java      MODIFY — loop over the 8 blocks instead of 1
src/main/java/org/ratden/skavenblight/datagen/ModBlockLootTableProvider.java MODIFY — loop over the 8 blocks instead of 1
src/main/java/org/ratden/skavenblight/datagen/ModBlockStateProvider.java     MODIFY — loop over Wind.values() instead of 1 standardMachineBlock call
src/main/java/org/ratden/skavenblight/datagen/ModBlockTagProvider.java       MODIFY — add the 8 wind_source tags + seed vanilla blocks

src/main/java/org/ratden/skavenblight/screen/ResearchTableMenu.java      MODIFY — stillValid references the BE's own Wind-specific block
src/main/java/org/ratden/skavenblight/screen/ResearchTableScreen.java    MODIFY — drop the cross-wind filter rail entirely (each table = one Wind now)

src/main/java/org/ratden/skavenblight/Config.java                        MODIFY — windSourceBlockBonus, researchWindBonusReference, researchWindMaxBonusMultiplier; update researchWindThreshold's comment

src/main/resources/assets/skavenblight/textures/gui/research_table_gui.png   REGENERATE — wider list panel, no rail
src/main/resources/assets/skavenblight/textures/block/research_table_<wind>_{top,side,front,front_on}.png  NEW — 32 files (8 winds × 4), replacing the 4 generic ones
src/main/resources/assets/skavenblight/lang/en_us.json                   MODIFY — 8 flavorful block names replacing the 1 generic one

src/test/java/org/ratden/skavenblight/magic/wind/ChunkWindStateTest.java        MODIFY — add tagged-count tests
src/test/java/org/ratden/skavenblight/block/entity/ResearchFormulasTest.java    NEW

docs/design/research-table-ux.md          MODIFY — addendum describing the v2 formulas and per-Wind split
```

Delete as part of Task 7 (superseded by the 32 per-Wind files):
`src/main/resources/assets/skavenblight/textures/block/research_table_top.png`,
`research_table_side.png`, `research_table_front.png`, `research_table_front_on.png`.

---

### Task 1: `ChunkWindState` tagged-block counters

**Files:**
- Modify: `src/main/java/org/ratden/skavenblight/magic/wind/ChunkWindState.java`
- Test: `src/test/java/org/ratden/skavenblight/magic/wind/ChunkWindStateTest.java`

**Interfaces:**
- Produces: `ChunkWindState.getTaggedBlockCount(Wind) -> int`, `.addTaggedBlockCount(Wind, int delta)`
  (delta may be negative on block-break; floors at 0 so a mismatched break/place pair — e.g. counting
  a block that predates this feature — can never go negative). `TaggedBlockWindHandler` (Task 3) calls
  `addTaggedBlockCount`; `TaggedBlockInfluence` (Task 3) calls `getTaggedBlockCount`.

- [ ] **Step 1: Write the failing test**

Add these two `@Test` methods to the existing
`src/test/java/org/ratden/skavenblight/magic/wind/ChunkWindStateTest.java` (the file already has
`defaultsToZero`, `gettersReflectSetters`, `roundTripsThroughNbt` — add these alongside them, inside
the same class, don't remove or restructure the existing three):

```java
    @Test
    void taggedBlockCountDefaultsToZeroAndAccumulates() {
        ChunkWindState state = new ChunkWindState();
        assertEquals(0, state.getTaggedBlockCount(Wind.HYSH));

        state.addTaggedBlockCount(Wind.HYSH, 3);
        assertEquals(3, state.getTaggedBlockCount(Wind.HYSH));

        state.addTaggedBlockCount(Wind.HYSH, 2);
        assertEquals(5, state.getTaggedBlockCount(Wind.HYSH));

        state.addTaggedBlockCount(Wind.HYSH, -2);
        assertEquals(3, state.getTaggedBlockCount(Wind.HYSH));

        // floors at 0, never goes negative
        state.addTaggedBlockCount(Wind.HYSH, -100);
        assertEquals(0, state.getTaggedBlockCount(Wind.HYSH));

        // unrelated wind untouched
        assertEquals(0, state.getTaggedBlockCount(Wind.AQSHY));
    }

    @Test
    void taggedBlockCountRoundTripsThroughNbt() {
        ChunkWindState state = new ChunkWindState();
        for (Wind wind : Wind.values()) {
            state.addTaggedBlockCount(wind, wind.ordinal() + 1);
        }

        CompoundTag tag = state.save(new CompoundTag());
        ChunkWindState loaded = ChunkWindState.load(tag);

        for (Wind wind : Wind.values()) {
            assertEquals(wind.ordinal() + 1, loaded.getTaggedBlockCount(wind));
        }
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "org.ratden.skavenblight.magic.wind.ChunkWindStateTest"`
Expected: FAIL — compile error, `getTaggedBlockCount`/`addTaggedBlockCount` do not exist yet.

- [ ] **Step 3: Implement the tagged-block counter**

Edit `src/main/java/org/ratden/skavenblight/magic/wind/ChunkWindState.java`. Add a new field next to
`dharLevel`:

```java
    private final int[] taggedBlockCount = new int[Wind.values().length];
```

Add these two methods next to `getDharLevel`/`setDharLevel`:

```java
    public int getTaggedBlockCount(Wind wind) {
        return taggedBlockCount[wind.ordinal()];
    }

    public void addTaggedBlockCount(Wind wind, int delta) {
        taggedBlockCount[wind.ordinal()] = Math.max(0, taggedBlockCount[wind.ordinal()] + delta);
    }
```

Update `save`/`load` to persist it. Unlike `current`/`baseline` (floats, which needed the
`ListTag`-of-`FloatTag` workaround since `CompoundTag` has no native float-array support),
`taggedBlockCount` is an `int[]`, and `CompoundTag` DOES support int arrays natively
(`putIntArray`/`getIntArray`) — no workaround needed here:

```java
    public CompoundTag save(CompoundTag tag) {
        tag.put("current", floatArrayTag(current));
        tag.put("baseline", floatArrayTag(baseline));
        tag.putFloat("dharLevel", dharLevel);
        tag.putIntArray("taggedBlockCount", taggedBlockCount);
        return tag;
    }

    public static ChunkWindState load(CompoundTag tag) {
        ChunkWindState state = new ChunkWindState();
        readFloatArrayTag(tag, "current", state.current);
        readFloatArrayTag(tag, "baseline", state.baseline);
        state.dharLevel = tag.getFloat("dharLevel");
        if (tag.contains("taggedBlockCount")) {
            int[] counts = tag.getIntArray("taggedBlockCount");
            System.arraycopy(counts, 0, state.taggedBlockCount, 0,
                    Math.min(counts.length, state.taggedBlockCount.length));
        }
        return state;
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "org.ratden.skavenblight.magic.wind.ChunkWindStateTest"`
Expected: PASS, all 5 tests green (3 pre-existing + 2 new).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/magic/wind/ChunkWindState.java \
        src/test/java/org/ratden/skavenblight/magic/wind/ChunkWindStateTest.java
git commit -m "feat(magic): add per-Wind tagged-block counters to ChunkWindState"
```

---

### Task 2: `ModWindBlockTags` + tag datagen + config

**Files:**
- Create: `src/main/java/org/ratden/skavenblight/magic/wind/ModWindBlockTags.java`
- Modify: `src/main/java/org/ratden/skavenblight/datagen/ModBlockTagProvider.java`
- Modify: `src/main/java/org/ratden/skavenblight/Config.java`

**Interfaces:**
- Produces: `ModWindBlockTags.windSource(Wind) -> TagKey<Block>`, resolving to
  `#skavenblight:wind_source/<wind_id>`. `TaggedBlockWindHandler` (Task 3) calls this to check
  whether a placed/broken block counts for a given Wind. `Config.windSourceBlockBonus` (int, default
  15) — baseline points contributed per tagged block in a chunk.

This task is data-only for its seed content (which vanilla blocks count for which Wind) plus one new
small Java class — no automated test (tag membership is trivially correct by construction; the
seed-block choices are verified visually in Task 3's manual check).

- [ ] **Step 1: Implement `ModWindBlockTags`**

Create `src/main/java/org/ratden/skavenblight/magic/wind/ModWindBlockTags.java`:

```java
package org.ratden.skavenblight.magic.wind;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.magic.Wind;

import java.util.EnumMap;
import java.util.Map;

/**
 * Tier 0 player control (design doc §3.4): a chunk's Wind baseline rises with how many blocks
 * tagged #skavenblight:wind_source/<wind> are placed in it. One tag per Wind, so decorating a
 * tower with the right thematic blocks is itself the mechanic - no new item/block types required.
 */
public class ModWindBlockTags {

    private static final Map<Wind, TagKey<Block>> WIND_SOURCE = new EnumMap<>(Wind.class);

    static {
        for (Wind wind : Wind.values()) {
            WIND_SOURCE.put(wind, TagKey.create(Registries.BLOCK,
                    ResourceLocation.fromNamespaceAndPath(Skavenblight.MODID, "wind_source/" + wind.getSerializedName())));
        }
    }

    public static TagKey<Block> windSource(Wind wind) {
        return WIND_SOURCE.get(wind);
    }
}
```

- [ ] **Step 2: Add the 8 tags + seed blocks to datagen**

Edit `src/main/java/org/ratden/skavenblight/datagen/ModBlockTagProvider.java`. Add these imports:

```java
import net.minecraft.world.level.block.Blocks;
import org.ratden.skavenblight.magic.Wind;
import org.ratden.skavenblight.magic.wind.ModWindBlockTags;
```

Add this at the end of `addTags`, after the existing `tag(BlockTags.WALLS)...` line:

```java
        tag(ModWindBlockTags.windSource(Wind.HYSH))
                .add(Blocks.GLOWSTONE, Blocks.SEA_LANTERN, Blocks.BEACON, Blocks.END_ROD);
        tag(ModWindBlockTags.windSource(Wind.AZYR))
                .add(Blocks.LAPIS_BLOCK, Blocks.AMETHYST_BLOCK, Blocks.CONDUIT, Blocks.LODESTONE);
        tag(ModWindBlockTags.windSource(Wind.CHAMON))
                .add(Blocks.IRON_BLOCK, Blocks.GOLD_BLOCK, Blocks.COPPER_BLOCK, Blocks.NETHERITE_BLOCK);
        tag(ModWindBlockTags.windSource(Wind.GHYRAN))
                .add(Blocks.MOSS_BLOCK, Blocks.FLOWERING_AZALEA, Blocks.BEEHIVE, Blocks.COMPOSTER);
        tag(ModWindBlockTags.windSource(Wind.AQSHY))
                .add(Blocks.MAGMA_BLOCK, Blocks.NETHERRACK, Blocks.CAMPFIRE, Blocks.BLAST_FURNACE);
        tag(ModWindBlockTags.windSource(Wind.GHUR))
                .add(Blocks.HAY_BLOCK, Blocks.COBWEB, Blocks.MUD, Blocks.ROOTED_DIRT);
        tag(ModWindBlockTags.windSource(Wind.ULGU))
                .add(Blocks.OBSIDIAN, Blocks.SOUL_SAND, Blocks.BLACK_CONCRETE, Blocks.ENDER_CHEST);
        tag(ModWindBlockTags.windSource(Wind.SHYISH))
                .add(Blocks.SOUL_SOIL, Blocks.WITHER_ROSE, Blocks.CRYING_OBSIDIAN, Blocks.SKELETON_SKULL);
```

- [ ] **Step 3: Add the config value**

Edit `src/main/java/org/ratden/skavenblight/Config.java`. Add this field right after the
`RESEARCH_WIND_THRESHOLD` field (inside the existing `// --- Research Table Configs ---` block is
fine, or its own `// --- Wind Influence Configs ---` block right after — use the latter, since this
config is a Wind-system tunable, not specifically a Research Table one):

```java
    // --- Wind Influence Configs ---
    private static final ModConfigSpec.IntValue WIND_SOURCE_BLOCK_BONUS = BUILDER.comment("Baseline bonus per tagged wind_source block placed in a chunk (see TaggedBlockInfluence).")
            .defineInRange("windSourceBlockBonus", 15, 0, 1000);
```

Add the public variable, next to the other `// --- Research Table Public Variables ---` (again, own
section is clearer):

```java
    // --- Wind Influence Public Variables ---
    public static int windSourceBlockBonus;
```

Add the load line inside `onLoad`, right after the Research Table config loads:

```java
        // Load Wind Influence Configs
        windSourceBlockBonus = WIND_SOURCE_BLOCK_BONUS.get();
```

- [ ] **Step 4: Compile and run datagen**

Run: `./gradlew compileJava`
Expected: `BUILD SUCCESSFUL`.

Run: `./gradlew runData`
Expected: `BUILD SUCCESSFUL`. Confirm the new tag files exist:
`ls src/generated/resources/data/skavenblight/tags/blocks/wind_source/` should list 8 files
(`hysh.json` ... `shyish.json`), each containing the 4 vanilla block ids from Step 2.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/magic/wind/ModWindBlockTags.java \
        src/main/java/org/ratden/skavenblight/datagen/ModBlockTagProvider.java \
        src/main/java/org/ratden/skavenblight/Config.java
git commit -m "feat(magic): add wind_source block tags (8 winds, 4 seed blocks each) + bonus config"
```

---

### Task 3: `TaggedBlockInfluence` + event handler + wiring

**Files:**
- Create: `src/main/java/org/ratden/skavenblight/magic/wind/influence/TaggedBlockInfluence.java`
- Create: `src/main/java/org/ratden/skavenblight/magic/wind/TaggedBlockWindHandler.java`
- Modify: `src/main/java/org/ratden/skavenblight/magic/wind/WindGridManager.java`

**Interfaces:**
- Consumes: `ChunkWindState.getTaggedBlockCount`/`.addTaggedBlockCount` (Task 1),
  `ModWindBlockTags.windSource` (Task 2), `Config.windSourceBlockBonus` (Task 2).
- Produces: Part A's complete, testable deliverable — placing/breaking tagged blocks now measurably
  moves `/skavendebug wind get`'s baseline numbers for the matching Wind.

No automated test — this is the same "no GameTest harness, verify via debug command" situation as
`WindGridManager`/`TimeOfDayInfluence` in the original Winds of Magic Foundation plan.

- [ ] **Step 1: Implement `TaggedBlockInfluence`**

Create `src/main/java/org/ratden/skavenblight/magic/wind/influence/TaggedBlockInfluence.java`:

```java
package org.ratden.skavenblight.magic.wind.influence;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.ratden.skavenblight.Config;
import org.ratden.skavenblight.magic.Wind;
import org.ratden.skavenblight.magic.wind.ChunkWindState;
import org.ratden.skavenblight.magic.wind.WindBaselineInfluence;
import org.ratden.skavenblight.magic.wind.WindGridManager;

/**
 * Tier 0 player control (design doc §3.4): decorating a chunk with tagged mundane blocks
 * (#skavenblight:wind_source/<wind>) raises that Wind's baseline. The count itself is maintained
 * incrementally by TaggedBlockWindHandler on block place/break - this class only reads it, it
 * never rescans the chunk.
 */
public class TaggedBlockInfluence implements WindBaselineInfluence {

    @Override
    public void apply(ServerLevel level, ChunkPos pos, float[] baselineDeltaOut) {
        ChunkWindState state = WindGridManager.get(level).getOrCreate(pos);
        for (Wind wind : Wind.values()) {
            baselineDeltaOut[wind.ordinal()] += state.getTaggedBlockCount(wind) * Config.windSourceBlockBonus;
        }
    }
}
```

- [ ] **Step 2: Implement the place/break event handler**

Create `src/main/java/org/ratden/skavenblight/magic/wind/TaggedBlockWindHandler.java`:

```java
package org.ratden.skavenblight.magic.wind;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.magic.Wind;

/**
 * Maintains ChunkWindState's tagged-block counters incrementally (+1 on place, -1 on break) rather
 * than ever rescanning a chunk - the design doc is explicit that this is the perf-safe approach.
 * Listens on the default game event bus so it fires for ANY block carrying a wind_source tag,
 * including plain vanilla blocks, not just ones this mod defines (see WarpFluxConduitBlock's
 * onPlace/onRemove for the alternative per-block-class pattern this deliberately does NOT use).
 */
@EventBusSubscriber(modid = Skavenblight.MODID)
public class TaggedBlockWindHandler {

    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        adjustTaggedCounts(serverLevel, event.getPos(), event.getPlacedBlock(), 1);
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        adjustTaggedCounts(serverLevel, event.getPos(), event.getState(), -1);
    }

    private static void adjustTaggedCounts(ServerLevel level, BlockPos pos, BlockState state, int delta) {
        ChunkWindState windState = WindGridManager.get(level).getOrCreate(new ChunkPos(pos));
        for (Wind wind : Wind.values()) {
            if (state.is(ModWindBlockTags.windSource(wind))) {
                windState.addTaggedBlockCount(wind, delta);
            }
        }
    }
}
```

- [ ] **Step 3: Register the influence**

Edit `src/main/java/org/ratden/skavenblight/magic/wind/WindGridManager.java`. Add the import:

```java
import org.ratden.skavenblight.magic.wind.influence.TaggedBlockInfluence;
```

Change the static initializer (currently just adds `TimeOfDayInfluence`) to also add the new one:

```java
    private static final List<WindBaselineInfluence> INFLUENCES = new ArrayList<>();
    static {
        INFLUENCES.add(new TimeOfDayInfluence());
        INFLUENCES.add(new TaggedBlockInfluence());
    }
```

- [ ] **Step 4: Compile**

Run: `./gradlew compileJava`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Manual verification — decorate a chunk and watch the baseline rise**

Run: `./gradlew runClient`. In a single-player creative world:

1. Run `/skavendebug wind get`. Note Hysh's current baseline (likely ~150 or 0 depending on
   time of day, per `TimeOfDayInfluence`).
2. Place a Sea Lantern, a Glowstone block, and a Beacon (or just 3+ Glowstone if you don't have
   a Beacon handy) in your current chunk.
3. Run `/skavendebug wind get` again. Expected: Hysh's `baseline` increased by
   `(number of tagged blocks placed) * 15` (the default `windSourceBlockBonus`) on top of whatever
   `TimeOfDayInfluence` was already contributing — e.g. 3 blocks × 15 = +45.
4. Break one of them. Run `/skavendebug wind get` again. Expected: Hysh's baseline drops by 15
   (back down by one block's worth), confirming the break-side decrement works too.
5. Place a block from a *different* Wind's tag (e.g. an Iron Block, tagged for Chamon) in the same
   chunk. Confirm Chamon's baseline rises while Hysh's is unaffected — tags are independent per Wind.

If any of this doesn't happen, debug via `TaggedBlockWindHandler`/`ChunkWindState` before moving on
to Task 4 — Part B's tower-boost fantasy depends on this working correctly.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/magic/wind/influence/TaggedBlockInfluence.java \
        src/main/java/org/ratden/skavenblight/magic/wind/TaggedBlockWindHandler.java \
        src/main/java/org/ratden/skavenblight/magic/wind/WindGridManager.java
git commit -m "feat(magic): wire TaggedBlockInfluence into the wind baseline computation"
```

---

### Task 4: `ResearchFormulas` — tier-scaled requirement + continuous wind bonus

**Files:**
- Create: `src/main/java/org/ratden/skavenblight/block/entity/ResearchFormulas.java`
- Test: `src/test/java/org/ratden/skavenblight/block/entity/ResearchFormulasTest.java`
- Modify: `src/main/java/org/ratden/skavenblight/block/entity/ResearchTableBlockEntity.java`
- Modify: `src/main/java/org/ratden/skavenblight/Config.java`
- Modify: `docs/design/research-table-ux.md`

**Interfaces:**
- Produces: `ResearchFormulas.requiredWindLevel(int tier, int baseThreshold) -> float`,
  `ResearchFormulas.speedMultiplier(float current, float required, int bonusReference, double
  maxBonusMultiplier) -> float`. Both are pure functions — no `Config`, no Minecraft types — matching
  `CastingResolver`'s already-proven "pass config values in as parameters" shape so they stay
  trivially unit-testable without any Bootstrap/registry concerns. `ResearchTableBlockEntity.tick`/
  `.tryStartResearch` (this task, Step 5) are the only callers, always passing `Config.*` values in
  from the call site.

This task deliberately changes the *math* on the existing single generic Research Table before Task
5 multiplies it into 8 blocks — so the formula is provably correct in isolation first.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/ratden/skavenblight/block/entity/ResearchFormulasTest.java`:

```java
package org.ratden.skavenblight.block.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ResearchFormulasTest {

    @Test
    void requiredWindLevelScalesWithTier() {
        assertEquals(200f, ResearchFormulas.requiredWindLevel(0, 200));
        assertEquals(400f, ResearchFormulas.requiredWindLevel(1, 200));
        assertEquals(600f, ResearchFormulas.requiredWindLevel(2, 200));
        assertEquals(800f, ResearchFormulas.requiredWindLevel(3, 200));
    }

    @Test
    void speedMultiplierIsOneAtExactlyTheRequirement() {
        assertEquals(1.0f, ResearchFormulas.speedMultiplier(200f, 200f, 300, 2.0), 0.001f);
    }

    @Test
    void speedMultiplierIsOneBelowTheRequirement() {
        // The function itself never goes below 1.0x - the caller is responsible for gating
        // "should this even be progressing" separately (current >= required) before applying this.
        assertEquals(1.0f, ResearchFormulas.speedMultiplier(50f, 200f, 300, 2.0), 0.001f);
    }

    @Test
    void speedMultiplierScalesWithExcessWind() {
        // 150 excess / 300 reference = 0.5 bonus -> 1.5x total
        assertEquals(1.5f, ResearchFormulas.speedMultiplier(350f, 200f, 300, 2.0), 0.001f);
        // 300 excess / 300 reference = 1.0 bonus -> 2.0x total
        assertEquals(2.0f, ResearchFormulas.speedMultiplier(500f, 200f, 300, 2.0), 0.001f);
    }

    @Test
    void speedMultiplierCapsAtMaxBonus() {
        // huge excess wind, but the bonus portion is capped at +2.0 -> 3.0x total, never more
        assertEquals(3.0f, ResearchFormulas.speedMultiplier(100000f, 200f, 300, 2.0), 0.001f);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "org.ratden.skavenblight.block.entity.ResearchFormulasTest"`
Expected: FAIL — `ResearchFormulas` does not exist.

- [ ] **Step 3: Implement `ResearchFormulas`**

Create `src/main/java/org/ratden/skavenblight/block/entity/ResearchFormulas.java`:

```java
package org.ratden.skavenblight.block.entity;

/**
 * Pure Research Table math, kept dependency-free (no Minecraft/BlockEntity types, no Config
 * reads) so it's trivially unit-testable - same reasoning as
 * org.ratden.skavenblight.magic.spell.CastingResolver. Callers pass Config.* values in explicitly.
 */
public final class ResearchFormulas {

    private ResearchFormulas() {}

    /**
     * Minimum local Wind level required to research a spell of the given tier: each tier above 0
     * requires that much more Wind, so higher-tier spells demand a stronger local presence of
     * their Wind, not just the same flat bar every tier used before.
     */
    public static float requiredWindLevel(int tier, int baseThreshold) {
        return baseThreshold * (1 + tier);
    }

    /**
     * Research speed multiplier once the local Wind meets the requirement: every bonusReference
     * points of *excess* wind above the requirement adds another 1.0x, capped at
     * maxBonusMultiplier extra. Never returns less than 1.0x - callers are responsible for
     * deciding separately whether research should be progressing at all (current >= required).
     */
    public static float speedMultiplier(float current, float required, int bonusReference, double maxBonusMultiplier) {
        float excess = Math.max(0f, current - required);
        double bonus = Math.min(maxBonusMultiplier, excess / bonusReference);
        return 1f + (float) bonus;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "org.ratden.skavenblight.block.entity.ResearchFormulasTest"`
Expected: PASS, 5 tests green.

- [ ] **Step 5: Wire it into `ResearchTableBlockEntity`**

Edit `src/main/java/org/ratden/skavenblight/block/entity/ResearchTableBlockEntity.java`.

Delete the existing `requiredWindLevel(Spell)` static method entirely (currently lines 67-72):

```java
    /** Flat Wind-level threshold a spell's Wind must meet locally to be researched. Phase 1 keeps
     *  this the same for every spell; per-spell scaling (e.g. by casting number) is a natural
     *  later refinement once there's more than one duration/difficulty worth distinguishing. */
    public static float requiredWindLevel(Spell spell) {
        return Config.researchWindThreshold;
    }
```

Replace the `tick` method's body (currently lines 74-105) with:

```java
    public static void tick(Level level, BlockPos pos, BlockState state, ResearchTableBlockEntity be) {
        if (level.isClientSide || !(level instanceof ServerLevel serverLevel)) {
            return;
        }

        if (be.targetSpellId != null) {
            Spell spell = SpellManager.get(be.targetSpellId);
            ServerPlayer researcher = be.researchingPlayer != null
                    ? serverLevel.getServer().getPlayerList().getPlayer(be.researchingPlayer)
                    : null;

            boolean shouldProgress = false;
            int increment = 1;
            if (spell != null && researcher != null) {
                float current = WindGridManager.get(serverLevel).getOrCreate(new ChunkPos(pos)).getCurrent(spell.wind());
                float required = ResearchFormulas.requiredWindLevel(spell.tier(), Config.researchWindThreshold);
                shouldProgress = current >= required;
                if (shouldProgress) {
                    float multiplier = ResearchFormulas.speedMultiplier(current, required,
                            Config.researchWindBonusReference, Config.researchWindMaxBonusMultiplier);
                    increment = Math.max(1, Math.round(multiplier));
                }
            }

            if (shouldProgress) {
                be.progress += increment;
                if (be.progress >= Config.researchTicksBase) {
                    be.completeResearch(researcher, spell);
                }
            } else if (be.progress > 0) {
                be.progress = Math.max(0, be.progress - 2);
            }
            be.setChanged();
        }

        boolean isLit = state.getValue(ResearchTableBlock.LIT);
        boolean shouldBeLit = be.targetSpellId != null;
        if (isLit != shouldBeLit) {
            level.setBlock(pos, state.setValue(ResearchTableBlock.LIT, shouldBeLit), 3);
        }
    }
```

In `tryStartResearch`, replace this line (currently line 136):

```java
        if (windState.getCurrent(spell.wind()) < requiredWindLevel(spell)) {
```

with:

```java
        if (windState.getCurrent(spell.wind()) < ResearchFormulas.requiredWindLevel(spell.tier(), Config.researchWindThreshold)) {
```

- [ ] **Step 6: Add the two new config values**

Edit `src/main/java/org/ratden/skavenblight/Config.java`. Update the existing
`RESEARCH_WIND_THRESHOLD` field's comment to reflect the new tier-scaling semantics (the field name,
default, and range all stay the same — only the comment string changes):

```java
    private static final ModConfigSpec.IntValue RESEARCH_WIND_THRESHOLD = BUILDER.comment("Base minimum local Wind level required to research a tier-0 spell at the Research Table; each additional tier requires this much more (tier 2 needs 3x this value).")
            .defineInRange("researchWindThreshold", 200, 0, 10000);
```

Add two new fields right after it, still inside the `// --- Research Table Configs ---` block:

```java
    private static final ModConfigSpec.IntValue RESEARCH_WIND_BONUS_REFERENCE = BUILDER.comment("Extra local Wind (above the research requirement) needed to add +1.0x to research speed.")
            .defineInRange("researchWindBonusReference", 300, 1, 100000);
    private static final ModConfigSpec.DoubleValue RESEARCH_WIND_MAX_BONUS_MULTIPLIER = BUILDER.comment("Cap on the speed bonus from abundant Wind - e.g. 2.0 means research can run up to 3x base speed (1x base + up to 2x bonus).")
            .defineInRange("researchWindMaxBonusMultiplier", 2.0, 0.0, 50.0);
```

Add the public variables, next to `researchWindThreshold`:

```java
    public static int researchWindBonusReference;
    public static double researchWindMaxBonusMultiplier;
```

Add the load lines inside `onLoad`, right after `researchWindThreshold = RESEARCH_WIND_THRESHOLD.get();`:

```java
        researchWindBonusReference = RESEARCH_WIND_BONUS_REFERENCE.get();
        researchWindMaxBonusMultiplier = RESEARCH_WIND_MAX_BONUS_MULTIPLIER.get();
```

- [ ] **Step 7: Update the design doc addendum**

Append this section to the end of `docs/design/research-table-ux.md`:

```markdown
## Addendum: v2 — per-school tables, tier-scaled requirement, continuous wind bonus

See `docs/superpowers/plans/2026-07-28-wizard-towers-research.md` for the full plan. Summary of
what changed from the v1 design above:

- One Research Table per Wind (8 total) instead of one generic table — each is permanently locked
  to its own Wind's spell list; `ResearchTableBlock` gained a `Wind wind` field.
- The Wind-level requirement to research a spell now scales with the spell's tier
  (`ResearchFormulas.requiredWindLevel`): tier 0 needs `researchWindThreshold`, tier 2 needs 3x
  that. Higher-tier spells demand a stronger local presence of their Wind, not just the same flat
  bar every tier used before.
- Research speed is no longer a binary gate — once the requirement is met, *excess* Wind above it
  grants a continuous speed multiplier (`ResearchFormulas.speedMultiplier`), capped by
  `researchWindMaxBonusMultiplier`. This is the direct mechanical payoff for building a "wizard
  tower": decorating the chunk with `#skavenblight:wind_source/<wind>`-tagged blocks
  (`TaggedBlockInfluence`) raises the local Wind, which both unlocks higher-tier research and makes
  all research in that tower faster.
```

- [ ] **Step 8: Run the full test suite and compile**

Run: `./gradlew test`
Expected: `BUILD SUCCESSFUL`, all tests pass (5 pre-existing wind/spell/player tests + 5 new
`ChunkWindStateTest` + 5 new `ResearchFormulasTest` = confirm the total count printed matches
16 (from before this plan) + 2 (Task 1) + 5 (Task 4) = 23).

Run: `./gradlew compileJava`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/block/entity/ResearchFormulas.java \
        src/test/java/org/ratden/skavenblight/block/entity/ResearchFormulasTest.java \
        src/main/java/org/ratden/skavenblight/block/entity/ResearchTableBlockEntity.java \
        src/main/java/org/ratden/skavenblight/Config.java \
        docs/design/research-table-ux.md
git commit -m "feat(research-table): tier-scaled wind requirement + continuous wind speed bonus"
```

---

### Task 5: Per-Wind Research Table blocks

**Files:**
- Modify: `src/main/java/org/ratden/skavenblight/block/custom/ResearchTableBlock.java`
- Modify: `src/main/java/org/ratden/skavenblight/block/entity/ResearchTableBlockEntity.java`
- Modify: `src/main/java/org/ratden/skavenblight/block/ModBlocks.java`
- Modify: `src/main/java/org/ratden/skavenblight/block/entity/ModBlockEntities.java`

**Interfaces:**
- Produces: `ResearchTableBlock.wind` (public final field), `ResearchTableBlockEntity.wind` (public
  final field, derived from the block). `ModBlocks.RESEARCH_TABLES` (`Map<Wind, DeferredBlock<Block>>`,
  replacing the old single `ModBlocks.RESEARCH_TABLE` constant — Task 6/7 read from this map).
  `ModBlockEntities.RESEARCH_TABLE` (unchanged name, but now `.build(null)` lists all 8 blocks as
  valid, since one `BlockEntityType`/`ResearchTableBlockEntity` class instance now serves all 8
  Wind-specific blocks — the Wind itself lives on the *block instance*, not the block entity type).

No automated test — block/BlockEntity registration wiring, verified by compiling and the manual
check at the end of Task 7 (placing one of each of the 8 blocks).

- [ ] **Step 1: Add a `Wind` field to `ResearchTableBlock`**

Edit `src/main/java/org/ratden/skavenblight/block/custom/ResearchTableBlock.java`. Add the import:

```java
import org.ratden.skavenblight.magic.Wind;
```

Change the class body: add a field, and change the constructor to take a `Wind`:

```java
    public final Wind wind;

    public ResearchTableBlock(Wind wind, Properties properties) {
        super(properties);
        this.wind = wind;
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(LIT, false));
    }
```

(This replaces the old `public ResearchTableBlock(Properties properties) { ... }` constructor —
delete the old one, this is the only constructor now.)

- [ ] **Step 2: Derive the BlockEntity's `Wind` from the block instance, and lock research to it**

Edit `src/main/java/org/ratden/skavenblight/block/entity/ResearchTableBlockEntity.java`.

Add a field and change the constructor (currently lines 54-56):

```java
    public final Wind wind;

    public ResearchTableBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.RESEARCH_TABLE.get(), pos, state);
        if (!(state.getBlock() instanceof ResearchTableBlock table)) {
            throw new IllegalStateException("ResearchTableBlockEntity created for a non-ResearchTableBlock state: " + state);
        }
        this.wind = table.wind;
    }
```

In `tryStartResearch`, add a Wind check right after the existing `if (spell == null) { return false; }`
check (so a player can never start researching a spell that doesn't belong to this specific tower's
school):

```java
        Spell spell = SpellManager.get(spellId);
        if (spell == null || spell.wind() != wind) {
            return false;
        }
```

(This replaces the old `if (spell == null) { return false; }` two-line block with the combined
check above — delete the old one.)

Update `getDisplayName` (currently returns the single generic key) to be Wind-specific:

```java
    @Override
    public Component getDisplayName() {
        return Component.translatable("block.skavenblight.research_table_" + wind.getSerializedName());
    }
```

- [ ] **Step 3: Replace the single block registration with 8, one per Wind**

Edit `src/main/java/org/ratden/skavenblight/block/ModBlocks.java`. Add imports:

```java
import org.ratden.skavenblight.magic.Wind;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
```

Delete the existing single registration:

```java
    public static final DeferredBlock<Block> RESEARCH_TABLE = registerBlock("research_table",
            () -> new ResearchTableBlock(BlockBehaviour.Properties.of()
                    .strength(2.5f)
                    .sound(SoundType.STONE)
                    .lightLevel(state -> state.getValue(ResearchTableBlock.LIT) ? 10 : 0)
            ));
```

Replace it with:

```java
    public static final Map<Wind, DeferredBlock<Block>> RESEARCH_TABLES = registerResearchTables();

    private static Map<Wind, DeferredBlock<Block>> registerResearchTables() {
        Map<Wind, DeferredBlock<Block>> map = new EnumMap<>(Wind.class);
        for (Wind wind : Wind.values()) {
            DeferredBlock<Block> table = registerBlock("research_table_" + wind.getSerializedName(),
                    () -> new ResearchTableBlock(wind, BlockBehaviour.Properties.of()
                            .strength(2.5f)
                            .sound(SoundType.STONE)
                            .lightLevel(state -> state.getValue(ResearchTableBlock.LIT) ? 10 : 0)
                    ));
            map.put(wind, table);
        }
        return Collections.unmodifiableMap(map);
    }
```

- [ ] **Step 4: Point the shared BlockEntityType at all 8 blocks**

Edit `src/main/java/org/ratden/skavenblight/block/entity/ModBlockEntities.java`. Add imports:

```java
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.registries.DeferredBlock;
```

Replace the existing `RESEARCH_TABLE` registration:

```java
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ResearchTableBlockEntity>> RESEARCH_TABLE =
            BLOCK_ENTITIES.register("research_table", () ->
                    BlockEntityType.Builder.of(ResearchTableBlockEntity::new,
                            ModBlocks.RESEARCH_TABLE.get()).build(null));
```

with:

```java
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ResearchTableBlockEntity>> RESEARCH_TABLE =
            BLOCK_ENTITIES.register("research_table", () ->
                    BlockEntityType.Builder.of(ResearchTableBlockEntity::new,
                            ModBlocks.RESEARCH_TABLES.values().stream()
                                    .map(DeferredBlock::get)
                                    .toArray(Block[]::new)
                    ).build(null));
```

- [ ] **Step 5: Compile**

Run: `./gradlew compileJava`
Expected: compile errors at this point are expected and OK to see, from the other files that still
reference the now-deleted `ModBlocks.RESEARCH_TABLE` singular constant
(`ResearchTableMenu.stillValid`, `ModCreativeModeTabs`, `ModBlockLootTableProvider`,
`ModBlockStateProvider`) — Task 6 and Task 7 fix those. Confirm the errors are *only* about
`ModBlocks.RESEARCH_TABLE` not existing (a "cannot find symbol" pointing at that exact name in those
4 files), not anything else — if you see a different kind of error, stop and report it rather than
proceeding into Task 6.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/block/custom/ResearchTableBlock.java \
        src/main/java/org/ratden/skavenblight/block/entity/ResearchTableBlockEntity.java \
        src/main/java/org/ratden/skavenblight/block/ModBlocks.java \
        src/main/java/org/ratden/skavenblight/block/entity/ModBlockEntities.java
git commit -m "feat(research-table): split into 8 Wind-locked blocks sharing one BlockEntity type"
```

(This commit intentionally leaves the project non-compiling — the 4 remaining broken references are
fixed in Task 6/7, which is a normal, expected state mid-refactor for a task sequence like this one.
If you are reviewing this commit in isolation, `git log`/`git diff` on the next 2 commits together
restores a compiling state.)

---

### Task 6: Simplify `ResearchTableMenu`/`ResearchTableScreen` for one Wind per table

**Files:**
- Modify: `src/main/java/org/ratden/skavenblight/screen/ResearchTableMenu.java`
- Modify: `src/main/java/org/ratden/skavenblight/screen/ResearchTableScreen.java`

**Interfaces:**
- Consumes: `ResearchTableBlockEntity.wind` (Task 5).
- Produces: a Menu/Screen pair that no longer needs a cross-wind filter, since every table only
  ever has one Wind's worth of spells to show. This also fixes the compile break Task 5 left behind
  in `ResearchTableMenu.stillValid`.

No automated test — GUI behavior, verified manually at the end of Task 7 once textures exist to
actually look at.

- [ ] **Step 1: Fix `ResearchTableMenu.stillValid`**

Edit `src/main/java/org/ratden/skavenblight/screen/ResearchTableMenu.java`. Replace:

```java
    @Override
    public boolean stillValid(Player player) {
        return stillValid(ContainerLevelAccess.create(blockEntity.getLevel(), blockEntity.getBlockPos()),
                player, ModBlocks.RESEARCH_TABLE.get());
    }
```

with:

```java
    @Override
    public boolean stillValid(Player player) {
        return stillValid(ContainerLevelAccess.create(blockEntity.getLevel(), blockEntity.getBlockPos()),
                player, ModBlocks.RESEARCH_TABLES.get(blockEntity.wind).get());
    }
```

- [ ] **Step 2: Simplify `ResearchTableScreen` — drop the cross-wind filter rail**

Edit `src/main/java/org/ratden/skavenblight/screen/ResearchTableScreen.java`. This is a deletion +
simplification pass, not new logic — make these changes:

Remove the rail-related constants (no longer needed since there's nothing to filter):

```java
    // Rail
    private static final int RAIL_TAB_X = 6;
    private static final int RAIL_TAB_FIRST_Y = 17;
    private static final int RAIL_TAB_W = 16;
    private static final int RAIL_TAB_H = 12;
    private static final int RAIL_TAB_STEP = 14;
```

Widen the spell list to absorb the freed rail space — change:

```java
    // Spell list
    private static final int LIST_X = 26;
    private static final int LIST_Y = 16;
    private static final int LIST_W = 70;
    private static final int LIST_H = 122;
```

to:

```java
    // Spell list
    private static final int LIST_X = 6;
    private static final int LIST_Y = 16;
    private static final int LIST_W = 90;
    private static final int LIST_H = 122;
```

Remove the `selectedFilter` field entirely:

```java
    private int selectedFilter = 0; // 0 = All, 1..8 = Wind.ordinal() + 1
```

Simplify `rebuildList()`. Find the current method:

```java
    private void rebuildList() {
        List<Map.Entry<ResourceLocation, Spell>> entries = new ArrayList<>(SpellManager.getAll().entrySet());
        entries.removeIf(e -> selectedFilter != 0 && e.getValue().wind().ordinal() != selectedFilter - 1);
        entries.sort(Comparator2.BY_WIND_THEN_ID);

        SpellListWidget.Entry previouslySelected = this.spellList.getSelected();
        this.spellList.replaceAllEntries(entries);
        if (previouslySelected != null) {
            this.spellList.children().stream()
                    .filter(entry -> entry.id.equals(previouslySelected.id))
                    .findFirst()
                    .ifPresent(this.spellList::setSelected);
        }
    }
```

Replace it with (only the `removeIf`/`sort` lines change — filter by this table's fixed Wind
instead of the removed `selectedFilter`, and sort by id alone since every entry is already the same
Wind, so the old wind-then-id comparator is no longer needed):

```java
    private void rebuildList() {
        List<Map.Entry<ResourceLocation, Spell>> entries = new ArrayList<>(SpellManager.getAll().entrySet());
        entries.removeIf(e -> e.getValue().wind() != this.menu.blockEntity.wind);
        entries.sort(Comparator.comparing(Map.Entry::getKey));

        SpellListWidget.Entry previouslySelected = this.spellList.getSelected();
        this.spellList.replaceAllEntries(entries);
        if (previouslySelected != null) {
            this.spellList.children().stream()
                    .filter(entry -> entry.id.equals(previouslySelected.id))
                    .findFirst()
                    .ifPresent(this.spellList::setSelected);
        }
    }
```

Add the import `java.util.Comparator` (the old file never imported it — `Comparator2` used it fully
qualified as `java.util.Comparator.comparingInt(...)` instead; the replacement above uses the
imported short form).

Now delete the `Comparator2` helper class entirely — it only existed to support the
wind-then-id sort the replacement above no longer needs:

```java
    private static final class Comparator2 {
        static final java.util.Comparator<Map.Entry<ResourceLocation, Spell>> BY_WIND_THEN_ID =
                java.util.Comparator.<Map.Entry<ResourceLocation, Spell>>comparingInt(e -> e.getValue().wind().ordinal())
                        .thenComparing(Map.Entry::getKey);
    }
```

Remove the rail-highlight rendering from `renderBg` — delete these 3 lines:

```java
        // Highlight the selected wind-filter tab
        int tabY = y + RAIL_TAB_FIRST_Y + selectedFilter * RAIL_TAB_STEP;
        guiGraphics.renderOutline(x + RAIL_TAB_X - 1, tabY - 1, RAIL_TAB_W + 2, RAIL_TAB_H + 2, 0xFFFFFFFF);
```

Remove the entire `mouseClicked` override (the rail was the only thing it handled; without it there's
nothing left to intercept before falling through to `super.mouseClicked`, so removing the override
entirely is correct, not a stub):

```java
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int x = (width - imageWidth) / 2;
        int y = (height - imageHeight) / 2;
        for (int i = 0; i <= Wind.values().length; i++) {
            int tabX = x + RAIL_TAB_X;
            int tabY = y + RAIL_TAB_FIRST_Y + i * RAIL_TAB_STEP;
            if (mouseX >= tabX && mouseX <= tabX + RAIL_TAB_W && mouseY >= tabY && mouseY <= tabY + RAIL_TAB_H) {
                selectedFilter = i;
                rebuildList();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
```

- [ ] **Step 3: Compile**

Run: `./gradlew compileJava`
Expected: `BUILD SUCCESSFUL` for everything except `ModCreativeModeTabs`, `ModBlockLootTableProvider`,
and `ModBlockStateProvider`, which still reference the deleted `ModBlocks.RESEARCH_TABLE` — Task 7
fixes those 3 remaining files. Confirm no *other* errors appeared from this task's edits (e.g. an
unused-import warning is fine; a real compile error in `ResearchTableMenu.java` or
`ResearchTableScreen.java` themselves means something in this task's edits was applied incorrectly —
stop and fix it before proceeding).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/screen/ResearchTableMenu.java \
        src/main/java/org/ratden/skavenblight/screen/ResearchTableScreen.java
git commit -m "refactor(research-table): drop cross-wind filter, one table = one school now"
```

---

### Task 7: Datagen, textures, lang, and full end-to-end verification

**Files:**
- Modify: `src/main/java/org/ratden/skavenblight/item/ModCreativeModeTabs.java`
- Modify: `src/main/java/org/ratden/skavenblight/datagen/ModBlockLootTableProvider.java`
- Modify: `src/main/java/org/ratden/skavenblight/datagen/ModBlockStateProvider.java`
- Modify: `src/main/resources/assets/skavenblight/lang/en_us.json`
- Create: 32 texture files (8 winds × `{top,side,front,front_on}`)
- Regenerate: `src/main/resources/assets/skavenblight/textures/gui/research_table_gui.png`
- Delete: the 4 old generic `research_table_*.png` block textures

**Interfaces:**
- Consumes: `ModBlocks.RESEARCH_TABLES` (Task 5). This is the last task — it finishes the 3 compile
  errors Task 5 introduced and Task 6 didn't touch, then produces a fully working, placeable,
  playable set of 8 towers.

- [ ] **Step 1: Fix the creative tab**

Edit `src/main/java/org/ratden/skavenblight/item/ModCreativeModeTabs.java`. Replace:

```java
                        output.accept(ModBlocks.RESEARCH_TABLE);
```

with:

```java
                        ModBlocks.RESEARCH_TABLES.values().forEach(output::accept);
```

- [ ] **Step 2: Fix the loot table**

Edit `src/main/java/org/ratden/skavenblight/datagen/ModBlockLootTableProvider.java`. Replace:

```java
        dropSelf(ModBlocks.RESEARCH_TABLE.get());
```

with:

```java
        ModBlocks.RESEARCH_TABLES.values().forEach(table -> dropSelf(table.get()));
```

- [ ] **Step 3: Fix the blockstate/model datagen**

Edit `src/main/java/org/ratden/skavenblight/datagen/ModBlockStateProvider.java`. Add the import:

```java
import org.ratden.skavenblight.magic.Wind;
```

Replace:

```java
        standardMachineBlock(ModBlocks.RESEARCH_TABLE,
                "research_table_side",
                "research_table_top",
                "research_table_front",
                "research_table_front_on");
```

with:

```java
        for (Wind wind : Wind.values()) {
            String id = "research_table_" + wind.getSerializedName();
            standardMachineBlock(ModBlocks.RESEARCH_TABLES.get(wind),
                    id + "_side", id + "_top", id + "_front", id + "_front_on");
        }
```

- [ ] **Step 4: Compile**

Run: `./gradlew compileJava`
Expected: `BUILD SUCCESSFUL` — this is the first fully-compiling state since Task 5's commit.

- [ ] **Step 5: Replace the lang keys**

Edit `src/main/resources/assets/skavenblight/lang/en_us.json`. Replace this line:

```json
  "block.skavenblight.research_table": "Research Table",
```

with:

```json
  "block.skavenblight.research_table_hysh": "Font of Hysh",
  "block.skavenblight.research_table_azyr": "Orrery of Azyr",
  "block.skavenblight.research_table_chamon": "Alembic of Chamon",
  "block.skavenblight.research_table_ghyran": "Grove of Ghyran",
  "block.skavenblight.research_table_aqshy": "Forge of Aqshy",
  "block.skavenblight.research_table_ghur": "Den of Ghur",
  "block.skavenblight.research_table_ulgu": "Veil of Ulgu",
  "block.skavenblight.research_table_shyish": "Ossuary of Shyish",
```

- [ ] **Step 6: Generate the 32 per-Wind block textures**

These are placeholder pixel art (the same procedural-generation approach the original Research Table
task used, since this project has no PIL/ImageMagick available — a pure-Python PNG writer). Write
`gen_wizard_tower_textures.py` (anywhere convenient, e.g. a scratch/tmp directory — it's a one-time
generation script, not part of the mod's source):

```python
import struct, zlib

def write_png(path, width, height, pixels_rgba):
    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data +
                struct.pack(">I", zlib.crc32(tag + data) & 0xffffffff))
    raw = bytearray()
    for row in pixels_rgba:
        raw.append(0)
        for (r, g, b, a) in row:
            raw += bytes((r, g, b, a))
    sig = b"\x89PNG\r\n\x1a\n"
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    idat = zlib.compress(bytes(raw), 9)
    with open(path, "wb") as f:
        f.write(sig)
        f.write(chunk(b"IHDR", ihdr))
        f.write(chunk(b"IDAT", idat))
        f.write(chunk(b"IEND", b""))

def new_canvas(w, h, fill=(0, 0, 0, 0)):
    return [[fill for _ in range(w)] for _ in range(h)]

def fill_rect(c, x, y, w, h, color):
    for yy in range(y, y + h):
        if 0 <= yy < len(c):
            for xx in range(x, x + w):
                if 0 <= xx < len(c[yy]):
                    c[yy][xx] = color

def border_rect(c, x, y, w, h, color, t=1):
    fill_rect(c, x, y, w, t, color)
    fill_rect(c, x, y + h - t, w, t, color)
    fill_rect(c, x, y, t, h, color)
    fill_rect(c, x + w - t, y, t, h, color)

def hexcolor(h, a=255):
    return ((h >> 16) & 0xFF, (h >> 8) & 0xFF, h & 0xFF, a)

STONE_DARK = hexcolor(0x241C12)
STONE = hexcolor(0x332818)
STONE_LIGHT = hexcolor(0x4A3D2C)

WINDS = [
    ("hysh", 0xF7F3D9), ("azyr", 0x3C6FB0), ("chamon", 0xC9A227), ("ghyran", 0x3E8E3E),
    ("aqshy", 0xB5342A), ("ghur", 0x8A5A2B), ("ulgu", 0x6E6E78), ("shyish", 0x6A3E85),
]

def base_stone():
    c = new_canvas(16, 16, fill=STONE)
    border_rect(c, 0, 0, 16, 16, STONE_DARK, 1)
    fill_rect(c, 0, 5, 16, 1, STONE_DARK)
    fill_rect(c, 0, 11, 16, 1, STONE_DARK)
    fill_rect(c, 5, 0, 1, 5, STONE_DARK)
    fill_rect(c, 10, 6, 1, 5, STONE_DARK)
    fill_rect(c, 4, 12, 1, 4, STONE_DARK)
    return c

def plus_rune(c, cx, cy, color):
    fill_rect(c, cx - 2, cy, 5, 1, color)
    fill_rect(c, cx, cy - 2, 1, 5, color)

out_dir = r"src/main/resources/assets/skavenblight/textures/block"

for name, hexval in WINDS:
    rune = hexcolor(hexval)
    glow = hexcolor(min(0xFFFFFF, hexval + 0x303030))

    top = base_stone()
    fill_rect(top, 6, 6, 4, 4, STONE_LIGHT)
    plus_rune(top, 8, 8, rune)
    write_png(f"{out_dir}/research_table_{name}_top.png", 16, 16, top)

    side = base_stone()
    fill_rect(side, 6, 2, 4, 3, STONE_LIGHT)
    plus_rune(side, 8, 3, rune)
    write_png(f"{out_dir}/research_table_{name}_side.png", 16, 16, side)

    front = base_stone()
    fill_rect(front, 4, 3, 8, 5, STONE_DARK)
    border_rect(front, 4, 3, 8, 5, STONE_LIGHT, 1)
    plus_rune(front, 8, 11, rune)
    write_png(f"{out_dir}/research_table_{name}_front.png", 16, 16, front)

    front_on = base_stone()
    fill_rect(front_on, 4, 3, 8, 5, rune)
    border_rect(front_on, 4, 3, 8, 5, glow, 1)
    fill_rect(front_on, 6, 4, 4, 3, glow)
    fill_rect(front_on, 5, 10, 6, 2, rune)
    write_png(f"{out_dir}/research_table_{name}_front_on.png", 16, 16, front_on)

print("wrote 32 textures")
```

Run it from the project root: `python3 gen_wizard_tower_textures.py`
Expected output: `wrote 32 textures`.

Verify: `file src/main/resources/assets/skavenblight/textures/block/research_table_hysh_top.png`
Expected: `PNG image data, 16 x 16, 8-bit/color RGBA, non-interlaced` (and the same for all 32 files).

- [ ] **Step 7: Delete the superseded generic textures**

```bash
git rm src/main/resources/assets/skavenblight/textures/block/research_table_top.png \
       src/main/resources/assets/skavenblight/textures/block/research_table_side.png \
       src/main/resources/assets/skavenblight/textures/block/research_table_front.png \
       src/main/resources/assets/skavenblight/textures/block/research_table_front_on.png
```

- [ ] **Step 8: Regenerate the GUI background texture (wider list, no rail)**

The existing `research_table_gui.png` was generated by a one-time script during the original
Research Table task with the rail baked in at x4-24. Regenerate it with the list widened to fill
that space (matching Task 6's new `LIST_X = 6, LIST_W = 90`). Write
`gen_research_table_gui_v2.py`:

```python
import struct, zlib

def write_png(path, width, height, pixels_rgba):
    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data +
                struct.pack(">I", zlib.crc32(tag + data) & 0xffffffff))
    raw = bytearray()
    for row in pixels_rgba:
        raw.append(0)
        for (r, g, b, a) in row:
            raw += bytes((r, g, b, a))
    sig = b"\x89PNG\r\n\x1a\n"
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    idat = zlib.compress(bytes(raw), 9)
    with open(path, "wb") as f:
        f.write(sig)
        f.write(chunk(b"IHDR", ihdr))
        f.write(chunk(b"IDAT", idat))
        f.write(chunk(b"IEND", b""))

def new_canvas(w, h, fill=(0, 0, 0, 0)):
    return [[fill for _ in range(w)] for _ in range(h)]

def fill_rect(c, x, y, w, h, color):
    for yy in range(y, y + h):
        if 0 <= yy < len(c):
            for xx in range(x, x + w):
                if 0 <= xx < len(c[yy]):
                    c[yy][xx] = color

def border_rect(c, x, y, w, h, color, t=1):
    fill_rect(c, x, y, w, t, color)
    fill_rect(c, x, y + h - t, w, t, color)
    fill_rect(c, x, y, t, h, color)
    fill_rect(c, x + w - t, y, t, h, color)

def slot_graphic(c, x, y, bg=(26, 19, 13, 255), light=(140, 122, 92, 255), dark=(15, 10, 6, 255)):
    fill_rect(c, x, y, 18, 18, bg)
    border_rect(c, x, y, 18, 18, dark, 1)
    border_rect(c, x + 1, y + 1, 16, 16, light, 1)

def hexcolor(h, a=255):
    return ((h >> 16) & 0xFF, (h >> 8) & 0xFF, h & 0xFF, a)

PANEL_BG = hexcolor(0x4A3D2C)
TITLE_BAR = hexcolor(0x1A130D)
LIST_BG = hexcolor(0x5A4B36)
DETAIL_BG = hexcolor(0x4A3D2C)
BORDER = hexcolor(0x241C12)
METER_FRAME = hexcolor(0x1A130D)
PROGRESS_FRAME = hexcolor(0x241C12)

canvas = new_canvas(256, 256, fill=(0, 0, 0, 0))
fill_rect(canvas, 0, 0, 176, 222, PANEL_BG)
border_rect(canvas, 0, 0, 176, 222, BORDER, 1)
fill_rect(canvas, 0, 0, 176, 14, TITLE_BAR)

# Spell list panel: widened to (6,16)-(96,138) now that the wind-filter rail is gone
fill_rect(canvas, 6, 16, 90, 122, LIST_BG)
border_rect(canvas, 6, 16, 90, 122, BORDER, 1)
for ry in range(16, 138, 14):
    fill_rect(canvas, 7, ry, 88, 1, hexcolor(0x2B2118))

# Detail pane: unchanged from v1
fill_rect(canvas, 98, 16, 74, 122, DETAIL_BG)
border_rect(canvas, 98, 16, 74, 122, BORDER, 1)

fill_rect(canvas, 100, 74, 60, 8, METER_FRAME)
border_rect(canvas, 100, 74, 60, 8, BORDER, 1)

slot_graphic(canvas, 132, 88)

fill_rect(canvas, 100, 110, 60, 6, PROGRESS_FRAME)
border_rect(canvas, 100, 110, 60, 6, BORDER, 1)

for i in range(3):
    for l in range(9):
        slot_graphic(canvas, 8 + l * 18, 146 + i * 18)
for i in range(9):
    slot_graphic(canvas, 8 + i * 18, 204)

write_png("src/main/resources/assets/skavenblight/textures/gui/research_table_gui.png", 256, 256, canvas)
print("wrote research_table_gui.png (v2, no rail)")
```

Run it from the project root: `python3 gen_research_table_gui_v2.py`
Expected output: `wrote research_table_gui.png (v2, no rail)`.

- [ ] **Step 9: Run datagen and the full test suite**

Run: `./gradlew runData`
Expected: `BUILD SUCCESSFUL`. Confirm 8 blockstate files and 8×2 model files exist:
`ls src/generated/resources/assets/skavenblight/blockstates/ | grep research_table` should list 8
files (`research_table_hysh.json` ... `research_table_shyish.json`).

Run: `./gradlew test`
Expected: `BUILD SUCCESSFUL`, all tests still pass.

- [ ] **Step 10: Manual end-to-end verification**

Run: `./gradlew runClient`. In a single-player creative world:

1. Open the creative inventory, confirm all 8 "research table" blocks appear (Font of Hysh, Orrery
   of Azyr, Alembic of Chamon, Grove of Ghyran, Forge of Aqshy, Den of Ghur, Veil of Ulgu, Ossuary
   of Shyish), each with a distinct wind-tinted rune texture.
2. Place the Forge of Aqshy (Fire). Right-click it — confirm the GUI opens with the wind-filter rail
   gone and the spell list showing ONLY Aqshy's 2 spells (`fireball`, `shield_of_aqshy`), not all 16.
3. `/skavendebug magic set_aptitude aqshy 60` and `/skavendebug magic set_aptitude aqshy 60` won't
   set tier — tier defaults to 0, which is fine since both Aqshy spells are tier 0. Select `fireball`
   in the list. Confirm the detail pane shows its Wind/tier line, description, and a Wind meter.
4. If the local Aqshy level is below the requirement (check via `/skavendebug wind get`), place a
   few Magma Blocks or Netherrack nearby (tagged `wind_source/aqshy` from Task 2) and wait — confirm
   the meter rises and eventually turns from red to Aqshy's color, and "Begin Research" becomes
   enabled.
5. Start research. Confirm the progress bar advances. Place several MORE Magma Blocks nearby (pushing
   Aqshy's local level well above the requirement) and confirm research visibly speeds up (progress
   bar fills noticeably faster than it did with wind just barely sufficient) — this is
   `ResearchFormulas.speedMultiplier` in action.
6. Let it complete. Confirm `fireball` shows a "known"/green state in the list afterward, and
   `/skavendebug magic cast skavenblight:aqshy/fireball` still works as it did before this plan
   (this plan doesn't change casting, only research).

If any step fails, do not consider this plan done — the whole point of Part B is this loop actually
working end-to-end.

- [ ] **Step 11: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/item/ModCreativeModeTabs.java \
        src/main/java/org/ratden/skavenblight/datagen/ModBlockLootTableProvider.java \
        src/main/java/org/ratden/skavenblight/datagen/ModBlockStateProvider.java \
        src/main/resources/assets/skavenblight/lang/en_us.json \
        src/main/resources/assets/skavenblight/textures
git commit -m "feat(research-table): datagen + per-Wind textures + lang for the 8 wizard towers"
```

---

## Self-Review Notes

- **Spec coverage:** all 4 asks from the conversation are covered — per-school tables (Task 5), wind
  level as a positive continuous modifier (Task 4's `speedMultiplier`), wind level scaling with tier
  as a research requirement (Task 4's `requiredWindLevel`), and themed decorative blocks providing a
  boost (Tasks 1-3, `TaggedBlockInfluence`). The "wizard tower" framing itself (a decorated structure
  around the table) is emergent from these mechanics, not a separate thing to build — no dedicated
  "tower" block or structure is needed since the mechanic rewards *any* placement of tagged blocks
  near the table, which is exactly what a player decorating a tower would naturally do.
- **Placeholder scan:** no TBD/TODO strings. The one spot that looks like a "leave it broken"
  instruction (Task 5 Step 5, "compile errors are expected") is explicitly justified as a normal
  multi-commit refactor sequencing choice, with an exact description of which errors are expected and
  instructions to stop if anything else appears — not a vague "add error handling" placeholder.
- **Type consistency:** `ResearchFormulas.requiredWindLevel(int tier, int baseThreshold)` and
  `.speedMultiplier(float current, float required, int bonusReference, double maxBonusMultiplier)`
  are used with identical signatures in both the test (Task 4 Step 1) and the wiring (Task 4 Step 5).
  `ModBlocks.RESEARCH_TABLES` (the `Map<Wind, DeferredBlock<Block>>`) is referenced identically across
  Task 5 (creation), Task 6 (`stillValid`), and Task 7 (creative tab, loot table, blockstate
  provider) — same type, same key (`Wind`), same value (`DeferredBlock<Block>`) everywhere.
  `ResearchTableBlockEntity.wind` (Task 5) is read identically in Task 6's `rebuildList` filter and
  Task 7's manual verification.

## Not in scope for this plan

- New custom decorative *blocks* for the towers (this plan reuses existing vanilla blocks via tags —
  a bespoke "Skaven Warpstone Sconce"-style decorative block set per Wind is a nice future art/content
  pass, not required for the mechanic).
- Per-spell (rather than per-tier) Wind requirements, or a non-linear tier curve — `tier * threshold`
  is the simplest formula that satisfies "more advanced spells need more Wind"; tune the curve later
  via config/playtesting, not by rewriting `ResearchFormulas`.
- Any change to casting, `SpellManager`, or the Modonomicon book.
- Biome-based wind influence (`BiomeInfluence`, still Phase 2+ backlog from the original Winds of
  Magic Foundation plan) — tagged blocks are a *player-placed* Tier 0 mechanic, biomes would be an
  *ambient* one; unrelated systems that happen to feed the same `WindBaselineInfluence` extension
  point.
