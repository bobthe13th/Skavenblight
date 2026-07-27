package org.ratden.skavenblight.event.skavenIncursion.planning.persistence;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.ratden.skavenblight.event.skavenIncursion.planning.composition.SourceGroupComposition;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceGroupEnvelope;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceGroupPlacementPlan;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceGroupSpatialRules;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourcePlacementPlan;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourcePlacementProfile;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceReservationArea;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceRole;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceSize;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable persistence-facing snapshot of one physical source group.
 *
 * This snapshot preserves:
 *
 * - physical source-group identity;
 * - owning front identity;
 * - ordered source-group composition bindings;
 * - source-group load rules;
 * - adaptive spatial rules;
 * - the final source-group envelope;
 * - source role;
 * - every persistent physical source;
 * - complete source reservation and preparation profiles;
 * - source positions and facings;
 * - ordered multi-wave source-composition bindings.
 *
 * Every structural UUID survives restoration. No new planning identity is
 * generated.
 *
 * This class represents immutable planning data only. NBT encoding is handled by the top-level persistent-incursion codec.
 */
public record SourceGroupPlacementSnapshot(
        UUID sourceGroupPlacementId,
        UUID frontId,
        List<UUID> sourceGroupCompositionIds,
        int maximumLoad,
        SourceGroupSpatialRulesSnapshot spatialRulesSnapshot,
        SourceGroupEnvelopeSnapshot envelopeSnapshot,
        SourceRole sourceRole,
        List<SourcePlacementSnapshot> sourcePlacementSnapshots
) {

    public SourceGroupPlacementSnapshot {
        if (sourceGroupPlacementId == null) {
            throw new IllegalArgumentException(
                    "Source-group placement snapshot ID cannot be null."
            );
        }

        if (frontId == null) {
            throw new IllegalArgumentException(
                    "Source-group placement front ID cannot be null."
            );
        }

        if (sourceGroupCompositionIds == null
                || sourceGroupCompositionIds.isEmpty()) {

            throw new IllegalArgumentException(
                    "Source-group placement snapshot requires at least one "
                            + "source-group composition binding."
            );
        }

        if (maximumLoad <= 0) {
            throw new IllegalArgumentException(
                    "Source-group placement maximum load must be greater "
                            + "than zero."
            );
        }

        if (spatialRulesSnapshot == null) {
            throw new IllegalArgumentException(
                    "Source-group spatial-rules snapshot cannot be null."
            );
        }

        if (envelopeSnapshot == null) {
            throw new IllegalArgumentException(
                    "Source-group envelope snapshot cannot be null."
            );
        }

        if (sourceRole == null) {
            throw new IllegalArgumentException(
                    "Source-group placement role cannot be null."
            );
        }

        if (sourcePlacementSnapshots == null
                || sourcePlacementSnapshots.isEmpty()) {

            throw new IllegalArgumentException(
                    "Source-group placement snapshot requires at least one "
                            + "physical source."
            );
        }

        sourceGroupCompositionIds =
                List.copyOf(
                        sourceGroupCompositionIds
                );

        sourcePlacementSnapshots =
                List.copyOf(
                        sourcePlacementSnapshots
                );

        validateUniqueIds(
                sourceGroupCompositionIds,
                "source-group composition"
        );

        validatePhysicalSources(
                sourceGroupPlacementId,
                maximumLoad,
                spatialRulesSnapshot,
                envelopeSnapshot,
                sourceRole,
                sourcePlacementSnapshots
        );
    }

    /**
     * Captures one complete physical source group.
     */
    public static SourceGroupPlacementSnapshot capture(
            SourceGroupPlacementPlan sourceGroupPlacementPlan
    ) {
        if (sourceGroupPlacementPlan == null) {
            throw new IllegalArgumentException(
                    "Source-group placement plan cannot be null."
            );
        }

        if (sourceGroupPlacementPlan.isEmpty()) {
            throw new IllegalArgumentException(
                    "Source-group placement "
                            + sourceGroupPlacementPlan
                            .getSourceGroupPlacementId()
                            + " contains no physical sources."
            );
        }

        List<SourcePlacementSnapshot> capturedSources =
                new ArrayList<>();

        for (SourcePlacementPlan sourcePlacementPlan
                : sourceGroupPlacementPlan
                .getSourcePlacementPlans()) {

            capturedSources.add(
                    SourcePlacementSnapshot.capture(
                            sourcePlacementPlan
                    )
            );
        }

        return new SourceGroupPlacementSnapshot(
                sourceGroupPlacementPlan.getSourceGroupPlacementId(),
                sourceGroupPlacementPlan.getFrontId(),
                sourceGroupPlacementPlan.getSourceGroupCompositionIds(),
                sourceGroupPlacementPlan.getMaximumSourceGroupLoad(),
                SourceGroupSpatialRulesSnapshot.capture(
                        sourceGroupPlacementPlan
                                .getSourceGroupSpatialRules()
                ),
                SourceGroupEnvelopeSnapshot.capture(
                        sourceGroupPlacementPlan
                                .getSourceGroupEnvelope()
                ),
                sourceGroupPlacementPlan.getSourceRole(),
                capturedSources
        );
    }

    /**
     * Restores one physical source group against already-restored immutable
     * source-group compositions.
     *
     * Composition restoration must happen first because
     * SourceGroupPlacementPlan inherits its load rules and role from the
     * initial bound SourceGroupComposition.
     */
    public SourceGroupPlacementPlan restore(
            Map<UUID, SourceGroupComposition>
                    sourceGroupCompositionsById
    ) {
        if (sourceGroupCompositionsById == null) {
            throw new IllegalArgumentException(
                    "Source-group composition map cannot be null."
            );
        }

        List<SourceGroupComposition> boundGroupCompositions =
                resolveBoundGroupCompositions(
                        sourceGroupCompositionsById
                );

        SourceGroupComposition initialGroupComposition =
                boundGroupCompositions.getFirst();

        validateBoundGroupComposition(
                initialGroupComposition
        );

        SourceGroupPlacementPlan restoredGroup =
                new SourceGroupPlacementPlan(
                        sourceGroupPlacementId,
                        frontId,
                        initialGroupComposition,
                        sourceRole,
                        spatialRulesSnapshot.restore(),
                        envelopeSnapshot.restore()
                );

        for (int compositionIndex = 1;
             compositionIndex < boundGroupCompositions.size();
             compositionIndex++) {

            SourceGroupComposition sourceGroupComposition =
                    boundGroupCompositions.get(
                            compositionIndex
                    );

            validateBoundGroupComposition(
                    sourceGroupComposition
            );

            restoredGroup.bindSourceGroupComposition(
                    sourceGroupComposition
            );
        }

        Map<UUID, SourceGroupComposition.SourceComposition>
                sourceCompositionsById =
                indexBoundSourceCompositions(
                        boundGroupCompositions
                );

        Set<UUID> restoredSourceCompositionBindings =
                new LinkedHashSet<>();

        for (SourcePlacementSnapshot sourcePlacementSnapshot
                : sourcePlacementSnapshots) {

            validateSourceCompositionBindings(
                    sourcePlacementSnapshot,
                    sourceCompositionsById,
                    restoredSourceCompositionBindings
            );

            SourcePlacementPlan restoredSource =
                    sourcePlacementSnapshot.restore();

            restoredGroup.addSourcePlacementPlan(
                    restoredSource
            );
        }

        Set<UUID> expectedSourceCompositionIds =
                new LinkedHashSet<>(
                        sourceCompositionsById.keySet()
                );

        if (!restoredSourceCompositionBindings.equals(
                expectedSourceCompositionIds
        )) {
            Set<UUID> missingSourceCompositionIds =
                    new LinkedHashSet<>(
                            expectedSourceCompositionIds
                    );

            missingSourceCompositionIds.removeAll(
                    restoredSourceCompositionBindings
            );

            Set<UUID> unexpectedSourceCompositionIds =
                    new LinkedHashSet<>(
                            restoredSourceCompositionBindings
                    );

            unexpectedSourceCompositionIds.removeAll(
                    expectedSourceCompositionIds
            );

            throw new IllegalArgumentException(
                    "Restored physical source group "
                            + sourceGroupPlacementId
                            + " does not exactly cover the source "
                            + "compositions belonging to its bound group "
                            + "compositions. Missing: "
                            + missingSourceCompositionIds
                            + ". Unexpected: "
                            + unexpectedSourceCompositionIds
                            + "."
            );
        }

        SourceGroupPlacementSnapshot reconstructedSnapshot =
                capture(
                        restoredGroup
                );

        if (!equals(
                reconstructedSnapshot
        )) {
            throw new IllegalArgumentException(
                    "Restored source-group placement "
                            + sourceGroupPlacementId
                            + " does not exactly match its saved snapshot."
            );
        }

        return restoredGroup;
    }

    public int getPhysicalSourceCount() {
        return sourcePlacementSnapshots.size();
    }

    public int getTotalSourceGroupLoad() {
        int totalLoad =
                0;

        for (SourcePlacementSnapshot sourcePlacementSnapshot
                : sourcePlacementSnapshots) {

            totalLoad +=
                    sourcePlacementSnapshot
                            .placementProfileSnapshot()
                            .sourceGroupLoadCost();
        }

        return totalLoad;
    }

    public int getRemainingSourceGroupLoad() {
        return maximumLoad
                - getTotalSourceGroupLoad();
    }

    public SourcePlacementSnapshot getSourcePlacementSnapshot(
            UUID sourcePlacementId
    ) {
        if (sourcePlacementId == null) {
            return null;
        }

        for (SourcePlacementSnapshot sourcePlacementSnapshot
                : sourcePlacementSnapshots) {

            if (sourcePlacementId.equals(
                    sourcePlacementSnapshot.sourcePlacementId()
            )) {
                return sourcePlacementSnapshot;
            }
        }

        return null;
    }

    private List<SourceGroupComposition>
    resolveBoundGroupCompositions(
            Map<UUID, SourceGroupComposition>
                    sourceGroupCompositionsById
    ) {
        List<SourceGroupComposition> resolvedCompositions =
                new ArrayList<>();

        for (UUID sourceGroupCompositionId
                : sourceGroupCompositionIds) {

            SourceGroupComposition sourceGroupComposition =
                    sourceGroupCompositionsById.get(
                            sourceGroupCompositionId
                    );

            if (sourceGroupComposition == null) {
                throw new IllegalArgumentException(
                        "Source-group placement "
                                + sourceGroupPlacementId
                                + " refers to unavailable source-group "
                                + "composition "
                                + sourceGroupCompositionId
                                + "."
                );
            }

            resolvedCompositions.add(
                    sourceGroupComposition
            );
        }

        return List.copyOf(
                resolvedCompositions
        );
    }

    private void validateBoundGroupComposition(
            SourceGroupComposition sourceGroupComposition
    ) {
        if (sourceGroupComposition == null) {
            throw new IllegalArgumentException(
                    "Bound source-group composition cannot be null."
            );
        }

        if (sourceGroupComposition.isEmpty()) {
            throw new IllegalArgumentException(
                    "Bound source-group composition "
                            + sourceGroupComposition
                            .getSourceGroupCompositionId()
                            + " cannot be empty."
            );
        }

        if (sourceGroupComposition.getMaximumSourceGroupLoad()
                != maximumLoad) {

            throw new IllegalArgumentException(
                    "Source-group placement "
                            + sourceGroupPlacementId
                            + " has maximum load "
                            + maximumLoad
                            + " but bound source-group composition "
                            + sourceGroupComposition
                            .getSourceGroupCompositionId()
                            + " has maximum load "
                            + sourceGroupComposition
                            .getMaximumSourceGroupLoad()
                            + "."
            );
        }

        if (sourceGroupComposition.getSourceRole()
                != sourceRole) {

            throw new IllegalArgumentException(
                    "Source-group placement "
                            + sourceGroupPlacementId
                            + " has role "
                            + sourceRole
                            + " but bound source-group composition "
                            + sourceGroupComposition
                            .getSourceGroupCompositionId()
                            + " has role "
                            + sourceGroupComposition.getSourceRole()
                            + "."
            );
        }
    }

    /**
     * Builds one index of every source-sized composition belonging to the
     * group compositions bound to this physical group.
     */
    private static Map<UUID, SourceGroupComposition.SourceComposition>
    indexBoundSourceCompositions(
            List<SourceGroupComposition> sourceGroupCompositions
    ) {
        LinkedHashMap<UUID, SourceGroupComposition.SourceComposition>
                sourceCompositionsById =
                new LinkedHashMap<>();

        for (SourceGroupComposition sourceGroupComposition
                : sourceGroupCompositions) {

            for (SourceGroupComposition.SourceComposition
                    sourceComposition
                    : sourceGroupComposition
                    .getSourceCompositions()) {

                UUID sourceCompositionId =
                        sourceComposition
                                .getSourceCompositionId();

                SourceGroupComposition.SourceComposition
                        previousComposition =
                        sourceCompositionsById.putIfAbsent(
                                sourceCompositionId,
                                sourceComposition
                        );

                if (previousComposition != null) {
                    throw new IllegalArgumentException(
                            "Physical source group refers to duplicate "
                                    + "source-composition ID "
                                    + sourceCompositionId
                                    + " across its bound source-group "
                                    + "compositions."
                    );
                }
            }
        }

        return Map.copyOf(
                sourceCompositionsById
        );
    }

    /**
     * Validates that one physical source is compatible with every
     * source-sized composition it executes.
     */
    private static void validateSourceCompositionBindings(
            SourcePlacementSnapshot sourcePlacementSnapshot,
            Map<UUID, SourceGroupComposition.SourceComposition>
                    sourceCompositionsById,
            Set<UUID> restoredSourceCompositionBindings
    ) {
        for (UUID sourceCompositionId
                : sourcePlacementSnapshot.sourceCompositionIds()) {

            SourceGroupComposition.SourceComposition sourceComposition =
                    sourceCompositionsById.get(
                            sourceCompositionId
                    );

            if (sourceComposition == null) {
                throw new IllegalArgumentException(
                        "Physical source placement "
                                + sourcePlacementSnapshot
                                .sourcePlacementId()
                                + " refers to source composition "
                                + sourceCompositionId
                                + " outside its bound physical source group."
                );
            }

            if (!restoredSourceCompositionBindings.add(
                    sourceCompositionId
            )) {
                throw new IllegalArgumentException(
                        "Source composition "
                                + sourceCompositionId
                                + " is bound to more than one physical source "
                                + "inside the same source group."
                );
            }

            if (sourcePlacementSnapshot.sourceType()
                    != sourceComposition.getRequiredSourceType()) {

                throw new IllegalArgumentException(
                        "Physical source placement "
                                + sourcePlacementSnapshot
                                .sourcePlacementId()
                                + " has source type "
                                + sourcePlacementSnapshot.sourceType()
                                + " but source composition "
                                + sourceCompositionId
                                + " requires "
                                + sourceComposition.getRequiredSourceType()
                                + "."
                );
            }

            if (!sourcePlacementSnapshot
                    .sourceSize()
                    .canFit(
                            sourceComposition.getRequiredSourceSize()
                    )) {

                throw new IllegalArgumentException(
                        "Physical source placement "
                                + sourcePlacementSnapshot
                                .sourcePlacementId()
                                + " has source size "
                                + sourcePlacementSnapshot.sourceSize()
                                + " but source composition "
                                + sourceCompositionId
                                + " requires "
                                + sourceComposition.getRequiredSourceSize()
                                + "."
                );
            }

            if (sourcePlacementSnapshot.sourceRole()
                    != sourceComposition.getSourceRole()) {

                throw new IllegalArgumentException(
                        "Physical source placement "
                                + sourcePlacementSnapshot
                                .sourcePlacementId()
                                + " has role "
                                + sourcePlacementSnapshot.sourceRole()
                                + " but source composition "
                                + sourceCompositionId
                                + " has role "
                                + sourceComposition.getSourceRole()
                                + "."
                );
            }
        }
    }

    private static void validatePhysicalSources(
            UUID sourceGroupPlacementId,
            int maximumLoad,
            SourceGroupSpatialRulesSnapshot spatialRulesSnapshot,
            SourceGroupEnvelopeSnapshot envelopeSnapshot,
            SourceRole sourceRole,
            List<SourcePlacementSnapshot> sourcePlacementSnapshots
    ) {
        SourceGroupSpatialRules spatialRules =
                spatialRulesSnapshot.restore();

        SourceGroupEnvelope envelope =
                envelopeSnapshot.restore();

        if (envelope.radius()
                < spatialRules.minimumInitialRadius()) {

            throw new IllegalArgumentException(
                    "Source-group envelope radius "
                            + envelope.radius()
                            + " is below the saved minimum radius "
                            + spatialRules.minimumInitialRadius()
                            + "."
            );
        }

        if (envelope.radius()
                > spatialRules.maximumRadius()) {

            throw new IllegalArgumentException(
                    "Source-group envelope radius "
                            + envelope.radius()
                            + " exceeds the saved maximum radius "
                            + spatialRules.maximumRadius()
                            + "."
            );
        }

        Set<UUID> sourcePlacementIds =
                new HashSet<>();

        Set<UUID> sourceCompositionIds =
                new HashSet<>();

        int totalLoad =
                0;

        for (SourcePlacementSnapshot sourcePlacementSnapshot
                : sourcePlacementSnapshots) {

            if (sourcePlacementSnapshot == null) {
                throw new IllegalArgumentException(
                        "Source-group placement snapshot cannot contain a "
                                + "null physical source."
                );
            }

            if (!sourceGroupPlacementId.equals(
                    sourcePlacementSnapshot.sourceGroupPlacementId()
            )) {
                throw new IllegalArgumentException(
                        "Physical source placement "
                                + sourcePlacementSnapshot
                                .sourcePlacementId()
                                + " belongs to source group "
                                + sourcePlacementSnapshot
                                .sourceGroupPlacementId()
                                + " rather than containing source group "
                                + sourceGroupPlacementId
                                + "."
                );
            }

            if (sourcePlacementSnapshot.sourceRole()
                    != sourceRole) {

                throw new IllegalArgumentException(
                        "Physical source placement "
                                + sourcePlacementSnapshot
                                .sourcePlacementId()
                                + " has role "
                                + sourcePlacementSnapshot.sourceRole()
                                + " but its source group has role "
                                + sourceRole
                                + "."
                );
            }

            if (!sourcePlacementIds.add(
                    sourcePlacementSnapshot.sourcePlacementId()
            )) {
                throw new IllegalArgumentException(
                        "Source-group placement snapshot contains duplicate "
                                + "source-placement ID "
                                + sourcePlacementSnapshot
                                .sourcePlacementId()
                                + "."
                );
            }

            for (UUID sourceCompositionId
                    : sourcePlacementSnapshot.sourceCompositionIds()) {

                if (!sourceCompositionIds.add(
                        sourceCompositionId
                )) {
                    throw new IllegalArgumentException(
                            "Source composition "
                                    + sourceCompositionId
                                    + " is bound to more than one physical "
                                    + "source in source group "
                                    + sourceGroupPlacementId
                                    + "."
                    );
                }
            }

            totalLoad +=
                    sourcePlacementSnapshot
                            .placementProfileSnapshot()
                            .sourceGroupLoadCost();

            if (!envelope.containsReservationCentre(
                    sourcePlacementSnapshot.getReservationBounds()
            )) {
                throw new IllegalArgumentException(
                        "Reservation centre for physical source "
                                + sourcePlacementSnapshot
                                .sourcePlacementId()
                                + " lies outside source-group envelope "
                                + sourceGroupPlacementId
                                + "."
                );
            }
        }

        if (totalLoad > maximumLoad) {
            throw new IllegalArgumentException(
                    "Physical source-group load "
                            + totalLoad
                            + " exceeds maximum load "
                            + maximumLoad
                            + "."
            );
        }

        validateSourceReservationsDoNotOverlap(
                sourcePlacementSnapshots
        );
    }

    private static void validateSourceReservationsDoNotOverlap(
            List<SourcePlacementSnapshot> sourcePlacementSnapshots
    ) {
        for (int firstIndex = 0;
             firstIndex < sourcePlacementSnapshots.size();
             firstIndex++) {

            SourcePlacementSnapshot firstSource =
                    sourcePlacementSnapshots.get(
                            firstIndex
                    );

            SourceReservationArea.WorldBounds firstBounds =
                    firstSource.getReservationBounds();

            for (int secondIndex = firstIndex + 1;
                 secondIndex < sourcePlacementSnapshots.size();
                 secondIndex++) {

                SourcePlacementSnapshot secondSource =
                        sourcePlacementSnapshots.get(
                                secondIndex
                        );

                SourceReservationArea.WorldBounds secondBounds =
                        secondSource.getReservationBounds();

                if (firstBounds.overlaps(
                        secondBounds
                )) {
                    throw new IllegalArgumentException(
                            "Physical source placements "
                                    + firstSource.sourcePlacementId()
                                    + " and "
                                    + secondSource.sourcePlacementId()
                                    + " have overlapping reservations."
                    );
                }
            }
        }
    }

    private static void validateUniqueIds(
            List<UUID> ids,
            String description
    ) {
        Set<UUID> uniqueIds =
                new HashSet<>();

        for (UUID id
                : ids) {

            if (id == null) {
                throw new IllegalArgumentException(
                        description
                                + " ID cannot be null."
                );
            }

            if (!uniqueIds.add(
                    id
            )) {
                throw new IllegalArgumentException(
                        "Duplicate "
                                + description
                                + " ID "
                                + id
                                + "."
                );
            }
        }
    }

    /**
     * Immutable snapshot of one persistent physical source.
     */
    public record SourcePlacementSnapshot(
            UUID sourcePlacementId,
            UUID sourceGroupPlacementId,
            List<UUID> sourceCompositionIds,
            SourceType sourceType,
            SourceSize sourceSize,
            SourceRole sourceRole,
            SourcePlacementProfileSnapshot placementProfileSnapshot,
            Direction facing,
            BlockPos anchorPos,
            BlockPos placedPos
    ) {

        public SourcePlacementSnapshot {
            if (sourcePlacementId == null) {
                throw new IllegalArgumentException(
                        "Source-placement snapshot ID cannot be null."
                );
            }

            if (sourceGroupPlacementId == null) {
                throw new IllegalArgumentException(
                        "Source-placement group ID cannot be null."
                );
            }

            if (sourceCompositionIds == null
                    || sourceCompositionIds.isEmpty()) {

                throw new IllegalArgumentException(
                        "Source-placement snapshot requires at least one "
                                + "source-composition binding."
                );
            }

            if (sourceType == null) {
                throw new IllegalArgumentException(
                        "Source-placement type cannot be null."
                );
            }

            if (sourceSize == null) {
                throw new IllegalArgumentException(
                        "Source-placement size cannot be null."
                );
            }

            if (sourceRole == null) {
                throw new IllegalArgumentException(
                        "Source-placement role cannot be null."
                );
            }

            if (placementProfileSnapshot == null) {
                throw new IllegalArgumentException(
                        "Source-placement profile snapshot cannot be null."
                );
            }

            validateHorizontalFacing(
                    facing
            );

            if (anchorPos == null) {
                throw new IllegalArgumentException(
                        "Source-placement anchor position cannot be null."
                );
            }

            if (placedPos == null) {
                throw new IllegalArgumentException(
                        "Completed source-placement snapshot requires a final "
                                + "placed position."
                );
            }

            sourceCompositionIds =
                    List.copyOf(
                            sourceCompositionIds
                    );

            validateUniqueIds(
                    sourceCompositionIds,
                    "source composition"
            );

            anchorPos =
                    anchorPos.immutable();

            placedPos =
                    placedPos.immutable();
        }

        private static SourcePlacementSnapshot capture(
                SourcePlacementPlan sourcePlacementPlan
        ) {
            if (sourcePlacementPlan == null) {
                throw new IllegalArgumentException(
                        "Source placement plan cannot be null."
                );
            }

            if (!sourcePlacementPlan.hasPlacedPos()) {
                throw new IllegalArgumentException(
                        "Source placement "
                                + sourcePlacementPlan
                                .getSourcePlacementId()
                                + " has no final placed position."
                );
            }

            return new SourcePlacementSnapshot(
                    sourcePlacementPlan.getSourcePlacementId(),
                    sourcePlacementPlan.getSourceGroupPlacementId(),
                    sourcePlacementPlan.getSourceCompositionIds(),
                    sourcePlacementPlan.getSourceType(),
                    sourcePlacementPlan.getSourceSize(),
                    sourcePlacementPlan.getSourceRole(),
                    SourcePlacementProfileSnapshot.capture(
                            sourcePlacementPlan
                                    .getPlacementProfile()
                    ),
                    sourcePlacementPlan.getFacing(),
                    sourcePlacementPlan.getAnchorPos(),
                    sourcePlacementPlan.getPlacedPos()
            );
        }

        private SourcePlacementPlan restore() {
            SourcePlacementPlan restoredSource =
                    new SourcePlacementPlan(
                            sourcePlacementId,
                            sourceGroupPlacementId,
                            sourceCompositionIds.getFirst(),
                            sourceType,
                            sourceSize,
                            sourceRole,
                            placementProfileSnapshot.restore(),
                            facing,
                            anchorPos
                    );

            for (int bindingIndex = 1;
                 bindingIndex < sourceCompositionIds.size();
                 bindingIndex++) {

                restoredSource.bindSourceComposition(
                        sourceCompositionIds.get(
                                bindingIndex
                        )
                );
            }

            restoredSource.setPlacedPos(
                    placedPos
            );

            SourcePlacementSnapshot reconstructedSnapshot =
                    capture(
                            restoredSource
                    );

            if (!equals(
                    reconstructedSnapshot
            )) {
                throw new IllegalArgumentException(
                        "Restored source placement "
                                + sourcePlacementId
                                + " does not exactly match its saved "
                                + "snapshot."
                );
            }

            return restoredSource;
        }

        public SourceReservationArea.WorldBounds
        getReservationBounds() {
            return placementProfileSnapshot
                    .reservationAreaSnapshot()
                    .restore()
                    .resolve(
                            placedPos,
                            facing
                    );
        }

        public SourceReservationArea.WorldBounds
        getPreparationBounds() {
            return placementProfileSnapshot
                    .preparationAreaSnapshot()
                    .restore()
                    .resolve(
                            placedPos,
                            facing
                    );
        }

        private static void validateHorizontalFacing(
                Direction facing
        ) {
            if (facing == null) {
                throw new IllegalArgumentException(
                        "Source-placement facing cannot be null."
                );
            }

            if (facing.getAxis().isVertical()) {
                throw new IllegalArgumentException(
                        "Source-placement facing must be horizontal."
                );
            }
        }
    }

    /**
     * Immutable snapshot of one source placement profile.
     *
     * The complete profile is retained rather than looking it up from the
     * current catalogue during restoration. An active incursion therefore
     * keeps the exact physical reservation with which it was planned.
     */
    public record SourcePlacementProfileSnapshot(
            SourceReservationAreaSnapshot reservationAreaSnapshot,
            SourceReservationAreaSnapshot preparationAreaSnapshot,
            int foundationDepth,
            int clearanceHeight,
            int sourceGroupLoadCost
    ) {

        public SourcePlacementProfileSnapshot {
            if (reservationAreaSnapshot == null) {
                throw new IllegalArgumentException(
                        "Reservation-area snapshot cannot be null."
                );
            }

            if (preparationAreaSnapshot == null) {
                throw new IllegalArgumentException(
                        "Preparation-area snapshot cannot be null."
                );
            }

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
                        "Source-group load cost must be greater than zero."
                );
            }

            /*
             * Validate the supplied constructor values directly.
             *
             * Record fields are assigned only after a compact constructor
             * body completes, so calling the instance restore() method here
             * would read uninitialised fields.
             */
            new SourcePlacementProfile(
                    reservationAreaSnapshot.restore(),
                    preparationAreaSnapshot.restore(),
                    foundationDepth,
                    clearanceHeight,
                    sourceGroupLoadCost
            );
        }

        private static SourcePlacementProfileSnapshot capture(
                SourcePlacementProfile placementProfile
        ) {
            if (placementProfile == null) {
                throw new IllegalArgumentException(
                        "Source placement profile cannot be null."
                );
            }

            return new SourcePlacementProfileSnapshot(
                    SourceReservationAreaSnapshot.capture(
                            placementProfile.reservationArea()
                    ),
                    SourceReservationAreaSnapshot.capture(
                            placementProfile.preparationArea()
                    ),
                    placementProfile.foundationDepth(),
                    placementProfile.clearanceHeight(),
                    placementProfile.sourceGroupLoadCost()
            );
        }

        private SourcePlacementProfile restore() {
            return new SourcePlacementProfile(
                    reservationAreaSnapshot.restore(),
                    preparationAreaSnapshot.restore(),
                    foundationDepth,
                    clearanceHeight,
                    sourceGroupLoadCost
            );
        }
    }

    /**
     * Immutable snapshot of one source-relative horizontal area.
     */
    public record SourceReservationAreaSnapshot(
            int minXOffset,
            int maxXOffset,
            int minZOffset,
            int maxZOffset
    ) {

        public SourceReservationAreaSnapshot {
            /*
             * Validate the supplied constructor values directly.
             *
             * Calling the instance restore() method here would read the
             * record fields before Java assigns them.
             */
            new SourceReservationArea(
                    minXOffset,
                    maxXOffset,
                    minZOffset,
                    maxZOffset
            );
        }

        private static SourceReservationAreaSnapshot capture(
                SourceReservationArea reservationArea
        ) {
            if (reservationArea == null) {
                throw new IllegalArgumentException(
                        "Source reservation area cannot be null."
                );
            }

            return new SourceReservationAreaSnapshot(
                    reservationArea.minXOffset(),
                    reservationArea.maxXOffset(),
                    reservationArea.minZOffset(),
                    reservationArea.maxZOffset()
            );
        }

        private SourceReservationArea restore() {
            return new SourceReservationArea(
                    minXOffset,
                    maxXOffset,
                    minZOffset,
                    maxZOffset
            );
        }

        public int width() {
            return maxXOffset
                    - minXOffset
                    + 1;
        }

        public int depth() {
            return maxZOffset
                    - minZOffset
                    + 1;
        }
    }

    /**
     * Immutable snapshot of authored source-group spatial rules.
     */
    public record SourceGroupSpatialRulesSnapshot(
            int minimumInitialRadius,
            int initialRadiusMargin,
            int radiusExpansionStep,
            int maximumRadius,
            int minimumAnchorSeparation,
            int maximumEnvelopeOverlap,
            int crossGroupSourceBuffer
    ) {

        public SourceGroupSpatialRulesSnapshot {
            /*
             * Validate the supplied constructor values directly.
             *
             * Calling the instance restore() method here would read default
             * field values because record fields are assigned after this
             * constructor body.
             */
            new SourceGroupSpatialRules(
                    minimumInitialRadius,
                    initialRadiusMargin,
                    radiusExpansionStep,
                    maximumRadius,
                    minimumAnchorSeparation,
                    maximumEnvelopeOverlap,
                    crossGroupSourceBuffer
            );
        }

        private static SourceGroupSpatialRulesSnapshot capture(
                SourceGroupSpatialRules sourceGroupSpatialRules
        ) {
            if (sourceGroupSpatialRules == null) {
                throw new IllegalArgumentException(
                        "Source-group spatial rules cannot be null."
                );
            }

            return new SourceGroupSpatialRulesSnapshot(
                    sourceGroupSpatialRules.minimumInitialRadius(),
                    sourceGroupSpatialRules.initialRadiusMargin(),
                    sourceGroupSpatialRules.radiusExpansionStep(),
                    sourceGroupSpatialRules.maximumRadius(),
                    sourceGroupSpatialRules.minimumAnchorSeparation(),
                    sourceGroupSpatialRules.maximumEnvelopeOverlap(),
                    sourceGroupSpatialRules.crossGroupSourceBuffer()
            );
        }

        private SourceGroupSpatialRules restore() {
            return new SourceGroupSpatialRules(
                    minimumInitialRadius,
                    initialRadiusMargin,
                    radiusExpansionStep,
                    maximumRadius,
                    minimumAnchorSeparation,
                    maximumEnvelopeOverlap,
                    crossGroupSourceBuffer
            );
        }
    }

    /**
     * Immutable snapshot of one circular physical source-group envelope.
     */
    public record SourceGroupEnvelopeSnapshot(
            BlockPos centre,
            int radius
    ) {

        public SourceGroupEnvelopeSnapshot {
            if (centre == null) {
                throw new IllegalArgumentException(
                        "Source-group envelope centre cannot be null."
                );
            }

            if (radius <= 0) {
                throw new IllegalArgumentException(
                        "Source-group envelope radius must be greater than "
                                + "zero."
                );
            }

            centre =
                    centre.immutable();
        }

        private static SourceGroupEnvelopeSnapshot capture(
                SourceGroupEnvelope sourceGroupEnvelope
        ) {
            if (sourceGroupEnvelope == null) {
                throw new IllegalArgumentException(
                        "Source-group envelope cannot be null."
                );
            }

            return new SourceGroupEnvelopeSnapshot(
                    sourceGroupEnvelope.centre(),
                    sourceGroupEnvelope.radius()
            );
        }

        private SourceGroupEnvelope restore() {
            return new SourceGroupEnvelope(
                    centre,
                    radius
            );
        }
    }
}