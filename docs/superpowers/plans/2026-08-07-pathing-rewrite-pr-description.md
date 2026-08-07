# Pathing system rewrite (Tasks 1-20 complete, Task 21 gate at 1/4)

77 files changed, ~7,700 insertions / ~4,000 deletions. Full rewrite of the
region-based flow-field pathing system per
`docs/superpowers/plans/2026-08-05-pathing-rewrite-implementation-plan.md`
(also included in this PR). Replaces the old `SiegeNode`/`SiegeLineTracer`/
`TerrainEvaluator` system end to end with a unified action vocabulary.

## Why

The old system spread five construction behaviors (mining, bridging, two
kinds of stairs, and separate climbing) across a `SiegeNode` type whose own
`.pos()` field meant different things depending on whether you were reading
it as a map key's value or a standalone record - a design-debt footgun
documented before this rewrite even started
(`docs/pathing/instruction-map-invariants.md`). This rewrite collapses the
whole action set onto one `PathAction` enum (`WALK`, `TUNNEL`, `BRIDGE`,
`CARVED_STAIR`, `AIR_STAIR`) with an unambiguous `FlowStep` record, and
removes bare climbing/PILLAR/SPIRAL/LADDER entirely - a stair (real or
carved) is now the only way to go up or down.

## New core data model

- **`PathAction.java`** (new) - the five-action enum replacing the old
  action set.
- **`FlowStep.java`** (new) - replaces `SiegeNode`. As a map value,
  `.pos()` always equals its own key; `.predecessorPos()` carries the next
  hop toward the target. No more dual meaning on one field.
- **`PlannedStep.java`** (new) - a build-order step. Later gained a second
  position field, `placementPos()`, distinct from the logical `pos()` (see
  "Task 21 gate investigation" below) - they diverge only for an ascending
  `AIR_STAIR`/`CARVED_STAIR` hop.
- **`PathStepEvaluator.java`** (new, replaces `TerrainEvaluator.java` +
  half of the old `SiegeLineTracer.java`) - one method generates both WALK
  and construction candidate steps (previously separate systems), one cost
  model for all five actions, hardness-based mining cost via
  `getDestroySpeed()`, and a bedrock failsafe (a stuck-forever cost ceiling
  instead of an infinite-cost block).
- **`PlatformInserter.java`** (new) - a post-processing pass over a
  finished build order that flags "seam" positions where two different
  non-WALK actions meet, so construction can clear a landing platform there
  instead of just placing whatever the seam action would normally place.
- **`TerrainSnapshot.java`** - gained a planned-cell override hook so
  in-progress construction reads as passable during planning without
  needing the real block placed yet.

## Flow field / region system

- **`FlowFieldCalculator.java`** - rewritten as one unified flood that
  produces both WALK and construction steps in a single pass (previously
  two separate flood mechanisms).
- **`FlowFieldState.java`**, **`RegionFlowField.java`** - ported onto
  `FlowStep`; climb-to-above resolution removed along with bare climbing.
- **`RegionScanner.java`** - ported to `PathStepEvaluator`/`FlowStep`;
  flood-fill restricted to WALK-only for region membership.
- **`RegionGraph.java`** - rewritten, absorbing the old `RegionIndex.java`
  (deleted) directly, plus new confirmed-unreachable tracking and a
  targeted bedrock retry path.
- **`RegionConnector.java`**, **`TerritoryRegionMap.java`** - targeted
  ports (not full rewrites) onto the new types, including an
  `onBlockChanged` authority fix so a real player-caused block change wins
  over a stale planned-state override.

## Siege project / construction system

- **`SiegeProject.java`** - rewritten onto `PlannedStep`/`PathStepEvaluator`,
  with platform support and fields shaped for persistence. Later hardened
  during the Task 21 investigation (see below): the placement/logical
  position split, and a worker-registration-continuation ordering fix.
