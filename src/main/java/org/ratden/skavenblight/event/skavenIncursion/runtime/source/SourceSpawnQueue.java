package org.ratden.skavenblight.event.skavenIncursion.runtime.source;

import org.ratden.skavenblight.event.skavenIncursion.planning.composition.SourceGroupComposition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Mutable runtime queue for one planned SourceComposition.
 *
 * SourceComposition remains the immutable planning authority. This queue
 * expands the purchased mob counts into individual delivery entries and
 * shuffles those entries once when fresh runtime is created.
 *
 * The resulting sequence is then authoritative for baseline ordinary
 * delivery. It is persisted exactly and is never shuffled again during
 * restoration.
 *
 * One queue represents one source composition in one wave. Cancelling this
 * queue therefore does not affect compositions assigned to the same physical
 * source in later waves.
 *
 * Attached-mob assignments may deliberately select a particular remaining
 * mob ID. This allows BEFORE_ORDINARY, WITH_ORDINARY and AFTER_ORDINARY
 * assignments to promote an already-budgeted mob without adding another mob
 * to the composition.
 */
public final class SourceSpawnQueue {

    private final UUID sourceCompositionId;

    /**
     * Canonical unique mob-type order copied from the immutable
     * SourceComposition.
     *
     * This is used for validation, count reporting and attachment reservation.
     * It is not the runtime delivery order.
     */
    private final List<String> mobOrder;

    /**
     * Original immutable-plan counts.
     */
    private final Map<String, Integer> plannedCounts;

    /**
     * Mutable remaining count for every canonical mob ID.
     */
    private final Map<String, Integer> remainingCounts;

    /**
     * Exact remaining individual delivery sequence.
     *
     * Duplicate mob IDs represent separate already-purchased mobs.
     */
    private final List<String> remainingMobOrder;

    /**
     * Creates a fresh randomly ordered queue from one immutable source
     * composition.
     */
    public SourceSpawnQueue(
            SourceGroupComposition.SourceComposition sourceComposition
    ) {
        QueuePlan queuePlan =
                createQueuePlan(
                        sourceComposition
                );

        List<String> shuffledMobOrder =
                expandPlannedMobOrder(
                        queuePlan
                );

        Collections.shuffle(
                shuffledMobOrder,
                ThreadLocalRandom.current()
        );

        this.sourceCompositionId =
                queuePlan.sourceCompositionId();

        this.mobOrder =
                queuePlan.mobOrder();

        this.plannedCounts =
                queuePlan.plannedCounts();

        this.remainingCounts =
                new LinkedHashMap<>(
                        queuePlan.plannedCounts()
                );

        this.remainingMobOrder =
                new ArrayList<>(
                        shuffledMobOrder
                );

        validateInternalState();
    }

    /**
     * Canonical constructor used by restoration.
     */
    private SourceSpawnQueue(
            QueuePlan queuePlan,
            List<String> remainingMobOrder
    ) {
        if (queuePlan == null) {
            throw new IllegalArgumentException(
                    "Restored source queue plan cannot be null."
            );
        }

        if (remainingMobOrder == null) {
            throw new IllegalArgumentException(
                    "Restored source queue order cannot be null."
            );
        }

        this.sourceCompositionId =
                queuePlan.sourceCompositionId();

        this.mobOrder =
                queuePlan.mobOrder();

        this.plannedCounts =
                queuePlan.plannedCounts();

        this.remainingCounts =
                createEmptyRemainingCounts(
                        queuePlan
                );

        this.remainingMobOrder =
                new ArrayList<>();

        for (String mobId
                : remainingMobOrder) {

            requireMobId(
                    mobId
            );

            if (!plannedCounts.containsKey(
                    mobId
            )) {
                throw new IllegalArgumentException(
                        "Restored source queue contains mob ID "
                                + mobId
                                + " that does not belong to immutable source "
                                + "composition "
                                + sourceCompositionId
                                + "."
                );
            }

            this.remainingMobOrder.add(
                    mobId
            );

            remainingCounts.put(
                    mobId,
                    remainingCounts.get(
                            mobId
                    ) + 1
            );
        }

        validateInternalState();
    }

