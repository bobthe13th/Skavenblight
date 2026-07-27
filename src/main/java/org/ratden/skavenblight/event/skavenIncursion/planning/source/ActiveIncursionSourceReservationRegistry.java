package org.ratden.skavenblight.event.skavenIncursion.planning.source;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.ratden.skavenblight.event.skavenIncursion.planning.IncursionPlan;
import org.ratden.skavenblight.event.skavenIncursion.planning.front.FrontPlan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Stores immutable physical-reservation snapshots for active incursions.
 *
 * These snapshots allow a new incursion to respect physical infrastructure
 * already reserved by another active incursion, including sources that have
 * been planned but have not yet spawned.
 *
 * This registry is authoritative placement state. It is deliberately
 * independent from debug-anchor blocks and their visualisation tracker:
 *
 * - removing a debug anchor does not release physical reservations;
 * - hiding visualisation does not release physical reservations;
 * - reservations remain until the owning incursion reaches final cleanup;
 * - debug cleanup may explicitly clear both systems.
 *
 * Each snapshot contains:
 *
 * - the completed circular envelope for every persistent source group;
 * - the group's spatial rules;
 * - the complete reservation bounds of every persistent physical source;
 * - the structural incursion, front, group and source IDs.
 *
 * The registry stores no references to mutable IncursionPlan objects.
 *
 * All methods are expected to be called from the logical server thread.
 */
public final class ActiveIncursionSourceReservationRegistry {

    private static final Map<
            ResourceKey<Level>,
            Map<UUID, IncursionReservationSnapshot>
            > RESERVATIONS_BY_LEVEL =
            new HashMap<>();

    /**
     * Creates and stores an immutable reservation snapshot from one completed
     * incursion plan.
     *
     * Registering the same incursion ID again in the same level replaces its
     * previous snapshot. Incursion IDs should ordinarily be unique, but
     * replacement makes repeated debug setup deterministic.
     */
    public static IncursionReservationSnapshot register(
            ServerLevel level,
            IncursionPlan incursionPlan
    ) {
        if (level == null) {
            throw new IllegalArgumentException(
                    "Reservation level cannot be null."
            );
        }

        if (incursionPlan == null) {
            throw new IllegalArgumentException(
                    "Incursion plan cannot be null."
            );
        }

        IncursionReservationSnapshot snapshot =
                createSnapshot(
                        incursionPlan
                );

        getOrCreateLevelReservations(
                level.dimension()
        ).put(
                snapshot.incursionId(),
                snapshot
        );

        return snapshot;
    }

    /**
     * Removes the reservation snapshot belonging to one incursion.
     *
     * Returns true when a snapshot was present and removed.
     */
    public static boolean remove(
            ServerLevel level,
            UUID incursionId
    ) {
        if (level == null) {
            throw new IllegalArgumentException(
                    "Reservation level cannot be null."
            );
        }

        if (incursionId == null) {
            throw new IllegalArgumentException(
                    "Incursion ID cannot be null."
            );
        }

        ResourceKey<Level> levelKey =
                level.dimension();

        Map<UUID, IncursionReservationSnapshot>
                levelReservations =
                RESERVATIONS_BY_LEVEL.get(
                        levelKey
                );

        if (levelReservations == null) {
            return false;
        }

        boolean removed =
                levelReservations.remove(
                        incursionId
                ) != null;

        if (levelReservations.isEmpty()) {
            RESERVATIONS_BY_LEVEL.remove(
                    levelKey
            );
        }

        return removed;
    }

    /**
     * Clears every active-incursion reservation in one level.
     *
     * Returns the number of incursion snapshots removed.
     */
    public static int clear(
            ServerLevel level
    ) {
        if (level == null) {
            throw new IllegalArgumentException(
                    "Reservation level cannot be null."
            );
        }

        Map<UUID, IncursionReservationSnapshot>
                removedReservations =
                RESERVATIONS_BY_LEVEL.remove(
                        level.dimension()
                );

        return removedReservations == null
                ? 0
                : removedReservations.size();
    }

