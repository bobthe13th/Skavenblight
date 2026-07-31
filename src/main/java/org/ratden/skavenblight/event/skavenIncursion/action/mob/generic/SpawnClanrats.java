package org.ratden.skavenblight.event.skavenIncursion.action.mob.generic;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import org.ratden.skavenblight.entity.ModEntities;
import org.ratden.skavenblight.entity.custom.ClanratEntity;
import org.ratden.skavenblight.event.skavenIncursion.leadership.LeadershipContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class SpawnClanrats {

    public static List<ClanratEntity> execute(
            ServerLevel level,
            BlockPos sourcePos,
            int count,
            LeadershipContext leadershipContext,
            UUID sourceId
    ) {
        List<ClanratEntity> spawnedClanrats = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            ClanratEntity clanrat = ModEntities.CLANRAT.get().create(level);

            if (clanrat == null) {
                continue;
            }

            BlockPos spawnPos = findSafeSpawnPos(level, sourcePos);

            if (spawnPos == null) {
                spawnPos = createFallbackSpawnPos(level, sourcePos);
            }

            if (spawnPos == null) {
                continue;
            }

            /*
             * ClanratEntity does not currently contain the leadership ID
             * setters used by WolfCat.
             *
             * These should be added once ClanratEntity participates in the
             * incursion leadership hierarchy:
             *
             * clanrat.setScenarioId(leadershipContext.getScenarioId());
             * clanrat.setSourceId(sourceId);
             * clanrat.setVermintideId(leadershipContext.getVermintideId());
             * clanrat.setFangId(leadershipContext.getFangId());
             * clanrat.setClawId(leadershipContext.getClawId());
             * clanrat.setPackId(leadershipContext.getPackId());
             */

            clanrat.moveTo(
                    spawnPos.getX() + 0.5D,
                    spawnPos.getY(),
                    spawnPos.getZ() + 0.5D,
                    level.random.nextFloat() * 360F,
                    0
            );

            level.addFreshEntity(clanrat);
            spawnedClanrats.add(clanrat);
        }

        return spawnedClanrats;
    }

    private static BlockPos findSafeSpawnPos(
            ServerLevel level,
            BlockPos sourcePos
    ) {
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

    private static BlockPos findSafePosNear(
            ServerLevel level,
            BlockPos pos
    ) {
        for (int yOffset = -2; yOffset <= 2; yOffset++) {
            BlockPos candidatePos = pos.offset(0, yOffset, 0);

            if (isSafeSpawnPos(level, candidatePos)) {
                return candidatePos;
            }
        }

        return null;
    }

    private static boolean isSafeSpawnPos(
            ServerLevel level,
            BlockPos pos
    ) {
        return level.getBlockState(pos).isAir()
                && level.getBlockState(pos.above()).isAir()
                && level.getBlockState(pos.below())
                .isSolidRender(level, pos.below());
    }

    private static BlockPos createFallbackSpawnPos(
            ServerLevel level,
            BlockPos sourcePos
    ) {
        BlockPos fallbackPos = sourcePos.offset(1, 0, 0);

        level.setBlock(
                fallbackPos.below(),
                Blocks.DIRT.defaultBlockState(),
                3
        );

        level.setBlock(
                fallbackPos,
                Blocks.AIR.defaultBlockState(),
                3
        );

        level.setBlock(
                fallbackPos.above(),
                Blocks.AIR.defaultBlockState(),
                3
        );

        if (isSafeSpawnPos(level, fallbackPos)) {
            return fallbackPos;
        }

        return null;
    }

    private SpawnClanrats() {
    }
}