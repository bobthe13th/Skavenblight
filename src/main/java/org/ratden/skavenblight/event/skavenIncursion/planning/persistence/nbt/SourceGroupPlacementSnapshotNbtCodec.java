package org.ratden.skavenblight.event.skavenIncursion.planning.persistence.nbt;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.ratden.skavenblight.event.skavenIncursion.planning.persistence.SourceGroupPlacementSnapshot;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceRole;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceSize;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceType;

import java.util.ArrayList;
import java.util.List;

/**
 * CompoundTag codec for SourceGroupPlacementSnapshot.
 *
 * This codec preserves the complete immutable physical-placement branch:
 *
 * - physical source-group and front IDs;
 * - ordered source-group composition bindings;
 * - source-group load and spatial rules;
 * - final source-group envelope;
 * - every persistent physical source;
 * - ordered source-composition bindings;
 * - source type, size and role;
 * - reservation and preparation areas;
 * - foundation and clearance requirements;
 * - source-group load cost;
 * - facing;
 * - initial anchor position;
 * - final placed position.
 *
 * Schema-version handling belongs to the eventual top-level incursion codec.
 * This class reads and writes one exact placement schema.
 */
public final class SourceGroupPlacementSnapshotNbtCodec {

    private static final String SOURCE_GROUP_PLACEMENT_ID =
            "source_group_placement_id";

    private static final String FRONT_ID =
            "front_id";

    private static final String SOURCE_GROUP_COMPOSITION_IDS =
            "source_group_composition_ids";

    private static final String MAXIMUM_LOAD =
            "maximum_load";

    private static final String SPATIAL_RULES =
            "spatial_rules";

    private static final String ENVELOPE =
            "envelope";

    private static final String SOURCE_ROLE =
            "source_role";

    private static final String SOURCE_PLACEMENTS =
            "source_placements";

    private static final String SOURCE_PLACEMENT_ID =
            "source_placement_id";

    private static final String SOURCE_COMPOSITION_IDS =
            "source_composition_ids";

    private static final String SOURCE_TYPE =
            "source_type";

    private static final String SOURCE_SIZE =
            "source_size";

    private static final String PLACEMENT_PROFILE =
            "placement_profile";

    private static final String FACING =
            "facing";

    private static final String ANCHOR_POS =
            "anchor_pos";

    private static final String PLACED_POS =
            "placed_pos";

    private static final String RESERVATION_AREA =
            "reservation_area";

    private static final String PREPARATION_AREA =
            "preparation_area";

    private static final String FOUNDATION_DEPTH =
            "foundation_depth";

    private static final String CLEARANCE_HEIGHT =
            "clearance_height";

    private static final String SOURCE_GROUP_LOAD_COST =
            "source_group_load_cost";

    private static final String MIN_X_OFFSET =
            "min_x_offset";

    private static final String MAX_X_OFFSET =
            "max_x_offset";

    private static final String MIN_Z_OFFSET =
            "min_z_offset";

    private static final String MAX_Z_OFFSET =
            "max_z_offset";

    private static final String MINIMUM_INITIAL_RADIUS =
            "minimum_initial_radius";

    private static final String INITIAL_RADIUS_MARGIN =
            "initial_radius_margin";

    private static final String RADIUS_EXPANSION_STEP =
            "radius_expansion_step";

    private static final String MAXIMUM_RADIUS =
            "maximum_radius";

    private static final String MINIMUM_ANCHOR_SEPARATION =
            "minimum_anchor_separation";

    private static final String MAXIMUM_ENVELOPE_OVERLAP =
            "maximum_envelope_overlap";

    private static final String CROSS_GROUP_SOURCE_BUFFER =
            "cross_group_source_buffer";

    private static final String CENTRE =
            "centre";

    private static final String RADIUS =
            "radius";

    private static final String X =
            "x";

    private static final String Y =
            "y";

    private static final String Z =
            "z";

    private SourceGroupPlacementSnapshotNbtCodec() {
    }

