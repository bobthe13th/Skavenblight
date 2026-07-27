package org.ratden.skavenblight.event.skavenIncursion.runtime.entity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.ratden.skavenblight.Skavenblight;

/**
 * GAME-bus adapter for event-driven attached-mob reconnection.
 *
 * The join event is filtered before any task is scheduled:
 *
 * - server side only;
 * - entities loaded from disk only;
 * - entities implementing AttachedMobAssignmentEntity only;
 * - entities carrying a non-null assignment ID only.
 *
 * No recurring tick handler is registered.
 */
@EventBusSubscriber(
        modid = Skavenblight.MODID
)
public final class AttachedMobEntityLifecycleEvents {

    @SubscribeEvent
    public static void onEntityJoinLevel(
            EntityJoinLevelEvent event
    ) {
        if (!event.loadedFromDisk()) {
            return;
        }

        if (!(event.getLevel()
                instanceof ServerLevel serverLevel)) {

            return;
        }

        Entity entity =
                event.getEntity();

        if (!(entity
                instanceof AttachedMobAssignmentEntity
                attachedAssignmentEntity)) {

            return;
        }

        if (!attachedAssignmentEntity
                .hasAttachedMobAssignmentId()) {

            return;
        }

        /*
         * EntityJoinLevelEvent can occur before the underlying chunk reaches
         * full status. Delay even this lightweight runtime interaction until
         * the server processes its queued work.
         */
        serverLevel
                .getServer()
                .execute(
                        () ->
                                AttachedMobEntityReconnectionService
                                        .handleLoadedEntity(
                                                serverLevel,
                                                entity
                                        )
                );
    }

    /**
     * Retries entities that entered loaded chunks before persistent
     * incursions were reconstructed.
     *
     * Scheduling this task also prevents subscriber ordering on
     * ServerStartedEvent from coupling this subsystem to the persistence
     * lifecycle subscriber.
     */
    @SubscribeEvent
    public static void onServerStarted(
            ServerStartedEvent event
    ) {
        event.getServer()
                .execute(
                        () -> {
                            for (ServerLevel level
                                    : event.getServer()
                                    .getAllLevels()) {

                                AttachedMobEntityReconnectionService
                                        .reconnectPendingEntities(
                                                level
                                        );
                            }
                        }
                );
    }

    @SubscribeEvent
    public static void onServerStopped(
            ServerStoppedEvent event
    ) {
        AttachedMobEntityReconnectionService
                .clearAllPendingEntities();
    }

    private AttachedMobEntityLifecycleEvents() {
    }
}