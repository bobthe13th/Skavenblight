package org.ratden.skavenblight.ai.goal.clanrat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.PathfinderMob;
import org.ratden.skavenblight.ai.pathing.SiegeNode;
import org.ratden.skavenblight.ai.pathing.region.RegionFlowField;

import java.util.Optional;
import java.util.function.Predicate;

/**
 * Shared "look at the node in front of us, and if it's a WALK step immediately before a node the
 * caller cares about, snap to that node instead" lookup, used by both AbstractSiegeConstructionGoal
 * (the per-block-claim goal family) and AbstractSiegeProjectGoal (the project-scoped family) - the
 * same lookahead applies regardless of what a matching node's own claim/registration model is.
 * Extracted from AbstractSiegeConstructionGoal.findEffectiveNode without behavior change; see that
 * class's own historical Javadoc (MAX_TARGET_CLAIM_DISTANCE / LOOKAHEAD_SNAP_DISTANCE) for the full
 * history of why this exact shape (1.5-block peek, WALK-hop-only, self-overlap guard) is correct.
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

    static Optional<SiegeNode> findEffectiveNode(RegionFlowField flowField, PathfinderMob mob,
                                                  Predicate<SiegeNode.SiegeAction> lookAheadMatch) {
        if (flowField == null || !(mob.level() instanceof ServerLevel serverLevel)) return Optional.empty();
        BlockPos currentPos = mob.blockPosition();

        SiegeNode node = flowField.getNextSiegeNode(serverLevel, currentPos);
        if (node == null) {
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                node = flowField.getNextSiegeNode(serverLevel, currentPos.relative(dir));
                if (node != null) break;
            }
        }

        if (node != null && node.action() == SiegeNode.SiegeAction.WALK) {
            SiegeNode nextNode = flowField.getNextSiegeNode(serverLevel, node.pos());
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
        if (node != null && node.action() != SiegeNode.SiegeAction.WALK && node.pos().equals(currentPos)) {
            return Optional.empty();
        }

        return Optional.ofNullable(node);
    }
}
