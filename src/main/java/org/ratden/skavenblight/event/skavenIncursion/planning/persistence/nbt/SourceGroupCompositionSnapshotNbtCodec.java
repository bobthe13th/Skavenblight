package org.ratden.skavenblight.event.skavenIncursion.planning.persistence.nbt;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.ratden.skavenblight.event.skavenIncursion.action.mob.MobModifier;
import org.ratden.skavenblight.event.skavenIncursion.action.mob.MobOrder;
import org.ratden.skavenblight.event.skavenIncursion.planning.budget.complexity.ComplexityOptionCategory;
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
 * structural IDs and optional Pack ownership.
 *
 * Schema-version handling belongs to the eventual top-level incursion codec.
 * This class reads and writes one exact composition schema.
 */
public final class SourceGroupCompositionSnapshotNbtCodec {

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
                                )
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
            CompoundTag tag
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
                                )
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
            CompoundTag tag
    ) {
        return new SourceGroupCompositionSnapshot.MobEntrySnapshot(
                IncursionSnapshotNbtSupport.requireString(
                        tag,
                        MOB_ID
                ),
                IncursionSnapshotNbtSupport.requireInt(
                        tag,
                        COUNT
                ),
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