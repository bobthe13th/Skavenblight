package org.ratden.skavenblight.magic.corruption;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.ratden.skavenblight.Config;
import org.ratden.skavenblight.item.ModItems;

/**
 * Once every Config.corruptionTickIntervalTicks per player: refresh the Tainted effect to match
 * their current tier (applying an escalating vanilla debuff at Severe+), and drain a trickle of
 * Corruption if they're carrying a Tome of Corruption.
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

        if (isCarryingTome(player)) {
            Corruption.grant(player, Config.tomeCarryCorruptionPerCheck);
        }

        CorruptionTier tier = Corruption.getTier(player);
        int refreshDuration = Config.corruptionTickIntervalTicks + 20;

        if (tier == CorruptionTier.NONE) {
            player.removeEffect(ModMobEffects.TAINTED);
            return;
        }

        player.addEffect(new MobEffectInstance(ModMobEffects.TAINTED, refreshDuration, tier.ordinal() - 1, false, false, true));

        if (tier == CorruptionTier.SEVERE || tier == CorruptionTier.CATASTROPHIC) {
            // MobEffects.NAUSEA doesn't exist in this mapping set — the compiling field name
            // is MobEffects.CONFUSION (registry id "minecraft:nausea"), confirmed against the
            // decompiled NeoForge sources during Task 4.
            player.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 100, 0));
        }
        if (tier == CorruptionTier.CATASTROPHIC) {
            player.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 100, 0));
        }
    }

    private static boolean isCarryingTome(ServerPlayer player) {
        if (player.getOffhandItem().is(ModItems.TOME_OF_CORRUPTION.get())) {
            return true;
        }
        for (ItemStack stack : player.getInventory().items) {
            if (stack.is(ModItems.TOME_OF_CORRUPTION.get())) {
                return true;
            }
        }
        return false;
    }
}
