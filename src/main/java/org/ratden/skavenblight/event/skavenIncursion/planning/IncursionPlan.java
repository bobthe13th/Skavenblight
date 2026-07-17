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
 */
public class IncursionPlan {

    private final UUID incursionId;
    private final List<FrontPlan> frontPlans;

    public IncursionPlan() {
        this.incursionId = UUID.randomUUID();
        this.frontPlans = new ArrayList<>();
    }

    public UUID getIncursionId() {
        return incursionId;
    }

    public void addFrontPlan(FrontPlan frontPlan) {
        if (frontPlan == null) {
            throw new IllegalArgumentException("Front plan cannot be null.");
        }

        frontPlans.add(frontPlan);
    }

    public List<FrontPlan> getFrontPlans() {
        return Collections.unmodifiableList(frontPlans);
    }

    public boolean hasFrontPlans() {
        return !frontPlans.isEmpty();
    }

    public int getFrontCount() {
        return frontPlans.size();
    }
}