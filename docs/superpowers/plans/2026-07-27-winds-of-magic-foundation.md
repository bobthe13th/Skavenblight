# Winds of Magic — Foundation & Spell Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up the ambient per-chunk Wind-of-Magic system, the data-driven Spell pipeline, and the
Modonomicon book scaffolding for all 8 Winds, then prove the whole pipeline end-to-end with 16 real
spells (2 per Wind) castable via a debug command. This is **Phase 1** of the larger Winds of Magic
feature described in `docs/superpowers/20260728windsofmagicdesign.md` — it deliberately stops short of
player-facing casting UI, biome/tagged-block influences, power stones, potions, runes, Dark Magic, and
familiars, all of which become their own follow-up plans once this foundation is proven (see
**Phase 2+ Backlog** at the end of this document).

**Architecture:** A new `org.ratden.skavenblight.magic` package tree, structured as a sibling system to
the existing Warp Flux network (own `SavedData`, own packages, ticked from the same
`LevelTickEvent.Post` this mod already uses). Winds are ambient world state (a value per chunk that
drifts toward a computed baseline), not a block network. Spells are datapack JSON
(`SimpleJsonResourceReloadListener`, exactly like vanilla recipes) with a small sealed-interface escape
hatch (`SpellEffect`) for the handful of effect shapes that need real code. Player magic data
(aptitude, tier, known spells) is a NeoForge `AttachmentType`, not a capability. All of this mirrors
patterns already in this codebase: `WarpFluxGridManager` (SavedData + tick), `ModDataMapProvider`
(datagen), `DebugNexusCommands`/`SkavenDebugCommand` (debug command registration), `Config.java`
(tiered config values).

**Tech Stack:** NeoForge 1.21.1 (Neo `21.1.228`), Java 21, Mojang Codecs/DataFixerUpper for all data
models, Modonomicon `1.120.3` (already a dependency) for the research book, JUnit 5 (newly added by
this plan — the project currently has zero automated tests).

## Global Constraints

- Minecraft 1.21.1 / NeoForge `21.1.228` (`gradle.properties`) — do not use APIs from other versions.
- Mod id is `skavenblight`, base package is `org.ratden.skavenblight` (`gradle.properties: mod_group_id`).
- New Java packages go under `org.ratden.skavenblight.magic` per
  `docs/superpowers/20260728windsofmagicdesign.md` §12 — do not scatter magic classes into existing
  packages (`block`, `item`, etc.).
- Spells, and all future magic content (runes, potions), must be **datapack JSON**, never a new Java
  class per content item — this is the single most important constraint in the source design doc (§5.1).
  `SpellEffect` is the one sanctioned escape hatch, and it must stay a small sealed interface, not grow
  into a scripting language.
- Follow existing conventions exactly: `SavedData` classes use the `Factory<>(ctor, load, null)` +
  `level.getDataStorage().computeIfAbsent(..., "skavenblight_<name>")` pattern from
  `WarpFluxGridManager.java`; debug commands are one class per subsystem under `command/debug/`,
  registered into the single `skavendebug` root in `SkavenDebugCommand.java`.
- The existing `nexus_research` Modonomicon book, its `nexus` and `skaven_engineering` categories, and
  the `dwarven_archeology` stub category must not be broken or renamed — only the `winds_of_magic`
  category is replaced (with 8 new per-Wind categories) and a new `dark_magic` category is added.
- Do not touch `WarpFluxNetwork`/`WarpFluxGridManager`/anything under `network/` — Winds of Magic is an
  architecturally separate system that happens to tick from the same event.
- This plan adds JUnit 5 to `build.gradle` for the first time in this project. Keep it minimal: a
  `test` source set with `useJUnitPlatform()`, nothing else. Tests that need Minecraft's builtin
  registries populated (e.g. resolving `minecraft:regeneration`) must call
  `SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();` once — see Task 7.
- Where a task's behavior can only be observed by running the game (chunk ticking, command output),
  there is no GameTest/JUnit harness in this project yet (a separate, unrelated effort is building one
  for the pathing system) — verification steps for those tasks are manual: run `./gradlew runClient`,
  execute the stated command, and confirm the stated output. Do not skip these steps or claim
  completion without actually running them.

---

## File Structure

```
src/main/java/org/ratden/skavenblight/magic/
├── Wind.java                          8-Wind enum: id, lore name, colour, translation key, Codec
├── wind/
│   ├── ChunkWindState.java            per-chunk float[8] current + float[8] baseline + dharLevel, NBT I/O
│   ├── WindGridManager.java           SavedData: loaded-chunk tracking, tick-and-drift, influence list
│   ├── WindBaselineInfluence.java     extension-point interface for baseline drivers
│   └── influence/
│       └── TimeOfDayInfluence.java    day -> Hysh up, night -> Ulgu up (only influence wired in Phase 1)
├── spell/
│   ├── CastingTime.java               enum: INSTANT, HALF_ACTION, FULL_ACTION, CHANNELLED
│   ├── SpellEffect.java               sealed interface + DamageEffect/MobEffectApply + dispatch Codec
│   ├── Spell.java                     data record + Codec (wind, tier, casting_number, effect, ...)
│   ├── SpellManager.java              SimpleJsonResourceReloadListener<Spell>, static lookup by id
│   └── CastingResolver.java           pure success-check formula, the only place casting math lives
├── player/
│   ├── PlayerMagicData.java           attachment payload record + Codec (tier/aptitude/known spells)
│   └── ModAttachments.java            DeferredRegister<AttachmentType<?>>
└── ModMagic.java                      wires reload listener + attachments into the mod event bus

src/main/java/org/ratden/skavenblight/command/debug/
├── DebugWindCommands.java             NEW — /skavendebug wind get
└── DebugMagicCommands.java            NEW — /skavendebug magic cast|set_aptitude|info

src/main/java/org/ratden/skavenblight/command/SkavenDebugCommand.java   MODIFY — register the 2 new commands
src/main/java/org/ratden/skavenblight/Skavenblight.java                 MODIFY — wire ModMagic, chunk load/unload, wind tick

src/test/java/org/ratden/skavenblight/magic/
├── WindTest.java
├── wind/ChunkWindStateTest.java
├── spell/CastingResolverTest.java
└── spell/SpellCodecTest.java

data/skavenblight/magic/spells/<wind>/<spell_id>.json     16 files (2 per Wind) — Task 11
data/skavenblight/modonomicon/books/nexus_research/categories/
├── winds_of_magic_hysh.json  ... winds_of_magic_shyish.json   (8 files, replace winds_of_magic.json)
├── dark_magic.json                                            (new, empty-for-now stub)
└── dwarven_archeology.json                                    MODIFY — sort_number 2 -> 10
data/skavenblight/modonomicon/books/nexus_research/entries/
├── winds_of_magic_<wind>/overview.json        (8 files, Task 5)
├── winds_of_magic_<wind>/petty_spells.json     (8 files, Task 11)
└── dark_magic/overview.json                    (stub, Task 5)
assets/skavenblight/lang/en_us.json             MODIFY — add all new category/entry/page/spell keys
build.gradle                                     MODIFY — add JUnit 5 test dependency + test task config
```

Delete as part of Task 5: `categories/winds_of_magic.json` and `entries/winds_of_magic/wip.json` (the
single WIP category/entry this replaces).

---

### Task 1: JUnit test infrastructure + `Wind` enum

**Files:**
- Modify: `build.gradle`
- Create: `src/main/java/org/ratden/skavenblight/magic/Wind.java`
- Test: `src/test/java/org/ratden/skavenblight/magic/WindTest.java`

**Interfaces:**
- Produces: `Wind` enum with 8 constants (`HYSH, AZYR, CHAMON, GHYRAN, AQSHY, GHUR, ULGU, SHYISH`),
  `Wind.CODEC` (`Codec<Wind>`), `wind.getLoreName()`, `wind.getColor()`, `wind.getTranslationKey()`.
  Every later task in this plan and all future magic tasks index arrays/maps by `Wind.ordinal()` or key
  off `Wind.CODEC` — this enum is the one canonical school/wind pairing (per design doc §2), never
  duplicate it as a separate concept.

- [ ] **Step 1: Add JUnit 5 to the build**

Edit `build.gradle`, inside the existing `dependencies { ... }` block, add:

```groovy
    testImplementation platform('org.junit:junit-bom:5.10.2')
    testImplementation 'org.junit.jupiter:junit-jupiter'
```

At the end of the file (top level, alongside other top-level blocks like `sourceSets.main.resources`),
add:

```groovy
test {
    useJUnitPlatform()
}
```

- [ ] **Step 2: Verify the test task runs (with nothing in it yet)**

