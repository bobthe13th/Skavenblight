package org.ratden.skavenblight.magic.corruption;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;

/**
 * The escape hatch for Chaos Manifestation table entries — mirrors SpellEffect exactly. A new
 * kind of consequence (e.g. a future "summon a Lesser Daemon" entry) needs a new permits-listed
 * case here, not a generic scripting hook.
 */
public sealed interface ChaosManifestationEffect
        permits ChaosManifestationEffect.GrantCorruption, ChaosManifestationEffect.ApplyMobEffect, ChaosManifestationEffect.Damage {

    Codec<ChaosManifestationEffect> CODEC = Codec.STRING.dispatch("type", ChaosManifestationEffect::type, type -> switch (type) {
        case "grant_corruption" -> GrantCorruption.CODEC;
        case "mob_effect" -> ApplyMobEffect.CODEC;
        case "damage" -> Damage.CODEC;
        default -> throw new IllegalArgumentException("Unknown chaos manifestation effect type: " + type);
    });

    void apply(ServerLevel level, LivingEntity target);

    String type();

    record GrantCorruption(int amount) implements ChaosManifestationEffect {
        public static final MapCodec<GrantCorruption> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Codec.INT.fieldOf("amount").forGetter(GrantCorruption::amount)
        ).apply(instance, GrantCorruption::new));

        @Override
        public void apply(ServerLevel level, LivingEntity target) {
            if (target instanceof ServerPlayer player) {
                org.ratden.skavenblight.magic.corruption.Corruption.grant(player, amount);
            }
        }

        @Override
        public String type() {
            return "grant_corruption";
        }
    }

    record ApplyMobEffect(Holder<MobEffect> effect, int durationTicks, int amplifier) implements ChaosManifestationEffect {
        public static final MapCodec<ApplyMobEffect> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                BuiltInRegistries.MOB_EFFECT.holderByNameCodec().fieldOf("effect").forGetter(ApplyMobEffect::effect),
                Codec.INT.fieldOf("duration_ticks").forGetter(ApplyMobEffect::durationTicks),
                Codec.INT.optionalFieldOf("amplifier", 0).forGetter(ApplyMobEffect::amplifier)
        ).apply(instance, ApplyMobEffect::new));

        @Override
        public void apply(ServerLevel level, LivingEntity target) {
            target.addEffect(new MobEffectInstance(effect, durationTicks, amplifier));
        }

        @Override
        public String type() {
            return "mob_effect";
        }
    }

    record Damage(float amount) implements ChaosManifestationEffect {
        public static final MapCodec<Damage> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Codec.FLOAT.fieldOf("amount").forGetter(Damage::amount)
        ).apply(instance, Damage::new));

        @Override
        public void apply(ServerLevel level, LivingEntity target) {
            target.hurt(target.damageSources().magic(), amount);
        }

        @Override
        public String type() {
            return "damage";
        }
    }
}
