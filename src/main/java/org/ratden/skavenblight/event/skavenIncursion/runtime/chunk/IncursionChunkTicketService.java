package org.ratden.skavenblight.event.skavenIncursion.runtime.chunk;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.common.world.chunk.TicketController;
import org.ratden.skavenblight.event.skavenIncursion.planning.chunk.IncursionChunkLoadPlan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Authoritative runtime service for applying one persisted
 * IncursionChunkLoadPlan through NeoForge chunk tickets.
 *
 * Active-incursion tickets use the incursion UUID as their owner.
 *
 * For one current wave, the retained footprint is divided into two disjoint
 * ticket sets:
 *
 * - ticking chunks:
 *   protected-base chunks plus every source-group activation footprint
 *   required by the current wave;
 *
 * - non-ticking chunks:
 *   every remaining retained chunk belonging to future or currently inactive
 *   source groups and route corridors.
 *
 * A chunk receives only one ticket classification from this controller at a
 * time. Promotion and demotion always add the new classification before
 * removing the old one so a retained chunk is never intentionally left
 * without either ticket during a transition.
 *
 * This service does not:
 *
 * - decide which wave should be active;
 * - decide whether an incursion may be admitted;
 * - wait for chunk readiness;
 * - perform physical-source reconciliation;
 * - alter IncursionPlan or persistence snapshots;
 * - manage permanent Nexus-owned base tickets.
 *
 * All methods are expected to run on the logical server thread.
 */
public final class IncursionChunkTicketService {

    /**
     * Calculates the exact disjoint ticking and non-ticking ticket state for
     * one active wave.
     */
    public static DesiredTicketState calculateDesiredTicketState(
            IncursionChunkLoadPlan chunkLoadPlan,
            int activeWaveIndex
    ) {
        requireChunkLoadPlan(
                chunkLoadPlan
        );

        if (!chunkLoadPlan
                .getWaveIndexes()
                .contains(
                        activeWaveIndex
                )) {

            throw new IllegalArgumentException(
                    "Incursion "
                            + chunkLoadPlan.getIncursionId()
                            + " has no chunk-load requirements for wave "
                            + activeWaveIndex
                            + "."
            );
        }

        LinkedHashSet<ChunkPos> retainedChunks =
                new LinkedHashSet<>(
                        chunkLoadPlan.getRetainedLoadedChunks()
                );

        LinkedHashSet<ChunkPos> tickingChunks =
                new LinkedHashSet<>(
                        chunkLoadPlan
                                .getTotalTickingChunksForWave(
                                        activeWaveIndex
                                )
                );

        if (!retainedChunks.containsAll(
                tickingChunks
        )) {
            LinkedHashSet<ChunkPos> chunksOutsideRetainedFootprint =
                    new LinkedHashSet<>(
                            tickingChunks
                    );

            chunksOutsideRetainedFootprint.removeAll(
                    retainedChunks
            );

            throw new IllegalStateException(
                    "Incursion "
                            + chunkLoadPlan.getIncursionId()
                            + " wave "
                            + activeWaveIndex
                            + " requires ticking chunks outside its retained "
                            + "footprint: "
                            + chunksOutsideRetainedFootprint
                            + "."
            );
        }

        LinkedHashSet<ChunkPos> nonTickingChunks =
                new LinkedHashSet<>(
                        retainedChunks
                );

        nonTickingChunks.removeAll(
                tickingChunks
        );

        return new DesiredTicketState(
                chunkLoadPlan.getIncursionId(),
                activeWaveIndex,
                retainedChunks,
                nonTickingChunks,
                tickingChunks
        );
    }