Run: `./gradlew test`
Expected: `BUILD SUCCESSFUL` with a `NO-SOURCE` or empty test task (no test classes exist yet, that's fine — this just proves the JUnit platform is wired up without errors).

- [ ] **Step 3: Write the failing test for `Wind`**

Create `src/test/java/org/ratden/skavenblight/magic/WindTest.java`:

```java
package org.ratden.skavenblight.magic;

import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WindTest {

    @Test
    void hasExactlyEightWinds() {
        assertEquals(8, Wind.values().length);
    }

    @Test
    void codecRoundTripsBySerializedName() {
        for (Wind wind : Wind.values()) {
            var encoded = Wind.CODEC.encodeStart(JsonOps.INSTANCE, wind).getOrThrow();
            Wind decoded = Wind.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
            assertEquals(wind, decoded);
        }
    }

    @Test
    void translationKeysAreNamespacedAndUnique() {
        long distinctKeys = java.util.Arrays.stream(Wind.values())
                .map(Wind::getTranslationKey)
                .distinct()
                .count();
        assertEquals(8, distinctKeys);
        assertEquals("wind.skavenblight.hysh", Wind.HYSH.getTranslationKey());
    }
}
```

- [ ] **Step 4: Run test to verify it fails**

Run: `./gradlew test --tests "org.ratden.skavenblight.magic.WindTest"`
Expected: FAIL — compile error, `Wind` class does not exist yet.

- [ ] **Step 5: Implement `Wind`**

Create `src/main/java/org/ratden/skavenblight/magic/Wind.java`:

```java
package org.ratden.skavenblight.magic;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

/**
 * The 8 Winds of Magic. This is the canonical school/wind pairing used everywhere in code —
 * never introduce a parallel "Lore" or "School" enum, extend this one.
 */
public enum Wind implements StringRepresentable {
    HYSH("hysh", "Light", 0xF7F3D9),
    AZYR("azyr", "Heavens", 0x3C6FB0),
    CHAMON("chamon", "Metal", 0xC9A227),
    GHYRAN("ghyran", "Life", 0x3E8E3E),
    AQSHY("aqshy", "Fire", 0xB5342A),
    GHUR("ghur", "Beasts", 0x8A5A2B),
    ULGU("ulgu", "Shadow", 0x6E6E78),
    SHYISH("shyish", "Death", 0x6A3E85);

    public static final Codec<Wind> CODEC = StringRepresentable.fromEnum(Wind::values);

    private final String id;
    private final String loreName;
    private final int color;

    Wind(String id, String loreName, int color) {
        this.id = id;
        this.loreName = loreName;
        this.color = color;
    }

    @Override
    public String getSerializedName() {
        return id;
    }

    public String getLoreName() {
        return loreName;
    }

    public int getColor() {
        return color;
    }

    public String getTranslationKey() {
        return "wind.skavenblight." + id;
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew test --tests "org.ratden.skavenblight.magic.WindTest"`
Expected: PASS, 3 tests green.

- [ ] **Step 7: Commit**

```bash
git add build.gradle src/main/java/org/ratden/skavenblight/magic/Wind.java src/test/java/org/ratden/skavenblight/magic/WindTest.java
git commit -m "feat(magic): add JUnit infra and the Wind enum"
```

---

### Task 2: `ChunkWindState`

**Files:**
- Create: `src/main/java/org/ratden/skavenblight/magic/wind/ChunkWindState.java`
- Test: `src/test/java/org/ratden/skavenblight/magic/wind/ChunkWindStateTest.java`

**Interfaces:**
- Consumes: `Wind` enum (Task 1) for `.ordinal()` indexing.
- Produces: `ChunkWindState` with `getCurrent(Wind)`, `setCurrent(Wind, float)`, `getBaseline(Wind)`,
  `setBaseline(Wind, float)`, `getDharLevel()`, `setDharLevel(float)`, `save(CompoundTag)` returning the
  tag, and static `load(CompoundTag)` returning a new instance. `WindGridManager` (Task 3) stores one of
  these per `ChunkPos` and calls `save`/`load` when persisting the whole map.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/ratden/skavenblight/magic/wind/ChunkWindStateTest.java`:

```java
package org.ratden.skavenblight.magic.wind;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import org.ratden.skavenblight.magic.Wind;

import static org.junit.jupiter.api.Assertions.*;

class ChunkWindStateTest {

    @Test
    void defaultsToZero() {
        ChunkWindState state = new ChunkWindState();
        for (Wind wind : Wind.values()) {
            assertEquals(0f, state.getCurrent(wind));
            assertEquals(0f, state.getBaseline(wind));
        }
        assertEquals(0f, state.getDharLevel());
    }

    @Test
    void gettersReflectSetters() {
        ChunkWindState state = new ChunkWindState();
        state.setCurrent(Wind.AQSHY, 123.5f);
        state.setBaseline(Wind.SHYISH, 42f);
        state.setDharLevel(7f);

        assertEquals(123.5f, state.getCurrent(Wind.AQSHY));
        assertEquals(42f, state.getBaseline(Wind.SHYISH));
        assertEquals(7f, state.getDharLevel());
        // unrelated winds untouched
        assertEquals(0f, state.getCurrent(Wind.HYSH));
    }

    @Test
    void roundTripsThroughNbt() {
        ChunkWindState state = new ChunkWindState();
        for (Wind wind : Wind.values()) {
            state.setCurrent(wind, wind.ordinal() * 10f + 1f);
            state.setBaseline(wind, wind.ordinal() * 20f + 2f);
        }
        state.setDharLevel(99.5f);

        CompoundTag tag = state.save(new CompoundTag());
        ChunkWindState loaded = ChunkWindState.load(tag);

        for (Wind wind : Wind.values()) {
            assertEquals(state.getCurrent(wind), loaded.getCurrent(wind), 0.001f);
            assertEquals(state.getBaseline(wind), loaded.getBaseline(wind), 0.001f);
        }
        assertEquals(99.5f, loaded.getDharLevel(), 0.001f);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "org.ratden.skavenblight.magic.wind.ChunkWindStateTest"`
Expected: FAIL — `ChunkWindState` does not exist.

- [ ] **Step 3: Implement `ChunkWindState`**

Create `src/main/java/org/ratden/skavenblight/magic/wind/ChunkWindState.java`:

```java
package org.ratden.skavenblight.magic.wind;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.ratden.skavenblight.magic.Wind;

/** Per-chunk wind levels: a "current" value drifting toward a recomputed "baseline". */
public final class ChunkWindState {

    private final float[] current = new float[Wind.values().length];
    private final float[] baseline = new float[Wind.values().length];
    private float dharLevel = 0f;

    public float getCurrent(Wind wind) {
        return current[wind.ordinal()];
    }

    public void setCurrent(Wind wind, float value) {
        current[wind.ordinal()] = value;
    }

    public float getBaseline(Wind wind) {
        return baseline[wind.ordinal()];
    }

    public void setBaseline(Wind wind, float value) {
        baseline[wind.ordinal()] = value;
    }

    public float getDharLevel() {
        return dharLevel;
    }

    public void setDharLevel(float value) {
        dharLevel = value;
    }

    public CompoundTag save(CompoundTag tag) {
        tag.put("current", floatArrayTag(current));
        tag.put("baseline", floatArrayTag(baseline));
        tag.putFloat("dharLevel", dharLevel);
        return tag;
    }

    public static ChunkWindState load(CompoundTag tag) {
        ChunkWindState state = new ChunkWindState();
        readFloatArrayTag(tag, "current", state.current);
        readFloatArrayTag(tag, "baseline", state.baseline);
        state.dharLevel = tag.getFloat("dharLevel");
        return state;
    }

    private static ListTag floatArrayTag(float[] values) {
        ListTag list = new ListTag();
        for (float v : values) {
            list.add(FloatTag.valueOf(v));
        }
        return list;
    }

    private static void readFloatArrayTag(CompoundTag tag, String key, float[] target) {
        if (!tag.contains(key, Tag.TAG_LIST)) return;
        ListTag list = tag.getList(key, Tag.TAG_FLOAT);
        for (int i = 0; i < Math.min(list.size(), target.length); i++) {
            target[i] = list.getFloat(i);
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "org.ratden.skavenblight.magic.wind.ChunkWindStateTest"`
Expected: PASS, 3 tests green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/magic/wind/ChunkWindState.java src/test/java/org/ratden/skavenblight/magic/wind/ChunkWindStateTest.java
git commit -m "feat(magic): add ChunkWindState with NBT round-trip"
```

---

### Task 3: `WindGridManager` + `TimeOfDayInfluence` + tick wiring

**Files:**
- Create: `src/main/java/org/ratden/skavenblight/magic/wind/WindBaselineInfluence.java`
- Create: `src/main/java/org/ratden/skavenblight/magic/wind/WindGridManager.java`
- Create: `src/main/java/org/ratden/skavenblight/magic/wind/influence/TimeOfDayInfluence.java`
- Modify: `src/main/java/org/ratden/skavenblight/Skavenblight.java`

**Interfaces:**
- Consumes: `ChunkWindState` (Task 2), `Wind` (Task 1).
- Produces: `WindGridManager.get(ServerLevel)`, `.getOrCreate(ChunkPos)`, `.markLoaded(ChunkPos)`,
  `.markUnloaded(ChunkPos)`, `.tick(ServerLevel)`. `DebugWindCommands` (Task 4) and
  `DebugMagicCommands` (Task 10) both call `WindGridManager.get(level).getOrCreate(pos)` to read wind
  levels. Later tasks (Phase 2 biome/tagged-block influences) add to the static `INFLUENCES` list —
  that's the one-line extension point, don't restructure it.

There is no automated test for this task — `ServerLevel` cannot be constructed outside a running game,
and this project has no GameTest harness yet (see Global Constraints). Verification is manual, via the
debug command added in Task 4. This task and Task 4 are reviewed/verified together.

- [ ] **Step 1: Write the influence interface**

Create `src/main/java/org/ratden/skavenblight/magic/wind/WindBaselineInfluence.java`:

```java
package org.ratden.skavenblight.magic.wind;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/**
 * One natural driver of a chunk's wind baseline (time of day, biome, distance to a Chaos Gate, ...).
 * Each implementation nudges baselineDeltaOut (indexed by Wind.ordinal(), length 8) — it does not
 * set absolute values, since multiple influences stack additively before WindGridManager.tick clamps
 * the result. Register new influences by adding one line to WindGridManager.INFLUENCES.
 */
public interface WindBaselineInfluence {
    void apply(ServerLevel level, ChunkPos pos, float[] baselineDeltaOut);
}
```

- [ ] **Step 2: Write the time-of-day influence**

Create `src/main/java/org/ratden/skavenblight/magic/wind/influence/TimeOfDayInfluence.java`:

```java
package org.ratden.skavenblight.magic.wind.influence;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.ratden.skavenblight.magic.Wind;
import org.ratden.skavenblight.magic.wind.WindBaselineInfluence;

/** Hysh (white/light) is elevated during the day, Ulgu (grey/shadow) during the night. */
public class TimeOfDayInfluence implements WindBaselineInfluence {

    private static final float DAY_HYSH_BONUS = 150f;
    private static final float NIGHT_ULGU_BONUS = 150f;
    private static final long DAY_TICKS = 12000L;
    private static final long FULL_CYCLE_TICKS = 24000L;

    @Override
    public void apply(ServerLevel level, ChunkPos pos, float[] baselineDeltaOut) {
        long dayTime = level.getDayTime() % FULL_CYCLE_TICKS;
        if (dayTime < DAY_TICKS) {
            baselineDeltaOut[Wind.HYSH.ordinal()] += DAY_HYSH_BONUS;
        } else {
            baselineDeltaOut[Wind.ULGU.ordinal()] += NIGHT_ULGU_BONUS;
        }
    }
}
```

- [ ] **Step 3: Implement `WindGridManager`**

Create `src/main/java/org/ratden/skavenblight/magic/wind/WindGridManager.java`:

```java
package org.ratden.skavenblight.magic.wind;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
import org.ratden.skavenblight.magic.Wind;
import org.ratden.skavenblight.magic.wind.influence.TimeOfDayInfluence;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ambient per-chunk wind levels. Sibling to WarpFluxGridManager: same SavedData + tick-from-
 * LevelTickEvent.Post shape, but winds have no conduit graph to flood-fill — every loaded chunk is
 * ticked independently.
 */
public class WindGridManager extends SavedData {

    /** Extension point: Phase 2 tasks append BiomeInfluence, TaggedBlockInfluence, etc. here. */
    private static final List<WindBaselineInfluence> INFLUENCES = new ArrayList<>();
    static {
        INFLUENCES.add(new TimeOfDayInfluence());
    }

    /** Fraction of the current-to-baseline gap closed per tick. */
    private static final float DRIFT_RATE = 0.02f;

    private final Map<ChunkPos, ChunkWindState> chunkStates = new HashMap<>();
    private final Set<ChunkPos> loadedChunks = new HashSet<>();

    public static WindGridManager get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new Factory<>(WindGridManager::new, WindGridManager::load, null),
                "skavenblight_winds"
        );
    }

    public ChunkWindState getOrCreate(ChunkPos pos) {
        return chunkStates.computeIfAbsent(pos, p -> new ChunkWindState());
    }

    public void markLoaded(ChunkPos pos) {
        loadedChunks.add(pos);
    }

    public void markUnloaded(ChunkPos pos) {
        loadedChunks.remove(pos);
    }

    public void tick(ServerLevel level) {
        float[] baselineDelta = new float[Wind.values().length];
        for (ChunkPos pos : loadedChunks) {
            ChunkWindState state = getOrCreate(pos);
            Arrays.fill(baselineDelta, 0f);
            for (WindBaselineInfluence influence : INFLUENCES) {
                influence.apply(level, pos, baselineDelta);
            }
            for (Wind wind : Wind.values()) {
                float newBaseline = Math.max(0f, baselineDelta[wind.ordinal()]);
                state.setBaseline(wind, newBaseline);
                float currentValue = state.getCurrent(wind);
                state.setCurrent(wind, currentValue + (newBaseline - currentValue) * DRIFT_RATE);
            }
        }
        this.setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<ChunkPos, ChunkWindState> entry : chunkStates.entrySet()) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putLong("pos", entry.getKey().toLong());
            entryTag.put("state", entry.getValue().save(new CompoundTag()));
            list.add(entryTag);
        }
        tag.put("chunks", list);
        return tag;
    }

    public static WindGridManager load(CompoundTag tag, HolderLookup.Provider registries) {
        WindGridManager manager = new WindGridManager();
        if (tag.contains("chunks", Tag.TAG_LIST)) {
            ListTag list = tag.getList("chunks", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag entryTag = list.getCompound(i);
                ChunkPos pos = new ChunkPos(entryTag.getLong("pos"));
                ChunkWindState state = ChunkWindState.load(entryTag.getCompound("state"));
                manager.chunkStates.put(pos, state);
            }
        }
        return manager;
    }
}
```

- [ ] **Step 4: Wire chunk load/unload tracking and tick into `Skavenblight.java`**

In `src/main/java/org/ratden/skavenblight/Skavenblight.java`, add these imports near the other
`org.ratden.skavenblight.*` imports:

```java
import org.ratden.skavenblight.magic.wind.WindGridManager;
```

Add these two `@SubscribeEvent` methods anywhere alongside the existing `onLevelTick` method (the class
already calls `NeoForge.EVENT_BUS.register(this)` in its constructor, so no extra registration is
needed):

```java
@net.neoforged.bus.api.SubscribeEvent
public void onChunkLoad(net.neoforged.neoforge.event.level.ChunkEvent.Load event) {
    if (event.getLevel() instanceof ServerLevel serverLevel) {
        WindGridManager.get(serverLevel).markLoaded(event.getChunk().getPos());
    }
}

@net.neoforged.bus.api.SubscribeEvent
public void onChunkUnload(net.neoforged.neoforge.event.level.ChunkEvent.Unload event) {
    if (event.getLevel() instanceof ServerLevel serverLevel) {
        WindGridManager.get(serverLevel).markUnloaded(event.getChunk().getPos());
    }
}
```

Then extend the existing `onLevelTick` method to also tick the wind grid:

```java
//Warp flux network
@net.neoforged.bus.api.SubscribeEvent
public void onLevelTick(net.neoforged.neoforge.event.tick.LevelTickEvent.Post event) {
    if (event.getLevel() instanceof ServerLevel serverLevel) {
        WarpFluxGridManager manager = WarpFluxGridManager.get(serverLevel);
        manager.tickNetworks(serverLevel);

        WindGridManager.get(serverLevel).tick(serverLevel);
    }
}
```

- [ ] **Step 5: Compile**

Run: `./gradlew compileJava`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/magic/wind/WindBaselineInfluence.java \
        src/main/java/org/ratden/skavenblight/magic/wind/WindGridManager.java \
        src/main/java/org/ratden/skavenblight/magic/wind/influence/TimeOfDayInfluence.java \
        src/main/java/org/ratden/skavenblight/Skavenblight.java
git commit -m "feat(magic): add WindGridManager ticking with time-of-day influence"
```

(Manual verification happens together with Task 4's debug command, below.)

---

### Task 4: `DebugWindCommands`

**Files:**
- Create: `src/main/java/org/ratden/skavenblight/command/debug/DebugWindCommands.java`
- Modify: `src/main/java/org/ratden/skavenblight/command/SkavenDebugCommand.java`

**Interfaces:**
- Consumes: `WindGridManager.get(level).getOrCreate(ChunkPos)` (Task 3), `Wind.values()` (Task 1).
- Produces: `/skavendebug wind get` — this is also how you'll manually verify Task 3's tick loop is
  actually running before moving on.

- [ ] **Step 1: Implement the command**

Create `src/main/java/org/ratden/skavenblight/command/debug/DebugWindCommands.java`:

```java
package org.ratden.skavenblight.command.debug;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.ratden.skavenblight.magic.Wind;
import org.ratden.skavenblight.magic.wind.ChunkWindState;
import org.ratden.skavenblight.magic.wind.WindGridManager;

public class DebugWindCommands {

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("wind")
                .then(Commands.literal("get")
                        .executes(context -> windGet(context.getSource())));
    }

    private static int windGet(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("This command must be run by a player."));
            return 0;
        }

        ServerLevel level = source.getLevel();
        ChunkPos pos = new ChunkPos(player.blockPosition());
        ChunkWindState state = WindGridManager.get(level).getOrCreate(pos);

        StringBuilder sb = new StringBuilder("Wind levels at chunk " + pos + ":");
        for (Wind wind : Wind.values()) {
            sb.append("\n").append(wind.getLoreName())
                    .append(": current=").append(String.format("%.1f", state.getCurrent(wind)))
                    .append(", baseline=").append(String.format("%.1f", state.getBaseline(wind)));
        }
        String message = sb.toString();
        source.sendSuccess(() -> Component.literal(message), false);
        return 1;
    }
}
```

- [ ] **Step 2: Register it**

In `src/main/java/org/ratden/skavenblight/command/SkavenDebugCommand.java`, add one line to the chain:

```java
public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
    dispatcher.register(
            Commands.literal("skavendebug")
                    .then(DebugDifficultyCommands.register())
                    .then(DebugCleanupCommands.register())
                    .then(DebugSourceCommands.register())
                    .then(DebugNexusCommands.register())
                    .then(DebugMobCommands.register())
                    .then(DebugPathingCommands.register())
                    .then(DebugIncursionLoadTest.register())
                    .then(DebugWindCommands.register())
    );
}
```

- [ ] **Step 3: Compile**

Run: `./gradlew compileJava`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Manual verification — run the client and watch wind levels drift**

Run: `./gradlew runClient`

In a single-player world:
1. Run `/skavendebug wind get`. Expected: 8 lines, one per Wind, `current=0.0` and `baseline=0.0` for
   everything except Hysh or Ulgu depending on time of day (default new-world time is dawn, so Hysh's
   baseline should already read `150.0` and start climbing toward it).
2. Wait ~30 seconds (or run `/time set noon` then wait a few seconds), run `/skavendebug wind get`
   again. Expected: Hysh's `current` value has moved noticeably closer to `150.0` (drifts 2% of the
   remaining gap per tick, 20 ticks/second — after 100 ticks it should have closed roughly 87% of the
   gap).
3. Run `/time set night`, wait, run `/skavendebug wind get` again. Expected: Hysh's baseline drops back
   to `0.0` and starts drifting down, Ulgu's baseline is now `150.0` and climbing.

If any of this doesn't happen, do not proceed — debug via `DebugWindCommands`/`WindGridManager` before
starting Task 5.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/command/debug/DebugWindCommands.java \
        src/main/java/org/ratden/skavenblight/command/SkavenDebugCommand.java
git commit -m "feat(magic): add /skavendebug wind get command"
```

---

### Task 5: Modonomicon scaffolding — 8 Wind categories + Dark Magic stub

**Files:**
- Delete: `src/main/resources/data/skavenblight/modonomicon/books/nexus_research/categories/winds_of_magic.json`
- Delete: `src/main/resources/data/skavenblight/modonomicon/books/nexus_research/entries/winds_of_magic/wip.json`
- Create: `.../categories/winds_of_magic_hysh.json` through `.../categories/winds_of_magic_shyish.json` (8 files)
- Create: `.../categories/dark_magic.json`
- Modify: `.../categories/dwarven_archeology.json` (sort_number 2 → 10)
- Create: `.../entries/winds_of_magic_<wind>/overview.json` (8 files)
- Create: `.../entries/dark_magic/overview.json`
- Modify: `src/main/resources/assets/skavenblight/lang/en_us.json`

**Interfaces:**
- Produces: category ids `skavenblight:winds_of_magic_hysh` ... `skavenblight:winds_of_magic_shyish`
  and `skavenblight:dark_magic`. Task 11 adds a `petty_spells.json` entry into each of the 8 wind
  categories created here — the category ids below must match exactly.

This task is data-only; there's no code to unit test. Verification is: the game launches without a
Modonomicon load error, and the book (`/give @s skavenblight:skavenblight_book` or find it in-world)
shows 8 Wind categories in place of the old single WIP one, plus Dark Magic, with Dwarven Archeology
now sorted after them.

- [ ] **Step 1: Delete the old single WIP category and entry**

```bash
git rm src/main/resources/data/skavenblight/modonomicon/books/nexus_research/categories/winds_of_magic.json
git rm src/main/resources/data/skavenblight/modonomicon/books/nexus_research/entries/winds_of_magic/wip.json
```

- [ ] **Step 2: Create the 8 Wind categories**

Sort numbers: `nexus`=0, `skaven_engineering`=1 (unchanged), the 8 winds take 2–9 in Wind-enum order,
`dark_magic`=10, `dwarven_archeology` moves to 11 (Step 4).

Create `src/main/resources/data/skavenblight/modonomicon/books/nexus_research/categories/winds_of_magic_hysh.json`:

```json
{
  "book": "skavenblight:nexus_research",
  "id": "skavenblight:winds_of_magic_hysh",
  "name": "category.skavenblight.nexus_research.winds_of_magic_hysh.name",
  "icon": "minecraft:glowstone_dust",
  "sort_number": 2
}
```

Create `.../categories/winds_of_magic_azyr.json`:

```json
{
  "book": "skavenblight:nexus_research",
  "id": "skavenblight:winds_of_magic_azyr",
  "name": "category.skavenblight.nexus_research.winds_of_magic_azyr.name",
  "icon": "minecraft:lapis_lazuli",
  "sort_number": 3
}
```

Create `.../categories/winds_of_magic_chamon.json`:

```json
{
  "book": "skavenblight:nexus_research",
  "id": "skavenblight:winds_of_magic_chamon",
  "name": "category.skavenblight.nexus_research.winds_of_magic_chamon.name",
  "icon": "minecraft:gold_ingot",
  "sort_number": 4
}
```

Create `.../categories/winds_of_magic_ghyran.json`:

```json
{
  "book": "skavenblight:nexus_research",
  "id": "skavenblight:winds_of_magic_ghyran",
  "name": "category.skavenblight.nexus_research.winds_of_magic_ghyran.name",
  "icon": "minecraft:oak_sapling",
  "sort_number": 5
}
```

Create `.../categories/winds_of_magic_aqshy.json`:

```json
{
  "book": "skavenblight:nexus_research",
  "id": "skavenblight:winds_of_magic_aqshy",
  "name": "category.skavenblight.nexus_research.winds_of_magic_aqshy.name",
  "icon": "minecraft:blaze_powder",
  "sort_number": 6
}
```

Create `.../categories/winds_of_magic_ghur.json`:

```json
{
  "book": "skavenblight:nexus_research",
  "id": "skavenblight:winds_of_magic_ghur",
  "name": "category.skavenblight.nexus_research.winds_of_magic_ghur.name",
  "icon": "minecraft:bone",
  "sort_number": 7
}
```

Create `.../categories/winds_of_magic_ulgu.json`:

```json
{
  "book": "skavenblight:nexus_research",
  "id": "skavenblight:winds_of_magic_ulgu",
  "name": "category.skavenblight.nexus_research.winds_of_magic_ulgu.name",
  "icon": "minecraft:ink_sac",
  "sort_number": 8
}
```

Create `.../categories/winds_of_magic_shyish.json`:

```json
{
  "book": "skavenblight:nexus_research",
  "id": "skavenblight:winds_of_magic_shyish",
  "name": "category.skavenblight.nexus_research.winds_of_magic_shyish.name",
  "icon": "minecraft:wither_rose",
  "sort_number": 9
}
```

- [ ] **Step 3: Create the Dark Magic category stub**

Create `.../categories/dark_magic.json`:

```json
{
  "book": "skavenblight:nexus_research",
  "id": "skavenblight:dark_magic",
  "name": "category.skavenblight.nexus_research.dark_magic.name",
  "icon": "minecraft:nether_star",
  "sort_number": 10
}
```

(Unlock-condition gating for Dark Magic — tier 2 in 2+ Winds — is Phase 2+ per the design doc §6/§7;
this stub is unconditionally visible for now, matching how `dwarven_archeology`'s WIP stub already
behaves.)

- [ ] **Step 4: Renumber `dwarven_archeology`**

Edit `.../categories/dwarven_archeology.json`, change `"sort_number": 2` to `"sort_number": 11`.

- [ ] **Step 5: Create the 8 overview entries**

Create `src/main/resources/data/skavenblight/modonomicon/books/nexus_research/entries/winds_of_magic_hysh/overview.json`:

```json
{
  "category": "skavenblight:winds_of_magic_hysh",
  "id": "skavenblight:overview",
  "name": "entry.skavenblight.nexus_research.winds_of_magic_hysh_overview.name",
  "icon": "minecraft:glowstone_dust",
  "x": 0,
  "y": 0,
  "pages": [
    {
      "type": "modonomicon:text",
      "title": "page.skavenblight.nexus_research.winds_of_magic_hysh_overview.title",
      "text": "page.skavenblight.nexus_research.winds_of_magic_hysh_overview.text"
    }
  ]
}
```

Repeat identically for the other 7 winds, changing only the wind id in every place it appears
(`category`, `name`, page `title`/`text` keys, and the folder name):
`winds_of_magic_azyr/overview.json`, `winds_of_magic_chamon/overview.json`,
`winds_of_magic_ghyran/overview.json`, `winds_of_magic_aqshy/overview.json`,
`winds_of_magic_ghur/overview.json`, `winds_of_magic_ulgu/overview.json`,
`winds_of_magic_shyish/overview.json` — use `minecraft:writable_book` as the icon for all 7 of these
(matches the existing `nexus/welcome.json` icon convention; the category file already carries the
wind-specific icon).

Create `src/main/resources/data/skavenblight/modonomicon/books/nexus_research/entries/dark_magic/overview.json`:

```json
{
  "category": "skavenblight:dark_magic",
  "id": "skavenblight:overview",
  "name": "entry.skavenblight.nexus_research.dark_magic_overview.name",
  "icon": "minecraft:nether_star",
  "x": 0,
  "y": 0,
  "pages": [
    {
      "type": "modonomicon:text",
      "title": "page.skavenblight.nexus_research.dark_magic_overview.title",
      "text": "page.skavenblight.nexus_research.dark_magic_overview.text"
    }
  ]
}
```

- [ ] **Step 6: Add lang keys**

In `src/main/resources/assets/skavenblight/lang/en_us.json`, remove the now-stale
`"entry.skavenblight.nexus_research.magic_wip.name"` line and add:

```json
  "category.skavenblight.nexus_research.winds_of_magic_hysh.name": "Hysh — The Light",
  "category.skavenblight.nexus_research.winds_of_magic_azyr.name": "Azyr — The Heavens",
  "category.skavenblight.nexus_research.winds_of_magic_chamon.name": "Chamon — Metal",
  "category.skavenblight.nexus_research.winds_of_magic_ghyran.name": "Ghyran — Life",
  "category.skavenblight.nexus_research.winds_of_magic_aqshy.name": "Aqshy — Fire",
  "category.skavenblight.nexus_research.winds_of_magic_ghur.name": "Ghur — Beasts",
  "category.skavenblight.nexus_research.winds_of_magic_ulgu.name": "Ulgu — Shadow",
  "category.skavenblight.nexus_research.winds_of_magic_shyish.name": "Shyish — Death",
  "category.skavenblight.nexus_research.dark_magic.name": "Dark Magic",

  "entry.skavenblight.nexus_research.winds_of_magic_hysh_overview.name": "The Wind of Light",
  "page.skavenblight.nexus_research.winds_of_magic_hysh_overview.title": "Hysh",
  "page.skavenblight.nexus_research.winds_of_magic_hysh_overview.text": "Hysh, the White Wind, flows strongest by daylight. It is the wind of healing, protection, and banishment — the Order of Light draws on it to ward the innocent and destroy the undead.",

  "entry.skavenblight.nexus_research.winds_of_magic_azyr_overview.name": "The Wind of the Heavens",
  "page.skavenblight.nexus_research.winds_of_magic_azyr_overview.title": "Azyr",
  "page.skavenblight.nexus_research.winds_of_magic_azyr_overview.text": "Azyr, the Blue Wind, whispers of fate and weather. The Celestial College reads its currents to divine the future and command the storm.",

  "entry.skavenblight.nexus_research.winds_of_magic_chamon_overview.name": "The Wind of Metal",
  "page.skavenblight.nexus_research.winds_of_magic_chamon_overview.title": "Chamon",
  "page.skavenblight.nexus_research.winds_of_magic_chamon_overview.text": "Chamon, the Yellow Wind, binds to metal and matter. The Golden Order shapes it into alchemy, transmutation, and enchantment.",

  "entry.skavenblight.nexus_research.winds_of_magic_ghyran_overview.name": "The Wind of Life",
  "page.skavenblight.nexus_research.winds_of_magic_ghyran_overview.title": "Ghyran",
  "page.skavenblight.nexus_research.winds_of_magic_ghyran_overview.text": "Ghyran, the Green Wind, is the breath of growing things. The Jade College tends it to heal the land and command nature's bounty.",

  "entry.skavenblight.nexus_research.winds_of_magic_aqshy_overview.name": "The Wind of Fire",
  "page.skavenblight.nexus_research.winds_of_magic_aqshy_overview.title": "Aqshy",
  "page.skavenblight.nexus_research.winds_of_magic_aqshy_overview.text": "Aqshy, the Red Wind, burns hottest in destruction. The Bright Order channels it into flame, forge, and ruin.",

  "entry.skavenblight.nexus_research.winds_of_magic_ghur_overview.name": "The Wind of Beasts",
  "page.skavenblight.nexus_research.winds_of_magic_ghur_overview.title": "Ghur",
  "page.skavenblight.nexus_research.winds_of_magic_ghur_overview.text": "Ghur, the Brown Wind, runs wild and hungry. The Amber Brotherhood rides it to command beasts and take their shape.",

  "entry.skavenblight.nexus_research.winds_of_magic_ulgu_overview.name": "The Wind of Shadow",
  "page.skavenblight.nexus_research.winds_of_magic_ulgu_overview.title": "Ulgu",
  "page.skavenblight.nexus_research.winds_of_magic_ulgu_overview.text": "Ulgu, the Grey Wind, thickens after dark. The Grey Order weaves it into illusion, fear, and things unseen.",

  "entry.skavenblight.nexus_research.winds_of_magic_shyish_overview.name": "The Wind of Death",
  "page.skavenblight.nexus_research.winds_of_magic_shyish_overview.title": "Shyish",
  "page.skavenblight.nexus_research.winds_of_magic_shyish_overview.text": "Shyish, the Purple Wind, pools where things end. The Amethyst Order commands it over death, decay, and the fates of mortals.",

  "entry.skavenblight.nexus_research.dark_magic_overview.name": "Dark Magic (Dhar)",
  "page.skavenblight.nexus_research.dark_magic_overview.title": "Dhar",
  "page.skavenblight.nexus_research.dark_magic_overview.text": "Where the Winds mix and stagnate, corruption follows. This chapter is locked for now — return once you have mastered at least two schools."
```

- [ ] **Step 6: Manual verification**

Run: `./gradlew runClient`, open the book, confirm: 8 Wind categories appear in order between Skaven
Engineering and Dark Magic, each opens to its overview page with real text (not "WIP"), Dark Magic
appears after them, Dwarven Archeology now appears last. No red/missing-translation-key text anywhere.

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/data/skavenblight/modonomicon src/main/resources/assets/skavenblight/lang/en_us.json
git commit -m "feat(magic): scaffold 8 Wind categories and Dark Magic stub in the research book"
```

---

### Task 6: `CastingResolver`

**Files:**
- Create: `src/main/java/org/ratden/skavenblight/magic/spell/CastingResolver.java`
- Test: `src/test/java/org/ratden/skavenblight/magic/spell/CastingResolverTest.java`

**Interfaces:**
- Produces: `CastingResolver.windLevelBonus(float windLevel) -> int`,
  `CastingResolver.resolve(int aptitude, int windLevelBonus, int castingNumber, int roll) -> CastingResolver.CastResult`,
  and the nested record `CastingResolver.CastResult(boolean success, int degreesOfSuccess, int roll)`.
  `DebugMagicCommands` (Task 10) is the only caller in this plan; it generates the `roll` itself
  (`level.getRandom().nextInt(100) + 1`) and passes it in — keep `resolve` a pure function of its
  inputs so it stays trivially testable and is the single place this formula ever lives (design doc
  §5.2).

This is pure logic with zero Minecraft-type dependencies — no bootstrap needed for its tests.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/ratden/skavenblight/magic/spell/CastingResolverTest.java`:

```java
package org.ratden.skavenblight.magic.spell;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CastingResolverTest {

    @Test
    void windLevelBonusScalesAndCaps() {
        assertEquals(0, CastingResolver.windLevelBonus(0f));
        assertEquals(10, CastingResolver.windLevelBonus(200f));
        assertEquals(50, CastingResolver.windLevelBonus(1000f));
        assertEquals(50, CastingResolver.windLevelBonus(5000f)); // capped
    }

    @Test
    void rollUnderOrEqualTotalSucceeds() {
        // total = 50 (aptitude) + 0 (windLevelBonus) - 30 (castingNumber) = 20
        CastingResolver.CastResult result = CastingResolver.resolve(50, 0, 30, 20);
        assertTrue(result.success());
        assertEquals(20, result.roll());
    }

    @Test
    void rollOverTotalFails() {
        CastingResolver.CastResult result = CastingResolver.resolve(50, 0, 30, 21);
        assertFalse(result.success());
        assertEquals(0, result.degreesOfSuccess());
    }

    @Test
    void degreesOfSuccessScaleWithMargin() {
        // total = 20, roll = 10 -> margin 10 -> 2 degrees
        CastingResolver.CastResult close = CastingResolver.resolve(50, 0, 30, 10);
        assertTrue(close.success());
        assertEquals(2, close.degreesOfSuccess());

        // total = 20, roll = 1 -> margin 19 -> 2 degrees (integer division floor)
        CastingResolver.CastResult wide = CastingResolver.resolve(50, 0, 30, 1);
        assertTrue(wide.success());
        assertEquals(2, wide.degreesOfSuccess());

        // total = 20, roll = 20 -> margin 0 -> 1 degree (barely made it)
        CastingResolver.CastResult barely = CastingResolver.resolve(50, 0, 30, 20);
        assertTrue(barely.success());
        assertEquals(1, barely.degreesOfSuccess());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "org.ratden.skavenblight.magic.spell.CastingResolverTest"`
Expected: FAIL — `CastingResolver` does not exist.

- [ ] **Step 3: Implement `CastingResolver`**

Create `src/main/java/org/ratden/skavenblight/magic/spell/CastingResolver.java`:

```java
package org.ratden.skavenblight.magic.spell;

/**
 * The one place casting-number math lives. success = aptitude + windLevelBonus - castingNumber >= roll,
 * roll is a d100 rolled by the caller (kept out of this class so the formula stays a pure function).
 */
public final class CastingResolver {

    private static final float WIND_LEVEL_SCALE = 20f;
    private static final int WIND_LEVEL_BONUS_CAP = 50;
    private static final int DEGREE_MARGIN_STEP = 10;

    private CastingResolver() {}

    public static int windLevelBonus(float windLevel) {
        int bonus = (int) (windLevel / WIND_LEVEL_SCALE);
        return Math.min(WIND_LEVEL_BONUS_CAP, Math.max(0, bonus));
    }

    public static CastResult resolve(int aptitude, int windLevelBonus, int castingNumber, int roll) {
        int total = aptitude + windLevelBonus - castingNumber;
        boolean success = roll <= total;
        int degrees = success ? Math.max(1, (total - roll) / DEGREE_MARGIN_STEP + 1) : 0;
        return new CastResult(success, degrees, roll);
    }

    public record CastResult(boolean success, int degreesOfSuccess, int roll) {}
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "org.ratden.skavenblight.magic.spell.CastingResolverTest"`
Expected: PASS, 4 tests green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/magic/spell/CastingResolver.java \
        src/test/java/org/ratden/skavenblight/magic/spell/CastingResolverTest.java
git commit -m "feat(magic): add CastingResolver success-check formula"
```

---

### Task 7: `SpellEffect`, `CastingTime`, and `Spell` data model

**Files:**
- Create: `src/main/java/org/ratden/skavenblight/magic/spell/CastingTime.java`
- Create: `src/main/java/org/ratden/skavenblight/magic/spell/SpellEffect.java`
- Create: `src/main/java/org/ratden/skavenblight/magic/spell/Spell.java`
- Test: `src/test/java/org/ratden/skavenblight/magic/spell/SpellCodecTest.java`

**Interfaces:**
- Consumes: `Wind.CODEC` (Task 1).
- Produces: `Spell` record (`wind, tier, castingNumber, castingTime, componentItem, effect,
  descriptionKey`) + `Spell.CODEC`; `SpellEffect` sealed interface with `DamageEffect(float amount)`
  and `MobEffectApply(Holder<MobEffect> effect, int durationTicks, int amplifier)`, both with
  `.apply(LivingEntity caster, LivingEntity target)` and a `type()` discriminator; `SpellEffect.CODEC`
  (dispatch keyed by a `"type"` JSON field: `"damage"` / `"mob_effect"`). `SpellManager` (Task 8) loads
  `Spell` values with this codec; `DebugMagicCommands` (Task 10) calls `spell.effect().apply(...)`.
  Adding a 3rd `SpellEffect` case later means: one new record implementing the interface, one line in
  the dispatch `switch`, zero changes to `Spell` or `SpellManager` — that's the escape-hatch contract
  from design doc §5.1, keep it that way.

- [ ] **Step 1: Write `CastingTime`**

Create `src/main/java/org/ratden/skavenblight/magic/spell/CastingTime.java`:

```java
package org.ratden.skavenblight.magic.spell;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

/**
 * v1 simplification of WFRP casting times: a plain enum, no per-cast channel-length field yet.
 * If a later spell needs a variable channel duration, add a separate int field to Spell rather than
 * parameterizing this enum.
 */
public enum CastingTime implements StringRepresentable {
    INSTANT("instant"),
    HALF_ACTION("half_action"),
    FULL_ACTION("full_action"),
    CHANNELLED("channelled");

    public static final Codec<CastingTime> CODEC = StringRepresentable.fromEnum(CastingTime::values);

    private final String id;

    CastingTime(String id) {
        this.id = id;
    }

    @Override
    public String getSerializedName() {
        return id;
    }
}
```

- [ ] **Step 2: Write the failing codec test (drives `SpellEffect` and `Spell`)**

Create `src/test/java/org/ratden/skavenblight/magic/spell/SpellCodecTest.java`:

```java
package org.ratden.skavenblight.magic.spell;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.ratden.skavenblight.magic.Wind;

import static org.junit.jupiter.api.Assertions.*;

class SpellCodecTest {

    private static final Gson GSON = new Gson();

    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void parsesDamageSpell() {
        String json = """
                {
                  "wind": "aqshy",
                  "tier": 0,
                  "casting_number": 30,
                  "casting_time": "half_action",
                  "effect": { "type": "damage", "amount": 8.0 },
                  "description_key": "spell.skavenblight.fireball.description"
                }
                """;
        JsonElement element = GSON.fromJson(json, JsonElement.class);

        Spell spell = Spell.CODEC.parse(JsonOps.INSTANCE, element).getOrThrow();

        assertEquals(Wind.AQSHY, spell.wind());
        assertEquals(0, spell.tier());
        assertEquals(30, spell.castingNumber());
        assertEquals(CastingTime.HALF_ACTION, spell.castingTime());
        assertTrue(spell.componentItem().isEmpty());
        assertInstanceOf(SpellEffect.DamageEffect.class, spell.effect());
        assertEquals(8.0f, ((SpellEffect.DamageEffect) spell.effect()).amount());
    }

    @Test
    void parsesMobEffectSpell() {
        String json = """
                {
                  "wind": "hysh",
                  "tier": 0,
                  "casting_number": 20,
                  "casting_time": "half_action",
                  "effect": {
                    "type": "mob_effect",
                    "effect": "minecraft:regeneration",
                    "duration_ticks": 200,
                    "amplifier": 1
                  },
                  "description_key": "spell.skavenblight.boon_of_hysh.description"
                }
                """;
        JsonElement element = GSON.fromJson(json, JsonElement.class);

        Spell spell = Spell.CODEC.parse(JsonOps.INSTANCE, element).getOrThrow();

        assertEquals(Wind.HYSH, spell.wind());
        assertInstanceOf(SpellEffect.MobEffectApply.class, spell.effect());
        SpellEffect.MobEffectApply mobEffect = (SpellEffect.MobEffectApply) spell.effect();
        assertEquals(200, mobEffect.durationTicks());
        assertEquals(1, mobEffect.amplifier());
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew test --tests "org.ratden.skavenblight.magic.spell.SpellCodecTest"`
Expected: FAIL — `SpellEffect`/`Spell` do not exist.

- [ ] **Step 4: Implement `SpellEffect`**

Create `src/main/java/org/ratden/skavenblight/magic/spell/SpellEffect.java`:

```java
package org.ratden.skavenblight.magic.spell;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;

/**
 * The escape hatch: 90% of spells are fully data-driven (Spell is a plain record), but the actual
 * game-world effect a spell has needs real code. Keep this a small sealed interface with a handful of
 * concrete cases, NOT a scripting language — a spell that needs a shape not listed here needs a new
 * case added here, not a generic "run this arbitrary logic" case.
 */
public sealed interface SpellEffect permits SpellEffect.DamageEffect, SpellEffect.MobEffectApply {

    Codec<SpellEffect> CODEC = Codec.STRING.dispatch("type", SpellEffect::type, type -> switch (type) {
        case "damage" -> DamageEffect.CODEC;
        case "mob_effect" -> MobEffectApply.CODEC;
        default -> throw new IllegalArgumentException("Unknown spell effect type: " + type);
    });

    void apply(LivingEntity caster, LivingEntity target);

    String type();

    record DamageEffect(float amount) implements SpellEffect {
        public static final MapCodec<DamageEffect> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                com.mojang.serialization.Codec.FLOAT.fieldOf("amount").forGetter(DamageEffect::amount)
        ).apply(instance, DamageEffect::new));

        @Override
        public void apply(LivingEntity caster, LivingEntity target) {
            target.hurt(target.damageSources().magic(), amount);
        }

        @Override
        public String type() {
            return "damage";
        }
    }

    record MobEffectApply(Holder<MobEffect> effect, int durationTicks, int amplifier) implements SpellEffect {
        public static final MapCodec<MobEffectApply> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                BuiltInRegistries.MOB_EFFECT.holderByNameCodec().fieldOf("effect").forGetter(MobEffectApply::effect),
                com.mojang.serialization.Codec.INT.fieldOf("duration_ticks").forGetter(MobEffectApply::durationTicks),
                com.mojang.serialization.Codec.INT.optionalFieldOf("amplifier", 0).forGetter(MobEffectApply::amplifier)
        ).apply(instance, MobEffectApply::new));

        @Override
        public void apply(LivingEntity caster, LivingEntity target) {
            target.addEffect(new MobEffectInstance(effect, durationTicks, amplifier));
        }

        @Override
        public String type() {
            return "mob_effect";
        }
    }
}
```

**Known version-drift risk:** if `Codec.STRING.dispatch(...)` doesn't compile as written (DFU dispatch
overloads occasionally shift between Minecraft versions), the fix is always the same shape — a
`Codec<String>` (or whatever the type-key codec is) calling `.dispatch(keyFieldName, instanceToKey,
keyToMapCodec)`. Check `Codec`'s dispatch overloads in your IDE if this line red-squiggles; don't
change the overall dispatch-by-`"type"`-field design.

- [ ] **Step 5: Implement `Spell`**

Create `src/main/java/org/ratden/skavenblight/magic/spell/Spell.java`:

```java
package org.ratden.skavenblight.magic.spell;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.item.crafting.Ingredient;
import org.ratden.skavenblight.magic.Wind;

