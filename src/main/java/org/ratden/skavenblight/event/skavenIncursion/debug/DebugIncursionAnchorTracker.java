package org.ratden.skavenblight.event.skavenIncursion.debug;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.ratden.skavenblight.event.skavenIncursion.planning.IncursionPlan;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks the debug-anchor marker blocks created for active planning-aware
 * incursions.
 *
 * The tracker stores only successful placement results. It does not own the
 * IncursionPlan, Scenario, fronts, source groups, sources or their runtime
 * lifecycle.
 *
 * All methods are expected to be called from the server thread.
 *
 * Later lifecycle integration will call removeAndForget when an incursion
 * finishes and removeAll when a level or server shuts down.
 */
public final class DebugIncursionAnchorTracker {

    private static final Map<
            ResourceKey<Level>,
            Map<
                    UUID,
                    DebugIncursionAnchorPlacementService.PlacementResult
                    >
            > PLACEMENTS_BY_LEVEL =
            new HashMap<>();

    /**
     * Creates and tracks the debug anchors for one completed IncursionPlan.
     *
     * Any previously tracked marker set using the same incursion ID in the
     * same level is removed first. Incursion IDs should normally be unique,
     * but this prevents stale duplicate markers during repeated debugging.
     *
     * Failed placement results are returned to the caller but are not stored.
     */
    public static DebugIncursionAnchorPlacementService.PlacementResult
    placeAndTrack(
            ServerLevel level,
            IncursionPlan incursionPlan
    ) {
        if (level == null) {
            throw new IllegalArgumentException(
                    "Debug-anchor level cannot be null."
            );
        }

        if (incursionPlan == null) {
            throw new IllegalArgumentException(
                    "Debug-anchor incursion plan cannot be null."
            );
        }

        UUID incursionId =
                incursionPlan.getIncursionId();

        removeAndForget(
                level,
                incursionId
        );

        DebugIncursionAnchorPlacementService.PlacementResult
                placementResult =
                DebugIncursionAnchorPlacementService.placeAnchors(
                        level,
                        incursionPlan
                );

        if (!placementResult.successful()) {
            return placementResult;
        }

        getOrCreateLevelPlacements(
                level.dimension()
        ).put(
                incursionId,
                placementResult
        );

        return placementResult;
    }

    /**
     * Reconstructs tracker state for one logically restored incursion.
     *
     * Existing valid world markers are reused. An incomplete or stale owned
     * marker set is replaced through the ordinary placement service.
     *
     * Failed restoration results are returned but are not tracked.
     *
     * Repeated calls are idempotent while the incursion is already tracked.
     */
    public static DebugIncursionAnchorPlacementService.PlacementResult
    restoreAndTrack(
            ServerLevel level,
            IncursionPlan incursionPlan
    ) {
        if (level == null) {
            throw new IllegalArgumentException(
                    "Restored debug-anchor level cannot be null."
            );
        }

        if (incursionPlan == null) {
            throw new IllegalArgumentException(
                    "Restored debug-anchor incursion plan cannot be null."
            );
        }

        UUID incursionId =
                incursionPlan.getIncursionId();

        if (incursionId == null) {
            throw new IllegalArgumentException(
                    "Restored debug-anchor plan has no incursion ID."
            );
        }

        DebugIncursionAnchorPlacementService.PlacementResult
                existingTrackedResult =
                getPlacementResult(
                        level,
                        incursionId
                );

        if (existingTrackedResult != null) {
            return existingTrackedResult;
        }

        DebugIncursionAnchorPlacementService.PlacementResult
                restorationResult =
                DebugIncursionAnchorRestorationService.restoreAnchors(
                        level,
                        incursionPlan
                );

        if (!restorationResult.successful()) {
            return restorationResult;
        }

        getOrCreateLevelPlacements(
                level.dimension()
        ).put(
                incursionId,
                restorationResult
        );

        return restorationResult;
    }

    /**
     * Removes the marker blocks tracked for one incursion and forgets the
     * associated placement result.
     *
     * Returns the number of marker blocks that were still present and were
     * successfully removed.
     */
    public static int removeAndForget(
            ServerLevel level,
            UUID incursionId
    ) {
        if (level == null) {
            throw new IllegalArgumentException(
                    "Debug-anchor level cannot be null."
            );
        }

        if (incursionId == null) {
            throw new IllegalArgumentException(
                    "Debug-anchor incursion ID cannot be null."
            );
        }

        ResourceKey<Level> levelKey =
                level.dimension();

        Map<
                UUID,
                DebugIncursionAnchorPlacementService.PlacementResult
                > levelPlacements =
                PLACEMENTS_BY_LEVEL.get(
                        levelKey
                );

        if (levelPlacements == null) {
            return 0;
        }

        DebugIncursionAnchorPlacementService.PlacementResult
                placementResult =
                levelPlacements.remove(
                        incursionId
                );

        if (levelPlacements.isEmpty()) {
            PLACEMENTS_BY_LEVEL.remove(
                    levelKey
            );
        }

        if (placementResult == null) {
            return 0;
        }

        return DebugIncursionAnchorPlacementService.removeAnchors(
                level,
                placementResult
        );
    }

