# Staircase SiegeProject Group GameTest — Design

## Goal

Prove, end-to-end and with no hand-fed instructions, that a group of clanrats spawned outside a
nexus's discovered territory can have the real pathing/AI stack (region discovery → connector
discovery → staircase macro-project construction → real per-mob goal arbitration → crowd
contention) get them all the way to the nexus, when the only viable crossing requires building a
staircase across a gap.

This directly answers the gap identified in conversation: the existing
`SiegeConstructionActionsGameTests` proves a single mob can *execute* one hand-injected
instruction correctly, but proves nothing about *discovering* a path across a gap, chaining
multi-step construction, real multi-goal AI arbitration, or group/crowd behavior. This spec covers
none of that gap for every `SiegeNode.SiegeAction` — see **Scope** below.

## Scope

**In scope:** the `BUILD_STAIR` SiegeProject type only — a diagonal gap that the region-graph
connector-discovery system (`RegionGraph`/`SiegeLineTracer`) must resolve as a staircase, built
for real by clanrats using their real, unmodified `BuildFlowFieldGoal`.

**Explicitly out of scope, deferred to future plans reusing this same test shape:** `BUILD_BRIDGE`
(horizontal gap), `BUILD_PILLAR`/`BUILD_SPIRAL`/`BUILD_LADDER` (vertical-shaft gaps), and `MINE`
(solid obstruction). Each would need its own gap geometry tailored to the specific branch of
`TerrainEvaluator.determineMacroAction` that produces it, and its own template(s). Not attempted
here.

## Verified mechanism (no test-only hooks needed anywhere)

Confirmed by reading the actual source, not assumed:

1. `WarpFluxConduitBlock.onPlace()` calls `WarpFluxGridManager.get(serverLevel).addConduit(serverLevel, pos)` directly — this is real placement-triggered production code, not something a test has to fake.
2. `addConduit` scans the conduit's 6 neighbors; a neighbor exposing the `WARP_FLUX` capability (a `WarpstoneNexusEntity` does) is registered as an endpoint, a new `WarpFluxNetwork` is created (or an existing adjacent one is reused), and `updateTerritory(Config.territoryChunkRadius)` is called.
3. `WarpFluxNetwork.isValid(level)` becomes true once any endpoint's block entity is a `WarpstoneNexusEntity`.
4. `WarpFluxNetwork.tick()` (driven every level tick via `WarpFluxGridEvents.onLevelTick` → `WarpFluxGridManager.tickNetworks`) has a **self-healing bootstrap**: the first tick where the network is valid and its `TerritoryRegionMap`'s region index is still empty triggers a full `regionMap.rebuild(level, territoryChunks, nexusPos)` automatically, with a retry interval for the case where forced chunk tickets haven't taken effect yet. No caller has to kick this off manually.
5. `ClanratEntity.customServerAiStep()` (every 40 ticks) independently discovers the network via `WarpFluxGridManager.get(serverLevel).getAllNetworks()`, resolves its own region via `RegionIndex.regionAt(...)`, checks `RegionRouteTree.isReachable(...)`, and calls `this.assignFlowField(...)` — which propagates the `RegionFlowField` to every `SiegeGoal` in the mob's own real, unmodified `goalSelector`. This is exactly what happens in live gameplay; the test does not call `assignFlowField` or any goal's `setFlowField` itself.

So the test setup is: build terrain → place a real nexus block → place a real conduit block against
it → let real ticks elapse, instead of anything hand-wired. The only "test-specific" work is
building the terrain and the pass/fail assertion.

## Terrain design principle

`TerrainEvaluator.determineMacroAction`'s action choice is direction-dependent:

- `dx == 0 && dz == 0` (pure vertical) → `PILLAR`/`SPIRAL`/`LADDER`
- `dy != 0` and horizontal component nonzero (diagonal) → `BUILD_STAIR`
- `dy == 0` (pure horizontal) → `BUILD_BRIDGE`

