package org.ratden.skavenblight.event.skavenIncursion.runtime.chunk;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent;
import net.neoforged.neoforge.common.world.chunk.TicketController;
import net.neoforged.neoforge.common.world.chunk.TicketHelper;
import net.neoforged.neoforge.common.world.chunk.TicketSet;
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.event.skavenIncursion.planning.chunk.IncursionChunkLoadPlan;
import org.ratden.skavenblight.event.skavenIncursion.runtime.persistence.PersistentIncursionSnapshot;
import org.ratden.skavenblight.event.skavenIncursion.runtime.persistence.SkavenIncursionSavedData;
import org.slf4j.Logger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * MOD-bus owner of the persistent chunk-ticket controller used by
 * planning-aware Skaven incursions.
 *
 * Active incursion tickets use the incursion UUID as their owner. This gives
 * each admitted incursion an isolated, persistent ticket namespace and allows
 * its complete ticket set to be removed without affecting another incursion.
 *
 * The controller's loading-validation callback runs before NeoForge
 * reinstates persisted tickets. It removes:
 *
 * - tickets whose incursion record no longer exists;
 * - tickets belonging to CLEANUP_PENDING incursions;
 * - tickets belonging to SUSPENDED incursions;
 * - chunks no longer present in the incursion's persisted chunk-load plan;
 * - tickets using the wrong ticking classification for the saved wave.
 *
 * The validation callback deliberately does not add missing tickets.
 * Authoritative restoration will later use IncursionChunkTicketService to
 * ensure that every expected ticket exists before world reconciliation.
 *
 * Block-position-owned tickets are not valid for this controller. Future
 * permanent Nexus or base tickets should use a separate controller and
 * lifecycle rather than sharing active-incursion ownership.
 */
@EventBusSubscriber(
        modid = Skavenblight.MODID,
        bus = EventBusSubscriber.Bus.MOD
)
public final class IncursionChunkTicketController {

    private static final Logger LOGGER =
            LogUtils.getLogger();

    private static final ResourceLocation CONTROLLER_ID =
            ResourceLocation.fromNamespaceAndPath(
                    Skavenblight.MODID,
                    "active_incursions"
            );

    private static final TicketController CONTROLLER =
            new TicketController(
                    CONTROLLER_ID,
                    IncursionChunkTicketController
                            ::validatePersistedTickets
            );

    /**
     * Registers the controller on NeoForge's mod-specific registration event.
     */
    @SubscribeEvent
    public static void registerTicketController(
            RegisterTicketControllersEvent event
    ) {
        if (event == null) {
            throw new IllegalArgumentException(
                    "Ticket-controller registration event cannot be null."
            );
        }

        event.register(
                CONTROLLER
        );
    }

    /**
     * Returns the registered controller to the runtime ticket service.
     *
     * Package-private visibility keeps ticket mutation inside the dedicated
     * runtime.chunk package.
     */
    static TicketController getController() {
        return CONTROLLER;
    }

    /**
     * Validates persisted NeoForge tickets against authoritative Skaven
     * incursion SavedData before those tickets are reinstated.
     *
     * This callback removes only invalid or stale tickets. Missing expected
     * tickets are handled later by restoration because TicketHelper is
     * intentionally a removal-oriented validation API.
     */
    private static void validatePersistedTickets(
            ServerLevel level,
            TicketHelper ticketHelper
    ) {
        if (level == null) {
            throw new IllegalArgumentException(
                    "Ticket-validation level cannot be null."
            );
        }

        if (ticketHelper == null) {
            throw new IllegalArgumentException(
                    "Ticket helper cannot be null."
            );
        }

        removeUnexpectedBlockOwnedTickets(
                level,
                ticketHelper
        );

        Map<UUID, TicketSet> persistedTicketsByOwner =
                new LinkedHashMap<>(
                        ticketHelper.getEntityTickets()
                );

        if (persistedTicketsByOwner.isEmpty()) {
            return;
        }

        SkavenIncursionSavedData savedData =
                SkavenIncursionSavedData.get(
                        level
                );

        for (Map.Entry<UUID, TicketSet> entry
                : persistedTicketsByOwner.entrySet()) {

            UUID incursionId =
                    entry.getKey();

            TicketSet persistedTicketSet =
                    entry.getValue();

            PersistentIncursionSnapshot persistentSnapshot =
                    savedData.getSnapshot(
                            incursionId
                    );

            if (persistentSnapshot == null) {
                int removedTicketCount =
                        countTickets(
                                persistedTicketSet
                        );

                ticketHelper.removeAllTickets(
                        incursionId
                );

                LOGGER.warn(
                        "Removed {} stale Skaven incursion chunk ticket{} "
                                + "owned by {} in level {} because no "
                                + "persistent incursion record exists.",
                        removedTicketCount,
                        pluralSuffix(
                                removedTicketCount
                        ),
                        incursionId,
                        level.dimension().location()
                );

                continue;
            }

            /*
             * CLEANUP_PENDING records have completed and must not continue to
             * consume simulation resources.
             *
             * SUSPENDED records are deliberately inert. A future explicit
             * resume operation must reacquire and verify their ticket state.
             */
            if (persistentSnapshot.requiresCleanup()
                    || persistentSnapshot.isSuspended()) {

                int removedTicketCount =
                        countTickets(
                                persistedTicketSet
                        );

                ticketHelper.removeAllTickets(
                        incursionId
                );

                LOGGER.debug(
                        "Removed {} Skaven incursion chunk ticket{} owned by "
                                + "{} in level {} because its persistent "
                                + "phase is {}.",
                        removedTicketCount,
                        pluralSuffix(
                                removedTicketCount
                        ),
                        incursionId,
                        level.dimension().location(),
                        persistentSnapshot.phase()
                );

                continue;
            }

            try {
                IncursionChunkLoadPlan chunkLoadPlan =
                        persistentSnapshot
                                .chunkLoadPlanSnapshot()
                                .restore();

                int currentWaveIndex =
                        persistentSnapshot
                                .scenarioRuntimeSnapshot()
                                .getCurrentWaveIndex();

                IncursionChunkTicketService.DesiredTicketState
                        desiredTicketState =
                        IncursionChunkTicketService
                                .calculateDesiredTicketState(
                                        chunkLoadPlan,
                                        currentWaveIndex
                                );

                int removedTicketCount =
                        removeUnexpectedUuidTickets(
                                ticketHelper,
                                incursionId,
                                persistedTicketSet,
                                desiredTicketState
                        );

                if (removedTicketCount > 0) {
                    LOGGER.warn(
                            "Removed {} unexpected Skaven incursion chunk "
                                    + "ticket{} owned by {} in level {}. "
                                    + "Missing required tickets, if any, "
                                    + "will be restored by the authoritative "
                                    + "incursion restoration pass.",
                            removedTicketCount,
                            pluralSuffix(
                                    removedTicketCount
                            ),
                            incursionId,
                            level.dimension().location()
                    );
                }
            } catch (RuntimeException exception) {
                int removedTicketCount =
                        countTickets(
                                persistedTicketSet
                        );

                ticketHelper.removeAllTickets(
                        incursionId
                );

                LOGGER.error(
                        "Removed {} Skaven incursion chunk ticket{} owned by "
                                + "{} in level {} because its persisted "
                                + "ticket requirements could not be "
                                + "validated.",
                        removedTicketCount,
                        pluralSuffix(
                                removedTicketCount
                        ),
                        incursionId,
                        level.dimension().location(),
                        exception
                );
            }
        }
    }

