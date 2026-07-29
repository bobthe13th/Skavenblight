package org.ratden.skavenblight.event.skavenIncursion.planning.stratagem;

import org.ratden.skavenblight.event.skavenIncursion.planning.front.FrontAllocationPattern;
import org.ratden.skavenblight.event.skavenIncursion.planning.front.FrontPlacementPattern;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Authoritative catalogue of Stratagems currently available to the
 * incursion-planning system.
 *
 * A Scenario decides which of these Stratagems it permits. The selected
 * Stratagem then supplies the front pattern and wave-budget structure used
 * by the focused planners.
 *
 * This is an internal Java catalogue, not a NeoForge registry. It therefore
 * has no DeferredRegister, event bus, or registration call in Skavenblight.
 */
public final class StratagemCatalogue {

    private static final Map<String, StratagemDefinition>
            DEFINITIONS_BY_ID =
            new LinkedHashMap<>();

    /**
     * Minimal baseline Stratagem used to prove the first planning pipeline.
     *
     * It creates one front and assigns the complete threat and complexity
     * budgets to one wave.
     */
    public static final StratagemDefinition STEADY_1 =
            register(
                    new StratagemDefinition(
                            "steady_1",
                            true,
                            StratagemDefinition.StratagemStyle.SIMPLE,
                            FrontPlacementPattern.ONE,
                            FrontAllocationPattern.BALANCED,
                            List.of(
                                    new StratagemDefinition.WaveProfile(
                                            1.0D,
                                            1.0D
                                    )
                            )
                    )
            );

    /**
     * Three-wave development Stratagem used to verify ordinary multi-wave
     * planning and runtime progression.
     *
     * It deliberately retains one front so the test isolates wave budget
     * division, source reuse and source-state transitions without also
     * introducing multi-front placement behaviour.
     *
     * The final wave receives the largest share to give the raid a modest
     * escalation in pressure.
     */
    public static final StratagemDefinition STEADY_3 =
            register(
                    new StratagemDefinition(
                            "steady_3",
                            true,
                            StratagemDefinition.StratagemStyle.SIMPLE,
                            FrontPlacementPattern.ONE,
                            FrontAllocationPattern.BALANCED,
                            List.of(
                                    new StratagemDefinition.WaveProfile(
                                            0.30D,
                                            0.30D
                                    ),
                                    new StratagemDefinition.WaveProfile(
                                            0.30D,
                                            0.30D
                                    ),
                                    new StratagemDefinition.WaveProfile(
                                            0.40D,
                                            0.40D
                                    )
                            )
                    )
            );

    /**
     * Returns a Stratagem definition by its stable authored ID.
     *
     * Returns null when no matching definition exists.
     */
    public static StratagemDefinition getById(
            String stratagemId
    ) {
        if (stratagemId == null || stratagemId.isBlank()) {
            return null;
        }

        return DEFINITIONS_BY_ID.get(stratagemId);
    }

    /**
     * Returns every currently defined Stratagem in declaration order.
     */
    public static Collection<StratagemDefinition> getAll() {
        return Collections.unmodifiableCollection(
                DEFINITIONS_BY_ID.values()
        );
    }

    private static StratagemDefinition register(
            StratagemDefinition definition
    ) {
        if (definition == null) {
            throw new IllegalArgumentException(
                    "Stratagem definition cannot be null."
            );
        }

        String stratagemId =
                definition.getId();

        StratagemDefinition existingDefinition =
                DEFINITIONS_BY_ID.putIfAbsent(
                        stratagemId,
                        definition
                );

        if (existingDefinition != null) {
            throw new IllegalStateException(
                    "Duplicate Stratagem definition ID: "
                            + stratagemId
                            + "."
            );
        }

        return definition;
    }

    private StratagemCatalogue() {
    }
}