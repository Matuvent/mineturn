package com.matuvent.mineturn.client;

import com.matuvent.mineturn.battle.BattleDevice;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.*;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

final class BattleDeviceRenderer extends EntityRenderer<BattleDevice> {
    private final net.minecraft.client.renderer.block.BlockRenderDispatcher blocks;
    BattleDeviceRenderer(EntityRendererProvider.Context context){super(context);blocks=context.getBlockRenderDispatcher();}
    @Override public ResourceLocation getTextureLocation(BattleDevice e){return net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS;}
    @Override public void render(BattleDevice e,float yaw,float partial,PoseStack pose,MultiBufferSource buffers,int light){
        pose.pushPose();pose.scale(0.75f,0.75f,0.75f);pose.translate(-0.5,0,-0.5);
        blocks.renderSingleBlock(net.minecraft.world.level.block.Blocks.COPPER_BLOCK.defaultBlockState(),pose,buffers,light,OverlayTexture.NO_OVERLAY);
        pose.popPose();super.render(e,yaw,partial,pose,buffers,light);
    }
}
