package org.ratden.skavenblight.event.skavenIncursion.planning.source;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Temporary physical-placement result for one complete source group.
 *
 * Adaptive source-group placement may attempt:
 *
 * - several envelope radii;
 * - several additional-group anchors;
 * - preferred terrain before preparable terrain.
 *
 * Failed attempts must not mutate FrontPlan, SourceGroupPlacementPlan or any
 * SourcePlacementPlan. This draft therefore holds a complete successful
 * candidate until SourcePlacementPlanner is ready to commit it to the final
 * IncursionPlan.
 *
 * The draft represents the persistent physical infrastructure required over
 * the source group's entire planned lifecycle, not only the first wave that
 * uses the group.
 */
public final class SourceGroupPlacementDraft {

    private final SourceGroupPlacementDemand sourceGroupDemand;
    private final SourceGroupEnvelope sourceGroupEnvelope;

    private final List<PhysicalSourcePlacementDraft>
            physicalSourcePlacements;

    public SourceGroupPlacementDraft(
            SourceGroupPlacementDemand sourceGroupDemand,
            SourceGroupEnvelope sourceGroupEnvelope,
            List<PhysicalSourcePlacementDraft>
                    physicalSourcePlacements
    ) {
        if (sourceGroupDemand == null) {
            throw new IllegalArgumentException(
                    "Source-group placement demand cannot be null."
            );
        }

        if (sourceGroupEnvelope == null) {
            throw new IllegalArgumentException(
                    "Source-group placement envelope cannot be null."
            );
        }

        if (physicalSourcePlacements == null
                || physicalSourcePlacements.isEmpty()) {
            throw new IllegalArgumentException(
                    "Source-group placement draft requires at least one "
                            + "physical source placement."
            );
        }

        validateEnvelope(
                sourceGroupDemand,
                sourceGroupEnvelope
        );

        List<PhysicalSourcePlacementDraft> copiedPlacements =
                List.copyOf(
                        physicalSourcePlacements
                );

        validatePhysicalSourcePlacements(
                sourceGroupDemand,
                sourceGroupEnvelope,
                copiedPlacements
        );

        this.sourceGroupDemand =
                sourceGroupDemand;

        this.sourceGroupEnvelope =
                sourceGroupEnvelope;

        this.physicalSourcePlacements =
                copiedPlacements;
    }

    public SourceGroupPlacementDemand getSourceGroupDemand() {
        return sourceGroupDemand;
    }

    public UUID getSourceGroupDemandId() {
        return sourceGroupDemand.getSourceGroupDemandId();
    }

    public UUID getFrontId() {
        return sourceGroupDemand.getFrontId();
    }

    public int getSourceGroupIndex() {
        return sourceGroupDemand.getSourceGroupIndex();
    }

    public SourceRole getSourceRole() {
        return sourceGroupDemand.getSourceRole();
    }

    public SourceGroupSpatialRules getSourceGroupSpatialRules() {
        return sourceGroupDemand.getSourceGroupSpatialRules();
    }

    public SourceGroupEnvelope getSourceGroupEnvelope() {
        return sourceGroupEnvelope;
    }

    public BlockPos getAnchorPos() {
        return sourceGroupEnvelope.centre();
    }

    public int getEnvelopeRadius() {
        return sourceGroupEnvelope.radius();
    }

    public List<PhysicalSourcePlacementDraft>
    getPhysicalSourcePlacements() {
        return physicalSourcePlacements;
    }

    public int getPhysicalSourceCount() {
        return physicalSourcePlacements.size();
    }

    public int getTotalSourceGroupLoad() {
        int totalLoad =
                0;

        for (PhysicalSourcePlacementDraft physicalSourcePlacement
                : physicalSourcePlacements) {

            totalLoad +=
                    physicalSourcePlacement
                            .placementProfile()
                            .sourceGroupLoadCost();
        }

        return totalLoad;
    }

    public int getPreferredSiteCount() {
        int preferredCount =
                0;

        for (PhysicalSourcePlacementDraft physicalSourcePlacement
                : physicalSourcePlacements) {

            if (physicalSourcePlacement.siteQuality()
                    == SiteQuality.PREFERRED) {
                preferredCount++;
            }
        }

        return preferredCount;
    }

    public int getPreparableSiteCount() {
        return getPhysicalSourceCount()
                - getPreferredSiteCount();
    }

