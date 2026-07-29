# Winds of Magic — Design Spec

Source material: `MagicDesign.md` (project notes) and WFRP *Realms of Sorcery* (2e core magic
book — Chapters II, VI, VII, VIII). This spec turns those into a concrete, data-driven feature
for Skavenblight, structured so that adding content later is mostly JSON + lang keys, not new
Java.

This is a **big** feature. Treat this doc as the map; build it in the milestone order given at
the end, not all at once.

## 1. How this relates to the existing Warp Flux system

Warp Flux (`network/WarpFluxNetwork`, `WarpFluxGridManager`) is **player-built infrastructure**:
conduits, batteries, a nexus, flowing along block networks. Winds of Magic is a different layer:
**ambient world state**. It is not a network of blocks — it's a value per chunk that exists
whether or not the player has built anything, and it drifts, driven by geography/time/biome, the
same way weather does.

Keep them architecturally separate (separate `SavedData`, separate packages), but:

- Both are per-chunk, both use `SavedData` + a grid manager ticked from `LevelTickEvent.Post`,
  so `WindGridManager` should read like a sibling of `WarpFluxGridManager`, not a clone of its
  network/graph logic (winds have no conduits to flood-fill).
- The Nexus/Chaos Gate lore concept can literally point at the same "distance to nexus" math the
  territory system already computes, if you want warpstone bases to run hot with Dhar over time —
  a nice thematic hook, not a requirement for v1.
- The Modonomicon book already scaffolds a `winds_of_magic` category inside the existing
  `nexus_research` book (`data/skavenblight/modonomicon/books/nexus_research/`). This spec
  replaces that single WIP category with one category per Wind, plus a `dark_magic` category,
  reusing the existing `dwarven_archeology` category (already stubbed, sort order right after
  the winds) as the Runesmithing gateway your notes call for.

## 2. Lore reference (grounding for the content lists below)

The 8 Winds and their WFRP Lores map 1:1 — use this as the canonical school/wind pairing
everywhere in code (an enum, not two parallel concepts):

| Wind | Colour | Lore name | Order/College | Theme |
|---|---|---|---|---|
| Hysh | White | Light | Order of Light | healing, protection, abjuration, banishing |
| Azyr | Blue | Heavens | Celestial College | astrology, fate, weather, portents |
| Chamon | Yellow | Metal | Golden Order | alchemy, transmutation, enchanting |
| Ghyran | Green | Life | Jade College | growth, nature, druidism |
| Aqshy | Red | Fire | Bright Order | destruction, heat, forging |
| Ghur | Brown | Beasts | Amber Brotherhood | shapeshifting, animal command |
| Ulgu | Grey | Shadow | Grey Order | illusion, stealth, fear |
| Shyish | Purple | Death | Amethyst Order | death, decay, undeath, fate-severing |

Above these: **High Magic** (Qhaysh, High Elves only — lore/story flavor, not player-castable),
and **Dark Magic** (Dhar — corrupted blend of multiple winds, unlocked late, dangerous).

## 3. Chunk Wind System

### 3.1 Data model

Per chunk: 8 floats "current" + 8 floats "baseline" (indexed by `Wind.ordinal()`). Values are
unbounded non-negative (`0..∞`, arbitrary scale — pick `0..1000` as the working range so effect
thresholds are round numbers).

```java
// magic/wind/ChunkWindState.java
public final class ChunkWindState {
    private final float[] current;   // size = Wind.values().length
    private final float[] baseline;
    // getCurrent(Wind), getBaseline(Wind), setCurrent(Wind, float), setBaseline(Wind, float)
    // CompoundTag read/write (two float arrays, same shape as WarpFluxGridManager's long[] pattern)
}
```

### 3.2 Manager (`SavedData`, mirrors `WarpFluxGridManager`)

