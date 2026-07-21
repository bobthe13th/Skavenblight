package org.ratden.skavenblight.event.skavenIncursion.planning.source;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Authoritative catalogue of physical placement profiles used by incursion
 * sources.
 *
 * Profiles are keyed by both SourceType and SourceSize because sources of
 * the same broad size may require different footprints and preparation
 * behaviour.
 *
 * This is an internal Java catalogue, not a NeoForge registry.
 */
public final class SourcePlacementProfileCatalogue {

    private static final Map<ProfileKey, SourcePlacementProfile>
            PROFILES =
            new LinkedHashMap<>();

    /**
     * Baseline normal Skaven tunnel.
     *
     * This preserves the existing intended tunnel preparation:
     *
     * - reserve a centred 5x5 area;
     * - prepare a centred 5x5 area;
     * - construct one foundation layer;
     * - clear three blocks of emergence height.
     */
    public static final SourcePlacementProfile
            SKAVEN_TUNNEL_NORMAL =
            register(
                    SourceType.SKAVEN_TUNNEL,
                    SourceSize.NORMAL,
                    new SourcePlacementProfile(
                            SourceReservationArea.centered(
                                    5,
                                    5
                            ),
                            SourceReservationArea.centered(
                                    5,
                                    5
                            ),
                            1,
                            3
                    )
            );

    /**
     * Returns the placement profile for the supplied source type and size.
     *
     * Returns null when that combination does not yet have an authored
     * placement profile.
     */
    public static SourcePlacementProfile get(
            SourceType sourceType,
            SourceSize sourceSize
    ) {
        if (sourceType == null || sourceSize == null) {
            return null;
        }

        return PROFILES.get(
                new ProfileKey(
                        sourceType,
                        sourceSize
                )
        );
    }

    /**
     * Returns the required placement profile.
     *
     * A missing profile is treated as an implementation/configuration error,
     * rather than silently assigning an arbitrary footprint.
     */
    public static SourcePlacementProfile require(
            SourceType sourceType,
            SourceSize sourceSize
    ) {
        SourcePlacementProfile profile =
                get(
                        sourceType,
                        sourceSize
                );

        if (profile == null) {
            throw new IllegalArgumentException(
                    "No source placement profile exists for source type "
                            + sourceType
                            + " and source size "
                            + sourceSize
                            + "."
            );
        }

        return profile;
    }

    private static SourcePlacementProfile register(
            SourceType sourceType,
            SourceSize sourceSize,
            SourcePlacementProfile profile
    ) {
        if (sourceType == null) {
            throw new IllegalArgumentException(
                    "Placement-profile source type cannot be null."
            );
        }

        if (sourceSize == null) {
            throw new IllegalArgumentException(
                    "Placement-profile source size cannot be null."
            );
        }

        if (profile == null) {
            throw new IllegalArgumentException(
                    "Source placement profile cannot be null."
            );
        }

        ProfileKey key =
                new ProfileKey(
                        sourceType,
                        sourceSize
                );

        SourcePlacementProfile existingProfile =
                PROFILES.putIfAbsent(
                        key,
                        profile
                );

        if (existingProfile != null) {
            throw new IllegalStateException(
                    "Duplicate source placement profile for source type "
                            + sourceType
                            + " and source size "
                            + sourceSize
                            + "."
            );
        }

        return profile;
    }

    private record ProfileKey(
            SourceType sourceType,
            SourceSize sourceSize
    ) {
    }

    private SourcePlacementProfileCatalogue() {
    }
}