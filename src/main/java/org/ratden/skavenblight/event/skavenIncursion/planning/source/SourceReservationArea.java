package org.ratden.skavenblight.event.skavenIncursion.planning.source;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.Objects;

/**
 * Horizontal area reserved for one physical incursion source.
 *
 * Offsets are stored relative to the source origin while the source is
 * considered to face north:
 *
 * - positive X extends to the source's right;
 * - negative X extends to the source's left;
 * - negative Z extends forward;
 * - positive Z extends backward.
 *
 * The area can be rotated into world coordinates using the source's actual
 * horizontal facing.
 *
 * Reservation bounds are inclusive because they represent occupied block
 * coordinates.
 */
public record SourceReservationArea(
        int minXOffset,
        int maxXOffset,
        int minZOffset,
        int maxZOffset
) {
    public SourceReservationArea {
        if (minXOffset > maxXOffset) {
            throw new IllegalArgumentException(
                    "Minimum X offset cannot exceed maximum X offset."
            );
        }

        if (minZOffset > maxZOffset) {
            throw new IllegalArgumentException(
                    "Minimum Z offset cannot exceed maximum Z offset."
            );
        }
    }

    /**
     * Creates an area evenly centred on its source origin.
     *
     * Centred areas must use odd dimensions so that a single source block can
     * occupy the exact centre. Even or asymmetric footprints should use the
     * main constructor with explicit offsets.
     */
    public static SourceReservationArea centered(
            int width,
            int depth
    ) {
        if (width <= 0) {
            throw new IllegalArgumentException(
                    "Reservation width must be positive."
            );
        }

        if (depth <= 0) {
            throw new IllegalArgumentException(
                    "Reservation depth must be positive."
            );
        }

        if (width % 2 == 0) {
            throw new IllegalArgumentException(
                    "A centred reservation width must be odd."
            );
        }

        if (depth % 2 == 0) {
            throw new IllegalArgumentException(
                    "A centred reservation depth must be odd."
            );
        }

        int halfWidth = width / 2;
        int halfDepth = depth / 2;

        return new SourceReservationArea(
                -halfWidth,
                halfWidth,
                -halfDepth,
                halfDepth
        );
    }

    public int width() {
        return maxXOffset - minXOffset + 1;
    }

    public int depth() {
        return maxZOffset - minZOffset + 1;
    }

    public int blockCount() {
        return width() * depth();
    }

    /**
     * Resolves this source-relative area into inclusive world-coordinate
     * bounds.
     */
    public WorldBounds resolve(
            BlockPos origin,
            Direction facing
    ) {
        Objects.requireNonNull(
                origin,
                "Reservation origin cannot be null."
        );

        validateHorizontalFacing(facing);

        int[][] localCorners = {
                {minXOffset, minZOffset},
                {minXOffset, maxZOffset},
                {maxXOffset, minZOffset},
                {maxXOffset, maxZOffset}
        };

        int worldMinX = Integer.MAX_VALUE;
        int worldMaxX = Integer.MIN_VALUE;
        int worldMinZ = Integer.MAX_VALUE;
        int worldMaxZ = Integer.MIN_VALUE;

        for (int[] localCorner : localCorners) {
            HorizontalOffset rotatedOffset =
                    rotate(
                            localCorner[0],
                            localCorner[1],
                            facing
                    );

            int worldX =
                    origin.getX() + rotatedOffset.x();

            int worldZ =
                    origin.getZ() + rotatedOffset.z();

            worldMinX = Math.min(worldMinX, worldX);
            worldMaxX = Math.max(worldMaxX, worldX);
            worldMinZ = Math.min(worldMinZ, worldZ);
            worldMaxZ = Math.max(worldMaxZ, worldZ);
        }

        return new WorldBounds(
                worldMinX,
                worldMaxX,
                worldMinZ,
                worldMaxZ
        );
    }

    /**
     * Returns whether this reservation overlaps another reservation after
     * both are resolved into world coordinates.
     *
     * Areas that merely sit beside one another without sharing a block do not
     * overlap.
     */
    public boolean overlaps(
            BlockPos origin,
            Direction facing,
            SourceReservationArea otherArea,
            BlockPos otherOrigin,
            Direction otherFacing
    ) {
        Objects.requireNonNull(
                otherArea,
                "Other reservation area cannot be null."
        );

        WorldBounds thisBounds =
                resolve(origin, facing);

        WorldBounds otherBounds =
                otherArea.resolve(
                        otherOrigin,
                        otherFacing
                );

        return thisBounds.overlaps(otherBounds);
    }

    private static HorizontalOffset rotate(
            int localX,
            int localZ,
            Direction facing
    ) {
        return switch (facing) {
            case NORTH -> new HorizontalOffset(
                    localX,
                    localZ
            );

            case EAST -> new HorizontalOffset(
                    -localZ,
                    localX
            );

            case SOUTH -> new HorizontalOffset(
                    -localX,
                    -localZ
            );

            case WEST -> new HorizontalOffset(
                    localZ,
                    -localX
            );

            case UP, DOWN -> throw new IllegalArgumentException(
                    "Source reservation facing must be horizontal."
            );
        };
    }

    private static void validateHorizontalFacing(
            Direction facing
    ) {
        Objects.requireNonNull(
                facing,
                "Source reservation facing cannot be null."
        );

        if (facing.getAxis().isVertical()) {
            throw new IllegalArgumentException(
                    "Source reservation facing must be horizontal."
            );
        }
    }

    private record HorizontalOffset(
            int x,
            int z
    ) {
    }

    /**
     * Inclusive horizontal world-coordinate bounds.
     */
    public record WorldBounds(
            int minX,
            int maxX,
            int minZ,
            int maxZ
    ) {
        public WorldBounds {
            if (minX > maxX) {
                throw new IllegalArgumentException(
                        "Minimum world X cannot exceed maximum world X."
                );
            }

            if (minZ > maxZ) {
                throw new IllegalArgumentException(
                        "Minimum world Z cannot exceed maximum world Z."
                );
            }
        }

        public int width() {
            return maxX - minX + 1;
        }

        public int depth() {
            return maxZ - minZ + 1;
        }

        public boolean contains(
                int x,
                int z
        ) {
            return x >= minX
                    && x <= maxX
                    && z >= minZ
                    && z <= maxZ;
        }

        public boolean contains(
                BlockPos pos
        ) {
            Objects.requireNonNull(
                    pos,
                    "Position cannot be null."
            );

            return contains(
                    pos.getX(),
                    pos.getZ()
            );
        }

        public boolean overlaps(
                WorldBounds other
        ) {
            Objects.requireNonNull(
                    other,
                    "Other world bounds cannot be null."
            );

            return minX <= other.maxX
                    && maxX >= other.minX
                    && minZ <= other.maxZ
                    && maxZ >= other.minZ;
        }
    }
}