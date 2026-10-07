package com.matuvent.mineturn.client;

import com.matuvent.mineturn.battle.BattleBullet;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.model.ShulkerBulletModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.*;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

final class BattleBulletRenderer extends EntityRenderer<BattleBullet> {
    private static final ResourceLocation TEXTURE=ResourceLocation.withDefaultNamespace("textures/entity/shulker/spark.png");
    private final ShulkerBulletModel<BattleBullet> model;
    BattleBulletRenderer(EntityRendererProvider.Context context){super(context);model=new ShulkerBulletModel<>(context.bakeLayer(ModelLayers.SHULKER_BULLET));}
    @Override public ResourceLocation getTextureLocation(BattleBullet entity){return TEXTURE;}
    @Override public void render(BattleBullet entity,float yaw,float partial,PoseStack pose,MultiBufferSource buffers,int light){
        pose.pushPose();pose.translate(0,0.15,0);pose.mulPose(Axis.YP.rotationDegrees((entity.tickCount+partial)*8));pose.scale(-0.5f,-0.5f,0.5f);
        model.setupAnim(entity,0,0,0,yaw,entity.getXRot());model.renderToBuffer(pose,buffers.getBuffer(model.renderType(TEXTURE)),15728880,OverlayTexture.NO_OVERLAY);
        pose.popPose();super.render(entity,yaw,partial,pose,buffers,light);
    }
}
