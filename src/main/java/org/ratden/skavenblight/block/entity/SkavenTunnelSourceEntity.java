package org.ratden.skavenblight.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.UUID;

public class SkavenTunnelSourceEntity extends BlockEntity {
    private UUID sourceId;
    private String parentIncursionName;
    private long createdGameTime;

    public SkavenTunnelSourceEntity(BlockPos pos, BlockState blockState) {
        super(ModBlockEntities.SKAVEN_TUNNEL_SOURCE.get(), pos, blockState);

        this.sourceId = UUID.randomUUID();
        this.parentIncursionName = "unknown";
        this.createdGameTime = -1;
    }

    public UUID getSourceId() {
        return sourceId;
    }

    public String getParentIncursionName() {
        return parentIncursionName;
    }

    public long getCreatedGameTime() {
        return createdGameTime;
    }

    public void setParentIncursionName(String parentIncursionName) {
        this.parentIncursionName = parentIncursionName;
        setChanged();
    }

    public void setCreatedGameTime(long createdGameTime) {
        this.createdGameTime = createdGameTime;
        setChanged();
    }
}