    /**
     * Transactionally installs tickets for a newly admitted incursion.
     *
     * Fresh acquisition requires that no ticket in the desired footprint
     * already exists for the new incursion UUID. Encountering an existing
     * ticket is treated as an ownership conflict rather than silently
     * accepting stale state.
     *
     * When acquisition fails, every ticket successfully added by this method
     * is removed again in reverse order.
     */
    public static TicketOperationResult acquireFreshTickets(
            ServerLevel level,
            IncursionChunkLoadPlan chunkLoadPlan,
            int activeWaveIndex
    ) {
        requireLevel(
                level
        );

        DesiredTicketState desiredTicketState =
                calculateDesiredTicketState(
                        chunkLoadPlan,
                        activeWaveIndex
                );

        List<AppliedTicket> appliedTickets =
                new ArrayList<>();

        try {
            addFreshTickets(
                    level,
                    desiredTicketState.incursionId(),
                    desiredTicketState.nonTickingChunks(),
                    false,
                    appliedTickets
            );

            addFreshTickets(
                    level,
                    desiredTicketState.incursionId(),
                    desiredTicketState.tickingChunks(),
                    true,
                    appliedTickets
            );
        } catch (RuntimeException exception) {
            rollbackAppliedTickets(
                    level,
                    desiredTicketState.incursionId(),
                    appliedTickets,
                    exception
            );

            throw exception;
        }

        return new TicketOperationResult(
                desiredTicketState.incursionId(),
                activeWaveIndex,
                desiredTicketState.nonTickingChunks().size(),
                desiredTicketState.tickingChunks().size(),
                desiredTicketState.nonTickingChunks().size(),
                desiredTicketState.tickingChunks().size(),
                0,
                0
        );
    }

    /**
     * Idempotently applies the exact desired ticket classification for one
     * active wave.
     *
     * This is intended for:
     *
     * - restoration after NeoForge has validated and reinstated persisted
     *   tickets;
     * - repairing missing expected tickets;
     * - promoting the next wave's complete source-group footprints;
     * - later demotion of chunks that no longer require full ticking.
     *
     * For each chunk, the desired ticket type is added before the undesired
     * type is removed. An unexpected exception may therefore leave temporary
     * duplicate ticket classifications, but it does not intentionally create
     * an unloaded gap. Repeating this method converges on the exact desired
     * state.
     */
    public static TicketOperationResult ensureTicketState(
            ServerLevel level,
            IncursionChunkLoadPlan chunkLoadPlan,
            int activeWaveIndex
    ) {
        requireLevel(
                level
        );

        DesiredTicketState desiredTicketState =
                calculateDesiredTicketState(
                        chunkLoadPlan,
                        activeWaveIndex
                );

        int nonTickingTicketsAdded =
                0;

        int tickingTicketsAdded =
                0;

        int nonTickingTicketsRemoved =
                0;

        int tickingTicketsRemoved =
                0;

        TicketController ticketController =
                IncursionChunkTicketController
                        .getController();

        /*
         * Promote all required ticking chunks first.
         *
         * The ticking ticket is installed before any retained non-ticking
         * ticket is removed.
         */
        for (ChunkPos chunkPos
                : sortedChunks(
                desiredTicketState.tickingChunks()
        )) {

            if (ticketController.forceChunk(
                    level,
                    desiredTicketState.incursionId(),
                    chunkPos.x,
                    chunkPos.z,
                    true,
                    true
            )) {
                tickingTicketsAdded++;
            }

            if (ticketController.forceChunk(
                    level,
                    desiredTicketState.incursionId(),
                    chunkPos.x,
                    chunkPos.z,
                    false,
                    false
            )) {
                nonTickingTicketsRemoved++;
            }
        }

        /*
         * Retain every remaining planned chunk without full simulation.
         *
         * The non-ticking ticket is installed before any old ticking ticket
         * is removed.
         */
        for (ChunkPos chunkPos
                : sortedChunks(
                desiredTicketState.nonTickingChunks()
        )) {

            if (ticketController.forceChunk(
                    level,
                    desiredTicketState.incursionId(),
                    chunkPos.x,
                    chunkPos.z,
                    true,
                    false
            )) {
                nonTickingTicketsAdded++;
            }

            if (ticketController.forceChunk(
                    level,
                    desiredTicketState.incursionId(),
                    chunkPos.x,
                    chunkPos.z,
                    false,
                    true
            )) {
                tickingTicketsRemoved++;
            }
        }

        return new TicketOperationResult(
                desiredTicketState.incursionId(),
                activeWaveIndex,
                desiredTicketState.nonTickingChunks().size(),
                desiredTicketState.tickingChunks().size(),
                nonTickingTicketsAdded,
                tickingTicketsAdded,
                nonTickingTicketsRemoved,
                tickingTicketsRemoved
        );
    }

