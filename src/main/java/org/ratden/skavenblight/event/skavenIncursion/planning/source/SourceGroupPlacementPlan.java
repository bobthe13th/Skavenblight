package org.ratden.skavenblight.event.skavenIncursion.planning.source;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.ratden.skavenblight.event.skavenIncursion.planning.composition.SourceGroupComposition;
import org.ratden.skavenblight.event.skavenIncursion.planning.composition.SourceGroupRules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Physical placement result for one persistent source group.
 *
 * One physical source group may support source-group compositions from
 * several waves. The contained SourcePlacementPlans represent the persistent
 * physical sources that execute those compositions.
 *
 * Source-group load is calculated only from the distinct physical sources in
 * this group. Reusing an existing source during a later wave adds no further
 * load. Creating another physical source does.
 *
 * The source-group envelope gives the group anchor explicit spatial meaning.
 * It is a circular horizontal envelope within which source reservation
 * centres are intended to be placed. Complete source reservations may
 * protrude beyond the envelope.
 *
 * Group rules and spatial rules are inherited when the physical group is
 * created. Later compositions bound to the group must use the same load rules
 * and source role.
 */
public class SourceGroupPlacementPlan {

    private final UUID sourceGroupPlacementId;
    private final UUID frontId;

    private final List<UUID> sourceGroupCompositionIds;

    private final SourceGroupRules sourceGroupRules;
    private final SourceGroupSpatialRules sourceGroupSpatialRules;
    private final SourceGroupEnvelope sourceGroupEnvelope;

    private final SourceRole sourceRole;

    private final List<SourcePlacementPlan> sourcePlacementPlans;

    /**
     * Compatibility constructor that derives an initial envelope from the
     * supplied source-group composition and creates a fresh structural ID.
     *
     * Current adaptive planning normally uses the full envelope constructor.
     * This path remains available for callers that only possess an anchor and
     * one initial composition.
     */
    public SourceGroupPlacementPlan(
            UUID frontId,
            SourceGroupComposition initialSourceGroupComposition,
            SourceRole sourceRole,
            BlockPos anchorPos
    ) {
        this(
                UUID.randomUUID(),
                frontId,
                initialSourceGroupComposition,
                sourceRole,
                SourceGroupSpatialRules.STANDARD,
                createCompatibilityEnvelope(
                        initialSourceGroupComposition,
                        anchorPos,
                        SourceGroupSpatialRules.STANDARD
                )
        );
    }

    /**
     * Creates one new physical source group using an already-selected final
     * envelope and a fresh structural ID.
     */
    public SourceGroupPlacementPlan(
            UUID frontId,
            SourceGroupComposition initialSourceGroupComposition,
            SourceRole sourceRole,
            SourceGroupSpatialRules sourceGroupSpatialRules,
            SourceGroupEnvelope sourceGroupEnvelope
    ) {
        this(
                UUID.randomUUID(),
                frontId,
                initialSourceGroupComposition,
                sourceRole,
                sourceGroupSpatialRules,
                sourceGroupEnvelope
        );
    }