    /**
     * Restores exact queue progress against its immutable source composition.
     */
    public static SourceSpawnQueue restore(
            SourceGroupComposition.SourceComposition sourceComposition,
            Snapshot snapshot
    ) {
        if (snapshot == null) {
            throw new IllegalArgumentException(
                    "Source spawn-queue snapshot cannot be null."
            );
        }

        QueuePlan queuePlan =
                createQueuePlan(
                        sourceComposition
                );

        if (!queuePlan
                .sourceCompositionId()
                .equals(
                        snapshot.sourceCompositionId()
                )) {

            throw new IllegalArgumentException(
                    "Source spawn-queue snapshot belongs to composition "
                            + snapshot.sourceCompositionId()
                            + " rather than supplied composition "
                            + queuePlan.sourceCompositionId()
                            + "."
            );
        }

        SourceSpawnQueue restoredQueue =
                new SourceSpawnQueue(
                        queuePlan,
                        snapshot.remainingMobOrder()
                );

        Snapshot reconstructedSnapshot =
                restoredQueue.createSnapshot();

        if (!snapshot.equals(
                reconstructedSnapshot
        )) {
            throw new IllegalArgumentException(
                    "Restored source spawn queue does not exactly match its "
                            + "saved snapshot for source composition "
                            + snapshot.sourceCompositionId()
                            + "."
            );
        }

        return restoredQueue;
    }

    public UUID getSourceCompositionId() {
        return sourceCompositionId;
    }

    /**
     * Returns the immutable composition's canonical unique mob-type order.
     *
     * This is not the shuffled individual delivery sequence.
     */
    public List<String> getMobOrder() {
        return mobOrder;
    }

    /**
     * Returns the exact remaining individual delivery sequence.
     */
    public List<String> getRemainingMobOrder() {
        return List.copyOf(
                remainingMobOrder
        );
    }

    public boolean hasRemainingMobs() {
        return !remainingMobOrder.isEmpty();
    }

    public boolean isEmpty() {
        return !hasRemainingMobs();
    }

