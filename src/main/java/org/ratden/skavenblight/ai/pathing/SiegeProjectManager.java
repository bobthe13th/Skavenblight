package org.ratden.skavenblight.ai.pathing;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;

import java.util.*;
import java.util.function.Predicate;

public class SiegeProjectManager {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int[][] CARDINAL_OFFSETS = { {1, 0}, {-1, 0}, {0, 1}, {0, -1} };

    // Upper bound on activeProjects - without this, a project that never completes (e.g.
    // because rats keep congregating and failing at its entry point) accumulates forever:
    // every such project is re-injected and re-locks its positions on every single pass,
    // permanently blocking alternate routes through them. Observed in practice: 803 active
    // projects in one test. Evicting the oldest isn't a loss - evaluateMacroProjects
    // rediscovers the same need on the very next pass if the obstacle it addressed still exists.
    private static final int MAX_ACTIVE_PROJECTS = 200;

    // Proximity threshold used by isNearExistingProject() (distSqr < 9, i.e. within ~3 blocks).
    private static final int PROXIMITY_RADIUS_SQR = 9;
    // Bucket size for the spatial index below - must exceed the proximity radius (3) so that
    // any two points within it are guaranteed to fall in the same or an adjacent bucket.
    private static final int BUCKET_SIZE = 4;

    // Default: territory-scale macro projects (no route-tree connector yet, or this region has
    // none - see setMaxCandidateProjectLength's doc). Kept generous, matching the original
    // single-flow-field design's own budget. Public (not package-private) because the caller that
    // needs it - TerritoryRegionMap.rebuildRegionsAndGraph/recomputeDirtyRegions - lives in the
    // ai.pathing.region sub-package, not this one.
    public static final int DEFAULT_MAX_CANDIDATE_PROJECT_LENGTH = 32;
    private int maxCandidateProjectLength = DEFAULT_MAX_CANDIDATE_PROJECT_LENGTH;

    // CopyOnWriteArrayList, not ArrayList: injectActiveProjects/finalizeCandidateProjects mutate
    // this from the async flow-field calculation thread (TerritoryRegionMap's own
    // CompletableFuture.runAsync), while findProjectContaining reads it synchronously from the
    // server thread via every mob's own AbstractSiegeProjectGoal.canUse() every tick - a plain
    // ArrayList throws ConcurrentModificationException the moment a read's iterator is live
    // while the calc thread concurrently removeIf's a completed project (confirmed via a real
    // GameTest crash - "Worker-Main" calc thread racing SiegeProjectManager.findProjectContaining
    // on the server thread, once Task 21's entryPos-signpost fix let projects actually progress
    // and churn instead of deadlocking near-instantly). Read-heavy/write-rare is exactly this
    // type's intended use case; add/remove/removeIf/List.copyOf all keep their existing call-site
    // semantics unchanged, snapshotting iteration instead of throwing.
    private final List<SiegeProject> activeProjects = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<SiegeProject> candidateProjects = new ArrayList<>();
    // Spatial index of every anchor evaluateMacroProjects() has fired on this calculation,
    // bucketed so isNearExistingProject() doesn't have to linearly scan every anchor seen so
    // far. That scan used to run once per (line, step) - up to 14 * 32 = 448 times per anchor -
    // against a set that grows for the entire calculation, an O(n^2) blowup that dominated
    // runtime on any territory large enough to trigger macro evaluation frequently (which is
    // most of them, given how often hitObstacle fires - see FlowFieldCalculator).
    private final Map<Long, List<BlockPos>> plannedProjectBuckets = new HashMap<>();
    private final Set<BlockPos> lockedPositions = new HashSet<>();

