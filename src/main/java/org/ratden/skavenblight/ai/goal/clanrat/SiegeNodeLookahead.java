package org.ratden.skavenblight.ai.goal.clanrat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.PathfinderMob;
import org.ratden.skavenblight.ai.pathing.FlowStep;
import org.ratden.skavenblight.ai.pathing.PathAction;
import org.ratden.skavenblight.ai.pathing.region.RegionFlowField;

import java.util.Optional;
import java.util.function.Predicate;

/**
 * Shared "look at the node in front of us, and if it's a WALK step immediately before a node the
 * caller cares about, snap to that node instead" lookup, used by both AbstractSiegeConstructionGoal
 * (the per-block-claim goal family) and AbstractSiegeProjectGoal (the project-scoped family) - the
 * same lookahead applies regardless of what a matching node's own claim/registration model is.
 * Extracted from AbstractSiegeConstructionGoal.findEffectiveNode without behavior change; see
 * AbstractSiegeConstructionGoal.MAX_TARGET_CLAIM_DISTANCE's own javadoc and this file's
 * LOOKAHEAD_SNAP_DISTANCE javadoc below for the full history of why this exact shape (1.5-block
 * peek, WALK-hop-only, self-overlap guard) is correct.
 */
final class SiegeNodeLookahead {

    /**
     * findEffectiveNode's own lookahead peek distance - deliberately separate from
     * AbstractSiegeConstructionGoal.MAX_TARGET_CLAIM_DISTANCE. The lookahead exists only to let a
     * mob standing one ordinary WALK step short of real work skip the pointless extra tick of
     * walking there first - i.e. it should never see further than a single legitimate adjacent
     * interaction (orthogonal 1.0, diagonal ~1.41) would ever reach on its own. Reusing
     * MAX_TARGET_CLAIM_DISTANCE (2.5, sized for tolerating crowd-shove during an ALREADY claimed
     * build) here let the lookahead claim a target a full two flow-field hops away - confirmed via
     * StaircaseSiegeGroupGameTests + a live debug-item observation: a rat still two cells back from
     * a gap's ledge would build the far stair immediately, before ever walking onto the ledge
     * itself, leaving the newly-built stair unreachable from where the rat actually stood. The
     * lookahead was never meant to affect what gets targeted, only to smooth movement across a
     * surface - this bounds it back to that.
     */
    private static final double LOOKAHEAD_SNAP_DISTANCE = 1.5D;

    private SiegeNodeLookahead() {}

    static Optional<FlowStep> findEffectiveNode(RegionFlowField flowField, PathfinderMob mob,
                                                  Predicate<PathAction> lookAheadMatch) {
        if (flowField == null || !(mob.level() instanceof ServerLevel serverLevel)) return Optional.empty();
        BlockPos currentPos = mob.blockPosition();

        FlowStep node = flowField.getNextStep(serverLevel, currentPos);
        if (node == null) {
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                node = flowField.getNextStep(serverLevel, currentPos.relative(dir));
                if (node != null) break;
            }
        }

        if (node != null && node.action() == PathAction.WALK) {
            // Corrected (2026-08-06, Task 21): node.pos() always equals the query key under the
            // disambiguated FlowStep convention (see RegionFlowField.getNextStep's own doc) - the
            // real one-hop-ahead peek this lookahead needs is node.predecessorPos().
            FlowStep nextNode = flowField.getNextStep(serverLevel, node.predecessorPos());
            if (nextNode != null && lookAheadMatch.test(nextNode.action())
                    && !nextNode.pos().equals(currentPos)
                    && currentPos.closerThan(nextNode.pos(), LOOKAHEAD_SNAP_DISTANCE)) {
                return Optional.of(nextNode);
            }
        }

        // A construction target that IS the mob's own current position can never be executed
        // safely - a mob can't place a block into the exact space its body occupies without
        // stepping aside first, which nothing here does. Defense in depth: the one known
        // source of this (SiegeProjectManager's old self-referential anchor instruction) has
        // been removed, but this guard means any future/unknown source degrades to
        // "no instruction" (safe - the mob just waits) instead of silently entombing it.
        // Confirmed via SiegeActivityLog in testing: exact mob-pos == target-pos matches on
        // BUILD_STAIR executions.
        if (node != null && node.action() != PathAction.WALK && node.pos().equals(currentPos)) {
            return Optional.empty();
        }

        return Optional.ofNullable(node);
    }
}