    public int getRemainingMobCount() {
        return remainingMobOrder.size();
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

    public int getPlannedCount(
            String mobId
    ) {
        if (mobId == null
                || mobId.isBlank()) {

            return 0;
        }

        return plannedCounts.getOrDefault(
                mobId,
                0
        );
    }

    public boolean containsMobId(
            String mobId
    ) {
        if (mobId == null
                || mobId.isBlank()) {

            return false;
        }

        return plannedCounts.containsKey(
                mobId
        );
    }

    /**
     * Returns whether at least one mob using this ID remains available.
     */
    public boolean canSpawnMob(
            String mobId
    ) {
        return getRemainingCount(
                mobId
        ) > 0;
    }

    /**
     * Returns an immutable snapshot of current remaining counts in immutable
     * plan order.
     */
    public Map<String, Integer> getRemainingCounts() {
        return Collections.unmodifiableMap(
                new LinkedHashMap<>(
                        remainingCounts
                )
        );
    }

    /**
     * Returns the first entry in the shuffled remaining delivery sequence.
     *
     * This does not alter the queue.
     *
     * @return the next remaining mob ID, or null when the queue is empty
     */
    public String peekNextMobId() {
        if (!hasRemainingMobs()) {
            return null;
        }

        return remainingMobOrder.get(
                0
        );
    }

    /**
     * Returns the first entry in the shuffled remaining delivery sequence
     * whose mob ID is currently eligible.
     *
     * This does not alter the queue. Ineligible entries remain in their exact
     * saved positions and may become eligible during a later delivery phase.
     *
     * Attached-assignment runtime uses this to protect mobs reserved for later
     * attachment phases without destroying the randomised baseline order.
     *
     * @return the next eligible remaining mob ID, or null when none exists
     */
    public String peekNextMobId(
            Set<String> eligibleMobIds
    ) {
        if (eligibleMobIds == null) {
            throw new IllegalArgumentException(
                    "Eligible mob-ID set cannot be null."
            );
        }

        if (eligibleMobIds.isEmpty()
                || !hasRemainingMobs()) {

            return null;
        }

        for (String eligibleMobId
                : eligibleMobIds) {

            requireMobId(
                    eligibleMobId
            );

            if (!plannedCounts.containsKey(
                    eligibleMobId
            )) {
                throw new IllegalArgumentException(
                        "Eligible mob ID "
                                + eligibleMobId
                                + " does not belong to source composition "
                                + sourceCompositionId
                                + "."
                );
            }
        }

        for (String candidateMobId
                : remainingMobOrder) {

            if (eligibleMobIds.contains(
                    candidateMobId
            )) {
                return candidateMobId;
            }
        }

        return null;
    }

    /**
     * Confirms that the first naturally selected queue entry successfully
     * spawned.
     *
     * Attached assignments or reservation-aware ordinary delivery that
     * deliberately select a later eligible entry must instead call
     * markMobSpawned(...).
     */
    public void markNextMobSpawned(
            String spawnedMobId
    ) {
        requireMobId(
                spawnedMobId
        );

        String expectedMobId =
                peekNextMobId();

        if (expectedMobId == null) {
            throw new IllegalStateException(
                    "Cannot confirm a mob spawn because the source queue is "
                            + "empty."
            );
        }

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

        consumeMobAtIndex(
                0
        );
    }

    /**
     * Confirms the successful spawn of one deliberately selected remaining
     * mob.
     *
     * The first remaining occurrence of that mob ID is consumed. This
     * preserves the relative order of every other purchased mob.
     *
     * This is used by:
     *
     * - attached assignments whose spawn priority overrides ordinary order;
     * - ordinary delivery that skipped entries reserved for attachments.
     */
    public void markMobSpawned(
            String spawnedMobId
    ) {
        requireMobId(
                spawnedMobId
        );

        if (!plannedCounts.containsKey(
                spawnedMobId
        )) {
            throw new IllegalArgumentException(
                    "Mob ID "
                            + spawnedMobId
                            + " does not belong to source composition "
                            + sourceCompositionId
                            + "."
            );
        }

        int queueIndex =
                remainingMobOrder.indexOf(
                        spawnedMobId
                );

        if (queueIndex < 0) {
            throw new IllegalStateException(
                    "Source queue has no remaining "
                            + spawnedMobId
                            + " to confirm as spawned."
            );
        }

        consumeMobAtIndex(
                queueIndex
        );
    }

    /**
     * Cancels every spawn still pending in this wave-specific queue.
     *
     * @return number of mobs newly cancelled
     */
    public int cancelRemainingMobs() {
        int cancelledCount =
                remainingMobOrder.size();

        remainingMobOrder.clear();

        for (String mobId
                : mobOrder) {

            remainingCounts.put(
                    mobId,
                    0
            );
        }

        validateInternalState();

        return cancelledCount;
    }

    /**
     * Captures the exact remaining individual delivery sequence.
     */
    public Snapshot createSnapshot() {
        return new Snapshot(
                sourceCompositionId,
                remainingMobOrder
        );
    }

    /**
     * Consumes one exact entry from the remaining delivery sequence.
     */
    private void consumeMobAtIndex(
            int queueIndex
    ) {
        if (queueIndex < 0
                || queueIndex >= remainingMobOrder.size()) {

            throw new IllegalArgumentException(
                    "Source queue index "
                            + queueIndex
                            + " is outside the remaining delivery sequence."
            );
        }

        String spawnedMobId =
                remainingMobOrder.remove(
                        queueIndex
                );

        int remainingForMob =
                remainingCounts.getOrDefault(
                        spawnedMobId,
                        0
                );

        if (remainingForMob <= 0) {
            throw new IllegalStateException(
                    "Source queue selected mob ID "
                            + spawnedMobId
                            + " with no remaining count."
            );
        }

        remainingCounts.put(
                spawnedMobId,
                remainingForMob - 1
        );

        validateInternalState();
    }

    private void validateInternalState() {
        if (sourceCompositionId == null) {
            throw new IllegalStateException(
                    "Source spawn queue has no source-composition ID."
            );
        }

        if (mobOrder == null
                || mobOrder.isEmpty()) {

            throw new IllegalStateException(
                    "Source spawn queue requires at least one canonical mob "
                            + "ID."
            );
        }

        if (plannedCounts == null
                || remainingCounts == null
                || remainingMobOrder == null) {

            throw new IllegalStateException(
                    "Source spawn queue collections cannot be null."
            );
        }

        if (!plannedCounts.keySet().equals(
                remainingCounts.keySet()
        )) {
            throw new IllegalStateException(
                    "Source spawn queue remaining mob IDs do not match its "
                            + "planned mob IDs."
            );
        }

        if (!new ArrayList<>(
                plannedCounts.keySet()
        ).equals(
                mobOrder
        )) {
            throw new IllegalStateException(
                    "Source spawn queue canonical mob order does not match "
                            + "immutable planned-count order."
            );
        }

        LinkedHashMap<String, Integer>
                calculatedRemainingCounts =
                new LinkedHashMap<>();

        for (String mobId
                : mobOrder) {

            Integer plannedCount =
                    plannedCounts.get(
                            mobId
                    );

            Integer remainingCount =
                    remainingCounts.get(
                            mobId
                    );

            if (plannedCount == null
                    || plannedCount <= 0) {

                throw new IllegalStateException(
                        "Source spawn queue contains invalid planned count for "
                                + mobId
                                + "."
                );
            }

            if (remainingCount == null
                    || remainingCount < 0) {

                throw new IllegalStateException(
                        "Source spawn queue contains invalid remaining count "
                                + "for "
                                + mobId
                                + "."
                );
            }

            if (remainingCount > plannedCount) {
                throw new IllegalStateException(
                        "Source spawn queue contains more remaining "
                                + mobId
                                + " than were originally planned."
                );
            }

            calculatedRemainingCounts.put(
                    mobId,
                    0
            );
        }

        for (String mobId
                : remainingMobOrder) {

            requireMobId(
                    mobId
            );

            if (!plannedCounts.containsKey(
                    mobId
            )) {
                throw new IllegalStateException(
                        "Source spawn queue delivery sequence contains "
                                + "unplanned mob ID "
                                + mobId
                                + "."
                );
            }

            calculatedRemainingCounts.put(
                    mobId,
                    calculatedRemainingCounts.get(
                            mobId
                    ) + 1
            );
        }

        if (!calculatedRemainingCounts.equals(
                remainingCounts
        )) {
            throw new IllegalStateException(
                    "Source spawn queue remaining counts do not match its "
                            + "individual delivery sequence."
            );
        }
    }

    private static QueuePlan createQueuePlan(
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

        UUID sourceCompositionId =
                sourceComposition.getSourceCompositionId();

        if (sourceCompositionId == null) {
            throw new IllegalArgumentException(
                    "Source composition has no ID."
            );
        }

        LinkedHashMap<String, Integer> copiedCounts =
                new LinkedHashMap<>();

        for (SourceGroupComposition.MobEntry mobEntry
                : sourceComposition.getMobEntries()) {

            if (mobEntry == null) {
                throw new IllegalArgumentException(
                        "Source composition "
                                + sourceCompositionId
                                + " contains a null mob entry."
                );
            }

            String mobId =
                    mobEntry.getMobId();

            int mobCount =
                    mobEntry.getCount();

            requireMobId(
                    mobId
            );

            if (mobCount <= 0) {
                throw new IllegalArgumentException(
                        "Source composition "
                                + sourceCompositionId
                                + " contains non-positive count for mob ID "
                                + mobId
                                + "."
                );
            }

            copiedCounts.merge(
                    mobId,
                    mobCount,
                    Integer::sum
            );
        }

        if (copiedCounts.isEmpty()) {
            throw new IllegalArgumentException(
                    "Source composition contains no mob entries."
            );
        }

        int copiedMobCount =
                copiedCounts.values()
                        .stream()
                        .mapToInt(
                                Integer::intValue
                        )
                        .sum();

        if (copiedMobCount
                != sourceComposition.getTotalMobCount()) {

            throw new IllegalArgumentException(
                    "Source composition total mob count does not match the "
                            + "total contained in its mob entries."
            );
        }

        return new QueuePlan(
                sourceCompositionId,
                List.copyOf(
                        copiedCounts.keySet()
                ),
                Collections.unmodifiableMap(
                        new LinkedHashMap<>(
                                copiedCounts
                        )
                )
        );
    }

    private static List<String> expandPlannedMobOrder(
            QueuePlan queuePlan
    ) {
        List<String> expandedMobOrder =
                new ArrayList<>();

        for (String mobId
                : queuePlan.mobOrder()) {

            int plannedCount =
                    queuePlan.plannedCounts()
                            .get(
                                    mobId
                            );

            for (int mobNumber = 0;
                 mobNumber < plannedCount;
                 mobNumber++) {

                expandedMobOrder.add(
                        mobId
                );
            }
        }

        return expandedMobOrder;
    }

    private static LinkedHashMap<String, Integer>
    createEmptyRemainingCounts(
            QueuePlan queuePlan
    ) {
        LinkedHashMap<String, Integer> emptyCounts =
                new LinkedHashMap<>();

        for (String mobId
                : queuePlan.mobOrder()) {

            emptyCounts.put(
                    mobId,
                    0
            );
        }

        return emptyCounts;
    }

    private static void requireMobId(
            String mobId
    ) {
        if (mobId == null
                || mobId.isBlank()) {

            throw new IllegalArgumentException(
                    "Mob ID cannot be blank."
            );
        }
    }

    /**
     * Exact immutable persistence snapshot of mutable queue progress.
     */
    public record Snapshot(
            UUID sourceCompositionId,
            List<String> remainingMobOrder
    ) {

        public Snapshot {
            if (sourceCompositionId == null) {
                throw new IllegalArgumentException(
                        "Source spawn-queue snapshot composition ID cannot be "
                                + "null."
                );
            }

            if (remainingMobOrder == null) {
                throw new IllegalArgumentException(
                        "Source spawn-queue snapshot order cannot be null."
                );
            }

            List<String> copiedMobOrder =
                    new ArrayList<>();

            for (String mobId
                    : remainingMobOrder) {

                requireMobId(
                        mobId
                );

                copiedMobOrder.add(
                        mobId
                );
            }

            remainingMobOrder =
                    List.copyOf(
                            copiedMobOrder
                    );
        }

        public int remainingMobCount() {
            return remainingMobOrder.size();
        }

        public boolean hasRemainingMobs() {
            return !remainingMobOrder.isEmpty();
        }

        public boolean isEmpty() {
            return remainingMobOrder.isEmpty();
        }
    }

    /**
     * Immutable canonical queue structure derived from the IncursionPlan.
     */
    private record QueuePlan(
            UUID sourceCompositionId,
            List<String> mobOrder,
            Map<String, Integer> plannedCounts
    ) {

        private QueuePlan {
            if (sourceCompositionId == null) {
                throw new IllegalArgumentException(
                        "Source queue plan composition ID cannot be null."
                );
            }

            if (mobOrder == null
                    || mobOrder.isEmpty()) {

                throw new IllegalArgumentException(
                        "Source queue plan requires canonical mob order."
                );
            }

            if (plannedCounts == null
                    || plannedCounts.isEmpty()) {

                throw new IllegalArgumentException(
                        "Source queue plan requires planned counts."
                );
            }

            mobOrder =
                    List.copyOf(
                            mobOrder
                    );

            plannedCounts =
                    Collections.unmodifiableMap(
                            new LinkedHashMap<>(
                                    plannedCounts
                            )
                    );

            if (!new ArrayList<>(
                    plannedCounts.keySet()
            ).equals(
                    mobOrder
            )) {
                throw new IllegalArgumentException(
                        "Source queue plan mob order does not match its "
                                + "planned-count order."
                );
            }
        }
    }
}