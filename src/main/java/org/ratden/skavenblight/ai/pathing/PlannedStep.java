package org.ratden.skavenblight.ai.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** One entry in a SiegeProject's ordered build list. Order comes from list position, not a
 * predecessor field - facing is computed once at plan time and never re-derived later.
 *
 * <p>{@code pos} stays the LOGICAL cell (matching every other consumer of a build-order position -
 * {@link SiegeProject#plannedStepAt}, {@link SiegeProject#getBuildOrderPositions}, the work-radius
 * checks in {@code tryRegisterWorker}/{@code canAcceptWorker} - and the position
 * {@code isActionCompleted} is asked about). {@code placementPos} is where the physical block
 * actually gets placed: identical to {@code pos} for every action except an ASCENDING
 * {@code AIR_STAIR}/{@code CARVED_STAIR} (see {@link SiegeProject#planSteps}), where it's one cell
 * lower - see that method's own doc for why. */
public record PlannedStep(BlockPos pos, PathAction action, Direction facing, BlockPos placementPos) {

    /** Convenience for every caller that doesn't know about the placement/logical split (tests,
     * NBT round-tripping) - placementPos defaults to pos, correct for every action that isn't an
     * ascending AIR_STAIR/CARVED_STAIR. */
    public PlannedStep(BlockPos pos, PathAction action, Direction facing) {
        this(pos, action, facing, pos);
    }
}
