package org.ratden.skavenblight.event.skavenIncursion.scenario.testing;

import net.minecraft.server.level.ServerLevel;
import org.ratden.skavenblight.block.entity.state.SourceState;
import org.ratden.skavenblight.event.skavenIncursion.director.IncursionTargetType;
import org.ratden.skavenblight.event.skavenIncursion.director.OverlapType;
import org.ratden.skavenblight.event.skavenIncursion.director.PressureProfile;
import org.ratden.skavenblight.event.skavenIncursion.leadership.LeadershipContext;
import org.ratden.skavenblight.event.skavenIncursion.planning.IncursionPlan;
import org.ratden.skavenblight.event.skavenIncursion.planning.composition.IncursionMobCatalogue;
import org.ratden.skavenblight.event.skavenIncursion.planning.stratagem.StratagemCatalogue;
import org.ratden.skavenblight.event.skavenIncursion.runtime.IncursionExecutionState;
import org.ratden.skavenblight.event.skavenIncursion.runtime.IncursionWaveController;
import org.ratden.skavenblight.event.skavenIncursion.runtime.WaveExecutionState;
import org.ratden.skavenblight.event.skavenIncursion.runtime.event.SourceDestroyedEvent;
import org.ratden.skavenblight.event.skavenIncursion.runtime.source.SourceExecutionState;
import org.ratden.skavenblight.event.skavenIncursion.scenario.ScenarioDefinition;
import org.ratden.skavenblight.event.skavenIncursion.scenario.ScenarioGoal;
import org.ratden.skavenblight.event.skavenIncursion.scenario.ScenarioPattern;
import org.ratden.skavenblight.event.skavenIncursion.scenario.SkavenScenario;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * Development-only planning-aware Scenario used to test the complete
 * incursion pipeline.
 *
 * CatDogRaid is not registered with the normal Director and must only be
 * started through explicit debug code.
 *
 * Its runtime now accepts any positive number of planned waves. Wave
 * progression is delegated to IncursionWaveController so this Scenario can
 * test multi-wave Stratagems without implementing reusable wave mechanics
 * itself.
 */
public class CatDogRaid implements SkavenScenario {

