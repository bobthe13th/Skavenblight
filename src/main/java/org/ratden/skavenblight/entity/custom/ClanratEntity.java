package org.ratden.skavenblight.entity.custom;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.util.GeckoLibUtil;

public class ClanratEntity extends Monster implements GeoEntity {
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    // References to our Blockbench animation keys
    protected static final RawAnimation IDLE = RawAnimation.begin().thenLoop("animation.clanrat.idle");
    protected static final RawAnimation WALK = RawAnimation.begin().thenLoop("animation.clanrat.walk");
    protected static final RawAnimation FALL = RawAnimation.begin().thenLoop("animation.clanrat.fall");
    protected static final RawAnimation CLIMB = RawAnimation.begin().thenLoop("animation.clanrat.climb");

    public ClanratEntity(EntityType<? extends Monster> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 20.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.3D)
                .add(Attributes.ATTACK_DAMAGE, 3.0D);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "MovementController", 4, event -> {
            if (this.onClimbable()) {
                return event.setAndContinue(CLIMB);
            }
            if (this.getDeltaMovement().y < -0.15 && !this.onGround()) {
                return event.setAndContinue(FALL);
            }
            if (event.isMoving()) {
                return event.setAndContinue(WALK);
            }
            return event.setAndContinue(IDLE);
        }));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }
}