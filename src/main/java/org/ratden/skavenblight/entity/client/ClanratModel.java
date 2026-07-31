package org.ratden.skavenblight.entity.client;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.entity.custom.ClanratEntity;
import software.bernie.geckolib.constant.DataTickets;
import software.bernie.geckolib.animation.AnimationState; // Updated path
import software.bernie.geckolib.cache.object.GeoBone;     // Replaced CoreGeoBone with GeoBone
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.model.data.EntityModelData;

public class ClanratModel extends GeoModel<ClanratEntity> {
    @Override
    public ResourceLocation getModelResource(ClanratEntity animatable) {
        return ResourceLocation.fromNamespaceAndPath(Skavenblight.MODID, "geo/clanrat.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(ClanratEntity animatable) {
        return ResourceLocation.fromNamespaceAndPath(Skavenblight.MODID, "textures/entity/clanrat.png");
    }

    @Override
    public ResourceLocation getAnimationResource(ClanratEntity animatable) {
        return ResourceLocation.fromNamespaceAndPath(Skavenblight.MODID, "animations/clanrat.animation.json");
    }

    // Dynamic Head Tracking: Makes the skaven stare at the player
    @Override
    public void setCustomAnimations(ClanratEntity animatable, long instanceId, AnimationState<ClanratEntity> animationState) {
        GeoBone head = getAnimationProcessor().getBone("head");

        if (head != null) {
            EntityModelData entityData = animationState.getData(DataTickets.ENTITY_MODEL_DATA);
            head.setRotX(entityData.headPitch() * Mth.DEG_TO_RAD);
            head.setRotY(entityData.netHeadYaw() * Mth.DEG_TO_RAD);
        }
    }
}