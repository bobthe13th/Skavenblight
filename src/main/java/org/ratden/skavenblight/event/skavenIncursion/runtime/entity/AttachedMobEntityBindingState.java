package org.ratden.skavenblight.event.skavenIncursion.runtime.entity;


import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Mutable runtime state mapping planned attached-mob assignments to the
 * entities that successfully fulfilled them.
 *
 * The immutable SourceComposition owns the complete ordered collection of
 * planned attached-mob assignment IDs. This runtime state stores bindings only
 * after those assignments successfully spawn.
 *
 * Each binding records:
 *
 * - the stable attached-mob assignment ID from the immutable plan;
 * - the UUID of the successfully spawned entity.
 *
 * The entity's physical position belongs to ordinary Minecraft entity and
 * chunk persistence. This runtime state does not poll positions, search for
 * entities, or force chunks to load during restoration.
 *
 * An assignment without a binding may mean that it has not spawned yet. The
 * owning source-wave execution state remains responsible for deciding whether
 * that assignment is pending, completed or otherwise no longer eligible to
 * spawn.
 *
 * This class does not:
 *
 * - spawn entities;
 * - apply attached-mob modifiers;
 * - decide whether a missing entity should be replaced;
 * - load chunks during restoration;
 * - inspect leadership or ownership metadata.
 *
 * Those responsibilities belong to spawning and world-reconciliation layers.
 */
public final class AttachedMobEntityBindingState {

    private final List<UUID> plannedAssignmentIds;

    private final Set<UUID> plannedAssignmentIdSet;

    /**
     * LinkedHashMap follows immutable plan order rather than entity-spawn
     * order.
     */
    private final Map<UUID, BindingSnapshot>
            bindingsByAssignmentId;

    /**
     * Creates fresh unbound runtime state for an ordered collection of
     * planned attached-mob assignments.
     *
     * An empty list is valid for a source composition containing no attached
     * assignments.
     */
    public AttachedMobEntityBindingState(
            List<UUID> plannedAssignmentIds
    ) {
        this.plannedAssignmentIds =
                validateAndCopyPlannedAssignmentIds(
                        plannedAssignmentIds
                );

        this.plannedAssignmentIdSet =
                Collections.unmodifiableSet(
                        new LinkedHashSet<>(
                                this.plannedAssignmentIds
                        )
                );

        this.bindingsByAssignmentId =
                new LinkedHashMap<>();
    }

    /**
     * Restores exact binding state against the authoritative ordered
     * collection of planned assignment IDs.
     */
    public static AttachedMobEntityBindingState restore(
            List<UUID> plannedAssignmentIds,
            Snapshot snapshot
    ) {
        if (snapshot == null) {
            throw new IllegalArgumentException(
                    "Attached-mob entity-binding snapshot cannot be null."
            );
        }

        AttachedMobEntityBindingState restoredState =
                new AttachedMobEntityBindingState(
                        plannedAssignmentIds
                );

        for (BindingSnapshot bindingSnapshot
                : snapshot.bindings()) {

            restoredState.restoreBinding(
                    bindingSnapshot
            );
        }

        Snapshot reconstructedSnapshot =
                restoredState.createSnapshot();

        if (!snapshot.equals(
                reconstructedSnapshot
        )) {
            throw new IllegalArgumentException(
                    "Restored attached-mob entity bindings do not exactly "
                            + "match their saved snapshot."
            );
        }

        return restoredState;
    }

    public List<UUID> getPlannedAssignmentIds() {
        return plannedAssignmentIds;
    }

    public int getPlannedAssignmentCount() {
        return plannedAssignmentIds.size();
    }

    public boolean hasPlannedAssignments() {
        return !plannedAssignmentIds.isEmpty();
    }

    public boolean containsAssignment(
            UUID attachedMobAssignmentId
    ) {
        if (attachedMobAssignmentId == null) {
            return false;
        }

        return plannedAssignmentIdSet.contains(
                attachedMobAssignmentId
        );
    }

    public boolean isBound(
            UUID attachedMobAssignmentId
    ) {
        if (attachedMobAssignmentId == null) {
            return false;
        }

        return bindingsByAssignmentId.containsKey(
                attachedMobAssignmentId
        );
    }

    public BindingSnapshot getBinding(
            UUID attachedMobAssignmentId
    ) {
        if (attachedMobAssignmentId == null) {
            return null;
        }

        return bindingsByAssignmentId.get(
                attachedMobAssignmentId
        );
    }

    public UUID getBoundEntityId(
            UUID attachedMobAssignmentId
    ) {
        BindingSnapshot bindingSnapshot =
                getBinding(
                        attachedMobAssignmentId
                );

        return bindingSnapshot == null
                ? null
                : bindingSnapshot.entityId();
    }


    public int getBoundAssignmentCount() {
        return bindingsByAssignmentId.size();
    }

    public int getUnboundAssignmentCount() {
        return plannedAssignmentIds.size()
                - bindingsByAssignmentId.size();
    }

    public boolean hasBoundAssignments() {
        return !bindingsByAssignmentId.isEmpty();
    }

