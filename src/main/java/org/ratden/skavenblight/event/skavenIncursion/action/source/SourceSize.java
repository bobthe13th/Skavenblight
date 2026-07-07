package org.ratden.skavenblight.event.skavenIncursion.action.source;

public enum SourceSize {
    SMALL(5),
    NORMAL(10),
    LARGE(25),
    HUGE(50);

    private final int capacityUnits;

    SourceSize(int capacityUnits) {
        this.capacityUnits = capacityUnits;
    }

    public int getCapacityUnits() {
        return capacityUnits;
    }

    public boolean canFit(SourceSize requiredSize) {
        return this.ordinal() >= requiredSize.ordinal();
    }
}