# Tzeentch's Curse: Corruption Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an extensible player-corruption system ("Tzeentch's Curse," from WFRP Realms of Sorcery pp. 179-181) with one concrete gain source (a placeholder Chaos Monolith structure, implemented per the book's actual mechanics), one gate/unlock item (Tome of Corruption), and one rare cleanse path — designed so future gain/loss sources plug in without touching the core.

**Architecture:** A `corruptionPoints` counter lives on the existing `PlayerMagicData` attachment (same pattern as tier/aptitude/knownSpells). A small `Corruption` utility grants/reduces it from anywhere; a `CorruptionTier` enum maps points to severity. A data-driven `ChaosManifestationTable` system (mirrors the existing `Spell`/`SpellManager` JSON-plus-sealed-interface pattern) resolves the actual in-world consequence when corruption-triggering events fire. Dark Magic ("Dhar") is a boolean tag on `Spell`, gated by a Tome-of-Corruption unlock flag, and is the second corruption source alongside Monoliths. Chaos Monoliths are a new tough, wound-tracked block that (a) risk-gate a "read the runes" action, and (b) buff-and-endanger casting in a radius around them, exactly as WFRP describes.

**Tech Stack:** NeoForge 1.21.1 (Neo 21.1.228), Java 21, Modonomicon 1.120.3, JUnit 5 (`neoForge { unitTest { ... } }` for anything touching registries; plain JUnit classpath for pure-function tests).

**Explicitly out of scope for this plan:** a permanent Mutation system (visible, lasting character changes). This plan builds the plumbing a mutation system would eventually consume — `CorruptionTier`, the Chaos Manifestation table, the Tainted effect — but does not add permanent mutations themselves. Do not add a "Mutation" concept in this plan; note it as backlog only.

## Global Constraints

- NeoForge 1.21.1, Neo version `21.1.228`, Java 21, mod id `skavenblight`, base package `org.ratden.skavenblight`.
- Never introduce a parallel enum for "school"/"lore" — the 8-value `org.ratden.skavenblight.magic.Wind` enum is canonical. Dhar/Dark Magic is a boolean tag on `Spell`, not a 9th Wind.
- `PlayerMagicData` (`org.ratden.skavenblight.magic.player.PlayerMagicData`) is the single attachment for all per-player magic state. Do not create a new `AttachmentType` for corruption — add fields to this record, following its existing `withX(...)` immutable-update pattern exactly.
- All new Codecs must use `optionalFieldOf` with a safe default for every field added to an already-shipped record (`PlayerMagicData`, `Spell`), so existing save data and existing spell JSON files continue to parse without edits. This mirrors the existing "Save compatibility note" already documented in `docs/design/research-table-ux.md`.
- Data-driven content lives under `data/skavenblight/magic/...`, loaded via `SimpleJsonResourceReloadListener` subclasses registered in `org.ratden.skavenblight.magic.ModMagic` on `NeoForge.EVENT_BUS` via `AddReloadListenerEvent` — mirror `SpellManager`/`ModMagic` exactly, do not register on the mod bus.
- New sealed-interface "effect" types follow the existing `SpellEffect` pattern: a small, closed set of concrete cases, not a generic scripting hook. A new kind of consequence needs a new `permits`-listed case, never a generic "run arbitrary logic" case.
- Config values follow the existing `Config.java` pattern exactly: a `private static final ModConfigSpec.IntValue` (or `.BooleanValue`/`.DoubleValue`) built via `BUILDER.comment(...).defineInRange(...)` or `.define(...)`, a `public static <type>` field, and an assignment line inside the existing `onLoad(ModConfigEvent event)` method. Never invent a second config-loading mechanism.
- Verify every NeoForge/vanilla API signature referenced below against the decompiled sources before using it if anything seems off — this plan's authors already extracted and read the real 1.21.1/NeoForge-21.1.228 sources for every signature quoted in this plan (`MobEffect`, `MobEffectCategory`, `BlockEntity`, `BlockBehaviour.attack`/`useWithoutItem`, `PlayerTickEvent`, `EntityType.create`), so treat quoted signatures as ground truth, not something to re-derive from memory.
- Unit-test pure-function logic (`Corruption`, `CorruptionTier`, weighted-table selection) with plain JUnit 5, no Minecraft bootstrap. Test anything touching `BuiltInRegistries`/codecs with the `neoForge { unitTest { ... } }` DSL already wired into `build.gradle`.

---

## File Structure

New files:

- `src/main/java/org/ratden/skavenblight/magic/corruption/CorruptionTier.java` — severity enum + point thresholds.
- `src/main/java/org/ratden/skavenblight/magic/corruption/Corruption.java` — static grant/reduce/query utility.
- `src/main/java/org/ratden/skavenblight/magic/corruption/ChaosManifestationEffect.java` — sealed interface, mirrors `SpellEffect`.
- `src/main/java/org/ratden/skavenblight/magic/corruption/ChaosManifestationTable.java` — data record + weighted entries.
- `src/main/java/org/ratden/skavenblight/magic/corruption/ChaosManifestationManager.java` — reload listener, mirrors `SpellManager`.
- `src/main/java/org/ratden/skavenblight/magic/corruption/ModMobEffects.java` — registers the `Tainted` `MobEffect`.
- `src/main/java/org/ratden/skavenblight/magic/corruption/TaintedMobEffect.java` — the effect class itself.
- `src/main/java/org/ratden/skavenblight/magic/corruption/CorruptionTickHandler.java` — periodic per-player Tainted refresh + Tome carry-drain.
- `src/main/java/org/ratden/skavenblight/magic/corruption/MonolithRegistry.java` — in-memory loaded-Monolith position index per level.
- `src/main/java/org/ratden/skavenblight/magic/spell/SpellCasting.java` — extracted casting service (used by the debug command and, later, real casting UI).
- `src/main/java/org/ratden/skavenblight/block/custom/ChaosMonolithBlock.java`
- `src/main/java/org/ratden/skavenblight/block/entity/ChaosMonolithBlockEntity.java`
- `src/main/java/org/ratden/skavenblight/item/custom/TomeOfCorruptionItem.java`
- `src/main/java/org/ratden/skavenblight/item/custom/CleansingWardItem.java`
- `src/main/java/org/ratden/skavenblight/command/debug/DebugCorruptionCommands.java`
- `src/test/java/org/ratden/skavenblight/magic/corruption/CorruptionTierTest.java`
- `src/test/java/org/ratden/skavenblight/magic/corruption/ChaosManifestationTableTest.java`
- `src/test/java/org/ratden/skavenblight/magic/player/PlayerMagicDataCorruptionTest.java`
- `src/test/java/org/ratden/skavenblight/magic/spell/SpellCastingTest.java` (JUnit + `unitTest` bootstrap — touches registries)
- Datapack: `data/skavenblight/magic/chaos_manifestations/{minor,moderate,severe,catastrophic}.json`
- Assets: monolith block/item textures, blockstate, model, loot table, lang keys, creative-tab entries.

Modified files:

- `src/main/java/org/ratden/skavenblight/magic/player/PlayerMagicData.java` — 3 new fields.
- `src/main/java/org/ratden/skavenblight/magic/spell/Spell.java` — 1 new field (`dark`).
- `src/main/java/org/ratden/skavenblight/magic/ModMagic.java` — register `ChaosManifestationManager`, `ModMobEffects`, register `CorruptionTickHandler` on `NeoForge.EVENT_BUS`.
- `src/main/java/org/ratden/skavenblight/command/debug/DebugMagicCommands.java` — delegate to `SpellCasting`.
- `src/main/java/org/ratden/skavenblight/command/debug/SkavenDebugCommand.java` (or wherever debug subcommands are aggregated) — register `DebugCorruptionCommands`.
- `src/main/java/org/ratden/skavenblight/Config.java` — new config values (added incrementally per task, see each task's Files section).
- `src/main/java/org/ratden/skavenblight/block/ModBlocks.java`, `src/main/java/org/ratden/skavenblight/item/ModItems.java`, `src/main/java/org/ratden/skavenblight/block/entity/ModBlockEntities.java`, `src/main/java/org/ratden/skavenblight/item/ModCreativeModeTabs.java`, `src/main/java/org/ratden/skavenblight/datagen/ModBlockLootTableProvider.java`, `src/main/java/org/ratden/skavenblight/datagen/ModBlockStateProvider.java` — register the new block/items.
- `src/main/resources/assets/skavenblight/lang/en_us.json` — new lang keys.

---

### Task 1: Corruption core on PlayerMagicData

**Files:**
- Modify: `src/main/java/org/ratden/skavenblight/magic/player/PlayerMagicData.java`
- Create: `src/main/java/org/ratden/skavenblight/magic/corruption/CorruptionTier.java`
- Create: `src/main/java/org/ratden/skavenblight/magic/corruption/Corruption.java`
- Test: `src/test/java/org/ratden/skavenblight/magic/corruption/CorruptionTierTest.java`
- Test: `src/test/java/org/ratden/skavenblight/magic/player/PlayerMagicDataCorruptionTest.java`

**Interfaces:**
- Produces: `PlayerMagicData.corruptionPoints()`, `.darkMagicUnlocked()`, `.lastCleanseGameTime()`, `.withCorruptionPoints(int)`, `.withDarkMagicUnlocked(boolean)`, `.withLastCleanseGameTime(long)`. `CorruptionTier` enum with `NONE, MINOR, MODERATE, SEVERE, CATASTROPHIC`, `.threshold()`, `.getSerializedName()`, static `CorruptionTier.forPoints(int)`, `CorruptionTier.CODEC`. `Corruption.grant(ServerPlayer, int)`, `Corruption.reduce(ServerPlayer, int)`, `Corruption.getPoints(ServerPlayer)`, `Corruption.getTier(ServerPlayer)` — all later tasks call these, never touch `PlayerMagicData` corruption fields directly.
- Consumes: nothing new (uses existing `ModAttachments.PLAYER_MAGIC`).

- [ ] **Step 1: Write the failing test for CorruptionTier**

```java
package org.ratden.skavenblight.magic.corruption;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CorruptionTierTest {

    @Test
    void forPointsReturnsNoneBelowFirstThreshold() {
        assertEquals(CorruptionTier.NONE, CorruptionTier.forPoints(0));
        assertEquals(CorruptionTier.NONE, CorruptionTier.forPoints(9));
    }

    @Test
    void forPointsReturnsHighestTierAtOrBelowPoints() {
        assertEquals(CorruptionTier.MINOR, CorruptionTier.forPoints(10));
        assertEquals(CorruptionTier.MINOR, CorruptionTier.forPoints(24));
        assertEquals(CorruptionTier.MODERATE, CorruptionTier.forPoints(25));
        assertEquals(CorruptionTier.SEVERE, CorruptionTier.forPoints(50));
        assertEquals(CorruptionTier.CATASTROPHIC, CorruptionTier.forPoints(100));
        assertEquals(CorruptionTier.CATASTROPHIC, CorruptionTier.forPoints(9999));
    }

    @Test
    void serializedNamesAreLowercaseIds() {
        assertEquals("none", CorruptionTier.NONE.getSerializedName());
        assertEquals("catastrophic", CorruptionTier.CATASTROPHIC.getSerializedName());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "org.ratden.skavenblight.magic.corruption.CorruptionTierTest"`
Expected: FAIL to compile — `CorruptionTier` does not exist yet.

- [ ] **Step 3: Create CorruptionTier**

```java
package org.ratden.skavenblight.magic.corruption;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

/**
 * How badly corrupted a player is. Point thresholds are structural game-design constants
 * (like CastingResolver's WIND_LEVEL_SCALE), not Config — they define what the tier names mean.
 */
public enum CorruptionTier implements StringRepresentable {
    NONE("none", 0),
    MINOR("minor", 10),
    MODERATE("moderate", 25),
    SEVERE("severe", 50),
    CATASTROPHIC("catastrophic", 100);

    public static final Codec<CorruptionTier> CODEC = StringRepresentable.fromEnum(CorruptionTier::values);

    private final String id;
    private final int threshold;

    CorruptionTier(String id, int threshold) {
        this.id = id;
        this.threshold = threshold;
    }

    @Override
    public String getSerializedName() {
        return id;
    }

    public int threshold() {
        return threshold;
    }

    /** Highest tier whose threshold is at or below the given points. Enum declaration order is ascending. */
    public static CorruptionTier forPoints(int points) {
        CorruptionTier result = NONE;
        for (CorruptionTier tier : values()) {
            if (points >= tier.threshold) {
                result = tier;
            }
        }
        return result;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "org.ratden.skavenblight.magic.corruption.CorruptionTierTest"`
Expected: PASS

- [ ] **Step 5: Write the failing test for PlayerMagicData's new fields**

```java
package org.ratden.skavenblight.magic.player;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerMagicDataCorruptionTest {

    @Test
    void emptyDataHasZeroCorruptionAndLockedDarkMagic() {
        assertEquals(0, PlayerMagicData.EMPTY.corruptionPoints());
        assertFalse(PlayerMagicData.EMPTY.darkMagicUnlocked());
        assertEquals(0L, PlayerMagicData.EMPTY.lastCleanseGameTime());
    }

    @Test
    void withCorruptionPointsReplacesOnlyThatField() {
        PlayerMagicData updated = PlayerMagicData.EMPTY.withCorruptionPoints(42);
        assertEquals(42, updated.corruptionPoints());
        assertFalse(updated.darkMagicUnlocked());
    }

    @Test
    void withDarkMagicUnlockedPreservesCorruptionPoints() {
        PlayerMagicData updated = PlayerMagicData.EMPTY
                .withCorruptionPoints(15)
                .withDarkMagicUnlocked(true);
        assertEquals(15, updated.corruptionPoints());
        assertTrue(updated.darkMagicUnlocked());
    }

    @Test
    void withLastCleanseGameTimeRoundTrips() {
        PlayerMagicData updated = PlayerMagicData.EMPTY.withLastCleanseGameTime(123456L);
        assertEquals(123456L, updated.lastCleanseGameTime());
    }
}
```

- [ ] **Step 6: Run test to verify it fails**

Run: `./gradlew test --tests "org.ratden.skavenblight.magic.player.PlayerMagicDataCorruptionTest"`
Expected: FAIL to compile — no `corruptionPoints()`/`withCorruptionPoints(...)` etc.

- [ ] **Step 7: Add the 3 new fields to PlayerMagicData**

Replace the full file with:

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
        Set<ResourceLocation> knownSpells,
        int corruptionPoints,
        boolean darkMagicUnlocked,
        long lastCleanseGameTime
) {
    public PlayerMagicData {
        tier = Map.copyOf(tier);
        aptitude = Map.copyOf(aptitude);
        knownSpells = Set.copyOf(knownSpells);
    }

    public static final Codec<PlayerMagicData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.unboundedMap(Wind.CODEC, Codec.INT).fieldOf("tier").forGetter(PlayerMagicData::tier),
            Codec.unboundedMap(Wind.CODEC, Codec.INT).fieldOf("aptitude").forGetter(PlayerMagicData::aptitude),
            ResourceLocation.CODEC.listOf()
                    .<Set<ResourceLocation>>xmap(HashSet::new, ArrayList::new)
                    .fieldOf("known_spells")
                    .forGetter(PlayerMagicData::knownSpells),
            Codec.INT.optionalFieldOf("corruption_points", 0).forGetter(PlayerMagicData::corruptionPoints),
            Codec.BOOL.optionalFieldOf("dark_magic_unlocked", false).forGetter(PlayerMagicData::darkMagicUnlocked),
            Codec.LONG.optionalFieldOf("last_cleanse_game_time", 0L).forGetter(PlayerMagicData::lastCleanseGameTime)
    ).apply(instance, PlayerMagicData::new));

    public static final PlayerMagicData EMPTY = new PlayerMagicData(Map.of(), Map.of(), Set.of(), 0, false, 0L);

    public int getAptitude(Wind wind) {
        return aptitude.getOrDefault(wind, 0);
    }

    public int getTier(Wind wind) {
        return tier.getOrDefault(wind, 0);
    }

    public PlayerMagicData withAptitude(Wind wind, int value) {
        Map<Wind, Integer> updated = new HashMap<>(aptitude);
        updated.put(wind, value);
        return new PlayerMagicData(tier, updated, knownSpells, corruptionPoints, darkMagicUnlocked, lastCleanseGameTime);
    }

    public PlayerMagicData withTier(Wind wind, int value) {
        Map<Wind, Integer> updated = new HashMap<>(tier);
        updated.put(wind, value);
        return new PlayerMagicData(updated, aptitude, knownSpells, corruptionPoints, darkMagicUnlocked, lastCleanseGameTime);
    }

    public PlayerMagicData withKnownSpell(ResourceLocation spellId) {
        Set<ResourceLocation> updated = new HashSet<>(knownSpells);
        updated.add(spellId);
        return new PlayerMagicData(tier, aptitude, updated, corruptionPoints, darkMagicUnlocked, lastCleanseGameTime);
    }

    public PlayerMagicData withCorruptionPoints(int value) {
        return new PlayerMagicData(tier, aptitude, knownSpells, value, darkMagicUnlocked, lastCleanseGameTime);
    }

    public PlayerMagicData withDarkMagicUnlocked(boolean value) {
        return new PlayerMagicData(tier, aptitude, knownSpells, corruptionPoints, value, lastCleanseGameTime);
    }

    public PlayerMagicData withLastCleanseGameTime(long value) {
        return new PlayerMagicData(tier, aptitude, knownSpells, corruptionPoints, darkMagicUnlocked, value);
    }
}
```

- [ ] **Step 8: Run test to verify it passes**

Run: `./gradlew test --tests "org.ratden.skavenblight.magic.player.PlayerMagicDataCorruptionTest"`
Expected: PASS

- [ ] **Step 9: Write the failing test for Corruption utility**

This test touches `ServerPlayer`/`player.getData(...)`, so it needs the `neoForge { unitTest { ... } }` bootstrap. Place it under the existing test source set as usual — the Gradle wiring already handles it.

```java
package org.ratden.skavenblight.magic.corruption;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import org.junit.jupiter.api.Test;
import org.ratden.skavenblight.magic.player.ModAttachments;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CorruptionTest {
    // NOTE: this project's existing registry-touching tests (see ChunkWindStateTest,
    // ResearchFormulasTest) establish the pattern for this — mirror whichever bootstrap
    // idiom those already use rather than re-deriving one here.
}
```

Since this repo's existing precedent for "needs live registries" tests is the `unitTest` DSL bootstrapping full FML/registries (see `Bootstrap.bootStrap()` usage from the Winds of Magic Foundation plan), and `Corruption` only needs a `ServerPlayer` to call `getData`/`setData` on — which requires a running server, not just registries — write this as a **pure logic test against `PlayerMagicData` + `CorruptionTier` directly** instead (no `ServerPlayer` needed at all, since `Corruption.grant` is a thin wrapper). Test the wrapped logic without the player plumbing:

```java
package org.ratden.skavenblight.magic.corruption;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Corruption.grant/reduce are thin ServerPlayer-attachment wrappers around this arithmetic —
 * exercise the arithmetic directly rather than standing up a live player, mirroring how
 * CastingResolverTest tests pure formulas without a live cast.
 */
class CorruptionArithmeticTest {

    @Test
    void grantNeverGoesNegative() {
        assertEquals(0, Corruption.applyDelta(5, -20));
    }

    @Test
    void grantAccumulates() {
        assertEquals(15, Corruption.applyDelta(10, 5));
    }

    @Test
    void reduceIsGrantWithNegatedAbsoluteAmount() {
        assertEquals(5, Corruption.applyDelta(10, -Math.abs(5)));
    }
}
```

- [ ] **Step 10: Run test to verify it fails**

Run: `./gradlew test --tests "org.ratden.skavenblight.magic.corruption.CorruptionArithmeticTest"`
Expected: FAIL to compile — `Corruption.applyDelta` does not exist yet.

- [ ] **Step 11: Create the Corruption utility**

```java
package org.ratden.skavenblight.magic.corruption;

import net.minecraft.server.level.ServerPlayer;
import org.ratden.skavenblight.magic.player.ModAttachments;
import org.ratden.skavenblight.magic.player.PlayerMagicData;

/**
 * The single choke point every corruption source (Monoliths, Dhar casting, the Tome, future
 * sources) grants or reduces through. Never mutate PlayerMagicData.corruptionPoints directly
 * from elsewhere — go through here so every source is easy to find and the floor-at-zero rule
 * can't be forgotten in a new call site.
 */
public final class Corruption {

    private Corruption() {}

    /** Pure arithmetic, extracted so it's testable without a live ServerPlayer. */
    public static int applyDelta(int currentPoints, int delta) {
        return Math.max(0, currentPoints + delta);
    }

    public static CorruptionTier grant(ServerPlayer player, int amount) {
        PlayerMagicData data = player.getData(ModAttachments.PLAYER_MAGIC.get());
        int updated = applyDelta(data.corruptionPoints(), amount);
        player.setData(ModAttachments.PLAYER_MAGIC.get(), data.withCorruptionPoints(updated));
        return CorruptionTier.forPoints(updated);
    }

    public static CorruptionTier reduce(ServerPlayer player, int amount) {
        return grant(player, -Math.abs(amount));
    }

    public static int getPoints(ServerPlayer player) {
        return player.getData(ModAttachments.PLAYER_MAGIC.get()).corruptionPoints();
    }

