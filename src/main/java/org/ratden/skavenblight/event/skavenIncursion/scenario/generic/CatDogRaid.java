package org.ratden.skavenblight.event.skavenIncursion.scenario.generic;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.ratden.skavenblight.block.entity.state.SourceState;
import org.ratden.skavenblight.event.skavenIncursion.director.IncursionTargetType;
import org.ratden.skavenblight.event.skavenIncursion.director.OverlapType;
import org.ratden.skavenblight.event.skavenIncursion.director.PressureProfile;
import org.ratden.skavenblight.event.skavenIncursion.leadership.LeadershipContext;
import org.ratden.skavenblight.event.skavenIncursion.planning.IncursionPlan;
import org.ratden.skavenblight.event.skavenIncursion.planning.budget.complexity.TestPackLeaderComplexity;
import org.ratden.skavenblight.event.skavenIncursion.planning.composition.IncursionMobCatalogue;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceDistanceProfile;
import org.ratden.skavenblight.event.skavenIncursion.planning.stratagem.StratagemCatalogue;
import org.ratden.skavenblight.event.skavenIncursion.runtime.IncursionExecutionState;
import org.ratden.skavenblight.event.skavenIncursion.runtime.IncursionWaveController;
import org.ratden.skavenblight.event.skavenIncursion.runtime.WaveExecutionState;
import org.ratden.skavenblight.event.skavenIncursion.runtime.entity.AttachedMobEntityReconnectionOwner;
import org.ratden.skavenblight.event.skavenIncursion.runtime.entity.AttachedMobEntityReconnectionResult;
import org.ratden.skavenblight.event.skavenIncursion.runtime.event.SourceDestroyedEvent;
import org.ratden.skavenblight.event.skavenIncursion.runtime.mob.IncursionMobTrackingState;
import org.ratden.skavenblight.event.skavenIncursion.runtime.persistence.PersistableSkavenScenario;
import org.ratden.skavenblight.event.skavenIncursion.runtime.persistence.PlannedScenarioRuntimeSnapshot;
import org.ratden.skavenblight.event.skavenIncursion.runtime.source.SourceExecutionState;
import org.ratden.skavenblight.event.skavenIncursion.scenario.ScenarioDefinition;
import org.ratden.skavenblight.event.skavenIncursion.scenario.ScenarioGoal;
import org.ratden.skavenblight.event.skavenIncursion.scenario.ScenarioPattern;

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
 * Its runtime accepts any positive number of planned waves. Wave progression
 * is delegated to IncursionWaveController so this Scenario can test
 * multi-wave Stratagems without implementing reusable wave mechanics itself.
 *
 * CatDogRaid uses PlannedScenarioRuntimeSnapshot because all of its mutable
 * persistence state is shared planned-Scenario state. It owns no additional
 * Scenario-specific persistence payload.
 *
 * Restoration is logical only. It does not assume that recorded source blocks
 * or spawned entities are currently loaded or still present in the world.
 * World reconciliation will occur after persistent incursion records have
 * been loaded.
 */