So the ground region and the nexus platform must be offset from each other in **both** a
horizontal axis and Y — a genuine diagonal relationship, not "directly below" (which is what the
original bug report's real geometry was, and which would produce `PILLAR`/`SPIRAL`, not stairs).

The void between the two platforms must be open air *only* along the intended diagonal line, with
everything else around it solid (walls, floor) so no other of `RegionGraph`'s 14 traced directions
per boundary cell (2 pure-vertical + 4 cardinal × 3 dy) can also complete a connection —
`RegionGraph` keeps the *cheapest* discovered connector per region pair, so an accidental shortcut
would silently make the test build something other than stairs.

## The 4 tests

| # | Rats | Gap | Purpose |
|---|---|---|---|
| 1 | 1 | small diagonal gap | Baseline: full stack works at all for one mob |
| 2 | 3-4 | small diagonal gap | Claim system / `AwaitFormationGoal` under light contention |
| 3 | 8-10 | small diagonal gap | Claim/queueing holds up without deadlock under heavier contention |
| 4 | 8-10 | large diagonal gap (forces 2+ chained macro-project hops) | The reported scenario's *shape* (long multi-hop staircase, large group) at scale |

## Template sizing

- **Tests 1-3** reuse the existing `pathing_test_tall` template (32×24×32, already in this repo,
  used by `PathingRegionGameTests`). A diagonal gap comfortably under `SiegeLineTracer.MAX_PROJECT_LENGTH`
  (32 steps) — e.g. roughly a 10-15 block offset in both the horizontal axis and Y — fits with
  margin for solid floor/walls around it, and stays a single `RegionGraph` connector (one macro-project
  line, no chaining needed), keeping tests 1-3 about contention/discovery, not chaining.
- **Test 4** needs more vertical room than any existing template provides. Rather than target the
  literal ~70-block scale from the original bug report, size it to force `RegionGraph.tryTrace`'s
  own hop-chaining (`MAX_CHAIN_HOPS = 12`, each hop up to `MAX_PROJECT_LENGTH = 32` steps) to
  actually engage — a diagonal offset past 32 steps (e.g. ~45 blocks in both the horizontal axis
  and Y, via a fixed direction vector like (1,1,0) repeated) requires at least 2 chained hops. This
  is the property actually worth proving (a long, multi-segment staircase discovered and built by a
  large group), and it's achieved well before 70 blocks. A new template (tentatively
  `pathing_test_giant`, ~32×64×32) will need to be generated, following the exact same
  already-proven procedure this codebase used for `pathing_test_tall`: a temporary
  `@EventBusSubscriber ServerStartedEvent` listener (not a `@GameTest`) run once via
  `./gradlew runServer`, building the platform with `ServerLevel#setBlockAndUpdate`, capturing it
  via `StructureTemplate#fillFromWorld`, and saving via `NbtIo.writeCompressed` under
  `src/main/resources/data/skavenblight/structure/`.

## Pass criteria

Primary (per explicit choice in conversation): every rat's `blockPosition()` ends up within a
small radius of the nexus target, checked via `succeedWhen`/retried every tick until
`timeoutTicks`.

Secondary, required in addition (not optional) — because "arrived" alone can't distinguish "built
a staircase" from "some other action won the race" if the terrain-isolation above has a flaw: the
blocks actually placed along the discovered crossing must be `Blocks.COBBLESTONE_STAIRS`. This can
be checked either by scanning the diagonal line's expected cells for that block type after the test
succeeds, or by hooking `SiegeActivityLog`/`RecordingRegionMap`-style observation (matching this
codebase's existing test conventions) to confirm at least one `BUILD_STAIR` action was actually
executed by a real goal instance, not injected. Exact mechanism is an implementation-plan-level
decision; the requirement itself is fixed by this spec.

## Runtime budget

Real ticks, no manual driving of goals or the region map. Accept a large `timeoutTicks` (thousands
for tests 1-3, more for test 4) rather than shortcut the real cooldown/settle-delay machinery this
test exists to exercise.

## Known risks / things this test suite may surface (not blockers, but expected)

- A prior-session production bug (not yet confirmed fixed or re-broken) where a region's scanned
  vertical bounds didn't cover the actual Y range clanrats stood at, causing a silent
  wilderness-fallback. These tests would surface a regression of this class directly, since they
  depend on real region scanning covering the mobs' real positions across a real vertical gap.
- The existing GameTest suite already has one pre-existing, unrelated flaky test
  (`testRepeatedConnectorCompletionsDontExplodeRebuildCount`, observed hanging on real-time waits
  independent of this work) and one pre-existing, unrelated failing test
  (`testparentregiongetsrealinstructionsforsharedconnectorcells`). Neither is this spec's concern
  to fix, but a failure in either during this work's own test runs should be checked against the
  known baseline before being treated as a new regression.
- `GameTestHelper.setBlock` may or may not trigger `WarpFluxConduitBlock.onPlace()` the same way
  real block placement does (structure-paste block setting sometimes uses update flags that skip
  neighbor/placement callbacks). If it doesn't fire automatically, the test can call
  `WarpFluxGridManager.get(serverLevel).addConduit(serverLevel, conduitPos)` directly — still real
  production code, just invoked explicitly instead of via the block's own callback. To be confirmed
  empirically during implementation; either path is an acceptable outcome of this spec.
