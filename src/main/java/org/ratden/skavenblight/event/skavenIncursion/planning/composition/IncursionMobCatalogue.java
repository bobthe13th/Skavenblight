package org.ratden.skavenblight.event.skavenIncursion.planning.composition;

import org.ratden.skavenblight.event.skavenIncursion.planning.budget.IncursionBudgetCosts;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceSize;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Authoritative catalogue of mobs available to the incursion-planning
 * system.
 *
 * Each definition describes the reusable planning characteristics of one
 * mob. Individual Scenarios separately decide whether that mob is legal and
 * assign its baseline selection weight.
 *
 * This is an internal Java catalogue, not a NeoForge registry. It therefore
 * has no DeferredRegister, event bus, or registration call in Skavenblight.
 */
public final class IncursionMobCatalogue {

    private static final Map<String, IncursionMobDefinition>
            DEFINITIONS_BY_ID =
            new LinkedHashMap<>();

    /**
     * Wolf Rat planning definition.
     *
     * The Java constant uses the correct conceptual name WOLF_RAT, while
     * the definition ID remains "rat_wolf" to match the entity's current
     * registered ID. Renaming that registry ID is a separate migration.
     */
    public static final IncursionMobDefinition WOLF_RAT =
            register(
                    new IncursionMobDefinition(
                            "rat_wolf",
                            IncursionBudgetCosts.WOLF_RAT,
                            SourceSize.SMALL,
                            IncursionMobDefinition.MobSize.SMALL,
                            EnumSet.of(
                                    IncursionMobDefinition
                                            .MobProfile.FAST,
                                    IncursionMobDefinition
                                            .MobProfile.FODDER
                            ),
                            EnumSet.of(
                                    IncursionMobDefinition
                                            .MobBehaviour.MELEE,
                                    IncursionMobDefinition
                                            .MobBehaviour.SWARM,
                                    IncursionMobDefinition
                                            .MobBehaviour.HUNTER
                            )
                    )
            );

    /**
     * Development-only Wolf Cat planning definition.
     *
     * Wolf Cat exists to test incursion planning and runtime execution with a
     * second mob type. Normal production Scenarios must not include it in their
     * mob rosters.
     *
     * Its planning characteristics are provisional and may be changed or the
     * definition removed once its development role is complete.
     */
    public static final IncursionMobDefinition WOLF_CAT =
            register(
                    new IncursionMobDefinition(
                            "wolf_cat",
                            IncursionBudgetCosts.WOLF_CAT,
                            SourceSize.SMALL,
                            IncursionMobDefinition.MobSize.SMALL,
                            EnumSet.of(
                                    IncursionMobDefinition
                                            .MobProfile.FAST
                            ),
                            EnumSet.of(
                                    IncursionMobDefinition
                                            .MobBehaviour.MELEE,
                                    IncursionMobDefinition
                                            .MobBehaviour.HUNTER
                            )
                    )
            );

    /**
     * Returns a definition by its stable authored ID.
     *
     * Returns null when no matching definition exists.
     */
    public static IncursionMobDefinition getById(String mobId) {
        if (mobId == null || mobId.isBlank()) {
            return null;
        }

        return DEFINITIONS_BY_ID.get(mobId);
    }

    /**
     * Returns all currently defined incursion mobs in declaration order.
     */
    public static Collection<IncursionMobDefinition> getAll() {
        return Collections.unmodifiableCollection(
                DEFINITIONS_BY_ID.values()
        );
    }

    private static IncursionMobDefinition register(
            IncursionMobDefinition definition
    ) {
        String mobId = definition.getMobId();

        IncursionMobDefinition existingDefinition =
                DEFINITIONS_BY_ID.putIfAbsent(
                        mobId,
                        definition
                );

        if (existingDefinition != null) {
            throw new IllegalStateException(
                    "Duplicate incursion mob definition ID: "
                            + mobId
                            + "."
            );
        }

        return definition;
    }

    private IncursionMobCatalogue() {
    }
}