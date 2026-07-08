package org.ratden.skavenblight.event.skavenIncursion.scenario;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import org.ratden.skavenblight.block.entity.state.SourceState;
import org.ratden.skavenblight.event.skavenIncursion.SkavenDifficultyTracker;
import org.ratden.skavenblight.event.skavenIncursion.SkavenIncursion;
import org.ratden.skavenblight.event.skavenIncursion.action.CreateTunnelSource;
import org.ratden.skavenblight.event.skavenIncursion.action.SetSourceState;
import org.ratden.skavenblight.event.skavenIncursion.action.SpawnClanrats;
import org.ratden.skavenblight.ai.pathing.StandardFlowField;
import org.ratden.skavenblight.network.WarpFluxGridManager;
import org.ratden.skavenblight.network.WarpFluxNetwork;

import java.util.Collections;

public class ClanratAssault implements SkavenIncursion {
    private final ServerLevel level;
    private final BlockPos targetPos;
    private final BlockPos sourcePos;
    private int elapsedTicks;
    private boolean finished;

    // The commander's map
    private final StandardFlowField flowField;

    public ClanratAssault(ServerLevel level, BlockPos targetPos) {
        this.level = level;
        this.targetPos = targetPos.immutable();
        this.sourcePos = targetPos.offset(5, 0, 0).immutable();
        this.elapsedTicks = 0;
        this.finished = false;

        WarpFluxNetwork network = WarpFluxGridManager.get(level).getNetworkAt(targetPos);
        if (network != null) {
            // Fetch the shared instance instead of using 'new'
            this.flowField = network.getSharedFlowField(targetPos);
        } else {
            // Fallback (Ideally this shouldn't happen)
            this.flowField = new StandardFlowField(targetPos, Collections.emptySet());
        }
    }

    public static int getBaseClanratCount() {
        return 5; // Clanrats are weaker, spawn more of them!
    }

    public static int getThreatContribution() {
        return SkavenDifficultyTracker.getThreat() / 5;
    }

    public static int getComplexityContribution() {
        return SkavenDifficultyTracker.getComplexity() >= 1 ? 2 : 0;
    }

    public static int calculateClanratCount() {
        return getBaseClanratCount() + getThreatContribution() + getComplexityContribution();
    }

    @Override
    public void tick() {
        elapsedTicks++;

        if (this.flowField != null) {
            this.flowField.calculateMapIfNeeded(level);
        }
        // --- NEW: UPDATE THE MAP EVERY SECOND ---
        // If a player builds a wall during the raid, the flow field adapts!
        if (elapsedTicks % 20 == 0) {
            flowField.calculateMap(level);
        }

        if (elapsedTicks == 1) {
            level.playSound(null, targetPos, SoundEvents.GRAVEL_BREAK, SoundSource.HOSTILE, 2f, 0.8f);
        }

        if (elapsedTicks == 20) {
            // Using a generic sound until you add a Skaven chitter
            level.playSound(null, sourcePos, SoundEvents.ZOMBIE_BREAK_WOODEN_DOOR, SoundSource.HOSTILE, 2f, 1f);
            CreateTunnelSource.execute(level, sourcePos, SourceState.ACTIVE);
        }

        if (elapsedTicks == 100) {
            // --- NEW: SPAWN TROOPS AND HAND OUT THE MAP ---
            SpawnClanrats.execute(level, sourcePos, calculateClanratCount(), flowField);
        }

        if (elapsedTicks == 160) {
            SetSourceState.execute(level, sourcePos, SourceState.COLLAPSED);
        }

        if (elapsedTicks == 180) {
            finished = true;
        }
    }

    @Override
    public boolean isFinished() {
        return finished;
    }
}