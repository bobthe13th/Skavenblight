package org.ratden.skavenblight.event.skavenIncursion.planning.persistence.nbt;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.ratden.skavenblight.event.skavenIncursion.action.mob.MobModifier;
import org.ratden.skavenblight.event.skavenIncursion.action.mob.MobOrder;
import org.ratden.skavenblight.event.skavenIncursion.planning.budget.complexity.ComplexityOptionCategory;
import org.ratden.skavenblight.event.skavenIncursion.planning.composition.IncursionMobCatalogue;
import org.ratden.skavenblight.event.skavenIncursion.planning.composition.IncursionMobDefinition;
import org.ratden.skavenblight.event.skavenIncursion.planning.composition.SourceGroupComposition;
import org.ratden.skavenblight.event.skavenIncursion.planning.persistence.SourceGroupCompositionSnapshot;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceRole;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceSize;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceType;

import java.util.ArrayList;
import java.util.List;

/**
 * CompoundTag codec for SourceGroupCompositionSnapshot.
 *
 * This codec preserves the complete immutable composition branch, including
 * structural IDs, represented mob threat and optional Pack ownership.
 *
 * This nested branch has its own format version because represented threat was
 * added after persistent incursion records already existed.
 *
 * Unversioned composition data is treated as version 1. Version 1 did not
 * store represented threat per mob, so migration resolves the ordinary threat
 * value from the current incursion mob catalogue.
 *
 * Version 2 stores represented threat directly. Once read, the immutable
 * SourceGroupCompositionSnapshot validates that the migrated or stored mob
 * entries exactly account for each source composition's original threatSpent
 * value.
 */
public final class SourceGroupCompositionSnapshotNbtCodec {

    private static final int CURRENT_FORMAT_VERSION =
            2;

    private static final int LEGACY_UNVERSIONED_FORMAT_VERSION =
            1;

    private static final String FORMAT_VERSION =
            "format_version";

    private static final String SOURCE_GROUP_COMPOSITION_ID =
            "source_group_composition_id";

    private static final String MAXIMUM_LOAD =
            "maximum_load";

    private static final String SOURCE_COMPOSITIONS =
            "source_compositions";

    private static final String PACK_ASSIGNMENT =
            "pack_assignment";

    private static final String SOURCE_COMPOSITION_ID =
            "source_composition_id";

    private static final String REQUIRED_SOURCE_TYPE =
            "required_source_type";

    private static final String REQUIRED_SOURCE_SIZE =
            "required_source_size";

    private static final String SOURCE_ROLE =
            "source_role";

    private static final String THREAT_SPENT =
            "threat_spent";

    private static final String SOURCE_GROUP_LOAD_COST =
            "source_group_load_cost";

    private static final String MOB_ENTRIES =
            "mob_entries";

    private static final String MODIFIERS =
            "modifiers";

    private static final String ORDERS =
            "orders";

    private static final String ATTACHED_MOB_ASSIGNMENTS =
            "attached_mob_assignments";

    private static final String MOB_ID =
            "mob_id";

    private static final String COUNT =
            "count";

    private static final String REPRESENTED_THREAT_PER_MOB =
            "represented_threat_per_mob";

    private static final String CAPACITY_COST_PER_MOB =
            "capacity_cost_per_mob";

    private static final String MINIMUM_SOURCE_SIZE =
            "minimum_source_size";

    private static final String ATTACHED_MOB_ASSIGNMENT_ID =
            "attached_mob_assignment_id";

    private static final String COMPLEXITY_OPTION =
            "complexity_option";

    private static final String SPAWN_PRIORITY =
            "spawn_priority";

    private static final String COMPLEXITY_OPTION_ID =
            "id";

    private static final String COMPLEXITY_COST =
            "complexity_cost";

    private static final String COMPLEXITY_CATEGORY =
            "category";

    private static final String PACK_ID =
            "pack_id";

    private SourceGroupCompositionSnapshotNbtCodec() {
    }

