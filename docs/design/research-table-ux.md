# Research Table — UX/UI Spec

**Mode:** Operate (the player is completing a task: unlocking a spell). Scanability and native
Minecraft affordances outrank decoration; brand/flavor lives in the details (color-coding, copy
tone, iconography), not in fighting the vanilla GUI grammar.

**What this is:** a new physical block, distinct from the `nexus_research` Modonomicon book. The
book is the *encyclopedia* — lore, flavor, and a read-only index of what exists. The Research
Table is the *workbench* — where a spell actually moves from "documented" to "in your
`PlayerMagicData.knownSpells`". Nothing in Phase 1 currently writes to `knownSpells` at all; this
is the first mechanic that does.

**Native reference:** this mod already has one machine GUI
(`WarpFluxFurnaceBlock`/`BlockEntity`/`Menu`/`Screen`) — a right-click-to-open, ticking,
progress-bar-driven machine with a `ContainerData`-synced state. The Research Table follows the
identical architecture (same class shapes, same NBT/sync conventions) so it reads as a sibling,
not a one-off.

---

## 1. What the player is actually doing

1. Walk up to a placed Research Table.
2. Right-click it. A GUI opens.
3. Browse a list of spells, filterable by Wind. Each spell shows whether it's already known,
   available to research, or locked.
4. Pick a spell you don't know yet. The detail pane shows its requirements: does the local Wind
   flow strongly enough here, and does it need a catalyst item.
