package org.ratden.skavenblight.event.skavenIncursion.planning.source;

import java.util.Objects;

/**
 * Physical planning, preparation and grouping requirements for one source
 * type and size combination.
 *
 * The reservation area describes all horizontal blocks that must remain
 * available to the source.
 *
 * The preparation area describes the horizontal footprint that runtime
 * execution may actively prepare by constructing foundation blocks and
 * clearing emergence space.
 *
 * Vertical preparation is measured relative to the source origin:
 *
 * - foundationDepth 1 permits preparation at Y - 1;
 * - clearanceHeight 3 permits preparation at Y, Y + 1 and Y + 2.
 *
 * Source group load is an authored measure of how much infrastructure this
 * source contributes to one source group. It is intentionally separate from
 * mob capacity, which controls how many mobs the source may contain.
 */
public record SourcePlacementProfile(
        SourceReservationArea reservationArea,
        SourceReservationArea preparationArea,
        int foundationDepth,
        int clearanceHeight,
        int sourceGroupLoadCost
) {
    public SourcePlacementProfile {
        Objects.requireNonNull(
                reservationArea,
                "Source reservation area cannot be null."
        );

        Objects.requireNonNull(
                preparationArea,
                "Source preparation area cannot be null."
        );

        if (foundationDepth < 0) {
            throw new IllegalArgumentException(
                    "Source foundation depth cannot be negative."
            );
        }

        if (clearanceHeight < 0) {
            throw new IllegalArgumentException(
                    "Source clearance height cannot be negative."
            );
        }

        if (sourceGroupLoadCost <= 0) {
            throw new IllegalArgumentException(
                    "Source group load cost must be greater than zero."
            );
        }

        validatePreparationFitsReservation(
                reservationArea,
                preparationArea
        );
    }

    /**
     * Returns whether runtime execution is expected to construct or replace
     * one or more foundation layers beneath the source.
     */
    public boolean hasFoundationPreparation() {
        return foundationDepth > 0;
    }

    /**
     * Returns whether runtime execution is expected to clear an emergence
     * volume from the source level upward.
     */
    public boolean hasClearancePreparation() {
        return clearanceHeight > 0;
    }

    private static void validatePreparationFitsReservation(
            SourceReservationArea reservationArea,
            SourceReservationArea preparationArea
    ) {
        if (preparationArea.minXOffset()
                < reservationArea.minXOffset()
                || preparationArea.maxXOffset()
                > reservationArea.maxXOffset()
                || preparationArea.minZOffset()
                < reservationArea.minZOffset()
                || preparationArea.maxZOffset()
                > reservationArea.maxZOffset()) {
            throw new IllegalArgumentException(
                    "Source preparation area must fit entirely inside its "
                            + "reservation area."
            );
        }
    }
}