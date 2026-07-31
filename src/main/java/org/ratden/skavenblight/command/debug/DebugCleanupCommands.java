package org.ratden.skavenblight.command.debug;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import org.ratden.skavenblight.block.ModBlocks;
import org.ratden.skavenblight.entity.custom.wolfCat.WolfCat;
import org.ratden.skavenblight.entity.custom.WolfRat;
import org.ratden.skavenblight.event.skavenIncursion.director.ActiveIncursionManager;
import org.ratden.skavenblight.event.skavenIncursion.debug.DebugIncursionAnchorTracker;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.ActiveIncursionSourceReservationRegistry;

public class DebugCleanupCommands {

    private static final int MOB_CLEANUP_RADIUS = 1000;
    private static final int SOURCE_CLEANUP_RADIUS = 160;

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("cleanup")
                .then(Commands.literal("all")
                        .executes(DebugCleanupCommands::cleanupAll))

                .then(Commands.literal("incursions")
                        .executes(DebugCleanupCommands::clearIncursions))

                .then(Commands.literal("sources")
                        .executes(context -> cleanupSources(
                                context.getSource(),
                                true
                        )))

                .then(Commands.literal("mobs")
                        .then(Commands.literal("all")
                                .executes(context -> cleanupAllSkavenblightMobs(
                                        context.getSource(),
                                        true
                                )))
                        .then(Commands.literal("wolf_rats")
                                .executes(context -> cleanupWolfRats(
                                        context.getSource(),
                                        true
                                )))
                        .then(Commands.literal("wolf_cats")
                                .executes(context -> cleanupWolfCats(
                                        context.getSource(),
                                        true
                                )))
                        .then(Commands.literal("vanilla")
                                .executes(context -> cleanupVanillaMobs(
                                        context.getSource(),
                                        true
                                ))));
    }

    private static int cleanupAll(
            CommandContext<CommandSourceStack> context
    ) {
        CommandSourceStack source =
                context.getSource();

        ServerLevel level =
                source.getLevel();

        int removedWolfRats =
                cleanupWolfRats(
                        source,
                        false
                );

        int removedWolfCats =
                cleanupWolfCats(
                        source,
                        false
                );

        int removedVanillaMobs =
                cleanupVanillaMobs(
                        source,
                        false
                );

        int removedSources =
                cleanupSources(
                        source,
                        false
                );

        int removedAnchors =
                DebugIncursionAnchorTracker.removeAll(
                        level
                );

        int removedIncursions =
                ActiveIncursionManager.clearIncursions();

        int removedReservationSnapshots =
                ActiveIncursionSourceReservationRegistry.clear(
                        level
                );

        int totalRemoved =
                removedWolfRats
                        + removedWolfCats
                        + removedVanillaMobs
                        + removedSources
                        + removedAnchors
                        + removedIncursions
                        + removedReservationSnapshots;

        source.sendSuccess(
                () -> Component.literal(
                        "Cleanup all complete."
                                + "\nRemoved wolf rats: "
                                + removedWolfRats
                                + "\nRemoved wolf cats: "
                                + removedWolfCats
                                + "\nRemoved vanilla mobs: "
                                + removedVanillaMobs
                                + "\nRemoved tunnel sources: "
                                + removedSources
                                + "\nRemoved debug anchors: "
                                + removedAnchors
                                + "\nRemoved active incursions: "
                                + removedIncursions
                                + "\nReleased incursion reservation snapshots: "
                                + removedReservationSnapshots
                                + "\nTotal removed or released: "
                                + totalRemoved
                ),
                false
        );

        return totalRemoved;
    }

    private static int clearIncursions(
            CommandContext<CommandSourceStack> context
    ) {
        CommandSourceStack source =
                context.getSource();

        ServerLevel level =
                source.getLevel();

        int removedIncursions =
                ActiveIncursionManager.clearIncursions();

        int removedAnchors =
                DebugIncursionAnchorTracker.removeAll(
                        level
                );

        int removedReservationSnapshots =
                ActiveIncursionSourceReservationRegistry.clear(
                        level
                );

        int totalRemoved =
                removedIncursions
                        + removedAnchors
                        + removedReservationSnapshots;

        source.sendSuccess(
                () -> Component.literal(
                        "Cleared incursions."
                                + "\nRemoved active incursions: "
                                + removedIncursions
                                + "\nRemoved debug anchors: "
                                + removedAnchors
                                + "\nReleased reservation snapshots: "
                                + removedReservationSnapshots
                ),
                false
        );

        return totalRemoved;
    }

    private static int cleanupAllSkavenblightMobs(
            CommandSourceStack source,
            boolean sendMessage
    ) {
        int removedWolfRats = cleanupWolfRats(source, false);
        int removedWolfCats = cleanupWolfCats(source, false);

        int totalRemoved = removedWolfRats + removedWolfCats;

        if (sendMessage) {
            source.sendSuccess(
                    () -> Component.literal(
                            "Removed Skavenblight mobs."
                                    + "\nWolf rats: " + removedWolfRats
                                    + "\nWolf cats: " + removedWolfCats
                                    + "\nTotal: " + totalRemoved
                    ),
                    false
            );
        }

        return totalRemoved;
    }

    private static int cleanupWolfRats(
            CommandSourceStack source,
            boolean sendMessage
    ) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            ServerLevel level = player.serverLevel();

            int removed = 0;

            for (WolfRat wolfRat : level.getEntitiesOfClass(
                    WolfRat.class,
                    player.getBoundingBox().inflate(MOB_CLEANUP_RADIUS)
            )) {
                wolfRat.discard();
                removed++;
            }

            int finalRemoved = removed;

            if (sendMessage) {
                source.sendSuccess(
                        () -> Component.literal(
                                "Removed " + finalRemoved + " wolf rats."
                        ),
                        false
                );
            }

            return removed;

        } catch (Exception exception) {
            if (sendMessage) {
                source.sendFailure(
                        Component.literal(
                                "Failed to remove wolf rats: " + exception.getMessage()
                        )
                );
            }

            return 0;
        }
    }

    private static int cleanupWolfCats(
            CommandSourceStack source,
            boolean sendMessage
    ) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            ServerLevel level = player.serverLevel();

            int removed = 0;

            for (WolfCat wolfCat : level.getEntitiesOfClass(
                    WolfCat.class,
                    player.getBoundingBox().inflate(MOB_CLEANUP_RADIUS)
            )) {
                wolfCat.discard();
                removed++;
            }

            int finalRemoved = removed;

            if (sendMessage) {
                source.sendSuccess(
                        () -> Component.literal(
                                "Removed " + finalRemoved + " wolf cats."
                        ),
                        false
                );
            }

            return removed;

        } catch (Exception exception) {
            if (sendMessage) {
                source.sendFailure(
                        Component.literal(
                                "Failed to remove wolf cats: " + exception.getMessage()
                        )
                );
            }

            return 0;
        }
    }

    private static int cleanupVanillaMobs(
            CommandSourceStack source,
            boolean sendMessage
    ) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            ServerLevel level = player.serverLevel();

            int removed = 0;

            for (Mob mob : level.getEntitiesOfClass(
                    Mob.class,
                    player.getBoundingBox().inflate(MOB_CLEANUP_RADIUS)
            )) {
                if (mob instanceof WolfRat || mob instanceof WolfCat) {
                    continue;
                }

                mob.discard();
                removed++;
            }

            int finalRemoved = removed;

            if (sendMessage) {
                source.sendSuccess(
                        () -> Component.literal(
                                "Removed " + finalRemoved + " vanilla/other mobs."
                        ),
                        false
                );
            }

            return removed;

        } catch (Exception exception) {
            if (sendMessage) {
                source.sendFailure(
                        Component.literal(
                                "Failed to remove vanilla mobs: " + exception.getMessage()
                        )
                );
            }

            return 0;
        }
    }

    private static int cleanupSources(
            CommandSourceStack source,
            boolean sendMessage
    ) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            ServerLevel level = player.serverLevel();

            int removed = 0;
            BlockPos playerPos = player.blockPosition();

            for (BlockPos pos : BlockPos.betweenClosed(
                    playerPos.offset(
                            -SOURCE_CLEANUP_RADIUS,
                            -SOURCE_CLEANUP_RADIUS,
                            -SOURCE_CLEANUP_RADIUS
                    ),
                    playerPos.offset(
                            SOURCE_CLEANUP_RADIUS,
                            SOURCE_CLEANUP_RADIUS,
                            SOURCE_CLEANUP_RADIUS
                    )
            )) {
                if (level.getBlockState(pos).is(ModBlocks.SKAVEN_TUNNEL_SOURCE.get())) {
                    level.removeBlock(pos, false);
                    removed++;
                }
            }

            int finalRemoved = removed;

            if (sendMessage) {
                source.sendSuccess(
                        () -> Component.literal(
                                "Removed " + finalRemoved + " tunnel sources."
                        ),
                        false
                );
            }

            return removed;

        } catch (Exception exception) {
            if (sendMessage) {
                source.sendFailure(
                        Component.literal(
                                "Failed to remove tunnel sources: " + exception.getMessage()
                        )
                );
            }

            return 0;
        }
    }

    private DebugCleanupCommands() {
    }
}