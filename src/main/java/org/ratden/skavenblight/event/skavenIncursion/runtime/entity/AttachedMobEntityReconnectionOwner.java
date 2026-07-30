package org.ratden.skavenblight.event.skavenIncursion.runtime.entity;

import net.minecraft.world.entity.Entity;

/**
 * Capability implemented by a persistent Scenario that owns attached-mob
 * entity bindings.
 *
 * Entity lifecycle events route through this interface rather than depending
 * directly on a particular Scenario implementation.
 */
public interface AttachedMobEntityReconnectionOwner {

    AttachedMobEntityReconnectionResult
    reconnectLoadedAttachedMobEntity(
            Entity entity
    );
}