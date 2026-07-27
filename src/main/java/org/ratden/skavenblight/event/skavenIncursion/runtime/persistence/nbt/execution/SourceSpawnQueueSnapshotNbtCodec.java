package org.ratden.skavenblight.event.skavenIncursion.runtime.persistence.nbt.execution;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.ratden.skavenblight.event.skavenIncursion.planning.composition.IncursionMobCatalogue;
import org.ratden.skavenblight.event.skavenIncursion.planning.composition.IncursionMobDefinition;
import org.ratden.skavenblight.event.skavenIncursion.planning.persistence.nbt.IncursionSnapshotNbtSupport;
import org.ratden.skavenblight.event.skavenIncursion.runtime.source.SourceSpawnQueue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CompoundTag codec for SourceSpawnQueue.Snapshot.
 *
 * Format version 3 preserves:
 *
 * - source-composition identity;
 * - the exact remaining individual delivery sequence;
 * - the represented threat carried by every pending delivery entry.
 *
 * The sequence has already been shuffled by fresh runtime. Restoration reads
 * it exactly and never performs another shuffle.
 *
 * Two older unversioned development formats remain readable:
 *
 * - legacy count/cursor queues are reconstructed into the exact round-robin
 *   order they would have delivered;
 * - exact string-only queues retain their saved order.
 *
 * Neither legacy format stored represented threat on individual entries. The
 * ordinary threat value is therefore resolved from the current incursion mob
 * catalogue during migration. SourceSpawnQueue.restore(...) subsequently
 * validates every migrated entry against the immutable persisted source
 * composition. A balance change that makes the legacy values incompatible is
 * rejected rather than silently altering the admitted plan.
 */
public final class SourceSpawnQueueSnapshotNbtCodec {

    private static final int CURRENT_FORMAT_VERSION =
            3;

    private static final String FORMAT_VERSION =
            "format_version";

    private static final String SOURCE_COMPOSITION_ID =
            "source_composition_id";

    private static final String REMAINING_DELIVERY_ENTRIES =
            "remaining_delivery_entries";

    private static final String MOB_ID =
            "mob_id";

    private static final String REPRESENTED_THREAT =
            "represented_threat";

    /*
     * Legacy exact-order field retained for read compatibility only.
     */
    private static final String LEGACY_REMAINING_MOB_ORDER =
            "remaining_mob_order";

    /*
     * Older count-and-cursor fields retained for read compatibility only.
     */
    private static final String LEGACY_REMAINING_COUNTS =
            "remaining_counts";

    private static final String LEGACY_NEXT_MOB_INDEX =
            "next_mob_index";

    private static final String LEGACY_REMAINING_MOB_COUNT =
            "remaining_mob_count";

    private SourceSpawnQueueSnapshotNbtCodec() {
    }

    public static CompoundTag write(
            SourceSpawnQueue.Snapshot snapshot
    ) {
        if (snapshot == null) {
            throw new IllegalArgumentException(
                    "Source-spawn queue snapshot cannot be null."
            );
        }

        CompoundTag tag =
                new CompoundTag();

        tag.putInt(
                FORMAT_VERSION,
                CURRENT_FORMAT_VERSION
        );

        tag.putUUID(
                SOURCE_COMPOSITION_ID,
                snapshot.sourceCompositionId()
        );

        ListTag remainingDeliveryEntryTags =
                new ListTag();

        for (SourceSpawnQueue.DeliveryEntry deliveryEntry
                : snapshot.remainingDeliveryEntries()) {

            remainingDeliveryEntryTags.add(
                    writeDeliveryEntry(
                            deliveryEntry
                    )
            );
        }

        tag.put(
                REMAINING_DELIVERY_ENTRIES,
                remainingDeliveryEntryTags
        );

        return tag;
    }

    public static SourceSpawnQueue.Snapshot read(
            CompoundTag tag
    ) {
        if (tag == null) {
            throw new IllegalArgumentException(
                    "Source-spawn queue NBT cannot be null."
            );
        }

        UUID sourceCompositionId =
                IncursionSnapshotNbtSupport.requireUuid(
                        tag,
                        SOURCE_COMPOSITION_ID
                );

        if (tag.contains(
                FORMAT_VERSION
        )) {
            int formatVersion =
                    readFormatVersion(
                            tag
                    );

            if (formatVersion
                    != CURRENT_FORMAT_VERSION) {

                throw unsupportedFormatVersion(
                        formatVersion
                );
            }

            return new SourceSpawnQueue.Snapshot(
                    sourceCompositionId,
                    readDeliveryEntries(
                            tag
                    )
            );
        }

        if (tag.contains(
                LEGACY_REMAINING_MOB_ORDER
        )) {
            return new SourceSpawnQueue.Snapshot(
                    sourceCompositionId,
                    migrateLegacyMobOrder(
                            readLegacyRemainingMobOrder(
                                    tag
                            )
                    )
            );
        }

        return readLegacyCountCursorSnapshot(
                tag,
                sourceCompositionId
        );
    }