    public PhysicalSourcePlacementDraft
    getPhysicalSourcePlacement(
            UUID physicalSourceDemandId
    ) {
        if (physicalSourceDemandId == null) {
            return null;
        }

        for (PhysicalSourcePlacementDraft physicalSourcePlacement
                : physicalSourcePlacements) {

            if (physicalSourcePlacement
                    .physicalSourceDemandId()
                    .equals(
                            physicalSourceDemandId
                    )) {
                return physicalSourcePlacement;
            }
        }

        return null;
    }

    /**
     * Returns the complete source reservations in this draft.
     *
     * These are the hard physical reservations used for overlap, network
     * clearance and cross-group separation checks.
     */
    public List<SourceReservationArea.WorldBounds>
    getReservationBounds() {
        List<SourceReservationArea.WorldBounds> reservationBounds =
                new ArrayList<>();

        for (PhysicalSourcePlacementDraft physicalSourcePlacement
                : physicalSourcePlacements) {

            reservationBounds.add(
                    physicalSourcePlacement.reservationBounds()
            );
        }

        return List.copyOf(
                reservationBounds
        );
    }

    private static void validateEnvelope(
            SourceGroupPlacementDemand sourceGroupDemand,
            SourceGroupEnvelope sourceGroupEnvelope
    ) {
        SourceGroupSpatialRules spatialRules =
                sourceGroupDemand.getSourceGroupSpatialRules();

        int envelopeRadius =
                sourceGroupEnvelope.radius();

        if (envelopeRadius
                < spatialRules.minimumInitialRadius()) {
            throw new IllegalArgumentException(
                    "Source-group draft envelope radius "
                            + envelopeRadius
                            + " is smaller than the authored minimum of "
                            + spatialRules.minimumInitialRadius()
                            + "."
            );
        }

        if (envelopeRadius
                > spatialRules.maximumRadius()) {
            throw new IllegalArgumentException(
                    "Source-group draft envelope radius "
                            + envelopeRadius
                            + " exceeds the authored maximum of "
                            + spatialRules.maximumRadius()
                            + "."
            );
        }

        if (!sourceGroupDemand
                .createEnvelopeRadiusAttempts()
                .contains(
                        envelopeRadius
                )) {
            throw new IllegalArgumentException(
                    "Source-group draft envelope radius "
                            + envelopeRadius
                            + " is not one of the adaptive radii authorised "
                            + "for demand "
                            + sourceGroupDemand.getSourceGroupDemandId()
                            + "."
            );
        }
    }

    private static void validatePhysicalSourcePlacements(
            SourceGroupPlacementDemand sourceGroupDemand,
            SourceGroupEnvelope sourceGroupEnvelope,
            List<PhysicalSourcePlacementDraft>
                    physicalSourcePlacements
    ) {
        if (physicalSourcePlacements.size()
                != sourceGroupDemand.getPhysicalSourceCount()) {
            throw new IllegalArgumentException(
                    "Source-group placement draft contains "
                            + physicalSourcePlacements.size()
                            + " physical source placements, but demand "
                            + sourceGroupDemand.getSourceGroupDemandId()
                            + " requires "
                            + sourceGroupDemand.getPhysicalSourceCount()
                            + "."
            );
        }

        Map<
                UUID,
                SourceGroupPlacementDemand.PhysicalSourceDemand
                > physicalSourceDemandsById =
                indexPhysicalSourceDemands(
                        sourceGroupDemand
                );

        Set<UUID> placedPhysicalSourceDemandIds =
                new HashSet<>();

        int calculatedSourceGroupLoad =
                0;

        for (PhysicalSourcePlacementDraft physicalSourcePlacement
                : physicalSourcePlacements) {

            if (physicalSourcePlacement == null) {
                throw new IllegalArgumentException(
                        "Source-group placement draft cannot contain a null "
                                + "physical source placement."
                );
            }

            UUID physicalSourceDemandId =
                    physicalSourcePlacement
                            .physicalSourceDemandId();

            if (!placedPhysicalSourceDemandIds.add(
                    physicalSourceDemandId
            )) {
                throw new IllegalArgumentException(
                        "Source-group placement draft contains duplicate "
                                + "physical source-demand ID "
                                + physicalSourceDemandId
                                + "."
                );
            }

            SourceGroupPlacementDemand.PhysicalSourceDemand
                    physicalSourceDemand =
                    physicalSourceDemandsById.get(
                            physicalSourceDemandId
                    );

            if (physicalSourceDemand == null) {
                throw new IllegalArgumentException(
                        "Source-group placement draft refers to unknown "
                                + "physical source-demand ID "
                                + physicalSourceDemandId
                                + "."
                );
            }

            validatePhysicalSourcePlacement(
                    sourceGroupEnvelope,
                    physicalSourceDemand,
                    physicalSourcePlacement
            );

            calculatedSourceGroupLoad +=
                    physicalSourcePlacement
                            .placementProfile()
                            .sourceGroupLoadCost();
        }

        if (placedPhysicalSourceDemandIds.size()
                != physicalSourceDemandsById.size()) {
            throw new IllegalArgumentException(
                    "Source-group placement draft does not place every "
                            + "physical source required by demand "
                            + sourceGroupDemand.getSourceGroupDemandId()
                            + "."
            );
        }

        if (calculatedSourceGroupLoad
                != sourceGroupDemand.getTotalSourceGroupLoad()) {
            throw new IllegalArgumentException(
                    "Source-group placement draft uses load "
                            + calculatedSourceGroupLoad
                            + ", but demand "
                            + sourceGroupDemand.getSourceGroupDemandId()
                            + " requires load "
                            + sourceGroupDemand.getTotalSourceGroupLoad()
                            + "."
            );
        }

        validateInternalReservationSeparation(
                physicalSourcePlacements
        );
    }