import java.util.Optional;

public record Spell(
        Wind wind,
        int tier,
        int castingNumber,
        CastingTime castingTime,
        Optional<Ingredient> componentItem,
        SpellEffect effect,
        String descriptionKey
) {
    public static final Codec<Spell> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Wind.CODEC.fieldOf("wind").forGetter(Spell::wind),
            Codec.INT.fieldOf("tier").forGetter(Spell::tier),
            Codec.INT.fieldOf("casting_number").forGetter(Spell::castingNumber),
            CastingTime.CODEC.fieldOf("casting_time").forGetter(Spell::castingTime),
            Ingredient.CODEC.optionalFieldOf("component_item").forGetter(Spell::componentItem),
            SpellEffect.CODEC.fieldOf("effect").forGetter(Spell::effect),
            Codec.STRING.fieldOf("description_key").forGetter(Spell::descriptionKey)
    ).apply(instance, Spell::new));
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew test --tests "org.ratden.skavenblight.magic.spell.SpellCodecTest"`
Expected: PASS, 2 tests green. (The `Bootstrap.bootStrap()` call in `@BeforeAll` may take a second or
two — that's normal, it's populating vanilla's registries so `minecraft:regeneration` resolves.)

- [ ] **Step 7: Run the full test suite**

Run: `./gradlew test`
Expected: all tests from Tasks 1, 2, 6, and this task pass.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/magic/spell/CastingTime.java \
        src/main/java/org/ratden/skavenblight/magic/spell/SpellEffect.java \
        src/main/java/org/ratden/skavenblight/magic/spell/Spell.java \
        src/test/java/org/ratden/skavenblight/magic/spell/SpellCodecTest.java
git commit -m "feat(magic): add Spell data model with DamageEffect/MobEffectApply"
```

