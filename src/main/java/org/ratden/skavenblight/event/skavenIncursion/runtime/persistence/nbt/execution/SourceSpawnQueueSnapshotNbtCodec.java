package org.ratden.skavenblight.event.skavenIncursion.runtime.persistence.nbt.execution;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import org.ratden.skavenblight.event.skavenIncursion.planning.persistence.nbt.IncursionSnapshotNbtSupport;
import org.ratden.skavenblight.event.skavenIncursion.runtime.source.SourceSpawnQueue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CompoundTag codec for SourceSpawnQueue.Snapshot.
 *
 * New snapshots preserve:
 *
 * - source-composition identity;
 * - the exact remaining individual mob-delivery sequence.
 *
 * The sequence has already been shuffled by fresh runtime. Restoration reads
 * it exactly and never performs another shuffle.
 *
 * The reader also accepts the older development format that stored:
 *
 * - remaining counts;
 * - a round-robin cursor;
 * - total remaining mob count.
 *
 * Old queue progress is converted into the exact sequence that the previous
 * round-robin implementation would have delivered from that point onward.
 *
 * The immutable SourceComposition remains authoritative for validating that
 * every saved mob was genuinely purchased.
 */
public final class SourceSpawnQueueSnapshotNbtCodec {

    private static final String SOURCE_COMPOSITION_ID =
            "source_composition_id";

    private static final String REMAINING_MOB_ORDER =
            "remaining_mob_order";

    /*
     * Legacy development fields retained for read compatibility only.
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

        tag.putUUID(
                SOURCE_COMPOSITION_ID,
                snapshot.sourceCompositionId()
        );

        ListTag remainingMobOrderTag =
                new ListTag();

        for (String mobId
                : snapshot.remainingMobOrder()) {

            remainingMobOrderTag.add(
                    StringTag.valueOf(
                            mobId
                    )
            );
        }

        tag.put(
                REMAINING_MOB_ORDER,
                remainingMobOrderTag
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

        java.util.UUID sourceCompositionId =
                IncursionSnapshotNbtSupport.requireUuid(
                        tag,
                        SOURCE_COMPOSITION_ID
                );

        if (tag.contains(
                REMAINING_MOB_ORDER
        )) {
            return new SourceSpawnQueue.Snapshot(
                    sourceCompositionId,
                    readRemainingMobOrder(
                            tag
                    )
            );
        }

        return readLegacySnapshot(
                tag,
                sourceCompositionId
        );
    }

    private static List<String> readRemainingMobOrder(
            CompoundTag tag
    ) {
        ListTag remainingMobOrderTag =
                IncursionSnapshotNbtSupport.requireStringList(
                        tag,
                        REMAINING_MOB_ORDER
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
     * sequence its round-robin queue would have produced.
     */
    private static SourceSpawnQueue.Snapshot readLegacySnapshot(
            CompoundTag tag,
            java.util.UUID sourceCompositionId
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

            calculatedRemainingMobCount +=
                    remainingCount;
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
                reconstructedRemainingOrder
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
}