package org.ratden.skavenblight.ai.pathing.region;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TerritoryRegionMapOnBlockChangedTest {

    @Test
    void siegeProjectTickNeverCallsForceRecalculationDirectly() {
        // Static-analysis-style guard: grep this repo's compiled SiegeProject.java source for the
        // string "forceRecalculation" and assert it's absent, so a future accidental re-add during
        // an unrelated edit is caught by CI, not just by code review memory.
        java.nio.file.Path siegeProjectSource = java.nio.file.Path.of(
                "src/main/java/org/ratden/skavenblight/ai/pathing/SiegeProject.java");
        String contents = readFileOrFail(siegeProjectSource);
        assertFalse(contents.contains("forceRecalculation"),
                "SiegeProject must never call forceRecalculation directly - the onBlockChanged fix "
                        + "is a deletion, not a new filter; planned-cell authority in TerrainSnapshot "
                        + "is what replaces it");
    }

    private static String readFileOrFail(java.nio.file.Path path) {
        try {
            return java.nio.file.Files.readString(path);
        } catch (java.io.IOException e) {
            throw new AssertionError("could not read " + path, e);
        }
    }
}
