package org.ratden.skavenblight.block.entity;

import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.ratden.skavenblight.Config;
import org.ratden.skavenblight.capability.custom.WarpFluxStorage;
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.util.GeckoLibUtil;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;

public class WarpLightningCoilBlockEntity extends BlockEntity implements GeoBlockEntity {

    private int cooldown = 0;

    private final WarpFluxStorage fluxStorage = new WarpFluxStorage(Config.wlCoilCapacity, 100, 0);

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    public WarpLightningCoilBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.WARP_LIGHTNING_COIL_BE.get(), pos, state);
    }

    public WarpFluxStorage getFluxStorage() {
        return this.fluxStorage;
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "controller", 0, event -> PlayState.CONTINUE));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }

    public void tick(Level level, BlockPos pos, BlockState state) {
        if (level.isClientSide) return;

        if (cooldown > 0) {
            cooldown--;
            return;
        }

        if (this.fluxStorage.getFlux() >= Config.wlCoilCostPerShot) {
            fireChainLightning(level, pos);
            setChanged();
        }
    }

    private void fireChainLightning(Level level, BlockPos pos) {
        double range = Config.wlCoilRange;
        AABB searchArea = new AABB(pos).inflate(range);

        List<LivingEntity> hostiles = level.getEntitiesOfClass(LivingEntity.class, searchArea,
                entity -> entity instanceof Enemy && entity.isAlive());

        if (hostiles.isEmpty()) return;

        hostiles.sort(Comparator.comparingDouble(e -> e.distanceToSqr(pos.getX(), pos.getY(), pos.getZ())));

        List<LivingEntity> hitTargets = new ArrayList<>();
        LivingEntity currentTarget = hostiles.get(0);
        hitTargets.add(currentTarget);

        for (int i = 0; i < Config.wlCoilChainCount; i++) {
            LivingEntity nextTarget = null;
            double closestDistSq = Config.wlCoilChainRange * Config.wlCoilChainRange;

            for (LivingEntity potentialTarget : hostiles) {
                if (hitTargets.contains(potentialTarget)) continue;

                double distSq = currentTarget.distanceToSqr(potentialTarget);

                // Ignore mobs standing right inside each other (< 0.5 blocks apart)
                if (distSq < 0.25) continue;

                if (distSq < closestDistSq) {
                    closestDistSq = distSq;
                    nextTarget = potentialTarget;
                }
            }

            if (nextTarget == null) break;
            hitTargets.add(nextTarget);
            currentTarget = nextTarget;
        }

        for (LivingEntity victim : hitTargets) {
            victim.hurt(level.damageSources().magic(), (float) Config.wlCoilDamage);
            victim.addEffect(new MobEffectInstance(MobEffects.POISON, Config.wlCoilPoisonTicks, 1));
            victim.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, Config.wlCoilSlowTicks, 5));
        }

        this.fluxStorage.extractFlux(Config.wlCoilCostPerShot, false);
        this.cooldown = 5;

        // Spawn the custom green lightning entities
        for (int i = 0; i < hitTargets.size(); i++) {
            Vec3 start;
            if (i == 0) {
                // Shift the start position upward to the top/head of the coil
                start = pos.getCenter().add(0, 2.75, 0);
            } else {
                // Subsequent bolts chain from the previous mob
                start = hitTargets.get(i - 1).position().add(0, hitTargets.get(i - 1).getBbHeight() / 2.0, 0);
            }

            Vec3 target = hitTargets.get(i).position().add(0, hitTargets.get(i).getBbHeight() / 2.0, 0);

            org.ratden.skavenblight.entity.custom.WarpLightningBoltEntity bolt =
                    new org.ratden.skavenblight.entity.custom.WarpLightningBoltEntity(org.ratden.skavenblight.entity.ModEntities.WARP_LIGHTNING_BOLT.get(), level);

            bolt.moveTo(start.x, start.y, start.z);
            bolt.setTargetPosition(target);
            level.addFreshEntity(bolt);
        }
    }
    @Override
    public void onLoad() {
        super.onLoad();
        if (this.getLevel() != null && !this.getLevel().isClientSide() && this.getLevel() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            org.ratden.skavenblight.network.WarpFluxGridManager manager =
                    org.ratden.skavenblight.network.WarpFluxGridManager.get(serverLevel);

            for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
                net.minecraft.core.BlockPos neighborPos = this.getBlockPos().relative(dir);
                org.ratden.skavenblight.network.WarpFluxNetwork network = manager.getNetworkAt(neighborPos);

                if (network != null) {
                    network.scanForEndpoints(serverLevel);
                    manager.setDirty();
                }
            }
        }
    }

    @Override
    public void setRemoved() {
        if (this.getLevel() != null && !this.getLevel().isClientSide() && this.getLevel() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            // Prevent running world modifications during server shutdown
            if (serverLevel.getServer().isRunning()) {
                org.ratden.skavenblight.network.WarpFluxGridManager manager =
                        org.ratden.skavenblight.network.WarpFluxGridManager.get(serverLevel);

                for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
                    net.minecraft.core.BlockPos neighborPos = this.getBlockPos().relative(dir);
                    org.ratden.skavenblight.network.WarpFluxNetwork network = manager.getNetworkAt(neighborPos);

                    if (network != null) {
                        network.getEndpoints().remove(this.getBlockPos());
                        manager.setDirty();
                    }
                }
            }
        }
        super.setRemoved();
    }
}