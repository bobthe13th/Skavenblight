package org.ratden.skavenblight.gametest;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.ai.pathing.region.Region;
import org.ratden.skavenblight.ai.pathing.region.RegionConnector;
import org.ratden.skavenblight.ai.pathing.region.TerritoryRegionMap;
import org.slf4j.Logger;

import java.util.List;
import java.util.Set;

/**
 * GameTest scaffolding for the region/graph-level pathing subsystem. Every later task in the
 * region-pathing-hardening plan (4, 5, 6, 7, 8, 9, 12) adds a {@code @GameTest} method to this
 * same file, reusing {@code skavenblight:pathing_test} (a flat 32x8x32 platform: solid stone
 * floor, open air above - see the structure's own generation note in task-2-report.md for how it
 * was produced without a dev client).
 *
 * <p>Two non-obvious things every method added here MUST account for, discovered the hard way
 * while writing {@link #testSingleConnectedRegion}:
 *
 * <ul>
 * <li><b>{@code skyAccess = true} is mandatory on every {@code @GameTest} in this class.</b>
 * Omitting it (the brief's original snippet did) makes {@code GameTestInfo.prepareTestStructure()}
 * auto-encase the structure in an invisible barrier box, including a ROOF one block above the
 * structure's own top layer. Because {@code TerritoryRegionMap}/{@code RegionScanner} deliberately
 * scan the FULL vertical build-height column (not just the structure's own 8 layers - see this
 * mod's top-level CLAUDE.md on why), the flat top of that barrier roof - same XZ footprint as the
 * platform, one Y above it - registers as its own fully-connected, disconnected "floor", producing
 * a bogus second Region purely as a test-harness artifact. Confirmed via direct block-state
 * inspection: with {@code skyAccess} left at its default (false), a barrier appeared exactly one
 * block above the structure's topmost captured layer, and the region scanner reported a second
 * 256-cell region sitting on top of it. Setting {@code skyAccess = true} suppresses the roof
 * (see {@code StructureUtils#encaseStructure}'s {@code p_326950_} argument) and the test collapses
 * back to the single intended region.</li>
 * <li><b>Helper-relative Y is template-relative Y + 1.</b> {@code StructureBlockEntity}'s
 * {@code structurePos} field defaults to {@code (0, 1, 0)} (not {@code (0,0,0)}) and is never
 * overridden by the GameTest harness's own structure-block setup, so the ACTUAL paste origin is
 * one block above {@code structureBlockPos}; but {@code GameTestHelper#absolutePos}/{@code #setBlock}
 * add the relative coordinate directly onto {@code structureBlockPos} with no such offset. Net
 * effect: {@code helper.setBlock(new BlockPos(x, 0, z), ...)} targets the STRUCTURE'S OWN
 * template-relative y=-1 (below the floor), helper y=1 targets the template's floor (y=0), and
 * helper y=2 targets the template's first open/walkable layer (y=1) - i.e. every helper Y is one
 * higher than the corresponding template Y. {@link #testSingleConnectedRegion} places its (inert -
 * the floor there was already stone) nexus marker at helper y=1, which is why the test still reads
 * as targeting "one above the floor" in its own comments despite that being template y=0. Any
 * later test carving gaps/walls into the floor must add 1 to whatever Y it means in template terms.</li>
 * <li><b>Territory wider than one chunk picks up GameTest's own encasement as bogus extra
 * regions.</b> {@code skyAccess = true} (above) only suppresses the encasement's ROOF; its side
 * walls and a floor beneath the whole structure+padding bounding box remain. Confirmed
 * empirically while writing {@link #testTwoDisconnectedRegionsGetOneConnector}: a territory
 * spanning multiple chunks (needed to see both sides of a >16-block-wide split) picked up (a) a
 * walkable "basement" ledge one layer below the structure's own floor, spanning the padding
 * around the structure, wherever that floor's overhead clearance is open - which is EVERYWHERE
 * in the padding (nothing of ours blocks it there) and also under any gap WE carve into our own
 * floor (removing the floor's solidity opens head clearance for the layer below it too), and (b)
 * a one-cell-wide walkable ledge along the TOP of the encasement's side walls, one ring outside
 * the structure's own footprint. Both are real, fully-connected Regions by the scanner's own
 * rules - not a bug in the scanner, just terrain we didn't intend to test. Because GameTest
 * doesn't chunk-align structure placement, a territory that spans the structure's full 0..31
 * necessarily spills into this padding on at least one side. The fix used here: keep territory
 * to exactly ONE chunk (as {@link #testSingleConnectedRegion} already did) so the encasement's
 * padding is never in scope at all, and derive every other coordinate from THAT chunk's actual
 * (run-dependent) alignment rather than the structure's own fixed 0..31 - see
 * {@link #anchorChunkFor} for the reusable helper that does this (including the alignment proof)
 * and {@link #testTwoDisconnectedRegionsGetOneConnector} for an example caller. Later tasks
 * adding their own {@code @GameTest} methods to this file should call {@link #anchorChunkFor}
 * rather than re-deriving this by hand.</li>
 * <li><b>A single-layer floor carve still leaves a walkable cell directly underneath it.</b>
 * Removing only the floor block (helper y=1) opens head clearance for the layer below (helper
 * y=0) too, and that layer has its own solid support courtesy of the same encasement floor
 * mentioned above - so a "gap" carved only at helper y=1 registers as two disconnected pieces
 * PLUS a third sliver Region exactly matching the gap's own footprint, one layer down. Carving
 * the full vertical range down to {@code level.getMinBuildHeight()} (not just the floor layer)
 * removes the support entirely and avoids this.</li>
 * </ul>
 */
