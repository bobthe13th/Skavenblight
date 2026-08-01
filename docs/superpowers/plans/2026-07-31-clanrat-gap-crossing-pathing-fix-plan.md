# Clanrat Gap-Crossing Pathing Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Root-cause and fix the non-deterministic gap-crossing failures in
`StaircaseSiegeGroupGameTests`, verified across repeated runs rather than a single pass.

**Architecture:** Two tasks, investigate then fix. Task 1 builds a small, reusable diagnostic
harness, runs the target test repeatedly, and compares a passing run against a failing run to find
the actual point of divergence — replacing guesswork with evidence. Task 2 implements whatever
Task 1's report identifies, then proves it's actually fixed by requiring 10 consecutive clean runs
of the full 4-test suite, not just one.

**Tech Stack:** Java 21, NeoForge 1.21.1 GameTest framework, existing `ai/goal/clanrat/` and
`ai/pathing/` packages this session already modified five times.

## Global Constraints

- No unit test framework — GameTest only, exactly as established by
  `docs/superpowers/plans/2026-07-31-staircase-siege-group-gametest-plan.md`.
- Every temporary diagnostic addition MUST be marked with a `// TEMPORARY DIAGNOSTIC - REVERT
  BEFORE COMMIT` comment and MUST be reverted before any commit in this plan — this codebase's own
  established convention from the session that built the target test suite.
- Every test run in this plan uses `./gradlew runGameTestServer` (headless) — no need for a
  rendered client unless Task 1's own escalation path (see below) is reached.
- Do not weaken, skip, or delete any of `StaircaseSiegeGroupGameTests`' four tests or their pass
  conditions to make them pass artificially. The bar is the tests passing as written, repeatedly.

---

## File Structure

| File | Change |
|---|---|
| `src/main/java/org/ratden/skavenblight/gametest/StaircaseSiegeGroupGameTests.java` | Temporary diagnostic instrumentation added and reverted (Task 1). No permanent change expected here. |
| `src/main/java/org/ratden/skavenblight/ai/goal/clanrat/AbstractSiegeConstructionGoal.java` | Temporary diagnostic instrumentation (Task 1); possible permanent fix (Task 2) if the report points here. |
| `src/main/java/org/ratden/skavenblight/ai/goal/clanrat/FollowFlowFieldGoal.java` | Temporary diagnostic instrumentation (Task 1); possible permanent fix (Task 2) if the report points here. |
| `src/main/java/org/ratden/skavenblight/ai/pathing/region/RegionFlowField.java` | Possible permanent fix (Task 2) if the report points here. |
| `src/main/java/org/ratden/skavenblight/ai/pathing/FlowFieldCalculator.java` | Possible permanent fix (Task 2) if the report points here — flow-field recompute timing was a live suspect this session. |

Task 2's exact file(s) are intentionally not fixed in advance: the whole reason this is a two-task,
investigate-then-fix plan (per the design spec) is that the root cause isn't known yet. Task 2's
brief will name the exact file(s) once Task 1's report exists.

---

### Task 1: Build a diagnostic harness and root-cause the non-determinism

**Files:**
- Modify (temporary, reverted at the end of this task):
  `src/main/java/org/ratden/skavenblight/gametest/StaircaseSiegeGroupGameTests.java`
- Modify (temporary, reverted at the end of this task):
  `src/main/java/org/ratden/skavenblight/ai/goal/clanrat/AbstractSiegeConstructionGoal.java`
- Create (durable output of this task, NOT reverted): a root-cause report as this task's own SDD
  report file (per `subagent-driven-development`'s own `task-1-report.md` convention) — no new
  source file needed.

**Interfaces:**
- Produces (used by Task 2): the written root-cause report — where in the codebase the bug lives,
  why, and under what condition, plus a recommended fix approach. Task 2's own brief will quote
  the relevant parts of this report directly; no code interface is produced by this task.

**Why:** Five real bugs were already found and fixed this session by reactive, ad-hoc logging
added one symptom at a time — and each fix revealed a *different* bug in a different place rather
than converging, which is exactly the pattern that means "stop guessing, gather evidence
systematically" (see `systematic-debugging`'s own Phase 1). This task replaces that pattern with a
deliberate comparative method: capture the same structured evidence across many runs, then diff a
passing run against a failing run to find where they actually diverge, instead of studying one run
at a time and hypothesizing.

- [ ] **Step 1: Add the diagnostic dump to the test file**

In `StaircaseSiegeGroupGameTests.java`, add this method (matches this session's own
`diagGoalDump` pattern, which already proved useful) and its call site:

```java
    // TEMPORARY DIAGNOSTIC - REVERT BEFORE COMMIT
    private static int DIAG_COUNT = 0;

    private static void diagDump(GameTestHelper helper, List<ClanratEntity> rats) {
        if (DIAG_COUNT++ % 10 != 0) return;
        org.slf4j.Logger log = com.mojang.logging.LogUtils.getLogger();
        for (ClanratEntity rat : rats) {
            log.info("[HARNESS] tick={} pos={} blockPos={} onGround={} delta={} goals={} "
                            + "navIsInProgress={} navIsDone={} navPath={}",
                    DIAG_COUNT, rat.position(), rat.blockPosition().toShortString(), rat.onGround(),
                    rat.getDeltaMovement(), rat.getActiveGoalNames(),
                    rat.getNavigation().isInProgress(), rat.getNavigation().isDone(),
                    rat.getNavigation().getPath());
        }
    }
    // END TEMPORARY DIAGNOSTIC
```

Add the call site as the first line inside `awaitArrivalAndStaircase`'s `succeedWhen` lambda (so it
runs every tick that lambda is retried):

