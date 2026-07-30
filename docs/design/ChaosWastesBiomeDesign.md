# Biome Design Spec: The Chaos Wastes

This design document outlines the adaptation of the **Chaos Wastes** from Warhammer Fantasy (specifically WFRP 2e Lore and mechanics) into a custom, deeply immersive, and dangerous modded biome for our Warhammer-themed NeoForge mod for Minecraft 1.21.1.

The Chaos Wastes are not merely a barren landscape of ice and stone; they represent the shifting boundary between physical reality and the Realm of Chaos. This document outlines how to translate the geography, bizarre out-of-place terrain, ruins, structures, weather phenomena (Change Storms), Monoliths, encounters, and the safe haven of "The Last Hope" into modular, data-driven Minecraft worldgen and gameplay mechanics.

---

## 1. Biome Technical Profile

- **Biome ID**: `skavenblight:chaos_wastes`
- **Primary Classification**: Cold/Frigid Steppes & Barren Rock.
- **Temperature**: `-0.4` (Ensures that precipitation falls as snow, and water freezes into ice naturally).
- **Downfall**: `0.8` (High humidity, generating regular blizzard conditions and Change Storms).
- **Music & Ambient Sounds**: 
  - *Music*: Low, eerie, dissonant orchestral drone mixed with whispers.
  - *Ambient Sounds*: Frigid, howling winds (`skavenblight:ambient.cold_wind`), distant metal clanking, faint demonic growling.
- **Visuals & Sky/Fog Shifting**:
  - *Sky Color*: Pale, sickly green-grey (`#5D6E5D`) or deep, unnatural violet (`#3E2A4F`).
  - *Fog Color*: Ashy grey (`#4E4E4E`), shifting to sickly neon colors during high-wind surges.
  - *Water Color*: Sluggish dark purple-brown (`#3B2230`).
  - *Water Fog Color*: Murky black (`#160C12`).
  - *Foliage & Grass Color*: Diseased, withered pale yellow-brown (`#7A704A`).

### 1.1 Custom Ground & Flora Blocks
- **Blasted Turf** (`skavenblight:blasted_turf`): A corrupted grass block that slowly spreads over normal dirt in high-Dhar chunks. Shovel-flattening it creates "Desecrated Path".
- **Permafrost Mud** (`skavenblight:permafrost_mud`): Slippery, freezing mud that slows players down (similar to soul sand but with ice friction mechanics).
- **Sickness Weed** (`skavenblight:sickness_weed`): Spiky, mutated undergrowth. Walking through it inflicts slow-acting poison and cuts armor durability.
- **Withered Thorns** (`skavenblight:withered_thorns`): Block-type hazard that damages entities and can be harvested for potion ingredients.
- **Mutated Pine Log/Leaves**: Wood with a violet-tinted bark that drips dark sap. Placing its leaves near water blocks causes the water to slowly corrupt.

---

## 2. Winds of Magic & Dhar Integration

Due to its northern location and proximity to the Chaos Gate, the Chaos Wastes are saturated with ambient magical energies.

1. **Elevated Baselines**: In the `WindGridManager` (ticked server-side), the default baseline value for all 8 winds of magic is set exceptionally high (e.g., `400.0` to `700.0` out of `1000.0` scale).
2. **True Dhar Coagulation**: Because multiple winds are constantly high in the same chunks, the Chaos Wastes naturally trigger the "four or more winds" threshold. This causes a rapid, continuous rise in `dharLevel` (accumulating over time).
3. **Perverse Fecundity Aura**: High Dhar levels in the chunk apply a passive multiplier to mob spawns. Hostile mobs spawn at double the normal vanilla cap, and any breeding/spawning of animals/villagers has a 50% chance to mutate the offspring into "Mutated Beastmen" or "Chaos Mutants" instead.
4. **Casting Boost & Risk**: Spellcasters within the Chaos Wastes receive a passive **+15 Catalytic Bonus** to all casting rolls, but any double rolled on a d100 casting check automatically triggers a **Major or Catastrophic Miscast** (Curse of Tzeentch) regardless of the spell's tier, due to the thinness of the reality barrier.

---

## 3. Out-of-Place Terrain (Dynamic Patch Generation)

The Chaos Wastes defy geographical logic. Small patches of warm deserts, sweltering jungles, or diseased farmlands sit incongruously amidst the freezing steppes. 

In Minecraft, this is implemented as **"Out-of-Place Micro-Biomes"** or **"Configured Patch Features"** that override block states, vegetation, and local temperatures within circular/oval regions of 60 to 120 blocks in diameter.