```java
// magic/wind/WindGridManager.java
public class WindGridManager extends SavedData {
    private final Map<ChunkPos, ChunkWindState> chunkStates = new HashMap<>();

    public static WindGridManager get(ServerLevel level) { ... } // SavedData.Factory, id "skavenblight_winds"
    public ChunkWindState getOrCreate(ChunkPos pos) { ... }

    public void tick(ServerLevel level) {
        // for each loaded/tracked chunk: drift current -> baseline (rate configurable),
        // recompute baseline from WindInfluence sources, apply thresholds -> WindEffectRegistry
    }
}
```

Only compute for **loaded** chunks (`level.getChunkSource().hasChunk`) plus any chunk a player is
within config radius of — don't iterate the whole `chunkStates` map blindly once worlds get old
and it accumulates thousands of entries. Evict/skip unloaded chunks the same way you'd want to
prune it later; a periodic cleanup pass (like `WarpFluxGridManager` never really needed, but this
system will) is worth a config value from day one.

### 3.3 Natural influences (baseline drivers)

Model each as its own small class implementing one interface, registered in a list the manager
consults each recompute — this is the extension point, keep it stupid-simple:

```java
public interface WindBaselineInfluence {
    // returns a delta per wind to nudge the baseline toward
    void apply(ServerLevel level, ChunkPos pos, float[] baselineDeltaOut);
}
```

Ship these four to start (matches `MagicDesign.md` §"Natural influence"):

1. **ChaosGateDistanceInfluence** — global background level from a Gaussian-ish falloff around a
   configurable world point (default: world spawn, or a future "Chaos Gate" structure/waystone
   block once you build one). Raises *all* winds slightly, Dhar-adjacent chunks more.
2. **BiomeInfluence** — data-driven via a **biome tag → wind bonus** data map (use
   `ModDataMapProvider`-style NeoForge `DataMapProvider`, exactly like the existing
   `FurnaceFuel` data map). E.g. `#skavenblight:wind_biome/warm_ocean_like` → `{aqshy: +40,
   hysh: +10, chamon: -20}`. This is the "hand-coding easy" lever for biomes: add a JSON line, no
   new Java.
