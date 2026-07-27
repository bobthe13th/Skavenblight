package org.ratden.skavenblight.event.skavenIncursion.runtime.persistence;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.ratden.skavenblight.event.skavenIncursion.planning.persistence.nbt.IncursionSnapshotNbtSupport;
import org.ratden.skavenblight.event.skavenIncursion.runtime.persistence.nbt.PersistentIncursionSnapshotNbtCodec;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Persistent owner of all admitted Skaven incursions in one ServerLevel.
 *
 * This data is stored through Minecraft's SavedData system and therefore
 * survives world saves, server shutdowns and integrated-server shutdowns.
 *
 * Each persistent record contains:
 *
 * - immutable planning state;
 * - selected Scenario and Stratagem identities;
 * - the original target snapshot;
 * - complete mutable Scenario runtime state;
 * - the persistence-layer lifecycle phase.
 *
 * Records are keyed by their canonical incursion UUID. The UUID is also
 * retained inside each PersistentIncursionSnapshot so loaded data can validate
 * its own identity rather than relying only on the surrounding map.
 *
 * This class stores immutable snapshots rather than live Scenario objects.
 * Reconstructing live Scenarios and reconciling them with the world belong to
 * the runtime restoration layer.
 */
public final class SkavenIncursionSavedData extends SavedData {

    public static final String DATA_NAME =
            "skavenblight_incursions";

    private static final int CURRENT_FORMAT_VERSION =
            1;

    private static final String FORMAT_VERSION =
            "format_version";

    private static final String INCURSIONS =
            "incursions";

    public static final SavedData.Factory<SkavenIncursionSavedData>
            FACTORY =
            new SavedData.Factory<>(
                    SkavenIncursionSavedData::new,
                    SkavenIncursionSavedData::load
            );

    /**
     * LinkedHashMap preserves admission order for deterministic saving,
     * inspection and future restoration.
     */
    private final Map<UUID, PersistentIncursionSnapshot>
            incursionsById;

    public SkavenIncursionSavedData() {
        this.incursionsById =
                new LinkedHashMap<>();
    }

    private SkavenIncursionSavedData(
            Map<UUID, PersistentIncursionSnapshot> loadedIncursions
    ) {
        if (loadedIncursions == null) {
            throw new IllegalArgumentException(
                    "Loaded persistent incursion map cannot be null."
            );
        }

        this.incursionsById =
                new LinkedHashMap<>(
                        loadedIncursions
                );

        validateStoredRecords();
    }

    /**
     * Returns the SavedData belonging to the supplied ServerLevel.
     *
     * Incursions are currently dimension-scoped: an incursion planned and
     * executed in one ServerLevel is persisted in that level's data storage.
     */
    public static SkavenIncursionSavedData get(
            ServerLevel level
    ) {
        if (level == null) {
            throw new IllegalArgumentException(
                    "Server level cannot be null."
            );
        }

        return level.getDataStorage()
                .computeIfAbsent(
                        FACTORY,
                        DATA_NAME
                );
    }

    /**
     * Loads the complete collection of persistent incursion records.
     *
     * Unsupported container versions and malformed records are rejected
     * explicitly rather than guessed or partially repaired.
     */
    public static SkavenIncursionSavedData load(
            CompoundTag tag,
            HolderLookup.Provider registries
    ) {
        if (tag == null) {
            throw new IllegalArgumentException(
                    "Skaven incursion SavedData NBT cannot be null."
            );
        }

        int formatVersion =
                IncursionSnapshotNbtSupport.requireInt(
                        tag,
                        FORMAT_VERSION
                );

        return switch (formatVersion) {
            case 1 -> loadVersionOne(
                    tag
            );

            default -> throw new IllegalArgumentException(
                    "Unsupported Skaven incursion SavedData format version "
                            + formatVersion
                            + ". Current supported version is "
                            + CURRENT_FORMAT_VERSION
                            + "."
            );
        };
    }

    @Override
    public CompoundTag save(
            CompoundTag tag,
            HolderLookup.Provider registries
    ) {
        if (tag == null) {
            throw new IllegalArgumentException(
                    "Skaven incursion SavedData destination tag cannot be "
                            + "null."
            );
        }

        validateStoredRecords();

        tag.putInt(
                FORMAT_VERSION,
                CURRENT_FORMAT_VERSION
        );

        ListTag incursionTags =
                new ListTag();

        for (PersistentIncursionSnapshot snapshot
                : incursionsById.values()) {

            incursionTags.add(
                    PersistentIncursionSnapshotNbtCodec.write(
                            snapshot
                    )
            );
        }

        tag.put(
                INCURSIONS,
                incursionTags
        );

        return tag;
    }

    /**
     * Adds a newly admitted incursion.
     *
     * Duplicate admission is rejected. Runtime updates to an existing
     * incursion must use replaceSnapshot(...) so accidental overwrites are not
     * silently accepted.
     */
    public void addSnapshot(
            PersistentIncursionSnapshot snapshot
    ) {
        requireSnapshot(
                snapshot
        );

        UUID incursionId =
                snapshot.incursionId();

        if (incursionsById.containsKey(
                incursionId
        )) {
            throw new IllegalStateException(
                    "Persistent incursion "
                            + incursionId
                            + " is already stored."
            );
        }

        incursionsById.put(
                incursionId,
                snapshot
        );

        setDirty();
    }