    // Per-calculation perf counters, reset in injectActiveProjects() and read by debug
    // tooling (PathingDebugFileWriter) - a calculation that's slow because macro evaluation
    // is firing on nearly every node looks very different from one that's slow for some
    // other reason, and there was previously no way to tell the two apart from outside.
    private int macroEvaluationCount = 0;
    // Pre-existing dead counter, carried forward as-is (not this task's to fix): declared, reset
    // every pass, exposed via getLineStepsEvaluated() for PathingDebugFileWriter, but never actually
    // incremented anywhere - confirmed unchanged from the pre-rewrite file. Always reads 0.
    private long lineStepsEvaluated = 0;
    // getCandidateProjectCount() (candidateProjects.size()) is always 0 by the time anything
    // outside this class can read it - finalizeCandidateProjects() unconditionally clears
    // candidateProjects right after moving survivors into activeProjects, and that's the last
    // thing that happens each pass. Confirmed in testing: every dump taken across an entire
    // session read "0 candidate this pass" regardless of how many lines actually completed -
    // a display artifact, not evidence evaluateSingleLine stopped producing candidates. These
    // two counters capture the real per-pass numbers before the clear.
    private int lastPassCandidatesGenerated = 0;
    private int lastPassCandidatesSurvived = 0;

    private final PathStepEvaluator pathStepEvaluator;

    public SiegeProjectManager(PathStepEvaluator pathStepEvaluator) {
        this.pathStepEvaluator = pathStepEvaluator;
    }

    /**
     * Registers a region's chosen parent connector as a persistent active project, so
     * injectActiveProjects seeds its remaining (not-yet-built) instructions into every
     * subsequent calculation pass for this region - exactly like a reactively-discovered
     * SiegeProject, except this one is chosen once by the route tree and never re-discovered
     * via evaluateMacroProjects (a region's own internal flood never hits an obstacle, by
     * construction - see RegionScanner).
     */
    public void setActiveConnectorProject(SiegeProject project) {
        this.activeProjects.clear();
        if (project != null) {
            this.activeProjects.add(project);
        }
    }

    /**
     * Layers an ADDITIONAL active project on top of whatever {@code setActiveConnectorProject}
     * already seeded for this pass, rather than replacing it - see
     * TerritoryRegionMap.injectSharedConnectorProjects (Task 9 Step 0b) for the caller. A region
     * can simultaneously be the route-tree CHILD of one connector (its own upstream project, set
     * via setActiveConnectorProject) and the route-tree PARENT of one or more other connectors
     * (each needing its own crossing project stacked here) - both must be present in
     * {@code activeProjects} together when {@code injectActiveProjects} next consumes the list.
     * Safe with the same "regions processed strictly sequentially" reasoning
     * setActiveConnectorProject's own doc already relies on: every region's pass always starts
     * with a setActiveConnectorProject clear+reseed, so nothing added here ever leaks into the
     * NEXT region's pass.
     */
    public void addSharedConnectorProject(SiegeProject project) {
        if (project != null) {
            this.activeProjects.add(project);
        }
    }

    /**
     * Called once per region-scoped pass (TerritoryRegionMap, mirroring its existing
     * setActiveConnectorProject call). Pass a short cap when this region already has a
     * route-tree-assigned parent connector - long-range connectivity is that connector's job now,
     * not this region's own reactive hitObstacle/evaluateMacroProjects search. Pass the default
     * (or call with DEFAULT_MAX_CANDIDATE_PROJECT_LENGTH) for a region with no route-tree
     * connector yet, where local discovery is still the only way it connects to anything.
     */
    public void setMaxCandidateProjectLength(int maxLength) {
        this.maxCandidateProjectLength = maxLength;
    }