@GameTestHolder(Skavenblight.MODID)
@PrefixGameTestTemplate(false)
public class PathingRegionGameTests {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Throws (so {@code succeedWhen} keeps retrying) until {@code condition} holds. */
    static void check(boolean condition, String message) {
        if (!condition) throw new GameTestAssertException(message);
    }

    /**
     * A single chunk to use as territory (see the class javadoc's "territory wider than one
     * chunk" bullet for why one chunk is deliberate), plus that chunk's own structure-relative
     * origin ({@code baseX}/{@code baseZ}) so callers can place trench/nexus/etc. geometry as
     * OFFSETS into the chunk's local 0..15 window instead of against the structure's fixed
     * 0..31 - the whole point being that this window's actual position varies per run (GameTest
     * doesn't chunk-align structure placement) and callers should never need to know or assume
     * where it lands.
     */
    private record ChunkAnchor(ChunkPos chunk, int baseX, int baseZ) {}

    /**
     * Picks the chunk containing structure-relative point (16, *, 16) and returns it as a
     * {@link ChunkAnchor}. This exact anchor point is load-bearing, not arbitrary - the
     * following holds for ANY GameTest placement offset:
     *
     * <p>Let {@code r = structureOriginX mod 16} (r in [0,15]). The chunk containing world-x =
     * {@code structureOriginX + 16} starts at relative x = {@code 16 - r} (in [1,16]) and ends at
     * relative x = {@code 31 - r} (in [16,31]) - i.e. that chunk's 16-wide window is ALWAYS
     * entirely contained in [1,31], regardless of alignment, never touching the structure's own
     * edges, let alone the encasement padding beyond them (see the class javadoc's "territory
     * wider than one chunk" bullet for what that padding contaminates). The same proof applies to
     * z. Confirmed empirically across multiple {@code runGameTestServer} invocations, each
     * placing the structure at a different world position/chunk-alignment offset (see
     * task-4-report.md) - this always produced a single clean chunk fully inside the structure's
     * own footprint. This is also why {@link #testSingleConnectedRegion}'s nexus at relative
     * (16,1,16) was never contaminated by the padding despite nobody having diagnosed why at the
     * time it was written.
     */
    private static ChunkAnchor anchorChunkFor(GameTestHelper helper) {
        BlockPos structureOrigin = helper.absolutePos(BlockPos.ZERO);
        ChunkPos chunk = new ChunkPos(helper.absolutePos(new BlockPos(16, 2, 16)));
        int baseX = chunk.getMinBlockX() - structureOrigin.getX();
        int baseZ = chunk.getMinBlockZ() - structureOrigin.getZ();
        return new ChunkAnchor(chunk, baseX, baseZ);
    }

    /**
     * Structure-relative Y of the world's actual minimum build height, for full-depth carves
     * (see the class javadoc's "single-layer floor carve" bullet for why a floor-only carve
     * isn't enough).
     */
    private static int minRelY(GameTestHelper helper) {
        return helper.getLevel().getMinBuildHeight() - helper.absolutePos(BlockPos.ZERO).getY();
    }

