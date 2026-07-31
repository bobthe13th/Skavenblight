package org.ratden.skavenblight.event.skavenIncursion.debug;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.joml.Vector3f;
import org.ratden.skavenblight.block.entity.debug.DebugAnchorSourceLink;
import org.ratden.skavenblight.block.entity.debug.DebugIncursionAnchorEntity;

import java.util.List;

/**
 * Server-controlled visualisation for incursion front and source-group
 * anchors.
 *
 * The physical debug-anchor blocks remain invisible. When visualisation is
 * enabled, this class periodically sends:
 *
 * - a coloured particle marker around every tracked anchor;
 * - a coloured particle line from that anchor to every source represented by
 *   it.
 *
 * The front anchor represents the first physical source group in its front.
 * Additional physical source groups use their own source-group anchors.
 *
 * No front-anchor-to-source-group-anchor lines are drawn.
 *
 * Colour is determined solely by the zero-based front index:
 *
 * 0 red
 * 1 orange
 * 2 yellow
 * 3 green
 * 4 blue
 * 5 purple
 *
 * This is a debug rendering system only. It does not create, remove, alter or
 * own any incursion planning or runtime objects.
 */
public final class DebugSourceVisualisation {

    /**
     * Redraw the visualisation twice per second.
     *
     * Dust particles persist long enough that this should appear reasonably
     * continuous without sending the entire line network every server tick.
     */
    private static final int RENDER_INTERVAL_TICKS =
            10;

    /**
     * Approximate distance between consecutive particles on a link.
     */
    private static final double LINE_PARTICLE_SPACING =
            1.0D;

    /**
     * Prevents exceptionally long links from creating an excessive number of
     * particles in one redraw.
     */
    private static final int MAXIMUM_PARTICLES_PER_LINE =
            96;

    private static final double ANCHOR_MARKER_RADIUS =
            0.40D;

    private static final float LINE_PARTICLE_SCALE =
            0.80F;

    private static final float ANCHOR_PARTICLE_SCALE =
            1.20F;

    private static final List<Vector3f> FRONT_COLOURS =
            List.of(
                    new Vector3f(
                            1.00F,
                            0.05F,
                            0.05F
                    ),
                    new Vector3f(
                            1.00F,
                            0.40F,
                            0.00F
                    ),
                    new Vector3f(
                            1.00F,
                            0.90F,
                            0.00F
                    ),
                    new Vector3f(
                            0.10F,
                            1.00F,
                            0.15F
                    ),
                    new Vector3f(
                            0.10F,
                            0.35F,
                            1.00F
                    ),
                    new Vector3f(
                            0.65F,
                            0.15F,
                            1.00F
                    )
            );

    /**
     * This is intentionally server-wide for the first implementation.
     *
     * Every player capable of receiving the particles will see the debug
     * visualisation while it is enabled.
     */
    private static boolean enabled =
            false;

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(
            boolean enabled
    ) {
        DebugSourceVisualisation.enabled =
                enabled;
    }

    /**
     * Reverses the current state and returns the new state.
     */
    public static boolean toggle() {
        enabled =
                !enabled;

        return enabled;
    }

    public static void disable() {
        enabled =
                false;
    }

    /**
     * Called from the common server-tick event.
     */
    public static void tick(
            MinecraftServer server
    ) {
        if (!enabled) {
            return;
        }

        if (server == null) {
            return;
        }

        for (ServerLevel level
                : server.getAllLevels()) {

            if (level.getGameTime()
                    % RENDER_INTERVAL_TICKS
                    != 0L) {
                continue;
            }

            renderLevel(
                    level
            );
        }
    }

    private static void renderLevel(
            ServerLevel level
    ) {
        List<
                DebugIncursionAnchorPlacementService.PlacementResult
                > placementResults =
                DebugIncursionAnchorTracker
                        .getPlacementResults(
                                level
                        );

        for (DebugIncursionAnchorPlacementService.PlacementResult
                placementResult
                : placementResults) {

            renderPlacementResult(
                    level,
                    placementResult
            );
        }
    }

    private static void renderPlacementResult(
            ServerLevel level,
            DebugIncursionAnchorPlacementService.PlacementResult
                    placementResult
    ) {
        if (placementResult == null
                || !placementResult.successful()) {
            return;
        }

        for (BlockPos anchorPos
                : placementResult.anchorPositions()) {

            if (!level.hasChunkAt(
                    anchorPos
            )) {
                continue;
            }

            if (!(level.getBlockEntity(anchorPos)
                    instanceof DebugIncursionAnchorEntity
                    anchorEntity)) {
                continue;
            }

            if (!anchorEntity.isInitialised()) {
                continue;
            }

            if (!placementResult
                    .incursionId()
                    .equals(
                            anchorEntity.getIncursionId()
                    )) {
                continue;
            }

            Vector3f frontColour =
                    getFrontColour(
                            anchorEntity.getFrontIndex()
                    );

            if (frontColour == null) {
                continue;
            }

            DustParticleOptions anchorParticle =
                    new DustParticleOptions(
                            frontColour,
                            ANCHOR_PARTICLE_SCALE
                    );

            DustParticleOptions lineParticle =
                    new DustParticleOptions(
                            frontColour,
                            LINE_PARTICLE_SCALE
                    );

            renderAnchorMarker(
                    level,
                    anchorPos,
                    anchorParticle
            );

            renderSourceLinks(
                    level,
                    anchorPos,
                    anchorEntity,
                    lineParticle
            );
        }
    }