    /**
     * Creates one physical source group using an existing structural ID.
     *
     * This constructor is intended for immutable plan restoration. Later
     * source-group composition bindings and physical source placements must
     * still be added through their ordinary validated methods.
     */
    public SourceGroupPlacementPlan(
            UUID sourceGroupPlacementId,
            UUID frontId,
            SourceGroupComposition initialSourceGroupComposition,
            SourceRole sourceRole,
            SourceGroupSpatialRules sourceGroupSpatialRules,
            SourceGroupEnvelope sourceGroupEnvelope
    ) {
        if (sourceGroupPlacementId == null) {
            throw new IllegalArgumentException(
                    "Source-group placement ID cannot be null."
            );
        }

        if (frontId == null) {
            throw new IllegalArgumentException(
                    "Front ID cannot be null."
            );
        }

        if (initialSourceGroupComposition == null) {
            throw new IllegalArgumentException(
                    "Initial source-group composition cannot be null."
            );
        }

        if (initialSourceGroupComposition.isEmpty()) {
            throw new IllegalArgumentException(
                    "Initial source-group composition cannot be empty."
            );
        }

        if (sourceRole == null) {
            throw new IllegalArgumentException(
                    "Source role cannot be null."
            );
        }

        if (initialSourceGroupComposition.getSourceRole()
                != sourceRole) {

            throw new IllegalArgumentException(
                    "Initial source-group composition role "
                            + initialSourceGroupComposition.getSourceRole()
                            + " does not match physical source-group role "
                            + sourceRole
                            + "."
            );
        }

        if (sourceGroupSpatialRules == null) {
            throw new IllegalArgumentException(
                    "Source-group spatial rules cannot be null."
            );
        }

        if (sourceGroupEnvelope == null) {
            throw new IllegalArgumentException(
                    "Source-group envelope cannot be null."
            );
        }

        validateEnvelopeAgainstSpatialRules(
                sourceGroupEnvelope,
                sourceGroupSpatialRules
        );

        this.sourceGroupPlacementId =
                sourceGroupPlacementId;

        this.frontId =
                frontId;

        this.sourceGroupCompositionIds =
                new ArrayList<>();

        this.sourceGroupRules =
                initialSourceGroupComposition.getSourceGroupRules();

        this.sourceGroupSpatialRules =
                sourceGroupSpatialRules;

        this.sourceGroupEnvelope =
                sourceGroupEnvelope;

        this.sourceRole =
                sourceRole;

        this.sourcePlacementPlans =
                new ArrayList<>();

        bindSourceGroupComposition(
                initialSourceGroupComposition
        );
    }

    public UUID getSourceGroupPlacementId() {
        return sourceGroupPlacementId;
    }

    public UUID getFrontId() {
        return frontId;
    }

    public SourceGroupRules getSourceGroupRules() {
        return sourceGroupRules;
    }

    public int getMaximumSourceGroupLoad() {
        return sourceGroupRules.maximumLoad();
    }

    public SourceGroupSpatialRules getSourceGroupSpatialRules() {
        return sourceGroupSpatialRules;
    }

    public SourceGroupEnvelope getSourceGroupEnvelope() {
        return sourceGroupEnvelope;
    }

    /**
     * Returns the conceptual centre of this physical source group.
     *
     * This is no longer merely the first source position. It is the centre of
     * the group's circular source-centre envelope.
     */
    public BlockPos getAnchorPos() {
        return sourceGroupEnvelope.centre();
    }

    public int getEnvelopeRadius() {
        return sourceGroupEnvelope.radius();
    }

    public SourceRole getSourceRole() {
        return sourceRole;
    }

    /**
     * Returns whether a source block centre lies within this group's circular
     * source-centre envelope.
     */
    public boolean containsSourceCentre(
            BlockPos sourceCentre
    ) {
        return sourceGroupEnvelope.containsSourceCentre(
                sourceCentre
        );
    }

    /**
     * Returns whether the centre of a complete source reservation lies inside
     * this group's envelope.
     *
     * The complete reservation is allowed to protrude beyond the envelope.
     */
    public boolean containsReservationCentre(
            SourceReservationArea.WorldBounds reservationBounds
    ) {
        return sourceGroupEnvelope.containsReservationCentre(
                reservationBounds
        );
    }

