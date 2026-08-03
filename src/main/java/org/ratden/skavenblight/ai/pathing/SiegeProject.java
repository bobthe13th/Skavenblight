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

    private final Set<Mob> workers = new HashSet<>();
    // Updated by nextUnbuiltInstruction()'s callers (tryRegisterWorker, Task 4's tick()) each time
    // they have real terrain access; isAtCapacity() reads this cheaply for callers (AwaitFormationGoal)
    // that don't want to thread a TerrainAccess/TerrainEvaluator through just to ask "is this full".
    private int cachedEffectiveCap = Integer.MAX_VALUE;
    private int width = 1;
    private double accumulatedWork = 0.0;

    public SiegeProject(Map<BlockPos, SiegeNode> instructions, List<SiegeNode> orderedSteps, BlockPos buildOrderAnchor,
                         BlockPos entryPos, int expectedEntryCost) {
        this(instructions, orderedSteps, buildOrderAnchor, entryPos, expectedEntryCost, null);
    }

    public SiegeProject(Map<BlockPos, SiegeNode> instructions, List<SiegeNode> orderedSteps, BlockPos buildOrderAnchor,
                         BlockPos entryPos, int expectedEntryCost, BlockPos exitPos) {
        this.instructions = new HashMap<>(instructions);
        this.buildOrder = planSteps(orderedSteps, buildOrderAnchor);
        this.entryPos = entryPos;
        this.expectedEntryCost = expectedEntryCost;
        this.exitPos = exitPos;
    }

    public boolean isCompleted(TerrainAccess terrain, TerrainEvaluator evaluator) {
        return instructions.values().stream().allMatch(node -> evaluator.isActionCompleted(terrain, node));
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
        Map<BlockPos, SiegeNode> remaining = new HashMap<>();
        instructions.forEach((pos, node) -> {
            if (!evaluator.isActionCompleted(terrain, node)) {
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

    /** How many of `orderedRemainingCosts` (in build order) `availableWork` fully covers, stopping
     * at the first one it can't - a large mid-sequence cost blocks everything after it even if the
     * total would otherwise suffice, matching "build in order" semantics. */
    static int countCompletable(List<Integer> orderedRemainingCosts, double availableWork) {
        int completed = 0;
        double remaining = availableWork;
        for (int cost : orderedRemainingCosts) {
            if (remaining < cost) break;
            remaining -= cost;
            completed++;
        }
        return completed;
    }

    /** BUILD_STAIR/BUILD_BRIDGE scale their worker cap with `width` (see the auto-widening design);
     * every other action type gets a flat cap regardless of `width`. */
    static int effectiveCapFor(SiegeNode.SiegeAction action, int width, int maxProjectWorkers, int workersPerWidenStep) {
        boolean widenable = action == SiegeNode.SiegeAction.BUILD_STAIR || action == SiegeNode.SiegeAction.BUILD_BRIDGE;
        return widenable ? width * workersPerWidenStep : maxProjectWorkers;
    }

    /** The first not-yet-built step in build order, or empty if the project is fully built OR
     * currently blocked on an incomplete MINE step (handled entirely by the old per-rat
     * SmartBreachGoal/flowField.tryClaimTarget path - out of scope for this mechanism; skipping
     * PAST an unmined obstacle to a build step beyond it would be physically wrong, since that
     * later step may depend on the obstacle already being cleared). */
    public Optional<PlannedStep> nextUnbuiltInstruction(TerrainAccess terrain, TerrainEvaluator evaluator) {
        for (PlannedStep step : buildOrder) {
            if (step.action() == SiegeNode.SiegeAction.WALK || step.action() == SiegeNode.SiegeAction.LEAP) continue;
            if (step.action() == SiegeNode.SiegeAction.MINE) return Optional.empty();
            if (!evaluator.isActionCompleted(terrain, new SiegeNode(step.pos(), step.action()))) return Optional.of(step);
        }
        return Optional.empty();
    }

    /** Registers `mob` as a worker if there's real build work nearby (within `workRadius` of the
     * next unbuilt step) and the project isn't already at capacity - see effectiveCapFor for how
     * capacity scales with width for BUILD_STAIR/BUILD_BRIDGE. Idempotent: re-registering an
     * already-registered mob succeeds trivially. Widening on a rejected registration is added in
     * a later task; for now a full project simply refuses. */
    public boolean tryRegisterWorker(Mob mob, TerrainAccess terrain, TerrainEvaluator evaluator, double workRadius,
                                      int maxProjectWorkers, int workersPerWidenStep) {
        Optional<PlannedStep> next = nextUnbuiltInstruction(terrain, evaluator);
        if (next.isEmpty()) return false;
        PlannedStep step = next.get();
        if (!mob.blockPosition().closerThan(step.pos(), workRadius)) return false;

        this.cachedEffectiveCap = effectiveCapFor(step.action(), this.width, maxProjectWorkers, workersPerWidenStep);
        if (workers.contains(mob)) return true;
        if (workers.size() >= this.cachedEffectiveCap) return false;

        workers.add(mob);
        return true;
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

    /** Advances this project's construction by one tick: adds work proportional to registered
     * worker count (capped), then places as many now-affordable not-yet-built steps as the
     * accumulated work covers, in build order. A project whose next step is blocked on an
     * incomplete MINE (see nextUnbuiltInstruction) or that's fully built is a no-op - callers
     * don't need to check either case first. */
    public void tick(ServerLevel level, RegionFlowField flowField, TerrainEvaluator evaluator) {
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
            // supportSolidAtClaim=true: unlike the old per-mob claim-then-execute window (a real
            // multi-tick gap the flag exists to guard), this placement is synchronous with the
            // "is it next in build order" check above - by definition every earlier step is
            // already built, so support is verified fresh right now, not snapshotted earlier.
            SiegeInteractionHandler.constructSiegeBlock(level, step.pos(), step.facing(), step.action(), flowField, null, true);

            Optional<PlannedStep> following = nextUnbuiltInstruction(terrain, evaluator);
            step = following.orElse(null);
        }
    }

}
