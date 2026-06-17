package org.ratden.skavenblight.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;

public class SkavenblightWorldData extends SavedData {
    public static final String DATA_NAME = "skavenblight_world_data";

    private boolean hasActiveNexus;
    private BlockPos activeNexusPos;

    public SkavenblightWorldData() {
        this.hasActiveNexus = false;
        this.activeNexusPos = BlockPos.ZERO;
    }

    public static SkavenblightWorldData load(
            CompoundTag tag,
            HolderLookup.Provider registries
    ) {
        SkavenblightWorldData data = new SkavenblightWorldData();

        data.hasActiveNexus = tag.getBoolean("has_active_nexus");

        if (data.hasActiveNexus) {
            int x = tag.getInt("active_nexus_x");
            int y = tag.getInt("active_nexus_y");
            int z = tag.getInt("active_nexus_z");

            data.activeNexusPos = new BlockPos(x, y, z);
        }

        return data;
    }

    @Override
    public CompoundTag save(
            CompoundTag tag,
            HolderLookup.Provider registries
    ) {
        tag.putBoolean("has_active_nexus", hasActiveNexus);

        if (hasActiveNexus && activeNexusPos != null) {
            tag.putInt("active_nexus_x", activeNexusPos.getX());
            tag.putInt("active_nexus_y", activeNexusPos.getY());
            tag.putInt("active_nexus_z", activeNexusPos.getZ());
        }

        return tag;
    }

    public boolean hasActiveNexus() {
        return hasActiveNexus;
    }

    public BlockPos getActiveNexusPos() {
        return activeNexusPos;
    }

    public void setActiveNexus(BlockPos pos) {
        this.hasActiveNexus = true;
        this.activeNexusPos = pos.immutable();
        setDirty();
    }

    public void clearActiveNexus() {
        this.hasActiveNexus = false;
        this.activeNexusPos = BlockPos.ZERO;
        setDirty();
    }

    public boolean isActiveNexus(BlockPos pos) {
        return hasActiveNexus && activeNexusPos.equals(pos);
    }
}