    public boolean areAllAssignmentsBound() {
        return bindingsByAssignmentId.size()
                == plannedAssignmentIds.size();
    }

    /**
     * Returns the bindings in immutable plan order.
     */
    public List<BindingSnapshot> getBindings() {
        List<BindingSnapshot> orderedBindings =
                new ArrayList<>();

        for (UUID attachedMobAssignmentId
                : plannedAssignmentIds) {

            BindingSnapshot bindingSnapshot =
                    bindingsByAssignmentId.get(
                            attachedMobAssignmentId
                    );

            if (bindingSnapshot != null) {
                orderedBindings.add(
                        bindingSnapshot
                );
            }
        }

        return List.copyOf(
                orderedBindings
        );
    }

    /**
     * Records the entity that successfully fulfilled one attached-mob
     * assignment.
     *
     * Repeating this operation with the same assignment and entity is
     * idempotent.
     *
     * Binding the assignment to a different entity is rejected. Replacing a
     * confirmed lost entity must be an explicit higher-level operation rather
     * than an accidental side effect of another spawn attempt.
     *
     * @return true when a new binding was created
     */
    public boolean bind(
            UUID attachedMobAssignmentId,
            Entity entity
    ) {
        requirePlannedAssignment(
                attachedMobAssignmentId
        );

        if (entity == null) {
            throw new IllegalArgumentException(
                    "Attached-mob entity cannot be null."
            );
        }

        if (entity.isRemoved()) {
            throw new IllegalArgumentException(
                    "A removed entity cannot fulfil an attached-mob "
                            + "assignment."
            );
        }

        UUID entityId =
                entity.getUUID();

        if (entityId == null) {
            throw new IllegalArgumentException(
                    "Attached-mob entity has no UUID."
            );
        }

        UUID assignmentUsingEntity =
                findAssignmentIdByEntityId(
                        entityId
                );

        if (assignmentUsingEntity != null
                && !assignmentUsingEntity.equals(
                attachedMobAssignmentId
        )) {

            throw new IllegalStateException(
                    "Entity "
                            + entityId
                            + " is already bound to attached-mob assignment "
                            + assignmentUsingEntity
                            + "."
            );
        }

        BindingSnapshot existingBinding =
                bindingsByAssignmentId.get(
                        attachedMobAssignmentId
                );

        if (existingBinding != null) {
            if (!existingBinding.entityId().equals(
                    entityId
            )) {
                throw new IllegalStateException(
                        "Attached-mob assignment "
                                + attachedMobAssignmentId
                                + " is already bound to entity "
                                + existingBinding.entityId()
                                + " and cannot be silently rebound to "
                                + entityId
                                + "."
                );
            }

            return false;
        }

        bindingsByAssignmentId.put(
                attachedMobAssignmentId,
                new BindingSnapshot(
                        attachedMobAssignmentId,
                        entityId
                )
        );

        reorderBindingsToPlanOrder();

        return true;
    }



    /**
     * Removes a binding after higher-level reconciliation has confirmed that
     * the entity no longer exists or that the binding must be explicitly
     * replaced.
     *
     * This method does not make the assignment eligible to spawn again. The
     * owning source-wave execution state must retain the authoritative spawn
     * progress separately.
     *
     * @return the removed binding, or null when the assignment was unbound
     */
    public BindingSnapshot removeBinding(
            UUID attachedMobAssignmentId
    ) {
        requirePlannedAssignment(
                attachedMobAssignmentId
        );

        return bindingsByAssignmentId.remove(
                attachedMobAssignmentId
        );
    }

    public UUID findAssignmentIdByEntityId(
            UUID entityId
    ) {
        if (entityId == null) {
            return null;
        }

        for (BindingSnapshot bindingSnapshot
                : bindingsByAssignmentId.values()) {

            if (entityId.equals(
                    bindingSnapshot.entityId()
            )) {
                return bindingSnapshot
                        .attachedMobAssignmentId();
            }
        }

        return null;
    }

    /**
     * Captures bound entities in immutable plan order.
     */
    public Snapshot createSnapshot() {
        return new Snapshot(
                getBindings()
        );
    }

    private void restoreBinding(
            BindingSnapshot bindingSnapshot
    ) {
        if (bindingSnapshot == null) {
            throw new IllegalArgumentException(
                    "Restored attached-mob binding cannot be null."
            );
        }

        UUID attachedMobAssignmentId =
                bindingSnapshot
                        .attachedMobAssignmentId();

        requirePlannedAssignment(
                attachedMobAssignmentId
        );

        if (bindingsByAssignmentId.containsKey(
                attachedMobAssignmentId
        )) {
            throw new IllegalArgumentException(
                    "Restored attached-mob bindings contain duplicate "
                            + "assignment ID "
                            + attachedMobAssignmentId
                            + "."
            );
        }

        UUID assignmentUsingEntity =
                findAssignmentIdByEntityId(
                        bindingSnapshot.entityId()
                );

        if (assignmentUsingEntity != null) {
            throw new IllegalArgumentException(
                    "Restored entity "
                            + bindingSnapshot.entityId()
                            + " is bound to more than one attached-mob "
                            + "assignment."
            );
        }

        bindingsByAssignmentId.put(
                attachedMobAssignmentId,
                bindingSnapshot
        );

        reorderBindingsToPlanOrder();
    }


