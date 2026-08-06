package org.ratden.skavenblight.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.ai.pathing.FlowStep;
import org.ratden.skavenblight.ai.pathing.PathAction;
import org.ratden.skavenblight.ai.pathing.region.RegionFlowField;
import org.ratden.skavenblight.entity.custom.ClanratEntity;
import org.ratden.skavenblight.network.WarpFluxNetwork;
import org.ratden.skavenblight.world.NexusTracker;

import java.util.List;

import static org.ratden.skavenblight.gametest.PathingRegionGameTests.check;
import static org.ratden.skavenblight.gametest.StaircaseSiegeGroupGameTests.buildElevatedPlatform;
import static org.ratden.skavenblight.gametest.StaircaseSiegeGroupGameTests.carveGroundBeneathPlatform;
import static org.ratden.skavenblight.gametest.StaircaseSiegeGroupGameTests.placeNexusAndConduit;
import static org.ratden.skavenblight.gametest.StaircaseSiegeGroupGameTests.restrictTerritoryToMinimalArea;
import static org.ratden.skavenblight.gametest.StaircaseSiegeGroupGameTests.spawnClanrats;

/**
 * The design doc's own explicit requirement: "write a test proving this before considering the
 * feature done, or you will ship a flow field that lies forever about a griefed project." Reuses
 * {@link StaircaseSiegeGroupGameTests}' real nexus + conduit + restricted-territory setup (same
 * production path - no hand-fed instructions) rather than duplicating it.
 *
 * <p>What's actually under test: {@code TerritoryRegionMap.plannedStateOverride()} makes an ACTIVE
 * project's planned final state authoritative for the {@code TerrainSnapshot} every recalculation
 * (full rebuild AND steady-state dirty-region recompute alike) is fed - see that method's own doc.
 * That authority is correct for a step NOT YET built (planning should treat a mid-construction site
 * as passable so the flow field doesn't route everyone on a detour around work already scheduled).
 * It is a real lie once a step that WAS built gets externally destroyed while its owning project is
 * still active (other steps still pending) - {@code plannedStepAt} still finds it in the build order
 * regardless of completion, so nothing stops the override from claiming a real hole is still solid
 * ground forever, across every future recalculation, since the snapshot never even sees the real
 * block state at that position to know otherwise.
 */
@GameTestHolder(Skavenblight.MODID)
@PrefixGameTestTemplate(false)
public class SiegeProjectGriefRecoveryGameTests {

    @GameTest(template = "pathing_test_giant", timeoutTicks = 6000, skyAccess = true)
    public static void playerBreakingAHalfBuiltProjectStillTriggersRealRecalculation(GameTestHelper helper) {
        NexusTracker.clearActiveNexus(helper.getLevel());

        BlockPos relativeGroundSpawn = new BlockPos(26, 2, 26);
        BlockPos relativeNexusPos = new BlockPos(40, 16, 26);

        buildElevatedPlatform(helper, relativeNexusPos, 5);
        carveGroundBeneathPlatform(helper, relativeGroundSpawn.getY() - 1, relativeNexusPos, 5);
        WarpFluxNetwork network = placeNexusAndConduit(helper, relativeNexusPos);
        restrictTerritoryToMinimalArea(helper, network, relativeGroundSpawn, relativeNexusPos);

        List<ClanratEntity> rats = spawnClanrats(helper, relativeGroundSpawn, 1, 2);
        ClanratEntity rat = rats.get(0);
        ServerLevel level = helper.getLevel();

        BlockPos[] griefedPos = new BlockPos[1];

        helper.startSequence()
                // Phase 1: wait until at least one stair is built, but the rat hasn't arrived yet -
                // a genuinely PARTIAL, still-active project (other steps still unbuilt, so it stays
                // in TerritoryRegionMap's activeProjects list and plannedStepAt still claims every
                // one of its cells, including the one about to be griefed).
                .thenWaitUntil(() -> {
                    check(rat.isAlive(), "the rat must still be alive while the project is under construction");
                    BlockPos firstStair = findFirstStair(helper, relativeGroundSpawn, relativeNexusPos);
                    check(firstStair != null, "at least one COBBLESTONE_STAIRS block must exist in the crossing zone before griefing it");
                    BlockPos absoluteNexus = helper.absolutePos(relativeNexusPos);
                    check(!rat.blockPosition().closerThan(absoluteNexus, 3.0),
                            "the rat must not have arrived yet - griefing an already-finished crossing proves nothing");
                    griefedPos[0] = firstStair;
                })
                .thenExecute(() -> {
                    // As the TEST HARNESS, not the rat: a raw level.destroyBlock call standing in
                    // for "a player broke it" - see this test's own class javadoc.
                    check(level.getBlockState(griefedPos[0]).is(Blocks.COBBLESTONE_STAIRS),
                            "setup sanity: the position captured in phase 1 must still be the stair we found");
                    level.destroyBlock(griefedPos[0], false);
                    check(!level.getBlockState(griefedPos[0]).is(Blocks.COBBLESTONE_STAIRS),
                            "setup sanity: the grief itself must have actually removed the stair");
                })
                // Phase 2: within a bounded number of ticks, the flow field's own instruction at the
                // griefed position must revert to reflect the REAL, now-broken world state, not the
                // project's stale planned-final-state override.
                .thenWaitUntil(() -> {
                    RegionFlowField field = network.getRegionMap().getRegionFlowFieldFor(griefedPos[0]);
                    check(field != null, "the griefed position must still resolve to a real region flow field");
                    FlowStep next = field.getNextStep(level, griefedPos[0]);
                    check(next != null && next.action() != PathAction.WALK,
                            "the flow field still reports " + (next == null ? "no instruction" : next.action())
                                    + " at the griefed position " + griefedPos[0]
                                    + " - it must revert to a real construction action once the built step is destroyed");
                })
                .thenSucceed();
    }

    /** First {@code COBBLESTONE_STAIRS} block found scanning the crossing zone, or null. */
    private static BlockPos findFirstStair(GameTestHelper helper, BlockPos relativeFrom, BlockPos relativeTo) {
        int minX = Math.min(relativeFrom.getX(), relativeTo.getX());
        int maxX = Math.max(relativeFrom.getX(), relativeTo.getX());
        int minY = Math.min(relativeFrom.getY(), relativeTo.getY());
        int maxY = Math.max(relativeFrom.getY(), relativeTo.getY());
        int minZ = Math.min(relativeFrom.getZ(), relativeTo.getZ());
        int maxZ = Math.max(relativeFrom.getZ(), relativeTo.getZ());

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos relative = new BlockPos(x, y, z);
                    if (helper.getBlockState(relative).is(Blocks.COBBLESTONE_STAIRS)) {
                        return helper.absolutePos(relative);
                    }
                }
            }
        }
        return null;
    }
}