```java
        helper.succeedWhen(() -> {
            diagDump(helper, rats); // TEMPORARY DIAGNOSTIC - REVERT BEFORE COMMIT
            for (ClanratEntity rat : rats) {
```

- [ ] **Step 2: Add the flow-field-instruction dump to the construction-goal base class**

In `AbstractSiegeConstructionGoal.java`, `findEffectiveNode` (the shared lookahead method) is
called every tick by every construction goal's own `canUse()` check, regardless of which goal ends
up active — logging here shows what the flow field is telling the mob at its exact current
position, independent of which goal is driving movement. Add this logging at the very top of the
method body (right after the existing `BlockPos currentPos = this.mob.blockPosition();` line):

```java
    protected final Optional<SiegeNode> findEffectiveNode(Predicate<SiegeNode.SiegeAction> lookAheadMatch) {
        if (this.flowField == null || !(this.mob.level() instanceof ServerLevel serverLevel)) return Optional.empty();
        BlockPos currentPos = this.mob.blockPosition();

        SiegeNode rawAtCurrent = this.flowField.getRawInstruction(currentPos); // TEMPORARY DIAGNOSTIC - REVERT BEFORE COMMIT
        LOGGER.info("[HARNESS] findEffectiveNode currentPos={} rawAtCurrent={}",
                currentPos.toShortString(), rawAtCurrent); // TEMPORARY DIAGNOSTIC - REVERT BEFORE COMMIT

        SiegeNode node = this.flowField.getNextSiegeNode(serverLevel, currentPos);
```

- [ ] **Step 3: Compile check**

Run: `./gradlew compileJava`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Run the target test repeatedly and save each run's log separately**

Run this exact loop (adjust the output directory to wherever you keep scratch files — do not
write these logs into the repo):

```bash
mkdir -p /tmp/harness_logs
for i in $(seq 1 20); do
  ./gradlew runGameTestServer -q > /tmp/harness_logs/run_$i.log 2>&1
  result=$(grep -q "testsingleratbuildsstaircaseacrosssmallgap" /tmp/harness_logs/run_$i.log \
    && grep "testsingleratbuildsstaircaseacrosssmallgap" /tmp/harness_logs/run_$i.log | grep -q "failed" \
    && echo "FAIL" || echo "PASS")
  echo "run $i: $result"
done
```

Expected: a mix of `PASS` and `FAIL` outcomes across the 20 runs (this session's own experience
running this same test many times shows both outcomes occur — if this run somehow produces 20/20
of the same result, run another 10 before proceeding; you need at least one of each to compare).

- [ ] **Step 5: Extract one passing run and one failing run's harness lines**

Pick one `PASS` log and one `FAIL` log from Step 4's output. For each, extract just this task's own
diagnostic lines in order:

```bash
grep "\[HARNESS\]" /tmp/harness_logs/run_<PASS_NUMBER>.log > /tmp/harness_logs/pass_trace.txt
grep "\[HARNESS\]" /tmp/harness_logs/run_<FAIL_NUMBER>.log > /tmp/harness_logs/fail_trace.txt
```

- [ ] **Step 6: Compare the two traces to find the first point of divergence**