5. If eligible, drop a catalyst in (if one's required) and start research. A progress bar fills
   over real time, same as smelting — the player can leave and come back.
6. When it completes, the spell is added to `knownSpells`. The list updates; that spell now shows
   a "Known" badge and can be revisited read-only for its flavor text.

No dice roll on research. **Casting** (the existing `/skavendebug magic cast` pipeline, and its
eventual real item/keybind UI) keeps the WFRP-style success/failure roll — that's the
moment-to-moment skill check, and randomness there is exciting. Research is the *permanent*
unlock underneath it: gating a one-time, non-repeatable investment behind a percentage roll would
mean a solo player could grind failures against a wall with no GM to adjudicate it. Determinism
here, chance there — two different systems, two different rules, and that split is deliberate.

---

## 2. Data model this reuses (nothing new required to make this real)

| Existing piece | How the Research Table uses it |
|---|---|
| `SpellManager.getAll()` | Full catalog of researchable spells (Task 8, already built) |
| `Spell.wind()` / `.tier()` | Filter rail + tier-gate ("locked" state) |
| `Spell.componentItem(): Optional<Ingredient>` | **First real consumer of this field.** Parsed since Task 7, never used until now. If present, it's the catalyst-slot filter. None of the 16 seed spells set it today, so today every spell's catalyst slot shows "No catalyst needed" — the mechanic is real and forward-compatible for the day a spell JSON adds one. |
| `PlayerMagicData.getTier(Wind)` / `.knownSpells()` | Eligibility: `spell.tier() <= player.getTier(spell.wind())` and `!knownSpells().contains(id)` |
| `PlayerMagicData.withKnownSpell(...)` *(new, small addition)* | On research completion, adds the spell id to the set — see §6 |
| `WindGridManager.get(level).getOrCreate(pos)` / `ChunkWindState.getCurrent(Wind)` | Research requires the table's own chunk to have that spell's Wind above a threshold; pauses (doesn't reset) if it drops, mirroring the furnace's flux-pause behavior |
| `Wind.getColor()` / `.getLoreName()` | Filter-rail tab tinting and spell-list row accents |

No new attachment, no new SavedData, no new Codec. This is a UI and a BlockEntity sitting on top
of systems that already exist.

---

## 3. Layout (wireframe)

Canvas: 176×222 visible region (standard 176px width, taller than the furnace's 166px to fit the
list + detail pane above the inventory). Texture file is a 256×256 canvas with the real panel in
the top-left corner — same convention as `warp_flux_furnace_gui.png`.

```
┌────────────────────────────────────────────────────────────┐
│  RESEARCH TABLE                                        [X] │  <- title bar, 12px tall
├───┬──────────────────────┬───────────────────────────────┤
│ A │  ░░ Fireball      ▸   │  [icon]  Fireball              │
│ l │  ▓▓ Boon of Hysh  ✓   │  Aqshy · Petty                 │  <- detail header
│ l │  ░░ Lightning Bolt▸   │                                │
├───┤  ░░ Starshine     ▸   │  "A roaring ball of flame      │
│ H │  ▓▓ Curse of Rust ✓   │   bursts from the caster's     │  <- flavor text (2-3 lines,
│ y │  ░░ ...               │   outstretched hand."          │     wraps, from the spell's
│ s │  ░░ ...               │                                │     description_key)
│ h │        (scrollbar)    │  Wind of Aqshy here:           │
├───┤                       │  [▓▓▓▓▓▓▓░░░] 720 / 500 req.  │  <- Wind meter, colored per-Wind
│ A │                       │                                │
│ z │                       │  Catalyst:  [ ]  (none needed) │  <- slot, or "none needed" label
│ y │                       │                                │
│ r │                       │  [██████████████░░░░░] 68%     │  <- progress bar (idle when 0)
├───┤                       │                                │
│...│                       │        [ Begin Research ]      │  <- disabled/enabled per state
│ 8 │                       │                                │
│ta-│                       │                                │
│bs │                       │                                │
└───┴──────────────────────┴───────────────────────────────┘
│  [ player inventory 3×9 ]                                   │
│  [ hotbar 1×9 ]                                              │
└────────────────────────────────────────────────────────────┘
```

- **Left rail (20px wide):** 9 vertical tabs — "All" on top, then the 8 Winds in canonical order
  (Hysh, Azyr, Chamon, Ghyran, Aqshy, Ghur, Ulgu, Shyish), each a small colored square using that
  Wind's `getColor()`. Selected tab is inset/pressed (vanilla creative-tab convention). Filters the
  list below it.
- **Spell list (middle, ~92px wide):** a vanilla `ObjectSelectionList`-style scrollable list (same
  widget class vanilla uses for the resource-pack and world-select screens — do not hand-roll
  scrolling). Each row: small icon (16×16, using the spell's Wind color as a tint/background chip
  since spells have no dedicated icon yet), name, and a trailing glyph:
  - `▸` — available to research (not yet known, tier eligible)
  - `✓` — known (researched already)
  - `🔒` — locked (tier too low) — greyed row, unclickable for research but still viewable
  read-only for flavor.
- **Detail pane (right, ~150px):** shows the selected spell's icon, name, Wind + tier line,
  description text, the Wind-level meter, the catalyst slot (or "none needed"), the progress bar,
  and the action button. Empty state (nothing selected yet): a muted placeholder icon and the
  line *"Choose a spell to study, yes-yes!"* — small, in-voice touch matching this mod's existing
  copy (`nexus_research` welcome page).
- **Bottom:** standard player inventory (3×9) + hotbar — identical slot math to
  `WarpFluxFurnaceMenu.addPlayerInventory/addPlayerHotbar`.

---

## 4. States (detail pane + action button)

| State | Wind meter | Catalyst | Button | Copy |
|---|---|---|---|---|
| Nothing selected | hidden | hidden | hidden | "Choose a spell to study, yes-yes!" |
| Selected, already known | hidden | hidden | hidden, replaced with a badge | "Mastered." + flavor text only |
| Selected, tier too low | shown greyed | hidden | disabled | "Requires Journeyman rank in Azyr." |
| Selected, Wind too faint | shown, red-tinted, current < required | shown if needed | disabled | "The Wind of Aqshy is too faint here..." |
| Selected, needs catalyst, slot empty | shown, sufficient | shown, empty, outlined | disabled | "Needs a catalyst: Fire Ruby." |
| Selected, all requirements met | shown, sufficient | shown, filled (or hidden if none needed) | enabled | "Begin Research" |
| Researching | shown, live | shown, greyed (locked while consuming) | replaced with progress bar + "Studying..." | — |
| Wind dropped mid-research | shown, red-tinted | unchanged | progress bar paused (doesn't reset) | "The Wind is fading — progress paused." |
| Complete | — | — | — | brief flash/pop (particle + sound, vanilla `LEVEL_UP`-style), row updates to `✓`, detail pane returns to "selected, already known" |

The pause-not-reset behavior on Wind loss is a direct mechanical echo of
`WarpFluxFurnaceBlockEntity.tick()`'s flux-pause (progress decays by 2/tick when `!shouldBeLit`
rather than zeroing) — same forgiveness curve, same reason: don't punish a player for an ambient
condition outside their direct control fluctuating for a moment.

---

## 5. Block behavior

- **Block:** `ResearchTableBlock`, `EntityBlock`, properties mirror `WarpFluxFurnaceBlock`: a
  `FACING` direction property (faces the player on placement) and a `RESEARCHING` boolean (renders
  a subtle glow/particle state while active — reuses the same `LIT`-style pattern, just renamed
  for clarity since "lit" reads wrong for a lectern/table).
- **Tick loop (server only):** while `RESEARCHING` and a valid spell target is set:
  - re-check Wind level each tick (cheap — table just reads `WindGridManager`, doesn't recompute
    anything itself);
  - if sufficient, increment progress; else decay it by a small fixed amount per tick (never
    below 0), same shape as the furnace's flux-pause;
  - on reaching the configured duration, unlock the spell (`PlayerMagicData.withKnownSpell`),
    clear progress and target, consume the catalyst if one was required.
- **Research duration:** a flat config value to start (`Config.researchTicksBase`, e.g. 200
  ticks / 10s — deliberately short for Phase 1 so this is fast to test; tune later), not scaled
  per-spell yet. A per-spell duration is a natural Phase 2 refinement once there's more than one
  duration worth distinguishing.

---

## 6. One small, justified addition to `PlayerMagicData`

`PlayerMagicData` (Task 9) has no way to add a single known spell without hand-building a new
`Set` at the call site. Adding:

```java
public PlayerMagicData withKnownSpell(ResourceLocation spellId) {
    Set<ResourceLocation> updated = new HashSet<>(knownSpells);
    updated.add(spellId);
    return new PlayerMagicData(tier, aptitude, updated);
}
```

mirrors the existing `withAptitude`/`withTier` shape exactly (immutable copy-and-return) and is
the obvious, minimal extension point — not a new mechanism, just completing the pattern the record
already established.

---

## 7. Voice / copy

This mod's existing lang entries lean into eager, slightly-unhinged Skaven speech (*"Welcome,
yes-yes!"*, *"The warlock-engineers are still deciphering these ancient scrawlings!"*). The
Research Table's copy follows the same register — short, exclamation-heavy, a little too excited
about chaotic magic — without overusing the "yes-yes" tic (once, in the empty state, is enough;
sprinkling it on every line would read as noise, not personality).

---

## 8. Explicitly out of scope for this pass

- Per-spell research duration/difficulty scaling (flat config value for now).
- A dedicated BlockEntityRenderer showing a floating/rotating catalyst item above the table
  (a nice future "delight" pass — not required for the mechanic to work).
- Any change to `SpellManager`, `CastingResolver`, `WindGridManager`'s tick cadence, or the debug
  commands — this feature only *reads* those systems and adds one write path
  (`withKnownSpell`).
- Real spell icons (spells have no dedicated icon field yet) — list rows use a Wind-colored
  generic icon for now; a per-spell icon is a natural follow-up once spell JSON grows an `icon`
  field, mirroring how Modonomicon entries already carry one.

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