    /**
     * Clears all reservation snapshots from all levels.
     *
     * This is intended for server shutdown protection. Ordinary incursion
     * cleanup should remove only the relevant incursion or level entries.
     *
     * Returns the total number of incursion snapshots removed.
     */
    public static int clearAll() {
        int removedCount =
                0;

        for (Map<UUID, IncursionReservationSnapshot>
                levelReservations
                : RESERVATIONS_BY_LEVEL.values()) {

            removedCount +=
                    levelReservations.size();
        }

        RESERVATIONS_BY_LEVEL.clear();

        return removedCount;
    }

    public static boolean contains(
            ServerLevel level,
            UUID incursionId
    ) {
        if (level == null
                || incursionId == null) {
            return false;
        }

        Map<UUID, IncursionReservationSnapshot>
                levelReservations =
                RESERVATIONS_BY_LEVEL.get(
                        level.dimension()
                );

        return levelReservations != null
                && levelReservations.containsKey(
                incursionId
        );
    }

    public static IncursionReservationSnapshot getSnapshot(
            ServerLevel level,
            UUID incursionId
    ) {
        if (level == null
                || incursionId == null) {
            return null;
        }

        Map<UUID, IncursionReservationSnapshot>
                levelReservations =
                RESERVATIONS_BY_LEVEL.get(
                        level.dimension()
                );

        if (levelReservations == null) {
            return null;
        }

        return levelReservations.get(
                incursionId
        );
    }

    public static List<IncursionReservationSnapshot> getSnapshots(
            ServerLevel level
    ) {
        if (level == null) {
            return List.of();
        }

        Map<UUID, IncursionReservationSnapshot>
                levelReservations =
                RESERVATIONS_BY_LEVEL.get(
                        level.dimension()
                );

        if (levelReservations == null
                || levelReservations.isEmpty()) {
            return List.of();
        }

        return List.copyOf(
                levelReservations.values()
        );
    }

    /**
     * Returns every active physical source-group reservation in one level.
     */
    public static List<SourceGroupReservationSnapshot>
    getSourceGroupReservations(
            ServerLevel level
    ) {
        List<SourceGroupReservationSnapshot>
                sourceGroupReservations =
                new ArrayList<>();

        for (IncursionReservationSnapshot incursionSnapshot
                : getSnapshots(level)) {

            sourceGroupReservations.addAll(
                    incursionSnapshot
                            .sourceGroupReservations()
            );
        }

        return List.copyOf(
                sourceGroupReservations
        );
    }

    /**
     * Returns every active persistent physical-source reservation in one
     * level, including sources whose first required wave has not yet begun.
     */
    public static List<SourceReservationSnapshot>
    getSourceReservations(
            ServerLevel level
    ) {
        List<SourceReservationSnapshot>
                sourceReservations =
                new ArrayList<>();

        for (SourceGroupReservationSnapshot sourceGroupReservation
                : getSourceGroupReservations(level)) {

            sourceReservations.addAll(
                    sourceGroupReservation
                            .sourceReservations()
            );
        }

        return List.copyOf(
                sourceReservations
        );
    }

    public static int getIncursionReservationCount(
            ServerLevel level
    ) {
        if (level == null) {
            return 0;
        }

        Map<UUID, IncursionReservationSnapshot>
                levelReservations =
                RESERVATIONS_BY_LEVEL.get(
                        level.dimension()
                );

        return levelReservations == null
                ? 0
                : levelReservations.size();
    }

    public static int getSourceGroupReservationCount(
            ServerLevel level
    ) {
        return getSourceGroupReservations(
                level
        ).size();
    }

    public static int getSourceReservationCount(
            ServerLevel level
    ) {
        return getSourceReservations(
                level
        ).size();
    }