    public void injectActiveProjects(TerrainAccess terrain,
                                     PriorityQueue<FlowFieldCalculator.QueueNode> calcQueue,
                                     Map<BlockPos, Integer> nextCostMap,
                                     Map<BlockPos, FlowStep> nextInstructionMap) {
        plannedProjectBuckets.clear();
        lockedPositions.clear();
        candidateProjects.clear();
        macroEvaluationCount = 0;
        lineStepsEvaluated = 0;
        lastPassCandidatesGenerated = 0;
        lastPassCandidatesSurvived = 0;

        activeProjects.removeIf(project -> project.isCompleted(terrain, pathStepEvaluator));

        for (SiegeProject project : activeProjects) {
            for (Map.Entry<BlockPos, FlowStep> entry : project.getRemainingInstructions(terrain, pathStepEvaluator).entrySet()) {
                BlockPos pos = entry.getKey();
                lockedPositions.add(pos);
                nextInstructionMap.put(pos, entry.getValue());
            }

            BlockPos entry = project.getEntryPos();
            int entryCost = project.getExpectedEntryCost();

            // entryPos is, by construction, the far side of a gap the ordinary Dijkstra flood
            // cannot independently cross while this project's own interior cells are still
            // unbuilt/locked - so once PathStepEvaluator.isActionCompleted correctly reports its own
            // WALK action as already done (see that class's own doc), the getRemainingInstructions
            // loop above stops re-seeding it, since nothing needs BUILDING there. But nothing needs
            // building there is not the same as nothing needs to ROUTE there: without this,
            // entryPos gets no instruction at all on the very next pass, stranding a mob standing on
            // it with no way to be told to walk onward into the project's still-unbuilt interior.
            //
            // Correction (2026-08-06, found executing Task 21's go/no-go gate): this MUST be an
            // unconditional put, not putIfAbsent. For a child region, TerritoryRegionMap.
            // rebuildRegionsAndGraph sets that region's OWN flow-field target to exactly this same
            // connector's entry position (target = parentConnector.entryFor(regionId)) - so by the
            // time this method runs, FlowFieldCalculator.startCalculation has ALREADY unconditionally
            // seeded a trivial self-referential terminal WALK step at this exact key
            // (nextInstructionMap.put(targetPos, FlowStep(targetPos, WALK, targetPos))). A
            // putIfAbsent here is therefore a permanent no-op for every child region: the real
            // "start crossing" instruction can never be installed, and a mob standing at the entry
            // point is told "you're already done, stand still" forever instead of being routed onto
            // the crossing - confirmed via a real GameTest run showing 0 stairs ever built in every
            // StaircaseSiegeGroupGameTests scenario, traced by instrumenting AbstractSiegeProjectGoal
            // .canUse() and finding the effective node frozen at a self-referential WALK exactly at
            // the rat's own position, every tick, forever. The "genuinely cheaper real route" this
            // guard meant to protect is not actually at risk from switching to put(): ordinary
            // Dijkstra relaxation (FlowFieldCalculator.processNeighbors) only ever overwrites
            // nextInstructionMap together with nextCostMap when it finds something strictly cheaper
            // than what's currently in nextCostMap, and that overwrite happens AFTER this method
            // returns, in the main queue-processing loop - so a later, genuinely cheaper real route
            // still wins regardless of what this seeds here. The only case actually affected by this
            // change is exactly the one that was broken: nothing else ever independently reaches
            // entryPos (its cost is pinned at 0, as the region's own target, and never relaxed
            // again), so whatever this method seeds here is final either way - it must be the real
            // crossing instruction, not the trivial placeholder that was winning the race by
            // construction order alone. Guarded on presence: a route-tree connector project
            // (RegionGraph.registerConnector's outbound/inbound instruction maps) deliberately does
            // NOT key its own entryPos in `instructions` - "the far region's own pass already covers
            // it" (see that method's own doc) - so there's nothing to fall back to there, and
            // inserting a literal null would NPE the first caller that reads it back out.
            //
            // Root-cause fix (2026-08-06, Task 21 go/no-go gate, second correction): the static
            // instruction this map stores at `entry` describes the FIRST hop's action
            // (e.g. AIR_STAIR) with predecessorPos() pointing at that hop's real target - but its
            // own pos() is `entry` itself (entryPos), which is real, already-walkable ground and
            // can NEVER satisfy any construction action's isActionCompleted check. Re-seeding this
            // SAME static value unconditionally on every pass (the fix immediately above, still
            // correct and still needed) means RegionFlowField.getNextStep's own completion-collapse
            // check - isActionCompleted(node.pos(), node.action()), correct for every ordinary
            // flood entry and for every OTHER position in this connector's own instruction map,
            // where node.pos() really is the position the action applies to - evaluates at the
            // WRONG position for this one signpost entry, and so NEVER collapses to WALK, even
            // after the real target (predecessorPos()) is genuinely built. Confirmed via
            // instrumented GameTest evidence: a worker stands at entryPos (this goal never moves a
            // mob while it builds) and builds several real hops - each of THEIR own map entries
            // correctly flips completed@key=true once built, since their own pos() is the real
            // built position - but entryPos's own entry stays permanently "AIR_STAIR ahead," so
            // FollowFlowFieldGoal's non-WALK branch freezes the mob in place forever once the
            // project's own work radius outruns it, never walking it onto the stairs it just built.
            // Fix: check completion at the position the action ACTUALLY applies to
            // (predecessorPos()), not at entry - once that's done, seed WALK instead, letting
            // ordinary movement finally close the gap onto real ground.
            FlowStep ownEntryInstruction = project.getInstructions().get(entry);
            if (ownEntryInstruction != null) {
                BlockPos firstRealTarget = ownEntryInstruction.predecessorPos();
                boolean firstHopDone = pathStepEvaluator.isActionCompleted(terrain, firstRealTarget, ownEntryInstruction.action());
                nextInstructionMap.put(entry, firstHopDone
                        ? new FlowStep(entry, PathAction.WALK, firstRealTarget)
                        : ownEntryInstruction);
            }

            if (entryCost < nextCostMap.getOrDefault(entry, Integer.MAX_VALUE)) {
                nextCostMap.put(entry, entryCost);
                calcQueue.add(new FlowFieldCalculator.QueueNode(entry, entryCost));
            }

            // Fallback only, deliberately with no matching nextCostMap entry (unlike entry above):
            // if this region's own ordinary Dijkstra propagation can genuinely reach exitPos (it
            // usually can - see SiegeProject.getExitPos's doc for the one case it can't), that
            // real step's own totalCost < Integer.MAX_VALUE comparison in
            // FlowFieldCalculator#processOrthogonalNeighbors will still overwrite this with the
            // real computed instruction later in the same pass. This only survives to publish when
            // nothing else ever reaches exitPos at all - exactly the stuck-forever case this
            // exists to prevent. Self-referential (matching FlowFieldCalculator.startCalculation's
            // own target-seeding convention), not a placeholder: detectMutualCyclePositions treats
            // predecessorPos().equals(pos()) as an expected chain terminus, never a cycle.
            BlockPos exit = project.getExitPos();
            if (exit != null) {
                nextInstructionMap.putIfAbsent(exit, new FlowStep(exit, PathAction.WALK, exit));
            }
        }
    }

