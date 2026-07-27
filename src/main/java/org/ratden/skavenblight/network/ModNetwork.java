package org.ratden.skavenblight.network;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.network.payload.SyncFlowFieldDebugPayload;

/**
 * Registers the network payloads used by Skavenblight.
 *
 * Payload definitions must be registered on both physical sides so the
 * client and server agree on the network protocol. Individual payload
 * directions determine which logical side receives their handlers.
 */
@EventBusSubscriber(
        modid = Skavenblight.MODID,
        bus = EventBusSubscriber.Bus.MOD
)
public final class ModNetwork {

    private static final String PROTOCOL_VERSION = "1";

    @SubscribeEvent
    public static void registerPayloads(
            RegisterPayloadHandlersEvent event
    ) {
        PayloadRegistrar registrar = event
                .registrar(Skavenblight.MODID)
                .versioned(PROTOCOL_VERSION);

        registrar.playToClient(
                SyncFlowFieldDebugPayload.TYPE,
                SyncFlowFieldDebugPayload.CODEC,
                SyncFlowFieldDebugPayload::handle
        );
    }

    private ModNetwork() {
    }
}