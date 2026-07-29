# Warp Flux Network: Wind-Infused Defensive & Utility Machines
## Comprehensive Technical Design Specification

This document outlines the detailed architecture, properties, and technical implementations for player-constructed, wind-infused machines powered by the **Warp Flux** network in the *Skavenblight* NeoForge mod.

---

## 1. General Architectural Standards

All machines detailed below are categorized under the following standards:
- **Registry Names:** Structured as `skavenblight:<wind_name>_<machine_name>` (e.g., `skavenblight:chamon_magnetizer`).
- **Warp Flux Integration:** Extends `Block` and utilizes a custom `BlockEntity` that implements or references the `IWarpFluxStorage` capability.
  - **Generators** produce Warp Flux and insert it into the network.
  - **Consumers** draw Warp Flux from the network to execute in-world actions during their block entity `tick()`.
- **Tick Management:** Machines use block entity tickers (`BlockEntityTicker<T>`) to safely process logic on the server-side, with selective client-side syncing via custom network packets or block state properties.

---

## 2. Selected Machine Detailed Specifications

---

### 🟢 HYSH (The Wind of Light)

#### A. The Aetheric Flux-Still Ward (Consumer)
*   **Purpose:** Anti-spellcaster zoning and magical negation.
*   **Warp Flux Draw:** 20 Flux/tick (active).
*   **Block Properties:** Facing, Active (boolean).
*   **BlockEntity Logic:**
    *   Maintains a bounding box of radius 16 blocks.
    *   Every 20 ticks, scans for entities extending `Mob` or player/mob spellcasting states.
    *   If a spellcasting entity is found inside the area, resets its casting cooldown/active cast state, cancels spell particles, and applies `Silence` effect (prevents starting spells).
    *   Applies a passive "Searing" burning effect to undead mobs.

#### B. The Purifying Sun-Beacon (Consumer)
*   **Purpose:** Anti-tunnel spawning and area illumination.
*   **Warp Flux Draw:** 10 Flux/tick.
*   **Block Properties:** Light Emission (15 when active).
*   **BlockEntity Logic:**
    *   Projects a 24-block protection radius.
    *   Subscribes to `IncursionSpawnEvent` or tunnel placement logic; completely cancels any Skaven tunnel breaches or mob spawns in the covered zone.
    *   Deals 1.5 hearts of fire damage every 2 seconds (40 ticks) to any Skaven entities within the line of sight.

#### C. The Prismatic Ward Grid (Consumer)
*   **Purpose:** Laser boundary fencing and perimeter alert.
*   **Warp Flux Draw:** 5 Flux/tick per active laser segment.
*   **Block Properties:** Facing, Connected (boolean).
*   **BlockEntity Logic:**
    *   Checks in the facing direction up to 15 blocks for another `Prismatic Ward Grid` node.
    *   If found, creates an invisible or client-side rendered laser beam using custom particle packets.
    *   When an entity intersecting the line is a Skaven, it triggers an explosion/fire damage burst and alerts linked players with an alarm sound.

#### D. The Aetheric Silence Siphon (Consumer)
*   **Purpose:** Direct neutralizing siphon for elite Skaven magic-users.
*   **Warp Flux Draw:** 30 Flux/tick.
*   **BlockEntity Logic:**
    *   Targets the closest active spellcaster mob in a 32-block radius.
    *   Renders a Hyshian light-tether to the target on the client.
    *   Applies `Slowness II` and disables their spell ability entirely as long as tethered.

---

### ⚡ AZYR (The Wind of the Heavens)

#### A. The Azyrite Divination Astrolabe (Consumer)
*   **Purpose:** Threat forecasting and weather manipulation.
*   **Warp Flux Draw:** 15 Flux/tick (idle), 10,000 Flux burst (to force rain).
*   **BlockEntity Logic:**
    *   Allows players to open a GUI showing the exact time remaining until the next scheduled Skaven Incursion wave.
    *   Allows inputting 10,000 Flux to force a thunderstorm, which increases the generation capacity of local lightning harvesters.

#### B. The Lightning Conductor Tower (Generator & Consumer)
*   **Purpose:** Lightning interception and power conversion.
*   **Warp Flux Draw/Gen:** Zero draw (passive). Generates 15,000 Flux per lightning strike.
*   **BlockEntity Logic:**
    *   Acts as a target for vanilla lightning strikes and Skaven warp-lightning bolts within a 32-block radius.
    *   Upon interception, cancels damage to nearby blocks and adds 15,000 Flux directly to its internal storage, transferring it into the connected Warp Flux Network.

