package org.ratden.skavenblight.ai.pathing.nbt;

import net.minecraft.core.BlockPos;
import org.ratden.skavenblight.ai.pathing.PlannedStep;

import java.util.List;
import java.util.UUID;

/**
 * Persisted shape of one active SiegeProject - everything needed to reconstruct it, and nothing
 * else. Deliberately excludes `instructions` (scratch flow-field data, cheap to rebuild from
 * buildOrder + live terrain on the next calculation pass) and `workers` (rat<->project linkage is
 * fully self-healing via nextUnbuiltInstruction's live-terrain re-derivation - a worker that was
 * mid-registration when the world saved simply re-registers on its own next tick).
 */
public record SiegeProjectSnapshot(UUID projectId, UUID networkId, BlockPos entryPos, int expectedEntryCost,
                                    BlockPos exitPos, List<PlannedStep> buildOrder, BlockPos widenAnchor, int width,
                                    double accumulatedWork, long lastTickedGameTime) {
}
