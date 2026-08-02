# First Automated Test: Debug Flow Field Item — Design Spec

**Status:** Draft — pending review
**Date:** 2026-07-26
**Author:** Claude (design session with bobthe13th)

## Problem

This repo has zero automated tests (per `CLAUDE.md`). Every fix, including the one that motivates this spec, is verified by reading code and reasoning about behavior, then confirmed live in a dev world. That works but leaves nothing in place to catch a regression later.

The motivating bug: `DebugFlowFieldReaderItem.inventoryTick` silently sent no network packet at all when the player's current region had no computed `RegionFlowField` for the active `DebugMode` (common for `DETAILED_NODES`/`MACRO_NAVMESH` — unmapped or route-unreachable regions). `WILDERNESS_PATH` mode has a borrow-fallback that almost always finds *some* field, so it was the only mode that reliably produced a packet. Cycling modes updated the on-screen chat message immediately, but the 3D overlay kept rendering whatever `WILDERNESS_PATH` last drew, since the client never received an update telling it otherwise — it looked exactly like the tool was stuck in Wilderness mode.

This has already been fixed directly in `DebugFlowFieldReaderItem.inventoryTick` (uncommitted at time of writing): when every matching network's field lookup comes back empty for the current mode, the item now sends an empty-but-current-mode payload so the client clears stale data instead of freezing on the last successful frame.

This spec covers building the first NeoForge GameTest in the repo, using this exact fix as the target, so it can't silently regress again — and so it establishes a workable pattern for testing this codebase's heavily `ServerLevel`/`BlockPos`-coupled systems going forward.

## Why GameTest, and why not the obvious approach

NeoForge GameTests (`net.minecraft.gametest.framework` + `net.neoforged.neoforge.gametest`) are already wired into `build.gradle` (`runs.gameTestServer`, `neoforge.enabledGameTestNamespaces` system property on every run config) but unused — no test classes exist yet. They spin up a real dedicated-server world, place a structure, and let test code interact with genuine `ServerLevel`/block-entity/entity state — the right fit for this codebase, which is tightly coupled to Minecraft's world model everywhere that matters (see `CLAUDE.md`'s architecture notes on `ai/pathing/`).

The obvious way to test `DebugFlowFieldReaderItem` would be to drive the real item through a real `ServerPlayer` (so `serverPlayer.connection.send(...)` actually fires) and assert on the packet that comes out. Investigating this (`javap` against the exact NeoForge 21.1.228 classes this project compiles against) turned up two problems:

1. `GameTestHelper.makeMockServerPlayerInLevel()` — the only helper that returns an actual `ServerPlayer` with a working `Connection` — is annotated `@Deprecated(forRemoval = true)`. Building a new test around an API already marked for removal is a bad foundation.
2. Even using it today, asserting "a payload was sent" means reaching into the mock connection's raw Netty `EmbeddedChannel` (`serverPlayer.connection.getConnection().channel()`, cast to `EmbeddedChannel`, call `readOutbound()`). `makeMockServerPlayerInLevel()` wires that channel with no packet-encoding pipeline configured at all (unlike a real connection), so what actually lands in the outbound queue is unverified by anything in this investigation — this is genuinely fragile territory for a first test.

**Decision:** extract the decision logic in `inventoryTick` — "given the grid manager, the player's level/position, and the current mode, which network/region field applies, and what `SyncFlowFieldDebugPayload` (if any) should be sent" — into a new method, `DebugFlowFieldReaderItem.computeDebugPayload(...)`, returning `Optional<SyncFlowFieldDebugPayload>`. `inventoryTick` becomes a thin wrapper: compute the payload, send it if present. This isolates the exact regression (previously: `Optional.empty()` when it should have been an empty-but-current-mode payload) behind a plain static method a GameTest can call directly — no `ServerPlayer`, no `Connection`, no packet capture, just a `ServerLevel`, `WarpFluxGridManager`, a `BlockPos`/`ChunkPos`, and a `DebugMode`.

## Approach

### 1. Extraction refactor (behavior-preserving)

`computeDebugPayload` reproduces the *current* (already-fixed) logic from `inventoryTick` verbatim — this is a pure refactor, not a re-fix:

