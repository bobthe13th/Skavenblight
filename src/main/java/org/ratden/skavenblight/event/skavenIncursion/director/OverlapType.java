package org.ratden.skavenblight.event.skavenIncursion.director;

public enum OverlapType {
    /**
     * Major authored content that should normally suppress or prevent other
     * normal Director content from starting.
     *
     * Examples:
     * - finales
     * - major sieges
     * - boss set pieces
     */
    EXCLUSIVE,

    /**
     * Main active pressure.
     *
     * Examples:
     * - raids
     * - large assaults
     * - major combat scenarios
     */
    MAJOR,

    /**
     * Secondary pressure that may eventually be allowed to overlap with
     * major content at higher incursion tempo.
     *
     * Examples:
     * - small infiltrations
     * - sabotage attempts
     * - minor rituals
     * - ambushes
     */
    MINOR,

    /**
     * Background content that does not consume a full incursion slot, but may
     * still be restricted by pressure profile, priority, or active set pieces.
     *
     * Examples:
     * - ambient sounds
     * - distant bells
     * - red eyes
     * - subtle world effects
     */
    BACKGROUND
}