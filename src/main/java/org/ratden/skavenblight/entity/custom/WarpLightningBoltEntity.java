package org.ratden.skavenblight.entity.custom;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

public class WarpLightningBoltEntity extends Entity {
    private static final EntityDataAccessor<Vector3f> TARGET_POS =
            SynchedEntityData.defineId(WarpLightningBoltEntity.class, EntityDataSerializers.VECTOR3);

    private int life;
    public long seed;

    public WarpLightningBoltEntity(EntityType<?> type, Level level) {
        super(type, level);
        this.seed = this.random.nextLong();
        this.life = 5; // Lasts for 5 ticks (1/4th of a second)
    }

    public void setTargetPosition(Vec3 target) {
        this.entityData.set(TARGET_POS, target.toVector3f());
    }

    public Vec3 getTargetPosition() {
        Vector3f vec = this.entityData.get(TARGET_POS);
        return new Vec3(vec.x(), vec.y(), vec.z());
    }

    @Override
    public void tick() {
        super.tick();
        this.life--;
        if (this.life <= 0) {
            this.discard();
        }
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(TARGET_POS, new Vector3f(0, 0, 0));
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        this.life = tag.getInt("Life");
        if (tag.contains("TargetX")) {
            setTargetPosition(new Vec3(tag.getDouble("TargetX"), tag.getDouble("TargetY"), tag.getDouble("TargetZ")));
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putInt("Life", this.life);
        Vec3 target = getTargetPosition();
        tag.putDouble("TargetX", target.x);
        tag.putDouble("TargetY", target.y);
        tag.putDouble("TargetZ", target.z);
    }
}