| Roll (1d100) | Out-of-Place Terrain | Technical Implementation & Block Palette |
|---|---|---|
| **01 - 10** | **Forest, Deciduous** | Thick grove of dark oak and birch trees; temperature set to `0.6` (mild autumn). Spawns mutated panthers, wolves, and bears. |
| **11 - 20** | **Swamp, Warm** | Silt-filled brackish water pools, weeping willow trees covered in moss; temperature set to `0.8` (hot/humid). Spawns slime, venomous insects, and swamp mutants. |
| **21 - 30** | **Desert, Warm** | Shifting desert sand and red sand dunes, dead bushes, cacti; temperature set to `1.8` (scorching). Spawns desert dhar-beetles. |
| **31 - 40** | **Desert, Cold** | Coarse white sand covered in packed ice; temperature set to `-0.8` (extreme frost). Freezes exposed players rapidly. |
| **41 - 50** | **Jungle** | Dense jungle wood, vines, cocoa beans, thick undergrowth; temperature set to `1.2` (tropical swelter). Spawns aggressive snakes and mutated apes. |
| **51 - 60** | **Badlands, Warm** | Wind-carved red terracotta spires and gold-rich sand layers; temperature set to `1.0`. High metallic wind baseline (Chamon). |
| **61 - 70** | **Badlands, Frozen** | Same as above but completely coated in packed/blue ice; temperature set to `-0.6`. |
| **71 - 80** | **Forest, Evergreen** | Imposing stand of giant redwood and spruce trees; temperature set to `-0.2`. Spawns giant wolverines and mutated lynxes. |
| **81 - 90** | **Orchard** | Rows of well-spaced Apple Trees with bright red apples. However, breaking/eating an apple reveals it is rotten to the core (inflicts Nausea and Poison). |
| **91 - 100** | **Farmlands** | Grid of neat tilled soil, hedges, and fences. Features withered wheat and rotten potatoes. Harvesting them yields diseased crop items that provide zero nourishment and cause sickness. |

---

## 4. Custom Weather: Change Storms

Standard rain and blizzards are replaced by **Change Storms** in the Chaos Wastes. These are handled via a custom weather tick handler triggered by high chunk `dharLevel`. When a storm begins, one of the following seven manifestations covers a 10-chunk radius for `1d10 + 5` minutes.

```java
public enum ChangeStormType {
    ACIDIC_PRECIPITATION,
    REVERSE_TEMPERATURE,
    WIND_OF_MADNESS,
    RAIN_OF_CREATURES,
    HAIL_FIRE,
    RAIN_OF_BLOOD,
    AETHYRIC_WIND
}
```

### 4.1 Weather Effects Specification

1. **Acidic Precipitation**
   - *Visual*: Dark, oily, yellowish-green rain/snow.
   - *Gameplay*: Deals 1 point of acid damage (`skavenblight:acid`) per minute to entities exposed to the sky. Rapidly degrades the durability of iron/leather armor and tools. Players must seek shelter or craft lead-lined armor.
2. **Reverse Temperature**
   - *Visual*: Unnatural heat shimmering in winter, or frost-motes in summer.
   - *Gameplay*: Suddenly flips the environmental temperature scale. Cold zones become scorching (causing heat stroke/hydration drain if wearing heavy arctic armor), and warm patches freeze solid instantly.
3. **Wind of Madness**
   - *Visual*: High, swirling grey wind particles accompanied by terrifying howling audio.
   - *Gameplay*: Exposure to the storm requires a Willpower check (`skavenblight:willpower`) every 60 seconds. Failure adds 1 Insanity Point. High Insanity causes auditory hallucinations (fake creeper hisses, phantom skeleton bows) and screen warping.
4. **Rain of Creatures**
   - *Visual*: Shower of tiny, deformed frogs, fish, and mutated pigs falling from the sky.
   - *Gameplay*: No direct damage, but witnessing it causes horror (requires a Willpower check or gain 1 Insanity Point). Ground becomes littered with dead mutated fauna that can be harvested for weird potion catalysts.
5. **Hail of Fire**
   - *Visual*: Blazing fireballs bombarding the landscape.
   - *Gameplay*: Deals flat physical and fire damage to anyone without overhead protection. Ignites flammable blocks (wood, wool, leaves) and starts forest fires.
6. **Rain of Blood**
   - *Visual*: Sickly dark crimson rain pouring from blood-red clouds.
   - *Gameplay*: Exposing oneself to this blood-rain risks contracting "The Bloody Flux" (stamina and hunger bar drain) or "The Green Pox" (gradual health wither).
7. **Aethyric Wind**
   - *Visual*: Radiant, multi-colored neon aurora-like gales sweeping across the land.
   - *Gameplay*: The raw fabric of the Aethyr tears open. Every 2 minutes, all entities (spellcaster or not) must succeed on a Willpower check or suffer a random **Minor Chaos Manifestation** (e.g., sudden teleportation, temporary levitation, block transmutation, or sprouting random cosmetic horns/tentacles).

