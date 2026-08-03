package org.ratden.skavenblight.ai.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

}
