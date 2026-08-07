package org.ratden.skavenblight.ai.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import org.ratden.skavenblight.ai.pathing.region.RegionFlowField;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A data class representing an active multi-block building or mining project.
 * This project may bridge directly to the ground, or it may be a chained segment
 * terminating in a mid-air BUILD_LANDING.
 */
public class SiegeProject {

    private final UUID id;
    private final UUID networkId;

    private final Map<BlockPos, FlowStep> instructions;
    private final BlockPos entryPos; // The block where rats enter this project (can be on ground or a mid-air landing)
    private final int expectedEntryCost; // The massive penalty cost assigned to this project
    // The block where this project hands off to whatever region/field is supposed to take over
    // once a mob finishes crossing - null when there's no separate handoff position to seed a
    // fallback for (see getExitPos's own doc for why this is nullable and what it's for).
    private final BlockPos exitPos;

    // Geometry-ordered build sequence, sourced from the chained-hop trace's own ordered steps -
    // NOT derived from `instructions` above, whose values point one step BACKWARD in trace order
    // (see this class's own Javadoc / the design doc's Background for why that convention is
    // wrong for "what to build, in what order, facing which way", and why RegionGraph's own
    // outboundInstructions/inboundInstructions already avoid it for the identical reason).
    private final List<PlannedStep> buildOrder;

    // Positions in buildOrder where PlatformInserter found a construction-type seam - see
    // PlatformInserter's own doc for why this is a side-channel set rather than a 6th PathAction.
    private final Set<BlockPos> platformPositions;

    /** The original build order's own starting anchor (the position its first step was traced
     * from) - stored so tryWiden can reconstruct the same trace direction from a shifted anchor.
     * Deliberately distinct from `entryPos` (the project's far-side entry, used for flow-field
     * routing) - see the constructor. */
    private final BlockPos widenAnchor;

    private final Set<Mob> workers = new HashSet<>();
    // Updated by nextUnbuiltInstruction()'s callers (tryRegisterWorker, tick()) each time
    // they have real terrain access; isAtCapacity() reads this cheaply for callers (AwaitFormationGoal)
    // that don't want to thread a TerrainAccess/PathStepEvaluator through just to ask "is this full".
    private int cachedEffectiveCap = Integer.MAX_VALUE;
    private int width = 1;
    private double accumulatedWork = 0.0;
    // See tick()'s own doc for why this exists. -1 can never collide with a real
    // ServerLevel.getGameTime() (which is >= 0 for the entire life of any level), so the very
    // first tick() call of a project's life always passes the guard.
    private long lastTickedGameTime = -1L;

    public SiegeProject(Map<BlockPos, FlowStep> instructions, List<FlowStep> orderedSteps, BlockPos buildOrderAnchor,
                         BlockPos entryPos, int expectedEntryCost, UUID networkId) {
        this(instructions, orderedSteps, buildOrderAnchor, entryPos, expectedEntryCost, null, networkId);
    }

    public SiegeProject(Map<BlockPos, FlowStep> instructions, List<FlowStep> orderedSteps, BlockPos buildOrderAnchor,
                         BlockPos entryPos, int expectedEntryCost, BlockPos exitPos, UUID networkId) {
        this.id = UUID.randomUUID();
        this.networkId = networkId;
        this.instructions = new HashMap<>(instructions);
        this.buildOrder = planSteps(orderedSteps, buildOrderAnchor);
        PlatformInserter.Result platformResult = PlatformInserter.insertPlatforms(this.buildOrder);
        this.platformPositions = platformResult.platformPositions();
        this.widenAnchor = buildOrderAnchor;
        this.entryPos = entryPos;
        this.expectedEntryCost = expectedEntryCost;
        this.exitPos = exitPos;
    }

    public UUID getId() {
        return this.id;
    }

    public UUID getNetworkId() {
        return this.networkId;
    }

    public boolean isCompleted(TerrainAccess terrain, PathStepEvaluator evaluator) {
        // Must check at entry.getKey() (the real position), not the stored step's own .pos() (the
        // PREDECESSOR position - see this class's own Javadoc on the anchor-ward `instructions`
        // convention). Checking the predecessor meant a fully-built project could never be detected
        // as complete - nextUnbuiltInstruction already gets this right by reconstructing from the
        // real position; this mirrors that.
        return instructions.entrySet().stream()
                .allMatch(entry -> evaluator.isActionCompleted(terrain, entry.getKey(), entry.getValue().action()));
    }

