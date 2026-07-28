package org.ratden.skavenblight.magic;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import org.ratden.skavenblight.magic.player.ModAttachments;
import org.ratden.skavenblight.magic.spell.SpellManager;

/** Wires the magic system into the mod event bus. One method per concern, mirrors Skavenblight.java. */
public class ModMagic {

    public static void register(IEventBus modEventBus) {
        ModAttachments.register(modEventBus);
        NeoForge.EVENT_BUS.addListener(ModMagic::onAddReloadListeners);
    }

    private static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new SpellManager());
    }
}