    private static IncursionReservationSnapshot createSnapshot(
            IncursionPlan incursionPlan
    ) {
        UUID incursionId =
                incursionPlan.getIncursionId();

        if (incursionId == null) {
            throw new IllegalArgumentException(
                    "Incursion plan has no incursion ID."
            );
        }

        if (!incursionPlan.hasFrontPlans()) {
            throw new IllegalArgumentException(
                    "Incursion plan "
                            + incursionId
                            + " contains no fronts."
            );
        }

        Set<UUID> frontIds =
                new HashSet<>();

        Set<UUID> sourceGroupPlacementIds =
                new HashSet<>();

        Set<UUID> sourcePlacementIds =
                new HashSet<>();

        List<SourceGroupReservationSnapshot>
                sourceGroupReservations =
                new ArrayList<>();

        for (FrontPlan frontPlan
                : incursionPlan.getFrontPlans()) {

            if (frontPlan == null) {
                throw new IllegalArgumentException(
                        "Incursion plan "
                                + incursionId
                                + " contains a null front."
                );
            }

            UUID frontId =
                    frontPlan.getFrontId();

            if (frontId == null) {
                throw new IllegalArgumentException(
                        "Incursion plan "
                                + incursionId
                                + " contains a front with no ID."
                );
            }

            if (!frontIds.add(
                    frontId
            )) {
                throw new IllegalArgumentException(
                        "Incursion plan "
                                + incursionId
                                + " contains duplicate front ID "
                                + frontId
                                + "."
                );
            }

            if (!frontPlan.hasSourceGroupPlacementPlans()) {
                throw new IllegalArgumentException(
                        "Front "
                                + frontId
                                + " contains no physical source groups."
                );
            }

            for (SourceGroupPlacementPlan sourceGroupPlacementPlan
                    : frontPlan
                    .getSourceGroupPlacementPlans()) {

                SourceGroupReservationSnapshot
                        sourceGroupReservation =
                        createSourceGroupSnapshot(
                                incursionId,
                                frontId,
                                sourceGroupPlacementPlan,
                                sourceGroupPlacementIds,
                                sourcePlacementIds
                        );

                sourceGroupReservations.add(
                        sourceGroupReservation
                );
            }
        }

        if (sourceGroupReservations.isEmpty()) {
            throw new IllegalArgumentException(
                    "Incursion plan "
                            + incursionId
                            + " produced no source-group reservations."
            );
        }

        return new IncursionReservationSnapshot(
                incursionId,
                sourceGroupReservations
        );
    }

    private static SourceGroupReservationSnapshot
    createSourceGroupSnapshot(
            UUID incursionId,
            UUID frontId,
            SourceGroupPlacementPlan sourceGroupPlacementPlan,
            Set<UUID> sourceGroupPlacementIds,
            Set<UUID> sourcePlacementIds
    ) {
        if (sourceGroupPlacementPlan == null) {
            throw new IllegalArgumentException(
                    "Front "
                            + frontId
                            + " contains a null source-group placement."
            );
        }

        if (!frontId.equals(
                sourceGroupPlacementPlan.getFrontId()
        )) {
            throw new IllegalArgumentException(
                    "Source-group placement "
                            + sourceGroupPlacementPlan
                            .getSourceGroupPlacementId()
                            + " belongs to a different front."
            );
        }

        UUID sourceGroupPlacementId =
                sourceGroupPlacementPlan
                        .getSourceGroupPlacementId();

        if (sourceGroupPlacementId == null) {
            throw new IllegalArgumentException(
                    "Front "
                            + frontId
                            + " contains a source group with no placement "
                            + "ID."
            );
        }

        if (!sourceGroupPlacementIds.add(
                sourceGroupPlacementId
        )) {
            throw new IllegalArgumentException(
                    "Incursion "
                            + incursionId
                            + " contains duplicate source-group placement ID "
                            + sourceGroupPlacementId
                            + "."
            );
        }

        if (sourceGroupPlacementPlan.isEmpty()) {
            throw new IllegalArgumentException(
                    "Source-group placement "
                            + sourceGroupPlacementId
                            + " contains no physical sources."
            );
        }

        SourceGroupEnvelope sourceGroupEnvelope =
                sourceGroupPlacementPlan
                        .getSourceGroupEnvelope();

        if (sourceGroupEnvelope == null) {
            throw new IllegalArgumentException(
                    "Source-group placement "
                            + sourceGroupPlacementId
                            + " has no completed envelope."
            );
        }

        SourceGroupSpatialRules sourceGroupSpatialRules =
                sourceGroupPlacementPlan
                        .getSourceGroupSpatialRules();

        if (sourceGroupSpatialRules == null) {
            throw new IllegalArgumentException(
                    "Source-group placement "
                            + sourceGroupPlacementId
                            + " has no spatial rules."
            );
        }

        if (sourceGroupPlacementPlan.getSourceRole()
                == null) {
            throw new IllegalArgumentException(
                    "Source-group placement "
                            + sourceGroupPlacementId
                            + " has no source role."
            );
        }

        List<SourceReservationSnapshot>
                sourceReservations =
                new ArrayList<>();

        for (SourcePlacementPlan sourcePlacementPlan
                : sourceGroupPlacementPlan
                .getSourcePlacementPlans()) {

            SourceReservationSnapshot sourceReservation =
                    createSourceSnapshot(
                            incursionId,
                            frontId,
                            sourceGroupPlacementId,
                            sourceGroupPlacementPlan,
                            sourcePlacementPlan,
                            sourcePlacementIds
                    );

            sourceReservations.add(
                    sourceReservation
            );
        }

        return new SourceGroupReservationSnapshot(
                incursionId,
                frontId,
                sourceGroupPlacementId,
                sourceGroupPlacementPlan.getSourceRole(),
                sourceGroupEnvelope,
                sourceGroupSpatialRules,
                sourceReservations
        );
    }

