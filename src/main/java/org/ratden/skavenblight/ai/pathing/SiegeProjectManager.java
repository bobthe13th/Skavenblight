package org.ratden.skavenblight.ai.pathing;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;

import java.util.*;

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

    private final List<SiegeProject> activeProjects = new ArrayList<>();
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

    private final TerrainEvaluator terrainEvaluator;
    private final SiegeLineTracer lineTracer;

    public SiegeProjectManager(TerrainEvaluator terrainEvaluator) {
        this.terrainEvaluator = terrainEvaluator;
        this.lineTracer = new SiegeLineTracer(terrainEvaluator);
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
                                     Map<BlockPos, SiegeNode> nextInstructionMap) {
        plannedProjectBuckets.clear();
        lockedPositions.clear();
        candidateProjects.clear();
        macroEvaluationCount = 0;
        lineStepsEvaluated = 0;
        lastPassCandidatesGenerated = 0;
        lastPassCandidatesSurvived = 0;

        activeProjects.removeIf(project -> project.isCompleted(terrain, terrainEvaluator));

        for (SiegeProject project : activeProjects) {
            for (Map.Entry<BlockPos, SiegeNode> entry : project.getRemainingInstructions(terrain, terrainEvaluator).entrySet()) {
                BlockPos pos = entry.getKey();
                lockedPositions.add(pos);
                nextInstructionMap.put(pos, entry.getValue());
            }

            BlockPos entry = project.getEntryPos();
            int entryCost = project.getExpectedEntryCost();

            // entryPos is, by construction, the far side of a gap the ordinary Dijkstra flood
            // cannot independently cross while this project's own interior cells are still
            // unbuilt/locked - so once TerrainEvaluator.isActionCompleted correctly reports its own
            // WALK action as already done (see that class's own doc), the getRemainingInstructions
            // loop above stops re-seeding it, since nothing needs BUILDING there. But nothing needs
            // building there is not the same as nothing needs to ROUTE there: without this,
            // entryPos gets no instruction at all on the very next pass, stranding a mob standing on
            // it with no way to be told to walk onward into the project's still-unbuilt interior.
            // putIfAbsent so a genuinely cheaper real route the ordinary flood found elsewhere in
            // this SAME pass (or the project's own BUILD_LANDING case, already put above) is never
            // overwritten - this is a fallback for "nothing else provides an instruction here", not
            // a preference over one that already exists. Guarded on presence: a route-tree
            // connector project (RegionGraph.registerConnector's 6-arg constructor) deliberately
            // does NOT key its own entryPos in `instructions` - "the far region's own pass already
            // covers it" (see that method's own doc) - so there's nothing to fall back to there,
            // and inserting a literal null would NPE the first caller that reads it back out.
            SiegeNode ownEntryInstruction = project.getInstructions().get(entry);
            if (ownEntryInstruction != null) {
                nextInstructionMap.putIfAbsent(entry, ownEntryInstruction);
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
            // exists to prevent.
            BlockPos exit = project.getExitPos();
            if (exit != null) {
                nextInstructionMap.putIfAbsent(exit, new SiegeNode(exit, SiegeNode.SiegeAction.WALK));
            }
        }
    }

    public void finalizeCandidateProjects(Map<BlockPos, Integer> finalCostMap, Map<BlockPos, SiegeNode> finalInstructionMap) {
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
                                      Map<BlockPos, SiegeNode> nextInstructionMap) {

        // A region with a route-tree-assigned parent connector (see setMaxCandidateProjectLength's
        // own doc) gets a short local-gap budget specifically so its OWN reactive search never
        // substitutes for the connector graph's job of long-range connectivity. That intent held
        // for a single macro-project line, but not for a CHAIN of them: evaluateSingleLine writes
        // a successful line's own endPos directly back onto this SAME pass's calcQueue (see its own
        // body), so FlowFieldCalculator's Dijkstra loop pops it as an ordinary frontier node and
        // fires evaluateMacroProjects again from there - a synthetic BUILD_LANDING always "hits an
        // obstacle" (see FlowFieldCalculator's isPlannedLanding check), so this repeats. Each such
        // chained call previously got a completely fresh maxCandidateProjectLength budget, letting
        // a region capped to (say) 6 blocks discover a path far longer than 6 blocks by chaining
        // several 6-block-capped lines end to end - confirmed via GameTest reaching 14 blocks
        // across 3 chained hops, all the way into a DIFFERENT region's own territory, producing a
        // flow-field cycle against that region's own route-tree-assigned connector project (see
        // docs/superpowers/plans/2026-08-01-flow-field-planning-gap-fix.md for the full trace).
        // Refusing to continue a chain - rather than tracking a cumulative step budget across the
        // 14-direction fan-out below, which would also restrict how far a single legitimate
        // obstacle's own multi-direction search can reach in one call - keeps a capped region's
        // search to "one obstacle, one line, one hop": exactly the "short local-gap" scope the cap
        // was always meant to provide. Regions with no parent connector yet (the default, uncapped
        // budget) are unaffected - they still need to chain freely to establish their own
        // connectivity in the first place.
        SiegeNode existingInstruction = nextInstructionMap.get(anchorPos);
        boolean isChainedLanding = existingInstruction != null
                && existingInstruction.action() == SiegeNode.SiegeAction.BUILD_LANDING;
        if (isChainedLanding && this.maxCandidateProjectLength < DEFAULT_MAX_CANDIDATE_PROJECT_LENGTH) {
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
        // (getValidOrthogonalSteps' MINE/1-block-drop steps, or another nearby anchor's own
        // fanned line landing on this same position) already discovers and repairs it safely
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

    private void evaluateSingleLine(TerrainAccess terrain, BlockPos anchorPos, FlowFieldState state, int anchorCost,
                                    int dx, int dy, int dz,
                                    PriorityQueue<FlowFieldCalculator.QueueNode> calcQueue,
                                    Map<BlockPos, Integer> nextCostMap,
                                    Map<BlockPos, SiegeNode> nextInstructionMap) {

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
        SiegeLineTracer.TraceResult result = lineTracer.trace(terrain, anchorPos, dx, dy, dz, state.getTargetPos(), anchorCost,
                pos -> terrainEvaluator.isOutOfBounds(terrain, pos, state) || isNearExistingProject(pos, anchorPos)
                        || (reenteredProject.isPresent() && lockedPositions.contains(pos)
                                && reenteredProject.get().getInstructions().containsKey(pos)),
                pos -> nextCostMap.getOrDefault(pos, Integer.MAX_VALUE),
                this.maxCandidateProjectLength);

        if (!result.completed() || result.instructions().isEmpty()) return;

        BlockPos endPos = result.endPos();
        int totalCost = result.totalCost();

        LOGGER.debug("[Pathfinder] Successful Macro Line built from {} to {} (Cost: {})",
                anchorPos.toShortString(), endPos.toShortString(), totalCost);

        // result.orderedSteps() is recorded walking FROM anchorPos (the target side, already
        // reached by Dijkstra flooding backward from the goal) OUT to endPos (the mob's side,
        // discovered last) - see SiegeLineTracer.trace's own doc. Passing that order unreversed
        // with buildOrderAnchor=anchorPos would make nextUnbuiltInstruction() (which just returns
        // build order's first unbuilt entry) always prefer whichever step is nearest the TARGET,
        // never the one nearest the mob. But endPos IS this project's entryPos - "the block where
        // rats enter this project" (see SiegeProject's own field doc) - so for a mob to physically
        // climb/cross from there, the step nearest entryPos must be built FIRST. Reversing here and
        // re-anchoring on endPos mirrors RegionGraph.registerConnector's own "towardA" direction
        // (a connector entered from its far side), which already needs and gets this exact
        // treatment for the identical reason.
        List<SiegeNode> buildOrderSteps = new ArrayList<>(result.orderedSteps());
        Collections.reverse(buildOrderSteps);
        // The reversed list's first entry is SiegeLineTracer.trace's own terminal step AT endPos -
        // needed only to seed planSteps' walking-facing computation for the step after it, not real
        // work (its own action is WALK: nothing to build once a trace terminates naturally). Left
        // in place, its position would equal buildOrderAnchor (both endPos), making
        // tryWiden's dirFrom/dirTo (widenAnchor vs buildOrder.get(0)) the SAME point - a zero
        // direction vector that breaks auto-widening for every BUILD_STAIR/BUILD_BRIDGE reactive
        // project. Drop it; planSteps still starts walking from buildOrderAnchor=endPos, so the
        // first REAL step's facing is unaffected.
        if (!buildOrderSteps.isEmpty() && buildOrderSteps.get(0).action() == SiegeNode.SiegeAction.WALK) {
            buildOrderSteps.remove(0);
        }

        // An anchor can itself already be a locked cell of an active project - not just that
        // project's own entryPos (see the self-collision guard above `reenteredProject`), but any
        // interior cell, including a region's own nexus/target position, which
        // FlowFieldCalculator.startCalculation seeds onto calcQueue unconditionally regardless of
        // lock state. If a genuinely-walkable cell sits one step away, the trace above terminates
        // in a single WALK hop - SiegeLineTracer.trace's own termination condition fires
        // immediately - and after the leading-WALK-drop just above, buildOrderSteps ends up EMPTY:
        // a "project" with nothing to build. Committing it anyway still writes its one
        // instructions() entry into nextInstructionMap with no cost comparison (see this class's
        // own comment on FlowFieldCalculator's cycle-risk), silently overwriting whatever that far
        // cell already had with a brand-new instruction pointing straight back at this anchor -
        // which, paired with the anchor's own pre-existing instruction pointing forward at that
        // same far cell, forms a direct mutual 2-cycle. Confirmed live via
        // testParentRegionGetsRealInstructionsForSharedConnectorCells: the cycle-breaker resolved
        // exactly this cycle by dropping the far cell's (unlocked) entry entirely, leaving it with
        // no instruction at all. A candidate with an empty build order does no useful work and
        // exists only to overwrite something real - discard it before it can.
        if (buildOrderSteps.isEmpty()) return;

        candidateProjects.add(new SiegeProject(result.instructions(), buildOrderSteps, endPos, endPos, totalCost));
        lastPassCandidatesGenerated++;

        nextCostMap.put(endPos, totalCost);
        nextInstructionMap.putAll(result.instructions());
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
