package org.ratden.skavenblight.event.skavenIncursion.planning.source;

public enum SourceDistanceProfile {
    CLOSE(6, 12),
    STANDARD(18, 28),
    FAR(36, 50),
    SIEGE(50, 68);

    private final int minOffsetFromBaseEdge;
    private final int maxOffsetFromBaseEdge;

    SourceDistanceProfile(
            int minOffsetFromBaseEdge,
            int maxOffsetFromBaseEdge
    ) {
        this.minOffsetFromBaseEdge = minOffsetFromBaseEdge;
        this.maxOffsetFromBaseEdge = maxOffsetFromBaseEdge;
    }

    public int getMinOffsetFromBaseEdge() {
        return minOffsetFromBaseEdge;
    }

    public int getMaxOffsetFromBaseEdge() {
        return maxOffsetFromBaseEdge;
    }

    public int getMinDistanceFromTarget(int baseRadius) {
        return Math.max(0, baseRadius) + minOffsetFromBaseEdge;
    }

    public int getMaxDistanceFromTarget(int baseRadius) {
        return Math.max(0, baseRadius) + maxOffsetFromBaseEdge;
    }

    public SourceDistanceProfile oneStepFarther() {
        return switch (this) {
            case CLOSE -> STANDARD;
            case STANDARD -> FAR;
            case FAR, SIEGE -> SIEGE;
        };
    }

    public SourceDistanceProfile oneStepCloser() {
        return switch (this) {
            case CLOSE -> CLOSE;
            case STANDARD -> CLOSE;
            case FAR -> STANDARD;
            case SIEGE -> FAR;
        };
    }
}