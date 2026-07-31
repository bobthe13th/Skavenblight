package org.ratden.skavenblight.event.skavenIncursion.runtime.entity;

import java.util.UUID;

/**
 * Result of attempting to reconnect one naturally loaded entity to its
 * persisted attached-mob assignment.
 *
 * Reconnection is deliberately strict. A conflicting entity is never silently
 * rebound over the entity recorded by persistent runtime.
 *
 * Successful reconnection verifies and repairs the live entity where needed,
 * but it does not mutate central incursion persistence.
 */
public record AttachedMobEntityReconnectionResult(
        UUID attachedMobAssignmentId,
        UUID entityId,
        boolean successful,
        String message
) {

    public AttachedMobEntityReconnectionResult {
        if (attachedMobAssignmentId == null) {
            throw new IllegalArgumentException(
                    "Reconnection assignment ID cannot be null."
            );
        }

        if (entityId == null) {
            throw new IllegalArgumentException(
                    "Reconnection entity ID cannot be null."
            );
        }

        if (message == null
                || message.isBlank()) {

            throw new IllegalArgumentException(
                    "Reconnection result message cannot be blank."
            );
        }
    }

    public static AttachedMobEntityReconnectionResult reconnected(
            UUID attachedMobAssignmentId,
            UUID entityId
    ) {
        return new AttachedMobEntityReconnectionResult(
                attachedMobAssignmentId,
                entityId,
                true,
                "Attached-mob entity reconnected successfully."
        );
    }

    public static AttachedMobEntityReconnectionResult rejected(
            UUID attachedMobAssignmentId,
            UUID entityId,
            String message
    ) {
        return new AttachedMobEntityReconnectionResult(
                attachedMobAssignmentId,
                entityId,
                false,
                message
        );
    }
}