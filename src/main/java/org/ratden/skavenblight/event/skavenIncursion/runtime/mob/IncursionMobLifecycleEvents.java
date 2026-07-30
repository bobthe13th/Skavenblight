package org.ratden.skavenblight.event.skavenIncursion.runtime.mob;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.event.skavenIncursion.director.ActiveIncursionManager;
import org.ratden.skavenblight.event.skavenIncursion.leadership.IncursionOwnedMob;

import java.util.UUID;

/**
 * GAME-bus adapter for authoritative persistent incursion-mob lifecycle
 * tracking.
 *
 * Confirmed living-entity deaths become DEFEATED.
 *
 * Destructive non-death removals become OTHER_TERMINAL_REMOVAL.
 *
 * EntityLeaveLevelEvent also fires for ordinary unloading and dimension
 * transfer. Those events are not terminal and must leave the persistent mob
 * resolution ACTIVE.
 *
 * LivingDeathEvent remains the earliest authoritative death notification.
 * EntityLeaveLevelEvent also accepts KILLED as an idempotent fallback in case
 * another removal path kills an entity without producing the expected living
 * death callback.
 */
@EventBusSubscriber(
        modid = Skavenblight.MODID
)
public final class IncursionMobLifecycleEvents {

    /**
     * Records a confirmed, uncancelled living-entity death.
     *
     * Running at LOWEST priority with receiveCanceled left false allows
     * earlier listeners to cancel the death before persistent tracking is
     * changed.
     */
    @SubscribeEvent(
            priority = EventPriority.LOWEST
    )
    public static void onLivingDeath(
            LivingDeathEvent event
    ) {
        LivingEntity entity =
                event.getEntity();

        if (!(entity.level()
                instanceof ServerLevel serverLevel)) {

            return;
        }

        if (!(entity
                instanceof IncursionOwnedMob incursionOwnedMob)) {

            return;
        }

        UUID incursionId =
                incursionOwnedMob.getScenarioId();

        if (incursionId == null) {
            return;
        }

        ActiveIncursionManager.reportTrackedMobDefeated(
                serverLevel,
                incursionId,
                entity.getUUID()
        );
    }

    /**
     * Classifies an incursion-owned entity leaving level tracking.
     *
     * RemovalReason.shouldDestroy() distinguishes permanent destruction from
     * unload-style removal:
     *
     * - KILLED is routed to DEFEATED;
     * - another destructive reason is routed to OTHER_TERMINAL_REMOVAL;
     * - unload and dimension-transfer reasons remain ACTIVE.
     *
     * Death routing is deliberately idempotent. In the normal death path,
     * LivingDeathEvent has already marked the mob DEFEATED before this event
     * occurs.
     */
    @SubscribeEvent
    public static void onEntityLeaveLevel(
            EntityLeaveLevelEvent event
    ) {
        if (!(event.getLevel()
                instanceof ServerLevel serverLevel)) {

            return;
        }

        Entity entity =
                event.getEntity();

        if (!(entity
                instanceof IncursionOwnedMob incursionOwnedMob)) {

            return;
        }

        UUID incursionId =
                incursionOwnedMob.getScenarioId();

        if (incursionId == null) {
            return;
        }

        Entity.RemovalReason removalReason =
                entity.getRemovalReason();

        /*
         * A null reason cannot be safely classified as a permanent removal.
         */
        if (removalReason == null) {
            return;
        }

        /*
         * KILLED is a confirmed defeat. This normally repeats the earlier
         * LivingDeathEvent notification, but markDefeated(...) is
         * intentionally idempotent.
         */
        if (removalReason
                == Entity.RemovalReason.KILLED) {

            ActiveIncursionManager.reportTrackedMobDefeated(
                    serverLevel,
                    incursionId,
                    entity.getUUID()
            );

            return;
        }

        /*
         * UNLOADED_TO_CHUNK, UNLOADED_WITH_PLAYER and CHANGED_DIMENSION are
         * non-destructive removals. Their tracked mobs remain ACTIVE.
         */
        if (!removalReason.shouldDestroy()) {
            return;
        }

        /*
         * A destructive reason other than KILLED is a permanent non-death
         * removal. In the current Vanilla removal-reason set, the usual case
         * is DISCARDED.
         */
        ActiveIncursionManager
                .reportTrackedMobOtherTerminalRemoval(
                        serverLevel,
                        incursionId,
                        entity.getUUID()
                );
    }

    private IncursionMobLifecycleEvents() {
    }
}