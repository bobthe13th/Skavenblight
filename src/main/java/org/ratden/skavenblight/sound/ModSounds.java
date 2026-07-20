package org.ratden.skavenblight.sound;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.ratden.skavenblight.Skavenblight;

/**
 * Registers the SoundEvents added by Skavenblight.
 *
 * SoundEvents provide the registered identities used by Java code.
 * Their audio files and playback definitions remain configured separately
 * in assets/skavenblight/sounds.json.
 */
public final class ModSounds {

    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(
                    Registries.SOUND_EVENT,
                    Skavenblight.MODID
            );

    public static final DeferredHolder<
            SoundEvent,
            SoundEvent
            > NEXUS_SPEED =
            registerSoundEvent(
                    "nexus_speed"
            );

    public static final DeferredHolder<
            SoundEvent,
            SoundEvent
            > GAME_OVER =
            registerSoundEvent(
                    "game_over"
            );

    private static DeferredHolder<
            SoundEvent,
            SoundEvent
            > registerSoundEvent(
            String name
    ) {
        ResourceLocation soundId =
                ResourceLocation.fromNamespaceAndPath(
                        Skavenblight.MODID,
                        name
                );

        return SOUND_EVENTS.register(
                name,
                () -> SoundEvent.createVariableRangeEvent(
                        soundId
                )
        );
    }

    public static void register(IEventBus eventBus) {
        SOUND_EVENTS.register(eventBus);
    }

    private ModSounds() {
    }
}