    public static CorruptionTier getTier(ServerPlayer player) {
        return CorruptionTier.forPoints(getPoints(player));
    }
}
```

- [ ] **Step 12: Run test to verify it passes**

Run: `./gradlew test --tests "org.ratden.skavenblight.magic.corruption.CorruptionArithmeticTest"`
Expected: PASS

- [ ] **Step 13: Run the full test suite to confirm no regressions**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, all existing tests still passing (PlayerMagicData is used across the Research Table screen/menu/block entity — confirm nothing there constructs a `PlayerMagicData` positionally with the old 3-arg shape; grep for `new PlayerMagicData(` across the codebase and fix any call site this step's record-shape change broke).

- [ ] **Step 14: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/magic/player/PlayerMagicData.java \
        src/main/java/org/ratden/skavenblight/magic/corruption/CorruptionTier.java \
        src/main/java/org/ratden/skavenblight/magic/corruption/Corruption.java \
        src/test/java/org/ratden/skavenblight/magic/corruption/CorruptionTierTest.java \
        src/test/java/org/ratden/skavenblight/magic/corruption/CorruptionArithmeticTest.java \
        src/test/java/org/ratden/skavenblight/magic/player/PlayerMagicDataCorruptionTest.java
git commit -m "feat(corruption): add corruption points, dark-magic unlock, and cleanse timestamp to PlayerMagicData"
```

---

### Task 2: Spell dark-magic tag + Chaos Manifestation data model

**Files:**
- Modify: `src/main/java/org/ratden/skavenblight/magic/spell/Spell.java`
- Create: `src/main/java/org/ratden/skavenblight/magic/corruption/ChaosManifestationEffect.java`
- Create: `src/main/java/org/ratden/skavenblight/magic/corruption/ChaosManifestationTable.java`
- Test: `src/test/java/org/ratden/skavenblight/magic/corruption/ChaosManifestationTableTest.java`

**Interfaces:**
- Consumes: `CorruptionTier` (Task 1), `Corruption.grant` (Task 1).
- Produces: `Spell.dark()`. `ChaosManifestationEffect` sealed interface with `apply(ServerLevel, LivingEntity)` and `type()`, cases `GrantCorruption`, `ApplyMobEffect`, `Damage`. `ChaosManifestationTable(CorruptionTier severity, List<WeightedEntry> entries)` with nested `record WeightedEntry(int weight, ChaosManifestationEffect effect)`, both with `CODEC` fields. These are consumed by `ChaosManifestationManager` in Task 3.

- [ ] **Step 1: Write the failing test for weighted-entry selection**

```java
package org.ratden.skavenblight.magic.corruption;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChaosManifestationTableTest {

    /** A no-op effect purely for exercising weighted selection without touching game state. */
    private record MarkerEffect(String label) implements ChaosManifestationEffect {
        @Override
        public void apply(ServerLevel level, LivingEntity target) {}

        @Override
        public String type() {
            return "marker";
        }
    }

    @Test
    void pickWeightedRespectsZeroRoll() {
        List<ChaosManifestationTable.WeightedEntry> entries = List.of(
                new ChaosManifestationTable.WeightedEntry(1, new MarkerEffect("a")),
                new ChaosManifestationTable.WeightedEntry(9, new MarkerEffect("b"))
        );
        // Roll of 0 (out of total weight 10) must land on the first entry.
        ChaosManifestationEffect picked = ChaosManifestationTable.pickWeighted(entries, 0);
        assertEquals("a", ((MarkerEffect) picked).label());
    }

    @Test
    void pickWeightedRespectsLastSlot() {
        List<ChaosManifestationTable.WeightedEntry> entries = List.of(
                new ChaosManifestationTable.WeightedEntry(1, new MarkerEffect("a")),
                new ChaosManifestationTable.WeightedEntry(9, new MarkerEffect("b"))
        );
        // Roll of 9 (last slot in a total weight of 10) must land on the second entry.
        ChaosManifestationEffect picked = ChaosManifestationTable.pickWeighted(entries, 9);
        assertEquals("b", ((MarkerEffect) picked).label());
    }

    @Test
    void pickWeightedIsDeterministicForAGivenRoll() {
        List<ChaosManifestationTable.WeightedEntry> entries = List.of(
                new ChaosManifestationTable.WeightedEntry(5, new MarkerEffect("only"))
        );
        Random random = new Random(42);
        for (int i = 0; i < 20; i++) {
            ChaosManifestationEffect picked = ChaosManifestationTable.pickWeighted(entries, random.nextInt(5));
            assertEquals("only", ((MarkerEffect) picked).label());
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "org.ratden.skavenblight.magic.corruption.ChaosManifestationTableTest"`
Expected: FAIL to compile — none of these types exist yet.

- [ ] **Step 3: Create ChaosManifestationEffect**

```java
package org.ratden.skavenblight.magic.corruption;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;

/**
 * The escape hatch for Chaos Manifestation table entries — mirrors SpellEffect exactly. A new
 * kind of consequence (e.g. a future "summon a Lesser Daemon" entry) needs a new permits-listed
 * case here, not a generic scripting hook.
 */
public sealed interface ChaosManifestationEffect
        permits ChaosManifestationEffect.GrantCorruption, ChaosManifestationEffect.ApplyMobEffect, ChaosManifestationEffect.Damage {

    Codec<ChaosManifestationEffect> CODEC = Codec.STRING.dispatch("type", ChaosManifestationEffect::type, type -> switch (type) {
        case "grant_corruption" -> GrantCorruption.CODEC;
        case "mob_effect" -> ApplyMobEffect.CODEC;
        case "damage" -> Damage.CODEC;
        default -> throw new IllegalArgumentException("Unknown chaos manifestation effect type: " + type);
    });

    void apply(ServerLevel level, LivingEntity target);

    String type();

    record GrantCorruption(int amount) implements ChaosManifestationEffect {
        public static final MapCodec<GrantCorruption> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Codec.INT.fieldOf("amount").forGetter(GrantCorruption::amount)
        ).apply(instance, GrantCorruption::new));

        @Override
        public void apply(ServerLevel level, LivingEntity target) {
            if (target instanceof ServerPlayer player) {
                org.ratden.skavenblight.magic.corruption.Corruption.grant(player, amount);
            }
        }

        @Override
        public String type() {
            return "grant_corruption";
        }
    }

    record ApplyMobEffect(Holder<MobEffect> effect, int durationTicks, int amplifier) implements ChaosManifestationEffect {
        public static final MapCodec<ApplyMobEffect> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                BuiltInRegistries.MOB_EFFECT.holderByNameCodec().fieldOf("effect").forGetter(ApplyMobEffect::effect),
                Codec.INT.fieldOf("duration_ticks").forGetter(ApplyMobEffect::durationTicks),
                Codec.INT.optionalFieldOf("amplifier", 0).forGetter(ApplyMobEffect::amplifier)
        ).apply(instance, ApplyMobEffect::new));

        @Override
        public void apply(ServerLevel level, LivingEntity target) {
            target.addEffect(new MobEffectInstance(effect, durationTicks, amplifier));
        }

        @Override
        public String type() {
            return "mob_effect";
        }
    }

    record Damage(float amount) implements ChaosManifestationEffect {
        public static final MapCodec<Damage> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Codec.FLOAT.fieldOf("amount").forGetter(Damage::amount)
        ).apply(instance, Damage::new));

        @Override
        public void apply(ServerLevel level, LivingEntity target) {
            target.hurt(target.damageSources().magic(), amount);
        }

        @Override
        public String type() {
            return "damage";
        }
    }
}
```

- [ ] **Step 4: Create ChaosManifestationTable**

```java
package org.ratden.skavenblight.magic.corruption;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;

public record ChaosManifestationTable(CorruptionTier severity, List<ChaosManifestationTable.WeightedEntry> entries) {

    public static final Codec<ChaosManifestationTable> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            CorruptionTier.CODEC.fieldOf("severity").forGetter(ChaosManifestationTable::severity),
            WeightedEntry.CODEC.listOf().fieldOf("entries").forGetter(ChaosManifestationTable::entries)
    ).apply(instance, ChaosManifestationTable::new));

    public record WeightedEntry(int weight, ChaosManifestationEffect effect) {
        public static final Codec<WeightedEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.INT.fieldOf("weight").forGetter(WeightedEntry::weight),
                ChaosManifestationEffect.CODEC.fieldOf("effect").forGetter(WeightedEntry::effect)
        ).apply(instance, WeightedEntry::new));
    }

    /**
     * Picks the entry containing the given roll, where roll is in [0, totalWeight). Entries are
     * walked in list order and each occupies a contiguous slice of weight-sized width — the same
     * shape as a WFRP d100 table read top to bottom. Callers are responsible for rolling
     * random.nextInt(totalWeight(entries)) themselves so this stays a pure, testable function.
     */
    public static ChaosManifestationEffect pickWeighted(List<WeightedEntry> entries, int roll) {
        int cursor = 0;
        for (WeightedEntry entry : entries) {
            cursor += entry.weight();
            if (roll < cursor) {
                return entry.effect();
            }
        }
        throw new IllegalArgumentException("roll " + roll + " exceeds total weight " + cursor);
    }

    public static int totalWeight(List<WeightedEntry> entries) {
        int total = 0;
        for (WeightedEntry entry : entries) {
            total += entry.weight();
        }
        return total;
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests "org.ratden.skavenblight.magic.corruption.ChaosManifestationTableTest"`
Expected: PASS

- [ ] **Step 6: Add the dark-magic tag to Spell**

Replace the full file with:

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
        // Parsed but not yet consumed by casting logic — component-item cost is a Phase 2 concern.
        Optional<Ingredient> componentItem,
        SpellEffect effect,
        String descriptionKey,
        // Dhar (Dark Magic): any Wind can have a dark-tagged variant. Casting one requires
        // PlayerMagicData.darkMagicUnlocked and grants corruption — see SpellCasting.
        boolean dark
) {
    public static final Codec<Spell> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Wind.CODEC.fieldOf("wind").forGetter(Spell::wind),
            Codec.INT.fieldOf("tier").forGetter(Spell::tier),
            Codec.INT.fieldOf("casting_number").forGetter(Spell::castingNumber),
            CastingTime.CODEC.fieldOf("casting_time").forGetter(Spell::castingTime),
            Ingredient.CODEC.optionalFieldOf("component_item").forGetter(Spell::componentItem),
            SpellEffect.CODEC.fieldOf("effect").forGetter(Spell::effect),
            Codec.STRING.fieldOf("description_key").forGetter(Spell::descriptionKey),
            Codec.BOOL.optionalFieldOf("dark", false).forGetter(Spell::dark)
    ).apply(instance, Spell::new));
}
```

- [ ] **Step 7: Run the full test suite to confirm no regressions**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL — the `dark` field is `optionalFieldOf` with default `false`, so all 16 existing spell JSON files parse unchanged. Grep for `new Spell(` across the codebase (any test fixture constructing one positionally) and add `false` as the trailing argument where needed.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/magic/spell/Spell.java \
        src/main/java/org/ratden/skavenblight/magic/corruption/ChaosManifestationEffect.java \
        src/main/java/org/ratden/skavenblight/magic/corruption/ChaosManifestationTable.java \
        src/test/java/org/ratden/skavenblight/magic/corruption/ChaosManifestationTableTest.java
git commit -m "feat(corruption): add Dhar spell tag and data-driven Chaos Manifestation table model"
```

---

### Task 3: Chaos Manifestation reload listener + content

**Files:**
- Create: `src/main/java/org/ratden/skavenblight/magic/corruption/ChaosManifestationManager.java`
- Modify: `src/main/java/org/ratden/skavenblight/magic/ModMagic.java`
- Create: `src/main/resources/data/skavenblight/magic/chaos_manifestations/minor.json`
- Create: `src/main/resources/data/skavenblight/magic/chaos_manifestations/moderate.json`
- Create: `src/main/resources/data/skavenblight/magic/chaos_manifestations/severe.json`
- Create: `src/main/resources/data/skavenblight/magic/chaos_manifestations/catastrophic.json`

**Interfaces:**
- Consumes: `ChaosManifestationTable`/`WeightedEntry`/`pickWeighted`/`totalWeight` (Task 2), `CorruptionTier` (Task 1).
- Produces: `ChaosManifestationManager.resolve(ServerLevel level, LivingEntity target, CorruptionTier severity)` — the single entry point every future trigger (Monolith failures in Task 7, Dhar cast failures, anything later) calls to apply a random consequence at a given severity. Multiple JSON files may declare the same `severity`; their entries are pooled together, not overwritten.

- [ ] **Step 1: Create ChaosManifestationManager**

```java
package org.ratden.skavenblight.magic.corruption;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.LivingEntity;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Loads data/<namespace>/magic/chaos_manifestations/**.json, exactly like SpellManager loads
 * spells. Multiple files may target the same severity — their entries are pooled, not the last
 * one winning, so datapacks can add manifestations without owning the whole table.
 */
public class ChaosManifestationManager extends SimpleJsonResourceReloadListener {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static Map<CorruptionTier, List<ChaosManifestationTable.WeightedEntry>> pooled = new EnumMap<>(CorruptionTier.class);

    public ChaosManifestationManager() {
        super(new Gson(), "magic/chaos_manifestations");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> resourceList, ResourceManager resourceManager, ProfilerFiller profiler) {
        Map<CorruptionTier, List<ChaosManifestationTable.WeightedEntry>> result = new EnumMap<>(CorruptionTier.class);
        resourceList.forEach((id, json) -> {
            try {
                ChaosManifestationTable.CODEC.parse(JsonOps.INSTANCE, json)
                        .resultOrPartial(error -> LOGGER.error("Failed to parse chaos manifestation table {}: {}", id, error))
                        .ifPresent(table -> result.computeIfAbsent(table.severity(), k -> new ArrayList<>())
                                .addAll(table.entries()));
            } catch (RuntimeException e) {
                LOGGER.error("Failed to parse chaos manifestation table {}", id, e);
            }
        });
        pooled = result;
        LOGGER.info("Loaded chaos manifestation tables for {} severities", pooled.size());
    }

    /** Applies one random consequence from the given severity's pool. No-op if that severity has no entries. */
    public static void resolve(ServerLevel level, LivingEntity target, CorruptionTier severity) {
        List<ChaosManifestationTable.WeightedEntry> entries = pooled.get(severity);
        if (entries == null || entries.isEmpty()) {
            LOGGER.warn("No chaos manifestation entries for severity {}", severity);
            return;
        }
        int roll = level.getRandom().nextInt(ChaosManifestationTable.totalWeight(entries));
        ChaosManifestationTable.pickWeighted(entries, roll).apply(level, target);
    }
}
```

- [ ] **Step 2: Register it in ModMagic**

Modify `src/main/java/org/ratden/skavenblight/magic/ModMagic.java` — add the import and the listener registration:

```java
package org.ratden.skavenblight.magic;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import org.ratden.skavenblight.magic.corruption.ChaosManifestationManager;
import org.ratden.skavenblight.magic.player.ModAttachments;
import org.ratden.skavenblight.magic.spell.SpellManager;

/**
 * Wires the magic system's registrations into the appropriate event buses. Attachment types are a
 * mod-bus concern (registered via {@link ModAttachments#register}); the spell-manager reload
 * listener is registered on {@link NeoForge#EVENT_BUS} instead, since {@code AddReloadListenerEvent}
 * only fires there. One method per concern, mirrors Skavenblight.java.
 */
public class ModMagic {

    public static void register(IEventBus modEventBus) {
        ModAttachments.register(modEventBus);
        NeoForge.EVENT_BUS.addListener(ModMagic::onAddReloadListeners);
    }

    private static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new SpellManager());
        event.addListener(new ChaosManifestationManager());
    }
}
```

- [ ] **Step 3: Create the minor.json table**

Adapted from the real WFRP source: `docs/human-made/WFRP - Tome of Corruption.md`, Appendix II "Tzeentch's Curse," Table A2-1 "Expanded Minor Chaos Manifestation" (its entries include "Rupture" — a nosebleed that persists until a Toughness Test — "Waxy Earful," "Stiff Sinews" (-5% to all tests), "Aethyric Shock" (lose 1 Wound regardless of Toughness/armor), and "Channel Bum" (-1 penalty to Casting Rolls for 1d10 minutes)):

```json
{
  "severity": "minor",
  "entries": [
    {
      "weight": 3,
      "effect": { "type": "damage", "amount": 1.0 }
    },
    {
      "weight": 3,
      "effect": { "type": "mob_effect", "effect": "minecraft:nausea", "duration_ticks": 100, "amplifier": 0 }
    },
    {
      "weight": 2,
      "effect": { "type": "mob_effect", "effect": "minecraft:weakness", "duration_ticks": 100, "amplifier": 0 }
    },
    {
      "weight": 2,
      "effect": { "type": "grant_corruption", "amount": 1 }
    }
  ]
}
```

- [ ] **Step 4: Create the moderate.json table**

Adapted from the upper-severity end of the same Table A2-1 (the real WFRP table's own escalation includes entries like "Intestinal Rebellion" and repeated "Aethyric Shock/Aethyric Attack" wound-loss results as its manifestations get worse toward the table's high rolls):

```json
{
  "severity": "moderate",
  "entries": [
    {
      "weight": 3,
      "effect": { "type": "damage", "amount": 3.0 }
    },
    {
      "weight": 3,
      "effect": { "type": "mob_effect", "effect": "minecraft:weakness", "duration_ticks": 200, "amplifier": 1 }
    },
    {
      "weight": 2,
      "effect": { "type": "mob_effect", "effect": "minecraft:nausea", "duration_ticks": 200, "amplifier": 1 }
    },
    {
      "weight": 2,
      "effect": { "type": "grant_corruption", "amount": 2 }
    }
  ]
}
```

- [ ] **Step 5: Create the severe.json table**

Adapted from the lower-severity end of Table A2-3 "Expanded Catastrophic Chaos Manifestation" (real entries: "Broken" — Will Power reduced 20% for 1d10 hours — "Stupefied" — Intelligence reduced 20% for 1d10 hours — and "Tzeentch's Lash," which knocks the victim out for 1d10 minutes):

```json
{
  "severity": "severe",
  "entries": [
    {
      "weight": 3,
      "effect": { "type": "mob_effect", "effect": "minecraft:blindness", "duration_ticks": 200, "amplifier": 0 }
    },
    {
      "weight": 3,
      "effect": { "type": "mob_effect", "effect": "minecraft:mining_fatigue", "duration_ticks": 200, "amplifier": 1 }
    },
    {
      "weight": 3,
      "effect": { "type": "damage", "amount": 6.0 }
    },
    {
      "weight": 3,
      "effect": { "type": "grant_corruption", "amount": 3 }
    }
  ]
}
```

- [ ] **Step 6: Create the catastrophic.json table**

This is the table Chaos Monoliths roll on for any failed cast in their radius (WFRP: "Any failure to cast a spell results in a Catastrophic Chaos Manifestation"). Adapted directly from Table A2-3 "Expanded Catastrophic Chaos Manifestation": "The Wither" (Toughness reduced 20% for 1d10 hours — the vanilla Wither effect is a near-literal namesake match), "Boiling Blood" (2d10 Wounds, average 11), "Aethyric Assault"/"Aethyric Attack" (a Critical Hit to a random location), and "Heretical Vision" (gain 1d10 Insanity Points, average 5 — this plan's closest analog to Insanity Points is Corruption Points):

```json
{
  "severity": "catastrophic",
  "entries": [
    {
      "weight": 3,
      "effect": { "type": "mob_effect", "effect": "minecraft:wither", "duration_ticks": 200, "amplifier": 1 }
    },
    {
      "weight": 3,
      "effect": { "type": "damage", "amount": 11.0 }
    },
    {
      "weight": 2,
      "effect": { "type": "mob_effect", "effect": "minecraft:blindness", "duration_ticks": 200, "amplifier": 0 }
    },
    {
      "weight": 3,
      "effect": { "type": "grant_corruption", "amount": 5 }
    }
  ]
}
```

- [ ] **Step 7: Run data validation and full test suite**

Run: `./gradlew runData` (regenerates datagen output, confirms nothing else broke) then `./gradlew test`
Expected: BUILD SUCCESSFUL. Manually start the dev server once and check the log line `Loaded chaos manifestation tables for 4 severities` appears.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/magic/corruption/ChaosManifestationManager.java \
        src/main/java/org/ratden/skavenblight/magic/ModMagic.java \
        src/main/resources/data/skavenblight/magic/chaos_manifestations/
git commit -m "feat(corruption): load Chaos Manifestation tables as a reload listener with placeholder content"
```

---

### Task 4: Tainted MobEffect + periodic corruption tick handler

**Files:**
- Create: `src/main/java/org/ratden/skavenblight/magic/corruption/TaintedMobEffect.java`
- Create: `src/main/java/org/ratden/skavenblight/magic/corruption/ModMobEffects.java`
- Create: `src/main/java/org/ratden/skavenblight/magic/corruption/CorruptionTickHandler.java`
- Modify: `src/main/java/org/ratden/skavenblight/Skavenblight.java` (register `ModMobEffects`)
- Modify: `src/main/java/org/ratden/skavenblight/magic/ModMagic.java` (register the tick handler)
- Modify: `src/main/java/org/ratden/skavenblight/Config.java` (add `corruptionTickIntervalTicks`)

**Interfaces:**
- Consumes: `Corruption.getTier` (Task 1).
- Produces: `ModMobEffects.TAINTED` (`DeferredHolder<MobEffect, TaintedMobEffect>`), used by nothing else in this plan directly (it's refreshed purely by the tick handler based on tier) but available for any future content to reference by registry name `skavenblight:tainted`.

- [ ] **Step 1: Verify the MobEffect constructor against decompiled sources**

This plan's authors already confirmed this against the real 1.21.1/NeoForge-21.1.228 decompiled sources: `net.minecraft.world.effect.MobEffect` has a `protected MobEffect(MobEffectCategory category, int color)` constructor (so it must be subclassed), and `net.minecraft.world.effect.MobEffectCategory` is a 3-value enum: `BENEFICIAL, HARMFUL, NEUTRAL`. If anything below fails to compile, re-verify against `~/.gradle/caches/neoformruntime/intermediate_results/*sourcesAndCompiledWithNeoForge*_output.jar!/net/minecraft/world/effect/MobEffect.java` before guessing a fix.

Also confirmed against those same sources: `MobEffectInstance`'s constructors and `LivingEntity.removeEffect` both take `Holder<MobEffect>`, not a bare `MobEffect`. Since `DeferredHolder<R, T>` (used by `ModMobEffects.TAINTED`) implements `Holder<R>` directly, pass `ModMobEffects.TAINTED` itself wherever a `Holder<MobEffect>` is needed — never `ModMobEffects.TAINTED.get()` (that returns the bare `TaintedMobEffect`, which won't compile against those signatures). Every code sample in this task and in Task 8 already reflects this; if you write a new call site, follow the same rule.

- [ ] **Step 2: Create TaintedMobEffect**

```java
package org.ratden.skavenblight.magic.corruption;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;

/**
 * Purely a visual/tracking marker of corruption tier (amplifier = tier ordinal, refreshed by
 * CorruptionTickHandler). The actual gameplay bite at higher tiers comes from vanilla effects
 * (Nausea, Weakness) the tick handler also applies — this effect itself has no tick behavior.
 */
public class TaintedMobEffect extends MobEffect {
    public TaintedMobEffect() {
        super(MobEffectCategory.HARMFUL, 0x5B2E73);
    }
}
```

- [ ] **Step 3: Register it**

```java
package org.ratden.skavenblight.magic.corruption;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.effect.MobEffect;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.ratden.skavenblight.Skavenblight;

public class ModMobEffects {

    public static final DeferredRegister<MobEffect> MOB_EFFECTS =
            DeferredRegister.create(Registries.MOB_EFFECT, Skavenblight.MODID);

    public static final DeferredHolder<MobEffect, TaintedMobEffect> TAINTED =
            MOB_EFFECTS.register("tainted", TaintedMobEffect::new);

    public static void register(IEventBus modEventBus) {
        MOB_EFFECTS.register(modEventBus);
    }
}
```

- [ ] **Step 4: Add the tick-interval config value**

In `src/main/java/org/ratden/skavenblight/Config.java`, add near the other `BUILDER` definitions (mirror the `WIND_SOURCE_BLOCK_BONUS` block exactly):

```java
    // --- Corruption Configs ---
    private static final ModConfigSpec.IntValue CORRUPTION_TICK_INTERVAL_TICKS = BUILDER.comment("How often (in ticks) each player's Tainted effect is refreshed and their Tome-of-Corruption carry drain is checked.")
            .defineInRange("corruptionTickIntervalTicks", 6000, 20, 72000);
```

Add the public field near the other `public static` declarations:

```java
    // --- Corruption Public Variables ---
    public static int corruptionTickIntervalTicks;
```

Add the load line inside `onLoad(final ModConfigEvent event)`, near the other config-section load blocks:

```java
        // Load Corruption Configs
        corruptionTickIntervalTicks = CORRUPTION_TICK_INTERVAL_TICKS.get();
```

- [ ] **Step 5: Create CorruptionTickHandler**

```java
package org.ratden.skavenblight.magic.corruption;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.ratden.skavenblight.Config;

/**
 * Once every Config.corruptionTickIntervalTicks per player: refresh the Tainted effect to match
 * their current tier, and at Severe+ apply an escalating vanilla debuff (the "intensifies with
 * use" bite the plain Tainted marker doesn't carry on its own).
 */
public final class CorruptionTickHandler {

    private CorruptionTickHandler() {}

    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (player.tickCount % Config.corruptionTickIntervalTicks != 0) {
            return;
        }

        CorruptionTier tier = Corruption.getTier(player);
        int refreshDuration = Config.corruptionTickIntervalTicks + 20;

        if (tier == CorruptionTier.NONE) {
            player.removeEffect(ModMobEffects.TAINTED);
            return;
        }

        player.addEffect(new MobEffectInstance(ModMobEffects.TAINTED, refreshDuration, tier.ordinal() - 1, false, false, true));

        if (tier == CorruptionTier.SEVERE || tier == CorruptionTier.CATASTROPHIC) {
            // MobEffects.NAUSEA doesn't exist in this mapping set — the compiling field name
            // is MobEffects.CONFUSION (registry id "minecraft:nausea"), confirmed against the
            // decompiled NeoForge sources during Task 4.
            player.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 100, 0));
        }
        if (tier == CorruptionTier.CATASTROPHIC) {
            player.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 100, 0));
        }
    }
}
```

- [ ] **Step 6: Register the mob effect and tick handler**

In `src/main/java/org/ratden/skavenblight/Skavenblight.java`, alongside the existing `ModSounds.register(modEventBus);` line, add:

```java
        org.ratden.skavenblight.magic.corruption.ModMobEffects.register(modEventBus);
