package org.ratden.skavenblight.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.entity.ModEntities;
import org.ratden.skavenblight.entity.custom.ClanratEntity;

import static org.ratden.skavenblight.gametest.PathingRegionGameTests.check;

/**
 * Documents a KNOWN, currently-unresolved limitation (not yet fixed - flagged for a deliberate
 * follow-up decision, not silently patched over): a completed one-wide {@code CARVED_STAIR}/{@code
 * AIR_STAIR} column - one {@code cobblestone_stairs} block per Y level, same (x,z) column, exactly
 * the geometry {@code SiegeInteractionHandler#constructSiegeBlock} produces for a chained vertical
 * run - cannot be RE-TRAVERSED from below by a fresh mob using real vanilla navigation, even though
 * the mob that originally built it climbed it fine one step at a time.
 *
 * <p>Real vanilla mob navigation independently agrees there's no way through a one-wide stacked
 * stair, confirming this isn't just a planning-model quirk - {@code PathStepEvaluator
 * .isWalkableTerrain}'s own foot/head clearance check already says "no" to standing under one. A
 * second wave of rats (or the same rat backtracking) sent up an already-built staircase this way
 * will genuinely get stuck a couple of levels up - this is a plausible mechanism behind the "flow
 * field routes a rat into the back of an existing stair" symptom reported against a live dump.
 * Fixing it means widening the column or adding periodic landings so a real mob's hitbox actually
 * clears each transition - {@code PlatformInserter} (Task 7) already inserts periodic platforms
 * into long construction chains for a related reason, but whether its current trigger conditions
 * also cover THIS specific one-wide-vertical-run shape is unconfirmed, not assumed fixed just
 * because PlatformInserter exists - deliberately left for a follow-up decision rather than expanded
 * on inline here.
 */
@GameTestHolder(Skavenblight.MODID)
@PrefixGameTestTemplate(false)
public class StackedStairColumnReTraversalGameTests {

    @GameTest(template = "pathing_test", timeoutTicks = 200, skyAccess = true)
    public static void clanratCannotYetReTraverseACompletedOneWideStairColumnFromBelow(GameTestHelper helper) {
        BlockPos relativeBase = new BlockPos(4, 2, 4);
        int treadCount = 6;

        helper.setBlock(relativeBase.below(), Blocks.STONE.defaultBlockState());
        for (int i = 0; i < treadCount; i++) {
            BlockPos treadPos = relativeBase.above(i);
            Direction facing = Direction.from2DDataValue(Math.abs(helper.absolutePos(treadPos).getY()) % 4);
            helper.setBlock(treadPos, Blocks.COBBLESTONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, facing));
        }
        // Solid floor to stand on at the very top, so "reached the top" has somewhere to land.
        helper.setBlock(relativeBase.above(treadCount), Blocks.STONE.defaultBlockState());

        BlockPos spawnPos = helper.absolutePos(relativeBase.below());
        ClanratEntity rat = new ClanratEntity(ModEntities.CLANRAT.get(), helper.getLevel());
        rat.setPos(spawnPos.getX() + 0.5, spawnPos.getY() + 1, spawnPos.getZ() + 0.5);
        helper.getLevel().addFreshEntity(rat);

        BlockPos absoluteTop = helper.absolutePos(relativeBase.above(treadCount));
        rat.getNavigation().moveTo(absoluteTop.getX() + 0.5, absoluteTop.getY() + 1, absoluteTop.getZ() + 0.5, 1.0);

        // Documents CURRENT behavior rather than racing to the first tick it holds: give
        // navigation the full timeout to prove it genuinely can't summit, not just that it hasn't
        // yet. If this ever starts passing the "still below the top" check, that means the
        // limitation described above has been fixed for real - go update/remove this test rather
        // than treating a sudden failure here as a regression to chase.
        helper.runAfterDelay(199, () -> {
            check(rat.isAlive(), "rat must still be alive after failing to summit the column");
            check(rat.blockPosition().getY() < absoluteTop.getY(),
                    "rat reached the top of the stacked column (" + rat.blockPosition() + ") - the known " +
                            "re-traversal limitation this test documents appears to be fixed; update or remove " +
                            "this test rather than leaving it stale");
            helper.succeed();
        });
    }
}