3. **TimeOfDayInfluence** — Hysh up during day, Ulgu up at night (from your notes: "White is
   elevated during the day, Grey is high during the night"). Pure function of `level.getDayTime()`.
4. **TaggedBlockInfluence** — scan block-placement events (not every tick) for blocks in new
   `skavenblight:wind_source/<wind>` block tags, maintaining a small per-chunk counter
   incrementally (increment on place, decrement on break) rather than rescanning the chunk each
   tick. This is Tier 0 player control from the notes ("player decorates with tagged mundane
   blocks").

Great Vortex distance affects *drift rate* and *baseline resistance*, not baseline value — apply
it as a multiplier in `WindGridManager.tick`, not as an `Influence`.

### 3.4 Player control tiers (from `MagicDesign.md`)

| Tier | Mechanism | Implementation hook |
|---|---|---|
| 0 | Decorate with tagged mundane blocks | `TaggedBlockInfluence` above |
| 1 | Cast basic spells, drain wind locally | `Spell` cast reduces `ChunkWindState.current` for its Wind |
| 2 | Magic blocks that raise a wind's baseline capacity/regen | New block + BE, writes into `WindGridManager` directly (like a `WarpFluxStorageBlock` but for ambient wind, not flux) |
| 3 | Wind-specific Power Stones, reduce cast dependency on local levels; leak wind when carried | Item capability/component read each tick while in inventory (`ItemTickEvent` or attach via `ItemStack` component tick) |

### 3.5 Effects at thresholds

`WindEffectRegistry` — data-driven list of `(Wind, minLevel, EffectHandler)`. Handler is a small
functional interface (`particle burst`, `ambient sound`, `mob spawn conditions`, `weather nudge`).
Per the notes: only the **top 2–3 winds** in a chunk actually trigger visuals; a wind sitting at
90/1000 in a chunk where three other winds are at 400+ shouldn't visually compete. Sort by level
descending, take top N, run their effects; if **4 or more** winds are simultaneously above a
"high" threshold, run Dark Magic manifestation effects instead of (not in addition to) the
individual wind effects — this is also your Dhar-formation trigger (see §6).

## 4. Player attributes & progression

Adapt WFRP's "Magic Characteristic" and per-Lore skill as **NeoForge Attributes**, one attribute
per Wind (`skavenblight:aptitude.hysh`, etc.) plus a generic `skavenblight:magic_characteristic`
that gates raw casting power. Advancement through tiers (Apprentice → Journeyman → Master →
Wizard Lord) should be Modonomicon **advancement-gated entries** (the book already sets
`auto_add_read_conditions: true`), not a separate custom levelling system — let Modonomicon own
progression UI, let advancements own the requirements (spells cast, chunk-wind thresholds
witnessed, items crafted), and store the *numeric* aptitude as an attachment (§7) that advancement
criteria read.

## 5. Spell system

### 5.1 Data model — spells are datapack JSON, not Java classes

This is the single most important "hand-coding easy" decision: a spell is *data*. New spells
should never require a new Java class. Use a `SimpleJsonResourceReloadListener<Spell>` (the same
mechanism vanilla uses for recipes/loot tables) reading `data/<ns>/magic/spells/*.json`, not a
full custom NeoForge datapack `Registry` (which needs `RegistryAccess` plumbing and is overkill
for content that never needs to be `Holder`-referenced from other registries).

```java
// magic/spell/Spell.java
public record Spell(
    Wind wind,                 // school/lore
    int tier,                  // 0 = petty, 1 = apprentice, ... matches wind tier ladder
    int castingNumber,         // WFRP-style difficulty, drives your success-check formula
    CastingTime castingTime,   // enum: INSTANT, HALF_ACTION, FULL_ACTION, CHANNELLED(ticks)
    Optional<Ingredient> componentItem,   // WFRP "ingredient" bonus item, optional, consumed
    SpellEffect effect,        // sealed interface, see below
    String descriptionKey      // lang key for the Modonomicon page text
) {
    public static final Codec<Spell> CODEC = ...;
}
```

`SpellEffect` as a small **sealed interface with a handful of concrete cases** (not a scripting
language — resist the urge): `DamageEffect`, `MobEffectApply`, `SummonEffect`,
`BlockTransformEffect`, `WindDrainEffect`, `UtilityEffect(String handlerId)` for anything one-off
that's easier as a registered Java handler than data. Each case gets its own `MapCodec` and a
`DISPATCH_CODEC` keyed by a `type` string, exactly like vanilla's `Codec.xor`/dispatch pattern for
particle options or trigger types — this keeps 90% of spells fully data-driven while leaving an
escape hatch for the fifteen spells that really do need bespoke code.

### 5.2 Casting number → Minecraft mapping

Suggest: casting number (WFRP: roll-under a %, degrees of success matter) becomes a **skill
check** against the player's per-wind aptitude attribute plus local wind level, resolved as a
simple `success = aptitude + windLevelBonus - castingNumber >= threshold roll`. Keep the actual
formula in one place (`magic/spell/CastingResolver.java`) so you can iterate on numbers without
touching every spell. Miscasts/"When Spells Go Wrong" (source doc Ch. VI) map naturally onto a
"failed roll by a lot → Dhar backlash" table — small, config-driven, not urgent for v1.

### 5.3 Player magic data

Attachment (NeoForge 1.21 `AttachmentType`, not an old-style `Capability` — capabilities are for
block/item/entity *services*, attachments are for arbitrary persistent data, which is what this
is):

```java
// magic/player/PlayerMagicData.java
public record PlayerMagicData(
    Map<Wind, Integer> tier,          // 0..3 per wind
    Map<Wind, Integer> aptitude,      // raw magic-characteristic-per-wind score
    Set<ResourceLocation> knownSpells
) {
    public static final Codec<PlayerMagicData> CODEC = ...;
    public static final PlayerMagicData EMPTY = new PlayerMagicData(Map.of(), Map.of(), Set.of());
}
```

```java
// magic/player/ModAttachments.java
DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
    DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, Skavenblight.MODID);
Supplier<AttachmentType<PlayerMagicData>> PLAYER_MAGIC = ATTACHMENT_TYPES.register("player_magic",
    () -> AttachmentType.builder(() -> PlayerMagicData.EMPTY).serialize(PlayerMagicData.CODEC).build());
```

## 6. Dark Magic (Dhar)

Two distinct triggers, both already implied by the notes — build both, they're cheap once the
wind system exists:

1. **Casting-side Dhar**: a player who mixes Lores (multiple Wind tiers ≥ 2) and attempts a
   Dark Magic spell risks corruption effects (a `MobEffect` "Tainted" that intensifies with use,
   eventually causing mutation-flavoured debuffs — reuse WFRP's Chaos Manifestation tables loosely
   for flavor).
2. **World-side true Dhar**: from §"Chunk Wind Levels" — 4+ winds simultaneously high in a chunk
   with no nearby Great Vortex influence swirl into a standing Dhar pool. Represent this as a
   **derived 9th value** on `ChunkWindState` (`dharLevel`), computed in `WindGridManager.tick`
   from how long the 4-wind condition has persisted (accumulate, don't spike instantly — "coagulate
   and stagnate" per the fiction). High `dharLevel` chunks are where you'd later spawn a "tar pool"
   block/structure, corrupt nearby mobs, or let a Dark Magic wizard "harvest" Dhar directly instead
   of a single Wind.

Dark Magic tab in Modonomicon: unlock condition = advancement requiring Tier 2 in **at least two**
different Winds (matches "unlocked after reaching tier 2 in multiple schools").

## 7. Research book (Modonomicon) structure

Book `skavenblight:nexus_research` already exists. Target category tree:

```
nexus_research (book)
├── nexus                  (existing — Warp Flux infrastructure)
├── skaven_engineering     (existing)
├── winds_of_magic_*       (NEW — one category per Wind, replaces the single WIP category)
│   ├── hysh, azyr, chamon, ghyran, aqshy, ghur, ulgu, shyish
│   └── each category: entries for [Overview] → [Petty spells] → [Apprentice spells] →
│         [Journeyman] → [Master] → [Power Stone] → [Magic Machine], gated by advancements
│         per tier so the book visibly grows as the player advances (Modonomicon supports
│         per-entry read/unlock conditions — use them instead of hiding content behind manual
│         page order)
├── dark_magic              (NEW — locked behind multi-wind tier-2 advancement)
└── dwarven_archeology      (existing, repurpose as the Runesmithing gateway per the notes:
                              "should require some kind of Dwarven relic or knowledge to unlock")
```

Practical tip for "hand-coding easy": Modonomicon supports **datagen** (a `BookProvider`-style
Java generator that emits the same JSON your hand-authored files would). Given 8 Winds × ~5
entries × several pages each, hand-writing that much boilerplate JSON is exactly the kind of
repetition worth generating from a small `List<WindContent>` in Java once the shape stabilizes.
For now, this repo hand-authors the *category* files (below) since there are only 9 of them, but
plan to datagen the *entry/page* files once each Wind's spell list is finalized — one loop over
`Wind.values()` emitting entries beats hand-copy-pasting 8 near-identical JSON trees.

## 8. Content catalog (concrete suggestions)

Spell names below are pulled from WFRP's Lore lists (renamed/trimmed where noted) — use them as
your petty/apprentice tier seed list; you don't need all of them, and Minecraft mechanics will
suggest cuts (anything that's pure roleplay flavor with no game hook, skip).

### Hysh — Light (healing/protection/banishing)
- Power stone: **Lumen Stones**
- Magic machine: a **Consecration Font** — slowly purifies/cures potion effects on players standing near it, costs Hysh.
- Spells: *Boon of Hysh* (heal over time), *Clarity* (cure blindness/nausea), *Banish* (damage undead/illagers), *Blinding Light* (flash + blind), *Daemonbane*, *Illuminate the Edifice* (light up an area/reveal hidden blocks), *Light of Purity* (remove negative effects), *Pillar of Radiance* (AoE holy damage), *Radiant Sentinel* (summon a guardian light construct), *Radiant Weapon* (temp weapon enchant).

### Azyr — Heavens (fate/weather/portents)
- Power stone: **True Sapphires**
- Magic machine: an **Astrolabe/Orrery** that predicts (and slightly biases) next weather change or gives a "wind forecast" for the chunk — nice UI hook for teaching the system to players.
- Spells: *Birdspeak* (understand/command birds — great early utility spell), *Clear Sky* (dispel rain/thunder locally), *Fortune's Renewal*, *Lens on the Sky* (reveal structures/coordinates), *Lightning Bolt*, *Lightning Storm* (AoE), *Premonition* (warn of nearby hostile mobs), *Starshine* (light + slow fall), *Windblast* (knockback cone), *Wings of Heaven* (temporary flight/slow fall).

### Chamon — Metal/Alchemy
- Power stone: **Goldstone**
- Magic machine: **Alchemy Lab** (explicitly called out in notes) — tiered (poor/good/best per Config's tier pattern already used for the Nexus), unlocks potion recipes.
- Spells: *Armour of Lead* (temp resistance), *Curse of Rust* (weaken enemy armor/tools), *Enchant Item*, *Fool's Gold* (transmute cosmetic-only), *Law of Gold* (Midas-style transmute, capped/risky), *Guard of Steel*, *Transformation of Metal* (shape metal blocks), *Trial and Error* (random minor alchemical boon/mishap — good flavor spell for miscast design).

### Ghyran — Life/Nature
- Power stone: **Vitaellum**
- Magic machine: a **Grove Shrine** boosting crop/tree growth rate in radius while Ghyran is high.
- Spells: *Cure Blight* (cure withering/poison, restore farmland), *Curse of Thorns* (root/damage), *Earthblood* (heal + regen), *Father of Thorns* (summon thorn hazard), *Fat of the Land* (temp saturation/crop yield), *Leaf Fall* (slow fall via leaves), *Spring Bloom* (instant crop growth, area), *Summer Heat* (accelerate growth further), *Vital Growth*, *Wood Shape* (reshape logs like a woodcutter's axe spell), *Winter Frost* (inverse — kill crops/freeze water, a "corrupted Ghyran" flavor spell).

### Aqshy — Fire
- Power stone: **Fire Rubies**
- Magic machine: a **Wind-Fed Blast Furnace** — smelts faster / doubles output while Aqshy is high (from notes).
- Spells: *Aqshy's Arrows* (multi-shot fire projectile), *Burning Vengeance*, *Conflagration of Doom* (big AoE), *Crown of Fire* (self-buff, fire aura), *Fireball*, *Fires of Uzhul*, *Flaming Sword of Rhuin* (weapon enchant), *Flashcook* (instant-cook items in inventory — good utility/economy spell), *Shield of Aqshy* (fire resistance), *Hearts of Fire*, *Taste of Fire* (breath weapon).

### Ghur — Beasts
- Power stone: **Ghost Amber**
- Magic machine: none obvious from source — suggest a **Beast Ward Totem** (repels/calms hostile mobs in radius).
- Spells: *Beast Master's Shape* (temporary shapeshift, capstone spell), *Calm the Wild Beast* (pacify hostile mob), *Cowering Beasts* (fear effect on animals/mobs), *Claws of Fury* (melee buff), *Crow's Feast* (summon/command birds to scout or harass), *Form of the Raging Bear*, *Form of the Ravening Wolf*, *Form of the Soaring Raven* (flight), *Form of the Puissant Steed* (speed boost, mount-flavored), *Leatherbane* (armor-piercing vs. leather/hide mobs), *Wings of the Falcon*.

### Ulgu — Shadow
- Power stone: **Crystal Mist**
- Magic machine: none obvious from source — suggest a **Mistveil Lantern** decoy/stealth block.
- Spells: *Bewilder* (confuse/miss chance debuff), *Burning Shadows*, *Clockactivity* (freeze/slow a mechanism — fun redstone-adjacent hook), *Doppelganger* (decoy clone), *Dread Aspect* (fear aura), *Mindhole* (memory/blindness debuff), *Mockery of Death* (fake-death/invisibility), *Shadow of Death*, *Shadowsteed* (fast dark mount/dash), *Path of Darkness* (short teleport in shadow), *Shroud of Invisibility*, *Substance of Shadow* (phase through walls briefly), *Take No Heed* (stealth boost).

### Shyish — Death
- Power stone: **Endstones**
- Magic machine: none from source — a **Bone Reliquary** for storing/reanimating mob drops as minions fits both lore and existing Skaven necromancer-adjacent flavor.
- Spells: *Death's Door* (execute low-HP target), *Death's Messenger* (summon minor spectral servant), *Death's Release* (mercy-kill/cleanse curse), *Deathsight* (see through walls/detect nearby deaths, X-ray-lite), *Final Words* (last-words message on death — pure flavor, cheap to add), *Grief's End*, *Icy Grip of Death* (slow + damage), *Limbwither* (cripple a limb — mining-speed/attack-speed debuff), *Steal Life* (life drain), *Swift Passing* (painless kill, good/neutral use), *Wind of Death* (strong AoE necrotic damage — capstone), *Tomb Robber's Curse* (curse-on-loot effect for grave/dungeon loot tables — nice tie-in to loot design).

### Dark Magic (Dhar) — a handful, not a full tier
Corrupted composites once unlocked: *Doom-Bolt* (high damage, self-corruption cost), *Withering Sight*, *Bind Daemon* (risky summon), *Sluggish Tar* (create/interact with a True Dhar pool per the fiction). Keep this list short — Dark Magic should feel like 5–8 unique, expensive, risky spells, not a ninth full Lore.

## 9. Rune Magic (Runesmithing)

Rules already fully specified in `MagicDesign.md` (Form/Three/Mastery/Pride/Time) — implement
those as literal constraints on an item-enchant-like system: a `RuneType` enum (`ARMOUR`,
`WEAPON`, `TALISMANIC`, `ENGINEERING`), a max of 3 non-master runes per item, master runes
exclusive. This maps very cleanly onto NeoForge **data components** (`ItemStack` component
holding `List<ResourceLocation> runes`), not a new enchantment-table UI — simpler to hand-code and
avoids fighting vanilla's enchantment system for something that isn't really an enchantment.

Suggested starter rune catalog (from the source's actual rune list — pick a subset per item type,
don't ship all of these at once):

| Rune | Type | Effect idea |
|---|---|---|
| Rune of Fire | Weapon | fire aspect |
| Rune of Fury | Weapon | attack speed |
| Rune of Cleaving | Weapon | bonus damage vs. armored |
| Rune of Striking | Weapon | flat damage bonus |
| Rune of Iron | Armour | flat defense |
| Rune of Stone | Armour | knockback resistance |
| Rune of Fortitude | Armour | max health |
| Rune of Warding | Armour | resistance to a damage type |
| Rune of Spellbreaking | Talismanic | debuff resistance / dispel |
| Rune of Spelleating | Talismanic | absorb hostile spell into a temporary buff |
| Rune of Luck | Talismanic | loot bonus |
| Rune of Resistance | Talismanic | status effect resistance |
| Rune of Speed | Engineering | mining/movement speed on a tool/machine |
| Rune of the Furnace | Engineering | smelting speed bonus (ties nicely to the Aqshy blast furnace) |
| Rune of Grudges | Engineering | bonus vs. a player-tagged "grudge" target — pure Dwarf flavor |

Master runes (jealous — no other runes allowed): reserve for the most powerful, one-of-a-kind
effects (flight, true invisibility, a "runefang"-tier weapon bonus). Very small list, high value.

Rule-of-Pride cooldown suggestion: rather than literally preventing a duplicate, apply an
escalating time-cost penalty to the Runesmith's *next* Runecraft attempt when they re-forge an
identical rune combination — keeps the flavor without a hard block that fights the player.

## 10. Potions & Brewing

Characteristics already enumerated in the notes match the source exactly (Name, Effect, Lag Time,
Volatility, Ingredient Cost/Locale/Difficulty, Creation Number, Creation Time) — implement as a
data record identical in shape:

```java
public record PotionRecipe(
    ResourceLocation id, Component name, MobEffectInstance effect,
    int lagTimeTicks, Volatility volatility,   // MINOR, MODERATE, MAJOR, EXTREME
    Ingredient ingredientTag, int ingredientCountMin, int ingredientCountMax,
    int creationNumber, int creationTimeTicks
) {}
```

Loaded the same way as `Spell` (`SimpleJsonResourceReloadListener`). Alchemy Lab tiers (poor/good/
best) gate which `creationNumber` difficulty a lab can attempt — reuse the exact
tier-config pattern `Config.java` already has for the Nexus (`tier0Capacity`/`tier1Capacity`/...).
Brewing disaster-on-failure table can start as 3–4 generic "spoiled potion" `MobEffect`s
(nausea/poison/weakness) rather than porting the full WFRP spoilage table (§Table 7-9) — that
table is delightfully specific to tabletop play (things like "Bunions", "Goitre") and mostly
doesn't translate to a Minecraft effect; pick the ~6 that do (Poison, Hallucination →
Nausea+Blindness, Madness → Blindness+Slowness, Mutation → apply a random negative effect,
Retching Sick → Poison, Sensory Loss → Blindness+Deafness-flavor) and stop there.

## 11. Familiars

Source confirms this is worth building small: creation vs. binding are two distinct 3-step flows
(matches the notes exactly), with a **Familiar Ability** list worth stealing directly since
they're small, discrete, and map onto Minecraft mechanics with almost no translation:

- **Aethyric Reservoir** — familiar itself stores a bit of Wind, like a mini power stone.
- **Link of Fate/Psyche** — familiar can "take" one hit for its master (redirect damage once)
  or share a status effect.
- **Lucky Charm** — small loot/luck bonus while familiar is nearby.
- **Focus Magic** — reduces casting number / raises success chance while familiar is present.
- **Magic Power** — flat aptitude bonus while familiar is nearby.
- **Master's Touch / Master's Voice** — utility command actions (fetch, tag/mark a mob, relay a
  message across dimensions/distance — this last one is a fun MC-specific extension).

Implementation shape: a familiar is an entity (or a `Mob`-riding-nothing companion) carrying an
attachment mirroring `PlayerMagicData`'s shape (`Set<FamiliarAbility>`, `personality`,
`relationship` enums), bonded to a player UUID. Random-generation tables (type, physical quirk,
personality, relationship) are exactly the kind of thing to keep as small weighted-list JSON
(`data/skavenblight/magic/familiar_traits/*.json`) rather than hardcoded Java switch statements —
same "content = data" principle as spells.

Given the scope of everything else in this doc, treat Familiars as an **optional milestone**
(your own notes say "might not be worth implementing") — the data shape costs little to reserve
now, but don't block the Winds/Spells/Runes milestones on it.

## 12. Package layout

```
org.ratden.skavenblight.magic/
├── Wind.java                       enum: 8 winds, colour, lore name, translation key
├── wind/
│   ├── ChunkWindState.java         per-chunk float[8] current + float[8] baseline (+ dharLevel)
│   ├── WindGridManager.java        SavedData, tick, drift-to-baseline
│   ├── WindBaselineInfluence.java  interface
│   ├── influence/
│   │   ├── ChaosGateDistanceInfluence.java
│   │   ├── BiomeInfluence.java     reads the wind_biome data map
│   │   ├── TimeOfDayInfluence.java
│   │   └── TaggedBlockInfluence.java
│   ├── WindEffectRegistry.java     threshold -> effect, top-N-winds selection, Dhar override
│   └── DharTracker.java            4+-winds-high persistence -> dharLevel accumulation
├── spell/
│   ├── Spell.java                  data record + Codec
│   ├── SpellEffect.java            sealed interface + dispatch codec
│   ├── SpellManager.java           SimpleJsonResourceReloadListener<Spell>
│   └── CastingResolver.java        the one place casting-number math lives
├── player/
│   ├── PlayerMagicData.java        attachment payload record + Codec
│   └── ModAttachments.java         DeferredRegister<AttachmentType<?>>
├── rune/
│   ├── Rune.java                   data record (type, master?, effect refs) + Codec
│   ├── RuneManager.java            SimpleJsonResourceReloadListener<Rune>
│   └── ItemRunes.java              data component: List<ResourceLocation>, Form/Three/Mastery checks
├── potion/
│   ├── PotionRecipe.java           data record + Codec
│   └── PotionRecipeManager.java    SimpleJsonResourceReloadListener<PotionRecipe>
├── familiar/                       (optional milestone, see §11)
│   ├── FamiliarData.java
│   └── FamiliarTraitTables.java
└── ModMagic.java                   wires everything into the mod event bus (mirrors Skavenblight.java's
                                     style: one method per concern, called from the constructor)
```

Datapack folders to match:

```
data/skavenblight/magic/spells/<wind>/<spell_id>.json
data/skavenblight/magic/runes/<rune_id>.json
data/skavenblight/magic/potions/<potion_id>.json
data/skavenblight/magic/wind_biome/<tag_or_direct>.json     (NeoForge DataMap)
data/skavenblight/tags/blocks/wind_source/<wind>.json       (Tier-0 player control blocks)
```

Modonomicon:

```
data/skavenblight/modonomicon/books/nexus_research/categories/winds_of_magic_<wind>.json  (x8)
data/skavenblight/modonomicon/books/nexus_research/categories/dark_magic.json
data/skavenblight/modonomicon/books/nexus_research/entries/winds_of_magic_<wind>/overview.json (x8, +more later)
data/skavenblight/modonomicon/books/nexus_research/entries/dark_magic/overview.json
```

## 13. Suggested build order

1. `Wind` enum + `ChunkWindState`/`WindGridManager` ticking with just the time-of-day influence
   (cheapest, immediately visible/testable via a debug command — add one to
   `command/debug/`, e.g. `DebugWindCommands`, mirroring the existing debug command package).
2. Modonomicon category/entry scaffolding for the 8 winds (mostly done by this spec — see the
   files added alongside it) so the book has somewhere for content to land as it's built.
3. `Spell`/`SpellManager`/`CastingResolver` with 2–3 real spells per wind (pick the simplest
   mechanically — heal, damage, buff) to prove the data pipeline before writing 60 spells.
4. `PlayerMagicData` attachment + a minimal casting UI/keybind.
5. Biome + tagged-block influences (Tier 0/1 player control).
6. Power stones (Tier 3) + one magic machine (Chamon alchemy lab is explicitly called out in the
   notes — good first machine since potions depend on it anyway).
7. Potions + brewing.
8. Runes.
9. Dark Magic + Dhar tracking.
10. Familiars (optional).
