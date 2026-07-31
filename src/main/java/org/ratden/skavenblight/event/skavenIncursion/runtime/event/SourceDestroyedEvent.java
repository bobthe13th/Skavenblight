package org.ratden.skavenblight.event.skavenIncursion.runtime.event;

import net.minecraft.core.BlockPos;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable notification that one physical incursion-source incarnation was
 * destroyed.
 *
 * This is a Skaven incursion domain event, not a NeoForge event-bus event.
 * The source block creates it when the block is genuinely removed from the
 * world, and ActiveIncursionManager will later route it to the owning active
 * incursion.
 *
 * A planned source location may create several physical incarnations across
 * different waves. Each incarnation has its own runtimeSourceId and may
 * therefore generate its own destruction event.
 *
 * @param runtimeSourceId
 *         unique ID of the destroyed physical source incarnation
 * @param scenarioInstanceId
 *         runtime instance ID of the Scenario that owns the source
 * @param sourcePos
 *         world position occupied by the destroyed source
 * @param destructionGameTime
 *         server game time when destruction was reported
 */
public record SourceDestroyedEvent(
        UUID runtimeSourceId,
        UUID scenarioInstanceId,
        BlockPos sourcePos,
        long destructionGameTime
) {

    public SourceDestroyedEvent {
        Objects.requireNonNull(
                runtimeSourceId,
                "Destroyed runtime source ID cannot be null."
        );

        Objects.requireNonNull(
                scenarioInstanceId,
                "Owning Scenario instance ID cannot be null."
        );

        Objects.requireNonNull(
                sourcePos,
                "Destroyed source position cannot be null."
        );

        if (destructionGameTime < 0L) {
            throw new IllegalArgumentException(
                    "Source destruction game time cannot be negative."
            );
        }

        sourcePos = sourcePos.immutable();
    }
}