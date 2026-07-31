package org.ratden.skavenblight.event.skavenIncursion.planning.chunk;

import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Immutable chunk-loading requirements derived from one completed and
 * validated IncursionPlan.
 *
 * This is a sibling planning result rather than part of IncursionPlan itself:
 * the tactical plan describes what the incursion is, while this plan describes
 * which world regions must remain available to execute it faithfully.
 *
 * The retained footprint contains the defended base plus every physical
 * source-group footprint and route corridor belonging to the incursion.
 *
 * The defended base is the baseline ticking footprint. A wave's source-group
 * activation footprint is promoted in addition to that baseline before any
 * source belonging to the wave may activate.
 *
 * This class does not own NeoForge tickets or runtime readiness state.
 */
public class IncursionChunkLoadPlan {

    private final UUID incursionId;

    private final Set<ChunkPos> protectedBaseChunks;
    private final List<SourceGroupChunkLoadPlan> sourceGroupPlans;
    private final Map<UUID, SourceGroupChunkLoadPlan> sourceGroupPlansById;

    private final Map<Integer, Set<UUID>>
            requiredSourceGroupPlacementIdsByWave;

    private final Map<Integer, Set<ChunkPos>>
            sourceActivationChunksByWave;

    private final Map<Integer, Set<ChunkPos>>
            totalTickingChunksByWave;

    private final Set<ChunkPos> retainedLoadedChunks;

    public IncursionChunkLoadPlan(
            UUID incursionId,
            Set<ChunkPos> protectedBaseChunks,
            List<SourceGroupChunkLoadPlan> sourceGroupPlans,
            Map<Integer, Set<UUID>> requiredSourceGroupPlacementIdsByWave
    ) {
        if (incursionId == null) {
            throw new IllegalArgumentException(
                    "Incursion ID cannot be null."
            );
        }

        this.incursionId =
                incursionId;

        this.protectedBaseChunks =
                immutableChunkSet(
                        protectedBaseChunks,
                        "Protected-base chunks"
                );

        if (this.protectedBaseChunks.isEmpty()) {
            throw new IllegalArgumentException(
                    "Protected-base chunk footprint cannot be empty."
            );
        }

        if (sourceGroupPlans == null) {
            throw new IllegalArgumentException(
                    "Source-group chunk-load plans cannot be null."
            );
        }

        ArrayList<SourceGroupChunkLoadPlan> copiedGroupPlans =
                new ArrayList<>();

        LinkedHashMap<UUID, SourceGroupChunkLoadPlan> groupPlansById =
                new LinkedHashMap<>();

        for (SourceGroupChunkLoadPlan sourceGroupPlan
                : sourceGroupPlans) {

            if (sourceGroupPlan == null) {
                throw new IllegalArgumentException(
                        "Source-group chunk-load plans cannot contain null."
                );
            }

            SourceGroupChunkLoadPlan previousPlan =
                    groupPlansById.put(
                            sourceGroupPlan
                                    .getSourceGroupPlacementId(),
                            sourceGroupPlan
                    );

            if (previousPlan != null) {
                throw new IllegalArgumentException(
                        "Duplicate source-group placement ID "
                                + sourceGroupPlan
                                .getSourceGroupPlacementId()
                                + " in chunk-load plan."
                );
            }

            copiedGroupPlans.add(
                    sourceGroupPlan
            );
        }

        if (copiedGroupPlans.isEmpty()) {
            throw new IllegalArgumentException(
                    "Incursion chunk-load plan requires at least one "
                            + "physical source group."
            );
        }

        this.sourceGroupPlans =
                Collections.unmodifiableList(
                        copiedGroupPlans
                );

        this.sourceGroupPlansById =
                Collections.unmodifiableMap(
                        groupPlansById
                );

        this.requiredSourceGroupPlacementIdsByWave =
                copyAndValidateWaveGroupIds(
                        requiredSourceGroupPlacementIdsByWave,
                        groupPlansById
                );

        validateEveryGroupIsUsedByAtLeastOneWave(
                groupPlansById.keySet(),
                this.requiredSourceGroupPlacementIdsByWave
        );

        this.sourceActivationChunksByWave =
                buildSourceActivationChunksByWave(
                        this.requiredSourceGroupPlacementIdsByWave,
                        groupPlansById
                );

        this.totalTickingChunksByWave =
                buildTotalTickingChunksByWave(
                        this.sourceActivationChunksByWave,
                        this.protectedBaseChunks
                );

        LinkedHashSet<ChunkPos> retainedChunks =
                new LinkedHashSet<>(
                        this.protectedBaseChunks
                );

        for (SourceGroupChunkLoadPlan sourceGroupPlan
                : this.sourceGroupPlans) {

            retainedChunks.addAll(
                    sourceGroupPlan.getActivationChunks()
            );
        }

        this.retainedLoadedChunks =
                Collections.unmodifiableSet(
                        retainedChunks
                );
    }