    private static SourceReservationSnapshot createSourceSnapshot(
            UUID incursionId,
            UUID frontId,
            UUID sourceGroupPlacementId,
            SourceGroupPlacementPlan sourceGroupPlacementPlan,
            SourcePlacementPlan sourcePlacementPlan,
            Set<UUID> sourcePlacementIds
    ) {
        if (sourcePlacementPlan == null) {
            throw new IllegalArgumentException(
                    "Source-group placement "
                            + sourceGroupPlacementId
                            + " contains a null source placement."
            );
        }

        if (!sourceGroupPlacementId.equals(
                sourcePlacementPlan.getSourceGroupPlacementId()
        )) {
            throw new IllegalArgumentException(
                    "Source placement "
                            + sourcePlacementPlan.getSourcePlacementId()
                            + " belongs to a different source group."
            );
        }

        UUID sourcePlacementId =
                sourcePlacementPlan
                        .getSourcePlacementId();

        if (sourcePlacementId == null) {
            throw new IllegalArgumentException(
                    "Source group "
                            + sourceGroupPlacementId
                            + " contains a source placement with no ID."
            );
        }

        if (!sourcePlacementIds.add(
                sourcePlacementId
        )) {
            throw new IllegalArgumentException(
                    "Incursion "
                            + incursionId
                            + " contains duplicate source-placement ID "
                            + sourcePlacementId
                            + "."
            );
        }

        if (!sourcePlacementPlan.hasPlacedPos()) {
            throw new IllegalArgumentException(
                    "Source placement "
                            + sourcePlacementId
                            + " has no final placed position."
            );
        }

        SourceReservationArea.WorldBounds reservationBounds =
                sourcePlacementPlan
                        .getReservationBounds();

        if (reservationBounds == null) {
            throw new IllegalArgumentException(
                    "Source placement "
                            + sourcePlacementId
                            + " has no reservation bounds."
            );
        }

        if (!sourceGroupPlacementPlan
                .containsReservationCentre(
                        reservationBounds
                )) {
            throw new IllegalArgumentException(
                    "Source placement "
                            + sourcePlacementId
                            + " lies outside source-group envelope "
                            + sourceGroupPlacementId
                            + "."
            );
        }

        return new SourceReservationSnapshot(
                incursionId,
                frontId,
                sourceGroupPlacementId,
                sourcePlacementId,
                reservationBounds
        );
    }

    private static Map<UUID, IncursionReservationSnapshot>
    getOrCreateLevelReservations(
            ResourceKey<Level> levelKey
    ) {
        return RESERVATIONS_BY_LEVEL.computeIfAbsent(
                levelKey,
                ignored -> new HashMap<>()
        );
    }

    /**
     * Immutable reservation state belonging to one active incursion.
     */
    public record IncursionReservationSnapshot(
            UUID incursionId,
            List<SourceGroupReservationSnapshot>
            sourceGroupReservations
    ) {

        public IncursionReservationSnapshot {
            if (incursionId == null) {
                throw new IllegalArgumentException(
                        "Reservation snapshot incursion ID cannot be null."
                );
            }

            if (sourceGroupReservations == null
                    || sourceGroupReservations.isEmpty()) {
                throw new IllegalArgumentException(
                        "Incursion reservation snapshot requires at least "
                                + "one source-group reservation."
                );
            }

            sourceGroupReservations =
                    List.copyOf(
                            sourceGroupReservations
                    );
        }

        public int getSourceGroupCount() {
            return sourceGroupReservations.size();
        }

        public int getSourceCount() {
            int sourceCount =
                    0;

            for (SourceGroupReservationSnapshot sourceGroupReservation
                    : sourceGroupReservations) {

                sourceCount +=
                        sourceGroupReservation
                                .getSourceCount();
            }

            return sourceCount;
        }
    }

