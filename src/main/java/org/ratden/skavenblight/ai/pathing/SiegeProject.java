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

/**
 * A data class representing an active multi-block building or mining project.
 * This project may bridge directly to the ground, or it may be a chained segment
 * terminating in a mid-air BUILD_LANDING.
 */
public class SiegeProject {

    private final Map<BlockPos, SiegeNode> instructions;
    private final BlockPos entryPos; // The block where rats enter this project (can be on ground or a mid-air landing)
    private final int expectedEntryCost; // The massive penalty cost assigned to this project
    // The block where this project hands off to whatever region/field is supposed to take over
    // once a mob finishes crossing - null when there's no separate handoff position to seed a
    // fallback for (see getExitPos's own doc for why this is nullable and what it's for).
    private final BlockPos exitPos;

    // Geometry-ordered build sequence, sourced from SiegeLineTracer.TraceResult.orderedSteps() -
    // NOT derived from `instructions` above, whose values point one step BACKWARD in trace order
    // (see this class's own Javadoc / the design doc's Background for why that convention is
    // wrong for "what to build, in what order, facing which way", and why RegionGraph's own
    // outboundInstructions/inboundInstructions already avoid it for the identical reason).
    private final List<PlannedStep> buildOrder;

    /** The original build order's own starting anchor (the position its first step was traced
     * from) - stored so tryWiden can reconstruct the same trace direction from a shifted anchor.
     * Deliberately distinct from `entryPos` (the project's far-side entry, used for flow-field
     * routing) - see the constructor. */
    private final BlockPos widenAnchor;

    private final Set<Mob> workers = new HashSet<>();
    // Updated by nextUnbuiltInstruction()'s callers (tryRegisterWorker, Task 4's tick()) each time
    // they have real terrain access; isAtCapacity() reads this cheaply for callers (AwaitFormationGoal)
    // that don't want to thread a TerrainAccess/TerrainEvaluator through just to ask "is this full".
    private int cachedEffectiveCap = Integer.MAX_VALUE;
    private int width = 1;
    private double accumulatedWork = 0.0;
    // See tick()'s own doc for why this exists. -1 can never collide with a real
    // ServerLevel.getGameTime() (which is >= 0 for the entire life of any level), so the very
    // first tick() call of a project's life always passes the guard.
    private long lastTickedGameTime = -1L;

    public SiegeProject(Map<BlockPos, SiegeNode> instructions, List<SiegeNode> orderedSteps, BlockPos buildOrderAnchor,
                         BlockPos entryPos, int expectedEntryCost) {
        this(instructions, orderedSteps, buildOrderAnchor, entryPos, expectedEntryCost, null);
    }

    public SiegeProject(Map<BlockPos, SiegeNode> instructions, List<SiegeNode> orderedSteps, BlockPos buildOrderAnchor,
                         BlockPos entryPos, int expectedEntryCost, BlockPos exitPos) {
        this.instructions = new HashMap<>(instructions);
        this.buildOrder = planSteps(orderedSteps, buildOrderAnchor);
        this.widenAnchor = buildOrderAnchor;
        this.entryPos = entryPos;
        this.expectedEntryCost = expectedEntryCost;
        this.exitPos = exitPos;
    }

    public boolean isCompleted(TerrainAccess terrain, TerrainEvaluator evaluator) {
        // Must check at entry.getKey() (the real position), not the stored node's own .pos()
        // (the PREDECESSOR position - see this class's own Javadoc on the anchor-ward
        // `instructions` convention). Checking the predecessor meant a fully-built project could
        // never be detected as complete - see nextUnbuiltInstruction, which already gets this
        // right by reconstructing a SiegeNode from the real position.
        return instructions.entrySet().stream()
                .allMatch(entry -> evaluator.isActionCompleted(terrain, new SiegeNode(entry.getKey(), entry.getValue().action())));
    }

    public boolean survivedMapOverwrite(Map<BlockPos, Integer> finalCostMap, Map<BlockPos, SiegeNode> finalInstructionMap) {
        // 1. ENTRY POINT VALIDATION
        // If the final Dijkstra map gave our entry point (whether ground or chained landing)
        // a cheaper cost than the project's massive penalty, a walkable highway exists! Kill the project.
        int finalCost = finalCostMap.getOrDefault(entryPos, Integer.MAX_VALUE);
        if (finalCost < expectedEntryCost) {
            return false;
        }

        // 2. Fallback check for the project blocks themselves
        return instructions.entrySet().stream().allMatch(entry -> {
            SiegeNode finalNode = finalInstructionMap.get(entry.getKey());
            return finalNode != null && finalNode.action() == entry.getValue().action();
        });
    }

