package org.ratden.skavenblight.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.ratden.skavenblight.Config;
import org.ratden.skavenblight.magic.corruption.MonolithRegistry;

public class ChaosMonolithBlockEntity extends BlockEntity {

    private int wounds = Config.monolithMaxWounds;
    private int woundsSinceLastSummon = 0;
    private long lastReadGameTime = 0L;
    private boolean hasBeenRead = false;

    public ChaosMonolithBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CHAOS_MONOLITH.get(), pos, state);
    }

    @Override
    public void setLevel(Level level) {
        super.setLevel(level);
        if (level instanceof ServerLevel serverLevel) {
            MonolithRegistry.register(serverLevel, worldPosition);
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level instanceof ServerLevel serverLevel) {
            MonolithRegistry.unregister(serverLevel, worldPosition);
        }
    }

    /** Called from ChaosMonolithBlock.attack(). Returns true if the Monolith was destroyed by this hit. */
    public boolean applyDamage(LivingEntity attacker) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        wounds -= Config.monolithDamagePerHit;
        woundsSinceLastSummon += Config.monolithDamagePerHit;
        setChanged();

        while (woundsSinceLastSummon >= Config.monolithWoundsPerDaemonSummon) {
            woundsSinceLastSummon -= Config.monolithWoundsPerDaemonSummon;
            summonLesserDaemon(serverLevel, attacker);
        }

        if (wounds <= 0) {
            serverLevel.destroyBlock(worldPosition, false);
            return true;
        }
        return false;
    }

    private void summonLesserDaemon(ServerLevel level, LivingEntity attacker) {
        Vex daemon = EntityType.VEX.create(level);
        if (daemon == null) {
            return;
        }
        daemon.moveTo(worldPosition.getX() + 0.5, worldPosition.getY() + 1.0, worldPosition.getZ() + 0.5,
                0.0F, 0.0F);
        daemon.setTarget(attacker);
        level.addFreshEntity(daemon);
    }

    public int getWounds() {
        return wounds;
    }

    /** True if this Monolith's runes were read too recently to be read again. */
    public boolean isReadOnCooldown(long currentGameTime) {
        if (!hasBeenRead) {
            return false;
        }
        return currentGameTime - lastReadGameTime < Config.monolithReadCooldownTicks;
    }

    /** Marks the runes as just having been read (success or resisted), starting the cooldown. */
    public void markRead(long currentGameTime) {
        lastReadGameTime = currentGameTime;
        hasBeenRead = true;
        setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("wounds", wounds);
        tag.putInt("woundsSinceLastSummon", woundsSinceLastSummon);
        tag.putLong("lastReadGameTime", lastReadGameTime);
        tag.putBoolean("hasBeenRead", hasBeenRead);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        wounds = tag.contains("wounds") ? tag.getInt("wounds") : Config.monolithMaxWounds;
        woundsSinceLastSummon = tag.getInt("woundsSinceLastSummon");
        lastReadGameTime = tag.getLong("lastReadGameTime");
        hasBeenRead = tag.getBoolean("hasBeenRead");
    }
}