    public void finalizeCandidateProjects(Map<BlockPos, Integer> finalCostMap, Map<BlockPos, FlowStep> finalInstructionMap) {
        for (SiegeProject project : candidateProjects) {
            if (project.survivedMapOverwrite(finalCostMap, finalInstructionMap)) {
                this.activeProjects.add(project);
                lastPassCandidatesSurvived++;
            }
        }
        candidateProjects.clear();

        // Evict oldest-first if we're over the cap - see MAX_ACTIVE_PROJECTS.
        while (activeProjects.size() > MAX_ACTIVE_PROJECTS) {
            activeProjects.remove(0);
        }
    }

    public Set<BlockPos> getLockedPositions() {
        return Collections.unmodifiableSet(this.lockedPositions);
    }

    /** Defensive copy, mirroring {@link #getLockedPositions} - used by TerritoryRegionMap to build
     * its planned-cell terrain override (see TerrainSnapshot's plannedStateOverride hook). */
    public List<SiegeProject> getActiveProjects() {
        return List.copyOf(this.activeProjects);
    }

    public Optional<SiegeProject> findProjectContaining(BlockPos pos) {
        for (SiegeProject project : activeProjects) {
            if (project.getInstructions().containsKey(pos)) return Optional.of(project);
        }
        return Optional.empty();
    }

