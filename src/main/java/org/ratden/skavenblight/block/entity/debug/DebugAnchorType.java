package org.ratden.skavenblight.block.entity.debug;

/**
 * Identifies which planning object one debug anchor represents.
 */
public enum DebugAnchorType {

    /**
     * Represents one FrontPlan anchor.
     *
     * The front anchor also acts as the visible anchor for the front's first
     * physical source group.
     */
    FRONT,

    /**
     * Represents an additional physical source group within one front.
     */
    SOURCE_GROUP
}