    private static CompoundTag writeDeliveryEntry(
            SourceSpawnQueue.DeliveryEntry deliveryEntry
    ) {
        if (deliveryEntry == null) {
            throw new IllegalArgumentException(
                    "Source-spawn delivery entry cannot be null."
            );
        }

        CompoundTag tag =
                new CompoundTag();

        tag.putString(
                MOB_ID,
                deliveryEntry.mobId()
        );

        tag.putInt(
                REPRESENTED_THREAT,
                deliveryEntry.representedThreat()
        );

        return tag;
    }

    private static List<SourceSpawnQueue.DeliveryEntry>
    readDeliveryEntries(
            CompoundTag tag
    ) {
        ListTag deliveryEntryTags =
                IncursionSnapshotNbtSupport.requireCompoundList(
                        tag,
                        REMAINING_DELIVERY_ENTRIES
                );

        List<SourceSpawnQueue.DeliveryEntry> deliveryEntries =
                new ArrayList<>();

        for (int deliveryIndex = 0;
             deliveryIndex < deliveryEntryTags.size();
             deliveryIndex++) {

            try {
                CompoundTag deliveryEntryTag =
                        deliveryEntryTags.getCompound(
                                deliveryIndex
                        );

                deliveryEntries.add(
                        new SourceSpawnQueue.DeliveryEntry(
                                IncursionSnapshotNbtSupport.requireString(
                                        deliveryEntryTag,
                                        MOB_ID
                                ),
                                IncursionSnapshotNbtSupport.requireInt(
                                        deliveryEntryTag,
                                        REPRESENTED_THREAT
                                )
                        )
                );
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                        "Failed to read source-spawn delivery entry at index "
                                + deliveryIndex
                                + ".",
                        exception
                );
            }
        }

