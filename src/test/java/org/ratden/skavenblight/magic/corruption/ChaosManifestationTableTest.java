package org.ratden.skavenblight.magic.corruption;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChaosManifestationTableTest {

    // ChaosManifestationEffect is sealed with an explicit permits clause (mirroring SpellEffect),
    // so a test-local marker record cannot implement it. GrantCorruption's int amount stands in
    // as the distinguishing label instead, purely to exercise weighted selection.

    @Test
    void pickWeightedRespectsZeroRoll() {
        List<ChaosManifestationTable.WeightedEntry> entries = List.of(
                new ChaosManifestationTable.WeightedEntry(1, new ChaosManifestationEffect.GrantCorruption(1)),
                new ChaosManifestationTable.WeightedEntry(9, new ChaosManifestationEffect.GrantCorruption(9))
        );
        // Roll of 0 (out of total weight 10) must land on the first entry.
        ChaosManifestationEffect picked = ChaosManifestationTable.pickWeighted(entries, 0);
        assertEquals(1, ((ChaosManifestationEffect.GrantCorruption) picked).amount());
    }

    @Test
    void pickWeightedRespectsLastSlot() {
        List<ChaosManifestationTable.WeightedEntry> entries = List.of(
                new ChaosManifestationTable.WeightedEntry(1, new ChaosManifestationEffect.GrantCorruption(1)),
                new ChaosManifestationTable.WeightedEntry(9, new ChaosManifestationEffect.GrantCorruption(9))
        );
        // Roll of 9 (last slot in a total weight of 10) must land on the second entry.
        ChaosManifestationEffect picked = ChaosManifestationTable.pickWeighted(entries, 9);
        assertEquals(9, ((ChaosManifestationEffect.GrantCorruption) picked).amount());
    }

    @Test
    void pickWeightedIsDeterministicForAGivenRoll() {
        List<ChaosManifestationTable.WeightedEntry> entries = List.of(
                new ChaosManifestationTable.WeightedEntry(5, new ChaosManifestationEffect.GrantCorruption(42))
        );
        Random random = new Random(42);
        for (int i = 0; i < 20; i++) {
            ChaosManifestationEffect picked = ChaosManifestationTable.pickWeighted(entries, random.nextInt(5));
            assertEquals(42, ((ChaosManifestationEffect.GrantCorruption) picked).amount());
        }
    }
}