    /**
     * Removes every tracked debug-anchor marker in one level.
     */
    public static int removeAll(
            ServerLevel level
    ) {
        if (level == null) {
            throw new IllegalArgumentException(
                    "Debug-anchor level cannot be null."
            );
        }

        ResourceKey<Level> levelKey =
                level.dimension();

        Map<
                UUID,
                DebugIncursionAnchorPlacementService.PlacementResult
                > levelPlacements =
                PLACEMENTS_BY_LEVEL.remove(
                        levelKey
                );

        if (levelPlacements == null
                || levelPlacements.isEmpty()) {
            return 0;
        }

        int removedCount =
                0;

        for (DebugIncursionAnchorPlacementService.PlacementResult
                placementResult
                : levelPlacements.values()) {

            removedCount +=
                    DebugIncursionAnchorPlacementService.removeAnchors(
                            level,
                            placementResult
                    );
        }

        return removedCount;
    }

    /**
     * Forgets every tracked debug-anchor placement without modifying the
     * world.
     *
     * This is used during server shutdown after the world has completed its
     * normal save. Physical marker blocks remain part of the saved world, but
     * static tracker state must not retain references to the previous server
     * lifecycle inside an integrated-client JVM.
     *
     * Debug-anchor tracking will later be reconstructed from restored
     * persistent incursions.
     *
     * @return number of tracked incursion placement results forgotten
     */
    public static int forgetAll() {
        int forgottenIncursionCount =
                0;

        for (Map<
                UUID,
                DebugIncursionAnchorPlacementService.PlacementResult
                > levelPlacements
                : PLACEMENTS_BY_LEVEL.values()) {

            forgottenIncursionCount +=
                    levelPlacements.size();
        }

        PLACEMENTS_BY_LEVEL.clear();

        return forgottenIncursionCount;
    }

    public static boolean isTracked(
            ServerLevel level,
            UUID incursionId
    ) {
        if (level == null
                || incursionId == null) {
            return false;
        }

        Map<
                UUID,
                DebugIncursionAnchorPlacementService.PlacementResult
                > levelPlacements =
                PLACEMENTS_BY_LEVEL.get(
                        level.dimension()
                );

        return levelPlacements != null
                && levelPlacements.containsKey(
                incursionId
        );
    }

    public static DebugIncursionAnchorPlacementService.PlacementResult
    getPlacementResult(
            ServerLevel level,
            UUID incursionId
    ) {
        if (level == null
                || incursionId == null) {
            return null;
        }

        Map<
                UUID,
                DebugIncursionAnchorPlacementService.PlacementResult
                > levelPlacements =
                PLACEMENTS_BY_LEVEL.get(
                        level.dimension()
                );

        if (levelPlacements == null) {
            return null;
        }

        return levelPlacements.get(
                incursionId
        );
    }

    public static List<
            DebugIncursionAnchorPlacementService.PlacementResult
            > getPlacementResults(
            ServerLevel level
    ) {
        if (level == null) {
            return List.of();
        }

        Map<
                UUID,
                DebugIncursionAnchorPlacementService.PlacementResult
                > levelPlacements =
                PLACEMENTS_BY_LEVEL.get(
                        level.dimension()
                );

        if (levelPlacements == null
                || levelPlacements.isEmpty()) {
            return List.of();
        }

        return List.copyOf(
                levelPlacements.values()
        );
    }

    public static List<UUID> getTrackedIncursionIds(
            ServerLevel level
    ) {
        if (level == null) {
            return List.of();
        }

        Map<
                UUID,
                DebugIncursionAnchorPlacementService.PlacementResult
                > levelPlacements =
                PLACEMENTS_BY_LEVEL.get(
                        level.dimension()
                );

        if (levelPlacements == null
                || levelPlacements.isEmpty()) {
            return List.of();
        }

        return List.copyOf(
                levelPlacements.keySet()
        );
    }

    public static int getTrackedIncursionCount(
            ServerLevel level
    ) {
        if (level == null) {
            return 0;
        }

        Map<
                UUID,
                DebugIncursionAnchorPlacementService.PlacementResult
                > levelPlacements =
                PLACEMENTS_BY_LEVEL.get(
                        level.dimension()
                );

        return levelPlacements == null
                ? 0
                : levelPlacements.size();
    }

    private static Map<
            UUID,
            DebugIncursionAnchorPlacementService.PlacementResult
            > getOrCreateLevelPlacements(
            ResourceKey<Level> levelKey
    ) {
        return PLACEMENTS_BY_LEVEL.computeIfAbsent(
                levelKey,
                ignored -> new HashMap<>()
        );
    }

    private DebugIncursionAnchorTracker() {
    }
}