    /**
     * Removes both ticket classifications from every chunk in the persisted
     * retained footprint.
     *
     * Removal is idempotent. Missing tickets are ignored.
     */
    public static TicketReleaseResult releaseAllTickets(
            ServerLevel level,
            IncursionChunkLoadPlan chunkLoadPlan
    ) {
        requireLevel(
                level
        );

        requireChunkLoadPlan(
                chunkLoadPlan
        );

        TicketController ticketController =
                IncursionChunkTicketController
                        .getController();

        int nonTickingTicketsRemoved =
                0;

        int tickingTicketsRemoved =
                0;

        for (ChunkPos chunkPos
                : sortedChunks(
                chunkLoadPlan.getRetainedLoadedChunks()
        )) {

            if (ticketController.forceChunk(
                    level,
                    chunkLoadPlan.getIncursionId(),
                    chunkPos.x,
                    chunkPos.z,
                    false,
                    true
            )) {
                tickingTicketsRemoved++;
            }

            if (ticketController.forceChunk(
                    level,
                    chunkLoadPlan.getIncursionId(),
                    chunkPos.x,
                    chunkPos.z,
                    false,
                    false
            )) {
                nonTickingTicketsRemoved++;
            }
        }

        return new TicketReleaseResult(
                chunkLoadPlan.getIncursionId(),
                nonTickingTicketsRemoved,
                tickingTicketsRemoved
        );
    }

    /**
     * Adds one fresh ticket classification for every supplied chunk.
     */
    private static void addFreshTickets(
            ServerLevel level,
            UUID incursionId,
            Set<ChunkPos> chunks,
            boolean ticking,
            List<AppliedTicket> appliedTickets
    ) {
        TicketController ticketController =
                IncursionChunkTicketController
                        .getController();

        for (ChunkPos chunkPos
                : sortedChunks(
                chunks
        )) {

            boolean ticketAdded =
                    ticketController.forceChunk(
                            level,
                            incursionId,
                            chunkPos.x,
                            chunkPos.z,
                            true,
                            ticking
                    );

            if (!ticketAdded) {
                throw new IllegalStateException(
                        "Could not acquire fresh "
                                + ticketDescription(
                                ticking
                        )
                                + " chunk ticket for incursion "
                                + incursionId
                                + " at chunk "
                                + chunkPos.x
                                + ", "
                                + chunkPos.z
                                + ". A ticket using this owner and "
                                + "classification may already exist."
                );
            }

            appliedTickets.add(
                    new AppliedTicket(
                            chunkPos,
                            ticking
                    )
            );
        }
    }

    /**
     * Removes only tickets that were confirmed as newly added during the
     * failed fresh-acquisition attempt.
     */
    private static void rollbackAppliedTickets(
            ServerLevel level,
            UUID incursionId,
            List<AppliedTicket> appliedTickets,
            RuntimeException originalException
    ) {
        TicketController ticketController =
                IncursionChunkTicketController
                        .getController();

        for (int ticketIndex =
             appliedTickets.size() - 1;
             ticketIndex >= 0;
             ticketIndex--) {

            AppliedTicket appliedTicket =
                    appliedTickets.get(
                            ticketIndex
                    );

            try {
                ticketController.forceChunk(
                        level,
                        incursionId,
                        appliedTicket.chunkPos().x,
                        appliedTicket.chunkPos().z,
                        false,
                        appliedTicket.ticking()
                );
            } catch (RuntimeException rollbackException) {
                originalException.addSuppressed(
                        rollbackException
                );
            }
        }
    }

