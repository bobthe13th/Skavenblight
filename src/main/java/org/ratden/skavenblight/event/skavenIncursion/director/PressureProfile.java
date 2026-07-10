package org.ratden.skavenblight.event.skavenIncursion.director;

public enum PressureProfile {
    /**
     * General ambience. This can often happen in the background without
     * implying immediate stealth or combat pressure.
     *
     * Examples:
     * - distant bells
     * - warpstone hum
     * - strange green moonlight
     * - low underground rumbling
     */
    AMBIENT,

    /**
     * Quiet paranoia or hidden threat. This usually works best when the player
     * is not already in obvious combat.
     *
     * Examples:
     * - red eyes at the edge of vision
     * - soft skittering nearby
     * - an Eshin shadow glimpse
     * - signs of hidden sabotage
     */
    SUBTLE,

    /**
     * Direct hostile pressure or open fighting.
     *
     * Examples:
     * - assaults
     * - raids
     * - open ambushes
     * - combat announcements
     */
    COMBAT,

    /**
     * Major authored moments where presentation and pacing should be protected.
     *
     * Examples:
     * - finales
     * - major sieges
     * - boss encounters
     * - confront-the-ritual-site scenarios
     */
    SET_PIECE
}