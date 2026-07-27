package org.ratden.skavenblight.event;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.event.skavenIncursion.director.ActiveIncursionManager;
import org.ratden.skavenblight.event.skavenIncursion.director.DirectorStartReason;
import org.ratden.skavenblight.event.skavenIncursion.director.SkavenDirector;
import org.ratden.skavenblight.event.skavenIncursion.scenario.tutorial.TutorialCampAttack;

/**
 * General common-side gameplay event hooks.
 *
 * This class is an appropriate home for small, cross-system gameplay hooks
 * that do not yet justify a dedicated event class.
 *
 * Substantial subsystem logic should remain in the system that owns it.
 * Methods here should primarily validate an event and delegate work.
 */
@EventBusSubscriber(
        modid = Skavenblight.MODID
)
public final class GameplayEvents {

    private static final ResourceLocation
            ACTIVATE_NEXUS_ADVANCEMENT_ID =
            ResourceLocation.fromNamespaceAndPath(
                    Skavenblight.MODID,
                    "activate_nexus"
            );

    /**
     * Supplies the shared server-tick hook used by the current incursion
     * runtime systems.
     */
    @SubscribeEvent
    public static void onServerTick(
            ServerTickEvent.Post event
    ) {
        ActiveIncursionManager.onServerTick(event);
        SkavenDirector.onServerTick(event);
    }

    /**
     * Starts Skavenblight when the player completes the Nexus activation
     * advancement, including the introductory tutorial attack.
     */
    @SubscribeEvent
    public static void onAdvancementEarned(
            AdvancementEvent.AdvancementEarnEvent event
    ) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        if (!event.getAdvancement()
                .id()
                .equals(ACTIVATE_NEXUS_ADVANCEMENT_ID)) {
            return;
        }

        SkavenDirector.tryStartSpecificScenario(
                player,
                TutorialCampAttack.id(),
                DirectorStartReason.ADVANCEMENT_TRIGGERED
        );
    }

    private GameplayEvents() {
    }
}