- **`SiegeProjectManager.java`** - rewritten against the unified evaluator.
- **`SiegeInteractionHandler.java`** - rewritten for all five actions plus
  platform execution (3x3 floor + headroom clearing at a seam). Later
  gained a `protectedPositions` guard so a platform's floor can't overwrite
  a neighboring step's own required-open landing cell.
- **`SiegeProjectStore.java`**, **`nbt/SiegeProjectNbtCodec.java`**,
  **`nbt/SiegeProjectSnapshot.java`** (all new) - persistence subsystem so
  an active siege project survives a server restart/chunk reload.
- **`Config.java`** - new tunables: `tunnelBaseCost`, `bridgeBaseCost`,
  `carvedStairBaseCost`, `airStairBaseCost` (per-action base costs),
  `bedrockFailsafeRatMinutes` (bedrock-adjacent stuck-cost ceiling), and
  `formationSlotSpacing` (for the new `AwaitFormationGoal` grid, below).

## Goal layer (mob AI)

- **`FollowFlowFieldGoal.java`** - climbing removed entirely; a mob now
  only ever crosses a gap via a real, built stair.
- **`AbstractSiegeProjectGoal.java`**, **`BuildFlowFieldGoal.java`**,
  **`SiegeNodeLookahead.java`** - ported onto the new types. Later gained a
  defensive null-guard against `flowField` going null mid-cycle (a real,
  if transient, race only reachable once construction actually completes).
- **`AwaitFormationGoal.java`** - real dynamic row/column formation grid
  (previously a stub/simpler wait), so a crowd of rats waiting for a
  contested project's capacity actually organizes into a grid instead of
  clumping.
- **Deleted** (folded into the goal above, or made dead by climbing
  removal): `AbstractSiegeConstructionGoal.java`, `DeployClimbableGoal.java`,
  `SiegeActionAnimator.java`, `SmartBreachGoal.java`, `SpiralSapperGoal.java`,
  `StrandedGoal.java`, `WarpSapperGoal.java`.
- **`ClanratEntity.java`** - goal-list registration updated for the deleted/
  replaced goals above.

## Debug & client tooling

- **`ClientDebugData.java`**, **`ClientRenderHandler.java`** - debug-line
  colors remapped to the new five-action vocabulary.
- **`PathingDebugFileWriter.java`** - node glyphs remapped to the new
  vocabulary; a raw-instruction-chain walk fixed to use `.predecessorPos()`
  now that producers are genuinely "map value `.pos()` == its own key"
  (previously terminated every trace after exactly one cell).
- **`SiegeActivityLog.java`**, debug server modes
  (`DetailedServerMode.java`, `IServerDebugMode.java`, `MacroServerMode.java`,
  `WildernessServerMode.java`), **`DebugPathingCommands.java`**,
  **`DebugFlowFieldReaderItem.java`**, **`SyncFlowFieldDebugPayload.java`**,
  **`WarpFluxNetwork.java`** - ported to the new action/step types.

## Deleted legacy source files

`SiegeNode.java`, `SiegeLineTracer.java`, `TerrainEvaluator.java`,
`region/RegionIndex.java` - fully replaced by the classes above; no
remaining references.

## Task 21 gate investigation (multiple sessions)

Task 21 is the go/no-go gate before Tasks 22-24 can start: all 4 tests in
`StaircaseSiegeGroupGameTests` must pass with real staircases built by
completely unmodified, real production code (real nexus, real conduit, real
territory rebuild - no hand-fed instructions). Getting there surfaced and
fixed a chain of real bugs, each one only reachable once the previous one
was fixed:

1. **FlowStep consumer/producer convention mismatch** - producers
   (`FlowFieldCalculator`, `RegionGraph`) were migrated to the new
   `FlowStep` convention correctly, but consumers (`FollowFlowFieldGoal`,
   `SiegeNodeLookahead`) were only mechanically retyped, still reading
   `.pos()` where they needed `.predecessorPos()` - a mob's own flow-field
   instruction resolved to "move to exactly where you already are."
2. **`entryPos` signpost staleness** - a connector's entry cell's
   completion was checked at the wrong position, permanently freezing a
   mob next to stairs it was never told to climb.
