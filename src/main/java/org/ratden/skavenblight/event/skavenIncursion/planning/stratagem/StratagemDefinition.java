package org.ratden.skavenblight.event.skavenIncursion.planning.stratagem;

import org.ratden.skavenblight.event.skavenIncursion.planning.front.FrontAllocationPattern;
import org.ratden.skavenblight.event.skavenIncursion.planning.front.FrontPlacementPattern;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Shared authored definition for one Stratagem.
 *
 * A Stratagem shapes how a selected Scenario applies its pressure. It defines
 * the baseline wave structure and front preferences used by the planners.
 *
 * Unusual or highly authored behaviour belongs to the individual Stratagem
 * implementation rather than being represented by speculative fields here.
 */
public class StratagemDefinition {

    private final String id;
    private final boolean canRepeat;
    private final StratagemStyle style;

    private final FrontPlacementPattern frontPlacementPattern;
    private final FrontAllocationPattern frontAllocationPattern;

    private final List<WaveProfile> waveProfiles;

    public StratagemDefinition(
            String id,
            boolean canRepeat,
            StratagemStyle style,
            FrontPlacementPattern frontPlacementPattern,
            FrontAllocationPattern frontAllocationPattern,
            List<WaveProfile> waveProfiles
    ) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(
                    "Stratagem ID cannot be blank."
            );
        }

        Objects.requireNonNull(
                style,
                "Stratagem style cannot be null."
        );

        Objects.requireNonNull(
                frontPlacementPattern,
                "Front placement pattern cannot be null."
        );

        Objects.requireNonNull(
                frontAllocationPattern,
                "Front allocation pattern cannot be null."
        );

        if (waveProfiles == null || waveProfiles.isEmpty()) {
            throw new IllegalArgumentException(
                    "A Stratagem must contain at least one wave profile."
            );
        }

        validateWaveProfiles(waveProfiles);

        this.id = id;
        this.canRepeat = canRepeat;
        this.style = style;
        this.frontPlacementPattern = frontPlacementPattern;
        this.frontAllocationPattern = frontAllocationPattern;
        this.waveProfiles = Collections.unmodifiableList(
                new ArrayList<>(waveProfiles)
        );
    }

    public String getId() {
        return id;
    }

    public boolean canRepeat() {
        return canRepeat;
    }

    public StratagemStyle getStyle() {
        return style;
    }

    public FrontPlacementPattern getFrontPlacementPattern() {
        return frontPlacementPattern;
    }

    public FrontAllocationPattern getFrontAllocationPattern() {
        return frontAllocationPattern;
    }

    public List<WaveProfile> getWaveProfiles() {
        return waveProfiles;
    }

    public int getWaveCount() {
        return waveProfiles.size();
    }

    public WaveProfile getWaveProfile(int waveIndex) {
        if (waveIndex < 0 || waveIndex >= waveProfiles.size()) {
            throw new IndexOutOfBoundsException(
                    "Wave index is outside the Stratagem wave range: "
                            + waveIndex
            );
        }

        return waveProfiles.get(waveIndex);
    }

    private static void validateWaveProfiles(
            List<WaveProfile> waveProfiles
    ) {
        double totalThreatShare = 0.0D;
        double totalComplexityShare = 0.0D;

        for (int index = 0; index < waveProfiles.size(); index++) {
            WaveProfile waveProfile = waveProfiles.get(index);

            if (waveProfile == null) {
                throw new IllegalArgumentException(
                        "Wave profile at index "
                                + index
                                + " cannot be null."
                );
            }

            totalThreatShare += waveProfile.threatShare();
            totalComplexityShare += waveProfile.complexityShare();
        }

        validateTotalShare(totalThreatShare, "Threat");
        validateTotalShare(totalComplexityShare, "Complexity");
    }

    private static void validateTotalShare(
            double totalShare,
            String shareName
    ) {
        double tolerance = 0.000001D;

        if (Math.abs(totalShare - 1.0D) > tolerance) {
            throw new IllegalArgumentException(
                    shareName
                            + " wave shares must total 1.0, but totalled "
                            + totalShare
                            + "."
            );
        }
    }

    /**
     * Broad indication of how heavily authored the Stratagem's tactical
     * relationships are.
     *
     * This distinction applies to Stratagems, not complexity options.
     */
    public enum StratagemStyle {
        SIMPLE,
        CUNNING
    }

    /**
     * Authored budget shares for one wave.
     *
     * Complexity normally follows threat, but it remains explicit so a
     * Stratagem can author cases such as a wave with threat but no complexity.
     */
    public record WaveProfile(
            double threatShare,
            double complexityShare
    ) {
        public WaveProfile {
            validateShare(threatShare, "Wave threat");
            validateShare(complexityShare, "Wave complexity");
        }

        private static void validateShare(
                double share,
                String shareName
        ) {
            if (!Double.isFinite(share)) {
                throw new IllegalArgumentException(
                        shareName + " share must be finite."
                );
            }

            if (share < 0.0D || share > 1.0D) {
                throw new IllegalArgumentException(
                        shareName
                                + " share must be between 0.0 and 1.0."
                );
            }
        }
    }
}