```java
public static Optional<SyncFlowFieldDebugPayload> computeDebugPayload(
        WarpFluxGridManager gridManager, ServerLevel serverLevel,
        ChunkPos playerChunk, BlockPos playerPos, DebugMode currentMode) {

    Set<ChunkPos> lastMatchedTerritory = null;

    for (WarpFluxNetwork network : gridManager.getAllNetworks()) {
        if (!network.isValid(serverLevel)) continue;

        if (network.getTerritoryChunks().contains(playerChunk)) {
            lastMatchedTerritory = network.getTerritoryChunks();

            TerritoryRegionMap regionMap = network.getRegionMap();
            RegionFlowField sharedField = regionMap.getRegionFlowFieldFor(playerPos);
            boolean usingBorrowedWildernessField = false;

            if (sharedField == null && currentMode == DebugMode.WILDERNESS_PATH) {
                sharedField = regionMap.getRegionIndex().getRegions().stream()
                        .map(r -> regionMap.getRegionFlowFieldFor(r.getMin()))
                        .filter(Objects::nonNull)
                        .findFirst().orElse(null);
                usingBorrowedWildernessField = sharedField != null;
            }

            if (sharedField == null) continue;

            Map<BlockPos, SiegeNode> localNodes = new HashMap<>();
            currentMode.getServerLogic().collectData(serverLevel, playerPos, sharedField, regionMap, localNodes);

            Set<ChunkPos> highlightedChunks;
            if (usingBorrowedWildernessField) {
                highlightedChunks = Set.of();
            } else {
                RegionFlowField highlightField = sharedField;
                highlightedChunks = regionMap.getRegionIndex().getRegions().stream()
                        .filter(r -> r.getId() == highlightField.getRegionId())
                        .findFirst()
                        .map(r -> r.getChunkCells().keySet())
                        .orElse(Set.of());
            }

            return Optional.of(new SyncFlowFieldDebugPayload(
                    network.getTerritoryChunks(), localNodes, highlightedChunks, currentMode.ordinal()));
        }
    }

    if (lastMatchedTerritory != null) {
        return Optional.of(new SyncFlowFieldDebugPayload(
                lastMatchedTerritory, Map.of(), Set.of(), currentMode.ordinal()));
    }

    return Optional.empty();
}
```

`inventoryTick` shrinks to:

```java
@Override
public void inventoryTick(ItemStack stack, Level level, Entity entity, int slotId, boolean isSelected) {
    if (!level.isClientSide() && isSelected && entity instanceof ServerPlayer serverPlayer) {
        if (level.getGameTime() % 20 == 0) {
            ServerLevel serverLevel = (ServerLevel) level;
            WarpFluxGridManager gridManager = WarpFluxGridManager.get(serverLevel);
            BlockPos playerPos = serverPlayer.blockPosition();

            CustomData customData = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
            int currentModeIndex = customData.copyTag().getInt("DebugMode");
            DebugMode currentMode = DebugMode.values()[currentModeIndex % DebugMode.values().length];

            computeDebugPayload(gridManager, serverLevel, serverPlayer.chunkPosition(), playerPos, currentMode)
                    .ifPresent(payload -> serverPlayer.connection.send(payload));
        }
    }
}
```

`computeDebugPayload` must be `public` (not package-private) so a GameTest class in a separate `gametest` package can call it directly.

### 2. Minimal world setup a GameTest can build

Tracing how a `WarpFluxNetwork` actually bootstraps its `TerritoryRegionMap` (relevant: `WarpFluxGridManager.addConduit`, `WarpFluxNetwork.tick`/`isValid`/`updateTerritory`):