    public boolean survivedMapOverwrite(Map<BlockPos, Integer> finalCostMap, Map<BlockPos, FlowStep> finalInstructionMap) {
        // 1. ENTRY POINT VALIDATION
        // If the final Dijkstra map gave our entry point (whether ground or chained landing)
        // a cheaper cost than the project's massive penalty, a walkable highway exists! Kill the project.
        int finalCost = finalCostMap.getOrDefault(entryPos, Integer.MAX_VALUE);
        if (finalCost < expectedEntryCost) {
            return false;
        }

        // 2. Fallback check for the project blocks themselves
        return instructions.entrySet().stream().allMatch(entry -> {
            FlowStep finalStep = finalInstructionMap.get(entry.getKey());
            return finalStep != null && finalStep.action() == entry.getValue().action();
        });
    }

    public Map<BlockPos, FlowStep> getRemainingInstructions(TerrainAccess terrain, PathStepEvaluator evaluator) {
        // Same real-position fix as isCompleted() above - step.pos() is the predecessor position,
        // not pos (the map key) the action actually applies to.
        Map<BlockPos, FlowStep> remaining = new HashMap<>();
        instructions.forEach((pos, step) -> {
            if (!evaluator.isActionCompleted(terrain, pos, step.action())) {
                remaining.put(pos, step);
            }
        });
        return remaining;
    }

    public BlockPos getEntryPos() {
        return entryPos;
    }

    public int getExpectedEntryCost() {
        return expectedEntryCost;
    }

    /**
     * The position, on the FAR side of this crossing, where a mob following it hands off to
     * whatever comes next - null for projects with no distinct handoff position (e.g. a reactive
     * macro-project discovered mid-flood, whose far end is already covered by the very flood that
     * discovered it). Deliberately NOT part of {@code instructions} (see
     * SiegeProjectManager#injectActiveProjects's use of this): a self-referential fallback step
     * seeded there would make {@link #isCompleted} and {@link #survivedMapOverwrite} - which both
     * iterate {@code instructions} as their ground truth for "is this project done/still needed" -
     * see a permanently-incomplete, permanently-necessary entry that can never be satisfied by any
     * terrain change, artificially pinning this project active forever.
     */
    public BlockPos getExitPos() {
        return exitPos;
    }

    /**
     * The full instruction map this project was built with, regardless of how much of it is
     * already complete - see {@link #getRemainingInstructions} for the completion-filtered view.
     * Needed by orphaned-connector-cell coverage: every position a mob could be standing on
     * mid-crossing, not just what's left to build.
     */
    public Map<BlockPos, FlowStep> getInstructions() {
        return Collections.unmodifiableMap(instructions);
    }

    /** The build-order step at {@code pos}, or null if this project has none there - used by
     * TerritoryRegionMap's planned-cell terrain override (see TerrainSnapshot's
     * plannedStateOverride hook) to make an active project's planned final state authoritative for
     * terrain evaluation. Linear scan: a project's build order is bounded by
     * maxCandidateProjectLength (at most 32 entries), so this is cheap without needing a second,
     * map-backed index kept in sync alongside buildOrder. */
    public PlannedStep plannedStepAt(BlockPos pos) {
        return buildOrder.stream().filter(s -> s.pos().equals(pos)).findFirst().orElse(null);
    }

    /** Every position in this project's build order that PlatformInserter marked as a
     * construction-type seam - see PlatformInserter's own doc. */
    public Set<BlockPos> getPlatformPositions() {
        return Collections.unmodifiableSet(platformPositions);
    }

    /** Package-private + static for direct unit testing, mirroring FlowFieldCalculator's own
     * detectMutualCyclePositions/pickCyclePositionToDrop pattern for pure logic extracted out of
     * a Minecraft-coupled class. */
    static List<PlannedStep> planSteps(List<FlowStep> orderedSteps, BlockPos anchor) {
        List<PlannedStep> planned = new ArrayList<>(orderedSteps.size());
        BlockPos previous = anchor;
        for (FlowStep step : orderedSteps) {
            BlockPos placementPos = placementPositionFor(previous, step.pos(), step.action());
            planned.add(new PlannedStep(step.pos(), step.action(), approachFacing(previous, step.pos()), placementPos));
            previous = step.pos();
        }
        return planned;
    }