3. **`activeProjects` concurrent-modification race** - a plain `ArrayList`
   read from the server thread while mutated from the async flow-field
   calculation thread, crashing the game-test server once workers actually
   registered/built at realistic volume.
4. **AIR_STAIR/CARVED_STAIR placement geometry** - an ascending stair was
   placed one cell too high (a 1.5-block rise vs. vanilla's ~1.25-block
   jump height, physically uncrossable). Fixed via the `PlannedStep`
   logical/physical position split described above.
5. **Worker-registration-continuation ordering** - unblocked by fixing
   (4): a mining worker standing still while its own build frontier
   advanced past it got dropped mid-chain by a work-radius check that ran
   before the "already registered" short-circuit.
6. **Test stair-counting blind spot** - unblocked by fixing (4):
   `StaircaseSiegeGroupGameTests`/`SiegeProjectGriefRecoveryGameTests`
   scanned a bounding box assuming the crossing stays at the shared Z
   between ground spawn and nexus; real chains climb diagonally along
   whichever axis the region graph picks, which the tight box couldn't see.
7. **PLATFORM seam floor collision** - found by a human watching the test
   run live in-game, not from logs: a connecting platform's floor was wide
   enough to overwrite the previous stair's own landing cell, walling a
   rat in. Fixed via the `protectedPositions` guard in
   `SiegeInteractionHandler` mentioned above.

**Current status: 1 of 4 gate tests passes** (`testSingleRatBuildsStaircaseAcrossSmallGap`,
reproduced across separate runs). The other 3 build correct, well-counted
stair chains (11-41 real stairs, past their 7-20 minimums) but don't finish
arriving within their timeout in the 40-test concurrent batch - not yet
determined whether that's a throughput/timing issue or a further defect.
Full status, root-cause detail, and suggested next steps:
`docs/superpowers/plans/2026-08-07-task-21-status-handoff.md`.

Two related things found and explicitly deferred (not Task 21 blockers,
flagged for their own follow-up with product input): whether a PLATFORM
seam is even needed for a same-direction action transition, and whether
mining/platform clearing should cost real time proportional to block
hardness rather than destroying instantly for free.

## Test suite

- New: `FakeTerrain.java`, `FlowFieldStateTest.java`, `FlowStepTest.java`,
  `PathStepEvaluatorCostTest.java`, `PathStepEvaluatorStepGenerationTest.java`,
  `PlatformInserterTest.java`, `SiegeProjectStoreTest.java`,
  `region/RegionGraphTest.java`, `SiegeProjectGriefRecoveryGameTests.java`
  (Task 20's grief-recovery GameTest),
  `TerrainSnapshotPlannedOverrideGameTests.java`.
- Deleted (superseded or made permanently unreachable by climbing removal):
  `SiegeLineTracerTest.java`, `TerrainEvaluatorTest.java`,
  `FollowFlowFieldGoalClimbGameTests.java`,
  `RegionFlowFieldClimbResolutionGameTests.java`,
  `SiegeConstructionActionsGameTests.java`.
- Updated for the new types/conventions:
  `FlowFieldCalculatorTest.java`, `SiegeProjectManagerTest.java`,
  `SiegeProjectTest.java`, `AwaitFormationGoalGameTests.java`,
  `PathingGoalRecalculationGameTests.java`, `PathingRegionGameTests.java`,
  `SiegeProjectAutoWidenGameTests.java`, `SiegeProjectTickGameTests.java`,
  `StackedStairColumnReTraversalGameTests.java`,
  `StaircaseSiegeGroupGameTests.java`.

`./gradlew test` passes clean. `./gradlew runGameTestServer` is not fully
green - see the handoff doc for the precise, documented remainder (Task
21's 3 remaining gate tests, plus 5 known-stale `PathingRegionGameTests`
fixtures asserting on pure-vertical geometry that climbing removal made
permanently unreachable, pre-existing and unrelated to this rewrite).
