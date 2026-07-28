package org.ratden.skavenblight.magic.spell;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.ratden.skavenblight.magic.Wind;

import static org.junit.jupiter.api.Assertions.*;

class SpellCodecTest {

    private static final Gson GSON = new Gson();

    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void parsesDamageSpell() {
        String json = """
                {
                  "wind": "aqshy",
                  "tier": 0,
                  "casting_number": 30,
                  "casting_time": "half_action",
                  "effect": { "type": "damage", "amount": 8.0 },
                  "description_key": "spell.skavenblight.fireball.description"
                }
                """;
        JsonElement element = GSON.fromJson(json, JsonElement.class);

        Spell spell = Spell.CODEC.parse(JsonOps.INSTANCE, element).getOrThrow();

        assertEquals(Wind.AQSHY, spell.wind());
        assertEquals(0, spell.tier());
        assertEquals(30, spell.castingNumber());
        assertEquals(CastingTime.HALF_ACTION, spell.castingTime());
        assertTrue(spell.componentItem().isEmpty());
        assertInstanceOf(SpellEffect.DamageEffect.class, spell.effect());
        assertEquals(8.0f, ((SpellEffect.DamageEffect) spell.effect()).amount());
    }

    @Test
    void parsesMobEffectSpell() {
        String json = """
                {
                  "wind": "hysh",
                  "tier": 0,
                  "casting_number": 20,
                  "casting_time": "half_action",
                  "effect": {
                    "type": "mob_effect",
                    "effect": "minecraft:regeneration",
                    "duration_ticks": 200,
                    "amplifier": 1
                  },
                  "description_key": "spell.skavenblight.boon_of_hysh.description"
                }
                """;
        JsonElement element = GSON.fromJson(json, JsonElement.class);

        Spell spell = Spell.CODEC.parse(JsonOps.INSTANCE, element).getOrThrow();

        assertEquals(Wind.HYSH, spell.wind());
        assertInstanceOf(SpellEffect.MobEffectApply.class, spell.effect());
        SpellEffect.MobEffectApply mobEffect = (SpellEffect.MobEffectApply) spell.effect();
        assertEquals(200, mobEffect.durationTicks());
        assertEquals(1, mobEffect.amplifier());
    }
}
