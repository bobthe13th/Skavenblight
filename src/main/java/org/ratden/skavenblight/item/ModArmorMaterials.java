package org.ratden.skavenblight.item;

import net.minecraft.Util;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.ratden.skavenblight.Skavenblight;

import java.util.EnumMap;
import java.util.List;

/**
 * Registers the armour materials used by Skavenblight items.
 */
public final class ModArmorMaterials {

    public static final DeferredRegister<ArmorMaterial>
            ARMOR_MATERIALS =
            DeferredRegister.create(
                    Registries.ARMOR_MATERIAL,
                    Skavenblight.MODID
            );

    public static final Holder<ArmorMaterial>
            WARPSTONE_ARMOR_MATERIAL =
            ARMOR_MATERIALS.register(
                    "warpstone",
                    () -> new ArmorMaterial(
                            Util.make(
                                    new EnumMap<>(ArmorItem.Type.class),
                                    protection -> {
                                        protection.put(
                                                ArmorItem.Type.BOOTS,
                                                5
                                        );

                                        protection.put(
                                                ArmorItem.Type.LEGGINGS,
                                                7
                                        );

                                        protection.put(
                                                ArmorItem.Type.CHESTPLATE,
                                                9
                                        );

                                        protection.put(
                                                ArmorItem.Type.HELMET,
                                                5
                                        );

                                        protection.put(
                                                ArmorItem.Type.BODY,
                                                11
                                        );
                                    }
                            ),
                            32,
                            SoundEvents.ARMOR_EQUIP_NETHERITE,
                            () -> Ingredient.of(
                                    ModItems.FUSED_WARPSTONE.get()
                            ),
                            List.of(
                                    new ArmorMaterial.Layer(
                                            ResourceLocation
                                                    .fromNamespaceAndPath(
                                                            Skavenblight.MODID,
                                                            "warpstone"
                                                    )
                                    )
                            ),
                            4.0F,
                            0.2F
                    )
            );

    public static void register(IEventBus eventBus) {
        ARMOR_MATERIALS.register(eventBus);
    }

    private ModArmorMaterials() {
    }
}