    /**
     * Where a step's block is physically placed, as distinct from {@code target} (the LOGICAL cell
     * a mob ends up standing in/on once it's crossed - see {@link PlannedStep}'s own doc). Identical
     * to {@code target} for every action except an ASCENDING {@code AIR_STAIR}/{@code CARVED_STAIR}
     * ({@code target} strictly above {@code from}, the real position this hop is approached from -
     * always a genuinely adjacent cell, one {@code PathStepEvaluator.candidateSteps} offset away in
     * every direction, regardless of whether {@code orderedSteps} is walked forward or reversed).
     *
     * <p>Root cause this corrects (Task 21's go/no-go gate, fourth/fifth session): {@code
     * PathStepEvaluator.candidateSteps} classifies an ascending neighbor at {@code (dx, dy=+1, dz)}
     * from the approach cell, and construction used to place the stair directly there. A bottom-half
     * stair's own low tread sits at {@code cell-base + 0.5} - one full cell above the target PLUS
     * 0.5 more above the approach floor, a 1.5-block rise no jump (vanilla's own jump height is
     * ~1.25 blocks) can ever cross, regardless of momentum. A real vanilla staircase places each
     * stair at the SAME cell level as its approach floor - crossing that one block delivers the full
     * 1.0 rise via the stair's own tread+riser geometry (two ~0.5 auto-steps), landing the mob
     * exactly one cell higher and correctly set up for an identically-placed next stair. Shifting
     * placement down by one cell (to the approach cell's own level, {@code target.below()}, which
     * always shares {@code target}'s X/Z) reproduces that geometry.
     *
     * <p>Only the ASCENDING case needs the shift. For a descending hop ({@code target} below {@code
     * from}), {@code target} is already the lower of the two real cells - exactly where a stair
     * should sit for a mob walking down onto it - so no adjustment is needed; unconditionally
     * shifting both directions would place a descending stair two cells too low. Since {@code
     * candidateSteps} only ever offers {@code dy} in {-1, 0, +1} per hop, "ascending" is exactly
     * {@code target.getY() > from.getY()}, and the shift is always exactly one cell.
     *
     * <p>Deliberately does NOT touch {@code target}/{@code PlannedStep.pos()} itself: every other
     * consumer of a build-order position ({@code plannedStepAt}, {@code getBuildOrderPositions}, the
     * work-radius checks) already means "the logical cell," and {@code isActionCompleted} is checked
     * against target too - see {@code PathStepEvaluator.isActionCompleted}'s AIR_STAIR/CARVED_STAIR
     * cases, which ask "is target standable" ({@code isWalkableTerrain}) rather than "is target
     * solid," so a stair one cell below target (providing target's own standing support) still
     * correctly resolves the hop as complete without this method needing to touch the logical side
     * at all.
     */
    private static BlockPos placementPositionFor(BlockPos from, BlockPos target, PathAction action) {
        boolean isStairAction = action == PathAction.AIR_STAIR || action == PathAction.CARVED_STAIR;
        boolean ascending = target.getY() > from.getY();
        return (isStairAction && ascending) ? target.below() : target;
    }