```

In `src/main/java/org/ratden/skavenblight/magic/ModMagic.java`, add the tick handler registration inside `register(IEventBus modEventBus)`:

```java
        NeoForge.EVENT_BUS.addListener(org.ratden.skavenblight.magic.corruption.CorruptionTickHandler::onPlayerTick);
```

- [ ] **Step 7: Compile and manually verify**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL. Manually start the dev server, run `/skaven magic ...` (or whatever the debug root command is) to grant a player corruption via a temporary means if Task 1's utility isn't yet exposed through a command — this task has no command surface of its own yet (Task 10 adds `DebugCorruptionCommands`), so a full in-game demonstration of the Tainted effect appearing is deferred to Task 10's verification. For this task, confirm only that the mod loads without error and `/effect give @s skavenblight:tainted 100 0` (vanilla command, works on any registered effect) successfully applies the effect and it's visible in the player's effect list.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/magic/corruption/TaintedMobEffect.java \
        src/main/java/org/ratden/skavenblight/magic/corruption/ModMobEffects.java \
        src/main/java/org/ratden/skavenblight/magic/corruption/CorruptionTickHandler.java \
        src/main/java/org/ratden/skavenblight/Skavenblight.java \
        src/main/java/org/ratden/skavenblight/magic/ModMagic.java \
        src/main/java/org/ratden/skavenblight/Config.java
git commit -m "feat(corruption): add Tainted mob effect and periodic per-tier debuff refresh"
```