    /**
     * Immutable reservation state for one persistent physical source group.
     */
    public record SourceGroupReservationSnapshot(
            UUID incursionId,
            UUID frontId,
            UUID sourceGroupPlacementId,
            SourceRole sourceRole,
            SourceGroupEnvelope sourceGroupEnvelope,
            SourceGroupSpatialRules sourceGroupSpatialRules,
            List<SourceReservationSnapshot> sourceReservations
    ) {

        public SourceGroupReservationSnapshot {
            if (incursionId == null) {
                throw new IllegalArgumentException(
                        "Source-group reservation incursion ID cannot be "
                                + "null."
                );
            }

            if (frontId == null) {
                throw new IllegalArgumentException(
                        "Source-group reservation front ID cannot be null."
                );
            }

            if (sourceGroupPlacementId == null) {
                throw new IllegalArgumentException(
                        "Source-group reservation ID cannot be null."
                );
            }

            if (sourceRole == null) {
                throw new IllegalArgumentException(
                        "Source-group reservation role cannot be null."
                );
            }

            if (sourceGroupEnvelope == null) {
                throw new IllegalArgumentException(
                        "Source-group reservation envelope cannot be null."
                );
            }

            if (sourceGroupSpatialRules == null) {
                throw new IllegalArgumentException(
                        "Source-group spatial rules cannot be null."
                );
            }

            if (sourceReservations == null
                    || sourceReservations.isEmpty()) {
                throw new IllegalArgumentException(
                        "Source-group reservation requires at least one "
                                + "physical-source reservation."
                );
            }

            for (SourceReservationSnapshot sourceReservation
                    : sourceReservations) {

                if (sourceReservation == null) {
                    throw new IllegalArgumentException(
                            "Source-group reservation cannot contain a null "
                                    + "source reservation."
                    );
                }

                if (!incursionId.equals(
                        sourceReservation.incursionId()
                )) {
                    throw new IllegalArgumentException(
                            "Source reservation belongs to a different "
                                    + "incursion."
                    );
                }

                if (!frontId.equals(
                        sourceReservation.frontId()
                )) {
                    throw new IllegalArgumentException(
                            "Source reservation belongs to a different "
                                    + "front."
                    );
                }

                if (!sourceGroupPlacementId.equals(
                        sourceReservation
                                .sourceGroupPlacementId()
                )) {
                    throw new IllegalArgumentException(
                            "Source reservation belongs to a different "
                                    + "source group."
                    );
                }
            }

            sourceReservations =
                    List.copyOf(
                            sourceReservations
                    );
        }

        public BlockPos getAnchorPos() {
            return sourceGroupEnvelope.centre();
        }

        public int getEnvelopeRadius() {
            return sourceGroupEnvelope.radius();
        }

        public int getCrossGroupSourceBuffer() {
            return sourceGroupSpatialRules
                    .crossGroupSourceBuffer();
        }

        public int getSourceCount() {
            return sourceReservations.size();
        }
    }

    /**
     * Immutable hard physical reservation for one persistent source.
     */
    public record SourceReservationSnapshot(
            UUID incursionId,
            UUID frontId,
            UUID sourceGroupPlacementId,
            UUID sourcePlacementId,
            SourceReservationArea.WorldBounds reservationBounds
    ) {

        public SourceReservationSnapshot {
            if (incursionId == null) {
                throw new IllegalArgumentException(
                        "Source reservation incursion ID cannot be null."
                );
            }

            if (frontId == null) {
                throw new IllegalArgumentException(
                        "Source reservation front ID cannot be null."
                );
            }

            if (sourceGroupPlacementId == null) {
                throw new IllegalArgumentException(
                        "Source reservation group ID cannot be null."
                );
            }

            if (sourcePlacementId == null) {
                throw new IllegalArgumentException(
                        "Source reservation placement ID cannot be null."
                );
            }

            if (reservationBounds == null) {
                throw new IllegalArgumentException(
                        "Source reservation bounds cannot be null."
                );
            }
        }
    }

    private ActiveIncursionSourceReservationRegistry() {
    }
}