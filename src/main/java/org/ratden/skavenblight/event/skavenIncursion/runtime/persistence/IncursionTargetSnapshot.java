package org.ratden.skavenblight.event.skavenIncursion.runtime.persistence;

import net.minecraft.core.BlockPos;
import org.ratden.skavenblight.event.skavenIncursion.director.IncursionTargetType;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.BasePlacementContext;

import java.util.Objects;

/**
 * Immutable persistence snapshot of the target selected for one incursion.
 *
 * This records the target information used during planning:
 *
 * - target type;
 * - target position at the time the incursion was planned;
 * - defended base radius used by placement calculations.
 *
 * This is a positional planning snapshot. It does not attempt to retain a
 * live reference to a player, Nexus block entity or other world object.
 *
 * A later restoration or reconciliation step may compare this snapshot with
 * the current world, but it must not silently rewrite the original target
 * used to produce the persisted IncursionPlan.
 */
public record IncursionTargetSnapshot(
        IncursionTargetType targetType,
        BlockPos targetPos,
        int baseRadius
) {

    public IncursionTargetSnapshot {
        Objects.requireNonNull(
                targetType,
                "Incursion target type cannot be null."
        );

        Objects.requireNonNull(
                targetPos,
                "Incursion target position cannot be null."
        );

        if (baseRadius < 0) {
            throw new IllegalArgumentException(
                    "Incursion target base radius cannot be negative."
            );
        }

        targetPos =
                targetPos.immutable();
    }

    /**
     * Captures the immutable target values from a planning placement context.
     */
    public static IncursionTargetSnapshot capture(
            BasePlacementContext placementContext
    ) {
        if (placementContext == null) {
            throw new IllegalArgumentException(
                    "Base placement context cannot be null."
            );
        }

        return new IncursionTargetSnapshot(
                placementContext.getTargetType(),
                placementContext.getTargetPos(),
                placementContext.getBaseRadius()
        );
    }

    /**
     * Reconstructs the original placement context represented by this
     * snapshot.
     *
     * This does not inspect or modify the current world.
     */
    public BasePlacementContext restorePlacementContext() {
        return new BasePlacementContext(
                targetPos,
                targetType,
                baseRadius
        );
    }

    public boolean usesBaseRadius() {
        return baseRadius > 0;
    }
}