    private void requirePlannedAssignment(
            UUID attachedMobAssignmentId
    ) {
        if (attachedMobAssignmentId == null) {
            throw new IllegalArgumentException(
                    "Attached-mob assignment ID cannot be null."
            );
        }

        if (!plannedAssignmentIdSet.contains(
                attachedMobAssignmentId
        )) {
            throw new IllegalArgumentException(
                    "Attached-mob assignment "
                            + attachedMobAssignmentId
                            + " does not belong to this binding state."
            );
        }
    }

    /**
     * Rebuilds insertion order after a restored or newly created binding is
     * added out of canonical plan order.
     */
    private void reorderBindingsToPlanOrder() {
        if (bindingsByAssignmentId.size() <= 1) {
            return;
        }

        LinkedHashMap<UUID, BindingSnapshot>
                orderedBindings =
                new LinkedHashMap<>();

        for (UUID attachedMobAssignmentId
                : plannedAssignmentIds) {

            BindingSnapshot bindingSnapshot =
                    bindingsByAssignmentId.get(
                            attachedMobAssignmentId
                    );

            if (bindingSnapshot != null) {
                orderedBindings.put(
                        attachedMobAssignmentId,
                        bindingSnapshot
                );
            }
        }

        bindingsByAssignmentId.clear();

        bindingsByAssignmentId.putAll(
                orderedBindings
        );
    }

    private static List<UUID>
    validateAndCopyPlannedAssignmentIds(
            List<UUID> plannedAssignmentIds
    ) {
        if (plannedAssignmentIds == null) {
            throw new IllegalArgumentException(
                    "Planned attached-mob assignment ID list cannot be null."
            );
        }

        List<UUID> copiedAssignmentIds =
                new ArrayList<>();

        Set<UUID> seenAssignmentIds =
                new HashSet<>();

        for (UUID attachedMobAssignmentId
                : plannedAssignmentIds) {

            if (attachedMobAssignmentId == null) {
                throw new IllegalArgumentException(
                        "Planned attached-mob assignment ID list cannot "
                                + "contain null."
                );
            }

            if (!seenAssignmentIds.add(
                    attachedMobAssignmentId
            )) {
                throw new IllegalArgumentException(
                        "Planned attached-mob assignment ID list contains "
                                + "duplicate ID "
                                + attachedMobAssignmentId
                                + "."
                );
            }

            copiedAssignmentIds.add(
                    attachedMobAssignmentId
            );
        }

        return List.copyOf(
                copiedAssignmentIds
        );
    }

    /**
     * Immutable persistence snapshot containing only assignments that have
     * successfully bound to an entity.
     *
     * Planned but unbound assignment identities remain authoritative in the
     * immutable IncursionPlan and therefore are not duplicated here.
     */
    public record Snapshot(
            List<BindingSnapshot> bindings
    ) {

        public Snapshot {
            if (bindings == null) {
                throw new IllegalArgumentException(
                        "Attached-mob binding snapshot list cannot be null."
                );
            }

            bindings =
                    List.copyOf(
                            bindings
                    );

            Set<UUID> assignmentIds =
                    new HashSet<>();

            Set<UUID> entityIds =
                    new HashSet<>();

            for (BindingSnapshot bindingSnapshot
                    : bindings) {

                if (bindingSnapshot == null) {
                    throw new IllegalArgumentException(
                            "Attached-mob binding snapshot list cannot "
                                    + "contain null."
                    );
                }

                if (!assignmentIds.add(
                        bindingSnapshot
                                .attachedMobAssignmentId()
                )) {
                    throw new IllegalArgumentException(
                            "Attached-mob binding snapshot contains duplicate "
                                    + "assignment ID "
                                    + bindingSnapshot
                                    .attachedMobAssignmentId()
                                    + "."
                    );
                }

                if (!entityIds.add(
                        bindingSnapshot.entityId()
                )) {
                    throw new IllegalArgumentException(
                            "Attached-mob binding snapshot contains duplicate "
                                    + "entity ID "
                                    + bindingSnapshot.entityId()
                                    + "."
                    );
                }
            }
        }

        public int getBindingCount() {
            return bindings.size();
        }

        public boolean isEmpty() {
            return bindings.isEmpty();
        }
    }

    /**
     * One successfully fulfilled attached-mob assignment.
     *
     * Physical entity position is intentionally not duplicated here.
     * Minecraft owns the entity's location through ordinary chunk
     * persistence.
     */
    public record BindingSnapshot(
            UUID attachedMobAssignmentId,
            UUID entityId
    ) {

        public BindingSnapshot {
            if (attachedMobAssignmentId == null) {
                throw new IllegalArgumentException(
                        "Attached-mob assignment ID cannot be null."
                );
            }

            if (entityId == null) {
                throw new IllegalArgumentException(
                        "Attached-mob entity ID cannot be null."
                );
            }
        }
    }
}