package org.ratden.skavenblight.magic.corruption;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;

/**
 * Purely a visual/tracking marker of corruption tier (amplifier = tier ordinal, refreshed by
 * CorruptionTickHandler). The actual gameplay bite at higher tiers comes from vanilla effects
 * (Nausea, Weakness) the tick handler also applies — this effect itself has no tick behavior.
 */
public class TaintedMobEffect extends MobEffect {
    public TaintedMobEffect() {
        super(MobEffectCategory.HARMFUL, 0x5B2E73);
    }
}
