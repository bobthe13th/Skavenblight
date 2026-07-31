package org.ratden.skavenblight.event.skavenIncursion.planning.persistence.nbt;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.ratden.skavenblight.event.skavenIncursion.planning.front.FrontPlacementPattern;
import org.ratden.skavenblight.event.skavenIncursion.planning.persistence.FrontPlanSnapshot;
import org.ratden.skavenblight.event.skavenIncursion.planning.persistence.SourceGroupCompositionSnapshot;
import org.ratden.skavenblight.event.skavenIncursion.planning.persistence.SourceGroupPlacementSnapshot;

import java.util.ArrayList;
import java.util.List;

/**
 * CompoundTag codec for FrontPlanSnapshot.
 *
 * This codec joins the two immutable branches belonging to one front:
 *
 * - ordered waves and their source-group compositions;
 * - persistent physical source groups and source placements.
 *
 * It preserves:
 *
 * - front identity and canonical front index;
 * - front anchor position;
 * - tactical placement pattern;
 * - threat and complexity shares;
 * - dominant-front status;
 * - ordered wave plans;
 * - wave budgets and shares;
 * - complete source-group composition snapshots;
 * - complete physical source-group placement snapshots.
 *
 * Schema-version handling belongs to the top-level
 * IncursionPlanSnapshotNbtCodec. This class reads and writes one exact front
 * schema.
 */
public final class FrontPlanSnapshotNbtCodec {

    private static final String FRONT_ID =
            "front_id";

    private static final String FRONT_INDEX =
            "front_index";

    private static final String ANCHOR_POS =
            "anchor_pos";

    private static final String PLACEMENT_PATTERN =
            "placement_pattern";

    private static final String THREAT_SHARE =
            "threat_share";

    private static final String COMPLEXITY_SHARE =
            "complexity_share";

    private static final String DOMINANT =
            "dominant";

    private static final String WAVE_PLANS =
            "wave_plans";

    private static final String SOURCE_GROUP_PLACEMENTS =
            "source_group_placements";

    private static final String WAVE_INDEX =
            "wave_index";

    private static final String THREAT_BUDGET =
            "threat_budget";

    private static final String COMPLEXITY_BUDGET =
            "complexity_budget";

    private static final String SOURCE_GROUP_COMPOSITIONS =
            "source_group_compositions";

    private static final String X =
            "x";

    private static final String Y =
            "y";

    private static final String Z =
            "z";

    private FrontPlanSnapshotNbtCodec() {
    }

    /**
     * Writes one complete immutable front snapshot.
     */
    public static CompoundTag write(
            FrontPlanSnapshot snapshot
    ) {
        if (snapshot == null) {
            throw new IllegalArgumentException(
                    "Front-plan snapshot cannot be null."
            );
        }

        CompoundTag tag =
                new CompoundTag();

        tag.putUUID(
                FRONT_ID,
                snapshot.frontId()
        );

        tag.putInt(
                FRONT_INDEX,
                snapshot.frontIndex()
        );

        tag.put(
                ANCHOR_POS,
                writeBlockPos(
                        snapshot.anchorPos()
                )
        );

        tag.putString(
                PLACEMENT_PATTERN,
                snapshot.placementPattern().name()
        );

        tag.putDouble(
                THREAT_SHARE,
                snapshot.threatShare()
        );

        tag.putDouble(
                COMPLEXITY_SHARE,
                snapshot.complexityShare()
        );

        tag.putBoolean(
                DOMINANT,
                snapshot.dominant()
        );

        ListTag wavePlanTags =
                new ListTag();

        for (FrontPlanSnapshot.WavePlanSnapshot wavePlanSnapshot
                : snapshot.wavePlanSnapshots()) {

            wavePlanTags.add(
                    writeWavePlan(
                            wavePlanSnapshot
                    )
            );
        }

        tag.put(
                WAVE_PLANS,
                wavePlanTags
        );

        ListTag sourceGroupPlacementTags =
                new ListTag();

        for (SourceGroupPlacementSnapshot
                sourceGroupPlacementSnapshot
                : snapshot.sourceGroupPlacementSnapshots()) {

            sourceGroupPlacementTags.add(
                    SourceGroupPlacementSnapshotNbtCodec.write(
                            sourceGroupPlacementSnapshot
                    )
            );
        }

        tag.put(
                SOURCE_GROUP_PLACEMENTS,
                sourceGroupPlacementTags
        );

        return tag;
    }

