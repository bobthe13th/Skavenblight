package org.ratden.skavenblight.entity.client;

import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.entity.custom.ClanratEntity;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class ClanratRenderer extends GeoEntityRenderer<ClanratEntity> {
    public ClanratRenderer(EntityRendererProvider.Context renderManager) {
        super(renderManager, new ClanratModel());
    }

    @Override
    public ResourceLocation getTextureLocation(ClanratEntity animatable) {
        return ResourceLocation.fromNamespaceAndPath(Skavenblight.MODID, "textures/entity/clanrat.png");
    }
}