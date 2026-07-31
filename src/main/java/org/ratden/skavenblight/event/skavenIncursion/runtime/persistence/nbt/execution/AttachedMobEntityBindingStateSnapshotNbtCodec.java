package org.ratden.skavenblight.event.skavenIncursion.runtime.persistence.nbt.execution;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.ratden.skavenblight.event.skavenIncursion.planning.persistence.nbt.IncursionSnapshotNbtSupport;
import org.ratden.skavenblight.event.skavenIncursion.runtime.entity.AttachedMobEntityBindingState;

import java.util.ArrayList;
import java.util.List;

/**
 * CompoundTag codec for AttachedMobEntityBindingState.Snapshot.
 *
 * Each saved entry joins:
 *
 * - one immutable attached-mob assignment ID;
 * - the UUID of the entity that fulfilled it.
 *
 * Entity position remains owned by ordinary Minecraft entity and chunk
 * persistence. Incursion persistence does not duplicate or poll that
 * information.
 *
 * The immutable IncursionPlan remains authoritative for which attached
 * assignments exist and the order in which they belong to their source
 * composition.
 *
 * Format-version ownership remains with the top-level persistent-incursion
 * codec.
 */
public final class
AttachedMobEntityBindingStateSnapshotNbtCodec {

    private static final String BINDINGS =
            "bindings";

    private static final String ATTACHED_MOB_ASSIGNMENT_ID =
            "attached_mob_assignment_id";

    private static final String ENTITY_ID =
            "entity_id";

    private AttachedMobEntityBindingStateSnapshotNbtCodec() {
    }

    /**
     * Writes every bound assignment in immutable plan order.
     */
    public static CompoundTag write(
            AttachedMobEntityBindingState.Snapshot snapshot
    ) {
        if (snapshot == null) {
            throw new IllegalArgumentException(
                    "Attached-mob entity-binding snapshot cannot be null."
            );
        }

        CompoundTag tag =
                new CompoundTag();

        ListTag bindingTags =
                new ListTag();

        for (AttachedMobEntityBindingState.BindingSnapshot
                bindingSnapshot
                : snapshot.bindings()) {

            bindingTags.add(
                    writeBinding(
                            bindingSnapshot
                    )
            );
        }

        tag.put(
                BINDINGS,
                bindingTags
        );

        AttachedMobEntityBindingState.Snapshot
                reconstructedSnapshot =
                read(
                        tag
                );

        if (!snapshot.equals(
                reconstructedSnapshot
        )) {
            throw new IllegalStateException(
                    "Attached-mob entity-binding NBT encoding did not "
                            + "produce an exact snapshot round trip."
            );
        }

        return tag;
    }

    /**
     * Reads the complete collection of saved attached-mob entity bindings.
     *
     * Validation against the immutable plan occurs when
     * AttachedMobEntityBindingState.restore(...) receives this snapshot.
     */
    public static AttachedMobEntityBindingState.Snapshot read(
            CompoundTag tag
    ) {
        if (tag == null) {
            throw new IllegalArgumentException(
                    "Attached-mob entity-binding NBT cannot be null."
            );
        }

        ListTag bindingTags =
                IncursionSnapshotNbtSupport.requireCompoundList(
                        tag,
                        BINDINGS
                );

        List<AttachedMobEntityBindingState.BindingSnapshot>
                bindings =
                new ArrayList<>();

        for (int bindingIndex = 0;
             bindingIndex < bindingTags.size();
             bindingIndex++) {

            try {
                bindings.add(
                        readBinding(
                                bindingTags.getCompound(
                                        bindingIndex
                                )
                        )
                );
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                        "Failed to read attached-mob entity binding at "
                                + "position "
                                + bindingIndex
                                + ".",
                        exception
                );
            }
        }

        return new AttachedMobEntityBindingState.Snapshot(
                bindings
        );
    }

    private static CompoundTag writeBinding(
            AttachedMobEntityBindingState.BindingSnapshot
                    bindingSnapshot
    ) {
        if (bindingSnapshot == null) {
            throw new IllegalArgumentException(
                    "Attached-mob entity binding cannot be null."
            );
        }

        CompoundTag tag =
                new CompoundTag();

        tag.putUUID(
                ATTACHED_MOB_ASSIGNMENT_ID,
                bindingSnapshot
                        .attachedMobAssignmentId()
        );

        tag.putUUID(
                ENTITY_ID,
                bindingSnapshot.entityId()
        );

        return tag;
    }

    private static AttachedMobEntityBindingState.BindingSnapshot
    readBinding(
            CompoundTag tag
    ) {
        return new AttachedMobEntityBindingState.BindingSnapshot(
                IncursionSnapshotNbtSupport.requireUuid(
                        tag,
                        ATTACHED_MOB_ASSIGNMENT_ID
                ),
                IncursionSnapshotNbtSupport.requireUuid(
                        tag,
                        ENTITY_ID
                )
        );
    }
}