    /**
     * Draws a small three-dimensional cross around the invisible anchor
     * block.
     *
     * This provides a clearly targetable visual centre without changing the
     * block's model or blockstate.
     */
    private static void renderAnchorMarker(
            ServerLevel level,
            BlockPos anchorPos,
            DustParticleOptions particle
    ) {
        double centreX =
                anchorPos.getX()
                        + 0.5D;

        double centreY =
                anchorPos.getY()
                        + 0.5D;

        double centreZ =
                anchorPos.getZ()
                        + 0.5D;

        sendParticle(
                level,
                particle,
                centreX,
                centreY,
                centreZ
        );

        sendParticle(
                level,
                particle,
                centreX + ANCHOR_MARKER_RADIUS,
                centreY,
                centreZ
        );

        sendParticle(
                level,
                particle,
                centreX - ANCHOR_MARKER_RADIUS,
                centreY,
                centreZ
        );

        sendParticle(
                level,
                particle,
                centreX,
                centreY + ANCHOR_MARKER_RADIUS,
                centreZ
        );

        sendParticle(
                level,
                particle,
                centreX,
                centreY - ANCHOR_MARKER_RADIUS,
                centreZ
        );

        sendParticle(
                level,
                particle,
                centreX,
                centreY,
                centreZ + ANCHOR_MARKER_RADIUS
        );

        sendParticle(
                level,
                particle,
                centreX,
                centreY,
                centreZ - ANCHOR_MARKER_RADIUS
        );
    }

    private static void renderSourceLinks(
            ServerLevel level,
            BlockPos anchorPos,
            DebugIncursionAnchorEntity anchorEntity,
            DustParticleOptions particle
    ) {
        double anchorX =
                anchorPos.getX()
                        + 0.5D;

        double anchorY =
                anchorPos.getY()
                        + 0.5D;

        double anchorZ =
                anchorPos.getZ()
                        + 0.5D;

        for (DebugAnchorSourceLink sourceLink
                : anchorEntity.getSourceLinks()) {

            BlockPos sourcePos =
                    sourceLink.sourcePos();

            /*
             * End the line near the upper centre of the source block so it
             * remains readable above the terrain and source model.
             */
            double sourceX =
                    sourcePos.getX()
                            + 0.5D;

            double sourceY =
                    sourcePos.getY()
                            + 1.0D;

            double sourceZ =
                    sourcePos.getZ()
                            + 0.5D;

            renderLine(
                    level,
                    particle,
                    anchorX,
                    anchorY,
                    anchorZ,
                    sourceX,
                    sourceY,
                    sourceZ
            );
        }
    }

    private static void renderLine(
            ServerLevel level,
            DustParticleOptions particle,
            double startX,
            double startY,
            double startZ,
            double endX,
            double endY,
            double endZ
    ) {
        double differenceX =
                endX - startX;

        double differenceY =
                endY - startY;

        double differenceZ =
                endZ - startZ;

        double distance =
                Math.sqrt(
                        differenceX * differenceX
                                + differenceY * differenceY
                                + differenceZ * differenceZ
                );

        if (distance <= 0.0D) {
            sendParticle(
                    level,
                    particle,
                    startX,
                    startY,
                    startZ
            );

            return;
        }

        int particleCount =
                Math.max(
                        1,
                        (int) Math.ceil(
                                distance
                                        / LINE_PARTICLE_SPACING
                        )
                );

        particleCount =
                Math.min(
                        particleCount,
                        MAXIMUM_PARTICLES_PER_LINE
                );

        for (int particleIndex = 0;
             particleIndex <= particleCount;
             particleIndex++) {

            double progress =
                    particleIndex
                            / (double) particleCount;

            sendParticle(
                    level,
                    particle,
                    startX
                            + differenceX * progress,
                    startY
                            + differenceY * progress,
                    startZ
                            + differenceZ * progress
            );
        }
    }

    private static void sendParticle(
            ServerLevel level,
            DustParticleOptions particle,
            double x,
            double y,
            double z
    ) {
        level.sendParticles(
                particle,
                x,
                y,
                z,
                1,
                0.0D,
                0.0D,
                0.0D,
                0.0D
        );
    }

    private static Vector3f getFrontColour(
            int frontIndex
    ) {
        if (frontIndex < 0
                || frontIndex
                >= FRONT_COLOURS.size()) {
            return null;
        }

        return FRONT_COLOURS.get(
                frontIndex
        );
    }

    private DebugSourceVisualisation() {
    }
}