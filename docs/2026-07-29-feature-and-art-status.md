# Feature & Art Status — 2026-07-29

A lot of systems landed quickly across the last few weeks (siege pathing, the Skaven Incursion director, Realms of Sorcery magic, warp flux machines). This is a snapshot of what's actually working, what's still scaffolding, and — the main ask — exactly which assets need a human artist next. It was compiled by auditing the registries against `src/main/resources/assets/skavenblight/**`, reading the subsystem source, and cross-referencing the existing design docs under `docs/superpowers/` and `docs/pathing/`.

Treat this as a snapshot, not a spec — re-verify against current code before acting on anything here after significant further work.

## TL;DR — art backlog, ranked

| Priority | Asset | Why |
|---|---|---|
| 1 | Nexus GUI texture (`textures/gui/nexus.png`) | Referenced by `NexusScreen.java`, doesn't exist. Every player who opens the Nexus UI sees a missing-texture checkerboard today. |
| 2 | `alchemical_laboratory` block — full texture/model/item art | Zero assets exist. Renders as the checkerboard in world and inventory. |
| 3 | WolfCat entity — model/texture/renderer | Currently a reskinned vanilla Cat (`extends Cat`, uses vanilla `CatRenderer`); code literally comments it as "Temporary Cat-based incursion mob." Has real AI, no Skaven-appropriate look. |
| 4 | Realms of Sorcery: spell icons/particles, wand/reagent/power-stone item textures | 35 working spells across 8 Winds exist as code+JSON, but there is zero player-facing art anywhere in this system — no particles, no items to hold, no icons. |
| 5 | Incursion atmosphere SFX (bell, whispers, chittering, red-eyes glow, green-moon tint) | Referenced by ID in `TutorialScheme` but the classes are empty stubs and no `.ogg`/particle assets exist at all. |
| 6 | Modonomicon illustrations (optional) | Book is currently 100% text pages with vanilla-item icons — no custom illustrations exist for any Wind, Power Stones, Witchsight, etc. Not broken, just plain. |

Lower priority / cleanup, not urgent: `skavenblight_book` and `warp_lightning_coil` are missing lang entries (untranslated names, not missing art); `basic_warp_flux_storage` and `spawn_tunnel_small` have lang keys that don't match their registry names; `warp_lightning_coil_dummy` gets an obtainable `BlockItem` with no texture at all and probably shouldn't have a `BlockItem`; leftover template lang entries (`example_block`, `example_item`, "Example Mod Tab") should just be deleted.

---

## 1. Core blocks, items, machines

**State: mature.** The ore/decorative Warpstone block family (ore, deepslate ore, block, stairs/slab/button/pressure-plate/fence/fence-gate/wall/door/trapdoor), the Warpstone armor set (with trim support), the Warp Flux Furnace (including its full GUI — `warp_flux_furnace_gui.png`, energy bar and lit-progress sprites), the Warp Flux Conduit multipart, the Piston Spike Trap, and the Warpstone Nexus block (custom Blockbench multipart model with 10 sub-textures) all have complete texture/model/blockstate/lang coverage. Most blockstates/models are datagen-generated rather than hand-authored, which is why they don't appear as static JSON in source — that's expected, not a gap.