    private static List<ChunkPos> sortedChunks(
            Set<ChunkPos> chunks
    ) {
        if (chunks == null) {
            throw new IllegalArgumentException(
                    "Chunk collection cannot be null."
            );
        }

        List<ChunkPos> sortedChunks =
                new ArrayList<>();

        for (ChunkPos chunkPos : chunks) {
            if (chunkPos == null) {
                throw new IllegalArgumentException(
                        "Chunk collection cannot contain null."
                );
            }

            sortedChunks.add(
                    new ChunkPos(
                            chunkPos.x,
                            chunkPos.z
                    )
            );
        }

        sortedChunks.sort(
                Comparator
                        .comparingInt(
                                (ChunkPos chunkPos) ->
                                        chunkPos.x
                        )
                        .thenComparingInt(
                                chunkPos ->
                                        chunkPos.z
                        )
        );

        return List.copyOf(
                sortedChunks
        );
    }

    private static String ticketDescription(
            boolean ticking
    ) {
        return ticking
                ? "ticking"
                : "non-ticking";
    }

    private static void requireLevel(
            ServerLevel level
    ) {
        if (level == null) {
            throw new IllegalArgumentException(
                    "Incursion chunk-ticket level cannot be null."
            );
        }
    }

    private static void requireChunkLoadPlan(
            IncursionChunkLoadPlan chunkLoadPlan
    ) {
        if (chunkLoadPlan == null) {
            throw new IllegalArgumentException(
                    "Incursion chunk-load plan cannot be null."
            );
        }
    }

    /**
     * Complete desired ticket classification for one current wave.
     *
     * The three sets are immutable. nonTickingChunks and tickingChunks are
     * disjoint, and their union exactly equals retainedChunks.
     */
    public record DesiredTicketState(
            UUID incursionId,
            int activeWaveIndex,
            Set<ChunkPos> retainedChunks,
            Set<ChunkPos> nonTickingChunks,
            Set<ChunkPos> tickingChunks
    ) {

        public DesiredTicketState {
            if (incursionId == null) {
                throw new IllegalArgumentException(
                        "Desired ticket-state incursion ID cannot be null."
                );
            }

            if (activeWaveIndex < 0) {
                throw new IllegalArgumentException(
                        "Desired ticket-state wave index cannot be negative."
                );
            }

            retainedChunks =
                    immutableChunkSet(
                            retainedChunks,
                            "Desired retained chunks"
                    );

            nonTickingChunks =
                    immutableChunkSet(
                            nonTickingChunks,
                            "Desired non-ticking chunks"
                    );

            tickingChunks =
                    immutableChunkSet(
                            tickingChunks,
                            "Desired ticking chunks"
                    );

            if (retainedChunks.isEmpty()) {
                throw new IllegalArgumentException(
                        "Desired retained chunk footprint cannot be empty."
                );
            }

            LinkedHashSet<ChunkPos> overlappingChunks =
                    new LinkedHashSet<>(
                            nonTickingChunks
                    );

            overlappingChunks.retainAll(
                    tickingChunks
            );

            if (!overlappingChunks.isEmpty()) {
                throw new IllegalArgumentException(
                        "Desired ticket state contains chunks classified as "
                                + "both ticking and non-ticking: "
                                + overlappingChunks
                                + "."
                );
            }

            LinkedHashSet<ChunkPos> classifiedChunks =
                    new LinkedHashSet<>(
                            nonTickingChunks
                    );

            classifiedChunks.addAll(
                    tickingChunks
            );

            if (!classifiedChunks.equals(
                    retainedChunks
            )) {
                LinkedHashSet<ChunkPos> unclassifiedChunks =
                        new LinkedHashSet<>(
                                retainedChunks
                        );

                unclassifiedChunks.removeAll(
                        classifiedChunks
                );

                LinkedHashSet<ChunkPos> unexpectedChunks =
                        new LinkedHashSet<>(
                                classifiedChunks
                        );

                unexpectedChunks.removeAll(
                        retainedChunks
                );

                throw new IllegalArgumentException(
                        "Desired ticket classifications do not exactly match "
                                + "the retained footprint. Unclassified: "
                                + unclassifiedChunks
                                + "; outside retained footprint: "
                                + unexpectedChunks
                                + "."
                );
            }
        }

        public int getRetainedChunkCount() {
            return retainedChunks.size();
        }

        public int getNonTickingChunkCount() {
            return nonTickingChunks.size();
        }

        public int getTickingChunkCount() {
            return tickingChunks.size();
        }
    }

