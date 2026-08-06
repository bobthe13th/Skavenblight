package org.ratden.skavenblight.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.block.ModBlocks;
import org.ratden.skavenblight.entity.ModEntities;
import org.ratden.skavenblight.entity.custom.ClanratEntity;
import org.ratden.skavenblight.network.WarpFluxGridManager;
import org.ratden.skavenblight.network.WarpFluxNetwork;
import org.ratden.skavenblight.world.NexusTracker;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.ratden.skavenblight.gametest.PathingRegionGameTests.check;

/**
 * End-to-end coverage proving a GROUP of clanrats can discover a diagonal gap, build a staircase
 * across it via the real region-discovery/connector/macro-project system, and reach a nexus - no
 * hand-fed instructions anywhere. See
 * docs/superpowers/specs/2026-07-31-staircase-siege-project-group-gametest-design.md for the full
 * design, including why the gap must be diagonal (TerrainEvaluator.determineMacroAction only
 * produces BUILD_STAIR for a diagonal direction - pure vertical produces PILLAR/SPIRAL/LADDER,
 * pure horizontal produces BRIDGE) and why this is scoped to BUILD_STAIR only (other SiegeProject
 * types are deferred to future plans reusing this same shape).
 *
 * <p>Unlike {@code SiegeConstructionActionsGameTests} (which hand-injects one instruction per
 * action and manually drives one goal instance), every test here uses the REAL production path:
 * a real {@code ActiveWarpstoneNexus} + {@code WarpFluxConduitBlock} (whose {@code onPlace} really
 * does register a real {@code WarpFluxNetwork}), real ticks (the network's own self-healing
 * bootstrap fires the first {@code TerritoryRegionMap.rebuild()} automatically), and real,
 * completely unmodified {@code ClanratEntity} mobs whose own {@code customServerAiStep()}
 * discovers the network and assigns flow fields to their own real goal list - exactly like live
 * gameplay. Nothing here calls {@code assignFlowField}/{@code setFlowField} directly.
 */
@GameTestHolder(Skavenblight.MODID)
@PrefixGameTestTemplate(false)
public class StaircaseSiegeGroupGameTests {

    private static final String BATCH = "staircase_siege_group";