    private static void validatePhysicalSourcePlacement(
            SourceGroupEnvelope sourceGroupEnvelope,
            SourceGroupPlacementDemand.PhysicalSourceDemand
                    physicalSourceDemand,
            PhysicalSourcePlacementDraft physicalSourcePlacement
    ) {
        if (!physicalSourceDemand
                .getPlacementProfile()
                .equals(
                        physicalSourcePlacement
                                .placementProfile()
                )) {
            throw new IllegalArgumentException(
                    "Physical source placement for demand "
                            + physicalSourceDemand
                            .getPhysicalSourceDemandId()
                            + " uses a different placement profile."
            );
        }

        SourceReservationArea.WorldBounds expectedReservationBounds =
                physicalSourcePlacement
                        .placementProfile()
                        .reservationArea()
                        .resolve(
                                physicalSourcePlacement.origin(),
                                physicalSourcePlacement.facing()
                        );

        if (!expectedReservationBounds.equals(
                physicalSourcePlacement
                        .reservationBounds()
        )) {
            throw new IllegalArgumentException(
                    "Physical source placement for demand "
                            + physicalSourceDemand
                            .getPhysicalSourceDemandId()
                            + " contains reservation bounds that do not "
                            + "match its origin, facing and profile."
            );
        }

        /*
         * Only the reservation centre must be inside the circular envelope.
         * The complete source reservation is deliberately permitted to
         * protrude beyond it.
         */
        if (!sourceGroupEnvelope.containsReservationCentre(
                physicalSourcePlacement
                        .reservationBounds()
        )) {
            throw new IllegalArgumentException(
                    "Physical source placement for demand "
                            + physicalSourceDemand
                            .getPhysicalSourceDemandId()
                            + " lies outside its source-group centre "
                            + "envelope."
            );
        }

        Set<UUID> expectedSourceCompositionIds =
                new HashSet<>(
                        physicalSourceDemand
                                .getSourceCompositionIds()
                );

        Set<UUID> actualSourceCompositionIds =
                new HashSet<>(
                        physicalSourcePlacement
                                .sourceCompositionIds()
                );

        if (!expectedSourceCompositionIds.equals(
                actualSourceCompositionIds
        )) {
            throw new IllegalArgumentException(
                    "Physical source placement for demand "
                            + physicalSourceDemand
                            .getPhysicalSourceDemandId()
                            + " has different source-composition bindings "
                            + "from its complete lifecycle demand."
            );
        }
    }

    /**
     * Sources inside one group may be close together, but their complete
     * physical reservations may not overlap.
     *
     * The additional cross-group buffer is intentionally not checked here.
     * SourcePlacementPlanner checks that against drafts belonging to other
     * source groups and other reserved incursions.
     */
    private static void validateInternalReservationSeparation(
            List<PhysicalSourcePlacementDraft>
                    physicalSourcePlacements
    ) {
        for (int firstIndex = 0;
             firstIndex < physicalSourcePlacements.size();
             firstIndex++) {

            PhysicalSourcePlacementDraft firstPlacement =
                    physicalSourcePlacements.get(
                            firstIndex
                    );

            for (int secondIndex = firstIndex + 1;
                 secondIndex < physicalSourcePlacements.size();
                 secondIndex++) {

                PhysicalSourcePlacementDraft secondPlacement =
                        physicalSourcePlacements.get(
                                secondIndex
                        );

                if (firstPlacement
                        .reservationBounds()
                        .overlaps(
                                secondPlacement
                                        .reservationBounds()
                        )) {
                    throw new IllegalArgumentException(
                            "Physical source placements "
                                    + firstPlacement
                                    .physicalSourceDemandId()
                                    + " and "
                                    + secondPlacement
                                    .physicalSourceDemandId()
                                    + " overlap inside source-group demand "
                                    + firstPlacement
                                    .sourceGroupDemandId()
                                    + "."
                    );
                }
            }
        }
    }

