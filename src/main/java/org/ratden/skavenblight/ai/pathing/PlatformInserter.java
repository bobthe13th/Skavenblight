package org.ratden.skavenblight.ai.pathing;

import net.minecraft.core.BlockPos;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Marks build-order seams where two different construction actions meet, so
 * SiegeInteractionHandler can clear a PLATFORM landing there instead of the seam action's normal
 * placement - a post-process over the build order, never a flood-time candidate move. */
public final class PlatformInserter {

    private PlatformInserter() {}

    public record Result(List<PlannedStep> buildOrder, Set<BlockPos> platformPositions) {}

    public static Result insertPlatforms(List<PlannedStep> buildOrder) {
        Set<BlockPos> platformPositions = new HashSet<>();
        for (int i = 1; i < buildOrder.size(); i++) {
            PathAction prev = buildOrder.get(i - 1).action();
            PathAction curr = buildOrder.get(i).action();
            if (prev != PathAction.WALK && curr != PathAction.WALK && prev != curr) {
                platformPositions.add(buildOrder.get(i).pos());
            }
        }
        return new Result(buildOrder, platformPositions);
    }
}