Read both extracted traces side by side (`pass_trace.txt` and `fail_trace.txt`). Do not just
compare final states — walk forward from the start of each trace and find the **first** tick where
the two traces show meaningfully different behavior (different goal name, different
`rawAtCurrent`/`node` value at a comparable position, `navIsInProgress` differing when it
shouldn't, a position or velocity that looks physically wrong). That divergence point is the real
lead — not wherever either trace happens to end up.

If nothing diverges in the first 20 lines of each, extract 2-3 more pass/fail pairs from Step 4's
other runs and repeat the comparison — one pair not showing a clear divergence doesn't mean there
isn't one; it means this specific pair's failure mode wasn't the one this session characterized as
"frozen from tick 0" (there may be more than one distinct failure mode contributing to the overall
non-determinism — say so explicitly in the report if that's what several comparisons show).

- [ ] **Step 7: Escalate to live observation if comparative diffing doesn't converge**

If, after comparing at least 3 pass/fail pairs, no clear divergence point or plausible root cause
has emerged: run `./gradlew runClient` (the mod's GameTest namespace is already enabled for the
client run config), enable cheats, use `/test list` to find this test's registered id, `/test run`
it, and hold the mod's `DebugFlowFieldReaderItem` (cycle to `DETAILED_NODES` mode) to watch the
live in-world flow-field overlay while the rat behaves. This was proven effective earlier this
session for a different, visually obvious bug — worth trying here even though non-determinism is
harder to catch by eye than a single consistently-wrong placement.

- [ ] **Step 8: Revert all temporary diagnostics**

Remove everything added in Steps 1-2 from both files (the `diagDump` method, its call site, the
`DIAG_COUNT` field, and the `findEffectiveNode` logging lines) — do not commit any of it.

- [ ] **Step 9: Compile check after revert**

Run: `./gradlew compileJava`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 10: Write the root-cause report**

Write the report as this task's own SDD report contents (per `subagent-driven-development`'s
report-file convention) covering: which file(s) and method(s) are actually responsible, the exact
condition that triggers the failure (not just "sometimes"), why it produces the specific symptoms
observed this session (frozen-from-start, mid-gap oscillation, mid-air-at-timeout), and a
recommended fix approach. If Step 7's live-observation escalation was needed, include what was
observed there. If multiple distinct failure modes were found (see Step 6's note), describe each
one separately rather than forcing a single narrative.

No commit for this task — nothing durable changes in the source tree (Step 8 reverted the only
edits). The report itself is this task's deliverable, handed to Task 2 as its required input.

---

### Task 2: Implement the fix and verify reliability

**Files:**
- To be named by Task 1's report — likely one or more of the files listed in this plan's own File
  Structure table above, but confirm against the report rather than assuming.

**Interfaces:**
- Consumes: Task 1's root-cause report in full — read it before writing any code. If the report's
  recommended approach seems wrong or incomplete once you're looking at the actual code, that's a
  legitimate finding to raise, not something to silently work around.

**Why:** Task 1 tells us what's broken and why. This task fixes it and — because the bug is known
to be non-deterministic — proves the fix holds up over many runs, not just one lucky green result.

- [ ] **Step 1: Read Task 1's report in full**

Do this before touching any code. Identify the exact file(s), method(s), and condition the report
names.

- [ ] **Step 2: Implement the fix**

Follow the report's recommended approach. Match this codebase's existing conventions for a fix
like this (see the five fixes already made this session in these same files — each one is a
small, targeted change with a doc comment explaining the specific bug and how it was confirmed,
not a broad rewrite).

- [ ] **Step 3: Compile check**

Run: `./gradlew compileJava`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Run the full test suite once**

Run: `./gradlew runGameTestServer`
Expected: all 4 `StaircaseSiegeGroupGameTests` tests pass. No NEW failures beyond the two
pre-existing, unrelated ones already documented
(`testparentregiongetsrealinstructionsforsharedconnectorcells` always failing,
`testRepeatedConnectorCompletionsDontExplodeRebuildCount` sometimes flaky on real-time waits).

If this single run doesn't pass: return to Task 1's report and re-examine whether the fix actually
addresses the named condition, rather than iterating blindly — if the report's own root cause was
incomplete or wrong, that itself is worth documenting before trying a different fix.

- [ ] **Step 5: Verify reliability across 10 consecutive runs**

Run this exact loop:

```bash
for i in $(seq 1 10); do
  ./gradlew runGameTestServer -q > /tmp/harness_logs/verify_$i.log 2>&1
  echo "=== run $i ==="
  grep -E "GAME TESTS COMPLETE|required test" /tmp/harness_logs/verify_$i.log
done
```

Expected: all 10 runs show exactly the same 2 known pre-existing failures (never more, never a
different failing test) and all 4 `StaircaseSiegeGroupGameTests` tests pass in every single run.

If any of the 10 runs fails a `StaircaseSiegeGroupGameTests` test: this is a genuine finding, not
noise. Do not retry the loop hoping for a better outcome — capture that run's log, compare it
against Task 1's own comparative-diffing method (Steps 5-6 of Task 1) against a passing run from
this same loop, and treat it as new evidence for a return to Task 1's investigation rather than a
one-off to explain away.

- [ ] **Step 6: Commit**

```bash
git add <the exact file(s) named in Task 1's report and modified in Step 2>
git commit -m "$(cat <<'EOF'
fix: resolve non-deterministic clanrat gap-crossing failures

<one paragraph naming the actual root cause found by Task 1's
investigation, why it caused the specific non-deterministic symptoms
observed (frozen-from-start / mid-gap oscillation / mid-air-at-timeout),
and how this fix addresses it - fill in from Task 1's report, do not
leave this as a generic placeholder>

Verified via 10 consecutive full-suite runs: all 4
StaircaseSiegeGroupGameTests tests pass in every run, with only the two
known pre-existing, unrelated failures appearing (never more).
EOF
)"
```

---

## Manual Validation (not part of task completion, informational only)

1. If Task 1's report identifies more than one distinct failure mode contributing to the overall
   non-determinism, confirm Task 2's fix actually addresses all of them, not just the one that
   happened to reproduce most easily during Task 1's own comparative runs.
2. Once this plan's tasks are both complete, consider whether `StaircaseSiegeGroupGameTests`' own
   `2026-07-31-staircase-siege-group-gametest-plan.md` should be updated to note the tests now pass
   reliably, since that plan currently documents them as known-red by design.
