package org.ratden.skavenblight.event.skavenIncursion.planning;

import net.minecraft.core.BlockPos;
import org.ratden.skavenblight.event.skavenIncursion.director.IncursionTargetType;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceDistanceProfile;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.WarpFluxNetworkGeometry;

/**
 * Shared horizontal distance geometry for front and source-group placement.
 *
 * A placement distance has one of two meanings:
 *
 * - for a Nexus target with captured Warp Flux network geometry, distance is
 *   clearance from the nearest protected network block;
 * - for a player target, or when no protected network geometry is available,
 *   distance is measured from the target centre.
 *
 * The supplied SourceDistanceProfile remains the authored tactical policy.
 * This class only resolves how that policy is interpreted in the current
 * planning context.
 *
 * FrontPlanner, SourcePlacementPlanner and debug reporting should all use this
 * abstraction rather than independently deciding whether distance means
 * target-centre distance or protected-network clearance.
 *
 * This class deals with anchor and source-group centre positions. Hard
 * clearance for complete source reservations remains the responsibility of
 * WarpFluxNetworkGeometry and SourcePlacementPlanner.
 */
public final class FrontPlacementGeometry {

    private static final double DISTANCE_EPSILON =
            0.0001D;

    private final BlockPos targetPos;
    private final int baseRadius;
    private final SourceDistanceProfile distanceProfile;
    private final WarpFluxNetworkGeometry protectedNetworkGeometry;
    private final DistanceMetric distanceMetric;

    private FrontPlacementGeometry(
            BlockPos targetPos,
            int baseRadius,
            SourceDistanceProfile distanceProfile,
            WarpFluxNetworkGeometry protectedNetworkGeometry,
            DistanceMetric distanceMetric
    ) {
        if (targetPos == null) {
            throw new IllegalArgumentException(
                    "Front-placement target position cannot be null."
            );
        }

        if (baseRadius < 0) {
            throw new IllegalArgumentException(
                    "Front-placement base radius cannot be negative."
            );
        }

        if (distanceProfile == null) {
            throw new IllegalArgumentException(
                    "Front-placement distance profile cannot be null."
            );
        }

        if (distanceMetric == null) {
            throw new IllegalArgumentException(
                    "Front-placement distance metric cannot be null."
            );
        }

        if (distanceMetric
                == DistanceMetric.PROTECTED_NETWORK_CLEARANCE
                && protectedNetworkGeometry == null) {

            throw new IllegalArgumentException(
                    "Protected-network placement requires captured network "
                            + "geometry."
            );
        }

        this.targetPos =
                targetPos.immutable();

        this.baseRadius =
                baseRadius;

        this.distanceProfile =
                distanceProfile;

        this.protectedNetworkGeometry =
                protectedNetworkGeometry;

        this.distanceMetric =
                distanceMetric;
    }

    /**
     * Resolves the correct distance metric from one immutable planning
     * context.
     *
     * Nexus-targeted planning uses the captured protected network geometry.
     * The context always attempts to provide a Nexus-position fallback when
     * no connected network has been recorded.
     *
     * Player-targeted planning remains target-centred even when an active
     * Nexus exists elsewhere. Complete source reservations must still respect
     * that network through the separate hard-clearance rule.
     */
    public static FrontPlacementGeometry from(
            IncursionPlanningContext context
    ) {
        if (context == null) {
            throw new IllegalArgumentException(
                    "Incursion planning context cannot be null."
            );
        }

        boolean useProtectedNetworkBoundary =
                context.targetType()
                        == IncursionTargetType.NEXUS
                        && context
                        .hasProtectedNetworkGeometrySnapshot();

        return new FrontPlacementGeometry(
                context.targetPos(),
                context.baseRadius(),
                context.frontDistanceProfile(),
                useProtectedNetworkBoundary
                        ? context
                        .protectedNetworkGeometrySnapshot()
                        : null,
                useProtectedNetworkBoundary
                        ? DistanceMetric
                          .PROTECTED_NETWORK_CLEARANCE
                        : DistanceMetric
                          .TARGET_CENTRE_DISTANCE
        );
    }

