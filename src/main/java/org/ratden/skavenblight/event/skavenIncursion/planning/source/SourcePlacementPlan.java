package org.ratden.skavenblight.event.skavenIncursion.planning.source;

import net.minecraft.core.BlockPos;

import java.util.UUID;

/**
 * Physical placement result for one planned source.
 *
 * Composition is decided before this object is created. This class records
 * the physical source that will eventually execute that composition.
 */
public class SourcePlacementPlan {

    private final UUID sourcePlacementId;
    private final UUID sourceGroupPlacementId;
    private final UUID sourceCompositionId;

    private final SourceType sourceType;
    private final SourceSize sourceSize;
    private final SourceRole sourceRole;

    private final BlockPos anchorPos;
    private BlockPos placedPos;

    public SourcePlacementPlan(
            UUID sourceGroupPlacementId,
            UUID sourceCompositionId,
            SourceType sourceType,
            SourceSize sourceSize,
            SourceRole sourceRole,
            BlockPos anchorPos
    ) {
        if (sourceGroupPlacementId == null) {
            throw new IllegalArgumentException(
                    "Source-group placement ID cannot be null."
            );
        }

        if (sourceCompositionId == null) {
            throw new IllegalArgumentException(
                    "Source composition ID cannot be null."
            );
        }

        if (sourceType == null) {
            throw new IllegalArgumentException(
                    "Source type cannot be null."
            );
        }

        if (sourceSize == null) {
            throw new IllegalArgumentException(
                    "Source size cannot be null."
            );
        }

        if (sourceRole == null) {
            throw new IllegalArgumentException(
                    "Source role cannot be null."
            );
        }

        if (anchorPos == null) {
            throw new IllegalArgumentException(
                    "Source anchor position cannot be null."
            );
        }

        this.sourcePlacementId = UUID.randomUUID();
        this.sourceGroupPlacementId = sourceGroupPlacementId;
        this.sourceCompositionId = sourceCompositionId;
        this.sourceType = sourceType;
        this.sourceSize = sourceSize;
        this.sourceRole = sourceRole;
        this.anchorPos = anchorPos.immutable();
        this.placedPos = null;
    }

    public UUID getSourcePlacementId() {
        return sourcePlacementId;
    }

    public UUID getSourceGroupPlacementId() {
        return sourceGroupPlacementId;
    }

    public UUID getSourceCompositionId() {
        return sourceCompositionId;
    }

    public SourceType getSourceType() {
        return sourceType;
    }

    public SourceSize getSourceSize() {
        return sourceSize;
    }

    public SourceRole getSourceRole() {
        return sourceRole;
    }

    public int getCapacityUnits() {
        return sourceSize.getCapacityUnits();
    }

    public BlockPos getAnchorPos() {
        return anchorPos;
    }

    public boolean hasPlacedPos() {
        return placedPos != null;
    }

    public BlockPos getPlacedPos() {
        return placedPos;
    }

    public void setPlacedPos(BlockPos placedPos) {
        if (placedPos == null) {
            this.placedPos = null;
            return;
        }

        this.placedPos = placedPos.immutable();
    }
}