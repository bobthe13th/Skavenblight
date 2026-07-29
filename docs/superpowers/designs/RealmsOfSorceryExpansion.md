# Winds of Magic — Phase 2: Realms of Sorcery Expansion Blueprint

This document details the architectural and mechanical design for the Phase 2 expansion of the **Winds of Magic** system. Drawn directly from the classic *Warhammer Fantasy Roleplay (WFRP) - Realms of Sorcery*, these systems build on the foundation of Phase 1 (datapack-driven spells, `CastingResolver`, `WindGridManager`, and chunk wind states) to deliver an immersive, dangerous, and deeply lore-accurate magical sandbox.

---

## 1. The Curse of Tzeentch (Arcane Miscasts)

### Lore Concept
Casting spells in Warhammer is never completely safe. The Aethyr is a roiling ocean of chaotic energy; drawing too much of it, or losing focus for a microsecond, allows the Chaos Gods—specifically Tzeentch, the Changer of Ways—to twist reality around the caster. This is represented by "The Curse of Tzeentch" (Miscasts).

### Mathematical Mechanics & Triggering
In Phase 1, `CastingResolver.resolve(...)` evaluates success using:
$$\text{success} = \text{aptitude} + \text{windLevelBonus} - \text{castingNumber} \ge \text{roll}$$
where the $\text{roll}$ is a $d100$.

To adapt WFRP’s miscast rules, we introduce the concept of **"Aethyrical Resonance" (Matching Digits / Doubles)**.
* **The Trigger**: If the $d100$ roll results in matching digits (e.g., $11, 22, 33, 44, 55, 66, 77, 88, 99, 100$), the fabric of reality buckles.
* **Severity Scaling**:
  * **Minor Miscast**: Triggered if the roll is a double AND the spell is a Tier 0/1 spell.
  * **Major Miscast**: Triggered if the roll is a double AND the spell is a Tier 2 spell, OR if a double is rolled and the casting failed by a margin of $\ge 30$.
  * **Catastrophic Miscast**: Triggered if the roll is a triple (if multiple dice are rolled, or a natural $100$ on a double) OR if the spell is Tier 3 and the roll is a double.

### Datapack-Driven Miscast Registry
Miscasts are fully data-driven. We define a custom datapack registry for miscast tables (`skavenblight:miscast_tables` loaded via simple JSON listeners).

#### JSON Schema (`data/skavenblight/magic/miscast_tables/minor.json`):
```json
{
  "severity": "minor",
  "effects": [
    {
      "weight": 30,
      "type": "aethyr_whispers",
      "description": "Unearthly whispers echo in the caster's mind. Applies Dark Whisper effect.",
      "action": {
        "type": "apply_mob_effect",
        "effect": "minecraft:nausea",
        "duration_ticks": 200,
        "amplifier": 0
      }
    },
    {
      "weight": 20,
      "type": "spilled_wind",
      "description": "The wind drains violently from the chunk, causing a localized magical vacuum.",
      "action": {
        "type": "drain_wind",
        "radius_chunks": 1,
        "fraction": 0.8
      }
    },
    {
      "weight": 10,
      "type": "witch_fire",
      "description": "Flickering pale green flames dance around the caster, igniting nearby flammable blocks.",
      "action": {
        "type": "ignite_surroundings",
        "radius": 3
      }
    }
  ]
}
```

### Technical Integration Blueprint (Java)
We hook into the casting sequence before resolving spell effects:

```java
public final class CastingResolver {
    // ... Existing resolve method ...

    public static MiscastSeverity checkMiscast(int roll, int tier, boolean success, int margin) {
        boolean isDouble = (roll % 11 == 0) || (roll == 100);
        if (!isDouble) {
            return MiscastSeverity.NONE;
        }

        if (roll == 100 || tier == 3) {
            return MiscastSeverity.CATASTROPHIC;
        }
        if (tier == 2 || (!success && margin >= 30)) {
            return MiscastSeverity.MAJOR;
        }
        return MiscastSeverity.MINOR;
    }
}
```