    public DistanceMetric getDistanceMetric() {
        return distanceMetric;
    }

    public boolean usesProtectedNetworkBoundary() {
        return distanceMetric
                == DistanceMetric.PROTECTED_NETWORK_CLEARANCE;
    }

    public boolean usesTargetCentre() {
        return distanceMetric
                == DistanceMetric.TARGET_CENTRE_DISTANCE;
    }

    public BlockPos getTargetPos() {
        return targetPos;
    }

    /**
     * Returns the angular origin used when generating front candidates.
     *
     * Protected-network placement begins from the Nexus position because the
     * captured network geometry is Nexus-backed. Target-centred placement
     * begins from the selected target.
     */
    public BlockPos getAngularOrigin() {
        if (usesProtectedNetworkBoundary()) {
            return protectedNetworkGeometry
                    .getNexusPos();
        }

        return targetPos;
    }

    public int getBaseRadius() {
        return baseRadius;
    }

    public SourceDistanceProfile getDistanceProfile() {
        return distanceProfile;
    }

    public WarpFluxNetworkGeometry getProtectedNetworkGeometry() {
        return protectedNetworkGeometry;
    }

    /**
     * Returns the preferred minimum value using the active distance metric.
     *
     * In protected-network mode this is clearance from the nearest protected
     * network block.
     *
     * In target-centred mode this is horizontal distance from the target
     * centre after including the supplied base radius.
     */
    public int getPreferredMinimumDistance() {
        if (usesProtectedNetworkBoundary()) {
            return distanceProfile
                    .getMinPreferredNetworkClearance();
        }

        return distanceProfile
                .getMinDistanceFromTarget(
                        baseRadius
                );
    }

    /**
     * Returns the preferred maximum value using the active distance metric.
     */
    public int getPreferredMaximumDistance() {
        if (usesProtectedNetworkBoundary()) {
            return distanceProfile
                    .getMaxPreferredNetworkClearance();
        }

        return distanceProfile
                .getMaxDistanceFromTarget(
                        baseRadius
                );
    }

    public int getPreferredDistanceSpan() {
        return getPreferredMaximumDistance()
                - getPreferredMinimumDistance();
    }

    /**
     * Finds a horizontal candidate along one tactical angle at or beyond the
     * requested placement distance.
     *
     * In protected-network mode, the returned position is the first point on
     * the Nexus-centred ray whose clearance from the complete network reaches
     * the requested value.
     *
     * In target-centred mode, the position is calculated directly from the
     * target centre. Block-coordinate rounding may make its measured distance
     * differ slightly from the requested integer.
     *
     * A null result is possible only when the protected-network search cannot
     * find a point within its bounded search range.
     */
    public BlockPos findPositionAtDistance(
            double angleDegrees,
            int requestedDistance
    ) {
        validateDistance(
                requestedDistance
        );

        if (usesProtectedNetworkBoundary()) {
            return protectedNetworkGeometry
                    .findPositionAtMinimumClearance(
                            angleDegrees,
                            requestedDistance
                    );
        }

        return positionAtAngleAndDistance(
                targetPos,
                angleDegrees,
                requestedDistance
        );
    }

    /**
     * Measures one candidate using the active distance metric.
     */
    public double measureDistance(
            BlockPos candidatePos
    ) {
        if (candidatePos == null) {
            throw new IllegalArgumentException(
                    "Candidate position cannot be null."
            );
        }

        if (usesProtectedNetworkBoundary()) {
            return protectedNetworkGeometry
                    .getMinimumHorizontalDistance(
                            candidatePos
                    );
        }

        return horizontalDistance(
                targetPos,
                candidatePos
        );
    }

