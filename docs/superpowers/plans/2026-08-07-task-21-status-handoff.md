# Task 21 status handoff (2026-08-07)

Written at the end of a multi-session investigation, right before merging
`worktree-pathing-rewrite` back into `Jimmy-clanrat-building`. Purpose: let a
smaller, focused session (manual in-game testing) pick up exactly where this
one left off, without needing to re-read five sessions' worth of history in
`docs/superpowers/plans/2026-08-05-pathing-rewrite-implementation-plan.md`'s
Task 21 section or the `pathing-flowstep-convention-bug` memory file first.
Both of those remain the full, detailed record - this is the short version.

## Where the rewrite stands

Tasks 1-20 of the pathing rewrite (`2026-08-05-pathing-rewrite-implementation-plan.md`)
are complete. Task 21 is the go/no-go gate before Tasks 22-24 (the carved-stair/
tunnel/bridge GameTest matrices) can start - it requires all 4 tests in
`StaircaseSiegeGroupGameTests.java` (batch `staircase_siege_group`) to pass, with
real `COBBLESTONE_STAIRS` blocks actually built, not just rats arriving somehow.

**Current score: 1 of 4 passing.**

| Test | Status | Notes |
|---|---|---|
| `testSingleRatBuildsStaircaseAcrossSmallGap` | **PASSES** | Reproduced across 2 separate full-suite runs |
| `testSmallGroupBuildsStaircaseAcrossSmallGap` | Fails - doesn't arrive in time | Builds 11 real stairs (needs 7) |
| `testLargeGroupBuildsStaircaseAcrossSmallGap` | Fails - doesn't arrive in time | Builds 11 real stairs (needs 7) |
| `testLargeGroupBuildsChainedStaircaseAcrossGiantGap` | Fails - doesn't arrive in time | Builds 41 real stairs (needs 20) |

None of the 3 remaining failures show any sign of broken construction or
counting - both are demonstrably working (see stair counts above, all well
past their minimums). The open question is purely: do these 3 need more real
build time than the 40-test concurrent batch gives them, or is there a
separate defect masked by "not enough time"? **This has not been
distinguished yet** - see "Suggested next steps" below.

## How we got to 1/4 (chronological, most relevant last)

This gate has been worked across five-plus sessions. The last two sessions
(this one) found and fixed two real, previously-undiscovered defects:

1. **AIR_STAIR/CARVED_STAIR placement geometry** (commit `7010ff7`). An
   ascending stair was placed one cell too high - a 1.5-block rise no jump
   (vanilla's ~1.25-block jump height) could ever cross. Fixed by splitting
   `PlannedStep` into a logical `pos()` (where the mob ends up standing) and a
   physical `placementPos()` (where the block actually goes) - they only
   diverge for an ascending AIR_STAIR/CARVED_STAIR hop, where `placementPos()`
   is `pos().below()`.

   Fixing this let construction actually complete for the first time ever in
   this gate (previously: 0 stairs built, every run, every prior session) -
   which surfaced two more defects that had been structurally unreachable
   until then, fixed in the same commit:
   - `SiegeProject.canAcceptWorker` checked its work-radius BEFORE its
     already-registered short-circuit, so a mining worker (CARVED_STAIR/
     TUNNEL) standing still while its own frontier advanced got dropped
     mid-chain the instant the next step drifted more than 3.5 blocks away.
   - `StaircaseSiegeGroupGameTests.countStairsInZone` (and
     `SiegeProjectGriefRecoveryGameTests.findFirstStair`) scanned a tight
     bounding box assuming the crossing stays at the ground-spawn/nexus's
     shared Z - real chains actually climb diagonally along whichever
     horizontal axis the region graph picks, which can be the OTHER one.
     Fixed via a shared `crossingZoneBounds` helper that widens whichever
     axis has zero span by the vertical climb distance.

2. **PLATFORM seam floor collision** (commit `699b281`) - **found by the user
   watching `staircase_siege_group:0` run live in-game**, not from logs. A
   connecting platform (inserted wherever two different construction actions
   meet, e.g. an AIR_STAIR chain transitioning into CARVED_STAIR mining) was
   placed on top of the previous stair instead of beside it, forming a
   2-block wall. Root cause: the platform's 3x3 floor is centered on its own
   node and wide enough that a fringe cell lands exactly on the PREVIOUS
   step's own logical landing cell, overwriting it with cobblestone. Fixed by
   passing every build-order step's own logical position into
   `constructSiegeBlock` as `protectedPositions`, and having the platform
   floor loop skip any cell in that set.

   **This fix is what got `testSingleRatBuildsStaircaseAcrossSmallGap` to
   pass.** A false lead from earlier in the same session (a "jump-bounce
   loop" theory built from log instrumentation alone) was retracted once the
   real cause was found - it's documented as retracted in the memory file so
   it doesn't get re-chased.

Both commits are on `worktree-pathing-rewrite`, merging into
`Jimmy-clanrat-building` along with this document.

## Suggested next steps (for the smaller manual-testing sessions)

1. **Run `staircase_siege_group:0` and watch it, the way the platform bug was
   found.** This has repeatedly outperformed log-based investigation this
   session - a person watching the game found in thirty seconds what an
   entire session of instrumentation missed. Watch each of the 3 failing
   tests specifically: does the rat get most of the way up and stall near the
   top, or does it stall early? Does it look like it's still actively
   building/climbing when the test times out, or does it look stuck?
2. If it looks like a genuine time/throughput problem (rat still visibly
   progressing at timeout): consider whether `Config.workPerRatPerTick` /
   `Config.maxProjectWorkers` / `Config.workersPerWidenStep` need retuning,
   or whether the 3 failing tests' `timeoutTicks` (5000/3000/20000 in
   `StaircaseSiegeGroupGameTests.java`) are simply too tight for a 40-test
   concurrent batch sharing one tick loop. Don't just raise the timeouts
   without understanding why more time is needed - that was tried once this
   session as a diagnostic only (not committed) and didn't resolve anything
   because construction was walled in at the time; it's untested now that the
   wall is gone.
3. If it looks stuck (not progressing) rather than slow: that's a real,
   distinct defect, and the same "watch it live" approach is the fastest way
   to characterize it before touching any code.
4. `playerBreakingAHalfBuiltProjectStillTriggersRealRecalculation`
   (`SiegeProjectGriefRecoveryGameTests`) is not one of the 4 gate tests but
   is closely related - it now gets past finding a real stair to grief
   (thanks to the same fixes), then fails on a deeper bug: the flow field
   doesn't revert from `WALK` back to a real construction action once a
   built step is destroyed. Worth its own look, not required for Task 21.

## Explicitly deferred, not forgotten

Two things the user flagged as real but out of scope for closing this gate:

1. **Is a PLATFORM seam even needed for a same-direction transition** like
   AIR_STAIR→CARVED_STAIR? `PlatformInserter` currently fires on any two
   differing non-WALK actions, which may be broader than actually necessary.
2. **Mining/platform clearing destroys blocks instantly, regardless of
   hardness.** `PathStepEvaluator.miningCost` only prices the single TUNNEL/
   CARVED_STAIR target cell; the extra floor/headroom cells a platform seam
   or `clearStairHeadroom` also destroys are free and instant. The user
   specifically doesn't want a player's obsidian to vanish instantly. This
   needs its own design pass (footprint-sum cost vs. per-cell gating is a
   real choice) with the user's input on the formula - don't guess at it.

## Where to look

- Full history: `docs/superpowers/plans/2026-08-05-pathing-rewrite-implementation-plan.md`,
  Task 21 section (search for "Gate verdict").
- Cross-session memory: the `pathing-flowstep-convention-bug` memory file
  (auto-loaded in future sessions on this project).
- The 4 gate tests: `src/main/java/org/ratden/skavenblight/gametest/StaircaseSiegeGroupGameTests.java`.
- The griefing test: `src/main/java/org/ratden/skavenblight/gametest/SiegeProjectGriefRecoveryGameTests.java`.
- Run them: `./gradlew runGameTestServer` (runs all 40 registered GameTests,
  not just these 4 - no per-class filter exists in this project's Gradle
  config). Check for orphaned `gameTestServer` java.exe processes before and
  after (`tasklist //FI "IMAGENAME eq java.exe" //FO CSV`) - they don't
  always exit cleanly.