    /**
     * Writes one complete immutable physical source-group snapshot.
     */
    public static CompoundTag write(
            SourceGroupPlacementSnapshot snapshot
    ) {
        if (snapshot == null) {
            throw new IllegalArgumentException(
                    "Source-group placement snapshot cannot be null."
            );
        }

        CompoundTag tag =
                new CompoundTag();

        tag.putUUID(
                SOURCE_GROUP_PLACEMENT_ID,
                snapshot.sourceGroupPlacementId()
        );

        tag.putUUID(
                FRONT_ID,
                snapshot.frontId()
        );

        IncursionSnapshotNbtSupport.putUuidList(
                tag,
                SOURCE_GROUP_COMPOSITION_IDS,
                snapshot.sourceGroupCompositionIds()
        );

        tag.putInt(
                MAXIMUM_LOAD,
                snapshot.maximumLoad()
        );

        tag.put(
                SPATIAL_RULES,
                writeSpatialRules(
                        snapshot.spatialRulesSnapshot()
                )
        );

        tag.put(
                ENVELOPE,
                writeEnvelope(
                        snapshot.envelopeSnapshot()
                )
        );

        tag.putString(
                SOURCE_ROLE,
                snapshot.sourceRole().name()
        );

        ListTag sourcePlacementTags =
                new ListTag();

        for (SourceGroupPlacementSnapshot.SourcePlacementSnapshot
                sourcePlacementSnapshot
                : snapshot.sourcePlacementSnapshots()) {

            sourcePlacementTags.add(
                    writeSourcePlacement(
                            sourcePlacementSnapshot
                    )
            );
        }

        tag.put(
                SOURCE_PLACEMENTS,
                sourcePlacementTags
        );

        return tag;
    }

    /**
     * Reads and validates one complete immutable physical source-group
     * snapshot.
     */
    public static SourceGroupPlacementSnapshot read(
            CompoundTag tag
    ) {
        if (tag == null) {
            throw new IllegalArgumentException(
                    "Source-group placement NBT cannot be null."
            );
        }

        ListTag sourcePlacementTags =
                IncursionSnapshotNbtSupport.requireCompoundList(
                        tag,
                        SOURCE_PLACEMENTS
                );

        List<SourceGroupPlacementSnapshot.SourcePlacementSnapshot>
                sourcePlacementSnapshots =
                new ArrayList<>();

        for (int index = 0;
             index < sourcePlacementTags.size();
             index++) {

            try {
                sourcePlacementSnapshots.add(
                        readSourcePlacement(
                                sourcePlacementTags.getCompound(
                                        index
                                )
                        )
                );
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                        "Failed to read physical source placement at index "
                                + index
                                + ".",
                        exception
                );
            }
        }

