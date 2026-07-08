package org.ratden.skavenblight.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import org.ratden.skavenblight.capability.custom.IWarpFluxStorage;
import org.ratden.skavenblight.capability.custom.WarpFluxStorage;
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.List;

public class PistonSpikeTrapBlockEntity extends BlockEntity implements GeoBlockEntity {
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    // --- FLUX BUFFER & CONFIG VALUES ---
    // (You will link these to your Config file later!)
    private final WarpFluxStorage fluxStorage = new WarpFluxStorage(1000, 100, 100);
    public boolean isThrusting = false;
    public int damageDelayTicks = 0;
    private int cooldown = 0;

    private final int FLUX_COST = 50;
    private final int MAX_RANGE = 3;
    private final int COOLDOWN_TICKS = 40; // 2 seconds
    private final float DAMAGE = 8.0f;
    private final double KNOCKBACK_STRENGTH = 1.2;

    // --- ANIMATIONS ---
    private static final RawAnimation THRUST_ANIM = RawAnimation.begin().thenPlay("animation.spike_trap.thrust");
    private static final RawAnimation RETRACT_ANIM = RawAnimation.begin().thenPlay("animation.spike_trap.retract");
    private static final RawAnimation IDLE_ANIM = RawAnimation.begin().thenLoop("animation.spike_trap.idle");

    public PistonSpikeTrapBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.PISTON_SPIKE_TRAP.get(), pos, state);
    }

    public IWarpFluxStorage getFluxStorage() {
        return this.fluxStorage;
    }

    // --- MAIN TICK LOGIC ---
    public static void tick(Level level, BlockPos pos, BlockState state, PistonSpikeTrapBlockEntity entity) {
        // Decrease the animation delay timer on BOTH the Server and the Client!
        if (entity.damageDelayTicks > 0) {
            entity.damageDelayTicks--;
        }

        // NOW we can stop the client from running the damage and power logic
        if (level.isClientSide()) return;

        // Handle Server Cooldown & Retraction logic
        if (entity.cooldown > 0) {
            entity.cooldown--;
            // Retract exactly when cooldown finishes
            if (entity.cooldown == 0 && entity.isThrusting) {
                entity.isThrusting = false;
                entity.sendUpdate(); // Tell client to switch back to idle
            }
            return;
        }

        // Check if we have enough power to fire
        if (entity.fluxStorage.getFlux() < entity.FLUX_COST) return;

        // Calculate reach and create detection hitbox
        Direction facing = state.getValue(BlockStateProperties.FACING);
        int reach = entity.getActualReach(level, pos, facing, entity.MAX_RANGE);
        AABB damageBox = entity.getDamageBox(pos, facing, reach);

        // Find hostile mobs (Enemy interface covers zombies, skeletons, creepers, etc.)
        List<LivingEntity> targets = level.getEntitiesOfClass(LivingEntity.class, damageBox,
                e -> e instanceof Enemy && e.isAlive());

        // If we found something to stab...
        if (!targets.isEmpty()) {
            // 1. Consume Power & Set Cooldown
            entity.fluxStorage.extractFlux(entity.FLUX_COST, false);
            entity.cooldown = entity.COOLDOWN_TICKS;
            entity.isThrusting = true;
            entity.damageDelayTicks = 5; // Roughly matches the 0.25s thrust animation

            // 2. Apply Damage & Knockback
            for (LivingEntity target : targets) {
                target.hurt(level.damageSources().generic(), entity.DAMAGE);

                target.knockback(entity.KNOCKBACK_STRENGTH, -facing.getStepX(), -facing.getStepZ());
                // Manual vertical knockback adjustments
                if (facing == Direction.UP) target.setDeltaMovement(target.getDeltaMovement().add(0, entity.KNOCKBACK_STRENGTH, 0));
                if (facing == Direction.DOWN) target.setDeltaMovement(target.getDeltaMovement().add(0, -entity.KNOCKBACK_STRENGTH, 0));
            }

            // 3. Play Sound & Sync Client
            level.playSound(null, pos, SoundEvents.PISTON_EXTEND, SoundSource.BLOCKS, 1.0F, 1.2F);
            entity.sendUpdate(); // Triggers the client-side animation change
        }
    }

    // Calculates how far out it can shoot before hitting a block
    private int getActualReach(Level level, BlockPos pos, Direction facing, int maxRange) {
        for (int i = 1; i <= maxRange; i++) {
            BlockPos checkPos = pos.relative(facing, i);
            if (!level.getBlockState(checkPos).isAir()) {
                return i - 1; // Blocked! Return the distance just before the wall
            }
        }
        return maxRange;
    }

    // Creates the invisible hitbox used to detect mobs
    private AABB getDamageBox(BlockPos pos, Direction facing, int reach) {
        BlockPos start = pos.relative(facing, 1);
        BlockPos end = pos.relative(facing, reach);
        return new AABB(start).minmax(new AABB(end)).expandTowards(1, 1, 1);
    }

    // --- GECKOLIB CONTROLLERS ---
    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "controller", 0, event -> {
            if (this.isThrusting) {
                if (this.damageDelayTicks > 0) {
                    return event.setAndContinue(THRUST_ANIM);
                } else {
                    return event.setAndContinue(RETRACT_ANIM);
                }
            }
            return event.setAndContinue(IDLE_ANIM);
        }));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }

    // --- NBT SAVING & SYNCING ---
    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.saveAdditional(tag, provider);
        tag.putInt("cooldown", this.cooldown);
        tag.putBoolean("isThrusting", this.isThrusting);
        tag.putInt("flux", this.fluxStorage.getFlux());
        tag.putInt("damageDelayTicks", this.damageDelayTicks);
    }

    @Override
    public void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.loadAdditional(tag, provider);
        this.cooldown = tag.getInt("cooldown");
        this.isThrusting = tag.getBoolean("isThrusting");
        this.damageDelayTicks = tag.getInt("damageDelayTicks");
        // (You may need to add a helper method in your fluxStorage to parse NBT or set the flux directly here!)
    }

    private void sendUpdate() {
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider provider) {
        CompoundTag tag = super.getUpdateTag(provider);
        tag.putBoolean("isThrusting", this.isThrusting);
        tag.putInt("damageDelayTicks", this.damageDelayTicks);
        return tag;
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider provider) {
        super.handleUpdateTag(tag, provider);
        this.isThrusting = tag.getBoolean("isThrusting");
        this.damageDelayTicks = tag.getInt("damageDelayTicks");
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}