#### C. The Gale-Force Repeller (Consumer)
*   **Purpose:** Area pushback and gas cloud dispersion.
*   **Warp Flux Draw:** 25 Flux/tick.
*   **BlockEntity Logic:**
    *   Rotates a mechanical wind vane toward a configured direction.
    *   Blasts a wind cone (length 12 blocks, angle 60°).
    *   Pushes back entities (applying high horizontal knockback).
    *   Identifies and clears lingering area-effect cloud entities (like poison globe clouds or negative potion clouds) within its zone.

---

### 🪙 CHAMON (The Wind of Metal)

#### A. The Warp-Furnace Transmuter (Consumer)
*   **Purpose:** Raw material smelting and alchemical transmutation.
*   **Warp Flux Draw:** 100 Flux/tick during transmutation.
*   **BlockEntity Logic:**
    *   Contains a 2-slot inventory (input/output).
    *   When supplied with raw iron/copper and sufficient Warp Flux, transmutes them into gold or specialized Skavenblight alloys over a 200-tick smelting process.

#### B. The Alchemical Plating Anvil (Consumer)
*   **Purpose:** High-durability equipment fortification.
*   **Warp Flux Draw:** 500 Flux per operation.
*   **BlockEntity Logic:**
    *   Allows players to plate iron/netherite armor and tools with Chamonite alloys.
    *   Adds custom modifier NBT tags that grant bonus armor toughness, knockback immunity, or tool mining speed.

#### C. The Chamonite Magnetizer (Consumer)
*   **Purpose:** Automated magnetic collection and sorting.
*   **Warp Flux Draw:** 5 Flux/tick (idle), 50 Flux per item pulled.
*   **BlockEntity Logic:**
    *   Features a 9-slot internal inventory.
    *   Scans a 12-block radius for dropped `ItemEntity` objects.
    *   When an item is found, spends 50 Flux to teleport/pull the item to the magnetizer block and inserts it into its internal inventory.
    *   Configurable to automatically export items in any of the 6 facing directions into adjacent inventories (like chests or hoppers).

#### D. The Alloy-Fortification Press (Consumer)
*   **Purpose:** Reinforcing base structures against chew/dig mechanics.
*   **Warp Flux Draw:** 40 Flux/operation.
*   **BlockEntity Logic:**
    *   Fuses stone/wooden building blocks in front of it with Chamon metallics.
    *   Sets block state tags/NBT marking them as unbreakable or high-resistance to Skaven digging/tunneling behaviors.

#### E. The Shrapnel-Claymore Launcher (Consumer)
*   **Purpose:** Proximity-based direction damage.
*   **Warp Flux Draw:** 150 Flux per blast.
*   **BlockEntity Logic:**
    *   When a Skaven steps within its frontal 5-block sensor cone, triggers a directional blast of metallic shrapnel.
    *   Deals heavy pierce damage to all mobs in the cone, shredding armor points.

---

### 🧪 GHYRAN (The Wind of Life)

#### A. The Ghyranic Overgrowth Pulser (Consumer)
*   **Purpose:** Accelerated organic production.
*   **Warp Flux Draw:** 30 Flux/tick.
*   **BlockEntity Logic:**
    *   Pulses a botanical growth wave every 100 ticks in a 10-block radius.
    *   Triggers bonemeal ticks on all valid crops, saplings, and mushrooms.

#### B. The Warp-Leech Regenerator (Consumer)
*   **Purpose:** Sustained player regeneration aura.
*   **Warp Flux Draw:** 40 Flux/tick.
*   **BlockEntity Logic:**
    *   Applies high-tier Regeneration and Health Boost effects to players holding warding talismans within a 16-block radius.

#### C. The Plague-Purge Fountain (Consumer)
*   **Purpose:** Negative effect neutralization.
*   **Warp Flux Draw:** 100 Flux per effect cleared.
*   **BlockEntity Logic:**
    *   Every 20 ticks, scans a 10-block radius for players carrying negative potion/plague effects.
    *   If detected, consumes 100 Flux to completely clear the effects, simulating the action of drinking Milk.

#### D. The Briar-Wall Spreader (Consumer)
*   **Purpose:** Perimeter slowing and puncture hazards.
*   **Warp Flux Draw:** 15 Flux/tick.
*   **BlockEntity Logic:**
    *   Maintains a barrier of prickly thorn-bushes.
    *   Regenerates broken briar blocks automatically within its configured radius.

#### E. The Ghyran Soil Infuser (Consumer)
*   **Purpose:** Soil modification and plant growth.
*   **Warp Flux Draw:** 20 Flux/tick.
*   **BlockEntity Logic:**
    *   Converts regular dirt, grass, and farmland within a 9x9 area centered on the machine into **Ghyran Dirt, Ghyran Grass, and Ghyran Farmland** blocks.
    *   **Ghyran Grass:** Spreads grass 5x faster; has a 1% chance per tick to spontaneously spawn random botanical plants (including modded flowers).
    *   **Ghyran Farmland:** Yields double harvest drops.
    *   **Decay Mechanism:** If the infuser is powered down, runs out of Flux, or is broken, the modified soil blocks slowly decay back into vanilla blocks over a randomized period (100 to 500 ticks per block).