    /**
     * Binds another wave's source-group composition to this persistent
     * physical source group.
     *
     * A physical source group should normally receive no more than one
     * composition from a particular wave. SourcePlacementPlanner enforces
     * that wave-specific restriction because this object does not own wave
     * indexes.
     */
    public void bindSourceGroupComposition(
            SourceGroupComposition sourceGroupComposition
    ) {
        if (sourceGroupComposition == null) {
            throw new IllegalArgumentException(
                    "Source-group composition cannot be null."
            );
        }

        if (sourceGroupComposition.isEmpty()) {
            throw new IllegalArgumentException(
                    "Source-group composition cannot be empty."
            );
        }

        if (!sourceGroupRules.equals(
                sourceGroupComposition.getSourceGroupRules()
        )) {
            throw new IllegalArgumentException(
                    "Source-group composition "
                            + sourceGroupComposition
                            .getSourceGroupCompositionId()
                            + " uses different source-group rules from "
                            + "physical source group "
                            + sourceGroupPlacementId
                            + "."
            );
        }

        if (sourceGroupComposition.getSourceRole()
                != sourceRole) {
            throw new IllegalArgumentException(
                    "Source-group composition "
                            + sourceGroupComposition
                            .getSourceGroupCompositionId()
                            + " has role "
                            + sourceGroupComposition.getSourceRole()
                            + " but physical source group "
                            + sourceGroupPlacementId
                            + " has role "
                            + sourceRole
                            + "."
            );
        }

        UUID sourceGroupCompositionId =
                sourceGroupComposition
                        .getSourceGroupCompositionId();

        if (sourceGroupCompositionIds.contains(
                sourceGroupCompositionId
        )) {
            throw new IllegalArgumentException(
                    "Source-group placement "
                            + sourceGroupPlacementId
                            + " is already bound to source-group composition "
                            + sourceGroupCompositionId
                            + "."
            );
        }

        sourceGroupCompositionIds.add(
                sourceGroupCompositionId
        );
    }

    public List<UUID> getSourceGroupCompositionIds() {
        return Collections.unmodifiableList(
                sourceGroupCompositionIds
        );
    }

    public boolean isBoundToSourceGroupComposition(
            UUID sourceGroupCompositionId
    ) {
        return sourceGroupCompositionId != null
                && sourceGroupCompositionIds.contains(
                sourceGroupCompositionId
        );
    }

    public int getBoundCompositionCount() {
        return sourceGroupCompositionIds.size();
    }

    /**
     * Returns the load contributed by the distinct physical sources currently
     * belonging to this group.
     *
     * Composition bindings from later waves do not increase this value unless
     * those waves require another physical source to be created.
     */
    public int getTotalSourceGroupLoad() {
        int totalLoad =
                0;

        for (SourcePlacementPlan sourcePlacementPlan
                : sourcePlacementPlans) {

            totalLoad +=
                    sourcePlacementPlan
                            .getPlacementProfile()
                            .sourceGroupLoadCost();
        }

        return totalLoad;
    }

    public int getRemainingSourceGroupLoad() {
        return sourceGroupRules.getRemainingLoad(
                getTotalSourceGroupLoad()
        );
    }

    public boolean isWithinSourceGroupLoadLimit() {
        return getTotalSourceGroupLoad()
                <= getMaximumSourceGroupLoad();
    }

    /**
     * Returns whether one additional physical source using this placement
     * profile can join the group.
     */
    public boolean canFitNewSource(
            SourcePlacementProfile placementProfile
    ) {
        if (placementProfile == null) {
            return false;
        }

        return sourceGroupRules.canFit(
                getTotalSourceGroupLoad(),
                placementProfile.sourceGroupLoadCost()
        );
    }

    /**
     * Resolves the placement profile required by a source composition and
     * determines whether creating a new physical source for it would fit.
     *
     * This does not account for possible reuse of an existing compatible
     * source. SourcePlacementPlanner should evaluate reuse before deciding
     * that another physical source is required.
     */
    public boolean canFitNewSource(
            SourceGroupComposition.SourceComposition sourceComposition
    ) {
        if (sourceComposition == null) {
            return false;
        }

        if (sourceComposition.getSourceRole()
                != sourceRole) {
            return false;
        }

        SourcePlacementProfile placementProfile =
                SourcePlacementProfileCatalogue.get(
                        sourceComposition.getRequiredSourceType(),
                        sourceComposition.getRequiredSourceSize()
                );

        return placementProfile != null
                && canFitNewSource(
                placementProfile
        );
    }