    public UUID getIncursionId() {
        return incursionId;
    }

    public Set<ChunkPos> getProtectedBaseChunks() {
        return protectedBaseChunks;
    }

    public int getProtectedBaseChunkCount() {
        return protectedBaseChunks.size();
    }

    public List<SourceGroupChunkLoadPlan> getSourceGroupPlans() {
        return sourceGroupPlans;
    }

    public int getSourceGroupCount() {
        return sourceGroupPlans.size();
    }

    public SourceGroupChunkLoadPlan getSourceGroupPlan(
            UUID sourceGroupPlacementId
    ) {
        if (sourceGroupPlacementId == null) {
            return null;
        }

        return sourceGroupPlansById.get(
                sourceGroupPlacementId
        );
    }

    public Set<Integer> getWaveIndexes() {
        return requiredSourceGroupPlacementIdsByWave.keySet();
    }

    public Set<UUID> getRequiredSourceGroupPlacementIdsForWave(
            int waveIndex
    ) {
        Set<UUID> groupIds =
                requiredSourceGroupPlacementIdsByWave.get(
                        waveIndex
                );

        return groupIds == null
                ? Set.of()
                : groupIds;
    }

    public Set<ChunkPos> getSourceActivationChunksForWave(
            int waveIndex
    ) {
        Set<ChunkPos> chunks =
                sourceActivationChunksByWave.get(
                        waveIndex
                );

        return chunks == null
                ? Set.of()
                : chunks;
    }

    public Set<ChunkPos> getTotalTickingChunksForWave(
            int waveIndex
    ) {
        Set<ChunkPos> chunks =
                totalTickingChunksByWave.get(
                        waveIndex
                );

        return chunks == null
                ? protectedBaseChunks
                : chunks;
    }

    public Set<ChunkPos> getRetainedLoadedChunks() {
        return retainedLoadedChunks;
    }

    public int getRetainedLoadedChunkCount() {
        return retainedLoadedChunks.size();
    }

    public int getMaximumWaveSourceActivationChunkCount() {
        int maximum = 0;

        for (Set<ChunkPos> chunks
                : sourceActivationChunksByWave.values()) {

            maximum = Math.max(
                    maximum,
                    chunks.size()
            );
        }

        return maximum;
    }

    public int getMaximumWaveTotalTickingChunkCount() {
        int maximum =
                protectedBaseChunks.size();

        for (Set<ChunkPos> chunks
                : totalTickingChunksByWave.values()) {

            maximum = Math.max(
                    maximum,
                    chunks.size()
            );
        }

        return maximum;
    }

    private static Map<Integer, Set<UUID>>
    copyAndValidateWaveGroupIds(
            Map<Integer, Set<UUID>> waveGroupIds,
            Map<UUID, SourceGroupChunkLoadPlan> groupPlansById
    ) {
        if (waveGroupIds == null) {
            throw new IllegalArgumentException(
                    "Wave source-group requirements cannot be null."
            );
        }

        TreeMap<Integer, Set<UUID>> copiedWaveGroupIds =
                new TreeMap<>();

        for (Map.Entry<Integer, Set<UUID>> entry
                : waveGroupIds.entrySet()) {

            Integer waveIndex =
                    entry.getKey();

            if (waveIndex == null
                    || waveIndex < 0) {

                throw new IllegalArgumentException(
                        "Wave index cannot be null or negative."
                );
            }

            Set<UUID> groupIds =
                    entry.getValue();

            if (groupIds == null
                    || groupIds.isEmpty()) {

                throw new IllegalArgumentException(
                        "Wave "
                                + waveIndex
                                + " must require at least one source group."
                );
            }

            LinkedHashSet<UUID> copiedGroupIds =
                    new LinkedHashSet<>();

            for (UUID groupId : groupIds) {
                if (groupId == null) {
                    throw new IllegalArgumentException(
                            "Wave source-group requirements cannot contain "
                                    + "null IDs."
                    );
                }

                if (!groupPlansById.containsKey(groupId)) {
                    throw new IllegalArgumentException(
                            "Wave "
                                    + waveIndex
                                    + " references unknown source-group "
                                    + "placement ID "
                                    + groupId
                                    + "."
                    );
                }

                copiedGroupIds.add(groupId);
            }

            copiedWaveGroupIds.put(
                    waveIndex,
                    Collections.unmodifiableSet(
                            copiedGroupIds
                    )
            );
        }

        if (copiedWaveGroupIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "Incursion chunk-load plan requires at least one wave."
            );
        }

