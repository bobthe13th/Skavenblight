package org.ratden.skavenblight.event.skavenIncursion.planning;

import org.ratden.skavenblight.event.skavenIncursion.planning.front.FrontPlan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Complete planning result for one incursion.
 *
 * This object is progressively populated by the focused planners before being
 * validated and handed to runtime execution.
 *
 * It does not perform planning or execute the incursion itself.
 *
 * A persistence restoration path may supply an existing incursion ID. Normal
 * planning continues to generate a fresh ID.
 */
public class IncursionPlan {

    private final UUID incursionId;
    private final List<FrontPlan> frontPlans;

    /**
     * Creates a new plan with a fresh structural incursion ID.
     */
    public IncursionPlan() {
        this(
                UUID.randomUUID()
        );
    }

    /**
     * Creates an empty plan using an existing structural incursion ID.
     *
     * This constructor is intended for rebuilding a persisted immutable plan.
     * Restored fronts must still be added and validated normally.
     */
    public IncursionPlan(
            UUID incursionId
    ) {
        if (incursionId == null) {
            throw new IllegalArgumentException(
                    "Incursion ID cannot be null."
            );
        }

        this.incursionId =
                incursionId;

        this.frontPlans =
                new ArrayList<>();
    }

    public UUID getIncursionId() {
        return incursionId;
    }

    public void addFrontPlan(
            FrontPlan frontPlan
    ) {
        if (frontPlan == null) {
            throw new IllegalArgumentException(
                    "Front plan cannot be null."
            );
        }

        for (FrontPlan existingFrontPlan
                : frontPlans) {

            if (existingFrontPlan
                    .getFrontId()
                    .equals(
                            frontPlan.getFrontId()
                    )) {

                throw new IllegalArgumentException(
                        "Incursion plan already contains front ID "
                                + frontPlan.getFrontId()
                                + "."
                );
            }
        }

        frontPlans.add(
                frontPlan
        );
    }

    public List<FrontPlan> getFrontPlans() {
        return Collections.unmodifiableList(
                frontPlans
        );
    }

    public boolean hasFrontPlans() {
        return !frontPlans.isEmpty();
    }

    public int getFrontCount() {
        return frontPlans.size();
    }
}