**Gaps found:**
- **`alchemical_laboratory`** — registered block (`AlchemicalLaboratoryBlock`) with a lang entry describing "Poor/Good/Best quality" labs, but **no texture, no model, no blockstate, no GUI/Menu/Screen at all**. This reads as a stub for a feature that was named and lang-stubbed but never actually built. Needs full art plus datagen wiring before it can go in front of a player.
- **Nexus GUI** — `NexusScreen.java` hardcodes and blits `skavenblight:textures/gui/nexus.png`, which does not exist anywhere in resources. This is the highest-priority single fix since the Nexus is presumably a central player-facing feature.
- **`warp_lightning_coil_dummy`** gets a normal obtainable `BlockItem` via `registerBlock()` but has zero texture/model/lang — if a player ever picks it (creative search, pick-block), it shows the checkerboard. Should probably be registered without a `BlockItem`, same pattern as the debug anchor blocks.
- Two **registry-name/lang-key mismatches** cause untranslated in-game names: `basic_warp_flux_storage` (lang defines `warp_flux_storage` instead) and `spawn_tunnel_small` (lang only defines the `_active` variant).
- Missing lang entries (translation-only, not art): `warp_lightning_coil`, `skavenblight_book` (the player-facing lore book — worth prioritizing since it's visible constantly), `debug_flow_field_reader` (dev tool, low priority).
- Dev-only debug blocks/items (`debug_front_anchor`, `debug_source_group_anchor`, `debug_flow_field_reader`) are intentionally invisible or have small purpose-made icons — confirmed not player-facing, no action needed.
- Cleanup: leftover mod-template lang entries (`itemGroup.skavenblight` → "Example Mod Tab", `example_block`, `example_item`) don't map to anything real anymore and should be deleted.

## 2. Mob entities

| Entity | Behavior | Art |
|---|---|---|
| Clanrat | Full custom AI (siege goals, see §4) | **Complete** — bespoke GeckoLib model, texture, and animation set |
| RatWolf (`rat_wolf`) | Standard wolf-derived behavior | **Complete** — custom renderer + custom texture (class is internally named `WolfRat` vs. registry `rat_wolf`/renderer `RatWolfRenderer` — just inconsistent naming, not a functional bug) |
| WolfCat (`wolf_cat`) | Real, wired-in AI (`WolfCatAttackBlockGoal`, `BreakNexusObstructionGoal`, `MoveToActiveNexusGoal`) — genuinely functions as an incursion mob | **Placeholder.** `extends Cat`, renders with vanilla `CatRenderer`, no mod texture exists. Source comment literally flags it as temporary. **This is the clearest "behavior done, art not started" case in the codebase.** |
| Warp Lightning Bolt | VFX-only entity | N/A by design — procedurally drawn, no texture expected |

## 3. Skaven Incursion event/director system

**State: bifurcated.** The planning/runtime core is large and functionally dense — director pacing, tempo brackets, overlap/pressure gating, a weighted scenario picker, leadership hierarchy (PACK→CLAW→FANG→VERMINTIDE) for tracing spawned mobs back to their source, NBT persistence, chunk-ticket management, and world-reconciliation on restore. Two scenarios are live end-to-end (`WolfRatAssault`, `CatDogRaid`), with `TutorialCampAttack` also registered.

**Known implementation gap (not art, but worth flagging):** `TutorialScheme` references scenario IDs (`tutorial_raid_late`, `tutorial_assault_early`, `tutorial_finale`) that don't exist in `ScenarioRegistry` — progression past the early tier will silently fail to start anything.

**Art/audio gap:** the "atmosphere" layer is a pure skeleton. `DistantBell`, `GreenMoon`, `RedEyesAtNight`, `SkavenChittering`, and `SkavenWhispers` are all empty `{}` classes, not even implementing `SkavenEffect`; `EffectRegistry` has their registration calls commented out. `TutorialScheme` already references `"red_eyes_at_night"` and `"distant_bell"` by string ID, confirming intent. None of the needed sounds exist — `sounds.json` only defines `nexus_speed` and `game_over`, and the sounds folder only has those two `.ogg` files. **Needed: a bell toll SFX, ambient chittering/whisper loops, a red-eye glow particle/texture, and a green-moon sky tint asset**, plus someone to wire the five effect classes to them.

No automated test coverage exists for this subsystem at all (director/scheme/leadership).

## 4. Siege pathing AI (region-based flow-field)

**State: mature and heavily hardened.** 50+ commits of fixes/tests/docs in the last month, a dedicated hardening pass, and a findings doc (`docs/pathing/region-pathing-hardening-findings.md`) that already tracks what's left. Two known issues are parked rather than fixed: the incremental-recompute performance premise is structurally unreachable for any connector-bearing region (falls back to full rebuild essentially every dirty tick), and a duplicate-`Region` bug at connector-merge completion is confirmed reproducible via GameTest. A related concurrency-only flaky test is also documented. Coverage is 19 GameTest methods across two files — no JUnit unit tests exist for this system, so the flaky test has no cheaper isolation layer.

**Art: none needed.** `SiegeActionAnimator` is a small functional helper (swing timing + vanilla block-crack overlay), not an animation system — it has no coupling to GeckoLib or custom models. All siege-constructed structures (stairs, cobblestone, ladders) place vanilla blocks only; no new block types are required.

## 5. Realms of Sorcery (Winds of Magic) + Modonomicon

**State: solid backend, no front end.** The spell effect system (`SpellEffect`, a sealed interface with 11 real effect types: damage, mob effects, heals, lightning, projectiles, sweeping cones, etc.) is genuine working gameplay code, not stubs. 35 spell definitions exist as data across all 8 Winds (Hysh, Aqshy, Ghyran, Shyish, Chamon, Ghur, Azyr, Ulgu), loaded through a proper `SimpleJsonResourceReloadListener`. Per-chunk wind drift (biome/time-of-day influenced) ticks server-side.

**The catch: there is no way for a player to actually cast a spell.** The only entry point is `/skavendebug magic cast`. There's no UI, keybind, wand, or item-triggered casting anywhere in the code.

**Art gap — this is a genuinely empty category, not partially done:**
- No spell particle types are registered at all.
- No wand, staff, reagent, or power-stone items exist in `ModItems` — the 8 canonical Power Stones described in the lore/design docs have zero corresponding items or textures.
- No grimoire/scroll textures.
- The Modonomicon book (`nexus_research`, categories for all 8 Winds + Dark Magic + Nexus lore) is entirely `modonomicon:text` pages using vanilla-item icons (e.g. `minecraft:blaze_powder`) — there are no custom illustrations anywhere, and several pages (Witchsight, Power Stones, Tzeentch's Curse, Grimoires & Scribing) describe systems that don't exist in code yet, i.e. they're intentionally forward-looking lore.

Potion brewing (`AlchemicalPotion`, `AlchemicalBrewingResolver`) is a real "skeleton" in the literal sense: the classes exist with working logic shapes but are wired into nothing — no reload listener, no recipes, no block, no GUI, no registration anywhere. Matches the `alchemical_laboratory` block gap in §1 — these are almost certainly the same unbuilt feature.

---

## How this was produced

Five parallel audits (registries vs. assets, Skaven Incursion, Realms of Sorcery, siege pathing, mob entities) cross-referenced `ModBlocks`/`ModItems`/`ModEntities`/`ModBlockEntities` against `src/main/resources/assets/skavenblight/**`, read the relevant subsystem source, and checked existing design docs under `docs/superpowers/` and `docs/pathing/` against actual implementation. Datagen-generated blockstates/models (via `ModBlockStateProvider`/`ModItemModelProvider`) are treated as complete even though no static JSON exists in source for them, since that's expected for this codebase's pattern.