---

### Task 8: `SpellManager` + `ModMagic` reload-listener wiring

**Files:**
- Create: `src/main/java/org/ratden/skavenblight/magic/spell/SpellManager.java`
- Create: `src/main/java/org/ratden/skavenblight/magic/ModMagic.java`
- Modify: `src/main/java/org/ratden/skavenblight/Skavenblight.java`

**Interfaces:**
- Consumes: `Spell.CODEC` (Task 7).
- Produces: `SpellManager.get(ResourceLocation id) -> Spell` (nullable), `SpellManager.getAll() ->
  Map<ResourceLocation, Spell>`. `DebugMagicCommands` (Task 10) and Task 11's authored spells both
  depend on this being wired to `data/skavenblight/magic/spells/**.json`.

No automated test here — a `SimpleJsonResourceReloadListener` needs a real `ResourceManager` to invoke
`apply` against, which means a running game. Verified manually in Task 11 once real spell JSON exists to
reload.

- [ ] **Step 1: Implement `SpellManager`**

Create `src/main/java/org/ratden/skavenblight/magic/spell/SpellManager.java`:

```java
package org.ratden.skavenblight.magic.spell;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;

/** Loads data/<namespace>/magic/spells/**.json, exactly like vanilla loads recipes. */
public class SpellManager extends SimpleJsonResourceReloadListener {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static Map<ResourceLocation, Spell> spells = Map.of();

    public SpellManager() {
        super(new Gson(), "magic/spells");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> resourceList, ResourceManager resourceManager, ProfilerFiller profiler) {
        Map<ResourceLocation, Spell> result = new HashMap<>();
        resourceList.forEach((id, json) -> Spell.CODEC.parse(JsonOps.INSTANCE, json)
                .resultOrPartial(error -> LOGGER.error("Failed to parse spell {}: {}", id, error))
                .ifPresent(spell -> result.put(id, spell)));
        spells = Map.copyOf(result);
        LOGGER.info("Loaded {} spells", spells.size());
    }

    public static Spell get(ResourceLocation id) {
        return spells.get(id);
    }

    public static Map<ResourceLocation, Spell> getAll() {
        return spells;
    }
}
```

