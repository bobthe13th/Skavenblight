package org.ratden.skavenblight.event.skavenIncursion.runtime.source;

import org.ratden.skavenblight.event.skavenIncursion.planning.composition.SourceGroupComposition;

import java.util.ArrayList;
import java.util.Collections;
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
 * expands the purchased mob entries into individual delivery entries carrying
 * both:
 *
 * - the mob ID to spawn;
 * - the exact amount of planned threat represented by that individual mob.
 *
 * Fresh runtime shuffles those delivery entries once. The exact remaining
 * sequence is then authoritative, persisted, and restored without another
 * shuffle.
 *
 * One queue represents one source composition in one wave. Cancelling this
 * queue therefore does not affect compositions assigned to the same physical
 * source in later waves.
 *
 * Attached-mob assignments may deliberately select a particular remaining
 * mob ID. This allows BEFORE_ORDINARY, WITH_ORDINARY and AFTER_ORDINARY
 * assignments to promote an already-budgeted mob without adding another mob
 * to the composition. When several remaining entries share the same mob ID,
 * the first occurrence in the exact delivery sequence is selected, including
 * its represented threat.
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
     * Original immutable-plan counts by mob ID.
     */
    private final Map<String, Integer> plannedCounts;

    /**
     * Exact individual deliveries contained in the immutable plan before
     * shuffling.
     */
    private final List<DeliveryEntry> plannedDeliveryEntries;

    /**
     * Fixed original planned threat for this source composition.
     */
    private final int plannedThreat;

    /**
     * Mutable remaining count for every canonical mob ID.
     */
    private final Map<String, Integer> remainingCounts;

    /**
     * Exact remaining individual delivery sequence.
     *
     * Duplicate entries represent separate already-purchased mobs.
     */
    private final List<DeliveryEntry> remainingDeliveryEntries;

    /**
     * Cached sum of represented threat still pending in the queue.
     */
    private int remainingThreat;

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

        List<DeliveryEntry> shuffledDeliveryEntries =
                new ArrayList<>(
                        queuePlan.plannedDeliveryEntries()
                );

        Collections.shuffle(
                shuffledDeliveryEntries,
                ThreadLocalRandom.current()
        );

        this.sourceCompositionId =
                queuePlan.sourceCompositionId();

        this.mobOrder =
                queuePlan.mobOrder();

        this.plannedCounts =
                queuePlan.plannedCounts();

        this.plannedDeliveryEntries =
                queuePlan.plannedDeliveryEntries();

        this.plannedThreat =
                queuePlan.plannedThreat();

        this.remainingCounts =
                new LinkedHashMap<>(
                        queuePlan.plannedCounts()
                );

        this.remainingDeliveryEntries =
                shuffledDeliveryEntries;

        this.remainingThreat =
                queuePlan.plannedThreat();

        validateInternalState();
    }

    /**
     * Canonical constructor used by restoration.
     */
    private SourceSpawnQueue(
            QueuePlan queuePlan,
            List<DeliveryEntry> remainingDeliveryEntries
    ) {
        if (queuePlan == null) {
            throw new IllegalArgumentException(
                    "Restored source queue plan cannot be null."
            );
        }

        if (remainingDeliveryEntries == null) {
            throw new IllegalArgumentException(
                    "Restored source queue delivery entries cannot be null."
            );
        }

        this.sourceCompositionId =
                queuePlan.sourceCompositionId();

        this.mobOrder =
                queuePlan.mobOrder();

        this.plannedCounts =
                queuePlan.plannedCounts();

        this.plannedDeliveryEntries =
                queuePlan.plannedDeliveryEntries();

        this.plannedThreat =
                queuePlan.plannedThreat();

        this.remainingCounts =
                createEmptyRemainingCounts(
                        queuePlan
                );

        this.remainingDeliveryEntries =
                new ArrayList<>();

        this.remainingThreat =
                0;

        Map<DeliveryEntry, Integer> availablePlannedEntries =
                countDeliveryEntries(
                        plannedDeliveryEntries
                );

        Map<DeliveryEntry, Integer> restoredEntryCounts =
                new LinkedHashMap<>();

        for (DeliveryEntry deliveryEntry
                : remainingDeliveryEntries) {

            if (deliveryEntry == null) {
                throw new IllegalArgumentException(
                        "Restored source queue cannot contain a null delivery "
                                + "entry."
                );
            }

            int restoredEntryCount =
                    restoredEntryCounts.merge(
                            deliveryEntry,
                            1,
                            Math::addExact
                    );

            int plannedEntryCount =
                    availablePlannedEntries.getOrDefault(
                            deliveryEntry,
                            0
                    );

            if (restoredEntryCount > plannedEntryCount) {
                throw new IllegalArgumentException(
                        "Restored source queue contains more delivery entries "
                                + "for mob ID "
                                + deliveryEntry.mobId()
                                + " representing "
                                + deliveryEntry.representedThreat()
                                + " threat than immutable source composition "
                                + sourceCompositionId
                                + " planned."
                );
            }

            if (!plannedCounts.containsKey(
                    deliveryEntry.mobId()
            )) {
                throw new IllegalArgumentException(
                        "Restored source queue contains mob ID "
                                + deliveryEntry.mobId()
                                + " that does not belong to immutable source "
                                + "composition "
                                + sourceCompositionId
                                + "."
                );
            }

            this.remainingDeliveryEntries.add(
                    deliveryEntry
            );

            this.remainingCounts.put(
                    deliveryEntry.mobId(),
                    Math.addExact(
                            this.remainingCounts.get(
                                    deliveryEntry.mobId()
                            ),
                            1
                    )
            );

            this.remainingThreat =
                    Math.addExact(
                            this.remainingThreat,
                            deliveryEntry.representedThreat()
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
                        snapshot.remainingDeliveryEntries()
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
     * Compatibility view of the exact remaining delivery sequence as mob IDs.
     *
     * New threat-aware consumers should use getRemainingDeliveryEntries().
     */
    public List<String> getRemainingMobOrder() {
        return extractMobOrder(
                remainingDeliveryEntries
        );
    }

    /**
     * Returns the exact remaining individual delivery sequence, including the
     * represented threat carried by every pending mob.
     */
    public List<DeliveryEntry> getRemainingDeliveryEntries() {
        return List.copyOf(
                remainingDeliveryEntries
        );
    }

    public int getPlannedThreat() {
        return plannedThreat;
    }

    public int getRemainingThreat() {
        return remainingThreat;
    }

    public boolean hasRemainingMobs() {
        return !remainingDeliveryEntries.isEmpty();
    }

    public boolean isEmpty() {
        return !hasRemainingMobs();
    }

    public int getRemainingMobCount() {
        return remainingDeliveryEntries.size();
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
     * @return the next remaining delivery entry, or null when the queue is
     * empty
     */
    public DeliveryEntry peekNextDeliveryEntry() {
        if (!hasRemainingMobs()) {
            return null;
        }

        return remainingDeliveryEntries.get(
                0
        );
    }

    /**
     * Compatibility view of peekNextDeliveryEntry().
     */
    public String peekNextMobId() {
        DeliveryEntry deliveryEntry =
                peekNextDeliveryEntry();

        return deliveryEntry == null
                ? null
                : deliveryEntry.mobId();
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
     * @return the next eligible remaining delivery entry, or null when none
     * exists
     */
    public DeliveryEntry peekNextDeliveryEntry(
            Set<String> eligibleMobIds
    ) {
        validateEligibleMobIds(
                eligibleMobIds
        );

        if (eligibleMobIds.isEmpty()
                || !hasRemainingMobs()) {

            return null;
        }

        for (DeliveryEntry candidateEntry
                : remainingDeliveryEntries) {

            if (eligibleMobIds.contains(
                    candidateEntry.mobId()
            )) {
                return candidateEntry;
            }
        }

        return null;
    }

    /**
     * Compatibility view of peekNextDeliveryEntry(Set).
     */
    public String peekNextMobId(
            Set<String> eligibleMobIds
    ) {
        DeliveryEntry deliveryEntry =
                peekNextDeliveryEntry(
                        eligibleMobIds
                );

        return deliveryEntry == null
                ? null
                : deliveryEntry.mobId();
    }

    /**
     * Confirms that the first naturally selected queue entry successfully
     * spawned and returns the exact consumed delivery entry.
     */
    public DeliveryEntry confirmNextMobSpawned(
            String spawnedMobId
    ) {
        requireMobId(
                spawnedMobId
        );

        DeliveryEntry expectedEntry =
                peekNextDeliveryEntry();

        if (expectedEntry == null) {
            throw new IllegalStateException(
                    "Cannot confirm a mob spawn because the source queue is "
                            + "empty."
            );
        }

        if (!expectedEntry.mobId().equals(
                spawnedMobId
        )) {
            throw new IllegalArgumentException(
                    "Source queue expected mob ID "
                            + expectedEntry.mobId()
                            + " but runtime reported "
                            + spawnedMobId
                            + "."
            );
        }

        return consumeDeliveryAtIndex(
                0
        );
    }

    /**
     * Compatibility operation for existing runtime callers.
     */
    public void markNextMobSpawned(
            String spawnedMobId
    ) {
        confirmNextMobSpawned(
                spawnedMobId
        );
    }

    /**
     * Confirms the successful spawn of one deliberately selected remaining
     * mob and returns the exact consumed delivery entry.
     *
     * The first remaining occurrence of that mob ID is consumed. This
     * preserves the relative order of every other purchased mob and retains
     * the represented threat assigned to the selected occurrence.
     */
    public DeliveryEntry confirmMobSpawned(
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
                findFirstDeliveryIndex(
                        spawnedMobId
                );

        if (queueIndex < 0) {
            throw new IllegalStateException(
                    "Source queue has no remaining "
                            + spawnedMobId
                            + " to confirm as spawned."
            );
        }

        return consumeDeliveryAtIndex(
                queueIndex
        );
    }

    /**
     * Compatibility operation for existing runtime callers.
     */
    public void markMobSpawned(
            String spawnedMobId
    ) {
        confirmMobSpawned(
                spawnedMobId
        );
    }

    /**
     * Cancels every spawn still pending in this wave-specific queue and
     * returns both its mob count and represented threat.
     */
    public CancellationResult cancelRemainingDeliveries() {
        CancellationResult cancellationResult =
                new CancellationResult(
                        remainingDeliveryEntries.size(),
                        remainingThreat
                );

        remainingDeliveryEntries.clear();

        for (String mobId
                : mobOrder) {

            remainingCounts.put(
                    mobId,
                    0
            );
        }

        remainingThreat =
                0;

        validateInternalState();

        return cancellationResult;
    }

    /**
     * Compatibility operation returning only the number of mobs cancelled.
     */
    public int cancelRemainingMobs() {
        return cancelRemainingDeliveries()
                .cancelledMobCount();
    }

    /**
     * Captures the exact remaining individual delivery sequence.
     */
    public Snapshot createSnapshot() {
        return new Snapshot(
                sourceCompositionId,
                remainingDeliveryEntries
        );
    }

    /**
     * Consumes one exact entry from the remaining delivery sequence.
     */
    private DeliveryEntry consumeDeliveryAtIndex(
            int queueIndex
    ) {
        if (queueIndex < 0
                || queueIndex >= remainingDeliveryEntries.size()) {

            throw new IllegalArgumentException(
                    "Source queue index "
                            + queueIndex
                            + " is outside the remaining delivery sequence."
            );
        }

        DeliveryEntry consumedEntry =
                remainingDeliveryEntries.remove(
                        queueIndex
                );

        int remainingForMob =
                remainingCounts.getOrDefault(
                        consumedEntry.mobId(),
                        0
                );

        if (remainingForMob <= 0) {
            throw new IllegalStateException(
                    "Source queue selected mob ID "
                            + consumedEntry.mobId()
                            + " with no remaining count."
            );
        }

        remainingCounts.put(
                consumedEntry.mobId(),
                remainingForMob - 1
        );

        remainingThreat =
                Math.subtractExact(
                        remainingThreat,
                        consumedEntry.representedThreat()
                );

        validateInternalState();

        return consumedEntry;
    }

    private int findFirstDeliveryIndex(
            String mobId
    ) {
        for (int deliveryIndex = 0;
             deliveryIndex < remainingDeliveryEntries.size();
             deliveryIndex++) {

            if (mobId.equals(
                    remainingDeliveryEntries
                            .get(
                                    deliveryIndex
                            )
                            .mobId()
            )) {
                return deliveryIndex;
            }
        }

        return -1;
    }

    private void validateEligibleMobIds(
            Set<String> eligibleMobIds
    ) {
        if (eligibleMobIds == null) {
            throw new IllegalArgumentException(
                    "Eligible mob-ID set cannot be null."
            );
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
                || plannedDeliveryEntries == null
                || remainingCounts == null
                || remainingDeliveryEntries == null) {

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

        LinkedHashMap<String, Integer> calculatedPlannedCounts =
                createEmptyCountMap(
                        mobOrder
                );

        int calculatedPlannedThreat =
                0;

        for (DeliveryEntry plannedEntry
                : plannedDeliveryEntries) {

            if (plannedEntry == null) {
                throw new IllegalStateException(
                        "Source spawn queue planned deliveries cannot contain "
                                + "null."
                );
            }

            if (!calculatedPlannedCounts.containsKey(
                    plannedEntry.mobId()
            )) {
                throw new IllegalStateException(
                        "Source spawn queue planned delivery contains "
                                + "unrecognised mob ID "
                                + plannedEntry.mobId()
                                + "."
                );
            }

            calculatedPlannedCounts.put(
                    plannedEntry.mobId(),
                    Math.addExact(
                            calculatedPlannedCounts.get(
                                    plannedEntry.mobId()
                            ),
                            1
                    )
            );

            calculatedPlannedThreat =
                    Math.addExact(
                            calculatedPlannedThreat,
                            plannedEntry.representedThreat()
                    );
        }

        if (!calculatedPlannedCounts.equals(
                plannedCounts
        )) {
            throw new IllegalStateException(
                    "Source spawn queue planned counts do not match its "
                            + "individual planned delivery entries."
            );
        }

        if (calculatedPlannedThreat
                != plannedThreat) {
            throw new IllegalStateException(
                    "Source spawn queue planned delivery entries represent "
                            + calculatedPlannedThreat
                            + " threat, but the queue records "
                            + plannedThreat
                            + "."
            );
        }

        LinkedHashMap<String, Integer> calculatedRemainingCounts =
                createEmptyCountMap(
                        mobOrder
                );

        int calculatedRemainingThreat =
                0;

        Map<DeliveryEntry, Integer> plannedEntryCounts =
                countDeliveryEntries(
                        plannedDeliveryEntries
                );

        Map<DeliveryEntry, Integer> remainingEntryCounts =
                new LinkedHashMap<>();

        for (DeliveryEntry remainingEntry
                : remainingDeliveryEntries) {

            if (remainingEntry == null) {
                throw new IllegalStateException(
                        "Source spawn queue delivery sequence cannot contain "
                                + "null."
                );
            }

            if (!calculatedRemainingCounts.containsKey(
                    remainingEntry.mobId()
            )) {
                throw new IllegalStateException(
                        "Source spawn queue delivery sequence contains "
                                + "unplanned mob ID "
                                + remainingEntry.mobId()
                                + "."
                );
            }

            int remainingEntryCount =
                    remainingEntryCounts.merge(
                            remainingEntry,
                            1,
                            Math::addExact
                    );

            int plannedEntryCount =
                    plannedEntryCounts.getOrDefault(
                            remainingEntry,
                            0
                    );

            if (remainingEntryCount > plannedEntryCount) {
                throw new IllegalStateException(
                        "Source spawn queue contains more remaining entries "
                                + "for mob ID "
                                + remainingEntry.mobId()
                                + " representing "
                                + remainingEntry.representedThreat()
                                + " threat than were planned."
                );
            }

            calculatedRemainingCounts.put(
                    remainingEntry.mobId(),
                    Math.addExact(
                            calculatedRemainingCounts.get(
                                    remainingEntry.mobId()
                            ),
                            1
                    )
            );

            calculatedRemainingThreat =
                    Math.addExact(
                            calculatedRemainingThreat,
                            remainingEntry.representedThreat()
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

        if (remainingThreat < 0) {
            throw new IllegalStateException(
                    "Source spawn queue remaining threat cannot be negative."
            );
        }

        if (calculatedRemainingThreat
                != remainingThreat) {
            throw new IllegalStateException(
                    "Source spawn queue delivery sequence represents "
                            + calculatedRemainingThreat
                            + " remaining threat, but the queue records "
                            + remainingThreat
                            + "."
            );
        }

        if (remainingThreat > plannedThreat) {
            throw new IllegalStateException(
                    "Source spawn queue cannot retain more threat than its "
                            + "immutable source composition planned."
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

        List<DeliveryEntry> plannedDeliveryEntries =
                new ArrayList<>();

        int copiedMobCount =
                0;

        int copiedThreat =
                0;

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

            int representedThreatPerMob =
                    mobEntry.getRepresentedThreatPerMob();

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

            if (representedThreatPerMob <= 0) {
                throw new IllegalArgumentException(
                        "Source composition "
                                + sourceCompositionId
                                + " contains non-positive represented threat "
                                + "for mob ID "
                                + mobId
                                + "."
                );
            }

            copiedCounts.merge(
                    mobId,
                    mobCount,
                    Math::addExact
            );

            for (int mobNumber = 0;
                 mobNumber < mobCount;
                 mobNumber++) {

                plannedDeliveryEntries.add(
                        new DeliveryEntry(
                                mobId,
                                representedThreatPerMob
                        )
                );
            }

            copiedMobCount =
                    Math.addExact(
                            copiedMobCount,
                            mobCount
                    );

            copiedThreat =
                    Math.addExact(
                            copiedThreat,
                            Math.multiplyExact(
                                    mobCount,
                                    representedThreatPerMob
                            )
                    );
        }

        if (copiedCounts.isEmpty()) {
            throw new IllegalArgumentException(
                    "Source composition contains no mob entries."
            );
        }

        if (copiedMobCount
                != sourceComposition.getTotalMobCount()) {

            throw new IllegalArgumentException(
                    "Source composition total mob count does not match the "
                            + "total contained in its mob entries."
            );
        }

        if (copiedThreat
                != sourceComposition.getCalculatedThreatSpent()) {
            throw new IllegalArgumentException(
                    "Source composition mob entries represent "
                            + copiedThreat
                            + " threat, but its calculated threat is "
                            + sourceComposition.getCalculatedThreatSpent()
                            + "."
            );
        }

        if (copiedThreat
                != sourceComposition.getThreatSpent()) {
            throw new IllegalArgumentException(
                    "Source composition mob entries represent "
                            + copiedThreat
                            + " threat, but the admitted plan records "
                            + sourceComposition.getThreatSpent()
                            + "."
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
                ),
                plannedDeliveryEntries,
                copiedThreat
        );
    }

    private static LinkedHashMap<String, Integer>
    createEmptyRemainingCounts(
            QueuePlan queuePlan
    ) {
        return createEmptyCountMap(
                queuePlan.mobOrder()
        );
    }

    private static LinkedHashMap<String, Integer> createEmptyCountMap(
            List<String> canonicalMobOrder
    ) {
        LinkedHashMap<String, Integer> emptyCounts =
                new LinkedHashMap<>();

        for (String mobId
                : canonicalMobOrder) {

            emptyCounts.put(
                    mobId,
                    0
            );
        }

        return emptyCounts;
    }

    private static Map<DeliveryEntry, Integer> countDeliveryEntries(
            List<DeliveryEntry> deliveryEntries
    ) {
        LinkedHashMap<DeliveryEntry, Integer> entryCounts =
                new LinkedHashMap<>();

        for (DeliveryEntry deliveryEntry
                : deliveryEntries) {

            if (deliveryEntry == null) {
                throw new IllegalArgumentException(
                        "Delivery-entry collection cannot contain null."
                );
            }

            entryCounts.merge(
                    deliveryEntry,
                    1,
                    Math::addExact
            );
        }

        return entryCounts;
    }

    private static List<String> extractMobOrder(
            List<DeliveryEntry> deliveryEntries
    ) {
        List<String> mobIds =
                new ArrayList<>(
                        deliveryEntries.size()
                );

        for (DeliveryEntry deliveryEntry
                : deliveryEntries) {

            mobIds.add(
                    deliveryEntry.mobId()
            );
        }

        return List.copyOf(
                mobIds
        );
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
     * One exact individual delivery purchased by the immutable composition.
     *
     * representedThreat is the value assigned by the admitted plan. Runtime
     * must not recalculate it from the live mob catalogue.
     */
    public record DeliveryEntry(
            String mobId,
            int representedThreat
    ) {

        public DeliveryEntry {
            requireMobId(
                    mobId
            );

            if (representedThreat <= 0) {
                throw new IllegalArgumentException(
                        "Delivery-entry represented threat must be greater "
                                + "than zero."
                );
            }
        }
    }

    /**
     * Exact immutable persistence snapshot of mutable queue progress.
     */
    public record Snapshot(
            UUID sourceCompositionId,
            List<DeliveryEntry> remainingDeliveryEntries
    ) {

        public Snapshot {
            if (sourceCompositionId == null) {
                throw new IllegalArgumentException(
                        "Source spawn-queue snapshot composition ID cannot be "
                                + "null."
                );
            }

            if (remainingDeliveryEntries == null) {
                throw new IllegalArgumentException(
                        "Source spawn-queue snapshot delivery entries cannot "
                                + "be null."
                );
            }

            List<DeliveryEntry> copiedDeliveryEntries =
                    new ArrayList<>(
                            remainingDeliveryEntries.size()
                    );

            for (DeliveryEntry deliveryEntry
                    : remainingDeliveryEntries) {

                if (deliveryEntry == null) {
                    throw new IllegalArgumentException(
                            "Source spawn-queue snapshot cannot contain a "
                                    + "null delivery entry."
                    );
                }

                copiedDeliveryEntries.add(
                        deliveryEntry
                );
            }

            remainingDeliveryEntries =
                    List.copyOf(
                            copiedDeliveryEntries
                    );
        }

        /**
         * Compatibility view used by existing inspection output.
         */
        public List<String> remainingMobOrder() {
            return extractMobOrder(
                    remainingDeliveryEntries
            );
        }

        public int remainingMobCount() {
            return remainingDeliveryEntries.size();
        }

        public int remainingThreat() {
            int totalThreat =
                    0;

            for (DeliveryEntry deliveryEntry
                    : remainingDeliveryEntries) {

                totalThreat =
                        Math.addExact(
                                totalThreat,
                                deliveryEntry.representedThreat()
                        );
            }

            return totalThreat;
        }

        public boolean hasRemainingMobs() {
            return !remainingDeliveryEntries.isEmpty();
        }

        public boolean isEmpty() {
            return remainingDeliveryEntries.isEmpty();
        }
    }

    /**
     * Result of terminally cancelling every pending queue entry.
     */
    public record CancellationResult(
            int cancelledMobCount,
            int cancelledThreat
    ) {

        public CancellationResult {
            if (cancelledMobCount < 0) {
                throw new IllegalArgumentException(
                        "Cancelled mob count cannot be negative."
                );
            }

            if (cancelledThreat < 0) {
                throw new IllegalArgumentException(
                        "Cancelled threat cannot be negative."
                );
            }

            if ((cancelledMobCount == 0)
                    != (cancelledThreat == 0)) {

                throw new IllegalArgumentException(
                        "Cancelled mob count and threat must either both be "
                                + "zero or both be positive."
                );
            }
        }
    }

    /**
     * Immutable canonical queue structure derived from the IncursionPlan.
     */
    private record QueuePlan(
            UUID sourceCompositionId,
            List<String> mobOrder,
            Map<String, Integer> plannedCounts,
            List<DeliveryEntry> plannedDeliveryEntries,
            int plannedThreat
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

            if (plannedDeliveryEntries == null
                    || plannedDeliveryEntries.isEmpty()) {

                throw new IllegalArgumentException(
                        "Source queue plan requires individual delivery "
                                + "entries."
                );
            }

            if (plannedThreat <= 0) {
                throw new IllegalArgumentException(
                        "Source queue plan threat must be greater than zero."
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

            plannedDeliveryEntries =
                    List.copyOf(
                            plannedDeliveryEntries
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