    public static final ScenarioDefinition DEFINITION =
            new ScenarioDefinition(
                    "cat_dog_raid",
                    ScenarioPattern.RAID,
                    ScenarioGoal.PRESSURE,
                    OverlapType.MAJOR,
                    PressureProfile.COMBAT,
                    EnumSet.of(
                            IncursionTargetType.PLAYER,
                            IncursionTargetType.NEXUS
                    ),
                    List.of(
                            new ScenarioDefinition.MobRosterEntry(
                                    IncursionMobCatalogue.WOLF_CAT,
                                    1.0D
                            ),
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
                    true,
                    true,
                    false
            );

    private static final int SOURCE_CREATION_DELAY_TICKS =
            20;

    private static final int FIRST_SPAWN_DELAY_TICKS =
            60;

    private static final int SPAWN_INTERVAL_TICKS =
            10;

    /**
     * Temporary fixed transition timing used until hybrid pacing is added.
     *
     * After a wave finishes scheduled delivery, its sources remain dormant
     * for this period before the next wave begins. The same period follows
     * the final wave before the controller reports complete.
     */
    private static final int WAVE_TRANSITION_GRACE_TICKS =
            60;

    /**
     * Prevents an uncreatable source or permanently failed spawn from leaving
     * a debug Scenario active forever.
     */
    private static final int MAXIMUM_RUNTIME_TICKS =
            4800;

    private final UUID instanceId;

    private final ServerLevel level;
    private final IncursionExecutionState incursionExecutionState;
    private final IncursionWaveController waveController;
    private final LeadershipContext leadershipContext;

    private int elapsedTicks;

    private int totalSourceDestructions;
    private int totalMobsCancelledByDestruction;

    private boolean sourcesCollapsed;
    private boolean finished;
    private boolean timedOut;

    public CatDogRaid(
            ServerLevel level,
            IncursionPlan incursionPlan
    ) {
        if (level == null) {
            throw new IllegalArgumentException(
                    "CatDogRaid level cannot be null."
            );
        }

        if (incursionPlan == null) {
            throw new IllegalArgumentException(
                    "CatDogRaid incursion plan cannot be null."
            );
        }

        this.level =
                level;

        this.instanceId =
                incursionPlan.getIncursionId();

        this.incursionExecutionState =
                new IncursionExecutionState(
                        incursionPlan
                );

        this.waveController =
                new IncursionWaveController(
                        incursionExecutionState,
                        SOURCE_CREATION_DELAY_TICKS,
                        FIRST_SPAWN_DELAY_TICKS,
                        SPAWN_INTERVAL_TICKS,
                        WAVE_TRANSITION_GRACE_TICKS
                );

        /*
         * Leadership hierarchy is not part of this runtime test yet.
         *
         * Sources and mobs still receive the correct Scenario/incursion ID so
         * source-destruction routing and ownership can be tested.
         */
        this.leadershipContext =
                LeadershipContext.debug(
                        instanceId
                );

        this.elapsedTicks = 0;

        this.totalSourceDestructions = 0;
        this.totalMobsCancelledByDestruction = 0;

        this.sourcesCollapsed = false;
        this.finished = false;
        this.timedOut = false;
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
    public UUID getInstanceId() {
        return instanceId;
    }

    @Override
    public ScenarioPattern getPattern() {
        return DEFINITION.pattern();
    }

    public IncursionExecutionState getIncursionExecutionState() {
        return incursionExecutionState;
    }

    public IncursionWaveController getWaveController() {
        return waveController;
    }

    /**
     * Convenience access for debug inspection.
     *
     * Returns null after every planned wave has completed.
     */
    public WaveExecutionState getCurrentWaveExecutionState() {
        return waveController.getCurrentWaveExecutionState();
    }

    public int getElapsedTicks() {
        return elapsedTicks;
    }

    public int getTotalSourceDestructions() {
        return totalSourceDestructions;
    }

    public int getTotalMobsCancelledByDestruction() {
        return totalMobsCancelledByDestruction;
    }

    public boolean hasTimedOut() {
        return timedOut;
    }

    @Override
    public void tick() {
        if (finished) {
            return;
        }

        elapsedTicks++;

        waveController.tick(
                level,
                leadershipContext
        );

        if (elapsedTicks >= MAXIMUM_RUNTIME_TICKS) {
            handleRuntimeTimeout();

            return;
        }

        if (!waveController.areAllWavesComplete()) {
            return;
        }

        collapseRemainingSources();

        sourcesCollapsed = true;
        finished = true;
    }

    /**
     * Receives a routed destruction event from ActiveIncursionManager.
     *
     * Only a currently recognised physical source incarnation is counted.
     * Duplicate events and events belonging to another Scenario are ignored.
     *
     * Destruction of an active source may cancel current-wave mobs.
     * Destruction of a dormant source remains meaningful but cancels no
     * current-wave delivery.
     */
    @Override
    public void onSourceDestroyed(
            SourceDestroyedEvent event
    ) {
        if (event == null) {
            throw new IllegalArgumentException(
                    "Source destruction event cannot be null."
            );
        }

        if (!instanceId.equals(
                event.scenarioInstanceId()
        )) {
            return;
        }

        IncursionWaveController.SourceDestructionResult result =
                waveController.handleSourceDestroyed(
                        event.runtimeSourceId()
                );

        if (!result.sourceRecognised()) {
            return;
        }

        totalSourceDestructions++;

        totalMobsCancelledByDestruction +=
                result.cancelledMobs();
    }

    @Override
    public boolean isFinished() {
        return finished;
    }

    /**
     * Retires every surviving physical source when the parent Scenario ends.
     *
     * Both ACTIVE and DORMANT sources become COLLAPSED. Changing SourceState
     * retains the same tunnel block and therefore does not emit a
     * SourceDestroyedEvent.
     */
    private void collapseRemainingSources() {
        if (sourcesCollapsed) {
            return;
        }

        for (SourceExecutionState sourceExecutionState
                : incursionExecutionState
                .getSourceExecutionStates()) {

            if (!sourceExecutionState.isAvailable()) {
                continue;
            }

            sourceExecutionState.setCurrentSourceState(
                    level,
                    SourceState.COLLAPSED
            );
        }
    }

    private void handleRuntimeTimeout() {
        waveController.cancelAllRemainingMobs();

        collapseRemainingSources();

        sourcesCollapsed = true;
        timedOut = true;
        finished = true;
    }
}