    /**
     * Writes one complete immutable source-group composition snapshot.
     */
    public static CompoundTag write(
            SourceGroupCompositionSnapshot snapshot
    ) {
        if (snapshot == null) {
            throw new IllegalArgumentException(
                    "Source-group composition snapshot cannot be null."
            );
        }

        CompoundTag tag =
                new CompoundTag();

        tag.putInt(
                FORMAT_VERSION,
                CURRENT_FORMAT_VERSION
        );

        tag.putUUID(
                SOURCE_GROUP_COMPOSITION_ID,
                snapshot.sourceGroupCompositionId()
        );

        tag.putInt(
                MAXIMUM_LOAD,
                snapshot.maximumLoad()
        );

        ListTag sourceCompositionTags =
                new ListTag();

        for (SourceGroupCompositionSnapshot
                .SourceCompositionSnapshot
                sourceCompositionSnapshot
                : snapshot.sourceCompositionSnapshots()) {

            sourceCompositionTags.add(
                    writeSourceComposition(
                            sourceCompositionSnapshot
                    )
            );
        }

        tag.put(
                SOURCE_COMPOSITIONS,
                sourceCompositionTags
        );

        if (snapshot.packAssignmentSnapshot() != null) {
            tag.put(
                    PACK_ASSIGNMENT,
                    writePackAssignment(
                            snapshot.packAssignmentSnapshot()
                    )
            );
        }

        return tag;
    }

    /**
     * Reads and validates one complete immutable source-group composition
     * snapshot.
     */
    public static SourceGroupCompositionSnapshot read(
            CompoundTag tag
    ) {
        if (tag == null) {
            throw new IllegalArgumentException(
                    "Source-group composition NBT cannot be null."
            );
        }

        int formatVersion =
                readFormatVersion(
                        tag
                );

        ListTag sourceCompositionTags =
                IncursionSnapshotNbtSupport.requireCompoundList(
                        tag,
                        SOURCE_COMPOSITIONS
                );

        List<SourceGroupCompositionSnapshot
                .SourceCompositionSnapshot>
                sourceCompositionSnapshots =
                new ArrayList<>();

        for (int index = 0;
             index < sourceCompositionTags.size();
             index++) {

            try {
                sourceCompositionSnapshots.add(
                        readSourceComposition(
                                sourceCompositionTags.getCompound(
                                        index
                                ),
                                formatVersion
                        )
                );
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                        "Failed to read source composition at index "
                                + index
                                + ".",
                        exception
                );
            }
        }

        CompoundTag packAssignmentTag =
                IncursionSnapshotNbtSupport.readOptionalCompound(
                        tag,
                        PACK_ASSIGNMENT
                );

        SourceGroupCompositionSnapshot.PackAssignmentSnapshot
                packAssignmentSnapshot =
                packAssignmentTag == null
                        ? null
                        : readPackAssignment(
                        packAssignmentTag
                );

