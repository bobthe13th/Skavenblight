package org.ratden.skavenblight.event.skavenIncursion.action.mob.generic;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import org.ratden.skavenblight.entity.ModEntities;
import org.ratden.skavenblight.entity.custom.WolfRat;
import org.ratden.skavenblight.event.skavenIncursion.leadership.LeadershipContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class SpawnWolfRats {

    public static List<WolfRat> execute(
            ServerLevel level,
            BlockPos sourcePos,
            int count,
            LeadershipContext leadershipContext,
            UUID sourceId
    ) {
        List<WolfRat> spawnedWolfRats = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            WolfRat wolfRat = ModEntities.RAT_WOLF.get().create(level);

            if (wolfRat == null) {
                continue;
            }

            BlockPos spawnPos = findSafeSpawnPos(level, sourcePos);

            if (spawnPos == null) {
                spawnPos = createFallbackSpawnPos(level, sourcePos);
            }

            if (spawnPos == null) {
                continue;
            }

            wolfRat.setScenarioId(leadershipContext.scenarioId());
            wolfRat.setSourceId(sourceId);
            wolfRat.setVermintideId(leadershipContext.vermintideId());
            wolfRat.setFangId(leadershipContext.fangId());
            wolfRat.setClawId(leadershipContext.clawId());
            wolfRat.setPackId(leadershipContext.packId());

            wolfRat.moveTo(
                    spawnPos.getX() + 0.5,
                    spawnPos.getY(),
                    spawnPos.getZ() + 0.5,
                    level.random.nextFloat() * 360F,
                    0
            );

            level.addFreshEntity(wolfRat);
            spawnedWolfRats.add(wolfRat);
        }

        return spawnedWolfRats;
    }

    private static BlockPos findSafeSpawnPos(ServerLevel level, BlockPos sourcePos) {
        int attempts = 24;

        for (int attempt = 0; attempt < attempts; attempt++) {
            int offsetX = level.random.nextInt(9) - 4;
            int offsetZ = level.random.nextInt(9) - 4;

            BlockPos candidatePos = sourcePos.offset(offsetX, 0, offsetZ);

            BlockPos safePos = findSafePosNear(level, candidatePos);

            if (safePos != null) {
                return safePos;
            }
        }

        return findSafePosNear(level, sourcePos);
    }

    private static BlockPos findSafePosNear(ServerLevel level, BlockPos pos) {
        for (int yOffset = -2; yOffset <= 2; yOffset++) {
            BlockPos candidatePos = pos.offset(0, yOffset, 0);

            if (isSafeSpawnPos(level, candidatePos)) {
                return candidatePos;
            }
        }

        return null;
    }

    private static boolean isSafeSpawnPos(ServerLevel level, BlockPos pos) {
        net.minecraft.world.level.block.state.BlockState floorState = level.getBlockState(pos.below());

        boolean isSolid = floorState.isSolidRender(level, pos.below());
        boolean isNotWater = floorState.getFluidState().isEmpty();
        boolean isNotLeaves = !floorState.is(net.minecraft.tags.BlockTags.LEAVES); // Requires standard block tags
        boolean isNotLog = !floorState.is(net.minecraft.tags.BlockTags.LOGS);

        return level.getBlockState(pos).isAir()
                && level.getBlockState(pos.above()).isAir()
                && isSolid
                && isNotWater
                && isNotLeaves
                && isNotLog;
    }

    private static BlockPos createFallbackSpawnPos(ServerLevel level, BlockPos sourcePos) {
        // We are no longer forcing Blocks.DIRT or hollowing out Blocks.AIR here!
        // Instead, we should just look for the nearest valid surface height.
        BlockPos fallbackPos = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, sourcePos.offset(1, 0, 0));

        if (isSafeSpawnPos(level, fallbackPos)) {
            return fallbackPos;
        }
        return null;
    }

    private SpawnWolfRats() {
    }
}