    public Map<BlockPos, SiegeNode> getRemainingInstructions(TerrainAccess terrain, TerrainEvaluator evaluator) {
        // Same real-position fix as isCompleted() above - node.pos() is the predecessor position,
        // not pos (the map key) the action actually applies to.
        Map<BlockPos, SiegeNode> remaining = new HashMap<>();
        instructions.forEach((pos, node) -> {
            if (!evaluator.isActionCompleted(terrain, new SiegeNode(pos, node.action()))) {
                remaining.put(pos, node);
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
     * SiegeProjectManager#injectActiveProjects's use of this): a self-referential fallback node
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
     * Added for task-8's orphaned-connector-cell test, which needs every position a mob could be
     * standing on mid-crossing, not just what's left to build.
     */
    public Map<BlockPos, SiegeNode> getInstructions() {
        return Collections.unmodifiableMap(instructions);
    }

    /**
     * One position this project still needs to act on, in build order: the real position the
     * action applies to (unlike `instructions`' values - see this class's own background doc),
     * the action, and the facing derived purely from trace geometry (predecessor step -> this
     * step), never from any mob's position - so placement can be centralized and driven by
     * whichever/however many workers are registered, not tied to whoever happens to execute it.
     */
    record PlannedStep(BlockPos pos, SiegeNode.SiegeAction action, Direction facing) {}

    /** Package-private + static for direct unit testing, mirroring FlowFieldCalculator's own
     * detectMutualCyclePositions/pickCyclePositionToDrop pattern for pure logic extracted out of
     * a Minecraft-coupled class. */
    static List<PlannedStep> planSteps(List<SiegeNode> orderedSteps, BlockPos anchor) {
        List<PlannedStep> planned = new ArrayList<>(orderedSteps.size());
        BlockPos previous = anchor;
        for (SiegeNode step : orderedSteps) {
            planned.add(new PlannedStep(step.pos(), step.action(), approachFacing(previous, step.pos())));
            previous = step.pos();
        }
        return planned;
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
        return Direction.NORTH; // pure-vertical step (BUILD_PILLAR/SPIRAL/LADDER) - facing unused for these.
    }

    /** BUILD_STAIR/BUILD_BRIDGE scale their worker cap with `width` (see the auto-widening design);
     * every other action type gets a flat cap regardless of `width`. */
    static int effectiveCapFor(SiegeNode.SiegeAction action, int width, int maxProjectWorkers, int workersPerWidenStep) {
        boolean widenable = action == SiegeNode.SiegeAction.BUILD_STAIR || action == SiegeNode.SiegeAction.BUILD_BRIDGE;
        return widenable ? width * workersPerWidenStep : maxProjectWorkers;
    }

    /** The first not-yet-built step in build order, or empty if the project is fully built OR
     * currently blocked on an INCOMPLETE MINE step (handled entirely by the old per-rat
     * SmartBreachGoal/flowField.tryClaimTarget path - out of scope for this mechanism; skipping
     * PAST an unmined obstacle to a build step beyond it would be physically wrong, since that
     * later step may depend on the obstacle already being cleared).
     *
     * <p>The completion check on the MINE branch is load-bearing, not defensive: without it this
     * method aborted on the mere PRESENCE of a MINE step anywhere in the build order, so once a
     * project's traced line contained one, every build step after it was unreachable forever -
     * even after SmartBreachGoal had actually cleared the obstacle in the real world. That is a
     * permanent project stall, and it silently contradicted this method's own documented contract
     * ("blocked on an incomplete MINE step"). Found by the whole-branch review; see
     * nextUnbuiltInstructionContinuesPastAnAlreadyClearedMineStep for the mirror-case test. */
    public Optional<PlannedStep> nextUnbuiltInstruction(TerrainAccess terrain, TerrainEvaluator evaluator) {
        for (PlannedStep step : buildOrder) {
            if (step.action() == SiegeNode.SiegeAction.WALK || step.action() == SiegeNode.SiegeAction.LEAP) continue;
            if (step.action() == SiegeNode.SiegeAction.MINE) {
                if (!evaluator.isActionCompleted(terrain, new SiegeNode(step.pos(), step.action()))) return Optional.empty();
                continue;
            }
            if (!evaluator.isActionCompleted(terrain, new SiegeNode(step.pos(), step.action()))) return Optional.of(step);
        }
        return Optional.empty();
    }

    /** Registers `mob` as a worker if there's real build work nearby (within `workRadius` of the
     * next unbuilt step) and the project isn't already at capacity - see effectiveCapFor for how
     * capacity scales with width for BUILD_STAIR/BUILD_BRIDGE. Idempotent: re-registering an
     * already-registered mob succeeds trivially. When a registration is rejected purely for being
     * over cap, this retries once against a freshly-widened cap (see tryWiden) before giving up -
     * so a growing crowd of rats spreads out into a new parallel lane instead of all queuing for
     * the same single-file line. */
    public boolean tryRegisterWorker(Mob mob, TerrainAccess terrain, TerrainEvaluator evaluator, double workRadius,
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
     * real registration. Without it, canUse() only checked "does some project exist here", so a rat
     * whose registration then failed in start() sat holding {MOVE, LOOK} at priority 6 doing
     * literally nothing (tick() returns immediately with no registered project), permanently
     * starving AwaitFormationGoal (8) and FollowFlowFieldGoal (9) of the MOVE flag they need - and
     * making AwaitFormationGoal's own at-capacity mechanism unreachable whenever ANY project
     * existed near a rat. Found by the whole-branch review.
     *
     * <p>Radius is checked BEFORE the already-registered short-circuit, exactly as
     * tryRegisterWorker does, so this doubles as the "has this worker drifted too far to still be
     * working here?" test canContinueToUse needs.
     *
     * <p>Deliberately answers TRUE for an over-cap project that is still eligible to widen, rather
     * than running the widen itself: {@link #tryWiden} runs a real {@link SiegeLineTracer} trace,
     * and canUse() is called for every rat on every tick, so attempting it here would turn a
     * demand-bounded retry into a per-rat-per-tick trace. The residual imprecision is narrow and
     * one-directional - a widen-eligible project whose trace actually fails (terrain won't take
     * another lane) still reports true here and then fails in start(), the one case where the old
     * MOVE-holding behavior survives. Every other rejection reason is now caught before the goal
     * ever starts.
     */
    public boolean canAcceptWorker(Mob mob, TerrainAccess terrain, TerrainEvaluator evaluator, double workRadius,
                                    int maxProjectWorkers, int workersPerWidenStep) {
        Optional<PlannedStep> next = nextUnbuiltInstruction(terrain, evaluator);
        if (next.isEmpty()) return false;
        PlannedStep step = next.get();
        if (!mob.blockPosition().closerThan(step.pos(), workRadius)) return false;
        if (workers.contains(mob)) return true;
        if (workers.size() < effectiveCapFor(step.action(), this.width, maxProjectWorkers, workersPerWidenStep)) return true;
        return isWidenEligible(step.action());
    }

    /** tryWiden's own eligibility preamble, extracted so {@link #canAcceptWorker} can ask the same
     * question without tracing anything. Says nothing about whether the trace itself would
     * succeed - only whether attempting it is allowed at all. */
    private boolean isWidenEligible(SiegeNode.SiegeAction currentAction) {
        boolean widenable = currentAction == SiegeNode.SiegeAction.BUILD_STAIR || currentAction == SiegeNode.SiegeAction.BUILD_BRIDGE;
        return widenable && this.width < org.ratden.skavenblight.Config.maxProjectWidth && !buildOrder.isEmpty();
    }

    /** Attempts to trace one more parallel lane, alternating sides on successive widens, when a
     * BUILD_STAIR/BUILD_BRIDGE project is at capacity - see the design doc's Auto-widening
     * section. No-ops (returns false) for non-widenable actions, once maxProjectWidth is reached,
     * or when the trace itself fails (terrain doesn't support it, out of bounds, too much mining)
     * - a failed attempt leaves width unchanged and is simply retried on the next rejected
     * registration, never per-tick, bounding retry frequency to actual demand. */
    private boolean tryWiden(SiegeNode.SiegeAction currentAction, TerrainAccess terrain, TerrainEvaluator evaluator) {
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
        // repeated widens - and for any straight trace it's the same unit direction as any two
        // consecutive original steps anyway, since SiegeLineTracer walks a fixed (dx,dy,dz) the
        // entire length (see its own doc), so this changes nothing for multi-step build orders.
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

        SiegeLineTracer tracer = new SiegeLineTracer(evaluator);
        SiegeLineTracer.TraceResult result = tracer.trace(terrain, newAnchor,
                Integer.signum(first.pos().getX() - this.entryAnchorForWidenTrace().getX()),
                Integer.signum(traceDy),
                Integer.signum(first.pos().getZ() - this.entryAnchorForWidenTrace().getZ()),
                newAnchor, 0, pos -> false, pos -> Integer.MAX_VALUE, buildOrder.size());

        if (!result.completed() || result.orderedSteps().isEmpty()) return false;

        buildOrder.addAll(planSteps(result.orderedSteps(), newAnchor));
        this.width++;
        return true;
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
     * depending on real terrain's action classification (BUILD_STAIR vs WALK) at each lane, which
     * calling tick() and asserting on placed blocks would. */
    public List<BlockPos> getBuildOrderPositions() {
        return buildOrder.stream().map(PlannedStep::pos).toList();
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
     * tryRegisterWorker/Task 4's tick() last computed, so it can lag by up to one tick. */
    public boolean isAtCapacity() {
        return workers.size() >= this.cachedEffectiveCap;
    }

    /** Advances this project's construction by one GAME tick: adds work proportional to registered
     * worker count (capped), then places as many now-affordable not-yet-built steps as the
     * accumulated work covers, in build order. A project whose next step is blocked on an
     * incomplete MINE (see nextUnbuiltInstruction) or that's fully built is a no-op - callers
     * don't need to check either case first.
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
     * goal happened to call first. Found by the whole-branch review; the idempotency guard was
     * called for during planning but never made it into the written task brief. */
    public void tick(ServerLevel level, RegionFlowField flowField, TerrainEvaluator evaluator) {
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
            int cost = evaluator.calculateActionCostForAction(terrain, step.pos(), step.action());
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
            // the first step of every such chain, forever refusing to place it - confirmed via
            // testBuildFlowFieldGoalCompletesMultiStepMacroChainWithNoPriorSupport, which failed at
            // step 0 until this was corrected. A placed stair/pillar/spiral is self-supporting for
            // pathing purposes regardless of what ends up below it once built (see
            // TerrainEvaluator.isWalkableTerrain's scaffold short-circuit) - there is no floating-step
            // risk here for tick() to guard against in the first place.
            SiegeInteractionHandler.constructSiegeBlock(level, step.pos(), step.facing(), step.action(), flowField, null, false);

            // The old per-goal onChainComplete (AbstractSiegeConstructionGoal's default, and
            // BuildFlowFieldGoal's own override before Task 8 migrated it onto this class) was the
            // ONLY thing that ever told the region system "a rat just built something here" -
            // SiegeInteractionHandler's direct level.setBlockAndUpdate/destroyBlock calls don't
            // fire the NeoForge BlockEvents SiegeBlockEventHandler listens for, and
            // TerritoryRegionMap.tick() early-returns with nothing to do when there are no dirty
            // regions (no periodic fallback refresh). Without this call, a placement here would
            // never get discovered - the exact "built a real pillar, but that position stayed
            // 'wilderness' three rebuild generations later" bug forceRecalculation's own javadoc
            // describes. Called once per PLACEMENT (not once per tick(), and not batched/cooldown-
            // gated here) deliberately: forceRecalculation/onBlockChanged must be given the EXACT
            // position that changed (see its own doc - passing a proxy position marks the wrong
            // chunk's terrain snapshot stale), and this loop can place more than one step per
            // tick() call when a step's cost is cheap relative to Config.workPerRatPerTick - each
            // placement can land in a different chunk, so each needs its own call. This is cheap to
            // call this often: TerritoryRegionMap.onBlockChanged is an O(1)
            // ConcurrentLinkedQueue.add, and the actual expensive recompute it can eventually
            // trigger is already independently rate-limited by TerritoryRegionMap's own
            // RECALC_COOLDOWN_TICKS (80 ticks) and Config.minimumSettleDelayMs (1000ms settle
            // delay) - unlike the old goal-level 100-tick recalculateCooldown, which was extra
            // insurance on top of that, not the only thing standing between this and a real cost.
            if (flowField != null) {
                flowField.forceRecalculation(step.pos());
            }

            Optional<PlannedStep> following = nextUnbuiltInstruction(terrain, evaluator);
            step = following.orElse(null);
        }
    }

}
