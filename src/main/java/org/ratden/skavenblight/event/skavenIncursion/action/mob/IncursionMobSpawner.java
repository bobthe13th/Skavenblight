package org.ratden.skavenblight.event.skavenIncursion.action.mob;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.ratden.skavenblight.event.skavenIncursion.action.mob.generic.SpawnWolfCats;
import org.ratden.skavenblight.event.skavenIncursion.action.mob.generic.SpawnWolfRats;
import org.ratden.skavenblight.event.skavenIncursion.leadership.LeadershipContext;
import org.ratden.skavenblight.event.skavenIncursion.planning.composition.IncursionMobCatalogue;

import java.util.List;
import java.util.UUID;

/**
 * Executes runtime spawn actions for mob IDs selected by the incursion
 * planning system.
 *
 * Authored mob definitions remain in IncursionMobCatalogue. This class only
 * dispatches already planned mob IDs to their runtime spawn actions.
 *
 * Streamed spawning uses spawnOne(...) so the caller receives the actual
 * entity that entered the world. That entity can then be associated with
 * attached complexity, leadership and persistent entity-binding state.
 */
public final class IncursionMobSpawner {

    /**
     * Attempts to spawn exactly one planned mob.
     *
     * @return the successfully added entity and its planned mob ID, or null
     * when the spawn action could not add an entity to the world
     */
    public static SpawnedMob spawnOne(
            String mobId,
            ServerLevel level,
            BlockPos sourcePos,
            LeadershipContext leadershipContext,
            UUID sourceId
    ) {
        validateCommonArguments(
                mobId,
                level,
                sourcePos,
                leadershipContext,
                sourceId
        );

        List<? extends Entity> spawnedEntities =
                executeSpawnAction(
                        mobId,
                        level,
                        sourcePos,
                        1,
                        leadershipContext,
                        sourceId
                );

        if (spawnedEntities.isEmpty()) {
            return null;
        }

        if (spawnedEntities.size() != 1) {
            throw new IllegalStateException(
                    "A single streamed spawn for mob ID "
                            + mobId
                            + " created "
                            + spawnedEntities.size()
                            + " entities."
            );
        }

        Entity spawnedEntity =
                spawnedEntities.getFirst();

        if (spawnedEntity == null
                || spawnedEntity.isRemoved()) {

            return null;
        }

        /*
         * Some older spawn actions add their created entity to their returned
         * list without checking the boolean result of addFreshEntity(...).
         *
         * Confirm that the entity is actually indexed by the ServerLevel
         * before reporting a successful spawn.
         */
        Entity worldEntity =
                level.getEntity(
                        spawnedEntity.getUUID()
                );

        if (worldEntity != spawnedEntity) {
            return null;
        }

        return new SpawnedMob(
                mobId,
                spawnedEntity
        );
    }

    /**
     * Retains a burst-oriented convenience route.
     *
     * Each entity is still spawned independently so only entities confirmed
     * to have entered the ServerLevel contribute to the returned count.
     */
    public static int spawn(
            String mobId,
            ServerLevel level,
            BlockPos sourcePos,
            int count,
            LeadershipContext leadershipContext,
            UUID sourceId
    ) {
        validateCommonArguments(
                mobId,
                level,
                sourcePos,
                leadershipContext,
                sourceId
        );

        if (count <= 0) {
            throw new IllegalArgumentException(
                    "Incursion mob spawn count must be greater than zero."
            );
        }

        int successfulSpawnCount =
                0;

        for (int spawnIndex = 0;
             spawnIndex < count;
             spawnIndex++) {

            SpawnedMob spawnedMob =
                    spawnOne(
                            mobId,
                            level,
                            sourcePos,
                            leadershipContext,
                            sourceId
                    );

            if (spawnedMob != null) {
                successfulSpawnCount++;
            }
        }

        return successfulSpawnCount;
    }

    private static List<? extends Entity> executeSpawnAction(
            String mobId,
            ServerLevel level,
            BlockPos sourcePos,
            int count,
            LeadershipContext leadershipContext,
            UUID sourceId
    ) {
        if (IncursionMobCatalogue.WOLF_RAT
                .getMobId()
                .equals(
                        mobId
                )) {

            return SpawnWolfRats.execute(
                    level,
                    sourcePos,
                    count,
                    leadershipContext,
                    sourceId
            );
        }

        if (IncursionMobCatalogue.WOLF_CAT
                .getMobId()
                .equals(
                        mobId
                )) {

            return SpawnWolfCats.execute(
                    level,
                    sourcePos,
                    count,
                    leadershipContext,
                    sourceId
            );
        }

        throw new IllegalArgumentException(
                "No planning-aware runtime spawn action exists for mob ID "
                        + mobId
                        + "."
        );
    }

    private static void validateCommonArguments(
            String mobId,
            ServerLevel level,
            BlockPos sourcePos,
            LeadershipContext leadershipContext,
            UUID sourceId
    ) {
        if (mobId == null
                || mobId.isBlank()) {

            throw new IllegalArgumentException(
                    "Incursion mob ID cannot be blank."
            );
        }

        if (level == null) {
            throw new IllegalArgumentException(
                    "Incursion mob spawn level cannot be null."
            );
        }

        if (sourcePos == null) {
            throw new IllegalArgumentException(
                    "Incursion mob source position cannot be null."
            );
        }

        if (leadershipContext == null) {
            throw new IllegalArgumentException(
                    "Incursion mob leadership context cannot be null."
            );
        }

        if (sourceId == null) {
            throw new IllegalArgumentException(
                    "Incursion mob source ID cannot be null."
            );
        }
    }

    /**
     * One mob confirmed to have successfully entered the ServerLevel.
     */
    public record SpawnedMob(
            String mobId,
            Entity entity
    ) {

        public SpawnedMob {
            if (mobId == null
                    || mobId.isBlank()) {

                throw new IllegalArgumentException(
                        "Spawned incursion mob ID cannot be blank."
                );
            }

            if (entity == null) {
                throw new IllegalArgumentException(
                        "Spawned incursion entity cannot be null."
                );
            }

            if (entity.isRemoved()) {
                throw new IllegalArgumentException(
                        "A removed entity cannot represent a successful "
                                + "incursion spawn."
                );
            }
        }
    }

    private IncursionMobSpawner() {
    }
}