- [ ] **Step 2: Create `ModMagic`**

Create `src/main/java/org/ratden/skavenblight/magic/ModMagic.java`:

```java
package org.ratden.skavenblight.magic;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.AddReloadListenersEvent;
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.magic.player.ModAttachments;
import org.ratden.skavenblight.magic.spell.SpellManager;

/** Wires the magic system into the mod event bus. One method per concern, mirrors Skavenblight.java. */
public class ModMagic {

    public static void register(IEventBus modEventBus) {
        ModAttachments.register(modEventBus);
        modEventBus.addListener(ModMagic::onAddReloadListeners);
    }

    private static void onAddReloadListeners(AddReloadListenersEvent event) {
        event.addListener(
                ResourceLocation.fromNamespaceAndPath(Skavenblight.MODID, "spells"),
                new SpellManager()
        );
    }
}
```

**Known version-drift risk:** if `AddReloadListenersEvent#addListener` doesn't accept
`(ResourceLocation, PreparableReloadListener)` in this NeoForge version, check the event class for the
actual overload (older versions took just the listener) and adjust this one call — nothing else in this
task depends on the exact overload shape.

- [ ] **Step 3: Wire `ModMagic` into `Skavenblight.java`**

This task references `ModAttachments`, which doesn't exist until Task 9. To keep this task's own build
green, stub it now — a package-private placeholder that Task 9 replaces with the real implementation:

Create a temporary `src/main/java/org/ratden/skavenblight/magic/player/ModAttachments.java`:

```java
package org.ratden.skavenblight.magic.player;

import net.neoforged.bus.api.IEventBus;

// Placeholder — Task 9 replaces this with the real AttachmentType registration.
public class ModAttachments {
    public static void register(IEventBus modEventBus) {
        // no-op until Task 9
    }
}
```

In `src/main/java/org/ratden/skavenblight/Skavenblight.java`, add the import:

```java
import org.ratden.skavenblight.magic.ModMagic;
```

In the constructor, alongside the other `modEventBus`/registration calls:

```java
ModMagic.register(modEventBus);
```

- [ ] **Step 4: Compile**

Run: `./gradlew compileJava`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/magic/spell/SpellManager.java \
        src/main/java/org/ratden/skavenblight/magic/ModMagic.java \
        src/main/java/org/ratden/skavenblight/magic/player/ModAttachments.java \
        src/main/java/org/ratden/skavenblight/Skavenblight.java
git commit -m "feat(magic): wire SpellManager as a datapack reload listener"
```

---

### Task 9: `PlayerMagicData` attachment

**Files:**
- Create: `src/main/java/org/ratden/skavenblight/magic/player/PlayerMagicData.java`
- Modify: `src/main/java/org/ratden/skavenblight/magic/player/ModAttachments.java` (replace Task 8's stub)

**Interfaces:**
- Produces: `PlayerMagicData(Map<Wind,Integer> tier, Map<Wind,Integer> aptitude,
  Set<ResourceLocation> knownSpells)` + `.CODEC` + `.EMPTY`, with `getAptitude(Wind)`, `getTier(Wind)`,
  `withAptitude(Wind, int)`, `withTier(Wind, int)` convenience methods; `ModAttachments.PLAYER_MAGIC`
  (`DeferredHolder<AttachmentType<?>, AttachmentType<PlayerMagicData>>`). `DebugMagicCommands` (Task 10)
  is the only consumer in this plan — it reads via `player.getData(ModAttachments.PLAYER_MAGIC.get())`
  and writes via `player.setData(ModAttachments.PLAYER_MAGIC.get(), newData)`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/ratden/skavenblight/magic/player/PlayerMagicDataTest.java`:

```java
package org.ratden.skavenblight.magic.player;

import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;
import org.ratden.skavenblight.magic.Wind;

import static org.junit.jupiter.api.Assertions.*;

class PlayerMagicDataTest {

    @Test
    void emptyHasZeroForEverything() {
        assertEquals(0, PlayerMagicData.EMPTY.getAptitude(Wind.HYSH));
        assertEquals(0, PlayerMagicData.EMPTY.getTier(Wind.HYSH));
        assertTrue(PlayerMagicData.EMPTY.knownSpells().isEmpty());
    }

    @Test
    void withAptitudeReturnsUpdatedCopy() {
        PlayerMagicData updated = PlayerMagicData.EMPTY.withAptitude(Wind.AQSHY, 35);

        assertEquals(35, updated.getAptitude(Wind.AQSHY));
        assertEquals(0, updated.getAptitude(Wind.HYSH));
        assertEquals(0, PlayerMagicData.EMPTY.getAptitude(Wind.AQSHY)); // original untouched
    }

    @Test
    void codecRoundTrips() {
        PlayerMagicData data = PlayerMagicData.EMPTY
                .withAptitude(Wind.AQSHY, 35)
                .withTier(Wind.AQSHY, 1);

        var encoded = PlayerMagicData.CODEC.encodeStart(JsonOps.INSTANCE, data).getOrThrow();
        PlayerMagicData decoded = PlayerMagicData.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();

        assertEquals(35, decoded.getAptitude(Wind.AQSHY));
        assertEquals(1, decoded.getTier(Wind.AQSHY));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "org.ratden.skavenblight.magic.player.PlayerMagicDataTest"`
Expected: FAIL — `PlayerMagicData` does not exist.

- [ ] **Step 3: Implement `PlayerMagicData`**

Create `src/main/java/org/ratden/skavenblight/magic/player/PlayerMagicData.java`:

```java
package org.ratden.skavenblight.magic.player;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;
import org.ratden.skavenblight.magic.Wind;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public record PlayerMagicData(
        Map<Wind, Integer> tier,
        Map<Wind, Integer> aptitude,
        Set<ResourceLocation> knownSpells
) {
    public static final Codec<PlayerMagicData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.unboundedMap(Wind.CODEC, Codec.INT).fieldOf("tier").forGetter(PlayerMagicData::tier),
            Codec.unboundedMap(Wind.CODEC, Codec.INT).fieldOf("aptitude").forGetter(PlayerMagicData::aptitude),
            ResourceLocation.CODEC.listOf()
                    .xmap(HashSet::new, ArrayList::new)
                    .fieldOf("known_spells")
                    .forGetter(PlayerMagicData::knownSpells)
    ).apply(instance, PlayerMagicData::new));

    public static final PlayerMagicData EMPTY = new PlayerMagicData(Map.of(), Map.of(), Set.of());

    public int getAptitude(Wind wind) {
        return aptitude.getOrDefault(wind, 0);
    }

    public int getTier(Wind wind) {
        return tier.getOrDefault(wind, 0);
    }

    public PlayerMagicData withAptitude(Wind wind, int value) {
        Map<Wind, Integer> updated = new HashMap<>(aptitude);
        updated.put(wind, value);
        return new PlayerMagicData(tier, updated, knownSpells);
    }

    public PlayerMagicData withTier(Wind wind, int value) {
        Map<Wind, Integer> updated = new HashMap<>(tier);
        updated.put(wind, value);
        return new PlayerMagicData(updated, aptitude, knownSpells);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "org.ratden.skavenblight.magic.player.PlayerMagicDataTest"`
Expected: PASS, 3 tests green.

- [ ] **Step 5: Replace the `ModAttachments` stub with the real registration**

Replace the full contents of `src/main/java/org/ratden/skavenblight/magic/player/ModAttachments.java`:

```java
package org.ratden.skavenblight.magic.player;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.ratden.skavenblight.Skavenblight;

public class ModAttachments {

    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, Skavenblight.MODID);

    public static final DeferredHolder<AttachmentType<?>, AttachmentType<PlayerMagicData>> PLAYER_MAGIC =
            ATTACHMENT_TYPES.register(
                    "player_magic",
                    () -> AttachmentType.builder(() -> PlayerMagicData.EMPTY)
                            .serialize(PlayerMagicData.CODEC)
                            .build()
            );

    public static void register(IEventBus modEventBus) {
        ATTACHMENT_TYPES.register(modEventBus);
    }
}
```

- [ ] **Step 6: Compile**

Run: `./gradlew compileJava`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/magic/player/PlayerMagicData.java \
        src/main/java/org/ratden/skavenblight/magic/player/ModAttachments.java \
        src/test/java/org/ratden/skavenblight/magic/player/PlayerMagicDataTest.java
git commit -m "feat(magic): add PlayerMagicData attachment"
```

---

### Task 10: `DebugMagicCommands` — cast, set_aptitude, info

**Files:**
- Create: `src/main/java/org/ratden/skavenblight/command/debug/DebugMagicCommands.java`
- Modify: `src/main/java/org/ratden/skavenblight/command/SkavenDebugCommand.java`

**Interfaces:**
- Consumes: `SpellManager.get` (Task 8), `CastingResolver.resolve`/`windLevelBonus` (Task 6),
  `WindGridManager.get(level).getOrCreate` (Task 3), `ModAttachments.PLAYER_MAGIC` /
  `PlayerMagicData` (Task 9).
- Produces: `/skavendebug magic cast <spell>`, `/skavendebug magic set_aptitude <wind> <amount>`,
  `/skavendebug magic info` — this is the end-to-end proof the whole pipeline works, and how Task 11's
  authored spells get verified.

This task has no unit test of its own (it's a command handler wiring already-tested pieces together);
its correctness is verified manually alongside Task 11, once there's at least one real spell JSON file
to cast.

- [ ] **Step 1: Implement `DebugMagicCommands`**

Create `src/main/java/org/ratden/skavenblight/command/debug/DebugMagicCommands.java`:

```java
package org.ratden.skavenblight.command.debug;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.ratden.skavenblight.magic.Wind;
import org.ratden.skavenblight.magic.player.ModAttachments;
import org.ratden.skavenblight.magic.player.PlayerMagicData;
import org.ratden.skavenblight.magic.spell.CastingResolver;
import org.ratden.skavenblight.magic.spell.Spell;
import org.ratden.skavenblight.magic.spell.SpellManager;
import org.ratden.skavenblight.magic.wind.ChunkWindState;
import org.ratden.skavenblight.magic.wind.WindGridManager;