        return new SourceGroupPlacementSnapshot(
                IncursionSnapshotNbtSupport.requireUuid(
                        tag,
                        SOURCE_GROUP_PLACEMENT_ID
                ),
                IncursionSnapshotNbtSupport.requireUuid(
                        tag,
                        FRONT_ID
                ),
                IncursionSnapshotNbtSupport.readUuidList(
                        tag,
                        SOURCE_GROUP_COMPOSITION_IDS
                ),
                IncursionSnapshotNbtSupport.requireInt(
                        tag,
                        MAXIMUM_LOAD
                ),
                readSpatialRules(
                        IncursionSnapshotNbtSupport.requireCompound(
                                tag,
                                SPATIAL_RULES
                        )
                ),
                readEnvelope(
                        IncursionSnapshotNbtSupport.requireCompound(
                                tag,
                                ENVELOPE
                        )
                ),
                IncursionSnapshotNbtSupport.requireEnum(
                        tag,
                        SOURCE_ROLE,
                        SourceRole.class
                ),
                sourcePlacementSnapshots
        );
    }

    private static CompoundTag writeSourcePlacement(
            SourceGroupPlacementSnapshot.SourcePlacementSnapshot snapshot
    ) {
        if (snapshot == null) {
            throw new IllegalArgumentException(
                    "Source-placement snapshot cannot be null."
            );
        }

        CompoundTag tag =
                new CompoundTag();

        tag.putUUID(
                SOURCE_PLACEMENT_ID,
                snapshot.sourcePlacementId()
        );

        tag.putUUID(
                SOURCE_GROUP_PLACEMENT_ID,
                snapshot.sourceGroupPlacementId()
        );

        IncursionSnapshotNbtSupport.putUuidList(
                tag,
                SOURCE_COMPOSITION_IDS,
                snapshot.sourceCompositionIds()
        );

        tag.putString(
                SOURCE_TYPE,
                snapshot.sourceType().name()
        );

        tag.putString(
                SOURCE_SIZE,
                snapshot.sourceSize().name()
        );

        tag.putString(
                SOURCE_ROLE,
                snapshot.sourceRole().name()
        );

        tag.put(
                PLACEMENT_PROFILE,
                writePlacementProfile(
                        snapshot.placementProfileSnapshot()
                )
        );

        tag.putString(
                FACING,
                snapshot.facing().name()
        );

        tag.put(
                ANCHOR_POS,
                writeBlockPos(
                        snapshot.anchorPos()
                )
        );

        tag.put(
                PLACED_POS,
                writeBlockPos(
                        snapshot.placedPos()
                )
        );

        return tag;
    }

    private static SourceGroupPlacementSnapshot.SourcePlacementSnapshot
    readSourcePlacement(
            CompoundTag tag
    ) {
        return new SourceGroupPlacementSnapshot.SourcePlacementSnapshot(
                IncursionSnapshotNbtSupport.requireUuid(
                        tag,
                        SOURCE_PLACEMENT_ID
                ),
                IncursionSnapshotNbtSupport.requireUuid(
                        tag,
                        SOURCE_GROUP_PLACEMENT_ID
                ),
                IncursionSnapshotNbtSupport.readUuidList(
                        tag,
                        SOURCE_COMPOSITION_IDS
                ),
                IncursionSnapshotNbtSupport.requireEnum(
                        tag,
                        SOURCE_TYPE,
                        SourceType.class
                ),
                IncursionSnapshotNbtSupport.requireEnum(
                        tag,
                        SOURCE_SIZE,
                        SourceSize.class
                ),
                IncursionSnapshotNbtSupport.requireEnum(
                        tag,
                        SOURCE_ROLE,
                        SourceRole.class
                ),
                readPlacementProfile(
                        IncursionSnapshotNbtSupport.requireCompound(
                                tag,
                                PLACEMENT_PROFILE
                        )
                ),
                IncursionSnapshotNbtSupport.requireEnum(
                        tag,
                        FACING,
                        Direction.class
                ),
                readBlockPos(
                        IncursionSnapshotNbtSupport.requireCompound(
                                tag,
                                ANCHOR_POS
                        )
                ),
                readBlockPos(
                        IncursionSnapshotNbtSupport.requireCompound(
                                tag,
                                PLACED_POS
                        )
                )
        );
    }

    private static CompoundTag writePlacementProfile(
            SourceGroupPlacementSnapshot
                    .SourcePlacementProfileSnapshot snapshot
    ) {
        if (snapshot == null) {
            throw new IllegalArgumentException(
                    "Source placement-profile snapshot cannot be null."
            );
        }

        CompoundTag tag =
                new CompoundTag();

        tag.put(
                RESERVATION_AREA,
                writeReservationArea(
                        snapshot.reservationAreaSnapshot()
                )
        );

        tag.put(
                PREPARATION_AREA,
                writeReservationArea(
                        snapshot.preparationAreaSnapshot()
                )
        );

        tag.putInt(
                FOUNDATION_DEPTH,
                snapshot.foundationDepth()
        );

        tag.putInt(
                CLEARANCE_HEIGHT,
                snapshot.clearanceHeight()
        );

        tag.putInt(
                SOURCE_GROUP_LOAD_COST,
                snapshot.sourceGroupLoadCost()
        );

        return tag;
    }

    private static SourceGroupPlacementSnapshot
            .SourcePlacementProfileSnapshot
    readPlacementProfile(
            CompoundTag tag
    ) {
        return new SourceGroupPlacementSnapshot
                .SourcePlacementProfileSnapshot(
                readReservationArea(
                        IncursionSnapshotNbtSupport.requireCompound(
                                tag,
                                RESERVATION_AREA
                        )
                ),
                readReservationArea(
                        IncursionSnapshotNbtSupport.requireCompound(
                                tag,
                                PREPARATION_AREA
                        )
                ),
                IncursionSnapshotNbtSupport.requireInt(
                        tag,
                        FOUNDATION_DEPTH
                ),
                IncursionSnapshotNbtSupport.requireInt(
                        tag,
                        CLEARANCE_HEIGHT
                ),
                IncursionSnapshotNbtSupport.requireInt(
                        tag,
                        SOURCE_GROUP_LOAD_COST
                )
        );
    }

    private static CompoundTag writeReservationArea(
            SourceGroupPlacementSnapshot
                    .SourceReservationAreaSnapshot snapshot
    ) {
        if (snapshot == null) {
            throw new IllegalArgumentException(
                    "Source reservation-area snapshot cannot be null."
            );
        }

        CompoundTag tag =
                new CompoundTag();

        tag.putInt(
                MIN_X_OFFSET,
                snapshot.minXOffset()
        );

        tag.putInt(
                MAX_X_OFFSET,
                snapshot.maxXOffset()
        );

        tag.putInt(
                MIN_Z_OFFSET,
                snapshot.minZOffset()
        );

        tag.putInt(
                MAX_Z_OFFSET,
                snapshot.maxZOffset()
        );

        return tag;
    }

    private static SourceGroupPlacementSnapshot
            .SourceReservationAreaSnapshot
    readReservationArea(
            CompoundTag tag
    ) {
        return new SourceGroupPlacementSnapshot
                .SourceReservationAreaSnapshot(
                IncursionSnapshotNbtSupport.requireInt(
                        tag,
                        MIN_X_OFFSET
                ),
                IncursionSnapshotNbtSupport.requireInt(
                        tag,
                        MAX_X_OFFSET
                ),
                IncursionSnapshotNbtSupport.requireInt(
                        tag,
                        MIN_Z_OFFSET
                ),
                IncursionSnapshotNbtSupport.requireInt(
                        tag,
                        MAX_Z_OFFSET
                )
        );
    }

    private static CompoundTag writeSpatialRules(
            SourceGroupPlacementSnapshot
                    .SourceGroupSpatialRulesSnapshot snapshot
    ) {
        if (snapshot == null) {
            throw new IllegalArgumentException(
                    "Source-group spatial-rules snapshot cannot be null."
            );
        }

        CompoundTag tag =
                new CompoundTag();

        tag.putInt(
                MINIMUM_INITIAL_RADIUS,
                snapshot.minimumInitialRadius()
        );

        tag.putInt(
                INITIAL_RADIUS_MARGIN,
                snapshot.initialRadiusMargin()
        );

        tag.putInt(
                RADIUS_EXPANSION_STEP,
                snapshot.radiusExpansionStep()
        );

        tag.putInt(
                MAXIMUM_RADIUS,
                snapshot.maximumRadius()
        );

        tag.putInt(
                MINIMUM_ANCHOR_SEPARATION,
                snapshot.minimumAnchorSeparation()
        );

        tag.putInt(
                MAXIMUM_ENVELOPE_OVERLAP,
                snapshot.maximumEnvelopeOverlap()
        );

        tag.putInt(
                CROSS_GROUP_SOURCE_BUFFER,
                snapshot.crossGroupSourceBuffer()
        );

        return tag;
    }

    private static SourceGroupPlacementSnapshot
            .SourceGroupSpatialRulesSnapshot
    readSpatialRules(
            CompoundTag tag
    ) {
        return new SourceGroupPlacementSnapshot
                .SourceGroupSpatialRulesSnapshot(
                IncursionSnapshotNbtSupport.requireInt(
                        tag,
                        MINIMUM_INITIAL_RADIUS
                ),
                IncursionSnapshotNbtSupport.requireInt(
                        tag,
                        INITIAL_RADIUS_MARGIN
                ),
                IncursionSnapshotNbtSupport.requireInt(
                        tag,
                        RADIUS_EXPANSION_STEP
                ),
                IncursionSnapshotNbtSupport.requireInt(
                        tag,
                        MAXIMUM_RADIUS
                ),
                IncursionSnapshotNbtSupport.requireInt(
                        tag,
                        MINIMUM_ANCHOR_SEPARATION
                ),
                IncursionSnapshotNbtSupport.requireInt(
                        tag,
                        MAXIMUM_ENVELOPE_OVERLAP
                ),
                IncursionSnapshotNbtSupport.requireInt(
                        tag,
                        CROSS_GROUP_SOURCE_BUFFER
                )
        );
    }

    private static CompoundTag writeEnvelope(
            SourceGroupPlacementSnapshot
                    .SourceGroupEnvelopeSnapshot snapshot
    ) {
        if (snapshot == null) {
            throw new IllegalArgumentException(
                    "Source-group envelope snapshot cannot be null."
            );
        }

        CompoundTag tag =
                new CompoundTag();

        tag.put(
                CENTRE,
                writeBlockPos(
                        snapshot.centre()
                )
        );

        tag.putInt(
                RADIUS,
                snapshot.radius()
        );

        return tag;
    }

    private static SourceGroupPlacementSnapshot
            .SourceGroupEnvelopeSnapshot
    readEnvelope(
            CompoundTag tag
    ) {
        return new SourceGroupPlacementSnapshot
                .SourceGroupEnvelopeSnapshot(
                readBlockPos(
                        IncursionSnapshotNbtSupport.requireCompound(
                                tag,
                                CENTRE
                        )
                ),
                IncursionSnapshotNbtSupport.requireInt(
                        tag,
                        RADIUS
                )
        );
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