---

### 🔥 AQSHY (The Wind of Fire)

#### A. The Aqshyan Combustion Engine (Generator)
*   **Purpose:** High-yield fuel combustion generator.
*   **Generation:** Produces up to 250 Flux/tick.
*   **BlockEntity Logic:**
    *   Burns burnable solid fuels (coal, charcoal, raw warpstone) or accepts lava buckets/fluid inputs.
    *   Requires water cooling blocks touching its sides. If cooling is removed while running at peak capacity, starts an overheat counter, eventually culminating in a massive block explosion.

#### B. The Flame-Warp Thrower Turret (Consumer)
*   **Purpose:** Automated close-range perimeter defense.
*   **Warp Flux Draw:** 45 Flux/tick.
*   **BlockEntity Logic:**
    *   Rotates on its Y-axis to target the closest Skaven within 16 blocks.
    *   Fires a stream of green warpfire particles, applying heavy damage and burning ticks to all entities in its direct path.

#### C. The Thermite Bore-Drill (Consumer)
*   **Purpose:** Automatic subterranean deep-mining.
*   **Warp Flux Draw:** 150 Flux/operation.
*   **BlockEntity Logic:**
    *   **Placement Requirement:** Must be placed directly on Bedrock.
    *   Scans upward or downward along a vertical shaft.
    *   Mines solid ore blocks, smelts them instantly, and outputs the refined metal ingots directly into an attached chest/inventory.

---

### 🐗 GHUR (The Wind of Beasts)

#### A. The Ghurish Rage-Amplifier (Consumer)
*   **Purpose:** Base pet and golem fortification.
*   **Warp Flux Draw:** 35 Flux/tick.
*   **BlockEntity Logic:**
    *   Applies a custom "Bestial Frenzy" status effect to all tamed wolves, ocelots, and iron golems within 20 blocks.
    *   Increases attack damage by 50% and movement speed by 40%.

---

### 🌫️ ULGU (The Wind of Shadow)

#### A. The Shadow-Step Relay (Consumer)
*   **Purpose:** Long-distance player teleportation.
*   **Warp Flux Draw:** 1,000 Flux per teleport operation.
*   **BlockEntity Logic:**
    *   Allows linking with other Shadow-Step Relays (even those on completely separate Warp Flux networks).
    *   When linked, a player stepping onto the pad can teleport directly to the destination.
    *   **Aesthetic Surface Rendering:** Displays a blurry, scaled-down real-time render or snapshot of the destination coordinates on the top surface of the block.

#### B. The Decoy Illusion Projector (Consumer)
*   **Purpose:** Tactical aggro-redirection.
*   **Warp Flux Draw:** 15 Flux/tick.
*   **BlockEntity Logic:**
    *   Flashes an illusory projection 5 blocks in front of the machine.
    *   **Skin Copying:** When placed, captures the UUID/skin of the placing player and projects a holographic entity mimicking the player's model.
    *   Draws heavy aggro from all nearby hostile Skaven, prompting them to attack the decoy.

---

### 💀 SHYISH (The Wind of Death)

#### A. The Soulsiphon Harvester (Generator)
*   **Purpose:** Necromantic death-energy power generator.
*   **Generation:** Generates 500 Flux per mob death, 5,000 Flux per player death.
*   **BlockEntity Logic:**
    *   Monsters and players dying within a 15-block radius have their soul energy harvested.
    *   The machine transfers the produced Warp Flux immediately into the network.

#### B. The Shyishan Chrono-Dilator (Consumer)
*   **Purpose:** Localized tick-rate and time control.
*   **Warp Flux Draw:** 80 Flux/tick.
*   **BlockEntity Logic:**
    *   **Dilator Mode:** Slows down movement, projectile velocities, and explosive timers for all hostile mobs in the chunk by 50%.
    *   **Speed-up Mode:** Boosts the tick rate of other player-owned nearby utility/resource machines (like transmuters, furnaces, or drills) by calling their ticking logic twice per game tick.

#### C. The Despair-Pylon (Consumer)
*   **Purpose:** Anti-swarm debuff emitter.
*   **Warp Flux Draw:** 25 Flux/tick.
*   **BlockEntity Logic:**
    *   Emits a heavy spiritual aura in a 16-block radius.
    *   Slows the movement speed and attack speed of all invading Skaven by 35%.
