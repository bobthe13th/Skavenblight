package org.ratden.skavenblight.event.skavenIncursion.planning.composition;

import org.ratden.skavenblight.event.skavenIncursion.planning.budget.complexity.ComplexityOptionCategory;
import org.ratden.skavenblight.event.skavenIncursion.planning.budget.complexity.ComplexityOptionDefinition;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Describes an ATTACHED complexity option that promotes one already-budgeted
 * mob inside a SourceComposition.
 *
 * The option does not add another mob, spend threat or consume additional
 * source capacity. CompositionPlanner selects one eligible mob and records an
 * AttachedMobAssignment against it.
 */
public record AttachedMobComplexityOption(
        ComplexityOptionDefinition definition,
        Set<String> eligibleMobIds,
        SourceGroupComposition.AttachedMobSpawnPriority spawnPriority,
        boolean createsPack
) {
    public AttachedMobComplexityOption {
        if (definition == null) {
            throw new IllegalArgumentException(
                    "Attached complexity definition cannot be null."
            );
        }

        if (definition.category()
                != ComplexityOptionCategory.ATTACHED) {
            throw new IllegalArgumentException(
                    "Attached mob complexity options require the ATTACHED "
                            + "category."
            );
        }

        if (eligibleMobIds == null
                || eligibleMobIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "Attached mob complexity option must allow at least one "
                            + "mob ID."
            );
        }

        LinkedHashSet<String> copiedMobIds =
                new LinkedHashSet<>();

        for (String mobId : eligibleMobIds) {
            if (mobId == null || mobId.isBlank()) {
                throw new IllegalArgumentException(
                        "Eligible attached mob ID cannot be blank."
                );
            }

            copiedMobIds.add(mobId);
        }

        eligibleMobIds =
                Collections.unmodifiableSet(
                        copiedMobIds
                );

        if (spawnPriority == null) {
            throw new IllegalArgumentException(
                    "Attached mob spawn priority cannot be null."
            );
        }
    }

    public String getId() {
        return definition.id();
    }

    public int getComplexityCost() {
        return definition.complexityCost();
    }

    public boolean allowsMobId(
            String mobId
    ) {
        return mobId != null
                && eligibleMobIds.contains(mobId);
    }
}