- A network is created the moment a `ModBlocks.WARP_FLUX_CONDUIT` block is placed with no adjacent network (`WarpFluxGridManager.addConduit`), which also calls `updateTerritory(Config.territoryChunkRadius)` (default radius **2** chunks — see `Config.java:41`).
- `WarpFluxNetwork.isValid(level)` requires at least one endpoint whose block entity is `WarpstoneNexusEntity` — i.e. a plain `ModBlocks.WARPSTONE_NEXUS` block (not the "active" variant, and no `NexusTracker` involvement) placed adjacent to the conduit is sufficient; `scanForEndpoints`/`onLoad` picks it up as an endpoint.
- `WarpFluxNetwork.tick(level)` (driven by `WarpFluxGridManager`'s own tick, which runs off `Skavenblight.onLevelTick`) self-bootstraps the very first `regionMap.rebuild(...)` call automatically once `isValid()` is true and the region index is still empty — no manual "activate" step, no command, no `NexusTracker.setActiveNexus` needed for the pathing system specifically.
- The rebuild is async (`CompletableFuture.runAsync`); a GameTest must poll for completion via `!regionMap.isCalculating() && regionMap.getGeneration() > 0`, not assume it's done after a fixed tick count.

So the smallest structure needed: a small flat floor, one `WARPSTONE_NEXUS` block, one `WARP_FLUX_CONDUIT` block orthogonally adjacent to it. No conduit network, no furnace, no active-nexus block required.

### 3. The regression check itself

Once the network's region map has completed its first rebuild, pick a `BlockPos` that is inside the network's territory (within `territoryChunkRadius` chunks of the nexus/conduit) but far enough from the built floor that it was never scanned into any region (region scanning only follows walkable cells reachable from the nexus within the built structure — empty air outside the platform, still inside the 2-chunk territory bubble, naturally qualifies). Call `computeDebugPayload(...)` for `DebugMode.DETAILED_NODES` at that position and assert the `Optional` is present — this is the exact condition that used to return `Optional.empty()` before the fix.

The precise offset for "in territory, but unscanned" must be confirmed empirically when the structure is first authored and the test first run (see plan's manual step) — the calculation above establishes it's a real, buildable position, not that any specific coordinate is guaranteed sight-unseen.

### 4. Structure authoring

GameTest structures are ordinary structure-template NBT files, the same format the vanilla Structure Block saves, loaded through the normal `StructureTemplateManager` datapack path (`data/<namespace>/structure/<id>.nbt` — singular `structure`, matching this project's 1.21.x resource-folder convention per `CLAUDE.md`). Vanilla's own gametests namespace theirs under a `gametest/` sub-path purely by convention (e.g. `data/minecraft/structure/gametest/redstone/...`), not because the loader requires it — this spec follows that convention: `@GameTest(template = "gametest/small_nexus_territory")` on a class carrying `@GameTestHolder(Skavenblight.MODID)` resolves to `data/skavenblight/structure/gametest/small_nexus_territory.nbt`.

There is no existing datagen path for these in this repo and no reliable way to hand-author correct NBT bytes blind. Authoring this one requires a live dev client: build the platform + nexus + conduit in creative mode, use a Structure Block (`define`/`save` mode) to save it as `skavenblight:gametest/small_nexus_territory`, then copy the saved `.nbt` from the dev world's save folder into `src/main/resources/data/skavenblight/structure/gametest/small_nexus_territory.nbt`. Confirm the exact resolved path empirically the first time the test is run — if NeoForge's registration adds any prefix beyond plain vanilla resolution, a "structure not found" failure will name the exact `ResourceLocation` it tried to load.

### 5. Test class registration

`@GameTestHolder(Skavenblight.MODID)` on the test class is sufficient — NeoForge's `GameTestHooks.registerGametests()` scans the mod's annotation data for `@GameTestHolder`-annotated classes and auto-registers every `@GameTest`-annotated method on them. No manual `RegisterGameTestsEvent` listener is needed.

## Out of scope

- Any test beyond this one. This spec is about proving out the GameTest pattern end-to-end (structure authoring, network/region bootstrap, an assertion that doesn't need packet capture) using the one regression already in hand — not a general testing strategy for the whole mod.
- Testing `WILDERNESS_PATH` mode's borrow-fallback path, or the shift-click deep-dump export path (`PathingDebugFileWriter`, currently mid-fix and uncommitted from a prior session) — separate behaviors, separate tests, later.
- Client-side rendering (`ClientDebugData`, `ClientRenderHandler`). GameTests run server-only (`gameTestServer` run config); there is no client to receive the payload and update rendering state, so this can only ever validate the server-side decision, not the visual result.
- Making `computeDebugPayload` reusable/public-API-shaped beyond what the test needs. It's `public` only because cross-package test access requires it — not a signal that other production code should start calling it.

## Testing / validation

1. `./gradlew compileJava` after the extraction refactor — confirm `inventoryTick`'s observable behavior is unchanged (still a pure refactor at this point).
2. Author the structure live (Section 4), confirming the exact "in territory, unscanned" offset by inspecting `/skavendebug pathing regions` or a deep dump against the placed structure.
3. `./gradlew runGameTestServer` — confirm the new test registers (check console output for the enabled-namespaces log line and the test's own pass/fail report) and passes.
4. Re-run `./gradlew runGameTestServer` after temporarily reverting the `inventoryTick` fix (git stash the fix, not the extraction) to confirm the test actually fails without it — proves the test exercises the real regression rather than passing vacuously.
