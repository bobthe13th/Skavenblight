package org.ratden.skavenblight.entity.custom;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import org.ratden.skavenblight.ai.goal.SmartBreachGoal;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;
import org.ratden.skavenblight.ai.goal.FollowFlowFieldGoal;
import org.ratden.skavenblight.ai.pathing.StandardFlowField;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.player.Player;

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

    // --- NEW: AI GOAL REGISTRATION ---
    @Override
    protected void registerGoals() {
        super.registerGoals();

        // 1. Core Survival Goals (Priority 0-1)
        this.goalSelector.addGoal(0, new FloatGoal(this)); // Swim if in water
        this.goalSelector.addGoal(1, new MeleeAttackGoal(this, 1.2D, false)); // Attack if close to target

        // 2. Custom Flow Field Goal (Priority 2)
        // High priority so it overrides wandering when assigned during an incursion.
        this.goalSelector.addGoal(2, new SmartBreachGoal(this));
        this.goalSelector.addGoal(2, new FollowFlowFieldGoal(this, 1.2D));

        // 3. Fallback Vanilla Goals (Priority 7-8)
        // If the rat spawns naturally (not in a raid), it will just wander around.
        this.goalSelector.addGoal(7, new WaterAvoidingRandomStrollGoal(this, 1.0D));
        this.goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));
    }

    // --- INCURSION MANAGER HELPER ---
    public void assignFlowField(StandardFlowField field) {
        this.goalSelector.getAvailableGoals().forEach(wrappedGoal -> {
            if (wrappedGoal.getGoal() instanceof FollowFlowFieldGoal flowGoal) {
                flowGoal.setFlowField(field);
            } else if (wrappedGoal.getGoal() instanceof SmartBreachGoal breachGoal) {
                breachGoal.setFlowField(field);
            }
        });
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