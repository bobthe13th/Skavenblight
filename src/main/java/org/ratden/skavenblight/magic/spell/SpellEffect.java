package org.ratden.skavenblight.magic.spell;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * The escape hatch: 90% of spells are fully data-driven (Spell is a plain record), but the actual
 * game-world effect a spell has needs real code. Keep this a small sealed interface with a handful of
 * concrete cases, NOT a scripting language — a spell that needs a shape not listed here needs a new
 * case added here, not a generic "run this arbitrary logic" case.
 */
public sealed interface SpellEffect permits
        SpellEffect.DamageEffect,
        SpellEffect.MobEffectApply,
        SpellEffect.AreaMobEffectApply,
        SpellEffect.HealCasterDamageEffect,
        SpellEffect.DurabilityDamageEffect,
        SpellEffect.LightningStrikeEffect,
        SpellEffect.ProjectileLaunchEffect,
        SpellEffect.SweepingConeDamageEffect,
        SpellEffect.VectorPushEffect,
        SpellEffect.ConditionalDamageEffect,
        SpellEffect.ExecuteDamageEffect {

    Codec<SpellEffect> CODEC = Codec.STRING.dispatch("type", SpellEffect::type, type -> switch (type) {
        case "damage" -> DamageEffect.CODEC;
        case "mob_effect" -> MobEffectApply.CODEC;
        case "area_mob_effect" -> AreaMobEffectApply.CODEC;
        case "heal_caster_damage" -> HealCasterDamageEffect.CODEC;
        case "durability_damage" -> DurabilityDamageEffect.CODEC;
        case "lightning_strike" -> LightningStrikeEffect.CODEC;
        case "projectile_launch" -> ProjectileLaunchEffect.CODEC;
        case "sweeping_cone_damage" -> SweepingConeDamageEffect.CODEC;
        case "vector_push" -> VectorPushEffect.CODEC;
        case "conditional_damage" -> ConditionalDamageEffect.CODEC;
        case "execute_damage" -> ExecuteDamageEffect.CODEC;
        default -> throw new IllegalArgumentException("Unknown spell effect type: " + type);
    });

    void apply(LivingEntity caster, LivingEntity target);

    String type();

    record DamageEffect(float amount) implements SpellEffect {
        public static final MapCodec<DamageEffect> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Codec.FLOAT.fieldOf("amount").forGetter(DamageEffect::amount)
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
                Codec.INT.fieldOf("duration_ticks").forGetter(MobEffectApply::durationTicks),
                Codec.INT.optionalFieldOf("amplifier", 0).forGetter(MobEffectApply::amplifier)
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

    record AreaMobEffectApply(Holder<MobEffect> effect, int durationTicks, int amplifier, float radius) implements SpellEffect {
        public static final MapCodec<AreaMobEffectApply> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                BuiltInRegistries.MOB_EFFECT.holderByNameCodec().fieldOf("effect").forGetter(AreaMobEffectApply::effect),
                Codec.INT.fieldOf("duration_ticks").forGetter(AreaMobEffectApply::durationTicks),
                Codec.INT.optionalFieldOf("amplifier", 0).forGetter(AreaMobEffectApply::amplifier),
                Codec.FLOAT.fieldOf("radius").forGetter(AreaMobEffectApply::radius)
        ).apply(instance, AreaMobEffectApply::new));

        @Override
        public void apply(LivingEntity caster, LivingEntity target) {
            target.level().getEntitiesOfClass(LivingEntity.class, target.getBoundingBox().inflate(radius)).forEach(entity -> {
                entity.addEffect(new MobEffectInstance(effect, durationTicks, amplifier));
            });
        }

        @Override
        public String type() {
            return "area_mob_effect";
        }
    }

    record HealCasterDamageEffect(float damageAmount, float healFraction) implements SpellEffect {
        public static final MapCodec<HealCasterDamageEffect> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Codec.FLOAT.fieldOf("damage_amount").forGetter(HealCasterDamageEffect::damageAmount),
                Codec.FLOAT.fieldOf("heal_fraction").forGetter(HealCasterDamageEffect::healFraction)
        ).apply(instance, HealCasterDamageEffect::new));

        @Override
        public void apply(LivingEntity caster, LivingEntity target) {
            target.hurt(target.damageSources().magic(), damageAmount);
            caster.heal(damageAmount * healFraction);
        }

        @Override
        public String type() {
            return "heal_caster_damage";
        }
    }

    record DurabilityDamageEffect(int damageAmount) implements SpellEffect {
        public static final MapCodec<DurabilityDamageEffect> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Codec.INT.fieldOf("damage_amount").forGetter(DurabilityDamageEffect::damageAmount)
        ).apply(instance, DurabilityDamageEffect::new));

        @Override
        public void apply(LivingEntity caster, LivingEntity target) {
            target.getHandSlots().forEach(stack -> {
                if (stack.isDamageableItem()) {
                    stack.setDamageValue(Math.min(stack.getMaxDamage(), stack.getDamageValue() + damageAmount));
                }
            });
            target.getArmorSlots().forEach(stack -> {
                if (stack.isDamageableItem()) {
                    stack.setDamageValue(Math.min(stack.getMaxDamage(), stack.getDamageValue() + damageAmount));
                }
            });
        }

        @Override
        public String type() {
            return "durability_damage";
        }
    }

    record LightningStrikeEffect(float amount) implements SpellEffect {
        public static final MapCodec<LightningStrikeEffect> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Codec.FLOAT.fieldOf("amount").forGetter(LightningStrikeEffect::amount)
        ).apply(instance, LightningStrikeEffect::new));

        @Override
        public void apply(LivingEntity caster, LivingEntity target) {
            net.minecraft.world.entity.LightningBolt bolt = net.minecraft.world.entity.EntityType.LIGHTNING_BOLT.create(target.level());
            if (bolt != null) {
                bolt.moveTo(target.position());
                target.level().addFreshEntity(bolt);
            }
            target.hurt(target.damageSources().magic(), amount);
        }

        @Override
        public String type() {
            return "lightning_strike";
        }
    }

    record ProjectileLaunchEffect(String projectileType, float speed) implements SpellEffect {
        public static final MapCodec<ProjectileLaunchEffect> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Codec.STRING.fieldOf("projectile_type").forGetter(ProjectileLaunchEffect::projectileType),
                Codec.FLOAT.fieldOf("speed").forGetter(ProjectileLaunchEffect::speed)
        ).apply(instance, ProjectileLaunchEffect::new));

        @Override
        public void apply(LivingEntity caster, LivingEntity target) {
            Vec3 dir = caster.getLookAngle().scale(speed);
            if (projectileType.equals("fireball")) {
                net.minecraft.world.entity.projectile.LargeFireball fireball = new net.minecraft.world.entity.projectile.LargeFireball(caster.level(), caster, dir, 1);
                fireball.setPos(caster.getX(), caster.getEyeY(), caster.getZ());
                caster.level().addFreshEntity(fireball);
            } else if (projectileType.equals("small_fireball")) {
                net.minecraft.world.entity.projectile.SmallFireball fireball = new net.minecraft.world.entity.projectile.SmallFireball(caster.level(), caster, dir);
                fireball.setPos(caster.getX(), caster.getEyeY(), caster.getZ());
                caster.level().addFreshEntity(fireball);
            } else {
                net.minecraft.world.entity.projectile.Arrow arrow = new net.minecraft.world.entity.projectile.Arrow(caster.level(), caster, new ItemStack(net.minecraft.world.item.Items.ARROW), null);
                arrow.shootFromRotation(caster, caster.getXRot(), caster.getYRot(), 0.0F, speed, 1.0F);
                caster.level().addFreshEntity(arrow);
            }
        }

        @Override
        public String type() {
            return "projectile_launch";
        }
    }

    record SweepingConeDamageEffect(float amount, float radius, float angleDegrees) implements SpellEffect {
        public static final MapCodec<SweepingConeDamageEffect> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Codec.FLOAT.fieldOf("amount").forGetter(SweepingConeDamageEffect::amount),
                Codec.FLOAT.fieldOf("radius").forGetter(SweepingConeDamageEffect::radius),
                Codec.FLOAT.fieldOf("angle_degrees").forGetter(SweepingConeDamageEffect::angleDegrees)
        ).apply(instance, SweepingConeDamageEffect::new));

        @Override
        public void apply(LivingEntity caster, LivingEntity target) {
            Vec3 look = caster.getLookAngle().normalize();
            caster.level().getEntitiesOfClass(LivingEntity.class, caster.getBoundingBox().inflate(radius)).forEach(entity -> {
                if (entity != caster) {
                    Vec3 toEntity = entity.position().subtract(caster.position()).normalize();
                    double dot = look.dot(toEntity);
                    double angle = Math.toDegrees(Math.acos(dot));
                    if (angle <= angleDegrees / 2.0) {
                        entity.hurt(entity.damageSources().magic(), amount);
                    }
                }
            });
        }

        @Override
        public String type() {
            return "sweeping_cone_damage";
        }
    }

    record VectorPushEffect(float strength) implements SpellEffect {
        public static final MapCodec<VectorPushEffect> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Codec.FLOAT.fieldOf("strength").forGetter(VectorPushEffect::strength)
        ).apply(instance, VectorPushEffect::new));

        @Override
        public void apply(LivingEntity caster, LivingEntity target) {
            Vec3 pushDir = target.position().subtract(caster.position()).normalize().scale(strength);
            target.setDeltaMovement(target.getDeltaMovement().add(pushDir));
            target.hurtMarked = true;
        }

        @Override
        public String type() {
            return "vector_push";
        }
    }

    record ConditionalDamageEffect(float baseAmount, float extraAmount, String groupName) implements SpellEffect {
        public static final MapCodec<ConditionalDamageEffect> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Codec.FLOAT.fieldOf("base_amount").forGetter(ConditionalDamageEffect::baseAmount),
                Codec.FLOAT.fieldOf("extra_amount").forGetter(ConditionalDamageEffect::extraAmount),
                Codec.STRING.fieldOf("group_name").forGetter(ConditionalDamageEffect::groupName)
        ).apply(instance, ConditionalDamageEffect::new));

        @Override
        public void apply(LivingEntity caster, LivingEntity target) {
            float finalDamage = baseAmount;
            if (groupName.equals("undead") && target.isInvertedHealAndHarm()) {
                finalDamage += extraAmount;
            }
            target.hurt(target.damageSources().magic(), finalDamage);
        }

        @Override
        public String type() {
            return "conditional_damage";
        }
    }

    record ExecuteDamageEffect(float baseAmount, float executeAmount, float thresholdFraction) implements SpellEffect {
        public static final MapCodec<ExecuteDamageEffect> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Codec.FLOAT.fieldOf("base_amount").forGetter(ExecuteDamageEffect::baseAmount),
                Codec.FLOAT.fieldOf("execute_amount").forGetter(ExecuteDamageEffect::executeAmount),
                Codec.FLOAT.fieldOf("threshold_fraction").forGetter(ExecuteDamageEffect::thresholdFraction)
        ).apply(instance, ExecuteDamageEffect::new));

        @Override
        public void apply(LivingEntity caster, LivingEntity target) {
            float finalDamage = baseAmount;
            if (target.getHealth() / target.getMaxHealth() <= thresholdFraction) {
                finalDamage += executeAmount;
            }
            target.hurt(target.damageSources().magic(), finalDamage);
        }

        @Override
        public String type() {
            return "execute_damage";
        }
    }
}
