package org.ratden.skavenblight.event.skavenIncursion.planning.budget.complexity;

/**
 * Identifies when a complexity option is considered during composition planning.
 *
 * The category does not determine tactical limits, weighting, placement preference,
 * or how many times an option may be purchased. Those decisions belong to the
 * Scenario, Stratagem, and CompositionPlanner.
 */
public enum ComplexityOptionCategory {
    /**
     * Changes or requires the underlying combat-source structure.
     *
     * Example: purchasing a Claw that requires a source group containing
     * multiple combat sources.
     */
    STRUCTURAL,

    /**
     * Modifies something that already exists in the planned composition.
     *
     * Examples: a Pack leader, a Claw aura, or a modifier applied to
     * an existing mob or leadership group.
     */
    ATTACHED,

    /**
     * Adds support infrastructure associated with a planned force.
     *
     * Example: an Emergency Brie-ch support source.
     */
    SUPPORT,

    /**
     * Adds a temporary or localised dramatic effect.
     *
     * Examples may eventually include bombardments, rituals, or other
     * battlefield spectacle.
     */
    SPECTACLE
}