    @GameTest(template = "pathing_test", timeoutTicks = 400, skyAccess = true)
    public static void testSingleConnectedRegion(GameTestHelper helper) {
        BlockPos relativeNexusPos = new BlockPos(16, 1, 16);
        helper.setBlock(relativeNexusPos, Blocks.STONE.defaultBlockState());
        BlockPos nexusPos = helper.absolutePos(relativeNexusPos);

        TerritoryRegionMap regionMap = new TerritoryRegionMap();
        Set<ChunkPos> territory = Set.of(new ChunkPos(nexusPos));
        regionMap.rebuild(helper.getLevel(), territory, nexusPos);

        helper.succeedWhen(() -> {
            check(!regionMap.isCalculating(), "region map still calculating");
            List<Region> regions = regionMap.getRegionIndex().getRegions();
            check(regions.size() == 1,
                    "expected exactly 1 region on a flat open platform, found " + regions.size());
            check(regions.get(0).cellCount() > 100,
                    "expected the single region to cover most of the open floor, found only "
                            + regions.get(0).cellCount() + " cells");
        });
    }

    /**
     * End-to-end validation of the headline region-graph requirement: a trench splits the floor
     * into two disconnected regions, {@code RegionGraph} proposes exactly one connector across
     * it, and {@code RegionRouteTree} marks the far side reachable via that connector.
     *
     * <p>The task-4 brief this test was transcribed from used helper-Y=0 for the trench carve,
     * helper-Y=1 for the nexus marker, and {@code Set.of(new ChunkPos(nexusPos))} (a single
     * chunk) as territory, with the trench/nexus/far-side placed at fixed structure-relative
     * coordinates (x=14..17, nexus x=6, far side x>17). None of that survived contact with a real
     * run - see the class javadoc's last two bullets for the two terrain artifacts this
     * discovered (the encasement's padding-side regions, and the floor-only-carve underside
     * sliver). The geometry actually used here fixes both by staying inside a single chunk (like
     * {@link #testSingleConnectedRegion}) whose alignment is discovered at runtime via
     * {@link #anchorChunkFor} rather than assumed - see that method's own javadoc for the
     * alignment proof (why anchoring on relative (16, *, 16) always keeps the chosen chunk fully
     * inside the structure's own footprint, regardless of where GameTest actually places it this
     * run). Every coordinate below is an offset from that chunk's own {@code baseX}/{@code baseZ},
     * not against the structure's fixed 0..31. The trench is carved full-depth (down to
     * {@link #minRelY}, not just the floor layer) per the class javadoc's underside-sliver note.
     *
     * <p><b>On the connector-count assertion below:</b> {@code RegionGraph.build} dedups
     * candidate connectors per unordered region-id pair ({@code bestPerPair}/{@code pairKey}), so
     * with exactly 2 regions the count is guaranteed to be AT MOST 1 - it cannot double-count the
     * one possible pair. It does NOT guarantee the count is exactly 1 rather than 0: whether
     * {@code tryTrace} actually finds a connector at all depends on the trench's width/depth
     * being within {@code SiegeLineTracer}'s reach and {@code RegionGraph.MAX_CHAIN_HOPS}, a
     * property of this test's geometry, not of the dedup map. That it comes out to exactly 1 here
     * is confirmed empirically (see task-4-report.md's two independent runs), not derived from
     * the dedup guarantee alone.
     */
    @GameTest(template = "pathing_test", timeoutTicks = 600, skyAccess = true)
    public static void testTwoDisconnectedRegionsGetOneConnector(GameTestHelper helper) {
        ChunkAnchor anchor = anchorChunkFor(helper);
        Set<ChunkPos> territory = Set.of(anchor.chunk());
        int baseX = anchor.baseX();
        int baseZ = anchor.baseZ();

        // A 4-block-wide trench (exceeds LEAP's documented 1-block-only range - see
        // SiegeNode.SiegeAction.LEAP's javadoc - so only a BUILD_BRIDGE-type connector can cross
        // it), splitting the chunk into a nexus side (local x 0-5) and a far side (local x 10-15).
        // Carved full-depth (see method javadoc) so no walkable sliver survives underneath it.
        int minRelY = minRelY(helper);
        for (int lx = 6; lx <= 9; lx++) {
            for (int lz = 0; lz <= 15; lz++) {
                for (int y = minRelY; y <= 1; y++) {
                    helper.setBlock(new BlockPos(baseX + lx, y, baseZ + lz), Blocks.AIR.defaultBlockState());
                }
            }
        }

        // helper-Y=2 (template-Y=1, the walkable layer) so the block it occupies goes solid
        // (matching how a real Nexus block replaces a walkable cell) while its horizontal
        // neighbor - nexusPos.north(), the probe used below - stays open air over an intact floor
        // and resolves to a real region id (placing the marker at helper-Y=1 instead would embed
        // it in the already-stone floor, and north() of an embedded marker sits on that same
        // solid floor too - not a member of any region, so regionIdAt returns null and the
        // probe's int != Integer comparison below throws on unboxing).
        BlockPos relativeNexusPos = new BlockPos(baseX + 1, 2, baseZ + 8);
        helper.setBlock(relativeNexusPos, Blocks.STONE.defaultBlockState());
        BlockPos nexusPos = helper.absolutePos(relativeNexusPos);

        TerritoryRegionMap regionMap = new TerritoryRegionMap();
        regionMap.rebuild(helper.getLevel(), territory, nexusPos);

        helper.succeedWhen(() -> {
            check(!regionMap.isCalculating(), "region map still calculating");
            List<Region> regions = regionMap.getRegionIndex().getRegions();
            check(regions.size() == 2, "expected exactly 2 regions (split by the trench), found " + regions.size());
            check(regionMap.getRegionGraph().getAllConnectors().size() == 1,
                    "expected exactly 1 connector between the 2 regions, found "
                            + regionMap.getRegionGraph().getAllConnectors().size());

            int farRegionId = regions.stream().mapToInt(Region::getId)
                    .filter(id -> id != regionMap.getRegionIndex().regionIdAt(nexusPos.north())) // arbitrary non-nexus-side probe
                    .findFirst().orElseThrow();
            check(regionMap.getRouteTree().isReachable(farRegionId),
                    "far region should be reachable via the planned connector");
        });
    }

