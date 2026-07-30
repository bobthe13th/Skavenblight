package org.ratden.skavenblight.event.skavenIncursion.runtime.chunk;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.ratden.skavenblight.event.skavenIncursion.planning.chunk.IncursionChunkLoadPlan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Performs a non-loading readiness inspection for one incursion's currently
 * required chunk-ticket state.
 *
 * Ticket ownership and desired ticking classification are established by
 * IncursionChunkTicketService before this inspection runs. This service then
 * asks the ServerChunkCache whether:
 *
 * - every retained chunk is currently available as a loaded LevelChunk;
 * - every chunk classified as ticking for the current wave is currently in a
 *   ticking-ready state.
 *
 * The inspection never calls getChunk(...), never creates another ticket and
 * never forces synchronous loading. An unavailable result therefore means
 * restoration should remain deferred and retry on a later server tick rather
 * than interpreting world absence as source destruction.
 */
public final class IncursionChunkReadinessService {

    public static ReadinessReport inspect(
            ServerLevel level,
            IncursionChunkLoadPlan chunkLoadPlan,
            int activeWaveIndex
    ) {
        if (level == null) {
            throw new IllegalArgumentException(
                    "Incursion chunk-readiness level cannot be null."
            );
        }

        if (chunkLoadPlan == null) {
            throw new IllegalArgumentException(
                    "Incursion chunk-load plan cannot be null."
            );
        }

        IncursionChunkTicketService.DesiredTicketState desiredTicketState =
                IncursionChunkTicketService.calculateDesiredTicketState(
                        chunkLoadPlan,
                        activeWaveIndex
                );

        List<ChunkPos> unloadedChunks =
                new ArrayList<>();

        List<ChunkPos> notTickingChunks =
                new ArrayList<>();

        for (ChunkPos chunkPos
                : sortedChunks(
                desiredTicketState.retainedChunks()
        )) {

            /*
             * getChunkNow(...) is deliberately non-loading. A null result
             * means the ticketed chunk has not become available yet.
             */
            if (level.getChunkSource()
                    .getChunkNow(
                            chunkPos.x,
                            chunkPos.z
                    ) == null) {

                unloadedChunks.add(
                        chunkPos
                );

                continue;
            }

            /*
             * Retained non-ticking chunks need only be loaded.
             *
             * Chunks required by the current wave must also have reached
             * ticking readiness before the incursion may resume.
             */
            if (desiredTicketState
                    .tickingChunks()
                    .contains(
                            chunkPos
                    )
                    && !level.getChunkSource()
                    .isPositionTicking(
                            chunkPos.toLong()
                    )) {

                notTickingChunks.add(
                        chunkPos
                );
            }
        }

        return new ReadinessReport(
                desiredTicketState.incursionId(),
                activeWaveIndex,
                desiredTicketState.getRetainedChunkCount(),
                desiredTicketState.getTickingChunkCount(),
                unloadedChunks,
                notTickingChunks
        );
    }

    private static List<ChunkPos> sortedChunks(
            Iterable<ChunkPos> chunks
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

    /**
     * Immutable result of one non-loading chunk-readiness inspection.
     */
    public record ReadinessReport(
            UUID incursionId,
            int activeWaveIndex,
            int retainedChunkCount,
            int requiredTickingChunkCount,
            List<ChunkPos> unloadedChunks,
            List<ChunkPos> notTickingChunks
    ) {

        public ReadinessReport {
            if (incursionId == null) {
                throw new IllegalArgumentException(
                        "Chunk-readiness incursion ID cannot be null."
                );
            }

            if (activeWaveIndex < 0) {
                throw new IllegalArgumentException(
                        "Chunk-readiness wave index cannot be negative."
                );
            }

            if (retainedChunkCount < 0) {
                throw new IllegalArgumentException(
                        "Retained chunk count cannot be negative."
                );
            }

            if (requiredTickingChunkCount < 0) {
                throw new IllegalArgumentException(
                        "Required ticking chunk count cannot be negative."
                );
            }

            if (requiredTickingChunkCount
                    > retainedChunkCount) {

                throw new IllegalArgumentException(
                        "Required ticking chunk count cannot exceed the "
                                + "retained chunk count."
                );
            }

            unloadedChunks =
                    immutableChunkList(
                            unloadedChunks,
                            "Unloaded chunk list"
                    );

            notTickingChunks =
                    immutableChunkList(
                            notTickingChunks,
                            "Not-ticking chunk list"
                    );
        }

        public boolean ready() {
            return unloadedChunks.isEmpty()
                    && notTickingChunks.isEmpty();
        }

        public int getUnavailableChunkCount() {
            return unloadedChunks.size()
                    + notTickingChunks.size();
        }

        /**
         * Creates a compact diagnostic suitable for restoration logs.
         */
        public String describeUnavailableState() {
            if (ready()) {
                return "All authoritative incursion chunks are ready.";
            }

            StringBuilder message =
                    new StringBuilder();

            message.append(
                    "Waiting for authoritative chunk readiness for wave "
            );

            message.append(
                    activeWaveIndex + 1
            );

            message.append(
                    ": "
            );

            message.append(
                    unloadedChunks.size()
            );

            message.append(
                    unloadedChunks.size() == 1
                            ? " retained chunk is not loaded"
                            : " retained chunks are not loaded"
            );

            message.append(
                    ", and "
            );

            message.append(
                    notTickingChunks.size()
            );

            message.append(
                    notTickingChunks.size() == 1
                            ? " required chunk is not ticking yet."
                            : " required chunks are not ticking yet."
            );

            if (!unloadedChunks.isEmpty()) {
                message.append(
                        " First unloaded chunk: "
                );

                message.append(
                        formatChunk(
                                unloadedChunks.getFirst()
                        )
                );

                message.append(
                        "."
                );
            } else if (!notTickingChunks.isEmpty()) {
                message.append(
                        " First non-ticking required chunk: "
                );

                message.append(
                        formatChunk(
                                notTickingChunks.getFirst()
                        )
                );

                message.append(
                        "."
                );
            }

            return message.toString();
        }

        private static List<ChunkPos> immutableChunkList(
                List<ChunkPos> chunks,
                String description
        ) {
            if (chunks == null) {
                throw new IllegalArgumentException(
                        description + " cannot be null."
                );
            }

            List<ChunkPos> copiedChunks =
                    new ArrayList<>();

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

            return List.copyOf(
                    copiedChunks
            );
        }

        private static String formatChunk(
                ChunkPos chunkPos
        ) {
            return chunkPos.x
                    + ", "
                    + chunkPos.z;
        }
    }

    private IncursionChunkReadinessService() {
    }
}