---

## 2. Witchsight (The Sight of the Aethyr)

### Lore Concept
Magisters possess the "Third Eye," allowing them to perceive the ambient flows of the Aethyr. To an untrained peasant, a magical leyline looks like an empty field. To a wizard with Witchsight, it is a glowing, turbulent river of color, reflecting the dominant Wind.

### Visual Architecture & Rendering
When Witchsight is active (triggered by wearing a specialized **Witchsight Goggles** helmet, drinking an **Aethyr Elixir**, or holding a **Chamon Prismatic Lens**), the client renders the ambient Winds of Magic:

1. **Ambient Particle Shimmers**:
   * Each chunk emits soft, swirling particles of the dominant wind's color (`Wind.getColor()`).
   * **Density Scaling**: Particle frequency scales with the local `ChunkWindState.getCurrent(wind)` level.
2. **Atmospheric Sky Glow & Shaders**:
   * In areas of extremely high wind (e.g., wind level $> 200$), a subtle post-processing shader is applied to distort the screen slightly (heat-haze for Aqshy, celestial star trails for Azyr, sluggish black tar-like vignettes for Dhar).

### Network Synchronization Blueprint
Since winds are ticked server-side in `WindGridManager`, we need efficient replication of the local grid to clients with Witchsight.

```java
public record ClientboundChunkWindDataPacket(
    long chunkPos,
    float[] currents,
    float dharLevel
) implements CustomPacketPayload {
    public static final Type<ClientboundChunkWindDataPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath("skavenblight", "chunk_wind_data"));

    // Custom StreamCodec for fast network serialization
}
```

On the client side, we register a tick handler that requests wind updates for nearby loaded chunks *only* if the player has Witchsight active, keeping network traffic minimal.

---

## 3. Power Stones (Arcane Batteries & Portable Anchors)

### Lore Concept
Only master wizards can craft Power Stones—dense crystal matrixes attuned to a single wind. They act as magical sponges, soaking up ambient energy to release it in times of need.

### The 8 Canonical Power Stones:
* **Hysh**: Lumen Stones (Glows with stored daylight)
* **Azyr**: True Sapphires (Flickers with miniature lightning)
* **Chamon**: Goldstone (Extremely dense, metallic sheen)
* **Ghyran**: Vitaellum (Pulses like a heartbeat, moss-covered)
* **Aqshy**: Fire Rubies (Constantly hot to the touch)
* **Ghur**: Ghost Amber (Amorphous, smells of pine and musk)
* **Ulgu**: Crystal Mist (Appears semi-translucent, shifts out of focus)
* **Shyish**: Endstones (Saps warmth from the air around it)

### Dual-State Mechanics
Each Power Stone has a dynamic charge capability stored as custom **Data Components** (`neo_forge` capability) on the item stack.

```json
{
  "item": "skavenblight:fire_ruby",
  "components": {
    "skavenblight:wind_charge": {
      "wind": "aqshy",
      "stored_charge": 120.0,
      "max_charge": 500.0
    }
  }
}
```

#### 1. Environmental Absorption & Leaking (Passive):
* **When in High Wind areas**: The stone absorbs local wind. It saps $0.5$ units/sec from the chunk's current level and adds it to its own internal buffer.
* **When in Low Wind areas**: The stone slowly leaks its charge back into the chunk baseline, acting as a portable wind anchor that players can use to artificially elevate baselines for farming/rituals.

#### 2. Casting Catalyst (Active):
When holding or carrying a charged Power Stone in the off-hand while casting a spell of the matching wind:
* The player can consume up to $50$ charge from the stone.
* This consumed charge is added directly to their casting check as a **Catalytic Bonus** (+1 to +25 bonus to the casting check), or used to bypass chunk wind depletion entirely!

