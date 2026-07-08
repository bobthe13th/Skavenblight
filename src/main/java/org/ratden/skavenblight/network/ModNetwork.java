package org.ratden.skavenblight.network;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.network.payload.SyncFlowFieldDebugPayload;

@EventBusSubscriber(modid = Skavenblight.MODID, bus = EventBusSubscriber.Bus.MOD)
public class ModNetwork {

    @SubscribeEvent
    public static void registerPayloads(final RegisterPayloadHandlersEvent event) {
        // Create the registrar for your mod id and assign a network protocol version (e.g., "1")
        final PayloadRegistrar registrar = event.registrar(Skavenblight.MODID).versioned("1");

        // --- payload registration line ---
        registrar.playToClient(
                SyncFlowFieldDebugPayload.TYPE,
                SyncFlowFieldDebugPayload.CODEC,
                SyncFlowFieldDebugPayload::handle
        );
    }
}