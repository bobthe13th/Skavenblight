package org.ratden.skavenblight.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.ratden.skavenblight.Skavenblight;

/**
 * General physical-client gameplay and rendering event hooks.
 *
 * This class is an appropriate home for small client-only hooks that cross
 * systems or do not yet justify a dedicated event class.
 *
 * Substantial rendering or gameplay logic should remain in the class or
 * subsystem that owns it. Methods here should primarily inspect events and
 * delegate work.
 */
@EventBusSubscriber(
        modid = Skavenblight.MODID,
        value = Dist.CLIENT
)
public final class ClientGameplayEvents {

    private static final RenderLevelStageEvent.Stage
            FLOW_FIELD_DEBUG_RENDER_STAGE =
            RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS;

    /**
     * Delegates world rendering for the client-side Flow Field debug view.
     *
     * NeoForge infers the game event bus from RenderLevelStageEvent.
     */
    @SubscribeEvent
    public static void onRenderLevelStage(
            RenderLevelStageEvent event
    ) {
        if (event.getStage() != FLOW_FIELD_DEBUG_RENDER_STAGE) {
            return;
        }

        ClientRenderHandler.renderFlowFieldDebug(
                event.getPoseStack()
                        .last()
                        .pose(),
                event.getCamera()
                        .getPosition()
        );
    }

    private ClientGameplayEvents() {
    }
}