package org.ratden.skavenblight.event.skavenIncursion.action.source;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.ratden.skavenblight.block.ModBlocks;
import org.ratden.skavenblight.event.skavenIncursion.director.IncursionTargetType;

public class SourcePlacement {

    public static BlockPos forAssault(
            ServerLevel level,
            BlockPos targetPos,
            IncursionTargetType targetType
    ) {
        if (targetType == IncursionTargetType.NEXUS) {
            // Pushes the spawn to the edge of a typical 32-block base territory radius[cite: 50]
            return nearTarget(level, targetPos, 32, 38);
        }

        // Expanded for players to keep it slightly out of immediate view[cite: 50]
        return nearTarget(level, targetPos, 24, 32);
    }

    private static BlockPos nearTarget(
            ServerLevel level,
            BlockPos targetPos,
            int minDistance,
            int maxDistance
    ) {
        int attempts = 30; // Increased attempts to account for stricter surface rules
        int sourceClearanceRadius = 6;

        for (int i = 0; i < attempts; i++) {
            BlockPos candidatePos = randomRingPos(level, targetPos, minDistance, maxDistance);

            // StartY is no longer needed since we use heightmaps[cite: 50]
            BlockPos surfacePos = findSurface(
                    level,
                    candidatePos.getX(),
                    candidatePos.getZ()
            );

            if (surfacePos != null
                    && !isTooCloseToExistingSource(level, surfacePos, sourceClearanceRadius)) {
                return surfacePos;
            }
        }

        // Fallback: If we fail to find a perfect spot (e.g., base is completely surrounded by ocean),
        // we still use the heightmap to ensure it stays on the surface instead of forcing a
        // raw Y-coordinate that might be mid-air or underground[cite: 50].
        BlockPos forcedPos = randomRingPos(level, targetPos, minDistance, maxDistance);
        return level.getHeightmapPos(
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                forcedPos
        );
    }

    private static BlockPos randomRingPos(
            ServerLevel level,
            BlockPos targetPos,
            int minDistance,
            int maxDistance
    ) {
        double angle = level.random.nextDouble() * Math.PI * 2.0;
        int distance = minDistance + level.random.nextInt(maxDistance - minDistance + 1);

        int x = targetPos.getX() + (int) Math.round(Math.cos(angle) * distance);
        int z = targetPos.getZ() + (int) Math.round(Math.sin(angle) * distance);

        return new BlockPos(x, targetPos.getY(), z);
    }

    private static BlockPos findSurface(
            ServerLevel level,
            int x,
            int z
    ) {
        // Replaces the manual Y-level loop with native surface snapping, ignoring tree canopies[cite: 50].
        BlockPos surfacePos = level.getHeightmapPos(
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                new BlockPos(x, 0, z)
        );

        BlockPos floorPos = surfacePos.below();
        net.minecraft.world.level.block.state.BlockState floorState = level.getBlockState(floorPos);

        boolean isSolid = floorState.isSolidRender(level, floorPos);
        boolean isNotWater = floorState.getFluidState().isEmpty();
        boolean isNotLeaves = !floorState.is(net.minecraft.tags.BlockTags.LEAVES);
        boolean isNotLog = !floorState.is(net.minecraft.tags.BlockTags.LOGS);

        if (isSolid && isNotWater && isNotLeaves && isNotLog) {
            return surfacePos;
        }

        return null;
    }

    private static boolean isTooCloseToExistingSource(
            ServerLevel level,
            BlockPos candidatePos,
            int radius
    ) {
        for (BlockPos pos : BlockPos.betweenClosed(
                candidatePos.offset(-radius, -radius, -radius),
                candidatePos.offset(radius, radius, radius))) {

            if (level.getBlockState(pos).is(ModBlocks.SKAVEN_TUNNEL_SOURCE.get())) {
                return true;
            }
        }

        return false;
    }
}