---

### Task 5: Extract SpellCasting service + Dhar gating and corruption

**Files:**
- Create: `src/main/java/org/ratden/skavenblight/magic/spell/SpellCasting.java`
- Modify: `src/main/java/org/ratden/skavenblight/command/debug/DebugMagicCommands.java`
- Modify: `src/main/java/org/ratden/skavenblight/Config.java` (add `dharCastCorruption`)
- Test: `src/test/java/org/ratden/skavenblight/magic/spell/SpellCastingOutcomeTest.java`

**Interfaces:**
- Consumes: `CastingResolver.resolve`/`.windLevelBonus` (existing), `Corruption.grant` (Task 1), `PlayerMagicData.darkMagicUnlocked` (Task 1).
- Produces: `SpellCasting.Outcome(boolean success, boolean blockedDarkMagic, int roll, int total, int degreesOfSuccess)` and `SpellCasting.attemptCast(ServerPlayer, Spell)`. Task 7 (Monolith casting zone) modifies this same method to add the zone bonus/catastrophe hook — read this task's final version of the file before starting Task 7.

- [ ] **Step 1: Write the failing test for the dark-magic gate**

`Outcome` itself is a plain record, so its shape is directly testable without a live cast:

```java
package org.ratden.skavenblight.magic.spell;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpellCastingOutcomeTest {

    @Test
    void blockedOutcomeIsNotASuccess() {
        SpellCasting.Outcome blocked = new SpellCasting.Outcome(false, true, 0, 0, 0);
        assertTrue(blocked.blockedDarkMagic());
        assertFalse(blocked.success());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "org.ratden.skavenblight.magic.spell.SpellCastingOutcomeTest"`
Expected: FAIL to compile — `SpellCasting` does not exist yet.

- [ ] **Step 3: Add the Dhar-cast corruption config value**

In `Config.java`:

```java
    // --- Corruption Configs ---
    private static final ModConfigSpec.IntValue DHAR_CAST_CORRUPTION = BUILDER.comment("Corruption Points granted per attempted Dhar (dark-tagged) spell cast, success or failure.")
            .defineInRange("dharCastCorruption", 1, 0, 100);
```

