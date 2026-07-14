package org.ratden.skavenblight.entity.custom;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import org.ratden.skavenblight.ai.goal.SmartBreachGoal;
import org.ratden.skavenblight.ai.goal.BuildFlowFieldGoal;
import org.ratden.skavenblight.ai.goal.WidenStairsGoal;
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
import org.ratden.skavenblight.network.WarpFluxGridManager;
import org.ratden.skavenblight.network.WarpFluxNetwork;
import org.ratden.skavenblight.block.entity.WarpstoneNexusEntity;

public class ClanratEntity extends Monster implements GeoEntity {
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    private StandardFlowField currentFlowField = null;
    private int territoryCheckCooldown = 0;

    protected static final RawAnimation IDLE = RawAnimation.begin().thenLoop("animation.clanrat.idle");
    protected static final RawAnimation WALK = RawAnimation.begin().thenLoop("animation.clanrat.walk");
    protected static final RawAnimation FALL = RawAnimation.begin().thenLoop("animation.clanrat.fall");
    protected static final RawAnimation CLIMB = RawAnimation.begin().thenLoop("animation.clanrat.climb");

    public ClanratEntity(EntityType<? extends Monster> type, Level level) {
        super(type, level);
    }

    @Override
    protected void registerGoals() {
        super.registerGoals();

        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new MeleeAttackGoal(this, 1.2D, false));
        this.goalSelector.addGoal(2, new SmartBreachGoal(this));
        this.goalSelector.addGoal(3, new BuildFlowFieldGoal(this));
        this.goalSelector.addGoal(4, new WidenStairsGoal(this));
        this.goalSelector.addGoal(5, new FollowFlowFieldGoal(this, 1.2D));
        this.goalSelector.addGoal(7, new WaterAvoidingRandomStrollGoal(this, 1.0D));
        this.goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();

        if (this.currentFlowField == null && --this.territoryCheckCooldown <= 0) {
            this.territoryCheckCooldown = 40;

            if (this.level() instanceof ServerLevel serverLevel) {
                WarpFluxGridManager gridManager = WarpFluxGridManager.get(serverLevel);
                ChunkPos currentChunk = this.chunkPosition();

                WarpFluxNetwork closestNetwork = null;
                double closestDist = Double.MAX_VALUE;

                for (WarpFluxNetwork network : gridManager.getAllNetworks()) {
                    // If we spawned inside a territory, lock on immediately and break
                    if (network.getTerritoryChunks().contains(currentChunk)) {
                        closestNetwork = network;
                        break;
                    }

                    // Otherwise, find the closest base by checking distance to its Nexus endpoints
                    for (BlockPos endpoint : network.getEndpoints()) {
                        double dist = this.blockPosition().distSqr(endpoint);
                        if (dist < closestDist) {
                            closestDist = dist;
                            closestNetwork = network;
                        }
                    }
                }

                // Assign the flow field from the network we selected
                if (closestNetwork != null) {
                    BlockPos activeNexus = null;

                    for (BlockPos endpoint : closestNetwork.getEndpoints()) {
                        if (serverLevel.getBlockEntity(endpoint) instanceof WarpstoneNexusEntity) {
                            activeNexus = endpoint;
                            break;
                        }
                    }

                    if (activeNexus != null) {
                        StandardFlowField sharedField = closestNetwork.getSharedFlowField(activeNexus);
                        this.assignFlowField(sharedField);
                    }
                }
            }
        }
    }

    public void assignFlowField(StandardFlowField field) {
        this.currentFlowField = field;
        this.goalSelector.getAvailableGoals().forEach(wrappedGoal -> {
            if (wrappedGoal.getGoal() instanceof FollowFlowFieldGoal flowGoal) {
                flowGoal.setFlowField(field);
            } else if (wrappedGoal.getGoal() instanceof SmartBreachGoal breachGoal) {
                breachGoal.setFlowField(field);
            } else if (wrappedGoal.getGoal() instanceof BuildFlowFieldGoal buildGoal) {
                buildGoal.setFlowField(field);
            } else if (wrappedGoal.getGoal() instanceof WidenStairsGoal widenGoal) {
                widenGoal.setFlowField(field);
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