    /**
     * Reads and validates one complete immutable front snapshot.
     */
    public static FrontPlanSnapshot read(
            CompoundTag tag
    ) {
        if (tag == null) {
            throw new IllegalArgumentException(
                    "Front-plan NBT cannot be null."
            );
        }

        List<FrontPlanSnapshot.WavePlanSnapshot>
                wavePlanSnapshots =
                readWavePlans(
                        tag
                );

        List<SourceGroupPlacementSnapshot>
                sourceGroupPlacementSnapshots =
                readSourceGroupPlacements(
                        tag
                );

        return new FrontPlanSnapshot(
                IncursionSnapshotNbtSupport.requireUuid(
                        tag,
                        FRONT_ID
                ),
                IncursionSnapshotNbtSupport.requireInt(
                        tag,
                        FRONT_INDEX
                ),
                readBlockPos(
                        IncursionSnapshotNbtSupport.requireCompound(
                                tag,
                                ANCHOR_POS
                        )
                ),
                IncursionSnapshotNbtSupport.requireEnum(
                        tag,
                        PLACEMENT_PATTERN,
                        FrontPlacementPattern.class
                ),
                IncursionSnapshotNbtSupport.requireDouble(
                        tag,
                        THREAT_SHARE
                ),
                IncursionSnapshotNbtSupport.requireDouble(
                        tag,
                        COMPLEXITY_SHARE
                ),
                IncursionSnapshotNbtSupport.requireBoolean(
                        tag,
                        DOMINANT
                ),
                wavePlanSnapshots,
                sourceGroupPlacementSnapshots
        );
    }

    private static List<FrontPlanSnapshot.WavePlanSnapshot>
    readWavePlans(
            CompoundTag tag
    ) {
        ListTag wavePlanTags =
                IncursionSnapshotNbtSupport.requireCompoundList(
                        tag,
                        WAVE_PLANS
                );

        List<FrontPlanSnapshot.WavePlanSnapshot>
                wavePlanSnapshots =
                new ArrayList<>();

        for (int index = 0;
             index < wavePlanTags.size();
             index++) {

            try {
                wavePlanSnapshots.add(
                        readWavePlan(
                                wavePlanTags.getCompound(
                                        index
                                )
                        )
                );
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                        "Failed to read wave plan at index "
                                + index
                                + ".",
                        exception
                );
            }
        }