    /**
     * Replaces the stored snapshot for an already admitted incursion.
     *
     * This is the normal operation after a live Scenario ticks or changes
     * persistence phase.
     */
    public void replaceSnapshot(
            PersistentIncursionSnapshot snapshot
    ) {
        requireSnapshot(
                snapshot
        );

        UUID incursionId =
                snapshot.incursionId();

        PersistentIncursionSnapshot previousSnapshot =
                incursionsById.get(
                        incursionId
                );

        if (previousSnapshot == null) {
            throw new IllegalStateException(
                    "Cannot replace persistent incursion "
                            + incursionId
                            + " because it is not stored."
            );
        }

        if (previousSnapshot.equals(
                snapshot
        )) {
            return;
        }

        incursionsById.put(
                incursionId,
                snapshot
        );

        setDirty();
    }

    /**
     * Removes a persistent incursion after cleanup has completed.
     *
     * @return the removed snapshot, or null when no record used that ID
     */
    public PersistentIncursionSnapshot removeSnapshot(
            UUID incursionId
    ) {
        requireIncursionId(
                incursionId
        );

        PersistentIncursionSnapshot removedSnapshot =
                incursionsById.remove(
                        incursionId
                );

        if (removedSnapshot != null) {
            setDirty();
        }

        return removedSnapshot;
    }

    public PersistentIncursionSnapshot getSnapshot(
            UUID incursionId
    ) {
        requireIncursionId(
                incursionId
        );

        return incursionsById.get(
                incursionId
        );
    }

    public boolean containsIncursion(
            UUID incursionId
    ) {
        requireIncursionId(
                incursionId
        );

        return incursionsById.containsKey(
                incursionId
        );
    }

    /**
     * Returns all stored snapshots in deterministic admission order.
     */
    public Collection<PersistentIncursionSnapshot> getSnapshots() {
        return List.copyOf(
                incursionsById.values()
        );
    }

    /**
     * Returns all stored incursion IDs in deterministic admission order.
     */
    public Set<UUID> getIncursionIds() {
        return Set.copyOf(
                incursionsById.keySet()
        );
    }

    public int getStoredIncursionCount() {
        return incursionsById.size();
    }

    public boolean hasStoredIncursions() {
        return !incursionsById.isEmpty();
    }

    /**
     * Removes every persistent incursion record.
     *
     * This should primarily be used by explicit debug or administrative
     * cleanup rather than normal Scenario completion.
     *
     * @return number of removed records
     */
    public int clearSnapshots() {
        int removedCount =
                incursionsById.size();

        if (removedCount == 0) {
            return 0;
        }

        incursionsById.clear();

        setDirty();

        return removedCount;
    }

    public static int getCurrentFormatVersion() {
        return CURRENT_FORMAT_VERSION;
    }

    private static SkavenIncursionSavedData loadVersionOne(
            CompoundTag tag
    ) {
        ListTag incursionTags =
                IncursionSnapshotNbtSupport.requireCompoundList(
                        tag,
                        INCURSIONS
                );

        Map<UUID, PersistentIncursionSnapshot>
                loadedIncursions =
                new LinkedHashMap<>();

        for (int recordIndex = 0;
             recordIndex < incursionTags.size();
             recordIndex++) {

            PersistentIncursionSnapshot snapshot;

            try {
                snapshot =
                        PersistentIncursionSnapshotNbtCodec.read(
                                incursionTags.getCompound(
                                        recordIndex
                                )
                        );
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                        "Failed to read persistent incursion record at "
                                + "position "
                                + recordIndex
                                + ".",
                        exception
                );
            }

            UUID incursionId =
                    snapshot.incursionId();

            PersistentIncursionSnapshot duplicateSnapshot =
                    loadedIncursions.putIfAbsent(
                            incursionId,
                            snapshot
                    );

            if (duplicateSnapshot != null) {
                throw new IllegalArgumentException(
                        "Skaven incursion SavedData contains duplicate "
                                + "incursion ID "
                                + incursionId
                                + "."
                );
            }
        }

        return new SkavenIncursionSavedData(
                loadedIncursions
        );
    }

    private void validateStoredRecords() {
        for (Map.Entry<UUID, PersistentIncursionSnapshot> entry
                : incursionsById.entrySet()) {

            UUID mapIncursionId =
                    entry.getKey();

            PersistentIncursionSnapshot snapshot =
                    entry.getValue();

            if (mapIncursionId == null) {
                throw new IllegalStateException(
                        "Persistent incursion map contains a null ID."
                );
            }

            if (snapshot == null) {
                throw new IllegalStateException(
                        "Persistent incursion map contains a null snapshot for "
                                + "ID "
                                + mapIncursionId
                                + "."
                );
            }

            if (!mapIncursionId.equals(
                    snapshot.incursionId()
            )) {
                throw new IllegalStateException(
                        "Persistent incursion map key "
                                + mapIncursionId
                                + " does not match stored snapshot ID "
                                + snapshot.incursionId()
                                + "."
                );
            }
        }
    }

    private static void requireSnapshot(
            PersistentIncursionSnapshot snapshot
    ) {
        if (snapshot == null) {
            throw new IllegalArgumentException(
                    "Persistent incursion snapshot cannot be null."
            );
        }
    }

    private static void requireIncursionId(
            UUID incursionId
    ) {
        if (incursionId == null) {
            throw new IllegalArgumentException(
                    "Incursion ID cannot be null."
            );
        }
    }
}