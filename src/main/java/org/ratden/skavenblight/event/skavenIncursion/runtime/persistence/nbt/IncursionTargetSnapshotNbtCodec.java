package org.ratden.skavenblight.event.skavenIncursion.runtime.persistence.nbt;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.ratden.skavenblight.event.skavenIncursion.director.IncursionTargetType;
import org.ratden.skavenblight.event.skavenIncursion.planning.persistence.nbt.IncursionSnapshotNbtSupport;
import org.ratden.skavenblight.event.skavenIncursion.runtime.persistence.IncursionTargetSnapshot;

/**
 * CompoundTag codec for IncursionTargetSnapshot.
 *
 * The target position is stored as BlockPos's packed long representation.
 * The containing persistent-incursion codec will own schema-version
 * management.
 */
public final class IncursionTargetSnapshotNbtCodec {

    private static final String TARGET_TYPE =
            "target_type";

    private static final String TARGET_POSITION =
            "target_position";

    private static final String BASE_RADIUS =
            "base_radius";

    private IncursionTargetSnapshotNbtCodec() {
    }

    /**
     * Writes one exact incursion-target snapshot.
     */
    public static CompoundTag write(
            IncursionTargetSnapshot snapshot
    ) {
        if (snapshot == null) {
            throw new IllegalArgumentException(
                    "Incursion target snapshot cannot be null."
            );
        }

        CompoundTag tag =
                new CompoundTag();

        tag.putString(
                TARGET_TYPE,
                snapshot.targetType().name()
        );

        tag.putLong(
                TARGET_POSITION,
                snapshot.targetPos().asLong()
        );

        tag.putInt(
                BASE_RADIUS,
                snapshot.baseRadius()
        );

        IncursionTargetSnapshot reconstructedSnapshot =
                read(
                        tag
                );

        if (!snapshot.equals(
                reconstructedSnapshot
        )) {
            throw new IllegalStateException(
                    "Incursion target NBT encoding did not produce an exact "
                            + "snapshot round trip."
            );
        }

        return tag;
    }

    /**
     * Reads and validates one exact incursion-target snapshot.
     */
    public static IncursionTargetSnapshot read(
            CompoundTag tag
    ) {
        if (tag == null) {
            throw new IllegalArgumentException(
                    "Incursion target NBT cannot be null."
            );
        }

        IncursionTargetType targetType =
                IncursionSnapshotNbtSupport.requireEnum(
                        tag,
                        TARGET_TYPE,
                        IncursionTargetType.class
                );

        long packedTargetPosition =
                IncursionSnapshotNbtSupport.requireLong(
                        tag,
                        TARGET_POSITION
                );

        int baseRadius =
                IncursionSnapshotNbtSupport.requireInt(
                        tag,
                        BASE_RADIUS
                );

        return new IncursionTargetSnapshot(
                targetType,
                BlockPos.of(
                        packedTargetPosition
                ),
                baseRadius
        );
    }
}