public class DebugMagicCommands {

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("magic")
                .then(Commands.literal("cast")
                        .then(Commands.argument("spell", ResourceLocationArgument.id())
                                .executes(context -> cast(context.getSource(),
                                        ResourceLocationArgument.getId(context, "spell")))))
                .then(Commands.literal("set_aptitude")
                        .then(Commands.argument("wind", com.mojang.brigadier.arguments.StringArgumentType.word())
                                .then(Commands.argument("amount", IntegerArgumentType.integer(0, 100))
                                        .executes(context -> setAptitude(context.getSource(),
                                                com.mojang.brigadier.arguments.StringArgumentType.getString(context, "wind"),
                                                IntegerArgumentType.getInteger(context, "amount"))))))
                .then(Commands.literal("info")
                        .executes(context -> info(context.getSource())));
    }

    private static int cast(CommandSourceStack source, ResourceLocation spellId) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("This command must be run by a player."));
            return 0;
        }

        Spell spell = SpellManager.get(spellId);
        if (spell == null) {
            source.sendFailure(Component.literal("Unknown spell: " + spellId));
            return 0;
        }

        PlayerMagicData data = player.getData(ModAttachments.PLAYER_MAGIC.get());
        int aptitude = data.getAptitude(spell.wind());

        ServerLevel level = source.getLevel();
        ChunkPos pos = new ChunkPos(player.blockPosition());
        ChunkWindState windState = WindGridManager.get(level).getOrCreate(pos);
        float windLevel = windState.getCurrent(spell.wind());
        int windLevelBonus = CastingResolver.windLevelBonus(windLevel);

        int roll = level.getRandom().nextInt(100) + 1;
        CastingResolver.CastResult result =
                CastingResolver.resolve(aptitude, windLevelBonus, spell.castingNumber(), roll);

        if (!result.success()) {
            int total = aptitude + windLevelBonus - spell.castingNumber();
            source.sendFailure(Component.literal(
                    "Cast failed: rolled " + roll + ", needed <= " + total
                            + " (aptitude " + aptitude + " + wind bonus " + windLevelBonus
                            + " - casting number " + spell.castingNumber() + ")"));
            return 0;
        }

        spell.effect().apply(player, player);
        windState.setCurrent(spell.wind(), Math.max(0f, windLevel - spell.castingNumber()));

        source.sendSuccess(() -> Component.literal(
                "Cast " + spellId + " successfully! (rolled " + roll
                        + ", degrees of success: " + result.degreesOfSuccess() + ")"), true);
        return 1;
    }

    private static int setAptitude(CommandSourceStack source, String windId, int amount) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("This command must be run by a player."));
            return 0;
        }

        Wind wind = parseWind(windId);
        if (wind == null) {
            source.sendFailure(Component.literal("Unknown wind: " + windId));
            return 0;
        }

        PlayerMagicData data = player.getData(ModAttachments.PLAYER_MAGIC.get());
        player.setData(ModAttachments.PLAYER_MAGIC.get(), data.withAptitude(wind, amount));

        source.sendSuccess(() -> Component.literal(
                "Set " + wind.getLoreName() + " aptitude to " + amount), true);
        return 1;
    }

    private static int info(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("This command must be run by a player."));
            return 0;
        }

        PlayerMagicData data = player.getData(ModAttachments.PLAYER_MAGIC.get());
        StringBuilder sb = new StringBuilder("Magic data:");
        for (Wind wind : Wind.values()) {
            sb.append("\n").append(wind.getLoreName())
                    .append(": tier=").append(data.getTier(wind))
                    .append(", aptitude=").append(data.getAptitude(wind));
        }
        sb.append("\nKnown spells: ").append(data.knownSpells());
        String message = sb.toString();
        source.sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    private static Wind parseWind(String id) {
        for (Wind wind : Wind.values()) {
            if (wind.getSerializedName().equals(id)) {
                return wind;
            }
        }
        return null;
    }
}
```

- [ ] **Step 2: Register it**

In `src/main/java/org/ratden/skavenblight/command/SkavenDebugCommand.java`, add:

```java
.then(DebugWindCommands.register())
.then(DebugMagicCommands.register())
```

- [ ] **Step 3: Compile**

Run: `./gradlew compileJava`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/command/debug/DebugMagicCommands.java \
        src/main/java/org/ratden/skavenblight/command/SkavenDebugCommand.java
git commit -m "feat(magic): add /skavendebug magic cast|set_aptitude|info commands"
```

(Full manual verification of casting happens in Task 11, once real spell JSON exists.)

---

### Task 11: Author 16 spells (2 per Wind) + Modonomicon petty-spell pages + end-to-end verification

**Files:**
- Create: `data/skavenblight/magic/spells/<wind>/<spell_id>.json` — 16 files
- Create: `.../entries/winds_of_magic_<wind>/petty_spells.json` — 8 files
- Modify: `src/main/resources/assets/skavenblight/lang/en_us.json`

**Interfaces:**
- Consumes: `Spell.CODEC` (Task 7), `SpellManager` (Task 8), the 8 categories from Task 5.
- Produces: the full seed content set this whole plan exists to prove — every Wind is castable, every
  Wind has a book page describing its spells.

All 16 spells use only the two `SpellEffect` cases built in Task 7 (`damage`, `mob_effect`) — that's a
deliberate scope cut for this proof phase; spells needing summons, block transforms, or wind-drain
effects (per the full catalog in `docs/superpowers/20260728windsofmagicdesign.md` §8) are Phase 2+ once
more `SpellEffect` cases exist.

- [ ] **Step 1: Author the Hysh spells (full worked example — copy this pattern exactly for the rest)**

Create `src/main/resources/data/skavenblight/magic/spells/hysh/boon_of_hysh.json`:

```json
{
  "wind": "hysh",
  "tier": 0,
  "casting_number": 20,
  "casting_time": "half_action",
  "effect": {
    "type": "mob_effect",
    "effect": "minecraft:regeneration",
    "duration_ticks": 200,
    "amplifier": 1
  },
  "description_key": "spell.skavenblight.boon_of_hysh.description"
}
```

Create `src/main/resources/data/skavenblight/magic/spells/hysh/blinding_light.json`:

```json
{
  "wind": "hysh",
  "tier": 0,
  "casting_number": 25,
  "casting_time": "instant",
  "effect": {
    "type": "mob_effect",
    "effect": "minecraft:blindness",
    "duration_ticks": 100,
    "amplifier": 0
  },
  "description_key": "spell.skavenblight.blinding_light.description"
}
```

- [ ] **Step 2: Author the remaining 14 spells (7 Winds x 2), following the exact same file/field shape**

Create `src/main/resources/data/skavenblight/magic/spells/azyr/lightning_bolt.json`:

```json
{
  "wind": "azyr",
  "tier": 0,
  "casting_number": 30,
  "casting_time": "half_action",
  "effect": { "type": "damage", "amount": 6.0 },
  "description_key": "spell.skavenblight.lightning_bolt.description"
}
```

Create `src/main/resources/data/skavenblight/magic/spells/azyr/starshine.json`:

```json
{
  "wind": "azyr",
  "tier": 0,
  "casting_number": 20,
  "casting_time": "half_action",
  "effect": {
    "type": "mob_effect",
    "effect": "minecraft:slow_falling",
    "duration_ticks": 200,
    "amplifier": 0
  },
  "description_key": "spell.skavenblight.starshine.description"
}
```

Create `src/main/resources/data/skavenblight/magic/spells/chamon/curse_of_rust.json`:

```json
{
  "wind": "chamon",
  "tier": 0,
  "casting_number": 25,
  "casting_time": "half_action",
  "effect": {
    "type": "mob_effect",
    "effect": "minecraft:weakness",
    "duration_ticks": 200,
    "amplifier": 1
  },
  "description_key": "spell.skavenblight.curse_of_rust.description"
}
```

Create `src/main/resources/data/skavenblight/magic/spells/chamon/armour_of_lead.json`:

```json
{
  "wind": "chamon",
  "tier": 0,
  "casting_number": 20,
  "casting_time": "half_action",
  "effect": {
    "type": "mob_effect",
    "effect": "minecraft:resistance",
    "duration_ticks": 200,
    "amplifier": 0
  },
  "description_key": "spell.skavenblight.armour_of_lead.description"
}
```

Create `src/main/resources/data/skavenblight/magic/spells/ghyran/earthblood.json`:

```json
{
  "wind": "ghyran",
  "tier": 0,
  "casting_number": 20,
  "casting_time": "half_action",
  "effect": {
    "type": "mob_effect",
    "effect": "minecraft:regeneration",
    "duration_ticks": 300,
    "amplifier": 0
  },
  "description_key": "spell.skavenblight.earthblood.description"
}
```

Create `src/main/resources/data/skavenblight/magic/spells/ghyran/curse_of_thorns.json`:

```json
{
  "wind": "ghyran",
  "tier": 0,
  "casting_number": 25,
  "casting_time": "instant",
  "effect": { "type": "damage", "amount": 3.0 },
  "description_key": "spell.skavenblight.curse_of_thorns.description"
}
```

Create `src/main/resources/data/skavenblight/magic/spells/aqshy/fireball.json`:

```json
{
  "wind": "aqshy",
  "tier": 0,
  "casting_number": 30,
  "casting_time": "half_action",
  "effect": { "type": "damage", "amount": 8.0 },
  "description_key": "spell.skavenblight.fireball.description"
}
```

Create `src/main/resources/data/skavenblight/magic/spells/aqshy/shield_of_aqshy.json`:

```json
{
  "wind": "aqshy",
  "tier": 0,
  "casting_number": 20,
  "casting_time": "half_action",
  "effect": {
    "type": "mob_effect",
    "effect": "minecraft:fire_resistance",
    "duration_ticks": 600,
    "amplifier": 0
  },
  "description_key": "spell.skavenblight.shield_of_aqshy.description"
}
```

Create `src/main/resources/data/skavenblight/magic/spells/ghur/claws_of_fury.json`:

```json
{
  "wind": "ghur",
  "tier": 0,
  "casting_number": 20,
  "casting_time": "half_action",
  "effect": {
    "type": "mob_effect",
    "effect": "minecraft:strength",
    "duration_ticks": 200,
    "amplifier": 0
  },
  "description_key": "spell.skavenblight.claws_of_fury.description"
}
```

Create `src/main/resources/data/skavenblight/magic/spells/ghur/cowering_beasts.json`:

```json
{
  "wind": "ghur",
  "tier": 0,
  "casting_number": 25,
  "casting_time": "instant",
  "effect": {
    "type": "mob_effect",
    "effect": "minecraft:weakness",
    "duration_ticks": 100,
    "amplifier": 0
  },
  "description_key": "spell.skavenblight.cowering_beasts.description"
}
```

Create `src/main/resources/data/skavenblight/magic/spells/ulgu/bewilder.json`:

```json
{
  "wind": "ulgu",
  "tier": 0,
  "casting_number": 20,
  "casting_time": "instant",
  "effect": {
    "type": "mob_effect",
    "effect": "minecraft:nausea",
    "duration_ticks": 100,
    "amplifier": 0
  },
  "description_key": "spell.skavenblight.bewilder.description"
}
```

Create `src/main/resources/data/skavenblight/magic/spells/ulgu/shroud_of_invisibility.json`:

```json
{
  "wind": "ulgu",
  "tier": 0,
  "casting_number": 30,
  "casting_time": "half_action",
  "effect": {
    "type": "mob_effect",
    "effect": "minecraft:invisibility",
    "duration_ticks": 200,
    "amplifier": 0
  },
  "description_key": "spell.skavenblight.shroud_of_invisibility.description"
}
```