---

## 5. Custom Structures & Landmarks

The Chaos Wastes generate several highly atmospheric structures, providing unique gameplay, dangers, and rare rewards.

```
data/skavenblight/worldgen/structure/
├── chaos_monolith_khorne.json
├── chaos_monolith_nurgle.json
├── chaos_monolith_slaanesh.json
├── chaos_monolith_tzeentch.json
├── forgotten_battlefield.json
├── hill_of_bones.json
└── the_last_hope_inn.json
```

### 5.1 Chaos Monoliths
Colossal, rough-hewn stone pillars marking the spot where a Chaos Champion ascended to Daemonhood. 

#### Core Mechanics:
- **Toughness and HP**: Monoliths are highly durable multiblock structures. To prevent simple griefing, they are made of indestructible-like blocks (`skavenblight:desecrated_monolith_stone`) that can only be chipped away using heavy picks/hammers. They possess **500 Wounds** (effectively 1000 HP).
- **Daemon Summoning**: As the monolith takes damage, it defends itself. For every **50 Wounds** lost, it spawns a corresponding Lesser Daemon of its alignment directly from its base. These daemons attack the defiler and fight to the death.
- **Rune Reading**: Reading the blasphemous runes carved on the monolith requires a Hard (`-20%`) Willpower test. Failure inflicts `1d5` Insanity Points. Success unlocks high-level Chaos magical spells in the player's Modonomicon.

#### Alignment Variants:
- **Monolith to the Great Beast (Undivided)**
  - *Appearance*: Dark rough granite, carved with the 8-pointed star of Chaos.
  - *Effect*: Amplifies local winds. Any spellcaster within 100 blocks adds `+10` to their casting roll.
- **Monolith to Khorne (The Blood God)**
  - *Appearance*: Black obsidian and brass plates, surrounded by massive piles of human/beast skulls. Fountains of actual blood seep from the crevices.
  - *Effect*: **Siphons Magic**. All spells cast within 100 blocks fail automatically and trigger a Catastrophic Chaos Manifestation. Enchanted armor and weapons operate normally.
- **Monolith to Nurgle (The Plague Lord)**
  - *Appearance*: Crumbling shale covered in toxic moss, green slime, and rotting organic matter. Swarms of flies buzz continuously.
  - *Effect*: Automatically inflicts "Green Pox" on any player staying near it for more than 3 minutes without a purification amulet. Bodies of slain monsters left at its base slowly decay into piles of rot, boosting nearby Ghyran and Dhar baseline winds.
- **Monolith to Slaanesh (The Prince of Pleasure)**
  - *Appearance*: Tumescent crystalline spires with glowing pink and purple veins of quartz. Carved with beguiling, hypnotic figures.
  - *Effect*: Hypnotic Aura. Looking directly at the monolith for too long forces a Willpower test. Failure mesmerizes the player, locking their movement keys for 10 seconds while whispering corrupting thoughts (increases insanity).
- **Monolith to Tzeentch (The Changer of Ways)**
  - *Appearance*: Shifting marble and black volcanic stone that floats slightly above the ground, sometimes dissolving into living blue fire or solid smoke.
  - *Effect*: High volatility. Spells cast here gain double potency, but miscasts are twice as likely.

---

### 5.2 Forgotten Battlefields
Sparsely generated ruins of historical battles between Norse Marauders, Beastmen herds, Greenskins, and crusade armies of the Empire.

- **Corpse Preservation**: Due to the extreme freezing temperature and magical energies, the corpses of Beastmen, Orcs, and Mutants do not fully decay. They generate as static "Preserved Corpses" (acting as natural chest loot containers). However, opening one has a 20% chance to wake the corpse as an aggressive undead Mutant or Wight!
- **Detritus & Scrap**: Slabs of rusted armor plates and broken weapon blocks are scattered in the permafrost. They can be smelted down in a Blast Furnace to retrieve iron and brass scrap.
- **Tattered Banners**: Ancient, weathered battle standards flap defiantly in the wind. Players can collect these banners to decorate their bases or burn them to cleanse the local chunk of minor Dhar.

---

### 5.3 Hill of Bones
A rare, massive landmark commemorating the total annihilation of "Sigmar's Brave"—a crusade led 300 years ago by the zealous priest Regimius.

- **Structure**: A solid hill rising 100 feet (blocks) high, composed of "Bone Pile" blocks, "Ribcages", and "Crushed Skulls".
- **Betrayed Souls**: The hill is haunted by the spectral remnants of the abandoned crusade. Custom hostile wraiths (**Betrayed Crusaders**) hover around the hill, dealing necrotic damage and applying a "Cowardice" debuff (disables player shield blocking and sprint).
- **The Core Crypt**: Hidden deep inside the bone hill lies the ruined command carriage of Regimius. It contains tarnished holy items (e.g., *Shattered Hammer of Sigmar*, *Tattered Priestly Vestments*) which can be restored via Chamon magic or Dwarven Runesmithing.

