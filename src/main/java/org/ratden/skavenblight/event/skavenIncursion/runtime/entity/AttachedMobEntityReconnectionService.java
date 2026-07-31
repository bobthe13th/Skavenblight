package org.ratden.skavenblight.event.skavenIncursion.runtime.entity;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.ratden.skavenblight.event.skavenIncursion.director.ActiveIncursionManager;
import org.ratden.skavenblight.event.skavenIncursion.leadership.IncursionOwnedMob;
import org.ratden.skavenblight.event.skavenIncursion.runtime.persistence.LivePersistentIncursion;
import org.ratden.skavenblight.event.skavenIncursion.runtime.persistence.SkavenIncursionSavedData;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Event-driven coordinator for attached-mob entity reconnection.
 *
 * This service never:
 *
 * - scans all entities;
 * - polls entity positions;
 * - forces a chunk to load;
 * - searches neighbouring chunks;
 * - creates replacement entities.
 *
 * It receives only relevant entities that Minecraft has already loaded
 * naturally.
 *
 * During early server startup, entities may load before persistent incursions
 * have been reconstructed. Their UUIDs are retained temporarily and retried
 * after ServerStartedEvent restoration completes.
 */
public final class AttachedMobEntityReconnectionService {

    private static final Logger LOGGER =
            LogUtils.getLogger();

    private static final Map<
            ResourceKey<Level>,
            Set<UUID>
            > PENDING_ENTITY_IDS_BY_LEVEL =
            new LinkedHashMap<>();

    /**
     * Attempts immediate reconnection or queues the entity when its
     * persistent incursion has not yet entered live runtime.
     */
    public static void handleLoadedEntity(
            ServerLevel level,
            Entity entity
    ) {
        if (level == null
                || entity == null
                || entity.isRemoved()) {

            return;
        }

        if (entity.level()
                != level) {

            return;
        }

        if (!(entity
                instanceof AttachedMobAssignmentEntity
                attachedAssignmentEntity)) {

            return;
        }

        UUID attachedMobAssignmentId =
                attachedAssignmentEntity
                        .getAttachedMobAssignmentId();

        if (attachedMobAssignmentId == null) {
            return;
        }

        if (!(entity
                instanceof IncursionOwnedMob incursionOwnedMob)) {

            LOGGER.warn(
                    "Loaded entity {} identifies attached assignment {}, but "
                            + "does not implement IncursionOwnedMob.",
                    entity.getUUID(),
                    attachedMobAssignmentId
            );

            return;
        }

        UUID incursionId =
                incursionOwnedMob.getScenarioId();

        if (incursionId == null) {
            LOGGER.warn(
                    "Loaded entity {} identifies attached assignment {}, but "
                            + "has no incursion ID.",
                    entity.getUUID(),
                    attachedMobAssignmentId
            );

            return;
        }

        LivePersistentIncursion liveIncursion =
                ActiveIncursionManager.getPersistentIncursion(
                        incursionId
                );

        if (liveIncursion == null) {
            /*
             * Queue only when a persistent record still exists. An entity
             * left behind after its incursion was fully cleaned up must not
             * create a permanent pending entry.
             */
            if (SkavenIncursionSavedData
                    .get(
                            level
                    )
                    .getSnapshot(
                            incursionId
                    ) != null) {

                queuePendingEntity(
                        level,
                        entity.getUUID()
                );
            }

            return;
        }

        removePendingEntity(
                level,
                entity.getUUID()
        );

        if (liveIncursion.getLevel()
                != level) {

            LOGGER.warn(
                    "Loaded attached entity {} belongs to incursion {}, but "
                            + "that live incursion is attached to another "
                            + "ServerLevel.",
                    entity.getUUID(),
                    incursionId
            );

            return;
        }

        if (!(liveIncursion.getScenario()
                instanceof AttachedMobEntityReconnectionOwner
                reconnectionOwner)) {

            LOGGER.warn(
                    "Loaded attached entity {} belongs to incursion {}, but "
                            + "Scenario {} does not support attached-mob "
                            + "reconnection.",
                    entity.getUUID(),
                    incursionId,
                    liveIncursion.getScenarioId()
            );

            return;
        }

        AttachedMobEntityReconnectionResult result;

        try {
            result =
                    reconnectionOwner
                            .reconnectLoadedAttachedMobEntity(
                                    entity
                            );
        } catch (RuntimeException exception) {
            LOGGER.error(
                    "Attached entity {} could not be reconnected to "
                            + "incursion {}.",
                    entity.getUUID(),
                    incursionId,
                    exception
            );

            return;
        }

        if (!result.successful()) {
            LOGGER.warn(
                    "Rejected attached entity reconnection for entity {}, "
                            + "assignment {} and incursion {}: {}",
                    result.entityId(),
                    result.attachedMobAssignmentId(),
                    incursionId,
                    result.message()
            );

            return;
        }
    }

    /**
     * Retries relevant entity UUIDs retained during early level loading.
     *
     * level.getEntity(...) checks the already loaded entity index and does not
     * request or force a chunk load.
     */
    public static int reconnectPendingEntities(
            ServerLevel level
    ) {
        if (level == null) {
            throw new IllegalArgumentException(
                    "Pending-reconnection level cannot be null."
            );
        }

        Set<UUID> pendingEntityIds =
                PENDING_ENTITY_IDS_BY_LEVEL.get(
                        level.dimension()
                );

        if (pendingEntityIds == null
                || pendingEntityIds.isEmpty()) {

            return 0;
        }

        int processedCount =
                0;

        for (UUID entityId
                : new ArrayList<>(
                pendingEntityIds
        )) {

            Entity entity =
                    level.getEntity(
                            entityId
                    );

            if (entity == null
                    || entity.isRemoved()) {

                continue;
            }

            handleLoadedEntity(
                    level,
                    entity
            );

            processedCount++;
        }

        removeEmptyLevelEntry(
                level.dimension()
        );

        return processedCount;
    }

    /**
     * Clears temporary startup-only state.
     */
    public static int clearAllPendingEntities() {
        int clearedCount =
                0;

        for (Set<UUID> pendingEntityIds
                : PENDING_ENTITY_IDS_BY_LEVEL.values()) {

            clearedCount +=
                    pendingEntityIds.size();
        }

        PENDING_ENTITY_IDS_BY_LEVEL.clear();

        return clearedCount;
    }

    private static void queuePendingEntity(
            ServerLevel level,
            UUID entityId
    ) {
        PENDING_ENTITY_IDS_BY_LEVEL
                .computeIfAbsent(
                        level.dimension(),
                        ignored ->
                                new LinkedHashSet<>()
                )
                .add(
                        entityId
                );
    }

    private static void removePendingEntity(
            ServerLevel level,
            UUID entityId
    ) {
        Set<UUID> pendingEntityIds =
                PENDING_ENTITY_IDS_BY_LEVEL.get(
                        level.dimension()
                );

        if (pendingEntityIds == null) {
            return;
        }

        pendingEntityIds.remove(
                entityId
        );

        removeEmptyLevelEntry(
                level.dimension()
        );
    }

    private static void removeEmptyLevelEntry(
            ResourceKey<Level> levelKey
    ) {
        Set<UUID> pendingEntityIds =
                PENDING_ENTITY_IDS_BY_LEVEL.get(
                        levelKey
                );

        if (pendingEntityIds != null
                && pendingEntityIds.isEmpty()) {

            PENDING_ENTITY_IDS_BY_LEVEL.remove(
                    levelKey
            );
        }
    }

    private AttachedMobEntityReconnectionService() {
    }
}