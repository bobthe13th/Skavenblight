package org.ratden.skavenblight.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.client.ClientRenderHandler;

// Note: No "bus = EventBusSubscriber.Bus.MOD" here. It defaults to the GAME bus.
@EventBusSubscriber(modid = Skavenblight.MODID, value = Dist.CLIENT)
public class ClientGameplayEvents {

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        // Render after translucent blocks so our debug lines aren't hidden behind glass/water
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            ClientRenderHandler.renderFlowFieldDebug(
                    event.getPoseStack().last().pose(),
                    event.getCamera().getPosition()
            );
        }
    }
}