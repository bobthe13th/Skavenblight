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

@EventBusSubscriber(modid = Skavenblight.MODID)
public class GameplayEvents {
    private static final ResourceLocation ACTIVATE_NEXUS_ADVANCEMENT =
            ResourceLocation.fromNamespaceAndPath(
                    Skavenblight.MODID,
                    "activate_nexus"
            );

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        ActiveIncursionManager.onServerTick(event);
        SkavenDirector.onServerTick(event);
    }

    @SubscribeEvent
    public static void onAdvancementEarned(AdvancementEvent.AdvancementEarnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        if (!event.getAdvancement().id().equals(ACTIVATE_NEXUS_ADVANCEMENT)) {
            return;
        }

        SkavenDirector.tryStartSpecificScenario(
                player,
                TutorialCampAttack.id(),
                DirectorStartReason.ADVANCEMENT_TRIGGERED
        );
    }
}