    /**
     * The literal headline requirement: with three regions in a line - C, then B, then A (the
     * nexus region) - and NO connector geometrically possible directly between C and A, the route
     * tree must route C through B (the intermediate region) rather than treat C as unreachable or
     * invent some other path. {@code RegionRouteTree.compute} is a plain Dijkstra over whatever
     * connectors {@code RegionGraph.build} actually discovered (see that method's own javadoc), so
     * the only way to make "C's parent is B" a SAFE assertion - rather than one that merely
     * happens to hold today - is to make a direct C-A connector geometrically impossible, not just
     * more expensive. That's what forces the routing decision structurally instead of leaving it
     * to a cost comparison against a connector that might or might not exist (see the class
     * javadoc bullet on {@code RegionGraph}'s per-pair dedup NOT guaranteeing a connector exists at
     * all for a given pair).
     *
     * <p>Kept within a single {@link #anchorChunkFor} chunk (16x16) like
     * {@link #testTwoDisconnectedRegionsGetOneConnector}, for the same reason - see that test's
     * javadoc and the class javadoc's "territory wider than one chunk" bullet. All three regions
     * plus both gaps have to fit in that one 16-wide (local x) window:
     *
     * <pre>
     * local x:  0  1  2 | 3  4  5  6  7 | 8  9 10 | 11 12 | 13 14 15
     *           `---C---'  `--gapCB(5)--'  `--B---'  `gapBA`  `---A---'
     * </pre>
     *
     * <p>Region C (local x 0-2) and region A (local x 13-15, containing the nexus) are separated
     * by the full 16-wide corridor; region B (local x 8-10) sits in the middle. The gap on B's far
     * side from C is 5 blocks wide ("long/expensive" - more BUILD_BRIDGE steps, matching the
     * brief's "wide/expensive-looking" framing), the gap on B's near side to A is 2 blocks wide
     * ("short/cheap"). Both gaps are carved full-depth (down to {@link #minRelY}, not just the
     * floor layer) per the class javadoc's underside-sliver note, exactly like the two-region
     * test's trench.
     *
     * <p>A connector bridging C directly to A is expected to be geometrically unreachable, not
     * merely more expensive than the B-adjacent route: {@code RegionGraph.tryTrace} traces a
     * straight line (fixed direction per call - see that method's javadoc) from a boundary cell
     * and returns as soon as it lands on ANY region's walkable ground, and since B's own floor
     * spans the ENTIRE local-x range between the two gaps at every local z (this territory's full
     * width), a ray cast from C toward A's side should land on B's floor first and register a C-B
     * connector rather than reaching A in the same trace - it would need to clear OVER B's floor
     * without ever touching it, which shouldn't be possible for a ray starting at the same
     * coplanar floor height B itself sits at (a flat single-story platform). This is reasoning
     * about {@code tryTrace}'s behavior, not a proven enumeration of all 14 trace directions
     * through {@code TerrainEvaluator.determineMacroAction} - what actually confirms it is the
     * connector list logged below: across two independent runs (different structure placement
     * offsets each time, per {@link #anchorChunkFor}'s alignment proof), it contained only C-B and
     * B-A connectors, never C-A - see task-5-report.md for both runs' full connector/hop-cost dumps.
     */
    @GameTest(template = "pathing_test", timeoutTicks = 600, skyAccess = true)
    public static void testThreeRegionsRouteThroughCheaperIntermediateHop(GameTestHelper helper) {
        ChunkAnchor anchor = anchorChunkFor(helper);
        Set<ChunkPos> territory = Set.of(anchor.chunk());
        int baseX = anchor.baseX();
        int baseZ = anchor.baseZ();
        int minRelY = minRelY(helper);

        // Gap C|B: local x 3-7 (5 wide, "long/expensive"). Gap B|A: local x 11-12 (2 wide,
        // "short/cheap"). Both carved full-depth (see method javadoc) so no walkable sliver
        // survives underneath either one.
        for (int lx = 3; lx <= 7; lx++) {
            for (int lz = 0; lz <= 15; lz++) {
                for (int y = minRelY; y <= 1; y++) {
                    helper.setBlock(new BlockPos(baseX + lx, y, baseZ + lz), Blocks.AIR.defaultBlockState());
                }
            }
        }
        for (int lx = 11; lx <= 12; lx++) {
            for (int lz = 0; lz <= 15; lz++) {
                for (int y = minRelY; y <= 1; y++) {
                    helper.setBlock(new BlockPos(baseX + lx, y, baseZ + lz), Blocks.AIR.defaultBlockState());
                }
            }
        }

        // Nexus marker inside region A (local x 13-15), helper-Y=2 (the walkable layer) - same
        // convention as testTwoDisconnectedRegionsGetOneConnector's nexus placement.
        BlockPos relativeNexusPos = new BlockPos(baseX + 14, 2, baseZ + 8);
        helper.setBlock(relativeNexusPos, Blocks.STONE.defaultBlockState());
        BlockPos nexusPos = helper.absolutePos(relativeNexusPos);

        // Probes into region C (local x 0-2) and region B (local x 8-10), untouched walkable
        // cells (helper-Y=2) well clear of either trench edge.
        BlockPos probeC = helper.absolutePos(new BlockPos(baseX + 1, 2, baseZ + 8));
        BlockPos probeB = helper.absolutePos(new BlockPos(baseX + 9, 2, baseZ + 8));

        TerritoryRegionMap regionMap = new TerritoryRegionMap();
        regionMap.rebuild(helper.getLevel(), territory, nexusPos);

        helper.succeedWhen(() -> {
            check(!regionMap.isCalculating(), "region map still calculating");
            List<Region> regions = regionMap.getRegionIndex().getRegions();
            check(regions.size() == 3, "expected 3 regions (A/B/C), found " + regions.size());

            Integer regionC = regionMap.getRegionIndex().regionIdAt(probeC);
            check(regionC != null, "region C probe position isn't in any region - adjust the probe");
            Integer regionB = regionMap.getRegionIndex().regionIdAt(probeB);
            check(regionB != null, "region B probe position isn't in any region - adjust the probe");

            // Evidence dump: every region's bounds/cell count plus the route tree's hop cost and
            // parent for it, and every connector RegionGraph actually built (with cost) - so the
            // C->B->A routing decision is visible in the log, not just asserted.
            for (Region region : regions) {
                LOGGER.info("[Skavenblight][test] region {}: min={} max={} cells={} hopCost={} parent={}",
                        region.getId(), region.getMin(), region.getMax(), region.cellCount(),
                        regionMap.getRouteTree().getHopCost(region.getId()),
                        regionMap.getRouteTree().getParentRegion(region.getId()));
            }
            for (RegionConnector connector : regionMap.getRegionGraph().getAllConnectors()) {
                LOGGER.info("[Skavenblight][test] connector region{}<->region{} cost={}",
                        connector.regionA(), connector.regionB(), connector.cost());
            }
            LOGGER.info("[Skavenblight][test] regionC={} regionB={} rootRegion={}",
                    regionC, regionB, regionMap.getRouteTree().getRootRegionId());

            Integer parentOfC = regionMap.getRouteTree().getParentRegion(regionC);
            check(parentOfC != null, "region C should be reachable through some parent hop");
            check(regionC != regionB && java.util.Objects.equals(parentOfC, regionB),
                    "region C's route should hop through region B (found parent=" + parentOfC + ", regionB=" + regionB + ")");
        });
    }
}