---

### 5.4 The Last Hope Inn
The final bastion of safety located on the southern edge of the Chaos Wastes biome. It is a fortified stone inn built to survive the harsh wilderness.

- **Innkeeper Knute Alsgaard**: A custom, neutral NPC who manages the inn. He sells essential survival provisions, warmth gear, lead-lined clothing, and high-quality alchemical brewing recipes at highly inflated prices (requires gold/silver coins).
- **Strict Guard Presence**: The inn is guarded by 12 elite NPC warriors (Norsemen, Kossars, Imperial Soldiers).
- **The Peace Pact (Weapons Chest)**: Upon entering the outer courtyard, players must step through an inspection gate.
  - *No Weapons*: Holding any weapon or magical catalyst trigger an immediate hostile reaction from the guards unless they are unequipped and kept in the player's backpack.
  - *Cleanse Check*: Any player with visible mutations or carrying a high amount of Dark Magic (Dhar) is denied entry, and the guards will open fire if they attempt to force their way in.
- **Defensive Siege Event**: Occasionally, the inn comes under attack by a Beastman horde or Marauder band. A world event triggers: all players currently inside the inn must join the NPC guards to defend the structure. Successful defense rewards players with massive discounts on Knute's goods and rare dwarven blueprints.

---

## 6. Spawn Catalog (Custom Encounters)

The Chaos Wastes spawn dedicated hostile entities that embody the corruptive strength and resilience of the region.

| Entity ID | Type / Tier | AI & Special Behaviors |
|---|---|---|
| `skavenblight:chaos_marauder` | Common | Aggressive humanoids armed with iron axes and furs. They patrol in packs of 2d10, occasionally riding mutated wolves. |
| `skavenblight:beastman_gor` | Common | Agile beastmen that leap at the player and apply bleeding effects. Boosted by Ghyran wind level. |
| `skavenblight:chaos_mutant` | Uncommon | Grotesque aberrations with randomized physical traits (extra limbs, acid spit, iron skin). |
| `skavenblight:chaos_warrior` | Elite | Heavily armored knights with massive shields. Highly resistant to physical damage; requires magical armor-piercing or Chamon spells to defeat. |
| `skavenblight:chaos_sorcerer` | Elite | Casts offensive wind spells (Aqshy fireballs, Shyish wither bolts). Triggers minor miscasts when defeated. |
| `skavenblight:least_daemon` | Common | Small, crawling imps of pure magic energy. They explode into elemental particles on death. |
| `skavenblight:lesser_daemon` | Uncommon | Specific daemons matching local monolith alignments (e.g., Bloodletters, Plaguebearers, Daemonettes, Pink Horrors). |
| `skavenblight:wight` | Uncommon | Frostbitten skeletal warriors found in Forgotten Battlefields. Armed with cursed copper swords. |

---

## 7. Adventure Hooks & Integration

To encourage players to brave the extreme climate and dangers of the Wastes, we propose adding four advancement-gated quest chains inside the Modonomicon.

### A. Sacrifice in the Snow
- **Goal**: Track down a missing merchant caravan led by Klaus Reinfrank.
- **Lore**: Klaus has been corrupted by Tzeentch and intends to sacrifice his crew at a hidden obelisk.
- **Gameplay**: Locate a ruined caravan in the steppes, follow a blood trail, and stop the ritual before a Greater Daemon is summoned.

### B. City of Deceit
- **Goal**: Discover the legendary sunken golden city of Gultberg.
- **Lore**: High-value gold and magical artifacts are hidden beneath an illusion cast by a Chaos Sorcerer.
- **Gameplay**: Use a **Chamon Prismatic Lens** or hold a specific wind staff to pierce the illusion and reveal the crumbling, trap-filled ruins of Gultberg underneath the snow dunes.

### C. The Hidden Fortress
- **Goal**: Rescue the captured companions of the insane nobleman William Neuner from a fortress of bone, brass, and iron.
- **Gameplay**: A high-difficulty dungeon raid in the heart of the Wastes, populated by elite Beastmen and Chaos Warriors, yielding high-tier Dwarven smithing runes.

### D. Death Comes on Icy Wings
- **Goal**: Terminate a Sorceress of Nurgle who has defiled a giant prehistoric frozen beast.
- **Gameplay**: Eradicate mutated flocks of carrion crows spreading a global pandemic, track down the Sorceress at her permafrost dig site, and destroy the thawed carcass before the disease reaches the Empire.
