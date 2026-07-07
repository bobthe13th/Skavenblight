package org.ratden.skavenblight.event.skavenIncursion.action.source;

import net.minecraft.core.BlockPos;
import org.ratden.skavenblight.event.skavenIncursion.director.IncursionTargetType;

public class BasePlacementContext {
    private final BlockPos targetPos;
    private final IncursionTargetType targetType;
    private final int baseRadius;

    public BasePlacementContext(
            BlockPos targetPos,
            IncursionTargetType targetType,
            int baseRadius
    ) {
        this.targetPos = targetPos.immutable();
        this.targetType = targetType;
        this.baseRadius = Math.max(0, baseRadius);
    }

    public static BasePlacementContext forDebugPlayer(
            BlockPos targetPos
    ) {
        return new BasePlacementContext(
                targetPos,
                IncursionTargetType.PLAYER,
                16
        );
    }

    public static BasePlacementContext forDebugBase(
            BlockPos targetPos,
            int baseRadius
    ) {
        return new BasePlacementContext(
                targetPos,
                IncursionTargetType.NEXUS,
                baseRadius
        );
    }

    public BlockPos getTargetPos() {
        return targetPos;
    }

    public IncursionTargetType getTargetType() {
        return targetType;
    }

    public int getBaseRadius() {
        return baseRadius;
    }

    public boolean usesBaseRadius() {
        return baseRadius > 0;
    }
}