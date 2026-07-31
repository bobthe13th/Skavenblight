package org.ratden.skavenblight.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.UUID;

public class SkavenTunnelSourceEntity extends BlockEntity {

    private UUID sourceId;

    private UUID scenarioId;
    private UUID packId;
    private UUID clawId;
    private UUID fangId;
    private UUID vermintideId;

    private long createdGameTime;

    public SkavenTunnelSourceEntity(BlockPos pos, BlockState blockState) {
        super(ModBlockEntities.SKAVEN_TUNNEL_SOURCE.get(), pos, blockState);

        this.sourceId = UUID.randomUUID();

        this.scenarioId = null;
        this.packId = null;
        this.clawId = null;
        this.fangId = null;
        this.vermintideId = null;

        this.createdGameTime = -1;
    }

    public UUID getSourceId() {
        return sourceId;
    }

    public UUID getScenarioId() {
        return scenarioId;
    }

    public UUID getPackId() {
        return packId;
    }

    public UUID getClawId() {
        return clawId;
    }

    public UUID getFangId() {
        return fangId;
    }

    public UUID getVermintideId() {
        return vermintideId;
    }

    public long getCreatedGameTime() {
        return createdGameTime;
    }

    public void setScenarioId(UUID scenarioId) {
        this.scenarioId = scenarioId;
        setChanged();
    }

    public void setPackId(UUID packId) {
        this.packId = packId;
        setChanged();
    }

    public void setClawId(UUID clawId) {
        this.clawId = clawId;
        setChanged();
    }

    public void setFangId(UUID fangId) {
        this.fangId = fangId;
        setChanged();
    }

    public void setVermintideId(UUID vermintideId) {
        this.vermintideId = vermintideId;
        setChanged();
    }

    public void setCreatedGameTime(long createdGameTime) {
        this.createdGameTime = createdGameTime;
        setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);

        if (sourceId != null) {
            tag.putUUID("source_id", sourceId);
        }

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

        tag.putLong("created_game_time", createdGameTime);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);

        if (tag.hasUUID("source_id")) {
            sourceId = tag.getUUID("source_id");
        }

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

        createdGameTime = tag.getLong("created_game_time");
    }
}