    /**
     * This controller reserves block-position ownership for no system.
     *
     * Any block-owned ticket using this ID is therefore stale or malformed
     * and is removed before ticket reinstatement.
     */
    private static void removeUnexpectedBlockOwnedTickets(
            ServerLevel level,
            TicketHelper ticketHelper
    ) {
        Map<BlockPos, TicketSet> blockTickets =
                new LinkedHashMap<>(
                        ticketHelper.getBlockTickets()
                );

        for (Map.Entry<BlockPos, TicketSet> entry
                : blockTickets.entrySet()) {

            BlockPos ownerPos =
                    entry.getKey();

            int removedTicketCount =
                    countTickets(
                            entry.getValue()
                    );

            ticketHelper.removeAllTickets(
                    ownerPos
            );

            LOGGER.warn(
                    "Removed {} unexpected block-owned Skaven incursion "
                            + "chunk ticket{} at {} in level {}. Active "
                            + "incursion tickets must use incursion UUID "
                            + "ownership.",
                    removedTicketCount,
                    pluralSuffix(
                            removedTicketCount
                    ),
                    ownerPos,
                    level.dimension().location()
            );
        }
    }

    /**
     * Removes tickets that either reference an unplanned chunk or use the
     * wrong ticking classification for the saved current wave.
     */
    private static int removeUnexpectedUuidTickets(
            TicketHelper ticketHelper,
            UUID incursionId,
            TicketSet persistedTicketSet,
            IncursionChunkTicketService.DesiredTicketState
                    desiredTicketState
    ) {
        int removedTicketCount =
                0;

        LongOpenHashSet persistedNonTickingChunks =
                new LongOpenHashSet(
                        persistedTicketSet.nonTicking()
                );

        for (long packedChunk
                : persistedNonTickingChunks) {

            ChunkPos chunkPos =
                    new ChunkPos(
                            packedChunk
                    );

            if (desiredTicketState
                    .nonTickingChunks()
                    .contains(
                            chunkPos
                    )) {

                continue;
            }

            ticketHelper.removeTicket(
                    incursionId,
                    packedChunk,
                    false
            );

            removedTicketCount++;
        }

        LongOpenHashSet persistedTickingChunks =
                new LongOpenHashSet(
                        persistedTicketSet.ticking()
                );

        for (long packedChunk
                : persistedTickingChunks) {

            ChunkPos chunkPos =
                    new ChunkPos(
                            packedChunk
                    );

            if (desiredTicketState
                    .tickingChunks()
                    .contains(
                            chunkPos
                    )) {

                continue;
            }

            ticketHelper.removeTicket(
                    incursionId,
                    packedChunk,
                    true
            );

            removedTicketCount++;
        }

        return removedTicketCount;
    }

    private static int countTickets(
            TicketSet ticketSet
    ) {
        if (ticketSet == null) {
            return 0;
        }

        return ticketSet.nonTicking().size()
                + ticketSet.ticking().size();
    }

    private static String pluralSuffix(
            int count
    ) {
        return count == 1
                ? ""
                : "s";
    }

    private IncursionChunkTicketController() {
    }
}