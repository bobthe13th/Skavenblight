package org.ratden.skavenblight.block.entity.state;

import net.minecraft.util.StringRepresentable;

public enum SourceState implements StringRepresentable {
    ACTIVE("active"),
    DORMANT("dormant"),
    COLLAPSED("collapsed");

    private final String name;

    SourceState(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }
}