(This lands right after `CORRUPTION_TICK_INTERVAL_TICKS` from Task 4 — same `// --- Corruption Configs ---` section, don't create a second one.)

```java
    public static int dharCastCorruption;
```

```java
        dharCastCorruption = DHAR_CAST_CORRUPTION.get();
```

- [ ] **Step 4: Create SpellCasting**

```java
package org.ratden.skavenblight.magic.spell;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.ratden.skavenblight.Config;
import org.ratden.skavenblight.magic.corruption.Corruption;
import org.ratden.skavenblight.magic.player.ModAttachments;
import org.ratden.skavenblight.magic.player.PlayerMagicData;
import org.ratden.skavenblight.magic.wind.ChunkWindState;
import org.ratden.skavenblight.magic.wind.WindGridManager;

/**
 * The single place a spell actually gets cast — used by the debug /magic cast command today and
 * intended for any future real casting UI/item, so casting-adjacent mechanics (Dhar gating,
 * Monolith zones) live here once instead of being duplicated per call site.
 */
public final class SpellCasting {

    private SpellCasting() {}

    public record Outcome(boolean success, boolean blockedDarkMagic, int roll, int total, int degreesOfSuccess) {}

    public static Outcome attemptCast(ServerPlayer player, Spell spell) {
        ServerLevel level = (ServerLevel) player.level();
        PlayerMagicData data = player.getData(ModAttachments.PLAYER_MAGIC.get());

        if (spell.dark() && !data.darkMagicUnlocked()) {
            return new Outcome(false, true, 0, 0, 0);
        }

        int aptitude = data.getAptitude(spell.wind());
        ChunkPos chunkPos = new ChunkPos(player.blockPosition());
        ChunkWindState windState = WindGridManager.get(level).getOrCreate(chunkPos);
        float windLevel = windState.getCurrent(spell.wind());
        int windLevelBonus = CastingResolver.windLevelBonus(windLevel);

        int roll = level.getRandom().nextInt(100) + 1;
        CastingResolver.CastResult result = CastingResolver.resolve(aptitude, windLevelBonus, spell.castingNumber(), roll);
        int total = aptitude + windLevelBonus - spell.castingNumber();

        if (spell.dark()) {
            Corruption.grant(player, Config.dharCastCorruption);
        }

        if (!result.success()) {
            return new Outcome(false, false, roll, total, 0);
        }

        spell.effect().apply(player, player);
        windState.setCurrent(spell.wind(), Math.max(0f, windLevel - spell.castingNumber()));
        return new Outcome(true, false, roll, total, result.degreesOfSuccess());
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests "org.ratden.skavenblight.magic.spell.SpellCastingOutcomeTest"`
Expected: PASS

- [ ] **Step 6: Delegate DebugMagicCommands.cast to SpellCasting**

Replace the `cast` method in `src/main/java/org/ratden/skavenblight/command/debug/DebugMagicCommands.java` (imports for `CastingResolver`, `ChunkWindState`, `WindGridManager` are no longer needed there and can be removed; add an import for `SpellCasting`):

```java
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

        SpellCasting.Outcome outcome = SpellCasting.attemptCast(player, spell);

        if (outcome.blockedDarkMagic()) {
            source.sendFailure(Component.literal(
                    "Cast failed: " + spellId + " is a Dhar spell and you have not unlocked Dark Magic."));
            return 0;
        }

        if (!outcome.success()) {
            source.sendFailure(Component.literal(
                    "Cast failed: rolled " + outcome.roll() + ", needed <= " + outcome.total()));
            return 0;
        }

        source.sendSuccess(() -> Component.literal(
                "Cast " + spellId + " successfully! (rolled " + outcome.roll()
                        + ", degrees of success: " + outcome.degreesOfSuccess() + ")"), true);
        return 1;
    }
```

- [ ] **Step 7: Run the full test suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Manually verify in-game**

Start the dev server, run `/magic set_aptitude <wind> 50`, then `/magic cast <a known spell id>` — confirm the success/failure message still appears exactly as before (behavior-preserving refactor). This confirms the extraction didn't change the existing observable behavior of the debug command.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/magic/spell/SpellCasting.java \
        src/main/java/org/ratden/skavenblight/command/debug/DebugMagicCommands.java \
        src/main/java/org/ratden/skavenblight/Config.java \
        src/test/java/org/ratden/skavenblight/magic/spell/SpellCastingOutcomeTest.java
git commit -m "refactor(magic): extract SpellCasting service, gate Dhar spells behind dark-magic unlock"
```

---

### Task 6: Chaos Monolith block — wounds, daemon summons, "read the runes"

**Files:**
- Create: `src/main/java/org/ratden/skavenblight/block/custom/ChaosMonolithBlock.java`
- Create: `src/main/java/org/ratden/skavenblight/block/entity/ChaosMonolithBlockEntity.java`
- Create: `src/main/java/org/ratden/skavenblight/magic/corruption/MonolithRegistry.java`
- Modify: `src/main/java/org/ratden/skavenblight/block/ModBlocks.java`
- Modify: `src/main/java/org/ratden/skavenblight/block/entity/ModBlockEntities.java`
- Modify: `src/main/java/org/ratden/skavenblight/item/ModCreativeModeTabs.java`
- Modify: `src/main/java/org/ratden/skavenblight/datagen/ModBlockLootTableProvider.java`
- Modify: `src/main/java/org/ratden/skavenblight/datagen/ModBlockStateProvider.java`
- Modify: `src/main/java/org/ratden/skavenblight/Config.java` (4 new values)
- Modify: `src/main/resources/assets/skavenblight/lang/en_us.json`
- Create: `src/main/resources/assets/skavenblight/textures/block/chaos_monolith.png`

**Interfaces:**
- Consumes: `Corruption.grant` (Task 1).
- Produces: `ModBlocks.CHAOS_MONOLITH`, `MonolithRegistry.register/unregister/isWithinRange(ServerLevel, BlockPos, int)` — consumed by Task 7's casting-zone hook.

- [ ] **Step 1: Add the Monolith config values**

In `Config.java`, same `// --- Corruption Configs ---` section:

```java
    private static final ModConfigSpec.IntValue MONOLITH_MAX_WOUNDS = BUILDER.comment("Total Wounds a Chaos Monolith has before it's destroyed (WFRP source: 500).")
            .defineInRange("monolithMaxWounds", 500, 10, 10000);
    private static final ModConfigSpec.IntValue MONOLITH_DAMAGE_PER_HIT = BUILDER.comment("Wounds removed per attack against a Chaos Monolith.")
            .defineInRange("monolithDamagePerHit", 5, 1, 500);
    private static final ModConfigSpec.IntValue MONOLITH_WOUNDS_PER_DAEMON_SUMMON = BUILDER.comment("Every this many Wounds lost, the Monolith summons a Lesser Daemon at the attacker (WFRP source: every 50 Wounds).")
            .defineInRange("monolithWoundsPerDaemonSummon", 50, 1, 10000);
    private static final ModConfigSpec.IntValue MONOLITH_READ_CORRUPTION_CHANCE_PERCENT = BUILDER.comment("Percent chance that reading a Chaos Monolith's runes grants Corruption Points (WFRP source: failing a Hard(-20%) Will Power Test).")
            .defineInRange("monolithReadCorruptionChancePercent", 60, 0, 100);
```

```java
    public static int monolithMaxWounds;
    public static int monolithDamagePerHit;
    public static int monolithWoundsPerDaemonSummon;
    public static int monolithReadCorruptionChancePercent;
```

```java
        monolithMaxWounds = MONOLITH_MAX_WOUNDS.get();
        monolithDamagePerHit = MONOLITH_DAMAGE_PER_HIT.get();
        monolithWoundsPerDaemonSummon = MONOLITH_WOUNDS_PER_DAEMON_SUMMON.get();
        monolithReadCorruptionChancePercent = MONOLITH_READ_CORRUPTION_CHANCE_PERCENT.get();
```

- [ ] **Step 2: Create MonolithRegistry**

```java
package org.ratden.skavenblight.magic.corruption;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory index of currently-loaded Chaos Monolith positions per dimension, rebuilt from
 * BlockEntity load/unload — deliberately not persisted, since every loaded Monolith's BlockEntity
 * re-registers itself on chunk load anyway. Keyed by dimension (not ServerLevel directly) so a
 * level reload can't leave a stale strong reference here.
 */
public final class MonolithRegistry {

    private static final Map<ResourceKey<Level>, Set<BlockPos>> POSITIONS = new HashMap<>();

    private MonolithRegistry() {}

    public static void register(ServerLevel level, BlockPos pos) {
        POSITIONS.computeIfAbsent(level.dimension(), key -> ConcurrentHashMap.newKeySet()).add(pos.immutable());
    }

    public static void unregister(ServerLevel level, BlockPos pos) {
        Set<BlockPos> positions = POSITIONS.get(level.dimension());
        if (positions != null) {
            positions.remove(pos);
        }
    }

    public static boolean isWithinRange(ServerLevel level, BlockPos pos, int radius) {
        Set<BlockPos> positions = POSITIONS.get(level.dimension());
        if (positions == null) {
            return false;
        }
        double radiusSq = (double) radius * radius;
        for (BlockPos monolith : positions) {
            if (monolith.distSqr(pos) <= radiusSq) {
                return true;
            }
        }
        return false;
    }
}
```

- [ ] **Step 3: Create ChaosMonolithBlockEntity**

```java
package org.ratden.skavenblight.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.ratden.skavenblight.Config;
import org.ratden.skavenblight.magic.corruption.MonolithRegistry;

public class ChaosMonolithBlockEntity extends BlockEntity {

    private int wounds = Config.monolithMaxWounds;
    private int woundsSinceLastSummon = 0;

    public ChaosMonolithBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CHAOS_MONOLITH.get(), pos, state);
    }

    @Override
    public void setLevel(Level level) {
        super.setLevel(level);
        if (level instanceof ServerLevel serverLevel) {
            MonolithRegistry.register(serverLevel, worldPosition);
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level instanceof ServerLevel serverLevel) {
            MonolithRegistry.unregister(serverLevel, worldPosition);
        }
    }

    /** Called from ChaosMonolithBlock.attack(). Returns true if the Monolith was destroyed by this hit. */
    public boolean applyDamage(LivingEntity attacker) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        wounds -= Config.monolithDamagePerHit;
        woundsSinceLastSummon += Config.monolithDamagePerHit;
        setChanged();

        while (woundsSinceLastSummon >= Config.monolithWoundsPerDaemonSummon) {
            woundsSinceLastSummon -= Config.monolithWoundsPerDaemonSummon;
            summonLesserDaemon(serverLevel, attacker);
        }

        if (wounds <= 0) {
            serverLevel.destroyBlock(worldPosition, false);
            return true;
        }
        return false;
    }

    private void summonLesserDaemon(ServerLevel level, LivingEntity attacker) {
        Vex daemon = EntityType.VEX.create(level);
        if (daemon == null) {
            return;
        }
        daemon.moveTo(worldPosition.getX() + 0.5, worldPosition.getY() + 1.0, worldPosition.getZ() + 0.5,
                0.0F, 0.0F);
        daemon.setTarget(attacker);
        level.addFreshEntity(daemon);
    }

    public int getWounds() {
        return wounds;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("wounds", wounds);
        tag.putInt("woundsSinceLastSummon", woundsSinceLastSummon);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        wounds = tag.contains("wounds") ? tag.getInt("wounds") : Config.monolithMaxWounds;
        woundsSinceLastSummon = tag.getInt("woundsSinceLastSummon");
    }
}
```

- [ ] **Step 4: Create ChaosMonolithBlock**

```java
package org.ratden.skavenblight.block.custom;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import org.ratden.skavenblight.Config;
import org.ratden.skavenblight.block.entity.ChaosMonolithBlockEntity;
import org.ratden.skavenblight.magic.corruption.ChaosManifestationManager;
import org.ratden.skavenblight.magic.corruption.CorruptionTier;

/**
 * Placeholder Chaos Monolith (WFRP Realms of Sorcery p.132-133): a tough, wound-tracked structure
 * that risk-gates a "read the runes" action and buffs-and-endangers casting nearby (see
 * SpellCasting for the casting-zone half of this, wired in Task 7). Only the generic Chaos-flavor
 * variant is implemented — the per-god variants (Khorne suppresses magic entirely, etc.) are
 * explicit future flavor work, not built here.
 */
public class ChaosMonolithBlock extends BaseEntityBlock {

    public ChaosMonolithBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ChaosMonolithBlockEntity(pos, state);
    }

    @Override
    protected void attack(BlockState state, Level level, BlockPos pos, Player player) {
        super.attack(state, level, pos, player);
        if (level.isClientSide) {
            return;
        }
        if (level.getBlockEntity(pos) instanceof ChaosMonolithBlockEntity monolith) {
            monolith.applyDamage(player);
        }
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return InteractionResult.PASS;
        }
        if (!(player instanceof net.minecraft.server.level.ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }

        int roll = serverLevel.getRandom().nextInt(100);
        if (roll < Config.monolithReadCorruptionChancePercent) {
            int gained = 1 + serverLevel.getRandom().nextInt(5);
            org.ratden.skavenblight.magic.corruption.Corruption.grant(serverPlayer, gained);
            player.displayClientMessage(Component.literal(
                    "The blasphemous runes claw at your mind. You gain " + gained + " Corruption."), true);
        } else {
            player.displayClientMessage(Component.literal(
                    "You resist the whispers of the runes... for now."), true);
        }
        return InteractionResult.CONSUME;
    }
}
```

- [ ] **Step 5: Register the block, block entity, creative tab entry, loot table, and blockstate**

In `src/main/java/org/ratden/skavenblight/block/ModBlocks.java`, add after `WARP_LIGHTNING_COIL_DUMMY`:

```java
    public static final DeferredBlock<Block> CHAOS_MONOLITH = registerBlock("chaos_monolith",
            () -> new org.ratden.skavenblight.block.custom.ChaosMonolithBlock(BlockBehaviour.Properties.of()
                    .strength(50.0f, 1200.0f)
                    .sound(SoundType.NETHER_GOLD_ORE)
                    .noOcclusion()));
```

In `src/main/java/org/ratden/skavenblight/block/entity/ModBlockEntities.java`, add:

```java
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<org.ratden.skavenblight.block.entity.ChaosMonolithBlockEntity>> CHAOS_MONOLITH =
            BLOCK_ENTITIES.register("chaos_monolith", () ->
                    BlockEntityType.Builder.of(org.ratden.skavenblight.block.entity.ChaosMonolithBlockEntity::new,
                            ModBlocks.CHAOS_MONOLITH.get()).build(null));
```

In `src/main/java/org/ratden/skavenblight/item/ModCreativeModeTabs.java`, add inside the `displayItems` block:

```java
                        output.accept(ModBlocks.CHAOS_MONOLITH);
```

In `src/main/java/org/ratden/skavenblight/datagen/ModBlockLootTableProvider.java`, add inside `generate()`. A destroyed Monolith drops nothing (it's meant to be ground down by daemons and attrition, not farmed) — register it with an empty loot table so datagen validation doesn't flag it as missing:

```java
        this.add(ModBlocks.CHAOS_MONOLITH.get(), net.minecraft.world.level.storage.loot.LootTable.lootTable());
```

In `src/main/java/org/ratden/skavenblight/datagen/ModBlockStateProvider.java`, add a simple cube-all blockstate/model entry using the provider's existing `simpleBlockWithItem`/`cubeAll` helper pair (same one the Research Table loop at line 247 already uses):

```java
        simpleBlockWithItem(ModBlocks.CHAOS_MONOLITH.get(), cubeAll(ModBlocks.CHAOS_MONOLITH.get()));
```

- [ ] **Step 6: Generate a placeholder texture**

Generate a 16x16 dark mottled-purple placeholder PNG at `src/main/resources/assets/skavenblight/textures/block/chaos_monolith.png` using a small standalone script (no PIL/ImageMagick needed — this project already relies on this technique for other placeholder textures):

```python
import struct, zlib, random

def write_png(path, width, height, pixel_fn):
    raw = bytearray()
    for y in range(height):
        raw.append(0)  # no filter
        for x in range(width):
            r, g, b = pixel_fn(x, y)
            raw += bytes([r, g, b])
    def chunk(tag, data):
        c = tag + data
        return struct.pack(">I", len(data)) + c + struct.pack(">I", zlib.crc32(c))
    sig = b"\x89PNG\r\n\x1a\n"
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    idat = zlib.compress(bytes(raw), 9)
    with open(path, "wb") as f:
        f.write(sig)
        f.write(chunk(b"IHDR", ihdr))
        f.write(chunk(b"IDAT", idat))
        f.write(chunk(b"IEND", b""))

random.seed(7)
def mottled_purple(x, y):
    base = 40, 20, 55
    jitter = random.randint(-10, 25)
    return tuple(max(0, min(255, c + jitter)) for c in base)

write_png("src/main/resources/assets/skavenblight/textures/block/chaos_monolith.png", 16, 16, mottled_purple)
```

Run this script once from the project root (`python3 <script>.py`, deleting the script afterward — it's a one-off asset-generation tool, not part of the build).

- [ ] **Step 7: Add lang keys**

In `src/main/resources/assets/skavenblight/lang/en_us.json`, add:

```json
"block.skavenblight.chaos_monolith": "Chaos Monolith"
```

- [ ] **Step 8: Compile and run datagen**

Run: `./gradlew runData && ./gradlew build`
Expected: BUILD SUCCESSFUL, blockstate/model/loot table generated under `src/generated/resources/`.

- [ ] **Step 9: Manually verify with RCON against a real dedicated server**

Following this project's established verification methodology (real dedicated server + RCON + a genuine `ServerPlayer`, not `/setblock`/`/fill`, since block placement events matter for `MonolithRegistry`):
1. Start the dev server, place a `skavenblight:chaos_monolith` block.
2. Right-click it repeatedly as a player — confirm the "gain N Corruption" / "resist" messages alternate roughly per `monolithReadCorruptionChancePercent`.
3. Attack it repeatedly (left-click) — confirm wounds decrease (add a temporary log line or check via a debug command stub if needed), confirm a Vex spawns and targets you after `monolithWoundsPerDaemonSummon` wounds of damage, and confirm the block is destroyed once wounds reach 0 after enough hits (lower `monolithMaxWounds`/`monolithDamagePerHit` in the config temporarily to make this fast to test, then restore the defaults before committing).

- [ ] **Step 10: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/block/custom/ChaosMonolithBlock.java \
        src/main/java/org/ratden/skavenblight/block/entity/ChaosMonolithBlockEntity.java \
        src/main/java/org/ratden/skavenblight/magic/corruption/MonolithRegistry.java \
        src/main/java/org/ratden/skavenblight/block/ModBlocks.java \
        src/main/java/org/ratden/skavenblight/block/entity/ModBlockEntities.java \
        src/main/java/org/ratden/skavenblight/item/ModCreativeModeTabs.java \
        src/main/java/org/ratden/skavenblight/datagen/ModBlockLootTableProvider.java \
        src/main/java/org/ratden/skavenblight/datagen/ModBlockStateProvider.java \
        src/main/java/org/ratden/skavenblight/Config.java \
        src/main/resources/assets/skavenblight/lang/en_us.json \
        src/main/resources/assets/skavenblight/textures/block/chaos_monolith.png \
        src/generated/
git commit -m "feat(corruption): add placeholder Chaos Monolith block with wounds, daemon summons, and rune-reading risk"
```

---

### Task 7: Monolith casting-zone bonus and Catastrophic failure hook

**Files:**
- Modify: `src/main/java/org/ratden/skavenblight/magic/spell/SpellCasting.java`
- Modify: `src/main/java/org/ratden/skavenblight/Config.java` (add `monolithCastingRadiusBlocks`)
- Test: `src/test/java/org/ratden/skavenblight/magic/spell/SpellCastingOutcomeTest.java` (extend)

**Interfaces:**
- Consumes: `MonolithRegistry.isWithinRange` (Task 6), `ChaosManifestationManager.resolve` (Task 3).
- Produces: nothing new — this closes the loop the plan's Global Constraints described (Monoliths both buff and endanger casting nearby).

- [ ] **Step 1: Add the casting-radius config value**

In `Config.java`, `// --- Corruption Configs ---` section:

```java
    private static final ModConfigSpec.IntValue MONOLITH_CASTING_RADIUS_BLOCKS = BUILDER.comment("Radius in blocks around a Chaos Monolith where casting gets a bonus but any failure triggers a Catastrophic Chaos Manifestation (WFRP source: 100 feet, approximated at Minecraft scale).")
            .defineInRange("monolithCastingRadiusBlocks", 20, 1, 200);
```

```java
    public static int monolithCastingRadiusBlocks;
```

```java
        monolithCastingRadiusBlocks = MONOLITH_CASTING_RADIUS_BLOCKS.get();
```

- [ ] **Step 2: Write the failing test for the zone bonus being additive to the roll math**

`SpellCasting.attemptCast` needs a live `ServerPlayer`/`ServerLevel`, which this project's existing tests don't stand up (the existing `CastingResolverTest`/`ChunkWindStateTest` precedent tests pure math, not live casts). Extract the zone-bonus math into a small pure function so it stays unit-testable, and add tests for that function specifically:

```java
package org.ratden.skavenblight.magic.spell;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpellCastingOutcomeTest {

    @Test
    void blockedOutcomeIsNotASuccess() {
        SpellCasting.Outcome blocked = new SpellCasting.Outcome(false, true, 0, 0, 0);
        assertTrue(blocked.blockedDarkMagic());
        assertFalse(blocked.success());
    }

    @Test
    void monolithBonusIsZeroOutsideZone() {
        assertEquals(0, SpellCasting.monolithBonus(false, 7));
    }

    @Test
    void monolithBonusIsD10PlusOneInsideZone() {
        // A raw die roll of 0 (nextInt(10)) must yield a bonus of 1, and 9 must yield 10 — the
        // book's "+1d10" is a roll of 1-10, not 0-9.
        assertEquals(1, SpellCasting.monolithBonus(true, 0));
        assertEquals(10, SpellCasting.monolithBonus(true, 9));
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew test --tests "org.ratden.skavenblight.magic.spell.SpellCastingOutcomeTest"`
Expected: FAIL to compile — `SpellCasting.monolithBonus` does not exist yet.

- [ ] **Step 4: Update SpellCasting with the zone hook**

Replace the full file:

```java
package org.ratden.skavenblight.magic.spell;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.ratden.skavenblight.Config;
import org.ratden.skavenblight.magic.corruption.ChaosManifestationManager;
import org.ratden.skavenblight.magic.corruption.Corruption;
import org.ratden.skavenblight.magic.corruption.CorruptionTier;
import org.ratden.skavenblight.magic.corruption.MonolithRegistry;
import org.ratden.skavenblight.magic.player.ModAttachments;
import org.ratden.skavenblight.magic.player.PlayerMagicData;
import org.ratden.skavenblight.magic.wind.ChunkWindState;
import org.ratden.skavenblight.magic.wind.WindGridManager;

/**
 * The single place a spell actually gets cast — used by the debug /magic cast command today and
 * intended for any future real casting UI/item, so casting-adjacent mechanics (Dhar gating,
 * Monolith zones) live here once instead of being duplicated per call site.
 */
public final class SpellCasting {

    private SpellCasting() {}

    public record Outcome(boolean success, boolean blockedDarkMagic, int roll, int total, int degreesOfSuccess) {}

    /**
     * WFRP source: all spellcasters within a Monolith's radius add +1d10 to their casting roll's
     * effective bonus, whether the spell is beneficial or not. A raw 0-9 die roll (nextInt(10))
     * maps to a 1-10 result, not 0-9. Extracted as a pure function so the die-roll shape is
     * testable without a live cast.
     */
    public static int monolithBonus(boolean withinZone, int rawD10Roll) {
        return withinZone ? rawD10Roll + 1 : 0;
    }

    public static Outcome attemptCast(ServerPlayer player, Spell spell) {
        ServerLevel level = (ServerLevel) player.level();
        PlayerMagicData data = player.getData(ModAttachments.PLAYER_MAGIC.get());

        if (spell.dark() && !data.darkMagicUnlocked()) {
            return new Outcome(false, true, 0, 0, 0);
        }

        int aptitude = data.getAptitude(spell.wind());
        ChunkPos chunkPos = new ChunkPos(player.blockPosition());
        ChunkWindState windState = WindGridManager.get(level).getOrCreate(chunkPos);
        float windLevel = windState.getCurrent(spell.wind());
        int windLevelBonus = CastingResolver.windLevelBonus(windLevel);

        boolean nearMonolith = MonolithRegistry.isWithinRange(level, player.blockPosition(), Config.monolithCastingRadiusBlocks);
        int zoneBonus = monolithBonus(nearMonolith, level.getRandom().nextInt(10));

        int roll = level.getRandom().nextInt(100) + 1;
        CastingResolver.CastResult result = CastingResolver.resolve(aptitude, windLevelBonus + zoneBonus, spell.castingNumber(), roll);
        int total = aptitude + windLevelBonus + zoneBonus - spell.castingNumber();

        if (spell.dark()) {
            Corruption.grant(player, Config.dharCastCorruption);
        }

        if (!result.success()) {
            if (nearMonolith) {
                ChaosManifestationManager.resolve(level, player, CorruptionTier.CATASTROPHIC);
            }
            return new Outcome(false, false, roll, total, 0);
        }

        spell.effect().apply(player, player);
        windState.setCurrent(spell.wind(), Math.max(0f, windLevel - spell.castingNumber()));
        return new Outcome(true, false, roll, total, result.degreesOfSuccess());
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests "org.ratden.skavenblight.magic.spell.SpellCastingOutcomeTest"`
Expected: PASS

- [ ] **Step 6: Manually verify with RCON against a real dedicated server**

Reuse Task 6's Monolith placement. As a real `ServerPlayer` standing within `monolithCastingRadiusBlocks` of it: cast a spell repeatedly via `/magic cast` and confirm (a) the effective total is higher than the same cast from far away (the zone bonus is visible in the failure message's "needed <= total"), and (b) a failed cast within the zone visibly applies a Catastrophic-tier consequence (check the player's effects/health after a failed cast). Then step outside the radius and confirm a failed cast there does NOT trigger a manifestation.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/magic/spell/SpellCasting.java \
        src/main/java/org/ratden/skavenblight/Config.java \
        src/test/java/org/ratden/skavenblight/magic/spell/SpellCastingOutcomeTest.java
git commit -m "feat(corruption): Monolith proximity buffs casting rolls but turns failures Catastrophic"
```

---

### Task 8: Tome of Corruption item — unlock gate + passive carry drain

**Files:**
- Create: `src/main/java/org/ratden/skavenblight/item/custom/TomeOfCorruptionItem.java`
- Modify: `src/main/java/org/ratden/skavenblight/item/ModItems.java`
- Modify: `src/main/java/org/ratden/skavenblight/item/ModCreativeModeTabs.java`
- Modify: `src/main/java/org/ratden/skavenblight/magic/corruption/CorruptionTickHandler.java`
- Modify: `src/main/java/org/ratden/skavenblight/Config.java` (add `tomeCarryCorruptionPerCheck`)
- Modify: `src/main/resources/assets/skavenblight/lang/en_us.json`
- Create: `src/main/resources/assets/skavenblight/textures/item/tome_of_corruption.png`

**Interfaces:**
- Consumes: `Corruption.grant` (Task 1), `PlayerMagicData.withDarkMagicUnlocked` (Task 1).
- Produces: `ModItems.TOME_OF_CORRUPTION`, used by nothing else in this plan but is the intended long-term unlock path for any future Dhar-tagged spell content.

- [ ] **Step 1: Add the carry-drain config value**

In `Config.java`:

```java
    private static final ModConfigSpec.IntValue TOME_CARRY_CORRUPTION_PER_CHECK = BUILDER.comment("Corruption Points gained per corruptionTickIntervalTicks while a Tome of Corruption is anywhere in the player's inventory.")
            .defineInRange("tomeCarryCorruptionPerCheck", 1, 0, 100);
```

```java
    public static int tomeCarryCorruptionPerCheck;
```

```java
        tomeCarryCorruptionPerCheck = TOME_CARRY_CORRUPTION_PER_CHECK.get();
```

- [ ] **Step 2: Create TomeOfCorruptionItem**

```java
package org.ratden.skavenblight.item.custom;

import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.ratden.skavenblight.magic.player.ModAttachments;
import org.ratden.skavenblight.magic.player.PlayerMagicData;

/**
 * Right-click to unlock Dark Magic (Dhar) spells once, permanently. Simply carrying the Tome
 * afterward keeps draining a small trickle of Corruption — see CorruptionTickHandler — this is
 * the "unlock gate + ongoing risk" design chosen for this item.
 */
public class TomeOfCorruptionItem extends Item {

    public TomeOfCorruptionItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, net.minecraft.world.InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            return InteractionResultHolder.success(stack);
        }

        PlayerMagicData data = player.getData(ModAttachments.PLAYER_MAGIC.get());
        if (data.darkMagicUnlocked()) {
            player.displayClientMessage(Component.literal("You have already given yourself to Dark Magic."), true);
            return InteractionResultHolder.pass(stack);
        }

        player.setData(ModAttachments.PLAYER_MAGIC.get(), data.withDarkMagicUnlocked(true));
        player.displayClientMessage(Component.literal("The Tome's secrets are yours. Dhar spells are now within your reach."), true);
        return InteractionResultHolder.consume(stack);
    }
}
```

- [ ] **Step 3: Register the item**

In `src/main/java/org/ratden/skavenblight/item/ModItems.java`, add alongside the other simple item registrations (mirror however `RAT_JUICE`/`RAW_WARPSTONE` are registered — a plain `Item::new` via `ITEMS.register`):

```java
    public static final DeferredItem<Item> TOME_OF_CORRUPTION = ITEMS.register("tome_of_corruption",
            () -> new org.ratden.skavenblight.item.custom.TomeOfCorruptionItem(new Item.Properties().stacksTo(1)));
```

(Confirmed: `ModItems.ITEMS` is `DeferredRegister.Items`, so `ITEMS.register(...)` returns `DeferredItem<Item>` — matches every existing entry in that file, e.g. `RAW_WARPSTONE`/`RAT_JUICE`. Add the `import net.neoforged.neoforge.registries.DeferredItem;` import if not already present.)

In `ModCreativeModeTabs.java`, add:

```java
                        output.accept(ModItems.TOME_OF_CORRUPTION);
```

- [ ] **Step 4: Wire the passive carry-drain into CorruptionTickHandler**

Replace the full file:

```java
package org.ratden.skavenblight.magic.corruption;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.ratden.skavenblight.Config;
import org.ratden.skavenblight.item.ModItems;

/**
 * Once every Config.corruptionTickIntervalTicks per player: refresh the Tainted effect to match
 * their current tier (applying an escalating vanilla debuff at Severe+), and drain a trickle of
 * Corruption if they're carrying a Tome of Corruption.
 */
public final class CorruptionTickHandler {

    private CorruptionTickHandler() {}

    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (player.tickCount % Config.corruptionTickIntervalTicks != 0) {
            return;
        }

        if (isCarryingTome(player)) {
            Corruption.grant(player, Config.tomeCarryCorruptionPerCheck);
        }

        CorruptionTier tier = Corruption.getTier(player);
        int refreshDuration = Config.corruptionTickIntervalTicks + 20;

        if (tier == CorruptionTier.NONE) {
            player.removeEffect(ModMobEffects.TAINTED);
            return;
        }

        player.addEffect(new MobEffectInstance(ModMobEffects.TAINTED, refreshDuration, tier.ordinal() - 1, false, false, true));

        if (tier == CorruptionTier.SEVERE || tier == CorruptionTier.CATASTROPHIC) {
            // MobEffects.NAUSEA doesn't exist in this mapping set — the compiling field name
            // is MobEffects.CONFUSION (registry id "minecraft:nausea"), confirmed against the
            // decompiled NeoForge sources during Task 4.
            player.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 100, 0));
        }
        if (tier == CorruptionTier.CATASTROPHIC) {
            player.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 100, 0));
        }
    }

    private static boolean isCarryingTome(ServerPlayer player) {
        for (ItemStack stack : player.getInventory().items) {
            if (stack.is(ModItems.TOME_OF_CORRUPTION.get())) {
                return true;
            }
        }
        return false;
    }
}
```

- [ ] **Step 5: Generate a placeholder texture and add lang keys**

Generate a 16x16 placeholder book-like texture at `src/main/resources/assets/skavenblight/textures/item/tome_of_corruption.png` using the same script technique as Task 6 Step 6 (a dark cover color with a lighter "page edge" stripe along one side is enough for a placeholder).

In `en_us.json`:

```json
"item.skavenblight.tome_of_corruption": "Tome of Corruption"
```

- [ ] **Step 6: Compile and manually verify**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL. Start the dev server, give yourself the Tome (`/give @s skavenblight:tome_of_corruption`), right-click to unlock, confirm the message appears and a second right-click shows the "already given yourself" message instead. Confirm a Dhar-tagged spell (add `"dark": true` temporarily to one existing spell JSON for this test, then revert) can now be cast via `/magic cast` where it was blocked before unlocking.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/item/custom/TomeOfCorruptionItem.java \
        src/main/java/org/ratden/skavenblight/item/ModItems.java \
        src/main/java/org/ratden/skavenblight/item/ModCreativeModeTabs.java \
        src/main/java/org/ratden/skavenblight/magic/corruption/CorruptionTickHandler.java \
        src/main/java/org/ratden/skavenblight/Config.java \
        src/main/resources/assets/skavenblight/lang/en_us.json \
        src/main/resources/assets/skavenblight/textures/item/tome_of_corruption.png
git commit -m "feat(corruption): add Tome of Corruption unlock item with passive carry drain"
```

