package org.ratden.skavenblight.event.skavenIncursion.planning.source;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Physical placement result for one planned source.
 *
 * Composition is decided before this object is created. One physical source
 * may execute source compositions from several waves, so it may be bound to
 * more than one SourceComposition ID.
 *
 * The stored placement profile describes the complete space reserved for the
 * source and the terrain preparation runtime execution may perform there.
 */
public class SourcePlacementPlan {

    private final UUID sourcePlacementId;
    private final UUID sourceGroupPlacementId;

    private final List<UUID> sourceCompositionIds;

    private final SourceType sourceType;
    private final SourceSize sourceSize;
    private final SourceRole sourceRole;

    private final SourcePlacementProfile placementProfile;
    private final Direction facing;

    private final BlockPos anchorPos;
    private BlockPos placedPos;

    public SourcePlacementPlan(
            UUID sourceGroupPlacementId,
            UUID initialSourceCompositionId,
            SourceType sourceType,
            SourceSize sourceSize,
            SourceRole sourceRole,
            SourcePlacementProfile placementProfile,
            Direction facing,
            BlockPos anchorPos
    ) {
        if (sourceGroupPlacementId == null) {
            throw new IllegalArgumentException(
                    "Source-group placement ID cannot be null."
            );
        }

        if (initialSourceCompositionId == null) {
            throw new IllegalArgumentException(
                    "Initial source composition ID cannot be null."
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

        if (placementProfile == null) {
            throw new IllegalArgumentException(
                    "Source placement profile cannot be null."
            );
        }

        validateHorizontalFacing(facing);

        if (anchorPos == null) {
            throw new IllegalArgumentException(
                    "Source anchor position cannot be null."
            );
        }

        this.sourcePlacementId = UUID.randomUUID();
        this.sourceGroupPlacementId = sourceGroupPlacementId;
        this.sourceCompositionIds = new ArrayList<>();

        this.sourceType = sourceType;
        this.sourceSize = sourceSize;
        this.sourceRole = sourceRole;

        this.placementProfile = placementProfile;
        this.facing = facing;

        this.anchorPos = anchorPos.immutable();
        this.placedPos = null;

        bindSourceComposition(initialSourceCompositionId);
    }

    public UUID getSourcePlacementId() {
        return sourcePlacementId;
    }

    public UUID getSourceGroupPlacementId() {
        return sourceGroupPlacementId;
    }

    /**
     * Binds another wave's source-sized composition to this physical source.
     */
    public void bindSourceComposition(
            UUID sourceCompositionId
    ) {
        if (sourceCompositionId == null) {
            throw new IllegalArgumentException(
                    "Source composition ID cannot be null."
            );
        }

        if (sourceCompositionIds.contains(sourceCompositionId)) {
            throw new IllegalArgumentException(
                    "Source placement "
                            + sourcePlacementId
                            + " is already bound to source composition "
                            + sourceCompositionId
                            + "."
            );
        }

        sourceCompositionIds.add(sourceCompositionId);
    }

    public List<UUID> getSourceCompositionIds() {
        return Collections.unmodifiableList(
                sourceCompositionIds
        );
    }

    public boolean isBoundToSourceComposition(
            UUID sourceCompositionId
    ) {
        return sourceCompositionId != null
                && sourceCompositionIds.contains(sourceCompositionId);
    }

    public int getBoundCompositionCount() {
        return sourceCompositionIds.size();
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

    public SourcePlacementProfile getPlacementProfile() {
        return placementProfile;
    }

    public SourceReservationArea getReservationArea() {
        return placementProfile.reservationArea();
    }

    public SourceReservationArea getPreparationArea() {
        return placementProfile.preparationArea();
    }

    public int getFoundationDepth() {
        return placementProfile.foundationDepth();
    }

    public int getClearanceHeight() {
        return placementProfile.clearanceHeight();
    }

    public Direction getFacing() {
        return facing;
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

    public void setPlacedPos(
            BlockPos placedPos
    ) {
        if (placedPos == null) {
            this.placedPos = null;
            return;
        }

        this.placedPos = placedPos.immutable();
    }

    /**
     * Returns the position used as the origin of the source's physical
     * footprint.
     *
     * Once a final position has been selected it takes precedence over the
     * initial anchor.
     */
    public BlockPos getReservationOrigin() {
        return hasPlacedPos()
                ? placedPos
                : anchorPos;
    }

    public SourceReservationArea.WorldBounds
    getReservationBounds() {
        return getReservationArea().resolve(
                getReservationOrigin(),
                facing
        );
    }

    public SourceReservationArea.WorldBounds
    getPreparationBounds() {
        return getPreparationArea().resolve(
                getReservationOrigin(),
                facing
        );
    }

    public boolean reservationOverlaps(
            SourcePlacementPlan other
    ) {
        Objects.requireNonNull(
                other,
                "Other source placement cannot be null."
        );

        return getReservationBounds().overlaps(
                other.getReservationBounds()
        );
    }

    private static void validateHorizontalFacing(
            Direction facing
    ) {
        if (facing == null) {
            throw new IllegalArgumentException(
                    "Source facing cannot be null."
            );
        }

        if (facing.getAxis().isVertical()) {
            throw new IllegalArgumentException(
                    "Source facing must be horizontal."
            );
        }
    }
}