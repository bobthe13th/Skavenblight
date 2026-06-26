package org.ratden.skavenblight.entity.custom;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.ratden.skavenblight.event.skavenIncursion.leadership.IncursionOwnedMob;

import java.util.UUID;

public class RatWolf extends Wolf implements IncursionOwnedMob {
    private UUID scenarioId;
    private UUID packId;
    private UUID clawId;
    private UUID fangId;
    private UUID vermintideId;

    public RatWolf(EntityType<? extends Wolf> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Wolf.createAttributes()
                .add(Attributes.MAX_HEALTH, 10.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.5D)
                .add(Attributes.ATTACK_DAMAGE, 3.0D)
                .add(Attributes.FOLLOW_RANGE, 50.0D);
    }

    @Override
    public void setScenarioId(UUID scenarioId) {
        this.scenarioId = scenarioId;
    }

    @Override
    public UUID getScenarioId() {
        return scenarioId;
    }

    @Override
    public void setPackId(UUID packId) {
        this.packId = packId;
    }

    @Override
    public UUID getPackId() {
        return packId;
    }
    @Override
    public void setClawId(UUID clawId) {
        this.clawId = clawId;
    }

    @Override
    public UUID getClawId() {
        return clawId;
    }

    @Override
    public void setFangId(UUID fangId) {
        this.fangId = fangId;
    }

    @Override
    public UUID getFangId() {
        return fangId;
    }

    @Override
    public void setVermintideId(UUID vermintideId) {
        this.vermintideId = vermintideId;
    }

    @Override
    public UUID getVermintideId() {
        return vermintideId;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);

        if (scenarioId != null) {
            tag.putUUID("scenario_id", scenarioId);
        }

        if (packId != null) {
            tag.putUUID("pack_id", packId);
        }
        if (clawId != null) {
            tag.putUUID("claw_id", clawId);
        }

        if (fangId != null) {
            tag.putUUID("fang_id", fangId);
        }

        if (vermintideId != null) {
            tag.putUUID("vermintide_id", vermintideId);
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);

        if (tag.hasUUID("scenario_id")) {
            scenarioId = tag.getUUID("scenario_id");
        }

        if (tag.hasUUID("pack_id")) {
            packId = tag.getUUID("pack_id");
        }
        if (tag.hasUUID("claw_id")) {
            clawId = tag.getUUID("claw_id");
        }

        if (tag.hasUUID("fang_id")) {
            fangId = tag.getUUID("fang_id");
        }

        if (tag.hasUUID("vermintide_id")) {
            vermintideId = tag.getUUID("vermintide_id");
        }
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(1, new FloatGoal(this));
        this.goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.4D, false));
        this.goalSelector.addGoal(3, new AttackBlockGoal(this));
        this.goalSelector.addGoal(4, new WaterAvoidingRandomStrollGoal(this, 1.0D));
        this.goalSelector.addGoal(5, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(6, new RandomLookAroundGoal(this));

        this.targetSelector.addGoal(1, new NearestAttackableTargetGoal<>(this, Player.class, true));
    }
}