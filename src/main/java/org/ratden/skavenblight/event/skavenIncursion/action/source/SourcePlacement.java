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
            return nearTarget(level, targetPos, 10, 18);
        }

        return nearTarget(level, targetPos, 8, 14);
    }

    private static BlockPos nearTarget(
            ServerLevel level,
            BlockPos targetPos,
            int minDistance,
            int maxDistance
    ) {
        int attempts = 20;
        int sourceClearanceRadius = 6;

        for (int i = 0; i < attempts; i++) {
            BlockPos candidatePos = randomRingPos(level, targetPos, minDistance, maxDistance);

            BlockPos surfacePos = findSurface(
                    level,
                    candidatePos.getX(),
                    targetPos.getY(),
                    candidatePos.getZ()
            );

            if (surfacePos != null
                    && !isTooCloseToExistingSource(level, surfacePos, sourceClearanceRadius)) {
                return surfacePos;
            }
        }

        for (int i = 0; i < attempts; i++) {
            BlockPos forcedPos = randomRingPos(level, targetPos, minDistance, maxDistance);

            if (!isTooCloseToExistingSource(level, forcedPos, sourceClearanceRadius)) {
                return forcedPos;
            }
        }

        return randomRingPos(level, targetPos, maxDistance + sourceClearanceRadius, maxDistance + sourceClearanceRadius + 8);
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
            int startY,
            int z
    ) {
        int minY = Math.max(level.getMinBuildHeight(), startY - 16);
        int maxY = Math.min(level.getMaxBuildHeight() - 1, startY + 16);

        for (int y = maxY; y >= minY; y--) {
            BlockPos groundPos = new BlockPos(x, y, z);
            BlockPos placePos = groundPos.above();

            if (level.getBlockState(groundPos).isSolidRender(level, groundPos)
                    && level.getBlockState(placePos).isAir()) {
                return placePos;
            }
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