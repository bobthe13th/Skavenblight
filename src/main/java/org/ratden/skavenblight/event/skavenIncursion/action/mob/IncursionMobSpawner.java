package org.ratden.skavenblight.event.skavenIncursion.action.mob;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.ratden.skavenblight.event.skavenIncursion.action.mob.generic.SpawnWolfCats;
import org.ratden.skavenblight.event.skavenIncursion.action.mob.generic.SpawnWolfRats;
import org.ratden.skavenblight.event.skavenIncursion.leadership.LeadershipContext;
import org.ratden.skavenblight.event.skavenIncursion.planning.composition.IncursionMobCatalogue;

import java.util.UUID;

/**
 * Executes the existing runtime spawn action for a mob selected by the
 * incursion-planning system.
 *
 * This class is not a content catalogue. Authored mob definitions remain in
 * IncursionMobCatalogue. This class only provides the runtime dispatch needed
 * to execute a planned mob ID.
 *
 * More mob types should be added here only once they are ready to participate
 * in the planning-aware runtime pipeline.
 */
public final class IncursionMobSpawner {

    /**
     * Spawns the requested number of one planned incursion mob type.
     *
     * Runtime may call this with a count of one to stream mobs over time or
     * with a larger count for an explicitly authored burst.
     *
     * Returns the number of entities successfully spawned.
     */
    public static int spawn(
            String mobId,
            ServerLevel level,
            BlockPos sourcePos,
            int count,
            LeadershipContext leadershipContext,
            UUID sourceId
    ) {
        validateArguments(
                mobId,
                level,
                sourcePos,
                count,
                leadershipContext,
                sourceId
        );

        if (IncursionMobCatalogue.WOLF_RAT
                .getMobId()
                .equals(mobId)) {
            return SpawnWolfRats.execute(
                    level,
                    sourcePos,
                    count,
                    leadershipContext,
                    sourceId
            ).size();
        }

        if (IncursionMobCatalogue.WOLF_CAT
                .getMobId()
                .equals(mobId)) {
            return SpawnWolfCats.execute(
                    level,
                    sourcePos,
                    count,
                    leadershipContext,
                    sourceId
            ).size();
        }

        throw new IllegalArgumentException(
                "No planning-aware runtime spawn action exists for mob ID "
                        + mobId
                        + "."
        );
    }

    private static void validateArguments(
            String mobId,
            ServerLevel level,
            BlockPos sourcePos,
            int count,
            LeadershipContext leadershipContext,
            UUID sourceId
    ) {
        if (mobId == null || mobId.isBlank()) {
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

        if (count <= 0) {
            throw new IllegalArgumentException(
                    "Incursion mob spawn count must be greater than zero."
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

    private IncursionMobSpawner() {
    }
}