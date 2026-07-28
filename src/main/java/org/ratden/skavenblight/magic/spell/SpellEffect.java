package org.ratden.skavenblight.magic.spell;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;

/**
 * The escape hatch: 90% of spells are fully data-driven (Spell is a plain record), but the actual
 * game-world effect a spell has needs real code. Keep this a small sealed interface with a handful of
 * concrete cases, NOT a scripting language — a spell that needs a shape not listed here needs a new
 * case added here, not a generic "run this arbitrary logic" case.
 */
public sealed interface SpellEffect permits SpellEffect.DamageEffect, SpellEffect.MobEffectApply {

    Codec<SpellEffect> CODEC = Codec.STRING.dispatch("type", SpellEffect::type, type -> switch (type) {
        case "damage" -> DamageEffect.CODEC;
        case "mob_effect" -> MobEffectApply.CODEC;
        default -> throw new IllegalArgumentException("Unknown spell effect type: " + type);
    });

    void apply(LivingEntity caster, LivingEntity target);

    String type();

    record DamageEffect(float amount) implements SpellEffect {
        public static final MapCodec<DamageEffect> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                com.mojang.serialization.Codec.FLOAT.fieldOf("amount").forGetter(DamageEffect::amount)
        ).apply(instance, DamageEffect::new));

        @Override
        public void apply(LivingEntity caster, LivingEntity target) {
            target.hurt(target.damageSources().magic(), amount);
        }

        @Override
        public String type() {
            return "damage";
        }
    }

    record MobEffectApply(Holder<MobEffect> effect, int durationTicks, int amplifier) implements SpellEffect {
        public static final MapCodec<MobEffectApply> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                BuiltInRegistries.MOB_EFFECT.holderByNameCodec().fieldOf("effect").forGetter(MobEffectApply::effect),
                com.mojang.serialization.Codec.INT.fieldOf("duration_ticks").forGetter(MobEffectApply::durationTicks),
                com.mojang.serialization.Codec.INT.optionalFieldOf("amplifier", 0).forGetter(MobEffectApply::amplifier)
        ).apply(instance, MobEffectApply::new));

        @Override
        public void apply(LivingEntity caster, LivingEntity target) {
            target.addEffect(new MobEffectInstance(effect, durationTicks, amplifier));
        }

        @Override
        public String type() {
            return "mob_effect";
        }
    }
}