    public boolean canFitNewSource(
            SourcePlacementPlan sourcePlacementPlan
    ) {
        if (sourcePlacementPlan == null) {
            return false;
        }

        if (sourcePlacementPlan.getSourceRole()
                != sourceRole) {
            return false;
        }

        return canFitNewSource(
                sourcePlacementPlan.getPlacementProfile()
        );
    }

    /**
     * Adds an already-created physical source placement to this group.
     *
     * The placement must belong to this group, use the group's source role,
     * have a unique placement ID and fit inside the remaining group load.
     *
     * Envelope containment is validated by the source-placement planner and
     * final plan validation because the final placed position is restored or
     * assigned separately from construction.
     */
    public void addSourcePlacementPlan(
            SourcePlacementPlan sourcePlacementPlan
    ) {
        if (sourcePlacementPlan == null) {
            throw new IllegalArgumentException(
                    "Source placement plan cannot be null."
            );
        }

        if (!sourceGroupPlacementId.equals(
                sourcePlacementPlan.getSourceGroupPlacementId()
        )) {
            throw new IllegalArgumentException(
                    "Source placement belongs to a different source group."
            );
        }

        if (sourcePlacementPlan.getSourceRole()
                != sourceRole) {
            throw new IllegalArgumentException(
                    "Source placement role "
                            + sourcePlacementPlan.getSourceRole()
                            + " does not match source-group role "
                            + sourceRole
                            + "."
            );
        }

        for (SourcePlacementPlan existingPlacement
                : sourcePlacementPlans) {

            if (existingPlacement
                    .getSourcePlacementId()
                    .equals(
                            sourcePlacementPlan
                                    .getSourcePlacementId()
                    )) {
                throw new IllegalArgumentException(
                        "Source group already contains source-placement ID "
                                + sourcePlacementPlan
                                .getSourcePlacementId()
                                + "."
                );
            }
        }

        if (!canFitNewSource(
                sourcePlacementPlan
        )) {
            throw new IllegalArgumentException(
                    "Adding source placement "
                            + sourcePlacementPlan
                            .getSourcePlacementId()
                            + " with load "
                            + sourcePlacementPlan
                            .getPlacementProfile()
                            .sourceGroupLoadCost()
                            + " would exceed physical source group "
                            + sourceGroupPlacementId
                            + "'s maximum load of "
                            + getMaximumSourceGroupLoad()
                            + "."
            );
        }

        sourcePlacementPlans.add(
                sourcePlacementPlan
        );
    }

    /**
     * Creates and adds one new physical source placement for a planned source
     * composition.
     */
    public SourcePlacementPlan createSourcePlacementPlan(
            SourceGroupComposition.SourceComposition sourceComposition,
            SourcePlacementProfile placementProfile,
            Direction facing,
            BlockPos sourceAnchorPos
    ) {
        if (sourceComposition == null) {
            throw new IllegalArgumentException(
                    "Source composition cannot be null."
            );
        }

        if (sourceComposition.getSourceRole()
                != sourceRole) {
            throw new IllegalArgumentException(
                    "Source composition role "
                            + sourceComposition.getSourceRole()
                            + " does not match physical source-group role "
                            + sourceRole
                            + "."
            );
        }

        if (placementProfile == null) {
            throw new IllegalArgumentException(
                    "Source placement profile cannot be null."
            );
        }

        if (!canFitNewSource(
                placementProfile
        )) {
            throw new IllegalArgumentException(
                    "Source placement profile load "
                            + placementProfile.sourceGroupLoadCost()
                            + " would exceed physical source group "
                            + sourceGroupPlacementId
                            + "'s maximum load of "
                            + getMaximumSourceGroupLoad()
                            + "."
            );
        }

        if (facing == null) {
            throw new IllegalArgumentException(
                    "Source facing cannot be null."
            );
        }

        if (sourceAnchorPos == null) {
            throw new IllegalArgumentException(
                    "Source anchor position cannot be null."
            );
        }

        SourcePlacementPlan sourcePlacementPlan =
                new SourcePlacementPlan(
                        sourceGroupPlacementId,
                        sourceComposition.getSourceCompositionId(),
                        sourceComposition.getRequiredSourceType(),
                        sourceComposition.getRequiredSourceSize(),
                        sourceComposition.getSourceRole(),
                        placementProfile,
                        facing,
                        sourceAnchorPos
                );

        addSourcePlacementPlan(
                sourcePlacementPlan
        );

        return sourcePlacementPlan;
    }