public class CatDogRaid
        implements PersistableSkavenScenario<
        PlannedScenarioRuntimeSnapshot
        >,
        AttachedMobEntityReconnectionOwner {

    public static final ScenarioDefinition DEFINITION =
            new ScenarioDefinition(
                    "cat_dog_raid",
                    ScenarioPattern.RAID,
                    SourceDistanceProfile.STANDARD,
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
                            StratagemCatalogue.STEADY_1,
                            StratagemCatalogue.STEADY_3
                    ),
                    List.of(
                            TestPackLeaderComplexity.OPTION
                    ),
                    0,
                    -1,
                    0,
                    0L,
                    true,
                    true,
                    false
            );

    /**
     * Deliberately slow development timings used while verifying persistent
     * incursion shutdown and restoration.
     *
     * These values create clear observation windows:
     *
     * - the first 4 seconds contain no physical source;
     * - the source then exists for 6 seconds before the first spawn;
     * - streamed spawns occur 6 seconds apart;
     * - a completed wave remains in transition for 10 seconds.
     *
     * Once persistence testing is complete, CatDogRaid can return to faster
     * general-purpose development timings.
     */
    private static final int SOURCE_CREATION_DELAY_TICKS =
            120;

    private static final int FIRST_SPAWN_DELAY_TICKS =
            900;

    private static final int SPAWN_INTERVAL_TICKS =
            900;

    /**
     * Temporary fixed transition timing used until hybrid pacing is added.
     *
     * After a wave finishes scheduled delivery, its sources remain dormant
     * for this period before the next wave begins. The same period follows
     * the final wave before the controller reports complete.
     *
     * The extended value gives persistence tests enough time to shut down
     * while the controller is explicitly between active waves.
     */
    private static final int WAVE_TRANSITION_GRACE_TICKS =
            300;

    /**
     * Prevents an uncreatable source or permanently failed spawn from leaving
     * a debug Scenario active forever.
     *
     * The longer test timeout allows slow streamed delivery, manual
     * inspection and repeated orderly server restarts without an otherwise
     * healthy test raid timing out.
     *
     * The value used by a particular Scenario instance is copied into
     * maximumRuntimeTicks and persisted. A Scenario already in progress
     * therefore retains its original timeout if this authored default changes
     * in a later mod version.
     */
    private static final int MAXIMUM_RUNTIME_TICKS =
            36000;

    private final UUID instanceId;

    private final ServerLevel level;
    private final IncursionExecutionState incursionExecutionState;
    private final IncursionWaveController waveController;
    private final LeadershipContext leadershipContext;

    private final int maximumRuntimeTicks;

    private int elapsedTicks;

    private int totalSourceDestructions;
    private int totalMobsCancelledByDestruction;

    private boolean sourcesCollapsed;
    private boolean finished;
    private boolean timedOut;

    /**
     * Creates a fresh CatDogRaid from a completed immutable IncursionPlan.
     */
    public CatDogRaid(
            ServerLevel level,
            IncursionPlan incursionPlan
    ) {
        validateLevelAndPlan(
                level,
                incursionPlan
        );

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
         *
         * This context is deterministic from instanceId and therefore does
         * not require an additional persistence snapshot.
         */
        this.leadershipContext =
                LeadershipContext.debug(
                        instanceId
                );

        this.maximumRuntimeTicks =
                MAXIMUM_RUNTIME_TICKS;

        this.elapsedTicks =
                0;

        this.totalSourceDestructions =
                0;

        this.totalMobsCancelledByDestruction =
                0;

        this.sourcesCollapsed =
                false;

        this.finished =
                false;

        this.timedOut =
                false;

        validateInternalState();
    }

    /**
     * Canonical restoration constructor.
     *
     * Every final field is assigned directly inside this constructor.
     */
    private CatDogRaid(
            ServerLevel level,
            IncursionPlan incursionPlan,
            IncursionExecutionState incursionExecutionState,
            IncursionWaveController waveController,
            int maximumRuntimeTicks,
            int elapsedTicks,
            int totalSourceDestructions,
            int totalMobsCancelledByDestruction,
            boolean sourcesCollapsed,
            boolean finished,
            boolean timedOut
    ) {
        validateLevelAndPlan(
                level,
                incursionPlan
        );

        if (incursionExecutionState == null) {
            throw new IllegalArgumentException(
                    "Restored incursion execution state cannot be null."
            );
        }

        if (waveController == null) {
            throw new IllegalArgumentException(
                    "Restored wave controller cannot be null."
            );
        }

        this.level =
                level;

        this.instanceId =
                incursionPlan.getIncursionId();

        this.incursionExecutionState =
                incursionExecutionState;

        this.waveController =
                waveController;

        this.leadershipContext =
                LeadershipContext.debug(
                        instanceId
                );

        this.maximumRuntimeTicks =
                maximumRuntimeTicks;

        this.elapsedTicks =
                elapsedTicks;

        this.totalSourceDestructions =
                totalSourceDestructions;

        this.totalMobsCancelledByDestruction =
                totalMobsCancelledByDestruction;

        this.sourcesCollapsed =
                sourcesCollapsed;

        this.finished =
                finished;

        this.timedOut =
                timedOut;

        validateInternalState();
    }

    /**
     * Restores exact logical Scenario progress against the supplied immutable
     * IncursionPlan.
     *
     * Restoration order is significant:
     *
     * 1. restore shared physical-source and wave-assignment state;
     * 2. restore the wave controller against those shared assignments;
     * 3. restore Scenario-level counters and lifecycle state.
     */
    public static CatDogRaid restore(
            ServerLevel level,
            IncursionPlan incursionPlan,
            PlannedScenarioRuntimeSnapshot snapshot
    ) {
        validateLevelAndPlan(
                level,
                incursionPlan
        );

        if (snapshot == null) {
            throw new IllegalArgumentException(
                    "Planned Scenario runtime snapshot cannot be null."
            );
        }

        if (!DEFINITION.id().equals(
                snapshot.scenarioId()
        )) {
            throw new IllegalArgumentException(
                    "CatDogRaid cannot restore Scenario snapshot "
                            + snapshot.scenarioId()
                            + "."
            );
        }

        UUID plannedIncursionId =
                incursionPlan.getIncursionId();

        if (!plannedIncursionId.equals(
                snapshot.instanceId()
        )) {
            throw new IllegalArgumentException(
                    "CatDogRaid snapshot belongs to instance "
                            + snapshot.instanceId()
                            + " but supplied IncursionPlan belongs to "
                            + plannedIncursionId
                            + "."
            );
        }

        IncursionExecutionState restoredExecutionState =
                IncursionExecutionState.restore(
                        incursionPlan,
                        snapshot.incursionExecutionSnapshot()
                );

        IncursionWaveController restoredWaveController =
                IncursionWaveController.restore(
                        restoredExecutionState,
                        snapshot.waveControllerSnapshot()
                );

        CatDogRaid restoredScenario =
                new CatDogRaid(
                        level,
                        incursionPlan,
                        restoredExecutionState,
                        restoredWaveController,
                        snapshot.maximumRuntimeTicks(),
                        snapshot.elapsedTicks(),
                        snapshot.totalSourceDestructions(),
                        snapshot.totalMobsCancelledByDestruction(),
                        snapshot.sourcesCollapsed(),
                        snapshot.finished(),
                        snapshot.timedOut()
                );

        PlannedScenarioRuntimeSnapshot reconstructedSnapshot =
                restoredScenario.createSnapshot();

        if (!reconstructedSnapshot.equals(
                snapshot
        )) {
            throw new IllegalArgumentException(
                    "Restored CatDogRaid does not exactly match its saved "
                            + "snapshot for instance "
                            + snapshot.instanceId()
                            + "."
            );
        }

        return restoredScenario;
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

    public int getMaximumRuntimeTicks() {
        return maximumRuntimeTicks;
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

    public boolean areSourcesCollapsed() {
        return sourcesCollapsed;
    }

    public boolean hasTimedOut() {
        return timedOut;
    }

    /**
     * Captures all mutable state shared by an ordinary planning-aware
     * Scenario.
     *
     * The immutable IncursionPlan is deliberately persisted separately.
     */
    @Override
    public PlannedScenarioRuntimeSnapshot createSnapshot() {
        return new PlannedScenarioRuntimeSnapshot(
                DEFINITION.id(),
                instanceId,
                maximumRuntimeTicks,
                elapsedTicks,
                totalSourceDestructions,
                totalMobsCancelledByDestruction,
                sourcesCollapsed,
                finished,
                timedOut,
                incursionExecutionState.createSnapshot(),
                waveController.createSnapshot()
        );
    }

    /**
     * Compatibility route for direct Scenario ticking outside the persistent
     * owner.
     *
     * Normal admitted runtime is ticked through tickPersistent(...) by
     * LivePersistentIncursion so successful mob delivery can reach the
     * authoritative persistent mob-tracking state.
     */
    @Override
    public void tick() {
        tickInternal(
                null
        );
    }

    /**
     * Advances this Scenario using the authoritative persistent mob-tracking
     * state owned by the same incursion.
     *
     * This method validates ownership and passes the state into the reusable
     * wave controller. WaveExecutionState and SourceWaveExecutionState will
     * consume it in the following implementation steps.
     */
    @Override
    public void tickPersistent(
            IncursionMobTrackingState mobTrackingState
    ) {
        if (mobTrackingState == null) {
            throw new IllegalArgumentException(
                    "CatDogRaid persistent tick requires mob-tracking state."
            );
        }

        if (!instanceId.equals(
                mobTrackingState.getIncursionId()
        )) {
            throw new IllegalArgumentException(
                    "CatDogRaid instance ID "
                            + instanceId
                            + " does not match mob-tracking incursion ID "
                            + mobTrackingState.getIncursionId()
                            + "."
            );
        }

        tickInternal(
                mobTrackingState
        );
    }

    private void tickInternal(
            IncursionMobTrackingState mobTrackingState
    ) {
        if (finished) {
            return;
        }

        elapsedTicks++;

        if (mobTrackingState == null) {
            waveController.tick(
                    level,
                    leadershipContext
            );
        } else {
            waveController.tick(
                    level,
                    leadershipContext,
                    mobTrackingState
            );
        }

        if (elapsedTicks
                >= maximumRuntimeTicks) {

            handleRuntimeTimeout();

            validateInternalState();

            return;
        }

        if (!waveController.areAllWavesComplete()) {
            validateInternalState();

            return;
        }

        collapseRemainingSources();

        finished =
                true;

        validateInternalState();
    }

    /**
     * Receives a routed destruction event from ActiveIncursionManager.
     *
     * Only a currently recognised physical-source incarnation is counted.
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

        validateInternalState();
    }

    /**
     * Reconciles a physical source that persistence recorded as available but
     * which is absent from the restored world.
     *
     * This follows the same source and current-wave cancellation path as a
     * normal destruction event, but does not increment
     * totalSourceDestructions because no routed world event was observed.
     *
     * Mobs cancelled because the source is missing still count as
     * destruction-related cancellations.
     */
    @Override
    public boolean reconcileMissingPhysicalSource(
            UUID runtimeSourceId
    ) {
        if (runtimeSourceId == null) {
            throw new IllegalArgumentException(
                    "Missing runtime source ID cannot be null."
            );
        }

        IncursionWaveController.SourceDestructionResult result =
                waveController.handleSourceDestroyed(
                        runtimeSourceId
                );

        if (!result.sourceRecognised()) {
            return false;
        }

        totalMobsCancelledByDestruction +=
                result.cancelledMobs();

        validateInternalState();

        return true;
    }

    /**
     * Routes a naturally loaded attached entity into the restored execution
     * graph.
     */
    @Override
    public AttachedMobEntityReconnectionResult
    reconnectLoadedAttachedMobEntity(
            Entity entity
    ) {
        return incursionExecutionState
                .reconnectLoadedAttachedMobEntity(
                        entity
                );
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

        sourcesCollapsed =
                true;
    }

    private void handleRuntimeTimeout() {
        waveController.cancelAllRemainingMobs();

        collapseRemainingSources();

        timedOut =
                true;

        finished =
                true;
    }

    /**
     * Performs lightweight live-runtime validation.
     *
     * Complete cross-layer snapshot validation belongs to createSnapshot(),
     * where persistence actually captures the runtime. Ordinary Scenario
     * ticking must not rebuild the complete incursion, wave, source and queue
     * snapshot graph merely to confirm basic lifecycle state.
     */
    private void validateInternalState() {
        if (!instanceId.equals(
                incursionExecutionState.getIncursionId()
        )) {
            throw new IllegalStateException(
                    "CatDogRaid instance ID does not match its incursion "
                            + "execution state."
            );
        }

        if (!instanceId.equals(
                waveController.getIncursionId()
        )) {
            throw new IllegalStateException(
                    "CatDogRaid instance ID does not match its wave "
                            + "controller."
            );
        }

        if (waveController.getIncursionExecutionState()
                != incursionExecutionState) {

            throw new IllegalStateException(
                    "CatDogRaid wave controller does not use the Scenario's "
                            + "shared IncursionExecutionState."
            );
        }

        if (maximumRuntimeTicks <= 0) {
            throw new IllegalStateException(
                    "CatDogRaid maximum runtime must be greater than zero."
            );
        }

        if (elapsedTicks < 0) {
            throw new IllegalStateException(
                    "CatDogRaid elapsed ticks cannot be negative."
            );
        }

        if (totalSourceDestructions < 0) {
            throw new IllegalStateException(
                    "CatDogRaid source-destruction count cannot be negative."
            );
        }

        if (totalMobsCancelledByDestruction < 0) {
            throw new IllegalStateException(
                    "CatDogRaid destruction-cancellation count cannot be "
                            + "negative."
            );
        }

        if (timedOut) {
            if (!finished) {
                throw new IllegalStateException(
                        "A timed-out CatDogRaid must be finished."
                );
            }

            if (!sourcesCollapsed) {
                throw new IllegalStateException(
                        "A timed-out CatDogRaid must have collapsed its "
                                + "surviving sources."
                );
            }

            if (elapsedTicks < maximumRuntimeTicks) {
                throw new IllegalStateException(
                        "A timed-out CatDogRaid cannot have fewer elapsed "
                                + "ticks than its maximum runtime."
                );
            }

            if (!waveController.wasCancelled()) {
                throw new IllegalStateException(
                        "A timed-out CatDogRaid requires a cancelled wave "
                                + "controller."
                );
            }

            return;
        }

        if (finished) {
            if (!sourcesCollapsed) {
                throw new IllegalStateException(
                        "A finished CatDogRaid must have collapsed its "
                                + "surviving sources."
                );
            }

            if (elapsedTicks >= maximumRuntimeTicks) {
                throw new IllegalStateException(
                        "A normally completed CatDogRaid cannot reach or "
                                + "exceed its timeout tick."
                );
            }

            if (!waveController.completedNormally()) {
                throw new IllegalStateException(
                        "A finished non-timeout CatDogRaid requires a normally "
                                + "completed wave controller."
                );
            }

            return;
        }

        if (sourcesCollapsed) {
            throw new IllegalStateException(
                    "An active CatDogRaid cannot report collapsed "
                            + "infrastructure."
            );
        }

        if (elapsedTicks >= maximumRuntimeTicks) {
            throw new IllegalStateException(
                    "An active CatDogRaid cannot have reached its timeout "
                            + "tick."
            );
        }

        if (waveController.areAllWavesComplete()) {
            throw new IllegalStateException(
                    "An active CatDogRaid requires an in-progress wave "
                            + "controller."
            );
        }
    }

    private static void validateLevelAndPlan(
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
                    "CatDogRaid IncursionPlan cannot be null."
            );
        }

        if (incursionPlan.getIncursionId() == null) {
            throw new IllegalArgumentException(
                    "CatDogRaid IncursionPlan has no incursion ID."
            );
        }
    }
}