        return List.copyOf(
                wavePlanSnapshots
        );
    }

    private static List<SourceGroupPlacementSnapshot>
    readSourceGroupPlacements(
            CompoundTag tag
    ) {
        ListTag sourceGroupPlacementTags =
                IncursionSnapshotNbtSupport.requireCompoundList(
                        tag,
                        SOURCE_GROUP_PLACEMENTS
                );

        List<SourceGroupPlacementSnapshot>
                sourceGroupPlacementSnapshots =
                new ArrayList<>();

        for (int index = 0;
             index < sourceGroupPlacementTags.size();
             index++) {

            try {
                sourceGroupPlacementSnapshots.add(
                        SourceGroupPlacementSnapshotNbtCodec.read(
                                sourceGroupPlacementTags.getCompound(
                                        index
                                )
                        )
                );
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                        "Failed to read physical source-group placement at "
                                + "index "
                                + index
                                + ".",
                        exception
                );
            }
        }

        return List.copyOf(
                sourceGroupPlacementSnapshots
        );
    }

    private static CompoundTag writeWavePlan(
            FrontPlanSnapshot.WavePlanSnapshot snapshot
    ) {
        if (snapshot == null) {
            throw new IllegalArgumentException(
                    "Wave-plan snapshot cannot be null."
            );
        }

        CompoundTag tag =
                new CompoundTag();

        tag.putInt(
                WAVE_INDEX,
                snapshot.waveIndex()
        );

        tag.putDouble(
                THREAT_SHARE,
                snapshot.threatShare()
        );

        tag.putDouble(
                COMPLEXITY_SHARE,
                snapshot.complexityShare()
        );

        tag.putInt(
                THREAT_BUDGET,
                snapshot.threatBudget()
        );

        tag.putInt(
                COMPLEXITY_BUDGET,
                snapshot.complexityBudget()
        );

        ListTag sourceGroupCompositionTags =
                new ListTag();

        for (SourceGroupCompositionSnapshot
                sourceGroupCompositionSnapshot
                : snapshot.sourceGroupCompositionSnapshots()) {

            sourceGroupCompositionTags.add(
                    SourceGroupCompositionSnapshotNbtCodec.write(
                            sourceGroupCompositionSnapshot
                    )
            );
        }

        tag.put(
                SOURCE_GROUP_COMPOSITIONS,
                sourceGroupCompositionTags
        );

        return tag;
    }

    private static FrontPlanSnapshot.WavePlanSnapshot
    readWavePlan(
            CompoundTag tag
    ) {
        ListTag sourceGroupCompositionTags =
                IncursionSnapshotNbtSupport.requireCompoundList(
                        tag,
                        SOURCE_GROUP_COMPOSITIONS
                );

        List<SourceGroupCompositionSnapshot>
                sourceGroupCompositionSnapshots =
                new ArrayList<>();

        for (int index = 0;
             index < sourceGroupCompositionTags.size();
             index++) {

            try {
                sourceGroupCompositionSnapshots.add(
                        SourceGroupCompositionSnapshotNbtCodec.read(
                                sourceGroupCompositionTags.getCompound(
                                        index
                                )
                        )
                );
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                        "Failed to read source-group composition at index "
                                + index
                                + " in wave "
                                + readWaveIndexForError(
                                tag
                        )
                                + ".",
                        exception
                );
            }
        }

        return new FrontPlanSnapshot.WavePlanSnapshot(
                IncursionSnapshotNbtSupport.requireInt(
                        tag,
                        WAVE_INDEX
                ),
                IncursionSnapshotNbtSupport.requireDouble(
                        tag,
                        THREAT_SHARE
                ),
                IncursionSnapshotNbtSupport.requireDouble(
                        tag,
                        COMPLEXITY_SHARE
                ),
                IncursionSnapshotNbtSupport.requireInt(
                        tag,
                        THREAT_BUDGET
                ),
                IncursionSnapshotNbtSupport.requireInt(
                        tag,
                        COMPLEXITY_BUDGET
                ),
                sourceGroupCompositionSnapshots
        );
    }

    /**
     * Reads the wave index only for contextual error reporting.
     *
     * A malformed or missing index is reported as "unknown" here and then
     * rejected authoritatively when the complete WavePlanSnapshot is built.
     */
    private static String readWaveIndexForError(
            CompoundTag tag
    ) {
        try {
            return Integer.toString(
                    IncursionSnapshotNbtSupport.requireInt(
                            tag,
                            WAVE_INDEX
                    )
            );
        } catch (IllegalArgumentException exception) {
            return "unknown";
        }
    }

    private static CompoundTag writeBlockPos(
            BlockPos blockPos
    ) {
        if (blockPos == null) {
            throw new IllegalArgumentException(
                    "Block position cannot be null."
            );
        }

        CompoundTag tag =
                new CompoundTag();

        tag.putInt(
                X,
                blockPos.getX()
        );

        tag.putInt(
                Y,
                blockPos.getY()
        );

        tag.putInt(
                Z,
                blockPos.getZ()
        );

        return tag;
    }

    private static BlockPos readBlockPos(
            CompoundTag tag
    ) {
        if (tag == null) {
            throw new IllegalArgumentException(
                    "Block-position NBT cannot be null."
            );
        }

        return new BlockPos(
                IncursionSnapshotNbtSupport.requireInt(
                        tag,
                        X
                ),
                IncursionSnapshotNbtSupport.requireInt(
                        tag,
                        Y
                ),
                IncursionSnapshotNbtSupport.requireInt(
                        tag,
                        Z
                )
        );
    }
}