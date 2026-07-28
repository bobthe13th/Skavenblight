package org.ratden.skavenblight.magic.player;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.ratden.skavenblight.Skavenblight;

public class ModAttachments {

    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, Skavenblight.MODID);

    public static final DeferredHolder<AttachmentType<?>, AttachmentType<PlayerMagicData>> PLAYER_MAGIC =
            ATTACHMENT_TYPES.register(
                    "player_magic",
                    () -> AttachmentType.builder(() -> PlayerMagicData.EMPTY)
                            .serialize(PlayerMagicData.CODEC)
                            .build()
            );

    public static void register(IEventBus modEventBus) {
        ATTACHMENT_TYPES.register(modEventBus);
    }
}