---

### Task 9: Cleansing Ward item — rare corruption reduction

**Files:**
- Create: `src/main/java/org/ratden/skavenblight/item/custom/CleansingWardItem.java`
- Modify: `src/main/java/org/ratden/skavenblight/item/ModItems.java`
- Modify: `src/main/java/org/ratden/skavenblight/item/ModCreativeModeTabs.java`
- Modify: `src/main/java/org/ratden/skavenblight/Config.java` (3 new values)
- Modify: `src/main/resources/assets/skavenblight/lang/en_us.json`
- Create: `src/main/resources/assets/skavenblight/textures/item/cleansing_ward.png`

**Interfaces:**
- Consumes: `Corruption.reduce`/`.getPoints` (Task 1), `PlayerMagicData.lastCleanseGameTime`/`.withLastCleanseGameTime` (Task 1), `Wind.HYSH` (existing), `WindGridManager`/`ChunkWindState` (existing).
- Produces: `ModItems.CLEANSING_WARD` — this plan's one deliberately rare, high-friction corruption-reduction path (per the "mostly one-way, rare cleanse" design decision).

- [ ] **Step 1: Add the cleanse config values**

```java
    private static final ModConfigSpec.IntValue CLEANSE_HYSH_REQUIREMENT = BUILDER.comment("Minimum local Hysh Wind level required to use a Cleansing Ward.")
            .defineInRange("cleanseHyshRequirement", 300, 0, 10000);
    private static final ModConfigSpec.IntValue CLEANSE_AMOUNT = BUILDER.comment("Corruption Points removed by a single successful Cleansing Ward use.")
            .defineInRange("cleanseAmount", 15, 1, 1000);
    private static final ModConfigSpec.IntValue CLEANSE_COOLDOWN_TICKS = BUILDER.comment("Minimum ticks between a player's Cleansing Ward uses (default: one Minecraft day).")
            .defineInRange("cleanseCooldownTicks", 24000, 0, 1000000);
```