    /**
     * The already-active project (if any) whose own {@code entryPos} is exactly {@code pos} - used
     * by {@link #evaluateSingleLine} to scope its self-collision guard to the ONE project being
     * re-entered, not every active project in the region. See that method's own doc for why this
     * distinction matters.
     */
    private Optional<SiegeProject> activeProjectWithEntryPos(BlockPos pos) {
        for (SiegeProject project : activeProjects) {
            if (project.getEntryPos().equals(pos)) return Optional.of(project);
        }
        return Optional.empty();
    }

    public int getActiveProjectCount() { return this.activeProjects.size(); }
    /** Always 0 once a pass has finished - see the field doc on lastPassCandidatesGenerated/Survived for the real per-pass numbers. */
    public int getCandidateProjectCount() { return this.candidateProjects.size(); }
    public int getMacroEvaluationCount() { return this.macroEvaluationCount; }
    public long getLineStepsEvaluated() { return this.lineStepsEvaluated; }
    public int getLastPassCandidatesGenerated() { return this.lastPassCandidatesGenerated; }
    public int getLastPassCandidatesSurvived() { return this.lastPassCandidatesSurvived; }

    public void evaluateMacroProjects(TerrainAccess terrain, BlockPos anchorPos, FlowFieldState state, int anchorCost,
                                      PriorityQueue<FlowFieldCalculator.QueueNode> calcQueue,
                                      Map<BlockPos, Integer> nextCostMap,
                                      Map<BlockPos, FlowStep> nextInstructionMap) {

        // A region with a route-tree-assigned parent connector (see setMaxCandidateProjectLength's
        // own doc) gets a short local-gap budget specifically so its OWN reactive search never
        // substitutes for the connector graph's job of long-range connectivity. That intent held
        // for a single macro-project line, but not for a CHAIN of them: evaluateSingleLine writes
        // a successful line's own endPos directly back onto this SAME pass's calcQueue (see its own
        // body), so FlowFieldCalculator's Dijkstra loop pops it as an ordinary frontier node and
        // fires evaluateMacroProjects again from there. Each such chained call previously got a
        // completely fresh maxCandidateProjectLength budget, letting a region capped to (say) 6
        // blocks discover a path far longer than 6 blocks by chaining several 6-block-capped lines
        // end to end - confirmed via GameTest reaching 14 blocks across 3 chained hops, all the way
        // into a DIFFERENT region's own territory, producing a flow-field cycle against that
        // region's own route-tree-assigned connector project (see
        // docs/superpowers/plans/2026-08-01-flow-field-planning-gap-fix.md for the full trace).
        // Refusing to continue a chain - rather than tracking a cumulative step budget across the
        // 14-direction fan-out below, which would also restrict how far a single legitimate
        // obstacle's own multi-direction search can reach in one call - keeps a capped region's
        // search to "one obstacle, one line, one hop": exactly the "short local-gap" scope the cap
        // was always meant to provide. Regions with no parent connector yet (the default, uncapped
        // budget) are unaffected - they still need to chain freely to establish their own
        // connectivity in the first place.
        //
        // Renamed from the old isChainedLanding (it checked for the old synthetic BUILD_LANDING
        // action specifically) and GENERALIZED, not dropped: PLATFORM is a post-process Set<BlockPos>
        // annotation on a finished SiegeProject (see PlatformInserter), never a PathAction visible in
        // nextInstructionMap, so no platform-shaped node can ever appear here mid-flood - there is
        // nothing left for an "isChainedPlatform" check to match. But the underlying need (a chain's
        // own construction terminus re-firing evaluateMacroProjects on itself) is still real and still
        // needs guarding - FlowFieldCalculator's own isPlannedConstructionTarget (its
        // processCalculationQueue) already generalized its half of this same check from
        // "action == BUILD_LANDING" to "action != WALK" and explicitly deferred refining it further to
        // this task. Mirrored here with the identical condition.
        FlowStep existingInstruction = nextInstructionMap.get(anchorPos);
        boolean isChainedConstruction = existingInstruction != null && existingInstruction.action() != PathAction.WALK;
        if (isChainedConstruction && this.maxCandidateProjectLength < DEFAULT_MAX_CANDIDATE_PROJECT_LENGTH) {
            return;
        }

        macroEvaluationCount++;

        // Deliberately no "anchor itself needs fixing" special case here anymore. That used to
        // putIfAbsent a SiegeNode(anchorPos, action) keyed at anchorPos itself - a literal
        // self-loop instructing whoever is AT anchorPos to build/mine AT anchorPos. That's
        // fundamentally unsafe to execute (a mob can't place a block into the exact space its
        // own body occupies without stepping aside first, which nothing here did), and it's
        // redundant: an anchor stops being walkable only when terrain changed after it was
        // first queued (typically a SiegeProject's entry point getting physically broken - see
        // injectActiveProjects), and the ordinary adjacent-neighbor repair path
        // (candidateSteps' own construction candidates) already discovers and repairs it safely
        // from an adjacent tile, the same way any other obstruction gets fixed. Confirmed via
        // SiegeActivityLog in testing: every "mob@X -> action at X" self-overlap traced back to
        // this exact line.

        for (int dy : new int[]{-1, 1}) {
            evaluateSingleLine(terrain, anchorPos, state, anchorCost, 0, dy, 0, calcQueue, nextCostMap, nextInstructionMap);
        }

        for (int[] dir : CARDINAL_OFFSETS) {
            for (int dy : new int[]{-1, 0, 1}) {
                evaluateSingleLine(terrain, anchorPos, state, anchorCost, dir[0], dy, dir[1], calcQueue, nextCostMap, nextInstructionMap);
            }
        }

        BlockPos immutableAnchor = anchorPos.immutable();
        plannedProjectBuckets.computeIfAbsent(bucketKeyFor(immutableAnchor), k -> new ArrayList<>()).add(immutableAnchor);
    }