Create `src/main/resources/data/skavenblight/magic/spells/shyish/steal_life.json`:

```json
{
  "wind": "shyish",
  "tier": 0,
  "casting_number": 30,
  "casting_time": "half_action",
  "effect": { "type": "damage", "amount": 4.0 },
  "description_key": "spell.skavenblight.steal_life.description"
}
```

Create `src/main/resources/data/skavenblight/magic/spells/shyish/icy_grip_of_death.json`:

```json
{
  "wind": "shyish",
  "tier": 0,
  "casting_number": 25,
  "casting_time": "half_action",
  "effect": {
    "type": "mob_effect",
    "effect": "minecraft:slowness",
    "duration_ticks": 200,
    "amplifier": 1
  },
  "description_key": "spell.skavenblight.icy_grip_of_death.description"
}
```

- [ ] **Step 3: Add a `petty_spells` Modonomicon entry per Wind**

Create `src/main/resources/data/skavenblight/modonomicon/books/nexus_research/entries/winds_of_magic_hysh/petty_spells.json`:

```json
{
  "category": "skavenblight:winds_of_magic_hysh",
  "id": "skavenblight:petty_spells",
  "name": "entry.skavenblight.nexus_research.winds_of_magic_hysh_petty_spells.name",
  "icon": "minecraft:glowstone_dust",
  "x": 1,
  "y": 0,
  "parent": "skavenblight:overview",
  "pages": [
    {
      "type": "modonomicon:text",
      "title": "spell.skavenblight.boon_of_hysh.name",
      "text": "spell.skavenblight.boon_of_hysh.description"
    },
    {
      "type": "modonomicon:text",
      "title": "spell.skavenblight.blinding_light.name",
      "text": "spell.skavenblight.blinding_light.description"
    }
  ]
}
```

Repeat for the other 7 winds, same shape, `x: 1, y: 0, parent: "skavenblight:overview"`, `category`
matching that wind's category id, referencing that wind's own 2 spell name/description lang keys:
`winds_of_magic_azyr/petty_spells.json` (lightning_bolt, starshine),
`winds_of_magic_chamon/petty_spells.json` (curse_of_rust, armour_of_lead),
`winds_of_magic_ghyran/petty_spells.json` (earthblood, curse_of_thorns),
`winds_of_magic_aqshy/petty_spells.json` (fireball, shield_of_aqshy),
`winds_of_magic_ghur/petty_spells.json` (claws_of_fury, cowering_beasts),
`winds_of_magic_ulgu/petty_spells.json` (bewilder, shroud_of_invisibility),
`winds_of_magic_shyish/petty_spells.json` (steal_life, icy_grip_of_death). Icons: reuse each category's
icon from Task 5 (lapis_lazuli, gold_ingot, oak_sapling, blaze_powder, bone, ink_sac, wither_rose
respectively).

- [ ] **Step 4: Add every spell name/description lang key**

In `src/main/resources/assets/skavenblight/lang/en_us.json`, add:

```json
  "entry.skavenblight.nexus_research.winds_of_magic_hysh_petty_spells.name": "Petty Spells",
  "entry.skavenblight.nexus_research.winds_of_magic_azyr_petty_spells.name": "Petty Spells",
  "entry.skavenblight.nexus_research.winds_of_magic_chamon_petty_spells.name": "Petty Spells",
  "entry.skavenblight.nexus_research.winds_of_magic_ghyran_petty_spells.name": "Petty Spells",
  "entry.skavenblight.nexus_research.winds_of_magic_aqshy_petty_spells.name": "Petty Spells",
  "entry.skavenblight.nexus_research.winds_of_magic_ghur_petty_spells.name": "Petty Spells",
  "entry.skavenblight.nexus_research.winds_of_magic_ulgu_petty_spells.name": "Petty Spells",
  "entry.skavenblight.nexus_research.winds_of_magic_shyish_petty_spells.name": "Petty Spells",

  "spell.skavenblight.boon_of_hysh.name": "Boon of Hysh",
  "spell.skavenblight.boon_of_hysh.description": "A soft white light bathes the caster, knitting flesh and easing pain.",
  "spell.skavenblight.blinding_light.name": "Blinding Light",
  "spell.skavenblight.blinding_light.description": "A flare of pure white light sears the eyes of all who look upon it.",

  "spell.skavenblight.lightning_bolt.name": "Lightning Bolt",
  "spell.skavenblight.lightning_bolt.description": "A crackling blue bolt leaps from the caster's hand, born of the storm-wracked heavens.",
  "spell.skavenblight.starshine.name": "Starshine",
  "spell.skavenblight.starshine.description": "The caster's fall slows as if drifting gently down from the night sky.",

  "spell.skavenblight.curse_of_rust.name": "Curse of Rust",
  "spell.skavenblight.curse_of_rust.description": "Metal pits and flakes, weakening the target's grip and guard.",
  "spell.skavenblight.armour_of_lead.name": "Armour of Lead",
  "spell.skavenblight.armour_of_lead.description": "A dull grey sheen of transmuted metal hardens over the caster's skin.",

  "spell.skavenblight.earthblood.name": "Earthblood",
  "spell.skavenblight.earthblood.description": "The green vitality of growing things flows into the caster's veins.",
  "spell.skavenblight.curse_of_thorns.name": "Curse of Thorns",
  "spell.skavenblight.curse_of_thorns.description": "Thorned vines erupt and lash at the target.",

  "spell.skavenblight.fireball.name": "Fireball",
  "spell.skavenblight.fireball.description": "A roaring ball of flame bursts from the caster's outstretched hand.",
  "spell.skavenblight.shield_of_aqshy.name": "Shield of Aqshy",
  "spell.skavenblight.shield_of_aqshy.description": "A shimmering heat haze wreathes the caster, turning aside flame.",

  "spell.skavenblight.claws_of_fury.name": "Claws of Fury",
  "spell.skavenblight.claws_of_fury.description": "The caster's muscles surge with bestial, savage strength.",
  "spell.skavenblight.cowering_beasts.name": "Cowering Beasts",
  "spell.skavenblight.cowering_beasts.description": "A primal snarl of dominance saps the target's will to fight.",

  "spell.skavenblight.bewilder.name": "Bewilder",
  "spell.skavenblight.bewilder.description": "Shifting grey shadows swim before the target's eyes, sowing confusion.",
  "spell.skavenblight.shroud_of_invisibility.name": "Shroud of Invisibility",
  "spell.skavenblight.shroud_of_invisibility.description": "Ulgu's grey mist wraps the caster, bending sight away from them.",

  "spell.skavenblight.steal_life.name": "Steal Life",
  "spell.skavenblight.steal_life.description": "The caster draws the vital warmth from the target's body.",
  "spell.skavenblight.icy_grip_of_death.name": "Icy Grip of Death",
  "spell.skavenblight.icy_grip_of_death.description": "A graveyard chill seizes the target's limbs, slowing them to a crawl."
```

- [ ] **Step 5: Manual end-to-end verification**

Run: `./gradlew runClient`. In a single-player world, as an OP:

1. `/skavendebug magic set_aptitude aqshy 60`
2. `/skavendebug magic cast skavenblight:fireball` repeatedly (aptitude 60, casting number 30 —
   should succeed most of the time even with 0 wind bonus). Expected: "Cast ... successfully!" message,
   and you take/deal fire damage feedback (self-targeted in this debug command, so you'll see your own
   health drop — that's expected, real targeting is a Phase 2 concern).
3. `/skavendebug magic cast skavenblight:boon_of_hysh` — expected to fail most of the time (aptitude 0
   for Hysh, casting number 20) — confirm the failure message shows the math
   (`aptitude 0 + wind bonus X - casting number 20`).
4. `/skavendebug magic set_aptitude hysh 50`, cast `boon_of_hysh` again — expect success and a visible
   Regeneration effect icon in the player's HUD.
5. `/skavendebug magic info` — confirm it lists all 8 Winds with the aptitudes just set.
6. Open the research book, navigate to each of the 8 Wind categories, confirm each now has an
   "Overview" and a "Petty Spells" page showing both spell names and descriptions with no missing
   translation keys.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/data/skavenblight/magic src/main/resources/data/skavenblight/modonomicon src/main/resources/assets/skavenblight/lang/en_us.json
git commit -m "feat(magic): author 16 seed spells (2 per Wind) and their book pages"
```

---

## Self-Review Notes

- **Spec coverage:** Chunk wind system with drift-to-baseline (§3), one influence wired end-to-end as
  proof (§3.3.3), player attribute storage (§4, minus the advancement-gating layer — explicitly deferred,
  see below), spell data model + JSON pipeline + casting-number formula (§5), Modonomicon category
  restructure (§7), package layout (§12) are all covered by Tasks 1–11. Deliberately **not** covered:
  biome/tagged-block influences (§3.3.1–2, §3.3.4), magic blocks/power stones (§3.4 tiers 2–3), the
  additional `SpellEffect` cases (summon/block-transform/wind-drain/utility), advancement-gated
  progression tiers, Dark Magic mechanics (§6), rune magic (§9), potions (§10), familiars (§11) — these
  are the Phase 2+ backlog below, each independently testable per the Scope Check in the writing-plans
  skill.
- **Placeholder scan:** no TBD/TODO/"add error handling" strings; the one intentionally-simplified spot
  (Task 10's `setAptitude` argument plumbing) is called out explicitly as a known simplification with a
  concrete note on how to clean it up, not a hidden gap.
- **Type consistency:** `Wind`, `ChunkWindState`, `WindGridManager`, `Spell`, `SpellEffect`,
  `CastingResolver.CastResult`, `PlayerMagicData`, `ModAttachments.PLAYER_MAGIC` are used with identical
  method signatures everywhere they're referenced across tasks (verified by re-reading each task's
  "Consumes" line against the producing task's "Produces" line).

## Phase 2+ Backlog (separate plans, once this one is merged)

Per the Scope Check in the writing-plans skill, each of these is an independently testable subsystem —
write a fresh plan per bullet rather than one enormous plan:

1. **Player control tiers & influences** — `BiomeInfluence` (NeoForge `DataMapProvider`, `wind_biome`
   data map), `TaggedBlockInfluence` (block place/break counters via `#skavenblight:wind_source/<wind>`
   tags), a Tier-2 "magic block" that raises a Wind's baseline capacity, real player-facing casting
   (item/keybind + target raycast, replacing the debug command's self-cast).
2. **Power Stones & first Magic Machine** — one item per Wind (§8's naming table), the Chamon Alchemy
   Lab (blocks potions milestone below).
3. **Potions & Brewing** — `PotionRecipe` record/codec + `SimpleJsonResourceReloadListener`, Alchemy Lab
   tiers reusing `Config.java`'s tier-config pattern, spoiled-potion effect table.
4. **Rune Magic** — `Rune` record/codec, `ItemRunes` data component, Form/Three/Mastery/Pride
   constraint checks.
5. **Dark Magic & Dhar** — `DharTracker` (4+-winds-high persistence), Tainted `MobEffect`, the
   `dark_magic` category's real advancement-gated unlock condition and its first 5–8 spells.
6. **Advancement-gated progression** — wire Apprentice/Journeyman/Master/Wizard Lord tiers as
   Modonomicon advancement-gated entries per §4, replacing this plan's ungated overview/petty-spells
   pages.
7. **Familiars** *(optional, per source notes)* — `FamiliarData` attachment, weighted-table trait
   generation, the create-vs-bind flows.
