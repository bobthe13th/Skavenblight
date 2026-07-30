package org.ratden.skavenblight.event.skavenIncursion.planning.source;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Authored spatial rules for physical source groups.
 *
 * A source group uses a circular envelope centred on its group anchor.
 * Only the centre of each source reservation must remain inside that
 * envelope. The complete source reservation may protrude beyond it.
 *
 * The envelope remains circular. Adaptation changes only its radius:
 *
 * initial radius
 * -> attempt source placement
 * -> expand by a fixed step when necessary
 * -> stop at the authored maximum
 *
 * Full source reservations still control:
 *
 * - physical source overlap;
 * - cross-group separation;
 * - terrain preparation;
 * - existing-incursion conflicts;
 * - hard Warp Flux network clearance.
 */
public record SourceGroupSpatialRules(
        int minimumInitialRadius,
        int initialRadiusMargin,
        int radiusExpansionStep,
        int maximumRadius,
        int minimumAnchorSeparation,
        int maximumEnvelopeOverlap,
        int crossGroupSourceBuffer
) {

    /**
     * Provisional ordinary source-group rules.
     *
     * These values are intended for visual and gameplay testing rather than
     * being treated as final balance values.
     *
     * With the current normal 5x5 tunnel reservation, approximate initial
     * radii are:
     *
     * one normal source:   5 blocks
     * two normal sources:  6 blocks
     * three normal sources: 7 blocks
     */
    public static final SourceGroupSpatialRules STANDARD =
            new SourceGroupSpatialRules(
                    4,
                    2,
                    2,
                    14,
                    8,
                    2,
                    3
            );

    public SourceGroupSpatialRules {
        if (minimumInitialRadius <= 0) {
            throw new IllegalArgumentException(
                    "Minimum initial source-group radius must be greater "
                            + "than zero."
            );
        }

        if (initialRadiusMargin < 0) {
            throw new IllegalArgumentException(
                    "Initial source-group radius margin cannot be negative."
            );
        }

        if (radiusExpansionStep <= 0) {
            throw new IllegalArgumentException(
                    "Source-group radius expansion step must be greater "
                            + "than zero."
            );
        }

        if (maximumRadius < minimumInitialRadius) {
            throw new IllegalArgumentException(
                    "Maximum source-group radius cannot be smaller than the "
                            + "minimum initial radius."
            );
        }

        if (minimumAnchorSeparation < 0) {
            throw new IllegalArgumentException(
                    "Minimum source-group anchor separation cannot be "
                            + "negative."
            );
        }

        if (maximumEnvelopeOverlap < 0) {
            throw new IllegalArgumentException(
                    "Maximum source-group envelope overlap cannot be "
                            + "negative."
            );
        }

        if (crossGroupSourceBuffer < 0) {
            throw new IllegalArgumentException(
                    "Cross-group source buffer cannot be negative."
            );
        }
    }

    /**
     * Estimates the first radius that should be attempted for a collection
     * of physical source profiles.
     *
     * The estimate uses the combined horizontal reservation area as an
     * approximate packed circle. It also respects the half-span of the
     * largest source so unusually large sources influence the initial
     * result.
     *
     * Because only source reservation centres must remain inside the
     * envelope, this estimate does not attempt to contain every block of
     * every source reservation.
     */
    public int calculateInitialRadius(
            Collection<SourcePlacementProfile> placementProfiles
    ) {
        if (placementProfiles == null
                || placementProfiles.isEmpty()) {
            throw new IllegalArgumentException(
                    "At least one source placement profile is required to "
                            + "calculate a group radius."
            );
        }

        long totalReservationArea =
                0L;

        int largestReservationHalfSpan =
                0;

        for (SourcePlacementProfile placementProfile
                : placementProfiles) {

            if (placementProfile == null) {
                throw new IllegalArgumentException(
                        "Source placement profile collection cannot contain "
                                + "null."
                );
            }

            int width =
                    placementProfile
                            .reservationArea()
                            .width();

            int depth =
                    placementProfile
                            .reservationArea()
                            .depth();

            if (width <= 0
                    || depth <= 0) {
                throw new IllegalArgumentException(
                        "Source reservation dimensions must be positive."
                );
            }

            totalReservationArea +=
                    (long) width * depth;

            int largestSpan =
                    Math.max(
                            width,
                            depth
                    );

            int reservationHalfSpan =
                    (int) Math.ceil(
                            largestSpan / 2.0D
                    );

            largestReservationHalfSpan =
                    Math.max(
                            largestReservationHalfSpan,
                            reservationHalfSpan
                    );
        }

        /*
         * Radius of a circle with approximately the same horizontal area as
         * the combined source reservations.
         *
         * This is an estimate only. Actual placement still checks every
         * complete source reservation independently.
         */
        int approximatePackedRadius =
                (int) Math.ceil(
                        Math.sqrt(
                                totalReservationArea
                                        / Math.PI
                        )
                );

        int estimatedRadius =
                Math.max(
                        largestReservationHalfSpan,
                        approximatePackedRadius
                ) + initialRadiusMargin;

        return normaliseInitialRadius(
                estimatedRadius
        );
    }

    /**
     * Returns every radius that should be attempted, beginning with the
     * supplied initial radius and ending with the maximum radius.
     *
     * The maximum is always included even when the expansion step does not
     * land on it exactly.
     */
    public List<Integer> createRadiusAttempts(
            int initialRadius
    ) {
        int normalisedInitialRadius =
                normaliseInitialRadius(
                        initialRadius
                );

        List<Integer> radiusAttempts =
                new ArrayList<>();

        int currentRadius =
                normalisedInitialRadius;

        while (currentRadius < maximumRadius) {
            radiusAttempts.add(
                    currentRadius
            );

            int nextRadius =
                    currentRadius
                            + radiusExpansionStep;

            if (nextRadius <= currentRadius) {
                throw new IllegalStateException(
                        "Source-group radius expansion did not advance."
                );
            }

            currentRadius =
                    Math.min(
                            nextRadius,
                            maximumRadius
                    );
        }

        if (radiusAttempts.isEmpty()
                || radiusAttempts.get(
                radiusAttempts.size() - 1
        ) != maximumRadius) {
            radiusAttempts.add(
                    maximumRadius
            );
        }

        return List.copyOf(
                radiusAttempts
        );
    }

    /**
     * Calculates the minimum permitted horizontal distance between two group
     * anchors.
     *
     * The envelopes may overlap slightly by maximumEnvelopeOverlap blocks,
     * preventing the circles from creating excessive dead space. The fixed
     * minimum anchor separation still prevents small groups from placing
     * their centres almost on top of one another.
     */
    public int calculateRequiredAnchorSeparation(
            int firstEnvelopeRadius,
            int secondEnvelopeRadius
    ) {
        validateEnvelopeRadius(
                firstEnvelopeRadius,
                "First"
        );

        validateEnvelopeRadius(
                secondEnvelopeRadius,
                "Second"
        );

        int radiusBasedSeparation =
                firstEnvelopeRadius
                        + secondEnvelopeRadius
                        - maximumEnvelopeOverlap;

        return Math.max(
                minimumAnchorSeparation,
                radiusBasedSeparation
        );
    }

    public boolean canPlaceEnvelopesTogether(
            SourceGroupEnvelope firstEnvelope,
            SourceGroupEnvelope secondEnvelope
    ) {
        if (firstEnvelope == null
                || secondEnvelope == null) {
            return false;
        }

        int requiredSeparation =
                calculateRequiredAnchorSeparation(
                        firstEnvelope.radius(),
                        secondEnvelope.radius()
                );

        double actualSeparationSquared =
                firstEnvelope
                        .horizontalDistanceSquaredTo(
                                secondEnvelope
                        );

        double requiredSeparationSquared =
                (double) requiredSeparation
                        * requiredSeparation;

        return actualSeparationSquared
                >= requiredSeparationSquared;
    }

    private int normaliseInitialRadius(
            int requestedRadius
    ) {
        return Math.max(
                minimumInitialRadius,
                Math.min(
                        requestedRadius,
                        maximumRadius
                )
        );
    }

    private void validateEnvelopeRadius(
            int radius,
            String description
    ) {
        if (radius <= 0) {
            throw new IllegalArgumentException(
                    description
                            + " source-group envelope radius must be greater "
                            + "than zero."
            );
        }

        if (radius > maximumRadius) {
            throw new IllegalArgumentException(
                    description
                            + " source-group envelope radius "
                            + radius
                            + " exceeds the authored maximum of "
                            + maximumRadius
                            + "."
            );
        }
    }
}