package org.ratden.skavenblight.event.skavenIncursion.runtime.source;

import org.ratden.skavenblight.event.skavenIncursion.planning.composition.SourceGroupComposition;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Mutable runtime queue for one planned SourceComposition.
 *
 * SourceComposition remains an immutable planning result. This queue copies
 * its mob counts and reduces them only after runtime confirms that a mob was
 * successfully spawned.
 *
 * One queue represents one source composition in one wave. Cancelling this
 * queue therefore does not affect compositions assigned to the same physical
 * source in later waves.
 */
public final class SourceSpawnQueue {

    private final UUID sourceCompositionId;

    private final List<String> mobOrder;
    private final Map<String, Integer> remainingCounts;

    private int nextMobIndex;
    private int remainingMobCount;

    public SourceSpawnQueue(
            SourceGroupComposition.SourceComposition sourceComposition
    ) {
        if (sourceComposition == null) {
            throw new IllegalArgumentException(
                    "Source composition cannot be null."
            );
        }

        if (sourceComposition.isEmpty()) {
            throw new IllegalArgumentException(
                    "Source composition cannot be empty."
            );
        }

        sourceCompositionId =
                sourceComposition.getSourceCompositionId();

        LinkedHashMap<String, Integer> copiedCounts =
                new LinkedHashMap<>();

        for (SourceGroupComposition.MobEntry mobEntry
                : sourceComposition.getMobEntries()) {

            copiedCounts.merge(
                    mobEntry.getMobId(),
                    mobEntry.getCount(),
                    Integer::sum
            );
        }

        if (copiedCounts.isEmpty()) {
            throw new IllegalArgumentException(
                    "Source composition contains no mob entries."
            );
        }

        mobOrder =
                List.copyOf(
                        copiedCounts.keySet()
                );

        remainingCounts =
                copiedCounts;

        nextMobIndex = 0;

        remainingMobCount =
                sourceComposition.getTotalMobCount();

        int copiedMobCount =
                copiedCounts
                        .values()
                        .stream()
                        .mapToInt(Integer::intValue)
                        .sum();

        if (copiedMobCount != remainingMobCount) {
            throw new IllegalArgumentException(
                    "Source composition total mob count does not match "
                            + "the total contained in its mob entries."
            );
        }
    }

    public UUID getSourceCompositionId() {
        return sourceCompositionId;
    }

    public boolean hasRemainingMobs() {
        return remainingMobCount > 0;
    }

    public boolean isEmpty() {
        return !hasRemainingMobs();
    }

    public int getRemainingMobCount() {
        return remainingMobCount;
    }

    public int getRemainingCount(
            String mobId
    ) {
        if (mobId == null
                || mobId.isBlank()) {
            return 0;
        }

        return remainingCounts.getOrDefault(
                mobId,
                0
        );
    }

    /**
     * Returns an immutable snapshot of the current remaining counts.
     */
    public Map<String, Integer> getRemainingCounts() {
        return Collections.unmodifiableMap(
                new LinkedHashMap<>(
                        remainingCounts
                )
        );
    }

    /**
     * Returns the next mob ID that runtime should attempt to spawn.
     *
     * This does not alter the queue. Runtime must call
     * markNextMobSpawned only after the entity was successfully added to the
     * world.
     *
     * Returns null when the queue is empty.
     */
    public String peekNextMobId() {
        int mobIndex =
                findNextMobIndex();

        if (mobIndex < 0) {
            return null;
        }

        return mobOrder.get(
                mobIndex
        );
    }

    /**
     * Confirms that the next scheduled mob successfully spawned.
     *
     * This is the only normal operation that reduces the queue. Failed spawn
     * attempts therefore leave the planned mob available for a later retry.
     */
    public void markNextMobSpawned(
            String spawnedMobId
    ) {
        if (spawnedMobId == null
                || spawnedMobId.isBlank()) {
            throw new IllegalArgumentException(
                    "Spawned mob ID cannot be blank."
            );
        }

        int mobIndex =
                findNextMobIndex();

        if (mobIndex < 0) {
            throw new IllegalStateException(
                    "Cannot confirm a mob spawn because the source queue "
                            + "is empty."
            );
        }

        String expectedMobId =
                mobOrder.get(
                        mobIndex
                );

        if (!expectedMobId.equals(
                spawnedMobId
        )) {
            throw new IllegalArgumentException(
                    "Source queue expected mob ID "
                            + expectedMobId
                            + " but runtime reported "
                            + spawnedMobId
                            + "."
            );
        }

        int remainingForMob =
                remainingCounts.getOrDefault(
                        expectedMobId,
                        0
                );

        if (remainingForMob <= 0) {
            throw new IllegalStateException(
                    "Source queue selected mob ID "
                            + expectedMobId
                            + " with no remaining count."
            );
        }

        remainingCounts.put(
                expectedMobId,
                remainingForMob - 1
        );

        remainingMobCount--;

        nextMobIndex =
                (mobIndex + 1)
                        % mobOrder.size();
    }

    /**
     * Cancels every spawn still pending in this wave-specific queue.
     *
     * Returns the number of mobs that were cancelled.
     */
    public int cancelRemainingMobs() {
        int cancelledCount =
                remainingMobCount;

        for (String mobId : mobOrder) {
            remainingCounts.put(
                    mobId,
                    0
            );
        }

        remainingMobCount = 0;

        return cancelledCount;
    }

    /**
     * Finds the next mob entry with a positive remaining count without
     * modifying round-robin state.
     */
    private int findNextMobIndex() {
        if (!hasRemainingMobs()) {
            return -1;
        }

        for (int checkedEntries = 0;
             checkedEntries < mobOrder.size();
             checkedEntries++) {

            int candidateIndex =
                    (nextMobIndex + checkedEntries)
                            % mobOrder.size();

            String mobId =
                    mobOrder.get(
                            candidateIndex
                    );

            if (remainingCounts.getOrDefault(
                    mobId,
                    0
            ) > 0) {
                return candidateIndex;
            }
        }

        throw new IllegalStateException(
                "Source spawn queue reports "
                        + remainingMobCount
                        + " remaining mobs, but no mob entry has a "
                        + "positive remaining count."
        );
    }
}