```java
    public static int cleanseHyshRequirement;
    public static int cleanseAmount;
    public static int cleanseCooldownTicks;
```

```java
        cleanseHyshRequirement = CLEANSE_HYSH_REQUIREMENT.get();
        cleanseAmount = CLEANSE_AMOUNT.get();
        cleanseCooldownTicks = CLEANSE_COOLDOWN_TICKS.get();
```

- [ ] **Step 2: Create CleansingWardItem**

```java
package org.ratden.skavenblight.item.custom;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import org.ratden.skavenblight.Config;
import org.ratden.skavenblight.magic.Wind;
import org.ratden.skavenblight.magic.corruption.Corruption;
import org.ratden.skavenblight.magic.player.ModAttachments;
import org.ratden.skavenblight.magic.player.PlayerMagicData;
import org.ratden.skavenblight.magic.wind.ChunkWindState;
import org.ratden.skavenblight.magic.wind.WindGridManager;

/**
 * The deliberately rare, high-friction way to reduce Corruption: requires standing in strong
 * ambient Hysh (Light opposes Dhar thematically), a long per-player cooldown, and consumes the
 * item. This is the one exception to Corruption being a one-way ratchet.
 */
public class CleansingWardItem extends Item {

    public CleansingWardItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            return InteractionResultHolder.success(stack);
        }
        if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResultHolder.pass(stack);
        }

        PlayerMagicData data = serverPlayer.getData(ModAttachments.PLAYER_MAGIC.get());
        long now = serverLevel.getGameTime();
        if (now - data.lastCleanseGameTime() < Config.cleanseCooldownTicks) {
            serverPlayer.displayClientMessage(Component.literal("The Ward is spent. You must wait before it can cleanse you again."), true);
            return InteractionResultHolder.fail(stack);
        }

        ChunkWindState windState = WindGridManager.get(serverLevel).getOrCreate(new ChunkPos(serverPlayer.blockPosition()));
        if (windState.getCurrent(Wind.HYSH) < Config.cleanseHyshRequirement) {
            serverPlayer.displayClientMessage(Component.literal("The Light here is too faint to cleanse you."), true);
            return InteractionResultHolder.fail(stack);
        }

        if (Corruption.getPoints(serverPlayer) <= 0) {
            serverPlayer.displayClientMessage(Component.literal("You bear no corruption to cleanse."), true);
            return InteractionResultHolder.fail(stack);
        }

        Corruption.reduce(serverPlayer, Config.cleanseAmount);
        serverPlayer.setData(ModAttachments.PLAYER_MAGIC.get(),
                serverPlayer.getData(ModAttachments.PLAYER_MAGIC.get()).withLastCleanseGameTime(now));
        serverPlayer.displayClientMessage(Component.literal("The Light burns the taint from you."), true);
        stack.shrink(1);
        return InteractionResultHolder.consume(stack);
    }
}
```

- [ ] **Step 3: Register the item**

In `ModItems.java`:

```java
    public static final DeferredItem<Item> CLEANSING_WARD = ITEMS.register("cleansing_ward",
            () -> new org.ratden.skavenblight.item.custom.CleansingWardItem(new Item.Properties()));
```

In `ModCreativeModeTabs.java`:

```java
                        output.accept(ModItems.CLEANSING_WARD);
```

- [ ] **Step 4: Generate a placeholder texture and add lang keys**

Generate a 16x16 placeholder texture at `src/main/resources/assets/skavenblight/textures/item/cleansing_ward.png` (a pale gold/white amulet-like shape, using the same script technique as before).

```json
"item.skavenblight.cleansing_ward": "Cleansing Ward"
```

- [ ] **Step 5: Compile and manually verify**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL. In-game: give yourself corruption (temporarily, via whatever means Task 6 already exposed — reading a Monolith enough times), stand in a chunk with high Hysh (a Hysh Wizard Tower area, or temporarily lower `cleanseHyshRequirement`), use the Ward, confirm points drop by `cleanseAmount` and the item is consumed. Try again immediately — confirm the cooldown message appears and no further reduction happens.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/item/custom/CleansingWardItem.java \
        src/main/java/org/ratden/skavenblight/item/ModItems.java \
        src/main/java/org/ratden/skavenblight/item/ModCreativeModeTabs.java \
        src/main/java/org/ratden/skavenblight/Config.java \
        src/main/resources/assets/skavenblight/lang/en_us.json \
        src/main/resources/assets/skavenblight/textures/item/cleansing_ward.png
git commit -m "feat(corruption): add Cleansing Ward as the sole rare corruption-reduction path"
```

---

### Task 10: Debug commands + Modonomicon documentation

**Files:**
- Create: `src/main/java/org/ratden/skavenblight/command/debug/DebugCorruptionCommands.java`
- Modify: `src/main/java/org/ratden/skavenblight/command/SkavenDebugCommand.java` (this is the file that already registers `DebugWindCommands`/`DebugMagicCommands` as subcommands — confirmed by grep before writing this plan).
- Create: Modonomicon entry/category JSON under `data/skavenblight/modonomicon/books/nexus_research/` documenting Tzeentch's Curse (mirror the structure of the existing Winds-of-Magic entries in that same book).

**Interfaces:**
- Consumes: everything from Tasks 1-9.
- Produces: an in-game way to inspect/adjust corruption state for testing, and a player-facing documentation page.

- [ ] **Step 1: Create DebugCorruptionCommands**

```java
package org.ratden.skavenblight.command.debug;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.ratden.skavenblight.magic.corruption.Corruption;
import org.ratden.skavenblight.magic.corruption.CorruptionTier;

public class DebugCorruptionCommands {

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("corruption")
                .then(Commands.literal("grant")
                        .then(Commands.argument("amount", IntegerArgumentType.integer())
                                .executes(context -> grant(context.getSource(),
                                        IntegerArgumentType.getInteger(context, "amount")))))
                .then(Commands.literal("info")
                        .executes(context -> info(context.getSource())));
    }

    private static int grant(CommandSourceStack source, int amount) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("This command must be run by a player."));
            return 0;
        }
        CorruptionTier tier = Corruption.grant(player, amount);
        source.sendSuccess(() -> Component.literal(
                "Corruption now " + Corruption.getPoints(player) + " (tier: " + tier.getSerializedName() + ")"), true);
        return 1;
    }

    private static int info(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("This command must be run by a player."));
            return 0;
        }
        int points = Corruption.getPoints(player);
        CorruptionTier tier = Corruption.getTier(player);
        source.sendSuccess(() -> Component.literal(
                "Corruption: " + points + " points (tier: " + tier.getSerializedName() + ")"), false);
        return 1;
    }
}
```

- [ ] **Step 2: Wire it into the debug command tree**

In `SkavenDebugCommand.java`, add `.then(DebugCorruptionCommands.register())` alongside the existing `.then(DebugWindCommands.register())`/`.then(DebugMagicCommands.register())` calls, with a matching import.

- [ ] **Step 3: Add a Modonomicon documentation entry**

Create a new entry JSON documenting Tzeentch's Curse under `data/skavenblight/modonomicon/books/nexus_research/entries/` (mirror the exact structure — category assignment, icon, page format — of an existing entry file in that directory, e.g. whichever one documents the Wizard Towers wind-tagging system). Content to cover: what Corruption Points are, that Dhar spells and Chaos Monoliths both grant them, that the Tainted effect intensifies at higher tiers, and that a Cleansing Ward is the only known way to reduce it. Do not invent new mechanics in this documentation beyond what Tasks 1-9 actually built — no mention of a mutation system (explicitly out of scope, see this plan's header).

- [ ] **Step 4: Compile and run the full test suite**

Run: `./gradlew build && ./gradlew test`
Expected: BUILD SUCCESSFUL, all tests passing.

- [ ] **Step 5: Manually verify the full loop end-to-end**

Using the same RCON-driven real-dedicated-server methodology as Tasks 6-7: as a real player, confirm `/corruption info` starts at 0; reading a Monolith, casting a Dhar spell, and carrying the Tome all raise it (confirm via `/corruption info` after each); confirm the Tainted effect appears once `corruptionTickIntervalTicks` elapses past 10 points; confirm a Cleansing Ward reduces it under the conditions from Task 9. Open the Modonomicon book in-game and confirm the new entry renders without errors.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/command/debug/DebugCorruptionCommands.java \
        data/skavenblight/modonomicon/books/nexus_research/
git commit -m "feat(corruption): add debug commands and Modonomicon documentation for Tzeentch's Curse"
```

---

## Backlog (explicitly out of scope for this plan)

- **Permanent Mutation system.** This plan builds the plumbing (`CorruptionTier`, `ChaosManifestationEffect`/`Table`, `Tainted`) a future mutation system would consume, but does not add lasting character mutations. A future plan should add a new `ChaosManifestationEffect` case (e.g. `ApplyMutation`) once the mutation system's own design is worked out.
- **Per-Chaos-god Monolith variants** (Khorne suppresses magic entirely, Slaanesh/Nurgle/Tzeentch cosmetic variants) — only the generic variant is built here.
- **Real player-facing casting UI/item** — `SpellCasting` is designed to be reused by one, but this plan only wires it to the existing debug `/magic cast` command.
- **More corruption gain/loss sources** — the user asked for this system to be easy to extend; `Corruption.grant`/`.reduce` and the pooled `ChaosManifestationManager` are the extension points for that, but only Monoliths, Dhar casting, the Tome, and the Ward are actually wired up in this plan.
