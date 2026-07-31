package org.ratden.skavenblight.event.skavenIncursion.action.effect;

import java.util.HashMap;
import java.util.Map;

public class EffectRegistry {

    private static final Map<String, SkavenEffect> EFFECTS = new HashMap<>();

    static {
        // Later:
        // register(new RedEyesAtNight());
        // register(new GreenMoon());
        // register(new DistantBell());
        // register(new SkavenChittering());
        // register(new SkavenWhispers());
    }

    private static void register(SkavenEffect effect) {
        EFFECTS.put(effect.getId(), effect);
    }

    public static SkavenEffect getEffect(String effectId) {
        return EFFECTS.get(effectId);
    }

    public static boolean hasEffect(String effectId) {
        return EFFECTS.containsKey(effectId);
    }

    private EffectRegistry() {
    }
}