    public List<SourcePlacementPlan> getSourcePlacementPlans() {
        return Collections.unmodifiableList(
                sourcePlacementPlans
        );
    }

    public boolean isEmpty() {
        return sourcePlacementPlans.isEmpty();
    }

    public int getSourceCount() {
        return sourcePlacementPlans.size();
    }

    public int getTotalCapacityUnits() {
        int total =
                0;

        for (SourcePlacementPlan sourcePlacementPlan
                : sourcePlacementPlans) {

            total +=
                    sourcePlacementPlan.getCapacityUnits();
        }

        return total;
    }

    /**
     * Derives a compatibility envelope from one initial composition.
     *
     * This estimate sees only the supplied composition. Adaptive all-wave
     * planning should normally provide its completed envelope explicitly.
     */
    private static SourceGroupEnvelope createCompatibilityEnvelope(
            SourceGroupComposition sourceGroupComposition,
            BlockPos anchorPos,
            SourceGroupSpatialRules spatialRules
    ) {
        if (sourceGroupComposition == null) {
            throw new IllegalArgumentException(
                    "Source-group composition cannot be null."
            );
        }

        if (sourceGroupComposition.isEmpty()) {
            throw new IllegalArgumentException(
                    "Source-group composition cannot be empty."
            );
        }

        if (anchorPos == null) {
            throw new IllegalArgumentException(
                    "Source-group anchor position cannot be null."
            );
        }

        if (spatialRules == null) {
            throw new IllegalArgumentException(
                    "Source-group spatial rules cannot be null."
            );
        }

        List<SourcePlacementProfile> placementProfiles =
                new ArrayList<>();

        for (SourceGroupComposition.SourceComposition sourceComposition
                : sourceGroupComposition.getSourceCompositions()) {

            placementProfiles.add(
                    SourcePlacementProfileCatalogue.require(
                            sourceComposition.getRequiredSourceType(),
                            sourceComposition.getRequiredSourceSize()
                    )
            );
        }

        int initialRadius =
                spatialRules.calculateInitialRadius(
                        placementProfiles
                );

        return new SourceGroupEnvelope(
                anchorPos,
                initialRadius
        );
    }

    private static void validateEnvelopeAgainstSpatialRules(
            SourceGroupEnvelope sourceGroupEnvelope,
            SourceGroupSpatialRules sourceGroupSpatialRules
    ) {
        int envelopeRadius =
                sourceGroupEnvelope.radius();

        if (envelopeRadius
                < sourceGroupSpatialRules.minimumInitialRadius()) {
            throw new IllegalArgumentException(
                    "Source-group envelope radius "
                            + envelopeRadius
                            + " is smaller than the authored minimum of "
                            + sourceGroupSpatialRules.minimumInitialRadius()
                            + "."
            );
        }

        if (envelopeRadius
                > sourceGroupSpatialRules.maximumRadius()) {
            throw new IllegalArgumentException(
                    "Source-group envelope radius "
                            + envelopeRadius
                            + " exceeds the authored maximum of "
                            + sourceGroupSpatialRules.maximumRadius()
                            + "."
            );
        }
    }
}