    /**
     * Carves a solid floor patch {@code size}x{@code size} centered on {@code relativeCenter},
     * at Y = {@code relativeCenter.getY() - 1} (so the walkable surface is {@code relativeCenter}'s
     * own Y) - an isolated elevated platform, disconnected from anything else by the open air
     * around/below it that this template already has by default.
     */
    static void buildElevatedPlatform(GameTestHelper helper, BlockPos relativeCenter, int size) {
        int half = size / 2;
        int floorY = relativeCenter.getY() - 1;
        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
                helper.setBlock(new BlockPos(relativeCenter.getX() + dx, floorY, relativeCenter.getZ() + dz),
                        Blocks.STONE.defaultBlockState());
            }
        }
    }

    /**
     * Removes the ground floor's own walkable surface (and its support) directly beneath the
     * platform's own X/Z footprint, in a margin-{@code size} area (wider than the platform itself,
     * for a clean break with no partially-walkable edge cell left behind).
     *
     * <p>Discovered empirically: the ground floor this template's own default state provides spans
     * the WHOLE 96x64x96 footprint, including directly under wherever the elevated platform gets
     * built - a diagonal X/Z offset between the ground spawn and the platform does NOT, by itself,
     * rule out a competing PURE VERTICAL connector from the ground column immediately below the
     * platform (RegionGraph fires all 14 directions - 2 vertical + 12 diagonal - from every
     * boundary cell and keeps whichever's cheapest per region pair; a straight PILLAR/SPIRAL climb
     * from directly under the platform is a real, geometrically-valid alternative to the intended
     * diagonal BUILD_STAIR crossing, and was observed actually winning in one run). Carving the
     * ground away under the platform removes that alternative outright, rather than relying on
     * relative cost to keep favoring the diagonal path every time.
     */
    static void carveGroundBeneathPlatform(GameTestHelper helper, int relativeGroundFloorY,
                                                     BlockPos relativeNexusPos, int platformSize) {
        int half = platformSize / 2 + 2;
        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
                BlockPos column = new BlockPos(relativeNexusPos.getX() + dx, 0, relativeNexusPos.getZ() + dz);
                for (int y = 0; y <= relativeGroundFloorY; y++) {
                    helper.setBlock(new BlockPos(column.getX(), y, column.getZ()), Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    /**
     * Places a real nexus + a real conduit against it, matching real placement exactly:
     * {@code ActiveWarpstoneNexus} first, then {@code WarpFluxConduitBlock} one block over so its
     * own {@code onPlace} finds the nexus as an adjacent {@code WARP_FLUX} capability endpoint. If
     * {@code helper.setBlock} didn't trigger that callback the same way real placement does
     * (structure-paste update flags can skip it), falls back to calling
     * {@code WarpFluxGridManager.addConduit} directly - still real production code either way.
     * Returns the resulting network so the caller can restrict its territory (see
     * {@link #restrictTerritoryToMinimalArea}) before any level tick lets it bootstrap a region
     * scan.
     */
    static WarpFluxNetwork placeNexusAndConduit(GameTestHelper helper, BlockPos relativeNexusPos) {
        helper.setBlock(relativeNexusPos, ModBlocks.ACTIVE_WARPSTONE_NEXUS.get().defaultBlockState());
        BlockPos relativeConduitPos = relativeNexusPos.relative(Direction.EAST);
        helper.setBlock(relativeConduitPos, ModBlocks.WARP_FLUX_CONDUIT.get().defaultBlockState());

        ServerLevel level = helper.getLevel();
        BlockPos absoluteConduitPos = helper.absolutePos(relativeConduitPos);
        WarpFluxGridManager manager = WarpFluxGridManager.get(level);
        if (manager.getNetworkAt(absoluteConduitPos) == null) {
            manager.addConduit(level, absoluteConduitPos);
        }
        return manager.getNetworkAt(absoluteConduitPos);
    }

    /**
     * Overrides {@code network}'s territory (normally auto-computed by
     * {@code WarpFluxNetwork#updateTerritory} as a {@code Config.territoryChunkRadius}-chunk
     * "bubble" around its conduit/endpoints - 2 chunks/32 blocks by default) down to the EXACT
     * chunks spanning {@code relativeFrom} to {@code relativeTo}, with no extra buffer.
     *
     * <p>Discovered empirically (Task 2, first two attempts): the default radius reaches past a
     * template's own footprint into GameTest's own auto-encasement geometry (side walls + a
     * walkable ledge along their top, which {@code skyAccess = true} does NOT suppress - see
     * {@code PathingRegionGameTests}' class javadoc for the mechanism). That's a REAL,
     * separately-scanned Region purely because it's genuinely walkable terrain the region scanner
     * has no way to know is a test-framework artifact rather than intended geometry - and in that
     * geometry, {@code FlowFieldCalculator}'s cycle-breaking safeguard (which exists to stop mobs
     * looping forever on a genuine flow-field cycle) can end up choosing to drop the real, locked,
     * genuinely-needed {@code BUILD_STAIR} instruction instead of the bogus encasement-ledge one,
     * since cost alone can't tell "real" apart from "test-framework artifact".
     *
     * <p>This override alone is NOT sufficient on a template whose gap already consumes most of
     * the template's own width (confirmed the hard way on the original 32-wide, 2x2-chunk
     * `pathing_test_tall`, and again on an initial 64-wide `pathing_test_giant`): however precisely
     * the territory is computed, it still has to span the gap, and if the gap is a large fraction
     * of the template's own size, that span reaches the template's edge regardless. The real fix
     * had two parts together: (1) this precise override, so the territory is never bigger than it
     * needs to be, AND (2) a template generously larger than any single test's gap (see Task 1's
     * `pathing_test_giant`, 96x64x96, and every test's own geometry in Tasks 2-5, each kept at
     * least 16 blocks/one chunk from every template edge) - so the minimal territory this override
     * computes has genuine room to stay clear of the encasement. Neither alone was enough; both
     * together are. {@code WarpFluxNetwork#getTerritoryChunks} returns the live, mutable backing
     * set (not a defensive copy) specifically so this kind of direct test-side override is
     * possible without needing a new production API - confirmed by reading the field itself.
     *
     * <p>Must be called before the next level tick reaches {@code WarpFluxNetwork#tick}'s
     * self-healing bootstrap (i.e. immediately after {@link #placeNexusAndConduit}, synchronously,
     * within the same {@code @GameTest} method body) - the bootstrap takes a defensive
     * {@code Set.copyOf} snapshot of whatever territory is present at that moment.
     */
    static void restrictTerritoryToMinimalArea(GameTestHelper helper, WarpFluxNetwork network,
                                                         BlockPos relativeFrom, BlockPos relativeTo) {
        ChunkPos chunkFrom = new ChunkPos(helper.absolutePos(relativeFrom));
        ChunkPos chunkTo = new ChunkPos(helper.absolutePos(relativeTo));

        int minX = Math.min(chunkFrom.x, chunkTo.x);
        int maxX = Math.max(chunkFrom.x, chunkTo.x);
        int minZ = Math.min(chunkFrom.z, chunkTo.z);
        int maxZ = Math.max(chunkFrom.z, chunkTo.z);

        Set<ChunkPos> minimalTerritory = new HashSet<>();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                minimalTerritory.add(new ChunkPos(x, z));
            }
        }

        network.getTerritoryChunks().clear();
        network.getTerritoryChunks().addAll(minimalTerritory);
    }

    /**
     * Spawns {@code count} clanrats spaced {@code spacingZ} blocks apart along Z, starting at
     * {@code relativeFirstSpawn} - purely to avoid spawn-time overlap; every rat still has to
     * funnel through the same single diagonal connector to reach the nexus regardless of where on
     * the ground floor it starts, which is exactly what the contention tests (Tasks 3-4) rely on.
     */
    static List<ClanratEntity> spawnClanrats(GameTestHelper helper, BlockPos relativeFirstSpawn,
                                                       int count, int spacingZ) {
        List<ClanratEntity> rats = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            BlockPos relativeSpawn = relativeFirstSpawn.offset(0, 0, i * spacingZ);
            BlockPos absoluteSpawn = helper.absolutePos(relativeSpawn);
            ClanratEntity rat = new ClanratEntity(ModEntities.CLANRAT.get(), helper.getLevel());
            rat.setPos(absoluteSpawn.getX() + 0.5, absoluteSpawn.getY(), absoluteSpawn.getZ() + 0.5);
            helper.getLevel().addFreshEntity(rat);
            rats.add(rat);
        }
        return rats;
    }

    /**
     * Counts {@code Blocks.COBBLESTONE_STAIRS} within the inclusive bounding box between
     * {@code relativeFrom} and {@code relativeTo} (order-independent per axis) - the secondary
     * pass condition. "Rats arrived" alone can't distinguish a real staircase crossing from some
     * other action winning the race, or from an accidental shortcut through GameTest's own
     * territory encasement (see this file's class javadoc / the design spec's terrain-margin
     * note) - either would leave this count far below the expected diagonal step count.
     */
    static int countStairsInZone(GameTestHelper helper, BlockPos relativeFrom, BlockPos relativeTo) {
        int minX = Math.min(relativeFrom.getX(), relativeTo.getX());
        int maxX = Math.max(relativeFrom.getX(), relativeTo.getX());
        int minY = Math.min(relativeFrom.getY(), relativeTo.getY());
        int maxY = Math.max(relativeFrom.getY(), relativeTo.getY());
        int minZ = Math.min(relativeFrom.getZ(), relativeTo.getZ());
        int maxZ = Math.max(relativeFrom.getZ(), relativeTo.getZ());

        int count = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (helper.getBlockState(new BlockPos(x, y, z)).is(Blocks.COBBLESTONE_STAIRS)) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    /**
     * The shared pass condition for every test in this file: every rat alive and within
     * {@code arrivalRadius} of {@code relativeNexusPos}, AND at least {@code minStairsExpected}
     * stair blocks exist in the bounding box between {@code relativeGroundEdge} and
     * {@code relativeNexusPos}. Registered via {@code succeedWhen}, so it's retried every tick
     * (via {@code check}'s throw-to-retry convention already established in
     * {@code PathingRegionGameTests}) until either it holds or {@code timeoutTicks} is exceeded.
     */
    private static void awaitArrivalAndStaircase(GameTestHelper helper, List<ClanratEntity> rats,
                                                  BlockPos relativeNexusPos, BlockPos relativeGroundEdge,
                                                  double arrivalRadius, int minStairsExpected) {
        BlockPos absoluteNexusPos = helper.absolutePos(relativeNexusPos);

        helper.succeedWhen(() -> {
            // Counted BEFORE the arrival checks purely so the arrival failure message can carry it:
            // "rat never arrived" on its own can't distinguish "no staircase was ever built" from
            // "a staircase exists but the rats couldn't or wouldn't climb it", and those point at
            // completely different subsystems. Both checks still have to pass either way.
            int stairsFound = countStairsInZone(helper, relativeGroundEdge, relativeNexusPos);

            for (ClanratEntity rat : rats) {
                check(rat.isAlive(), "every rat must still be alive - one dying mid-crossing is a failure, not a pass");
                check(rat.blockPosition().closerThan(absoluteNexusPos, arrivalRadius),
                        rat + " has not yet arrived within " + arrivalRadius + " blocks of the nexus at "
                                + absoluteNexusPos + " (currently at " + rat.blockPosition() + "); "
                                + stairsFound + " stair block(s) built in the crossing zone so far");
            }

            check(stairsFound >= minStairsExpected,
                    "expected at least " + minStairsExpected + " COBBLESTONE_STAIRS blocks between "
                            + relativeGroundEdge + " and " + relativeNexusPos + ", found " + stairsFound
                            + " - rats arrived, but not clearly via a real staircase crossing");
        });
    }

    @GameTest(template = "pathing_test_giant", batch = BATCH, timeoutTicks = 6000, skyAccess = true)
    public static void testSingleRatBuildsStaircaseAcrossSmallGap(GameTestHelper helper) {
        NexusTracker.clearActiveNexus(helper.getLevel());

        BlockPos relativeGroundSpawn = new BlockPos(26, 2, 26);
        BlockPos relativeNexusPos = new BlockPos(40, 16, 26);

        buildElevatedPlatform(helper, relativeNexusPos, 5);
        carveGroundBeneathPlatform(helper, relativeGroundSpawn.getY() - 1, relativeNexusPos, 5);
        WarpFluxNetwork network = placeNexusAndConduit(helper, relativeNexusPos);
        restrictTerritoryToMinimalArea(helper, network, relativeGroundSpawn, relativeNexusPos);

        List<ClanratEntity> rats = spawnClanrats(helper, relativeGroundSpawn, 1, 2);

        awaitArrivalAndStaircase(helper, rats, relativeNexusPos, relativeGroundSpawn, 3.0, 7);
    }

    @GameTest(template = "pathing_test_giant", batch = BATCH, timeoutTicks = 5000, skyAccess = true)
    public static void testSmallGroupBuildsStaircaseAcrossSmallGap(GameTestHelper helper) {
        NexusTracker.clearActiveNexus(helper.getLevel());

        BlockPos relativeGroundSpawn = new BlockPos(26, 2, 26);
        BlockPos relativeNexusPos = new BlockPos(40, 16, 26);

        buildElevatedPlatform(helper, relativeNexusPos, 5);
        carveGroundBeneathPlatform(helper, relativeGroundSpawn.getY() - 1, relativeNexusPos, 5);
        WarpFluxNetwork network = placeNexusAndConduit(helper, relativeNexusPos);
        restrictTerritoryToMinimalArea(helper, network, relativeGroundSpawn, relativeNexusPos);

        List<ClanratEntity> rats = spawnClanrats(helper, relativeGroundSpawn, 4, 2);

        awaitArrivalAndStaircase(helper, rats, relativeNexusPos, relativeGroundSpawn, 3.0, 7);
    }

    @GameTest(template = "pathing_test_giant", batch = BATCH, timeoutTicks = 3000, skyAccess = true)
    public static void testLargeGroupBuildsStaircaseAcrossSmallGap(GameTestHelper helper) {
        NexusTracker.clearActiveNexus(helper.getLevel());

        BlockPos relativeGroundSpawn = new BlockPos(26, 2, 26);
        BlockPos relativeNexusPos = new BlockPos(40, 16, 26);

        buildElevatedPlatform(helper, relativeNexusPos, 5);
        carveGroundBeneathPlatform(helper, relativeGroundSpawn.getY() - 1, relativeNexusPos, 5);
        WarpFluxNetwork network = placeNexusAndConduit(helper, relativeNexusPos);
        restrictTerritoryToMinimalArea(helper, network, relativeGroundSpawn, relativeNexusPos);

        List<ClanratEntity> rats = spawnClanrats(helper, relativeGroundSpawn, 10, 2);

        awaitArrivalAndStaircase(helper, rats, relativeNexusPos, relativeGroundSpawn, 3.0, 7);
    }

    @GameTest(template = "pathing_test_giant", batch = BATCH, timeoutTicks = 20000, skyAccess = true)
    public static void testLargeGroupBuildsChainedStaircaseAcrossGiantGap(GameTestHelper helper) {
        NexusTracker.clearActiveNexus(helper.getLevel());

        BlockPos relativeGroundSpawn = new BlockPos(26, 2, 26);
        BlockPos relativeNexusPos = new BlockPos(70, 46, 26);

        buildElevatedPlatform(helper, relativeNexusPos, 5);
        carveGroundBeneathPlatform(helper, relativeGroundSpawn.getY() - 1, relativeNexusPos, 5);
        WarpFluxNetwork network = placeNexusAndConduit(helper, relativeNexusPos);
        restrictTerritoryToMinimalArea(helper, network, relativeGroundSpawn, relativeNexusPos);

        List<ClanratEntity> rats = spawnClanrats(helper, relativeGroundSpawn, 10, 2);

        awaitArrivalAndStaircase(helper, rats, relativeNexusPos, relativeGroundSpawn, 3.0, 20);
    }
}