    private static Map<
            UUID,
            SourceGroupPlacementDemand.PhysicalSourceDemand
            > indexPhysicalSourceDemands(
            SourceGroupPlacementDemand sourceGroupDemand
    ) {
        Map<
                UUID,
                SourceGroupPlacementDemand.PhysicalSourceDemand
                > physicalSourceDemandsById =
                new HashMap<>();

        for (SourceGroupPlacementDemand.PhysicalSourceDemand
                physicalSourceDemand
                : sourceGroupDemand.getPhysicalSourceDemands()) {

            if (physicalSourceDemand == null) {
                throw new IllegalArgumentException(
                        "Source-group demand cannot contain a null physical "
                                + "source demand."
                );
            }

            SourceGroupPlacementDemand.PhysicalSourceDemand previousDemand =
                    physicalSourceDemandsById.put(
                            physicalSourceDemand
                                    .getPhysicalSourceDemandId(),
                            physicalSourceDemand
                    );

            if (previousDemand != null) {
                throw new IllegalArgumentException(
                        "Source-group demand contains duplicate physical "
                                + "source-demand ID "
                                + physicalSourceDemand
                                .getPhysicalSourceDemandId()
                                + "."
                );
            }
        }

        return physicalSourceDemandsById;
    }

    /**
     * Temporary planned position for one persistent physical source.
     *
     * The source-composition IDs include every wave-specific composition that
     * will use this physical source during the incursion.
     */
    public record PhysicalSourcePlacementDraft(
            UUID sourceGroupDemandId,
            UUID physicalSourceDemandId,
            SourcePlacementProfile placementProfile,
            BlockPos origin,
            Direction facing,
            SourceReservationArea.WorldBounds reservationBounds,
            List<UUID> sourceCompositionIds,
            SiteQuality siteQuality
    ) {

        public PhysicalSourcePlacementDraft {
            if (sourceGroupDemandId == null) {
                throw new IllegalArgumentException(
                        "Physical source draft group-demand ID cannot be "
                                + "null."
                );
            }

            if (physicalSourceDemandId == null) {
                throw new IllegalArgumentException(
                        "Physical source draft demand ID cannot be null."
                );
            }

            if (placementProfile == null) {
                throw new IllegalArgumentException(
                        "Physical source draft placement profile cannot be "
                                + "null."
                );
            }

            if (origin == null) {
                throw new IllegalArgumentException(
                        "Physical source draft origin cannot be null."
                );
            }

            if (facing == null
                    || facing.getAxis().isVertical()) {
                throw new IllegalArgumentException(
                        "Physical source draft facing must be horizontal."
                );
            }

            if (reservationBounds == null) {
                throw new IllegalArgumentException(
                        "Physical source draft reservation bounds cannot be "
                                + "null."
                );
            }

            if (sourceCompositionIds == null
                    || sourceCompositionIds.isEmpty()) {
                throw new IllegalArgumentException(
                        "Physical source draft requires at least one "
                                + "source-composition binding."
                );
            }

            Set<UUID> uniqueSourceCompositionIds =
                    new HashSet<>();

            for (UUID sourceCompositionId
                    : sourceCompositionIds) {

                if (sourceCompositionId == null) {
                    throw new IllegalArgumentException(
                            "Physical source draft cannot contain a null "
                                    + "source-composition ID."
                    );
                }

                if (!uniqueSourceCompositionIds.add(
                        sourceCompositionId
                )) {
                    throw new IllegalArgumentException(
                            "Physical source draft contains duplicate "
                                    + "source-composition ID "
                                    + sourceCompositionId
                                    + "."
                    );
                }
            }

            if (siteQuality == null) {
                throw new IllegalArgumentException(
                        "Physical source draft site quality cannot be null."
                );
            }

            origin =
                    origin.immutable();

            sourceCompositionIds =
                    List.copyOf(
                            sourceCompositionIds
                    );
        }
    }

    /**
     * Whether the selected source site already satisfies the authored terrain
     * preference or merely permits safe runtime preparation.
     */
    public enum SiteQuality {
        PREFERRED,
        PREPARABLE
    }
}