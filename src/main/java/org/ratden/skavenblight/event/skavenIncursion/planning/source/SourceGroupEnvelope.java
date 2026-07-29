package org.ratden.skavenblight.event.skavenIncursion.planning.source;

import net.minecraft.core.BlockPos;

/**
 * Circular horizontal source-centre envelope for one physical source group.
 *
 * The centre is the conceptual source-group anchor used by planning.
 * Its Y coordinate does not affect envelope containment or separation.
 *
 * Only the centre of a source reservation must remain inside this envelope.
 * The complete reservation may extend beyond it, but remains subject to all
 * physical overlap, cross-group buffer, terrain and protected-network rules.
 *
 * The envelope is immutable. Adaptive planning creates a replacement
 * envelope with a larger radius when another placement attempt is required.
 */
public record SourceGroupEnvelope(
        BlockPos centre,
        int radius
) {

    public SourceGroupEnvelope {
        if (centre == null) {
            throw new IllegalArgumentException(
                    "Source-group envelope centre cannot be null."
            );
        }

        if (radius <= 0) {
            throw new IllegalArgumentException(
                    "Source-group envelope radius must be greater than zero."
            );
        }

        centre =
                centre.immutable();
    }

    /**
     * Returns whether the centre of a source block lies inside or directly on
     * this envelope.
     */
    public boolean containsSourceCentre(
            BlockPos sourceCentre
    ) {
        if (sourceCentre == null) {
            return false;
        }

        return containsHorizontalPoint(
                sourceCentre.getX() + 0.5D,
                sourceCentre.getZ() + 0.5D
        );
    }

    /**
     * Returns whether the horizontal centre of a complete source reservation
     * lies inside or directly on this envelope.
     *
     * This is deliberately less restrictive than requiring the complete
     * reservation bounds to fit inside the circle.
     */
    public boolean containsReservationCentre(
            SourceReservationArea.WorldBounds reservationBounds
    ) {
        if (reservationBounds == null) {
            return false;
        }

        double reservationCentreX =
                (
                        reservationBounds.minX()
                                + reservationBounds.maxX()
                                + 1.0D
                ) / 2.0D;

        double reservationCentreZ =
                (
                        reservationBounds.minZ()
                                + reservationBounds.maxZ()
                                + 1.0D
                ) / 2.0D;

        return containsHorizontalPoint(
                reservationCentreX,
                reservationCentreZ
        );
    }

    public boolean containsHorizontalPoint(
            double pointX,
            double pointZ
    ) {
        if (!Double.isFinite(pointX)
                || !Double.isFinite(pointZ)) {
            return false;
        }

        double differenceX =
                pointX
                        - getCentreX();

        double differenceZ =
                pointZ
                        - getCentreZ();

        double distanceSquared =
                differenceX * differenceX
                        + differenceZ * differenceZ;

        double radiusSquared =
                (double) radius * radius;

        return distanceSquared
                <= radiusSquared;
    }

    /**
     * Returns the squared horizontal distance between this envelope's centre
     * and another envelope's centre.
     */
    public double horizontalDistanceSquaredTo(
            SourceGroupEnvelope otherEnvelope
    ) {
        if (otherEnvelope == null) {
            throw new IllegalArgumentException(
                    "Other source-group envelope cannot be null."
            );
        }

        double differenceX =
                otherEnvelope.getCentreX()
                        - getCentreX();

        double differenceZ =
                otherEnvelope.getCentreZ()
                        - getCentreZ();

        return differenceX * differenceX
                + differenceZ * differenceZ;
    }

    public double horizontalDistanceTo(
            SourceGroupEnvelope otherEnvelope
    ) {
        return Math.sqrt(
                horizontalDistanceSquaredTo(
                        otherEnvelope
                )
        );
    }

    /**
     * Returns a new envelope using the same conceptual group centre and a
     * different radius.
     */
    public SourceGroupEnvelope withRadius(
            int newRadius
    ) {
        return new SourceGroupEnvelope(
                centre,
                newRadius
        );
    }

    public double getCentreX() {
        return centre.getX()
                + 0.5D;
    }

    public double getCentreZ() {
        return centre.getZ()
                + 0.5D;
    }

    public int getMinimumX() {
        return centre.getX()
                - radius;
    }

    public int getMaximumX() {
        return centre.getX()
                + radius;
    }

    public int getMinimumZ() {
        return centre.getZ()
                - radius;
    }

    public int getMaximumZ() {
        return centre.getZ()
                + radius;
    }

    public int getDiameter() {
        return radius * 2;
    }
}