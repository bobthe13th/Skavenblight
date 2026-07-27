package org.ratden.skavenblight.event.skavenIncursion.scenario.tutorial;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import org.ratden.skavenblight.block.entity.state.SourceState;
import org.ratden.skavenblight.event.skavenIncursion.action.mob.generic.SpawnWolfRats;
import org.ratden.skavenblight.event.skavenIncursion.action.source.generic.CreateTunnelSource;
import org.ratden.skavenblight.event.skavenIncursion.action.source.SetSourceState;
import org.ratden.skavenblight.event.skavenIncursion.action.source.SourcePlacement;
import org.ratden.skavenblight.event.skavenIncursion.director.IncursionTargetType;
import org.ratden.skavenblight.event.skavenIncursion.director.OverlapType;
import org.ratden.skavenblight.event.skavenIncursion.director.PressureProfile;
import org.ratden.skavenblight.event.skavenIncursion.leadership.LeaderGroup;
import org.ratden.skavenblight.event.skavenIncursion.leadership.LeaderGroupType;
import org.ratden.skavenblight.event.skavenIncursion.leadership.LeaderRank;
import org.ratden.skavenblight.event.skavenIncursion.leadership.LeadershipRegistry;
import org.ratden.skavenblight.event.skavenIncursion.planning.composition.IncursionMobCatalogue;
import org.ratden.skavenblight.event.skavenIncursion.planning.stratagem.StratagemCatalogue;
import org.ratden.skavenblight.event.skavenIncursion.scenario.ScenarioDefinition;
import org.ratden.skavenblight.event.skavenIncursion.scenario.ScenarioGoal;
import org.ratden.skavenblight.event.skavenIncursion.scenario.ScenarioPattern;
import org.ratden.skavenblight.event.skavenIncursion.scenario.SkavenScenario;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceDistanceProfile;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

public class TutorialCampAttack implements SkavenScenario {
    public static final ScenarioDefinition DEFINITION =
            new ScenarioDefinition(
                    "tutorial_camp_attack",
                    ScenarioPattern.AMBUSH,
                    SourceDistanceProfile.CLOSE,
                    ScenarioGoal.PRESSURE,
                    OverlapType.MAJOR,
                    PressureProfile.COMBAT,
                    EnumSet.of(
                            IncursionTargetType.PLAYER
                    ),
                    List.of(
                            new ScenarioDefinition.MobRosterEntry(
                                    IncursionMobCatalogue.WOLF_RAT,
                                    1.0D
                            )
                    ),
                    List.of(
                            StratagemCatalogue.STEADY_1
                    ),
                    0,
                    -1,
                    0,
                    0L,
                    false,
                    true,
                    false
            );

    private static final int WOLF_RAT_COUNT = 3;

    private final UUID instanceId;
    private final LeadershipRegistry leadershipRegistry;
    private final LeaderGroup packGroup;

    private final ServerLevel level;
    private final BlockPos targetPos;
    private final BlockPos sourcePos;
    private final IncursionTargetType targetType;

    private UUID sourceId;
    private int elapsedTicks;
    private boolean finished;

    public TutorialCampAttack(ServerLevel level, BlockPos targetPos, IncursionTargetType targetType) {
        this.instanceId = UUID.randomUUID();
        this.leadershipRegistry = new LeadershipRegistry(instanceId);

        this.packGroup = leadershipRegistry.createLeaderGroup(
                LeaderGroupType.PACK,
                LeaderRank.NONE
        );

        this.level = level;
        this.targetPos = targetPos.immutable();
        this.targetType = IncursionTargetType.PLAYER;
        this.sourcePos = SourcePlacement.forAssault(
                level,
                targetPos,
                IncursionTargetType.PLAYER
        ).immutable();

        this.sourceId = null;
        this.elapsedTicks = 0;
        this.finished = false;
    }

    public static String id() {
        return DEFINITION.id();
    }

    @Override
    public ScenarioDefinition getDefinition() {
        return DEFINITION;
    }

    @Override
    public String getId() {
        return DEFINITION.id();
    }

    @Override
    public ScenarioPattern getPattern() {
        return DEFINITION.pattern();
    }

    @Override
    public UUID getInstanceId() {
        return instanceId;
    }

    public static String getDebugName() {
        return "Tutorial Camp Attack";
    }

    public static String getDebugSummary() {
        return "Tutorial Camp Attack"
                + "\nTarget type: PLAYER"
                + "\nWolf rats: " + WOLF_RAT_COUNT
                + "\nScaling: none"
                + "\nRepeat: advancement-limited / metadata says false";
    }

    public static String getDebugTimeline() {
        return "Timeline: "
                + "tick 1 digging sound, "
                + "tick 30 tunnel appears, "
                + "tick 70 wolf rats spawn, "
                + "tick 150 tunnel collapses, "
                + "tick 170 attack ends";
    }

    @Override
    public void tick() {
        elapsedTicks++;

        if (elapsedTicks == 1) {
            level.playSound(
                    null,
                    targetPos,
                    SoundEvents.GRAVEL_BREAK,
                    SoundSource.HOSTILE,
                    2f,
                    0.7f
            );
        }

        if (elapsedTicks == 30) {
            level.playSound(
                    null,
                    sourcePos,
                    SoundEvents.WOLF_GROWL,
                    SoundSource.HOSTILE,
                    2f,
                    1f
            );

            sourceId = CreateTunnelSource.execute(
                    level,
                    sourcePos,
                    SourceState.ACTIVE,
                    leadershipRegistry.createPackContext(packGroup)
            );
        }

        if (elapsedTicks == 70) {
            SpawnWolfRats.execute(
                    level,
                    sourcePos,
                    WOLF_RAT_COUNT,
                    leadershipRegistry.createPackContext(packGroup),
                    sourceId
            );
        }

        if (elapsedTicks == 150) {
            SetSourceState.execute(level, sourcePos, SourceState.COLLAPSED);
        }

        if (elapsedTicks == 170) {
            finished = true;
        }
    }

    @Override
    public boolean isFinished() {
        return finished;
    }
}