    public boolean isWithinPreferredBand(
            BlockPos candidatePos
    ) {
        double measuredDistance =
                measureDistance(
                        candidatePos
                );

        return measuredDistance
                + DISTANCE_EPSILON
                >= getPreferredMinimumDistance()
                && measuredDistance
                - DISTANCE_EPSILON
                <= getPreferredMaximumDistance();
    }

    public boolean isAtOrBeyondPreferredMinimum(
            BlockPos candidatePos
    ) {
        return measureDistance(
                candidatePos
        ) + DISTANCE_EPSILON
                >= getPreferredMinimumDistance();
    }

    public boolean isBeyondPreferredMaximum(
            BlockPos candidatePos
    ) {
        return measureDistance(
                candidatePos
        ) - DISTANCE_EPSILON
                > getPreferredMaximumDistance();
    }

    public boolean isAtOrBeyondDistance(
            BlockPos candidatePos,
            double requiredDistance
    ) {
        if (!Double.isFinite(
                requiredDistance
        )) {
            throw new IllegalArgumentException(
                    "Required placement distance must be finite."
            );
        }

        if (requiredDistance < 0.0D) {
            throw new IllegalArgumentException(
                    "Required placement distance cannot be negative."
            );
        }

        return measureDistance(
                candidatePos
        ) + DISTANCE_EPSILON
                >= requiredDistance;
    }

    /**
     * Returns whether the candidate is no closer to the defended geometry
     * than the supplied reference position.
     *
     * SourcePlacementPlanner uses this when moving additional source groups:
     * lateral and outward movement is legal, but occupancy fallback must not
     * pull a group inward past its front.
     */
    public boolean isNoCloserThan(
            BlockPos candidatePos,
            BlockPos referencePos
    ) {
        if (referencePos == null) {
            throw new IllegalArgumentException(
                    "Reference position cannot be null."
            );
        }

        return measureDistance(
                candidatePos
        ) + DISTANCE_EPSILON
                >= measureDistance(
                referencePos
        );
    }

    /**
     * Compares two positions by their distance from the active boundary.
     *
     * A negative result means the first position is closer.
     * A positive result means the first position is farther away.
     */
    public int compareDistance(
            BlockPos first,
            BlockPos second
    ) {
        return Double.compare(
                measureDistance(
                        first
                ),
                measureDistance(
                        second
                )
        );
    }

    public String getDistanceDescription() {
        return switch (distanceMetric) {
            case PROTECTED_NETWORK_CLEARANCE ->
                    "clearance from protected Warp Flux network";

            case TARGET_CENTRE_DISTANCE ->
                    "distance from target centre";
        };
    }

    private void validateDistance(
            int distance
    ) {
        if (distance < 0) {
            throw new IllegalArgumentException(
                    "Placement distance cannot be negative."
            );
        }
    }

    private static BlockPos positionAtAngleAndDistance(
            BlockPos origin,
            double angleDegrees,
            int distance
    ) {
        double radians =
                Math.toRadians(
                        angleDegrees
                );

        int offsetX =
                (int) Math.round(
                        Math.cos(
                                radians
                        ) * distance
                );

        int offsetZ =
                (int) Math.round(
                        Math.sin(
                                radians
                        ) * distance
                );

        return origin.offset(
                offsetX,
                0,
                offsetZ
        ).immutable();
    }

    private static double horizontalDistance(
            BlockPos first,
            BlockPos second
    ) {
        long differenceX =
                (long) first.getX()
                        - second.getX();

        long differenceZ =
                (long) first.getZ()
                        - second.getZ();

        return Math.sqrt(
                differenceX
                        * differenceX
                        + differenceZ
                        * differenceZ
        );
    }

    public enum DistanceMetric {

        /**
         * Distance to the nearest protected block in the Nexus-backed Warp
         * Flux network snapshot.
         */
        PROTECTED_NETWORK_CLEARANCE,

        /**
         * Horizontal distance from the selected target's centre.
         */
        TARGET_CENTRE_DISTANCE
    }
}