    /** Same dx/dz-comparison logic as BuildFlowFieldGoal.computeApproachFacing, fed geometry
     * instead of a mob's live position. */
    private static Direction approachFacing(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        if (Math.abs(dx) > Math.abs(dz)) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        } else if (dz != 0) {
            return dz > 0 ? Direction.SOUTH : Direction.NORTH;
        }
        return Direction.NORTH; // pure-vertical step - facing unused (no PathAction is pure-vertical anymore).
    }

    /** All four construction actions scale their worker cap with `width` (see the auto-widening
     * design) - every action is parallel-lane-capable now, not just two of the old five. */
    static int effectiveCapFor(PathAction action, int width, int maxProjectWorkers, int workersPerWidenStep) {
        boolean widenable = action != PathAction.WALK;
        return widenable ? width * workersPerWidenStep : maxProjectWorkers;
    }

    /** The first not-yet-built step in build order, or empty if the project is fully built. */
    public Optional<PlannedStep> nextUnbuiltInstruction(TerrainAccess terrain, PathStepEvaluator evaluator) {
        for (PlannedStep step : buildOrder) {
            if (step.action() == PathAction.WALK) continue;
            if (!evaluator.isActionCompleted(terrain, step.pos(), step.action())) return Optional.of(step);
        }
        return Optional.empty();
    }

    /** Registers `mob` as a worker if there's real build work nearby (within `workRadius` of the
     * next unbuilt step) and the project isn't already at capacity - see effectiveCapFor for how
     * capacity scales with width. Idempotent: re-registering an already-registered mob succeeds
     * trivially. When a registration is rejected purely for being over cap, this retries once
     * against a freshly-widened cap (see tryWiden) before giving up - so a growing crowd of rats
     * spreads out into a new parallel lane instead of all queuing for the same single-file line. */
    public boolean tryRegisterWorker(Mob mob, TerrainAccess terrain, PathStepEvaluator evaluator, double workRadius,
                                      int maxProjectWorkers, int workersPerWidenStep) {
        Optional<PlannedStep> next = nextUnbuiltInstruction(terrain, evaluator);
        if (next.isEmpty()) return false;
        PlannedStep step = next.get();
        if (!mob.blockPosition().closerThan(step.pos(), workRadius)) return false;

        this.cachedEffectiveCap = effectiveCapFor(step.action(), this.width, maxProjectWorkers, workersPerWidenStep);
        if (workers.contains(mob)) return true;

        if (workers.size() >= this.cachedEffectiveCap) {
            if (!tryWiden(step.action(), terrain, evaluator)) return false;
            this.cachedEffectiveCap = effectiveCapFor(step.action(), this.width, maxProjectWorkers, workersPerWidenStep);
            if (workers.size() >= this.cachedEffectiveCap) return false;
        }

        workers.add(mob);
        return true;
    }

    /**
     * Non-mutating "would {@link #tryRegisterWorker} succeed for {@code mob} right now?" - the same
     * radius and capacity preconditions, in the same order, with nothing written and no widen
     * attempted. Exists for AbstractSiegeProjectGoal's canUse()/canContinueToUse(), which need to
     * decide whether to take (and keep) the mob's MOVE flag BEFORE start() gets a chance to try the
     * real registration.
     *
     * <p>Radius is checked BEFORE the already-registered short-circuit, exactly as
     * tryRegisterWorker does, so this doubles as the "has this worker drifted too far to still be
     * working here?" test canContinueToUse needs.
     *
     * <p>Deliberately answers TRUE for an over-cap project that is still eligible to widen, rather
     * than running the widen itself: {@link #tryWiden} runs a real chained-hop trace, and canUse()
     * is called for every rat on every tick, so attempting it here would turn a demand-bounded
     * retry into a per-rat-per-tick trace. The residual imprecision is narrow and one-directional -
     * a widen-eligible project whose trace actually fails (terrain won't take another lane) still
     * reports true here and then fails in start(), the one case where the old MOVE-holding behavior
     * survives. Every other rejection reason is now caught before the goal ever starts.
     *
     * <p>The already-registered short-circuit runs BEFORE the radius check, not after: a mining
     * action (TUNNEL/CARVED_STAIR) advances its own "next unbuilt step" out from under a stationary
     * worker as soon as each hop completes, since - unlike AIR_STAIR, where the mob physically climbs
     * each finished stair via FollowFlowFieldGoal and so closes distance every hop - mining carves
     * space ahead of the mob's standing cell without ever moving it. Two or three consecutive hops
     * can put the new next-step comfortably outside workRadius (3.5) of a mob that never left its
     * original position, and canContinueToUse() routes through this same method - so checking radius
     * first was dropping mid-chain workers the instant the frontier outran them, permanently
     * orphaning the project's tick() calls. Registration (tryRegisterWorker, and canUse() since
     * stop()/unregisterWorker always empties `workers` before canUse() is next consulted) still
     * checks radius first via its own, separate call site, so admission is unaffected - this only
     * loosens continuation for a mob already doing the work.
     */
    public boolean canAcceptWorker(Mob mob, TerrainAccess terrain, PathStepEvaluator evaluator, double workRadius,
                                    int maxProjectWorkers, int workersPerWidenStep) {
        Optional<PlannedStep> next = nextUnbuiltInstruction(terrain, evaluator);
        if (next.isEmpty()) return false;
        PlannedStep step = next.get();
        if (workers.contains(mob)) return true;
        if (!mob.blockPosition().closerThan(step.pos(), workRadius)) return false;
        if (workers.size() < effectiveCapFor(step.action(), this.width, maxProjectWorkers, workersPerWidenStep)) return true;
        return isWidenEligible(step.action());
    }

    /** tryWiden's own eligibility preamble, extracted so {@link #canAcceptWorker} can ask the same
     * question without tracing anything. Says nothing about whether the trace itself would
     * succeed - only whether attempting it is allowed at all. */
    private boolean isWidenEligible(PathAction currentAction) {
        boolean widenable = currentAction != PathAction.WALK;
        return widenable && this.width < org.ratden.skavenblight.Config.maxProjectWidth && !buildOrder.isEmpty();
    }

    /** Attempts to trace one more parallel lane, alternating sides on successive widens, when a
     * construction-type project is at capacity - see the design doc's Auto-widening section.
     * No-ops (returns false) once maxProjectWidth is reached, or when the trace itself fails
     * (terrain doesn't support it, out of bounds, too much mining) - a failed attempt leaves width
     * unchanged and is simply retried on the next rejected registration, never per-tick, bounding
     * retry frequency to actual demand. */
    private boolean tryWiden(PathAction currentAction, TerrainAccess terrain, PathStepEvaluator evaluator) {
        if (!isWidenEligible(currentAction)) return false;

        PlannedStep first = buildOrder.get(0);
        // Perpendicular horizontal axis to the trace direction, alternating sides per widen: even
        // widths go one way, odd the other, so the structure grows outward on both sides. The
        // direction vector is always (original first step - original anchor), NEVER derived from
        // buildOrder.get(1): after any widen has already happened, buildOrder.get(1) is whatever
        // that widen appended (a sideways lane), not "the original trace's second step" - branching
        // on buildOrder.size() to pick between the two would silently re-derive the direction from
        // the wrong pair of points on the second and later widens, since buildOrder keeps growing
        // with each one. `widenAnchor` and `buildOrder.get(0)` are both stable for the project's
        // entire life (new lanes are only ever appended, never inserted before index 0, and
        // `widenAnchor` never changes), so this is the only pairing that stays correct across
        // repeated widens.
        BlockPos dirFrom = this.entryAnchorForWidenTrace();
        BlockPos dirTo = first.pos();
        int dx = dirTo.getX() - dirFrom.getX();
        int dz = dirTo.getZ() - dirFrom.getZ();
        int perpX = -dz;
        int perpZ = dx;
        int side = (this.width % 2 == 0) ? 1 : -1;
        BlockPos offset = new BlockPos(perpX * side, 0, perpZ * side);

        BlockPos newAnchor = this.entryAnchorForWidenTrace().offset(offset.getX(), 0, offset.getZ());
        int traceDy = first.pos().getY() - this.entryAnchorForWidenTrace().getY();
        int traceDx = Integer.signum(first.pos().getX() - this.entryAnchorForWidenTrace().getX());
        int traceDz = Integer.signum(first.pos().getZ() - this.entryAnchorForWidenTrace().getZ());

        List<FlowStep> traced = traceChainedHops(terrain, evaluator, newAnchor, traceDx, Integer.signum(traceDy), traceDz,
                buildOrder.size());
        if (traced == null || traced.isEmpty()) return false;

        buildOrder.addAll(planSteps(traced, newAnchor));
        this.width++;
        return true;
    }

    /**
     * Walks a FIXED (dx, dy, dz) direction one hop at a time from {@code start}, classifying each
     * hop via {@link PathStepEvaluator#candidateSteps} (the same per-neighbor classification the
     * main flood uses), until either a genuinely walkable cell is reached or {@code maxHops} is
     * exhausted - both are SUCCESS (matching old SiegeLineTracer.trace's own contract: hitting its
     * length cap returned {@code completed=true}, appending a synthetic BUILD_LANDING; that action
     * type is gone, so this simply stops collecting instead of synthesizing a replacement marker,
     * one cell short of where the old landing would have sat - see this task's own commit message).
     * Returns null (real failure) only when candidateSteps offers no step at all at the exact fixed
     * offset from the current cursor - the equivalent of the old tracer's aborted() cases
     * (out-of-bounds/invalid). Replaces the old SiegeLineTracer.trace call this method used to
     * make: SiegeLineTracer always walked a single fixed direction the entire length (see its own
     * now-deleted doc), so filtering candidateSteps' fan-out down to the one neighbor at the exact
     * (dx, dy, dz) offset reproduces the identical fixed-direction behavior without a dedicated
     * tracer class.
     *
     * <p>Package-private + static for direct unit testing (same rationale as {@link #planSteps}/
     * {@link #effectiveCapFor}): {@link #tryWiden} is only reachable through
     * {@link #tryRegisterWorker}, which needs a real {@code Mob} - this method is the actual new
     * logic worth testing directly, without one.
     */
    static List<FlowStep> traceChainedHops(TerrainAccess terrain, PathStepEvaluator evaluator, BlockPos start,
                                            int dx, int dy, int dz, int maxHops) {
        List<FlowStep> traced = new ArrayList<>();
        BlockPos cursor = start;
        for (int hop = 0; hop < maxHops; hop++) {
            BlockPos neighbor = cursor.offset(dx, dy, dz);
            List<PathStepEvaluator.EvaluatedStep> steps = evaluator.candidateSteps(
                    terrain, cursor, Collections.emptySet(), pos -> false);
            PathStepEvaluator.EvaluatedStep matching = steps.stream()
                    .filter(s -> s.pos().equals(neighbor)).findFirst().orElse(null);
            if (matching == null) return null; // no valid step in this exact direction - real trace failure

            if (matching.action() == PathAction.WALK) {
                return traced; // reached genuinely walkable ground - trace complete
            }

            traced.add(new FlowStep(matching.pos(), matching.action(), cursor));
            cursor = neighbor;
        }
        return traced; // maxHops exhausted while still legitimately building - success, same as the old cap
    }

    private BlockPos entryAnchorForWidenTrace() {
        return this.widenAnchor;
    }

    public int getWidth() {
        return this.width;
    }

    /** Test-support accessor: every position currently in build order (original steps first, then
     * each widen's appended lane, in the order they were added) - lets tests verify tryWiden's
     * geometry actually lands on genuinely different positions across successive widens, without
     * depending on real terrain's action classification at each lane, which calling tick() and
     * asserting on placed blocks would. */
    public List<BlockPos> getBuildOrderPositions() {
        return buildOrder.stream().map(PlannedStep::pos).toList();
    }

    /** Persistence accessor (SiegeProjectStore): the full ordered build list, unlike
     * {@link #getBuildOrderPositions}'s positions-only view. */
    public List<PlannedStep> getBuildOrder() {
        return List.copyOf(buildOrder);
    }

    /** Persistence accessor (SiegeProjectStore): the original trace anchor tryWiden reconstructs
     * its direction from - see that field's own doc. */
    public BlockPos getWidenAnchor() {
        return this.widenAnchor;
    }

    /** Persistence accessor (SiegeProjectStore): the game-time stamp tick()'s idempotency guard
     * last saw - see that field's own doc for why a restored world must clamp this on load. */
    public long getLastTickedGameTime() {
        return this.lastTickedGameTime;
    }

    /** Test-support accessor (same role as {@link #getBuildOrderPositions}): build progress banked
     * but not yet spent on a placement. Exists so a test can assert tick()'s accumulation SCALING
     * directly - N workers must add {@code min(N, cap) * workPerRatPerTick} per game tick, however
     * many separate goal instances call tick() within that tick (see tick()'s own idempotency doc)
     * - rather than inferring it from how many blocks happened to get placed. Public rather than
     * package-private because the GameTests that need it live in
     * org.ratden.skavenblight.gametest, not this package. */
    public double getAccumulatedWork() {
        return this.accumulatedWork;
    }

    public void unregisterWorker(Mob mob) {
        workers.remove(mob);
    }

    /** Cheap, terrain-free capacity check for callers (AwaitFormationGoal) that just need "is
     * there room here right now" without re-deriving the next unbuilt step - reads whatever
     * tryRegisterWorker/tick() last computed, so it can lag by up to one tick. */
    public boolean isAtCapacity() {
        return workers.size() >= this.cachedEffectiveCap;
    }

    /** Advances this project's construction by one GAME tick: adds work proportional to registered
     * worker count (capped), then places as many now-affordable not-yet-built steps as the
     * accumulated work covers, in build order. A project that's fully built is a no-op - callers
     * don't need to check first.
     *
     * <p><b>Idempotent per game tick.</b> Every registered worker runs its OWN
     * AbstractSiegeProjectGoal instance, and vanilla's GoalSelector ticks every running goal once
     * per server tick - so with W workers registered on one project, this method is called W times
     * within a single game tick, all from AbstractSiegeProjectGoal.tick() (its only production
     * call site). Without this guard, each of those W calls added
     * {@code min(W, cap) * workPerRatPerTick}, making real per-game-tick progress
     * {@code W * min(W, cap) * workPerRatPerTick} - quadratic in worker count, and with the cap
     * (the design's ONLY bound on build rate) rendered meaningless past a handful of rats. The
     * guard makes every call after the first in the same game tick a complete no-op: no worker
     * pruning, no accumulation, no placement, since all of that already ran this tick for whichever
     * goal happened to call first. */
    public void tick(ServerLevel level, RegionFlowField flowField, PathStepEvaluator evaluator) {
        long now = level.getGameTime();
        if (now == this.lastTickedGameTime) return;
        this.lastTickedGameTime = now;

        workers.removeIf(mob -> !mob.isAlive());

        LiveTerrainAccess terrain = new LiveTerrainAccess(level);
        Optional<PlannedStep> next = nextUnbuiltInstruction(terrain, evaluator);
        if (next.isEmpty()) return;

        this.cachedEffectiveCap = effectiveCapFor(next.get().action(), this.width,
                org.ratden.skavenblight.Config.maxProjectWorkers, org.ratden.skavenblight.Config.workersPerWidenStep);

        int activeWorkers = Math.min(workers.size(), this.cachedEffectiveCap);
        if (activeWorkers == 0) return;

        this.accumulatedWork += activeWorkers * org.ratden.skavenblight.Config.workPerRatPerTick;

        PlannedStep step = next.get();
        while (step != null) {
            int cost = evaluator.baseCostFor(step.action());
            if (step.action() == PathAction.TUNNEL || step.action() == PathAction.CARVED_STAIR) {
                cost += evaluator.miningCost(terrain, step.pos());
            }
            if (this.accumulatedWork < cost) break;

            this.accumulatedWork -= cost;
            // supportSolidAtClaim=false: this flag (see SiegeInteractionHandler.constructSiegeBlock's
            // own doc, which explicitly says it's "never true for a macro SiegeProject's next unbuilt
            // chain step") guards the old per-mob claim-then-execute race window - a real multi-tick
            // gap between "support looked solid when claimed" and "support got mined out from under
            // it before execute() ran". A hardcoded `true` here was wrong, not
            // merely redundant: for a diagonal chain (the exact case this method exists to build),
            // step N's OWN pos().below() is essentially never the previous step's position (a
            // +1X/+1Y trace has chain[N].below() = (x+N, y+N-1, z) while chain[N-1] = (x+N-1, y+N-1,
            // z) - different cells), so it is normal for pos().below() to still be open air right up
            // to and through this exact placement. Passing `true` made
            // SiegeInteractionHandler.constructSiegeBlock's own no-support guard fire on literally
            // the first step of every such chain, forever refusing to place it.
            // A placed stair/pillar is self-supporting for pathing purposes regardless of what ends
            // up below it once built - there is no floating-step risk here for tick() to guard
            // against in the first place.
            //
            // targetPos: a PLATFORM seam (see PlatformInserter) clears a staging area keyed by the
            // LOGICAL build-order position (a mob's own standing/turning cell), unaffected by the
            // placement/logical split below - use step.pos() for those. Every other step passes
            // step.placementPos(), which differs from step.pos() only for an ascending AIR_STAIR/
            // CARVED_STAIR (see PlannedStep's own doc and SiegeProject.placementPositionFor) - the
            // physical block goes one cell lower than the logical cell a mob ends up standing in.
            boolean isPlatform = this.platformPositions.contains(step.pos());
            BlockPos targetPos = isPlatform ? step.pos() : step.placementPos();
            SiegeInteractionHandler.constructSiegeBlock(level, targetPos, step.facing(), step.action(), flowField, null, false,
                    isPlatform);
            // Real placements never fire NeoForge's BlockEvent (SiegeInteractionHandler uses
            // level.setBlockAndUpdate/destroyBlock directly - see RegionFlowField#forceRecalculation's
            // own doc), so nothing else marks this region dirty. Without this call, region membership
            // never grows to include the newly-built cell (FlowFieldState.isOutOfBounds keeps
            // rejecting it against the region's stale, pre-construction bounds forever), silently
            // stalling every chain after its first placed step - confirmed via a full GameTest suite
            // run showing 0 stairs built across every StaircaseSiegeGroupGameTests scenario before
            // this fix. Dirty the position that actually changed (targetPos), matching whatever
            // constructSiegeBlock was just called with above.
            flowField.forceRecalculation(targetPos);

            Optional<PlannedStep> following = nextUnbuiltInstruction(terrain, evaluator);
            step = following.orElse(null);
        }
    }

}
