package org.ratden.skavenblight.event.skavenIncursion.planning.persistence;

import net.minecraft.world.level.ChunkPos;
import org.ratden.skavenblight.event.skavenIncursion.planning.chunk.IncursionChunkLoadPlan;
import org.ratden.skavenblight.event.skavenIncursion.planning.chunk.SourceGroupChunkLoadPlan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Immutable persistence-facing snapshot of one complete calculated incursion
 * chunk-load plan.
 *
 * This snapshot preserves:
 *
 * - incursion identity;
 * - the exact protected-base chunk footprint used at admission;
 * - every physical source-group footprint and route corridor;
 * - the physical source groups required by each wave.
 *
 * Derived unions and costs are reconstructed from this data rather than
 * persisted redundantly.
 *
 * Persisting the calculated footprint prevents later changes to the Warp Flux
 * network, calculator constants or spatial algorithms from changing the
 * battlefield footprint of an incursion that is already underway.
 */
public record IncursionChunkLoadPlanSnapshot(
        UUID incursionId,
        List<ChunkPos> protectedBaseChunks,
        List<SourceGroupChunkLoadPlanSnapshot>
        sourceGroupPlanSnapshots,
        Map<Integer, List<UUID>>
        requiredSourceGroupPlacementIdsByWave
) {

    public IncursionChunkLoadPlanSnapshot {
        if (incursionId == null) {
            throw new IllegalArgumentException(
                    "Incursion chunk-load snapshot ID cannot be null."
            );
        }

        protectedBaseChunks =
                canonicalChunkList(
                        protectedBaseChunks,
                        "Protected-base chunk snapshot",
                        true
                );

        if (sourceGroupPlanSnapshots == null
                || sourceGroupPlanSnapshots.isEmpty()) {

            throw new IllegalArgumentException(
                    "Incursion chunk-load snapshot requires at least one "
                            + "physical source-group snapshot."
            );
        }

        sourceGroupPlanSnapshots =
                List.copyOf(
                        sourceGroupPlanSnapshots
                );

        Set<UUID> knownSourceGroupIds =
                validateSourceGroupSnapshots(
                        sourceGroupPlanSnapshots
                );

        requiredSourceGroupPlacementIdsByWave =
                canonicalWaveRequirements(
                        requiredSourceGroupPlacementIdsByWave,
                        knownSourceGroupIds
                );

        validateEverySourceGroupIsUsed(
                knownSourceGroupIds,
                requiredSourceGroupPlacementIdsByWave
        );
    }

    /**
     * Captures one complete calculated incursion chunk-load plan.
     */
    public static IncursionChunkLoadPlanSnapshot capture(
            IncursionChunkLoadPlan chunkLoadPlan
    ) {
        if (chunkLoadPlan == null) {
            throw new IllegalArgumentException(
                    "Incursion chunk-load plan cannot be null."
            );
        }

        List<SourceGroupChunkLoadPlanSnapshot>
                capturedGroupSnapshots =
                new ArrayList<>();

        for (SourceGroupChunkLoadPlan sourceGroupPlan
                : chunkLoadPlan.getSourceGroupPlans()) {

            capturedGroupSnapshots.add(
                    SourceGroupChunkLoadPlanSnapshot.capture(
                            sourceGroupPlan
                    )
            );
        }

        Map<Integer, List<UUID>>
                capturedWaveRequirements =
                new TreeMap<>();

        for (int waveIndex
                : chunkLoadPlan.getWaveIndexes()) {

            capturedWaveRequirements.put(
                    waveIndex,
                    new ArrayList<>(
                            chunkLoadPlan
                                    .getRequiredSourceGroupPlacementIdsForWave(
                                            waveIndex
                                    )
                    )
            );
        }

        return new IncursionChunkLoadPlanSnapshot(
                chunkLoadPlan.getIncursionId(),
                new ArrayList<>(
                        chunkLoadPlan.getProtectedBaseChunks()
                ),
                capturedGroupSnapshots,
                capturedWaveRequirements
        );
    }

    /**
     * Restores one complete calculated incursion chunk-load plan.
     */
    public IncursionChunkLoadPlan restore() {
        List<SourceGroupChunkLoadPlan> restoredGroupPlans =
                new ArrayList<>();

        for (SourceGroupChunkLoadPlanSnapshot groupSnapshot
                : sourceGroupPlanSnapshots) {

            restoredGroupPlans.add(
                    groupSnapshot.restore()
            );
        }

        Map<Integer, Set<UUID>> restoredWaveRequirements =
                new LinkedHashMap<>();

        for (Map.Entry<Integer, List<UUID>> entry
                : requiredSourceGroupPlacementIdsByWave.entrySet()) {

            restoredWaveRequirements.put(
                    entry.getKey(),
                    new LinkedHashSet<>(
                            entry.getValue()
                    )
            );
        }

        IncursionChunkLoadPlan restoredPlan =
                new IncursionChunkLoadPlan(
                        incursionId,
                        new LinkedHashSet<>(
                                protectedBaseChunks
                        ),
                        restoredGroupPlans,
                        restoredWaveRequirements
                );

        IncursionChunkLoadPlanSnapshot reconstructedSnapshot =
                capture(
                        restoredPlan
                );

        if (!equals(
                reconstructedSnapshot
        )) {
            throw new IllegalArgumentException(
                    "Restored incursion chunk-load plan "
                            + incursionId
                            + " does not exactly match its saved snapshot."
            );
        }

        return restoredPlan;
    }

    public int getProtectedBaseChunkCount() {
        return protectedBaseChunks.size();
    }

    public int getSourceGroupCount() {
        return sourceGroupPlanSnapshots.size();
    }

    public Set<Integer> getWaveIndexes() {
        return requiredSourceGroupPlacementIdsByWave.keySet();
    }

    public int getRetainedLoadedChunkCount() {
        Set<ChunkPos> retainedChunks =
                new LinkedHashSet<>(
                        protectedBaseChunks
                );

        for (SourceGroupChunkLoadPlanSnapshot groupSnapshot
                : sourceGroupPlanSnapshots) {

            retainedChunks.addAll(
                    groupSnapshot.groupFootprintChunks()
            );

            retainedChunks.addAll(
                    groupSnapshot.routeCorridorChunks()
            );
        }

        return retainedChunks.size();
    }

    private static Set<UUID> validateSourceGroupSnapshots(
            List<SourceGroupChunkLoadPlanSnapshot>
                    sourceGroupSnapshots
    ) {
        Set<UUID> sourceGroupIds =
                new LinkedHashSet<>();

        for (SourceGroupChunkLoadPlanSnapshot groupSnapshot
                : sourceGroupSnapshots) {

            if (groupSnapshot == null) {
                throw new IllegalArgumentException(
                        "Incursion chunk-load snapshot cannot contain a null "
                                + "source-group snapshot."
                );
            }

            UUID sourceGroupId =
                    groupSnapshot.sourceGroupPlacementId();

            if (!sourceGroupIds.add(
                    sourceGroupId
            )) {
                throw new IllegalArgumentException(
                        "Incursion chunk-load snapshot contains duplicate "
                                + "source-group placement ID "
                                + sourceGroupId
                                + "."
                );
            }
        }

        return Collections.unmodifiableSet(
                sourceGroupIds
        );
    }

    private static Map<Integer, List<UUID>>
    canonicalWaveRequirements(
            Map<Integer, List<UUID>> waveRequirements,
            Set<UUID> knownSourceGroupIds
    ) {
        if (waveRequirements == null
                || waveRequirements.isEmpty()) {

            throw new IllegalArgumentException(
                    "Incursion chunk-load snapshot requires at least one "
                            + "wave requirement."
            );
        }

        TreeMap<Integer, List<UUID>>
                canonicalRequirements =
                new TreeMap<>();

        for (Map.Entry<Integer, List<UUID>> entry
                : waveRequirements.entrySet()) {

            Integer waveIndex =
                    entry.getKey();

            if (waveIndex == null
                    || waveIndex < 0) {

                throw new IllegalArgumentException(
                        "Chunk-load snapshot wave index cannot be null or "
                                + "negative."
                );
            }

            List<UUID> sourceGroupIds =
                    entry.getValue();

            if (sourceGroupIds == null
                    || sourceGroupIds.isEmpty()) {

                throw new IllegalArgumentException(
                        "Chunk-load snapshot wave "
                                + waveIndex
                                + " must require at least one physical "
                                + "source group."
                );
            }

            ArrayList<UUID> copiedGroupIds =
                    new ArrayList<>();

            Set<UUID> uniqueGroupIds =
                    new HashSet<>();

            for (UUID sourceGroupId : sourceGroupIds) {
                if (sourceGroupId == null) {
                    throw new IllegalArgumentException(
                            "Wave source-group requirements cannot contain "
                                    + "null IDs."
                    );
                }

                if (!knownSourceGroupIds.contains(
                        sourceGroupId
                )) {
                    throw new IllegalArgumentException(
                            "Chunk-load snapshot wave "
                                    + waveIndex
                                    + " references unknown physical "
                                    + "source-group placement ID "
                                    + sourceGroupId
                                    + "."
                    );
                }

                if (!uniqueGroupIds.add(
                        sourceGroupId
                )) {
                    throw new IllegalArgumentException(
                            "Chunk-load snapshot wave "
                                    + waveIndex
                                    + " contains duplicate physical "
                                    + "source-group placement ID "
                                    + sourceGroupId
                                    + "."
                    );
                }

                copiedGroupIds.add(
                        sourceGroupId
                );
            }

            copiedGroupIds.sort(
                    Comparator.naturalOrder()
            );

            canonicalRequirements.put(
                    waveIndex,
                    List.copyOf(
                            copiedGroupIds
                    )
            );
        }

        return Collections.unmodifiableMap(
                canonicalRequirements
        );
    }

    private static void validateEverySourceGroupIsUsed(
            Set<UUID> knownSourceGroupIds,
            Map<Integer, List<UUID>> waveRequirements
    ) {
        Set<UUID> unusedSourceGroupIds =
                new LinkedHashSet<>(
                        knownSourceGroupIds
                );

        for (List<UUID> waveSourceGroupIds
                : waveRequirements.values()) {

            unusedSourceGroupIds.removeAll(
                    waveSourceGroupIds
            );
        }

        if (!unusedSourceGroupIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "Incursion chunk-load snapshot contains physical source "
                            + "groups unused by every wave: "
                            + unusedSourceGroupIds
                            + "."
            );
        }
    }

    private static List<ChunkPos> canonicalChunkList(
            List<ChunkPos> chunks,
            String description,
            boolean requireNonEmpty
    ) {
        if (chunks == null) {
            throw new IllegalArgumentException(
                    description + " cannot be null."
            );
        }

        if (requireNonEmpty
                && chunks.isEmpty()) {

            throw new IllegalArgumentException(
                    description + " cannot be empty."
            );
        }

        ArrayList<ChunkPos> copiedChunks =
                new ArrayList<>();

        Set<ChunkPos> uniqueChunks =
                new LinkedHashSet<>();

        for (ChunkPos chunkPos : chunks) {
            if (chunkPos == null) {
                throw new IllegalArgumentException(
                        description + " cannot contain null."
                );
            }

            ChunkPos copiedChunk =
                    new ChunkPos(
                            chunkPos.x,
                            chunkPos.z
                    );

            if (!uniqueChunks.add(
                    copiedChunk
            )) {
                throw new IllegalArgumentException(
                        description
                                + " contains duplicate chunk "
                                + copiedChunk.x
                                + ", "
                                + copiedChunk.z
                                + "."
                );
            }

            copiedChunks.add(
                    copiedChunk
            );
        }

        copiedChunks.sort(
                Comparator
                        .comparingInt(
                                (ChunkPos chunkPos) ->
                                        chunkPos.x
                        )
                        .thenComparingInt(
                                chunkPos ->
                                        chunkPos.z
                        )
        );

        return List.copyOf(
                copiedChunks
        );
    }
}