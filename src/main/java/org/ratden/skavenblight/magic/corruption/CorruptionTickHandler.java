package org.ratden.skavenblight.magic.corruption;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.ratden.skavenblight.Config;

/**
 * Once every Config.corruptionTickIntervalTicks per player: refresh the Tainted effect to match
 * their current tier, and at Severe+ apply an escalating vanilla debuff (the "intensifies with
 * use" bite the plain Tainted marker doesn't carry on its own).
 */
public final class CorruptionTickHandler {

    private CorruptionTickHandler() {}

    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (player.tickCount % Config.corruptionTickIntervalTicks != 0) {
            return;
        }

        CorruptionTier tier = Corruption.getTier(player);
        int refreshDuration = Config.corruptionTickIntervalTicks + 20;

        if (tier == CorruptionTier.NONE) {
            player.removeEffect(ModMobEffects.TAINTED);
            return;
        }

        player.addEffect(new MobEffectInstance(ModMobEffects.TAINTED, refreshDuration, tier.ordinal() - 1, false, false, true));

        if (tier == CorruptionTier.SEVERE || tier == CorruptionTier.CATASTROPHIC) {
            // MobEffects.CONFUSION is Nausea's actual field name in these mappings (registry id "nausea").
            player.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 100, 0));
        }
        if (tier == CorruptionTier.CATASTROPHIC) {
            player.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 100, 0));
        }
    }
}