    /**
     * Replaces one {@code SiegeLineTracer.trace()} call: walks a fixed {@code (dx,dy,dz)} direction
     * one hop at a time via {@code PathStepEvaluator.candidateSteps} filtered to the exact offset
     * each time - the same technique {@code RegionGraph.traceLine}/{@code SiegeProject
     * .traceChainedHops} use, mirrored here rather than shared directly since this method also
     * needs the self-collision guard and the relative cost ceiling neither of those two callers
     * needs (they have no {@code nextCostMap} of their own to compare against).
     *
     * <p>Terminates naturally the moment a WALK step is offered, aborts the moment NO candidate
     * exists in the exact fixed direction (out of bounds, the self-collision/proximity guard below,
     * or genuinely no valid step there), or, if {@code maxCandidateProjectLength} is exhausted while
     * still legitimately building, stops collecting and treats what's been gathered so far as a
     * SUCCESS - the same cap-exhaustion-is-success contract {@code SiegeLineTracer.trace} used to
     * honor by appending a synthetic BUILD_LANDING; that action type is gone, so this simply stops
     * one cell short of where the old landing would have sat, with nothing synthesized in its place.
     *
     * <p>Cost is the sum of each hop's own {@code PathStepEvaluator.EvaluatedStep.cost()} - the same
     * real per-step cost model the ordinary flood and RegionGraph's own connector discovery already
     * use, replacing {@code SiegeLineTracer}'s old bespoke {@code buildingBasePenalty *
     * COST_MULTIPLIER} + dy-biased-multiplier formula. That old formula was a second, divergent cost
     * model that existed only for macro-project line evaluation; dropping it (rather than porting it
     * forward) means connector costs (RegionGraph) and macro-project costs (here) are FINALLY
     * comparable on one scale - the design's whole point. The relative ceiling itself (below) is
     * kept, unlike RegionGraph/SiegeProject's traces: {@code nextCostMap} is real here, so a fresh
     * line that can't beat what the ordinary flood already knows about a position must still abort
     * rather than overwrite it with something no better.
     */
    private void evaluateSingleLine(TerrainAccess terrain, BlockPos anchorPos, FlowFieldState state, int anchorCost,
                                    int dx, int dy, int dz,
                                    PriorityQueue<FlowFieldCalculator.QueueNode> calcQueue,
                                    Map<BlockPos, Integer> nextCostMap,
                                    Map<BlockPos, FlowStep> nextInstructionMap) {

        // An active project's own entryPos is unconditionally re-added to nextInstructionMap AND
        // calcQueue every pass (see injectActiveProjects), so ordinary Dijkstra can pop it again
        // and re-fire evaluateMacroProjects on it as a brand-new obstacle anchor. Without a guard,
        // one of the 14 fanned directions below could trace straight back over a cell that SAME
        // project already owns - nextInstructionMap.putAll further down writes with no cost
        // comparison (see FlowFieldCalculator's own comment above breakMutualCycles), so it would
        // silently overwrite that cell's correct instruction to point back at the anchor, forming a
        // direct mutual 2-cycle with the anchor's own still-standing instruction.
        //
        // Scoped to self-collision only (anchorPos IS an active project's own entryPos, AND the
        // position being traced onto is still locked as one of THAT SAME project's own cells) -
        // deliberately not "abort on any locked cell": that blanket version also blocks a fresh
        // line merely crossing a DIFFERENT, unrelated project's cell, which poses no cycle risk at
        // all and would otherwise stop real construction from ever being planned. A cross-project
        // collision that does form a cycle still falls back to FlowFieldCalculator's existing
        // breakMutualCycles/pickCyclePositionToDrop.
        Optional<SiegeProject> reenteredProject = activeProjectWithEntryPos(anchorPos);
        Predicate<BlockPos> abortAt = pos -> pathStepEvaluator.isOutOfBounds(terrain, pos, state)
                || isNearExistingProject(pos, anchorPos)
                || (reenteredProject.isPresent() && lockedPositions.contains(pos)
                        && reenteredProject.get().getInstructions().containsKey(pos));

        Map<BlockPos, FlowStep> instructions = new HashMap<>();
        // Accumulated in the same loop, no extra terrain work: each hop paired with the action
        // candidateSteps computed for that very position - see this class's own doc.
        List<FlowStep> orderedSteps = new ArrayList<>();
        BlockPos current = anchorPos;
        int totalCost = anchorCost;
        BlockPos endPos = null;

        for (int hop = 1; hop <= maxCandidateProjectLength; hop++) {
            BlockPos next = current.offset(dx, dy, dz);
            // Tested explicitly here, mirroring SiegeLineTracer.trace's own first-line check and
            // RegionGraph.traceLine's identical pattern - not merely relying on candidateSteps'
            // internal outOfBounds filtering (passed below too), so the abort reason is unambiguous.
            if (abortAt.test(next)) return;

            List<PathStepEvaluator.EvaluatedStep> steps = pathStepEvaluator.candidateSteps(terrain, current, Collections.emptySet(), abortAt);
            PathStepEvaluator.EvaluatedStep matching = steps.stream()
                    .filter(s -> s.pos().equals(next)).findFirst().orElse(null);
            if (matching == null) return; // out of bounds, self-collision, or no valid step in this exact direction

            int candidateCost = totalCost + matching.cost();
            if (candidateCost >= nextCostMap.getOrDefault(next, Integer.MAX_VALUE)) return; // never improves on what's already known there

            instructions.put(next, new FlowStep(next, matching.action(), current));
            orderedSteps.add(new FlowStep(next, matching.action(), current));
            totalCost = candidateCost;
            endPos = next;
            current = next;

            if (matching.action() == PathAction.WALK) break;
        }

        if (orderedSteps.isEmpty()) return;

        LOGGER.debug("[Pathfinder] Successful Macro Line built from {} to {} (Cost: {})",
                anchorPos.toShortString(), endPos.toShortString(), totalCost);

        // orderedSteps is recorded walking FROM anchorPos (the target side, already reached by
        // Dijkstra flooding backward from the goal) OUT to endPos (the mob's side, discovered
        // last). Passing that order unreversed with buildOrderAnchor=anchorPos would make
        // nextUnbuiltInstruction() (which just returns build order's first unbuilt entry) always
        // prefer whichever step is nearest the TARGET, never the one nearest the mob. But endPos IS
        // this project's entryPos - "the block where rats enter this project" - so for a mob to
        // physically climb/cross from there, the step nearest entryPos must be built FIRST.
        // Reversing here mirrors RegionGraph.registerConnector's own "towardA" direction (a
        // connector entered from its far side), which already needs and gets this exact treatment
        // for the identical reason.
        List<FlowStep> buildOrderSteps = new ArrayList<>(orderedSteps);
        Collections.reverse(buildOrderSteps);
        // The reversed list's first entry is this trace's own terminal step AT endPos - needed only
        // to seed planSteps' walking-facing computation for the step after it, not real work (its
        // own action is WALK: nothing to build once a trace terminates naturally). Left in place,
        // its position would equal buildOrderAnchor (both endPos), making tryWiden's dirFrom/dirTo
        // the SAME point - a zero direction vector that breaks auto-widening. Drop it; planSteps
        // still starts walking from buildOrderAnchor=endPos, so the first REAL step's facing is
        // unaffected.
        if (buildOrderSteps.get(0).action() == PathAction.WALK) {
            buildOrderSteps.remove(0);
        }

        // An anchor can itself already be a locked cell of an active project - not just that
        // project's own entryPos (see the self-collision guard above `reenteredProject`), but any
        // interior cell, including a region's own nexus/target position, which
        // FlowFieldCalculator.startCalculation seeds onto calcQueue unconditionally regardless of
        // lock state. If a genuinely-walkable cell sits one step away, the trace above terminates
        // in a single WALK hop immediately - and after the leading-WALK-drop just above,
        // buildOrderSteps ends up EMPTY: a "project" with nothing to build. Committing it anyway
        // still writes its one instructions() entry into nextInstructionMap with no cost comparison
        // (see this class's own comment on FlowFieldCalculator's cycle-risk), silently overwriting
        // whatever that far cell already had with a brand-new instruction pointing straight back at
        // this anchor - which, paired with the anchor's own pre-existing instruction pointing
        // forward at that same far cell, forms a direct mutual 2-cycle. A candidate with an empty
        // build order does no useful work and exists only to overwrite something real - discard it
        // before it can.
        if (buildOrderSteps.isEmpty()) return;

        candidateProjects.add(new SiegeProject(instructions, buildOrderSteps, endPos, endPos, totalCost, UUID.randomUUID()));
        lastPassCandidatesGenerated++;

        nextCostMap.put(endPos, totalCost);
        nextInstructionMap.putAll(instructions);
        calcQueue.add(new FlowFieldCalculator.QueueNode(endPos, totalCost));
    }

    private boolean isNearExistingProject(BlockPos pos, BlockPos currentAnchor) {
        int bx = Math.floorDiv(pos.getX(), BUCKET_SIZE);
        int by = Math.floorDiv(pos.getY(), BUCKET_SIZE);
        int bz = Math.floorDiv(pos.getZ(), BUCKET_SIZE);

        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    List<BlockPos> bucket = plannedProjectBuckets.get(BlockPos.asLong(bx + dx, by + dy, bz + dz));
                    if (bucket == null) continue;

                    for (BlockPos existing : bucket) {
                        if (existing.equals(currentAnchor)) continue;
                        if (existing.distSqr(pos) < PROXIMITY_RADIUS_SQR) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static long bucketKeyFor(BlockPos pos) {
        return BlockPos.asLong(
                Math.floorDiv(pos.getX(), BUCKET_SIZE),
                Math.floorDiv(pos.getY(), BUCKET_SIZE),
                Math.floorDiv(pos.getZ(), BUCKET_SIZE));
    }
}