        return Collections.unmodifiableMap(
                copiedWaveGroupIds
        );
    }

    private static void validateEveryGroupIsUsedByAtLeastOneWave(
            Set<UUID> sourceGroupPlacementIds,
            Map<Integer, Set<UUID>> waveGroupIds
    ) {
        LinkedHashSet<UUID> unusedGroupIds =
                new LinkedHashSet<>(
                        sourceGroupPlacementIds
                );

        for (Set<UUID> groupIds
                : waveGroupIds.values()) {

            unusedGroupIds.removeAll(groupIds);
        }

        if (!unusedGroupIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "Chunk-load plan contains physical source groups that "
                            + "are not required by any wave: "
                            + unusedGroupIds
                            + "."
            );
        }
    }

    private static Map<Integer, Set<ChunkPos>>
    buildSourceActivationChunksByWave(
            Map<Integer, Set<UUID>> waveGroupIds,
            Map<UUID, SourceGroupChunkLoadPlan> groupPlansById
    ) {
        LinkedHashMap<Integer, Set<ChunkPos>> chunksByWave =
                new LinkedHashMap<>();

        for (Map.Entry<Integer, Set<UUID>> entry
                : waveGroupIds.entrySet()) {

            LinkedHashSet<ChunkPos> waveChunks =
                    new LinkedHashSet<>();

            for (UUID sourceGroupPlacementId
                    : entry.getValue()) {

                waveChunks.addAll(
                        groupPlansById
                                .get(sourceGroupPlacementId)
                                .getActivationChunks()
                );
            }

            chunksByWave.put(
                    entry.getKey(),
                    Collections.unmodifiableSet(
                            waveChunks
                    )
            );
        }

        return Collections.unmodifiableMap(
                chunksByWave
        );
    }

    private static Map<Integer, Set<ChunkPos>>
    buildTotalTickingChunksByWave(
            Map<Integer, Set<ChunkPos>> sourceChunksByWave,
            Set<ChunkPos> protectedBaseChunks
    ) {
        LinkedHashMap<Integer, Set<ChunkPos>> chunksByWave =
                new LinkedHashMap<>();

        for (Map.Entry<Integer, Set<ChunkPos>> entry
                : sourceChunksByWave.entrySet()) {

            LinkedHashSet<ChunkPos> waveChunks =
                    new LinkedHashSet<>(
                            protectedBaseChunks
                    );

            waveChunks.addAll(
                    entry.getValue()
            );

            chunksByWave.put(
                    entry.getKey(),
                    Collections.unmodifiableSet(
                            waveChunks
                    )
            );
        }

        return Collections.unmodifiableMap(
                chunksByWave
        );
    }

    private static Set<ChunkPos> immutableChunkSet(
            Set<ChunkPos> chunks,
            String description
    ) {
        if (chunks == null) {
            throw new IllegalArgumentException(
                    description + " cannot be null."
            );
        }

        LinkedHashSet<ChunkPos> copiedChunks =
                new LinkedHashSet<>();

        for (ChunkPos chunkPos : chunks) {
            if (chunkPos == null) {
                throw new IllegalArgumentException(
                        description + " cannot contain null."
                );
            }

            copiedChunks.add(chunkPos);
        }

        return Collections.unmodifiableSet(
                copiedChunks
        );
    }
}