---

## 4. Grimoires & Scroll Scribing

### Lore Concept
Spells are not naturally retained in the human mind—they must be meticulously cataloged in massive, iron-bound Grimoires or prepared on single-use Scrolls. Scribing is the art of translating chaotic flows into perfect ink geometry.

### Scribing Desk (New Utility Block)
Players construct a Scribing Desk to transcribe known spells from their `PlayerMagicData` onto tangible items:

1. **Requirements**:
   * **Base Medium**: Parchment or Blank Scroll.
   * **Ink Catalyst**: Attuned Pigments (e.g., Goldstone dust for Chamon, Ember ash for Aqshy).
   * **Casting Skill Check**: Scribing requires a d100 check against the scribe's aptitude. A failure ruins the parchment; a miscast triggers a localized explosion of the scribed wind.
2. **Scrolls**:
   * Single-use items containing a bound spell.
   * Casting from a scroll bypassed the player's spell knowledge checks but still requires a wind level check.
3. **Grimoires**:
   * Multi-use books containing a collection of spells for a specific Wind.
   * Holding a Wind Grimoire increases the player's aptitude by $+10$ for that specific wind but increases miscast severity by one tier if a double is rolled (due to the dense concentration of raw magic).

---

## 5. Environmental Wind Catalysts

To make the ambient wind system truly dynamic, chunk baselines should respond organically to block compositions and biomes. We propose two highly configurable `WindBaselineInfluence` extensions.

### A. Block-Level Baselines (`BlockBaselineInfluence`)
Certain blocks radiate high elemental energies, shifting the local baseline of the wind they attune to.

* **Aqshy (Fire)**: Boosted by `minecraft:lava`, `minecraft:magma_block`, `minecraft:fire`, and `minecraft:blast_furnace`.
* **Ghyran (Life)**: Boosted by `minecraft:oak_leaves`, `minecraft:moss_block`, and fully grown crops.
* **Shyish (Death)**: Boosted by `minecraft:soul_sand`, `minecraft:spawner`, and grave blocks.
* **Chamon (Metal)**: Boosted by iron, gold, and copper ores or raw metal blocks.

### B. Biome-Level Baselines (`BiomeBaselineInfluence`)
A datapack-driven biome map defining wind baseline multipliers.

#### JSON Configuration Schema (`data/skavenblight/magic/wind_influence/biomes.json`):
```json
{
  "influences": [
    {
      "biome_tag": "minecraft:is_ocean",
      "wind_modifiers": {
        "ghyran": 40.0,
        "azyr": 20.0,
        "aqshy": -50.0
      }
    },
    {
      "biome_tag": "minecraft:is_hill",
      "wind_modifiers": {
        "azyr": 80.0,
        "ghur": 30.0
      }
    },
    {
      "biome_tag": "minecraft:is_nether",
      "wind_modifiers": {
        "aqshy": 250.0,
        "shyish": 100.0,
        "hysh": -150.0
      }
    }
  ]
}
```

These baseline modifiers accumulate during the `WindGridManager.tick(...)` phase, providing an organic handoff between different zones in the Minecraft world.

---

## Phase 2 Implementation Timeline

| Task | Core Class / Path | Description |
|---|---|---|
| **2.1: Miscast System** | `CastingResolver.java`, `MiscastManager.java` | Implement the matching-digits check and load datapack-driven miscast tables. |
| **2.2: Witchsight Rendering** | `WitchsightRenderer.java` | Wire render events to spawn custom colored wind particles based on sync'd wind data. |
| **2.3: Power Stone Items** | `PowerStoneItem.java`, `ModItems.java` | Implement the 8 sub-types of stones with active/passive charge logic. |
| **2.4: Environmental Influences** | `BlockBaselineInfluence.java`, `BiomeBaselineInfluence.java` | Add block scanning and biome-tag multiplier parsing to the grid tick loop. |