        return List.copyOf(
                deliveryEntries
        );
    }

    private static int readFormatVersion(
            CompoundTag tag
    ) {
        if (!tag.contains(
                FORMAT_VERSION,
                Tag.TAG_INT
        )) {
            throw new IllegalArgumentException(
                    "Source-spawn queue NBT field '"
                            + FORMAT_VERSION
                            + "' is not a valid integer."
            );
        }

        int formatVersion =
                tag.getInt(
                        FORMAT_VERSION
                );

        if (formatVersion
                != CURRENT_FORMAT_VERSION) {

            throw unsupportedFormatVersion(
                    formatVersion
            );
        }

        return formatVersion;
    }

    private static IllegalArgumentException unsupportedFormatVersion(
            int formatVersion
    ) {
        return new IllegalArgumentException(
                "Unsupported source-spawn queue format version "
                        + formatVersion
                        + ". Current supported version is "
                        + CURRENT_FORMAT_VERSION
                        + "."
        );
    }

    private static List<String> readLegacyRemainingMobOrder(
            CompoundTag tag
    ) {
        ListTag remainingMobOrderTag =
                IncursionSnapshotNbtSupport.requireStringList(
                        tag,
                        LEGACY_REMAINING_MOB_ORDER
                );

        List<String> remainingMobOrder =
                new ArrayList<>();

        for (int mobIndex = 0;
             mobIndex < remainingMobOrderTag.size();
             mobIndex++) {

            remainingMobOrder.add(
                    remainingMobOrderTag.getString(
                            mobIndex
                    )
            );
        }

        return List.copyOf(
                remainingMobOrder
        );
    }

    /**
     * Converts the former count-and-cursor representation into the exact
     * sequence its round-robin queue would have produced, then adds the
     * represented threat that the older format did not store.
     */
    private static SourceSpawnQueue.Snapshot
    readLegacyCountCursorSnapshot(
            CompoundTag tag,
            UUID sourceCompositionId
    ) {
        Map<String, Integer> savedRemainingCounts =
                IncursionSnapshotNbtSupport.readStringIntMap(
                        tag,
                        LEGACY_REMAINING_COUNTS
                );

        int nextMobIndex =
                IncursionSnapshotNbtSupport.requireInt(
                        tag,
                        LEGACY_NEXT_MOB_INDEX
                );

        int savedRemainingMobCount =
                IncursionSnapshotNbtSupport.requireInt(
                        tag,
                        LEGACY_REMAINING_MOB_COUNT
                );

        List<String> canonicalMobOrder =
                new ArrayList<>(
                        savedRemainingCounts.keySet()
                );

        if (canonicalMobOrder.isEmpty()) {
            throw new IllegalArgumentException(
                    "Legacy source-spawn queue contains no canonical mob "
                            + "entries."
            );
        }

        if (nextMobIndex < 0
                || nextMobIndex >= canonicalMobOrder.size()) {

            throw new IllegalArgumentException(
                    "Legacy source-spawn queue next-mob index "
                            + nextMobIndex
                            + " is outside its canonical mob order."
            );
        }

        LinkedHashMap<String, Integer> mutableRemainingCounts =
                new LinkedHashMap<>();

        int calculatedRemainingMobCount =
                0;

        for (String mobId
                : canonicalMobOrder) {

            Integer remainingCount =
                    savedRemainingCounts.get(
                            mobId
                    );

            if (remainingCount == null
                    || remainingCount < 0) {

                throw new IllegalArgumentException(
                        "Legacy source-spawn queue contains invalid remaining "
                                + "count for mob ID "
                                + mobId
                                + "."
                );
            }

            mutableRemainingCounts.put(
                    mobId,
                    remainingCount
            );

            calculatedRemainingMobCount =
                    Math.addExact(
                            calculatedRemainingMobCount,
                            remainingCount
                    );
        }

        if (savedRemainingMobCount < 0) {
            throw new IllegalArgumentException(
                    "Legacy source-spawn queue remaining total cannot be "
                            + "negative."
            );
        }

        if (calculatedRemainingMobCount
                != savedRemainingMobCount) {

            throw new IllegalArgumentException(
                    "Legacy source-spawn queue count entries total "
                            + calculatedRemainingMobCount
                            + ", but its recorded remaining total is "
                            + savedRemainingMobCount
                            + "."
            );
        }

        List<String> reconstructedRemainingOrder =
                reconstructLegacyRemainingOrder(
                        canonicalMobOrder,
                        mutableRemainingCounts,
                        nextMobIndex,
                        savedRemainingMobCount
                );

        return new SourceSpawnQueue.Snapshot(
                sourceCompositionId,
                migrateLegacyMobOrder(
                        reconstructedRemainingOrder
                )
        );
    }

    private static List<String> reconstructLegacyRemainingOrder(
            List<String> canonicalMobOrder,
            Map<String, Integer> mutableRemainingCounts,
            int initialNextMobIndex,
            int remainingMobCount
    ) {
        List<String> reconstructedOrder =
                new ArrayList<>(
                        remainingMobCount
                );

        int nextMobIndex =
                initialNextMobIndex;

        while (reconstructedOrder.size()
                < remainingMobCount) {

            int selectedMobIndex =
                    -1;

            for (int checkedEntries = 0;
                 checkedEntries < canonicalMobOrder.size();
                 checkedEntries++) {

                int candidateMobIndex =
                        (nextMobIndex + checkedEntries)
                                % canonicalMobOrder.size();

                String candidateMobId =
                        canonicalMobOrder.get(
                                candidateMobIndex
                        );

                if (mutableRemainingCounts.getOrDefault(
                        candidateMobId,
                        0
                ) > 0) {
                    selectedMobIndex =
                            candidateMobIndex;

                    break;
                }
            }

            if (selectedMobIndex < 0) {
                throw new IllegalArgumentException(
                        "Legacy source-spawn queue reports remaining mobs, "
                                + "but no canonical mob entry has a positive "
                                + "remaining count."
                );
            }

            String selectedMobId =
                    canonicalMobOrder.get(
                            selectedMobIndex
                    );

            reconstructedOrder.add(
                    selectedMobId
            );

            mutableRemainingCounts.put(
                    selectedMobId,
                    mutableRemainingCounts.get(
                            selectedMobId
                    ) - 1
            );

            nextMobIndex =
                    (selectedMobIndex + 1)
                            % canonicalMobOrder.size();
        }

        return List.copyOf(
                reconstructedOrder
        );
    }

    private static List<SourceSpawnQueue.DeliveryEntry>
    migrateLegacyMobOrder(
            List<String> remainingMobOrder
    ) {
        List<SourceSpawnQueue.DeliveryEntry> deliveryEntries =
                new ArrayList<>(
                        remainingMobOrder.size()
                );

        for (String mobId
                : remainingMobOrder) {

            deliveryEntries.add(
                    new SourceSpawnQueue.DeliveryEntry(
                            mobId,
                            resolveLegacyRepresentedThreat(
                                    mobId
                            )
                    )
            );
        }

        return List.copyOf(
                deliveryEntries
        );
    }

    private static int resolveLegacyRepresentedThreat(
            String mobId
    ) {
        IncursionMobDefinition mobDefinition =
                IncursionMobCatalogue.getById(
                        mobId
                );

        if (mobDefinition == null) {
            throw new IllegalArgumentException(
                    "Cannot migrate legacy source-spawn queue mob entry '"
                            + mobId
                            + "' because no current incursion mob definition "
                            + "exists for that ID."
            );
        }

        return mobDefinition.getThreatCost();
    }
}