    /**
     * Result of acquiring or reconciling one active wave's ticket state.
     */
    public record TicketOperationResult(
            UUID incursionId,
            int activeWaveIndex,
            int desiredNonTickingTicketCount,
            int desiredTickingTicketCount,
            int nonTickingTicketsAdded,
            int tickingTicketsAdded,
            int nonTickingTicketsRemoved,
            int tickingTicketsRemoved
    ) {

        public TicketOperationResult {
            if (incursionId == null) {
                throw new IllegalArgumentException(
                        "Ticket operation incursion ID cannot be null."
                );
            }

            if (activeWaveIndex < 0) {
                throw new IllegalArgumentException(
                        "Ticket operation wave index cannot be negative."
                );
            }

            requireNonNegative(
                    desiredNonTickingTicketCount,
                    "Desired non-ticking ticket count"
            );

            requireNonNegative(
                    desiredTickingTicketCount,
                    "Desired ticking ticket count"
            );

            requireNonNegative(
                    nonTickingTicketsAdded,
                    "Added non-ticking ticket count"
            );

            requireNonNegative(
                    tickingTicketsAdded,
                    "Added ticking ticket count"
            );

            requireNonNegative(
                    nonTickingTicketsRemoved,
                    "Removed non-ticking ticket count"
            );

            requireNonNegative(
                    tickingTicketsRemoved,
                    "Removed ticking ticket count"
            );
        }

        public int getDesiredTicketCount() {
            return desiredNonTickingTicketCount
                    + desiredTickingTicketCount;
        }

        public int getAddedTicketCount() {
            return nonTickingTicketsAdded
                    + tickingTicketsAdded;
        }

        public int getRemovedTicketCount() {
            return nonTickingTicketsRemoved
                    + tickingTicketsRemoved;
        }
    }

    /**
     * Result of releasing one incursion's known ticket footprint.
     */
    public record TicketReleaseResult(
            UUID incursionId,
            int nonTickingTicketsRemoved,
            int tickingTicketsRemoved
    ) {

        public TicketReleaseResult {
            if (incursionId == null) {
                throw new IllegalArgumentException(
                        "Ticket-release incursion ID cannot be null."
                );
            }

            requireNonNegative(
                    nonTickingTicketsRemoved,
                    "Released non-ticking ticket count"
            );

            requireNonNegative(
                    tickingTicketsRemoved,
                    "Released ticking ticket count"
            );
        }

        public int getRemovedTicketCount() {
            return nonTickingTicketsRemoved
                    + tickingTicketsRemoved;
        }
    }

    private record AppliedTicket(
            ChunkPos chunkPos,
            boolean ticking
    ) {

        private AppliedTicket {
            if (chunkPos == null) {
                throw new IllegalArgumentException(
                        "Applied ticket chunk cannot be null."
                );
            }

            chunkPos =
                    new ChunkPos(
                            chunkPos.x,
                            chunkPos.z
                    );
        }
    }

    private static Set<ChunkPos> immutableChunkSet(
            Set<ChunkPos> chunks,
            String description
    ) {
        if (chunks == null) {
            throw new IllegalArgumentException(
                    description + " cannot be null."
            );
        }

        LinkedHashSet<ChunkPos> copiedChunks =
                new LinkedHashSet<>();

        for (ChunkPos chunkPos : chunks) {
            if (chunkPos == null) {
                throw new IllegalArgumentException(
                        description + " cannot contain null."
                );
            }

            copiedChunks.add(
                    new ChunkPos(
                            chunkPos.x,
                            chunkPos.z
                    )
            );
        }

        return Collections.unmodifiableSet(
                copiedChunks
        );
    }

    private static void requireNonNegative(
            int value,
            String description
    ) {
        if (value < 0) {
            throw new IllegalArgumentException(
                    description + " cannot be negative."
            );
        }
    }

    private IncursionChunkTicketService() {
    }
}