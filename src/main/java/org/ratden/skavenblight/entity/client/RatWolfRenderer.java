package org.ratden.skavenblight.entity.client;

import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.WolfRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.animal.Wolf;
import org.ratden.skavenblight.Skavenblight;

public class RatWolfRenderer extends WolfRenderer {

    private static final ResourceLocation RAT_WOLF_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Skavenblight.MODID, "textures/entity/rat_wolf.png");

    public RatWolfRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public ResourceLocation getTextureLocation(Wolf entity) {
        return RAT_WOLF_TEXTURE;
    }
}