package org.ratden.skavenblight.event.skavenIncursion.scheme;

import org.ratden.skavenblight.event.skavenIncursion.scheme.chieftainTest.ChieftainTestScheme;

import java.util.HashMap;
import java.util.Map;

public class SchemeRegistry {

    private static final Map<String, SkavenScheme> SCHEMES = new HashMap<>();

    static {
        register(new ChieftainTestScheme());
    }

    private static void register(SkavenScheme scheme) {
        SCHEMES.put(scheme.getId(), scheme);
    }

    public static SkavenScheme getScheme(String schemeId) {
        SkavenScheme scheme = SCHEMES.get(schemeId);

        if (scheme == null) {
            return SCHEMES.get("chieftain_test");
        }

        return scheme;
    }
}