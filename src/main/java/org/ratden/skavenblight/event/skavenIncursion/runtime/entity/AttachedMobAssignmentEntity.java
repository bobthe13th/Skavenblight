package org.ratden.skavenblight.event.skavenIncursion.runtime.entity;

import java.util.UUID;

/**
 * Capability implemented by an entity that can fulfil one immutable
 * attached-mob assignment.
 *
 * The assignment ID is written into the entity's ordinary Minecraft NBT.
 * Minecraft therefore carries the identity with the entity and saves it with
 * whichever chunk currently contains that entity.
 *
 * No central system needs to poll the entity's position.
 *
 * The ID is optional because:
 *
 * - ordinary incursion mobs do not fulfil attached assignments;
 * - the entity may exist briefly before attachment processing completes;
 * - failed transactional attachment processing may clear the ID.
 */
public interface AttachedMobAssignmentEntity {

    UUID getAttachedMobAssignmentId();

    void setAttachedMobAssignmentId(
            UUID attachedMobAssignmentId
    );

    default boolean hasAttachedMobAssignmentId() {
        return getAttachedMobAssignmentId() != null;
    }
}