        return new SourceGroupCompositionSnapshot(
                IncursionSnapshotNbtSupport.requireUuid(
                        tag,
                        SOURCE_GROUP_COMPOSITION_ID
                ),
                IncursionSnapshotNbtSupport.requireInt(
                        tag,
                        MAXIMUM_LOAD
                ),
                sourceCompositionSnapshots,
                packAssignmentSnapshot
        );
    }

    private static CompoundTag writeSourceComposition(
            SourceGroupCompositionSnapshot
                    .SourceCompositionSnapshot snapshot
    ) {
        CompoundTag tag =
                new CompoundTag();

        tag.putUUID(
                SOURCE_COMPOSITION_ID,
                snapshot.sourceCompositionId()
        );

        tag.putString(
                REQUIRED_SOURCE_TYPE,
                snapshot.requiredSourceType().name()
        );

        tag.putString(
                REQUIRED_SOURCE_SIZE,
                snapshot.requiredSourceSize().name()
        );

        tag.putString(
                SOURCE_ROLE,
                snapshot.sourceRole().name()
        );

        tag.putInt(
                THREAT_SPENT,
                snapshot.threatSpent()
        );

        tag.putInt(
                SOURCE_GROUP_LOAD_COST,
                snapshot.sourceGroupLoadCost()
        );

        ListTag mobEntryTags =
                new ListTag();

        for (SourceGroupCompositionSnapshot.MobEntrySnapshot
                mobEntrySnapshot
                : snapshot.mobEntrySnapshots()) {

            mobEntryTags.add(
                    writeMobEntry(
                            mobEntrySnapshot
                    )
            );
        }

        tag.put(
                MOB_ENTRIES,
                mobEntryTags
        );

        IncursionSnapshotNbtSupport.putEnumList(
                tag,
                MODIFIERS,
                snapshot.modifiers()
        );

        IncursionSnapshotNbtSupport.putEnumList(
                tag,
                ORDERS,
                snapshot.orders()
        );

        ListTag attachedAssignmentTags =
                new ListTag();

        for (SourceGroupCompositionSnapshot
                .AttachedMobAssignmentSnapshot
                attachedAssignmentSnapshot
                : snapshot.attachedMobAssignmentSnapshots()) {

            attachedAssignmentTags.add(
                    writeAttachedMobAssignment(
                            attachedAssignmentSnapshot
                    )
            );
        }

        tag.put(
                ATTACHED_MOB_ASSIGNMENTS,
                attachedAssignmentTags
        );

        return tag;
    }

    private static SourceGroupCompositionSnapshot
            .SourceCompositionSnapshot
    readSourceComposition(
            CompoundTag tag,
            int formatVersion
    ) {
        ListTag mobEntryTags =
                IncursionSnapshotNbtSupport.requireCompoundList(
                        tag,
                        MOB_ENTRIES
                );

        List<SourceGroupCompositionSnapshot.MobEntrySnapshot>
                mobEntrySnapshots =
                new ArrayList<>();

        for (int index = 0;
             index < mobEntryTags.size();
             index++) {

            try {
                mobEntrySnapshots.add(
                        readMobEntry(
                                mobEntryTags.getCompound(
                                        index
                                ),
                                formatVersion
                        )
                );
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                        "Failed to read mob entry at index "
                                + index
                                + ".",
                        exception
                );
            }
        }

        ListTag attachedAssignmentTags =
                IncursionSnapshotNbtSupport.requireCompoundList(
                        tag,
                        ATTACHED_MOB_ASSIGNMENTS
                );

        List<SourceGroupCompositionSnapshot
                .AttachedMobAssignmentSnapshot>
                attachedAssignmentSnapshots =
                new ArrayList<>();

        for (int index = 0;
             index < attachedAssignmentTags.size();
             index++) {

            try {
                attachedAssignmentSnapshots.add(
                        readAttachedMobAssignment(
                                attachedAssignmentTags.getCompound(
                                        index
                                )
                        )
                );
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                        "Failed to read attached-mob assignment at index "
                                + index
                                + ".",
                        exception
                );
            }
        }

        return new SourceGroupCompositionSnapshot
                .SourceCompositionSnapshot(
                IncursionSnapshotNbtSupport.requireUuid(
                        tag,
                        SOURCE_COMPOSITION_ID
                ),
                IncursionSnapshotNbtSupport.requireEnum(
                        tag,
                        REQUIRED_SOURCE_TYPE,
                        SourceType.class
                ),
                IncursionSnapshotNbtSupport.requireEnum(
                        tag,
                        REQUIRED_SOURCE_SIZE,
                        SourceSize.class
                ),
                IncursionSnapshotNbtSupport.requireEnum(
                        tag,
                        SOURCE_ROLE,
                        SourceRole.class
                ),
                IncursionSnapshotNbtSupport.requireInt(
                        tag,
                        THREAT_SPENT
                ),
                IncursionSnapshotNbtSupport.requireInt(
                        tag,
                        SOURCE_GROUP_LOAD_COST
                ),
                mobEntrySnapshots,
                IncursionSnapshotNbtSupport.readEnumList(
                        tag,
                        MODIFIERS,
                        MobModifier.class
                ),
                IncursionSnapshotNbtSupport.readEnumList(
                        tag,
                        ORDERS,
                        MobOrder.class
                ),
                attachedAssignmentSnapshots
        );
    }

    private static CompoundTag writeMobEntry(
            SourceGroupCompositionSnapshot.MobEntrySnapshot snapshot
    ) {
        CompoundTag tag =
                new CompoundTag();

        tag.putString(
                MOB_ID,
                snapshot.mobId()
        );

        tag.putInt(
                COUNT,
                snapshot.count()
        );

        tag.putInt(
                REPRESENTED_THREAT_PER_MOB,
                snapshot.representedThreatPerMob()
        );

        tag.putInt(
                CAPACITY_COST_PER_MOB,
                snapshot.capacityCostPerMob()
        );

        tag.putString(
                MINIMUM_SOURCE_SIZE,
                snapshot.minimumSourceSize().name()
        );

        return tag;
    }

    private static SourceGroupCompositionSnapshot.MobEntrySnapshot
    readMobEntry(
            CompoundTag tag,
            int formatVersion
    ) {
        String mobId =
                IncursionSnapshotNbtSupport.requireString(
                        tag,
                        MOB_ID
                );

        int representedThreatPerMob =
                switch (formatVersion) {
                    case LEGACY_UNVERSIONED_FORMAT_VERSION ->
                            resolveLegacyRepresentedThreat(
                                    mobId
                            );

                    case CURRENT_FORMAT_VERSION ->
                            IncursionSnapshotNbtSupport.requireInt(
                                    tag,
                                    REPRESENTED_THREAT_PER_MOB
                            );

                    default -> throw unsupportedFormatVersion(
                            formatVersion
                    );
                };

        return new SourceGroupCompositionSnapshot.MobEntrySnapshot(
                mobId,
                IncursionSnapshotNbtSupport.requireInt(
                        tag,
                        COUNT
                ),
                representedThreatPerMob,
                IncursionSnapshotNbtSupport.requireInt(
                        tag,
                        CAPACITY_COST_PER_MOB
                ),
                IncursionSnapshotNbtSupport.requireEnum(
                        tag,
                        MINIMUM_SOURCE_SIZE,
                        SourceSize.class
                )
        );
    }

    /**
     * Reads this nested composition branch's format version.
     *
     * Existing records written before nested versioning was introduced do not
     * contain FORMAT_VERSION and are treated as version 1.
     */
    private static int readFormatVersion(
            CompoundTag tag
    ) {
        if (!tag.contains(
                FORMAT_VERSION
        )) {
            return LEGACY_UNVERSIONED_FORMAT_VERSION;
        }

        if (!tag.contains(
                FORMAT_VERSION,
                Tag.TAG_INT
        )) {
            throw new IllegalArgumentException(
                    "Source-group composition NBT field '"
                            + FORMAT_VERSION
                            + "' is not a valid integer."
            );
        }

        int formatVersion =
                tag.getInt(
                        FORMAT_VERSION
                );

        if (formatVersion < LEGACY_UNVERSIONED_FORMAT_VERSION
                || formatVersion > CURRENT_FORMAT_VERSION) {

            throw unsupportedFormatVersion(
                    formatVersion
            );
        }

        return formatVersion;
    }

    /**
     * Migrates one legacy mob entry that predates persisted represented
     * threat.
     *
     * The old format retained the source composition's total threatSpent but
     * not its per-mob distribution. The current catalogue is therefore the
     * only recoverable source for the ordinary per-mob value. Snapshot
     * validation subsequently rejects the legacy record if those reconstructed
     * values no longer equal its persisted total.
     */
    private static int resolveLegacyRepresentedThreat(
            String mobId
    ) {
        IncursionMobDefinition mobDefinition =
                IncursionMobCatalogue.getById(
                        mobId
                );

        if (mobDefinition == null) {
            throw new IllegalArgumentException(
                    "Cannot migrate legacy mob entry '"
                            + mobId
                            + "' because no current incursion mob definition "
                            + "exists for that ID."
            );
        }

        return mobDefinition.getThreatCost();
    }

    private static IllegalArgumentException unsupportedFormatVersion(
            int formatVersion
    ) {
        return new IllegalArgumentException(
                "Unsupported source-group composition format version "
                        + formatVersion
                        + ". Current supported version is "
                        + CURRENT_FORMAT_VERSION
                        + "."
        );
    }

    private static CompoundTag writeAttachedMobAssignment(
            SourceGroupCompositionSnapshot
                    .AttachedMobAssignmentSnapshot snapshot
    ) {
        CompoundTag tag =
                new CompoundTag();

        tag.putUUID(
                ATTACHED_MOB_ASSIGNMENT_ID,
                snapshot.attachedMobAssignmentId()
        );

        tag.putString(
                MOB_ID,
                snapshot.mobId()
        );

        tag.put(
                COMPLEXITY_OPTION,
                writeComplexityOption(
                        snapshot.complexityOptionSnapshot()
                )
        );

        tag.putString(
                SPAWN_PRIORITY,
                snapshot.spawnPriority().name()
        );

        return tag;
    }

    private static SourceGroupCompositionSnapshot
            .AttachedMobAssignmentSnapshot
    readAttachedMobAssignment(
            CompoundTag tag
    ) {
        return new SourceGroupCompositionSnapshot
                .AttachedMobAssignmentSnapshot(
                IncursionSnapshotNbtSupport.requireUuid(
                        tag,
                        ATTACHED_MOB_ASSIGNMENT_ID
                ),
                IncursionSnapshotNbtSupport.requireString(
                        tag,
                        MOB_ID
                ),
                readComplexityOption(
                        IncursionSnapshotNbtSupport.requireCompound(
                                tag,
                                COMPLEXITY_OPTION
                        )
                ),
                IncursionSnapshotNbtSupport.requireEnum(
                        tag,
                        SPAWN_PRIORITY,
                        SourceGroupComposition
                                .AttachedMobSpawnPriority.class
                )
        );
    }

    private static CompoundTag writeComplexityOption(
            SourceGroupCompositionSnapshot
                    .ComplexityOptionSnapshot snapshot
    ) {
        CompoundTag tag =
                new CompoundTag();

        tag.putString(
                COMPLEXITY_OPTION_ID,
                snapshot.id()
        );

        tag.putInt(
                COMPLEXITY_COST,
                snapshot.complexityCost()
        );

        tag.putString(
                COMPLEXITY_CATEGORY,
                snapshot.category().name()
        );

        return tag;
    }

    private static SourceGroupCompositionSnapshot
            .ComplexityOptionSnapshot
    readComplexityOption(
            CompoundTag tag
    ) {
        return new SourceGroupCompositionSnapshot
                .ComplexityOptionSnapshot(
                IncursionSnapshotNbtSupport.requireString(
                        tag,
                        COMPLEXITY_OPTION_ID
                ),
                IncursionSnapshotNbtSupport.requireInt(
                        tag,
                        COMPLEXITY_COST
                ),
                IncursionSnapshotNbtSupport.requireEnum(
                        tag,
                        COMPLEXITY_CATEGORY,
                        ComplexityOptionCategory.class
                )
        );
    }

    private static CompoundTag writePackAssignment(
            SourceGroupCompositionSnapshot
                    .PackAssignmentSnapshot snapshot
    ) {
        CompoundTag tag =
                new CompoundTag();

        tag.putUUID(
                PACK_ID,
                snapshot.packId()
        );

        tag.putUUID(
                SOURCE_COMPOSITION_ID,
                snapshot.sourceCompositionId()
        );

        tag.putUUID(
                ATTACHED_MOB_ASSIGNMENT_ID,
                snapshot.attachedMobAssignmentId()
        );

        return tag;
    }

    private static SourceGroupCompositionSnapshot
            .PackAssignmentSnapshot
    readPackAssignment(
            CompoundTag tag
    ) {
        return new SourceGroupCompositionSnapshot
                .PackAssignmentSnapshot(
                IncursionSnapshotNbtSupport.requireUuid(
                        tag,
                        PACK_ID
                ),
                IncursionSnapshotNbtSupport.requireUuid(
                        tag,
                        SOURCE_COMPOSITION_ID
                ),
                IncursionSnapshotNbtSupport.requireUuid(
                        tag,
                        ATTACHED_MOB_ASSIGNMENT_ID
                )
        );
    }
}